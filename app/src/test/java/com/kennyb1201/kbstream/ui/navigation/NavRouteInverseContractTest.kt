package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route table, read in both directions.
 *
 * Until this phase the graph only had to turn a `Screen` into a route - the
 * shell still held the value it was rendering. The moment the host owns the
 * shell that inverts: each destination renders from ITS OWN entry, so every
 * route needs an inverse, and a route missing from it is a destination that
 * silently draws Home.
 *
 * `else -> Screen.Home` is what makes that silence possible, which is why this
 * test exists: the compiler cannot see a branch that was never written, and the
 * screen would look like a navigation bug rather than a missing case.
 */
class NavRouteInverseContractTest {

    private val routes: String by lazy { stripComments(source(NAV_ROUTES)) }

    /** Every route type the table declares, by class name. */
    private val declared: List<String> by lazy {
        Regex("""@SerialName\("[^"]+"\)\s*(?:internal )?data (?:object|class) (\w+)""")
            .findAll(routes)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `the table declares the routes it claims to`() {
        assertTrue(
            "no route types were found: the table's shape changed and this test " +
                "is no longer measuring anything",
            declared.size >= 26
        )
    }

    @Test
    fun `every route converts to a screen`() {
        val inverse = routes.substringAfter("fun screenOf(route: Any): Screen")
        declared.forEach { route ->
            assertTrue(
                "$route has no branch in screenOf: a destination entered on it " +
                    "would fall through to `else -> Screen.Home` and draw the wrong " +
                    "page with no error anywhere",
                inverse.contains("is $route ->") ||
                    inverse.contains("is $route ->\n")
            )
        }
    }

    @Test
    fun `every route is produced from a screen`() {
        val forward = routes.substringAfter("fun of(screen: Screen): Any?")
            .substringBefore("private fun returnOf")
        declared.forEach { route ->
            assertTrue(
                "$route is never produced by NavRoutes.of, so nothing can navigate " +
                    "to it",
                forward.contains("$route(") || forward.contains("$route\n") ||
                    forward.contains("-> $route")
            )
        }
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
        const val NAV_ROUTES =
            "com/kennyb1201/kbstream/ui/navigation/NavRoutes.kt"
    }
}
