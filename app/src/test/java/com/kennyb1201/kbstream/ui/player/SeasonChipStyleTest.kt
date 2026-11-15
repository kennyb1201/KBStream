package com.kennyb1201.kbstream.ui.player

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The season chip's ring, built for real (spec 2026-11-15).
 *
 * Field report: the focused chip's FILL and its text render, and the episode
 * rows highlight, but the 2dp outline never draws on any chip - so a focused
 * unselected chip is indistinguishable from its neighbours on AMOLED, where the
 * raised and surface fills are both near-black.
 *
 * F1 was written to settle two suspects. The first is settled here, and it is
 * settled NO: the theme walk's copy of a shape does keep the stroke. AOSP's
 * `GradientDrawable.GradientState` copies `mStrokeWidth` and `mStrokeColors` in
 * its copy constructor, so `constantState.newDrawable()` - which is all
 * `themedShape` uses before it rewrites the fill - reproduces a 2dp stroke
 * exactly. The test below pins that, so a future change to that copy path cannot
 * silently become the reason a ring disappears.
 *
 * The ring is therefore not lost in the copy, and it has been moved out of the
 * background anyway: `SeasonChipStyle.ring` is the chip's FOREGROUND, which the
 * walk never touches. The chip's fill and accent are resolved here rather than
 * left to the walk, because the walk now skips the chip entirely - the first
 * test proves a themed copy is faithful, and it is still the wrong shape for
 * state that the adapter owns.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = SeasonChipStyleTest.NoopApplication::class)
class SeasonChipStyleTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** Kenny's theme: AMOLED on, an accent that is not the default brass. */
    private fun moveTheme() {
        AppPreferences.setAmoledBlack(context, true)
        AppPreferences.setAccentIndex(context, 1)
    }

    // ── F1(a): the copy keeps the stroke ──────────────────────────────────

    @Test
    fun `the themed copy of a focused chip keeps its ring`() {
        moveTheme()
        // The chip's shape as 317 built it - the XML tones, and the ring as a
        // background stroke - because that is the drawable the walk copied. The
        // chip no longer writes its ring there, and a themed fill would make the
        // copy an identity, so the old shape is what this asserts on.
        val density = context.resources.displayMetrics.density
        val original = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * density
            setColor(ContextCompat.getColor(context, R.color.kb_surface_raised))
            setStroke((2 * density).toInt(), ContextCompat.getColor(context, R.color.kb_accent))
        }
        val before = stroke(original)

        val copy = requireNotNull(themedChromeBackground(context, original)) as GradientDrawable

        assertNotSame("the walk replaces the drawable with a copy", original, copy)
        assertEquals("the copy keeps the ring's width", before.first, stroke(copy).first)
        assertEquals("and its colour", before.second, stroke(copy).second)
        assertTrue("a ring of 2dp is what the chip is drawn with", before.first > 0)
    }

    // ── F2: the ring the chip actually wears ──────────────────────────────

    @Test
    fun `the focused ring is a 2dp accent outline on an unselected chip`() {
        moveTheme()
        val ring = SeasonChipStyle.ring(context, selected = false)
        val density = context.resources.displayMetrics.density

        val (width, color) = stroke(ring)
        assertEquals((2 * density).toInt().coerceAtLeast(1), width)
        assertEquals("the accent, so it reads on the near-black surface", themeAccentColor(context), color)
        assertTrue("the ring draws the outline only, over the fill", transparentFill(ring))
    }

    @Test
    fun `the selected chip's ring is kb_text_hi, not the accent it sits on`() {
        moveTheme()
        val ring = SeasonChipStyle.ring(context, selected = true)

        assertEquals(
            ContextCompat.getColor(context, R.color.kb_text_hi),
            stroke(ring).second
        )
    }

    // ── The walk cannot take it away ──────────────────────────────────────

    @Test
    fun `the theme walk leaves the chip's own background alone`() {
        moveTheme()
        val chip = TextView(context)
        chip.id = R.id.picker_season_chip
        SeasonChipStyle.apply(chip, selected = false, focused = true)
        val ringed = chip.background
        assertNotNull(ringed)

        retintPlayerChromeView(chip, context)

        assertSame("the chip's state is the adapter's, not the walk's", ringed, chip.background)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * The stroke a shape was built with: width in pixels and colour.
     *
     * There is no public getter for either, so it is read off the shape's own
     * ConstantState - the same state the walk's copy is made from.
     */
    private fun stroke(shape: GradientDrawable): Pair<Int, Int> {
        val state = requireNotNull(shape.constantState) { "a GradientDrawable always has a state" }
        val width = state.javaClass.field(state, "mStrokeWidth") as Int
        val colors = state.javaClass.field(state, "mStrokeColors") ?: state.javaClass.field(state, "mStrokeColorStateList")
        val color = when (colors) {
            is ColorStateList -> colors.defaultColor
            else -> colors?.javaClass?.getMethod("getDefaultColor")?.invoke(colors) as Int
        }
        return width to color
    }

    private fun transparentFill(shape: GradientDrawable): Boolean =
        (shape.color?.defaultColor ?: 0) == 0

    private fun Class<*>.field(instance: Any, name: String): Any? {
        var type: Class<*>? = this
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { field ->
                field.isAccessible = true
                return field.get(instance)
            }
            type = type.superclass
        }
        throw AssertionError("GradientDrawable state has no $name")
    }
}
