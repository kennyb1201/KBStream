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

    /**
     * How the auto-fetch attaches: no player rebuild.
     *
     * The rebuild is what the attach cost before - a re-open of the stream,
     * a second or two of buffering - and it is exactly what an automatic
     * subtitle must not do to a film already playing (see
     * `NativePlayerActivity.attachExternalSubtitle`). The manual routes keep
     * the rebuild, because the viewer asked for that one and expects a beat.
     */
    private val nativeAutoAttach = "attachExternalSubtitle(uri, rebuild = false)"

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
        val autoCheck = nativeAuto.indexOf(gate)
        val autoAttach = nativeAuto.indexOf(nativeAutoAttach)
        assertTrue(
            "the auto-fetch must still attach a usable body, without a rebuild",
            autoAttach > autoCheck
        )
        val manualCheck = nativeManual.indexOf(gate)
        val manualAttach = nativeManual.indexOf("attachExternalSubtitle(uri)")
        assertTrue("the manual pick must still attach a usable body", manualAttach > manualCheck)
    }

    @Test
    fun `an automatic subtitle never announces itself over the picture`() {
        // The toast that used to pop up "Subtitles: …" mid-playback. An
        // automatically fetched track is not news the viewer needs, and the
        // popup landed on top of the film at the exact moment the attach used
        // to stall it. The subtitle appearing IS the feedback.
        assertFalse(
            "the native auto-fetch must not raise a toast",
            nativeAuto.contains("Toast")
        )
        assertTrue(
            "and it must attach without rebuilding the player",
            nativeAuto.contains(nativeAutoAttach)
        )
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
        val attach = nativeAuto.indexOf(nativeAutoAttach)
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

    /**
     * The second arrival hazard for the same route: a bad body is refused above,
     * and a *good* body for the wrong video is refused here.
     *
     * The search and the download are network round trips, and the playback they
     * were started for can be replaced while they run - the credits advance to
     * the next episode, a pick from the end panel switches episodes, a stream
     * handoff re-opens the same screen with new extras. Attaching on arrival
     * then draws the previous episode's text over the new picture and remembers
     * it for that video, which is a worse outcome than no subtitle: the viewer
     * cannot tell it is the wrong track.
     */
    @Test
    fun `the auto-fetch is bound to the video it was launched for`() {
        val capture = nativeAuto.indexOf("val launchIdentity = videoIdentity()")
        assertTrue("the identity must be captured at launch", capture >= 0)
        assertTrue(
            "and before the first request goes out",
            capture < nativeAuto.indexOf("SubtitleSearchHelper.search(")
        )

        val attach = nativeAuto.indexOf(nativeAutoAttach)
        assertTrue("the route must still attach", attach > capture)
        assertTrue(
            "the identity must be re-checked right before the attach",
            nativeAuto.lastIndexOf("stillSameVideo(launchIdentity)", attach) > capture
        )
        val recheck = nativeAuto.substring(0, attach)
        assertTrue(
            "a download for another video must not reach the player",
            nativeAuto.substring(recheck.lastIndexOf("stillSameVideo(launchIdentity)"), attach)
                .contains("return@launch")
        )
    }

    @Test
    fun `the video identity is the episode, not the stream url`() {
        // A source switch, a failover or a rebuild re-opens the same episode
        // under a new URL; keying on that would throw away a track that is
        // still the right one.
        val line = readSource(NATIVE)
            .lineSequence()
            .firstOrNull { it.contains("private fun videoIdentity()") }
        assertTrue("videoIdentity must exist", line != null)
        val body = line.orEmpty()
        assertTrue(body.contains("itemName"))
        assertTrue(body.contains("season"))
        assertTrue(body.contains("episode"))
        assertFalse("the stream url is not the video's identity", body.contains("currentUrl"))
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
