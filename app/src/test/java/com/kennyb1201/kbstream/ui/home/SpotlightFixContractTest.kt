package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three Spotlight defects, pinned (spec 2026-11-12).
 *
 * All three were layout/plumbing failures that a build cannot see and a
 * source-reading test can:
 *
 *  - the hero was sized by the phone's width math, which on a television is
 *    taller than the screen, so the rails got a zero-height viewport and the
 *    D-pad had nowhere to go;
 *  - the billboard's pills were touch clickables with no focus treatment and no
 *    anchor, so nothing held focus on Spotlight entry, and with no rails
 *    viewport the top-bar hold-up (Settings' only door from Home) could not be
 *    reached at all;
 *  - the TV's feed chooser was a hardcoded four builtins, two of which are
 *    progress rails rather than catalogs, instead of the Home's rails.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/test/java/.../SpotlightFixContractTest.kt, then JUnitCore with
 * -Duser.dir at the repository root so the source reader finds src/main/java.
 */
class SpotlightFixContractTest {

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

    // ── (a) the hero keeps the rails a viewport ───────────────────────────

    @Test
    fun `the Spotlight hero is capped by a fraction of the viewport, not by width`() {
        val section = source(SPOTLIGHT_HOME)
        assertTrue(
            "one named fraction, so the cap is a rule rather than a number in a call",
            section.contains("SPOTLIGHT_HERO_FRACTION = 0.52f")
        )
        assertTrue(
            "and it is the VIEWPORT height that is fractioned - the fix has to " +
                "hold at every TV density",
            section.contains("LocalConfiguration.current.screenHeightDp * SPOTLIGHT_HERO_FRACTION")
        )
        assertTrue(
            "the section hands that height to the billboard",
            section.contains("heroHeight = heroHeight,")
        )

        val mobile = source(MOBILE)
        val hero = mobile.substring(
            mobile.indexOf("private fun MobileHero("),
            mobile.indexOf("fun MobileBillboard(")
        )
        assertTrue(
            "the hero honours a handed-down height...",
            hero.contains("Modifier.height(heroHeight)")
        )
        assertTrue(
            "...and keeps its width-driven 16:10 only when there is none, which " +
                "is what leaves the phone's own frame exactly as it was",
            hero.contains("Modifier.aspectRatio(16f / 10f)")
        )
        assertTrue(
            "a fixed height travels through the pager to every page",
            mobile.contains("heroHeight = heroHeight,")
        )

        val home = source(HOME)
        assertTrue(
            "and the rails keep the rest of the screen - the viewport the D-pad " +
                "needs is the weight, not a guess",
            home.contains("LazyColumn(") && home.contains(".weight(1f)")
        )
    }

    @Test
    fun `the phone's billboard is untouched by the cap`() {
        val mobile = source(MOBILE)
        val call = mobile.substring(
            mobile.indexOf("MobileBillboard(\n                            pages = billboardPages,"),
            mobile.indexOf("onPlay = { page ->", mobile.indexOf("MobileBillboard(\n                            pages = billboardPages,"))
        )
        assertFalse(
            "the phone passes no height",
            call.contains("heroHeight")
        )
        assertFalse(
            "and no focus anchor: touch has no D-pad to park",
            call.contains("firstPillFocusRequester")
        )
    }

    // ── (b) the pills are the TV's focus, and the way out is intact ───────

    @Test
    fun `the hero's pills take D-pad focus and show it`() {
        val mobile = source(MOBILE)
        assertTrue(
            "focus is read from the pill's own interaction source, the same one " +
                "its click uses",
            mobile.contains("val focused by interactionSource.collectIsFocusedAsState()")
        )
        assertTrue(
            "focused, the pill grows - the scale language the rail cards speak",
            mobile.contains("focused -> 1.04f")
        )
        assertTrue(
            "and wears the accent ring, so the viewer can SEE which pill has it",
            mobile.contains("focused -> KBAccent")
        )
        assertTrue(
            "the anchored pill is the one the Home points at",
            mobile.contains("focusRequester = firstPillFocusRequester,")
        )
    }

    @Test
    fun `Spotlight parks focus on the hero's Play pill on entry`() {
        val section = source(SPOTLIGHT_HOME)
        assertTrue(
            "a requester is held for the pill",
            section.contains("val firstPillFocus = remember { FocusRequester() }")
        )
        assertTrue(
            "and asked for once the hero has rows and a frame",
            section.contains("withFrameNanos { }") &&
                section.contains("firstPillFocus.requestFocus()")
        )
        assertTrue(
            "the request cannot crash an entry that has no node yet",
            section.contains("runCatching { firstPillFocus.requestFocus() }")
        )
    }

    @Test
    fun `the rails keep the hold-up to the top bar in Spotlight too`() {
        val home = source(HOME)
        assertTrue(
            "the hook rides the rails list itself, so it covers the hero's DOWN " +
                "into the rails as well as every rail kind",
            home.contains("rememberHomeTopBarHoldUpHook(")
        )
        assertTrue(
            "and the bar it summons is drawn by the same screen, not by a layout",
            home.contains("topBarFocusRequester")
        )
        // The trap this class of fix must not re-ship: a hero that consumes the
        // viewport, so the list has none.
        val hero = home.substring(
            home.indexOf("if (spotlightActive) {"),
            home.indexOf("} else HomeHeroHost(")
        )
        assertFalse(
            "the Spotlight branch must not size the hero to the whole screen",
            hero.contains("fillMaxSize()")
        )
    }

    // ── (c) the chooser is the Home's rails, not a hardcoded four ─────────

    @Test
    fun `the TV chooser enumerates the Home's rails with friendly labels`() {
        val settings = source(SETTINGS)
        val options = settings.substring(
            settings.indexOf("private fun billboardFeedOptions(context: Context)"),
            settings.indexOf("private fun BillboardSettingsSection()")
        )
        assertTrue("the builder exists", options.isNotEmpty())
        assertTrue(
            "it reads the arrangement Home actually renders",
            options.contains("KBHomeOrderPrefs.get(context)")
        )
        assertTrue(
            "and every installed add-on catalog, so an unarranged rail is offered too",
            options.contains("getCatalogConfigurations()")
        )
        assertTrue(
            "keyed the way Home keys its rails, not by a name it invented",
            options.contains("KBHomeOrderPrefs.addonKeyFromManifest(")
        )
        assertTrue(
            "labelled through the shared chip rule, never a raw key",
            options.contains("billboardFeedLabel(title)")
        )
        assertFalse(
            "the hardcoded builtin list is gone",
            options.contains("BUILTIN_CONTINUE_WATCHING to")
        )
        assertTrue(
            "and the section offers what the builder found",
            settings.contains("catalogOptions = withContext(kotlinx.coroutines.Dispatchers.IO) {") &&
                settings.contains("billboardFeedOptions(context)")
        )
    }

    @Test
    fun `Continue Watching and Upcoming are offered by neither picker`() {
        assertTrue(
            "the exclusion is one named set both surfaces read",
            BILLBOARD_EXCLUDED_FEED_KEYS.contains(com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING) &&
                BILLBOARD_EXCLUDED_FEED_KEYS.contains(com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE)
        )
        assertTrue("a catalog key is still offered", billboardFeedOffered("addon:https://a.example:movie:top"))
        assertFalse(
            "and neither progress rail is",
            billboardFeedOffered(com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING) ||
                billboardFeedOffered(com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE)
        )

        val phone = source(PHONE_MANAGER)
        assertTrue(
            "the phone's picker filters its rail rows by the same rule",
            phone.contains("billboardFeedOffered(row.key)")
        )
        val settings = source(SETTINGS)
        assertTrue(
            "and so does the TV's",
            settings.contains("billboardFeedOffered(key)")
        )
    }

    // ── (d) stored selections are normalised ──────────────────────────────

    @Test
    fun `a stored feed is normalised on write and ignored on read`() {
        val cw = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING
        val upcoming = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE
        assertEquals(
            "the excluded keys are dropped, not merely hidden",
            listOf("addon:https://a.example:movie:top"),
            normalizeBillboardFeeds(listOf(cw, "addon:https://a.example:movie:top", upcoming))
        )
        assertEquals(
            "and an all-excluded selection is the default, never an empty feed",
            com.kennyb1201.kbstream.data.settings.AppPreferences.DEFAULT_BILLBOARD_FEEDS,
            normalizeBillboardFeeds(listOf(cw, upcoming))
        )
        assertEquals(
            "duplicates collapse too, so one rail is one chip",
            listOf("addon:https://a.example:movie:top"),
            normalizeBillboardFeeds(
                listOf("addon:https://a.example:movie:top", "addon:https://a.example:movie:top")
            )
        )

        val rules = source(RULES)
        assertTrue(
            "the hero's own resolution filters them as well, so an older pref " +
                "cannot resurrect a progress rail",
            rules.contains("val order = keys.filter(::billboardFeedOffered)")
        )
        assertTrue(
            "both pickers write through the shared normaliser",
            source(PHONE_MANAGER).contains("normalizeBillboardFeeds(next)") &&
                source(SETTINGS).contains("normalizeBillboardFeeds(next)")
        )
    }

    // ── (e) nothing else moved ────────────────────────────────────────────

    @Test
    fun `Modern and the phone keep their own layout`() {
        val home = source(HOME)
        assertTrue(
            "Modern is still the focus-following hero, in the same if's else",
            home.contains("} else HomeHeroHost(")
        )
        assertTrue(
            "and the layout still chooses between the two",
            home.contains("val spotlightActive = homeLayout == HomeLayout.SPOTLIGHT")
        )
        val settings = source(SETTINGS)
        assertTrue(
            "the expand delay and its steps are untouched",
            settings.contains("HomeExpandDelay.OPTIONS_MS")
        )
        assertTrue(
            "and the three billboard controls still write the phone's prefs",
            settings.contains("AppPreferences.setHomeBillboardFeeds(context, normalized)") &&
                settings.contains("AppPreferences.setHomeBillboardCycleMs(context, option)") &&
                settings.contains("AppPreferences.setHomeBillboardTrailerMode(context, mode)")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SPOTLIGHT_HOME = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/home/BillboardRules.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val PHONE_MANAGER = "com/kennyb1201/kbstream/ui/mobile/HomeManagerDialogMobile.kt"
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }
}
