package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F0's hosting, pinned before anything renders through it.
 *
 * The seam's whole claim is that the shell keeps ONE source of truth while
 * destinations move across: `screen` is written exactly as before, and the
 * NavHost follows it. If that direction ever inverts - the host navigating and
 * the state being derived from the back stack - Back, the restoration codec,
 * the cold-launch gate and the exit-confirm dialog all start depending on a
 * stack that is not the one the shell encodes.
 *
 * F1 moves the first destination across, so the tests below pin the shape that
 * makes that move survivable: the host lives INSIDE the renderer's content
 * lambda (one destination deep, where the shell's transition, the hero registry
 * and the splash above it all still apply), the fall-through is still the
 * AnimatedContent, and exactly one kind is migrated - because a transition
 * between two migrated kinds would ask for two NavHosts over one controller.
 */
class NavHostHostingContractTest {

    private val shell: String by lazy { stripComments(source(MAIN_ACTIVITY)) }
    private val graph: String by lazy { stripComments(source(GRAPH)) }

    @Test
    fun `the host is only reached for a migrated kind and the shell still renders otherwise`() {
        assertTrue(
            "the NavHost must be guarded by the seam's migrated check, or it " +
                "replaces the renderer for every destination at once",
            shell.contains("if (navSeam.isMigrated(current)) {")
        )
        assertTrue(
            "the host is not composed at all",
            shell.contains("com.kennyb1201.kbstream.ui.navigation.NavHostGraph(")
        )
        assertTrue(
            "the fall-through must still be the AnimatedContent the shell has " +
                "always rendered, keyed on the screen kind",
            shell.contains("\n    AnimatedContent(\n        targetState = screen,")
        )
        assertFalse(
            "the host must not be hosted beside the renderer: a NavController " +
                "attaches to exactly one NavHost, and the two compositions a " +
                "transition keeps alive would be two",
            shell.contains("} else\n    AnimatedContent(")
        )
    }

    @Test
    fun `the host follows the screen value rather than the other way round`() {
        assertTrue(
            "nothing follows the shell's screen changes",
            shell.contains("LaunchedEffect(screen) {")
        )
        assertTrue(
            "and nothing clears the stack when the shell leaves the migrated " +
                "kinds, so the next entry would resume a stack the shell no " +
                "longer describes",
            shell.contains("navController.popBackStack(navController.graph.id, inclusive = false)")
        )
        assertFalse(
            "entering a migrated kind must not navigate: the host opens on the " +
                "route of the screen being entered, and navigating to it would " +
                "replace that entry and replay its enter transition",
            shell.contains("navController.navigate(")
        )
        assertFalse(
            "the screen state must not be read back out of the back stack: the " +
                "codec and the cold-launch gate own restoration",
            shell.contains("currentBackStackEntry!!") ||
                shell.contains("previousBackStackEntry!!")
        )
    }

    @Test
    fun `the F1 leaves are the migrated set and the host opens on the entered route`() {
        val migrated = Regex("migratedKinds = setOf\\(([^)]*)\\)")
            .find(shell)?.groupValues?.get(1)
            ?: error("the seam is constructed without a migrated set")
        // Parsed rather than compared as text: the set is now long enough to be
        // written one kind per line, and what this pins is membership, not
        // formatting.
        val kinds = Regex("\"(\\w+)\"").findAll(migrated).map { it.groupValues[1] }.toList()
        assertEquals(
            "the migrated set is F1's eight leaves (each answering Back itself) " +
                "followed by F2's eight browse drill-downs, whose primitive" +
                " arguments are the whole of their state",
            listOf(
                "remote",
                "stats",
                "catalogBuilder",
                "simkl",
                "trakt",
                "addons",
                "bingecat",
                "publicLists",
                "actor",
                "studio",
                "decade",
                "tag",
                "collection",
                "catalogGrid",
                "kbFolder",
                "yearReview"
            ),
            kinds
        )
        assertTrue(
            "the host opens on the route of the screen being entered, captured at " +
                "mount, so entering a migrated kind navigates nothing and replays " +
                "no enter transition",
            shell.contains("val start = remember {") &&
                shell.contains(
                    "com.kennyb1201.kbstream.ui.navigation.NavRoutes.of(current)!!"
                ) &&
                shell.contains("startDestination = start,")
        )
        assertTrue(
            "and the graph must open on that captured destination, not on a fixed one",
            graph.contains("startDestination = startDestination,")
        )
        assertFalse(
            "a fixed start destination would paint the wrong screen for a frame " +
                "before the real one is pushed over it",
            graph.contains("startDestination = RemoteRoute,")
        )
        assertTrue(
            "the host may only be composed for the destination being ENTERED: " +
                "two NavHosts over one controller fails at attach time",
            shell.contains("if (!navSeam.isMigrated(screen) || current === screen) {")
        )
        val seam = stripComments(source(SEAM))
        assertTrue(
            "the migrated set must still default to empty, so a phase flips one " +
                "destination instead of the renderer for all of them",
            seam.contains("private val migratedKinds: Set<String> = emptySet()")
        )
        assertTrue(
            "navigateTo must still be the state write it always was while nothing " +
                "is migrated",
            seam.contains("fun navigateTo(value: Screen) {\n        state.value = value\n    }")
        )
    }

