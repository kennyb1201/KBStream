package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring of Home's personalized row, "Top Picks for You".
 *
 * The row is one row built out of three things that live in three places, and
 * every one of them can be missing while the others look right - which is
 * exactly the failure mode the built-in renderer's own comment warns about (an
 * unknown built-in key draws NOTHING, silently, on the surface whose branch is
 * missing):
 *
 *  1. the arrangement knows the key (see the order tests, in `data/kb`);
 *  2. BOTH screens draw it, and both are told it has content (a branch on one
 *     surface only is a row that exists on the television and not on the phone);
 *  3. the ViewModel builds it: seeds from the profile's own completions, one
 *     blend per seed with the row's own parameters, and the shared merge.
 *
 * The row's arithmetic (the merge, the watched mode) is pinned in
 * `RecommendationBlendTest`; what is pinned here is the join, read from the
 * sources, because neither Home screen can be driven from a JVM test.
 */
class TopPicksRailContractTest {

    @Test
    fun `both screens draw the row and both are told it has content`() {
        // The build-in key must be matched explicitly on both surfaces: a
        // built-in key with no branch is not an error, it is an empty slot.
        listOf(home, mobile).forEach { source ->
            assertTrue(
                "every surface with a built-in switch must handle the row",
                source.contains("BUILTIN_TOP_PICKS_FOR_YOU")
            )
        }
        assertTrue(
            "the TV must draw it from its own composable",
            home.contains("BuiltinTopPicksRail(")
        )
        assertTrue(
            "the phone must draw it inside its built-in switch",
            mobile.contains("KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU -> {")
        )

        // The content gate: the key reaches the merge only while the row has
        // picks, so an empty (or not-yet-built, or failed) row is absent rather
        // than a bare heading.
        assertTrue(
            "the content gate must ask the picks, not the profile kind",
            home.contains("if (topPicks.isNotEmpty()) {") &&
                mobile.contains("if (topPicks.isNotEmpty())")
        )
        assertTrue(
            "the TV's gate is the pick list",
            home.contains("val builtinWithContent = remember(upNext, upcomingSchedule, topPicks) {")
        )
        assertTrue(
            "and so is the phone's",
            mobile.contains("remember(upNext.isNotEmpty(), upcomingVisible, topPicks.isNotEmpty()) {")
        )
    }

    @Test
    fun `the row reuses the rail card every other rail draws`() {
        // No second card shape: the TV row draws the shared poster tile and
        // registers its cards in the same focus map the catalog rails fill, and
        // the phone draws the same tile its catalog rails draw.
        assertTrue(
            "the TV row must draw the shared poster card",
            home.contains("private fun BuiltinTopPicksRail(") &&
                home.contains("posterUrl = meta.poster,")
        )
        assertTrue(
            "and register its cards for the return-from-detail restore",
            home.contains("cardFocusRequesters[cardKey] = requester")
        )
        assertTrue(
            "the phone reuses the poster tile the catalog rails draw",
            mobile.contains("private fun mobileBlendPickPreview(") &&
                mobile.contains("contentDescription = meta.name,")
        )
        assertFalse(
            "no second card composable may be invented for this row",
            home.contains("TopPicksCard(") || mobile.contains("TopPicksCard(")
        )
        assertFalse(
            "and no new badge pipeline",
            home.contains("TopPicksBadge") || mobile.contains("TopPicksBadge")
        )
    }

