package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole-player deep dive (2026-10-27), pinned.
 *
 * Four areas were read as one pass - the player chrome, the ExoPlayer engine,
 * the MPV engine, and the session/flow layer that hands a session between them -
 * and the fixes that came out of it span all four. None of them can be driven in
 * a JVM test: both engines are Activities over a decoder, and the session layer
 * lives in a composable. So the wiring is pinned by reading the source, which is
 * how this repo's other player contracts do it.
 *
 * What is pinned here, and why each one fails silently on a TV otherwise:
 *
 *  - a hash row (P2P, no direct URL) in the failure walk used to be a no-op that
 *    REPORTED success, leaving "Trying next source…" over a dead player (S-F1);
 *  - the auto-switch budget was reset by every rebuild, so it never bound and a
 *    dead list was walked one full retry ladder at a time (S-F3);
 *  - tapping a hash row in the in-player picker closed the picker and changed
 *    nothing, with no word about why (S-F2);
 *  - MPV could open a file against a surface it never attached (M-F2);
 *  - MPV's exit save filed a resume point where ExoPlayer filed a completion
 *    (S-F7);
 *  - Back-to-picker resumed at the position the session STARTED at (S-F6);
 *  - a manual subtitle pick was reverted by the next READY with subtitle mode
 *    Off (E-F1), a sidecar was dropped entirely on separate-audio titles (E-F2),
 *    and a refused picker handoff offered no fallback while the identical
 *    refusal at READY time did (E-F5);
 *  - OFF for a show was undone by the remembered sidecar (M-F1) and the
 *    remembered track's language fallback was kind-blind (M-F3);
 *  - the auto-hide countdown died instead of reposting, leaving the overlay up
 *    over the picture after ordinary panel use (U-F1), an MPV hold-scrub had no
 *    feedback of any kind (U-F2), MPV's spinner flashed undebounced (U-F8), its
 *    gestures ignored an open picker/INFO/error card (U-F9), ExoPlayer's pinch
 *    said nothing when the chrome was hidden (U-F10), and an empty picker was a
 *    silent press (U-F12);
 *  - the MPV error ring could evict the line that named the cause (M-I8), the
 *    zap repaint guard compared channel NAMES (E-I7), and the session trace
 *    evicted the binge chain it exists to show and never recorded an exit save
 *    or a source switch (S-I11).
 */
class PlayerDeepDiveContractTest {

    private val native: String by lazy { read(NATIVE) }
    private val mpv: String by lazy { read(MPV) }
    private val mpvView: String by lazy { read(MPV_VIEW) }
    private val chrome: String by lazy { read(CHROME) }
    private val main: String by lazy { read(MAIN) }

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

    /** The text of the member at [signature] up to the next four-space member. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** [chars] from the first occurrence of [marker] - for markers, not members. */
    private fun window(source: String, marker: String, chars: Int): String {
        val at = source.indexOf(marker)
        assertTrue("source missing marker: $marker", at >= 0)
        return source.substring(at, minOf(at + chars, source.length))
    }

    // --- S-F1 / S-F3 / S-F2: the source walk and the source picker ---------

    @Test
    fun `the exo walk steps past a hash row instead of reporting a phantom switch`() {
        val ladder = functionBody(
            native,
            // The scope argument (tab = null here: every automatic recovery is
            // the merged walk) is part of the signature this pins.
            "private fun tryNextSource(\n" +
                "        delayMs: Long = 0L,\n" +
                "        statusText: String? = null,\n" +
                "        tab: String? = null\n" +
                "    ): Boolean {"
        )
        assertTrue(
            "a row with no direct URL cannot be opened by this engine, so the walk keeps looking",
            ladder.contains("if (!sources[candidate].url.isNullOrBlank()) {")
        )
        assertTrue(
            "and it must answer false when every row left is one - the caller falls to the card",
            ladder.contains("val index = nextIndex ?: return false")
        )
        assertFalse(
            "the old shape handed the hash row straight to switchToSource, whose own " +
                "`url ?: return` turned the whole walk into a silent no-op",
            ladder.contains("val nextStream = sources[nextIndex]")
        )
    }

