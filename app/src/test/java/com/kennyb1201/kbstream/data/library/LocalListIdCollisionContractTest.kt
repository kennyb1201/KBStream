package com.kennyb1201.kbstream.data.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LS-P2-8: two local lists created in the same millisecond derive the same
 * negative id, and the JSON store is keyed by it - the second silently replaced
 * the first.
 */
class LocalListIdCollisionContractTest {

    @Test
    fun `creating a list skips an id already in use`() {
        val body = functionBody("fun createList(")
        assertTrue(
            "the id must be bumped off any collision before it is stored",
            body.contains("while (lists.any { it.id == idForList })")
        )
        assertTrue(
            "and the stored list must carry the resolved id",
            body.contains("id = idForList")
        )
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), MODELS)
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
        const val MODELS = "com/kennyb1201/kbstream/data/library/LibraryModels.kt"
    }
}
