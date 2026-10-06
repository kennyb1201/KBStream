package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HD-P2-9: an edit to the query must retire a pending submit, so the debounced
 * hits that arrive next cannot steal focus to the first row after the viewer
 * moved on.
 */
class SubmitFocusRetireContractTest {

    @Test
    fun `editing the query clears a pending submit focus`() {
        val src = readSource()
        val effect = src.indexOf("LaunchedEffect(query) {")
        assertTrue("a query-change effect must retire the pending submit", effect >= 0)
        val body = src.substring(effect, (effect + 400).coerceAtMost(src.length))
        assertTrue(
            "and it must clear the flag",
            body.contains("awaitingSubmitFocus = false")
        )
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), GUIDE)
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

    private companion object {
        const val GUIDE = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
    }
}