    @Test
    fun `the auto-switch budget is reset per session, not per rebuild`() {
        val create = functionBody(native, "private fun createPlayer()")
        assertFalse(
            "every source switch rebuilds the player, so resetting the budget here made " +
                "MAX_AUTO_SOURCE_SWITCHES unreachable",
            create.contains("autoSourceSwitchCount = 0")
        )
        assertTrue(
            "the session-start step is the one place it starts over",
            functionBody(native, "private fun probeThenCreatePlayer()")
                .contains("autoSourceSwitchCount = 0")
        )
    }

    @Test
    fun `a hash row pressed in either engine's own picker says why and stays put`() {
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            val row = window(src, "isSelected = stream.url == currentUrl", 1_400)
            assertTrue(
                "$who must refuse a row it cannot open rather than closing the picker over it",
                row.contains("stream.url.isNullOrBlank()")
            )
            assertTrue(
                "$who must say what the row needs (TorBox resolves it, not the player)",
                row.contains("needs TorBox")
            )
        }
    }

    // --- M-F2 / S-F7: the MPV engine --------------------------------------

    @Test
    fun `a failed mpv surface attach never opens the file`() {
        val created = functionBody(mpvView, "surfaceCreated(holder: SurfaceHolder) {")
        assertTrue(
            "attachSurface swallows its own failure, so the caller has to check",
            created.contains("if (!surfaceAttached) {")
        )
        assertTrue(
            "and surface the engine card rather than opening against a window mpv does not hold",
            created.contains("onEngineFailed?.invoke(")
        )
    }

    @Test
    fun `mpv's exit save files the same completion verdict the back path does`() {
        val stop = functionBody(mpv, "override fun onStop() {")
        assertTrue(
            "the system ending the session must be judged like the viewer ending it",
            stop.contains("val completedOnExit = shouldRecordCompletion(")
        )
        assertTrue(
            "and the verdict must reach the write",
            stop.contains("saveProgress(reason = \"stop\", forceCompleted = completedOnExit)")
        )
    }

    // --- S-F6: the picker the exit returns to ------------------------------

    @Test
    fun `back-to-picker hands the resume to history, not the launch point`() {
        assertTrue(
            "the launch position is frozen for the session's life, so carrying it back " +
                "restarted the next source at the launch point",
            main.contains("resumePositionMs = 0L,")
        )
        assertFalse(
            "the stale carry is gone",
            main.contains("resumePositionMs = current.startPositionMs,")
        )
    }

    // --- E-F1 / E-F2 / E-F5: subtitles on the native engine ----------------

    @Test
    fun `every terminal branch of the subtitle auto-select pass latches`() {
        val pass = functionBody(native, "private fun autoSelectPreferredLanguages() {")
        assertTrue(
            "the pass has to latch at all - that is what stops a rebuffer's READY " +
                "from re-running it over a manual pick",
            pass.contains("if (changed) languagesAutoSelected = true")
        )
        val disables = Regex("setTrackTypeDisabled\\(C\\.TRACK_TYPE_TEXT, true\\)")
            .findAll(pass).map { it.range.first }.toList()
        assertTrue(
            "Off, NeedsMpv and Choice.Off each disable text; expected at least three",
            disables.size >= 3
        )
        disables.forEach { at ->
            // The window covers each branch's own trailing comment: the disable
            // and its latch are the same decision, and nothing else in the pass
            // sets `changed` in between.
            assertTrue(
                "a text-disable that does not latch is a manual pick reverted on the " +
                    "next rebuffer (the Off branch was exactly this)",
                pass.substring(at, minOf(at + 900, pass.length)).contains("changed = true")
            )
        }
    }

    @Test
    fun `a sidecar survives a separate-audio session`() {
        assertTrue(
            "the subtitle configuration travels on the item the VIDEO source is built from, " +
                "so the separate-audio merge carries it too",
            native.contains("mediaItemBuilder.setSubtitleConfigurations(listOf(sidecar))")
        )
        assertFalse(
            "the branch that logged and dropped it is gone",
            native.contains("External subtitles are not merged with a separate audio source")
        )
        assertTrue(
            "and the engine is still handed the subtitle area back",
            native.contains("if (sidecar != null) sidecarRenderedByApp = false")
        )
    }

    @Test
    fun `a refused picker handoff falls back to a fetched subtitle, as the auto path does`() {
        val refused = window(native, "This subtitle needs the MPV player", 1_600)
        assertTrue(
            "the viewer pressed a subtitle track: refusing the engine handoff must not be " +
                "the end of the road when a fetched text track would render here",
            refused.contains("maybeAutoFetchSubtitle()")
        )
    }

    // --- M-F1 / M-F3: subtitles on the mpv engine -------------------------

    @Test
    fun `an OFF pick beats the remembered sidecar on a reopen`() {
        val restore = functionBody(mpv, "private fun restoreRememberedSubtitle()")
        val off = restore.indexOf("if (subtitlesOff) return")
        val latch = restore.indexOf("subtitleMemoryRestored = true")
        assertTrue("the restore must consult the OFF fact at all", off >= 0)
        assertTrue(
            "and consult it BEFORE the once-per-open latch, so clearing OFF in the same " +
                "session can still restore",
            off in 0 until latch
        )
        val choose = functionBody(mpv, "internal fun chooseSubtitlesOff()")
        assertTrue(
            "the OFF pick also erases the memory the restore would come back from",
            choose.contains("PlayerTrackMemory.forget(this, subtitleMemoryKey())")
        )
    }

    @Test
    fun `the remembered track's language fallback keeps its kind`() {
        val apply = functionBody(mpvView, "fun applyRememberedSubtitleTrack(")
        assertTrue(
            "an exact signature still wins outright",
            apply.contains("firstOrNull { it.signature == signature }")
        )
        assertTrue(
            "and the language fallback is kind-ranked, so an SDH pick cannot degrade to a " +
                "forced/signs track on the next episode's encode",
            apply.contains("rankedSubtitleMatch(")
        )
        assertFalse(
            "first-in-language is the shape that lost the kind",
            apply.contains("?: tracks.firstOrNull { LanguageMatch.matches(")
        )
    }

    // --- U-F1 / U-F2 / U-F8 / U-F9 / U-F10 / U-F12: the chrome and gestures -

    @Test
    fun `a panel that outlives the auto-hide countdown does not strand the overlay`() {
        // An object expression, not a SAM lambda: the countdown reposts ITSELF,
        // and inside a lambda `this` is the chrome - which is why the repost did
        // not compile until the shape changed.
        val hide = functionBody(chrome, "private val hideRunnable = object : Runnable {")
        assertTrue(
            "the countdown must answer 'not now' by COMING BACK, not by dying",
            hide.contains("handler.postDelayed(this, autoHideMs)")
        )
        assertTrue(
            "and it must ask the host before hiding, as it always did",
            hide.contains("if (!host.chromeMayAutoHide()) {")
        )
    }

    @Test
    fun `an mpv hold-scrub draws a readout and leaves it up on release`() {
        assertTrue(
            "the readout exists",
            mpv.contains("private fun showScrubReadout()")
        )
        assertTrue(
            "every ramp tick re-labels it, so it tracks the hold",
            functionBody(mpv, "private val mpvScrubRunnable = object : Runnable {")
                .contains("showScrubReadout()")
        )
        assertTrue(
            "and the release draws it once more - the landing position is the point of a hold",
            functionBody(mpv, "private fun endMpvHoldScrub()")
                .contains("showScrubReadout()")
        )
    }

    @Test
    fun `mpv's buffering spinner is posted, not assigned`() {
        assertTrue(
            "a sub-second blip must not paint a spinner",
            mpv.contains(
                "handler.postDelayed(showMpvBufferingRunnable, MPV_BUFFERING_SPINNER_DELAY_MS)"
            )
        )
        assertTrue(
            "at the native player's own delay, so the engines feel the same",
            mpv.contains("private const val MPV_BUFFERING_SPINNER_DELAY_MS = 400L")
        )
        assertTrue(
            "and every place that takes the spinner down also drops the pending show",
            functionBody(mpv, "private fun hideMpvBuffering()")
                .contains("handler.removeCallbacks(showMpvBufferingRunnable)")
        )
    }

    @Test
    fun `every mpv panel that owns the screen blocks the touch gestures`() {
        val gate = window(mpv, "override fun gesturesAllowed(): Boolean =", 500)
        listOf(
            "the settings panel" to "settingsContainer?.visibility != View.VISIBLE",
            "an open picker" to "pickerContainer?.visibility != View.VISIBLE",
            "the INFO readout" to "!infoPanelVisible",
            "the error card" to "errorContainer?.visibility != View.VISIBLE"
        ).forEach { (what, condition) ->
            assertTrue("$what must block gestures, as it does on the native player", gate.contains(condition))
        }
    }

    @Test
    fun `an exo pinch names the aspect mode it landed on`() {
        val pinch = window(native, "override fun cycleAspect() {", 1_400)
        assertTrue(
            "a pinch does not raise the chrome, so the button's label is not the feedback",
            pinch.contains("Aspect: \${ASPECT_MODES[next]}")
        )
    }

    @Test
    fun `an empty picker says it is empty, on both engines`() {
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            val picker = functionBody(src, "private fun showPicker(mode: PickerMode) {")
            assertTrue(
                "$who keeps the panel shut on an empty list",
                picker.contains("if (items.isEmpty()) {")
            )
            assertTrue(
                "but it is no longer a press that did nothing",
                picker.contains("emptyPickerMessage(mode)")
            )
            val copy = functionBody(src, "private fun emptyPickerMessage(mode: PickerMode): String =")
            listOf("no audio tracks", "No other sources", "No subtitles").forEach { phrase ->
                assertTrue("$who must name the empty state: $phrase", copy.contains(phrase))
            }
        }
    }

    // --- M-I8 / E-I7 / S-I11: the diagnostics and the zap guard ------------

    @Test
    fun `the mpv error ring is wide enough for a reconnect burst`() {
        val reasons = read(MPV_ERROR_REASON)
        assertTrue(
            "max_retries=5 emits its own lines between the cause and FFmpeg's generic one; " +
                "a four-line ring could evict the cause before END_FILE classified it",
            reasons.contains("const val MAX_KEPT = 8")
        )
    }

    @Test
    fun `the zap repaint guard compares the channel id, not its name`() {
        assertTrue(
            "the banner records the id of the channel it is about",
            native.contains("zapChannelId = channel.channelId")
        )
        assertTrue(
            "and a slow EPG read only paints when that id still matches",
            native.contains("zapChannelId == channel.channelId")
        )
        assertFalse(
            "names are not unique across playlist groups - the cache key already knows this",
            native.contains("zapChannelName?.text?.toString() == channel.name")
        )
    }

    @Test
    fun `the session trace keeps a binge and records what happened in it`() {
        val trace = read(SESSION_TRACE)
        assertTrue(
            "the ring is shared with per-session event notes, so 12 evicted the chain it exists to show",
            trace.contains("private const val MAX_LINES = 24")
        )
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            assertTrue("$who must record a source switch", src.contains("\"source switch: s="))
            assertTrue("$who must record the exit save", src.contains("\"exit save: s="))
            assertTrue(
                "$who must write them through the trace, not its own ring alone",
                src.contains("PlaybackSessionTrace.note(")
            )
        }
    }

    @Test
    fun `the source-switch note carries the identity the report needs`() {
        // The session's own s/e next to the index it switched to: a dump has to
        // say which episode moved, not only that something moved.
        //
        // The index is pinned PER ENGINE, because the two keep it differently:
        // the Media3 ladder has a running counter, while MPV has none and derives
        // the number from the list the picker showed. This used to assert one
        // shared expression naming `currentSourceIndex` in both files - a field
        // MPV never had, so the assertion was matching a line the app could not
        // compile.
        val d = '$'
        val shape = "index=${d}INDEX/${d}{sources.size} auto=${d}isAutoRecovery"
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            assertTrue(
                "$who must record the source it switched to",
                src.contains("source switch: s=")
            )
            assertTrue(
                "$who must name the stream it landed on",
                src.contains("label=${d}currentSourceLabel")
            )
        }
        assertTrue(
            "the ladder's index is its own running one",
            native.contains(shape.replace("INDEX", "currentSourceIndex"))
        )
        assertTrue(
            "and MPV's is derived from the picker's list, since it counts none",
            mpv.contains(shape.replace("INDEX", "sourceIndex")) &&
                mpv.contains("val sourceIndex = sources.indexOfFirst { it.url == newUrl }")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val MPV_ERROR_REASON = "com/kennyb1201/kbstream/ui/player/MpvErrorReason.kt"
        const val CHROME = "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val SESSION_TRACE = "com/kennyb1201/kbstream/data/reporting/PlaybackSessionTrace.kt"
    }
}
