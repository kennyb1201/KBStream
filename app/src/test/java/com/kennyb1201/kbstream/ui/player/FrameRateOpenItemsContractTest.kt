package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three AFR items the 2026-10-28 landing reconciliation left open, pinned
 * where a JVM test can see them.
 *
 * All three are decisions about the Android shell rather than arithmetic:
 *
 *  1. **S1 (audit AFR)** — 23.976 fps film is an exact multiple of both 23.976 Hz
 *     and 24.000 Hz, so the ladder's closest-rate rule always chose 23.976. The
 *     dive could not settle whether a panel presenting that mode badly is what
 *     makes film judder with matching on, and arithmetic cannot — so the choice
 *     is armed by a device-local preference, read by both engines, off by
 *     default. The ladder's own rules are covered by [FrameRateMatchTest].
 *  2. **S6** — a confirm that fires while the picture is stopped is deferred and
 *     replayed, not dropped. It used to vanish, which left a session that paused
 *     inside the 1.5 s window with no landing line at all.
 *  3. **C1's live half** — a source that will never be matched (a live channel)
 *     puts the panel back instead of standing at the previous title's rate.
 *
 * Source text is what a JVM test can see of an Activity; each assertion names
 * the call site it is about so a refactor that moves one fails loudly.
 */
class FrameRateOpenItemsContractTest {

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

    private fun source(path: String): String =
        File(sourceRoot, path).readText().replace(Regex("\\s+"), " ")

    private fun matcher(): String =
        source("com/kennyb1201/kbstream/ui/player/FrameRateMatch.kt")

    private fun player(): String =
        source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")

    private fun mpvPlayer(): String =
        source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")

    private fun preferences(): String =
        source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")

    private fun settingsScreen(): String =
        source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")

    private fun settingsSearch(): String =
        source("com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt")

    // ── S1: the whole-rate preference ──────────────────────────────────

    @Test
    fun `both engines hand the matcher this panel's answer`() {
        // Read at construction, not per offer: a ladder that changed halfway
        // through a title would be renegotiating HDMI under a playing film.
        listOf("the native player" to player(), "the MPV player" to mpvPlayer()).forEach {
            (name, text) ->
            assertTrue(
                "$name must pass the device's answer into FrameRateMatcher",
                text.contains("preferWholeRate = AppPreferences.getPreferWholeFrameRate(this)")
            )
        }
        assertTrue(
            "and the matcher's own default is off, so a caller that says nothing " +
                "keeps the shipped behaviour",
            matcher().contains("private val preferWholeRate: Boolean = false")
        )
    }

    @Test
    fun `the preference is threaded through the search the ladder actually calls`() {
        val text = matcher()
        assertEquals(
            "every bestMode call in the shell has to carry the flag, or the row would " +
                "change nothing on one arm of the ladder",
            2,
            text.split("FrameRateMatch.bestMode(contentFps, modes, preferWholeRate)").size - 1
        )
        assertTrue(
            "the pre-fix (bisect) order asks the same question the same way",
            text.contains("FrameRateMatch.chooseMode( contentFps, modes, currentModeId, preferWholeRate )")
        )
        assertTrue(
            "and the pure step receives it rather than re-reading a pref of its own",
            text.contains("exactMultipleMode(content, usable, preferWholeRate)")
        )
    }

    @Test
    fun `the preference is stored per device and off by default`() {
        val prefs = preferences()
        assertTrue(
            "a default of true would switch every TV onto the rate nobody has tried",
            prefs.contains("KEY_PREFER_WHOLE_FRAME_RATE, false")
        )
        assertTrue(prefs.contains("KEY_PREFER_WHOLE_FRAME_RATE = \"prefer_whole_frame_rate\""))
    }

    @Test
    fun `the row is drawn, findable, and writes the preference`() {
        val screen = settingsScreen()
        val label = "Prefer 24 Hz Over 23.976 Hz"
        assertTrue(
            "the row has to be next to the switch it refines, got none in SettingsScreen",
            screen.contains("label = \"$label\"")
        )
        assertTrue(
            "and toggling it has to persist, or it does nothing at all",
            screen.contains("AppPreferences.setPreferWholeFrameRate(context, it)")
        )
        assertTrue(
            "a drawn row no query can name is the SE-2 shape the search index exists for",
            settingsSearch().contains("\"$label\"")
        )
    }

    // ── S6: a confirm a pause swallowed ────────────────────────────────

    @Test
    fun `a confirm that fires on a stopped picture is deferred, not dropped`() {
        val text = matcher()
        assertTrue(
            "the look has to be kept, because it is the only record of whether the " +
                "panel took the request",
            text.contains(
                "deferredConfirm = { confirm(serial, beforeModeId, contentFps, step, requestedMode) }"
            )
        )
        assertTrue(
            "and replayed once the picture moves",
            text.contains("fun resumeDeferredConfirm()")
        )
        assertTrue(
            "on the hold tick, which is the one thing running while a title plays",
            text.contains("fun verifyEngagedMode() { // The tick is the one thing")
        )
    }

    @Test
    fun `both engines replay it on the way back into the session`() {
        listOf("the native player" to player(), "the MPV player" to mpvPlayer()).forEach {
            (name, text) ->
            val rearm = text.substringAfter("private fun rearmFrameRateMatch()")
                .substringBefore("private fun ensureFrameRateMatcher()")
            assertTrue(
                "$name must replay a deferred look before the remembered-rate guard can " +
                    "return, or a paused engage is never checked",
                rearm.contains("frameRateMatcher?.resumeDeferredConfirm()")
            )
        }
    }

