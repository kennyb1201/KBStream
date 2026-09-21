package com.kennyb1201.kbstream.data.iptv

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sanitizer exists because one unescaped `&` in a description fails the
 * whole guide import, and its failure mode is asymmetric: escaping too little
 * breaks the import, escaping too much corrupts text that was already correct.
 * Both directions are pinned here, with the read sizes varied so the pushback
 * path (chars consumed while looking ahead at a candidate entity) is exercised
 * the way a real 8 KB-buffered stream would exercise it.
 */
class XmltvEntitySanitizingReaderTest {

    /** Reads [input] through the sanitizer in [chunk] sized requests. */
    private fun sanitize(input: String, chunk: Int = 8_192): String {
        val reader = XmltvEntitySanitizingReader(StringReader(input))
        val out = StringBuilder()
        val buffer = CharArray(chunk)
        while (true) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read < 0) break
            out.append(buffer, 0, read)
        }
        return out.toString()
    }

    @Test
    fun `a bare ampersand is escaped`() {
        assertEquals("Guns &amp; Ammo", sanitize("Guns & Ammo"))
        assertEquals("Q&amp;A", sanitize("Q&A"))
        // Two in one document, with text in between.
        assertEquals("A &amp; B &amp; C", sanitize("A & B & C"))
    }

    @Test
    fun `valid entity references pass through untouched`() {
        val valid = "&amp; &lt; &gt; &quot; &apos; &#38; &#x26;"
        assertEquals(valid, sanitize(valid))
    }

    @Test
    fun `a hex reference must use a lowercase x, as XML requires`() {
        // CharRef is '&#x' + hex, lowercase. "&#X26;" is not valid XML, so it is
        // escaped rather than passed through to a parser that would reject it.
        assertEquals("&amp;#X26;", sanitize("&#X26;"))
    }

    @Test
    fun `an entity-looking name that is not predefined is escaped`() {
        // "&notanentity;" is not one of the five predefined names. Escaping the
        // ampersand is what keeps the parser alive; the rest is literal text.
        assertEquals("&amp;notanentity;", sanitize("&notanentity;"))
        // A lone ';' after an escaped ampersand survives as text.
        assertEquals("&amp;;", sanitize("&;"))
    }

    @Test
    fun `an unterminated ampersand at end of stream is escaped`() {
        assertEquals("trailing &amp;", sanitize("trailing &"))
        assertEquals("name &amp;", sanitize("name &"))
    }

    @Test
    fun `escaping survives every read-chunk size`() {
        // Same document, read one char at a time and in awkward chunk sizes:
        // the candidate entity straddles the boundary in each case, which is
        // exactly where a naive transformer loses or duplicates characters.
        val input = "A & B &amp; C &#38; D &notanentity; E &"
        val expected = "A &amp; B &amp; C &#38; D &amp;notanentity; E &amp;"

        for (chunk in listOf(1, 2, 3, 7, 16, 8_192)) {
            assertEquals("chunk=$chunk", expected, sanitize(input, chunk))
        }
    }

    @Test
    fun `a zero-length read reads nothing without ending the stream`() {
        val reader = XmltvEntitySanitizingReader(StringReader("abc"))
        val buffer = CharArray(4)

        assertEquals(0, reader.read(buffer, 0, 0))

        // ...and the stream is still there afterwards.
        assertEquals(3, reader.read(buffer, 0, 3))
        assertEquals("abc", String(buffer, 0, 3))
    }

    @Test
    fun `a real document keeps its markup intact`() {
        val xml = """
            <programme start="20260921120000 +0000" channel="bbc1.uk">
              <title>Fishing &amp; Boating</title>
              <desc>Anglers discuss rods &amp; reels & tactics</desc>
            </programme>
        """.trimIndent()

        val sanitized = sanitize(xml)

        assertEquals(
            xml.replace("& tactics", "&amp; tactics"),
            sanitized
        )
        assertTrue(sanitized.contains("<programme start=\"20260921120000 +0000\""))
    }

    @Test
    fun `closing the reader closes the source`() {
        var closed = false
        val source = object : java.io.StringReader("x") {
            override fun close() {
                closed = true
                super.close()
            }
        }

        XmltvEntitySanitizingReader(source).close()

        assertTrue(closed)
    }
}
