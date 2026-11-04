package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring behind MPV's regular-subtitle size: when it is computed, and what
 * it must not touch.
 *
 * [MpvSubtitleFontSizeTest] pins the number. This pins the three things that
 * make the number reach the screen, read out of the source because a libmpv
 * `SurfaceView` cannot be driven in a JVM test (the same reason this tree's
 * other player contracts read the source):
 *
 *  - **the first frame**: the size is computed against the video's height, which
 *    mpv reports as 0 until a frame decodes, so `video-params/h` has to be
 *    observed and the size re-applied when it arrives - otherwise the open-time
 *    fallback is what every cue keeps for the whole session;
 *  - **a size change mid-playback**: the height is remembered, so the setting
 *    computes against the picture already on screen rather than waiting for
 *    another frame or dropping to the fallback;
 *  - **ASS stays untouched**: an appearance apply writes exactly `sub-font-size`,
 *    `sub-back-color`, `sub-border-size` and `sub-pos`. Nothing here may add a
 *    `sub-ass-*` / `sub-scale` option, which would restyle a fansub's own
 *    typesetting on both engines.
 *
 * It also pins the shared step list: the main player's `renderText` sizes its
 * text from `SubtitleOverlayStyle.exoTextSp`, not from a second copy of
 * 11 / 14 / 18, because the bug was two independent lists answering one
 * setting.
 */
class MpvSubtitleSizeContractTest {

    private val view: String by lazy { read(MPV_VIEW) }
    private val activity: String by lazy { read(MPV_ACTIVITY) }
    private val nativePlayer: String by lazy { read(NATIVE_PLAYER) }
    private val style: String by lazy { read(SUBTITLE_OVERLAY_STYLE) }

    /** `applySubtitleAppearance`'s own body, from its signature to the size writer. */
    private val appearance: String by lazy {
        view.slice("fun applySubtitleAppearance(", "private fun applySubtitleFontSize()")
    }

    /** The size writer itself. */
    private val sizeWriter: String by lazy {
        view.slice("private fun applySubtitleFontSize()", "private fun setStreamOption(")
    }

    /** Both size/background/position writers, which is the whole option set. */
    private val appearanceFamily: String by lazy {
        view.slice("fun applySubtitleAppearance(", "private fun setStreamOption(")
    }

    /** The int64 property handler, where the height arrives. */
    private val intEvent: String by lazy {
        view.slice("override fun eventProperty(property: String, value: Long)", "override fun eventProperty(property: String, value: Double)")
    }

    /** The observation block set up at init. */
    private val observations: String by lazy {
        view.slice("private fun observeProperties()", "// --- Surface lifecycle")
    }

    @Test
    fun `the size is computed, not a second fixed table in the view`() {
        assertTrue(
            "applySubtitleAppearance must size through the one shared mapping",
            appearance.contains("applySubtitleFontSize()") &&
                sizeWriter.contains("SubtitleOverlayStyle.mpvFontSize(")
        )
        assertFalse(
            "and must no longer carry mpv's old 44 / 55 / 66 of its own - that table grows with " +
                "the video's height while Exo's steps grow with the display's density, which is " +
                "the whole mismatch",
            appearance.contains("44.0") || appearance.contains("66.0")
        )
        assertTrue(
            "the fallback steps live beside the rule, so they cannot drift from it",
            style.contains("fun mpvFallbackFontSize(")
        )
    }

    @Test
    fun `the size is expressed in the same pixels as Exo's, through the display's scaled density`() {
        assertTrue(
            "the sp-to-pixel step has to be the one COMPLEX_UNIT_SP resolves through (user " +
                "font scale included), which is what setTextSize applies on the other engine",
            sizeWriter.contains("TypedValue.applyDimension(") &&
                sizeWriter.contains("TypedValue.COMPLEX_UNIT_SP")
        )
    }

    @Test
    fun `the first frame re-applies the size, through an observation of the video height`() {
        assertTrue(
            "video-params/h must be observed: it was not, and the open-time value is 0",
            observations.contains(
                "MpvBackend.observeProperty(\"video-params/h\", MpvFormat.MPV_FORMAT_INT64)"
            )
        )
        assertTrue(
            "and the handler must re-apply the size when it arrives (or changes on a " +
                "resolution switch)",
            intEvent.contains("\"video-params/h\" -> {") &&
                intEvent.contains("applySubtitleFontSize()")
        )
        assertTrue(
            "remembering the height is what keeps the number honest once the frame is up",
            intEvent.contains("videoHeightPx = height")
        )
        assertFalse(
            "a re-apply of the size must not rewrite the background or the position - they do " +
                "not depend on the height, and a redundant write is a visible restyle",
            intEvent.contains("sub-back-color") || intEvent.contains("sub-pos")
        )
    }

    @Test
    fun `a size change mid-playback computes against the height already in hand`() {
        assertTrue(
            "the writer must read the remembered height, not re-read mpv's property and not " +
                "fall back to a guess",
            sizeWriter.contains("videoHeightPx") &&
                !sizeWriter.contains("getPropertyIntOrNull")
        )
        assertTrue(
            "choosing a size re-applies the appearance with all three values, which is the " +
                "path the remembered height feeds",
            activity.contains("internal fun chooseSubtitleSize(size: Int) {") &&
                activity.contains(
                    "surface?.applySubtitleAppearance(subtitleSize, subtitleBackground, subtitlePosition)"
                )
        )
        assertTrue(
            "and the index it applied is remembered, so a later height change keeps the choice",
            appearance.contains("subtitleSize = size")
        )
    }

    @Test
    fun `an appearance apply writes exactly the four text-subtitle options`() {
        // `sub-pos` is written across two lines, so the call is read with its
        // argument list starting on the next line.
        val options = Regex("styleOption\\(\\s*\"([^\"]+)\"").findAll(appearanceFamily)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "background and position are unchanged; the size joins them and nothing else is set",
            setOf("sub-font-size", "sub-back-color", "sub-border-size", "sub-pos"),
            options
        )
        assertTrue(
            "the size option is written by the size writer, not smuggled in beside the fills",
            sizeWriter.contains("styleOption(\"sub-font-size\"")
        )
        assertFalse(
            "no ASS styling option may be set from the appearance path: mpv's sub-font-size does " +
                "not restyle an ASS script, and a sub-ass-* option here would",
            appearanceFamily.contains("sub-ass") || appearanceFamily.contains("sub-scale")
        )
        assertTrue(
            "the engine still pins ASS tracks to their authored look at open",
            view.contains("MpvBackend.setOptionString(\"sub-ass-override\", \"no\")")
        )
    }

    @Test
    fun `both engines answer the one setting from the one step list`() {
        assertTrue(
            "renderText must size text from the shared steps",
            nativePlayer.contains("SubtitleOverlayStyle.exoTextSp(subtitleSize)")
        )
        assertFalse(
            "and must not keep a second copy of them, which is how the two engines diverged",
            nativePlayer.contains("val sizes = listOf(11f, 14f, 18f)")
        )
        assertTrue(
            "the list itself is one table, with an out-of-range index read as neutral",
            style.contains("private val EXO_TEXT_SP = floatArrayOf(11f, 14f, 18f)") &&
                style.contains("EXO_TEXT_SP.getOrElse(sizeIndex)")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val SUBTITLE_OVERLAY_STYLE =
            "com/kennyb1201/kbstream/ui/player/SubtitleOverlayStyle.kt"
    }
}
