package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.iptv.db.CachedPlaylistChannelRow
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The US filter at the choke point it lives at: one `loadPlaylist()` of the
 * built-in lineup writes ONLY its US channels, renumbered, and the same M3U at
 * any other URL is written whole.
 *
 * This runs the real repository against a real database under Robolectric,
 * because the claim is about the rows that end up cached - which is what the
 * guide reads - and not about a decision in isolation. The playlist body is
 * served by an OkHttp interceptor, so the load's fetch, parse, filter and cache
 * write are the production ones; only the wire is synthetic.
 *
 * The lineup it filters is iptv-org's `us.m3u`: a country list that still
 * carries international feeds, and whose US entries include foreign-language
 * channels that must stay (see [FreeLineup.isUsChannel]).
 */
@RunWith(AndroidJUnit4::class)
class FreeLineupUsPlaylistLoadTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * A US affiliate, one non-US feed, an entry with no `tvg-id` at all, and an
     * uppercase `.US` affiliate - the four cases the rule has to separate.
     */
    private val syntheticM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="ABC.us@WFTV" group-title="News",ABC WFTV
        https://stream.example/abc.m3u8
        #EXTINF:-1 tvg-id="LBCInternational.lb@SD" group-title="News",LBC International
        https://stream.example/lbc.m3u8
        #EXTINF:-1 group-title="News",No Id Channel
        https://stream.example/noid.m3u8
        #EXTINF:-1 tvg-id="AE.US@East" group-title="Entertainment",A&E
        https://stream.example/ae.m3u8
    """.trimIndent()

    @Test
    fun `the built-in lineup caches only its US channels, renumbered`() = runBlocking {
        val repository = IptvRepository(context, clientServing(syntheticM3u))

        val playlist = repository.loadPlaylist(FreeLineup.PLAYLIST_URL)

        assertEquals(
            "the lineup the screen publishes is filtered too, not only the cache",
            listOf("ABC WFTV", "No Id Channel", "A&E"),
            playlist.channels.map { it.name }
        )

        val rows = cachedRows(FreeLineup.PLAYLIST_URL)
        assertEquals(
            listOf("ABC WFTV", "No Id Channel", "A&E"),
            rows.map { it.displayName }
        )
        assertEquals(
            "positions are renumbered from zero across the KEPT entries",
            listOf(0, 1, 2),
            rows.map { it.position }
        )
    }

    @Test
    fun `the same playlist at another URL is written whole`() = runBlocking {
        // The scoping is the promise: "US only" is a rule about the BUILT-IN
        // lineup, not a filter over the viewer's own sources.
        val otherUrl = "https://provider.example/custom.m3u"
        val repository = IptvRepository(context, clientServing(syntheticM3u))

        repository.loadPlaylist(otherUrl)

        val rows = cachedRows(otherUrl)
        assertEquals(
            listOf("ABC WFTV", "LBC International", "No Id Channel", "A&E"),
            rows.map { it.displayName }
        )
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.position })
    }

    @Test
    fun `a refresh replaces the filtered rows rather than adding to them`() = runBlocking {
        // Every load rewrites this URL's rows in one transaction, so the filter
        // is a standing rule: a second load cannot leave the first one's rows
        // (or an older build's foreign rows) behind.
        val repository = IptvRepository(context, clientServing(syntheticM3u))

        repository.loadPlaylist(FreeLineup.PLAYLIST_URL)
        repository.loadPlaylist(FreeLineup.PLAYLIST_URL)

        val rows = cachedRows(FreeLineup.PLAYLIST_URL)
        assertEquals(listOf("ABC WFTV", "No Id Channel", "A&E"), rows.map { it.displayName })
        assertEquals(3, rows.size)
    }

    /** The rows the guide reads, straight out of the cache the load wrote. */
    private suspend fun cachedRows(url: String): List<CachedPlaylistChannelRow> =
        // The write happens on Dispatchers.IO inside loadPlaylist; the read is
        // put there too, since Room refuses main-thread queries on this
        // (file-backed) instance.
        withContext(Dispatchers.IO) {
            IptvDatabase.getInstance(context).iptvDao()
                .getCachedPlaylistChannelPage(url, 100, 0)
        }

    /** An OkHttp client that answers every request with [body], no network. */
    private fun clientServing(body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
}
