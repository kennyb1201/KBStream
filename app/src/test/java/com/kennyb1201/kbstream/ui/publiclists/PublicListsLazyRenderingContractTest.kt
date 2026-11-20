package com.kennyb1201.kbstream.ui.publiclists

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both public-lists surfaces compose lazily, and a non-playback death is written
 * down exactly once per launch.
 *
 * The field report (TCL Smart TV, 1.7 GB, 0.9.407): opening the Trakt public
 * lists page force-closed the app with no warning, nothing in Sentry and no line
 * in the diagnostics export. Trakt's directory is up to 300 cards (3 pages ×
 * 100) against MDBList's 100, and the page used to render every card in one pass
 * from a `verticalScroll` Column - so the process was killed by the system,
 * which is invisible to a crash reporter by definition. Lazy composition is the
 * fix, and these assertions are what stop a later edit from putting a `forEach`
 * over the cards back into a scrolling Column.
 */
class PublicListsLazyRenderingContractTest {

    private companion object {
        const val TV = "com/kennyb1201/kbstream/ui/publiclists/PublicListsScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/PublicListsScreenMobile.kt"
        const val APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val EXIT_READ = "ProcessExitRecord.previous(applicationContext)"
        const val MARKER_CONSUME = "PlaybackDeathMarker.consume(applicationContext)"
    }

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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun `both public-lists surfaces render their cards lazily`() {
        mapOf(TV to "TV", MOBILE to "phone").forEach { (path, form) ->
            val source = source(path)

            assertTrue(
                "$form: the page must be one LazyColumn, not a scrolling Column",
                source.contains("LazyColumn(")
            )
            assertTrue(
                "$form: the list must remember its own scroll state",
                source.contains("rememberLazyListState()")
            )
            assertTrue(
                "$form: a LazyColumn nested in a verticalScroll is an infinite-height crash",
                !source.contains("verticalScroll(")
            )
            assertTrue(
                "$form: the cards must be items with stable keys",
                source.contains("items(cards, key = { it.key })") ||
                    source.contains("items(state.cards, key = { it.key })")
            )
            assertTrue(
                "$form: the detail's titles must be items too - that read is uncapped",
                source.contains("items(openable.size)")
            )
            assertTrue(
                "$form: the per-card loop is what killed the TCL",
                !source.contains("cards.forEach {")
            )
        }
    }

    @Test
    fun `the previous exit is read once, before the playback-death branch`() {
        val application = source(APPLICATION)

        assertTrue(
            "every launch must read the platform's exit history, playback or not",
            occurrences(application, EXIT_READ) == 1
        )

        val exitRead = application.indexOf(EXIT_READ)
        val marker = application.indexOf(MARKER_CONSUME)

        assertTrue(
            "the read must come before the marker branch, or a death without a " +
                "marker goes unrecorded again",
            exitRead in 0 until marker
        )
        assertTrue(
            "a non-deliberate exit must be named in the diagnostics export",
            application.contains("\"process_exit\"") &&
                application.contains("previous process: \${exit.label}")
        )
        assertTrue(
            "deliberate exits (force-stop, update) are noise and must stay unrecorded",
            application.contains("!exit.deliberate")
        )
    }
}
