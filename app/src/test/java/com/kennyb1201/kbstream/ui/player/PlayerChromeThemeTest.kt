package com.kennyb1201.kbstream.ui.player

import android.app.Application
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBAccentPalette
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.ui.theme.themeVoidWindowColor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The players' classic-View chrome follows the AMOLED / pure-black toggles and
 * the global accent.
 *
 * Reported: "player buttons and surfaces aren't following amoled toggle and
 * focus ring around buttons aren't following theme colors". The control-bar
 * buttons are drawn by @drawable/mpv_control_bg, a SELECTOR whose focused state
 * is the app's focus look (a raised plate behind a 2dp accent stroke); the old
 * chrome pass only understood a GradientDrawable or a ripple wrapping exactly
 * one, so a selector fell straight through it - the twenty-five buttons kept
 * #141A24 under every theme and the D-pad ring stayed brass under every accent.
 *
 * This is the one contract test that does not read the sources: a stroke colour
 * cannot be read back (the platform exposes no getter for a shape's stroke),
 * so the ring is RENDERED and the pixels are checked - the same way a viewer
 * sees it. That is also why the assertions are about colours rather than about
 * drawable internals: "the ring is the chosen accent and not the default brass"
 * is the reported bug, stated as what it actually looks like.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = PlayerChromeThemeTest.NoopApplication::class)
class PlayerChromeThemeTest {

    /** Mirrors PlayerActivityContractTest: the real Application starts work nothing here needs. */
    class NoopApplication : Application()

