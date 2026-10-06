package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player guide fills itself in when it opens before the guide screen had
 * matched the lineup to an imported guide.
 *
 * Reported: "if I go into guide and click into a channel right away before
 * [the] guide finishes showing every channel and then hold down select to open
 * the in-player guide, it never populates ... I have to wait like a minute
 * before clicking into a channel for the guide to be populated."
 *
 * The overlay is launched from the guide screen's lineup, whose `epgChannelId`
 * is only resolved once the guide screen has matched the playlist against an
 * imported guide. Click into a channel before that import lands and every
 * entry is unmatched; [planGuideQueries] then skips them all, so the overlay
 * read nothing and its one-shot paint stayed blank for as long as it was open -
 * even after the data arrived a minute later.
 *
 * Three pieces fix it, and dropping any one brings the blank overlay back:
 *  - [LiveChannelZapRegistry.ZapChannel] carries the channel's own guide
 *    identity (`tvgId`/`tvgName`), because the match has to be resolvable from
 *    the entry alone;
 *  - the player resolves any missing match itself and re-reads while the
 *    overlay is open, keyed on the guide import revision;
 *  - the guide screen still publishes those identity fields.
 *
 * A missing call here compiles cleanly and is invisible until someone opens the
 * guide too early on a television, which is why this reads the sources (see
 * PlayerGuideWriteGateContractTest).
 */
class PlayerGuideMatchFallbackContractTest {

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

    private companion object {
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val GUIDE_SCREEN = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val REGISTRY = "com/kennyb1201/kbstream/data/iptv/LiveChannelZapRegistry.kt"
    }

    @Test
    fun `the lineup entry carries its own guide identity`() {
        val registry = source(REGISTRY)
        assertTrue(
            "ZapChannel must carry tvgId so an unmatched entry can be resolved",
            registry.contains("val tvgId: String? = null")
        )
        assertTrue(
            "ZapChannel must carry tvgName so an unmatched entry can be resolved",
            registry.contains("val tvgName: String? = null")
        )
        val guideScreen = source(GUIDE_SCREEN)
        assertTrue(
            "the guide screen must publish tvgId into the lineup",
            guideScreen.contains("tvgId = item.channel.tvgId")
        )
        assertTrue(
            "the guide screen must publish tvgName into the lineup",
            guideScreen.contains("tvgName = item.channel.tvgName")
        )
    }

    @Test
    fun `the player resolves a missing guide match through the repository`() {
        val player = source(PLAYER)
        assertTrue(
            "the player must detect entries needing a match",
            player.contains("needsGuideMatch(channel)")
        )
        assertTrue(
            "the player must build the match query from the entry, once per " +
                "published guide source - an entry matched in a secondary guide " +
                "can never be resolved against the primary one",
            player.contains("guideMatchQueryForSource(channel, source)")
        )
        assertTrue(
            "the per-source resolve must walk every configured source",
            player.contains("pending.flatMap { channel ->") &&
                player.contains("guideSourcesOf(channel)")
        )
        assertTrue(
            "the player must resolve matches via the repository",
            player.contains(".resolveGuideChannelIds(")
        )
        assertTrue(
            "the resolved match must feed both the query plan and the row lookup",
            player.contains("channel.copy(epgChannelId = guideChannelIdFor(channel))") &&
                player.contains("guideChannelIdFor(channel)")
        )
    }

    @Test
    fun `the player re-reads the guide while the overlay is open`() {
        val player = source(PLAYER)
        assertTrue(
            "opening the guide must start the watcher",
            player.contains("startChannelGuideWatcher()")
        )
        assertTrue(
            "closing the guide must stop the watcher",
            player.contains("channelGuideWatchJob?.cancel()")
        )
        assertTrue(
            "the watcher must key on the guide import revision",
            player.contains("GuideRevision.total()")
        )
        assertTrue(
            "an empty read must end the identity pass rather than load forever",
            player.contains("publishChannelGuideRows(programs = emptyMap(), loading = false)")
        )
    }
}
