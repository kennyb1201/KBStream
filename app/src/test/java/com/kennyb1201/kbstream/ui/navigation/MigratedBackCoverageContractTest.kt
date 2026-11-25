package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hazard every phase of this migration carries, pinned before the next flip.
 *
 * The shell's global `BackHandler` stands down for a migrated kind, because inside
 * a Nav destination that handler and the NavController's pop callback would both
 * fire. What replaces it is the destination's OWN `BackHandler`: it registers
 * inside the destination, after the NavController's callback, so the dispatcher's
 * LIFO order still hands it the press - the same ordering the screen had when it
 * was an arm of the `when`.
 *
 * A migrated kind with no local handler has neither. Its Back falls through to the
 * NavController, whose stack holds only the destination it opened on, so the pop
 * does nothing and the press continues to the activity - Back finishes the app
 * instead of landing on the opener. That is strictly worse than the arm it
 * replaced, and it is invisible until someone presses Back on a device.
 *
 * Hence the table below: a kind may only join `migratedKinds` once it is named
 * here with a source that registers a handler of its own. Adding the next
 * destination to the seam without doing that fails this test by name.
 */
class MigratedBackCoverageContractTest {

    private val shell: String by lazy { stripComments(source(MAIN_ACTIVITY)) }

    /**
     * Where each migrated kind's Back lives. Every file listed must register a
     * `BackHandler`; the phone twins are listed alongside their TV pages where
     * the screen branches on form factor, because the mobile composable is what
     * registers the handler there (the TV one returns before its own).
     */
    private val handlers: Map<String, List<String>> = mapOf(
        "remote" to listOf("$PACKAGE" + "ui/remote/RemoteScreen.kt"),
        "stats" to listOf(
            "$PACKAGE" + "ui/settings/StatsScreen.kt",
            "$PACKAGE" + "ui/mobile/StatsScreenMobile.kt"
        ),
        "catalogBuilder" to listOf(
            "$PACKAGE" + "ui/addons/CatalogBuilderScreen.kt",
            "$PACKAGE" + "ui/mobile/CatalogBuilderScreenMobile.kt"
        ),
        "simkl" to listOf(
            "$PACKAGE" + "ui/simkl/SimklConnectScreen.kt",
            "$PACKAGE" + "ui/mobile/SimklConnectScreenMobile.kt"
        ),
        "trakt" to listOf(
            "$PACKAGE" + "ui/trakt/TraktConnectScreen.kt",
            "$PACKAGE" + "ui/mobile/TraktConnectScreenMobile.kt"
        ),
        // The add-on page's handler is registered above its form-factor branch,
        // so the one file covers the TV page and the touch twin - which is the
        // point: the touch page had none and inherited the shell's.
        "addons" to listOf("$PACKAGE" + "ui/addons/AddonsScreen.kt"),
        // Two pages behind one destination, and both need their own: the connect
        // page (no handler before the migration) and the browser it opens.
        "bingecat" to listOf(
            "$PACKAGE" + "ui/bingecat/BingeCatConnectScreen.kt",
            "$PACKAGE" + "ui/bingecat/BingeCatPublicListsScreen.kt",
            "$PACKAGE" + "ui/mobile/BingeCatPublicListsScreenMobile.kt"
        ),
        "publicLists" to listOf(
            "$PACKAGE" + "ui/publiclists/PublicListsScreen.kt",
            "$PACKAGE" + "ui/mobile/PublicListsScreenMobile.kt"
        )
    )

    @Test
    fun `the shell stands down for migrated kinds, which is why a local handler is required`() {
        assertTrue(
            "the shell's global handler must stay gated on the seam: if it fires " +
                "for a migrated kind it double-pops, and if it does not, the " +
                "destination's own handler is the only thing that can land Back",
            shell.contains("BackHandler(enabled = !navSeam.isMigrated(screen))")
        )
    }

    @Test
    fun `every migrated kind registers a BackHandler of its own`() {
        val migrated = Regex("migratedKinds = setOf\\(([^)]*)\\)")
            .find(shell)
            ?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            ?: error("the seam is no longer constructed with a migrated set")

        assertTrue("the seam migrates nothing at all", migrated.isNotEmpty())

        migrated.forEach { kind ->
            val files = handlers[kind]
                ?: error(
                    "$kind was flipped onto the NavHost without an entry in this " +
                        "table: name the file that registers its BackHandler (or add " +
                        "one), because with the shell's handler standing down a " +
                        "destination that has none lets Back finish the activity " +
                        "instead of landing on the screen that opened it"
                )
            files.forEach { path ->
                val text = stripComments(source(path))
                assertTrue(
                    "$kind has no BackHandler in $path: inside the NavHost nothing " +
                        "else can land its Back, and the NavController has only the " +
                        "entered destination to pop - so the press reaches the " +
                        "activity and finishes it. A form-factor twin needs its own, " +
                        "because the other one returns before it registers one",
                    // Both call shapes: `BackHandler { ... }` answers Back with a
                    // body (the phone catalog builder cancels a draft before it
                    // leaves), and `BackHandler(onBack = ...)` hands it on.
                    Regex("BackHandler\\s*[({]").containsMatchIn(text)
                )
            }
        }
    }

    @Test
    fun `a merged kind is named once`() {
        val migrated = Regex("migratedKinds = setOf\\(([^)]*)\\)")
            .find(shell)
            ?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: error("the seam is no longer constructed with a migrated set")
        assertTrue(
            "a kind listed twice would hide a duplicate under the set's own dedupe",
            migrated.size == migrated.toSet().size
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
    }
}
