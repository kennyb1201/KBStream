package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotlight hero's RENDER path (spec 2026-11-12).
 *
 * Field evidence, build 0.9.245: a TV paste showed `pages dealt=20 settled=20`
 * and `resolve resolved=20 null=0 of 20` - a healthy pool - while the Home had
 * no billboard hero at all. The data path was fine; the render path was shut.
 *
 * The branch, verified at the call sites and pinned below:
 *
 *  - B1 (layout) is the failure. `getHomeLayout` answered "modern", so
 *    `spotlightActive` was false and the hero item was never emitted - which is
 *    also why nothing cleared the pool: the clear only runs inside the hero
 *    section, so the trace kept a 20/20 settled pool from an earlier visit
 *    beside a Home with no hero.
 *  - The cause of the revert was 0.9.245's own device-store seed: ONE flag, set
 *    on the first read whichever surface made it, copying from `prefs(context)`
 *    - the ACTIVE PROFILE's file, which is the LEGACY GLOBAL file (holding none
 *    of the six) until the stored profiles are restored. A read in that window
 *    copied nothing and marked the box seeded for good, so `getHomeLayout` has
 *    answered the "modern" default ever since.
 *  - B2 (item emission), B3 (a stale gate), B4 (blank art) and B5 (zero height)
 *    were each checked and are not the failure: the item is emitted under
 *    `spotlightActive` with its key, the section's gate reads collected state,
 *    the art falls back backdrop -> background -> poster, and the item's height
 *    is handed down. They are pinned here so a later change cannot quietly make
 *    one of them the next cause.
 */
class SpotlightRenderGateContractTest {

    private val prefs by lazy { source(PREFERENCES) }
    private val home by lazy { source(HOME_SCREEN) }
    private val section by lazy { source(SPOTLIGHT_HOME) }

    // ── B1: the layout gate, the seed that shut it, and the per-profile move

    @Test
    fun `the layout is read from the profile, seeded once, and never decided without one`() {
        assertTrue(
            "the layout reads THIS PROFILE's value (spec 2026-11-15)",
            prefs.contains("layoutValue(context, KEY_HOME_LAYOUT) as? String")
        )
        assertTrue(
            "one key at a time, so one bad read cannot decide the other five",
            prefs.contains("private fun layoutValue(context: Context, key: String): Any?")
        )
        assertTrue(
            "the seed never overwrites a value the profile already holds - a choice made " +
                "there is final",
            prefs.contains("profile.all[key]?.let { return it }")
        )
        assertTrue(
            "it seeds from the pre-move device store, so no Home flips on the update",
            prefs.contains("devicePrefs(context).all[key]")
        )
        assertTrue(
            "and it refuses to run, or mark, before a profile exists: prefs() is the " +
                "legacy global file until then, and the 0.9.245 field revert came from " +
                "deciding a box in exactly that window",
            prefs.contains("ProfileStorage.activeProfileId(context)") &&
                prefs.contains("if (profileId.isNullOrBlank()) return null")
        )
        assertTrue(
            "the seeded flag is PER KEY and lives in the profile, so a later read of a " +
                "key the seed had nothing for still falls to its default instead of " +
                "re-reading the device store",
            prefs.contains("private const val LAYOUT_SEEDED_PREFIX = \"__layout_seeded__\"") &&
                prefs.contains("profile.getBoolean(LAYOUT_SEEDED_PREFIX + key, false)")
        )
        assertFalse(
            "the one-shot flag that poisoned the field box is gone from the source, and " +
                "nothing consults it",
            prefs.contains("DEVICE_PREFS_SEEDED_KEY") ||
                prefs.contains("\"device_prefs_seeded\"")
        )
    }

    @Test
    fun `the section's gate reads collected pages, and the item is keyed`() {
        assertTrue(
            "the hero item is emitted only in Spotlight, with its own list key",
            home.contains("if (spotlightActive) {") &&
                home.contains("key = SPOTLIGHT_HERO_ITEM_KEY")
        )
        assertTrue(
            "the layout selection is the stored value, not a remembered constant",
            home.contains("HomeLayout.fromStored(AppPreferences.getHomeLayout(context))") &&
                home.contains("val spotlightActive = homeLayout == HomeLayout.SPOTLIGHT")
        )
        assertTrue(
            "the section's gate is current state: pages are collected, not remembered",
            section.contains("val pages by viewModel.billboardPages.collectAsStateWithLifecycle()") &&
                section.contains(
                    "if (!billboardEnabled || pages.none { page -> page.settled }) {"
                )
        )
        assertTrue(
            "and the art falls back backdrop -> background -> poster, so a page with no " +
                "backdrop is never a blank frame",
            source(MOBILE_HOME)
                .contains(".data(backdropUrl ?: meta.background ?: meta.poster)")
        )
    }

    // ── F2: the gate inputs in the paste ──────────────────────────────────

    @Test
    fun `the Home reports the gate inputs it actually renders with`() {
        assertTrue(
            "the enabled flag and the layout are written from the Home, which is composed " +
                "even when the hero is not",
            home.contains("SpotlightDiagTrace.billboardGate(") &&
                home.contains("layout = homeLayout.stored") &&
                home.contains("spotlightActive = spotlightActive")
        )
        assertTrue(
            // The render line sits between them since the 2026-11-12 follow-up
            // (it is the same question one step further along: what did the
            // render do with this gate's answer), and the gate is still FIRST.
            // The rest line joined them on 2026-11-17: where the pager actually
            // sits, the same frame question one step further again.
            "and the trace carries them as the first billboard lines",
            source(TRACE).contains("listOf(\"gate\", \"render\", \"rest\", \"feed\"") ||
                source(TRACE).contains("listOf(\"gate\", \"render\", \"feed\"") ||
                source(TRACE).contains("listOf(\"gate\", \"render\"")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val PREFERENCES = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SPOTLIGHT_HOME = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val MOBILE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val TRACE = "com/kennyb1201/kbstream/data/reporting/SpotlightDiagTrace.kt"
    }
}
