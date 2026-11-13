package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV Home's second layout, modern's twin (spec: Home — Spotlight).
 *
 * Spotlight is a presentation switch, so almost all of it is source shape: the
 * same rails, drawn twice, with the hero turned into a billboard and the focused
 * card turned into the preview. These pin that shape - which screen offers the
 * setting, which composable draws which hero, what the expansion is anchored to,
 * and which surface owns the one pooled trailer player - rather than the
 * neighbouring text, so a rewrite of the rails cannot quietly drop the layout.
 *
 * The pure arithmetic (the delay steps, the trailer rule) is checked by calling
 * it; everything else reads the sources the way the other Home contract tests
 * do, because the question is "is this still wired to the right component",
 * which a composition would answer with a device attached.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/test/java/.../SpotlightHomeContractTest.kt, then JUnitCore with
 * -Duser.dir at the repository root so the source reader finds src/main/java.
 */
class SpotlightHomeContractTest {

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

    /** The text between [start] and the next [end], or "" when either is absent. */
    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── (a) the setting: TV offers both layouts, the phone offers none ─────

    @Test
    fun `the TV offers Modern and Spotlight with a search entry`() {
        val settings = source(SETTINGS)
        assertTrue(
            "the TV Interface pane must draw the Home layout row",
            settings.contains("text = \"Home layout\"")
        )
        assertTrue(
            "and offer every layout as a chip, so Modern and Spotlight both come " +
                "from the enum instead of a hand-written pair",
            settings.contains("HomeLayout.entries.forEach { layout ->")
        )
        assertTrue(
            "picking one stores it, device-locally",
            settings.contains("AppPreferences.setHomeLayout(context, layout.stored)")
        )
        assertTrue(
            "the row is searchable from birth",
            settings.contains("SettingsSearchIndex.entryForLabel(\"Home layout\")")
        )

        val search = source(SEARCH)
        assertTrue(
            "the index carries the entry",
            search.contains("\"interface.home-layout\"") &&
                search.contains("\"Home layout\"")
        )
        assertTrue(
            "and marks it TV only: the phone Home has no rail focus to expand a " +
                "card from, so the phone must not offer the setting",
            between(search, "internal val TV_ONLY_SEARCH_KEYS", "internal val MOBILE_ONLY_SEARCH_KEYS")
                .contains("\"interface.home-layout\"")
        )

        val phoneSettings = source(PHONE_SETTINGS)
        assertFalse(
            "the phone's panes must not draw a Home layout row",
            phoneSettings.contains("Home layout")
        )
        assertFalse(
            "nor write the pref",
            phoneSettings.contains("setHomeLayout")
        )
    }

    @Test
    fun `modern stays the default and is what an unset pref reads back`() {
        assertEquals(
            "a fresh install keeps the Home it has always had",
            HomeLayout.MODERN,
            HomeLayout.fromStored(null)
        )
        assertEquals(
            "and a stored value from an older build falls back rather than crashing",
            HomeLayout.MODERN,
            HomeLayout.fromStored("something-else")
        )
        assertEquals(HomeLayout.SPOTLIGHT, HomeLayout.fromStored("spotlight"))

        val prefs = source(PREFS)
        assertTrue(
            // Repointed by the 2026-11-12 render-path spec: the read now goes
            // through the per-key device inheritance (deviceValue) instead of a
            // raw getString, because a one-shot seed could decide "modern" from
            // an empty legacy store and permanently hide the hero. The DEFAULT
            // is what this pins, and it is unchanged.
            "the pref's default is modern at the storage layer too",
            prefs.contains("deviceValue(context, KEY_HOME_LAYOUT) as? String") &&
                prefs.contains("takeIf { it.isNotBlank() } ?: \"modern\"")
        )
        val sync = source(SYNC)
        // The 2026-11-12 spec narrowed the exclusion: the layout selection, its
        // dwell and the billboard settings now sync TV to TV (and only from a
        // TV, only to a TV), while the screen GEOMETRY family stays local.
        assertTrue(
            "the layout and its dwell are TV-scoped sync keys now, not local ones",
            sync.contains("internal val TV_SCOPED_SYNC_KEYS = setOf(") &&
                sync.contains("\"home_layout\",") &&
                sync.contains("\"home_card_expand_ms\"")
        )
        assertTrue(
            "and the three geometry keys are still the local family",
            sync.contains("`poster_size`, `home_landscape_cards` and `landscape_posters`") &&
                sync.contains("never appears here")
        )
    }

