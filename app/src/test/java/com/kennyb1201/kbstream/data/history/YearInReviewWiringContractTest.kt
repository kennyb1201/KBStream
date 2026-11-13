package com.kennyb1201.kbstream.data.history

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the year in review is wired (spec 2026-11-13).
 *
 * [YearInReviewTest] pins the arithmetic; this pins the properties that only
 * exist across files, and the ones the spec names as constraints: the reads stay
 * local and profile-scoped, marked-watched rows are excluded in SQL as well as
 * in Kotlin, the hours figure is never presented as exact, all three entry
 * points exist, and the share card carries no profile name.
 */
class YearInReviewWiringContractTest {

    private val dao by lazy { source(WATCH_HISTORY_DAO) }
    private val compute by lazy { source(YEAR_IN_REVIEW) }
    private val screen by lazy { source(YEAR_REVIEW_SCREEN) }
    private val viewModel by lazy { source(YEAR_REVIEW_VIEW_MODEL) }
    private val share by lazy { source(YEAR_REVIEW_SHARE) }
    private val homeCard by lazy { source(YEAR_IN_REVIEW_CARD) }
    private val home by lazy { source(HOME_SCREEN) }
    private val phoneHome by lazy { source(PHONE_HOME) }
    private val library by lazy { source(LIBRARY_SCREEN) }
    private val phoneLibrary by lazy { source(PHONE_LIBRARY) }
    private val settings by lazy { source(SETTINGS_SCREEN) }
    private val phoneSettings by lazy { source(PHONE_SETTINGS) }
    private val activity by lazy { source(MAIN_ACTIVITY) }
    private val nav by lazy { source(NAV_SERIALIZATION) }
    private val sharePaths by lazy { repoFile("app/src/main/res/xml/update_file_paths.xml") }
    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml") }

    // ── The reads ────────────────────────────────────────────────────────

    @Test
    fun `the year is a local range, and marked watches are excluded in the query too`() {
        assertTrue("the review reads a year window", dao.contains("fun rowsForYear(fromMs: Long, toMs: Long)"))
        assertTrue("completed rows enter by their completion stamp", dao.contains("completedAt >= :fromMs AND completedAt < :toMs"))
        assertTrue("open rows enter by their last update", dao.contains("updatedAt >= :fromMs AND updatedAt < :toMs"))
        // Belt and braces: the Kotlin side re-checks the flag, but a query that
        // counted markers would still have to read thousands of them.
        assertTrue("SQL excludes one-tap marks", dao.contains("WHERE markedWatched = 0"))
        assertTrue("and there is a cheap change token for the cache", dao.contains("fun latestUpdateAt(): Long"))
    }

    @Test
    fun `the screen is per profile and builds no new collection`() {
        assertTrue("the profile is part of the cache key", viewModel.contains("\$profileId|\$wanted"))
        assertTrue("the database is resolved per profile", viewModel.contains("getInstanceScoped"))
        assertTrue("the active profile is read at load time", viewModel.contains("ProfileStorage.activeProfileId"))
        assertFalse(
            "no background job: nothing loads until the screen asks",
            viewModel.contains("init {")
        )
    }

    @Test
    fun `the cache is invalidated by a history write`() {
        assertTrue("the numbers carry the token they were read at", viewModel.contains("CacheEntry(token, it)"))
        assertTrue("a matching token repaints the cache", viewModel.contains("cached.token == token"))
        assertTrue("a write moves the token", dao.contains("MAX(updatedAt)"))
    }

    @Test
    fun `there is no new table, no play counter and no pattern for most-rewatched`() {
        assertFalse("no play count column was added", dao.contains("playCount"))
        assertFalse(
            "history keeps one row per title - the entity gains nothing",
            source(WATCH_HISTORY_ENTITY).contains("rewatch")
        )
        assertTrue("marked rows are skipped in the arithmetic", compute.contains("filter { !it.markedWatched }"))
    }

    // ── The screen ───────────────────────────────────────────────────────

