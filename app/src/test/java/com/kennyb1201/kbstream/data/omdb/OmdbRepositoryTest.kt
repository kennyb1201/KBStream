package com.kennyb1201.kbstream.data.omdb

import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheSizeRow
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OmdbRepository]'s contract: the awards string on success, null for every
 * "nothing to show" shape, and one network request per title per cache
 * lifetime.
 *
 * The repository is exercised through its injectable seams (the fake API and
 * the fake cache), so these run as plain JVM tests - no device, no emulator.
 * What is pinned here is the behavior the Detail screen depends on: a missing
 * awards line is never an error (it must not throw), and the free tier's 1,000
 * requests/day are protected by the cache rather than by luck.
 */
class OmdbRepositoryTest {

    private val api = FakeOmdbApi()
    private val cache = FakeCacheDao()
    private val repository = OmdbRepository(
        apiProvider = { api },
        cacheProvider = { cache },
        apiKey = { "test-key" },
    )

    // ── Success and the null cases ──────────────────────────────────

    @Test
    fun `returns the awards text on success`() = runBlocking {
        api.result = OmdbResponse(
            awards = "Won 2 Oscars. 159 wins & 220 nominations total",
            response = "True"
        )
        assertEquals(
            "Won 2 Oscars. 159 wins & 220 nominations total",
            repository.awardsFor("tt1375666")
        )
    }

    @Test
    fun `no key saved means no request and no awards`() = runBlocking {
        val keyless = OmdbRepository(
            apiProvider = { api },
            cacheProvider = { cache },
            apiKey = { "" },
        )
        assertNull(keyless.awardsFor("tt1375666"))
        assertEquals("a blank key must not spend a request", 0, api.calls)
    }

    @Test
    fun `a blank imdb id means no request and no awards`() = runBlocking {
        assertNull(repository.awardsFor("   "))
        assertEquals(0, api.calls)
    }

    @Test
    fun `a failed request is null, not an exception`() = runBlocking {
        api.failure = IOException("no network")
        assertNull(repository.awardsFor("tt1375666"))
    }

    @Test
    fun `Response=False is null`() = runBlocking {
        api.result = OmdbResponse(awards = null, response = "False")
        assertNull(repository.awardsFor("tt1375666"))
    }

    @Test
    fun `an N-A awards field is null`() = runBlocking {
        api.result = OmdbResponse(awards = "N/A", response = "True")
        assertNull(repository.awardsFor("tt1375666"))
    }

    @Test
    fun `a blank awards field is null`() = runBlocking {
        api.result = OmdbResponse(awards = "   ", response = "True")
        assertNull(repository.awardsFor("tt1375666"))
    }

    // ── Caching ─────────────────────────────────────────────────────

    @Test
    fun `the second call for the same imdb id performs no request`() = runBlocking {
        api.result = OmdbResponse(awards = "Won 1 Oscar.", response = "True")
        repository.awardsFor("tt0111161")
        repository.awardsFor("tt0111161")
        assertEquals(1, api.calls)
    }

    @Test
    fun `a title with no awards is not re-requested either`() = runBlocking {
        // The negative answer is cached too: a title OMDB has nothing for does
        // not gain awards between two opens of its detail page, and asking
        // again would spend the daily quota to be told the same thing.
        api.result = OmdbResponse(awards = "N/A", response = "True")
        assertNull(repository.awardsFor("tt0000001"))
        assertNull(repository.awardsFor("tt0000001"))
        assertEquals(1, api.calls)
    }

    @Test
    fun `a real answer survives into the shared disk cache`() = runBlocking {
        api.result = OmdbResponse(awards = "Won 3 BAFTAs.", response = "True")
        repository.awardsFor("tt0109830")

        // A fresh repository over the same cache stands in for the next screen
        // open or the next process: no network should be needed.
        val reopened = OmdbRepository(
            apiProvider = { api },
            cacheProvider = { cache },
            apiKey = { "test-key" },
        )
        val callsBefore = api.calls
        assertEquals("Won 3 BAFTAs.", reopened.awardsFor("tt0109830"))
        assertEquals(callsBefore, api.calls)
    }

    /**
     * The cache table belongs to the app, not to this feature: the awards
     * rows must be namespaced so they can never collide with a TMDB detail
     * row's key.
     */
    @Test
    fun `awards rows are namespaced in the shared cache`() = runBlocking {
        api.result = OmdbResponse(awards = "Won 1 Oscar.", response = "True")
        repository.awardsFor("tt0111161")
        assertEquals(listOf("omdb:awards:tt0111161"), cache.upserts)
    }

    // ── Fakes ───────────────────────────────────────────────────────

    private class FakeOmdbApi : OmdbApiService {
        var calls = 0
        var result: OmdbResponse = OmdbResponse()
        var failure: Throwable? = null

        override suspend fun getByImdbId(imdbId: String, apiKey: String): OmdbResponse {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    /** An in-memory stand-in for the Room DAO, so no database is opened. */
    private class FakeCacheDao : TmdbJsonCacheDao {
        private val rows = LinkedHashMap<String, TmdbJsonCacheEntity>()
        val upserts = mutableListOf<String>()

        override suspend fun getByKey(key: String): TmdbJsonCacheEntity? = rows[key]

        override suspend fun upsert(item: TmdbJsonCacheEntity) {
            rows[item.key] = item
            upserts += item.key
        }

        override suspend fun deleteOlderThan(minUpdatedAt: Long): Int {
            val doomed = rows.values.filter { it.updatedAt < minUpdatedAt }.map { it.key }
            doomed.forEach { rows.remove(it) }
            return doomed.size
        }

        override suspend fun deleteByKeys(keys: List<String>) {
            keys.forEach { rows.remove(it) }
        }

        override suspend fun sizeIndex(): List<TmdbJsonCacheSizeRow> =
            rows.values.map {
                TmdbJsonCacheSizeRow(
                    key = it.key,
                    bytes = it.json.length.toLong(),
                    updatedAt = it.updatedAt
                )
            }

        override suspend fun count(): Int = rows.size

        override suspend fun totalBytes(): Long? =
            if (rows.isEmpty()) null else rows.values.sumOf { it.json.length.toLong() }
    }
}
