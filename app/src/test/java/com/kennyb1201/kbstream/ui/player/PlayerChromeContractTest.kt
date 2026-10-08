package com.kennyb1201.kbstream.ui.player

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The shared overlay's contract.
 *
 * The two players used to carry their own copy of this chrome, wired twice; this
 * pins the one copy they now share: every button reaches the host method it
 * always reached, a progress tick paints the same readouts, the bar hides itself
 * on the same six-second timeout, and it comes up. The chrome is inflated from
 * [R.layout.player_chrome] under neutral `chrome_*` ids, which is what makes it
 * the same overlay on both engines.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = PlayerChromeContractTest.NoopApplication::class)
class PlayerChromeContractTest {

    /** The real Application starts work nothing here needs. */
    class NoopApplication : Application()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun inflateChrome(): View =
        LayoutInflater.from(context).inflate(R.layout.player_chrome, null)

    // ── button wiring: one callback per button ───────────────────────────

    @Test
    fun `every control button calls the host method it always called`() {
        val host = FakeHost()
        val layer = inflateChrome()
        PlayerChrome(layer, host)

        layer.findViewById<View>(R.id.chrome_btn_play_pause).performClick()
        layer.findViewById<View>(R.id.chrome_btn_next).performClick()
        layer.findViewById<View>(R.id.chrome_btn_source).performClick()
        layer.findViewById<View>(R.id.chrome_btn_audio).performClick()
        layer.findViewById<View>(R.id.chrome_btn_subtitle).performClick()
        layer.findViewById<View>(R.id.chrome_btn_speed).performClick()
        layer.findViewById<View>(R.id.chrome_btn_aspect).performClick()
        layer.findViewById<View>(R.id.chrome_btn_player_switch).performClick()
        layer.findViewById<View>(R.id.chrome_btn_player_external).performClick()
        layer.findViewById<View>(R.id.chrome_btn_info).performClick()
        layer.findViewById<View>(R.id.chrome_btn_settings).performClick()

        assertEquals(
            listOf(
                "playPause", "next", "source", "audio", "subtitle", "speed",
                "aspect", "switch", "external", "info", "settings"
            ),
            host.calls
        )
    }

    @Test
    fun `the skip prompt calls back only while the host asks for it`() {
        val host = FakeHost()
        val layer = inflateChrome()
        val chrome = PlayerChrome(layer, host)
        val skip = layer.findViewById<TextView>(R.id.chrome_skip_intro)

        chrome.setSkipIntro("SKIP INTRO")
        assertEquals(View.VISIBLE, skip.visibility)
        skip.performClick()
        assertEquals(listOf("skipIntro"), host.calls)

        chrome.setSkipIntro(null)
        assertEquals(View.GONE, skip.visibility)
    }

    // ── progress ─────────────────────────────────────────────────────────

    @Test
    fun `refreshProgress renders the position and the duration`() {
        val host = FakeHost().apply {
            positionMs = 90_000L
            durationMs = 3_600_000L
            playing = true
        }
        val layer = inflateChrome()
        val chrome = PlayerChrome(layer, host)

        chrome.refreshProgress()

        assertEquals("01:30", layer.findViewById<TextView>(R.id.chrome_position).text.toString())
        assertEquals("1:00:00", layer.findViewById<TextView>(R.id.chrome_duration).text.toString())
    }

    @Test
    fun `an unknown duration stays the unknown glyph, not 00_00`() {
        val host = FakeHost().apply { durationMs = 0L }
        val layer = inflateChrome()
        PlayerChrome(layer, host).refreshProgress()

        assertEquals("--:--", layer.findViewById<TextView>(R.id.chrome_duration).text.toString())
    }

    // ── auto-hide ────────────────────────────────────────────────────────

