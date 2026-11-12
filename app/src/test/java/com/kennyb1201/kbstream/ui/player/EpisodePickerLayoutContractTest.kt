package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player episode picker's layout (spec 2026-11-12).
 *
 * Four field complaints, one panel: the seasons were rows at the head of the
 * episode list, so switching season meant scrolling past them; the 320dp panel
 * with its 56x84 portrait crop could not show a landscape still or a readable
 * synopsis; the overview was cut at two lines; and the only sign of the episode
 * playing now was the accent colour of its label, which reads the same as the
 * row the D-pad merely sits on.
 *
 * So: a pinned season strip, a 420dp panel, a 16:9 still, three lines of
 * overview, and a NOW PLAYING chip in the badges row - in the fixed order
 * rating, NOW PLAYING, UNAVAILABLE, which is where this spec meets the ratings
 * one.
 *
 * Source-reading, like the rest of this module's contracts: the panel is a View
 * layout inside two Android-bound activities, and no device runs in CI.
 */
class EpisodePickerLayoutContractTest {

    private val exoXml by lazy { resource("layout/activity_player.xml") }
    private val mpvXml by lazy { resource("layout/activity_mpv_player.xml") }
    private val rowXml by lazy { resource("layout/picker_item.xml") }
    private val chipXml by lazy { resource("layout/picker_season_chip.xml") }
    private val exo by lazy { source(EXO) }
    private val mpv by lazy { source(MPV) }
    private val adapter by lazy { source(PICKER_ADAPTER) }
    private val rows by lazy { source(PLAYER_EPISODE_PICKER) }
    private val strip by lazy { source(SEASON_STRIP) }

    // ── (a) seasons are a pinned strip, not rows in the list ──────────────

    @Test
    fun `the season strip replaces the season rows the list used to carry`() {
        assertTrue(
            "the shared builder offers the strip's chips from the same seasons list",
            rows.contains("fun seasonStrip(") &&
                rows.contains("seasons: List<Int>") &&
                rows.contains("PickerItem(")
        )
        val listBuilder = rows.substringAfter("fun rows(").substringBefore("return episodeRows + loadingRow")
        assertFalse(
            "and the episode list no longer builds season rows",
            listBuilder.contains("seasonLabel(season)") || listBuilder.contains("seasonRows")
        )
        assertTrue(
            "the list is episodes plus the loading row, nothing else",
            rows.contains("return episodeRows + loadingRow")
        )
        listOf(exo to "Exo", mpv to "MPV").forEach { (source, engine) ->
            assertTrue(
                "$engine builds the strip from the seasons it loaded, through the shared builder",
                source.contains("PlayerEpisodePicker.seasonStrip(") &&
                    source.contains("seasons = episodePickerSeasons,")
            )
            assertTrue(
                "$engine shows the strip when the episode panel binds it",
                source.contains("pickerSeasons.visibility = View.VISIBLE") ||
                    source.contains("pickerSeasons?.visibility = View.VISIBLE")
            )
            assertTrue(
                "$engine hides the strip for the non-episode pickers",
                source.contains("pickerSeasons.visibility = View.GONE") ||
                    source.contains("pickerSeasons?.visibility = View.GONE")
            )
        }
        listOf(exoXml to "picker_seasons", mpvXml to "mpv_picker_seasons").forEach { (xml, id) ->
            val at = xml.indexOf("@+id/$id\"")
            assertTrue("the $id view exists", at >= 0)
            val listAt = xml.indexOf("@+id/" + if (id == "picker_seasons") "picker_list\"" else "mpv_picker_list\"")
            assertTrue(
                "and it sits ABOVE the list, pinning the strip while the list scrolls",
                listAt > at
            )
            assertTrue(
                "it starts hidden, so a source picker never flashes a strip",
                xml.substringAfter("@+id/$id\"").substringBefore("/>")
                    .contains("android:visibility=\"gone\"")
            )
        }
    }

    // ── (b) a wider panel and a landscape still ───────────────────────────

    @Test
    fun `the panel is wider than 320dp and the episode still is landscape`() {
        listOf(exoXml to "picker_container", mpvXml to "mpv_picker_container").forEach { (xml, id) ->
            val width = xml.substringAfter("android:id=\"@+id/$id\"")
                .substringBefore("android:layout_height")
                .substringAfter("android:layout_width=\"")
                .substringBefore("\"")
            assertTrue(
                "$id is wider than the old 320dp ($id is $width)",
                width.endsWith("dp") && width.removeSuffix("dp").toInt() > 320
            )
        }
        val thumb = rowXml.substringAfter("android:id=\"@+id/picker_item_thumb\"")
            .substringBefore("/>")
        assertFalse(
            "the still is not the old 56dp portrait slot",
            thumb.contains("android:layout_width=\"56dp\"")
        )
        assertFalse(
            "nor the old 84dp height",
            thumb.contains("android:layout_height=\"84dp\"")
        )
        val w = thumb.substringAfter("android:layout_width=\"").substringBefore("dp\"").toInt()
        val h = thumb.substringAfter("android:layout_height=\"").substringBefore("dp\"").toInt()
        assertTrue("the still is landscape 16:9 ($w x $h)", w * 9 == h * 16)
        assertTrue("and both edges are bigger than the thumb it replaced", w > 56 && h > 56)
        assertTrue(
            "the strip's chips are chips, not rows",
            chipXml.contains("android:id=\"@+id/picker_season_chip\"") &&
                !chipXml.contains("picker_item_thumb")
        )
    }

