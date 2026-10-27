package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewing-stats dive (2026-10-26), pinned at the source level.
 *
 * A JVM test cannot compose either screen, and the SQL half is exercised for
 * real by [com.kennyb1201.kbstream.data.history.ViewingStatsDaoTest]. What is
 * checked here is the shape of the fixes, one per finding:
 *
 *  - C1: the numbers are re-read on entry and on a profile change, and the
 *    view model does NOT load itself any more - a load in `init` runs once per
 *    process, which is how a reopened screen kept another profile's figures;
 *  - C2 + I2: `TopShow` carries the media type, both screens name movies as
 *    movies, and the ranking's name/poster/type come from the newest row;
 *  - C3: both stats queries fold the `tmdb:`/`tt…` id flavors;
 *  - C4: the TV top-show rows are focusable, so the list can scroll, while the
 *    headline plates stay read-only;
 *  - C5 + I1: the streak counts the viewer's local days, the runtime format
 *    falls back to minutes, and the streak badge is hidden at zero;
 *  - C6: a failed read names itself and offers a retry on both screens.
 */
class StatsDeepDiveContractTest {

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
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun tvStats(): String =
        source("com/kennyb1201/kbstream/ui/settings/StatsScreen.kt")

    private fun mobileStats(): String =
        source("com/kennyb1201/kbstream/ui/mobile/StatsScreenMobile.kt")

    private fun viewModel(): String =
        source("com/kennyb1201/kbstream/ui/settings/StatsViewModel.kt")

    private fun dao(): String =
        source("com/kennyb1201/kbstream/data/history/WatchHistoryDao.kt")

    private fun stats(): String =
        source("com/kennyb1201/kbstream/data/history/ViewingStats.kt")

    @Test
    fun `both screens re-read on entry and on a profile change`() {
        listOf(
            "ui/settings/StatsScreen.kt" to tvStats(),
            "ui/mobile/StatsScreenMobile.kt" to mobileStats()
        ).forEach { (path, screen) ->
            assertTrue(
                "$path must re-read when it is entered - the stats view model is " +
                    "activity-scoped, so without this the numbers were whatever its " +
                    "one process-lifetime load produced (stats C1)",
                screen.contains("LaunchedEffect(activeProfileId) { viewModel.load() }")
            )
            assertTrue(
                "$path must key that reload on the ACTIVE PROFILE, or a profile " +
                    "switch keeps showing the previous profile's figures under the " +
                    "\"this profile's history\" subtitle (stats C1)",
                screen.contains("ProfileManager.activeProfile")
            )
        }
    }

    @Test
    fun `the view model no longer loads itself`() {
        val vm = viewModel()
        assertFalse(
            "a load in init runs once per PROCESS: that is the whole of C1's " +
                "freeze, so the screen owns the trigger instead",
            vm.contains("init { load() }")
        )
        assertTrue("the screen's trigger is still the public load()", vm.contains("fun load()"))
        assertTrue(
            "and a reload resets to loading first, so the previous profile's " +
                "numbers cannot sit under the new profile's subtitle",
            vm.contains("_state.value = UiState()")
        )
    }

    @Test
    fun `the ranking names what it ranked`() {
        assertTrue(
            "TopShow has to carry the media type, or the row cannot tell a movie " +
                "from an episode (stats C2)",
            stats().contains("val type: String")
        )
        assertTrue(
            "the type has to come out of the query",
            dao().contains("AS type")
        )
        listOf(
            "ui/settings/StatsScreen.kt" to tvStats(),
            "ui/mobile/StatsScreenMobile.kt" to mobileStats()
        ).forEach { (path, screen) ->
            assertTrue(
                "$path must read the type from the row",
                screen.contains("show.type.equals(\"movie\", ignoreCase = true)")
            )
            assertTrue(
                "$path must word a finished movie as a movie: the list used to file " +
                    "every ranking under \"1 episode finished\" (stats C2)",
                screen.contains("isMovie -> \"movie finished\"")
            )
            assertTrue(
                "$path must call the list what it is - it ranks movies too",
                screen.contains("\"TOP TITLES\"") &&
                    !screen.contains("\"TOP SHOWS\"")
            )
        }
    }

