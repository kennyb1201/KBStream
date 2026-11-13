package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caught-up Upcoming card is exempt from the Continue Watching
 * just-completed rule (spec 2026-11-12, F1/F2/F3).
 *
 * Reported on build 253, field-verified twice on the same install with a warm
 * arts cache: MobLand, Tulsa King, Eternally Yours and The Unbelievable with Dan
 * Aykroyd - each with a next episode airing the following day, each caught up -
 * were missing from Upcoming. The paste showed the whole upstream chain healthy
 * (gate, feed 20, pool 20 dealt / 20 settled, 48 enriched, 13 future-dated, none
 * of the four in any drop line) and the loss happening at one step: 48 caught-up
 * candidates became 23 cards in `applyContinueWatchingDismissals`.
 *
 * The rule that ate them is right for Continue Watching and meaningless for
 * Upcoming: "never offer an episode this profile already watched" cannot be
 * true of an episode that has not aired, and the caught-up card is the ONLY
 * route to Upcoming for such a show - finishing the last aired episode removes
 * the local resume row, so the local leg has nothing to offer, and the filter
 * then took the tracker card too by assuming that leg "owns the show's next
 * card anyway" (F3: it does not, for exactly this case).
 *
 * These assertions pin the three parts of the fix, none of which a unit test
 * can reach end to end (the gate is a private ViewModel suspend function over a
 * database and two network feeds):
 *
 *  - Upcoming lists skip the completion drop, and still run the safety gates;
 *  - a card that PROVES its episode is unaired survives the predicate anywhere;
 *  - the caught-up card names the episode it advertises (F2), so the
 *    exact-match branch works as documented.
 */
class UpcomingCaughtUpExemptionContractTest {

    private val home by lazy { source(HOME_VIEW_MODEL) }
    private val rules by lazy { source(HOME_UP_NEXT) }

    // ── F1: the gate, and who is exempt from which half of it ─────────────

    @Test
    fun `the publish gate takes an upcoming flag, and only the completion drop honours it`() {
        val gate = home.substringAfter("private suspend fun applyContinueWatchingDismissals(")
            .substringBefore("\n    /**\n     * The items whose title is positively owned")

        assertTrue(
            "the gate needs to be told which rail it is filtering",
            gate.contains("upcoming: Boolean = false")
        )
        assertTrue(
            "a Continue Watching publish still runs the drop - both paths, including " +
                "the no-dismissals early return",
            gate.contains("if (upcoming) profileSafe else dropJustCompletedTrackerCards(profileSafe)") &&
                gate.contains("if (upcoming) filtered else dropJustCompletedTrackerCards(filtered)")
        )
        assertTrue(
            "Kids Mode still applies to Upcoming - it is a safety rule, not a viewing one",
            gate.contains("kidsFilterUpNext(items)")
        )
        assertTrue(
            "and so does profile ownership",
            gate.contains("applyTrackerTitleOwnership(kidsSafe)")
        )
        assertTrue(
            "and the viewer's own dismissals",
            gate.contains("continueWatchingDismissalOutcome(")
        )
    }

    @Test
    fun `both Upcoming legs pass the flag and neither Continue Watching path does`() {
        val flagged = callSites(home, "applyContinueWatchingDismissals(")
            .count { arguments -> arguments.contains("upcoming = true") }
        assertEquals(
            "exactly the two Upcoming legs (the Simkl caught-up leg and the " +
                "local + MDBList one) are Upcoming-only lists",
            2,
            flagged
        )
        assertTrue(
            "the rail publish does NOT pass it: a tracker card for a finished episode " +
                "must still leave Continue Watching (S2)",
            home.contains("_upNext.value = applyContinueWatchingDismissals(")
        )
        assertTrue(
            "and neither does the merge's dismiss hook",
            home.contains("dismiss = { items -> applyContinueWatchingDismissals(items) }")
        )
    }

    @Test
    fun `the caught-up cards are Upcoming-only, which is what makes the exemption safe`() {
        // Neither leg publishes to [upNext]; the rail is handed its own list.
        assertTrue(
            "the Simkl leg keeps its cards off Continue Watching",
            home.contains("lastCaughtUpUpcomingItems = upcomingCards")
        )
        assertTrue(
            "and the local + MDBList leg does the same",
            home.contains("lastLocalCaughtUpUpcomingItems = cards")
        )
    }

    // ── F1's other half: the predicate itself ─────────────────────────────

