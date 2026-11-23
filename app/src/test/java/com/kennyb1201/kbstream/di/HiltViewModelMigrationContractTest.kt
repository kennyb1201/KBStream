package com.kennyb1201.kbstream.di

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ViewModel that has moved onto the graph stays moved, and stays correct.
 *
 * Spec 2026-11-22, item 3, F1. The migration is one class per commit, so this
 * file grows a name at a time: [MIGRATED] is the set that must be graph-built,
 * and every entry is checked for the two things that could quietly regress - a
 * screen falling back to the default factory (which would build a SECOND
 * instance beside the injected one) and a move that takes a class's
 * profile-scoped reads with it.
 *
 * Pinned at the source, like the module contract beside it: instantiating a
 * `@HiltViewModel` needs a running Hilt component.
 */
class HiltViewModelMigrationContractTest {

    @Test
    fun `every migrated view model is graph-built`() {
        MIGRATED.forEach { name ->
            val source = source("$UI$name.kt")
            assertTrue("$name is @HiltViewModel", source.contains("@HiltViewModel"))
            assertTrue(
                "$name's constructor is the graph's",
                source.contains("@Inject constructor(")
            )
        }
    }

    /**
     * The first move, and the reason this file exists: the class that moved is
     * the one whose reads follow the active profile. Construction was all that
     * was supposed to change.
     */
    @Test
    fun `the first move left the profile rules where they were`() {
        val viewModel = source("$UI/settings/YearReviewViewModel.kt")
        assertTrue(
            "the scoped database is still resolved per load",
            viewModel.contains("getInstanceScoped")
        )
        assertTrue(
            "the active profile is still read at load time",
            viewModel.contains("ProfileStorage.activeProfileId")
        )
    }

    @Test
    fun `the screen asks the graph instead of the default factory`() {
        val screen = source("$UI/settings/YearReviewScreen.kt")
        assertTrue(
            "the screen resolves its view model from the entry point",
            screen.contains("= hiltViewModel()")
        )
        assertFalse(
            "the default factory would build a second instance beside the injected one",
            screen.contains("= viewModel()")
        )
    }

    @Test
    fun `the activity hosting it is an entry point`() {
        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue(
            "hiltViewModel() resolves through the activity's entry point",
            activity.contains("@AndroidEntryPoint\nclass MainActivity")
        )
    }

    /**
     * The navigation-free bridge, deliberately: `hilt-navigation-compose` exists
     * and would work, but it pulls navigation-compose in, and the relay's order
     * puts the navigation migration (item 5) after Cast. A migration must not
     * hand the next spec its dependencies early.
     */
    @Test
    fun `the bridge is a declared dependency, and it is the navigation-free one`() {
        assertTrue(
            "the catalog names androidx.hilt:hilt-lifecycle-viewmodel-compose",
            rootFile("gradle/libs.versions.toml")
                .contains("androidx.hilt:hilt-lifecycle-viewmodel-compose")
        )
        assertTrue(
            "the app depends on it",
            rootFile("app/build.gradle.kts")
                .contains("libs.androidx.hilt.lifecycle.viewmodel.compose")
        )
        assertFalse(
            "navigation-compose belongs to item 5, not to this migration",
            rootFile("app/build.gradle.kts").contains("navigation.compose")
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun rootFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val settings = File(dir, "settings.gradle.kts")
            if (settings.isFile) {
                val file = File(dir, path)
                assertTrue("file missing: $file", file.isFile)
                return file
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "repository root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val UI = "com/kennyb1201/kbstream/ui"

        /** The classes that have moved onto the graph, in migration order. */
        val MIGRATED = listOf("settings/YearReviewViewModel")
    }
}
