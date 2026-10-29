package com.kennyb1201.kbstream.data.mdblist

import org.json.JSONArray
import org.json.JSONObject

/*
 * The MDBList ratings leg's WIRE: the body one write carries, and what its
 * answer means.
 *
 * Kept apart from MdbListRatingWrites.kt (which owns the key, the client and the
 * call) for one reason only: none of it touches Android, OkHttp or the client, so
 * every rule here is pinned by a plain JVM test - the same shape TasteRatingMapping
 * and the queue's own rules already use.
 *
 * The shape here is the DOCUMENTED one, not an inference: MDBList publishes its
 * OpenAPI schema at `https://api.mdblist.com/schema/` (read 2026-10-29), and both
 * endpoints' request bodies and 200 responses agree with what this file writes
 * and reads. What remains for a live release to confirm is the account-level
 * round trip only - that a rating written here shows up on the account, and
 * disappears again on a remove. See the landing-gate block in
 * MdbListRatingWrites.kt.
 */

/** What one MDBList ratings call came back as. */
internal enum class MdbListRatingOutcome {

    /** The write landed - added, updated or deleted. */
    LANDED,

    /**
     * The endpoint named the title instead of changing it: nothing counted as
     * updated or removed, and the item came back in `not_found` or `errors`.
     * Dropped, never retried.
     */
    NOT_FOUND,

    /** The title carries no id MDBList can be addressed with. Dropped. */
    NO_IDS,

    /**
     * The key was refused (401/403): the entry is dropped and the leg stays
     * closed until the profile's key changes - a retired key is not something a
     * retry can fix, and a removed key is a PAUSE, not a refusal.
     */
    AUTH,

    /**
     * Nothing was sent and nothing failed: no key, or a 429/budget park. The
     * entry stays queued and does NOT count as an attempt.
     */
    PAUSED,

    /** No usable answer (transport failure, or any other non-2xx). An attempt. */
    NO_ANSWER
}

/**
 * The body for one ratings write: one title, in one bucket.
 *
 * [ids] is the client's own `idsNode` output, so `imdb` and `tmdb` are the only
 * id forms that can go out and a title with neither is refused before this is
 * reached. The bucket is `movies` when [isMovie], `shows` otherwise - the split
 * `TasteRatingMapping.isMovie` makes for the other two legs, read off the
 * store's normalized type. Season and episode granularity is never used:
 * per-episode thumbs are excluded from the taste system entirely, and the
 * endpoint's nested support is unused upside.
 *
 * [rating] arrives already on the 1-10 scale, and NULL means a REMOVAL: the
 * remove endpoint documents "the same shapes as adding, without `rating`", so a
 * take-back carries the ids only - the number the queue kept for presence is not
 * put on the wire, and neither is a timestamp. A non-null rating is written as
 * the whole number it is: the API documents 1-10 in 0.5 steps, and this app only
 * ever sends the whole-number subset the thumb mapping produces (8 and 2, or a
 * clamped numeric rating) - the half-star question is not answered by sending
 * one.
 *
 * `rated_at` is written only on a write with a real timestamp; an absent one
 * defaults to "now" server-side, which is what a null from
 * `TasteRatingMapping.ratedAtIso` means.
 */
internal fun mdbListRatingsPayload(
    ids: JSONObject,
    isMovie: Boolean,
    rating: Int?,
    ratedAtIso: String?
): JSONObject {
    val entry = JSONObject().put("ids", ids)
    if (rating != null) {
        entry.put("rating", rating)
        if (!ratedAtIso.isNullOrBlank()) entry.put("rated_at", ratedAtIso)
    }
    return JSONObject().put(if (isMovie) "movies" else "shows", JSONArray().put(entry))
}

/**
 * The IMDb id inside the app's own id spelling, or null.
 *
 * [parentId] is what `TasteRatingSubmission` resolves for a queued entry: an
 * `tt...` id, a `tmdb:<n>` spelling, or empty for a title the store only knows
 * by name. Only the `tt` form may be sent as `imdb`; the TMDB half arrives
 * separately as the entry's resolved twin, so a `tmdb:<n>` parent contributes
 * nothing here and is never invented into an `imdb` value.
 */