    private companion object {
        /** A bright palette entry whose channels all differ from the brass default. */
        const val EMERALD_INDEX = 17

        val BRASS = 0xFFE8A33D.toInt()
        val AMOLED_SURFACE = 0xFF06080B.toInt()
        val AMOLED_RAISED = 0xFF0D1117.toInt()
        val PURE_BLACK_SURFACE = 0xFF000000.toInt()
        val PURE_BLACK_RAISED = 0xFF050505.toInt()

        const val CHROME_THEME = "com/kennyb1201/kbstream/ui/player/PlayerChromeTheme.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV_PLAYER = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL_PLAYER =
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val density: Float
        get() = context.resources.displayMetrics.density

    private val emerald: Int
        get() = KBAccentPalette[EMERALD_INDEX].color.toArgb()

    // ── the control-bar button ───────────────────────────────────────────

    @Test
    fun `the focused control plate follows the AMOLED toggle`() {
        AppPreferences.setAmoledBlack(context, true)
        val plate = focusedPlate(R.drawable.mpv_control_bg)

        assertEquals(
            "the focused button's plate must be the AMOLED raised tone, not the " +
                "XML's #1D2530",
            AMOLED_RAISED,
            plate.color?.defaultColor
        )
        assertEquals(
            "and it must keep the control bar's own 10dp corner",
            10f * density,
            plate.cornerRadius,
            0.01f
        )
    }

    @Test
    fun `the unfocused control plate follows the AMOLED toggle`() {
        AppPreferences.setAmoledBlack(context, true)
        val plate = statePlate(R.drawable.mpv_control_bg, intArrayOf())

        assertEquals(
            "the rest of the chrome has to move with it: an AMOLED install still " +
                "painting #141A24 buttons is the reported bug",
            AMOLED_SURFACE,
            plate.color?.defaultColor
        )
    }

    @Test
    fun `pure black flattens the control plates too`() {
        AppPreferences.setAmoledBlack(context, true)
        AppPreferences.setPureBlackSurface(context, true)

        assertEquals(
            PURE_BLACK_RAISED,
            focusedPlate(R.drawable.mpv_control_bg).color?.defaultColor
        )
        assertEquals(
            PURE_BLACK_SURFACE,
            statePlate(R.drawable.mpv_control_bg, intArrayOf()).color?.defaultColor
        )
    }

    @Test
    fun `the focus ring is painted in the chosen accent, not the brass`() {
        AppPreferences.setAccentIndex(context, EMERALD_INDEX)
        val focused = chromePlateSpec(context, intArrayOf(android.R.attr.state_focused))

        assertEquals(
            "the focused button must be ringed in the chosen accent - this is the " +
                "reported \"focus ring around buttons aren't following theme colors\"",
            emerald,
            focused?.ringColor
        )
        assertEquals(
            "with the app's 2dp focus stroke",
            2f,
            focused?.ringWidthDp
        )
        assertEquals(
            "while the plate behind it stays the XML's own raised tone: the ring " +
                "follows the accent and the fill follows the surface toggles, which " +
                "is why the two symptoms were reported separately",
            0xFF1D2530.toInt(),
            focused?.fill
        )
        assertFalse(
            "the brass the XML resolved must be gone",
            focused?.ringColor == BRASS
        )
    }

    @Test
    fun `the selection outline follows the accent too, at a hairline`() {
        AppPreferences.setAccentIndex(context, EMERALD_INDEX)
        val selected = chromePlateSpec(context, intArrayOf(android.R.attr.state_selected))

        assertEquals(
            "the in-player guide's \"this is the channel playing now\" outline is " +
                "the same accent at a hairline width",
            emerald,
            selected?.ringColor
        )
        assertEquals(1f, selected?.ringWidthDp)
    }

    @Test
    fun `an untouched install keeps the brass ring and the XML plate`() {
        val focused = chromePlateSpec(context, intArrayOf(android.R.attr.state_focused))

        assertEquals(
            "with the default palette the rebuild is an identity, which is why the " +
                "whole pass is skipped then",
            BRASS,
            focused?.ringColor
        )
        assertEquals(0xFF1D2530.toInt(), focused?.fill)
    }

    @Test
    fun `an untouched install keeps the brass ring and the inflated drawable`() {
        val view = View(context)
        val inflated = ContextCompat.getDrawable(context, R.drawable.mpv_control_bg)
        view.background = inflated

        retintPlayerChromeView(view, context)

        assertSame(
            "with neither toggle on and the accent untouched there is nothing to " +
                "re-theme, so the inflated drawable must survive untouched",
            inflated,
            view.background
        )
        assertEquals(
            "and the ring stays the brass it was inflated with",
            BRASS,
            themeAccentColor(context)
        )
    }

    // ── the picker row's ripple, over a selector ─────────────────────────

    @Test
    fun `a picker row's ripple carries the themed plate`() {
        AppPreferences.setAmoledBlack(context, true)
        AppPreferences.setAccentIndex(context, EMERALD_INDEX)

        val view = View(context)
        view.background = ContextCompat.getDrawable(context, R.drawable.picker_item_bg)
        retintPlayerChromeView(view, context)

        val ripple = view.background as? RippleDrawable
        assertTrue(
            "the picker row is a ripple wrapping a selector - the second shape " +
                "the old pass fell through",
            ripple != null
        )
        val content = ripple?.getDrawable(0) as? StateListDrawable
        assertTrue("and its content is the selector", content != null)

        content?.setState(intArrayOf(android.R.attr.state_focused))
        val focused = content?.current as? GradientDrawable
        assertEquals(
            "the focused picker row's plate follows the AMOLED toggle",
            AMOLED_RAISED,
            focused?.color?.defaultColor
        )
    }

    // ── the walk, over a real inflated layout ────────────────────────────

    @Test
    fun `the walk themes a whole inflated chrome layout`() {
        AppPreferences.setAmoledBlack(context, true)
        val card = LayoutInflater.from(context).inflate(R.layout.cast_member_item, null)

        retintPlayerChrome(card, context)

        val plate = card.background as? RippleDrawable
        assertTrue("the card's plate is the XML ripple over a selector", plate != null)
        val selector = plate?.getDrawable(0) as? StateListDrawable
        selector?.setState(intArrayOf(android.R.attr.state_focused))
        assertEquals(
            "its focused plate follows the AMOLED toggle, which is what the walk " +
                "is for - the card is inflated long after bindViews",
            AMOLED_RAISED,
            (selector?.current as? GradientDrawable)?.color?.defaultColor
        )
        val avatar = card.findViewById<ImageView>(R.id.cast_member_image).background
        assertEquals(
            "and the avatar circle's fixed #1D2530 oval goes with it (it is all " +
                "that shows for a cast member TMDB has no photo for)",
            AMOLED_RAISED,
            (avatar as? GradientDrawable)?.color?.defaultColor
        )
    }

    // ── the press flash ──────────────────────────────────────────────────

    @Test
    fun `the press flash is re-resolved from the content's fill`() {
        AppPreferences.setAccentIndex(context, EMERALD_INDEX)

        assertEquals(
            "an accent-filled control keeps the void flash the XML paired it with - " +
                "an accent-on-accent flash is no flash at all",
            ContextCompat.getColor(context, R.color.kb_void),
            themedRippleFlash(context, BRASS)
        )
        assertEquals(
            "while a surface-filled button flashes the chosen accent instead of " +
                "the brass it was inflated with",
            emerald,
            themedRippleFlash(context, AMOLED_SURFACE)
        )

        AppPreferences.setAccentIndex(context, 0)
        assertEquals(
            "at the default accent nothing has moved, so the flash stays brass",
            BRASS,
            themedRippleFlash(context, AMOLED_SURFACE)
        )
    }

    // ── wiring: one implementation, all three engines ────────────────────

    @Test
    fun `all three engines run the shared chrome pass`() {
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER).forEach { path ->
            val text = source(path).replace(Regex("\\s+"), " ")
            assertTrue(
                "$path must run the shared chrome pass - a private copy of it is " +
                    "how the focus ring went unthemed for so long",
                text.contains("retintPlayerChrome(")
            )
        }
    }