    // ── (b) the hero is the phone's billboard, and it does not follow focus ─

    @Test
    fun `Spotlight draws the billboard hero instead of the focus-following one`() {
        val home = source(HOME)
        val spotlightBranch = between(home, "if (spotlightActive) {", "} else HomeHeroHost(")
        assertTrue(
            "the Spotlight branch must exist",
            spotlightBranch.isNotEmpty()
        )
        assertTrue(
            "and draw the billboard section - as the rails list's first item since " +
                "the scroll-away spec (see SpotlightScrollHeroContractTest), which is " +
                "still Spotlight's hero and nothing else's",
            home.contains("if (spotlightActive) {") &&
                home.contains("SpotlightHeroSection(")
        )
        assertTrue(
            "while Modern keeps the hero host untouched, in the same if's else",
            home.contains("} else HomeHeroHost(")
        )

        val section = source(SPOTLIGHT_HOME)
        assertTrue(
            "the hero IS the phone's billboard - one component, not a second hero",
            section.contains("MobileBillboard(")
        )
        assertTrue(
            "fed by the same feed chooser the phone's dialog writes",
            section.contains("billboardChosenFeed(") &&
                section.contains("AppPreferences.getHomeBillboardFeeds(context)")
        )
        assertTrue(
            "and cycling on the same hold pref",
            section.contains("cycleMs = billboardCycleMs") &&
                section.contains("AppPreferences.getHomeBillboardCycleMs(context)")
        )
        assertTrue(
            "with the billboard's own Play and Details",
            section.contains("onPlay = { page ->") && section.contains("onDetails = { page ->")
        )
        assertFalse(
            "and NOTHING in it reads the focused card: the hero cycling is the " +
                "point, not an oversight",
            section.contains("focusedItem")
        )
    }

    @Test
    fun `the focused card still resolves its meta through the hero resolve`() {
        val home = source(HOME)
        val spotlightBranch = between(home, "if (spotlightActive) {", "} else HomeHeroHost(")
        assertTrue(
            "Spotlight drives the same meta resolve off the focused card, so the " +
                "panel and the hero show the same data source",
            spotlightBranch.contains("viewModel.resolveHeroMeta(focused)")
        )
        assertTrue(
            "after the hero's own dwell, unchanged",
            spotlightBranch.contains("delay(HERO_RESOLVE_DEBOUNCE_MS)")
        )
    }

    // ── (c) the expansion overlays; neighbours do not move ─────────────────

    @Test
    fun `the expanded card is drawn over the rail at the card's own bounds`() {
        val preview = source(SPOTLIGHT_PREVIEW)
        assertTrue(
            "the panel grows past the card rather than replacing it",
            preview.contains("PANEL_GROWTH")
        )
        assertTrue(
            "and is positioned from the focused card's bounds - an overlay, so " +
                "the rail's other cards never move",
            preview.contains("spotlightPanelBox(") &&
                preview.contains("cardLeftPx = snapshot.bounds.left")
        )

        val section = source(SPOTLIGHT_HOME)
        assertTrue(
            "the host draws it over the screen rather than inside the rail",
            section.contains("Box(modifier = modifier.fillMaxSize().zIndex(SPOTLIGHT_PANEL_Z))") &&
                section.contains("SpotlightPreviewPanel(")
        )

        val home = source(HOME)
        assertTrue(
            "the Home provides the tracker around the rails, and null on Modern",
            home.contains("LocalSpotlightCardFocus provides") &&
                home.contains("if (spotlightActive) spotlightTracker else null")
        )
        assertTrue(
            "and the overlay is drawn over the rails, under the screensaver",
            home.indexOf("SpotlightPreviewHost(") in
                (home.indexOf("if (screensaverVisible") - 4_000) until home.indexOf("if (screensaverVisible")
        )
        assertTrue(
            "the cards report themselves through the one component rails already " +
                "draw, so no rail had to change",
            source(POSTER_CARD).contains("LocalSpotlightCardFocus.current")
        )
    }

