package com.kennyb1201.kbstream.data.sync

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/**
 * The backfill that closes "the session was connected before its sync enrollment
 * existed" (spec 2026-11-14).
 *
 * A tracker session publishes when it is created, refreshed or deliberately
 * disconnected - each of those calls its auth object's publish hook, which
 * enqueues the blob. A session that predates the enrollment was never enqueued
 * by anything, so the outbox never held it and the periodic flush (which only
 * drains a non-empty outbox) could not see it. It sat on the device it was
 * connected on until a human pressed Sync now: Kenny's Kitsu connect on the TV
 * never reached the phone until he did exactly that, twice.
 *
 * The fix is a start-time question with a durable answer: for every enrolled
 * tracker session, has THIS DEVICE published what it currently holds? The
 * "currently holds" half is a fingerprint of the blob minus its build clock
 * (`updatedAt`, which is stamped fresh on every build and therefore says nothing
 * about the session). The "has published" half is a marker written when a push
 * of that key actually succeeded - in the sync layer's own prefs, because an
 * in-memory record (`_lastPushedPrefKeys`) dies with the process that would need
 * it.
 *
 * Everything here is pure so the two decisions - what is due, and whether a
 * payload is the same session as last time - are unit tested directly.
 */
internal object TrackerAuthBackfillRules {

    /** The build clock every prefs blob carries - never the session itself. */
    private const val BUILD_CLOCK_FIELD = "updatedAt"

    /** Prefix of the durable "this device published this" markers. */
    private const val MARKER_PREFIX = "auth_pushed:"

    /**
     * An enrolled tracker session: the prefs key that carries it, whether this
     * profile has anything to say about it at all, and what it holds now.
     */
    data class EnrolledAuth(
        val prefKey: String,
        val hasSomethingToPublish: Boolean,
        val fingerprint: String
    )

    /**
     * What this device's copy of a session looks like: the blob's own fields,
     * ordered and hashed, with the build clock left out.
     *
     * `updatedAt` has to go: every builder stamps it with the build's clock, so
     * two consecutive reads of an unchanged session differ in exactly that field
     * and nothing else. Ordering matters for the same reason - a JsonObject's
     * iteration order is its insertion order, and the point of the hash is that
     * the same session always produces the same string.
     *
     * The hash (not the blob) is what gets stored, so a marker never becomes a
     * second copy of a token.
     */
    fun fingerprint(payload: JsonObject): String {
        val canonical = payload.entries
            .filter { (key, _) -> key != BUILD_CLOCK_FIELD }
            .sortedBy { (key, _) -> key }
            .joinToString("|") { (key, value) -> "$key=$value" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            value.toString(16).padStart(2, '0')
        }
    }

    /**
     * The keys a start must enqueue.
     *
     * Three ways to be left out, and each is a rule rather than an optimisation:
     *  - nothing to publish: a profile that never connected that tracker stays
     *    out of the cloud entirely (the enrollment's own blank-suppression);
     *  - an unusable fingerprint: an empty session has nothing worth a push;
     *  - already published: the marker says this device pushed exactly this
     *    session, so a plain restart enqueues nothing (settler S5).
     *
     * A session that changed since its marker - a connect, a refresh rotation, a
     * deliberate disconnect - differs in the blob and is due again, which is how
     * a disconnect still travels (settler S4).
     *
     * [lastPublished] maps each key to the fingerprint this device last published,
     * or null/absent when it never has.
     */
    fun due(
        enrolled: List<EnrolledAuth>,
        lastPublished: Map<String, String?>
    ): List<String> = enrolled
        .filter { it.hasSomethingToPublish }
        .filter { it.fingerprint.isNotEmpty() }
        .filter { lastPublished[it.prefKey] != it.fingerprint }
        .map { it.prefKey }

    /** The durable marker's pref name for one stored (profile-scoped) key. */
    fun markerKey(storedKey: String): String = MARKER_PREFIX + storedKey
}
