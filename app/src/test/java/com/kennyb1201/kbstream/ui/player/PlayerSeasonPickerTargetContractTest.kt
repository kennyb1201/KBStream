package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The episode picker's season poster and season strip (spec 2026-11-17).
 *
 * The report: "the season poster is too big on the episode picker in player and
 * i still cant see what season i want to change to in it". Two separate faults
 * in the same panel - a poster that took more of the panel than the Detail row
 * with the same job, and a strip whose focused chip could not be picked out of
 * the one that was already selected.
 *
 * The sizes and the chip's look are pinned here as source and as the pure
 * arithmetic behind F3; the chip's actual drawn state is pinned against real
 * views in [SeasonChipStyleTest] (Robolectric).
 */
class PlayerSeasonPickerTargetContractTest {

    // ── F1: one poster size, the Detail season poster's ───────────────────

    @Test
    fun `both engines' picker posters are the Detail season poster's size`() {
        listOf(MPV_LAYOUT to "mpv_picker_season_poster", EXO_LAYOUT to "picker_season_poster")
            .forEach { (layout, id) ->
                val xml = resFile(layout)
                val block = xml.substringAfter("android:id=\"@+id/$id\"")
                    .substringBefore("/>")
                assertTrue(
                    "$id is 84dp - the Detail TV season poster's width, not a " +
                        "bigger box of its own",
                    block.contains("android:layout_width=\"84dp\"")
                )
                assertFalse(
                    "$id is no longer the 120dp / 96dp the two engines disagreed about",
                    block.contains("android:layout_width=\"120dp\"") ||
                        block.contains("android:layout_width=\"96dp\"")
                )
                assertTrue(
                    "it still draws the poster it is given rather than cropping it " +
                        "to a box",
                    block.contains("android:adjustViewBounds=\"true\"") &&
                        block.contains("android:scaleType=\"fitCenter\"")
                )
            }
    }

    @Test
    fun `the phone's 72dp comes from one shared answer, applied by both engines`() {
        val picker = source(PICKER)
        assertTrue(
            "the two widths live once, named for what they are",
            picker.contains("TV_SEASON_POSTER_DP = 84") &&
                picker.contains("PHONE_SEASON_POSTER_DP = 72")
        )
        assertTrue(
            "and the form factor picks between them - XML cannot ask",
            picker.contains("if (FormFactor.isTv) TV_SEASON_POSTER_DP else PHONE_SEASON_POSTER_DP")
        )
        listOf(EXO, MPV).forEach { activity ->
            assertTrue(
                "$activity applies the shared width when it binds the poster",
                source(activity).contains("PlayerEpisodePicker.seasonPosterWidthPx(this)")
            )
        }
    }

    // ── F2: the target chip has three marks ───────────────────────────────

    @Test
    fun `the focused chip wears a heavier ring, a bold label and a slight scale`() {
        val style = source(CHIP_STYLE)
        assertTrue(
            "3dp, up from the 2dp the field read as no ring",
            style.contains("RING_DP = 3f")
        )
        assertTrue(
            "a ~1.05x scale, named once so the strip's reveal can ask for it",
            style.contains("FOCUS_SCALE = 1.05f")
        )
        assertTrue(
            "bold on the target, the chip's own medium cut otherwise",
            style.contains("R.font.inter_bold") && style.contains("R.font.inter_medium")
        )
        val apply = style.substringAfter("fun apply(")
        assertTrue(
            "all three are set by the one call a bind and a focus change share",
            apply.contains("chip.typeface = typefaceFor(context, focused)") &&
                apply.contains("chip.scaleX = scale") &&
                apply.contains("chip.scaleY = scale")
        )
        assertTrue(
            "and the ring stays a foreground the theme walk cannot replace",
            style.contains("fun ring(context: Context, selected: Boolean): GradientDrawable")
        )
    }

    @Test
    fun `the selected chip keeps the accent fill and the plain chips stay plain`() {
        val style = source(CHIP_STYLE)
        val fill = style.substringAfter("fun fillFor(").substringBefore("fun ring(")
        assertTrue(
            "selected is still the themed accent",
            fill.contains("selected -> themeAccentColor(context)")
        )
        assertTrue(
            "and the focused-unselected chip is raised off the plain one",
            fill.contains("focused -> themedTone(context, R.color.kb_surface_raised)") &&
                fill.contains("else -> themedTone(context, R.color.kb_surface)")
        )
    }

