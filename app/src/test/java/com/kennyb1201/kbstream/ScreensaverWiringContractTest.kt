package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
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
    fun `the switch is on by default and the wait is five minutes`() {
        assertTrue(
            "the overlay must default to ON: a television left on a static Home is " +
                "the case it exists for, and a device that never opened Settings has " +
                "no stored answer to fall back on",
            prefs.contains("KEY_SCREENSAVER_ENABLED = \"screensaver_enabled\"") &&
                prefs.contains("getBoolean(KEY_SCREENSAVER_ENABLED, true)")
        )
        assertTrue(
            "but an explicit OFF must survive, which means the switch writes the key " +
                "rather than relying on the default",
            prefs.contains("fun setScreensaverEnabled(context: Context, enabled: Boolean)") &&
                prefs.contains("putBoolean(KEY_SCREENSAVER_ENABLED, enabled)")
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
                            browseShortcutMenu == null &&
                            !heroTrailerPlaying
                    """
                )
            )
        )
        assertTrue(
            "and a PLAYING hero trailer gates it too: watching one produces no key " +
                "presses, so the overlay used to arm over the trailer and stop it",
            squash(home).contains("!heroTrailerPlaying")
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
            "the pool's own gate is the clearlogo: a rail item's backdrop is the " +
                "catalog's single default image (the Top 10 promo art), so the " +
                "picture is the RESOLVED one instead",
            rules.contains("fun MetaPreview.hasScreensaverArt(): Boolean") &&
                squash(rules).contains(
                    "fun MetaPreview.hasScreensaverArt(): Boolean = !logo.isNullOrBlank()"
                )
        )
        assertTrue(
            "and what the overlay shows is built at arm time from the clean art",
            squash(home).contains("val arming = screensaverCleanSlides(latestScreensaverPool)") &&
                squash(home).contains("if (arming.isEmpty()) return@LaunchedEffect") &&
                squash(home).contains("screensaverSlidesShown = arming.shuffled()")
        )
    }

    @Test
    fun `the show's order is shuffled once per show, and nothing else is`() {
        assertTrue(
            "the resolved slides are dealt a fresh order at the one place a show " +
                "is armed, or every idle evening is the same tour in rail order",
            squash(home).contains("screensaverSlidesShown = arming.shuffled()")
        )
        assertTrue(
            "the shuffle is of the SHOWN snapshot, after the clean-art filter, so " +
                "the resolve pass and its ordered prefix are untouched",
            squash(home).contains(
                "val arming = screensaverCleanSlides(latestScreensaverPool) { meta -> " +
                    "viewModel.screensaverCleanBackdrop(meta.id, meta.type) }"
            )
        )
        assertFalse(
            "and the rules stay order-preserving: a shuffled clean-slide list or " +
                "resolve prefix would make one show differ from itself between reads",
            rules.contains("shuffled()")
        )
        assertTrue(
            "the pool itself is still the ordered rail snapshot the pass walks",
            squash(home).contains(
                "val screensaverPool = remember(rails) { screensaverSlides(" +
                    "rails.flatMap { rail -> rail.items }) }"
            )
        )
    }

    @Test
    fun `the show draws the hero's ranked artwork, never the rail's raw backdrop`() {
        assertTrue(
            "the resolve has to go through the hero-artwork path, which ranks " +
                "textless art first - the raw rail backdrop is the default promo image",
            squash(homeVm).contains("tmdbHeroArtworkRepository.resolve(") &&
                homeVm.contains("screensaverCleanBackdrops[key] = cleanBackdrop")
        )
        assertTrue(
            "and arming reads only what RESOLVED",
            squash(home).contains(
                "viewModel.screensaverCleanBackdrop(meta.id, meta.type)"
            )
        )
        // Bounded by the next declaration, so a hit elsewhere in the file cannot
        // stand in for the clean-slide rule itself.
        val clean = squash(rules).substringAfter("internal fun screensaverCleanSlides(")
            .substringBefore("internal fun screensaverGenre(")
        assertTrue("the clean backdrop replaces the item's background: $clean", clean.contains("item.copy(background = backdrop)"))
        assertTrue(
            "and a title without one is DROPPED, never shown with its raw art: $clean",
            clean.contains("?: return@mapNotNull null")
        )
        assertFalse(
            "no fallback to the rail's own background may exist on this path: $clean",
            clean.contains("item.background")
        )
        assertTrue(
            "nothing resolved is no show at all, not an empty overlay",
            squash(home).contains("if (arming.isEmpty()) return@LaunchedEffect")
        )
    }

    @Test
    fun `the pool is resolved during the idle wait, never while the show arms`() {
        val pass = squash(home)
            .substringAfter("LaunchedEffect(screensaverArmed, screensaverPool)")
            .substringBefore("// The wait itself")
        assertTrue(
            "the pass lives on the arm gate, so exiting the overlay, opening a menu " +
                "or pausing Home cancels it: $pass",
            pass.contains("if (!screensaverArmed) return@LaunchedEffect") &&
                pass.contains("viewModel.warmScreensaverPool(screensaverPool)")
        )
        assertTrue(
            "the pass is a suspend function over the pool, capped by the rules' bound",
            homeVm.contains("internal suspend fun warmScreensaverPool(items: List<MetaPreview>)") &&
                squash(homeVm).contains("screensaverPreresolveItems(items).forEach")
        )
        assertTrue(
            "at most thirty titles, in slide order",
            rules.contains("SCREENSAVER_PRERESOLVE_LIMIT = 30") &&
                squash(rules).contains(
                    "internal fun screensaverPreresolveItems(items: List<MetaPreview>): List<MetaPreview> = items.take(SCREENSAVER_PRERESOLVE_LIMIT)"
                )
        )
        val warm = squash(homeVm)
            .substringAfter("internal suspend fun warmScreensaverPool(")
            .substringBefore("private suspend fun screensaverDetailOf")
        assertTrue(
            "a failure is per title and silent: no error state, no emptied show",
            warm.contains("safeSuspend {") &&
                warm.contains("skipped++")
        )
        assertTrue(
            "and the counts are a log line, not the UI",
            warm.contains("\"HOME_SAVER\"") && warm.contains("Log.d(")
        )
        assertFalse(
            "it must never touch the focused hero's own job: $warm",
            warm.contains("heroResolveJob")
        )
    }

    @Test
    fun `the genre map is state-observed, and the pass fills it for every slide`() {
        assertTrue(
            "the memo is snapshot state, so a genre landing mid-slide redraws that " +
                "slide's corner instead of waiting a cycle",
            squash(homeVm).contains(
                "private val resolvedHeroGenres = androidx.compose.runtime.mutableStateMapOf<String, String>()"
            )
        )
        assertTrue(
            "the idle-wait pass writes each resolved title's genre line, by the " +
                "overlay's own rule",
            squash(homeVm).contains("screensaverGenre(detail?.genres?.map { it.name })") &&
                squash(homeVm).contains("resolvedHeroGenres[key] = genre")
        )
        assertTrue(
            "which is the first two joined, so the label reads \"ACTION · DRAMA\"",
            rules.contains("internal const val SCREENSAVER_GENRE_SEPARATOR = \" · \"") &&
                squash(rules).contains("?.take(2)") &&
                squash(rules).contains(".joinToString(SCREENSAVER_GENRE_SEPARATOR)")
        )
        assertEquals(
            "the rule has exactly two callers, both of them screensaver memos - the " +
                "hero draws its own genre from its detail state and keeps showing one",
            2,
            homeVm.split("screensaverGenre(").size - 1
        )
        assertTrue(
            "and the overlay still reads it without a fetch",
            squash(home).contains("genreOf = { meta -> viewModel.resolvedHeroGenre(meta.id, meta.type) }")
        )
    }

    @Test
    fun `the corner label is centered on the wordmark and reads as a label`() {
        assertTrue(
            "a fitted wordmark centers ITSELF in its box, so the label has to be " +
                "centered too or it pins to the box's left edge",
            squash(overlay).contains("horizontalAlignment = Alignment.CenterHorizontally")
        )
        val label = squash(overlay)
            .substringAfter("genreOf(slide)?.let { genre ->")
            .substringBefore("private val ScreensaverCornerPadding")
        assertTrue("the genre is drawn as tracked capitals: $label", label.contains("genre.uppercase()"))
        assertTrue("tracked", label.contains("letterSpacing = ScreensaverGenreTracking"))
        assertTrue(
            "at a small, dim label size rather than the title's near-white body text",
            label.contains("color = KBTextLo") &&
                label.contains("style = MaterialTheme.typography.bodySmall")
        )
        assertTrue(
            "one line, ellipsized: a long genre may not run past the art it labels",
            label.contains("maxLines = 1") &&
                label.contains("overflow = TextOverflow.Ellipsis") &&
                label.contains("textAlign = TextAlign.Center") &&
                label.contains(".width(ScreensaverLogoWidth)")
        )
        assertTrue(
            "two letterspaced points, in the range the app's other labels use, " +
                "tightened for this line",
            overlay.contains("private val ScreensaverGenreTracking = 2.sp")
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
            "what it shows is the snapshot it armed with, not the live rails",
            squash(home).contains("screensaverSlidesShown = arming.shuffled()")
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
