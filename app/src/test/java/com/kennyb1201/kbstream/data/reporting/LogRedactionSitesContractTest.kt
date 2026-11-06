package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log lines that carry a user credential, pinned at their call site.
 *
 * [RedactionTest] covers what the helper does. This file covers the other half,
 * and the half a sweep keeps finding: a line that never calls it. Each of these
 * three writes something the app is not allowed to keep - a user-supplied
 * profile URL that can carry a Stremio-style config or an Xtream `user:pass`
 * path, a signed googlevideo URL whose query is the signature, and a one-time
 * subtitle download link - and each fails silently, because the line looks fine
 * in a capture that has nothing to compare it against.
 */
class LogRedactionSitesContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `a user-supplied profile url is redacted, not echoed`() {
        val repository = source("com/kennyb1201/kbstream/data/kb/KBRepository.kt")
        val line = repository
            .substringAfter("suspend fun loadProfiles(")
            .substringBefore("    /** One profile's collections")
        assertTrue(
            "the failure log must go through the shared helper: the URL is the " +
                "viewer's, and a manifest/iptv URL can hold a credential in its path",
            line.contains("Redaction.url(url)")
        )
        assertFalse(
            "the raw value must not survive anywhere in the line",
            line.contains("url=\$url")
        )
    }

    @Test
    fun `a signed stream url is not printed whole`() {
        val factory =
            source("com/kennyb1201/kbstream/data/youtube/YoutubeChunkedDataSourceFactory.kt")
        assertTrue(
            "the opening line is the one that carries `expire`, `ei`, `ip` and the " +
                "start of the signature",
            factory.contains("Redaction.streamUrl(originalUrlString)")
        )
        assertFalse(
            "a 160-char truncation is not redaction",
            factory.contains("url=\${originalUrlString?.take(160)}")
        )
    }

    @Test
    fun `a subtitle response body is redacted before it is logged`() {
        val helper = source("com/kennyb1201/kbstream/ui/player/SubtitleSearchHelper.kt")
        assertTrue(
            "one helper for all three body lines",
            helper.contains("private fun bodyForLog(body: String): String =") &&
                helper.contains("Redaction.text(body.take(200))")
        )
        listOf(
            "OpenSubtitles /download HTTP",
            "OpenSubtitles /download had no link",
            "OpenSubtitles subtitle file was not a subtitle"
        ).forEach { line ->
            assertTrue("the line is still there: $line", helper.contains(line))
        }
        assertEquals(
            "every one of the three body lines logs through bodyForLog",
            3,
            Regex("\\$\\{bodyForLog\\(").findAll(helper).count()
        )
        assertFalse(
            "no body line may log the raw text",
            helper.contains("\${meta.body.take(200)}") ||
                helper.contains("\${file.body.take(200)}")
        )
    }
}