    @Test
    fun `the panel carries the meta line and the two-line overview, and no action row`() {
        val preview = source(SPOTLIGHT_PREVIEW)
        assertTrue("title", preview.contains("meta?.name ?: snapshot.title.orEmpty()"))
        assertTrue("meta line", preview.contains("spotlightMetaLine(meta)"))
        assertTrue(
            "the overview is capped at two lines, never a wall of text",
            preview.contains("maxLines = 2")
        )
        assertTrue(
            "the watched mark",
            preview.contains("if (snapshot.isWatched)")
        )
        assertFalse(
            "and NO action row (rework 2026-11-12, F2): the card's own OK opens " +
                "Details, so the panel does not need a copy of it",
            preview.contains("label = \"PLAY\"") ||
                preview.contains("label = \"DETAILS\"")
        )
        assertTrue(
            "the meta line is the hero's own shape: year, runtime, rating, genre",
            preview.contains("meta.releaseInfo") &&
                preview.contains("meta.runtime") &&
                preview.contains("meta.imdbRating") &&
                preview.contains("meta.genres")
        )
    }

    @Test
    fun `focus leaving the card collapses it and stops its trailer`() {
        val preview = source(SPOTLIGHT_PREVIEW)
        val tracker = between(preview, "internal class SpotlightCardFocus", "staticCompositionLocalOf")
        // Repointed by the 2026-11-12 dwell spec: the blur no longer ERASES the
        // card. Erasing it on every blur is what let a one-frame flicker cancel
        // a two-second dwell (and why the panel never opened), so the honest
        // focus bit is separate and the Home debounces it before collapsing.
        assertTrue(
            "no card HOLDING focus is represented by the focus bit",
            tracker.contains("cardFocused = false")
        )
        val host = source(SPOTLIGHT_HOME)
        // Repointed again by the 2026-11-12 panel rework (F3): the panel follows
        // the CARD the viewer is on. A different card collapses it, a departure
        // collapses it once the grace has passed, a menu or a disabled expand
        // collapses it - and a blur that lands back on the same card does not.
        assertTrue(
            "the host collapses on a menu, a disabled expand, another card, or a departure",
            host.contains("menuOpen -> \"menuOpen\"") &&
                host.contains("\"cardChanged\"") &&
                host.contains("\"focusLeft\"") &&
                host.contains("live.node !== heldNode")
        )
        assertTrue(
            "and the card the panel is on is the tracker's NODE, not the title",
            host.contains("val heldNodeNow = if (tracker.cardFocused) tracker.focusedNode else null") &&
                tracker.contains("var focusedNode: Any? = null")
        )
        assertTrue(
            "the panel (and its trailer) only exist while expanded",
            host.contains("val panel = expanded?.snapshot ?: return")
        )
    }

    // ── (d) one pooled player: the expanded card wins, the hero keeps still ─

