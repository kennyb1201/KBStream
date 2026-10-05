package com.kennyb1201.kbstream.data.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, ACCOUNT-SCOPED staging for watch-history deletes made while SIGNED
 * OUT.
 *
 * The gap this closes: signing out does NOT delete the account's cloud rows, so
 * a delete made while signed out used to remove only the local row, and the
 * next sign-in as the SAME account pulled the cloud copy straight back (see
 * [SupabaseSync.deleteHistoryRows]). Those deletes cannot ride the ordinary
 * outbox, which [SupabaseSync.signOut] clears on purpose so no queued write
 * lands under whichever account signs in next. They are staged here instead,
 * each tagged with the account it belongs to, and replayed only when THAT
 * account signs back in.
 *
 * The store deliberately lives in its OWN prefs file, not the session store
 * ([SupabaseSync]'s `kbstream_sync`): sign-out clears the session store, and
 * the last-account id plus the staged deletes must survive it. It is not a
 * credential, so it does not need [com.kennyb1201.kbstream.data.security.SecureTokenStore].
 *
 * The rules it implements (normalization, identity, same-account replay) live
 * in [DeferredDeleteRules] so they are unit tested without Android.
 */
internal object SyncDeferredDeletes {

    private const val BASE_NAME = "kbstream_sync_deferred_deletes"
    private const val KEY_ACCOUNT = "last_account_id"
    private const val KEY_DELETES = "deferred_deletes"

    /** One delete waiting for its account to sign back in. */
    data class Staged(
        val accountId: String,
        val table: String,
        val keyColumn: String,
        val key: String,
        val payloadJson: String,
        val enqueuedAtMs: Long
    ) {
        val id: String get() = DeferredDeleteRules.id(accountId, table, keyColumn, key)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(BASE_NAME, Context.MODE_PRIVATE)

    /**
     * Remembers [accountId] as the account a later signed-out delete belongs
     * to. Called on every successful sign-in, and again on sign-out BEFORE the
     * session store is cleared (an upgraded install may sign out before it has
     * ever recorded the id here). A blank id is ignored.
     */
    fun rememberAccount(context: Context, accountId: String?) {
        val normalized = DeferredDeleteRules.normalizeAccount(accountId) ?: return
        prefs(context).edit().putString(KEY_ACCOUNT, normalized).apply()
    }

    /** The last account seen on this device, or null if none ever signed in. */
    fun lastAccountId(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT, null)

    /** Adds one staged delete, replacing any prior stage for the same row. */
    @Synchronized
    fun stage(context: Context, item: Staged) {
        val current = read(context).associateBy { it.id }.toMutableMap()
        current[item.id] = item
        write(context, current.values.toList())
    }

    /**
     * Every delete staged for [accountId] (compared normalized). Only these may
     * replay when [accountId] signs in; another account's staged deletes are
     * left untouched.
     */
    fun stagedFor(context: Context, accountId: String?): List<Staged> {
        val target = DeferredDeleteRules.normalizeAccount(accountId) ?: return emptyList()
        return read(context).filter { it.accountId == target }
    }

    /** Drops the staged deletes with [ids] (called once they are replayed). */
    @Synchronized
    fun remove(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val drop = ids.toSet()
        write(context, read(context).filterNot { it.id in drop })
    }

    private fun read(context: Context): List<Staged> {
        val raw = prefs(context).getString(KEY_DELETES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val accountId =
                        obj.optString("accountId").takeIf { it.isNotEmpty() } ?: continue
                    val table = obj.optString("table").takeIf { it.isNotEmpty() } ?: continue
                    val keyColumn =
                        obj.optString("keyColumn").takeIf { it.isNotEmpty() } ?: continue
                    val key = obj.optString("key").takeIf { it.isNotEmpty() } ?: continue
                    val payload = obj.optString("payload").takeIf { it.isNotEmpty() } ?: continue
                    add(
                        Staged(
                            accountId = accountId,
                            table = table,
                            keyColumn = keyColumn,
                            key = key,
                            payloadJson = payload,
                            enqueuedAtMs = obj.optLong("enqueuedAtMs", 0L)
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun write(context: Context, items: List<Staged>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("accountId", item.accountId)
                    .put("table", item.table)
                    .put("keyColumn", item.keyColumn)
                    .put("key", item.key)
                    .put("payload", item.payloadJson)
                    .put("enqueuedAtMs", item.enqueuedAtMs)
            )
        }
        prefs(context).edit().putString(KEY_DELETES, array.toString()).apply()
    }
}
