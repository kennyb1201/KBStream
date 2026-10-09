package com.kennyb1201.kbstream.data.omdb

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OMDB wire contract, exercised against the real Retrofit + Moshi stack.
 *
 * [OmdbRepositoryTest] fakes the service, so it cannot see a wrong query
 * parameter name, a wrong host, or a `@Json` name that does not match OMDB's
 * payload — each of which would silently look exactly like "this title has no
 * awards". The canned response below goes through Retrofit's request builder
 * and Moshi's generated adapter, so all four are pinned.
 */
class OmdbApiServiceTest {

    private val requests = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                requests += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "Title": "Inception",
                          "Awards": "Won 4 Oscars. 159 wins & 220 nominations total",
                          "Response": "True"
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
        )
        .build()

    private val api = omdbRetrofitApi(client)

    @Test
    fun `awards and response parse out of OMDB's payload`() = runBlocking {
        val response = api.getByImdbId("tt1375666", "secret-key")
        assertEquals(
            "Won 4 Oscars. 159 wins & 220 nominations total",
            response.awards
        )
        assertEquals("True", response.response)
    }

    @Test
    fun `the request goes to OMDB with the documented query parameters`() = runBlocking {
        api.getByImdbId("tt1375666", "secret-key")

        assertEquals(
            "https://www.omdbapi.com/",
            requests.single().substringBefore("?")
        )
        assertTrue(
            "the id travels as `i`",
            requests.single().contains("i=tt1375666")
        )
        assertTrue(
            "and the credential as `apikey`, which is what OMDB documents",
            requests.single().contains("apikey=secret-key")
        )
    }

    @Test
    fun `the whole repository pipeline reads awards from a real response`() = runBlocking {
        // End to end over the real converter: what the screen would show.
        val repository = OmdbRepository(
            apiProvider = { api },
            cacheProvider = { NoCacheDao },
            apiKey = { "secret-key" },
        )
        assertEquals(
            "Won 4 Oscars. 159 wins & 220 nominations total",
            repository.awardsFor("tt1375666")
        )
    }

    /** The repository's cache is irrelevant here; none of its methods are used. */
    private object NoCacheDao : com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao {
        override suspend fun getByKey(key: String) = null
        override suspend fun upsert(
            item: com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
        ) = Unit
        override suspend fun deleteOlderThan(minUpdatedAt: Long) = 0
        override suspend fun deleteByKeys(keys: List<String>) = Unit
        override suspend fun sizeIndex() =
            emptyList<com.kennyb1201.kbstream.data.cache.TmdbJsonCacheSizeRow>()
        override suspend fun count() = 0
        override suspend fun totalBytes(): Long? = null
    }
}