    @Test
    fun `the graph composes the F1 leaves and lands Back where the arms did`() {
        assertTrue(
            "the three leaves the shell hands the host must all be composed here, " +
                "or the host would open on a route it has no destination for",
            graph.contains("composable<RemoteRoute> {") &&
                graph.contains("composable<StatsRoute> {") &&
                graph.contains("composable<CatalogBuilderRoute> {")
        )
        assertTrue(
            "its Back must land on Settings, which is what the shell's arm did " +
                "(onBack = { screen = Screen.Settings })",
            graph.contains("RemoteScreen(onBack = { onNavigate(Screen.Settings) })")
        )
        assertTrue(
            "reduced motion is still instant: the transitions read the shell's " +
                "own animation length",
            graph.contains("tween(transitionMs)")
        )
    }

    @Test
    fun `the F1 destinations land Back where the arms did`() {
        assertTrue(
            "stats is hardwired to Settings in the shell and must stay so",
            graph.contains("composable<StatsRoute> {") &&
                graph.contains("StatsScreen(onBack = { onNavigate(Screen.Settings) })")
        )
        assertTrue(
            "the catalog builder must land on its route's return argument, not on " +
                "whatever is underneath",
            graph.contains("composable<CatalogBuilderRoute> { entry ->") &&
                graph.contains("onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) }")
        )
        val table = stripComments(source(NAV_ROUTES))
        assertTrue(
            "a return argument is decoded with the codec that survives process " +
                "death, then sanitized, so the depth cap and the autoplay strip hold",
            table.contains("decodeScreen(JSONObject(returnRoute))") &&
                table.contains("stableBackDestination(")
        )
        assertTrue(
            "an unreadable argument lands on Home rather than crashing",
            table.contains("if (returnRoute == null) {") &&
                table.contains("getOrDefault(Screen.Home)")
        )
    }

    @Test
    fun `every destination in the graph is a route the table declared`() {
        val routes = Regex("composable<(\\w+)>").findAll(graph)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("the graph composes no destination at all", routes.isNotEmpty())
        val table = stripComments(source(NAV_ROUTES))
        routes.forEach { route ->
            assertTrue(
                "$route is composed here but NavRoutes.kt does not declare it: a " +
                    "destination the table cannot translate back is a kind the " +
                    "seam can never revert",
                Regex("(data class|data object) $route\\b").containsMatchIn(table)
            )
        }
        assertFalse(
            "the player is a launch spec, never a route: it must not appear in " +
                "the graph",
            graph.contains("PlayerRoute")
        )
        assertTrue(
            "and the same in the table itself",
            !table.contains("data class PlayerRoute") &&
                !table.contains("data object PlayerRoute")
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
        const val SEAM = "$PACKAGE" + "ui/navigation/NavSeam.kt"
        const val GRAPH = "$PACKAGE" + "ui/navigation/NavHostGraph.kt"
        const val NAV_ROUTES = "$PACKAGE" + "ui/navigation/NavRoutes.kt"
    }
}
