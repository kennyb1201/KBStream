package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The durable half of [BingeCatPendingHistoryRules]: the plays a completion
 * write could not land.
 *
 * One JSON file in `filesDir`, beside the app's other blobs, holding the request
 * BODY of each failed completion plus what the queue's rules need (profile,
 * key, age, attempts). The body is Moshi's own JSON for
 * [BingeCatHistoryBatch], so a replay deserialises the request that was
 * actually attempted rather than reconstructing it from earlier state.
 *
 * Everything here is best-effort and silent: a queue write that fails (no
 * context, a full disk, a corrupt file) must never fail the completion that
 * triggered it, and a corrupt file is DISCARDED rather than repaired - the
 * alternative is a queue that cannot be read forever.
 *
 * Not synced, not per-profile-filed: entries carry their own `mappedProfileId`
 * and are filtered by [BingeCatPendingHistoryRules.usable], so a device's queue
 * can hold a previous mapping's plays safely and drops them the moment they
 * stop belonging to the profile being written.
 */
internal class BingeCatPendingHistory(
    context: Context?,
    private val moshi: Moshi
) {

    private val file: File? = context?.let { File(it.filesDir, FILE_NAME) }

    private val mutex = Mutex()

    private val batchAdapter = moshi.adapter(BingeCatHistoryBatch::class.java)

    /** Queues one failed completion. Same-key entries replace each other. */
    suspend fun enqueue(
        mappedProfileId: Long,
        key: String,
        batch: BingeCatHistoryBatch,
        label: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val body = runCatching { batchAdapter.toJson(batch) }.getOrNull() ?: return
        update { entries ->
            BingeCatPendingHistoryRules.enqueue(
                entries,
                BingeCatPendingPlay(
                    mappedProfileId = mappedProfileId,
                    key = key,
                    body = body,
                    label = label,
                    queuedAtMs = nowMs
                ),
                nowMs
            )
        }
        Log.i(TAG, "queued completion for replay ($label)")
    }

    /**
     * What this profile may replay, oldest first.
     *
     * Entries belonging to another BingeCat profile are dropped here rather than
     * skipped: they are another person's plays, and the only two outcomes for
     * them are "replayed into the wrong profile" and "dropped".
     */
    suspend fun usable(
        mappedProfileId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): List<BingeCatPendingPlay> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = read()
            val usable = BingeCatPendingHistoryRules.usable(all, mappedProfileId, nowMs)
            if (usable.size != all.size) write(usable)
            usable
        }
    }

    /** The batch behind an entry, or null when its body no longer parses. */
    suspend fun decode(entry: BingeCatPendingPlay): BingeCatHistoryBatch? =
        withContext(Dispatchers.IO) {
            runCatching { batchAdapter.fromJson(entry.body) }
                .getOrNull()
                ?.takeUnless { it.isEmpty }
        }

    /** Applies one replay answer: a success drops the entry, a failure counts. */
    suspend fun settle(
        key: String,
        success: Boolean
    ) {
        update { entries -> BingeCatPendingHistoryRules.settle(entries, key, success) }
    }

    private suspend fun update(
        transform: (List<BingeCatPendingPlay>) -> List<BingeCatPendingPlay>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(read())
            write(next)
        }
    }

    private fun read(): List<BingeCatPendingPlay> {
        val target = file ?: return emptyList()
        if (!target.exists()) return emptyList()
        val raw = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    val body = row.optString(KEY_BODY)
                    val profileId = row.optLong(KEY_PROFILE)
                    if (body.isBlank() || profileId <= 0L) continue
                    add(
                        BingeCatPendingPlay(
                            mappedProfileId = profileId,
                            key = row.optString(KEY_KEY),
                            body = body,
                            label = row.optString(KEY_LABEL),
                            queuedAtMs = row.optLong(KEY_QUEUED_AT),
                            attempts = row.optInt(KEY_ATTEMPTS)
                        )
                    )
                }
            }
        }.getOrElse {
            // Discarded, not repaired: an unreadable queue cannot be read later
            // either, and rethrowing would fail the completion that wrote it.
            Log.w(TAG, "pending history unreadable, discarding: ${it.message}")
            runCatching { target.delete() }
            emptyList()
        }
    }

    private fun write(entries: List<BingeCatPendingPlay>) {
        val target = file ?: return
        runCatching {
            if (entries.isEmpty()) {
                if (target.exists()) target.delete()
                return
            }
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put(KEY_PROFILE, entry.mappedProfileId)
                        .put(KEY_KEY, entry.key)
                        .put(KEY_BODY, entry.body)
                        .put(KEY_LABEL, entry.label)
                        .put(KEY_QUEUED_AT, entry.queuedAtMs)
                        .put(KEY_ATTEMPTS, entry.attempts)
                )
            }
            target.writeText(array.toString())
        }.onFailure { Log.w(TAG, "pending history not saved: ${it.message}") }
    }

    private companion object {
        const val TAG = "BINGECAT_PENDING"

        /** Beside the app's other files; no schema, so no migration. */
        const val FILE_NAME = "bingecat_pending_history.json"

        const val KEY_PROFILE = "profile"
        const val KEY_KEY = "key"
        const val KEY_BODY = "body"
        const val KEY_LABEL = "label"
        const val KEY_QUEUED_AT = "queuedAt"
        const val KEY_ATTEMPTS = "attempts"
    }
}
