package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HD-P2-7: a cancelled catch-up load must not clear its replacement's loading
 * flag. Cancellation is cooperative, so the old job's `finally` runs after the
 * new job set `_catchupLoading = true`; without a generation guard the dialog
 * showed "No recent programs available" until the real results landed.
 */
class CatchupLoadingRaceContractTest {

    @Test
    fun `only the current catch-up load clears the loading flag`() {
        val src = readSource()
        assertTrue(
            "the loader must stamp a generation",
            src.contains("private var catchupGeneration = 0")
        )
        val body = functionBody("fun loadCatchupPrograms(")
        assertTrue(
            "each load must take its own generation",
            body.contains("val generation = ++catchupGeneration")
        )
        assertTrue(
            "and only that generation may clear the flag",
            body.contains("if (generation == catchupGeneration) _catchupLoading.value = false")
        )
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), IPTV_VM)
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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(signature: String): String {
        val src = readSource()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val IPTV_VM = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
    }
}