    @Test
    fun `the expanded card owns the one trailer player and the hero shows its still`() {
        assertTrue(
            "hero mode while a card is up is OFF, whatever the viewer chose",
            SpotlightTrailerRule.heroModeWhileExpanded() == off
        )
        val section = source(SPOTLIGHT_HOME)
        assertTrue(
            "and the hero is handed exactly that while the card is expanded",
            section.contains("trailerMode = if (cardExpanded) {") &&
                section.contains("SpotlightTrailerRule.heroModeWhileExpanded()")
        )

        val preview = source(SPOTLIGHT_PREVIEW)
        val trailer = source(SPOTLIGHT_PANEL_TRAILER)
        assertTrue(
            "the card reuses the hero's player composable, not a new one",
            trailer.contains("HeroInlineTrailerPlayer(")
        )
        assertTrue(
            "and the hero's resolve ladder, so there is no second resolve path",
            trailer.contains("TrailerPlayerLauncher.resolvePlayableUrl(")
        )
        assertTrue(
            "AUTO waits its delay from the EXPANSION, never from focus, and the " +
                "delay is the viewer's own (fix spec F7)",
            trailer.contains("delay(delayMs.coerceAtLeast(0L))") &&
                source(SPOTLIGHT_HOME).contains("SpotlightTrailerRule.expandedCardDelayMs(")
        )
        assertFalse(
            "the panel has no TRAILER pill any more (rework 2026-11-12, F2)",
            preview.contains("label = \"TRAILER\"")
        )

        // The rule itself, as arithmetic (rework F4): the autoplay pref decides,
        // OFF is the only veto, and there is no manual path left to fall back on.
        assertTrue(SpotlightTrailerRule.autoplaysInCard(auto, heroTrailerAutoplay = true))
        assertTrue(SpotlightTrailerRule.autoplaysInCard(manual, heroTrailerAutoplay = true))
        assertFalse(SpotlightTrailerRule.autoplaysInCard(auto, heroTrailerAutoplay = false))
        assertFalse(SpotlightTrailerRule.autoplaysInCard(off, heroTrailerAutoplay = true))
    }

    // ── (e) Modern is the same screen it was ───────────────────────────────

    @Test
    fun `switching back to Modern restores the focus-following hero`() {
        val home = source(HOME)
        assertTrue(
            "the layout is read once per Home, not duplicated per rail",
            home.contains("HomeLayout.fromStored(AppPreferences.getHomeLayout(context))")
        )
        assertTrue(
            "and Modern is the branch that draws HomeHeroHost - the untouched hero",
            home.contains("} else HomeHeroHost(")
        )
        assertEquals(
            "with no card tracker provided at all, a Modern Home pays nothing for " +
                "the feature",
            1,
            Regex("LocalSpotlightCardFocus provides").findAll(home).count()
        )
    }

    // ── (f) the phone Home is unchanged ───────────────────────────────────

    @Test
    fun `the phone Home neither offers the layout nor reports card focus`() {
        val mobile = source(MOBILE)
        assertFalse(
            "no layout setting on the phone",
            mobile.contains("HomeLayout") || mobile.contains("setHomeLayout")
        )
        assertFalse(
            "and no card preview: the phone's billboard is the Home it always had",
            mobile.contains("LocalSpotlightCardFocus")
        )
        assertEquals(
            "the billboard component itself is shared, though - one hero for both " +
                "Homes, which is why the phone's copy is visible to the TV",
            1,
            Regex("internal fun MobileBillboard\\(").findAll(mobile).count()
        )
        assertTrue(
            "the phone's rail key now comes from the shared rule, so a feed picked " +
                "on one Home names the same rail on the other",
            mobile.contains("com.kennyb1201.kbstream.ui.home.billboardFeedKey(this)")
        )
    }

    // ── (g) the expand delay setting ──────────────────────────────────────

    @Test
    fun `the expand delay offers No expand then one to ten seconds, default two`() {
        assertEquals(
            "eleven steps: No expand, then 1s-10s",
            listOf(0L) + (1..10).map { it * 1_000L },
            HomeExpandDelay.OPTIONS_MS
        )
        assertEquals(2_000L, HomeExpandDelay.DEFAULT_MS)
        assertEquals("No expand", HomeExpandDelay.label(0L))
        assertEquals("5s", HomeExpandDelay.label(5_000L))
        assertFalse(
            "No expand really means no expansion",
            HomeExpandDelay.expands(HomeExpandDelay.NO_EXPAND_MS)
        )
        assertTrue(HomeExpandDelay.expands(1_000L))
        assertEquals(
            "a hand-edited value lands on the nearest offered step",
            3_000L,
            HomeExpandDelay.normalize(3_400L)
        )

        val prefs = source(PREFS)
        assertTrue(
            // Same repoint as the layout's default: the read is deviceValue now,
            // the default is still the two-second step.
            "stored default is the two-second step",
            prefs.contains("deviceValue(context, KEY_HOME_CARD_EXPAND_MS) as? Long ?: 2_000L")
        )
        val settings = source(SETTINGS)
        assertTrue(
            "the TV offers the row, with its own search entry",
            settings.contains("text = \"Card expand delay\"") &&
                settings.contains("SettingsSearchIndex.entryForLabel(\"Card expand delay\")")
        )
        assertTrue(
            "writing the picked step",
            settings.contains("AppPreferences.setHomeCardExpandMs(context, option)")
        )
        assertTrue(
            "and the list is the rule's own, so the row cannot drift from it",
            settings.contains("HomeExpandDelay.OPTIONS_MS")
        )
        assertTrue(
            "No expand keeps Spotlight: the layout branch keys on the layout alone",
            source(HOME).contains("val spotlightActive = homeLayout == HomeLayout.SPOTLIGHT")
        )
    }