    @Test
    fun `a deferred look never outlives its source or its session`() {
        val text = matcher()
        val begin = text.substringAfter("fun beginSource()").substringBefore("fun verifyEngagedMode()")
        assertTrue(
            "a look at the previous source's panel must not read this source's answer",
            begin.contains("deferredConfirm = null")
        )
        val release = text.substringAfter("fun release()").substringBefore("private fun restorePanelMode()")
        assertTrue(
            "and nothing is replayed after the player has gone",
            release.contains("deferredConfirm = null")
        )
    }

    // ── C1's live half ─────────────────────────────────────────────────

    @Test
    fun `a live source puts the panel back instead of inheriting a film's rate`() {
        val restart = player()
            .substringAfter("private fun restartFrameRateMatchForSource()")
            .substringBefore("/**")
        assertTrue(
            "a live source is never matched, so the mode the last title set has to be " +
                "given back here rather than at exit",
            restart.contains("if (isLiveChannel) matcher.restoreForUnmatchedSource()")
        )
        assertTrue(
            "and it happens before the new source's window opens, which is what the " +
                "call after it does",
            restart.indexOf("restoreForUnmatchedSource()") < restart.indexOf("matcher.beginSource()")
        )
    }

    @Test
    fun `the restore drops the hold and writes the pre-title mode`() {
        val text = matcher()
        val restore = text.substringAfter("fun restoreForUnmatchedSource()")
            .substringBefore("private fun apply(")
        assertTrue("the held mode goes", restore.contains("heldModeId = UNSET"))
        assertTrue("the surface vote is cleared", restore.contains("clearSurfaceRate()"))
        assertTrue(
            "and the panel is put back through the same write release() uses",
            restore.contains("restorePanelMode()")
        )
        assertTrue(
            "a session that never engaged must do nothing: the ordinary launch on a channel",
            restore.contains("if (!restoreOnRelease) return")
        )
        assertFalse(
            "paying the debt here must not leave it owing again at exit",
            restore.contains("restoreOnRelease = true")
        )
    }

    // ── The repair budget is never spent on a switch in flight ─────────

    /**
     * Field capture 2026-10-29 (Onn 4K Plus, 0.9.110):
     *
     *     session: afr asked for a mode: mode=28 ... (was 22)
     *     session: afr re-asked the engaged mode: mode=28 was 22
     *     session: afr panel moved: mode=22->28 ... step=MODE
     *
     * The hold tick ran ~a second after the request, read the panel's OLD mode
     * (the HDMI renegotiation was still running), re-asked the same mode and
     * spent the session's one repair doing it. The switch landed at the
     * confirm immediately afterwards - so the extra request bought nothing, it
     * made the opening renegotiate twice, and a panel that really does leave
     * the engaged mode later in the film had no repair left (the 3:2 judder
     * this class exists to remove, back for the rest of the title).
     */
    @Test
    fun `the hold tick leaves a mode request alone until it can have landed`() {
        val text = matcher()
        val verify = text.substringAfter("fun verifyEngagedMode()")
            .substringBefore("fun release()")

        assertTrue(
            "the settle window must gate the repair, before the repair budget " +
                "can be spent: $verify",
            verify.contains(
                "if (modeRequestedAtMs != 0L && now - modeRequestedAtMs < " +
                    "REASSERT_SETTLE_MS) return"
            )
        )
        assertTrue(
            "and it must come before this session gives up its one repair: $verify",
            verify.indexOf("REASSERT_SETTLE_MS") < verify.indexOf("reassertedMode = true")
        )
        val request = text.substringAfter("private fun requestMode(")
            .substringBefore("private fun setPreferredModeId(")
        assertTrue(
            "a request is what starts the window: $request",
            request.contains("modeRequestedAtMs = SystemClock.elapsedRealtime()")
        )
        val begin = text.substringAfter("fun beginSource()")
            .substringBefore("fun verifyEngagedMode()")
        assertTrue(
            "and a new source does not inherit the previous one's window: $begin",
            begin.contains("modeRequestedAtMs = 0L")
        )
    }

    // ── Tunneling and matching cannot both win ─────────────────────────

    /**
     * Field A/B (Onn 4K Plus, 2026-10-30, same title, same 16 Mbps source, auto
     * buffer): matching ON juddered at a correct 23.976/24.000 mode and matching
     * OFF played clean at 60 Hz.
     *
     * A tunnelled session's frames are paced by the platform's tunnel, and the
     * rate this app asks for is a WINDOW attribute the tunnel is never told
     * about - so at film rates the two settings contradict each other. Matching
     * is the explicit request for cadence, so it wins: the tunnel is skipped for
     * that session, which is what this pins.
     */
    @Test
    fun `a tunnelled session is not used while frame-rate matching is on`() {
        val text = player()
        val tunnel = text.substringAfter("val audioTuningActive =")
            .substringBefore("playWhenReady = !fromActorReturn")

        assertTrue(
            "the tunnel gate must consult the matching setting: $tunnel",
            tunnel.contains("val frameRateMatchActive =") &&
                // Labeled on purpose: this block is the player's own `apply`,
                // so a bare `this` is the ExoPlayer and does not compile as a
                // Context (caught by the v0.9.111 build).
                tunnel.contains(
                    "AppPreferences.getMatchFrameRate(this@NativePlayerActivity)"
                )
        )
        assertTrue(
            "on a TV only, the same gate the matcher itself uses: $tunnel",
            tunnel.contains("com.kennyb1201.kbstream.FormFactor.isTv")
        )
        assertTrue(
            "and that flag must close the tunnel instead of only logging: $tunnel",
            tunnel.contains("!frameRateMatchActive")
        )
        assertTrue(
            "a skip a viewer can see in the report has to say why: $tunnel",
            tunnel.contains("Tunneling skipped: frame-rate matching is on")
        )
    }
}
