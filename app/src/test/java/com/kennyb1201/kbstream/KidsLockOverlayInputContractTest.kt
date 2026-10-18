package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kids time-lock overlay has to TAKE the input, not just paint over it.
 *
 * It used to be a plain `Box` with a background and nothing else: no
 * `focusable`, no key interception, no BackHandler, no focus request. So when a
 * limit engaged, the control UNDERNEATH kept focus - D-pad presses went on
 * driving the covered screen (blind navigation, opening screens and settings)
 * and taps passed through the non-consuming Box. The only focusable node it had
 * was the parent-PIN field, which nothing ever requested, so a parent picking up
 * the remote had to D-pad-hunt from a hidden node to reach it.
 *
 * Playback is still ended by `KidsTimeGuard.enforceLock` inside the three player
 * Activities; this pins the shell's half.
 */
class KidsLockOverlayInputContractTest {

    private val overlay: String by lazy {
        val src = MainActivitySource.read()
        val start = src.indexOf("private fun KidsTimeLockOverlay() {")
        assertTrue("KidsTimeLockOverlay must exist", start >= 0)
        val end = src.indexOf("private fun UpdateAvailablePopup(", start)
        assertTrue("the overlay body must be bounded by the next composable", end > start)
        src.substring(start, end)
    }

    @Test
    fun `the overlay takes focus when the lock engages`() {
        assertTrue(
            "with a PIN on screen the field is what has to be focused, and with " +
                "none the overlay itself: the previously focused control underneath " +
                "otherwise keeps and drives the covered screen",
            overlay.contains("landFocusOn(if (hasPinField) pinField else lockRoot)")
        )
        assertTrue(
            "and the PIN field has to be the node that request names",
            overlay.contains("focusRequester = pinField")
        )
        assertTrue(
            "a D-pad press that lands on the root must bounce back to the field, " +
                "or the root becomes a dead end that swallows the digits",
            overlay.contains("if (state.isFocused && hasPinField)")
        )
    }

    @Test
    fun `the overlay consumes the input that reaches it`() {
        assertTrue(
            "the root is focusable, so focus has somewhere to land with no PIN",
            overlay.contains(".focusable()")
        )
        assertTrue(
            "and it swallows whatever the focused child did not consume - but only " +
                "while the ROOT holds focus, or the digits would never reach the field",
            overlay.contains(".onKeyEvent { lockRootFocused }")
        )
        assertTrue(
            "touch used to pass straight through the non-consuming Box",
            overlay.contains("indication = null")
        )
        assertTrue(
            "Back must not walk out from under the veil either",
            overlay.contains("BackHandler(enabled = true) { }")
        )
    }

    private object MainActivitySource {
        fun read(): String {
            val file = File(
                findMainSourceRoot(),
                "com/kennyb1201/kbstream/MainActivity.kt"
            )
            assertTrue("source missing: $file", file.isFile)
            return file.readText()
        }

        private fun findMainSourceRoot(): File {
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
    }
}
