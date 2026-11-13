package com.kennyb1201.kbstream.data.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** One phone this TV has paired with. */
internal data class RemotePhone(
    val id: String,
    val name: String,
    val pairedAtMs: Long
)

/**
 * The phones this TV has paired with, and the PIN currently on screen
 * (spec 2026-11-13, F1).
 *
 * Device-local and deliberately NOT profile-scoped: a pairing is a statement
 * about this television and that phone, not about who is watching. The remote
 * controls whichever profile is active on the TV ("the remote controls the TV's
 * active profile, period"), so filing pairings per profile would ask the same
 * phone to re-pair after every profile switch.
 *
 * It never syncs: a pairing is a fact about two devices on one Wi-Fi, and
 * carrying a token to another household's TV would be a credential with no
 * meaning there.
 *
 * The PIN lives in MEMORY only. A PIN is a five-minute secret shown on the TV;
 * writing it to disk would leave it recoverable long after it expired, and a
 * process restart invalidating it is the safe direction.
 */
internal object RemotePairings {

    const val PREFS = "kbstream_remote"

    private const val KEY_PHONE_PREFIX = "phone_"

    private val pinState = MutableStateFlow<RemotePin?>(null)

    /** A PIN and when it was issued. */
    internal data class RemotePin(val value: String, val issuedAtMs: Long)

    /** The PIN the settings row draws, or null when none is live. */
    fun livePin(nowMs: Long = System.currentTimeMillis()): String? {
        val pin = pinState.value ?: return null
        if (nowMs - pin.issuedAtMs >= RemoteRules.PIN_TTL_MS) return null
        return pin.value
    }

    /**
     * The PIN to show, minting one when there is none or it has expired.
     *
     * Called by the settings row (so a PIN is always on screen when the viewer
     * looks) and by the server when an unpaired phone asks (so a phone that
     * arrives while the row is closed still has something to enter).
     */
    fun ensurePin(
        nowMs: Long = System.currentTimeMillis(),
        random: kotlin.random.Random = kotlin.random.Random.Default
    ): String {
        livePin(nowMs)?.let { return it }
        val fresh = RemoteRules.newPin(random)
        pinState.value = RemotePin(fresh, nowMs)
        return fresh
    }

    /**
     * Consumes the PIN on a correct entry.
     *
     * One PIN pairs one phone: it is cleared the moment it is used, so a
     * shoulder-surfed code cannot pair a second device, and the next phone needs
     * a fresh one from the TV's screen.
     */
    fun consumePin(candidate: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pin = pinState.value ?: return false
        val accepted = RemoteRules.pinAccepts(pin.value, pin.issuedAtMs, nowMs, candidate)
        if (accepted) pinState.value = null
        return accepted
    }

    fun clearPin() {
        pinState.value = null
    }

    /** The pairing token for [phoneId], or null when that phone is not paired. */
    fun tokenFor(context: Context?, phoneId: String?): String? {
        val id = phoneId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val raw = runCatching { prefs(context)?.getString(KEY_PHONE_PREFIX + id, null) }
            .getOrNull()
            ?: return null
        return runCatching { JSONObject(raw).optString("token").trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun pair(
        context: Context?,
        phoneId: String,
        phoneName: String,
        token: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val id = phoneId.trim().takeIf { it.isNotEmpty() } ?: return
        val store = prefs(context) ?: return
        val value = JSONObject()
            .put("name", RemoteRules.deviceLabel(phoneName))
            .put("token", token)
            .put("at", nowMs)
            .toString()
        runCatching { store.edit().putString(KEY_PHONE_PREFIX + id, value).apply() }
    }

    fun forget(context: Context?, phoneId: String?) {
        val id = phoneId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching { prefs(context)?.edit()?.remove(KEY_PHONE_PREFIX + id)?.apply() }
    }

    /** Every paired phone, most recently paired first. */
    fun phones(context: Context?): List<RemotePhone> {
        val store = prefs(context) ?: return emptyList()
        val out = ArrayList<RemotePhone>()
        runCatching {
            store.all.forEach { (key, value) ->
                if (!key.startsWith(KEY_PHONE_PREFIX)) return@forEach
                val node = runCatching { JSONObject(value as? String ?: return@forEach) }
                    .getOrNull() ?: return@forEach
                val id = key.removePrefix(KEY_PHONE_PREFIX)
                if (id.isBlank()) return@forEach
                out += RemotePhone(
                    id = id,
                    name = RemoteRules.deviceLabel(node.optString("name")),
                    pairedAtMs = node.optLong("at", 0L)
                )
            }
        }
        return out.sortedByDescending { it.pairedAtMs }
    }

    private fun prefs(context: Context?) = context?.applicationContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
