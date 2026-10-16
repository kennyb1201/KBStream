package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring half of the watched-overrides per-key merge (the merge itself is
 * covered by [WatchedOverridesRulesTest]).
 *
 * Three separate mistakes produced the same report - a hand-marked title
 * vanishing, or an unmarked one coming back:
 *
 *  - the blob carried one whole-set stamp, so a mark made on one TV was erased
 *    by the other TV's later mark of a different title: every key now travels
 *    with its own edit time and the two are merged, not replaced;
 *  - the unmark paths wrote the prefs set directly, outside the override mutex
 *    and without any stamp, so a sync apply racing them won and a pull put the
 *    override straight back: they go through the one mutex'd, stamping writer
 *    now;
 *  - an unmark had no way to travel at all, so the other device's older mark
 *    won by default: the unmark is published as an edit of its own.
 *
 * Every one of those is a line that compiles perfectly and does nothing when it
 * is dropped, so it is pinned here, in the shape the other contract tests use.
 */
class WatchedOverridesPerKeyWiringContractTest {

    @Test
    fun `the applier merges per key instead of replacing the set`() {
        val applier = functionBody(SYNC_PAYLOAD, "private fun applyWatchedOverrides(")
        assertTrue(
            "the apply must go through the per-key merge",
            applier.contains("WatchedOverridesRules.merge(")
        )
        assertTrue(
            "and it must write both per-key maps it read",
            applier.contains("WatchedOverridesRules.KEY_STAMPS_KEY") &&
                applier.contains("WatchedOverridesRules.REMOVED_KEY")
        )
        assertTrue(
            "the coarse staleness guard stays in front of it",
            applier.contains("WatchedOverridesRules.shouldApply")
        )
        assertTrue(
            "a deliberate clear is still a wipe, not a merge",
            applier.contains("set.isEmpty() && deliberateClear")
        )
    }

    @Test
    fun `the builder publishes the per-key stamps and the unmarks`() {
        val builder = functionBody(SYNC_PAYLOAD, "fun buildWatchedOverrides(")
        assertTrue(
            "a blob with no per-key stamps carries no evidence of a local edit",
            builder.contains("WatchedOverridesRules.TIMESTAMPS_FIELD")
        )
        assertTrue(
            "and the unmarks are the only way the other device hears about them",
            builder.contains("WatchedOverridesRules.REMOVED_FIELD")
        )
    }

    @Test
    fun `the bulk push gate does not strand an unmark-only device`() {
        val sync = source(SYNC)
        val gate = sync.substringAfter("if (key == PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES)")
        assertTrue(
            "the unmarks have to reach shouldPublish",
            gate.substringBefore("return@forEach").contains("shouldPublish(overrides.toSet(), unmarked)")
        )
    }

    @Test
    fun `every unmark goes through the mutex'd, stamping writer`() {
        listOf("suspend fun markPartiallyWatchedLocal(", "suspend fun clearWatchedOverride(")
            .forEach { signature ->
                val body = functionBody(REPOSITORY, signature)
                assertTrue(
                    "$signature must unmark through applyOverrideKeys",
                    body.contains("applyOverrideKeys(")
                )
                assertFalse(
                    "$signature writes the override set directly, outside the mutex and " +
                        "without a stamp - that is the write a sync apply races",
                    body.contains("putStringSet(\n                KEY_WATCHED_OVERRIDES") ||
                        body.contains("putStringSet(\n                    KEY_WATCHED_OVERRIDES")
                )
            }
    }

    @Test
    fun `the one writer stamps each key it touched`() {
        val writer = functionBody(REPOSITORY, "private suspend fun applyOverrideKeys(")
        assertTrue(
            "a mark has to claim its own time per key",
            writer.contains("keyStamps[key] = now")
        )
        assertTrue(
            "and an unmark has to claim one too, or a sibling's older mark wins",
            writer.contains("removedStamps[key] = now")
        )
        assertTrue(
            "both maps must be persisted, or the merge reads no opinion at all",
            writer.contains("KEY_OVERRIDE_STAMPS") && writer.contains("KEY_OVERRIDE_REMOVED")
        )
        assertTrue(
            "the whole-set stamp the staleness guard compares against stays",
            writer.contains("WatchedOverridesRules.EDITED_AT_KEY")
        )
        assertTrue(
            "and the mutex is what makes the read-modify-write atomic",
            writer.contains("overridesMutex.withLock")
        )
    }

    /**
     * The body of the function starting at [signature], up to its closing brace
     * (a line indented by exactly four spaces).
     */
    private fun functionBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun source(path: String): String {
        val file = File(sourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val SYNC_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val REPOSITORY =
            "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
    }
}
