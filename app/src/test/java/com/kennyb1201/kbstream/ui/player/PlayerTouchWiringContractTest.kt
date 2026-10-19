package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3's contract: the touch layer exists on phones and nowhere on TV.
 *
 * Both players were built for a remote, so the risk in adding gestures is not
 * that a gesture is wrong (that is [PlayerTouchRulesTest]) but that the TV path
 * quietly changes with it, or that a seek starts pausing the film. Those are
 * wiring facts, so they are read out of the sources the way the other player
 * contract tests read them.
 *
 * The invariant this pins hardest is the seek: [PlayerTouchGestures] must not
 * contain a single play or pause call, because the state the viewer chose
 * before the drag is the one the drag has to end in. The chrome's own scrub bar
 * pauses on purpose and lives in PlayerChrome, not here.
 */
class PlayerTouchWiringContractTest {

    private fun mainSource(name: String): String = File(sourceRoot(), name).readText()

    private fun sourceRoot(): File {
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

    private val player = "com/kennyb1201/kbstream/ui/player/"

    @Test
    fun `the main player keeps the remote's touch arm exactly and adds the phone one behind the gate`() {
        val src = mainSource(player + "NativePlayerActivity.kt")

        assertTrue(
            "the TV arm is untouched: a press on the picture toggles the chrome",
            src.contains(
                "                if (event.action == android.view.MotionEvent.ACTION_UP) {\n" +
                    "                    if (controlsVisible) hideControls() else showControls()\n" +
                    "                }"
            )
        )
        assertTrue(
            "and the same listener hands every other form factor to the gesture layer",
            src.contains("com.kennyb1201.kbstream.FormFactor.isTv") &&
                src.contains("playerTouchGestures?.onTouch(playerView, event)")
        )
        assertTrue(
            "the gesture layer is constructed only off TV",
            src.contains(
                "if (!com.kennyb1201.kbstream.FormFactor.isTv) attachPhoneTouchGestures()"
            )
        )
    }

    @Test
    fun `a swipe seek commits without deciding whether the film is playing`() {
        val src = mainSource(player + "NativePlayerActivity.kt")
        val start = src.indexOf("override fun commitSeek(targetMs: Long) {")
        assertTrue("commitSeek must exist", start >= 0)
        val body = src.substring(start, start + 400)

        assertTrue("it seeks", body.contains("exoPlayer?.seekTo(targetMs)"))
        assertFalse(
            "and it does not pause, play, or read playWhenReady: the drag leaves " +
                "the state it found (PlayerTouchRules.playAfterSeek)",
            body.contains("pause") || body.contains("play(") ||
                body.contains("playWhenReady")
        )
    }

    @Test
    fun `the gesture layer itself never touches playback`() {
        val src = mainSource(player + "PlayerTouchGestures.kt")

        assertFalse(
            "a gesture is not a transport command",
            src.contains(".play()") || src.contains(".pause(") ||
                src.contains("playWhenReady") || src.contains("setPaused")
        )
        assertTrue(
            "a tap is read on the lift, so a drag can never end in a toggle",
            src.contains("override fun onSingleTapUp")
        )
        assertTrue(
            "a hold is fullscreen, unless the finger was dragging",
            src.contains("override fun onLongPress") && src.contains("if (dragAxis != null) return")
        )
        assertTrue(
            "a pinch walks the aspect modes once",
            src.contains("ScaleGestureDetector") && src.contains("if (!pinched)")
        )
        assertTrue(
            "a drag only starts turning into a seek after the slop",
            src.contains("PlayerTouchRules.axisFor(dx, dy, slop)")
        )
        assertTrue(
            "the halves decide brightness or volume",
            src.contains("PlayerTouchRules.zoneFor(event.x, v.width)")
        )
    }

    @Test
    fun `the mpv engine gets the same layer, off TV only`() {
        val src = mainSource(player + "MpvPlayerActivity.kt")

        assertTrue(
            "attached where the surface is built",
            src.contains(
                "if (!com.kennyb1201.kbstream.FormFactor.isTv) {\n" +
                    "            attachPhoneTouchGestures(view)"
            )
        )
        assertTrue(
            "its tap toggles the shared chrome",
            src.contains(
                "if (controlsVisible) setControlsVisible(View.GONE) else showControls()"
            )
        )
        assertTrue(
            "its pinch reuses the engine's own aspect cycle",
            src.contains("override fun cycleAspect() = this@MpvPlayerActivity.cycleAspect()")
        )
        assertTrue(
            "and its seek is the engine's own",
            src.contains("override fun commitSeek(targetMs: Long) = seekTo(targetMs)")
        )
    }

    @Test
    fun `both players lay the picture out as a portrait band and re-run it on rotation`() {
        listOf("NativePlayerActivity.kt", "MpvPlayerActivity.kt").forEach { name ->
            val src = mainSource(player + name)

            assertTrue("$name applies the stage", src.contains("PlayerPortraitStage.apply("))
            assertTrue(
                "$name re-applies it after a rotation, since configChanges keeps the activity alive",
                src.contains(
                    "override fun onConfigurationChanged(" +
                        "newConfig: android.content.res.Configuration)"
                ) && src.contains("        applyPlayerPortraitStage()\n    }")
            )
        }
    }

    @Test
    fun `the external handoff engine needs none of this`() {
        val src = mainSource(player + "ExternalPlayerActivity.kt")

        assertFalse(
            "it plays no video itself, so there is no picture to gesture over",
            src.contains("PlayerTouchGestures") || src.contains("PlayerPortraitStage")
        )
    }
}