internal fun mdbListImdbId(parentId: String): String? =
    parentId.trim().takeIf { it.startsWith("tt") && it.length > 2 }

/**
 * What a ratings answer means, without the network.
 *
 * Read rather than merely status-checked, because both endpoints answer 200
 * whether or not they could resolve the title: the add/update response counts
 * what changed in `updated` and what it could not place in `not_found`, and the
 * remove response counts `removed`; both add `errors` for items they could not
 * find or rejected (all per the published schema). So:
 *
 *  - 429 -> [MdbListRatingOutcome.PAUSED]. The budget interceptor synthesizes its
 *    local park with this same code (`MDBLIST_LOCAL_BUDGET_CODE` = 429), so a
 *    locally spent budget and a server 429 read identically here on purpose:
 *    neither is a failure, and neither may advance an entry.
 *  - 401/403 -> [MdbListRatingOutcome.AUTH]; any other non-2xx ->
 *    [MdbListRatingOutcome.NO_ANSWER], a 404 included: for a title both ids
 *    resolve that is a SHAPE verdict (the path moved), and dropping it silently
 *    would hide the one failure the landing gate exists to surface.
 *  - a change counted (`updated` or `removed` > 0) -> [MdbListRatingOutcome.LANDED],
 *    even when another bucket also mentions the title.
 *  - nothing changed and the item came back in `not_found` or `errors` ->
 *    [MdbListRatingOutcome.NOT_FOUND]. The other error the schema names is an
 *    invalid rating, which this app cannot send: the mapping is whole numbers on
 *    the documented 1-10 scale.
 *  - a 2xx that changed nothing and named nothing -> [MdbListRatingOutcome.LANDED]:
 *    for an add that is an accepted no-op, and for a remove it is "there was no
 *    rating to take back", which is idempotent, not a failure. An empty or
 *    unreadable body is taken at its word for the same reason - the same rule the
 *    other two legs apply to their 201s.
 */
internal fun mdbListRatingOutcomeOf(code: Int, body: String?): MdbListRatingOutcome {
    if (code == 429) return MdbListRatingOutcome.PAUSED
    if (code == 401 || code == 403) return MdbListRatingOutcome.AUTH
    if (code !in 200..299) return MdbListRatingOutcome.NO_ANSWER

    val text = body.orEmpty()
    if (text.isBlank()) return MdbListRatingOutcome.LANDED
    val root = runCatching { JSONObject(text) }.getOrNull()
        ?: return MdbListRatingOutcome.LANDED

    val updated = mdbListBucketCount(root, "updated")
    val removed = mdbListBucketCount(root, "removed")
    val notFound = mdbListBucketCount(root, "not_found")
    val errors = root.optJSONArray("errors")?.length() ?: 0
    return when {
        updated > 0 || removed > 0 -> MdbListRatingOutcome.LANDED
        notFound > 0 || errors > 0 -> MdbListRatingOutcome.NOT_FOUND
        else -> MdbListRatingOutcome.LANDED
    }
}

/** The four per-type counts an MDBList ratings response reports. */
private val RATING_BUCKETS = listOf("movies", "shows", "seasons", "episodes")

/**
 * The total of [key]'s per-type counts, or 0 when the response omits it.
 *
 * Both responses report an OBJECT of counts (`{"movies": 1, "shows": 0, ...}`),
 * not a scalar, so a title that landed in one type must still read as larger than
 * zero. A missing or differently shaped bucket reads as 0, which lands the write
 * rather than failing one the server accepted.
 */
private fun mdbListBucketCount(root: JSONObject, key: String): Int {
    val bucket = root.optJSONObject(key) ?: return 0
    return RATING_BUCKETS.sumOf { type -> bucket.optInt(type, 0) }
}
