package com.kennyb1201.kbstream.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.kennyb1201.kbstream.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player overlay is fixed chrome: nothing on it scrolls vertically.
 *
 * Field report (2026-10-21, television): "the clearlogo on the player overlay is
 * getting cut off on TV unless I scroll up into it", then the rule this pins -
 * "nothing should be scrollable on the overlay except left to right for actors;
 * the clearlogo and description and badges should be fixed and all fit."
 *
 * The overlay's title block used to sit in a `ScrollView` that took whatever the
 * clock, badges, cast band and control bar left. On a television that is 540dp
 * tall, and the block (a 104dp clearlogo, an episode line and a five-line
 * synopsis) is taller than what is left of it - so the block was clipped and
 * only came whole into view by scrolling it. A player's chrome is not a page:
 * the block is now a plain weighted `LinearLayout`, the clock and "Ends at"
 * share one row, and the synopsis is capped at three lines, so the clearlogo,
 * the badges and the buttons are always whole and on screen.
 *
 * The measurement half runs the real layout at a television's size
 * (960x540dp at xhdpi), with every optional block visible at once, and checks
 * the fixed blocks land inside the screen rather than being pushed past its
 * bottom edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class PlayerOverlayFitContractTest {

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

    /** Whitespace-collapsed layout source, so a wrapped tag still matches. */
    private fun layout(file: String): String {
        val layoutFile = File(File(sourceRoot.parentFile, "res/layout"), file)
        assertTrue("layout missing: $layoutFile", layoutFile.isFile)
        return layoutFile.readText().replace(Regex("\\s+"), " ")
    }

    /** One view tag: from its id to the end of the tag. */
    private fun slot(xml: String, id: String): String =
        xml.substringAfter("@+id/$id").substringBefore("/>")

    // ── structure: nothing scrolls, the prose is bounded ─────────────────

    @Test
    fun `the overlay has no vertical scroll view`() {
        val chrome = layout("player_chrome.xml")
        assertFalse(
            "the overlay's title block must not be a ScrollView: on a TV it is " +
                "taller than the room left for it, so the clearlogo was clipped " +
                "and had to be scrolled back into view",
            chrome.contains("<ScrollView")
        )
        assertTrue(
            "the fixed info block takes the room the control bar does not",
            chrome.contains("android:id=\"@+id/chrome_info\"") &&
                slot(chrome, "chrome_info").contains("android:layout_weight=\"1\"")
        )
        assertTrue(
            "and it is a plain LinearLayout - the overlay's one vertical stack",
            chrome.contains("android:id=\"@+id/chrome_info\"") &&
                slot(chrome, "chrome_info").contains("android:orientation=\"vertical\"")
        )
    }

    @Test
    fun `the synopsis is capped so the blocks under it always fit`() {
        val overview = slot(layout("player_chrome.xml"), "chrome_overview")
        assertTrue(
            "the synopsis is the only block that grows: it must be capped at the " +
                "lines a 540dp television has room for",
            overview.contains("android:maxLines=\"3\"")
        )
        assertFalse(
            "five lines was taller than the room between the clearlogo and the " +
                "badges, which is what the ScrollView was hiding",
            overview.contains("android:maxLines=\"5\"")
        )
        assertTrue(
            "and it still ellipsizes rather than cutting a line in half",
            overview.contains("android:ellipsize=\"end\"")
        )
    }

    @Test
    fun `the clock and the ends-at estimate share one row`() {
        val chrome = layout("player_chrome.xml")
        assertTrue(
            "two stacked lines cost a line of the synopsis on a television",
            chrome.contains("android:id=\"@+id/chrome_clock_row\"")
        )
        val row = slot(chrome, "chrome_clock_row")
        assertTrue(
            "the row is horizontal and right-aligned exactly as the two lines were",
            row.contains("android:orientation=\"horizontal\"") &&
                row.contains("android:gravity=\"end|center_vertical\"")
        )
        listOf("chrome_clock", "chrome_ends_at").forEach { id ->
            assertTrue("$id must stay in the shared layout", chrome.contains("@+id/$id"))
        }
    }

    @Test
    fun `only the cast row scrolls, and only sideways`() {
        val chrome = layout("player_chrome.xml")
        assertTrue(
            "the cast band keeps its HorizontalScrollView: a title's cast is " +
                "longer than any screen",
            slot(chrome, "chrome_cast_row").isNotEmpty() &&
                chrome.contains("HorizontalScrollView")
        )
        assertTrue(
            "the badge chips do not scroll at all now - they are a fixed row",
            slot(chrome, "chrome_badge_row").contains("android:layout_width=\"match_parent\"")
        )
    }

    // ── measurement: every block lands on the television ─────────────────

    @Test
    fun `every block of the overlay fits a television with all of them up`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.player_chrome, null) as ViewGroup
        val chrome = root.findViewById<View>(R.id.chrome_root) as LinearLayout
        chrome.visibility = View.VISIBLE
        listOf(
            R.id.chrome_seekbar_row,
            R.id.chrome_clock,
            R.id.chrome_ends_at,
            R.id.chrome_clear_logo,
            R.id.chrome_overview,
            R.id.chrome_badge_row,
            R.id.chrome_cast_section
        ).forEach { root.findViewById<View>(it).visibility = View.VISIBLE }
        (root.findViewById<View>(R.id.chrome_overview) as TextView).text =
            "A synopsis long enough to want every line the overlay can give it. ".repeat(12)
        (root.findViewById<View>(R.id.chrome_episode_label) as TextView).apply {
            text = "S01E12"
            visibility = View.VISIBLE
        }
        (root.findViewById<View>(R.id.chrome_episode_title) as TextView).apply {
            text = "The Long Episode Title That Wraps Onto A Second Line For Sure"
            visibility = View.VISIBLE
        }
        val badges = root.findViewById<View>(R.id.chrome_badge_row) as LinearLayout
        repeat(5) { badges.addView(TextView(context).apply { text = "4K HDR" }) }
        val cast = root.findViewById<View>(R.id.chrome_cast_row) as LinearLayout
        repeat(8) {
            val tile = inflater.inflate(R.layout.cast_member_item, cast, false)
            (tile.findViewById<View>(R.id.cast_member_name) as TextView).text = "Actor Name"
            (tile.findViewById<View>(R.id.cast_member_character) as TextView).apply {
                text = "Character"
                visibility = View.VISIBLE
            }
            cast.addView(tile)
        }

        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)

        val density = context.resources.displayMetrics.density
        val bottomLimit = chrome.bottom - chrome.paddingBottom
        for (i in 0 until chrome.childCount) {
            val child = chrome.getChildAt(i)
            assertTrue(
                "overlay block $i (id ${child.id}) ends at ${child.bottom}, past the " +
                    "screen's $bottomLimit: the fixed chrome must fit, not scroll",
                child.bottom <= bottomLimit
            )
        }

        val info = root.findViewById<View>(R.id.chrome_info) as LinearLayout
        val logo = root.findViewById<View>(R.id.chrome_clear_logo)
        val logoHeight = (104 * density).toInt()
        assertEquals(
            "the clearlogo keeps its 104dp height - it is not squeezed to fit",
            logoHeight,
            logo.height
        )
        assertTrue(
            "the fixed info block must be tall enough for the clearlogo it starts " +
                "with: ${info.height}px of block for a ${logo.height}px logo",
            info.height >= logo.height
        )
        assertTrue(
            "the control bar stays on screen, not pushed past the bottom edge",
            root.findViewById<View>(R.id.chrome_btn_play_pause).bottom <= bottomLimit
        )
        assertTrue(
            "and the badge chips sit above it, whole",
            root.findViewById<View>(R.id.chrome_badge_row).bottom <= bottomLimit
        )
    }
}
