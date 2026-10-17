package com.kennyb1201.kbstream.data.history

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-shot history root migration runs on EVERY profile, off the launch
 * path, once, and its cloud half runs in one order - and every one of those is
 * a wiring fact that compiles perfectly while being wrong:
 *
 *  - a pass that opens only the ACTIVE profile's database leaves every other
 *    profile's rows noisy, and a profile switch mid-pass retires the connection
 *    under it;
 *  - a pass that reads the launcher's own query misses every row that never
 *    saved a position, which is exactly the row an old build wrote when the
 *    viewer started an episode from the beginning and stopped;
 *  - the local delete and insert are one transaction, and the cloud half has to
 *    follow the local write: tombstones filed before the rows are canonical
 *    would delete the row that is staying;
 *  - and the pass may only retire itself once nothing was left unresolved, or
 *    one offline launch means the stale ids are never revisited.
 *
 * Read from the source, the way this repo pins the shapes it cannot launch: the
 * runner needs an Application, a TMDB key and a profile on disk.
 */
class HistoryRootMigrationWiringContractTest {

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

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The text of the member starting at [signature], up to the next member. */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `the migration runs from the launch, isolated and off the critical path`() {
        val app = read("com/kennyb1201/kbstream/MainApplication.kt")
        assertTrue(
            "MainApplication must start the pass, or no device that already has history rows " +
                "ever gets them re-rooted",
            app.contains("HistoryRootMigration") && app.contains(".runOnce(this@MainApplication)")
        )
        assertTrue(
            "and it must be isolated like every other launch step, so a broken migration cannot " +
                "take the process down before the first frame",
            app.contains("\"app_create_history_root_migration\"")
        )
    }

    @Test
    fun `every profile is repaired through its own by-name handle`() {
        val src = read(MIGRATION)
        val body = functionBody(src, "    suspend fun runOnce(context: Context): Boolean")
        assertTrue(
            "the pass must cover the app's profile list",
            src.contains("ProfileManager.profileIds(")
        )
        assertTrue(
            "plus the resolvable active id, which exists before ProfileManager.init has run",
            src.contains("ProfileManager.resolveActiveId(")
        )
        assertTrue(
            "each profile's history is opened by NAME, so a profile switch mid-pass cannot " +
                "retire the connection under the pass and only one profile is not enough",
            body.contains("WatchHistoryDatabase.openForProfile(")
        )
        assertFalse(
            "and not through the active-profile singleton, which a profile switch rebinds",
            src.contains("getInstanceScoped(")
        )
        assertTrue(
            "the handle must be closed again: it is opened per profile, per pass",
            src.contains("db.close()")
        )
    }

    @Test
    fun `the pass reads the whole table and applies it in one transaction`() {
        val src = read(MIGRATION)
        assertTrue(
            "the rows must come from the whole table: the launcher's own read skips every row " +
                "with no saved position, and a stale root is just as wrong there",
            src.contains("dao.getAllRows()")
        )
        val apply = src.indexOf("dao.applyRootMigration(")
        val tombstone = src.indexOf("SupabaseSync.deleteHistoryRows(")
        val upload = src.indexOf("SupabaseSync.enqueueHistory(")
        assertTrue("the migration must apply its plan", apply >= 0)
        assertTrue(
            "the local rows must be canonical BEFORE the cloud half: a tombstone filed first " +
                "would delete a row this device is about to re-upload",
            apply in 1 until tombstone && tombstone < upload
        )
        assertTrue(
            "the abandoned ids must be tombstoned, or the next pull hands the stale row back, " +
                "and the canonical rows re-published",
            src.contains("plan.tombstonedIds") && src.contains("plan.writes.forEach")
        )
    }

    @Test
    fun `the pass only retires itself when nothing was left unresolved`() {
        val src = read(MIGRATION)
        val guard = src.indexOf("if (unmigrated > 0)")
        val retire = src.indexOf("markDone(appContext)")
        assertTrue("the pass must count what it could not verify", guard >= 0)
        assertTrue(
            "and leave its own gate unset when something could not be: one offline launch must " +
                "not mean the stale ids are never looked at again",
            guard < retire
        )
        assertTrue(
            "an empty profile list is not a finished migration either",
            src.contains("if (profileIds.isEmpty()) return@withContext false")
        )
        assertTrue(
            "the gate is its own prefs file, not a user-visible preference",
            src.contains("private const val KEY_DONE") && src.contains("private const val PREFS")
        )
    }

    @Test
    fun `the re-root rule has one definition, and every rewriter shares it`() {
        val rule = read(RULE_HOME)
        assertTrue(
            "the rule lives with the other id rules",
            rule.contains("internal fun rerootedEpisodeStreamId(")
        )
        assertFalse(
            "and not twice: a second copy is how the rail and the migration drift apart",
            read("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")
                .contains("internal fun rerootedEpisodeStreamId(")
        )
        assertTrue(
            "the rail's read must import it",
            read("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
                .contains("import com.kennyb1201.kbstream.data.player.rerootedEpisodeStreamId")
        )
        assertTrue(
            "the player's random hop must import it",
            read("com/kennyb1201/kbstream/ui/player/RandomEpisode.kt")
                .contains("import com.kennyb1201.kbstream.data.player.rerootedEpisodeStreamId")
        )
        assertTrue(
            "the migration itself must use it rather than its own arithmetic",
            read(MIGRATION).contains("rerootedEpisodeStreamId(")
        )
    }

    private companion object {
        const val MIGRATION =
            "com/kennyb1201/kbstream/data/history/HistoryRootMigration.kt"
        const val RULE_HOME =
            "com/kennyb1201/kbstream/data/player/EpisodeSchemeStore.kt"
    }
}
