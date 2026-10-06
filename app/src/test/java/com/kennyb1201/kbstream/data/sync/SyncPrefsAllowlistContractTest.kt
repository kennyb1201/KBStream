package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SI-P2-2: the display-prefs applier must adopt ONLY keys we still sync.
 *
 * A blob pushed by a build that predates the secure-store move still carries an
 * API key. Without an allowlist the pull wrote it back into plaintext display
 * prefs on every sync, undoing the migration.
 */
class SyncPrefsAllowlistContractTest {

    @Test
    fun `the display applier drops keys outside the synced set`() {
        val body = functionBody("private fun applyDisplayPrefs(")
        assertTrue(
            "a key outside SYNCED_PREF_KEYS is exactly the stale-blob case",
            body.contains("if (key !in PrefsPayloadBuilder.SYNCED_PREF_KEYS) return@forEach")
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
        const val PREFS = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
    }
}
