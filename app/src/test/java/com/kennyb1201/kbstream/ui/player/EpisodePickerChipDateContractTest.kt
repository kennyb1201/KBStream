package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season chip's focus ring and the episode row's date (spec 2026-11-12).
 *
 * Two field gaps in the player's episode picker, both in the one builder and
 * adapter pair both engines share:
 *
 *  - the selected season chip showed NOTHING when focused, because the stroke
 *    was `focused && !selected` at 1dp - so the common landing (scrolling up
 *    onto the season already loaded) changed nothing, and 1dp read as nothing
 *    from a sofa. A focused chip now always wears a 2dp ring, in the colour that
 *    reads on its own fill;
 *  - the air date was in the row's data and never drawn, because the label was
 *    built from the episode id and the name only. The date is its own view
 *    beside the label now, so a long title ellipsizes first and the date stays.
 *
 * Pinned by source, like this picker's other contracts: the adapter is Android
 * view code and the row builder is Compose.
 */
class EpisodePickerChipDateContractTest {

    private val strip by lazy { source(SEASON_STRIP) }
    private val adapter by lazy { source(PICKER_ADAPTER) }
    private val picker by lazy { source(PLAYER_PICKER) }
    private val rowXml by lazy { source(ROW_XML) }

    // ── F1: the chip's ring ─────────────────────────────────────────────

    @Test
    fun `a focused chip always wears a 2dp ring, selected or not`() {
        val style = strip.substringAfter("private fun applyChipStyle(")
            .substringBefore("}\n}")
        assertTrue(
            "the stroke is drawn for EVERY focused chip",
            style.contains("if (focused) {\n                setStroke(")
        )
        assertFalse(
            "and not only for an unselected one - that is what left the loaded " +
                "season looking unfocused",
            style.contains("focused && !selected")
        )
        assertTrue(
            "2dp, the weight the episode rows' own focus state uses",
            style.contains("(2 * density).toInt()")
        )
        assertFalse("1dp is what was invisible", style.contains("(1 * density).toInt()"))
    }

    @Test
    fun `the ring contrasts with the fill it sits on`() {
        val style = strip.substringAfter("private fun applyChipStyle(")
        assertTrue(
            "kb_text_hi on the accent-filled selected chip, where an accent ring " +
                "would disappear",
            style.contains("if (selected) ContextCompat.getColor(context, R.color.kb_text_hi)")
        )
        assertTrue(
            "and the accent on every other chip",
            style.contains("else accent")
        )
        assertTrue(
            "the selected fill is still the accent",
            style.contains("selected -> accent")
        )
        assertTrue(
            "and its text is still kb_void",
            style.contains("selected -> ContextCompat.getColor(context, R.color.kb_void)")
        )
    }

    // ── F2: the date ───────────────────────────────────────────────────

    @Test
    fun `the date is its own view beside the label, not part of its text`() {
        assertTrue(
            "the row model carries it",
            adapter.contains("val date: String? = null")
        )
        assertTrue(
            "the view holder finds it",
            adapter.contains("view.findViewById(R.id.picker_item_date)")
        )
        assertTrue(
            "the bind sets it from the item",
            adapter.contains("bindDate(holder.date, item.date)")
        )
        val label = adapter.substringAfter("val item = items[position]")
            .substringBefore("holder.itemView.setOnClickListener")
        assertFalse(
            "and the label's text is still the label alone: a date appended there " +
                "would be the part ellipsized off a long title",
            label.contains("item.date")
        )
    }

    @Test
    fun `the label gives way before the date does`() {
        // Both views are self-closing, so the element ends at "/>".
        val label = rowXml.substringAfter("android:id=\"@+id/picker_item_label\"")
            .substringBefore("/>")
        val date = rowXml.substringAfter("android:id=\"@+id/picker_item_date\"")
            .substringBefore("/>")
        assertTrue(
            "the label is the part that shrinks and ellipsizes",
            label.contains("android:layout_width=\"0dp\"") &&
                label.contains("android:layout_weight=\"1\"") &&
                label.contains("android:ellipsize=\"end\"")
        )
        assertTrue(
            "the date keeps its own measured width and never ellipsizes",
            date.contains("android:layout_width=\"wrap_content\"") &&
                !date.contains("android:ellipsize=\"end\"")
        )
        assertTrue(
            "and it starts hidden, so a row without a date looks exactly as it did",
            date.contains("android:visibility=\"gone\"")
        )
    }

    @Test
    fun `the separator travels with the date, so the title cannot eat it`() {
        assertTrue(
            "the date's own view carries the middle dot",
            adapter.contains("date.text = \"\\u00b7 \$text\"")
        )
        assertFalse(
            "and the label's own text never carries the date: an absent date would " +
                "leave a dangling separator there, and a long title would take it",
            picker.substringAfter("label = buildString {")
                .substringBefore("},")
                .contains("airDate")
        )
    }

    @Test
    fun `the builder formats through Detail's one formatter, and blank means no date`() {
        assertTrue(
            "Detail's formatter is the one used, not a second implementation",
            picker.contains("import com.kennyb1201.kbstream.ui.detail.formatEpisodeAirDate") &&
                picker.contains("formatEpisodeAirDate(raw)")
        )
        assertTrue(
            "the episode's own field feeds it, with blank treated as absent - no " +
                "dangling separator, no empty slot",
            picker.contains("date = episode.airDate") &&
                picker.contains("?.takeIf { it.isNotBlank() }")
        )
        assertFalse(
            "no inline re-formatting of the raw string",
            picker.contains("SimpleDateFormat") || picker.contains("DateTimeFormatter")
        )
        assertTrue(
            "the formatter still lives in Detail, where the episode cards' rules are",
            source(DETAIL).contains("internal fun formatEpisodeAirDate(")
        )
    }

    // ── S3: nothing else moved ─────────────────────────────────────────

    @Test
    fun `the badges order and the pick path are untouched`() {
        val bind = adapter.substringAfter("fun onBindViewHolder(")
        val rating = bind.indexOf("bindRatingChip(")
        val nowPlaying = bind.indexOf("bindNowPlayingChip(")
        val unavailable = bind.indexOf("bindUnavailableChip(")
        assertTrue(
            "rating, then NOW PLAYING, then UNAVAILABLE",
            rating in 0 until nowPlaying && nowPlaying < unavailable
        )
        assertTrue(
            "the date is not a badge: it is never bound into the chips row",
            !bind.contains("bindDate(holder.badges") &&
                bind.contains("bindDate(holder.date, item.date)")
        )
        assertTrue(
            "picking still jumps to the row's own episode, and only when it is playable",
            picker.contains("onClick = { if (!unavailable) onPick(episode) }")
        )
        assertTrue(
            "an unaired episode still draws its future date: the same field feeds " +
                "the badge and the date",
            picker.contains("val unavailable = isEpisodeUnavailable(episode.airDate)")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private companion object {
        const val SEASON_STRIP = "java/com/kennyb1201/kbstream/ui/player/SeasonStripAdapter.kt"
        const val PICKER_ADAPTER = "java/com/kennyb1201/kbstream/ui/player/PickerAdapter.kt"
        const val PLAYER_PICKER = "java/com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt"
        const val ROW_XML = "res/layout/picker_item.xml"
        const val DETAIL = "java/com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
