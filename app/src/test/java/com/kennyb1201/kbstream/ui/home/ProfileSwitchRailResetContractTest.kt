package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a profile switch does to the rails, pinned by reading the source.
 *
 * This module carries no Compose harness, and these particular failures are all
 * invisible to one anyway: they are races and orderings on a TV remote press.
 * Every rule here is one that showed up as "the profile I just left is still on
 * screen for a couple of seconds", which is exactly the shape that no unit test
 * of a pure function can catch and no single-file contract can attribute - so
 * the reset is pinned as a whole, per rail family, in the switch turn.
 *
 * The families are the six things Home draws: the addon/catalog rails, Continue
 * Watching, Upcoming, the KB collections, the browse rows mirrored from Search,
 * and the hero. Each comes from a different place (addon cache, watch history,
 * Simkl + history, the profile blob, profile-scoped prefs), which is why one of
 * them being left out is easy - Upcoming was, because it is BUILT from Continue
 * Watching but published through a flow of its own.
 */
class ProfileSwitchRailResetContractTest {

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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The block between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val KB_HOME_VM = "com/kennyb1201/kbstream/ui/kb/KBHomeViewModel.kt"
    }

    private fun homeVm(): String = source(HOME_VM)

    /** The profile-switch collector, from its declaration to the next observer. */
    private fun switchHandler(): String =
        between(homeVm(), "private fun observeProfileSwitches()", "private fun observeAddonChanges()")

    // ------------------------------------------- every family, in switch turn --

    @Test
    fun `the switch empties every rail family before it rebuilds anything`() {
        val handler = switchHandler()
        listOf(
            "addon and catalog rails" to "_rails.value = emptyList()",
            "Continue Watching" to "_upNext.value = emptyList()",
            "Upcoming" to "_upcomingSchedule.value = emptyList()",
            "the hero" to "_heroMeta.value = null"
        ).forEach { (family, clear) ->
            assertTrue(
                "$family is not cleared in the switch turn, so it stays on screen " +
                    "showing the profile being left",
                handler.contains(clear)
            )
        }
        // ...and the clears come before the rebuild, so no frame can repaint the
        // outgoing profile's rows in between.
        val lastClear = handler.indexOf("_upcomingSchedule.value = emptyList()")
        val rebuild = handler.indexOf("rebuildRailsForActiveProfile()")
        assertTrue("the switch does not rebuild the rails at all", rebuild > 0)
        assertTrue(
            "the rails are rebuilt before the outgoing profile's rows are cleared",
            lastClear in 1 until rebuild
        )
    }

    @Test
    fun `the switch never goes through the manual refresh, which drops the catalog cache`() {
        val handler = switchHandler()
        assertFalse(
            "refreshAllHomeData() clears the catalog cache, which is the seconds of " +
                "Home holding only its locally-known rails",
            handler.contains("refreshAllHomeData()")
        )
        assertTrue(
            "the switch has to rebuild the rails for the profile that just became active",
            handler.contains("rebuildRailsForActiveProfile()")
        )
    }

    @Test
    fun `a build from before the switch cannot publish over the cleared state`() {
        // Upcoming is built FROM upNext but published through a flow of its own,
        // and that build reads Simkl, this profile's history and one
        // next-air-date lookup per show. Without the epoch, the build that was
        // already running for the profile being left lands on top of the
        // cleared rail a moment later - "it corrects itself" the slow way.
        val handler = switchHandler()
        assertTrue(
            "the switch must invalidate a build that started before it",
            handler.contains("upcomingBuildEpoch += 1")
        )
        val observer = between(
            homeVm(),
            "private fun observeUpcomingSchedule()",
            "private val _isLoading"
        )
        assertTrue(
            "the Upcoming build is not guarded by the epoch",
            squash(observer).contains("if (epochAtStart == upcomingBuildEpoch) {")
        )
        assertTrue(
            "the epoch is not captured when the build starts",
            observer.contains("val epochAtStart = upcomingBuildEpoch")
        )
        assertTrue(
            "the observer has to be started eagerly, as the derived flow was",
            squash(homeVm()).contains("observeUpNext() observeUpcomingSchedule()")
        )
    }

    @Test
    fun `Upcoming is published, not derived, so it can be cleared`() {
        // A derived flow (stateIn) holds its last built list until a new one
        // lands and cannot be emptied from outside, which is the whole bug.
        val vm = homeVm()
        assertTrue(
            "Upcoming has no flow of its own to empty",
            vm.contains("MutableStateFlow<List<UpcomingEpisode>>(emptyList())")
        )
        assertFalse(
            "a stateIn-derived flow cannot be cleared on a switch",
            vm.contains("stateIn(")
        )
    }

    // --------------------------------------------- the warm-cache rail rebuild --

    @Test
    fun `the switch reuses the warm catalog cache, and the manual refresh still does not`() {
        val vm = homeVm()
        val rebuild = between(
            vm,
            "private fun rebuildRailsForActiveProfile()",
            "fun refreshRailsOnly()"
        )
        assertTrue(
            "the switch rebuild has to keep the cached catalog pages",
            rebuild.contains("clearCatalogCache = false")
        )
        assertTrue(
            "the rails themselves still have to be rebuilt",
            rebuild.contains("forceRefresh = true")
        )
        // The manual refresh is the opposite case on purpose: it is what the
        // viewer presses when the rows are stale, so it must refetch.
        val manual = between(vm, "fun refreshAllHomeData()", "private fun rebuildRailsForActiveProfile()")
        assertFalse(
            "the manual refresh must not serve the cache it exists to bypass",
            manual.contains("clearCatalogCache = false")
        )
    }

    @Test
    fun `a profile switch drops the outgoing collections and keeps the local layout`() {
        val vm = source(KB_HOME_VM)
        val handler = between(vm, "private fun observeProfileSwitches()", "private fun resetForProfileSwitch()")
        assertTrue(
            "the switch has to reset before it reloads",
            handler.contains("resetForProfileSwitch()")
        )
        assertFalse(
            "a bare UiState() also throws away the arrangement and the browse rows, " +
                "which are the incoming profile's own - and local",
            handler.contains("_state.value = UiState()")
        )
        // The reset is the last thing in the file, so its block runs to the end
        // (its only sibling after it is the class's closing brace).
        val reset = vm.substring(vm.indexOf("private fun resetForProfileSwitch()"))
        assertTrue(
            "the collections (the profile-scoped rail content) must not be carried over",
            reset.contains("_state.value = UiState(")
        )
        assertFalse(
            "the reset must not publish the outgoing profile's collections",
            reset.contains("collections =")
        )
        assertTrue(
            "the incoming profile's own layout is read in the same turn",
            reset.contains("KBHomeOrderPrefs.get(context)")
        )
        assertTrue(
            "and its browse rows with it",
            reset.contains("BrowseHomeShortcuts.list(context)")
        )
    }
}
