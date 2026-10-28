package com.kennyb1201.kbstream.ui.actor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The actor-screen deep dive's fixes, pinned where a JVM cannot compose them.
 *
 * The pure rules are unit-tested directly ([ActorFilmographyRulesTest]); what
 * this pins is the WIRING the dive could only see in source: the crew section on
 * both screens, the error and empty branches, the silent-press fallbacks, the
 * sibling-profile filter on the badge sets, and the null-person state.
 *
 * Read from the source: these are Compose branches, a ViewModel that wants an
 * Application, and two profiles, none of which stand up on a JVM.
 */
class ActorDeepDiveContractTest {

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun viewModel(): String = source(VIEW_MODEL)
    private fun tv(): String = source(TV_SCREEN)
    private fun mobile(): String = source(MOBILE_SCREEN)

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    // ── C1: the crew section ─────────────────────────────────────────────

    @Test
    fun `the actor page has a crew section on both screens`() {
        val vm = viewModel()
        assertTrue(
            "the cast-only sort is the page that hid the work a director or " +
                "writer was tapped for; the crew rule must exist",
            vm.contains("fun sortedCrewCredits(person: TmdbPersonDetail?): List<TmdbPersonCredit>") &&
                vm.contains("internal fun sortedCrewFilmography(")
        )
        val tvSource = tv()
        assertTrue(
            "the TV page must paint a crew rail",
            tvSource.contains("\"DIRECTING & WRITING\"") &&
                tvSource.contains("item(key = ACTOR_CREW_RAIL)") &&
                tvSource.contains("viewModel.sortedCrewCredits(p)")
        )
        val mobileSource = mobile()
        assertTrue(
            "and so must the phone page",
            mobileSource.contains("SectionLabel(\"DIRECTING & WRITING\")") &&
                mobileSource.contains("viewModel.sortedCrewCredits(person)")
        )
    }

    // ── C2: the zero-credits error consults cast AND crew ────────────────

    @Test
    fun `the zero-credits error is gated on the sections actually drawn`() {
        assertTrue(
            "the error must not fire for a crew-only person whose page has work to draw",
            viewModel().contains("if (result != null && drawnCredits(result).isEmpty()) {")
        )
    }

    @Test
    fun `the TV error branch is gated on there being no person, as on the phone`() {
        assertTrue(
            "a loaded person's header must not be replaced by the zero-credits " +
                "message - the same gate the phone page already applied",
            tv().contains("error != null && loadedPerson == null ->")
        )
        assertTrue(
            "and the phone keeps its own gate",
            mobile().contains("error != null && person == null ->")
        )
    }

    // ── C3: a press never dies silently on a missing IMDb id ─────────────

    @Test
    fun `a card press falls back to the TMDB id when IMDb resolution fails`() {
        val vm = viewModel()
        assertTrue(
            "the Detail route resolves a TMDB id itself, so the press must navigate " +
                "by it rather than dropping the click",
            vm.contains(
                "val routeId = imdbId?.takeIf { it.isNotBlank() } ?: " +
                    "WatchedStatusKeys.tmdbKeyId(tmdbId)"
            ) && vm.contains("onNavigateDetail(normalizedType, routeId)")
        )
    }

    @Test
    fun `the watched menu rows fall back to the TMDB flavor too`() {
        val vm = viewModel()
        assertTrue(
            "Mark as Watched must not be a silent no-op when there is no IMDb id",
            vm.contains("markWatchedLocal( WatchedStatusKeys.tmdbKeyId(tmdbId), normalizedType )")
        )
        assertTrue(
            "nor Mark as Unwatched",
            vm.contains("markUnwatchedLocal( WatchedStatusKeys.tmdbKeyId(tmdbId), normalizedType )")
        )
    }

    // ── C6: the sibling-profile filter on both badge sets ────────────────

    @Test
    fun `both badge sets drop titles another profile owns`() {
        val vm = viewModel()
        assertEquals(
            "the checkmark AND the eye sets read the same account-scoped cache the " +
                "ownership filter exists for",
            2,
            occurrences(vm, "val siblingOwned = siblingOwnedMarkerIdsFor(personDetail, resolvedMap)")
        )
        // Collapsed to one line: the predicate is split over three lines in the
        // source, and the shape being pinned is the conjunction, not the wrap.
        val flattened = vm.replace(Regex("\\s+"), " ")
        assertEquals(
            "and both apply the filter at publication, as Home does - exempting only " +
                "a key this profile marked itself (spec 'explicit mark wins')",
            2,
            occurrences(
                flattened,
                ".filterNot { key -> keyOwnedByAnotherProfile(key, siblingOwned) && " +
                    "key !in explicitWatched }"
            )
        )
        assertTrue(
            "and that exemption is the live override set, read for this publication",
            vm.contains("watchedStatusRepository.explicitWatchedOverrideKeys()")
        )
        assertTrue(
            "the ownership map and profile id are what the filter is built from",
            vm.contains("private fun siblingOwnedMarkerIdsFor(") &&
                vm.contains("TitleProfileOwnership.snapshot(getApplication())")
        )
    }

    // ── C7: a null person names the state on both screens ────────────────

    @Test
    fun `a person that loaded nothing says so, with a retry, on both screens`() {
        val tvSource = tv()
        assertTrue(
            "the TV `when` used to fall off the end into a blank screen",
            tvSource.contains("message = \"Couldn't load this person.\"") &&
                tvSource.contains("onRetry = { viewModel.load(actorId) }")
        )
        val mobileSource = mobile()
        assertTrue(
            "the phone used to leave a name-less header and nothing else",
            mobileSource.contains("message = \"Couldn't load this person.\"") &&
                mobileSource.contains("onAction = { viewModel.load(actorId) }")
        )
    }

    // ── I1: the empty state, on TV too ───────────────────────────────────

    @Test
    fun `the TV page names the empty filmography the phone already named`() {
        assertTrue(
            "a loaded person with every credit hidden drew the bio and nothing else",
            tv().contains("\"No credits to show for this person.\"") &&
                tv().contains("if (movies.isEmpty() && tvShows.isEmpty() && crewCredits.isEmpty()) {")
        )
        assertTrue(
            "and the phone's empty state covers the crew section too",
            mobile().contains("if (movies.isEmpty() && tvShows.isEmpty() && crewCredits.isEmpty()) {")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/actor/ActorViewModel.kt"
        const val TV_SCREEN = "com/kennyb1201/kbstream/ui/actor/ActorScreen.kt"
        const val MOBILE_SCREEN = "com/kennyb1201/kbstream/ui/mobile/ActorScreenMobile.kt"
    }
}
