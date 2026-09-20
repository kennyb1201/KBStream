package com.kennyb1201.kbstream.data.iptv

import android.view.KeyEvent

/**
 * Channel-number entry: the digits a remote sends and the channel they mean.
 *
 * Pure on purpose — the guide and the player both tune by number, and the
 * matching rule is worth unit testing instead of discovering on the TV. The
 * [KeyEvent] codes below are compile-time constants, so this stays testable
 * on the JVM.
 */
internal object ChannelNumberEntry {

    /** Longest number a remote can type; matches the guide's own limit. */
    const val MAX_DIGITS = 4

    /** The digit [keyCode] carries, or null when it is not a number key. */
    fun digitFor(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    /** [current] plus [digit], or unchanged when already at [MAX_DIGITS]. */
    fun push(current: String, digit: Int): String =
        if (current.length >= MAX_DIGITS) current else current + digit

    /**
     * Index in [chnos] that [entry] tunes to, or -1 when nothing matches.
     *
     * An exact channel number wins, compared with leading zeros ignored so a
     * remote user typing "07" still lands on channel 7. Only when the entire
     * lineup carries no numbers at all (plenty of M3U playlists ship none)
     * does the entry fall back to a 1-based position — the only numbering
     * such a playlist actually has.
     */
    fun target(chnos: List<String?>, entry: String): Int {
        val wanted = normalize(entry)
        if (wanted.isEmpty()) return -1
        val numbers = chnos.map { it?.let(::normalize).orEmpty() }
        if (numbers.any { it.isNotEmpty() }) return numbers.indexOfFirst { it == wanted }
        val index = (entry.trim().toIntOrNull() ?: return -1) - 1
        return if (index in chnos.indices) index else -1
    }

    /** Blank for a missing number, otherwise the digits without leading zeros. */
    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.trimStart('0').ifEmpty { "0" }
    }
}
