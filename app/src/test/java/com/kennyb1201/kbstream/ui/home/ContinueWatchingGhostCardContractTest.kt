package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ghost Continue Watching card (spec 2026-11-12, build 262): Eternally
 * Yours S1E6 offered as "next up" after it was watched and finished - and the
 * same show missing from Upcoming, because both rails read the same evidence.
 *
 * The predicate's behaviour is pinned in [JustCompletedTrackerCardTest] (it is
 * a pure function and runs in the normal test task). What cannot be pinned
 * there is the WIRING, and the wiring is two thirds of this fix:
 *
 *  1. **F1** - the identity gate no longer decides by "does any key intersect".
 *     The id keys are weighed apart from the title, and when they do not meet
 *     the title + season + episode triple is what may drop the card - never the
 *     title alone, never the pair alone.
 *  2. **F2** - the merge consults the TRACKER FEEDS' own finished verdict, not
 *     only this device's watch_history. A completion made on another device
 *     leaves no local row, so the local rule had no evidence and the card
 *     survived every re-merge.
 *  3. **F3** - the rule stays in the merge, where every publish passes. No
 *     one-time cleanup, no bus event, no recency window: a reload, a resume
 *     refresh, a lag retry and a TTL expiry all run the same predicate.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out this file, then JUnitCore with -Duser.dir at the repo root.
 */
class ContinueWatchingGhostCardContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    private val predicate: String
        get() = between(
            source(RULES),
            "internal fun trackerCardJustCompletedByProfile(",
            "/** The prefix every title identity key carries"
        )

    // ── F1: the triple, not the intersection ───────────────────────────────

    @Test
    fun `the id keys and the title are weighed apart, so the pair can decide`() {
        val body = predicate

        assertTrue(
            "the id keys are the non-title keys, and a shared one is still the " +
                "strongest identity",
            body.contains("!key.startsWith(TITLE_KEY_PREFIX) && key in cardKeys")
        )
        assertTrue(
            "the title is compared WITHOUT its media-type bucket: a card whose " +
                "parentType collapsed to 'unknown' and a row always bucketed " +
                "'series' are one show",
            body.contains("mark.keys.any { key -> titleKeyName(key) == cardTitleName }") &&
                source(RULES).contains("private fun titleKeyName(key: String): String?")
        )
        assertTrue(
            "and when the ids do not meet, the full triple is what drops the card",
            body.contains("mark.season == cardSeason && mark.episode == cardEpisode")
        )
    }

    @Test
    fun `a title alone or an episode pair alone never drops a card`() {
        val fallback = between(predicate, "if (!idsMeet) {", "// A mark with no episode")

        assertTrue(
            "no title match, no drop - a shared episode number is not identity",
            fallback.contains("if (!titlesMeet) return@any false")
        )
        assertTrue(
            "the pair is required whenever both sides name one",
            fallback.contains("mark.season == cardSeason && mark.episode == cardEpisode")
        )
    }

    // ── F2: the tracker's own verdict is evidence ──────────────────────────

    @Test
    fun `the merge reads the feeds' own finished verdict, not only local rows`() {
        val body = predicate

        assertTrue(
            "the predicate takes the tracker's verdict",
            body.contains("caughtUpShowKeys: Set<String> = emptySet()")
        )
        assertTrue(
            "and weighs it on the card's own identity keys",
            body.contains("upNextStrongIdentityKeys(item).any { key -> key in caughtUpShowKeys }")
        )
        assertTrue(
            "the unaired-episode check still runs first: Upcoming's caught-up " +
                "card is the one legitimate card for a finished show",
            body.indexOf("trackerCardNamesUnairedEpisode(item)") in
                0 until body.indexOf("caughtUpShowKeys.isNotEmpty()")
        )

        val viewModel = source(HOME_VIEW_MODEL)
        assertTrue(
            "the caller asks WatchedStatusRepository - the feeds' watched state " +
                "merged with the profile's rows",
            viewModel.contains("private suspend fun trackerCaughtUpShowKeys(") &&
                viewModel.contains("watchedStatusRepository.isWatchedCached(form, mediaType)")
        )
        assertTrue(
            "both id flavors a card can be cached under are tried",
            viewModel.contains("?.let { tmdb -> \"tmdb:\$tmdb\" }")
        )
        assertTrue(
            "and the verdict is handed to the pure rule",
            viewModel.contains("trackerCaughtUpShowKeys(items)") &&
                viewModel.contains("caughtUpShowKeys")
        )
    }

    @Test
    fun `no local rows is not a reason to skip the tracker evidence`() {
        val viewModel = source(HOME_VIEW_MODEL)

        assertTrue(
            "the early return is skipped when the tracker has an answer: 'no " +
                "local rows' is exactly the cross-device case F2 is about",
            viewModel.contains(
                "if (recentRows.isEmpty() && completedRows.isEmpty() && " +
                    "caughtUpShowKeys.isEmpty()) {"
            )
        )
        val rules = source(RULES)
        assertTrue(
            "and the pure path keeps a tracker-only verdict alive too",
            rules.contains("if (completedRows.isEmpty() && caughtUpShowKeys.isEmpty()) return items") &&
                rules.contains("if (marks.isEmpty() && caughtUpShowKeys.isEmpty()) return items")
        )
        assertTrue(
            "only positive verdicts are used: a cold or failed read keeps the card",
            viewModel.contains("} catch (_: Exception) {") &&
                viewModel.contains("false")
        )
        assertFalse(
            "a title is never enough on its own here - the verdict keys are the " +
                "card's ids, so a namesake cannot be caught by the name",
            viewModel.contains("add(upNextTitleKey(item))") ||
                viewModel.contains("add(upNextTitleKeyFor(")
        )
    }

    // ── F3: the rule lives in the merge ────────────────────────────────────

    @Test
    fun `the drop is the merge's, run on every publish`() {
        val viewModel = source(HOME_VIEW_MODEL)

        assertTrue(
            "the predicate is reached through the merge's own drop",
            viewModel.contains("dropTrackerCardsForCompletedTargets(")
        )
        assertEquals(
            "and the merge calls it on every publish, not once from a completion " +
                "event: a reload, a resume refresh, a lag retry and a TTL expiry " +
                "all pass through these two gates",
            2,
            Regex("dropJustCompletedTrackerCards\\(profileSafe\\)|" +
                "dropJustCompletedTrackerCards\\(filtered\\)").findAll(viewModel).count()
        )
    }

    private companion object {
        const val RULES = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
