package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring half of the watched-overrides guard (the rules themselves are
 * covered by [WatchedOverridesRulesTest]).
 *
 * All three pieces are single lines that compile perfectly and do nothing if
 * they are dropped, and each of them was missing when the wipe was reported:
 * the bulk push's gate, the applier's call to the guard, and the local edit
 * stamp the staleness half compares against. A unit test can reach the rules
 * but not these call sites, so they are read out of the source, the same shape
 * as the other contract tests in this tree.
 */
class WatchedOverridesGateWiringContractTest {

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

    @Test
    fun `the bulk push gates the watched-overrides blob`() {
        val sync = source("com/kennyb1201/kbstream/data/sync/SupabaseSync.kt")
        val gate = sync.substringAfter("if (key == PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES)")
        assertTrue(
            "pushPrefsBlobs must consult WatchedOverridesRules.shouldPublish",
            gate.substringBefore("enqueuePrefs").contains("WatchedOverridesRules.shouldPublish")
        )
    }

    @Test
    fun `the applier goes through the guard instead of a bare putStringSet`() {
        val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
        val applier = payload.substringAfter("private fun applyWatchedOverrides")
        assertTrue(
            "applyWatchedOverrides must call WatchedOverridesRules.shouldApply",
            applier.contains("WatchedOverridesRules.shouldApply")
        )
        assertTrue(
            "an adoption has to stamp the blob it accepted",
            applier.contains("WatchedOverridesRules.SYNCED_AT_KEY")
        )
    }

    @Test
    fun `a hand-mark claims its own edit time`() {
        val repository = source("com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt")
            .substringAfter("private suspend fun applyOverrideKeys")
            .substringBefore("cacheMutex")
        assertTrue(
            "applyOverrideKeys must stamp EDITED_AT_KEY, or the staleness half has nothing to compare",
            repository.contains("WatchedOverridesRules.EDITED_AT_KEY")
        )
    }

    @Test
    fun `the payload marks an intentional empty set`() {
        val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
        val builder = payload.substringAfter("fun buildWatchedOverrides")
            .substringBefore("Local library")
        assertTrue(
            "buildWatchedOverrides must say whether the empty set is meant",
            builder.contains("WatchedOverridesRules.CLEARED_FIELD")
        )
    }
}
