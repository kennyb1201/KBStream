package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening the in-player guide on a long IPTV lineup must not scan it.
 *
 * Sentry reported the app "not responding" on the onn. 4K Plus running 0.6.109
 * (ANR group 7797702774): the main thread sat in
 * `LiveChannelZapRegistry.indexOfChannel`, reached from the guide's long-press
 * opener through `focusChannelGuideCurrent` and `currentZapChannel`. The lineup
 * is a whole IPTV group, so that scan is the wrong shape for the open path, and
 * the identity paint built a row object per channel on the same thread before
 * the overlay could take input.
 *
 * These are wiring points inside an activity and an object, so a device cannot
 * be avoided; the behaviour of the pieces themselves is pinned by
 * [LiveChannelZapIndexTest] and [ChannelGuideRowSourceTest].
 */
class PlayerGuideOpenAnrContractTest {

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

    /** The text of the function starting at [signature] up to the next member. */
    private fun functionBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `identity lookups read a published index`() {
        val lookup = functionBody(REGISTRY, "fun indexOfChannel(channelId: String): Int =")
        assertTrue(
            "the lineup index is what a lookup answers from",
            lookup.contains("channelIndex[channelId]")
        )
        assertFalse(
            "a lookup must not walk the lineup",
            lookup.contains("indexOfFirst") || lookup.contains("indexOf(")
        )

        val publish = functionBody(REGISTRY, "private fun applyActiveGroup() {")
        assertTrue(
            "publishing a group must build the id index",
            publish.contains("channelIndex = firstIndexBy(")
        )
        assertTrue(
            "and the stream-url fallback index",
            publish.contains("streamUrlIndex = firstIndexBy(")
        )
    }

    @Test
    fun `the player reads rows and channels without materialising the lineup`() {
        val publish = functionBody(
            PLAYER,
            "private fun publishChannelGuideRows(\n        programs: Map<String, ChannelNowNext>,\n        loading: Boolean\n    ) {"
        )
        assertTrue(
            "rows must be built per visible position",
            publish.contains("ChannelGuideRowSource(")
        )
        assertFalse(
            "the identity pass must not map the whole lineup into rows",
            publish.contains("lineupChannels().mapIndexed")
        )

        val lineup = functionBody(PLAYER, "private fun lineupChannels()")
        assertTrue(
            "the published list is handed out as-is",
            lineup.contains("LiveChannelZapRegistry.snapshot()")
        )
        assertFalse(
            "rebuilding an equal list per call is a full allocation",
            lineup.contains("mapNotNull")
        )
    }

    @Test
    fun `the guide parks focus through the index`() {
        val focus = functionBody(PLAYER, "private fun focusChannelGuideCurrent() {")
        assertTrue(
            "the open path asks the registry where the channel is",
            focus.contains("LiveChannelZapRegistry::indexOfChannel")
        )
        assertFalse(
            "not a scan of the lineup",
            focus.contains("indexOfFirst")
        )
    }

    private companion object {
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val REGISTRY = "com/kennyb1201/kbstream/data/iptv/LiveChannelZapRegistry.kt"
    }
}
