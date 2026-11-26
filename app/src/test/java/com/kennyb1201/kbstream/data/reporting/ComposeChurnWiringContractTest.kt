package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The churn counter is wired where a walk can run it, and its line rides the
 * frame line.
 *
 * A source-reading test, like its neighbours: the module has no Compose test
 * rule, and what this pins is the counts' PLACEMENT (a body, not an effect) and
 * the shape of the line the paste carries. The arithmetic is covered by
 * [ComposeChurnTest].
 */
class ComposeChurnWiringContractTest {

    private val root by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (found == null && candidate.isDirectory) found = candidate
            }
            dir = dir.parentFile
        }
        found ?: throw AssertionError("main source root not found")
    }

    private fun read(relative: String): String {
        val file = File(root, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `every scope a walk can run is counted in its own body`() {
        val expected = mapOf(
            "com/kennyb1201/kbstream/ui/home/HomeScreen.kt" to listOf("home", "spotlight.tint"),
            "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt" to listOf("spotlight.panel"),
            "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt" to listOf("billboard"),
            "com/kennyb1201/kbstream/ui/components/PosterCard.kt" to listOf("poster"),
            "com/kennyb1201/kbstream/ui/components/LandscapeCard.kt" to listOf("landscape"),
            "com/kennyb1201/kbstream/ui/components/KBCard.kt" to listOf("kbcard")
        )
        expected.forEach { (path, scopes) ->
            val source = read(path)
            scopes.forEach { scope ->
                assertTrue(
                    "$path must count '$scope'",
                    source.contains("ComposeChurn.note(\"$scope\")")
                )
            }
            assertTrue(
                "$path must import the counter rather than qualify it",
                source.contains("import com.kennyb1201.kbstream.data.reporting.ComposeChurn")
            )
        }
    }

    @Test
    fun `the count is taken by the body, not after it`() {
        val card = read("com/kennyb1201/kbstream/ui/components/PosterCard.kt")
        val note = card.indexOf("ComposeChurn.note(\"poster\")")
        val body = card.indexOf("val context = LocalContext.current", note)
        assertTrue("the note must come before the card's own work", note > 0 && body > note)
        assertFalse(
            "a SideEffect runs after composition and would not count a skipped body",
            card.substring(note - 40, note).contains("SideEffect")
        )
    }

    @Test
    fun `a gesture's line carries the delta, not the process total`() {
        val trace = read("com/kennyb1201/kbstream/data/reporting/UiFrameTrace.kt")
        assertTrue(
            "the session records where the counters stood when it opened",
            trace.contains("val churnAtStart: Map<String, Long>") &&
                trace.contains("Session(label, ComposeChurn.snapshot())")
        )
        assertTrue(
            "and the line is that session's increase",
            trace.contains("ComposeChurn.suffix(ComposeChurn.since(session.churnAtStart))")
        )
        assertTrue(
            "appended to the frame line, so a paste cannot carry one without the other",
            trace.contains("            churn\n")
        )
    }

    @Test
    fun `the counter writes no Compose state and reflects on nothing`() {
        val source = read("com/kennyb1201/kbstream/data/reporting/ComposeChurn.kt")
        assertFalse(
            "a state write in composition would invalidate the card just counted",
            source.contains("mutableStateOf") || source.contains("mutableIntStateOf")
        )
        assertFalse(
            "R8 renames the UI package, so a reflected name would print as a.b",
            source.contains("javaClass") || source.contains("simpleName") ||
                source.contains("stackTrace")
        )
        assertTrue(
            "no lock on the composition hot path",
            source.contains("ConcurrentHashMap") && source.contains("LongAdder")
        )
    }
}
