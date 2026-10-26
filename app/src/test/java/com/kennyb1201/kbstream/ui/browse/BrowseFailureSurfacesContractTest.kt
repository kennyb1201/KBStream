package com.kennyb1201.kbstream.ui.browse

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three ways a browse page used to fail SILENTLY.
 *
 *  1. **A card press with no `imdb_id`** (C3). `resolveAndNavigate` navigated
 *     only on a non-blank IMDb id and had no else branch, so a TMDB entry with
 *     no `imdb_id` - or one whose resolution failed offline - did nothing at
 *     all when pressed. The Detail route resolves a TMDB id itself (the actor
 *     page's cards have always relied on that), so the press now opens the
 *     title under its `tmdb:<n>` id instead of dying.
 *  2. **A menu mark with no `imdb_id`** (C6). `markAsWatched` / `markUnwatched`
 *     returned on the same null, so the menu dismissed and wrote nothing - while
 *     the badge it was meant to change works WITHOUT IMDb resolution at all
 *     (the `tmdb:<n>` flavor). The mark now writes that same flavor, which is
 *     the key the tile's own badge check consults.
 *  3. **A total rail failure reading as an empty category** (C4). Per-rail
 *     failures are converted to flags, not throws, and a failed rail is dropped
 *     exactly like a genuinely empty one - so a TMDB outage produced an empty
 *     page, the screens took their EMPTY branch, and the error branch they
 *     already had (the one with the retry) could not be reached by the failure
 *     path at all. The rail layer now reports "every rail failed" as a
 *     failure, which is the only layer that can tell the two apart.
 */
class BrowseFailureSurfacesContractTest {

    private val sectionViewModels = listOf(
        "ui/tag/TagViewModel.kt" to "normalizedType",
        "ui/studio/StudioViewModel.kt" to "normalizedType",
        "ui/decade/DecadeViewModel.kt" to "normalizedType",
        "ui/collection/CollectionViewModel.kt" to "\"movie\""
    )

    @Test
    fun `a card press opens the title under its tmdb id when there is no imdb one`() {
        sectionViewModels
            .filterNot { (path, _) -> path.endsWith("CollectionViewModel.kt") }
            .forEach { (path, _) ->
                val vm = normalized(code(path))
                assertTrue(
                    "$path: the route id falls back to the tmdb flavor the rail item " +
                        "already carries (the actor page's shape), instead of navigating " +
                        "only on a non-blank IMDb id",
                    vm.contains(
                        "val routeId = imdbId?.takeIf { it.isNotBlank() } " +
                            "?: WatchedStatusKeys.tmdbKeyId(tmdbId)"
                    ) &&
                        vm.contains("onNavigateDetail(normalizedType, routeId)")
                )
                assertFalse(
                    "$path: the silent press is what it replaced",
                    vm.contains("if (!imdbId.isNullOrBlank()) {")
                )
            }
    }

    @Test
    fun `a menu mark writes the tmdb flavor when there is no imdb one`() {
        sectionViewModels.forEach { (path, type) ->
            val vm = normalized(code(path))
            assertTrue(
                "$path: mark-as-watched has to land somewhere the badge reads - the " +
                    "mark's own null return was a no-op on the row the viewer pressed",
                vm.contains(
                    "markWatchedLocal( WatchedStatusKeys.tmdbKeyId(tmdbId), $type )"
                )
            )
            assertTrue(
                "$path: and unmark has to clear the same flavor",
                vm.contains(
                    "markUnwatchedLocal( WatchedStatusKeys.tmdbKeyId(tmdbId), $type )"
                )
            )
            assertFalse(
                "$path: the silent no-op return is gone",
                vm.contains(".getOrNull() ?: return@launch")
            )
        }
    }

    @Test
    fun `every rail failing is reported as a failure, not as an empty page`() {
        val stream = normalized(code("data/tmdb/BrowseRailStream.kt"))
        assertTrue(
            "the rail layer has to name the total failure - the screens cannot tell " +
                "an outage from an empty category out of `sections.isEmpty()` alone",
            stream.contains("internal class BrowseRailsUnavailableException(") &&
                stream.contains("val failedRails: Int,")
        )
        assertTrue(
            "and count the rails that reported failed (the flag the dimension rails " +
                "carry instead of throwing)",
            stream.contains("if (deep.failed) failedRails.incrementAndGet()")
        )
        assertTrue(
            "throwing only when NOTHING was published and every rail failed: one " +
                "unreachable rail still leaves the page standing, and a page whose " +
                "rails answered with no rows is untouched",
            stream.contains(
                "if (finished.isEmpty() && titles.isNotEmpty() && " +
                    "failedRails.get() >= titles.size) {"
            ) &&
                stream.contains("throw BrowseRailsUnavailableException(")
        )
    }

    @Test
    fun `the discover-backed rails carry the failure flag too`() {
        val repo = normalized(code("data/tmdb/TmdbRepository.kt"))
        assertTrue(
            "a decade / service / cross-genre rail used to collapse every fetch " +
                "failure into an empty list, so those pages could never report an " +
                "outage at all",
            repo.contains("private data class DiscoverRail(") &&
                repo.contains("): DiscoverRail = coroutineScope {") &&
                repo.contains("?: return@coroutineScope DiscoverRail(emptyList(), page + 1, failed = true)")
        )
        assertTrue(
            "and the loaders that re-wrap a rail page keep the flag instead of " +
                "building a fresh page with failed = false",
            occurrences(repo, "failed = rail.failed") >= 3 &&
                occurrences(repo, "failed = result.failed") >= 2
        )
        assertTrue(
            "a service rail swallows its own failure as it always has - it now also " +
                "says the fetch failed, which is what the all-rails-failed rule needs",
            repo.contains("}.getOrDefault(TagRailPage(emptyList(), false, failed = true))")
        )
    }

    @Test
    fun `the error branch the failure now reaches still carries a retry`() {
        listOf(
            "ui/tag/TagScreen.kt",
            "ui/studio/StudioScreen.kt",
            "ui/decade/DecadeScreen.kt"
        ).forEach { path ->
            val screen = code(path)
                val errorBranch = screen
                    .substringAfter("error != null -> {")
                    .substringBefore("sectionsRaw.any")
                assertTrue(
                    "$path: the branch a failed-every-rail load now takes has to keep " +
                        "its retry, or the outage is a dead end",
                    errorBranch.contains("message = \"Error: \$error\"") &&
                        errorBranch.contains("onRetry = {")
                )
                assertFalse(
                    "$path: and that branch must not be the empty one - the whole " +
                        "finding is that the outage reached the wrong branch",
                    errorBranch.contains("Nothing found for")
                )
            }
        val phone = code("ui/mobile/BrowseSectionsMobile.kt")
        assertTrue(
            "the phone's error branch is keyed on error with nothing drawn, and " +
                "carries TRY AGAIN",
            phone.contains("error != null && sections.isEmpty() ->") &&
                phone.contains("action = \"TRY AGAIN\"") &&
                phone.contains("onAction = onRetry")
        )
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

    private fun code(path: String): String {
        val file = File(sourceRoot, "com/kennyb1201/kbstream/$path")
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun normalized(text: String): String =
        text.lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
            }
            .joinToString("\n")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1
}
