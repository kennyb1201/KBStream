package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state batch from the audit: episode-scheme anchoring, the per-key MDBList
 * budget, targetSdk 36, and the two P2s that ride along.
 *
 * Each item is wiring that a pure unit test cannot see end to end - an Activity
 * handoff, a process-wide budget counter, a Gradle manifest value - so the
 * shape is pinned here at the source. The arithmetic itself is covered by
 * [com.kennyb1201.kbstream.data.player.EpisodeSchemeFileCursorTest].
 */
class PlayerRelayBatchBContractTest {

    private fun readSource(path: String): String {
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

    /** A tracked repo file (a .kts, e.g.), found by walking up from the module dir. */
    private fun readProjectFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("project file not found: $relative")
    }

    // ── P1-4: the players anchor on the file cursor ────────────────────────

    @Test
    fun `the scheme exposes the cursor-anchored answers`() {
        val scheme = readSource(EPISODE_SCHEME)
        assertTrue(scheme.contains("fun episodesOfFileClamped(fileEp: Int, maxEpisodes: Int?)"))
        assertTrue(scheme.contains("fun labelForFile(fileEp: Int): Int"))
    }

    @Test
    fun `all three players use the cursor, not the session label`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val src = readSource(path)
            assertTrue(
                "$path must derive the covered set from the cursor",
                src.contains("episodesOfFileClamped(")
            )
            assertTrue(
                "$path must derive the next label from the next file",
                src.contains("bingeScheme.labelForFile(")
            )
            // The old label-plus-factor arithmetic must be gone from the label
            // path (it survives only in nextFileEpisodeFor, which asks a
            // different question and is fine).
            assertTrue(
                "$path still adds the factor to the session label",
                !src.contains("bingeScheme.advance(fileE, e).second") &&
                    !src.contains("bingeScheme.advance(fileEpisode, showEpisode).second")
            )
        }
    }

    // ── P1-5: the MDBList budget is per API key ────────────────────────────

    @Test
    fun `the MDBList budget is scoped to the request's own key`() {
        val mdb = readSource(MDBLIST)
        assertTrue(
            "a per-key record must exist",
            mdb.contains("private class KeyBudget")
        )
        assertTrue(
            "and be resolved from the request's apikey",
            mdb.contains("private fun budgetFor(request: Request)")
        )
        assertTrue(
            "with the raw key hashed, never stored",
            mdb.contains("private fun keyTag(apiKey: String): String")
        )
        assertTrue(
            "the day/count prefs are keyed per budget tag",
            mdb.contains("private fun budgetDayKey(tag: String)") &&
                mdb.contains("private fun budgetCountKey(tag: String)")
        )
        // The old process-wide fields must be gone.
        assertTrue(
            "a process-wide counter would still throttle across profiles",
            !mdb.contains("private var requestsDay") &&
                !mdb.contains("private val requestsToday")
        )
    }

    @Test
    fun `the MDBList disk keys hash the key rather than embed it`() {
        val mdb = readSource(MDBLIST)
        assertTrue(
            "the snapshot disk key must not contain the raw apiKey",
            mdb.contains("\"mdblist:snapshot:\${keyTag(apiKey)}\"")
        )
        assertTrue(
            "nor the playback disk key",
            mdb.contains("\"mdblist:playback:\${keyTag(apiKey)}\"")
        )
        assertTrue(
            "nor the ratings disk key",
            mdb.contains("\"mdblist:ratings:\${keyTag(apiKey)}:")
        )
    }

    // ── P1-6: the app targets API 36 ───────────────────────────────────────

    @Test
    fun `the app targets API 36`() {
        val gradle = readProjectFile("app/build.gradle.kts")
        assertTrue(
            "Play blocks phone/tablet updates below API 36; targetSdk must be 36",
            gradle.contains("targetSdk = 36")
        )
        assertTrue(
            "and compileSdk must remain high enough to build against it",
            gradle.contains("compileSdk = 37")
        )
    }

    // ── P2: a stall supersedes a stale failure, and the store is locked ────

    @Test
    fun `a stall after a failure supersedes it instead of re-arming it`() {
        val mem = readSource(SOURCE_MEMORY)
        assertTrue(
            "the open verdict needs its own timestamp",
            mem.contains("val openAtMs: Long = 0L")
        )
        assertTrue(
            "a stall must not refresh the open verdict's time",
            mem.contains("openAtMs = previous?.let { openVerdictAtMs(it) } ?: 0L")
        )
        assertTrue(
            "and a later stall must drop the addon from the failed set",
            mem.contains("private fun stallSupersedesOpen(outcome: Outcome): Boolean") &&
                mem.contains("!stallSupersedesOpen(it)")
        )
        assertTrue(
            "the open set must age off its own timestamp",
            mem.contains("entry.addons.filterValues { isFresh(openVerdictAtMs(it)) }")
        )
    }

    @Test
    fun `the memory store's read-modify-write is synchronized`() {
        val mem = readSource(SOURCE_MEMORY)
        assertTrue(
            "update() must serialize its read-mutate-write",
            mem.contains("@Synchronized\n    private fun update(")
        )
        assertTrue(
            "and so must forget()",
            mem.contains("@Synchronized\n    fun forget(")
        )
    }

    // ── P2: the played-link invalidation handle reaches every play path ────

    @Test
    fun `every auto-play and manual-pick path stamps the link cache key`() {
        val main = readSource(MAIN)
        assertEquals(
            "the cache-hit and freshly-resolved auto-play paths must both stamp it",
            2,
            main.split(".copy(linkCacheKey = pending.streamKey)").size - 1
        )
        assertTrue(
            "and the manual pick must carry the same handle",
            main.contains(".copy(linkCacheKey = streamKey)")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        private const val EXTERNAL =
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        private const val EPISODE_SCHEME =
            "com/kennyb1201/kbstream/data/player/EpisodeScheme.kt"
        private const val MDBLIST =
            "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
        private const val SOURCE_MEMORY =
            "com/kennyb1201/kbstream/data/player/SourceAddonMemory.kt"
        private const val MAIN =
            "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
