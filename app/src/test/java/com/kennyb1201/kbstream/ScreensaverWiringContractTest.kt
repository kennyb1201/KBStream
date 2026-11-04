package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV screensaver's wiring, which the app itself cannot assert.
 *
 * This module carries no Compose harness, so everything that would fail silently
 * on a TV is pinned by reading the sources: a Settings row that changes nothing,
 * a pref that leaks onto the account, an overlay that lets the dismissing press
 * through to a rail, a trailer that keeps playing underneath, or a phone screen
 * that grew a TV-only control.
 */
class ScreensaverWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val prefs =
        source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")
    private val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val settings = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val search = source("com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt")
    private val timeoutRow =
        source("com/kennyb1201/kbstream/ui/settings/ScreensaverTimeoutRow.kt")
    private val home = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val overlay = source("com/kennyb1201/kbstream/ui/home/HomeScreensaver.kt")
    private val rules = source("com/kennyb1201/kbstream/ui/home/ScreensaverRules.kt")
    private val homeVm = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

    @Test
    fun `the switch is off by default and the wait is five minutes`() {
        assertTrue(
            "the toggle must default to off: a fullscreen takeover is opt-in",
            prefs.contains("KEY_SCREENSAVER_ENABLED = \"screensaver_enabled\"") &&
                prefs.contains("getBoolean(KEY_SCREENSAVER_ENABLED, false)")
        )
        assertTrue(
            "and the wait defaults to 5 minutes, out of the offered set",
            prefs.contains("DEFAULT_SCREENSAVER_TIMEOUT_MINUTES = 5") &&
                prefs.contains("SCREENSAVER_TIMEOUT_OPTIONS_MINUTES = listOf(2, 5, 10, 15)") &&
                prefs.contains("DEFAULT_SCREENSAVER_TIMEOUT_MINUTES")
        )
        assertTrue(
            "a stored wait that is not one of the chips snaps to the nearest",
            prefs.contains("fun screensaverTimeoutFromStored(raw: Int): Int")
        )
    }

    @Test
    fun `both prefs are device-local, so neither reaches the account`() {
        val synced = payload.substringAfter("internal val SYNCED_PREF_KEYS = setOf(")
            .substringBefore("\n    )")
        assertFalse(
            "the switch and the wait are this television's, not the account's: " +
                "neither may be a synced key",
            synced.contains("screensaver")
        )
        // ...and both are declared device-local beside the allow-list they are
        // kept out of, which is the tree's own record of that decision.
        val excluded = payload.substringAfter("internal val EXCLUDED_PREF_KEYS = setOf(")
            .substringBefore("\n    )")
        listOf("screensaver_enabled", "screensaver_timeout").forEach { key ->
            assertTrue("$key must be declared device-local", excluded.contains("\"$key\""))
        }
        val setters = prefs.substringAfter("fun setScreensaverEnabled(")
            .substringBefore("// ── 24-hour clock")
        assertFalse(
            "publishing the display blob would sync a device-local wait: $setters",
            setters.contains("syncDisplayPrefsBlob") ||
                setters.contains("notifyDisplayPrefChanged")
        )
    }

    @Test
    fun `the TV settings page draws the switch and the wait, and search can find them`() {
        assertTrue(
            "no toggle row labelled \"TV Screensaver\" on the TV page",
            squash(settings).contains("ToggleRow( label = \"TV Screensaver\",")
        )
        assertTrue(
            "the toggle writes the pref it reads",
            squash(settings).contains("AppPreferences.setScreensaverEnabled(context, enabled)")
        )
        assertTrue(
            "the wait row is the screen's own chips, beside the toggle",
            squash(settings).contains(
                squash(
                    """
                    ScreensaverTimeoutRow(
                        selectedMinutes = screensaverTimeoutMinutes,
                        enabled = screensaverEnabled,
                        chip = { text, isSelected -> PillChip(text, isSelected) },
                        onPick = { minutes ->
                            screensaverTimeoutMinutes = minutes
                            AppPreferences.setScreensaverTimeoutMinutes(context, minutes)
                        }
                    )
                    """
                )
            )
        )
        assertTrue(
            "a wait nothing waits for is dimmed, like the trailer delay row",
            timeoutRow.contains("Modifier.alpha(dim)") &&
                timeoutRow.contains("AppPreferences.SCREENSAVER_TIMEOUT_OPTIONS_MINUTES")
        )
        // Search: the row is indexed (the screen's contract test enforces the
        // other direction), and the phone's results must not name it.
        assertTrue(
            "the screensaver toggle is drawn but no query can name it",
            search.contains("\"interface.tv-screensaver\"") &&
                search.contains("\"TV Screensaver\"")
        )
        assertTrue(
            "the phone draws no such row, so its search must not offer it",
            search.substringAfter("internal val TV_ONLY_SEARCH_KEYS")
                .substringBefore(")")
                .contains("interface.tv-screensaver")
        )
    }

    @Test
    fun `Home arms the timer from real keys, gates it on its own overlays, and re-reads the pref`() {
        assertTrue(
            "a key press has to push the deadline out",
            squash(home).contains(
                "screensaverLastKeyAt = SystemClock.uptimeMillis()"
            )
        )
        assertTrue(
            "idle is measured from the LAST press, not from a fixed deadline",
            squash(home).contains("val idleFor = SystemClock.uptimeMillis() - screensaverLastKeyAt")
        )
        assertTrue(
            "a modal open means the viewer is mid-task: every Home overlay has to gate arming",
            squash(home).contains(
                squash(
                    """
                    val screensaverArmed =
                        screensaverEnabled &&
                            homeResumed &&
                            !screensaverVisible &&
                            screensaverPool.isNotEmpty() &&
                            !showTopBar &&
                            continueWatchingMenu == null &&
                            upcomingMenu == null &&
                            posterMenu == null &&
                            browseShortcutMenu == null
                    """
                )
            )
        )
        assertTrue(
            "returning to Home (from Settings included) has to pick up a changed pref",
            squash(home).contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)") &&
                squash(home).contains("screensaverEnabled = AppPreferences.getScreensaverEnabled(context)")
        )
        val paused = squash(home)
            .substringAfter("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)")
            .substringBefore("// Armed only while")
        assertTrue(
            "a paused Home (sleep, HDMI switch, background) is not an idle Home, so " +
                "the overlay drops: $paused",
            paused.contains("homeResumed = false") &&
                paused.contains("screensaverVisible = false")
        )
    }

    @Test
    fun `the pool is Home's own loaded titles, snapshotted per arming`() {
        assertTrue(
            "the pool is snapshotted when the rails change, not re-read per slide",
            squash(home).contains(
                "val screensaverPool = remember(rails) { screensaverSlides(rails.flatMap { rail -> rail.items }) }"
            )
        )
        assertTrue(
            "the art gate is backdrop AND clearlogo, with no poster fallback",
            rules.contains("fun MetaPreview.hasScreensaverArt(): Boolean") &&
                rules.contains("!background.isNullOrBlank() && !logo.isNullOrBlank()")
        )
    }

    @Test
    fun `the overlay consumes its dismissing press and stays above Home`() {
        assertTrue(
            "the overlay is drawn from Home, last enough to cover the rails",
            squash(home).contains(
                "if (screensaverVisible && screensaverSlidesShown.isNotEmpty()) { HomeScreensaver("
            )
        )
        assertTrue(
            "what it shows is the pool it armed with, not the live rails",
            squash(home).contains("screensaverSlidesShown = latestScreensaverPool")
        )
        assertTrue(
            "Back exits the overlay instead of leaving Home",
            squash(home).contains("BackHandler(enabled = screensaverVisible)")
        )
        // Comments sit between the exit call and the return, so this is read
        // structurally: the handler calls onExit() on the key-down and then
        // ends on a bare `true` - consuming the press rather than passing it on
        // to the focused rail behind.
        val handler = Regex("\\.onPreviewKeyEvent \\{ event ->[\\s\\S]*?onExit\\(\\)[\\s\\S]*?\\n\\s*true\\n")
            .find(overlay)
        assertTrue(
            "one press = exit and nothing else: the key handler must CONSUME",
            handler != null
        )
        assertTrue(
            "...and only a key DOWN exits, so the matching up event cannot exit twice",
            handler!!.value.contains("event.type == KeyEventType.KeyDown")
        )
        assertTrue(
            "the D-pad has to land on the overlay, or its keys never reach that handler",
            overlay.contains("focusRequester.requestFocus()")
        )
    }

    @Test
    fun `focus goes back to the card it armed on, with a fallback when that card is gone`() {
        assertTrue(
            "the target is captured when the overlay arms",
            squash(home).contains(
                "screensaverRestoreTarget = focusedCardRequester ?: lastPosterFocusRequester"
            )
        )
        val exit = squash(home).substringAfter("fun exitScreensaver()")
            .substringBefore("// The wait itself")
        assertTrue(
            "exit must hand focus back through Home's own registry, never leave it nowhere: $exit",
            exit.contains("restoreFocus(") &&
                exit.contains("screensaverRestoreTarget") &&
                exit.contains("railFocusRequesters[viewModel.lastFocusedRailItemKey]") &&
                exit.contains("focusedCardRequester") &&
                exit.contains("lastPosterFocusRequester")
        )
    }

    @Test
    fun `the hero trailer stops underneath, through its own switch`() {
        assertTrue(
            "the overlay tells the hero it is covered",
            squash(home).contains("trailerSuppressed = screensaverVisible")
        )
        assertTrue(
            "and suppression is the hero's own autoPlayTrailer switch, not a second stop path",
            squash(home).contains("!trailerSuppressed &&")
        )
    }

    @Test
    fun `the sweep is the billboard's own, run on the image, and still with reduced motion`() {
        assertTrue(
            "the overlay must reuse billboardSweep rather than copy the move",
            Regex("billboardSweep\\(\\w+, SCREENSAVER_DWELL_MS\\)").containsMatchIn(overlay) &&
                overlay.contains("import androidx.compose.animation.core.Animatable")
        )
        assertTrue(
            "the parity is the CYCLE's, so a one-title pool still alternates",
            squash(overlay).contains("var cycle by remember { mutableIntStateOf(0) }") &&
                overlay.contains("slides[shownCycle % slides.size]")
        )
        assertTrue(
            "the move is a graphics layer on the image, so nothing recomposes per frame",
            squash(overlay).contains("graphicsLayer {")
        )
        assertTrue(
            "animations off is a still slide, not a slower sweep",
            overlay.contains("rememberReducedMotion()") &&
                squash(overlay).contains("if (reducedMotion) return@graphicsLayer")
        )
        assertFalse(
            "nothing on an ambient overlay may be clickable",
            overlay.contains("clickable(")
        )
    }

    @Test
    fun `the panel stays awake while the overlay is up, and only then`() {
        assertTrue(
            "the overlay must hold the screen on",
            overlay.contains("view.keepScreenOn = true")
        )
        assertTrue(
            "and hand the flag back exactly as it found it",
            overlay.contains("onDispose { view.keepScreenOn = previous }")
        )
    }

    @Test
    fun `the genre comes from an already-resolved hero, never from a new fetch`() {
        assertTrue(
            "the overlay asks the ViewModel for a resolved genre only",
            squash(home).contains("genreOf = { meta -> viewModel.resolvedHeroGenre(meta.id, meta.type) }")
        )
        assertTrue(
            "the memo records the hero's own genres when they resolve",
            homeVm.contains("private val resolvedHeroGenres") &&
                homeVm.contains("screensaverGenre(finalMeta.genres)")
        )
        assertTrue(
            "and reading it is a plain lookup, with no fetch",
            squash(homeVm).contains(
                "internal fun resolvedHeroGenre( id: String, type: String ): String? ="
            )
        )
    }

    @Test
    fun `no phone screen grew a TV control`() {
        listOf(
            "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt",
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        ).forEach { path ->
            assertFalse(
                "$path must not learn about the TV screensaver",
                source(path).contains("creensaver")
            )
        }
    }
}
