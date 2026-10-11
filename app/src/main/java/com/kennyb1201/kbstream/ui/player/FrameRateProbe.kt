package com.kennyb1201.kbstream.ui.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * The content's frame rate, read from the stream's OWN bytes before playback.
 *
 * Why this exists: the whole frame-rate-matching chain starts at
 * [FrameRateMatcher.onContentFrameRate], and nobody calls it when the player
 * itself does not know the rate. Addon streams frequently carry no fps at all —
 * ExoPlayer's `Format.frameRate` is `NO_VALUE` and mpv's `videoFrameRate` is
 * never reported — so the matcher logs "no usable frame rate was reported" and
 * the panel never switches, even on a box whose HDMI output demonstrably
 * supports 24p. The declared rate is in the container's own header, and a few
 * megabytes of the head is enough to read it.
 *
 * Deliberate limits, all chosen to fail quiet:
 *  - Only http(s) file URLs. A live/playlist URL is the caller's "don't bother"
 *    (a live TV playlist is 60 fps anyway, and its bytes never end). Obvious
 *    manifests are refused here as well.
 *  - The platform [MediaExtractor] reads `KEY_FRAME_RATE` off the head chunk;
 *    there is no timestamp sampling, because sampling a truncated head hangs
 *    and starves everything else.
 *  - The whole probe gets [budgetMs]; any failure, timeout, or unparseable head
 *    returns null, which every caller treats as "the probe said nothing" — so
 *    today's code paths run byte for byte.
 *  - Results go through [FrameRateMatch.normalise], so probe junk (0, NaN,
 *    absurd) is refused exactly like player-reported junk.
 *
 * The cache is in memory for the life of the process. No disk: the probe is
 * cheap, and addon URLs are signed and expiring, so a disk entry would mostly
 * be a stale row nobody can ever hit.
 */
internal object FrameRateProbe {

    private const val TAG = "PLAYER_AFR"

    /**
     * The head fetched for parsing. A container declares its video track's rate
     * in the first few kilobytes; 4 MB is generous headroom for a box that
     * served a slow start.
     */
    private const val HEAD_BYTES = 4_194_304L

    /** Manifests and playlists are not files to parse. */
    private val NON_FILE_SUFFIXES = listOf(".m3u8", ".m3u", ".mpd", ".ism")

    /** URL → fps, for the life of the process. */
    private val cache = ConcurrentHashMap<String, Double>()

    /**
     * How long the whole probe (warm-up + head + parse) may take. Settable so a
     * test can drive the timeout path without waiting the production budget.
     */
    @Volatile
    internal var budgetMs: Long = 6_000L

    /**
     * Reads the frame rate out of a downloaded head. Extracted as a seam because
     * [MediaExtractor] is an Android native API a JVM unit test cannot drive;
     * production never overrides it.
     */
    @Volatile
    internal var fpsReader: (File) -> Double? = { extractFps(it) }

    private val client by lazy {
        BaseHttpClient.derived {
            connectTimeout(3, TimeUnit.SECONDS)
            readTimeout(4, TimeUnit.SECONDS)
        }
    }

    /**
     * Runs one call with a whole-call deadline of [timeoutMs]. [okhttp3.Call.timeout]
     * bounds the blocking body read too, which is what makes a stalled server
     * resolve inside the budget instead of hanging on a socket read.
     */
    private fun executeWithTimeout(request: Request, timeoutMs: Long) = client.newCall(request).also {
        it.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
    }.execute()

    /**
     * Whether [url] is a file the probe can read a rate out of. http(s) only,
     * with the obvious manifest extensions refused: a live playlist's bytes
     * never end and carry no rate, so asking is pure waste.
     */
    fun isProbeable(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        return NON_FILE_SUFFIXES.none { clean.endsWith(it) }
    }

    /**
     * The content's fps, or null when the probe cannot say. Never throws;
     * [headers] are the stream's own request headers, forwarded because debrid
     * and signed URLs need them.
     */
    suspend fun probe(url: String, headers: Map<String, String>): Double? =
        probe(url, headers, cacheDir = null)

