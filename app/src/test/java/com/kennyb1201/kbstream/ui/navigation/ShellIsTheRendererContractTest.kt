package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-state invariants of the flip (spec: navcompose, F3+F4+F5).
 *
 * These replace three classes whose subject no longer exists - the arm/host split
 * (`NavHostHostingContractTest`, `MigratedBackCoverageContractTest`,
 * `MigrationBackParityContractTest`). Every claim they made is translated here
 * rather than dropped, because each one guarded a failure that is still possible:
 *
 *  - "the host is only reached for a migrated kind" -> there is no second
 *    renderer left to fall back to, so the host must be emitted once and never
 *    behind a guard;
 *  - "a migrated arm draws nothing, so the screen is composed once" -> inverted,
 *    and now the load-bearing rule: the host renders FROM the arm table, so an
 *    arm that draws nothing draws nothing anywhere;
 *  - "the destination lands where the arm does" -> the destination is now the
 *    only landing, and it must be the sanitized one (autoplay stripped);
 *  - "no migrated destination navigates to another migrated kind" -> no
 *    destination navigates to another destination except through the shell's
 *    recorded value, so a transition still cannot compose two hosts.
 */
class ShellIsTheRendererContractTest {

    private val main: String by lazy { stripComments(source("com/kennyb1201/kbstream/MainActivity.kt")) }
    private val shell: String by lazy {
        stripComments(source("com/kennyb1201/kbstream/ui/navigation/NavShell.kt"))
    }

    @Test
    fun `the host is emitted once and never behind a migrated guard`() {
        assertEquals(
            "the host replaces the renderer outright: two renderers is exactly the " +
                "state this flip ended, and a second NavHost on one controller throws",
            1,
            Regex("""\bNavShell\(""").findAll(main).count()
        )
        // The arms keep their own `isMigrated` guard as the revert seam; the host
        // call itself must sit at the top of the composition, with no guard of
        // its own, or some kinds render from the arms and others from the host.
        val call = main.indexOf("NavShell(")
        assertTrue("the host call must exist", call > 0)
        val before = main.substring((call - 400).coerceAtLeast(0), call)
        assertFalse(
            "a seam guard around the host would leave some kinds on the arm table " +
                "and others on the host - the re-entrant split, rebuilt",
            before.contains("isMigrated")
        )
        assertFalse(
            "the per-entry host is gone: it existed only because one NavController " +
                "takes one host and the renderer kept two screens composed at once",
            main.contains("NavHostGraph(")
        )
    }

    @Test
    fun `no arm is blanked, because the host renders from the arm table`() {
        assertTrue(
            "the migrated set must stay empty: the host asks the arm table for " +
                "every kind, so a kind left in the set renders as nothing - a " +
                "blank screen with a correct-looking stack under it",
            main.contains("migratedKinds = emptySet()")
        )
    }

    @Test
    fun `every returnTo destination lands on the sanitized return`() {
        val returnToDestinations = Regex(
            """toRoute<(\w+)>\(\)\.returnRoute"""
        ).findAll(shell).map { it.groupValues[1] }.toList()
        assertTrue("the leaves and drill-downs all carry a returnTo", returnToDestinations.size >= 16)
        // One handler per destination, each through landingOf, which is what
        // strips a one-shot autoplay: returning to a Detail page must never
        // press Play, and Back is the only place that can still do it.
        assertEquals(
            "each returnTo destination needs a landing of its own",
            returnToDestinations.size,
            Regex("""onNavigate\(NavRoutes\.landingOf\(""").findAll(shell).count()
        )
    }

    @Test
    fun `the graph root is captured once and the stack follows the screen value`() {
        assertTrue(
            "the root must be the screen the shell opened on, captured at the " +
                "first composition: re-deriving it would move the graph under a viewer",
            main.contains("val graphStart = remember {")
        )
        assertTrue(
            "the effect must compare the controller's current kind before it " +
                "navigates, or a destination's own membership sends the stack back " +
                "the way it came (navigate -> screen writes -> navigate)",
            main.contains("screenOfEntry(entry) == screen")
        )
        assertTrue(main.contains("launchSingleTop = true"))
    }

    /**
     * The black-window regression (field report 2026-11-25: a black screen right
     * after the profile picker, with only the exit prompt still drawing).
     *
     * Popping the host back to the graph root empties the back stack - the
     * controller drops an entry left sitting on a NavGraph - and an empty host
     * composes nothing at all: no screen, no BackHandler, and, because the mirror
     * returned early on the missing entry, no way back to one. Both halves are
     * pinned here so a future "tidy the stack" cannot restore it.
     */
    @Test
    fun `the shell never empties the host, and an empty host takes the navigation`() {
        assertFalse(
            "the shell must never pop the host back to the graph root: with every " +
                "kind on the host that pop runs on every screen change, leaves the " +
                "stack empty, and an empty host draws nothing at all",
            main.contains("popBackStack(navController.graph.id")
        )
        assertFalse(
            "a missing entry must take the navigation below, not an early return - " +
                "that return is what left the black window up for ever",
            main.contains("currentBackStackEntry ?: return@LaunchedEffect")
        )
        assertTrue(
            "the compare-and-skip stays, so a destination's own reflection cannot " +
                "send the stack back the way it came",
            main.contains("screenOfEntry(entry) == screen")
        )
    }

    /**
     * The Back regression (field report 2026-11-25, build 6368: Back out of the
     * streams picker or an actor page landed on Home instead of the Detail page
     * that pushed it).
     *
     * Back had two owners. The NavController registers its own callback for as
     * long as its stack can pop, the host is composed after the shell, and the
     * dispatcher gives the later registration the press - so the host popped and
     * consumed it, the shell's BackHandler never ran, and what the pop revealed
     * was the graph's start destination. The fix is one flag: an inclusive pop
     * leaves a single entry, which leaves the controller unpoppable and hands
     * Back back to the shell. Pinned because that flag looks like tidying, and
     * restoring the two-deep stack silently restores the bug.
     */
    @Test
    fun `back belongs to the shell, and the host never pops it`() {
        assertTrue(
            "the mirror must replace the whole stack with the single new entry: a " +
                "host holding one entry cannot pop, and that is what keeps the " +
                "NavController's own Back callback disabled",
            main.contains("popUpTo(navController.graph.id) { inclusive = true }")
        )
        assertFalse(
            "a stack two deep is poppable, and the controller's callback is registered " +
                "after the shell's - it would pop the host, swallow the press, and " +
                "reveal Home from any drill-down",
            main.contains("popUpTo(navController.graph.id) { inclusive = false }")
        )
        assertTrue(
            "the shell stays the owner of Back for pushed screens",
            main.contains("BackHandler(enabled = !navSeam.isMigrated(screen))")
        )
        // The two the field report broke on, each through the sanitized return so
        // Back lands on the page that pushed it and its one-shots stay stripped.
        listOf("Screen.Streams", "Screen.Actor").forEach { kind ->
            assertTrue(
                "$kind must keep its shell Back destination",
                Regex(
                    """is ${Regex.escape(kind)} ->\s*\n\s*stableBackDestination\("""
                ).containsMatchIn(main)
            )
        }
    }

    @Test
    fun `the player is still an overlay and not a destination`() {
        assertFalse(
            "the Player is a separate Activity that covers this one; as a " +
                "destination it would need an enter transition and would keep the " +
                "shell composed underneath it",
            shell.contains("Screen.Player")
        )
        assertFalse(
            "and nothing may route to it: it has no route, which is how the " +
                "overlay stays outside the stack",
            Regex("""composable<PlayerRoute>""").containsMatchIn(shell)
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
}
