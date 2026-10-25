package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide-match section's wiring, which a JVM test cannot exercise end to end
 * (it needs the repository, a cached playlist and imported guides).
 *
 * Three things are load-bearing and invisible to the pure rules in
 * `GuideMatchReportTest`:
 *
 *  - it must sit beside the per-source guide line it explains, because the pair
 *    is what answers "what does each source hold" and "who is holding it for";
 *  - building it must never import, fetch or re-query programs - a diagnostics
 *    capture that changed the state it reports would be worse than none;
 *  - it must read the CACHED playlist, which is the only copy that already
 *    carries the Free TV US filter and the other load-time filters.
 */
class GuideMatchDiagnosticsContractTest {

    private val diagnostics by lazy {
        read("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
    }
    private val repository by lazy {
        read("com/kennyb1201/kbstream/data/iptv/IptvRepository.kt")
    }

    @Test
    fun `the section sits directly after the per-source guide line`() {
        val guideLine = diagnostics.indexOf("guideLine(app)?.let { report.appendLine(it) }")
        assertTrue("the guide line is gone", guideLine >= 0)

        val after = diagnostics.substring(guideLine).lineSequence()
            .drop(1)
            // Past the explanation comment: the first code after the comment.
            .first { it.isNotBlank() && !it.trim().startsWith("//") }
        assertTrue(
            "the match section must follow the guide line, got: $after",
            after.trim() == "guideMatchLines(app).forEach { report.appendLine(it) }"
        )
    }

    @Test
    fun `building the section neither imports nor fetches`() {
        val report = body(
            repository,
            "suspend fun guideMatchReport(): GuideMatchReport? = withContext(Dispatchers.IO) {"
        )

        assertTrue(
            "the channel list must come from the cache",
            report.contains("loadCachedPlaylist(")
        )
        assertFalse("a capture must not start an import", report.contains("importGuide("))
        assertFalse("a capture must not fetch a playlist", report.contains("loadPlaylist("))
        assertFalse("a capture must not touch the network", report.contains("IptvHttpClient"))
        assertFalse("a capture must not fetch a guide", report.contains("streamXmltv"))
        // A profile with nothing cached has no lineup to describe.
        assertTrue(
            "an empty cache is the no-lineup case",
            report.contains("return@withContext null")
        )
    }

    @Test
    fun `rows carry name, tvg-id and group, and diagnostics redacts the sources`() {
        val guidance = read("com/kennyb1201/kbstream/data/iptv/GuideMatchReport.kt")

        assertTrue(guidance.contains("val displayName: String"))
        assertTrue(guidance.contains("val tvgId: String?"))
        assertTrue(guidance.contains("val groupTitle: String?"))
        assertFalse(
            "a row must never carry the stream url (an Xtream account lives there)",
            guidance.substringBefore("fun lines(").contains("streamUrl")
        )
        assertTrue(
            "source urls are redacted with the same rule the guide line uses",
            diagnostics.contains("guideMatchSectionLines(report, Redaction::url)")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val brace = source.indexOf('{', start)
        assertTrue("no body for member: $signature", brace >= 0)
        val rest = source.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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
        throw AssertionError("main source root not found")
    }
}
