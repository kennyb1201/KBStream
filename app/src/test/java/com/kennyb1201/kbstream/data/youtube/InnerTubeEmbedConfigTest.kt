package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the watch config is minted.
 *
 * The visitor data used to be scraped from a hardcoded desktop watch page
 * (`watch?v=dQw4w9WgXcQ`, Chrome/133 TV UA) and then handed to the mobile and
 * VR client contexts - identities that never minted it, on a video unrelated to
 * the one being played. The config is now minted on the video's own EMBED page,
 * the page whose context (WEB_EMBEDDED_PLAYER, with `thirdParty.embedUrl`) is
 * the one built for third-party playback.
 *
 * The watch page stays as the fallback for an embed page that does not deliver
 * a key, which is the only behavior that has to stay exactly as it was.
 */
class InnerTubeEmbedConfigTest {

    @Test
    fun `the minted page is the video's own embed page`() {
        assertEquals(
            "https://www.youtube.com/embed/gc0t8AfMP6k",
            youtubeEmbedUrl("gc0t8AfMP6k")
        )
    }

    @Test
    fun `an embed page's ytcfg yields the key and the visitor`() {
        val scraped = InnerTubeExtractor.scrapeConfig(EMBED_PAGE)

        assertEquals(EMBED_KEY, scraped.apiKey)
        assertEquals(EMBED_VISITOR, scraped.visitorData)
    }

    @Test
    fun `the embed page wins over the watch page`() {
        val minted = InnerTubeExtractor.mintedConfig(
            InnerTubeExtractor.scrapeConfig(EMBED_PAGE),
            InnerTubeExtractor.scrapeConfig(WATCH_PAGE)
        )

        assertEquals(EMBED_KEY, minted.apiKey)
        assertEquals(EMBED_VISITOR, minted.visitorData)
    }

    @Test
    fun `a page missing the key falls back to the watch page`() {
        val minted = InnerTubeExtractor.mintedConfig(
            InnerTubeExtractor.scrapeConfig(EMBED_PAGE_WITHOUT_KEY),
            InnerTubeExtractor.scrapeConfig(WATCH_PAGE)
        )

        assertEquals(WATCH_KEY, minted.apiKey)
        assertEquals(WATCH_VISITOR, minted.visitorData)
    }

    @Test
    fun `a failed embed fetch falls back to the watch page`() {
        val minted = InnerTubeExtractor.mintedConfig(
            InnerTubeExtractor.scrapeConfig(null),
            InnerTubeExtractor.scrapeConfig(WATCH_PAGE)
        )

        assertEquals(WATCH_KEY, minted.apiKey)
        assertEquals(WATCH_VISITOR, minted.visitorData)
    }

    @Test
    fun `no page at all mints nothing`() {
        val minted = InnerTubeExtractor.mintedConfig(
            InnerTubeExtractor.scrapeConfig(null),
            null
        )

        assertNull(minted.apiKey)
        assertNull(minted.visitorData)
    }

    @Test
    fun `a page without a visitor still yields its key`() {
        // The visitor is allowed to be absent (some pages omit it); the key is
        // what the player request cannot do without.
        val scraped = InnerTubeExtractor.scrapeConfig("""ytcfg.set({"INNERTUBE_API_KEY":"$WATCH_KEY"});""")

        assertEquals(WATCH_KEY, scraped.apiKey)
        assertNull(scraped.visitorData)
    }

    @Test
    fun `the embed page is fetched before the watch page`() {
        // Wiring, read from the source: the mint has to try the embed page
        // first and reach for the watch page only in the fallback branch.
        val source = extractorSource.substringAfter("private suspend fun ensureWatchConfig(")
        val scrapeAt = source.indexOf("scrapeConfig(")
        val embedAt = source.indexOf("performRequest(url = embedUrl")
        val watchAt = source.indexOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertTrue("the embed page must be scraped", scrapeAt >= 0)
        assertTrue("the embed fetch must be the first source", embedAt > scrapeAt)
        assertTrue("the watch-page fallback must still exist", watchAt > embedAt)
        assertTrue(
            "and it must be reached only when the embed page had no key",
            source.contains("if (embedConfig.apiKey.isNullOrBlank()) {")
        )
        assertTrue(
            "the fallback branch is the one that fetches the watch page",
            watchAt > source.indexOf("if (embedConfig.apiKey.isNullOrBlank()) {")
        )
    }

    @Test
    fun `the config TTL and invalidation are unchanged`() {
        assertTrue(
            extractorSource.contains(
                "private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours"
            )
        )
        assertTrue(extractorSource.contains("Log.w(TAG, \"Watch config invalidated\")"))
    }

    /** The extractor's own source, whitespace flattened for the wiring checks. */
    private val extractorSource: String by lazy {
        val relative = "src/main/java/com/kennyb1201/kbstream/data/youtube/InnerTubeExtractor.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return@lazy candidate.readText().replace(Regex("\\s+"), " ")
            }
            dir = dir.parentFile
        }
        throw AssertionError("InnerTubeExtractor.kt not found walking up from the project root")
    }

    private companion object {
        const val EMBED_KEY = "AIzaSyEmbedPageKeyForTheFixture0000000"
        const val EMBED_VISITOR = "CgtlbWJlZHZpc2l0b3IiEwoLZ2MwdDhBZk1QNms"

        const val WATCH_KEY = "AIzaSyWatchPageKeyForTheFixture000000"
        const val WATCH_VISITOR = "Cgt3YXRjaHZpc2l0b3IiEwoLZ2MwdDhBZk1QNms"

        /** ytcfg.set(...) bootstrap of an embed page, trimmed to what is read. */
        val EMBED_PAGE = """
            <!DOCTYPE html><html><head><script nonce="x">ytcfg.set({
              "INNERTUBE_API_KEY":"$EMBED_KEY",
              "INNERTUBE_CONTEXT":{"client":{"clientName":"WEB_EMBEDDED_PLAYER","clientVersion":"1.20260708.01.00","visitorData":"$EMBED_VISITOR"}},
              "VISITOR_DATA":"$EMBED_VISITOR"
            });</script></head><body></body></html>
        """.trimIndent()

        /** An embed page that loaded but carries no player key. */
        val EMBED_PAGE_WITHOUT_KEY = """
            <!DOCTYPE html><html><head><script nonce="x">ytcfg.set({
              "INNERTUBE_CONTEXT":{"client":{"clientName":"WEB_EMBEDDED_PLAYER","clientVersion":"1.20260708.01.00"}}
            });</script></head><body></body></html>
        """.trimIndent()

        /** The watch page that was the only source before this change. */
        val WATCH_PAGE = """
            <!DOCTYPE html><html><head><script nonce="y">ytcfg.set({
              "INNERTUBE_API_KEY":"$WATCH_KEY",
              "VISITOR_DATA":"$WATCH_VISITOR"
            });</script></head><body></body></html>
        """.trimIndent()
    }
}
