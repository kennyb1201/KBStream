package com.kennyb1201.kbstream.data.mdblist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MDBList ratings leg's wire shape and its answer taxonomy.
 *
 * Both follow MDBList's published OpenAPI schema
 * (`https://api.mdblist.com/schema/`, read 2026-10-29): the request body is
 * `{movies|shows: [{ids, rating, rated_at}]}`, and a 200 answers per-type counts
 * in `updated` (add/update) or `removed` (remove), plus `not_found` and `errors`
 * for items it could not place. Both are read, not just the status, because an
 * unresolved title still comes back 200.
 *
 * What a JVM can pin is the whole wire contract, which is exactly what a live
 * release would otherwise have to discover: one title per call in one bucket, the
 * ids the client's own `idsNode` produced, an integer on the 1-10 scale,
 * `rated_at` only when there is a timestamp, and the mapping from (code, body) to
 * an outcome. The network call itself is not unit-tested - the same line the other
 * two legs draw.
 */
class MdbListRatingShapeTest {

    private fun ids(imdb: String? = null, tmdb: Int? = null): JSONObject {
        val node = JSONObject()
        if (imdb != null) node.put("imdb", imdb)
        if (tmdb != null) node.put("tmdb", tmdb)
        return node
    }

    // ── the body ─────────────────────────────────────────────────────

    @Test
    fun `a film is one movies entry with its ids, rating and timestamp`() {
        val body = mdbListRatingsPayload(
            ids = ids(imdb = "tt0133093", tmdb = 603),
            isMovie = true,
            rating = 8,
            ratedAtIso = "2026-10-28T12:34:56Z"
        )

        assertTrue("a film goes in the movies bucket", body.has("movies"))
        assertFalse("and never in both buckets", body.has("shows"))
        val entries = body.getJSONArray("movies")
        assertEquals("one title per call", 1, entries.length())
        val entry = entries.getJSONObject(0)
        assertEquals(8, entry.getInt("rating"))
        assertEquals("2026-10-28T12:34:56Z", entry.getString("rated_at"))
        assertEquals("tt0133093", entry.getJSONObject("ids").getString("imdb"))
        assertEquals(603, entry.getJSONObject("ids").getInt("tmdb"))
    }

    @Test
    fun `a series is one shows entry, and its rating is an integer`() {
        val body = mdbListRatingsPayload(
            ids = ids(tmdb = 1399),
            isMovie = false,
            rating = 2,
            ratedAtIso = "2026-10-28T12:34:56Z"
        )

        assertTrue(body.has("shows"))
        assertFalse("no movies bucket for a show", body.has("movies"))
        val entry = body.getJSONArray("shows").getJSONObject(0)
        assertEquals(2, entry.getInt("rating"))
        // An integer on the wire, never a half-star double.
        assertTrue(entry.get("rating") is Int)
    }

    @Test
    fun `a removal sends the ids only, not the queue's presence number`() {
        // The schema's remove shapes are "the same shapes as adding, WITHOUT
        // `rating`": the value the queue kept is presence bookkeeping, and it is
        // not put on the wire. A timestamp with no rating on it is meaningless
        // too, and the documented examples omit it.
        val body = mdbListRatingsPayload(
            ids = ids(imdb = "tt0133093", tmdb = 603),
            isMovie = true,
            rating = null,
            ratedAtIso = "2026-10-28T12:34:56Z"
        )

        val entry = body.getJSONArray("movies").getJSONObject(0)
        assertFalse("a remove is not an opinion", entry.has("rating"))
        assertFalse("and carries no rating timestamp", entry.has("rated_at"))
        assertEquals("tt0133093", entry.getJSONObject("ids").getString("imdb"))
        assertEquals(603, entry.getJSONObject("ids").getInt("tmdb"))
    }

    @Test
    fun `a write with no timestamp leaves rated_at out, and the server fills it in`() {
        val entry = mdbListRatingsPayload(ids(tmdb = 603), true, 8, null)
            .getJSONArray("movies").getJSONObject(0)

        assertEquals(8, entry.getInt("rating"))
        assertFalse("an absent rated_at means \"now\"; it is not invented", entry.has("rated_at"))
    }

    @Test
    fun `the payload writes the number it is handed, unconverted`() {
        // The leg clamps first (see MdbListRatingWrites, pinned by the contract
        // test). What matters here is that nothing on the wire converts it: a
        // mapped thumb goes out as the same integer, never a scaled or
        // fractional value.
        val entry = mdbListRatingsPayload(ids(tmdb = 603), true, 10, null)
            .getJSONArray("movies").getJSONObject(0)

        assertEquals(10, entry.getInt("rating"))
        assertTrue(entry.get("rating") is Int)
    }

