package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-shot query handoff behind a spoken / deep-link search.
 *
 * [SearchSeed] is a handoff, not state: the last writer wins, the first reader
 * consumes it. The bug this pins is the OTHER ending — a spoken "play X" whose
 * lookup resolved to the title's own screen, so nothing was ever going to
 * Search, left the query pending and replayed it on the next visit to Search.
 */
class SearchSeedLifecycleTest {

    @Test
    fun `a set query is handed over exactly once`() {
        SearchSeed.set("bluey")
        assertEquals("bluey", SearchSeed.consume())
        assertNull(SearchSeed.consume())
    }

    @Test
    fun `clear drops a query that will never reach search`() {
        SearchSeed.set("bluey")
        SearchSeed.clear()
        assertNull(SearchSeed.consume())
    }

    @Test
    fun `blank queries are never stashed`() {
        SearchSeed.set("   ")
        assertNull(SearchSeed.consume())
        SearchSeed.set(null)
        assertNull(SearchSeed.consume())
    }

    @Test
    fun `a resolved spoken play drops the seed before it opens the title`() {
        val body = functionBody("private fun openSpokenTitle(")
        val clear = body.indexOf("SearchSeed.clear()")
        val detail = body.indexOf("startActivity(detailIntent(target.type, target.id))")
        assertTrue(
            "the resolved play path must clear the pending seed it stashed for the fallback",
            clear >= 0
        )
        assertTrue("and clear it before the title's own screen opens", clear in 0 until detail)
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), VOICE)
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
        const val VOICE = "com/kennyb1201/kbstream/ui/search/VoiceSearchActivity.kt"
    }
}
