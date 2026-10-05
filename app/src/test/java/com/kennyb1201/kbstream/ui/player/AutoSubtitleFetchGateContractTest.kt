package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every downloaded subtitle must be validated before it reaches the player.
 *
 * `SubtitleDownload.Ready` only means the HTTP call answered 200 — an HTML error
 * page, an empty body, a truncated file, or a plain-text "download limit"
 * notice all arrive as `Ready`. Attaching one of those did two visible wrong
 * things: the player rebuilt for a track that never drew (the picture/audio
 * blink), and the surface claimed success anyway — the auto-fetch toast
 * ("Subtitles: …") and mpv's manual line ("Subtitle loaded: …") both named a
 * track the picker never showed. The manual route is the one a viewer
 * deliberately picked, so a bad file there has to be *told*, not swallowed.
 *
 * The rule lives once, on `SubtitleSearchHelper.isUsableSubtitleBody` (unit
 * tested in `SubtitleSearchHelperTest`): the same ASS sniff the load path uses,
 * else an SRT/WebVTT parse to at least one cue. This class pins that all four
 * download routes — auto and manual, in both engines — gate on it, and that the
 * gate precedes the attach and any success message.
 *
 * This is wiring inside Android activities, so a device cannot be avoided here.
 * What a contract test can pin is the shape and the order.
 */
class AutoSubtitleFetchGateContractTest {

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
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val nativeAuto: String by lazy {
        functionBody(NATIVE, "private fun maybeAutoFetchSubtitle() {")
    }

    private val nativeManual: String by lazy {
        functionBody(NATIVE, "private fun downloadOnlineSubtitle(hit: SubtitleSearchResult) {")
    }

    private val mpvAuto: String by lazy {
        functionBody(MPV, "private fun maybeAutoFetchSubtitle() {")
    }

    private val mpvManual: String by lazy {
        functionBody(MPV, "private fun downloadOnlineSubtitle(hit: SubtitleSearchResult) {")
    }

    private val gate = "SubtitleSearchHelper.isUsableSubtitleBody("

    @Test
    fun `all four download routes gate on the shared usability rule`() {
        listOf(
            "native auto-fetch" to nativeAuto,
            "native manual picker" to nativeManual,
            "mpv auto-fetch" to mpvAuto,
            "mpv manual picker" to mpvManual
        ).forEach { (route, body) ->
            assertTrue("$route must validate the body before attaching", body.contains(gate))
        }
    }

    @Test
    fun `native routes reject before the player is rebuilt`() {
        listOf("auto-fetch" to nativeAuto, "manual picker" to nativeManual).forEach {
                (route, body) ->
            val check = body.indexOf(gate)
            val attach = body.indexOf("attachExternalSubtitle(uri)")
            assertTrue("$route must still attach a usable body", attach > check)
        }
    }

    @Test
    fun `a manual pick with no readable cues is reported, not loaded`() {
        // ExoPlayer: an honest, visible failure instead of a silent rebuild.
        val check = nativeManual.indexOf(gate)
        val attach = nativeManual.indexOf("attachExternalSubtitle(uri)")
        val reject = nativeManual.substring(check, attach)
        assertTrue("the rejected body must leave the coroutine", reject.contains("return@launch"))
        assertTrue("the viewer must be told", reject.contains("Toast.LENGTH_LONG"))

        // mpv: the gate must sit ahead of both the attach and the
        // "Subtitle loaded" line, so neither runs for a bad body.
        val mpvCheck = mpvManual.indexOf(gate)
        val mpvAttach = mpvManual.indexOf("applyDownloadedSubtitle(hit, uri)")
        val mpvLoaded = mpvManual.indexOf("\"Subtitle loaded:")
        assertTrue("mpv must gate before attaching", mpvCheck in 0 until mpvAttach)
        assertTrue("mpv must gate before claiming success", mpvCheck in 0 until mpvLoaded)
        val mpvReject = mpvManual.substring(mpvCheck, mpvAttach)
        assertTrue("mpv must leave the coroutine", mpvReject.contains("return@launch"))
        assertTrue("mpv must tell the viewer", mpvReject.contains("showToast("))
    }

    @Test
    fun `an auto-fetch with no readable cues is dropped silently, never announced`() {
        // ExoPlayer: log and bail before the attach; no toast for a bad body.
        val check = nativeAuto.indexOf(gate)
        val attach = nativeAuto.indexOf("attachExternalSubtitle(uri)")
        val reject = nativeAuto.substring(check, attach)
        assertTrue("the rejected body must leave the coroutine", reject.contains("return@launch"))
        assertTrue("the rejected body must be logged", reject.contains("Log.w("))
        assertFalse("a rejected body must not raise a toast", reject.contains("Toast"))

        // mpv: same, ahead of the attach and the "Subtitles:" announcement.
        // The LAST announcement, not the first: a prefetched subtitle (fetched
        // during the previous episode's credits, and validated by the prefetch)
        // announces itself before the search even starts, and it is not what
        // this gate is about - the file THIS route downloaded is the one whose
        // announcement has to come after the check.
        val mpvCheck = mpvAuto.indexOf(gate)
        val mpvAttach = mpvAuto.indexOf("applyDownloadedSubtitle(pick, uri)")
        val mpvToast = mpvAuto.lastIndexOf("\"Subtitles: ")
        assertTrue("mpv must gate before attaching", mpvCheck in 0 until mpvAttach)
        assertTrue("mpv must gate before announcing its own download", mpvCheck in 0 until mpvToast)
        val mpvReject = mpvAuto.substring(mpvCheck, mpvAttach)
        assertTrue("mpv must leave the coroutine", mpvReject.contains("return@launch"))
        assertFalse("a rejected body must not raise a toast", mpvReject.contains("showToast"))
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
