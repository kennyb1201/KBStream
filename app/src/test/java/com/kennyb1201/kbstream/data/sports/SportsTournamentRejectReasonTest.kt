package com.kennyb1201.kbstream.data.sports

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tournament EPG diagnostic's reject= token, pinned as behaviour.
 *
 * The token is what a logcat capture is read for: a tournament card showing
 * "not in your playlist" has to be attributable to ONE filter - not-in-playlist,
 * channel-name-mismatch, duplicate, or none - and `none` with an empty result is
 * its own answer (the bug is after filtering, not in it). There is no
 * time-window token: the tournament tier matches by name alone (its EPG lookup
 * reads no clock), so a row airing outside ESPN's own slot - a replay of a
 * finished round, which is the common case for golf - is a row the tier takes,
 * and the token has to agree.
 *
 * Because the reason is produced by the SAME code the tier uses (see
 * [SportsChannelMatcher.tournamentRejectReason]), the important thing these
 * tests pin is that the token agrees with the matcher's actual decision: a row
 * the token calls `none` is one [SportsChannelMatcher.matches] really takes.
 */
class SportsTournamentRejectReasonTest {

    private val hour = 60L * 60_000L

    /** A golf event starting now, named for the exact case in the report. */
    private fun event(dateMs: Long = NOW) = TournamentEvent(
        id = "401850916",
        league = "golf/pga",
        name = "Baycurrent Classic",
        dateMs = dateMs,
        state = GameState.UPCOMING,
        statusDetail = "Round 1",
        leaders = emptyList(),
        broadcastNames = emptyList(),
    )

    private fun program(
        title: String = "Baycurrent Classic",
        channelId: String = CHANNEL_ID,
        startMs: Long = NOW,
        endMs: Long = NOW + 3 * hour,
    ) = MatcherProgram(
        channelId = channelId,
        title = title,
        startMs = startMs,
        endMs = endMs,
    )

    private fun channel() = IptvChannel(
        id = CHANNEL_ID,
        name = "Golf Channel",
        displayName = "Golf Channel",
        streamUrl = "http://example.test/golf.ts",
        groupTitle = "Sports",
        logoUrl = null,
        tvgId = "golf",
        tvgName = null,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null,
    )

    private fun reason(
        program: MatcherProgram = program(),
        event: TournamentEvent = event(),
        channelInPlaylist: Boolean = true,
        alreadyMatched: Boolean = false,
    ) = SportsChannelMatcher.tournamentRejectReason(
        program = program,
        event = event,
        channelInPlaylist = channelInPlaylist,
        alreadyMatched = alreadyMatched,
    )

    @Test
    fun `a row whose channel is not in the playlist is not-in-playlist`() {
        assertEquals("not-in-playlist", reason(channelInPlaylist = false))
        // And that reason outranks every other: a wrong channel is the first
        // thing a capture has to rule out, whatever else the row is.
        assertEquals(
            "not-in-playlist",
            reason(
                program = program(title = "PGA Golf", startMs = NOW + 40 * hour),
                channelInPlaylist = false,
                alreadyMatched = true,
            )
        )
    }

    @Test
    fun `a row well outside the event's own slot is still none`() {
        // The Baycurrent case: ESPN dates a tournament with ONE broadcast slot,
        // yet the guide carries the event as replays and highlights across four
        // days. Half a day after the slot used to read `time-window`; it is now
        // a row the matcher takes, and the token has to say so.
        val replay = program(startMs = NOW + 12 * hour, endMs = NOW + 15 * hour)
        assertEquals("none", reason(program = replay))
        assertEquals(
            "and none is a row the matcher actually takes",
            listOf(CHANNEL_ID),
            SportsChannelMatcher.matches(
                event = event(),
                channels = listOf(channel()),
                programs = listOf(replay),
            ).map { it.id },
        )
    }

    @Test
    fun `a title that does not carry the event name is channel-name-mismatch`() {
        val reason = reason(program = program(title = "PGA Golf Round 1"))
        assertEquals("channel-name-mismatch", reason)
    }

    @Test
    fun `a row already claimed for the event is duplicate`() {
        val reason = reason(alreadyMatched = true)
        assertEquals("duplicate", reason)
    }

    @Test
    fun `a row that passes every filter is none`() {
        assertEquals("none", reason())
    }

    @Test
    fun `none is a row the matcher actually takes`() {
        // The contract between the token and the tier: if this ever disagrees,
        // the diagnostic is lying about the filter it names.
        assertEquals("none", reason())
        val taken = SportsChannelMatcher.matches(
            event = event(),
            channels = listOf(channel()),
            programs = listOf(program()),
        )
        assertEquals(listOf(CHANNEL_ID), taken.map { it.id })
    }

    private companion object {
        const val CHANNEL_ID = "golf-channel"
        const val NOW = 1_760_000_000_000L
    }
}
