package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3U playlists are hand-edited files from hundreds of providers, so the parser
 * is fed everything from textbook-clean output to lines with missing commas,
 * reused tvg-ids and quoted names containing commas. Each of those cases is
 * pinned here, because a mis-parse is invisible: the channel simply appears
 * with the wrong name, loses its logo, or vanishes entirely.
 */
class M3uParserTest {

    private val parser = M3uParser()

    private fun parse(body: String, name: String? = null) =
        parser.parse(content = body.trimIndent(), playlistName = name)

    // ── Names and attributes ─────────────────────────────────────────────

    @Test
    fun `a standard entry yields name, id, group and logo`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn.us" tvg-logo="http://img/cnn.png" group-title="News",CNN
            http://host/cnn.ts
            """
        ).channels.single()

        assertEquals("CNN", channel.name)
        assertEquals("tvg:cnn.us", channel.id)
        assertEquals("cnn.us", channel.tvgId)
        assertEquals("News", channel.groupTitle)
        assertEquals("http://img/cnn.png", channel.logoUrl)
        assertEquals("http://host/cnn.ts", channel.streamUrl)
    }

    @Test
    fun `a comma inside a quoted attribute does not end the name`() {
        // The comma that separates attributes from the name must be the first
        // one OUTSIDE quotes, or "News, World" swallows the channel name.
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, World" tvg-name="A, B",Real Name
            http://host/x.ts
            """
        ).channels.single()

        assertEquals("Real Name", channel.name)
        assertEquals("News, World", channel.groupTitle)
        assertEquals("A, B", channel.tvgName)
    }

    @Test
    fun `an unquoted comma in the name is kept`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1,News, Sports
            http://host/x.ts
            """
        ).channels.single()

        assertEquals("News, Sports", channel.name)
    }

    @Test
    fun `an entry with no comma falls back to its trailing text as the name`() {
        // Sloppy sources omit the comma. Those entries used to be dropped as
        // "Unknown Channel", which silently lost real channels.
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 CNN International
            http://host/cnn.ts
            """
        )

        assertEquals("CNN International", playlist.channels.single().name)
    }

    @Test
    fun `an attribute list with no comma still resolves the name from tvg-name`() {
        // The no-comma salvage must not mistake the attribute list for a name.
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn.us" tvg-name="CNN HD"
            http://host/cnn.ts
            """
        ).channels.single()

        assertEquals("CNN HD", channel.name)
        assertEquals("cnn.us", channel.tvgId)
    }

    @Test
    fun `a blank name falls back to tvg-name`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="Discovery" ,
            http://host/d.ts
            """
        ).channels.single()

        assertEquals("Discovery", channel.name)
    }

    @Test
    fun `display names are tidied but not otherwise rewritten`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,-=- Sky Sports 1 -=-
            http://host/s.ts
            """
        )

        assertEquals("=- Sky Sports 1 -=", playlist.channels.single().name)
    }

    // ── Playlist header ──────────────────────────────────────────────────

    @Test
    fun `header attributes supply the guide url and playlist name`() {
        val playlist = parse(
            """
            #EXTM3U url-tvg="http://epg/guide.xml" x-tvg-name="My Provider"
            #EXTINF:-1,A
            http://host/a.ts
            """
        )

        assertEquals("http://epg/guide.xml", playlist.epgUrl)
        assertEquals("My Provider", playlist.name)
    }

    @Test
    fun `an unquoted header attribute is still read`() {
        val playlist = parse(
            """
            #EXTM3U url-tvg=http://epg/guide.xml
            #EXTINF:-1,A
            http://host/a.ts
            """
        )

        assertEquals("http://epg/guide.xml", playlist.epgUrl)
    }

    @Test
    fun `an explicit playlist name overrides the header`() {
        val playlist = parse(
            """
            #EXTM3U x-tvg-name="From Header"
            #EXTINF:-1,A
            http://host/a.ts
            """,
            name = "Typed By User"
        )

        assertEquals("Typed By User", playlist.name)
    }

    // ── Per-entry headers ────────────────────────────────────────────────

    @Test
    fun `EXTVLCOPT lines supply per-entry request headers`() {
        // The canonical form: these lines carry the User-Agent and Referer many
        // providers require, and were being dropped as ordinary comments.
        val channel = parse(
            """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (Linux; Android 11)
            #EXTVLCOPT:http-referrer=http://provider.example/
            #EXTINF:-1,Sky Sports 1
            http://host/s.ts
            """
        ).channels.single()

        assertEquals("Mozilla/5.0 (Linux; Android 11)", channel.headers["User-Agent"])
        assertEquals("http://provider.example/", channel.headers["Referer"])
    }

    @Test
    fun `EXTVLCOPT lines after the EXTINF are applied too`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1,Sky Sports 1
            #EXTVLCOPT:http-user-agent=Player/1.0
            http://host/s.ts
            """
        ).channels.single()

        assertEquals("Player/1.0", channel.headers["User-Agent"])
    }

    @Test
    fun `the alternate referrer spellings all reach the Referer header`() {
        for (key in listOf("http-referrer", "http-referer", "referrer", "referer")) {
            val channel = parse(
                """
                #EXTM3U
                #EXTVLCOPT:${key}=http://ref.example/
                #EXTINF:-1,Ch
                http://host/c.ts
                """
            ).channels.single()

            assertEquals("key=$key", "http://ref.example/", channel.headers["Referer"])
        }
    }

    @Test
    fun `non-header EXTVLCOPT options are ignored`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTVLCOPT:network-caching=1000
            #EXTINF:-1,Ch
            http://host/c.ts
            """
        ).channels.single()

        assertTrue(channel.headers.isEmpty())
    }

    @Test
    fun `headers do not leak onto the next channel`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=OnlyThisOne/1.0
            #EXTINF:-1,With UA
            http://host/with.ts
            #EXTINF:-1,Without UA
            http://host/without.ts
            """
        ).channels

        assertEquals("OnlyThisOne/1.0", channels[0].headers["User-Agent"])
        assertTrue(channels[1].headers.isEmpty())
    }

    @Test
    fun `an EXTINF attribute wins over the EXTVLCOPT value for the same header`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=FromVlc/1.0
            #EXTINF:-1 user-agent="FromExtinf/2.0",Ch
            http://host/c.ts
            """
        ).channels.single()

        assertEquals("FromExtinf/2.0", channel.headers["User-Agent"])
    }

    // ── Filtering ────────────────────────────────────────────────────────

    @Test
    fun `keeper lines and instructional entries are dropped`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,#########################
            http://host/sep.ts
            #EXTINF:-1,Play this to learn how to use the app
            http://host/info.ts
            #EXTINF:-1 group-title="Information",Contact us
            http://host/contact.ts
            #EXTINF:-1,Real Channel
            http://host/real.ts
            """
        )

        assertEquals(listOf("Real Channel"), playlist.channels.map { it.name })
    }

    @Test
    fun `an entry whose line is not a playable url is dropped`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,Not A Stream
            just some text
            #EXTINF:-1,Real Channel
            http://host/real.ts
            """
        )

        assertEquals(listOf("Real Channel"), playlist.channels.map { it.name })
    }

    @Test
    fun `rtmp, udp and rtsp entries count as playable`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,Rtmp
            rtmp://host/live
            #EXTINF:-1,Udp
            udp://@239.0.0.1:1234
            #EXTINF:-1,Rtsp
            rtsp://host/stream
            #EXTINF:-1,Https
            https://host/s.ts
            """
        )

        assertEquals(4, playlist.channels.size)
    }

    @Test
    fun `an EXTINF with no following url produces no channel`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,Dangling
            """
        )

        assertTrue(playlist.channels.isEmpty())
    }

    // ── Identity ─────────────────────────────────────────────────────────

    @Test
    fun `an entry with no identifying attributes gets a stable url-derived id`() {
        val body = """
            #EXTM3U
            #EXTINF:-1,Nameless Source
            http://host/anon.ts
        """

        // Same input twice must produce the same id: the id keys Compose's item
        // tracking, so an unstable one makes channels flicker while scrolling.
        val first = parse(body).channels.single().id
        val second = parse(body).channels.single().id

        assertEquals(first, second)
        assertTrue(first.startsWith("url:"))
    }

    @Test
    fun `a provider channel id identifies the channel when tvg-id is absent`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 channel-id="12345",Ch
            http://host/c.ts
            """
        ).channels.single()

        assertEquals("tvg:12345", channel.id)
        assertEquals("12345", channel.providerChannelId)
    }

    @Test
    fun `the same stream listed twice is dropped`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn.us",CNN
            http://host/cnn.ts
            #EXTINF:-1 tvg-id="cnn.us",CNN
            http://host/cnn.ts
            """
        )

        assertEquals(1, playlist.channels.size)
    }

    @Test
    fun `a reused tvg-id for a different stream is disambiguated, not dropped`() {
        // Sloppy providers reuse one tvg-id across different streams. Both are
        // real channels, so both must survive with unique ids.
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="shared.id",Channel One
            http://host/one.ts
            #EXTINF:-1 tvg-id="shared.id",Channel Two
            http://host/two.ts
            """
        ).channels

        assertEquals(listOf("Channel One", "Channel Two"), channels.map { it.name })
        assertEquals(2, channels.map { it.id }.toSet().size)
        assertTrue(channels.all { it.id.startsWith("tvg:shared.id") })
    }

    @Test
    fun `catchup attributes are carried through`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup="default" catchup-days="7" catchup-source="http://h/dvr/`${'$'}{start}.ts",Ch
            http://host/c.ts
            """
        ).channels.single()

        assertEquals("default", channel.catchup)
        assertEquals("7", channel.catchupDays)
        assertEquals("http://h/dvr/`${'$'}{start}.ts", channel.catchupSource)
    }

    @Test
    fun `an empty playlist parses to no channels and no guide url`() {
        val playlist = parse("#EXTM3U")

        assertTrue(playlist.channels.isEmpty())
        assertNull(playlist.epgUrl)
        assertNull(playlist.name)
    }

    @Test
    fun `an id attribute identifies the channel when no EPG id is present`() {
        // `id` is a panel-internal id, not an EPG id, so it does not become the
        // tvg candidate — but it still has to produce a stable channel id.
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 id="777",Ch
            http://host/c.ts
            """
        ).channels.single()

        assertEquals("provider:777", channel.id)
        assertNull(channel.tvgId)
    }
}
