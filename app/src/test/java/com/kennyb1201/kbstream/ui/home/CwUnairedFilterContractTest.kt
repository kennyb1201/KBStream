package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching withholds a tracker card whose own episode has not aired
 * (spec 2026-11-13).
 *
 * Reported on 0.9.267: MobLand S2E9 and Tulsa King S4E5 sat in Continue Watching
 * on the day they air, and in Upcoming at the same time. Both were Simkl-leg
 * cards whose feed still saw them as not caught up, so the merged walk handed the
 * builder the tracker's next pair without asking whether it had aired.
 *
 * WHY THE FIX LIVES AT THE READ. `_upNext` is ONE list feeding both rails:
 * `observeUpcomingSchedule` builds Upcoming's leg (a) from the very cards CW was
 * showing, and for these shows that leg is the ONLY route into Upcoming. So every
 * obvious repair - dropping the card in `dropJustCompletedTrackerCards`, nulling
 * the resolver's fall-through, returning null from the Simkl builder, tightening
 * the feed gate - either does nothing (the builder falls back to the tracker's
 * own pair) or removes the show from Upcoming as well, which is the regression
 * the 2026-11-12 spec was built around. The filter therefore runs where CW is
 * READ, beside the hidden-titles pass, and Upcoming's own flow never sees it.
 */
class CwUnairedFilterContractTest {

    private val rules by lazy { source(HOME_UP_NEXT) }
    private val home by lazy { source(HOME_SCREEN) }
    private val phone by lazy { source(PHONE_HOME) }
    private val viewModel by lazy { source(HOME_VIEW_MODEL) }

    // ── The predicate ────────────────────────────────────────────────────

    @Test
    fun `the rule needs all three proofs before it withholds a card`() {
        val predicate = rules.substringAfter("internal fun upNextUnairedForCW(")
            .substringBefore("internal fun upNextContinueWatchingDecision(")

        assertTrue(
            "1. tracker cards only - local cards are built from rows the walk " +
                "already skips unaired episodes for",
            predicate.contains("if (!isTrackerSourcedCard(item)) return false")
        )
        assertTrue(
            "2. a real resume keeps its card whatever the air date says",
            predicate.contains("if (item.startPositionMs != 0L) return false")
        )
        assertTrue(
            "3. and the air record must prove this CARD's episode is unaired",
            predicate.contains("return trackerCardNamesUnairedEpisode(item, today)")
        )
        assertTrue(
            "the date is the caller's, so one pass cannot see two days",
            rules.contains(
                "internal fun upNextUnairedForCW(\n" +
                    "    item: UpNextItem,\n" +
                    "    today: LocalDate = LocalDate.now(ZoneId.systemDefault())\n" +
                    "): Boolean"
            )
        )
    }

    @Test
    fun `the proof is the narrow one, so the failures stay failed-open`() {
        // Everything this rule must NOT touch is decided by the shared proof:
        // no air info, an air record naming a different episode (the stale card
        // for the episode just finished), a movie, an episode TMDB has not
        // listed. All of those answer false and the card stays.
        val proof = source(AIR_DATE_RULES).substringAfter("internal fun episodeProvablyUnaired(")
            .substringBefore("fun ")

        assertTrue(
            "the air record has to name THIS card's (season, episode)",
            proof.contains("if (airSeason?.takeIf { it > 0 } != season) return false") &&
                proof.contains("if (airEpisode?.takeIf { it > 0 } != episode) return false")
        )
        assertTrue(
            "and date it today or later - a past date is not a proof",
            proof.contains("return !date.isBefore(today)")
        )
        assertTrue(
            "the tracker-ahead-of-TMDB card has no air info at all, so it stays",
            proof.contains("val season = cardSeason?.takeIf { it > 0 } ?: return false")
        )
    }

    // ── The read sites ───────────────────────────────────────────────────

