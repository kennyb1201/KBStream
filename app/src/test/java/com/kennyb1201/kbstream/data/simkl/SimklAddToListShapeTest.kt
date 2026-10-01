package com.kennyb1201.kbstream.data.simkl

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression these pin: every "Add to Library" reached this profile's own
 * My List and neither tracker's watchlist, while the menu closed as if it had
 * saved. Simkl sends the destination status on each ITEM now — the root-level
 * `to` this app used to send is answered with `400 empty_field` ("Missed \"to\"
 * parameter"), so the request never landed. And because the endpoint answers
 * 201 even when it could not match an item (that item comes back under
 * `not_found`), a bare `isSuccessful` check reports a rejection as a save.
 *
 * See https://api.simkl.org/api-reference/simkl/add-to-list.
 */
class SimklAddToListShapeTest {

    /** Built the way every Moshi instance in the app is built. */
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private fun entry(to: String) = SimklAddToListEntry(
        to = to,
        title = "Some Title",
        year = 2010,
        ids = SimklAddToListIds(imdb = "tt1375666", tmdb = 27205)
    )

    /** The destination status travels on each item; the request root has no `to`. */
    @Test
    fun `the destination status is serialized per item, not on the request root`() {
        val json = moshi.adapter(SimklAddToListRequest::class.java)
            .toJson(
                SimklAddToListRequest(
                    movies = listOf(entry("plantowatch")),
                    shows = emptyList()
                )
            )

        val root = JSONObject(json)
        assertFalse("a root-level `to` is the shape Simkl rejects", root.has("to"))
        assertEquals(
            "plantowatch",
            root.getJSONArray("movies").getJSONObject(0).getString("to")
        )
        // Title and year still ride along for the server's own lookup.
        assertEquals("Some Title", root.getJSONArray("movies").getJSONObject(0).getString("title"))
        assertEquals(2010, root.getJSONArray("movies").getJSONObject(0).getInt("year"))
    }

    /** A matched add (item echoed under `added`) is a success. */
    @Test
    fun `an added item is read as landed`() {
        val parsed = landing(
            """{"added":{"movies":[{"to":"plantowatch"}],"shows":[]},""" +
                """"not_found":{"movies":[],"shows":[]}}"""
        )
        assertTrue(parsed)
    }

    /** The 201-with-not_found case: the tracker could not match the title. */
    @Test
    fun `an unmatched item is not read as landed`() {
        val parsed = landing(
            """{"added":{"movies":[],"shows":[]},""" +
                """"not_found":{"movies":[{"title":"Some Title","year":2010}],"shows":[]}}"""
        )
        assertFalse(parsed)
    }

    /** A body with neither bucket (an unexpected shape) is taken at its word. */
    @Test
    fun `an empty response is not treated as a rejection`() {
        assertTrue(landing("{}"))
    }

    private fun landing(json: String): Boolean {
        val response = moshi.adapter(SimklAddToListResponse::class.java).fromJson(json)
            ?: return true
        return response.landed
    }
}
