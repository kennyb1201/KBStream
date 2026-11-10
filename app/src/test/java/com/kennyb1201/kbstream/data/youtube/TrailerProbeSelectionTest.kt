package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe order: which candidates are tried, and in what order, before a
 * trailer is allowed to mount one.
 *
 * This is [pickBest]'s rule seen as a LIST rather than a head - the preferred
 * client's pool first, the rest of the pool after it - because the fix for the
 * trailers that died ~20 s in is "probe the top few and play the best one that
 * serves bytes", not "lower the bar for what plays". Every assertion here is
 * about that distinction: a lower-ranked candidate may only play when every
 * candidate above it failed its probe.
 */
class TrailerProbeSelectionTest {

    @Test
    fun `the preferred client's pool leads, ranked by score`() {
        val order = probeOrder(
            listOf(
                candidate("android", score = 10.0, url = "android-low"),
                candidate("android", score = 90.0, url = "android-high"),
                candidate("tv", score = 1.0e13, url = "tv-best")
            ),
            preferredClient = "android"
        )

        assertEquals(
            "a higher-scored source from another client cannot be probed before " +
                "the preferred client's own, exactly as it could not mount before it",
            listOf("android-high", "android-low", "tv-best"),
            order.map { it.url }
        )
    }

    @Test
    fun `with no preferred candidate the rest of the pool keeps its ranking`() {
        val order = probeOrder(
            listOf(
                candidate("tv", score = 10.0, url = "tv-low"),
                candidate("ios", score = 90.0, url = "ios-high")
            ),
            preferredClient = "android"
        )

        assertEquals(
            "demoted, not banned: the whole pool is ranked by score when the " +
                "preferred client has nothing",
            listOf("ios-high", "tv-low"),
            order.map { it.url }
        )
    }

    @Test
    fun `an excluded client is not probed at all`() {
        val order = probeOrder(
            listOf(
                candidate("android", score = 90.0, url = "android"),
                candidate("tv", score = 10.0, url = "tv")
            ),
            preferredClient = "android",
            excludeClients = setOf("android")
        )

        assertEquals(
            "the resume's exclusion is applied before the preference, so a " +
                "probe cannot re-offer the class that just died",
            listOf("tv"),
            order.map { it.url }
        )
    }

    @Test
    fun `the head of the probe order is still what plays`() {
        val pool = listOf(
            candidate("tv", score = 1.0e13, url = "tv-best"),
            candidate("android", score = 10.0, url = "android"),
            candidate("ios", score = 50.0, url = "ios")
        )

        assertEquals(
            "pickBest and the probe order cannot disagree: one is the other's head",
            pickBest(pool, preferredClient = "android"),
            probeOrder(pool, preferredClient = "android").first()
        )
    }

    @Test
    fun `the throttle parameter only breaks ties`() {
        val order = probeOrder(
            listOf(
                candidate("android", score = 10.0, url = "throttled", hasN = true),
                candidate("android", score = 10.0, url = "clean", hasN = false)
            ),
            preferredClient = "android"
        )

        assertEquals(
            "same score: the URL without an `n` throttle is the better first probe",
            "clean",
            order.first().url
        )
    }

    @Test
    fun `three candidates per shape are probed`() {
        val pool = (1..6).map { index ->
            candidate("android", score = 100.0 - index, url = "candidate-$index")
        }

        assertEquals(
            "the field cases had one candidate and nothing behind it; three " +
                "covers the preferred client's best two plus the next best " +
                "without turning a resolve into a sweep",
            3,
            MAX_PROBED_CANDIDATES
        )
        assertEquals(
            "and the cap keeps the probe set to the top of the ranking",
            listOf("candidate-1", "candidate-2", "candidate-3"),
            probeOrder(pool, preferredClient = "android").take(MAX_PROBED_CANDIDATES)
                .map { it.url }
        )
    }

    @Test
    fun `a probe is bounded to a few seconds`() {
        assertTrue(
            "the cheap ranged read is worth a second or two, not the resolve's " +
                "whole budget",
            CANDIDATE_PROBE_TIMEOUT_MS in 1_500..3_500
        )
        assertTrue(
            "and the set as a whole cannot add up to a stalled resolve",
            CANDIDATE_PROBE_BUDGET_MS >= CANDIDATE_PROBE_TIMEOUT_MS &&
                CANDIDATE_PROBE_BUDGET_MS <= 6_000
        )
    }

    private fun candidate(
        client: String,
        score: Double,
        url: String,
        hasN: Boolean = false
    ): StreamCandidate = StreamCandidate(
        client = client,
        priority = if (client == "android_vr") 4 else 0,
        url = url,
        score = score,
        hasN = hasN,
        height = 720,
        ext = "mp4"
    )
}
