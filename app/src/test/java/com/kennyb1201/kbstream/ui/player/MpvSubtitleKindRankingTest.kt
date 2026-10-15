package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPV's automatic subtitle pick ranks the language matches by KIND before
 * first-wins, mirroring the ExoPlayer engine.
 *
 * Reported (field, 2026-10-15): forced subtitles auto-selected over
 * SDH/regular "sometimes" on MPV. The "sometimes" was the file's track order -
 * the old pick was a bare `firstOrNull { LanguageMatch.matches(...) }`, so a
 * forced-first English list got forced subtitles even when an SDH or regular
 * track sat later. MPV's `Track` carries no forced flag, so kind is read from
 * the title through [SubtitleKindRules], the same detection the ExoPlayer SDH
 * bucket uses.
 *
 * The ranking lives in [MpvPlayerView]'s companion as pure functions, so the
 * behavior is pinned directly rather than by reading the source (this engine
 * has no unit harness); the wiring into [MpvPlayerView.selectSubtitleLanguage]
 * is pinned by source, as the repo's other MPV contracts do.
 */
class MpvSubtitleKindRankingTest {

    // ── the scenarios from the report ──────────────────────────────────────

    @Test
    fun `a forced-first file still lands on its SDH track`() {
        val matches = listOf(
            track(title = "English (Forced)"),
            track(title = "English SDH"),
            track(title = "English")
        )
        assertEquals(
            "the SDH track must win over first-wins",
            matches[1],
            MpvPlayerView.rankedSubtitleMatch(matches)
        )
    }

    @Test
    fun `a forced track loses to a plain regular track`() {
        val matches = listOf(track(title = "English (Forced)"), track(title = "English"))
        assertEquals(matches[1], MpvPlayerView.rankedSubtitleMatch(matches))
    }

    @Test
    fun `a forced track alone is still chosen, so the fallback survives`() {
        val matches = listOf(track(title = "English (Forced)"))
        assertEquals(matches[0], MpvPlayerView.rankedSubtitleMatch(matches))
    }

    @Test
    fun `an untitled track outranks a forced one, since it reads as regular`() {
        val matches = listOf(track(title = null), track(title = "Forced"))
        assertEquals(matches[0], MpvPlayerView.rankedSubtitleMatch(matches))
    }

    @Test
    fun `a title with no kind keeps first-wins, as before`() {
        val matches = listOf(track(title = "English"), track(title = "English 2"))
        assertEquals(
            "no SDH and no forced evidence, so the first match is kept",
            matches[0],
            MpvPlayerView.rankedSubtitleMatch(matches)
        )
    }

    // ── the classifiers ────────────────────────────────────────────────────

    @Test
    fun `SDH is read off the track's own title`() {
        listOf("SDH", "English SDH", "English (HI)", "English CC", "Hearing Impaired").forEach {
            assertTrue(
                "a track titled \"$it\" is SDH",
                MpvPlayerView.isSdhTitle(track(title = it))
            )
        }
        assertFalse(MpvPlayerView.isSdhTitle(track(title = "English")))
        assertFalse(MpvPlayerView.isSdhTitle(track(title = null)))
    }

    @Test
    fun `a forced or signs title is not regular, and neither is SDH`() {
        listOf("Forced", "English (Forced)", "Signs", "Signs & Songs", "Foreign Parts").forEach {
            assertFalse(
                "a track titled \"$it\" is forced, not regular",
                MpvPlayerView.isRegularTitle(track(title = it))
            )
        }
        assertFalse(MpvPlayerView.isRegularTitle(track(title = "English SDH")))
        assertTrue(
            "a plain title is regular",
            MpvPlayerView.isRegularTitle(track(title = "English"))
        )
        assertTrue(
            "an untitled track reads as regular, like the Exo untagged bucket",
            MpvPlayerView.isRegularTitle(track(title = null))
        )
    }

    @Test
    fun `no matches means no pick`() {
        assertNull(MpvPlayerView.rankedSubtitleMatch(emptyList()))
    }

    // ── the wiring into the automatic pick ─────────────────────────────────

    @Test
    fun `the automatic pick ranks the language matches instead of taking the first`() {
        val body = functionBody(source(), "fun selectSubtitleLanguage(language: String): Boolean {")
        assertTrue(
            "language is still the gate before kind",
            body.contains("subtitleTracks().filter { LanguageMatch.matches(language, it.language) }")
        )
        assertTrue(
            "and the gated matches are ranked by kind before one is picked",
            body.contains("rankedSubtitleMatch(matches)")
        )
        assertFalse(
            "the old bare first-wins must be gone",
            body.contains("firstOrNull { LanguageMatch.matches(language, it.language) }")
        )
    }

    @Test
    fun `the ranked order is SDH, then regular, then first`() {
        val companion = functionBody(source(), "fun rankedSubtitleMatch(matches: List<Track>): Track? =")
        val sdh = companion.indexOf("firstOrNull { isSdhTitle(it) }")
        val regular = companion.indexOf("firstOrNull { isRegularTitle(it) }")
        val first = companion.indexOf("?: matches.firstOrNull()")
        assertTrue("SDH arm missing", sdh >= 0)
        assertTrue("regular arm must follow SDH", regular > sdh)
        assertTrue("the first-match fallback must be last", first > regular)
    }

    @Test
    fun `the blank-language and explicit-pick paths are untouched`() {
        val body = functionBody(source(), "fun selectSubtitleLanguage(language: String): Boolean {")
        assertTrue(
            "blank hands the choice back to mpv",
            body.contains("MpvBackend.setPropertyString(\"sid\", \"auto\")")
        )
        assertTrue(
            "and no match still turns subtitles off",
            body.contains("clearSubtitles()")
        )
        val explicit = functionBody(source(), "fun selectSubtitleTrack(id: Int) {")
        assertTrue(
            "an explicit pick is direct, with no ranking",
            explicit.contains("MpvBackend.setPropertyInt(\"sid\", id)") &&
                !explicit.contains("rankedSubtitleMatch")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun track(
        language: String? = "en",
        title: String? = null,
        id: Int = 0
    ) = MpvPlayerView.Track(
        id = id,
        type = "sub",
        language = language,
        title = title,
        codec = "subrip",
        channels = null,
        selected = false
    )

    private val cachedSource: String by lazy { read(MPV_VIEW) }

    private fun source(): String = cachedSource

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /**
     * The text of the declaration whose signature contains [signature], from
     * that name to the next line indented no deeper - which is the declaration
     * after it, whether the body is a block or an expression.
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing: $signature", start >= 0)
        val indent = start - (src.lastIndexOf('\n', start) + 1)
        val rest = src.substring(start)
        val end = Regex("\n {0,$indent}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun sourceRoot(): File {
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
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
