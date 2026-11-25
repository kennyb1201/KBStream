package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay question a walk cannot answer: whose Back fires first, and on which
 * screens Back is not answered by the screen itself.
 *
 * Navigation Compose registers the NavController's pop callback when the
 * NavHost composes. A destination's own `BackHandler` is registered inside that
 * destination, which composes *after* the host, so it stays ahead of the pop in
 * the dispatcher's LIFO order - the ordering every screen has today, where its
 * own handler beats the shell's. That only holds while the handler stays inside
 * the destination: move one up into the parent composition (MainActivity, a
 * shared wrapper) and it composes *before* the host and silently loses. Nothing
 * about such a move errors - Back just starts landing somewhere else - so it is
 * pinned here, per F1 destination.
 *
 * The second half records the one thing the migration must decide rather than
 * inherit: the three connect pages have no handler of their own, so today their
 * hardware Back is the shell's `else -> Screen.Home` while their header button
 * goes to `returnTo`. Both facts are pinned so F1 either keeps that split on
 * purpose or closes it on purpose - never by accident.
 */
class BackHandlerOrderingContractTest {

    /** F1 destinations whose Back their own file answers, with the file that does. */
    private val ownHandlers: Map<String, String> = linkedMapOf(
        "Stats" to "ui/settings/StatsScreen.kt",
        "Remote" to "ui/remote/RemoteScreen.kt",
        "Simkl" to "ui/simkl/SimklConnectScreen.kt",
        "Trakt" to "ui/trakt/TraktConnectScreen.kt",
        "Addons" to "ui/addons/AddonsScreen.kt",
        "CatalogBuilder" to "ui/addons/CatalogBuilderScreen.kt",
        "PublicLists" to "ui/publiclists/PublicListsScreen.kt",
        "BingeCat public lists" to "ui/bingecat/BingeCatPublicListsScreen.kt"
    ).mapValues { (_, path) -> PACKAGE + path }

    /**
     * The phone twins that carry their own handlers, same rule. The phone Addons
     * screen is not one of them: it defers to the shell's `is Screen.Addons ->`
     * arm on purpose (pinned below), because its Back is only reached with no
     * dialog standing over it.
     */
    private val mobileHandlers: List<String> = listOf(
        "ui/mobile/StatsScreenMobile.kt",
        "ui/mobile/CatalogBuilderScreenMobile.kt",
        "ui/mobile/SimklConnectScreenMobile.kt",
        "ui/mobile/TraktConnectScreenMobile.kt",
        "ui/mobile/PublicListsScreenMobile.kt",
        "ui/mobile/BingeCatPublicListsScreenMobile.kt"
    ).map { PACKAGE + it }

    /** No handler in the file: their hardware Back is the shell's else-arm. */
    private val shellBackedPages: List<String> = listOf(
        "ui/bingecat/BingeCatConnectScreen.kt"
    ).map { PACKAGE + it }

    @Test
    fun `every F1 destination answers Back from inside its own composition`() {
        ownHandlers.forEach { (name, path) ->
            val text = stripComments(source(path))
            assertTrue(
                "$name has no BackHandler in $path: its Back would fall through " +
                    "to the shell, which is not where it lands today",
                text.contains("BackHandler")
            )
            val firstScreenFun = SCREEN_FUN.find(text)
            assertTrue("$name: no @Composable *Screen( in $path", firstScreenFun != null)
            // Both call shapes count: `BackHandler { ... }` and
            // `BackHandler(onBack = ...)`; the import line matches neither.
            val handlerAt = HANDLER_CALL.findAll(text).map { it.range.first }.firstOrNull()
            assertTrue(
                "$name registers its Back before its own composable is declared, " +
                    "so it lives in a parent scope and would compose ahead of the " +
                    "NavHost - LIFO would then give Back to the NavController",
                handlerAt != null && handlerAt > firstScreenFun!!.range.first
            )
        }
        mobileHandlers.forEach { path ->
            assertTrue(
                "$path must answer Back itself, like its TV twin",
                stripComments(source(path)).contains("BackHandler")
            )
        }
    }

