package com.kennyb1201.kbstream.ui.player

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addon subtitle bulk path, pinned where it can be seen.
 *
 * Field: up to 20 addon subtitle tracks were downloaded STRICTLY SERIALLY -
 * one blocking OkHttp call at a time - and nothing attached until every track
 * resolved. One dead host (`nebula.bright67.online` refused) therefore held
 * every working track hostage: the six good tracks in the log could not appear
 * until all fourteen doomed attempts had drained, one at a time. The same host
 * was then re-attempted once per track, there was no total call timeout, and
 * an all-failed batch said nothing at all outside a Log.w.
 *
 * The refused connection itself is network-side and not fixable here; this is
 * about the app-side handling around it. There is no device in CI, so the
 * structure is read out of the source the way this tree's other contracts are
 * - except the failure classification, which is a real unit test.
 */
class AddonSubtitleBulkDownloadContractTest {

    private val source: String by lazy { read(ADDON_SUBTITLES) }

    /**
     * Just the [AddonSubtitleController.attachTracks] body, so a `contains`
     * cannot be satisfied by some other method in the file.
     */
    private val attach: String by lazy {
        source.substringAfter("private fun attachTracks(player: Player)")
            .substringBefore("fun assSourceFor(")
    }

    // --- §1 bounded concurrency -------------------------------------------

    @Test
    fun `the batch downloads concurrently, bounded, not strictly serially`() {
        assertTrue("the batch must fan out", attach.contains("offers.map { offer ->"))
        assertTrue("one task per offer", attach.contains("async {"))
        assertTrue("awaitAll keeps offer order", attach.contains("awaitAll()"))
        assertTrue(
            "under a BOUNDED parallelism, not unbounded: one task per track against one host is " +
                "twenty sockets at once",
            attach.contains("Semaphore(BULK_DOWNLOAD_PARALLELISM)") &&
                attach.contains(".withPermit {")
        )
        assertTrue("and the parallelism bound must be declared", source.contains("BULK_DOWNLOAD_PARALLELISM = 4"))
    }

    @Test
    fun `one track's failure cannot discard its siblings' downloads`() {
        assertTrue(
            "the batch must run in a supervisorScope",
            attach.contains("supervisorScope {")
        )
        assertTrue(
            "and each offer's work must be caught and turned into a null: with a plain async, a " +
                "throw reaches awaitAll() and discards every other track's completed download",
            attach.contains("runCatchingCancellable {") && attach.contains("}.getOrNull()")
        )
        assertTrue(
            "nulls are dropped from the batch",
            attach.contains(".awaitAll().filterNotNull()")
        )
    }

    @Test
    fun `the shared download cache is thread-safe now that four callers touch it`() {
        assertTrue(
            "a plain mutableMapOf touched from four coroutines is a data race, and a corrupted " +
                "cache hands a track the wrong file",
            source.contains("private val downloadCache = ConcurrentHashMap<String, Uri>()")
        )
    }

    @Test
    fun `all successes still attach in a single rebuild`() {
        val items = Regex("setMediaItem\\(").findAll(attach).count()
        val prepares = Regex("\\.prepare\\(\\)").findAll(attach).count()
        assertTrue("exactly one setMediaItem for the whole batch (found $items)", items == 1)
        assertTrue("and exactly one prepare() (found $prepares)", prepares == 1)
    }

    @Test
    fun `the bulk result reaches the UI on the main thread`() {
        assertTrue(
            "the callback must exist as a nullable hook, so a controller with no listener behaves " +
                "exactly as before",
            source.contains("var onBulkResult: ((succeeded: Int, failed: Int) -> Unit)? = null")
        )
        assertTrue(
            "and it must report how many offers resolved and how many did not",
            attach.contains("onBulkResult?.invoke(configs.size, offers.size - configs.size)")
        )
        val beforeInvoke = attach.substringBefore("onBulkResult?.invoke")
        val mainBlock = beforeInvoke.substringAfterLast("withContext(Dispatchers.Main) {")
        assertTrue(
            "the invoke must be the first statement of a Dispatchers.Main block: the listener " +
                "owns views",
            mainBlock.isBlank()
        )
        assertTrue(
            "and it must report BEFORE the empty-batch early return, or the all-failed case - the " +
                "one that used to be silent - is exactly the case that never reports",
            attach.indexOf("onBulkResult?.invoke") < attach.indexOf("if (configs.isEmpty()) return@launch")
        )
    }

    // --- §3/§4 dead hosts and timeouts ------------------------------------

    @Test
    fun `the download client bounds a stalled call, not just a stalled read`() {
        assertTrue(
            "connect and read timeouts are per-read: an accept-then-stall server can hold a call " +
                "(and one of the bulk permits) indefinitely without a total call timeout",
            source.contains("callTimeout(30, TimeUnit.SECONDS)")
        )
    }

    @Test
    fun `a dead host is memoized, skipped before the call, and released when it expires`() {
        assertTrue(
            "the memo must be process-wide safe",
            source.contains("private val deadHosts = ConcurrentHashMap<String, Long>()")
        )
        assertTrue(
            "with a TTL, so a host that recovers is not blackholed for the life of the process",
            source.contains("private const val DEAD_HOST_TTL_MS = 10 * 60 * 1000L")
        )
        assertTrue(
            "the skip must be checked BEFORE the request is issued",
            source.indexOf("Skipping recently-dead subtitle host:") <
                source.indexOf("downloadClient.newCall(request)")
        )
        assertTrue(
            "an expired entry is dropped so the host gets another chance",
            source.contains("deadHosts.remove(requestHost)")
        )
        assertTrue(
            "and a failure records the host",
            source.contains("deadHosts[failedHost] = SystemClock.elapsedRealtime()")
        )
    }

    @Test
    fun `only a failure to establish the connection blackholes a host`() {
        assertTrue(
            "the catch must gate the memo on the classification, or an HTTP 500 hides the rest " +
                "of that host's subtitles",
            source.contains("if (failedHost != null && isConnectionFailure(e)) {")
        )
    }

    @Test
    fun `the failure classification is the dead-host set`() {
        assertTrue(AddonSubtitleSource.isConnectionFailure(ConnectException("refused")))
        assertTrue(AddonSubtitleSource.isConnectionFailure(UnknownHostException("no dns")))
        assertTrue(AddonSubtitleSource.isConnectionFailure(SSLException("handshake")))
        assertTrue(
            "a host that takes the connection and then never answers is the shape the memo is " +
                "for: without it that host is re-attempted once per track of every picker open",
            AddonSubtitleSource.isConnectionFailure(SocketTimeoutException("read timed out"))
        )
        assertFalse(
            "an IO failure after the connection is up is the track's problem, not the host's",
            AddonSubtitleSource.isConnectionFailure(IOException("http 500"))
        )
        assertFalse(AddonSubtitleSource.isConnectionFailure(RuntimeException("corrupt body")))
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val ADDON_SUBTITLES = "com/kennyb1201/kbstream/ui/player/AddonSubtitles.kt"
    }
}