    // ── (c) three lines of overview ───────────────────────────────────────

    @Test
    fun `the description allows three lines`() {
        val desc = rowXml.substringAfter("android:id=\"@+id/picker_item_desc\"")
            .substringBefore("</TextView>")
        assertTrue(
            "F3: three lines, still 12sp and still ellipsised",
            desc.contains("android:maxLines=\"3\"") &&
                desc.contains("android:ellipsize=\"end\"") &&
                desc.contains("android:textSize=\"12sp\"")
        )
        assertFalse("not two any more", desc.contains("android:maxLines=\"2\""))
    }

    // ── (d) the playing episode carries its own marker ────────────────────

    @Test
    fun `the playing episode's row carries the NOW PLAYING marker and the accent label`() {
        assertTrue(
            "the row builder marks the playing episode",
            rows.contains("nowPlaying = isCurrent")
        )
        assertTrue(
            "and no other row can: the flag is only ever the current-episode test",
            Regex("nowPlaying = ").findAll(rows).count() == 1
        )
        assertTrue(
            "the adapter draws the words, not a glyph, so it reads apart from the watched check",
            adapter.contains("internal const val NOW_PLAYING_LABEL = \"NOW PLAYING\"")
        )
        assertTrue(
            "and it is only bound when the row is the playing one",
            adapter.contains("bindNowPlayingChip(holder.badges, item.nowPlaying)") &&
                adapter.contains("if (!nowPlaying) return")
        )
        assertTrue(
            "the accent label stays - the marker is in addition, not instead",
            rows.contains("isSelected = isCurrent")
        )
        assertTrue(
            "the marker is an accent plate with void text, distinct from the selection colour",
            adapter.contains("R.color.kb_accent") && adapter.contains("R.color.kb_void")
        )
    }

    // ── (e) the badges row's fixed chip order ────────────────────────────

    @Test
    fun `the chips sit in the order rating, NOW PLAYING, UNAVAILABLE`() {
        val bind = adapter.substringAfter("override fun onBindViewHolder")
            .substringBefore("private fun bindStill")
        val rating = bind.indexOf("bindRatingChip(holder.badges, item.rating)")
        val nowPlaying = bind.indexOf("bindNowPlayingChip(holder.badges, item.nowPlaying)")
        val unavailable = bind.indexOf("bindUnavailableChip(holder.badges, item.unavailable)")
        assertTrue("all three are bound", rating >= 0 && nowPlaying >= 0 && unavailable >= 0)
        assertTrue("rating first", rating < nowPlaying)
        assertTrue("then NOW PLAYING", nowPlaying < unavailable)
        assertTrue(
            "each appends with the row's own gap, so a combination never runs together",
            adapter.contains("if (row.childCount > 0) marginStart = (BADGE_CHIP_GAP_DP * density).toInt()")
        )
        assertTrue(
            "and a row with no chips draws no badges row",
            adapter.contains("row.visibility = View.GONE")
        )
    }

    // ── (f) every non-episode row is what it was ─────────────────────────

    @Test
    fun `source, audio, subtitle and speed rows keep their old shape`() {
        val thumb = rowXml.substringAfter("android:id=\"@+id/picker_item_thumb\"")
            .substringBefore("/>")
        assertTrue(
            "the still slot is still gone by default, so a source row has no art slot",
            thumb.contains("android:visibility=\"gone\"")
        )
        assertTrue(
            "the panel hides the strip before binding a non-episode picker, in both engines",
            exo.contains("pickerSeasons.visibility = View.GONE") &&
                mpv.contains("pickerSeasons?.visibility = View.GONE")
        )
        assertTrue(
            "and both engines give it the list below as its DOWN target",
            exo.contains("pickerSeasons.nextFocusDownId = R.id.picker_list") &&
                mpv.contains("pickerSeasons?.nextFocusDownId = R.id.mpv_picker_list")
        )
        assertTrue(
            "the strip's adapter is only the chip layout",
            strip.contains("R.layout.picker_season_chip") &&
                !strip.contains("R.layout.picker_item")
        )
    }

    private fun resource(relative: String): String {
        val file = File(findRoot("src/main/res"), relative)
        assertTrue("resource missing: $file", file.isFile)
        return file.readText()
    }

    private fun source(relative: String): String {
        val file = File(findRoot("src/main/java"), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findRoot(suffix: String): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "$prefix$suffix")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val EXO = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val PICKER_ADAPTER = "com/kennyb1201/kbstream/ui/player/PickerAdapter.kt"
        const val PLAYER_EPISODE_PICKER = "com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt"
        const val SEASON_STRIP = "com/kennyb1201/kbstream/ui/player/SeasonStripAdapter.kt"
    }
}
