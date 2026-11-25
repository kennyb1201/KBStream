package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope of a migrated destination's ViewModel, pinned.
 *
 * `hiltViewModel()` defaults to `LocalViewModelStoreOwner.current`, and inside a
 * NavHost that is the destination's back-stack entry. The shell pops that entry
 * the moment it leaves the kind, so the default would not merely re-scope the
 * ViewModel - it would end it. What that costs, per page, is the thing each arm's
 * own note warns about: a browse page loses the pages it loaded when Detail opens
 * over it, the public-list browsers lose the level the viewer left and restart an
 * import already following its job, and the catalog grid stops sharing Home's
 * ViewModel - the instance holding the very rails it was opened from.
 *
 * The fix is one parameter, not one per page: the shell hands the graph the
 * ACTIVITY's store, and each destination resolves with it. These tests exist
 * because the failure is silent - every destination still compiles and still
 * shows the right page, and only the loaded state is gone when the viewer comes
 * back.
 */
class DestinationViewModelScopeContractTest {

    private val graph: String by lazy { stripComments(source(GRAPH)) }
    private val shell: String by lazy { stripComments(source(MAIN_ACTIVITY)) }

    @Test
    fun `the graph is handed the activity's store, and the shell passes the activity`() {
        assertTrue(
            "NavHostGraph must take the ViewModel store owner it resolves with. " +
                "Without it every destination falls back to the back-stack entry, " +
                "which the shell destroys when it leaves the kind",
            graph.contains("viewModelOwner: ViewModelStoreOwner,")
        )
        assertFalse(
            "the owner must not have a default: a caller that forgets it would " +
                "silently re-scope every destination to the visit",
            graph.contains("viewModelOwner: ViewModelStoreOwner =")
        )
        assertTrue(
            "the shell must pass its own activity, which is the store every arm " +
                "resolved against",
            shell.contains("viewModelOwner = this@MainActivity,")
        )
    }

    @Test
    fun `no destination resolves a ViewModel against the visit`() {
        assertFalse(
            "a bare hiltViewModel() inside a destination is scoped to the " +
                "back-stack entry: the page still renders, and everything it had " +
                "loaded is gone the next time the shell enters the kind",
            Regex("""hiltViewModel\(\s*\)""").containsMatchIn(graph)
        )
        assertEquals(
            "every destination that passes a ViewModel must pass the " +
                "activity-scoped one. This count is the inventory of them - a new " +
                "destination with a ViewModel bumps it in the same commit, which " +
                "is what keeps this from quietly rotting",
            13,
            Regex("""hiltViewModel\(viewModelStoreOwner = viewModelOwner\)""")
                .findAll(graph).count()
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    /**
     * Both comment kinds removed, with string and char literals copied through.
     *
     * Block comments matter here specifically: the graph's own KDoc explains the
     * rule by naming `hiltViewModel()`, and a scan that only drops `//` would
     * read that sentence as a call and fail on the documentation of the fix.
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    out.append(c)
                }
                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i++
                }
                inString -> {
                    out.append(c)
                    if (c == '\\') i++ else if (c == '"') inString = false
                }
                inChar -> {
                    out.append(c)
                    if (c == '\\') i++ else if (c == '\'') inChar = false
                }
                c == '/' && next == '/' -> inLine = true
                c == '/' && next == '*' -> inBlock = true
                c == '"' -> {
                    inString = true
                    out.append(c)
                }
                c == '\'' -> {
                    inChar = true
                    out.append(c)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/"
        const val GRAPH = "$PACKAGE" + "ui/navigation/NavHostGraph.kt"
        const val MAIN_ACTIVITY = "$PACKAGE" + "MainActivity.kt"
    }
}
