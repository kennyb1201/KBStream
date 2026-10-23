package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every in-place rewrite in the Dolby Vision strip is bounded by the space it is
 * written into (sweep 7, §4.3).
 *
 * The strip compacts a sample in place, so a rewrite that GREW would copy over
 * bytes that have not been read yet - corrupting the sample silently, or, for a
 * strip that answers with more bytes than it was given, running past the sample
 * entirely. No in-tree input is known to grow today, which is exactly why this is
 * a guard rather than a fix: the tree can only prove the rule is written down.
 * The opposing half is already covered by behaviour tests (the SEI pass only ever
 * moves bytes DOWN, and the Annex-B VPS/SPS rewrite keeps the original NAL when
 * its replacement is longer).
 */
class DolbyVisionRewriteBoundsContractTest {

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java/" + FILE)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `a native strip that answered with more bytes than it was given is refused`() {
        val src = source()
        assertTrue(
            "the guard must exist, and it must not be a silent static-constant read",
            src.contains("private fun nativeStripTooLong(written: Int, length: Int): Boolean")
        )
        // BOTH native call sites: Annex-B and the length-prefixed framing. The
        // second one is what made this a finding - a strip that grew there would
        // have written past the caller's sample.
        assertTrue(
            "both framings must consult it before copying the native answer back",
            src.split("if (nativeStripTooLong(written, length)) return -1").size - 1 == 2
        )
        assertTrue(
            "and a grown answer is reported, not swallowed",
            src.contains("\"DV strip grew the sample (\${length}B -> \${written}B): keeping the \"")
        )
    }

    @Test
    fun `a parameter-set rewrite may never be longer than the NAL it replaces`() {
        val src = source()
        // Three rewrite sites: Annex-B VPS, Annex-B SPS, and the length-prefixed
        // pair. The length-prefixed ones were the gap.
        assertTrue(
            "every rewritten set is size-checked before it is written in place",
            src.split("res.rbsp.size <= origRbspSize").size - 1 == 4
        )
        assertTrue(
            "and an over-long one keeps the original NAL and says why",
            src.split("reason = \"rewriteTooLong\"").size - 1 == 4
        )
    }

    private companion object {
        const val FILE = "com/kennyb1201/kbstream/ui/player/DolbyVisionCompat.kt"
    }
}
