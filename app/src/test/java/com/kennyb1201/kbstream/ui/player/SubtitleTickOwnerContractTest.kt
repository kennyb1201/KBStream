package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The position-driven subtitle tick must be owned by a handler that is still
 * attached to a player.
 *
 * The bug this pins: `cancelPending` ("drop what this handler is holding") also
 * re-synced position mode through `updateFromPosition`, which removes and
 * re-posts `positionTick`. Every one of its callers discards the handler on the
 * next statement - a rebuild and a live zap replace it, `onStop`/`onDestroy`
 * null it - so the re-post armed a tick on an object nobody holds a reference
 * to and nothing could cancel. It went on rendering from the shared cue list
 * into the shared subtitle view for the rest of the session, once more per
 * rebuild, racing the live handler's own tick.
 *
 * The rule is therefore: the clear clears, and the tick is posted from exactly
 * one place (`updateFromPosition`), which is called by the paths that keep the
 * handler. Wiring inside an Android activity, so the contract is read out of
 * the source, the shape the other player contract tests use.
 */
class SubtitleTickOwnerContractTest {

    private fun source(): String = File(sourceRoot(), NATIVE).readText()

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

    /**
     * The body of the INNER-class member at [signature], up to the next member
     * of that class (eight spaces of indent). The outer-member helper the other
     * contract tests use would run past the end of the handler and swallow its
     * siblings.
     */
    private fun innerBody(signature: String): String {
        val src = source()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {8}(fun |private fun |override fun |private val |val )")
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `the discard clear clears and never re-arms`() {
        val body = innerBody("fun cancelPending() {")

        assertTrue(
            "the pending show must be dropped",
            body.contains("handler.removeCallbacks(delayedShow)")
        )
        assertTrue(
            "and the tick it holds",
            body.contains("handler.removeCallbacks(positionTick)")
        )
        assertTrue("and the cues it is holding", body.contains("currentCues = emptyList()"))
        assertFalse(
            "a clear must not POST anything: the handler it belongs to is being " +
                "discarded by every caller, so a tick posted here outlives it",
            body.contains("updateFromPosition") ||
                body.contains("postDelayed") ||
                body.contains("handler.post(")
        )
    }

    @Test
    fun `every clear is followed by discarding that handler`() {
        // The premise of the rule above: a clear is a teardown step, so the
        // caller must drop the handler it just cleared instead of keeping one
        // that has no tick and no cues.
        val src = source()
        val call = "cancelPending()"
        var seen = 0
        var at = src.indexOf(call)
        while (at >= 0) {
            val declaration = src.substring((at - 4).coerceAtLeast(0), at) == "fun "
            if (!declaration) {
                seen++
                val after = src.substring(at, (at + 400).coerceAtMost(src.length))
                assertTrue(
                    "the handler cleared at offset $at must be replaced or nulled " +
                        "right after the clear",
                    after.contains("subtitleCueHandler = null") ||
                        after.contains("subtitleCueHandler = SubtitleCueHandler()")
                )
            }
            at = src.indexOf(call, at + 1)
        }
        assertEquals(
            "the clear has four callers: a rebuild, a live zap, onStop and onDestroy",
            4,
            seen
        )
    }

    @Test
    fun `the tick is posted from exactly one place`() {
        val src = source()
        val posts = Regex("handler\\.post(Delayed)?\\(positionTick").findAll(src).count()
        assertEquals("positionTick must have one posting site", 1, posts)
        assertTrue(
            "and that site is updateFromPosition, which the paths that KEEP the " +
                "handler call",
            innerBody("fun updateFromPosition() {").contains("postDelayed(positionTick")
        )
        assertTrue(
            "the READY handler is one of those paths",
            src.contains("subtitleCueHandler?.updateFromPosition()")
        )
        assertTrue(
            "and an offset nudge is another, so the clear is not what re-syncs",
            innerBody("fun reapplyOffset() {").contains("updateFromPosition()")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
