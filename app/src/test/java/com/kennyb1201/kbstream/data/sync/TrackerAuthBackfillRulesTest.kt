package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enrollment backfill's two decisions (spec 2026-11-14, F1).
 *
 * The field report: Kitsu connected on the TV on 2026-11-13, before the session
 * enrollment landed, never appeared on the phone until Sync now was pressed
 * twice. The session was publishable the whole time and was never queued: the
 * queue only ever holds what changed, and nothing had changed it since the
 * enrollment existed.
 *
 * So the backfill has to answer two questions, and both are here:
 *  - is this device's copy the same session it last published? (a fingerprint,
 *    because every build restamps `updatedAt` and that says nothing about the
 *    session);
 *  - which enrolled keys are due? (with the enrollment's own gates: nothing for a
 *    profile that never connected, nothing for a session already published, and
 *    a changed session due again).
 */
class TrackerAuthBackfillRulesTest {

    // ── the fingerprint ───────────────────────────────────────────────────

    @Test
    fun `an unchanged session keeps its fingerprint through a build clock`() {
        // `updatedAt` is `System.currentTimeMillis()` at build time: two reads of
        // the same session differ in that field and nothing else, and a
        // fingerprint that included it would report every session as changed on
        // every start - the backfill would re-push on every launch forever.
        val first = TrackerAuthBackfillRules.fingerprint(kitsu(updatedAt = 1_700_000_000_000L))
        val second = TrackerAuthBackfillRules.fingerprint(kitsu(updatedAt = 1_800_000_000_000L))
        assertEquals(first, second)
    }

    @Test
    fun `a token, a rotation and a disconnect are each a new session`() {
        val base = TrackerAuthBackfillRules.fingerprint(kitsu(updatedAt = 1_700_000_000_000L))
        val newToken = TrackerAuthBackfillRules.fingerprint(
            kitsu(updatedAt = 1_700_000_000_000L, accessToken = "new-access")
        )
        val rotated = TrackerAuthBackfillRules.fingerprint(
            kitsu(updatedAt = 1_700_000_000_000L, refreshToken = "rotated")
        )
        val signedOut = TrackerAuthBackfillRules.fingerprint(
            kitsu(updatedAt = 1_700_000_000_000L, signedOut = true)
        )
        listOf(newToken, rotated, signedOut).forEach { fingerprint ->
            assertNotEquals(
                "a changed session has to be due again: that is how a refresh " +
                    "and a deliberate disconnect reach the sibling",
                base,
                fingerprint
            )
        }
        // And they are different FROM EACH OTHER, so two independent changes
        // cannot collide into one marker.
        assertEquals(3, setOf(newToken, rotated, signedOut).size)
    }

    @Test
    fun `the field order a builder happens to use does not change the fingerprint`() {
        val ordered = buildJsonObject {
            put("access_token", JsonPrimitive("a"))
            put("viewer_name", JsonPrimitive("kenny"))
            put("updatedAt", JsonPrimitive(1_700_000_000_000L))
        }
        val reordered = buildJsonObject {
            put("viewer_name", JsonPrimitive("kenny"))
            put("updatedAt", JsonPrimitive(1_700_000_000_000L))
            put("access_token", JsonPrimitive("a"))
        }
        assertEquals(
            TrackerAuthBackfillRules.fingerprint(ordered),
            TrackerAuthBackfillRules.fingerprint(reordered)
        )
    }

