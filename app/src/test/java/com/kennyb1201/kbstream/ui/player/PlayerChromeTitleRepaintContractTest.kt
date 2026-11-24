package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared chrome's title block is repainted by BOTH engines, and a logo load
 * says what it did (spec 2026-11-23).
 *
 * The field report: a Continue Watching launch whose paste carried exactly one
 * `LOGO:` line - `extra=present` - and both the splash and the overlay showed
 * the plain title, forever. The lookup was exonerated by that line; the draw
 * path was not. `updateHeaderInfo()` painted the two logo slots during setup
 * and nothing ever repainted them, while MPV repaints its chrome at three
 * moments (`setupControls`, the first frame, and each source start). Exo had no
 * `refreshTitle()` call anywhere in the tree.
 *
 * Pinned at the source, like the rest of the player contracts: inflating a
 * player activity needs a running Android runtime, and what regressed here was
 * a missing call, not a wrong rule.
 */
class PlayerChromeTitleRepaintContractTest {

    private val native = source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
    private val mpv = source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")
    private val chrome = source("com/kennyb1201/kbstream/ui/player/PlayerChrome.kt")
    private val logoArt = source("com/kennyb1201/kbstream/ui/components/LogoArt.kt")

    @Test
    fun `the Exo player repaints the chrome title at MPV's three moments`() {
        // MPV: setupControls(), the first-frame handler, and the file-loaded
        // handler. The Exo player must have the same three.
        assertTrue(
            "MPV is the reference and must keep its three repaints",
            count(mpv, "updateNowPlayingText()") >= 4
        )
        assertTrue(
            "the chrome-setup paint goes through the shared chrome",
            native.contains("chrome = PlayerChrome(findViewById(R.id.chrome_layer), this)")
        )
        assertEquals(
            "the first frame and a source switch repaint it - setup must not, " +
                "because updateHeaderInfo() has just painted the same facts and a " +
                "second load would cancel the one it started (spec 2026-11-24, F1):\n" +
                callSites(native, "chrome?.refreshTitle()"),
            2,
            count(native, "chrome?.refreshTitle()")
        )
        assertTrue(
            "and the setup paint is updateHeaderInfo()'s alone",
            native.substringAfter("private fun bindViews()")
                .substringBefore("\n    private fun ")
                .contains("updateHeaderInfo()") &&
                !native.substringAfter("private fun bindViews()")
                    .substringBefore("\n    private fun ")
                    .contains("chrome?.refreshTitle()")
        )
        assertTrue(
            "and the repaint sits on the splash-hide path, where a logo load " +
                "that landed under the splash becomes visible",
            native.substringAfter("private fun markFirstFrameRendered()")
                .substringBefore("private fun ")
                .contains("chrome?.refreshTitle()")
        )
        assertTrue(
            "and on the source-switch path, because a new source is new title facts",
            native.substringAfter("fun switchToSource(")
                .substringBefore("private fun lightSwitchLiveChannel()")
                .contains("chrome?.refreshTitle()")
        )
    }

    @Test
    fun `the shared chrome repaints from the live field, not a captured URL`() {
        assertTrue(
            "the paint reads the host's current facts",
            chrome.contains("val info = host.chromeTitleInfo()")
        )
        assertTrue(
            "and the live clear-logo URL is that fact on the Exo host",
            native.contains("override fun chromeTitleInfo(): ChromeTitleInfo") &&
                native.contains("clearLogoUrl = clearLogoUrl,")
        )
    }

    @Test
    fun `a logo load into a title slot says whether it loaded`() {
        assertTrue(
            "one helper owns the trace, so no surface can load untraced",
            logoArt.contains("internal fun tracedLogoRequest(") &&
                logoArt.contains("PlaybackSessionTrace.note(\"LOGO: loaded \$surface\")") &&
                logoArt.contains("\"LOGO: load-failed \$surface ") &&
                logoArt.contains("onFailure?.invoke()")
        )
        assertTrue(
            "the reason is Coil's own error class, never its message or the URL",
            logoArt.contains("throwable.javaClass.simpleName")
        )
        listOf("updateHeaderInfo", "applyClearLogo").forEach { painter ->
            val body = native.substringAfter("private fun $painter(")
                .substringBefore("\n    private fun ")
            assertTrue(
                "$painter paints the chrome slot through the shared chrome and the " +
                    "splash slot through the traced request",
                body.contains("chrome?.showTitleLogo(") &&
                    body.contains("tracedLogoRequest(") &&
                    body.contains("surface = \"splash\"") &&
                    !Regex("(?<![A-Za-z])clearLogo\\.load\\s*\\(").containsMatchIn(body)
            )
        }
        val slot = chrome.substringAfter("internal fun showTitleLogo(")
            .substringBefore("fun refreshTitle()")
        assertTrue(
            "and the repaint's own load is traced too - it is the one that runs " +
                "after setup, through the one method that owns the chrome slot",
            slot.contains("tracedLogoRequest(") &&
                slot.contains("surface = \"chrome\"")
        )
        assertTrue(
            "refreshTitle paints through it rather than loading the slot itself",
            chrome.substringAfter("fun refreshTitle()")
                .substringBefore("fun setSkipIntro(")
                .contains("showTitleLogo(info.clearLogoUrl)") &&
                !chrome.substringAfter("fun refreshTitle()")
                    .substringBefore("fun setSkipIntro(")
                    .contains("logo.load(")
        )
    }

    @Test
    fun `a failed chrome load restores the title text instead of leaving it blank`() {
        assertTrue(
            "the chrome hands its own fallback to the traced load",
            chrome.contains("clearLogo?.visibility = View.GONE") &&
                chrome.contains("itemName?.text = info.itemName") &&
                chrome.contains("itemName?.visibility = View.VISIBLE")
        )
        assertTrue(
            "and the Exo host has the same fallback, for its setup paints",
            native.contains("private fun restoreChromeTitleText()") &&
                native.contains("itemNameView.visibility = View.VISIBLE") &&
                native.contains(") { restoreChromeTitleText() }")
        )
        assertTrue(
            "the splash keeps its existing fallback rather than a second one",
            native.contains("private fun showSplashTitleFallback()")
        )
    }

    // --- helpers -----------------------------------------------------------

    private fun count(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    private fun callSites(text: String, needle: String): String {
        val lines = text.lines()
        return lines.withIndex()
            .filter { it.value.contains(needle) }
            .joinToString("\n") { "line ${it.index + 1}: ${it.value.trim()}" }
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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
}
