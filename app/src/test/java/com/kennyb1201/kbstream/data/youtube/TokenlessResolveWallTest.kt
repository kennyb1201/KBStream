package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenless-resolve wall: who gets a second mint, and what "tokenless" means.
 *
 * The 2026-11-03 phone capture is the shape these decisions exist for - a cold
 * WebView spent the whole GVS budget (`pot.gvs: state=failed:timeout
 * latency=6008ms`, `pot.player: state=none`), the resolve succeeded tokenless,
 * and the adaptive source it mounted died at the same spot on every surface. Two
 * answers follow from that: one more GVS mint on the now-warm session, and - when
 * even that yields nothing - a source that is NOT mounted.
 *
 * Both answers have to stay scoped to a COLD MINT. A resolve that is tokenless by
 * configuration (minting off, or no visitor to bind a token to) is exactly what
 * the app shipped before this spec, and must not change shape because a minter it
 * never asked for said nothing.
 *
 * The arithmetic is here rather than in a source-contract test because it is the
 * part that can be wrong without the app looking wrong: a re-mint that ignores
 * the extractor's budget turns one slow resolve into the fresh-config retry loop.
 */
class TokenlessResolveWallTest {

    // ── the budget a second mint may spend ────────────────────────────────────

    @Test
    fun `a second mint never gets more than a first one would`() {
        // Nothing spent yet: the cap is a full GVS budget, not the extractor's.
        assertEquals(
            InnerTubeExtractor.GVS_TOKEN_BUDGET_MS,
            InnerTubeExtractor.remintBudgetMs(elapsedMs = 0L)
        )
    }

    @Test
    fun `a second mint only gets what the extractor has left`() {
        // The first attempt's 6 s GVS mint timed out and the 4 s player mint with
        // it: 10 s of the 15 s pass is gone, so the second mint gets ~5 s.
        assertEquals(5_000L, InnerTubeExtractor.remintBudgetMs(elapsedMs = 10_000L))
    }

    @Test
    fun `a spent budget yields no second mint at all`() {
        // Past the extractor's own cap the pass is about to be cancelled, and a
        // mint started here would push the resolve into the fresh-config retry.
        assertEquals(0L, InnerTubeExtractor.remintBudgetMs(elapsedMs = 15_000L))
        assertEquals(0L, InnerTubeExtractor.remintBudgetMs(elapsedMs = 40_000L))
    }

    @Test
    fun `a clock that reports nothing spent cannot buy a longer mint`() {
        // System.nanoTime can only grow, but the rule must hold even for a
        // nonsense reading: the answer is still capped by the GVS budget.
        assertEquals(
            InnerTubeExtractor.GVS_TOKEN_BUDGET_MS,
            InnerTubeExtractor.remintBudgetMs(elapsedMs = -5_000L)
        )
    }

    // ── who gets one ─────────────────────────────────────────────────────────

    @Test
    fun `a cold mint is the case a second attempt can still save`() {
        assertTrue(
            InnerTubeExtractor.shouldRemintGvs(
                gvsToken = null,
                visitorData = "visitor-abc",
                mintingEnabled = true,
                remainingBudgetMs = 5_000L
            )
        )
    }

    @Test
    fun `a token already in hand is not minted twice`() {
        assertFalse(
            InnerTubeExtractor.shouldRemintGvs(
                gvsToken = "pot-token",
                visitorData = "visitor-abc",
                mintingEnabled = true,
                remainingBudgetMs = 6_000L
            )
        )
    }

    @Test
    fun `minting off is tokenless by configuration, not a cold mint`() {
        assertFalse(
            InnerTubeExtractor.shouldRemintGvs(
                gvsToken = null,
                visitorData = "visitor-abc",
                mintingEnabled = false,
                remainingBudgetMs = 6_000L
            )
        )
    }

    @Test
    fun `no visitor is tokenless by configuration too`() {
        assertFalse(
            InnerTubeExtractor.shouldRemintGvs(
                gvsToken = null,
                visitorData = null,
                mintingEnabled = true,
                remainingBudgetMs = 6_000L
            )
        )
        assertFalse(
            InnerTubeExtractor.shouldRemintGvs(
                gvsToken = null,
                visitorData = "   ",
                mintingEnabled = true,
                remainingBudgetMs = 6_000L
            )
        )
    }

    @Test
    fun `a budget too small to finish is not spent`() {
        // The mint's own script window is ~5 s; below the floor the attempt can
        // only delay the fallback the resolve is about to walk.
        assertFalse(
            InnerTubeExtractor.shouldRemintGvs(
                gvsToken = null,
                visitorData = "visitor-abc",
                mintingEnabled = true,
                remainingBudgetMs = InnerTubeExtractor.MIN_REMINT_BUDGET_MS - 1
            )
        )
        assertTrue(
            InnerTubeExtractor.shouldRemintGvs(
                gvsToken = null,
                visitorData = "visitor-abc",
                mintingEnabled = true,
                remainingBudgetMs = InnerTubeExtractor.MIN_REMINT_BUDGET_MS
            )
        )
    }

    // ── what the winner is judged by ─────────────────────────────────────────

    @Test
    fun `a token still missing after the re-mint is a cold mint`() {
        assertTrue(
            InnerTubeExtractor.isColdMintTokenless(
                gvsToken = null,
                visitorData = "visitor-abc",
                mintingEnabled = true
            )
        )
    }

    @Test
    fun `minting off and no visitor are never cold-mint tokenless`() {
        assertFalse(
            "minting off is a deliberate tokenless resolve",
            InnerTubeExtractor.isColdMintTokenless(
                gvsToken = null,
                visitorData = "visitor-abc",
                mintingEnabled = false
            )
        )
        assertFalse(
            "no visitor means there was nothing to bind a token to",
            InnerTubeExtractor.isColdMintTokenless(
                gvsToken = null,
                visitorData = null,
                mintingEnabled = true
            )
        )
        assertFalse(
            "a token in hand is not tokenless at all",
            InnerTubeExtractor.isColdMintTokenless(
                gvsToken = "pot-token",
                visitorData = "visitor-abc",
                mintingEnabled = true
            )
        )
    }

    // ── the answer is only ever about the ADAPTIVE winner ────────────────────

    @Test
    fun `a resolve is not flagged cold-mint tokenless unless the adaptive pick says so`() {
        // The default stays false: every resolve return passes the flag
        // explicitly (see TokenlessResolveWiringContractTest), so a caller that
        // builds a ResolvedSource by hand still gets "mountable".
        val source = PlayableSource.Muxed(url = "https://googlevideo.example/hls")
        assertFalse(InnerTubeExtractor.ResolvedSource(source, "android").potlessByColdMint)
        assertTrue(
            InnerTubeExtractor.ResolvedSource(
                source,
                "android",
                potlessByColdMint = true
            ).potlessByColdMint
        )
    }
}