    /**
     * As [probe], writing the transient head file into [cacheDir] when the
     * caller has one. A null or unusable [cacheDir] falls back to the process
     * temp directory; a probe that cannot write a temp file simply returns null.
     */
    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        cacheDir: File?
    ): Double? {
        // Every null return names itself. The probe says nothing on at least
        // five different paths, so without these the field log cannot tell
        // "the probe never ran" from "it ran and found nothing" - which is
        // exactly the ambiguity a field report of a missing AFR switch leaves.
        if (!isProbeable(url)) {
            Log.i(TAG, "probe skipped: URL not probeable (host=${hostOf(url)})")
            return null
        }
        cache[url]?.let { return it }
        val measured = withTimeoutOrNull(budgetMs) {
            runCatchingCancellable { downloadAndRead(url, headers, cacheDir) }
                .onFailure { Log.w(TAG, "frame-rate probe failed", it) }
                .getOrNull()
        }
        if (measured == null) {
            Log.w(TAG, "probe: no reading (timeout or download failed, host=${hostOf(url)})")
            return null
        }
        val fps = FrameRateMatch.normalise(measured)
        if (fps == null) {
            Log.w(TAG, "probe: reading $measured fps unusable (host=${hostOf(url)})")
            return null
        }
        cache[url] = fps
        Log.i(TAG, "probed $fps fps from the stream head")
        return fps
    }

    /**
     * The host of [url] for the log lines, or "?" when it will not parse.
     *
     * Never the URL itself: addon and debrid links are signed, so their query
     * string is a credential, and a field log is a thing users paste in public.
     */
    private fun hostOf(url: String): String =
        runCatching { Request.Builder().url(url).build().url.host }.getOrNull() ?: "?"

    private suspend fun downloadAndRead(
        url: String,
        headers: Map<String, String>,
        cacheDir: File?
    ): Double? = withContext(Dispatchers.IO) {
        val dir = cacheDir?.takeIf { it.isDirectory }
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        // Warm the origin with a single byte first: some CDNs need the warmed
        // connection (or the first byte) before a ranged head is served. Given
        // a short slice of the budget so a stalled warm-up cannot eat it all.
        runCatching {
            executeWithTimeout(request(url, headers, from = 0L, to = 0L), budgetMs / 3)
                .close()
        }
        val temp = File.createTempFile("afrprobe", ".head", dir)
        try {
            executeWithTimeout(request(url, headers, from = 0L, to = HEAD_BYTES - 1), budgetMs)
                .use { response ->
                    if (response.code != 206 && !response.isSuccessful) {
                        Log.w(TAG, "probe head request: HTTP ${response.code}")
                        return@withContext null
                    }
                    response.body.byteStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            if (temp.length() == 0L) {
                Log.w(TAG, "probe head: empty body")
                return@withContext null
            }
            fpsReader(temp)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun request(
        url: String,
        headers: Map<String, String>,
        from: Long,
        to: Long
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-$to")
            .header("Accept", "*/*")
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) builder.header(key, value)
        }
        return builder.get().build()
    }

    /**
     * The declared video rate on the downloaded head, or null when the head has
     * no parsable video track. The extractor is released on every path, and the
     * whole read is wrapped so a malformed head is a "cannot say", not a crash.
     */
    private fun extractFps(file: File): Double? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                readFrameRate(format)?.let { return@runCatching it }
                // The video track is here and simply has no rate: a different
                // failure from "no video track at all", and the one a
                // mislabelled container produces.
                Log.i(TAG, "probe: video track has no frame-rate key (mime=$mime)")
                return@runCatching null
            }
            Log.i(TAG, "probe: head has no video track")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrNull()

    /**
     * `KEY_FRAME_RATE` is documented as an integer, but some extractors hand it
     * back as a float, so both are tried before giving up.
     */
    private fun readFrameRate(format: MediaFormat): Double? {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }
            .getOrNull()
            ?.let { return it.toDouble() }
        return runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
            .getOrNull()
    }
}
