package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPV: a track list that arrives after the file-open pass still gets the audio
 * pick applied.
 *
 * The MPV engine states this title's audio track once, from mpv's file-loaded
 * callback ([MpvPlayerActivity.applyRememberedTracks]), and mpv's open-time
 * `alang` covers the ordinary file. mpv's track list is not final at that
 * moment for every stream: a split stream's audio is added afterwards with
 * `audio-add`, and a container index that is still being built reports its
 * tracks in a later `track-list` change. On those files the pass had nothing to
 * choose from - the remembered pick missed, the language found no track - and
 * mpv's own default (the muxer's first / DEFAULT-flagged track, on a dual-audio
 * release often another language) played for the whole episode.
 *
 * The `track-list` property is already observed (bare, because the list is read
 * through its property paths), but its notification went nowhere: the bare
 * `eventProperty` overload was `= Unit`. It now raises a callback, and the
 * activity re-runs the same pass - once, only when the open pass really had no
 * list, and before its own track selection can re-trigger the property.
 *
 * Read out of the source, like this engine's other contracts, because
 * exercising it needs libmpv and a stream.
 */
class MpvLateTrackListAudioContractTest {

    private val view by lazy { read(MPV_VIEW) }
    private val activity by lazy { read(MPV_ACTIVITY) }

    private val bareProperty by lazy {
        block(view, "override fun eventProperty(property: String) {")
    }
    private val handler by lazy { block(activity, "private fun onTrackListChanged() {") }
    private val openPass by lazy { block(activity, "private fun applyRememberedTracks() {") }
    private val fileLoaded by lazy { block(activity, "private fun onFileLoaded(mediaTitle: String?) {") }

    // ── The notification ──────────────────────────────────────────────────

    @Test
    fun `the bare track-list notification is routed, not swallowed`() {
        assertTrue(
            "the view exposes a callback for the change",
            view.contains("var onTrackListChanged: (() -> Unit)? = null")
        )
        assertTrue(
            "the bare overload is the one `track-list` arrives on, and it now does something",
            bareProperty.contains("if (property == \"track-list\")")
        )
        assertTrue(
            "delivered on the main thread, like every other view callback",
            bareProperty.contains("post { onTrackListChanged?.invoke() }")
        )
        assertTrue(
            "and the observation it answers is still the bare one - the list is read " +
                "through its property paths, not through this event",
            view.contains("MpvBackend.observeProperty(\"track-list\", MpvFormat.MPV_FORMAT_NONE)")
        )
    }

    @Test
    fun `the activity wires the callback`() {
        assertTrue(
            "the activity subscribes next to the other view callbacks",
            activity.contains("view.onTrackListChanged = { onTrackListChanged() }")
        )
    }

    // ── What the open pass records ────────────────────────────────────────

    @Test
    fun `the open pass says whether it had a list to work from`() {
        val decision = openPass.indexOf("view.applyRememberedAudioTrack(audioTrackSignature)")
        val record = openPass.indexOf("audioSelectionWaitingForTracks = view.audioTracks().isEmpty()")
        assertTrue("the audio arm is still the file-open pick", decision >= 0)
        assertTrue(
            "the wait is recorded from the SAME list the pick was asked against, after it",
            record > decision
        )
    }

    // ── The re-run ────────────────────────────────────────────────────────

    @Test
    fun `only a pass that had no list re-runs, and only once there is one`() {
        assertTrue(
            "a list for the file that is open, not for the next load",
            handler.contains("if (!fileLoaded) return")
        )
        assertTrue(
            "nothing is re-run unless the open pass really missed",
            handler.contains("if (!audioSelectionWaitingForTracks) return")
        )
        assertTrue(
            "and not until the list actually carries an audio track",
            handler.contains("if (view.audioTracks().isEmpty()) return")
        )
        assertEquals(
            "the wait is consumed before the pass, so the pass's own track selection - " +
                "which rewrites track-list/<n>/selected, another change of this property - " +
                "cannot re-enter here",
            1,
            Regex("audioSelectionWaitingForTracks = false").findAll(handler).count()
        )
        assertTrue(
            "consumed first, pass second",
            handler.indexOf("audioSelectionWaitingForTracks = false") <
                handler.indexOf("applyRememberedTracks()")
        )
    }

    @Test
    fun `it re-runs the file-open pass itself, not a second picker`() {
        assertTrue(
            "the same pass the file open runs",
            handler.contains("applyRememberedTracks()") &&
                fileLoaded.contains("applyRememberedTracks()")
        )
        listOf(
            "selectAudioLanguage(",
            "applyRememberedAudioTrack(",
            "setPropertyInt(\"aid\"",
            "selectAudioTrack("
        ).forEach { pick ->
            assertFalse("the late list must not grow its own '$pick' path", handler.contains(pick))
        }
        assertFalse(
            "nor write anything down: the stored pick is what is being re-stated",
            handler.contains("persistTitlePreferences") || handler.contains("audioTrackSignature =")
        )
        assertFalse(
            "and it clears no remembered language",
            handler.contains("audioLanguage =")
        )
    }

    @Test
    fun `the parity table names the late list on both engines`() {
        assertTrue(
            "the row both engines are held to",
            read(TRACK_BRIDGE).contains("10. A track list that arrives AFTER the engine's open pass")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced: $declaration")
    }

    private fun read(relative: String): String {
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
        const val TRACK_BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
