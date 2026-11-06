package com.kennyb1201.kbstream.data.trakt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Trakt list reference a KB source's address becomes.
 *
 * The field 404 this pins: a folder whose source was Trakt's own canonical
 * public-list URL, `https://trakt.tv/lists/12345`. The parser had no branch for
 * that shape - it has no owner in it - so the catch-all read the literal
 * segment `lists` as the owner's username and the read asked Trakt for
 * `users/lists/12345/items/{type}`. Trakt has no user called "lists", so every
 * action on the ref (open, like, save, copy) answered 404.
 *
 * All rows are at [TraktListRefParser.parse] level: no network, no repository.
 */
class TraktListRefParserTest {

    private fun ref(raw: String?): TraktListRef? = TraktListRefParser.parse(raw)

    @Test
    fun `Trakt's canonical public list url has no owner`() {
        listOf(
            // The exact URL from the field report - the row the pre-fix parser
            // failed, reading "lists" as the owner.
            "https://trakt.tv/lists/12345" to "12345",
            // The same list as a browser copies it (trailing slash), as a bare
            // host path, and as a path without the host. Scheme, `#`, `?` and
            // the trailing slash are all stripped before the segments are read,
            // so all of them land on the same shape.
            "https://trakt.tv/lists/12345/" to "12345",
            "http://trakt.tv/lists/12345" to "12345",
            "trakt.tv/lists/12345" to "12345",
            "lists/12345" to "12345",
            "  https://trakt.tv/lists/12345?trakt_return_to=/home#top  " to "12345",
            // A slug-addressed public list takes the same branch: the id field
            // carries the slug and Trakt's id-only endpoint accepts either.
            "https://trakt.tv/lists/my-list" to "my-list"
        ).forEach { (raw, listId) ->
            assertEquals(
                "`$raw` must parse ownerless (list `$listId`)",
                TraktListRef(user = null, listId = listId),
                ref(raw)
            )
        }
    }

    @Test
    fun `a source that names the owner keeps the owner`() {
        // Unchanged: the user branch is how every personal and every explicitly
        // owner-addressed list is read, and the fix must not swallow it.
        assertEquals(
            TraktListRef(user = "alice", listId = "my-list"),
            ref("https://trakt.tv/users/alice/lists/my-list")
        )
        assertEquals(
            TraktListRef(user = "alice", listId = "my-list"),
            ref("alice/lists/my-list")
        )
        assertEquals(
            "the users/ prefix is optional but the owner is not",
            TraktListRef(user = "alice", listId = "my-list"),
            ref("https://trakt.tv/users/alice/lists/my-list/")
        )
    }

    @Test
    fun `a bare id or slug is still ownerless`() {
        assertEquals(TraktListRef(user = null, listId = "12345"), ref("12345"))
        assertEquals(TraktListRef(user = null, listId = "my-list"), ref("my-list"))
        assertEquals(
            "an @-prefixed slug is the same slug",
            TraktListRef(user = null, listId = "my-list"),
            ref("@my-list")
        )
    }

    @Test
    fun `a source that is not a list is still read by the tolerant catch-all`() {
        // The spec's table expected null here, but this tip's parser does not
        // validate list-ness: the catch-all reads the last two segments as
        // owner + slug, which is the tolerance every non-canonical KB export
        // relies on ("there is no single KB export version to pin"). Narrowing
        // that is a separate decision - the 404 this change fixes is the
        // canonical `lists/{id}` shape, and that branch runs before this one.
        // Asserted as the code stands, so changing it has to be deliberate.
        assertEquals(
            TraktListRef(user = "movies", listId = "123"),
            ref("https://trakt.tv/movies/123")
        )
        assertEquals(
            "and a source with nothing to address is no ref at all",
            null,
            ref("   ")
        )
    }

    @Test
    fun `an ownerless ref reads through the id-only endpoint that already exists`() {
        // The whole fix is the parse: the read already has the branch, and this
        // change must not add an endpoint, a resolve or a second read path.
        val repo = mainSource("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")
        val half = repo.substringAfter("private suspend fun readListHalf(")
            .substringBefore("/**")
        assertTrue(
            "a null owner must read `lists/{id}` - no owner is prepended",
            half.contains("if (user == null)") && half.contains("api.listItemsById(")
        )
        assertTrue(
            "and a named owner still reads the user-addressed endpoint",
            half.contains("api.listItems(")
        )
        assertEquals(
            "one read of the id-only endpoint, as before",
            1,
            Regex("api\\.listItemsById\\(").findAll(half).count()
        )
    }

    // ------------------------------------------------------------- sources

    private fun mainSource(path: String): String {
        val root = findSourceRoot()
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
