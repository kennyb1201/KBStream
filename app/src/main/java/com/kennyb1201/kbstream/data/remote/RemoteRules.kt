package com.kennyb1201.kbstream.data.remote

import kotlin.random.Random

/**
 * The phone-as-a-remote rules that have no Android in them (spec 2026-11-13).
 *
 * Kept apart from the server, the client and the injector so the parts a field
 * test can only see the end of - which PIN is still valid, which key names may
 * be sent, what counts as a LAN address - are pinned by a plain JVM test.
 */
internal object RemoteRules {

    /**
     * The NSD service type both sides use.
     *
     * Namespaced to the app, and `_tcp` because the channel is a TCP WebSocket.
     * Android limits a service type to 15 characters for the label and 8 for the
     * protocol, so `kbstream-remote` + `_tcp` fits.
     */
    const val SERVICE_TYPE = "_kbstream-remote._tcp"

    /** Bumped when the wire contract changes in a way an older peer cannot read. */
    const val VERSION = 1

    /** The path the WebSocket lives on, beside a plain GET used as a liveness probe. */
    const val WEBSOCKET_PATH = "/remote"

    /** How long a displayed PIN may be used. */
    const val PIN_TTL_MS = 5L * 60L * 1000L

    const val PIN_DIGITS = 4

    /** How many characters the pairing token has (hex). */
    const val TOKEN_LENGTH = 32

    /**
     * The key names the phone may send.
     *
     * A closed list, not "whatever KeyEvent has": the channel is a remote, and a
     * caller that can name any key code can press anything the app handles. Each
     * name maps to the KeyEvent a physical remote produces (see
     * [RemoteKeyCodes]) where one exists.
     */
    val KEYS: List<String> = listOf(
        "UP",
        "DOWN",
        "LEFT",
        "RIGHT",
        "OK",
        "BACK",
        "HOME",
        "PLAY_PAUSE",
        "SEEK_BACK",
        "SEEK_FORWARD",
        "VOLUME_UP",
        "VOLUME_DOWN",
        "MUTE"
    )

    /** A key name in the wire's spelling, or null when it is not one of [KEYS]. */
    fun normaliseKey(raw: String?): String? {
        val code = raw?.trim()?.uppercase() ?: return null
        return code.takeIf { it in KEYS }
    }

    /** A fresh [PIN_DIGITS]-digit PIN, leading zeros kept ("0042" is a valid PIN). */
    fun newPin(random: Random = Random.Default): String {
        val value = random.nextInt(10_000)
        return value.toString().padStart(PIN_DIGITS, '0')
    }

    /** A fresh pairing token. */
    fun newToken(random: Random = Random.Default): String {
        val bytes = random.nextBytes(TOKEN_LENGTH / 2)
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /**
     * Whether [candidate] may use the PIN issued at [issuedAtMs].
     *
     * Expired means unusable even when it is the right digits: an old PIN sitting
     * in a screenshot must not pair a new phone. A blank stored PIN is "none
     * issued", which no candidate satisfies.
     */
    fun pinAccepts(
        storedPin: String?,
        issuedAtMs: Long,
        nowMs: Long,
        candidate: String?
    ): Boolean {
        val stored = storedPin?.trim().orEmpty()
        val offered = candidate?.trim().orEmpty()
        if (stored.length != PIN_DIGITS || offered.length != PIN_DIGITS) return false
        if (!offered.all { it.isDigit() }) return false
        if (issuedAtMs <= 0L || nowMs - issuedAtMs >= PIN_TTL_MS) return false
        return stored == offered
    }

    /** A token check that does not short-circuit on length. */
    fun tokenMatches(stored: String?, offered: String?): Boolean {
        val a = stored?.trim().orEmpty()
        val b = offered?.trim().orEmpty()
        if (a.isEmpty() || a.length != b.length) return false
        var diff = 0
        a.indices.forEach { index -> diff = diff or (a[index].code xor b[index].code) }
        return diff == 0
    }

    /**
     * Whether [host] names an address on the local network.
     *
     * Both ends are already on the same Wi-Fi by construction (NSD only resolves
     * LAN services, and the TV binds to its own LAN address), so this is defence
     * in depth against a resolved record pointing somewhere else: a public
     * address, a loopback alias, or a hostile mDNS answer.
     */
    fun isLanHost(host: String?): Boolean {
        val text = host?.trim()?.trim('[', ']').orEmpty()
        if (text.isEmpty()) return false
        if (!text.all { it.isDigit() || it == '.' }) return false
        val parts = text.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return false }
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            // Link-local, which is what a TV that has not got a DHCP lease yet
            // answers on; still the same cable/AP, never the internet.
            octets[0] == 169 && octets[1] == 254 -> true
            else -> false
        }
    }

    /** The name shown for a discovered TV, in the shape the pairing row lists. */
    fun deviceLabel(name: String?): String {
        val clean = name?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        return clean.ifEmpty { "KBStream TV" }
    }
}