    @Test
    fun `both screens filter the CW read, and neither keeps the old verdict alone`() {
        listOf("TV" to home, "phone" to phone).forEach { (name, src) ->
            val block = src.substringAfter("val cwFilterPass = remember(")
                .substringBefore("val upcoming")
            assertTrue(
                "$name: the pass must compose the unaired rule with the hidden one",
                block.contains("upNextContinueWatchingDecision(")
            )
            assertTrue(
                "$name: and drop the card on either verdict",
                block.contains(
                    "filterNot { (_, decision) -> decision.hidden || decision.dropped }"
                )
            )
            assertFalse(
                "$name: no read site may keep the hidden-only verdict",
                block.contains("filterNot { (_, decision) -> decision.hidden }")
            )
        }
    }

    @Test
    fun `the pass re-derives when the date changes, so a card returns by itself`() {
        // S3: the morning after the episode airs, the same card proves aired and
        // comes back with no publish, no re-add, no dismiss to clear.
        listOf("TV" to home, "phone" to phone).forEach { (name, src) ->
            assertTrue(
                "$name: the date is part of the pass's identity",
                src.contains("val cwFilterDate = LocalDate.now(ZoneId.systemDefault())") &&
                    src.contains(
                        "remember(upNextRaw, hiddenTitleKeys, cwShowNames, cwFilterDate)"
                    )
            )
            assertTrue(
                "$name: and it is the date the decision is given",
                src.contains("upNextContinueWatchingDecision(") &&
                    src.contains("cwFilterDate")
            )
        }
    }

    // ── F2: the drop is visible in the paste ─────────────────────────────

    @Test
    fun `the trace names this rule, without pretending it was a hidden title`() {
        assertTrue(
            "the token is appended to the line the paste carries",
            rules.contains("internal const val CW_UNAIRED_DROP_SUFFIX = \" dropped=unaired\"") &&
                rules.contains("line = hidden.line + CW_UNAIRED_DROP_SUFFIX")
        )
        assertTrue(
            "the decision carries the verdict beside hidden=, so a paste can tell " +
                "the two rules apart",
            source(HIDDEN_IDENTITY).contains("val dropped: Boolean = false")
        )

        val composed = rules.substringAfter("internal fun upNextContinueWatchingDecision(")
            .substringBefore("/**\n * The same identity, for the other direction")
        assertTrue(
            "the hidden-titles verdict itself is untouched",
            composed.contains("return hidden") &&
                composed.contains("dropped = true")
        )
        assertTrue(
            "and the same trace sink the hidden pass already writes",
            home.contains("SideEffect { CwFilterTrace.recordAll(cwFilterLines) }") &&
                phone.contains("SideEffect { CwFilterTrace.recordAll(cwFilterLines) }")
        )
    }

    // ── Nothing upstream moves ───────────────────────────────────────────

    @Test
    fun `the publish path and Upcoming are untouched`() {
        // The regression this spec exists to avoid: these names are the shared
        // path, and none of them may learn about the CW rule.
        listOf(
            "upNextUnairedForCW(",
            "upNextContinueWatchingDecision(",
            "CW_UNAIRED_DROP_SUFFIX"
        ).forEach { token ->
            assertFalse(
                "HomeViewModel must not reference $token",
                viewModel.contains(token)
            )
        }
        assertTrue(
            "`_upNext` is still published through the same gate",
            viewModel.contains("_upNext.value = applyContinueWatchingDismissals(") ||
                viewModel.contains("applyContinueWatchingDismissals(items)")
        )
        assertTrue(
            "and Upcoming still builds from the unfiltered flow",
            viewModel.contains("observeUpcomingSchedule")
        )
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
        const val ROOT = "com/kennyb1201/kbstream/ui/home/"
        const val HOME_UP_NEXT = "${ROOT}HomeUpNext.kt"
        const val HOME_SCREEN = "${ROOT}HomeScreen.kt"
        const val PHONE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val HOME_VIEW_MODEL = "${ROOT}HomeViewModel.kt"
        const val HIDDEN_IDENTITY = "${ROOT}UpNextHiddenIdentity.kt"
        const val AIR_DATE_RULES = "${ROOT}AirDateRules.kt"
    }
}
