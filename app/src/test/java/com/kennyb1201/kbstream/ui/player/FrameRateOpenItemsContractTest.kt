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
}