    @Test
    fun `only the tt form of the app's id spelling is an imdb id`() {
        assertEquals("tt0133093", mdbListImdbId("tt0133093"))
        assertEquals("tt1", mdbListImdbId("  tt1  "))
        assertNull("a TMDB spelling is not an imdb id", mdbListImdbId("tmdb:603"))
        assertNull("neither is a bare spelling", mdbListImdbId("tt"))
        assertNull("nor an empty one", mdbListImdbId(""))
    }

    // ── the answer ───────────────────────────────────────────────────

    @Test
    fun `a 2xx with an empty body is a landing`() {
        assertEquals(MdbListRatingOutcome.LANDED, mdbListRatingOutcomeOf(200, ""))
        assertEquals(MdbListRatingOutcome.LANDED, mdbListRatingOutcomeOf(204, null))
    }

    @Test
    fun `a title the endpoint could not resolve is not a landing`() {
        assertEquals(
            MdbListRatingOutcome.NOT_FOUND,
            mdbListRatingOutcomeOf(
                200,
                """{"updated":{"movies":0,"shows":0,"seasons":0,"episodes":0},""" +
                    """"not_found":{"movies":1,"shows":0,"seasons":0,"episodes":0}}"""
            )
        )
    }

    @Test
    fun `an item the endpoint listed as an error is not a landing`() {
        // The schema's `errors` carries what it could not find or refused. The
        // one refusal this app cannot provoke is an invalid rating (whole numbers
        // on 1-10 always), so a zeroed `updated` plus an error is an unmatched id.
        assertEquals(
            MdbListRatingOutcome.NOT_FOUND,
            mdbListRatingOutcomeOf(
                200,
                """{"updated":{"movies":0},"errors":[{"ids":{"imdb":"tt1"},""" +
                    """"message":"Movie not found"}]}"""
            )
        )
    }

    @Test
    fun `a counted change wins over a not_found mention`() {
        assertEquals(
            MdbListRatingOutcome.LANDED,
            mdbListRatingOutcomeOf(
                200,
                """{"updated":{"movies":1,"shows":0,"seasons":0,"episodes":0},""" +
                    """"not_found":{"movies":1,"shows":0,"seasons":0,"episodes":0}}"""
            )
        )
    }

    @Test
    fun `a remove is read off its own bucket`() {
        assertEquals(
            MdbListRatingOutcome.LANDED,
            mdbListRatingOutcomeOf(
                200,
                """{"removed":{"movies":1,"shows":0,"seasons":0,"episodes":0}}"""
            )
        )
        // Taking back a rating the account never had changes nothing and names
        // nothing: idempotent, and still a landing.
        assertEquals(
            MdbListRatingOutcome.LANDED,
            mdbListRatingOutcomeOf(
                200,
                """{"removed":{"movies":0,"shows":0,"seasons":0,"episodes":0}}"""
            )
        )
    }

    @Test
    fun `an accepted no-op is a landing`() {
        assertEquals(MdbListRatingOutcome.LANDED, mdbListRatingOutcomeOf(200, "{}"))
    }

    @Test
    fun `an unreadable 2xx body is taken at its word, like the other two legs`() {
        // The server accepted the write in a shape this build does not read;
        // failing it would drop a rating the account actually holds.
        assertEquals(MdbListRatingOutcome.LANDED, mdbListRatingOutcomeOf(200, "not json"))
    }

    @Test
    fun `a 429 is a pause, whether it came from the server or the local budget`() {
        // The budget interceptor synthesizes its park with the same code
        // (MDBLIST_LOCAL_BUDGET_CODE = 429), and neither is a failed attempt.
        assertEquals(MdbListRatingOutcome.PAUSED, mdbListRatingOutcomeOf(429, ""))
        assertEquals(
            MdbListRatingOutcome.PAUSED,
            mdbListRatingOutcomeOf(429, """{"error":"Daily API limit exceeded"}""")
        )
    }

    @Test
    fun `a refused key is auth, so the leg closes instead of retrying`() {
        assertEquals(MdbListRatingOutcome.AUTH, mdbListRatingOutcomeOf(401, ""))
        assertEquals(MdbListRatingOutcome.AUTH, mdbListRatingOutcomeOf(403, ""))
    }

    @Test
    fun `a 404 is no answer, never a silent drop`() {
        // The shape verdict this gate exists to surface: dropping it would hide
        // the one failure the release test is looking for.
        assertEquals(MdbListRatingOutcome.NO_ANSWER, mdbListRatingOutcomeOf(404, ""))
        assertEquals(MdbListRatingOutcome.NO_ANSWER, mdbListRatingOutcomeOf(500, ""))
        assertEquals(MdbListRatingOutcome.NO_ANSWER, mdbListRatingOutcomeOf(400, ""))
    }
}
