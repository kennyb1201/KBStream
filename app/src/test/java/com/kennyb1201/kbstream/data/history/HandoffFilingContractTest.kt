package com.kennyb1201.kbstream.data.history

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A departed session's row must land in ITS OWN profile, and the player trace
 * must say the truth about whether it did.
 *
 * Observed on device 0.5.12: a handoff filed `filed s=3 e=17 row=... completed=true`
 * in the trace while no completed row ever appeared locally. The write was
 * refused because the active profile no longer matched the session's, and the
 * trace printed BEFORE the asynchronous Room write — so "filed" was a lie.
 *
 * The fix has two halves, both pinned here at the source because the behaviour
 * lives in Android activity code no unit test can drive without a TV:
 *
 *  - [PlaybackHistoryWriter.write] redirects the row to the session profile's
 *    one-shot database instead of refusing, and only refuses — loudly, with a
 *    `written=false reason=unknown-profile` note — when that profile is gone;
 *  - both engines report the `filed` verdict from the write's own callback, so
 *    it can no longer precede (and misreport) the write.
 */
class HandoffFilingContractTest {

    private fun readSource(path: String): String {
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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    // ── the writer redirects instead of refusing ───────────────────────

    @Test
    fun `a departed session writes to its own profile's file`() {
        val src = readSource(WRITER)
        assertTrue(
            "the redirect must open the SESSION profile's one-shot database",
            src.contains("WatchHistoryDatabase.openForProfile(context, target)")
        )
        assertTrue(
            "the session profile must be resolved against the live profile list",
            src.contains("ProfileManager.profileIds(context).contains(pid)")
        )
        assertTrue(
            "the write must report the outcome back to the caller",
            src.contains("internal data class WriteResult(val ok: Boolean, val profileId: String?)")
        )
        assertTrue(
            "the one-shot handle must be closed",
            src.contains("runCatching { db.close() }")
        )
    }

    @Test
    fun `an unknown session profile is refused loudly, never silently`() {
        val src = readSource(WRITER)
        assertTrue("the refusal must be an error, not a warning", src.contains("Log.e("))
        assertTrue(
            "the refusal must leave a trace note",
            src.contains("written=false reason=unknown-profile")
        )
        // The old quiet refusal is gone.
        assertFalse(src.contains("refusing to file"))
    }

    @Test
    fun `the one-shot open resolves the profile file by name`() {
        val src = readSource(DB)
        assertTrue(src.contains("internal fun openForProfile("))
        assertTrue(src.contains("dbName(profileId, \"kbstream_watch_history\")"))
    }

    // ── the trace tells the truth ──────────────────────────────────────

    @Test
    fun `both engines report the filed verdict from the write's callback`() {
        listOf(NATIVE, MPV).forEach { path ->
            val body = functionBody(path, "private fun fileEpisodeForHandoff() {")
            assertTrue(
                "$path must wait for the write's outcome",
                body.contains("{ ok, profile ->")
            )
            assertTrue(
                "$path must report written= and the destination profile",
                body.contains("written=\$ok profile=\${profile ?: \"-\"}")
            )
            // The verdict must come AFTER the write, not before it.
            val call = body.indexOf("saveProgress(reason = \"handoff\"")
            val note = body.indexOf("filed s=")
            assertTrue("$path must still emit a filed note", note > 0)
            assertTrue("$path prints the verdict before the write", note > call)
            // The old pre-write verdict string is gone.
            assertFalse(
                "$path still prints a verdict before the write",
                body.contains("row=\$historyId completed=\$completed\"")
            )
        }
    }

    @Test
    fun `saveProgress exposes an onWritten hook and invokes it with the outcome`() {
        listOf(NATIVE, MPV).forEach { path ->
            val src = readSource(path)
            assertTrue(
                "$path saveProgress must accept an onWritten callback",
                src.contains("onWritten: ((Boolean, String?) -> Unit)? = null")
            )
            assertTrue(
                "$path must invoke onWritten with the write result",
                src.contains("onWritten?.invoke(result.ok, result.profileId)")
            )
        }
    }

    private companion object {
        private const val WRITER = "com/kennyb1201/kbstream/data/history/PlaybackHistoryWriter.kt"
        private const val DB = "com/kennyb1201/kbstream/data/history/WatchHistoryDatabase.kt"
        private const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
