package com.kennyb1201.kbstream.data.simkl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MobLand was missing from Upcoming with no log line to say why.
 *
 * A caught-up show with no local history row reaches the rail through Simkl's
 * caught-up leg alone, and that leg's only input is the cached all-shows
 * library. The library blob was written before the finish, so it still carried
 * the pre-finish tally (17 of 18 aired watched), the caught-up filter answered
 * "not caught up", and the show never reached the build - and because the blob
 * lives on disk for twelve hours it survived the force-close and the relaunch.
 *
 * The repair is at read time: a blob older than this device's last watched
 * write is not evidence, so the leg refreshes instead of serving it. That is the
 * case the write-path invalidation cannot cover - a finish whose Simkl push
 * failed or never ran, and a fetch that raced Simkl's own apply - so the
 * invalidation itself is unchanged (F1d).
 *
 * T1 and T2's arithmetic is in [ShowLibraryFreshnessRulesTest]; this file pins
 * the wiring and the writers that feed it, because the rule is only as good as
 * the stamp it reads. T3 and T4 are the field cases: the same code path serves
 * them (a relaunch reads the same blob the next Home load does), which is why
 * they are pinned by the writers here rather than by a second mechanism.
 */
class UpcomingCaughtUpFreshnessContractTest {

    // ── T1: the caught-up leg refuses a blob older than the last write ──

    @Test
    fun `the caught-up leg measures the blob against the last watched write`() {
        assertTrue(
            "the leg has to know what the read WOULD serve",
            caughtUp.contains("val blobBefore = showLibraryStamp( accessToken )")
        )
        assertTrue(
            "and when this device last wrote watched state",
            caughtUp.contains("val lastWriteAt = lastWatchedWriteAt()")
        )
        assertTrue(
            "the pure rule decides, not a second copy of it",
            caughtUp.contains("!isShowLibraryBlobTrustworthy(") &&
                caughtUp.contains("blobUpdatedAt = blobBefore.updatedAt") &&
                caughtUp.contains("lastWatchedWriteAt = lastWriteAt")
        )
    }

    @Test
    fun `a stale blob is refreshed instead of served`() {
        assertTrue(
            "the refresh is the existing parameter, forced",
            caughtUp.contains("getAllShowItemsCached( accessToken = accessToken, forceRefresh = refreshed )")
        )
        assertFalse(
            "never a blanket forceRefresh = true: that would fetch on every load",
            caughtUp.contains("forceRefresh = true")
        )
    }

    @Test
    fun `the refreshed tally is what makes a caught-up show a candidate`() {
        // The one episode that was the whole miss: 17 of 18 aired is filtered
        // out, 18 of 18 is a candidate. The filter is unchanged and still runs
        // on whatever the guard above decided to read.
        val rules = flat(source(READS))
        assertTrue(
            "the caught-up filter is the rule, not a count written here",
            caughtUp.contains("ShowCompletionRules.isCaughtUpUpcomingCandidate(")
        )
        assertTrue(
            "aired = total - notAired, and watched >= aired is caught up",
            rules.contains("total - notAired") && rules.contains("watched >= airedTotal")
        )
        val guard = caughtUp.indexOf("forceRefresh = refreshed")
        val filter = caughtUp.indexOf("ShowCompletionRules.isCaughtUpUpcomingCandidate(")
        assertTrue(
            "and it filters AFTER the read, so it sees the refreshed blob",
            guard >= 0 && filter > guard
        )
    }

    // ── T2: the cache still serves when nothing was written ────────────

    @Test
    fun `the TTLs still gate the cache`() {
        val repo = flat(source(REPOSITORY))
        assertTrue(
            "the memory TTL check is intact and still bypassed only by forceRefresh",
            repo.contains("!forceRefresh && cached != null && cachedAllShowItemsToken == accessToken && now - cachedAllShowItemsFetchedAt < ALL_SHOW_ITEMS_TTL_MS")
        )
        assertTrue(
            "and so is the disk one",
            repo.contains("now - diskCached.updatedAt < ALL_SHOW_ITEMS_DISK_TTL_MS")
        )
        assertTrue(
            "the TTLs themselves are unchanged: 15 minutes, 12 hours",
            repo.contains("ALL_SHOW_ITEMS_TTL_MS = 15L * 60L * 1000L") &&
                repo.contains("ALL_SHOW_ITEMS_DISK_TTL_MS = 12L * 60L * 60L * 1000L")
        )
    }

    @Test
    fun `the blob is read, never refreshed, by the stamp`() {
        assertTrue(
            "the stamp only looks: memory when it is this account's, else disk",
            repo.contains("return ShowLibraryStamp( updatedAt = cachedAllShowItemsBlobUpdatedAt, source = \"memory\" )") &&
                repo.contains("updatedAt = disk.updatedAt") &&
                repo.contains("source = \"none\"")
        )
        assertTrue(
            "and a memory snapshot parsed out of the disk keeps the BLOB's mint " +
                "time: the relaunch that served the pre-finish tally read it off " +
                "the disk, where `fetchedAt` would have said \"now\"",
            repo.contains("cachedAllShowItemsBlobUpdatedAt = diskCached.updatedAt")
        )
        assertFalse(
            "and it must not fetch - that is the load's job, once",
            stampBody.contains("getAllShowItems(")
        )
    }

