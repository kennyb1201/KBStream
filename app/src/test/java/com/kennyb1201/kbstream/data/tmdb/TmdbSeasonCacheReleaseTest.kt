package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season payloads survive the player.
 *
 * Reported cost: watch something, back out to Home, and the Continue Watching
 * rail rebuilds - resolving every card's season/finale status from
 * [TmdbRepository.getSeasonEpisodes]. Those payloads are cached, but opening the
 * fullscreen player released the memory cache, so that rebuild re-read one
 * payload per card from disk on the path the viewer walks most.
 *
 * Keeping the cache is only defensible because it is already bounded (entry cap)
 * and expiring (12h TTL), with the disk table underneath for a cold process -
 * which is what these cases pin, together with the release policy itself: one
 * cache stopped being dropped, and every other one still is.
 *
 * A source contract rather than a live cache: the repository is a process
 * singleton constructed from a Context over Retrofit/Moshi/Room, so the policy
 * is pinned where it is decided.
 */
class TmdbSeasonCacheReleaseTest {

    private val repository = source(REPOSITORY)

    // ── T1: what releaseCaches() gives back ────────────────────────────────

    @Test
    fun `opening the player no longer empties the season payload cache`() {
        val release = section(repository, "override fun releaseCaches() {")

        assertFalse(
            "the player-return path re-reads one payload per Continue Watching " +
                "card from disk whenever this line comes back",
            release.contains("seasonEpisodesCache.clear()")
        )
    }

    @Test
    fun `every other cache it cleared is still cleared`() {
        val release = section(repository, "override fun releaseCaches() {")

        listOf(
            "detailCache.clear()",
            "railDetailCache.clear()",
            "imdbResolutionMemoryCache.clear()",
            "tmdbResolutionMemoryCache.clear()",
            "reviewsCache.clear()",
            "entityLogoMemory.clear()"
        ).forEach { clear ->
            assertTrue(
                "releaseCaches() still gives $clear back to a tight heap",
                release.contains(clear)
            )
        }
    }

    // ── the reason keeping it is safe ──────────────────────────────────────

    @Test
    fun `the cache kept across playback is still bounded and still expires`() {
        assertTrue(
            "an entry older than the TTL is not served",
            repository.contains(
                "if (cached != null && now - cached.first < seasonEpisodesCacheTtlMs)"
            )
        )
        assertTrue(
            "and the TTL is still the twelve hours the rail's payloads were " +
                "measured against",
            repository.contains("private val seasonEpisodesCacheTtlMs = 12L * 60L * 60L * 1000L")
        )
        assertTrue(
            "the entry cap is what keeps a surviving cache from growing past " +
                "what browsing already allowed it",
            repository.contains(
                "evictOldest(seasonEpisodesCache, { it.first }, MAX_SEASON_ENTRIES)"
            )
        )
    }

    @Test
    fun `diagnostics can still see how full it is`() {
        assertTrue(
            "the exemption is only safe to reason about if its size is reportable",
            repository.contains("season=\${seasonEpisodesCache.size}/\$MAX_SEASON_ENTRIES")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** From [landmark] to the next companion constant, which follows the policy. */
    private fun section(src: String, landmark: String): String {
        val start = src.indexOf(landmark)
        assertTrue("missing landmark: $landmark", start >= 0)
        val rest = src.substring(start + landmark.length)
        val end = rest.indexOf("private fun pruneImdbCacheOnce()")
        assertTrue("missing the end of releaseCaches()", end > 0)
        return rest.substring(0, end)
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

    private companion object {
        const val REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