    @Test
    fun `the marker holds a digest, not the session`() {
        val token = "kitsu-access-token-that-must-not-be-copied"
        val fingerprint = TrackerAuthBackfillRules.fingerprint(
            kitsu(updatedAt = 1L, accessToken = token)
        )
        assertFalse(
            "a marker in a prefs file must never be a second copy of a credential",
            fingerprint.contains(token)
        )
        assertEquals("a hex digest", 64, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" })
    }

    // ── what a start must enqueue ─────────────────────────────────────────

    @Test
    fun `a session this device never published is due`() {
        val entry = enrolled("kitsu_auth", hasSomethingToPublish = true, fingerprint = "abc")
        assertEquals(
            "the field report: publishable the whole time, never queued",
            listOf("kitsu_auth"),
            TrackerAuthBackfillRules.due(listOf(entry), emptyMap())
        )
    }

    @Test
    fun `a published session is not due again`() {
        val entry = enrolled("kitsu_auth", hasSomethingToPublish = true, fingerprint = "abc")
        assertTrue(
            "settler S5: after the first backfill push a plain restart queues nothing",
            TrackerAuthBackfillRules.due(
                listOf(entry),
                mapOf("kitsu_auth" to "abc")
            ).isEmpty()
        )
    }

    @Test
    fun `a changed session is due even though its key has a marker`() {
        val entry = enrolled("kitsu_auth", hasSomethingToPublish = true, fingerprint = "abc")
        assertEquals(
            "a refresh rotation or a deliberate disconnect publishes through its own " +
                "path, but the backfill must not treat the old marker as \"already sent\"",
            listOf("kitsu_auth"),
            TrackerAuthBackfillRules.due(listOf(entry), mapOf("kitsu_auth" to "old"))
        )
    }

    @Test
    fun `a profile that never connected a tracker is never due`() {
        listOf("kitsu_auth", "mal_auth", "anilist_auth").forEach { key ->
            val entry = enrolled(key, hasSomethingToPublish = false, fingerprint = "blank")
            assertTrue(
                "$key: settler S3 - a profile with no session publishes nothing, so " +
                    "its blank can never be seeded over a live one",
                TrackerAuthBackfillRules.due(listOf(entry), emptyMap()).isEmpty()
            )
        }
    }

    @Test
    fun `the enrolled order is kept, and each key is answered separately`() {
        val entries = listOf(
            enrolled("kitsu_auth", hasSomethingToPublish = true, fingerprint = "k"),
            enrolled("mal_auth", hasSomethingToPublish = true, fingerprint = "m"),
            enrolled("anilist_auth", hasSomethingToPublish = false, fingerprint = "a")
        )
        assertEquals(
            "one start can owe more than one session: the spec covers all three, " +
                "and AniList stays out because this profile never connected it",
            listOf("kitsu_auth", "mal_auth"),
            TrackerAuthBackfillRules.due(entries, mapOf("kitsu_auth" to "stale"))
        )
    }

    @Test
    fun `two profiles' copies of one key are two markers`() {
        assertNotEquals(
            "a marker keyed by the session alone would let one profile's published " +
                "Kitsu session excuse the other's unpublished one",
            TrackerAuthBackfillRules.markerKey("p:one:kitsu_auth"),
            TrackerAuthBackfillRules.markerKey("p:two:kitsu_auth")
        )
        assertEquals(
            TrackerAuthBackfillRules.markerKey("p:one:kitsu_auth"),
            TrackerAuthBackfillRules.markerKey("p:one:kitsu_auth")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun enrolled(
        prefKey: String,
        hasSomethingToPublish: Boolean,
        fingerprint: String
    ) = TrackerAuthBackfillRules.EnrolledAuth(
        prefKey = prefKey,
        hasSomethingToPublish = hasSomethingToPublish,
        fingerprint = fingerprint
    )

    /** The Kitsu blob's shape, which is what the builder produces. */
    private fun kitsu(
        updatedAt: Long,
        accessToken: String = "kitsu-access",
        refreshToken: String = "kitsu-refresh",
        signedOut: Boolean = false
    ): JsonObject = buildJsonObject {
        put("updatedAt", JsonPrimitive(updatedAt))
        put("access_token", JsonPrimitive(accessToken))
        put("refresh_token", JsonPrimitive(refreshToken))
        put("expires_at", JsonPrimitive(1_700_000_000_000L))
        put("signed_out", JsonPrimitive(signedOut))
    }
}
