package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring of BingeCat's persisted cache (spec 2026-11-21, rules 1-3).
 *
 * The store and its codec are unit-tested next door ([BingeCatCacheCodecTest]);
 * what cannot be tested without Android is the SEQUENCE in the repository - what
 * is read before a network call, what a failure serves, and what a write
 * invalidates. That sequence is the whole spec: the 0.9.437 paste showed
 * `bingecat=422` calls and empty pinned rails, and every line pinned here is one
 * of the ways those two numbers were produced.
 */
class BingeCatCacheWiringContractTest {

    private val repository: String by lazy {
        normalized(stripComments(read("data/bingecat/BingeCatRepository.kt")))
    }

    private val rails: String by lazy {
        normalized(stripComments(read("data/library/LibraryHomeRails.kt")))
    }

    private fun snapshotFn(): String =
        repository.substringAfter("suspend fun snapshot(")
            .substringBefore("suspend fun listRows(")

    private fun listRowsFn(): String =
        repository.substringAfter("suspend fun listRows(")
            .substringBefore("/** The cached snapshot")

    @Test
    fun `a cold start reads the disk before it considers the network`() {
        assertTrue(
            "the memory copy is hydrated from disk BEFORE the TTL check, so a " +
                "launch inside the TTL makes no BingeCat call at all - the old " +
                "code looked only at a field that a cold start always found empty",
            snapshotFn().contains("val fresh = cached ?: restoreFromDisk(mapped, activeProfile)")
        )
        assertTrue(
            "and the wait inside the lock re-hydrates too: the first caller can " +
                "have failed while this one queued, and the disk still holds the " +
                "last good read",
            snapshotFn().contains("val current = cached ?: restoreFromDisk(mapped, activeProfile)")
        )
        assertTrue(
            "the restore refuses another mapping's or another profile's rows, the " +
                "same two guards every use of the memory copy applies",
            repository.contains("if (restored.mappedId != mapped || restored.profileId != activeProfile)")
        )
    }

    @Test
    fun `the last good read outlives the process`() {
        assertTrue(
            "rule 1: an answered refresh is written to disk, keyed by the token it " +
                "was read with (a hash - the file never holds a credential)",
            snapshotFn().contains("cacheToken()?.let { diskCache.writeSnapshot(it, refreshed) }")
        )
        assertTrue(
            "and the failure clock starts over with it",
            snapshotFn().contains("diskCache.writeFailureAtMs(0L)")
        )
    }

    @Test
    fun `a failed refresh both serves and backs off`() {
        assertTrue(
            "rule 2: a failed refresh keeps the previous snapshot for THIS mapping - " +
                "the Library must never empty because a read failed",
            snapshotFn().contains(
                "current?.takeIf { it.mappedId == mapped && it.profileId == activeProfile }"
            )
        )
        assertTrue(
            "rule 3: and stamps the failure, so the next caller does not spend the " +
                "same four legs to learn the same 429",
            snapshotFn().contains("diskCache.writeFailureAtMs(nowMs)")
        )
        assertTrue(
            "the backoff is a gate on BOTH sides of the lock, beside the park",
            snapshotFn().split("bingeCatReadParkMs() > 0L || failedRecently(nowMs)").size == 3
        )
        assertTrue(
            "and it is minutes, not the park's seconds",
            repository.contains("const val FAILURE_BACKOFF_MS = 5L * 60L * 1_000L")
        )
    }

    @Test
    fun `a pinned list is walked once, not once per Home load`() {
        val rows = listRowsFn()
        assertTrue(
            "rule 1: the stored walk answers inside the TTL - a pinned rail used " +
                "to cost a whole paged read on every launch",
            rows.contains("if (stored != null && nowMs - stored.savedAtMs < CACHE_TTL_MS)")
        )
        assertTrue(
            "rule 2: a parked or recently-failed leg serves the stored rows instead " +
                "of an empty rail",
            rows.contains("if (bingeCatReadParkMs() > 0L || failedRecently(nowMs))") &&
                rows.contains("return stored?.rows")
        )
        assertTrue(
            "a page that did not answer abandons the walk and serves the stored one",
            rows.contains("diskCache.writeFailureAtMs(nowMs)") &&
                rows.contains("return stored?.rows")
        )
        assertTrue(
            "rule 1 again: only a COMPLETE walk replaces the cache - the same " +
                "all-or-nothing rule the callers already draw by",
            rows.contains("token?.let { diskCache.writeRows(it, ref, rows, nowMs) }")
        )
        assertTrue(
            "and a walk stopped by the page ceiling is not a complete one",
            rows.contains("if (truncated) {") &&
                rows.indexOf("if (truncated) {") <
                rows.indexOf("diskCache.writeRows(it, ref, rows, nowMs)")
        )
    }

    @Test
    fun `a write drops the snapshot but keeps the list rows`() {
        assertTrue(
            "a finished episode must not re-walk every pinned rail: the play changed " +
                "no list's contents, only the history the badge is read from",
            repository.contains("forgetSnapshot()") &&
                repository.contains("diskCache.clearSnapshot()")
        )
        assertTrue(
            "while a session or mapping change drops everything - those rows belong " +
                "to another account or another BingeCat profile",
            repository.contains("fun forgetCache() { cached = null diskCache.clear() }")
        )
        assertTrue(
            "and the history-write path takes that arm, not the everything one",
            repository.contains("forgetSnapshot() BingeCatTrace.recordWriteSuccess")
        )
    }

    @Test
    fun `the Home rail paints the stored watchlist before asking`() {
        assertTrue(
            "rule 1: `cachedSnapshot` now means \"the last launch's read\" as well as " +
                "this one's, and the rail prefers it",
            repository.contains("fun cachedSnapshot(): BingeCatSnapshot? {") &&
                repository.contains("return restoreFromDisk(mapped, active)")
        )
        assertTrue(
            "the rail falls back to the blocking read only when there is nothing stored",
            rails.contains("(repository.cachedSnapshot() ?: repository.snapshot())?.watchlist")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found: $relative")
    }

    private fun stripComments(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker < 0) line else line.substring(0, marker)
        }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
