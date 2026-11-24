package com.kennyb1201.kbstream.ui.player

import android.app.Application
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBAccentPalette
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The overlay's cast band follows the AMOLED / pure-black toggles and the global
 * accent.
 *
 * Reported: on MPV, the actor chips kept the default palette - the navy chip
 * fill and the brass focus ring - with the AMOLED toggle on, while the rest of
 * the overlay followed it. The band is built by [PlayerChrome.bindCast], which
 * inflates `cast_member_item` long after the activity has run the shared chrome
 * walk ([retintPlayerChrome]); the tiles therefore kept the drawable exactly as
 * the XML resolved it. Exo only looked right because its walk happened to run
 * after its first bind, which is an ordering accident, not a fix - a source
 * switch re-binds the band on both engines and would have brought the navy back.
 *
 * These tests go through the same path the players do - `refreshTitle()` ->
 * `bindCast()` - because what regressed was a missing walk, not a rule: the walk
 * itself is pinned by [PlayerChromeThemeTest]. The ring is asserted through
 * [chromePlateSpec] rather than read back off the drawable, because the platform
 * has no getter for a shape's stroke (see that spec's own docstring).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = PlayerChromeCastThemeTest.NoopApplication::class)
class PlayerChromeCastThemeTest {

    /** The real Application starts work nothing here needs. */
    class NoopApplication : Application()