    // ── T1/T4: every watched write feeds the stamp ─────────────────────

    @Test
    fun `a successful Simkl write stamps the moment`() {
        assertTrue(
            "the one place a Simkl write succeeds is where the timestamp comes from",
            invalidate.contains("WatchedWriteStamp.record(")
        )
        assertTrue(
            "and the invalidation it already did is still there (F1d)",
            invalidate.contains("invalidateShowLibraryCache()")
        )
    }

    @Test
    fun `both local mark paths stamp, and the marker write does too`() {
        val watched = flat(source(WATCHED_REPO))
        assertEquals(
            "a hand mark (applyOverrideKeys), the episode marker write, and the " +
                "episode CLEAR (the picker menu's other direction, spec 2026-11-13) " +
                "are three different local writes, so all three have to say so",
            3,
            watched.split("WatchedWriteStamp.record(").size - 1
        )
        assertTrue(
            "the override path stamps with the write's own instant",
            watched.contains("WatchedWriteStamp.record( context = context, at = now )")
        )
    }

    @Test
    fun `a finish that no tracker accepted still stamps`() {
        // T4's case: offline, a failed push, or a finish recorded only locally.
        // The players' completion row is the write that always happens, so the
        // stamp cannot live only on the Simkl push.
        assertTrue(
            "the history writer stamps the first completion of a row",
            history.contains("WatchedWriteStamp.record(context)") &&
                history.contains("preserveCompletion(context, entry, dao)")
        )
        assertTrue(
            "and only when there was no completion stamp before, so the ticks " +
                "after the end of an episode cannot keep moving it",
            history.contains("if (existing?.completedAt == null) {")
        )
    }

    @Test
    fun `the stamp is a device clock, and profile scoped`() {
        val stamp = flat(source(STAMP))
        assertTrue(
            "wall clock, the same basis as the blob's updatedAt",
            stamp.contains("System.currentTimeMillis()")
        )
        assertTrue(
            "profile scoped, because each profile has its own library blob",
            stamp.contains("ProfileStorage.prefsName(context, PREFS_NAME)")
        )
        assertTrue(
            "the Simkl account's activity token is NOT this number",
            repo.contains("fun lastWatchedWriteAt(): Long") &&
                repo.contains("Not [getSavedWatchedActivityAll]")
        )
    }

    // ── F1d: no second invalidation path ───────────────────────────────

    @Test
    fun `the write path still invalidates, unchanged`() {
        val writes = flat(source(HISTORY_WRITES))
        assertTrue(
            "every watched write still drops the caches",
            writes.split("invalidateWatchedSnapshots()").size - 1 >= 7
        )
        assertFalse(
            "and the read guard is not a second invalidation",
            caughtUp.contains("invalidateWatchedSnapshots")
        )
    }

    // ── F2: the next miss is one grep away ─────────────────────────────

    @Test
    fun `the caught-up load says what it read and what it kept`() {
        assertTrue(
            "one line, gated like the local leg's",
            caughtUp.contains("if ( UPCOMING_DIAGNOSTICS ) {") &&
                caughtUp.contains("\"UPCOMING_DIAG\"")
        )
        assertTrue(
            "the blob's age",
            caughtUp.contains("\"caught-up blob source=\"") &&
                caughtUp.contains("\" ageMs=\"")
        )
        assertTrue(
            "cache or refresh",
            caughtUp.contains("\" refreshed=\" + refreshed")
        )
        assertTrue(
            "and the candidates that survived the filter, by id",
            caughtUp.contains("\" candidates=\" + caughtUp.size") &&
                caughtUp.contains("it.imdbId ?: it.tmdbId ?: it.simklId")
        )
    }

    // ── source access ───────────────────────────────────────────────────

    private val repo by lazy { flat(source(REPOSITORY)) }
    private val history by lazy { flat(source(HISTORY)) }
    private val invalidate by lazy {
        repo.substringAfter("internal suspend fun invalidateWatchedSnapshots()")
            .substringBefore("internal suspend fun clearContinueWatchingCache()")
    }
    private val stampBody by lazy {
        repo.substringAfter("internal suspend fun showLibraryStamp(")
            .substringBefore("suspend fun getPlaybackItems(")
    }
    private val caughtUp by lazy {
        flat(source(READS)).substringAfter("fun SimklRepository.getCaughtUpUnreleasedShowsImpl()")
            .substringBefore("fun SimklRepository.isShowFullyWatched(")
    }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private companion object {
        const val READS = "com/kennyb1201/kbstream/data/simkl/SimklReads.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/simkl/SimklRepository.kt"
        const val HISTORY_WRITES = "com/kennyb1201/kbstream/data/simkl/SimklHistoryWrites.kt"
        const val WATCHED_REPO = "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
        const val HISTORY = "com/kennyb1201/kbstream/data/history/PlaybackHistoryWriter.kt"
        const val STAMP = "com/kennyb1201/kbstream/data/watched/WatchedWriteStamp.kt"
    }
}
