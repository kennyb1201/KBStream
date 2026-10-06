package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player guide switches groups with LEFT/RIGHT, and reads every
 * configured guide source while it is up.
 *
 * Requested: "make it where the in player iptv guide we can change groups with
 * left and right dpad press. also the in player guide still says alot of no
 * guide data even though the regular guide in guide screen is fully populated".
 *
 * Both halves are wiring, so a unit test cannot press a remote on this host.
 * What is checkable is that the pieces exist and are joined up:
 *
 *  - the guide screen publishes EVERY group and every configured guide source,
 *    because the registry used to carry only the group it was launched from and
 *    only the primary source URL;
 *  - the player resolves LEFT/RIGHT to a group step while the overlay is open,
 *    ahead of the scrub (the guide can be up with the controls hidden, and a
 *    group change must never seek the video);
 *  - the player asks every published source about a channel, in the overlay and
 *    in the zap banner, because programs are stored under whichever guide
 *    matched the channel.
 */
class PlayerGuideGroupSwitchContractTest {

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
        const val PROGRAMS = "com/kennyb1201/kbstream/ui/player/ChannelGuidePrograms.kt"
    }

    @Test
    fun `the registry can hold and step through groups`() {
        val registry = source(REGISTRY)
        assertTrue(
            "the registry must carry every group, not just the browsed one",
            registry.contains("data class ZapGroup(")
        )
        assertTrue(
            "the guide screen and the player both publish through setGroups",
            registry.contains("fun setGroups(")
        )
        assertTrue(
            "LEFT/RIGHT need a step that clamps and reports whether it moved",
            registry.contains("fun offsetGroup(delta: Int): Boolean")
        )
        assertTrue(
            "the header needs to say which group of how many is browsed",
            registry.contains("fun groupPosition(): Pair<Int, Int>?")
        )
        assertTrue(
            "the active group's channels must become the zap lineup",
            registry.contains("private fun applyActiveGroup()")
        )
    }

    @Test
    fun `the guide screen publishes every group and source`() {
        val guideScreen = source(GUIDE_SCREEN)
        assertTrue(
            "the guide screen must publish all groups, or there is nothing to step to",
            guideScreen.contains("LiveChannelZapRegistry.setGroups(") &&
                guideScreen.contains("groups = groups.map { label ->")
        )
        assertTrue(
            "the selected group must still be the one browsed",
            guideScreen.contains("selected = selectedGroup")
        )
        assertTrue(
            "the configured guide sources must be published, primary first",
            guideScreen.contains("val guideSourceUrls = remember(epgUrl, extraEpgUrls)") &&
                guideScreen.contains("epgUrls = guideSourceUrls")
        )
        assertTrue(
            "the group switch must re-key the publish so the player sees new groups",
            guideScreen.contains("groups,\n        unhiddenChannels,")
        )
    }

    @Test
    fun `the player steps the guide group on LEFT and RIGHT`() {
        val player = source(PLAYER)
        assertTrue(
            "there must be a group step",
            player.contains("private fun switchChannelGuideGroup(direction: Int) {")
        )
        assertTrue(
            "the step must go through the registry's clamped move",
            player.contains("LiveChannelZapRegistry.offsetGroup(direction)")
        )
        assertTrue(
            "the overlay must repaint and re-read for the new group",
            player.contains("publishChannelGuideRows(programs = emptyMap(), loading = true)") &&
                player.contains("loadChannelGuidePrograms()")
        )
        assertTrue(
            "LEFT/RIGHT must be resolved while the guide is open, before the scrub",
            player.contains("if (isGuideShowing && horizontal) {") &&
                player.contains("switchChannelGuideGroup(")
        )
        assertTrue(
            "the header must name the browsed group and its position",
            player.contains("private fun channelGuideTitleText(): String") &&
                player.contains("channelGuideTitle?.text = channelGuideTitleText()")
        )
    }

    @Test
    fun `the player reads every published guide source`() {
        val programs = source(PROGRAMS)
        assertTrue(
            "the query planner must walk the published source list",
            programs.contains("internal fun guideSourcesOf(") &&
                programs.contains("for (sourceUrl in guideSourcesOf(channel))")
        )
        assertTrue(
            "a missing match must be resolvable against any source",
            programs.contains("internal fun guideMatchQueriesFor(") &&
                programs.contains("internal fun guideMatchQueryForSource(")
        )
        val player = source(PLAYER)
        assertTrue(
            "the overlay's match resolve must try every source",
            player.contains("guideSourcesOf(channel)")
        )
        assertTrue(
            "the zap banner must read every source and merge, not just the primary",
            player.contains("private suspend fun loadZapEpg(epgSources: List<String>, epgChannelId: String)") &&
                player.contains("for (source in epgSources) {")
        )
    }
}