    private companion object {
        /** A palette entry whose channels all differ from the brass default. */
        const val EMERALD_INDEX = 17

        const val XML_SURFACE = 0xFF141A24.toInt()
        const val XML_RAISED = 0xFF1D2530.toInt()
        const val AMOLED_SURFACE = 0xFF06080B.toInt()
        const val AMOLED_RAISED = 0xFF0D1117.toInt()
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val focused = intArrayOf(android.R.attr.state_focused)

    private val emerald: Int
        get() = KBAccentPalette[EMERALD_INDEX].color.toArgb()

    private val brass: Int
        get() = ContextCompat.getColor(context, R.color.kb_accent)

    // ── the reported theme ───────────────────────────────────────────────

    @Test
    fun `an AMOLED cast band wears the pure-black plate and the chosen accent ring`() {
        AppPreferences.setAmoledBlack(context, true)
        AppPreferences.setAccentIndex(context, EMERALD_INDEX)

        bindCastTiles().forEachIndexed { index, tile ->
            val selector = tile.plateSelector()

            selector.setState(intArrayOf())
            assertEquals(
                "tile $index must fill with the AMOLED surface, not the XML's #141A24 - " +
                    "the navy actor chip is the field report",
                AMOLED_SURFACE,
                selector.current.fill()
            )

            selector.setState(focused)
            assertEquals(
                "and its focused plate must be the AMOLED raised tone, not #1D2530",
                AMOLED_RAISED,
                selector.current.fill()
            )

            assertEquals(
                "the avatar oval is themed by the same walk - it is all that shows " +
                    "for a cast member TMDB has no photo for",
                AMOLED_RAISED,
                (tile.findViewById<ImageView>(R.id.cast_member_image).background
                    as? GradientDrawable)?.color?.defaultColor
            )
        }

        assertEquals(
            "the focused chip must ring in the viewer's accent (Emerald on this " +
                "profile), not the brass the XML resolved",
            emerald,
            chromePlateSpec(context, focused)?.ringColor
        )
        assertFalse(
            "the brass ring must be gone",
            chromePlateSpec(context, focused)?.ringColor == brass
        )
    }

    // ── the untouched install ────────────────────────────────────────────

    @Test
    fun `the default palette leaves every chip exactly as the XML inflated it`() {
        AppPreferences.setAmoledBlack(context, false)
        AppPreferences.setAccentIndex(context, 0)

        bindCastTiles().forEachIndexed { index, tile ->
            val selector = tile.plateSelector()

            selector.setState(intArrayOf())
            assertEquals(
                "tile $index must keep the XML's surface fill when nothing has moved",
                XML_SURFACE,
                selector.current.fill()
            )

            selector.setState(focused)
            assertEquals(
                "and the XML's raised focused fill",
                XML_RAISED,
                selector.current.fill()
            )
        }
        assertEquals(
            "with the brass ring intact: the walk is a no-op on the default palette",
            brass,
            chromePlateSpec(context, focused)?.ringColor
        )
    }

    // ── wiring ───────────────────────────────────────────────────────────

    /**
     * The runtime tests above catch a walk that never runs; this pins WHERE it
     * runs, because running it before the tiles are added would theme an empty
     * row and leave the same navy chips on screen.
     */
    @Test
    fun `the band is re-walked after bindCast has added its tiles`() {
        val chrome = source("com/kennyb1201/kbstream/ui/player/PlayerChrome.kt")
        val body = chrome
            .substringAfter("private fun bindCast(")
            .substringBefore("private fun LinearLayout.inflateCastTile")

        assertTrue(
            "bindCast must re-walk the band it just inflated:\n$body",
            body.contains("retintPlayerChrome(row, row.context)")
        )
        assertTrue(
            "and it must do so after the tiles are added, not before",
            body.indexOf("row.addView(itemView)") <
                body.indexOf("retintPlayerChrome(row, row.context)")
        )
        assertTrue(
            "the walk stays scoped to the cast row - the rest of the overlay is " +
                "the activity's own pass",
            !body.contains("retintPlayerChrome(findViewById(")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * The tiles exactly as the players get them: the shared overlay inflated,
     * its title repainted from the host's cast (the MPV setup path), then each
     * tile read out of the band.
     */
    private fun bindCastTiles(): List<View> {
        val layer = LayoutInflater.from(context).inflate(R.layout.player_chrome, null)
        val cast = listOf(
            PlayerCastMember(id = 1, name = "Ada", character = "The Mechanic", profilePath = null),
            PlayerCastMember(id = 2, name = "Grace", character = null, profilePath = null)
        )
        PlayerChrome(layer, CastHost(cast)).refreshTitle()

        val row = layer.findViewById<LinearLayout>(R.id.chrome_cast_row)
        assertEquals("bindCast paints one tile per cast member", cast.size, row.childCount)
        return (0 until row.childCount).map { row.getChildAt(it) }
    }

    /** The selector inside the tile's ripple - the plate that carries the chip. */
    private fun View.plateSelector(): StateListDrawable {
        val ripple = background as? RippleDrawable
        assertTrue("the cast tile's plate is the XML ripple over a selector", ripple != null)
        val selector = ripple?.getDrawable(0) as? StateListDrawable
        assertTrue("and its content is the selector that carries the focus ring", selector != null)
        return selector!!
    }

    private fun Drawable?.fill(): Int? = (this as? GradientDrawable)?.color?.defaultColor

    /** A host whose only job is to hand the chrome a cast to draw. */
    private class CastHost(private val cast: List<PlayerCastMember>) : PlayerChromeHost {
        override fun chromeIsPlaying() = false
        override fun chromePositionMs() = 0L
        override fun chromeDurationMs() = 0L
        override fun onChromePlayPause() {}
        override fun onChromeSeekTo(positionMs: Long) {}
        override fun onChromeNext() {}
        override fun onChromeOpenSourcePicker() {}
        override fun onChromeOpenAudioPicker() {}
        override fun onChromeOpenSubtitlePicker() {}
        override fun onChromeOpenSpeedPicker() {}
        override fun onChromeOpenAspectPicker() {}
        override fun onChromeOpenSettings() {}
        override fun onChromeOpenInfo() {}
        override fun onChromeSwitchPlayer() {}
        override fun onChromeOpenExternal() {}
        override fun onChromeSkipIntro() {}
        override fun chromeTitleInfo() = ChromeTitleInfo(cast = cast)
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
