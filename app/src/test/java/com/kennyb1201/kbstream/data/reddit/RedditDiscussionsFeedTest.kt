package com.kennyb1201.kbstream.data.reddit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Reddit source reads the site's keyless Atom search feed, whose shape is
 * fixed but whose ENTRIES are not curated: the same search returns subreddit
 * listings, namesake threads and one-line link posts next to real write-ups.
 * Every one of those has to be dropped, because a card that is not about the
 * title the user opened is worse than no card at all.
 *
 * The feed embeds each post as HTML-escaped markup
 * (`&lt;div class=&quot;md&quot;&gt;…`), so the parse that matters is the one
 * that turns that back into readable text with the tags gone — not the one
 * that reads the XML.
 */
class RedditDiscussionsFeedTest {

    private val body =
        "In Shrek (2001) the entire movie can fit into a 20mb gif file. " +
            "The whole 90 minutes was rendered and the file size is tiny, " +
            "which is a detail worth noting about how compressible the film is."

    private fun entry(
        title: String,
        link: String,
        bodyHtml: String = escapedParagraph(body),
        author: String = "someone",
        updated: String = "2023-02-07T00:29:16+00:00"
    ): String = """
        <entry>
          <title>$title</title>
          <link href="$link" />
          <updated>$updated</updated>
          <author><name>/u/$author</name></author>
          <content type="html">$bodyHtml</content>
        </entry>
    """

    private fun feed(vararg entries: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
           <feed xmlns="http://www.w3.org/2005/Atom">
           ${entries.joinToString("\n")}
           </feed>"""

    private fun escapedParagraph(text: String): String =
        "&lt;!-- SC_OFF --&gt;&lt;div class=&quot;md&quot;&gt;&lt;p&gt;$text&lt;/p&gt;" +
            "&lt;/div&gt;&lt;!-- SC_ON --&gt;"

    @Test
    fun `a real thread becomes a review card`() {
        val reviews = RedditDiscussionsClient.parseFeed(
            feed(
                entry(
                    title = "In Shrek (2001) the whole movie fits in a 20mb gif",
                    link = "https://www.reddit.com/r/shittymoviedetails/comments/83nv4u/in_shrek/"
                )
            ),
            "Shrek"
        )

        assertEquals(1, reviews.size)
        val review = reviews.single()
        assertEquals("reddit:83nv4u", review.id)
        assertEquals("u/someone", review.author)
        // The card's date renderer splits on the "T".
        assertEquals("2023-02-07T00:29:16+00:00", review.createdAt)
        assertTrue(review.content.contains("r/shittymoviedetails"))
        assertTrue(review.content.contains("https://www.reddit.com/r/shittymoviedetails/comments/83nv4u/in_shrek/"))
        assertTrue(review.content.contains("20mb gif file"))
    }

    @Test
    fun `markup never reaches the card`() {
        val reviews = RedditDiscussionsClient.parseFeed(
            feed(
                entry(
                    title = "Shrek is a masterpiece of the form, argued at length",
                    link = "https://www.reddit.com/r/movies/comments/abc123/shrek/"
                )
            ),
            "Shrek"
        )

        val content = reviews.single().content
        assertFalse(content.contains("<div"))
        assertFalse(content.contains("&lt;"))
        assertFalse(content.contains("SC_OFF"))
        assertFalse(content.contains("&quot;"))
    }

    @Test
    fun `a post whose title does not name the title is dropped`() {
        // The search's own relevance is loose enough that this is the common
        // case, not an edge case: a post about something else entirely whose
        // body happens to mention the title.
        val reviews = RedditDiscussionsClient.parseFeed(
            feed(
                entry(
                    title = "Why has no one told me Tubi is the goat?",
                    link = "https://www.reddit.com/r/television/comments/1roqdv1/why_has_no_one/"
                )
            ),
            "Shrek"
        )

        assertTrue(reviews.isEmpty())
    }

    @Test
    fun `community listings in the feed are dropped`() {
        // The feed's first hits are often subreddits (r/Shrek), not posts.
        val reviews = RedditDiscussionsClient.parseFeed(
            feed(
                entry(
                    title = "Shrek",
                    link = "https://www.reddit.com/r/Shrek/",
                    bodyHtml = escapedParagraph("Fans of Shrek unite as this is the largest dedicated Shrek forum on reddit! ".repeat(3))
                )
            ),
            "Shrek"
        )

        assertTrue(reviews.isEmpty())
    }

    @Test
    fun `a one-line post is not a review`() {
        val reviews = RedditDiscussionsClient.parseFeed(
            feed(
                entry(
                    title = "Shrek is great",
                    link = "https://www.reddit.com/r/funny/comments/aaa111/shrek/",
                    bodyHtml = escapedParagraph("This.")
                )
            ),
            "Shrek"
        )

        assertTrue(reviews.isEmpty())
    }

    @Test
    fun `a broken feed yields nothing instead of throwing`() {
        assertTrue(RedditDiscussionsClient.parseFeed("<feed><entry>", "Shrek").isEmpty())
        assertTrue(RedditDiscussionsClient.parseFeed("", "Shrek").isEmpty())
        assertTrue(RedditDiscussionsClient.parseFeed("not xml at all", "Shrek").isEmpty())
    }

    @Test
    fun `html to text keeps the words and drops the markup`() {
        // The XML parser hands the feed's `<content>` back ALREADY entity-
        // decoded ("&lt;p&gt;" arrives as "<p>"), which is the form this
        // helper strips.
        val text = RedditDiscussionsClient.htmlToText(
            "<!-- SC_OFF --><div class=\"md\"><p>First line &amp; second line</p>" +
                "</div><!-- SC_ON --><p>New paragraph &#39;quoted&#39;</p><br/>tail"
        )

        assertTrue(text.startsWith("First line & second line"))
        assertTrue(text.contains("New paragraph 'quoted'"))
        assertTrue(text.contains("tail"))
        assertFalse(text.contains("<"))
        assertFalse(text.contains("&amp;"))
    }
}
