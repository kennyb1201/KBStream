package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the resolve gets the visitor it presents, and what adoption is NOT
 * allowed to change.
 *
 * The behavioral half is [PoTokenProviderTest] / [PoTokenWarmTest]: a mint whose
 * page presents another visitor is adopted, cached under the page's identity and
 * translated on lookup. This pins the half no fake mint can reach - the request
 * itself. The field capture behind the change is `pot.player: state=minted
 * bind=mismatch spent=no source=warm` (0.9.25, Onn 4K Plus): both tokens of a
 * successful mint withheld because the page presented the visitor YouTube issued
 * it rather than the one scraped from a cookie-less fetch. Discarding them was
 * structural, so if this wiring is dropped later the resolve silently goes
 * tokenless again and nothing fails.
 *
 * It also pins the things the spec froze: one point of entry for the visitor in
 * the player request, unchanged budgets, the unchanged LOGIN_REQUIRED
 * degradation, and no surviving withhold rule.
 */
class PoTokenAdoptionWiringContractTest {

    private val extractor by lazy { read(YOUTUBE + "InnerTubeExtractor.kt") }

    private val provider by lazy { read(YOUTUBE + "WebViewPoTokenProvider.kt") }

    private val trace by lazy { read(YOUTUBE + "PoTokenTrace.kt") }

    @Test
    fun `the resolve presents the token's identity, not the scrape`() {
        val extract = extractInternal()

        assertTrue(
            "the request visitor must be the effective one: the page's identity when the " +
                "mint adopted it, the scrape only as the fallback",
            extract.contains("val visitor = presentedVisitor(config.visitorData, tokens.visitor)")
        )
        assertTrue(
            "and every client's player request must present it",
            extract.contains("visitorData = visitor,")
        )
        assertFalse(
            "the scraped visitor must not reach the request directly any more",
            extract.contains("visitorData = config.visitorData,")
        )
    }

    @Test
    fun `the effective visitor is asked for after the mint`() {
        val mint = slice(
            extractor,
            "private suspend fun mintTokens(",
            "suspend fun prewarmPoTokens("
        )

        val gvsMint = mint.indexOf("provider.gvsToken(videoId, visitorData)")
        val effective = mint.indexOf("provider.effectiveVisitor(visitorData)")
        assertTrue("the mint must still be the thing that loads the page", gvsMint >= 0)
        assertTrue(
            "the identity is a provider seam, not something the extractor derives",
            effective >= 0
        )
        assertTrue(
            "a page identity is only known once the mint has run, so it is asked for after",
            gvsMint < effective
        )
        assertTrue(
            "and it rides back with the tokens",
            mint.contains("ResolveTokens(player, gvs, visitor)")
        )
    }

    @Test
    fun `the visitor still enters the player request at exactly one place`() {
        val request = slice(
            extractor,
            "private suspend fun fetchPlayerResponse(",
            "// ── HLS parsing"
        )

        assertEquals(
            "one header, so 'present the token's identity' stays a single-point change",
            1,
            Regex("x-goog-visitor-id").findAll(request).count()
        )
        val payload = slice(
            extractor,
            "internal fun playerRequestPayload(",
            "private suspend fun fetchPlayerResponse("
        )
        assertFalse(
            "the payload context carries no visitor, and must not start to",
            payload.contains("visitorData")
        )
    }

    @Test
    fun `the withhold rule is gone and adoption is the rule`() {
        assertTrue(
            "the page's visitor is recorded against the request's",
            provider.contains("bindings[requestVisitor] = effective")
        )
        assertTrue(
            "and the trace names the adoption rather than a refusal",
            provider.contains("PoTokenState.BIND_ADOPTED")
        )
        // Comments keep the field history (`bind=mismatch spent=no`), so the scan
        // is over the code: no refusal branch and no withheld token may return.
        val code = code(provider)
        assertFalse(
            "no visitor mismatch may be reported as a binding outcome again",
            code.contains("mismatch")
        )
        assertFalse(
            "and nothing may withhold a minted token",
            code.contains("withholding") || code.contains("spent = false")
        )
        assertTrue(
            "the trace states the spend both ways, so 'was it actually used?' needs no " +
                "inference",
            trace.contains("spent=\${if (spent) \"yes\" else \"no\"}")
        )

        val warm = slice(provider, "override suspend fun warmUp(", "override fun releaseWarm()")
        assertTrue(
            "the pre-warm caches through the same adopt",
            warm.contains("adopt(")
        )
        assertTrue(
            "but claims no spend: the resolve it is preparing for has not happened yet",
            warm.contains("spent = null")
        )
    }

    @Test
    fun `an adopted identity that gets LOGIN_REQUIRED still degrades to the tokenless path`() {
        val extract = extractInternal()

        assertTrue(
            "the all-clients refusal is still what invalidates the identity, adopted or not",
            extract.contains("if (loginRequiredCount == YouTubeClients.all.size)") &&
                extract.contains("invalidateConfig()")
        )
        assertTrue(
            "and the resolve then returns null exactly as the tokenless path does",
            Regex("invalidateConfig\\(\\)\\s*\\n\\s*return null").containsMatchIn(extract)
        )
        val invalidate = slice(extractor, "fun invalidateConfig()", "// ── Player API")
        assertTrue(
            "and the identity's tokens are still dropped with it",
            invalidate.contains("PoTokens.invalidate()")
        )

        val providerInvalidate = slice(
            provider,
            "override fun invalidate()",
            "private fun elapsedMs("
        )
        assertTrue(providerInvalidate.contains("gvsTokens.clear()"))
        assertTrue(
            "the binding goes too: a refused identity must not keep steering the next " +
                "resolve at the visitor whose session was refused",
            providerInvalidate.contains("bindings.clear()")
        )
    }

    @Test
    fun `adoption adds no budget and no second request`() {
        assertEquals(
            "two mint windows, as before: the resolve's and the pre-warm's",
            2,
            Regex("withTimeout\\(").findAll(provider).count()
        )
        assertEquals(5_000L, WebViewPoTokenMinter.MINT_SCRIPT_FALLBACK_MS)
        assertEquals(6_000L, InnerTubeExtractor.GVS_TOKEN_BUDGET_MS)
        assertEquals(10_000L, WebViewPoTokenProvider.DEFAULT_MINT_TIMEOUT_MS)
        assertEquals(
            "one player request per client, still",
            1,
            Regex("fetchPlayerResponse\\(").findAll(extractInternal()).count()
        )
    }

    private fun extractInternal(): String = slice(
        extractor,
        "private suspend fun extractInternal(",
        "private data class ResolveTokens("
    )

    /** The source without its comments, for "this code path is gone" assertions. */
    private fun code(source: String): String = source
        .lineSequence()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString("\n")

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
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
        const val YOUTUBE = "com/kennyb1201/kbstream/data/youtube/"
    }
}
