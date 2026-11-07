package com.kennyb1201.kbstream.data.mdblist

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned-list reads were the one hot path with no cache at all: a pinned rail
 * re-reads its list on every Home entry, one request per load (one per page
 * above 1,000 items), against a 1,000/day key three devices share.
 *
 * The cache's own rules are driven directly here with an injected clock. The
 * wiring - which reads consult the cache before walking, which writes drop an
 * entry - is pinned on the client's source, because the client needs an Android
 * context and a live OkHttp stack to run. Each source slice is checked for both
 * of its delimiters first: `substringAfter` silently yields the whole file when
 * its marker is missing, and a renamed read would then be asserted against
 * everything else and pass for the wrong reason.
 */
class MdbListReadCacheTest {

    private var now = 1_000L
    private val ttl = 30 * 60 * 1000L

    private fun cache() = MdbListReadCache<List<String>>(ttl) { now }

    // ── spec T1: inside the TTL is no walk; past it is one ─────────────

    @Test
    fun `a read inside the TTL is served from the cache`() {
        val cache = cache()
        cache.put("k:list:1", listOf("tt1", "tt2"))
        now += ttl - 1
        assertEquals(listOf("tt1", "tt2"), cache.get("k:list:1"))
    }

    @Test
    fun `a read past the TTL is a miss and drops the entry`() {
        val cache = cache()
        cache.put("k:list:1", listOf("tt1"))
        now += ttl + 1
        assertNull(cache.get("k:list:1"))
        assertEquals("an expired entry must not linger", 0, cache.size())
    }

    @Test
    fun `an empty list is an answer and is cached as one`() {
        // The client distinguishes "the walk failed" (null) from "this list is
        // empty" (the empty list): the reconcile deletes a rail for a list the
        // account no longer has, so the two must not collapse here.
        val cache = cache()
        cache.put("k:list:2", emptyList())
        assertEquals(emptyList<String>(), cache.get("k:list:2"))
    }

    // ── spec T1: a write drops its own entry, and only its own ─────────

    @Test
    fun `invalidating one list leaves every other list's read alone`() {
        val cache = cache()
        cache.put("k:list:1", listOf("a"))
        cache.put("k:list:2", listOf("b"))
        cache.invalidate("k:list:1")
        assertNull(cache.get("k:list:1"))
        assertEquals(listOf("b"), cache.get("k:list:2"))
    }

    @Test
    fun `invalidating a key that was never read is harmless`() {
        val cache = cache()
        cache.invalidate("k:list:9")
        assertEquals(0, cache.size())
    }

    // ── the client's wiring, pinned on the source ──────────────────────

    @Test
    fun `the reads consult the cache before walking`() {
        val listItems = body("suspend fun getListItems(", "One list's items, following the cursor")
        assertTrue(
            "getListItems must answer a fresh entry before spending a request",
            listItems.contains("listItemsCache.get(cacheKey)?.let { return it }")
        )
        assertTrue(
            "only a FINISHED walk may be cached: a null has to keep reaching " +
                "the reconcile as 'no answer'",
            listItems.contains("if (walked != null) listItemsCache.put(cacheKey, walked)")
        )

        val watchlist = body("suspend fun getWatchlist(", "POST /watchlist/items/add")
        assertTrue(
            watchlist.contains("watchlistCache.get(cacheKey)?.let { return it }")
        )
        assertTrue(watchlist.contains("watchlistCache.put(cacheKey, entries)"))

        val userLists = body("suspend fun getUserListsOrNull(", "GET /lists/{id}/items")
        assertTrue(userLists.contains("userListsCache.get(cacheKey)?.let { return it }"))
        assertTrue(userLists.contains("if (lists != null) userListsCache.put(cacheKey, lists)"))
    }

    @Test
    fun `the cache key carries the key tag and never the key`() {
        val client = source(CLIENT)
        assertTrue(client.contains("private fun listItemsCacheKey(apiKey: String, listId: Int)"))
        assertTrue(
            "a raw key in a process-wide map key is the secret living in memory",
            client.contains("\"\${keyTag(apiKey)}:list:\$listId\"")
        )
        assertTrue(client.contains("\"\${keyTag(apiKey)}:watchlist\""))
        assertTrue(client.contains("\"\${keyTag(apiKey)}:lists\""))
    }

    @Test
    fun `the lists are cached for the directories' thirty minutes`() {
        val client = source(CLIENT)
        assertTrue(
            "the pinned-list window is the public directories' own 30 minutes",
            client.contains("private const val LIST_ITEMS_TTL_MS = 30 * 60 * 1000L")
        )
        assertTrue(
            client.contains("MdbListReadCache<List<MdbListEntry>>(LIST_ITEMS_TTL_MS)")
        )
        assertTrue(
            client.contains("MdbListReadCache<List<MdbListUserList>>(LIST_ITEMS_TTL_MS)")
        )
    }

    @Test
    fun `every write that moves a list drops exactly that entry`() {
        val add = body("suspend fun addToList(", "suspend fun removeFromList(")
        assertTrue(add.contains("listItemsCache.invalidate(listItemsCacheKey(apiKey, listId))"))

        val remove = body("suspend fun removeFromList(", "POST /lists/user/add")
        assertTrue(remove.contains("listItemsCache.invalidate(listItemsCacheKey(apiKey, listId))"))

        val create = body("suspend fun createList(", "PUT /lists/{id}")
        assertTrue(
            "a new list changes the account's list SET",
            create.contains("userListsCache.invalidate(userListsCacheKey(apiKey))")
        )

        val rename = body("suspend fun renameList(", "DELETE /lists/{id}")
        assertTrue(
            "the name lives in the list set, not in the list's items",
            rename.contains("userListsCache.invalidate(userListsCacheKey(apiKey))")
        )

        val delete = body("suspend fun deleteList(", "GET /watchlist/items")
        assertTrue(
            "a deleted list loses both its row and its pinned rail's items",
            delete.contains("userListsCache.invalidate(userListsCacheKey(apiKey))") &&
                delete.contains("listItemsCache.invalidate(listItemsCacheKey(apiKey, listId))")
        )

        val watchlistAdd = body("suspend fun addToWatchlist(", "suspend fun removeFromWatchlist(")
        assertTrue(watchlistAdd.contains("watchlistCache.invalidate(watchlistCacheKey(apiKey))"))

        val watchlistRemove = body("suspend fun removeFromWatchlist(", "Builds {movies:")
        assertTrue(watchlistRemove.contains("watchlistCache.invalidate(watchlistCacheKey(apiKey))"))
    }

    // ── source helpers ────────────────────────────────────────────────

    private fun body(after: String, before: String): String {
        val client = source(CLIENT)
        assertTrue("missing marker: $after", client.contains(after))
        assertTrue("missing marker: $before", client.contains(before))
        return client.substringAfter(after).substringBefore(before)
    }

    private val sourceRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val CLIENT = "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
}
