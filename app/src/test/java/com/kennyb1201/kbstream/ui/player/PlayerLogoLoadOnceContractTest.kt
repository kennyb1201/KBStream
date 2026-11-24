package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A carried clearlogo is loaded ONCE per surface at setup, and a cancelled load
 * is as visible in the trace as a failed one (spec 2026-11-24).
 *
 * The field paste: a Continue Watching launch (`extra=present`, a good
 * `image.tmdb.org` SVG URL) carried exactly one `LOGO:` line for a 40-second
 * session - the launch one. The splash and the overlay showed no logo, and no
 * `loaded` / `load-failed` line said why. The setup issued the chrome load THREE
 * times back to back (`updateHeaderInfo()` twice, then `refreshTitle()`), and a
 * new `ImageView.load()` cancels the request already on its way to that view.
 * Coil's `onCancel` was not wired, so every cancellation was invisible.
 *
 * So the two facts pinned here are structural, like the rest of the player
 * contracts: one load call per surface, guarded by the URL already issued, and a
 * cancel line from the one wrapper every title slot loads through. Inflating a
 * player activity needs a running Android runtime; what went wrong here was how
 * many loads setup issued, not how a bitmap decodes.
 */
class PlayerLogoLoadOnceContractTest {

    private val native = source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
    private val chrome = source("com/kennyb1201/kbstream/ui/player/PlayerChrome.kt")
    private val logoArt = source("com/kennyb1201/kbstream/ui/components/LogoArt.kt")

    @Test
    fun `setup issues one chrome load and one splash load for a carried URL`() {
        val slot = chrome.substringAfter("internal fun showTitleLogo(")
            .substringBefore("fun refreshTitle(")
        val issue = chrome.substringAfter("private fun issue(logo")
            .substringBefore("fun refreshTitle(")
        assertEquals(
            "the chrome slot has exactly one load call, so no pass can add a second " +
                "into the same ImageView:\n$slot",
            1,
            Regex("logo\\.loadLogo\\(").findAll(issue).count()
        )
        assertTrue(
            "and the load is issued only for a URL that has not landed - the second " +
                "setup pass is free (spec 2026-11-24 round 3, F2: the dedup keys on " +
                "LANDING, not on asking)",
            slot.contains("if (url != loadedLogoUrl) {") &&
                slot.contains("issue(logo, url, onFailure)") &&
                issue.contains("onLoaded = { loadedLogoUrl = url }")
        )

        val header = native.substringAfter("private fun updateHeaderInfo()")
            .substringBefore("\n    private fun ")
        assertEquals(
            "the splash slot has exactly one load call:\n$header",
            1,
            Regex("splashClearLogo\\.loadLogo\\(").findAll(
                native.substringAfter("private fun issueSplashLogo(")
                    .substringBefore("\n    private fun ")
            ).count()
        )
        assertTrue(
            "and the second of the two setup passes does not re-issue it, because " +
                "a second load would cancel the first fetch",
            header.contains("loadSplashLogo(resolvedLogoUrl, LogoRepaint.SETUP)")
        )
        assertTrue(
            "the chrome slot is painted through the chrome, never loaded directly",
            header.contains("chrome?.showTitleLogo(resolvedLogoUrl)") &&
                !DIRECT_CHROME_LOAD.containsMatchIn(header)
        )

        val setup = native.substringAfter("private fun bindViews()")
            .substringBefore("\n    private fun ")
        assertTrue(
            "and setup paints the slots once: updateHeaderInfo(), no refreshTitle() " +
                "after it",
            setup.contains("updateHeaderInfo()") &&
                !setup.contains("chrome?.refreshTitle()")
        )
    }

    @Test
    fun `a lookup that fills the empty slot obeys the same one-load rule`() {
        val apply = native.substringAfter("private fun applyClearLogo(url: String) {")
            .substringBefore("\n    private fun ")
        assertTrue(
            "the chrome slot still loads through the chrome",
            apply.contains("chrome?.showTitleLogo(url)")
        )
        assertTrue(
            "the splash slot loads through the guarded helper, never directly",
            apply.contains("loadSplashLogo(url, LogoRepaint.SETUP)") &&
                !apply.contains("splashClearLogo.load(")
        )
        assertFalse(
            "the chrome's view is never loaded directly over the chrome's back",
            DIRECT_CHROME_LOAD.containsMatchIn(apply)
        )
    }

    @Test
    fun `a repaint of facts already painted issues no load`() {
        val repaint = chrome.substringAfter("fun refreshTitle(repaint: LogoRepaint")
            .substringBefore("fun setSkipIntro(")
        assertTrue(
            "the repaint paints through the one method that owns the slot",
            repaint.contains("showTitleLogo(info.clearLogoUrl, repaint = repaint)")
        )
        assertFalse(
            "and owns no load of its own: a first-frame or source-switch repaint " +
                "must not cancel the setup fetch (spec 2026-11-24, F3):\n$repaint",
            repaint.contains("logo.load(")
        )
    }

    @Test
    fun `a cancelled logo load is traced, not silent`() {
        val wrapper = logoArt.substringAfter("internal fun ImageView.loadLogo(")
            .substringBefore("/** One token for a Coil failure")
        assertTrue(
            "the wrapper Coil's cancellation calls back into must trace it",
            wrapper.contains("onCancel = { _ ->") &&
                wrapper.contains("PlaybackSessionTrace.note(\"LOGO: cancelled \$surface\")")
        )
        // All three outcomes, one wrapper: a cancellation can never again be
        // mistaken for art that simply never arrived.
        listOf(
            "PlaybackSessionTrace.note(\"LOGO: issued \$surface\")",
            "PlaybackSessionTrace.note(\"LOGO: loaded \$surface\")",
            "\"LOGO: load-failed \$surface ",
            "PlaybackSessionTrace.note(\"LOGO: cancelled \$surface\")"
        ).forEach { line ->
            assertTrue("the traced load must emit: $line", wrapper.contains(line))
        }
        // Spec 2026-11-24 round 3, F2: the setup loads stop handing Coil a
        // pre-built request. The player issues during setup, into views that are
        // inflated but not attached, which is the one combination every silent
        // paste shares.
        assertTrue(
            "the load goes through Coil's builder block, with the opt-out inside it",
            wrapper.contains("load(url) {") && wrapper.contains("logoArt()")
        )
        listOf("chrome", "splash").forEach { surface ->
            assertTrue(
                "and $surface's issue site uses it",
                !Regex("load\\(\\s*tracedLogoRequest").containsMatchIn(chrome + native)
            )
        }
    }

    // --- helpers -----------------------------------------------------------

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

    private companion object {
        /**
         * A load of the chrome's own view. Not `splashClearLogo.load(`: that name
         * ends in the same seven letters and is the splash's legal load.
         */
        val DIRECT_CHROME_LOAD = Regex("(?<![A-Za-z])clearLogo\\.load\\s*\\(")
    }
}
