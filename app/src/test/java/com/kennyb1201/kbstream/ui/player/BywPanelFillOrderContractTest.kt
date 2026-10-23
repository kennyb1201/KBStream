package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credits recommendation panel is raised FILLED, never empty.
 *
 * The field report: "sometimes byw pops up but is blank for like 3 to 7 seconds
 * and then fills in". The panel was shown on the spot and the TMDB round-trip
 * that builds its row ran afterwards, so the box sat on screen with nothing in
 * it for as long as the fetch took - and on an empty answer it appeared and was
 * taken straight back down.
 *
 * The fetch now comes first, and the raise, the credits arrangement and the row
 * build happen in one turn. Nothing is raised when the fetch answers nothing:
 * the credits simply keep playing full screen.
 *
 * The order is what these assertions read; there is no device in CI and neither
 * Activity can be driven from a JVM test.
 */
class BywPanelFillOrderContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
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

    /**
     * The panel function up to the next member: a line indented by exactly four
     * spaces, which the body's deeper indents do not match.
     */
    private fun panel(path: String): String {
        val src = source(path)
        val start = src.indexOf("private fun showBecauseYouWatchedPanel() {")
        assertTrue("$path must keep the BYW panel", start >= 0)
        val rest = src.substring(start)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `every panel fetches its picks before it is shown`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val body = panel(path)
            val fetch = body.indexOf("buildBecauseYouWatchedPicks(")
            val show = body.indexOf("show(itemName)")
            val build = body.indexOf("build(picks)")
            assertTrue("$path: the panel must still fetch its picks", fetch >= 0)
            assertTrue("$path: and still raise the panel", show >= 0)
            assertTrue(
                "$path: show() must come AFTER the fetch, or the panel is up with " +
                    "an empty row for the whole length of the TMDB round-trip",
                fetch < show
            )
            assertTrue(
                "$path: and the row is built in the same turn as the raise, so no " +
                    "frame can paint the panel empty",
                show < build
            )
        }
    }

    @Test
    fun `an empty pick list raises nothing`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val body = panel(path)
            assertTrue(
                "$path: the empty answer must return before the raise",
                body.contains("picks.isEmpty() || bywDismissed")
            )
            assertFalse(
                "$path: the old empty-box path - show it, then hide it again - is " +
                    "gone with the raise it belonged to",
                body.contains("isVisible")
            )
        }
    }

    private companion object {
        const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL =
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
