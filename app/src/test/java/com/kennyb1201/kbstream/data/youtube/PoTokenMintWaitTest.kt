package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The resolve's own mint wait (0.9.408).
 *
 * The 14:29 paste on 0.9.405 carried `pot.gvs: state=minted latency=7241ms
 * bind=adopted source=warm` - and no way to read it. The resolver's GVS budget is
 * 6 s while a provider-level mint is allowed 10 s, so a mint stamped at 7241 ms
 * may have finished AFTER the resolve gave up waiting, in which case the token
 * decorated nothing and the playback cost was the 6000 ms cut-off, not the
 * 7241 ms. `pot.mint:` records the wait where it is paid; `pot.gvs:` records the
 * mint where it finished. Only the first is a playback latency, and this pins
 * both the line and the fact that the resolve is the thing recording it.
 */
class PoTokenMintWaitTest {

    @Before
    fun clean() {
        PoTokenTrace.clear()
    }

    private fun line(prefix: String): String? =
        PoTokenTrace.lines().firstOrNull { it.startsWith(prefix) }

    @Test
    fun `a mint the resolve cut off is not reported as a spent token`() {
        PoTokenTrace.recordMintWait(waitedMs = 6001, budgetMs = 6000, gvsInBudget = false)

        assertEquals(
            "the gvs line can say minted at 7241 ms while this one says the " +
                "resolve stopped at 6 s - that pair is the whole diagnosis",
            "pot.mint: resolve waited 6001ms budget=6000ms gvs=none",
            line("pot.mint:")
        )
    }

    @Test
    fun `a mint inside the budget says so`() {
        PoTokenTrace.recordMintWait(waitedMs = 1200, budgetMs = 6000, gvsInBudget = true)

        assertEquals(
            "the in-budget case is the one where the 7.2 s is NOT a lie about " +
                "the wait, so the two cases must not read alike",
            "pot.mint: resolve waited 1200ms budget=6000ms gvs=in-budget",
            line("pot.mint:")
        )
    }

    @Test
    fun `the resolve is what records the wait`() {
        val source = source("com/kennyb1201/kbstream/data/youtube/InnerTubeExtractor.kt")

        val mintTokens = source.indexOf("private suspend fun mintTokens(")
        val remint = source.indexOf("private suspend fun remintGvsWhenTokenless(")

        assertTrue("mintTokens must still exist", mintTokens >= 0)
        assertTrue("remintGvsWhenTokenless must still follow it", remint > mintTokens)

        val body = source.substring(mintTokens, remint)

        assertTrue(
            "the first mint is the one every resolve pays, so the wait has to be " +
                "recorded inside it - recorded at the call site it would miss the " +
                "remint path's own spend",
            body.contains("PoTokenTrace.recordMintWait(")
        )
        assertTrue(
            "and it must be timed around BOTH budgets, or the player token's own " +
                "wait is left out of the number",
            body.indexOf("val gvs = tokenWithinBudget(") < body.indexOf("recordMintWait(") &&
                body.indexOf("provider.playerToken(") < body.indexOf("recordMintWait(")
        )
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val file = File(dir, "${prefix}src/main/java/$path")
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found: $path")
    }
}