    @Test
    fun `the bar hides itself after the existing timeout`() {
        val layer = inflateChrome()
        val chrome = PlayerChrome(layer, FakeHost())

        chrome.show()
        assertEquals(View.VISIBLE, layer.findViewById<View>(R.id.chrome_root).visibility)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlayerChrome.AUTO_HIDE_MS + 100))
        assertEquals(
            "the overlay must drop again after six seconds without input",
            View.GONE,
            layer.findViewById<View>(R.id.chrome_root).visibility
        )
    }

    @Test
    fun `the timeout is the six seconds both activities used`() {
        assertEquals(6_000L, PlayerChrome.AUTO_HIDE_MS)
    }

    // ── chapters ─────────────────────────────────────────────────────────

    @Test
    fun `chapter marks render when set and clear when empty`() {
        val layer = inflateChrome()
        val chrome = PlayerChrome(layer, FakeHost())
        val bar = layer.findViewById<ChapterSeekBar>(R.id.chrome_seekbar)

        val marks = listOf(
            ChapterMark(0L, "Cold open"),
            ChapterMark(600_000L, "Part two")
        )
        chrome.setChapters(marks, 3_600_000L)
        assertEquals(marks, bar.chapterMarks)
        assertEquals(3_600_000L, bar.chapterDurationMs)

        chrome.setChapters(emptyList(), 0L)
        assertTrue(
            "with no marks the bar draws exactly like the stock SeekBar it replaces",
            bar.chapterMarks.isEmpty()
        )
    }

    // ── the shared layout ────────────────────────────────────────────────

    @Test
    fun `the shared layout carries the whole overlay under neutral ids`() {
        val layer = inflateChrome()
        val ids = listOf(
            R.id.chrome_root, R.id.chrome_clock, R.id.chrome_ends_at,
            R.id.chrome_clear_logo, R.id.chrome_item_name,
            R.id.chrome_episode_label, R.id.chrome_episode_title, R.id.chrome_overview,
            R.id.chrome_badge_row, R.id.chrome_cast_section, R.id.chrome_cast_row,
            R.id.chrome_seekbar_row, R.id.chrome_seekbar, R.id.chrome_position,
            R.id.chrome_duration,
            R.id.chrome_btn_play_pause, R.id.chrome_btn_next, R.id.chrome_btn_source,
            R.id.chrome_btn_audio, R.id.chrome_btn_subtitle, R.id.chrome_btn_speed,
            R.id.chrome_btn_aspect, R.id.chrome_btn_player_switch,
            R.id.chrome_btn_player_external, R.id.chrome_btn_info, R.id.chrome_btn_settings,
            R.id.chrome_skip_intro
        )
        ids.forEach { id ->
            assertTrue(
                "the shared overlay must declare " +
                    context.resources.getResourceName(id),
                layer.findViewById<View>(id) != null
            )
        }
    }

    @Test
    fun `the shared chrome is themed by the shared walk`() {
        AppPreferences.setAmoledBlack(context, true)
        val layer = inflateChrome()

        retintPlayerChrome(layer, context)

        val button = layer.findViewById<ImageView>(R.id.chrome_btn_play_pause)
        val selector = button.background as StateListDrawable
        selector.setState(intArrayOf())
        val plate = selector.current as GradientDrawable
        assertEquals(
            "the one shared overlay must follow the AMOLED toggle exactly as the " +
                "activity roots did",
            0xFF06080B.toInt(),
            plate.color?.defaultColor
        )
    }

    // ── test double ──────────────────────────────────────────────────────

    /**
     * A host that records which callbacks fired, and reports a controllable
     * playhead. It is the rename the activities implement: nothing here is
     * overlay logic.
     */
    private class FakeHost : PlayerChromeHost {
        val calls = mutableListOf<String>()
        var playing = false
        var positionMs = 0L
        var durationMs = 0L

        override fun chromeIsPlaying() = playing
        override fun chromePositionMs() = positionMs
        override fun chromeDurationMs() = durationMs
        override fun onChromePlayPause() { calls += "playPause" }
        override fun onChromeSeekTo(positionMs: Long) { calls += "seekTo" }
        override fun onChromeNext() { calls += "next" }
        override fun onChromeOpenSourcePicker() { calls += "source" }
        override fun onChromeOpenAudioPicker() { calls += "audio" }
        override fun onChromeOpenSubtitlePicker() { calls += "subtitle" }
        override fun onChromeOpenSpeedPicker() { calls += "speed" }
        override fun onChromeOpenAspectPicker() { calls += "aspect" }
        override fun onChromeOpenSettings() { calls += "settings" }
        override fun onChromeOpenInfo() { calls += "info" }
        override fun onChromeSwitchPlayer() { calls += "switch" }
        override fun onChromeOpenExternal() { calls += "external" }
        override fun onChromeSkipIntro() { calls += "skipIntro" }
        override fun chromeTitleInfo() = ChromeTitleInfo()
    }
}
