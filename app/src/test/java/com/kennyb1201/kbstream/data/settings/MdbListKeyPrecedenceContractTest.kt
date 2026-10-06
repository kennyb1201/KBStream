package com.kennyb1201.kbstream.data.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SI-P2-1: one key-precedence chain. A pasted key wins; the build-time
 * BuildConfig key is the fallback, so a build that ships one still rates titles
 * AND verifies - the getter used to stop at "" while the comment promised
 * BuildConfig precedence.
 */
class MdbListKeyPrecedenceContractTest {

    @Test
    fun `the getter falls back to the build key`() {
        val body = functionBody("fun getMdbListApiKey(")
        assertTrue(
            "the build-time key must be the last resort",
            body.contains("return BuildConfig.MDBLIST_API_KEY.trim()")
        )
        val stored = body.indexOf("if (stored.isNotBlank()) return stored")
        val build = body.indexOf("BuildConfig.MDBLIST_API_KEY")
        assertTrue("a pasted key must still win", build > stored)
    }

    @Test
    fun `the doc comment matches the pasted-key-first behaviour`() {
        assertTrue(
            "the stale preference claim must be gone",
            readSource().contains("A PASTED key wins")
        )
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), PREFS)
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
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    }
}