    // ── F3: the target is never half-cut ─────────────────────────────────

    @Test
    fun `moving along the strip keeps the focused chip whole`() {
        val adapter = source(STRIP)
        assertTrue(
            "the chip's own focus listener asks the strip to reveal it",
            adapter.contains("if (focused) holder.itemView.post { reveal(holder) }")
        )
        assertTrue(
            "for the chip AS DRAWN, scale included - otherwise the ring is the " +
                "part left under the edge",
            adapter.contains("child.width * (SeasonChipStyle.FOCUS_SCALE - 1f)") &&
                adapter.contains("viewportRight = strip.width")
        )
        assertTrue(
            "and the scroll is the strip's own",
            adapter.contains("if (dx != 0) strip.scrollBy(dx, 0)")
        )
    }

    @Test
    fun `the reveal scrolls the least distance that shows the chip whole`() {
        // Inside the strip already: no movement, so a chip that fits never drifts.
        assertEquals(
            0,
            SeasonStripReveal.dx(childLeft = 40, childRight = 160, viewportLeft = 0, viewportRight = 420)
        )
        // Exactly at the far edge is still whole.
        assertEquals(
            0,
            SeasonStripReveal.dx(childLeft = 300, childRight = 420, viewportLeft = 0, viewportRight = 420)
        )
        // Hanging off the far edge: scroll by the overhang only.
        assertEquals(
            30,
            SeasonStripReveal.dx(childLeft = 330, childRight = 450, viewportLeft = 0, viewportRight = 420)
        )
        // Hanging off the near edge: scroll back by the amount it is under it.
        assertEquals(
            -20,
            SeasonStripReveal.dx(childLeft = -20, childRight = 100, viewportLeft = 0, viewportRight = 420)
        )
        // Wider than the strip: the near edge is aligned, because that is the only
        // part that can be read at all.
        assertEquals(
            10,
            SeasonStripReveal.dx(childLeft = 10, childRight = 610, viewportLeft = 0, viewportRight = 420)
        )
    }

    @Test
    fun `opening the picker still lands on the loaded season's chip`() {
        val adapter = source(STRIP)
        assertTrue(
            "focusSelected is untouched: the strip opens where the list is",
            adapter.contains("fun focusSelected(parent: RecyclerView): Boolean") &&
                adapter.contains("items.indexOfFirst { it.isSelected }")
        )
    }

    // ── F4: the poster is the LISTED season's, never the focused one ─────

    @Test
    fun `the poster never follows the focus`() {
        listOf(EXO, MPV).forEach { activity ->
            val bind = source(activity)
                .substringAfter("private fun bindEpisodeSeasonPoster()")
                .substringBefore("private fun bindEpisodePicker()")
            assertTrue(
                "$activity draws the selected season's resolved art",
                bind.contains("val url = episodePickerPosterUrl")
            )
            assertFalse(
                "$activity's poster is not re-pointed at a focused chip - poster and " +
                    "list would disagree",
                bind.contains("focused") || bind.contains("forcused") || bind.contains("isFocused")
            )
        }
        val adapter = source(STRIP)
        assertFalse(
            "and the strip's focus handling never reaches the poster",
            adapter.contains("seasonPoster") || adapter.contains("season_poster")
        )
    }

    @Test
    fun `a season with no poster shows no poster`() {
        listOf(EXO, MPV).forEach { activity ->
            val bind = source(activity)
                .substringAfter("private fun bindEpisodeSeasonPoster()")
                .substringBefore("private fun bindEpisodePicker()")
            assertTrue(
                "$activity hides the poster when the resolver answered nothing - " +
                    "never the show's poster",
                bind.contains("if (url.isNullOrBlank())") &&
                    bind.contains("view.visibility = View.GONE") &&
                    bind.contains("view.setImageDrawable(null)")
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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

    private fun resFile(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/res/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("res file not found: $relative")
    }

    private companion object {
        const val EXO = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val PICKER = "com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt"
        const val STRIP = "com/kennyb1201/kbstream/ui/player/SeasonStripAdapter.kt"
        const val CHIP_STYLE = "com/kennyb1201/kbstream/ui/player/SeasonChipStyle.kt"
        const val EXO_LAYOUT = "layout/activity_player.xml"
        const val MPV_LAYOUT = "layout/activity_mpv_player.xml"
    }
}
