package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone shell's safe area.
 *
 * `MainActivity` makes the window edge-to-edge (`setDecorFitsSystemWindows(
 * window, false)`) because the TV shell wants the whole panel and a television
 * has no status or navigation bar for content to slide under. A phone does: the
 * phone screens were drawn from y = 0, so their own 14-18dp top padding put the
 * first heading *under the clock* and the last row under the gesture bar. That
 * is invisible to a compiler and to a contract test that only checks screen
 * sources, which is why the rule is pinned here, at the shell.
 *
 * The fix is deliberately one place - the root Box - and deliberately gated on
 * the form factor: the TV layout must stay byte-for-byte what it was, and its
 * insets are already handled by overscan on the boxes themselves.
 */
class MobileSafeAreaContractTest {

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun activity(): String =
        source("com/kennyb1201/kbstream/MainActivity.kt")

    @Test
    fun `the window is edge-to-edge, so the shell has to reserve the bars`() {
        val main = activity()
        assertTrue(
            "the window deliberately does not fit system windows",
            main.contains("WindowCompat.setDecorFitsSystemWindows( window, false )") ||
                main.contains("setDecorFitsSystemWindows(\n            window,\n            false\n        )")
        )
        assertTrue(
            "the phone branch must pad the system bars and the notch",
            main.contains(
                "windowInsetsPadding( WindowInsets.systemBars.union(WindowInsets.displayCutout) )"
            ) ||
                main.contains("windowInsetsPadding(") &&
                main.contains("systemBars.union(WindowInsets.displayCutout)")
        )
        assertTrue(
            "the padding must be applied to the shell's root Box, once, not per screen",
            main.contains(".then(mobileSafeArea)")
        )
    }

    @Test
    fun `the safe area is phone-only and leaves the tv layout untouched`() {
        val main = activity()
        val gate = main.indexOf("val mobileSafeArea = if (")
        assertTrue("the safe area must be gated on the form factor", gate >= 0)
        val block = main.substring(gate, main.indexOf(".then(mobileSafeArea)", gate))
        assertTrue(
            "a TV must get the untouched Modifier - overscan, not insets",
            block.contains("com.kennyb1201.kbstream.FormFactor.isTv") &&
                block.contains("Modifier } else {")
        )
        assertTrue(
            "and the phone branch is the one that carries the insets",
            block.contains("Modifier.windowInsetsPadding(")
        )
    }

    @Test
    fun `ime handling still follows the safe area`() {
        val main = activity()
        val safe = main.indexOf(".then(mobileSafeArea)")
        val ime = main.indexOf(".imePadding()", safe)
        assertTrue("imePadding must still be applied app-wide", ime > safe)
        assertEquals(
            "the safe area is applied exactly once, before the IME padding, so the " +
                "keyboard inset is measured against the space left by the bars",
            1,
            Regex("windowInsetsPadding\\(").findAll(main).count()
        )
    }
}
