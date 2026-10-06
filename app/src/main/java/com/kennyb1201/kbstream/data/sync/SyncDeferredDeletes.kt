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

    /**
     * The account's email alongside [KEY_ACCOUNT], so a delete staged under the
     * email form still matches once the session starts reporting a user id - and
     * so a user-id-tagged stage still matches a caller that only has the email
     * (SD-5). Not a credential: it is the same address the account already
     * shows in Settings.
     */
    private const val KEY_ACCOUNT_EMAIL = "last_account_email"
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
     * Remembers the account a later signed-out delete belongs to, under BOTH
     * identities it is known by. Called on every successful sign-in, and again
     * on sign-out BEFORE the session store is cleared (an upgraded install may
     * sign out before it has ever recorded the id here).
     *
     * [accountId] should be the Supabase user id whenever the session carries
     * one: it is the stable choice, because it survives an email change, while
     * the email is only a fallback. Both are recorded so a delete staged under
     * either form can still be matched (SD-5). A call with both blank is a
     * no-op, so an unknown account never clears what was already known.
     */
    fun rememberAccount(context: Context, accountId: String?, email: String? = null) {
        val id = DeferredDeleteRules.normalizeAccount(accountId)
        val address = DeferredDeleteRules.normalizeAccount(email)
        if (id == null && address == null) return
        val editor = prefs(context).edit()
        id?.let { editor.putString(KEY_ACCOUNT, it) }
        address?.let { editor.putString(KEY_ACCOUNT_EMAIL, it) }
        editor.apply()
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
     * Every delete staged for the account signing in, matched against BOTH
     * identities it is known by - its Supabase user id and its email (SD-5),
     * normalized. Only these may replay; another account's staged deletes are
     * left untouched.
     */
    fun stagedFor(
        context: Context,
        accountId: String?,
        email: String? = null
    ): List<Staged> {
        val targets = DeferredDeleteRules.identities(accountId, email)
        if (targets.isEmpty()) return emptyList()
        return read(context).filter { staged ->
            val id = DeferredDeleteRules.normalizeAccount(staged.accountId)
            id != null && id in targets
        }
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
