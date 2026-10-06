package com.kennyb1201.kbstream.ui.home

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Shows show up in Continue Watching at 12am the day they release, but they
 * don't actually air til later in the night of that day."
 *
 * TMDB's episode dates are a calendar day with no time, and a show's day is the
 * EVENING it airs. The next-up walk used to read "date is not after today" as
 * "has aired", so at 00:00 on the air date the show was promoted into Continue
 * Watching with a next episode that had no sources behind it for another ~15
 * hours. The rule now needs the date to be in the PAST, which is the same
 * semantics the Upcoming rail already had ("an episode airing later today still
 * shows", labeled Today): an episode dated today is upcoming, not aired.
 *
 * The pure boundary cases are pinned here, plus the two wirings that have to
 * stay consistent for the fix to mean anything - the Continue Watching episode
 * walk gating on this rule, and the Upcoming builder keeping today.
 */
class AiredEpisodeGateTest {

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
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    // ── the rule ─────────────────────────────────────────────────────

    @Test
    fun `an episode dated today has not aired yet`() {
        // The report, in one assertion: at 00:00 on the air date the episode
        // is still hours away, so it must not be offered as watchable.
        assertFalse(
            isAiredOrUnknown("2026-10-06", today = LocalDate.of(2026, 10, 6))
        )
    }

    @Test
    fun `the date only becomes aired once it is behind us`() {
        val airDate = "2026-10-06"

        assertFalse(isAiredOrUnknown(airDate, today = LocalDate.of(2026, 10, 5)))
        assertFalse(isAiredOrUnknown(airDate, today = LocalDate.of(2026, 10, 6)))
        assertTrue(isAiredOrUnknown(airDate, today = LocalDate.of(2026, 10, 7)))
        assertTrue(isAiredOrUnknown(airDate, today = LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `the default clock is today, so today is withheld`() {
        // Every call site takes the default: nothing in the app passes a date
        // in, so this has to hold with the real clock too.
        val today = LocalDate.now()

        assertFalse(isAiredOrUnknown(today.toString()))
        assertTrue(isAiredOrUnknown(today.minusDays(1).toString()))
        assertFalse(isAiredOrUnknown(today.plusDays(1).toString()))
    }

    @Test
    fun `a missing or unreadable date still counts as aired`() {
        // Failing open is still the point: this rule may only ever WITHHOLD an
        // episode when the date positively says it has not aired, never hide a
        // show whose date the catalog left blank.
        assertTrue(isAiredOrUnknown(null))
        assertTrue(isAiredOrUnknown(""))
        assertTrue(isAiredOrUnknown("   "))
        assertTrue(isAiredOrUnknown("garbage"))
        assertTrue(isAiredOrUnknown("2026-13-45"))
    }

    // ── the wiring the fix depends on ────────────────────────────────

    @Test
    fun `the continue watching episode walk gates on the rule`() {
        // The card the report is about is chosen by this walk: the next
        // unwatched episode is only a candidate when the rule says it aired.
        // Drop the gate and an unaired episode is offered again.
        val vm = squash(source(HOME_VIEW_MODEL))
        val walk = vm.substringAfter("val nextUnwatchedInSeason =")
            .substringBefore("if (nextUnwatchedInSeason != null)")

        assertTrue(
            "the next-up walk must test the air date",
            walk.contains("isAiredOrUnknown(")
        )
    }

    @Test
    fun `the upcoming rail still keeps an episode airing today`() {
        // The other half of the split, and why withholding it from Continue
        // Watching is not the same as hiding it: today's episode belongs to
        // the Upcoming rail, labeled "Today". If this comparison ever became
        // "exclude today" the show would be on no rail at all on its air day.
        val vm = squash(source(HOME_VIEW_MODEL))

        assertTrue(
            "today's air date has to survive the Upcoming builder",
            vm.contains("if (epochMs < startOfToday) continue")
        )
    }

    private companion object {
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
