package com.kennyb1201.kbstream.data.sync

import android.content.Context
import kotlinx.serialization.json.JsonObject

/**
 * The enrolled tracker sessions, as the start-time backfill reads them
 * (spec 2026-11-14, F1).
 *
 * ONE list, and the backfill is written over it rather than over Kitsu: the next
 * tracker whose session starts travelling joins here once, and the same "has this
 * device ever published it" question is asked of it from the same place. The
 * rules it feeds are pure ([TrackerAuthBackfillRules]); this object is the
 * Android half - the stores, the blobs and their publish gates.
 *
 * It lives beside the payload builder rather than inside it so the builder's own
 * surface is unchanged: the backfill reads through the same public `build…Auth`
 * and `has…AuthToPublish` functions the bulk push uses, which is what keeps the
 * queued payload identical to the one a connect would have published.
 */
internal object TrackerAuthBackfill {

    /**
     * The tracker sessions this app carries. Order is the rail's, not
     * meaningful; membership is.
     */
    private val ENROLLED_KEYS = listOf(
        PrefsPayloadBuilder.KEY_KITSU_AUTH,
        PrefsPayloadBuilder.KEY_MAL_AUTH,
        PrefsPayloadBuilder.KEY_ANILIST_AUTH
    )

    /** True when [prefKey] carries an enrolled tracker session. */
    fun isEnrolled(prefKey: String): Boolean = prefKey in ENROLLED_KEYS

    /**
     * One enrolled session's blob, by key - what the backfill fingerprints and
     * what it enqueues. Null for any other key, so a caller cannot queue a blob
     * it did not mean.
     */
    fun blob(context: Context, prefKey: String): JsonObject? = when (prefKey) {
        PrefsPayloadBuilder.KEY_KITSU_AUTH -> PrefsPayloadBuilder.buildKitsuAuth(context)
        PrefsPayloadBuilder.KEY_MAL_AUTH -> PrefsPayloadBuilder.buildMalAuth(context)
        PrefsPayloadBuilder.KEY_ANILIST_AUTH -> PrefsPayloadBuilder.buildAnilistAuth(context)
        else -> null
    }

    /** The enrollment's own gate: has this profile anything to say about [prefKey]? */
    fun hasSomethingToPublish(context: Context, prefKey: String): Boolean = when (prefKey) {
        PrefsPayloadBuilder.KEY_KITSU_AUTH -> PrefsPayloadBuilder.hasKitsuAuthToPublish(context)
        PrefsPayloadBuilder.KEY_MAL_AUTH -> PrefsPayloadBuilder.hasMalAuthToPublish(context)
        PrefsPayloadBuilder.KEY_ANILIST_AUTH -> PrefsPayloadBuilder.hasAnilistAuthToPublish(context)
        else -> false
    }

    /**
     * Every enrolled session, with its gate and what this profile holds now.
     *
     * Both halves are read through this one list on purpose: the gate and the
     * blob have to describe the SAME session, or the backfill could queue a
     * fingerprint it never read the gate for.
     */
    fun entries(context: Context): List<TrackerAuthBackfillRules.EnrolledAuth> =
        ENROLLED_KEYS.mapNotNull { prefKey ->
            val payload = blob(context, prefKey) ?: return@mapNotNull null
            TrackerAuthBackfillRules.EnrolledAuth(
                prefKey = prefKey,
                hasSomethingToPublish = hasSomethingToPublish(context, prefKey),
                fingerprint = TrackerAuthBackfillRules.fingerprint(payload)
            )
        }
}