    @Test
    fun `every engine paints its own window in the theme's void`() {
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER).forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must paint its window from the toggle: the theme's static " +
                    "colorBackground leaves the ordinary #0A0E14 void wherever the " +
                    "video surface does not reach",
                text.contains("applyPlayerWindowTone(this)")
            )
        }
        AppPreferences.setAmoledBlack(context, true)
        assertEquals(
            "and that call resolves the AMOLED void - black, not #0A0E14",
            0xFF000000.toInt(),
            themeVoidWindowColor(context)
        )
    }

    @Test
    fun `no engine keeps a private copy of the chrome walk`() {
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER).forEach { path ->
            val text = source(path)
            assertFalse(
                "$path has its own chrome walk again - there is one, in " +
                    "PlayerChromeTheme.kt",
                text.contains("private fun themedChromeBackground(") ||
                    text.contains("private fun refillPlayerChromeView(")
            )
        }
    }

    // ── the state list, with and without the API-29 accessors ────────────

    /**
     * `StateListDrawable.getStateCount()` is API 29 and this app ships minSdk
     * 26, so the states are read where the platform has them and probed where it
     * does not. The two paths must end at the SAME states, or a Fire TV on API 28
     * would rebuild a different D-pad focus ring than a newer box.
     */
    @Test
    fun `the probe recovers the states the platform's own list reports`() {
        listOf(R.drawable.mpv_control_bg, R.drawable.channel_guide_item_bg).forEach { res ->
            val selector = ContextCompat.getDrawable(context, res) as StateListDrawable

            assertStates(
                chromeSelectorStates(selector),
                probeSelectorStates(selector),
                "$res must rebuild identically with and without the API-29 list"
            )
        }
    }

    @Test
    fun `a state the selector does not declare is not invented`() {
        val button = ContextCompat.getDrawable(context, R.drawable.mpv_control_bg)
            as StateListDrawable

        assertStates(
            listOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            probeSelectorStates(button),
            "mpv_control_bg declares no selected state, so the probe must not add " +
                "one - that would draw a selection outline on the control bar's " +
                "buttons that the XML never had"
        )

        val guide = ContextCompat.getDrawable(context, R.drawable.channel_guide_item_bg)
            as StateListDrawable

        assertStates(
            listOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            probeSelectorStates(guide),
            "the guide's \"this is the channel playing now\" state has to survive"
        )
    }

    @Test
    fun `the API-29 state accessors are never called outside their guard`() {
        val source = source(CHROME_THEME)
        val readerStart = source.indexOf("@RequiresApi(Build.VERSION_CODES.Q)")
        assertTrue(
            "the exact read must stay behind a version annotation, which is what " +
                "lint's NewApi requires on an app that ships minSdk 26",
            readerStart >= 0
        )
        val reader = source.substring(readerStart).substringBefore("\n/**")
        listOf("selector.stateCount", "selector.getStateSet(").forEach { call ->
            assertEquals(
                "$call must be called exactly once, and only in the guarded reader",
                1,
                Regex(Regex.escape(call)).findAll(source).count()
            )
            assertTrue("$call must sit inside the @RequiresApi reader", reader.contains(call))
        }
        assertFalse(
            "the per-state drawables come from setState/current (API 1) now, not " +
                "from getStateDrawable()",
            source.contains("getStateDrawable(")
        )
        assertTrue(
            "and the read is gated where it is chosen",
            source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** The focused plate of a chrome selector, after the pass has run over it. */
    private fun focusedPlate(drawableRes: Int): GradientDrawable =
        statePlate(drawableRes, intArrayOf(android.R.attr.state_focused))

    private fun statePlate(drawableRes: Int, state: IntArray): GradientDrawable {
        val view = View(context)
        view.background = ContextCompat.getDrawable(context, drawableRes)
        retintPlayerChromeView(view, context)
        val selector = view.background as StateListDrawable
        selector.setState(state)
        val plate = selector.current as? GradientDrawable
        assertTrue("$drawableRes must resolve to a plate for that state", plate != null)
        return plate!!
    }

    /** The two state lists, in order, compared element by element. */
    private fun assertStates(expected: List<IntArray>, actual: List<IntArray>, message: String) {
        assertEquals("$message (state count)", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertTrue(
                "$message (state $index)",
                expected[index].contentEquals(actual[index])
            )
        }
    }

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/$path")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $path")
    }
}