    @Test
    fun `the expand delay is not the hero's 150ms debounce`() {
        val home = source(HOME)
        assertTrue(
            "the two timers stay separate: the debounce is the fixed resolve gate",
            home.contains("HERO_RESOLVE_DEBOUNCE_MS = 150L")
        )
        assertTrue(
            "and the expansion waits the chosen delay, not the debounce",
            source(SPOTLIGHT_HOME).contains("delay(expandDelayMs)")
        )
        assertFalse(
            "which would be this mistake: an expansion gated on the debounce",
            source(SPOTLIGHT_HOME).contains("HERO_RESOLVE_DEBOUNCE_MS")
        )
    }

    // ── (h) the TV billboard controls write the phone's prefs ─────────────

    @Test
    fun `the TV billboard offers the feed chooser, the hold and the trailer mode`() {
        val settings = source(SETTINGS)
        val section = between(settings, "private fun BillboardSettingsSection()", "private fun LanguageChipGrid")
        assertTrue("the section exists", section.isNotEmpty())
        assertTrue(
            "catalogs toggle the same feed list the phone's dialog writes",
            section.contains("AppPreferences.setHomeBillboardFeeds(context, normalized)") &&
                section.contains("AppPreferences.getHomeBillboardFeeds(context)")
        )
        assertTrue(
            "the hold picker writes the same cycle pref",
            section.contains("AppPreferences.setHomeBillboardCycleMs(context, option)") &&
                section.contains("AppPreferences.BILLBOARD_CYCLE_OPTIONS_MS")
        )
        assertTrue(
            "and the trailer mode writes the one shared billboard mode",
            section.contains("AppPreferences.setHomeBillboardTrailerMode(context, mode)") &&
                section.contains("BillboardTrailerMode.entries")
        )
        assertTrue(
            "all three are searchable where they live",
            section.contains("entryForLabel(\"Billboard catalogs\")") &&
                section.contains("entryForLabel(\"Billboard hold\")") &&
                section.contains("entryForLabel(\"Billboard trailer\")")
        )
        listOf(
            "interface.billboard-catalogs",
            "interface.billboard-hold",
            "interface.billboard-trailer"
        ).forEach { key ->
            assertTrue(
                "the index carries $key",
                source(SEARCH).contains("\"$key\"")
            )
        }

        val phone = source(PHONE_MANAGER)
        assertTrue(
            "and the phone's own dialog keeps writing them - one preference, two " +
                "doors, never a second copy",
            phone.contains("setHomeBillboardTrailerMode") &&
                phone.contains("setHomeBillboardCycleMs")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SPOTLIGHT_HOME = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val SPOTLIGHT_PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val SPOTLIGHT_PANEL_TRAILER =
            "com/kennyb1201/kbstream/ui/home/SpotlightPanelTrailer.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val PHONE_MANAGER = "com/kennyb1201/kbstream/ui/mobile/HomeManagerDialogMobile.kt"
        const val PHONE_SETTINGS = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"

        val auto = com.kennyb1201.kbstream.data.settings.BillboardTrailerMode.AUTO
        val manual = com.kennyb1201.kbstream.data.settings.BillboardTrailerMode.MANUAL
        val off = com.kennyb1201.kbstream.data.settings.BillboardTrailerMode.OFF
    }
}
