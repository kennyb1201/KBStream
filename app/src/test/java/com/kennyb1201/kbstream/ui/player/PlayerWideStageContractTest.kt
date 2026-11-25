package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9's contract, and the line it must not cross.
 *
 * The wide-window stage is layout params only: a capped, centred bottom chrome
 * cluster above 840dp, restored exactly when it stops applying. Playback already
 * survives resize (the players declare `density|uiMode` and re-lay out in
 * `onConfigurationChanged`), so anything this stage did beyond params would be a
 * new way to interrupt a picture that was fine - hence the "no playback calls"
 * assertion below, which is the one a future edit is most likely to break.
 */
class PlayerWideStageContractTest {

    private val gestures: String by lazy { stripComments(source(GESTURES)) }
    private val native: String by lazy { stripComments(source(NATIVE)) }
    private val mpv: String by lazy { stripComments(source(MPV)) }
    private val chrome: String by lazy { source(CHROME_XML) }

    @Test
    fun `the stage asks the shared rule and restores what the xml declared`() {
        assertTrue(
            "the wide stage must ask MobileAdaptiveRules, so the 840dp boundary " +
                "and the TV answer live in the one pure place they are tested",
            gestures.contains("MobileAdaptiveRules") &&
                gestures.contains(".playerWideStageApplies(")
        )
        assertTrue(
            "it must remember the original width and gravity per view, or a " +
                "resize back below the breakpoint leaves the cap on",
            gestures.contains("intArrayOf(params.width, params.gravity)") &&
                gestures.contains("params.width = saved[0]")
        )
        assertTrue(
            "the cluster is capped by the rule's own constant, not a literal",
            gestures.contains("PLAYER_CHROME_MAX_WIDTH_DP")
        )
    }

    @Test
    fun `both engines apply it from the seam they already re-lay out through`() {
        listOf("native" to native, "mpv" to mpv).forEach { (engine, source) ->
            assertTrue(
                "$engine does not apply the wide stage, so a tablet in landscape " +
                    "keeps the full-width chrome",
                source.contains(
                    "PlayerWideStage.apply(this, root, findViewById(R.id.chrome_bottom_cluster))"
                )
            )
            assertTrue(
                "$engine must apply it beside the portrait stage, in the same " +
                    "function every config change comes through",
                source.contains("PlayerPortraitStage.apply(") &&
                    source.contains("PlayerWideStage.apply(")
            )
        }
    }

    @Test
    fun `the cluster the stage caps is named in the shared chrome layout`() {
        assertTrue(
            "the bottom cluster has no id, so the stage has nothing to cap",
            chrome.contains("android:id=\"@+id/chrome_bottom_cluster\"")
        )
    }

    @Test
    fun `the cap is settled after layout, not against the window the activity left`() {
        // `onConfigurationChanged` hands over the new configuration before the
        // tree is measured against it, so `root.width` on the first pass is the
        // previous window: a tablet rotated into landscape was capped at its
        // portrait width and kept it until the next rotation. A stage that is
        // in the build and still looks absent is worse than one that is missing,
        // so the second, post-layout pass is part of the contract.
        val stage = stripComments(source(GESTURES))
            .substringAfter("internal object PlayerWideStage")
        assertTrue(
            "without a pass after layout, the cap uses the width of the window " +
                "the activity just left",
            stage.contains("root.post { applyNow(activity, root, view) }")
        )
        assertFalse(
            "and the posted pass must not post again, or every resize queues " +
                "another layout pass for as long as the player is open",
            stage.substringAfter("private fun applyNow").contains("post {")
        )
    }

    @Test
    fun `the cap is measured against the room the chrome gives the cluster`() {
        // The cluster's parent is the chrome root with its own 48dp horizontal
        // padding. A cap of min(960dp, window) is wider than that content box on
        // any window below ~1056dp, so the seek bar would grow into the padding
        // and touch the screen edge on exactly the tablets this stage is for.
        val stage = stripComments(source(GESTURES))
            .substringAfter("internal object PlayerWideStage")
        assertTrue(
            "the cap must be the smaller of the constant and the parent's content " +
                "width, or a window between 840dp and ~1056dp gets a wider cluster " +
                "than the XML gave it",
            stage.contains("parent.width - parent.paddingLeft - parent.paddingRight") &&
                stage.contains("params.width = minOf(capPx, available.coerceAtLeast(0))")
        )
    }

    @Test
    fun `the stage touches layout only`() {
        val stage = stripComments(source(GESTURES))
            .substringAfter("internal object PlayerWideStage")
        listOf("playWhenReady", "seekTo(", "start()", "pause()", "player.").forEach { forbidden ->
            assertFalse(
                "the wide stage must not touch playback: `$forbidden` inside it " +
                    "would be a new way to interrupt a picture that resize already " +
                    "survives",
                stage.contains(forbidden)
            )
        }
    }

    private fun source(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        error("app/src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PLAYER = "app/src/main/java/com/kennyb1201/kbstream/ui/player/"
        const val GESTURES = PLAYER + "PlayerTouchGestures.kt"
        const val NATIVE = PLAYER + "NativePlayerActivity.kt"
        const val MPV = PLAYER + "MpvPlayerActivity.kt"
        const val CHROME_XML = "app/src/main/res/layout/player_chrome.xml"
    }
}
