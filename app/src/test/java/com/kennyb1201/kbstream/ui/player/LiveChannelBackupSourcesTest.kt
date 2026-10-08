package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.liveChannelScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A live channel launch's source list, which is how a game gets backups.
 *
 * The player already walks the ranked sources it is launched with, so a second
 * feed needs no player change at all - it needs the LAUNCH to carry it. These
 * are the two places that has to be true: the launch builds one source per
 * channel (each with that channel's own headers, since a switch to a backup
 * would otherwise drop the provider's Referer and fail exactly like the feed it
 * replaces), and the payload the player reads back keeps them per source.
 */
class LiveChannelBackupSourcesTest {

    private fun channel(
        id: String,
        name: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ) = IptvChannel(
        id = id,
        name = name,
        displayName = name,
        streamUrl = url,
        groupTitle = null,
        logoUrl = null,
        tvgId = null,
        tvgName = null,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null,
        headers = headers,
    )

    @Test
    fun `the chosen feed is the first source and the backups follow it`() {
        val primary = channel("a", "ESPN", "http://playlist.test/espn")
        val backup = channel(
            id = "b",
            name = "ESPN2",
            url = "http://playlist.test/espn2",
            headers = mapOf("Referer" to "http://provider.test/")
        )

        val launch = liveChannelScreen(
            channel = primary,
            backups = listOf(backup),
            epgIconUrl = null,
            returnTo = Screen.Home
        )

        assertEquals("http://playlist.test/espn", launch.url)
        assertEquals(
            "the chosen feed, then the backup - the order the player walks",
            listOf("http://playlist.test/espn", "http://playlist.test/espn2"),
            launch.sources.map { it.url }
        )
        assertEquals("the backup is named by its own channel", "ESPN2", launch.sources[1].name)
        assertEquals(
            "and keeps its own headers, so the switch still reaches the host",
            mapOf("Referer" to "http://provider.test/"),
            launch.sources[1].headers
        )
        assertEquals(
            "the session still starts with the chosen feed's headers",
            emptyMap<String, String>(),
            launch.streamHeaders
        )
    }

    @Test
    fun `a guide click still launches exactly the channel it was given`() {
        val only = channel("a", "BBC One", "http://playlist.test/bbc")

        val launch = liveChannelScreen(channel = only, epgIconUrl = null, returnTo = Screen.Guide)

        assertEquals(listOf("http://playlist.test/bbc"), launch.sources.map { it.url })
    }

    @Test
    fun `a source's own headers survive the payload the player reads`() {
        val raw = """
            [{"name":"ESPN2","url":"http://playlist.test/espn2",
              "headers":{"Referer":"http://provider.test/","User-Agent":"VLC/3.0"}}]
        """.trimIndent()

        val parsed = parseSourcesJson(raw)

        assertEquals(1, parsed.size)
        assertEquals(
            mapOf("Referer" to "http://provider.test/", "User-Agent" to "VLC/3.0"),
            parsed.single().headers
        )
    }

    @Test
    fun `a payload written before the field existed still parses`() {
        val parsed = parseSourcesJson("""[{"name":"ESPN","url":"http://playlist.test/espn"}]""")

        assertEquals(1, parsed.size)
        assertNull("an older payload has no headers, not empty ones", parsed.single().headers)
    }
}