    @Test
    fun `a top row's metadata is pinned to its newest row`() {
        val dao = dao()
        assertTrue(
            "name, poster and type are not aggregates, so each has to be picked " +
                "from the newest row rather than left to SQLite (stats I2)",
            dao.contains(
                "(SELECT s.name FROM watch_history s WHERE s.parentId = w.parentId " +
                    "ORDER BY s.updatedAt DESC LIMIT 1) AS name"
            ) &&
                dao.contains(
                    "(SELECT s.poster FROM watch_history s WHERE s.parentId = w.parentId " +
                        "ORDER BY s.updatedAt DESC LIMIT 1) AS poster"
                ) &&
                dao.contains(
                    "(SELECT s.type FROM watch_history s WHERE s.parentId = w.parentId " +
                        "ORDER BY s.updatedAt DESC LIMIT 1) AS type"
                )
        )
    }

    @Test
    fun `both stats queries fold the id flavors`() {
        val dao = dao()
        assertTrue(
            "Titles finished has to count one show once, however many flavors its " +
                "rows were stored under (stats C3)",
            dao.contains("COUNT(DISTINCT COALESCE(") &&
                dao.contains(
                    "CASE WHEN parentId LIKE 'tt%' THEN parentId END"
                ) &&
                dao.contains(
                    "WHERE rc.key IN ('series::' || parentId, " +
                        "'series::' || REPLACE(parentId, 'tmdb:', ''))"
                )
        )
        assertTrue(
            "and the top list has to group by the same folded id, or the same show " +
                "ranks twice with its runtime split (stats C3)",
            dao.contains("CASE WHEN w.parentId LIKE 'tt%' THEN w.parentId END") &&
                dao.contains(
                    "WHERE rc.key IN ('series::' || w.parentId, " +
                        "'series::' || REPLACE(w.parentId, 'tmdb:', ''))"
                ) &&
                dao.contains("GROUP BY COALESCE(")
        )
        assertTrue(
            "the fold has to be the flavor rule the episode checkmarks already use " +
                "(parentIdFlavors), not a second opinion about what a twin is",
            dao.contains("FROM imdb_resolution_cache rc")
        )
    }

    @Test
    fun `the streak counts the viewer's local days and the runtime falls back to minutes`() {
        val stats = stats()
        assertTrue(
            "the streak's day boundaries have to be the viewer's, not UTC's (stats C5)",
            stats.contains("zone: ZoneId = ZoneId.systemDefault()") &&
                stats.contains("toLocalDate().toEpochDay()")
        )
        assertFalse(
            "dividing the timestamps by a day length is the UTC bucketing this " +
                "replaced - it made three local evenings read as a one-day streak",
            stats.contains("it / DAY_MS")
        )
        assertTrue(
            "and everything shorter than an hour is minutes, not \"0 h\" (stats I1)",
            stats.contains("${'$'}{ms / 60_000L} min")
        )
        listOf(
            "ui/settings/StatsScreen.kt" to tvStats(),
            "ui/mobile/StatsScreenMobile.kt" to mobileStats()
        ).forEach { (path, screen) ->
            assertTrue(
                "$path must not draw \"0 day streak\" on a profile that has none " +
                    "(stats I1)",
                screen.contains("if (state.streakDays > 0) {")
            )
        }
    }

    @Test
    fun `the tv top list is reachable from the d-pad`() {
        val tv = tvStats()
        assertEquals(
            "the ranked rows must be focusable - with no focusable node below BACK " +
                "the D-pad could not reach a row past the fold, so the list never " +
                "scrolled (stats C4)",
            1,
            Regex("\\.focusable\\(\\)").findAll(tv).count()
        )
        assertTrue(
            "and the focus position has to be visible, or the viewer is moving " +
                "blind",
            tv.contains("onFocusChanged { focused = it.isFocused }")
        )
        assertFalse(
            "the phone scrolls with a finger, so it asks for no focus at all",
            mobileStats().contains(".focusable()")
        )
    }

    @Test
    fun `a failed read is named and retryable on both screens`() {
        val vm = viewModel()
        assertTrue(
            "a read that throws has to be caught and carried, not left to kill " +
                "the coroutine with the loading flag still set (stats C6)",
            vm.contains("runCatching {") &&
                vm.contains("READ_FAILED = \"Could not read this profile's watch history.\"")
        )
        val tv = tvStats()
        assertTrue(
            "the TV page must name the failure and offer the retry",
            tv.contains("state.error?.let { message ->") &&
                tv.contains("KBButton(label = \"RETRY\", onClick = { viewModel.load() })")
        )
        val mobile = mobileStats()
        assertTrue(
            "and so must the phone page",
            mobile.contains("val error = state.error") &&
                mobile.contains("label = \"RETRY\"") &&
                mobile.contains("viewModel.load()")
        )
    }
}
