package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotlight deep-dive fixes (spec 2026-11-12, second fix spec).
 *
 * Spotlight shipped, then the audit found the seams the first fix spec did not
 * reach: the trailer handoff had no stop on either edge, the screensaver could
 * not see either Spotlight trailer, the panel's pills could not be reached and
 * collapsed the panel when they were, and the panel did not fit its own actions.
 * Each fix is one rule, so the contract is one pin per rule - the arithmetic
 * where the rule is arithmetic (the delay, the placement, the pill's
 * conditions), and the source shape where the question is "is this still wired
 * to that component".
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/test/java/.../SpotlightFixesContractTest.kt plus HomeLayoutRules
 * and BillboardTrailerMode, then JUnitCore with -Duser.dir at the repo root.
 */
class SpotlightFixesContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── (a) F1: the handoff stops on both edges ────────────────────────────

    @Test
    fun `expanding stops the hero trailer through the billboard's own stop`() {
        val billboard = source(MOBILE_HOME)
        assertTrue(
            "the mode flip alone is not a stop: the billboard must stop what is " +
                "already playing when another surface takes the player",
            billboard.contains("LaunchedEffect(trailerSuppressed) {") &&
                // Repointed by the 2026-11-12 collapse spec: the stop is now
                // conditional on something actually playing. Releasing the pooled
                // player - and its surface - for a page showing a still is
                // needless window-level churn under the focused rail card.
                billboard.contains(
                    "if (trailerSuppressed && (playback != null || playbackPageKey != null)) " +
                        "stopTrailer()"
                )
        )
        assertTrue(
            "and start nothing while the card owns the player",
            billboard.contains("if (trailerSuppressed) return@LaunchedEffect") ||
                billboard.contains("if (trailerSuppressed) return")
        )
        assertTrue(
            "the cycle re-arms when the card collapses, so it restarts instead of " +
                "staying frozen on the page it stopped",
            billboard.contains("trailerSuppressed\n    ) {")
        )
        val spotlight = source(SPOTLIGHT_HOME)
        assertTrue(
            "the Home hands the flag in from the expansion state",
            spotlight.contains("trailerSuppressed = cardExpanded")
        )
        assertTrue(
            "and the mode still flips, so no new trailer starts behind the card",
            spotlight.contains("SpotlightTrailerRule.heroModeWhileExpanded()")
        )
    }

    @Test
    fun `collapsing stops the panel's trailer and records no death for the cut`() {
        val trailer = source(SPOTLIGHT_PANEL_TRAILER)
        val dispose = between(trailer, "DisposableEffect(key) {", "LaunchedEffect(source != null)")
        assertTrue(
            "the panel's dispose is the release the empty onDispose used to leave out",
            dispose.contains("TrailerPlayerPool.releaseForReuse()")
        )
        assertFalse(
            "a deliberate cut must not poison a healthy source",
            dispose.contains("recordTrailerDeath")
        )
        assertTrue(
            "and the playing flag falls with the surface",
            dispose.contains("onPlayingChange(false)")
        )
    }

    // ── (b) F2: the screensaver hears both surfaces ───────────────────────

    @Test
    fun `a playing billboard or panel trailer holds the screensaver off`() {
        val billboard = source(MOBILE_HOME)
        assertTrue(
            "the billboard reports its own playing state from the pool",
            billboard.contains("onTrailerPlayingChange: (Boolean) -> Unit = {}") &&
                billboard.contains("val trailerPlayingNow = playback != null") &&
                billboard.contains("onTrailerPlayingChange(trailerPlayingNow)")
        )
        assertTrue(
            "and falls on dispose, so a trailer that ends or is cut cannot stick",
            billboard.contains("onDispose { onTrailerPlayingChange(false) }")
        )
        val home = source(HOME)
        assertTrue(
            "the Home ORs the three surfaces into the one gate",
            home.contains("val anyTrailerPlaying =") &&
                home.contains("heroTrailerPlaying || spotlightBillboardTrailerPlaying ||") &&
                home.contains("spotlightPanelTrailerPlaying")
        )
        assertTrue(
            "and the arm condition reads that OR",
            between(home, "val screensaverArmed =", "// The falling edge").contains(
                "!anyTrailerPlaying"
            )
        )
        assertTrue(
            "the panel reports through its own player",
            source(SPOTLIGHT_PREVIEW).contains("onPlayingChange = onTrailerPlayingChange")
        )
    }

    // ── (c) F3: DOWN enters the pills, and focus inside keeps the panel ────

    @Test
    fun `DOWN from the focused card enters the panel before the next rail`() {
        val card = source(POSTER_CARD)
        assertTrue(
            "the card's own DOWN is the handoff",
            card.contains("spotlightFocus.onDownFromCard()") &&
                card.contains("Key.DirectionDown")
        )
        val preview = source(SPOTLIGHT_PREVIEW)
        val tracker = between(preview, "internal class SpotlightCardFocus", "staticCompositionLocalOf")
        assertTrue(
            "only while the panel is up, and it consumes the press so the rail " +
                "below never also moves",
            tracker.contains("if (!panelExpanded || snapshot == null) return false") &&
                tracker.contains("fun onDownFromCard(): Boolean")
        )
        assertTrue(
            "the panel registers the pill row as the target",
            preview.contains("tracker?.focusPills = {") &&
                preview.contains("pillsFocus.requestFocus()")
        )
        assertTrue(
            "and focus inside the row is not a collapse",
            tracker.contains("if (handingOff || pillsFocused) return") &&
                tracker.contains("fun onPillsFocusChanged(focused: Boolean)")
        )
        assertTrue(
            "UP returns to the card that owns the panel",
            tracker.contains("fun onUpFromPills(): Boolean") &&
                preview.contains("tracker?.onUpFromPills() == true")
        )
    }

    @Test
    fun `a last-rail card lands on PLAY and the panel stays up`() {
        // The same mechanism is what makes this case work: there is no
        // geometry involved in the DOWN any more, so the last rail (no card
        // below) and a middle rail behave identically.
        val tracker = between(
            source(SPOTLIGHT_PREVIEW),
            "internal class SpotlightCardFocus",
            "staticCompositionLocalOf"
        )
        assertTrue(
            "the handoff ignores the rail's geometry entirely",
            !tracker.contains("bounds.")
        )
        assertTrue(
            "and the panel is expanded before DOWN can be consumed",
            tracker.contains("var panelExpanded by mutableStateOf(false)")
        )
    }

    // ── (d) F4: the panel fits the viewport ───────────────────────────────

    @Test
    fun `the panel flips up and clamps to the viewport, pills included`() {
        // A first-rail portrait card on a 540px-tall viewport: growing down from
        // its top would end at 666, so the panel flips to grow UP from the card's
        // bottom and lands fully on screen.
        val firstRail = spotlightPanelBox(
            cardLeftPx = 0f,
            cardTopPx = 324f,
            cardRightPx = 180f,
            cardBottomPx = 504f,
            growth = 1.9f,
            viewportWidthPx = 960,
            viewportHeightPx = 540,
            marginPx = 16f
        )
        assertEquals(342, firstRail.height)
        assertTrue(
            "the whole panel, pills row included, is inside the bottom edge",
            firstRail.top + firstRail.height <= 540 - 16
        )
        assertTrue("and not above the top edge", firstRail.top >= 16)

        // A right-column card: the panel is clamped to the right edge instead of
        // hanging off it (which used to cut the TRAILER pill entirely).
        val rightColumn = spotlightPanelBox(
            cardLeftPx = 780f,
            cardTopPx = 100f,
            cardRightPx = 920f,
            cardBottomPx = 280f,
            growth = 1.9f,
            viewportWidthPx = 960,
            viewportHeightPx = 540,
            marginPx = 16f
        )
        // The card is 140 wide, so the panel asks 266 and the clamp is what keeps
        // it on screen rather than the growth.
        assertEquals(266, rightColumn.width)
        assertTrue(rightColumn.left + rightColumn.width <= 960 - 16)

        // A panel that cannot fit is capped, never overhung.
        val capped = spotlightPanelBox(
            cardLeftPx = 0f,
            cardTopPx = 0f,
            cardRightPx = 900f,
            cardBottomPx = 500f,
            growth = 1.9f,
            viewportWidthPx = 960,
            viewportHeightPx = 540,
            marginPx = 16f
        )
        assertTrue(capped.width <= 960 - 32)
        assertTrue(capped.height <= 540 - 32)

        val preview = source(SPOTLIGHT_PREVIEW)
        assertTrue(
            "the panel is placed by that arithmetic, on the measured viewport",
            preview.contains("spotlightPanelBox(") &&
                preview.contains("viewportWidthPx = viewport.width")
        )
        assertTrue(
            "and the pills wrap, so they stay inside the panel's background",
            preview.contains("FlowRow(") &&
                !preview.contains("Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n            SpotlightPill")
        )
    }

    // ── (e) F5: the panel's trailer gets the ids, the ladder and callbacks ──

    @Test
    fun `the panel's trailer resolves with the title's ids and the hero's ladder`() {
        val trailer = source(SPOTLIGHT_PANEL_TRAILER)
        assertTrue(
            "the ids are passed, so the ladder's IMDb leg can fire",
            trailer.contains("imdbId = imdbId") && trailer.contains("tmdbId = tmdbId")
        )
        assertFalse(
            "null ids are what the audit found",
            trailer.contains("imdbId = null")
        )
        assertTrue(
            "a death is planned through the hero's own ladder",
            trailer.contains("heroTrailerRetryPlan(") &&
                trailer.contains("TrailerPlayerLauncher.invalidate(")
        )
        assertTrue(
            "and recorded, like every other surface",
            trailer.contains("PoTokenTrace.recordTrailerDeath(")
        )
        assertTrue(
            "a natural end unmounts the player so the still returns",
            trailer.contains("onEnded = {") && trailer.contains("source = null")
        )
        assertTrue(
            "a failure retries through the ladder",
            trailer.contains("onFailed = { positionMs -> requestRetry(positionMs, 0L) }")
        )
        assertTrue(
            "and the still is held until the first frame, so the start never " +
                "flashes black",
            trailer.contains("onFirstFrame = { firstFrame = true }") &&
                trailer.contains("source == null || !firstFrame")
        )
        val host = source(SPOTLIGHT_HOME)
        assertTrue(
            "the ids come from the focused title, not from nothing",
            host.contains("imdbId = ") && host.contains("tmdbId = ")
        )
    }

    // ── (f) F6: no panel over an open menu ────────────────────────────────

    @Test
    fun `the panel never opens over an open menu`() {
        val host = source(SPOTLIGHT_HOME)
        // Repointed twice: F6b moved the key off the snapshot OBJECT, and the
        // 2026-11-12 dwell spec moved it off `hasSnapshot` too - that flag is a
        // blur away from false, and a one-frame blur used to cancel the wait.
        // The menu is still a guard and still a key.
        assertTrue(
            "the dwell is cancelled by an open menu, and keyed on the held CARD",
            host.contains("LaunchedEffect(heldCard, expandDelayMs, menuOpen)") &&
                host.contains("menuOpen -> \"menuOpen\"") &&
                host.contains("\"!expands\"")
        )
        val home = source(HOME)
        assertTrue(
            "and the Home names both menus as the open state",
            home.contains("menuOpen = posterMenu != null || browseShortcutMenu != null")
        )
    }

    // ── (g) F7: the card's trailer delay is the viewer's ───────────────────

    @Test
    fun `the card's trailer waits the viewer's hero-trailer delay`() {
        assertEquals(6_000L, SpotlightTrailerRule.expandedCardDelayMs(6_000L))
        assertEquals(0L, SpotlightTrailerRule.expandedCardDelayMs(0L))
        assertEquals(0L, SpotlightTrailerRule.expandedCardDelayMs(-1L))
        val rules = source(LAYOUT_RULES)
        assertFalse(
            "the hardcoded six seconds is what the fix removed",
            rules.contains("EXPANDED_CARD_DELAY_MS")
        )
        val host = source(SPOTLIGHT_HOME)
        assertTrue(
            "the Home passes the pref through the rule",
            host.contains("SpotlightTrailerRule.expandedCardDelayMs(trailerDelayMs)")
        )
        assertTrue(
            "and the value is still counted from the EXPANSION (this composition)",
            source(SPOTLIGHT_PANEL_TRAILER).contains("delay(delayMs.coerceAtLeast(0L))")
        )
    }

    // ── (h) F8: the TRAILER pill needs a trailer, and a press retries ──────

    @Test
    fun `no TRAILER pill without a key, and a press retries a failed resolve`() {
        val mode = com.kennyb1201.kbstream.data.settings.BillboardTrailerMode.MANUAL
        assertTrue(
            SpotlightTrailerRule.showsTrailerPill(mode, true, "abc123")
        )
        assertFalse(
            "MANUAL on a title with no trailer used to offer a dead press",
            SpotlightTrailerRule.showsTrailerPill(mode, true, null)
        )
        assertFalse(SpotlightTrailerRule.showsTrailerPill(mode, true, "  "))
        assertFalse(
            "OFF offers none at all",
            SpotlightTrailerRule.showsTrailerPill(
                com.kennyb1201.kbstream.data.settings.BillboardTrailerMode.OFF,
                true,
                "abc123"
            )
        )
        val trailer = source(SPOTLIGHT_PANEL_TRAILER)
        assertTrue(
            "a fresh press is a generation, so it re-fires even after a failure",
            trailer.contains("pressRequest: Int = 0") &&
                trailer.contains("LaunchedEffect(pressRequest)") &&
                trailer.contains("if (pressRequest > 0) request += 1")
        )
        assertTrue(
            "the pill is shown by the key-aware rule",
            source(SPOTLIGHT_PREVIEW).contains("SpotlightTrailerRule.showsTrailerPill(")
        )
    }

    // ── (i) F9: a scroll moves the panel, it does not kill the trailer ─────

    @Test
    fun `a scroll under the expanded card keeps the trailer and its delay`() {
        val trailer = source(SPOTLIGHT_PANEL_TRAILER)
        assertFalse(
            "the trailer's state used to be keyed on the card's bounds, so a " +
                "scroll reset it and re-armed the delay",
            trailer.contains("remember(snapshot.bounds")
        )
        assertTrue(
            "it is keyed on the title instead",
            trailer.contains("remember(key)") || trailer.contains("remember(trailerKey)")
        )
        val panel = source(SPOTLIGHT_PREVIEW)
        assertTrue(
            "while the panel itself is what moves with the card",
            panel.contains("spotlightPanelBox(") &&
                panel.contains(".offset { IntOffset(box.left, box.top) }")
        )
    }

    // ── (j) F10: an unaired card offers UNAVAILABLE, not PLAY ─────────────

    @Test
    fun `an unaired card shows UNAVAILABLE instead of PLAY`() {
        val preview = source(SPOTLIGHT_PREVIEW)
        assertTrue(
            "the snapshot carries the card's date",
            preview.contains("val airDate: String? = null") &&
                preview.contains("isEpisodeUnavailable(airDate)")
        )
        assertTrue(
            "and the pill row swaps PLAY for UNAVAILABLE",
            preview.contains("if (unaired) {") &&
                preview.contains("label = \"UNAVAILABLE\"")
        )
        val card = source(POSTER_CARD)
        assertTrue(
            "the card reports the rail's own date",
            card.contains("airDate: String? = null") && card.contains("airDate = airDate")
        )
        val home = source(HOME)
        assertTrue(
            "the Upcoming rail's card is unaired by construction",
            home.contains("java.time.Instant.ofEpochMilli(upcoming.airDateEpochMs)")
        )
        assertTrue(
            "and a catalog card hands over its releaseInfo",
            home.contains("airDate = meta.releaseInfo")
        )
    }

    // ── (k) F11: the dead state is gone ───────────────────────────────────

    @Test
    fun `focusedAtMs is gone`() {
        assertFalse(
            "nothing read it - it was written on focus and never looked at",
            source(SPOTLIGHT_PREVIEW).contains("focusedAtMs")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SPOTLIGHT_HOME = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val SPOTLIGHT_PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val SPOTLIGHT_PANEL_TRAILER =
            "com/kennyb1201/kbstream/ui/home/SpotlightPanelTrailer.kt"
        const val LAYOUT_RULES = "com/kennyb1201/kbstream/ui/home/HomeLayoutRules.kt"
        const val MOBILE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"
    }
}
