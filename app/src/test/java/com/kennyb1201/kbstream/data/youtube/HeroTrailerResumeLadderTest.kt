package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero trailer's resume ladder, driven directly.
 *
 * The 2026-10-28 field capture is a fixed-position death on an `android_vr`-signed
 * adaptive source: every offset read from that URL class is refused, so one
 * exclusion answers exactly one class and the next refusal needs the next class
 * excluded. That makes three questions arithmetic rather than judgement, and they
 * are pinned here: how the set grows, when it is exhausted, and when a video is
 * blocked instead of resumed.
 *
 * Poisoning must be RARE and correct: it is what makes a video go straight to the
 * backdrop for the rest of the session, so it may only be a verdict of the ladder
 * (no client left, or the cap spent) - never the mere fact of a second death. A
 * null signing client is the shape the set cannot bound, which is why the cap
 * exists at all.
 */
class HeroTrailerResumeLadderTest {

    /** The five clients that can sign a source (`YouTubeClients.all`'s keys). */
    private val clients = setOf("android", "tv", "ios", "web_embedded", "android_vr")

    private fun decide(
        deadClient: String?,
        excluded: Set<String> = emptySet(),
        attempts: Int = 0
    ) = heroTrailerResumeDecision(
        deadClient = deadClient,
        excludedClients = excluded,
        resumeAttempts = attempts,
        allClientKeys = clients
    )

    @Test
    fun `the first death of a known client excludes it and resumes`() {
        val decision = decide("android_vr")

        assertEquals(HeroTrailerResumeVerdict.RESUME, decision.verdict)
        assertEquals(setOf("android_vr"), decision.nextExcludedClients)
    }

    @Test
    fun `a second death ADDS to the set, never replaces it`() {
        val second = decide("tv", excluded = setOf("android_vr"), attempts = 1)

        assertEquals(HeroTrailerResumeVerdict.RESUME, second.verdict)
        assertEquals(
            "the class excluded on the first step is still refused on the second",
            setOf("android_vr", "tv"),
            second.nextExcludedClients
        )
    }

    @Test
    fun `the ladder is exhausted when the set covers every client`() {
        val decision = decide(
            deadClient = "android_vr",
            excluded = clients - "android_vr",
            attempts = 4
        )

        assertEquals(HeroTrailerResumeVerdict.POISON, decision.verdict)
        assertEquals(clients, decision.nextExcludedClients)
    }

    @Test
    fun `the cap bounds the one case the set cannot - an unknown signing client`() {
        assertTrue(
            "the cap is the ladder size minus the initial play: five clients, one " +
                "excluded per step, four steps",
            MAX_HERO_TRAILER_RESUMES == 4
        )
        val unknownBelowCap = decide(deadClient = null, attempts = MAX_HERO_TRAILER_RESUMES - 1)
        assertEquals(
            "an unknown client grows nothing, so only the cap can stop it",
            HeroTrailerResumeVerdict.RESUME,
            unknownBelowCap.verdict
        )
        assertEquals(emptySet<String>(), unknownBelowCap.nextExcludedClients)

        val unknownAtCap = decide(deadClient = null, attempts = MAX_HERO_TRAILER_RESUMES)
        assertEquals(HeroTrailerResumeVerdict.POISON, unknownAtCap.verdict)
    }

    @Test
    fun `a blank signing client is unknown too`() {
        val decision = decide(deadClient = "  ")

        assertEquals(HeroTrailerResumeVerdict.RESUME, decision.verdict)
        assertTrue("a blank key must not enter the set", decision.nextExcludedClients.isEmpty())
    }

    @Test
    fun `only a walked ladder poisons on a resolve that found nothing`() {
        assertFalse(
            "a first-attempt resolve failure is ordinary - it keeps its retry",
            heroTrailerResolveFailurePoisons(emptySet())
        )
        assertTrue(
            "no source from any remaining client IS the ladder running out",
            heroTrailerResolveFailurePoisons(setOf("android_vr"))
        )
    }
}