    @Test
    fun `the row is built from the profile's own completions, with the row's own parameters`() {
        assertTrue(
            "the build must be its own entry point",
            viewModel.contains("fun refreshTopPicks()")
        )
        assertTrue(
            "seeded from completed history rows",
            viewModel.contains("getRecentCompletedSeeds(TOP_PICKS_SEED_SCAN)")
        )
        assertTrue(
            "one blend per seed, with the row's parameters",
            viewModel.contains("buildRecommendationBlend(") &&
                viewModel.contains("includeFranchise = false") &&
                viewModel.contains("watchedMode = BlendWatchedMode.DROP_COMPLETED") &&
                viewModel.contains("dropHidden = true")
        )
        assertTrue(
            "the profile's taste signals steer the seeds and the drop",
            viewModel.contains("TasteSignals.snapshot(") &&
                viewModel.contains("taste.isDisliked(") &&
                viewModel.contains("likedAt(taste,")
        )
        assertTrue(
            "and the merge is the shared one, capped per seed and in total",
            viewModel.contains("mergeTopPicksSeeds(") &&
                viewModel.contains("perSeedCap = TOP_PICKS_PER_SEED") &&
                viewModel.contains("rowCap = TOP_PICKS_ROW_CAP")
        )
        assertTrue(
            "the profile-wide filters apply once, to the merged list",
            viewModel.contains("repo.kidsFilter(picks)") &&
                viewModel.contains("repo.filterByHomeAvailability(picks)")
        )
    }

    @Test
    fun `the row publishes only from its own build, and never blocks Home`() {
        // One publish in the build's own gate, one clear in the profile switch:
        // nothing else writes the row, so no rail-build path can publish it -
        // which is what keeps a slow or failed Top Picks build off Home's
        // first paint.
        assertEquals(
            "the row must publish from its own build and clear on a switch, nothing else",
            2,
            Regex("_topPicks\\.value = ").findAll(viewModel).count()
        )
        assertTrue(
            "the publish is gated on the build epoch, so a switch supersedes it",
            viewModel.contains("if (epoch == topPicksBuildEpoch) {")
        )
        assertTrue(
            "a profile switch clears the outgoing profile's row",
            viewModel.contains("topPicksBuildEpoch += 1") &&
                viewModel.contains("_topPicks.value = emptyList()")
        )
    }

    @Test
    fun `the seed query is completed-only, one row per parent, newest first`() {
        assertTrue(
            "a seed is a completion, never a resume row",
            history.contains("suspend fun getRecentCompletedSeeds(limit: Int)")
        )
        assertTrue(
            "keyed one row per title, so an episode's completion names its show once",
            history.contains("GROUP BY parentId")
        )
        assertTrue(
            "ranked by completion, newest first",
            history.contains("ORDER BY completedAt DESC")
        )
        assertTrue(
            "and capped by its caller",
            history.contains("LIMIT :limit")
        )
    }

    @Test
    fun `the row is arranged like the other built-ins, and adds no preference`() {
        assertTrue(
            "the key is a built-in key, so the manager can move, hide and rename it",
            order.contains("BUILTIN_TOP_PICKS_FOR_YOU")
        )
        assertTrue(
            "with a default title",
            order.contains("\"Top Picks for You\"")
        )
        assertFalse(
            "no new preference: the rail manager is the control",
            preferences.contains("top_picks") || preferences.contains("topPicks")
        )
        assertFalse(
            "and no Settings row for it",
            settings.contains("Top Picks for You")
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private val home by lazy { source(HOME) }
    private val mobile by lazy { source(MOBILE) }
    private val viewModel by lazy { source(VIEW_MODEL) }
    private val history by lazy { source(HISTORY) }
    private val order by lazy { source(ORDER) }
    private val preferences by lazy { source(PREFERENCES) }
    private val settings by lazy { source(SETTINGS) }

    private companion object {
        const val ROOT = "com/kennyb1201/kbstream/"
        const val HOME = ROOT + "ui/home/HomeScreen.kt"
        const val MOBILE = ROOT + "ui/mobile/HomeScreenMobile.kt"
        const val VIEW_MODEL = ROOT + "ui/home/HomeViewModel.kt"
        const val HISTORY = ROOT + "data/history/WatchHistoryDao.kt"
        const val ORDER = ROOT + "data/kb/KBHomeOrderPrefs.kt"
        const val PREFERENCES = ROOT + "data/settings/AppPreferences.kt"
        const val SETTINGS = ROOT + "ui/settings/SettingsScreen.kt"
    }
}
