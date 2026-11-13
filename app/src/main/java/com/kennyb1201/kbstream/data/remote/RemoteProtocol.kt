package com.kennyb1201.kbstream.data.remote

import org.json.JSONObject

/**
 * The JSON the phone and the TV exchange over the pairing channel
 * (spec 2026-11-13).
 *
 * Small on purpose: a remote sends keys and one title id, and the TV answers
 * with a verdict. The channel carries no account, no tracker session and no
 * credential of any kind - only the token a pairing minted, which is good for
 * exactly this LAN channel.
 *
 * Every message is one JSON object with a `type`. Unknown types are ignored
 * rather than answered, so a newer phone talking to an older TV degrades to
 * "that button does nothing" instead of an error loop.
 */
internal object RemoteProtocol {

    // ── Phone -> TV ───────────────────────────────────────────────────────

    const val TYPE_PAIR = "pair"
    const val TYPE_HELLO = "hello"
    const val TYPE_KEY = "key"
    const val TYPE_PLAY = "play"

    // ── TV -> phone ───────────────────────────────────────────────────────

    const val TYPE_PAIRED = "paired"
    const val TYPE_PAIR_FAILED = "pair_failed"
    const val TYPE_READY = "ready"
    const val TYPE_AUTH_REQUIRED = "auth_required"
    const val TYPE_PLAY_RESULT = "play_result"
    const val TYPE_PONG = "pong"

    /** What one decoded phone message is. */
    sealed interface Incoming {
        data class Pair(val phoneId: String, val phoneName: String, val pin: String) : Incoming

        data class Hello(val phoneId: String, val token: String) : Incoming

        /** A normalised key name (see [RemoteRules.KEYS]). */
        data class Key(val code: String) : Incoming

        data class Play(val id: String, val mediaType: String?) : Incoming

        data object Unknown : Incoming
    }

    /**
     * Decodes one frame. Null on anything that is not a JSON object we know -
     * malformed input is the other end's problem, never a crash here.
     */
    fun decode(raw: String?): Incoming? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_FRAME_CHARS) return null
        val node = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (node.optString("type").trim()) {
            TYPE_PAIR -> {
                val pin = node.optString("pin").trim()
                val phoneId = node.optString("phoneId").trim()
                if (pin.isEmpty() || phoneId.isEmpty()) return Incoming.Unknown
                Incoming.Pair(
                    phoneId = phoneId.take(MAX_ID_CHARS),
                    phoneName = RemoteRules.deviceLabel(node.optString("phoneName")),
                    pin = pin
                )
            }

            TYPE_HELLO -> {
                val phoneId = node.optString("phoneId").trim()
                if (phoneId.isEmpty()) return Incoming.Unknown
                Incoming.Hello(phoneId.take(MAX_ID_CHARS), node.optString("token").trim())
            }

            TYPE_KEY -> {
                val code = RemoteRules.normaliseKey(node.optString("code"))
                    ?: return Incoming.Unknown
                Incoming.Key(code)
            }

            TYPE_PLAY -> {
                val id = node.optString("id").trim()
                if (id.isEmpty() || id.length > MAX_ID_CHARS) return Incoming.Unknown
                val type = node.optString("mediaType").trim().lowercase()
                Incoming.Play(id, type.takeIf { it == "movie" || it == "series" })
            }

            else -> Incoming.Unknown
        }
    }

    // ── Encoders ──────────────────────────────────────────────────────────

    fun paired(token: String): String =
        JSONObject().put("type", TYPE_PAIRED).put("token", token).toString()

    fun pairFailed(reason: String): String =
        JSONObject().put("type", TYPE_PAIR_FAILED).put("reason", reason).toString()

    fun ready(name: String): String =
        JSONObject().put("type", TYPE_READY).put("name", name).toString()

    fun authRequired(): String = JSONObject().put("type", TYPE_AUTH_REQUIRED).toString()

    /**
     * The verdict the phone shows after "Play on TV".
     *
     * [mode] is `resume` when the TV found a resume point and is starting from
     * it, `detail` when the title has none and the TV opened its page, and null
     * when nothing was opened ([ok] false with a [reason]).
     */
    fun playResult(ok: Boolean, mode: String?, reason: String? = null): String {
        val node = JSONObject().put("type", TYPE_PLAY_RESULT).put("ok", ok)
        mode?.let { node.put("mode", it) }
        reason?.let { node.put("reason", it) }
        return node.toString()
    }

    fun pong(): String = JSONObject().put("type", TYPE_PONG).toString()

    /** The phone's own encoders, in one place so both ends spell a frame the same way. */
    fun pairRequest(phoneId: String, phoneName: String, pin: String): String =
        JSONObject()
            .put("type", TYPE_PAIR)
            .put("phoneId", phoneId)
            .put("phoneName", phoneName)
            .put("pin", pin)
            .toString()

    fun hello(phoneId: String, token: String?): String =
        JSONObject()
            .put("type", TYPE_HELLO)
            .put("phoneId", phoneId)
            .put("token", token.orEmpty())
            .toString()

    fun key(code: String): String =
        JSONObject().put("type", TYPE_KEY).put("code", code).toString()

    fun play(id: String, mediaType: String?): String =
        JSONObject()
            .put("type", TYPE_PLAY)
            .put("id", id)
            .put("mediaType", mediaType.orEmpty())
            .toString()

    /** Replies the phone reads. */
    fun parseReply(raw: String?): Reply? {
        val node = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return null
        val type = node.optString("type").trim()
        return when (type) {
            TYPE_PAIRED -> Reply.Paired(node.optString("token").trim())
            TYPE_PAIR_FAILED -> Reply.PairFailed(node.optString("reason"))
            TYPE_READY -> Reply.Ready(node.optString("name"))
            TYPE_AUTH_REQUIRED -> Reply.AuthRequired
            TYPE_PLAY_RESULT -> Reply.PlayResult(
                ok = node.optBoolean("ok", false),
                mode = node.optString("mode").takeIf { it.isNotBlank() },
                reason = node.optString("reason").takeIf { it.isNotBlank() }
            )
            else -> null
        }
    }

    sealed interface Reply {
        data class Paired(val token: String) : Reply
        data class PairFailed(val reason: String?) : Reply
        data class Ready(val name: String) : Reply
        data object AuthRequired : Reply
        data class PlayResult(val ok: Boolean, val mode: String?, val reason: String?) : Reply
    }

    /** A frame larger than this is not a remote command. */
    private const val MAX_FRAME_CHARS = 4_096

    private const val MAX_ID_CHARS = 128
}
