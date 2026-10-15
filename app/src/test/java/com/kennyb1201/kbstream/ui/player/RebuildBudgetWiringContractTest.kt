package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring half of the rebuild budget (the rule itself is covered by
 * [RebuildBudgetTest]).
 *
 * Three call sites, each a line that compiles perfectly and silently restores a
 * reported symptom when it goes away:
 *
 *  - the ladder is where the budget is CONSULTED (a rebuild the cap refuses
 *    hands off to the backup engine instead of asking a wedged decoder pool
 *    again);
 *  - a source change resets it, because the cause string is identical on every
 *    source — without this, two rebuilds on the source the viewer left left the
 *    next one with none;
 *  - the error card's manual RETRY resets it AND records the rebuild it is about
 *    to perform, so the press really starts a fresh run (it used to reset the
 *    ladder but not the cap, and spent one rebuild before dropping back onto
 *    the same card) without silently bypassing the cap.
 */
class RebuildBudgetWiringContractTest {

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

    private val native by lazy {
        source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
    }

    @Test
    fun `the failure ladder is still where the cap is consulted`() {
        assertTrue(
            "the ladder must ask the budget before rebuilding",
            native.contains("!rebuildBudget.allows(causeKey)")
        )
        assertTrue(
            "and the rebuild it queues must be counted",
            native.contains("?.let { rebuildBudget.record(it) }")
        )
    }

    @Test
    fun `a source change starts a fresh budget`() {
        val switchBody = native.substringAfter("fun switchToSource(")
            .substringBefore("fun adoptInitialSource(")
        assertTrue(
            "switchToSource must reset the budget, or the next source inherits a spent cap",
            switchBody.contains("rebuildBudget.reset()")
        )
        val adoptBody = native.substringAfter("private fun adoptInitialSource(")
            .substringBefore("// --- PlayerChromeHost")
        assertTrue(
            "adoptInitialSource must reset it too: it is where a different source is adopted",
            adoptBody.contains("rebuildBudget.reset()")
        )
    }

    @Test
    fun `the error card's RETRY resets the budget and pays for its own rebuild`() {
        val retry = native.substringAfter("btnRetry.setOnClickListener {")
            .substringBefore("// Change source")
        assertTrue(
            "RETRY must reset the budget with the rest of the ladder state",
            retry.contains("rebuildBudget.reset()")
        )
        assertTrue(
            "and count the rebuild it performs, so the cap is not bypassed by pressing again",
            retry.contains("rebuildBudget.record(")
        )
    }
}
