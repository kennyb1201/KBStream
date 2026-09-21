package com.kennyb1201.kbstream.data.iptv

import java.io.Reader

/**
 * Escapes bare `&` characters to `&amp;` as the stream is consumed.
 *
 * XMLTV feeds are not always well-formed: a channel or programme description
 * containing an unescaped `&` ("Guns & Ammo", "Q&A") makes XmlPullParser throw,
 * and that exception fails the whole import — the staging design keeps the live
 * guide intact, but the guide then simply never updates for that provider, with
 * nothing on screen to say why. Escaping invalid entity references while
 * streaming means such a feed imports with a slightly mangled description
 * instead of not importing at all.
 *
 * Valid references pass through untouched: the five predefined XML entities,
 * decimal (`&#38;`) and hex (`&#x26;`) character references, and anything else
 * shaped like an entity name. This lives separately from the parser so it can
 * be unit tested against the chunk-boundary cases, which is where a streaming
 * transformer like this goes wrong.
 *
 * Constant memory (fixed buffers plus a bounded pushback queue), so a
 * multi-hundred-MB guide can still stream through a TV's heap.
 */
internal class XmltvEntitySanitizingReader(
    private val source: Reader
) : Reader() {

    private val inBuf = CharArray(IN_BUF_SIZE)
    private var inPos = 0
    private var inLen = 0
    private var eof = false

    // Chars consumed from the source but not yet emitted (pushback when a
    // candidate '&' turns out NOT to start a valid entity).
    private val lookahead = ArrayDeque<Char>()

    // Transformed output not yet delivered to the caller.
    private val pending = StringBuilder()

    private fun nextSourceChar(): Char? {
        lookahead.removeFirstOrNull()?.let { return it }
        if (inPos >= inLen) {
            if (eof) return null
            inLen = source.read(inBuf)
            inPos = 0
            if (inLen < 0) {
                eof = true
                return null
            }
        }
        return inBuf[inPos++]
    }

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        // Reader contract: a zero-length request reads nothing and must not
        // block, recurse or report end-of-stream.
        if (len == 0) return 0

        // Drain the pushback even after end-of-stream: an unterminated '&' at
        // the very end of the document leaves the characters that followed it
        // in `lookahead`, and stopping at `eof` used to drop them ("Q&A" lost
        // its "A").
        while (pending.length < len && (!eof || lookahead.isNotEmpty())) {
            val c = nextSourceChar() ?: break
            if (c != '&') {
                pending.append(c)
                continue
            }

            // Collect a candidate entity reference: '&' followed by entity
            // chars up to ';' (bounded — numeric refs can't exceed ~10 chars,
            // predefined names are 3-5, so 12 is generous).
            val candidate = StringBuilder().append('&')
            var valid = false
            while (candidate.length < MAX_ENTITY_LENGTH) {
                val n = nextSourceChar()
                if (n == null) {
                    eof = true
                    break
                }
                candidate.append(n)
                if (n == ';') {
                    valid = isValidEntity(candidate)
                    break
                }
                if (!n.isLetterOrDigit() && n != '#') break
            }

            if (valid) {
                pending.append(candidate)
            } else {
                pending.append("&amp;")
                // Push back everything consumed after the '&'.
                for (i in candidate.length - 1 downTo 1) {
                    lookahead.addFirst(candidate[i])
                }
            }
        }

        if (pending.isEmpty()) return -1

        val n = minOf(len, pending.length)
        for (i in 0 until n) cbuf[off + i] = pending[i]
        pending.delete(0, n)
        return n
    }

    override fun close() {
        source.close()
    }

    private fun isValidEntity(s: StringBuilder): Boolean {
        val body = s.substring(1, s.length - 1) // strip & and ;
        return body in PREDEFINED ||
            (
                body.startsWith("#x") && body.length > 2 &&
                    body.substring(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                ) ||
            (body.startsWith("#") && body.length > 1 && body.substring(1).all { it.isDigit() })
    }

    private companion object {
        const val IN_BUF_SIZE = 8_192
        const val MAX_ENTITY_LENGTH = 12
        val PREDEFINED = setOf("amp", "lt", "gt", "quot", "apos")
    }
}
