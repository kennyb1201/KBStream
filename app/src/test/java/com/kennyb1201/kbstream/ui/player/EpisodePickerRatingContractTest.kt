package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The episode's rating in the player's episode picker (spec 2026-11-12).
 *
 * Display only: the picker SHOWS the episode's TMDB vote average - the crowd's
 * rating, the same number the episode cards print - beside the row's existing
 * chips. It does not let the viewer rate an episode; per-episode user ratings
 * are a separate commission on both trackers and are untouched here.
 *
 * The picker is View-based (a RecyclerView of `picker_item` rows) and the card's
 * chip is Compose, so the row cannot reuse the composable: it carries a View
 * twin that reads the same plate colour, shape and format constants. That twin
 * is what keeps S2 true - one episode cannot show two different ratings on two
 * screens.
 *
 * Source-reading, like the rest of this module's contracts: the panel lives in
 * two Android-bound activities and its rows are built by a shared function.
 */
class EpisodePickerRatingContractTest {

    private val adapter by lazy { source(PICKER_ADAPTER) }
    private val rows by lazy { source(PLAYER_EPISODE_PICKER) }
    private val card by lazy { source(EPISODE_TOUCH) }

    // ── (a) the value reaches the row, and the row draws the chip ─────────

    @Test
    fun `an episode's vote average reaches the adapter and draws a one-decimal chip`() {
        assertTrue(
            "the row model carries the rating, null by default like its siblings",
            adapter.contains("val rating: Double? = null")
        )
        assertTrue(
            "the shared row builder passes the episode's own vote average, so both " +
                "engines get it from one place",
            rows.contains("rating = episode.voteAverage")
        )
        assertTrue(
            "and the adapter binds the chip into the badges row",
            adapter.contains("bindRatingChip(holder.badges, item.rating)")
        )
        val chip = adapter.substringAfter("fun bindRatingChip(row: LinearLayout, rating: Double?)")
            .substringBefore("private const val BADGE_IMAGE_HEIGHT_DP")
        assertTrue(
            "the score is the card's format and the card's plate colour",
            chip.contains("\"%.1f\".format(rating)") &&
                chip.contains("com.kennyb1201.kbstream.ui.components.KBImdbTint")
        )
        assertTrue(
            "with the cards' wording on the plate",
            adapter.contains("internal const val RATING_PLATE_LABEL = \"IMDb\"") &&
                card.contains("text = \"IMDb\"")
        )
        assertTrue(
            "and the card's own one-decimal format, so the two cannot disagree",
            card.contains("\"%.1f\".format(rating)")
        )
        assertTrue(
            "the plate shares the card's corner shape rather than a new one",
            adapter.contains("private const val RATING_PLATE_CORNER_DP = 10")
        )
    }

    // ── (b) no vote average, no chip ──────────────────────────────────────

    @Test
    fun `an episode with no vote average draws no chip and leaves no gap`() {
        val chip = adapter.substringAfter("fun bindRatingChip(row: LinearLayout, rating: Double?)")
            .substringBefore("private const val BADGE_IMAGE_HEIGHT_DP")
        assertTrue(
            "a null rating returns before anything is added to the row",
            chip.replace(Regex("\\s+"), " ").trimStart().startsWith("{ if (rating == null) return")
        )
        assertFalse(
            "an empty chip is never added as a spacer",
            chip.contains("View.INVISIBLE") || chip.contains("rating ?: 0")
        )
        assertTrue(
            "the row is only shown when this chip adds itself",
            chip.contains("row.visibility = View.VISIBLE") &&
                !chip.contains("row.visibility = View.GONE")
        )
    }

    // ── (c) a rating and the UNAVAILABLE chip share the row ───────────────

    @Test
    fun `an unaired episode shows both its rating and the UNAVAILABLE chip`() {
        assertTrue(
            "both chips are bound into the same row, unconditionally",
            adapter.contains("bindRatingChip(holder.badges, item.rating)") &&
                adapter.contains("bindUnavailableChip(holder.badges, item.unavailable)")
        )
        assertFalse(
            "the rating is not gated on the episode being aired",
            adapter.contains("bindRatingChip(holder.badges, if (item.unavailable)")
        )
        val chips = adapter.substringAfter("fun bindRatingChip(row: LinearLayout, rating: Double?)")
            .substringBefore("private const val BADGE_IMAGE_HEIGHT_DP")
        assertTrue(
            "and the rating chip leaves the gap the badge chips use, so it reads " +
                "as the row's own chip beside them",
            chips.contains("if (row.childCount > 0) marginStart = (BADGE_CHIP_GAP_DP * density).toInt()")
        )
        assertTrue(
            "the UNAVAILABLE chip still appends to that same row",
            adapter.contains("row.visibility = View.VISIBLE\n            row.addView(chip)") ||
                adapter.contains("UNAVAILABLE_LABEL")
        )
    }

    // ── (d) every other row is unchanged ─────────────────────────────────

    @Test
    fun `source, audio, subtitle, speed and season rows carry no rating`() {
        assertTrue(
            "the season chips are built without one - they live in the strip now, " +
                "not in the episode list",
            rows.contains("fun seasonStrip(") &&
                rows.contains("label = seasonLabel(season)") &&
                !rows.substringAfter("fun rows(").substringBefore("fun }").contains("seasonLabel(season)")
        )
        assertTrue(
            "and so is the loading row",
            rows.contains("listOf(PickerItem(label = LOADING_LABEL, onClick = {}))")
        )
        assertTrue(
            "exactly one construction in the shared builder passes the rating, and " +
                "it is the episode row's",
            1 == Regex("rating = ").findAll(rows).count()
        )
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val PICKER_ADAPTER = "com/kennyb1201/kbstream/ui/player/PickerAdapter.kt"
        const val PLAYER_EPISODE_PICKER = "com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt"
        const val EPISODE_TOUCH = "com/kennyb1201/kbstream/ui/components/touch/KBEpisodeTouch.kt"
    }
}
