package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-playback frame-rate probe only helps where it is actually launched,
 * and the wiring is the part source review cannot see at runtime: a probe that
 * is written but never called, or called on the wrong engine, looks exactly like
 * a probe that found nothing. So the call sites are pinned here by reading the
 * sources.
 *
 * Also pinned: the probe reads the container's own declared rate (never samples
 * timestamps on a truncated head, which hangs), refuses a null result through
 * the matcher's own `normalise`, and never runs for a live channel where the
 * bytes never end.
 */
class FrameRateProbeWiringContractTest {

    private fun source(path: String): String {
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `the probe's own maps are bounded`() {
        // Both were unbounded `ConcurrentHashMap`s for the life of the process
        // (audit AFR C12). A television runs for days, every distinct signed URL
        // added an entry, and a hit was rare because signed URLs rotate - so the
        // map only grew. The cap is the whole fix; the LRU form is what keeps
        // the entries that can still be hit.
        val probe = squash(source(PROBE))
        assertTrue(
            "there is a stated cap",
            probe.contains("private const val MAX_TRACKED_URLS = 32")
        )
        assertEquals(
            "both maps evict, so neither can grow past it",
            2,
            count(probe, "override fun removeEldestEntry")
        )
        assertTrue(
            "and every access goes through the lock - the plain ConcurrentHashMap " +
                "is gone",
            !probe.contains("ConcurrentHashMap<") &&
                probe.contains("synchronized(cacheLock)")
        )
    }

    /** How many times [needle] occurs in [text]. */
    private fun count(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    /**
     * The squashed source between the first [from] anchor and the next [to].
     * Both are asserted to exist, so a rename here fails loudly instead of
     * quietly widening the window to the whole file.
     */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor missing after '${from}': $to", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `the probe reads the declared rate first and only then the timestamps`() {
        val probe = source(PROBE)
        assertTrue(
            "the declared video rate is read off the head",
            probe.contains("MediaFormat.KEY_FRAME_RATE")
        )
        assertTrue(
            "the extractor must be released on every path",
            probe.contains("extractor.release()")
        )
        assertTrue(
            "the head is a ranged fetch",
            probe.contains("bytes=\$from-\$to")
        )
    }

    @Test
    fun `a head that declares no rate falls back to a bounded timestamp estimate`() {
        // Field 2026-10-11: a Matroska WEB-DL declares no KEY_FRAME_RATE, so the
        // declared path returned null on a stream the decoder measured at
        // 23.976 while the cadence sat in the head's own sample timestamps. The
        // fallback has to stay second (a declared rate is authoritative),
        // bounded (a truncated head must not become an open-ended walk), and
        // pure (the rule is unit-tested without an extractor).
        val probe = source(PROBE)
        val declared = probe.indexOf("readFrameRate(format)?.let")
        val fallback = probe.indexOf("estimateFps(extractor, i, mime)")
        assertTrue(
            "the track's own declared rate must be consulted before any estimate",
            declared >= 0 && fallback > declared
        )
        assertTrue(
            "the estimate is a pure rule the JVM tests drive",
            probe.contains(
                "internal fun estimateFpsFromTimestamps(timestamps: List<Long>): Double?"
            )
        )
        assertTrue(
            "the walk is capped, so a slow or truncated head degrades to null inside the budget",
            probe.contains("MAX_TIMESTAMP_SAMPLES = 48") &&
                probe.contains("repeat(MAX_TIMESTAMP_SAMPLES)")
        )
        assertTrue(
            "and the failure still logs as a null path rather than staying silent",
            probe.contains("probe: timestamp cadence unusable")
        )
    }

    @Test
    fun `probe junk is refused through the matcher's own normalise`() {
        assertTrue(
            "0, NaN and absurd rates must be refused exactly like player-reported ones",
            source(PROBE).contains("FrameRateMatch.normalise(")
        )
    }

    @Test
    fun `the native player probes alongside player prep and marks the source`() {
        val native = source(NATIVE)
        assertTrue(
            "the probe must be launched from the native player's setup",
            native.contains("FrameRateProbe.probe(")
        )
        assertTrue(
            "the probed rate is labelled so the diagnostics row can name its source",
            native.contains("FrameRateSource.PROBE")
        )
        assertTrue(
            "a live channel is never probed: its bytes never end",
            native.contains("!isLiveChannel && FrameRateProbe.isProbeable(currentUrl)")
        )
    }

    @Test
    fun `the mpv player probes at session start with the player report as fallback`() {
        val mpv = source(MPV)
        assertTrue(
            "the probe launch is a named step at session start",
            squash(mpv).contains("fun loadStream() { startFrameRateProbe()")
        )
        assertTrue(
            "the probe must actually be launched",
            mpv.contains("FrameRateProbe.probe(")
        )
        assertTrue(
            "the probed rate is labelled for the diagnostics row",
            mpv.contains("FrameRateSource.PROBE")
        )
        assertTrue(
            "mpv's own frame-rate report stays as the fallback/confirmation",
            mpv.contains("onVideoFrameRateChanged = { fps ->")
        )
    }

    @Test
    fun `every null path of the probe names itself in the log`() {
        // The field capture this pins (2026-10-11) had three playbacks whose
        // PLAYER_AFR lines contained only the panel report: the probe returned
        // null five different ways, silently, so the log could not say which.
        val probe = source(PROBE)
        assertTrue(
            "a URL that cannot be probed must say so",
            probe.contains("probe skipped: URL not probeable (host=\${hostOf(url)})")
        )
        assertTrue(
            "a probe that read nothing must be distinguishable from one never launched",
            probe.contains("probe: no reading (timeout or download failed, host=\${hostOf(url)})")
        )
        assertTrue(
            "a refused reading must name the value it refused",
            probe.contains("probe: reading \$measured fps unusable (host=\${hostOf(url)})")
        )
        assertTrue(
            "and a head that arrived carrying nothing must NOT be logged as a download " +
                "failure: that is the retry's own input, and the two kinds decide it",
            probe.contains(
                "probe: head arrived but carries no usable rate (host=\${hostOf(url)})"
            )
        )
        assertTrue(
            "a head that is not a 206 must report the status it got",
            probe.contains("probe head request: HTTP \${response.code}")
        )
        assertTrue(
            "an empty head body must say so",
            probe.contains("probe head: empty body")
        )
        assertTrue(
            "a video track without a rate key is the mislabelled-container case",
            probe.contains("probe: video track has no frame-rate key (mime=\$mime)")
        )
        assertTrue(
            "no video track at all is its own answer",
            probe.contains("probe: head has no video track")
        )
    }

    @Test
    fun `a cached rate says it came from the cache`() {
        // Field 2026-10-17: a second playback of the same URL offered the panel a
        // rate with no `probed ...` line before it, because a cache hit returned
        // silently, and the capture read as if the probe had never run at all.
        val probe = squash(source(PROBE))
        assertTrue(
            "a cache hit must log the rate it returned, or the field log cannot tell a hit " +
                "from a probe that never ran",
            probe.contains("probe cache hit: \$it fps (host=\${hostOf(url)})")
        )
        assertTrue(
            "and it is logged where the cache is read, before any download (the read goes " +
                "through cachedFps now that the map is a bounded LRU, audit AFR C12)",
            probe.indexOf("cachedFps(url)?.let {") in 0 until probe.indexOf("val reading = ")
        )
    }

    @Test
    fun `the probe logs the host, never the signed url`() {
        val probe = source(PROBE)
        assertTrue(
            "the host helper is the only piece of the URL a log line may carry",
            probe.contains("private fun hostOf(url: String): String")
        )
        assertTrue(
            "a URL that will not parse still has to produce a log line",
            probe.contains("?: \"?\"")
        )
        listOf("\$url", "\${url}").forEach { interpolation ->
            assertTrue(
                "a signed debrid URL must never be interpolated into a log line: $interpolation",
                !probe.contains(interpolation)
            )
        }
    }

    @Test
    fun `a probe that was never launched says so, on the AFR tag`() {
        val native = source(NATIVE)
        assertTrue(
            "the else leg is what makes 'never launched' visible at all",
            native.contains("probe not launched: matcher=\${matcher != null} ")
        )
        assertTrue(
            "it belongs to the same tag as the probe's own lines",
            native.contains("\"PLAYER_AFR\"")
        )
    }

    @Test
    fun `both engines retry the probe once, from the signal that bytes are flowing`() {
        // Field 2026-10-16: the probe's budget lost to a slow host's cold start
        // and nothing ever re-attempted, so the session stayed at 60 Hz while
        // the extractor was demonstrably receiving bytes ~3 s later. The retry
        // hangs off the version of that confirmation each engine already has -
        // the native player's arriving video track, mpv's own rate report - and
        // neither may reach for a timer or a resume, because both of those fire
        // before anything is known about the stream.
        val native = squash(source(NATIVE))
        val mpv = squash(source(MPV))
        val nativeBlock = window(
            native,
            "frameRateMatcher?.onContentFrameRate(reportedFrameRate)",
            "val codec = fmt.codecs.orEmpty()"
        )
        val mpvBlock = window(mpv, "onVideoFrameRateChanged = { fps ->", "setupControls()")

        listOf("the native player" to nativeBlock, "the mpv player" to mpvBlock)
            .forEach { (who, block) ->
                assertTrue(
                    "$who must ask the one-shot record whether the first attempt died on the network",
                    block.contains("FrameRateProbe.consumeNetworkFailure(currentUrl)")
                )
                assertTrue(
                    "$who must not retry when a rate is already known from any source",
                    block.contains("lastContentFps == null")
                )
                assertTrue(
                    "$who must launch the retry on the activity's coroutine scope",
                    block.contains("lifecycleScope.launch")
                )
                assertTrue(
                    "$who must not reach for a timer",
                    !block.contains("postDelayed")
                )
                assertTrue(
                    "$who must not retry from the resume path, which runs before the stream is known",
                    !block.contains("FrameRateSource.RESUME")
                )
                assertTrue(
                    "$who must give the second attempt more time than the first",
                    block.contains("budgetMs = 12_000L")
                )
                assertTrue(
                    "$who must label the retried rate, or the diagnostics row calls it a first probe",
                    block.contains("FrameRateSource.PROBE_RETRY")
                )
                assertTrue(
                    "$who must log the retry on the probe's own tag",
                    block.contains(
                        "Log.i(\"PLAYER_AFR\", \"retrying frame-rate probe after network failure\")"
                    )
                )
            }

        // Counted as CALL SITES, not as mentions: both files' comments name the
        // record on purpose, and only the calls can retry anything.

        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            assertEquals(
                "$who must ask the one-shot record from exactly one place - a second call " +
                    "site would be a second retry",
                1,
                count(src, "FrameRateProbe.consumeNetworkFailure(")
            )
            assertEquals(
                "and log the retry from exactly one place, or the field log counts attempts " +
                    "that did not happen",
                1,
                count(src, "retrying frame-rate probe after network failure")
            )
        }
        assertTrue(
            "and the native one really is inside that branch: the branch sets the flag " +
                "this retry gates on before reaching it",
            native.indexOf("videoTrackPresent = true") in
                0 until native.indexOf("FrameRateProbe.consumeNetworkFailure(")
        )
        assertTrue(
            "every existing caller keeps the process budget: that is what makes the " +
                "first attempt byte-for-byte today's behavior",
            squash(source(PROBE)).contains("budgetMs: Long = FrameRateProbe.budgetMs")
        )
    }

