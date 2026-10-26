package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both Home badges obey the sibling-ownership rule, not just the eye.
 *
 * The rule (already applied to the eye): a title another profile positively
 * owns draws no badge here, however the marker resolved - the tracker feeds are
 * per ACCOUNT and a TV's history can carry rows from before profiles existed.
 * The completed checkmark was published straight from the same preload cache,
 * unfiltered, so the eye's fix left its twin in place.
 *
 * Read from the source: the resolution needs a device and two profiles.
 */
class HomeWatchedBadgeOwnershipContractTest {

    private val viewModel = normalized(stripComments(source(VIEW_MODEL)))

    @Test
    fun `the completed badge is filtered like the eye on refresh`() {
        val refresh = viewModel.substringAfter("siblingOwnedMarkerIds = siblingOwnedIds")
            .substringBefore("_partialWatchedKeys.value =")
        assertTrue(
            "the completed set must drop keys another profile positively owns",
            refresh.contains("_watchedKeys.value =") &&
                refresh.contains("filterNot { key -> keyOwnedByAnotherProfile(key) }")
        )
        assertTrue(
            "the ownership ids must be resolved BEFORE the completed set is published, " +
                "or the filter reads the previous refresh's map",
            viewModel.indexOf("siblingOwnedMarkerIds = siblingOwnedIds") <
                viewModel.indexOf("_watchedKeys.value = resolvedWatchedKeys")
        )
    }

    @Test
    fun `the eye keeps the filter it already had`() {
        val eyePublish = viewModel.substringAfter("siblingOwnedMarkerIds = siblingOwnedIds")
            .substringAfter("_partialWatchedKeys.value =")
            .substringBefore("Log.d(")
        assertTrue(
            "the partial set must stay filtered - this is the PAW Patrol case",
            eyePublish.contains("keyOwnedByAnotherProfile(key)")
        )
        assertTrue(
            "and it must still subtract the completed set first",
            eyePublish.contains("resolvedPartialWatchedKeys - resolvedWatchedKeys")
        )
    }

    @Test
    fun `a live watch write cannot put a sibling-owned checkmark back`() {
        val bus = viewModel.substringAfter("WatchStateBus.updates.collect { update ->")
            .substringBefore("// Dynamic addon catalogs")
        val completedPublish = bus.substringAfter("_watchedKeys.value =")
            .substringBefore("val partial =")
        assertTrue(
            "the live update's completed publish must apply the same filter",
            completedPublish.contains("filterNot { key -> keyOwnedByAnotherProfile(key) }")
        )
    }

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
