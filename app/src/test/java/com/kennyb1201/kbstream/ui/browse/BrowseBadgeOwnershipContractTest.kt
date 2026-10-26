package com.kennyb1201.kbstream.ui.browse

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sibling-profile filter on the four browse pages' badge sets.
 *
 * The watched cache key is `"type::id"` with no profile component, and the
 * tracker feeds behind it are per ACCOUNT, so a kids-profile completion can
 * badge a title on Profile 1's genre / studio / decade / collection page. Home
 * and the actor page already publish their badge sets through
 * [titleOwnedByAnotherProfile] (`HomeViewModel.keyOwnedByAnotherProfile`,
 * `ActorViewModel.siblingOwnedMarkerIdsFor`); these four read the same cache
 * family through the same methods and had no filter at all - neither set, at
 * any point.
 *
 * Browse sets are section-derived, so unlike the search screen's they do not
 * accumulate across profile switches by themselves: the window here is a page
 * that keeps its sections (the same-route guards skip the reload) or one that
 * is composed after a switch. The filter is what closes both.
 */
class BrowseBadgeOwnershipContractTest {

    private val viewModels = listOf(
        "tag/TagViewModel.kt",
        "studio/StudioViewModel.kt",
        "decade/DecadeViewModel.kt",
        "collection/CollectionViewModel.kt"
    )

    private val filter = ".filterNot { key -> keyOwnedByAnotherProfile(key, siblingOwned) }"

    @Test
    fun `both badge sets are filtered at publication`() {
        viewModels.forEach { path ->
            val vm = normalized(code(path))
            assertEquals(
                "$path: the checkmark AND the eye set have to drop the keys another " +
                    "profile owns - filtering one of them is how Home's own " +
                    "completed-vs-eye asymmetry was found",
                2,
                occurrences(vm, filter)
            )
            assertEquals(
                "$path: and the ids they are filtered against are resolved once per " +
                    "publication, from the sections that publication is about",
                2,
                occurrences(vm, "val siblingOwned = siblingOwnedMarkerIdsFor(")
            )
            assertTrue(
                "$path: the set has to be resolved BEFORE the flatMap that builds the " +
                    "keys, or the filter reads the previous publication's ids",
                vm.indexOf("val siblingOwned = siblingOwnedMarkerIdsFor(") <
                    vm.indexOf(".flatMap { studioItem ->") ||
                    vm.indexOf("val siblingOwned = siblingOwnedMarkerIdsFor(") <
                    vm.indexOf(".flatMap { part ->")
            )
            assertTrue(
                "$path: and the partial set still subtracts the completed one, as a Set",
                vm.contains(".toSet() - watchedKeys.value")
            )
        }
    }

    @Test
    fun `the ownership answers come from the one ownership rule`() {
        viewModels.forEach { path ->
            val vm = normalized(code(path))
            assertTrue(
                "$path: this is the tracker cards' rule, not a second definition of " +
                    "'another profile owns it'",
                vm.contains("private fun siblingOwnedMarkerIdsFor(") &&
                    vm.contains("TitleProfileOwnership.snapshot(getApplication())") &&
                    vm.contains("titleOwnedByAnotherProfile(") &&
                    vm.contains("ProfileManager.activeProfile.value?.id")
            )
            assertTrue(
                "$path: and the key test is the shared shape - the key the badge sets " +
                    "carry ENDS WITH `::\$id`, it is not the id itself",
                vm.contains("private fun keyOwnedByAnotherProfile(") &&
                    vm.contains("siblingOwned.any { id -> key.endsWith(\"::\$id\") }")
            )
            assertTrue(
                "$path: both id flavors of an owned title are collected, or the " +
                    "unresolved half of a page keeps its badge",
                vm.contains("WatchedStatusKeys.tmdbKeyId(") &&
                    vm.contains("listOfNotNull(")
            )
        }
    }

    @Test
    fun `the drop is positive-evidence only, on browse too`() {
        // The rule itself is TitleProfileOwnership's: no owner recorded, no live
        // profile or no title key all answer false. Pinned at the source so a
        // future "hide anything not provably mine" edit cannot slip into the
        // browse pages and strip the cross-device badge.
        viewModels.forEach { path ->
            val vm = code(path)
            assertTrue(
                "$path: no title name, no ownership key, no owner map - all answer " +
                    "'not owned', which is what keeps an unattributed title badged",
                vm.contains("if (owners.isEmpty()) return emptySet()") &&
                    vm.contains("?: return emptySet()")
            )
            assertFalse(
                "$path: the filter must not be re-implemented as an id comparison " +
                    "against the resolved map",
                vm.contains("siblingOwnedMarkerIds.contains")
            )
        }
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    /**
     * The source with comment lines dropped, so the rules above are stated about
     * the CODE: these files' comments name the filter and the helper, and
     * asserting against that prose would grade the documentation.
     */
    private fun code(path: String): String =
        File(sourceRoot, "com/kennyb1201/kbstream/ui/$path")
            .readText()
            .lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
            }
            .joinToString("\n")

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1
}
