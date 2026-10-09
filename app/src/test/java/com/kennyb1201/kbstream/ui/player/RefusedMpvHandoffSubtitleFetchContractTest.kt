package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this pins: a file whose preferred-language subtitle is a bitmap
 * (PGS/VobSub/DVB) takes the `NeedsMpv` branch of the ready-time pass. When the
 * handoff to MPV is REFUSED — live TV, a DRM session, no libmpv, or the
 * automatic fallback switched off — the branch only logged and left text off.
 * OpenSubtitles could have supplied a renderable text track, and the one path
 * that could fix it was never tried: the viewer asked for subtitles in their
 * language, the file has only bitmap ones, this engine cannot draw them, and
 * nothing happened.
 *
 * The fix falls through to the same fetch the `Off` branch already used. A
 * GRANTED handoff still skips it (MPV draws the bitmap track itself, so a fetch
 * would be pure waste), and the bitmap track stays disarmed either way.
 *
 * This is wiring inside an Android activity, so the branches are pinned where
 * they live: [NativePlayerActivity.autoSelectPreferredLanguages] for the
 * handoff, and [NativePlayerActivity.maybeAutoFetchSubtitle] for the gates.
 * The "which branch" decision itself is pure and pinned through
 * [SubtitleTrackRules.choose] below.
 */
class RefusedMpvHandoffSubtitleFetchContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
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

    /**
     * The text of the member starting at [signature] up to the next member: a
     * line indented by exactly four spaces (see
     * [AudioAutoSelectLanguageContractTest]).
     */
    private fun functionBody(signature: String): String {
        val src = readSource(NATIVE)
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * The body of one `when` branch, from its arrow to the next branch. The
     * marker omits a leading `is `, which only some branches carry.
     */
    private fun choiceBody(body: String, choice: String): String {
        val marker = "SubtitleTrackRules.Choice.$choice ->"
        val start = body.indexOf(marker)
        assertTrue("source missing choice branch: $choice", start >= 0)
        val rest = body.substring(start + marker.length)
        val end = rest.indexOf("SubtitleTrackRules.Choice.")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    /**
     * The text of the block whose `{` follows [marker], with braces matched, so
     * an assertion inside it means "inside this branch" rather than "somewhere
     * in the file".
     */
    private fun bracedBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("source missing: $marker", start >= 0)
        val open = source.indexOf('{', start + marker.length)
        assertTrue("no block after: $marker", open >= 0)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces after: $marker")
    }

    private val autoSelect: String by lazy {
        functionBody("private fun autoSelectPreferredLanguages() {")
    }

    private val needsMpv: String by lazy { choiceBody(autoSelect, "NeedsMpv") }

    private val offBranch: String by lazy { choiceBody(autoSelect, "Off") }

    private val autoFetch: String by lazy {
        functionBody("private fun maybeAutoFetchSubtitle() {")
    }

    private val refusedHandoff: String by lazy {
        bracedBlock(
            needsMpv,
            "if (!handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_SUBTITLE))"
        )
    }

    // ── the fix ────────────────────────────────────────────────────────────

    @Test
    fun `a refused MPV handoff asks for a fetched subtitle`() {
        assertTrue(
            "the bitmap-only file has nothing this engine can draw; a refused handoff must fall through " +
                "to the OpenSubtitles fetch, exactly as the Off branch does",
            refusedHandoff.contains("maybeAutoFetchSubtitle()")
        )
        assertTrue(
            "the refusal is still reported",
            refusedHandoff.contains("Log.w(")
        )
    }

    @Test
    fun `a granted handoff never fetches`() {
        val handoff = needsMpv.indexOf("handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_SUBTITLE)")
        val fetch = needsMpv.indexOf("maybeAutoFetchSubtitle()")
        assertTrue("the handoff has to be tried", handoff in 0 until fetch)
        assertTrue(
            "the fetch must sit AFTER the MPV handoff in the source, and only inside the refused block: " +
                "MPV draws the bitmap track itself, so fetching for a granted handoff is pure waste",
            refusedHandoff.contains("maybeAutoFetchSubtitle()") &&
                fetch > needsMpv.indexOf("handoff refused")
        )
        // Exactly one call in the NeedsMpv branch: a second one outside the
        // refused block would fetch even when MPV took over.
        assertEquals(
            "the NeedsMpv branch must fetch only from the refused path",
            1,
            Regex("maybeAutoFetchSubtitle\\(\\)").findAll(needsMpv).count()
        )
    }

    @Test
    fun `the bitmap track stays disarmed on the refused path too`() {
        // The fetch attaches a sidecar later; it never re-arms the bitmap track,
        // which is the whole reason this branch exists.
        assertTrue(
            "an armed bitmap track draws nothing and reports nothing",
            needsMpv.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)")
        )
        assertTrue(
            "and that disable must follow the fetch attempt, so it always runs",
            needsMpv.indexOf("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)") >
                needsMpv.indexOf("maybeAutoFetchSubtitle()")
        )
        assertFalse(
            "the refused path must not arm the bitmap track instead",
            refusedHandoff.contains("setOverrideForType(")
        )
    }

    // ── the gates the refused path inherits ────────────────────────────────

    @Test
    fun `every existing fetch gate applies to the refused path`() {
        assertTrue(
            "live TV has no title to search and must never reach the network",
            autoFetch.contains("if (isLiveChannel) return")
        )
        assertTrue(
            "Forced and Off must never fetch a full translation",
            autoFetch.contains("SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this)) !=") &&
                autoFetch.contains("SubtitleModeRules.ON")
        )
        assertTrue(
            "the once-per-session latch is the only thing keeping a rebuffer from re-fetching",
            autoFetch.contains("if (autoSubtitleFetchTried || autoSubtitleFetchInFlight) return") &&
                autoFetch.contains("autoSubtitleFetchTried = true")
        )
        assertTrue(
            "the toggle is the viewer's opt-in",
            autoFetch.contains("if (!AppPreferences.getAutoFetchSubtitles(this)) return")
        )
        assertTrue(
            "no OpenSubtitles key means no fetch",
            autoFetch.contains("if (AppPreferences.getOpensubtitlesApiKey(this).isBlank()) return")
        )
        assertTrue(
            "a title is what gets searched for",
            autoFetch.contains("if (queryTitle.isBlank()) return")
        )
    }

    @Test
    fun `no double fetch - the refused path and the Off branch share one latch`() {
        // Both branches call the same function, whose first act with the network
        // is to set autoSubtitleFetchTried: a refused handoff followed by an Off
        // pass cannot download twice.
        assertTrue(offBranch.contains("maybeAutoFetchSubtitle()"))
        assertTrue(
            "the latch has to be set before the search is launched",
            autoFetch.indexOf("autoSubtitleFetchTried = true") <
                autoFetch.indexOf("lifecycleScope.launch")
        )
    }

    @Test
    fun `the Off branch is untouched`() {
        assertTrue(
            "the Off branch keeps its own fetch",
            offBranch.contains("maybeAutoFetchSubtitle()")
        )
        assertTrue(
            "and keeps text off rather than arming a dead track",
            offBranch.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)")
        )
    }

    // ── the branch this path is reached from is pure ───────────────────────

    private fun candidate(language: String?, mimeType: String?, forced: Boolean = false) =
        SubtitleTrackRules.Candidate(
            language = language,
            mimeType = mimeType,
            forced = forced,
            supported = false
        )

    /**
     * A PGS-only file in the preferred language really does take the NeedsMpv
     * branch (not Off, not Show) — that is the precondition the fix depends on.
     */
    @Test
    fun `a PGS-only file in the preferred language chooses NeedsMpv`() {
        val choice = SubtitleTrackRules.choose(
            listOf(candidate("eng", "application/pgs")),
            preferredLanguage = "en",
            mode = SubtitleModeRules.ON
        )
        assertTrue(
            "a bitmap-only match must not be Off (which would hide the whole problem) nor Show: $choice",
            choice is SubtitleTrackRules.Choice.NeedsMpv
        )
    }

    /**
     * Forced mode can reach the same branch, and it is the mode gate inside
     * [NativePlayerActivity.maybeAutoFetchSubtitle] — not the branch — that
     * stops the fetch there.
     */
    @Test
    fun `forced mode reaches NeedsMpv and is stopped by the mode gate`() {
        val choice = SubtitleTrackRules.choose(
            listOf(candidate("eng", "application/pgs", forced = true)),
            preferredLanguage = "en",
            mode = SubtitleModeRules.FORCED
        )
        assertTrue(
            "a forced bitmap track reaches the handoff too: $choice",
            choice is SubtitleTrackRules.Choice.NeedsMpv
        )
        assertTrue(
            "so the refusal path must inherit the mode gate",
            autoFetch.contains("SubtitleModeRules.ON")
        )
    }

    /**
     * Regression pin: a renderable SRT takes the Show branch, which never calls
     * the fetch at all.
     */
    @Test
    fun `an SRT file takes the Show branch and never fetches`() {
        val supported = SubtitleTrackRules.Candidate(
            language = "eng",
            mimeType = "application/x-subrip",
            forced = false,
            supported = true
        )
        val choice = SubtitleTrackRules.choose(
            listOf(supported),
            preferredLanguage = "en",
            mode = SubtitleModeRules.ON
        )
        assertTrue(choice is SubtitleTrackRules.Choice.Show)
        assertFalse(
            "the Show branch must not fetch: it has a track to draw",
            choiceBody(autoSelect, "Show").contains("maybeAutoFetchSubtitle()")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
