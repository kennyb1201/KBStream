package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live poll that changed nothing must not republish the sections (dig four,
 * 2026-11-16, §2c rank 9).
 *
 * `refreshLeagues` reassigned `_sections` on every 30 s tick while a game was
 * live, so every visible card recomposed whether or not a score had moved. The
 * section list is a list of data classes and the merge keeps the previous
 * instance for leagues the poll did not touch, so an equality check is a real
 * change detector: a republish now means something happened.
 */
class SportsHubPollRepublishContractTest {

    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt") }

    @Test
    fun `the poll publishes only when a section actually changed`() {
        val refresh = viewModel
            .substringAfter("private suspend fun refreshLeagues(")
            .substringBefore("val now = System.currentTimeMillis()")
        assertTrue(
            "the guard is structural equality on the merged list: $refresh",
            refresh.contains("if (updated != _sections.value) {") &&
                refresh.contains("_sections.value = updated")
        )
        assertTrue(
            "and the match pass still runs - a moved score with unchanged " +
                "sections (the match key) must still be reconciled",
            refresh.contains("resolveMatches(updated)")
        )
        assertTrue(
            "the merge keeps the previous instance for untouched leagues, which " +
                "is what makes the equality check cheap and correct",
            refresh.contains("_sections.value.map { fresh[it.league.path] ?: it }")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
