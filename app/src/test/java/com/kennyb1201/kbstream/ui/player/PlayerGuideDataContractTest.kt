package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player guide's roads to guide data, all of which have to exist for a
 * channel the guide screen has not matched yet.
 *
 * Reported: "the inplayer guide is still saying a lot of no guide data but the
 * regular guide is fully populated." Two surfaces can say that without any one
 * of them being obviously wrong:
 *
 *  - the guide screen matches channels in batches of 80 as they scroll past, so
 *    most of a large lineup arrives at the player with no `epgChannelId` at all.
 *    A surface that only reads the PUBLISHED match therefore reports "No guide
 *    data" for every channel the guide screen has not reached while the guide
 *    screen - which matches each channel it is asked about - is fully populated.
 *    The overlay's rows resolved that themselves; the zap banner and the
 *    overlay's own program block did not, so they kept saying it forever.
 *  - the resolution has to be able to MATCH what the guide screen matched. Its
 *    matcher takes the playlist's `tvg-id` and its provider id
 *    (`channel-id`/`id`/`cuid`); a player that only fed it `tvg-id` could not
 *    resolve a playlist whose ids live in the other field.
 *  - and it has to survive on a real heap. The guide screen's repository holds
 *    every channel of the guide plus two lookup maps in memory for the session,
 *    and the player is launched from that screen, which stays composed under
 *    it: a second repository means a second copy of the same guide, built later
 *    under a heap the first one has filled. A resolution killed there leaves
 *    every row of the overlay reading "No guide data".
 *
 * Source-level on purpose: each piece is a call away from silently disappearing,
 * and a television is the only place the loss shows up (see
 * PlayerGuideWriteGateContractTest for the same reasoning).
 */
class PlayerGuideDataContractTest {

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
        return file.readText()
    }

    /** Whitespace-insensitive, so a re-indent cannot break a pin. */
    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private companion object {
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val PROGRAMS = "com/kennyb1201/kbstream/ui/player/ChannelGuidePrograms.kt"
        const val GUIDE_SCREEN = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val REGISTRY = "com/kennyb1201/kbstream/data/iptv/LiveChannelZapRegistry.kt"
    }

    @Test
    fun `the lineup entry carries the provider id the guide screen matches on`() {
        val registry = source(REGISTRY)
        assertTrue(
            "ZapChannel must carry providerChannelId, or a playlist whose ids live " +
                "there cannot be resolved from the player at all",
            registry.contains("val providerChannelId: String? = null")
        )
        assertTrue(
            "the guide screen must publish providerChannelId into the lineup",
            source(GUIDE_SCREEN).contains("providerChannelId = item.channel.providerChannelId")
        )
    }

    @Test
    fun `the player's matcher is fed the same ids the guide screen feeds it`() {
        val programs = squash(source(PROGRAMS))
        assertTrue(
            "guideMatchQueryForSource must offer the provider id alongside tvg-id, the " +
                "way findBestMatchMulti does",
            programs.contains(
                "idCandidates = listOf(channel.tvgId, channel.providerChannelId)"
            )
        )
        // ...and must not skip an entry that has ONLY the provider id.
        assertTrue(
            "a provider id alone has to be identity enough to resolve with",
            programs.contains(
                "channel.tvgId.isNullOrBlank() && channel.providerChannelId.isNullOrBlank()"
            )
        )
    }

    @Test
    fun `the banner resolves a missing match before it reports no guide`() {
        val player = source(PLAYER)
        assertTrue(
            "the zap banner and the overlay's program block must resolve a missing " +
                "guide match themselves - the overlay's rows do",
            player.contains("resolveMissingGuideMatches(listOf(channel))")
        )
        // The resolution has to sit in resolveZapEpg, the one path both surfaces
        // read through; anywhere else and one of them keeps saying it.
        val resolveZapEpg = player.substringAfter("private suspend fun resolveZapEpg(")
            .substringBefore("private suspend fun loadZapEpg(")
        assertTrue(
            "resolveZapEpg must be where the missing match is resolved",
            resolveZapEpg.contains("resolveMissingGuideMatches(listOf(channel))")
        )
    }

    @Test
    fun `a stale empty snapshot may not stand in for a just-resolved match`() {
        val resolveZapEpg = source(PLAYER)
            .substringAfter("private suspend fun resolveZapEpg(")
            .substringBefore("private suspend fun loadZapEpg(")
        assertTrue(
            "an empty now/next cached before the match was known is not an answer " +
                "about this channel, so it has to be treated as a miss",
            squash(resolveZapEpg).contains("!(justResolved && cached.now == null)")
        )
    }

    @Test
    fun `the banner and the program block share one cache entry per channel`() {
        val banner = source(PLAYER)
            .substringAfter("private fun showZapBanner(")
            .substringBefore("private suspend fun resolveZapEpg(")
        assertTrue(
            "the banner must read and write the same cache key the block does",
            banner.contains("zapEpgCacheKey(channel)")
        )
        assertFalse(
            "the banner used to key the cache off the primary source alone, so its " +
                "entries and the block's were two different sets",
            banner.contains("channel.channelId + \"|\"")
        )
    }

    @Test
    fun `a landing import drops the snapshots taken before it`() {
        val player = source(PLAYER)
        val revisionBranch = player.substringAfter("val revision = GuideRevision.total()")
            .substringBefore("guideResolveRevision = revision")
        assertTrue(
            "a successful import is exactly what an empty now/next was missing, so " +
                "the cached snapshots have to go with the re-resolve",
            revisionBranch.contains("zapEpgCache.clear()")
        )
    }

    @Test
    fun `the player resolves against the guide screen's repository, not its own copy`() {
        val player = source(PLAYER)
        assertTrue(
            "the player must use the shared repository so its resolution reuses the " +
                "guide screen's snapshot instead of building a second copy of the guide",
            player.contains("IptvRepository.shared(applicationContext)")
        )
        assertFalse(
            "a private IptvRepository in the player doubles the guide's in-memory footprint",
            player.contains("IptvRepository(applicationContext)")
        )
        assertTrue(
            "the guide screen's view model has to share it too, or there is nothing " +
                "to share",
            source("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")
                .contains("IptvRepository.shared(app)")
        )
        assertTrue(
            "the background refresh must write through the shared instance, so its " +
                "import invalidates the caches being read",
            source("com/kennyb1201/kbstream/data/iptv/EpgRefreshWorker.kt")
                .contains("IptvRepository.shared(applicationContext)")
        )
    }
}
