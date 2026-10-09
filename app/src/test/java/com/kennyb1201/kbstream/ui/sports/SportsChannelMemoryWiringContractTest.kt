package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three matcher-accuracy changes are wired end to end, not just written.
 *
 * None of it is reachable from a plain unit test: the ViewModel wants an
 * Application, a live tick and ESPN data, and the hub has no Compose harness
 * here. So the wiring is read out of the source, the way the other UI contracts
 * in this tree are - which also means the next edit to these files cannot
 * quietly drop a link and leave the feature half-connected.
 */
class SportsChannelMemoryWiringContractTest {

    @Test
    fun `the correction memory is read before matching and written on a manual pick`() {
        val vm = source(VIEW_MODEL)
        assertTrue(
            "the read path: the matcher is handed the memory so a remembered channel skips the tiers",
            vm.contains("SportsChannelMemory.recall(getApplication(), key, channels)")
        )
        assertTrue(
            "and the lambda is passed into the match call",
            vm.contains("SportsChannelMatcher.matches(game, channels, programs, remembered)")
        )
        assertTrue(
            "the write path: a manual pick records BOTH teams",
            vm.contains("SportsChannelMemory.rememberPick(getApplication(), game, channelId)")
        )
        assertTrue(
            "the settings row clears it",
            vm.contains("SportsChannelMemory.clear(getApplication())")
        )
    }

    @Test
    fun `the guide synopsis is threaded into the matcher's program rows`() {
        val vm = source(VIEW_MODEL)
        assertTrue(
            "epgCandidates must select the description too, or tier 1b has nothing to read",
            vm.contains("description = row.description")
        )
    }

    @Test
    fun `picking a backup feed is what teaches the memory`() {
        val screen = source(SCREEN)
        assertTrue(
            "the sheet reports the viewer's own choice",
            screen.contains("onManualPick = { chosen -> viewModel.rememberChannel(game, chosen.id) }")
        )
        assertTrue(
            "and it is the backup tap - the feed OTHER than the matched head - that reports it",
            screen.contains("onManualPick(backup)")
        )
        assertTrue(
            "the settings panel offers the clear row",
            screen.contains("onClearMemory = viewModel::clearChannelMemory")
        )
        assertTrue(
            "and the panel draws it",
            screen.contains("ClearMemoryRow(onClear = onClearMemory)")
        )
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
    }
}