    @Test
    fun `the hours figure is labelled approximate, with the reason on screen`() {
        assertTrue("the headline carries the mark", screen.contains("\"≈ \${hoursLabel(review.approxRuntimeMs)} watched\""))
        assertTrue("the reason is printed, not buried", screen.contains("Approximate: history keeps one row per title"))
        assertTrue("a minute-scale figure stays a minute figure", screen.contains("minutes"))
    }

    @Test
    fun `a year with no history is an empty state, not an error`() {
        assertTrue(screen.contains("Nothing watched in \$year."))
        assertTrue("the year picker offers only the years with history", screen.contains("viewModel.load(candidate)"))
    }

    @Test
    fun `a failed read offers a retry instead of an empty page`() {
        assertTrue(viewModel.contains("const val READ_FAILED"))
        assertTrue(screen.contains("KBButton(label = \"RETRY\""))
    }

    // ── The entries ──────────────────────────────────────────────────────

    @Test
    fun `the seasonal card is offered from December to January, and named for the year`() {
        assertTrue(homeCard.contains("today.monthValue == 12"))
        assertTrue(homeCard.contains("if (today.monthValue == 1) today.year - 1 else today.year"))
        assertTrue("TV Home draws it", home.contains("YearInReviewHomeCard("))
        assertTrue("and so does the phone", phoneHome.contains("YearInReviewHomeCard("))
    }

    @Test
    fun `the Library and Settings rows are the year-round doors`() {
        assertTrue("the TV library header", library.contains("YEAR IN REVIEW"))
        assertTrue("the phone library header", phoneLibrary.contains("YEAR IN REVIEW"))
        assertTrue("the TV settings row", settings.contains("label = \"Year in review\""))
        assertTrue("the phone settings row", phoneSettings.contains("label = \"Year in review\""))
    }

    @Test
    fun `the route exists, restores, and gives Back its own destination`() {
        assertTrue(activity.contains("data class YearReview("))
        assertTrue(activity.contains("YearReviewScreen("))
        assertTrue(nav.contains("\"yearReview\""))
    }

    // ── The share card (F2) ──────────────────────────────────────────────

    @Test
    fun `the share card carries no profile name and only three titles`() {
        assertFalse("no profile name is drawn", share.contains("profileName"))
        assertTrue("at most three titles", share.contains("topShows.take(3)"))
        assertTrue("it goes through the system sheet", share.contains("Intent.createChooser(send"))
        assertTrue("the file is scoped to one read grant", share.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue("the shared cache path exists", sharePaths.contains("path=\"share/\""))
        assertTrue("and the provider that serves it is declared", manifest.contains("updateprovider"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "app/src/main/java/$relative")
            if (file.isFile) return file.readText()
            val nested = File(dir, "src/main/java/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw AssertionError("repo file not found: $relative")
    }

    private companion object {
        const val HISTORY = "com/kennyb1201/kbstream/data/history/"
        const val UI = "com/kennyb1201/kbstream/ui/"
        const val WATCH_HISTORY_DAO = "${HISTORY}WatchHistoryDao.kt"
        const val WATCH_HISTORY_ENTITY = "${HISTORY}WatchHistoryEntity.kt"
        const val YEAR_IN_REVIEW = "${HISTORY}YearInReview.kt"
        const val YEAR_REVIEW_SCREEN = "${UI}settings/YearReviewScreen.kt"
        const val YEAR_REVIEW_VIEW_MODEL = "${UI}settings/YearReviewViewModel.kt"
        const val YEAR_REVIEW_SHARE = "${UI}settings/YearReviewShareCard.kt"
        const val YEAR_IN_REVIEW_CARD = "${UI}home/YearInReviewCard.kt"
        const val HOME_SCREEN = "${UI}home/HomeScreen.kt"
        const val PHONE_HOME = "${UI}mobile/HomeScreenMobile.kt"
        const val LIBRARY_SCREEN = "${UI}library/LibraryScreen.kt"
        const val PHONE_LIBRARY = "${UI}mobile/LibraryScreenMobile.kt"
        const val SETTINGS_SCREEN = "${UI}settings/SettingsScreen.kt"
        const val PHONE_SETTINGS = "${UI}mobile/SettingsScreenMobile.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NAV_SERIALIZATION = "com/kennyb1201/kbstream/NavStateSerialization.kt"
    }
}
