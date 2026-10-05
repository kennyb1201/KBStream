package com.kennyb1201.kbstream.data.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import com.kennyb1201.kbstream.data.addon.StreamDrm
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The played-link cache: replaying a title within a few hours must reuse the
 * exact debrid link that played last time instead of resolving again.
 *
 * What must not be gotten wrong, and is pinned here:
 *  - the round-trip keeps what the player needs to start the SAME link — the
 *    URL, the per-link request headers a gated host demands, and the DRM
 *    license the engine check reads;
 *  - a miss is a miss (unknown key, blank key/url, expired entry), so the
 *    caller falls through to the normal resolve;
 *  - the store stays bounded (sources capped, oldest entry evicted), and an
 *    expired entry is not silently kept alive.
 */
@RunWith(AndroidJUnit4::class)
class PlayedLinkCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStore() {
        prefs().edit().clear().commit()
    }

    /** The store's own prefs file, resolved the way the cache resolves it. */
    private fun prefs() =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_played_links"),
            Context.MODE_PRIVATE
        )

    private fun stream(url: String) = Stream(
        name = "Provider",
        title = "Some Movie 1080p",
        url = url
    )

    @Test
    fun `a remembered link round-trips with its fallbacks, headers and drm`() {
        val played = Stream(
            name = "Provider",
            title = "Some Movie 1080p",
            url = "https://cdn.example/movie.mp4",
            headers = mapOf("Referer" to "https://host.example/"),
            drm = StreamDrm(
                licenseUrl = "https://drm.example/license",
                headers = mapOf("X-License" to "1")
            )
        )
        val sources = listOf(played, stream("https://cdn.example/fallback.mp4"))

        PlayedLinkCache.remember(context, "tt1", played, sources)

        val cached = PlayedLinkCache.get(context, "tt1")
        assertNotNull("the entry must be a hit", cached)
        assertEquals("https://cdn.example/movie.mp4", cached!!.played.url)
        assertEquals(
            "gated hosts need the same headers on a replay",
            "https://host.example/",
            cached.played.requestHeaders["Referer"]
        )
        assertEquals("https://drm.example/license", cached.played.drm?.licenseUrl)
        assertEquals("1", cached.played.drm?.headers?.get("X-License"))
        assertEquals("the ordered fallbacks must survive", 2, cached.sources.size)
        assertEquals("https://cdn.example/fallback.mp4", cached.sources[1].url)
    }

    @Test
    fun `a cached stream carries no binge group, so continuity is never inferred from it`() {
        val played = Stream(
            url = "https://cdn.example/x.mp4",
            behaviorHints = StreamBehaviorHints(bingeGroup = "provider|1080p")
        )

        PlayedLinkCache.remember(context, "tt2", played, listOf(played))

        val cached = PlayedLinkCache.get(context, "tt2")
        assertNotNull(cached)
        assertNull(cached!!.played.bingeGroup)
    }

    @Test
    fun `an unknown key is a miss`() {
        assertNull(PlayedLinkCache.get(context, "never-stored"))
    }

    @Test
    fun `a blank key or a url-less stream is never stored`() {
        PlayedLinkCache.remember(context, "", stream("https://cdn.example/a.mp4"), emptyList())
        PlayedLinkCache.remember(context, "k", Stream(url = null), emptyList())
        PlayedLinkCache.remember(context, "k", Stream(url = "   "), emptyList())

        assertNull(PlayedLinkCache.get(context, "k"))
        assertNull(PlayedLinkCache.get(context, ""))
    }

    @Test
    fun `only the head of the source list is kept`() {
        val sources = (0..25).map { stream("https://cdn.example/$it.mp4") }

        PlayedLinkCache.remember(context, "cap", sources.first(), sources)

        val cached = PlayedLinkCache.get(context, "cap")
        assertNotNull(cached)
        assertEquals(10, cached!!.sources.size)
        assertEquals("https://cdn.example/0.mp4", cached.sources.first().url)
        assertEquals("https://cdn.example/9.mp4", cached.sources.last().url)
    }

    @Test
    fun `entries past the cap evict the oldest first`() {
        for (i in 0 until 31) {
            PlayedLinkCache.remember(context, "k$i", stream("https://cdn.example/$i.mp4"), emptyList())
        }

        assertNull("the oldest entry must be evicted", PlayedLinkCache.get(context, "k0"))
        assertNotNull("the newest entry must survive", PlayedLinkCache.get(context, "k30"))
    }

    @Test
    fun `an entry older than the ttl is a miss`() {
        seed(
            key = "stale",
            url = "https://cdn.example/stale.mp4",
            atMs = System.currentTimeMillis() - PlayedLinkCache.TTL_MS - 1_000L
        )

        assertNull(PlayedLinkCache.get(context, "stale"))
    }

    @Test
    fun `an entry inside the ttl is a hit`() {
        seed(
            key = "fresh",
            url = "https://cdn.example/fresh.mp4",
            atMs = System.currentTimeMillis() - 1_000L
        )

        assertEquals(
            "https://cdn.example/fresh.mp4",
            PlayedLinkCache.get(context, "fresh")?.played?.url
        )
    }

    @Test
    fun `forget removes only the named entry`() {
        PlayedLinkCache.remember(context, "a", stream("https://cdn.example/a.mp4"), emptyList())
        PlayedLinkCache.remember(context, "b", stream("https://cdn.example/b.mp4"), emptyList())

        PlayedLinkCache.forget(context, "a")

        assertNull(PlayedLinkCache.get(context, "a"))
        assertNotNull(PlayedLinkCache.get(context, "b"))
    }

    /**
     * Writes an entry straight into the store so its timestamp can be forged —
     * the one thing the public API cannot express, because `remember` always
     * stamps "now". The blob uses the store's own field names; every field but
     * the url and the timestamp has a default.
     */
    private fun seed(key: String, url: String, atMs: Long) {
        val blob = """{"entries":{"$key":{"played":{"url":"$url"},"atMs":$atMs}}}"""
        prefs().edit().putString("played_links_v1", blob).commit()
    }
}