    @Test
    fun `the predicate exempts a card proven to name an unaired episode`() {
        val predicate = rules.substringAfter("internal fun trackerCardJustCompletedByProfile(")
            .substringBefore("internal fun dropTrackerCardsForCompletedTargets(")

        val proofAt = predicate.indexOf("trackerCardNamesUnairedEpisode(item)")
        val movieBranchAt = predicate.indexOf("if (mark.season == null || mark.episode == null)")

        assertTrue("the proof must be applied at all", proofAt >= 0)
        assertTrue(
            "and it must run BEFORE any drop branch, or the branches decide first",
            movieBranchAt > proofAt
        )
        assertTrue(
            "proven unaired means kept, not dropped",
            predicate.contains("if (trackerCardNamesUnairedEpisode(item)) return false")
        )
        assertTrue(
            "the proof is the episode-level one: the air record must name the CARD's episode",
            rules.contains("internal fun trackerCardNamesUnairedEpisode(") &&
                rules.contains("episodeProvablyUnaired(")
        )
    }

    @Test
    fun `the 'no episode named' branch is still there for Continue Watching`() {
        // S2 depends on this: the exemption is for cards that PROVE they are
        // unaired, and a card that proves nothing is still dropped.
        val predicate = rules.substringAfter("internal fun trackerCardJustCompletedByProfile(")
            .substringBefore("internal fun dropTrackerCardsForCompletedTargets(")
        assertTrue(
            predicate.contains("if (cardSeason == null || cardEpisode == null) return@any true")
        )
        assertTrue(
            predicate.contains("mark.season == cardSeason && mark.episode == cardEpisode")
        )
    }

    // ── F2: the card names the episode it advertises ──────────────────────

    @Test
    fun `the Simkl caught-up leg moves the card's pair onto its next episode`() {
        assertTrue(
            "one pure helper decides it",
            rules.contains("internal fun upcomingCardWithNextEpisodePair(item: UpNextItem): UpNextItem") &&
                rules.contains("if (item.season == season && item.episode == episode) return item") &&
                rules.contains("season = season,") &&
                rules.contains("episode = episode,")
        )
        val leg = home.substringAfter("private suspend fun loadCaughtUpUpcomingItems()")
            .substringBefore("private suspend fun buildCaughtUpUpcomingCard(")
        assertTrue(
            "and the caught-up leg applies it before the gate that was dropping the cards",
            leg.contains("built.map { item -> upcomingCardWithNextEpisodePair(item) }")
        )
        assertTrue(
            "a card with nothing dated keeps its nulls - the pair is never invented",
            rules.contains("val air = item.nextEpisodeAir ?: return item")
        )
    }

    @Test
    fun `the local + MDBList leg already builds its card on the next episode`() {
        // F2's question for this leg: does the card carry the pair today? It
        // does - the builder reads it straight off the air info it advertises.
        val builder = home.substringAfter("private suspend fun buildCaughtUpUpcomingCard(")
            .substringBefore("private suspend fun upcomingAirForCaughtUp(")
        assertTrue(
            builder.contains("season = air?.seasonNumber,") &&
                builder.contains("episode = air?.episodeNumber,")
        )
    }

    // ── F3: the fix stands alone ──────────────────────────────────────────

    @Test
    fun `nothing makes the local leg emit a card for a show with no local row`() {
        // The other way to "fix" this would be to keep a stale resume row
        // alive for a caught-up show. That is the bug the cleanup exists to
        // prevent, and it must not be reintroduced.
        assertTrue(
            "the exemption is in the filter, not in what the local leg builds",
            home.contains("upcoming = true")
        )
        assertTrue(
            "and the local leg still only builds from the rows it has",
            home.contains("private suspend fun buildCaughtUpUpcomingCard(")
        )
    }

    /**
     * The argument text of every call to [callee], with nested calls intact:
     * the flag is the LAST argument, after builders and lambdas that contain
     * parentheses of their own.
     */
    private fun callSites(src: String, callee: String): List<String> {
        val sites = ArrayList<String>()
        var from = src.indexOf(callee)
        while (from >= 0) {
            var index = from + callee.length
            var depth = 1
            while (index < src.length && depth > 0) {
                when (src[index]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                }
                index++
            }
            sites.add(src.substring(from + callee.length, index - 1))
            from = src.indexOf(callee, index)
        }
        return sites
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val HOME_UP_NEXT = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
    }
}
