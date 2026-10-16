package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync's log lines carry a redacted throwable, never a raw one.
 *
 * supabase-kt's exceptions quote the failing request - URL and, on the auth
 * paths, the credential that was sent. `Log.e(tag, message, t)` prints the
 * throwable's own message, so a raw throwable at a sign-in, a session restore
 * or a session persist puts an account URL (and an Xtream-style credential
 * query, where one is involved) into logcat and into any log an app like this
 * is asked to share. [Redaction.throwable] is the app's answer: a copy whose
 * message is scrubbed with the stack trace preserved, so the site keeps its
 * diagnostic value.
 *
 * The call SITES were fixed one at a time (the auth paths in the sync minors
 * batch); this pins them all at once, because the next raw `, e)` added to this
 * file is exactly how the leak comes back.
 */
class SyncSecretRedactionContractTest {

    private val sync by lazy { source(SYNC) }

    @Test
    fun `no log statement in the sync layer prints a raw throwable`() {
        // The shape a leak has: Log.<level>(TAG, "…", <a bare throwable name>).
        // `Redaction.throwable(x)` and a formatted message are both fine; a bare
        // identifier as the throwable argument is not.
        val bareThrowable =
            Regex("""Log\.[weid]\(TAG, "[^"]*", (e|t|it|throwable|error|exception|cause)\)""")
        val offenders =
            bareThrowable.findAll(sync).map { it.value }.toList()
        assertTrue(
            "these log sites print an unredacted throwable: " +
                offenders.joinToString(", ") { it },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the auth and session paths keep the redacted form`() {
        listOf(
            "restoreSession failed (token kept)",
            "restoreSession crashed (token kept)",
            "signIn failed",
            "signUp failed",
            "session persist failed"
        ).forEach { line ->
            assertTrue(
                "\"$line\" must log Redaction.throwable(...), not the raw throwable",
                sync.contains("Log.e(TAG, \"$line\", Redaction.throwable(") ||
                    sync.contains("Log.w(TAG, \"$line\", Redaction.throwable(") ||
                    sync.contains("\"$line\", Redaction.throwable(")
            )
        }
    }

    @Test
    fun `the crash reporter still gets the real throwable`() {
        // Redaction is for the LOG. CrashReporter is the app's own reporting
        // path and redacts at the Sentry boundary, so it must keep the original
        // - a redacted copy would report a class name instead of the failure.
        val restoreBlock = sync.substringAfter("catch (t: Throwable) {")
            .substringBefore("// ── Auth ──")
        assertTrue(
            "the restore path reports the original throwable",
            restoreBlock.contains("CrashReporter.recordNonFatal(\n                    t,")
        )
    }

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
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

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
    }
}
