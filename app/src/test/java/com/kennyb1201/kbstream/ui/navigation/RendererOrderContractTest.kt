package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who answers Back, in the order the dispatcher sees them.
 *
 * `OnBackPressedDispatcher` walks its callbacks last-registered-first. Today that
 * gives three layers: the shell's global handler, registered before the renderer,
 * so a screen's own `BackHandler` (inside the renderer) wins over it; and the
 * Kids Time Lock veil, registered AFTER the renderer, so it wins over everything.
 *
 * The renderer is being replaced by a NavHost, which registers its own pop
 * callback when it composes. Nothing about that changes the rule - but every
 * layer's position is a source-order fact, and getting one of them wrong is a
 * Back press that lands somewhere else with no error anywhere. So it is pinned
 * both before the cut and after it: these assertions must hold for whichever
 * renderer is in the file.
 */
class RendererOrderContractTest {

    private val shell: String by lazy { stripComments(source(MAIN_ACTIVITY)) }

    /**
     * Where the screen tree starts. Since the flip that is the host, which is
     * `NavShell` - the NavHost itself lives in its own file, emitted through it.
     */
    private val rendererAt: Int by lazy {
        listOf("    com.kennyb1201.kbstream.ui.navigation.NavShell(", "    NavShell(")
            .map { shell.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
    }

    private val veilAt: Int by lazy { shell.indexOf("BackHandler(enabled = true) { }") }

    @Test
    fun `a renderer is composed`() {
        assertTrue(
            "neither AnimatedContent nor NavHost is composed in the shell: this " +
                "test is no longer measuring a renderer",
            rendererAt >= 0
        )
    }

    @Test
    fun `the time lock veil is registered after the renderer, so it wins`() {
        assertTrue("the Kids Time Lock veil is gone", veilAt >= 0)
        assertTrue(
            "the veil composes at $veilAt but the renderer at $rendererAt: a " +
                "handler registered BEFORE the screen tree loses the dispatcher's " +
                "LIFO order to it, and Back would work inside a locked profile",
            veilAt > rendererAt
        )
    }

    @Test
    fun `the veil is the last BackHandler the shell registers`() {
        val calls = Regex("BackHandler\\s*[({]").findAll(shell).map { it.range.first }.toList()
        assertTrue("no BackHandler in the shell at all", calls.isNotEmpty())
        assertTrue(
            "a BackHandler is registered after the veil, so it answers Back " +
                "inside a locked profile",
            calls.max() == veilAt
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

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
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