    @Test
    fun `both engines restart matching for a source switched mid-session`() {
        // Audit AFR C1: the matcher is built once per activity, so the engage
        // window, the held mode and the remembered rate all described the
        // activity's FIRST title. After 90 s every offer for a switched source
        // was refused as late, on both engines.
        val native = squash(source(NATIVE))
        val mpv = squash(source(MPV))

        val nativeSwitch = window(
            native,
            "fun switchToSource(stream: Stream",
            "recreatePlayer(settleMs = SOURCE_SWITCH_SETTLE_MS)"
        )
        assertTrue(
            "the native player restarts the per-source state before the rebuild",
            nativeSwitch.contains("restartFrameRateMatchForSource()")
        )
        val nativeRestart = window(
            native,
            "private fun restartFrameRateMatchForSource()",
            "internal fun stepDialogueBoost"
        )
        assertTrue(
            "which forgets the old source's rate (audit AFR C2)",
            nativeRestart.contains("lastContentFps = null")
        )
        assertTrue(
            "restarts the engage window and the held mode",
            nativeRestart.contains("matcher.beginSource()")
        )
        assertTrue(
            "and re-probes the new head, because an addon stream reports no rate",
            nativeRestart.contains("FrameRateProbe.probe(probeUrl, probeHeaders, cacheDir)")
        )

        val mpvSwitch = window(
            mpv,
            "private fun switchToSource(stream: Stream",
            "private fun showSettingsPanel"
        )
        assertTrue(
            "mpv restarts the same state for its switch",
            mpvSwitch.contains("frameRateMatcher?.beginSource()")
        )
        assertTrue(
            "and probes the new file's head, which it never used to do",
            mpvSwitch.contains("startFrameRateProbe()")
        )
    }

    @Test
    fun `live channels are not offered to the matcher`() {
        // Audit AFR C9: a 50 fps live channel on a 60 Hz-only panel has no mode
        // to switch to, so the only reachable outcome was the surface vote - a
        // live surface asked for 50 fps, the write the 2026-10-18 capture
        // convicted. The probe was already gated on live; the offer was not.
        val native = squash(source(NATIVE))
        val offer = window(
            native,
            "if (!isLiveChannel) { if (FrameRateMatch.normalise(reportedFrameRate)",
            "// A video track arriving is the field-proven"
        )
        assertTrue(
            "the live gate wraps the remember AND the offer",
            offer.contains("lastContentFps = reportedFrameRate") &&
                offer.contains("frameRateMatcher?.onContentFrameRate(reportedFrameRate)")
        )
    }

    private companion object {
        const val PROBE = "com/kennyb1201/kbstream/ui/player/FrameRateProbe.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