    @Test
    fun `the shell keeps exactly the two handlers that are not a destination's`() {
        val shell = stripComments(source(MAIN_ACTIVITY))
        assertEquals(
            "MainActivity must register only the seam-gated global handler and the " +
                "Kids Time Lock veil: a third one is a destination's Back living " +
                "in a parent composition, which the host out-orders",
            2,
            HANDLER_CALL.findAll(shell).count()
        )
        assertTrue(
            "the global handler is enabled only while the current destination is " +
                "not one the NavHost owns, or a migrated destination's Back is " +
                "handled twice",
            shell.contains("BackHandler(enabled = !navSeam.isMigrated(screen))")
        )
        assertTrue(
            "the time lock veil still swallows Back over everything, migrated or not",
            shell.contains("BackHandler(enabled = true) { }")
        )
    }

    @Test
    fun `the two connect pages on the host land both Back and the arrow on returnTo`() {
        // Simkl and Trakt moved with their own handler, which is the one thing a
        // migrated kind cannot do without: the shell's global handler stands down
        // for it, and the NavController's stack holds only the entered
        // destination, so an unhandled press would reach the activity and finish
        // the app. `onBack` is also what the header arrow calls, so the two
        // agree now - they did not when hardware Back was the shell's Home.
        listOf(
            PACKAGE + "ui/simkl/SimklConnectScreen.kt",
            PACKAGE + "ui/trakt/TraktConnectScreen.kt",
            PACKAGE + "ui/mobile/SimklConnectScreenMobile.kt",
            PACKAGE + "ui/mobile/TraktConnectScreenMobile.kt"
        ).forEach { path ->
            assertTrue(
                "$path must answer Back from inside its own composition, on the " +
                    "same landing its header button uses",
                stripComments(source(path)).contains("BackHandler(onBack = onBack)")
            )
        }
        val graph = stripComments(source(PACKAGE + "ui/navigation/NavHostGraph.kt"))
        listOf("SimklRoute", "TraktRoute").forEach { route ->
            assertTrue(
                "$route has no destination: the kind is migrated but nothing renders " +
                    "it",
                graph.contains("composable<$route> { entry ->")
            )
        }
        assertTrue(
            "and both land Back on their own return argument, decoded and sanitized",
            graph.contains("SimklConnectScreen(\n                onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) }") &&
                graph.contains("TraktConnectScreen(\n                onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) }")
        )
    }

    @Test
    fun `the last connect page without a handler of its own still defers to the shell`() {
        shellBackedPages.forEach { path ->
            assertFalse(
                "$path has grown its own BackHandler: good, but then it belongs in " +
                    "the first test's list - the split this one records is closed, and " +
                    "the commit that closes it says so",
                stripComments(source(path)).contains("BackHandler")
            )
        }
        val shell = stripComments(source(MAIN_ACTIVITY))
        assertTrue(
            "the shell lost its is Screen.BingeCat -> render arm",
            shell.contains("is Screen.BingeCat ->")
        )
        assertTrue(
            "the shell's Back else-arm is still Home - that is what answers that " +
                "page's hardware Back while the arrow reads returnTo",
            shell.substringAfter("screen = when (val current = screen) {")
                .contains("else ->\n                Screen.Home")
        )
        assertFalse(
            "BingeCat now has its own Back arm in the shell, so hardware Back was " +
                "made to agree with the arrow - a deliberate behaviour change this " +
                "test must record",
            Regex("is Screen[.]BingeCat ->").containsMatchIn(
                shell.substringAfter("screen = when (val current = screen) {")
                    .substringBefore("else ->")
            )
        )
    }

    @Test
    fun `the phone Addons screen still defers its Back to the shell on purpose`() {
        val addons = stripComments(source(PACKAGE + "ui/mobile/AddonsScreenMobile.kt"))
        assertFalse(
            "the phone Addons screen grew its own BackHandler; if that is deliberate " +
                "it has to be added to the list above, because the shell would " +
                "otherwise handle the same press twice",
            addons.contains("BackHandler")
        )
        val shell = stripComments(source(MAIN_ACTIVITY))
        assertTrue(
            "the shell no longer answers Addons' Back itself",
            shell.contains("is Screen.Addons ->\n                stableBackDestination(current.returnTo)")
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
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/"
        const val MAIN_ACTIVITY = "$PACKAGE" + "MainActivity.kt"
        val SCREEN_FUN = Regex("fun (\\w*Screen\\w*)[(]")
        val HANDLER_CALL = Regex("BackHandler\\s*[({]")
    }
}
