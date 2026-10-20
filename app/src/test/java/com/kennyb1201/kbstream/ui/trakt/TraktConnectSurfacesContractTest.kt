package com.kennyb1201.kbstream.ui.trakt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two surfaces the connect screens gained, pinned where they belong.
 *
 * The QR exists only while linking: the connected state has no code left to
 * enter, and drawing a scanner beside a finished account would be noise. The
 * counts row exists only on Trakt's screen, is fed by a null-hiding read, and
 * is requested once per screen entry rather than per recomposition. Simkl's
 * counts stay exactly as they were - this spec added a QR to it, not a redesign.
 *
 * Read out of source for the placement rules (a Compose render needs a host and
 * an activity), with the bitmap itself exercised directly in
 * `QrCodeEncodingTest` and the count rule in `TraktWatchedCountsTest`.
 */
class TraktConnectSurfacesContractTest {

    private val traktTv = source("ui/trakt/TraktConnectScreen.kt")
    private val traktMobile = source("ui/mobile/TraktConnectScreenMobile.kt")
    private val simklTv = source("ui/simkl/SimklConnectScreen.kt")
    private val simklMobile = source("ui/mobile/SimklConnectScreenMobile.kt")
    private val viewModel = source("ui/trakt/TraktViewModel.kt")
    private val repository = source("data/trakt/TraktRepository.kt")

    @Test
    fun `both Trakt screens draw the QR only inside the linking branch`() {
        listOf("TV" to traktTv, "mobile" to traktMobile).forEach { (name, source) ->
            assertEquals("$name: exactly one QR, in the linking state", 1, occurrences(source, "KBQrCode("))

            val qr = source.indexOf("KBQrCode(")
            val opening = source.lastIndexOf("verificationUrl?.let", qr)
            assertTrue(
                "$name: the QR must sit inside the verificationUrl?.let block",
                opening in 0 until qr
            )
            val between = source.substring(opening, qr)
            assertFalse(
                "$name: the QR must not also be reachable from the connected branch",
                between.contains("isConnected")
            )
        }
    }

    @Test
    fun `both Simkl screens draw the QR only inside the linking branch`() {
        listOf("TV" to simklTv, "mobile" to simklMobile).forEach { (name, source) ->
            assertEquals("$name: exactly one QR, in the linking state", 1, occurrences(source, "KBQrCode("))

            val qr = source.indexOf("KBQrCode(")
            val opening = source.lastIndexOf("verificationUrl?.let", qr)
            assertTrue(
                "$name: the QR must sit inside the verificationUrl?.let block",
                opening in 0 until qr
            )
            val between = source.substring(opening, qr)
            assertFalse(
                "$name: the QR must not also be reachable from the connected branch",
                between.contains("isConnected")
            )
        }
    }

    @Test
    fun `Trakt's counts row is hidden when the read answers nothing`() {
        listOf("TV" to traktTv, "mobile" to traktMobile).forEach { (name, source) ->
            assertTrue(
                "$name: the counts row must be gated on a nullable read",
                source.contains("uiState.watchedCounts?.let")
            )
            assertTrue(
                "$name: and drawn through the shared two-tile row",
                source.contains("TraktWatchedCountsRow(counts = counts)")
            )
            assertFalse(
                "$name: an absent read must not become a zero row",
                source.contains("watchedCounts ?: TraktWatchedCounts")
            )
        }
    }

    @Test
    fun `the counts read happens once per screen entry`() {
        listOf("TV" to traktTv, "mobile" to traktMobile).forEach { (name, source) ->
            val effect = source.substringAfter("LaunchedEffect(uiState.isConnected)")
            assertTrue(
                "$name: the entry effect is what asks for the counts",
                effect.contains("vm.refreshWatchedCounts()")
            )
            assertFalse(
                "$name: the fetch must not run from composition itself",
                source.contains("kotlinx.coroutines.launch") ||
                    source.contains("GlobalScope")
            )
        }
    }

    @Test
    fun `the view model asks once and keeps a failure as null`() {
        val refresh = viewModel
            .substringAfter("fun refreshWatchedCounts()")
            .substringBefore("fun startConnect()")

        assertEquals("one read per request, not a poll", 1, occurrences(refresh, "repository.watchedCounts()"))
        assertTrue("a failure is taken as null", refresh.contains("getOrNull()"))
        assertTrue(
            "and stored as the row-hiding null",
            refresh.contains("watchedCounts = counts")
        )
        assertTrue(
            "a disconnect must not be followed by the old account's tiles",
            viewModel.substringAfter("fun disconnect()").contains("countsJob?.cancel()")
        )
    }

    @Test
    fun `the repository spends one budget slot on the pair and hides half answers`() {
        val body = repository
            .substringAfter("suspend fun watchedCounts()")
            .substringBefore("suspend fun playbackSessions()")

        assertTrue(body.contains("api.watchedMovies(authorization)"))
        assertTrue(body.contains("api.watchedShows(authorization)"))
        assertEquals("one slot for the pair", 1, occurrences(body, "guard.tryAcquire()"))
        assertTrue(
            "a failed response must be passed as null, not as an empty list",
            body.contains("isSuccessful == true")
        )
    }

    @Test
    fun `Simkl's counts rows are untouched`() {
        assertTrue("TV Simkl still draws its own two-tile row", simklTv.contains("SimklWatchedCountsRow(counts = counts)"))
        assertTrue("mobile Simkl still draws its own tiles", simklMobile.contains("SimklCountTile("))
        assertFalse("and it did not become Trakt's row", simklTv.contains("TraktWatchedCountsRow"))
        assertFalse(simklMobile.contains("TraktWatchedCountsRow"))
    }

    private fun occurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    private fun source(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
