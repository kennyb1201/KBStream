package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route table is the migration's contract before it has a single
 * destination: every [Screen] variant has exactly one route, and the one
 * variant that is not a destination is named as such.
 *
 * The strongest half of this is the compiler, not a test: `NavRoutes.of` is an
 * exhaustive `when` over a sealed hierarchy, so a new screen without a route
 * does not build. That guarantee is exactly what a later "just add the screen
 * to the `when`" edit can quietly undo - by answering the new variant with an
 * existing route, or by dropping a variant while its screen still exists. These
 * tests read both files and hold the table to one route per variant.
 *
 * Read from the source: the route types are a data table, and the destination
 * they describe lives in a 6,000-line Activity this phase does not touch.
 */
class NavRouteTableContractTest {

    /** Every variant of `sealed class Screen`, in declaration order. */
    private val variants: List<String> by lazy {
        val block = source(MAIN_ACTIVITY)
            .substringAfter("sealed class Screen {")
            .substringBefore("private data class PendingPlay(")
        Regex("^ {4}(?:data )?(?:class|object) (\\w+)", RegexOption.MULTILINE)
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()
    }

    private val navRoutes: String by lazy { stripComments(source(NAV_ROUTES)) }

    @Test
    fun `the hierarchy is the one this table was written for`() {
        // 26 destinations plus the player launch spec. Pinned by count so a
        // variant added anywhere is visible here, not only at the seed later.
        assertEquals(27, variants.size)
        assertTrue(variants.contains("Player"))
    }

    @Test
    fun `every screen has a route and the seam answers each of them`() {
        variants.filter { it != "Player" }.forEach { name ->
            assertTrue(
                "$name has no route type: the migration cannot reach it",
                Regex("(data class|data object) ${name}Route\\b").containsMatchIn(navRoutes)
            )
            assertTrue(
                "$name is not translated by NavRoutes.of",
                navRoutes.contains("is Screen.$name ->")
            )
        }
        assertEquals(
            "the player is a launch spec, not a destination: it must answer null " +
                "so the seam keeps it out of Nav and as an overlay",
            1,
            Regex("is Screen\\.Player -> null").findAll(navRoutes).count()
        )
    }

    @Test
    fun `the route names are fixed strings and unique`() {
        val names = Regex("@SerialName\\(\"([^\"]+)\"\\)")
            .findAll(navRoutes)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "one @SerialName per destination, or type-safe routes fall back to " +
                "generated class names that move when a package does",
            variants.size - 1,
            names.size
        )
        assertEquals("two destinations share a route name", names.size, names.toSet().size)
        assertFalse(
            "a route name must be a stable slug, never a class name",
            names.any { it.contains('.') }
        )
    }

    @Test
    fun `returning is data, not a back stack`() {
        // The migration's whole hazard: today's Back is one value plus an
        // explicit returnTo, so every route that can be returned FROM must carry
        // it as an argument rather than relying on the stack underneath.
        listOf(
            "AddonsRoute",
            "CatalogBuilderRoute",
            "SimklRoute",
            "TraktRoute",
            "BingeCatRoute",
            "PublicListsRoute",
            "YearReviewRoute",
            "DetailRoute",
            "ActorRoute",
            "StudioRoute",
            "DecadeRoute",
            "TagRoute",
            "CollectionRoute",
            "CatalogGridRoute",
            "KBFolderRoute",
            "ProfilePickerRoute",
            "ProfileEditRoute",
            "StreamsRoute"
        ).forEach { route ->
            val body = navRoutes.substringAfter("${route}(")
                .substringBefore(")")
            assertTrue(
                "$route must carry returnRoute: its Back landing is not the " +
                    "screen that happens to be underneath it",
                body.contains("returnRoute")
            )
        }
        assertTrue(
            "and the return destination is encoded with the codec that already " +
                "survives process death, not a second serialization",
            navRoutes.contains("private fun returnOf(screen: Screen): String = " +
                "encodeScreen(screen).toString()")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NAV_ROUTES = "com/kennyb1201/kbstream/ui/navigation/NavRoutes.kt"
    }
}
