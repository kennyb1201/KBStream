package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * These two builders are all that stands between a TV remote keystroke and
 * SQLite: [ftsPrefixExpression] becomes an FTS `MATCH` expression (malformed
 * input is a hard error, not an empty result set) and [likeContainsPattern]
 * becomes the substring fallback's pattern (unescaped `%` or `_` silently
 * widens the match).
 */
class EpgSearchQueryTest {

    // ── FTS prefix expression ────────────────────────────────────────────

    @Test
    fun `every word becomes a prefix term`() {
        assertEquals("simps*", ftsPrefixExpression("simps"))
        // Case is left as typed: FTS folding is case-insensitive for ASCII, and
        // the expression is what gets logged when a search looks wrong.
        assertEquals("Hawaii* Five*", ftsPrefixExpression("Hawaii Five"))
    }

    @Test
    fun `word punctuation separates terms instead of breaking the query`() {
        // The raw characters are FTS operators; passed through they would make
        // SQLite reject the whole MATCH expression.
        assertEquals("Hawaii* Five* 0*", ftsPrefixExpression("Hawaii Five-0"))
        assertEquals("Law* Order*", ftsPrefixExpression("Law & Order"))
        assertEquals("Sports* center*", ftsPrefixExpression("\"Sports\": center"))
        // The index tokenizes "M*A*S*H" the same way, so all four single-letter
        // tokens have to be present — which is exactly what this expression asks.
        assertEquals("M* A* S* H*", ftsPrefixExpression("M*A*S*H"))
    }

    @Test
    fun `whitespace and blank queries collapse`() {
        assertEquals("the* good* wife*", ftsPrefixExpression("   the   good  wife "))
        assertNull(ftsPrefixExpression(""))
        assertNull(ftsPrefixExpression("   "))
        assertNull(ftsPrefixExpression("!!! ... ???"))
    }

    @Test
    fun `non-ascii words stay searchable`() {
        assertEquals("Café*", ftsPrefixExpression("Café"))
        assertEquals("40*", ftsPrefixExpression("40%"))
    }

    @Test
    fun `a pasted paragraph is capped`() {
        val expression = ftsPrefixExpression("one two three four five six seven eight")
        assertEquals("one* two* three* four* five* six*", expression)
    }

    // ── LIKE substring fallback ──────────────────────────────────────────

    @Test
    fun `like pattern wraps the trimmed query`() {
        assertEquals("%friends%", likeContainsPattern("friends"))
        assertEquals("%five 0%", likeContainsPattern("  five 0  "))
    }

    @Test
    fun `like wildcards in the query are matched literally`() {
        // Without the escapes "100%" would match any title starting with 100
        // and "s_arch" would match "search".
        assertEquals("%100\\%%", likeContainsPattern("100%"))
        assertEquals("%s\\_arch%", likeContainsPattern("s_arch"))
        assertEquals("%\\\\%", likeContainsPattern("\\"))
    }
}
