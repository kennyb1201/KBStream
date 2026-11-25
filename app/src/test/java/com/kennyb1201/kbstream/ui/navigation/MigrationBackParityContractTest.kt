package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two copies of every Back landing, held to each other.
 *
 * A migrated destination exists twice for the length of this migration: as an arm
 * of the shell's `when`, and as a `composable<…>` in the graph. Both state where
 * Back goes, and both compile - so the arm can drift from the destination without
 * anything noticing, and the drift only shows up as a landing that changed for the
 * half of the app that is not rendering through that path.
 *
 * The arm is not dead code, either: the spec's revert is "remove the kind from
 * `migratedKinds`", and that only restores the old behaviour if the arm is still
 * there with the landing it had. An arm deleted while its destination is live
 * makes the phase unrevertible, which is worse than a wrong landing - it is a
 * one-way door in a migration whose whole promise is that each step can be undone.
 *
 * The transition rules are pinned here for the same reason: they are the two ways
 * this migration could still change how the app *moves*, and neither is visible in
 * a unit test unless it is asserted from source.
 */
class MigrationBackParityContractTest {

    private val shell: String by lazy { stripComments(source(MAIN_ACTIVITY)) }
    private val graph: String by lazy { stripComments(source(GRAPH)) }

    /**
     * kind -> (Screen variant, the landing the arm states). The landing is the
     * expression that produces the destination, because that is what Back lands
     * on; the graph's equivalent is derived from it in the parity test.
     */
    private val parity: List<Triple<String, String, String>> = listOf(
        // To Settings, hardwired on both sides.
        Triple("remote", "Screen.Remote", "Screen.Settings"),
        Triple("stats", "Screen.Stats", "Screen.Settings"),
        // Through the codec's sanitizer, which is the same call the arm made.
        Triple("catalogBuilder", "Screen.CatalogBuilder", "stableBackDestination(current.returnTo)"),
        // The two tracker pages state their own returnTo, so both entries land on
        // the opener rather than on whichever one Settings happened to be under.
        Triple("simkl", "Screen.Simkl", "stableBackDestination(current.returnTo)"),
        Triple("trakt", "Screen.Trakt", "stableBackDestination(current.returnTo)")
    )

    @Test
    fun `every migrated kind still has its arm, with the landing it always had`() {
        migratedKinds().forEach { kind ->
            val row = parity.firstOrNull { it.first == kind }
                ?: error(
                    "$kind has no row here: name the arm it came from and the " +
                        "landing that arm states, so the flip can be reverted"
                )
            val (_, variant, armLanding) = row
            val arm = armBody(variant)
            assertTrue(
                "the arm for $variant is gone. Removing the kind from migratedKinds " +
                    "is how this phase is reverted, and it cannot be reverted to an " +
                    "arm that no longer exists",
                arm != null
            )
            assertTrue(
                "the $variant arm no longer lands on $armLanding, so the shell and " +
                    "the destination disagree about Back and only one of them is " +
                    "shipped at a time",
                arm!!.contains(armLanding)
            )
        }
    }

    @Test
    fun `the destination lands where the arm does`() {
        migratedKinds().forEach { kind ->
            val (_, variant, armLanding) = parity.first { it.first == kind }
            val route = variant.removePrefix("Screen.")
            val destination = graphBody(route)
            assertTrue("$kind has no composable<$route" + "Route> in the graph", destination != null)
            val graphLanding = when (armLanding) {
                "stableBackDestination(current.returnTo)" ->
                    "NavRoutes.landingOf(route.returnRoute)"
                else -> armLanding
            }
            assertTrue(
                "the $route destination lands on something other than $graphLanding, " +
                    "which is what its arm states: hardware Back and the graph's own " +
                    "Back would part company the moment the kind is flipped",
                destination!!.contains(graphLanding)
            )
        }
    }

    @Test
    fun `the sanitizer is the same call on both sides`() {
        val routes = stripComments(source(NAV_ROUTES))
        assertTrue(
            "the route's return argument must be decoded and then sanitized by the " +
                "same function the shell's Back used, or Back out of a migrated " +
                "drill-down can re-fire a one-shot autoplay the arm would have stripped",
            routes.contains("stableBackDestination(")
        )
    }

    @Test
    fun `no migrated destination navigates straight to another migrated kind`() {
        // The host is composed for the destination being ENTERED, and a
        // transition keeps the outgoing screen composed too - so a move between
        // two migrated kinds would ask for two NavHosts over one controller,
        // which is illegal. Until the host is owned by a wrapper, that move must
        // not exist: this test turns the next one into a failure with a reason
        // instead of a crash on a device.
        val migratedVariants = migratedKinds()
            .mapNotNull { kind -> parity.firstOrNull { it.first == kind }?.second }
            .toSet()
        val navToScreen = Regex("""onNavigate\(Screen\.(\w+)""")
        migratedKinds().forEach { kind ->
            val (_, variant, _) = parity.first { it.first == kind }
            val route = variant.removePrefix("Screen.")
            val destination = graphBody(route) ?: return@forEach
            navToScreen.findAll(destination)
                .map { it.groupValues[1] }
                .forEach { target ->
                    assertTrue(
                        "$route navigates straight to Screen.$target, which is " +
                            "already migrated: crossing between two migrated kinds " +
                            "composes two NavHosts and no single controller can " +
                            "attach to both",
                        "Screen.$target" !in migratedVariants
                    )
                }
        }
    }

    @Test
    fun `the player stays unanimated and a re-target still replays nothing`() {
        assertTrue(
            "the content key is what suppresses a same-kind re-target: with it the " +
                "shell treats Detail -> Detail as one screen, without it every " +
                "re-target replays a full-screen transition",
            shell.contains("contentKey = { it.typeName() }")
        )
        val playerRule = shell
            .substringAfter("initialState is Screen.Player || targetState is Screen.Player ->")
            .substringBefore("\n\n")
        assertTrue(
            "a transition to or from the player must stay `None`: the player is a " +
                "separate Activity that covers this one, and animating it would only " +
                "keep the outgoing screen composed behind it",
            playerRule.contains("EnterTransition.None togetherWith ExitTransition.None")
        )
    }

    private fun migratedKinds(): List<String> =
        Regex("migratedKinds = setOf\\(([^)]*)\\)")
            .find(shell)
            ?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            ?: error("the seam is no longer constructed with a migrated set")

    /** The arm's body, from its `is Screen.X ->` to the next arm at the same depth. */
    private fun armBody(variant: String): String? {
        val marker = "is $variant ->"
        val start = shell.indexOf(marker)
        if (start < 0) return null
        val rest = shell.substring(start + marker.length)
        val end = rest.indexOf("\n        is Screen.")
        return if (end < 0) rest else rest.substring(0, end)
    }

    /** The destination's body, from its `composable<XRoute>` to the next one. */
    private fun graphBody(route: String): String? {
        val marker = "composable<$route" + "Route>"
        val start = graph.indexOf(marker)
        if (start < 0) return null
        val rest = graph.substring(start + marker.length)
        val end = rest.indexOf("composable<")
        return if (end < 0) rest else rest.substring(0, end)
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
        const val GRAPH = "$PACKAGE" + "ui/navigation/NavHostGraph.kt"
        const val NAV_ROUTES = "$PACKAGE" + "ui/navigation/NavRoutes.kt"
    }
}
