package com.kennyb1201.kbstream.data.iptv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A channel with no guide match must not be re-matched on every lineup query.
 *
 * The positive cache is keyed by the source URL, so a "no source matched" answer
 * had nowhere to live and the whole name/callsign matcher re-ran for every
 * unmatched channel. These are the wiring pins for the separate guide-set key.
 */
class EpgNegativeMatchCacheContractTest {

    private val sourceRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String = File(sourceRoot, path).readText()

    private val repository by lazy {
        source("com/kennyb1201/kbstream/data/iptv/IptvRepository.kt")
    }
    private val dao by lazy {
        source("com/kennyb1201/kbstream/data/iptv/IptvDao.kt")
    }

    @Test
    fun `a negative is stored under its own guide-set key, never a source URL`() {
        assertTrue(
            repository.contains("private fun negativeMatchKey(guideUrls: List<String>): String") &&
                repository.contains("private val NEGATIVE_MATCH_NONCE = UUID.randomUUID().toString()")
        )
        assertTrue(
            "the nonce makes a previous process's row inert after a guide reimport",
            repository.contains("GuideRevision.of(it)")
        )
        assertTrue(
            "the resolution pass writes the no-match row",
            repository.contains("epgUrl = negativeKey") &&
                repository.contains("matchType = EpgMatchType.NO_MATCH.name")
        )
        assertTrue(
            "and a cached negative is read as an answer, not as a positive source match",
            repository.contains("cachedNegative.containsKey(channel.id)")
        )
    }

    @Test
    fun `both lineup reads consult the negative cache`() {
        assertTrue(
            "the guide lineup and the program window both build the same key",
            repository.split("val negativeKey = negativeMatchKey(").size - 1 == 2
        )
        assertTrue(
            "both pass the loaded negative map into the resolver",
            repository.split("cachedNegative = cachedNegative").size - 1 == 2
        )
    }

    @Test
    fun `stale guide-set rows are pruned without dropping the current answer`() {
        assertTrue(
            dao.contains("deleteStalePlaylistEpgNegativeMatches") &&
                dao.contains("epgUrl LIKE 'no-match:%'") &&
                dao.contains("AND epgUrl != :keepKey")
        )
        assertTrue(
            repository.contains(
                "dao.deleteStalePlaylistEpgNegativeMatches(playlistUrl, negativeKey)"
            )
        )
    }
}
