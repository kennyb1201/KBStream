package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's route coverage, pinned before it is wired in.
 *
 * Three things have to be true of every destination in the host that owns the
 * shell, and none of them is visible in behaviour until the day one is missing:
 *
 *  - it exists (a route with no destination is a screen nothing can reach);
 *  - it answers Back itself (the shell's global table sits BELOW the
 *    controller's pop callback in the dispatcher, so it can no longer answer for
 *    any screen - a destination without a handler lets Back leave the app);
 *  - it re-provides the activity's ViewModel store (inside a destination the
 *    ambient owner is the back-stack entry, which is destroyed when the shell
 *    leaves the kind).
 */
class NavShellCoverageContractTest {

    private val shell: String by lazy { stripComments(source(SHELL)) }
    private val routes: String by lazy { stripComments(source(NAV_ROUTES)) }

    private val declared: List<String> by lazy {
        Regex("""@SerialName\("[^"]+"\)\s*(?:internal )?data (?:object|class) (\w+)""")
            .findAll(routes)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `every declared route has a destination`() {
        assertTrue("the route table declares nothing", declared.size >= 26)
        declared.forEach { route ->
            assertTrue(
                "$route has no destination in the shell: the graph cannot reach " +
                    "it, and no test would notice until a screen stopped opening",
                shell.contains("composable<$route>")
            )
        }
    }

    @Test
    fun `every destination answers Back for itself`() {
        val destinations = Regex("""composable<\w+>""").findAll(shell).count()
        val handlers = Regex("""BackHandler\s*\{""").findAll(shell).count()
        assertEquals(
            "a destination without its own BackHandler lands on the controller's " +
                "pop (or, with an empty stack, on the activity): the shell's table " +
                "is registered below the pop callback and can never answer",
            destinations,
            handlers
        )
    }

    @Test
    fun `every destination re-provides the activity's ViewModel store`() {
        val destinations = Regex("""composable<\w+>""").findAll(shell).count()
        // Call sites only: the helper's own declaration ends in `.Destination(`.
        val rendered = Regex("""Destination\((Screen\.|NavRoutes\.screenOfEntry\()""")
            .findAll(shell).count()
        assertEquals(
            "a destination that renders without re-providing the activity's store " +
                "scopes every screen's ViewModel to the visit - the browse pages " +
                "lose their loaded results and an import restarts on the way back",
            destinations,
            rendered
        )
        assertTrue(
            "and the provider must be INSIDE the destination: NavHost installs the " +
                "back-stack entry as the owner there, so a provider around the host " +
                "would be shadowed",
            shell.contains("CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelOwner)")
        )
    }

    @Test
    fun `a lateral tab move is a crossfade and television keeps the depth slide`() {
        assertTrue(
            "the lateral rule must keep the television out of it: TV has no bar, so " +
                "its moves between those screens really are pushes",
            shell.contains("if (FormFactor.isTv) return false")
        )
        assertTrue(
            "and it must use the bar's own predicate, not a second list of which " +
                "screens are top-level",
            shell.contains("mobileNavSelection(")
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
        const val PACKAGE = "com/kennyb1201/kbstream/"
        const val SHELL = "$PACKAGE" + "ui/navigation/NavShell.kt"
        const val NAV_ROUTES = "$PACKAGE" + "ui/navigation/NavRoutes.kt"
    }
}
