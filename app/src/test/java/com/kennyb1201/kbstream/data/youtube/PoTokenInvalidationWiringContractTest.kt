package com.kennyb1201.kbstream.data.youtube

import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The wiring that makes the token layer an enhancement rather than a new
 * failure mode.
 *
 * Four things have to hold, and none of them is visible from the provider
 * alone:
 *
 *  - an identity-blocked failure (403 + googlevideo) clears the token caches
 *    alongside the watch config, so a refused token is never spent twice;
 *  - the extractor is the single place both invalidations happen, so every
 *    existing 403 path reaches them;
 *  - the app installs the real provider, without which the extractor would
 *    silently keep running with the no-op one;
 *  - the kill switch exists, defaults ON, and is device-local.
 *
 * Mirrors the `90abd41e` contract tests: the invalidation is read out of the
 * source, and the behavior itself is driven through the real call site.
 */
class PoTokenInvalidationWiringContractTest {

    @Before
    fun setUp() {
        PoTokens.resetForTest()
        PoTokenTrace.clear()
    }

    @After
    fun tearDown() {
        PoTokens.resetForTest()
        PoTokenTrace.clear()
    }

    @Test
    fun `an identity-blocked invalidation clears the token caches`() = runBlocking {
        val minter = CountingMinter()
        PoTokens.install(WebViewPoTokenProvider(minter))

        assertEquals("gvs", PoTokens.provider().gvsToken("v1", "vis"))
        assertEquals("player", PoTokens.provider().playerToken("vis"))
        assertEquals(1, minter.mints)

        // The exact call every 403 path already makes (HomeScreen's hero and
        // NativePlayerActivity's trailer branch both call it).
        InnerTubeExtractor.invalidateConfig()

        assertNull(
            "the player token was minted for the visitor just refused",
            PoTokens.provider().playerToken("vis")
        )
        assertEquals(
            "the stream token is minted again instead of being spent twice",
            "gvs",
            PoTokens.provider().gvsToken("v1", "vis")
        )
        assertEquals(2, minter.mints)
        assertTrue(PoTokenTrace.lines().any { it.contains("pot.cache: cleared") })
    }

    @Test
    fun `the extractor is the one place both invalidations happen`() {
        val source = flat(readSource(INNER_TUBE))
        val body = source.substringAfter("fun invalidateConfig() {")
            .substringBefore("Log.w(TAG, \"Watch config invalidated\")")

        assertTrue("the watch config still goes", body.contains("cachedConfig.set(null)"))
        assertTrue(
            "and the tokens it was minted for go with it",
            body.contains("PoTokens.invalidate()")
        )
    }

    @Test
    fun `the app installs the real provider`() {
        val app = flat(readSource(MAIN_APPLICATION))

        assertTrue(
            "without an install the extractor silently uses the no-op provider",
            app.contains("PoTokens.install(") &&
                app.contains("WebViewPoTokenProvider.from(")
        )
    }

    @Test
    fun `the kill switch defaults on and is device-local`() {
        val prefs = flat(readSource(APP_PREFERENCES))

        assertTrue(
            "the preference is named youtube_po_token_enabled",
            prefs.contains("KEY_YOUTUBE_PO_TOKEN_ENABLED = \"youtube_po_token_enabled\"")
        )
        assertTrue(
            "and it defaults ON: the layer degrades on its own",
            prefs.contains("KEY_YOUTUBE_PO_TOKEN_ENABLED, true")
        )
        // Device-local, like the decoder switches beside it: whether a box can
        // mint is a property of the box, so a TV's answer must not follow the
        // account to the phone.
        assertTrue(
            "the kill switch must not ride the sync payload",
            "youtube_po_token_enabled" in PrefsPayloadBuilder.EXCLUDED_PREF_KEYS
        )
        assertFalse(
            "and must never be synced",
            "youtube_po_token_enabled" in PrefsPayloadBuilder.SYNCED_PREF_KEYS
        )
    }

    @Test
    fun `the diagnostics report carries the token state`() {
        val diagnostics = flat(readSource(DIAGNOSTICS))

        assertTrue(
            "the report has to show a silent BotGuard break",
            diagnostics.contains("PoTokenTrace.lines().forEach { report.appendLine(it) }")
        )
    }

    @Test
    fun `the mint failure is logged at warning level`() {
        // Fire OS suppresses debug, and a mint that fails there is the exact
        // thing a field capture has to be able to read.
        val provider = flat(readSource(WEB_VIEW_PROVIDER))

        assertTrue(provider.contains("Log.w(") && provider.contains("PO token mint failed"))
        assertTrue(provider.contains("PO token cache cleared"))
    }

    @Test
    fun `the chunked data source is untouched by the token layer`() {
        // `pot` rides the signed URL: the chunked source already reopens the
        // exact URL it was handed, so any rewrite there would break the
        // signature the token is bound to.
        val chunked = flat(readSource(CHUNKED_SOURCE))

        assertTrue(
            "the chunked source must stay a byte-preserving pass-through",
            chunked.contains("The chunk request carries the ACTIVE client User-Agent") &&
                !chunked.contains("potDecorated(")
        )
    }

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

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

    private class CountingMinter : PoTokenMinter {
        var mints = 0

        override suspend fun mint(videoId: String, visitorData: String): MintedPoTokens? {
            mints++
            return MintedPoTokens("player", "gvs", visitorData)
        }
    }

    private companion object {
        const val YOUTUBE = "com/kennyb1201/kbstream/data/youtube/"
        const val INNER_TUBE = "${YOUTUBE}InnerTubeExtractor.kt"
        const val WEB_VIEW_PROVIDER = "${YOUTUBE}WebViewPoTokenProvider.kt"
        const val CHUNKED_SOURCE = "${YOUTUBE}YoutubeChunkedDataSourceFactory.kt"
        const val MAIN_APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val APP_PREFERENCES =
            "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
    }
}
