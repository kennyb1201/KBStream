package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.iptv.EpgTermLookup
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.likeTermsQuery
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.MatcherProgram
import com.kennyb1201.kbstream.data.sports.SportsChannelMatcher
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.SportsTier1Ledger
import com.kennyb1201.kbstream.data.sports.SportsTier1Trace
import com.kennyb1201.kbstream.data.sports.TeamVariants
import com.kennyb1201.kbstream.data.sports.TIER1_GATE_CHANNEL_BY_ID
import com.kennyb1201.kbstream.data.sports.TIER1_GATE_NONE_GRADE
import com.kennyb1201.kbstream.data.sports.Tier1Drop
import com.kennyb1201.kbstream.data.sports.Tier1GameTrace
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier-1 trace, the `LIKE` fallback behind it, and the report that carries
 * them.
 *
 * The field case this exists for (Kenny, 2026-10-29): Lightning vs Jets, and the
 * hub listed three ESPN+ feeds while the guide's own STRONG row for the working
 * TSN feed - "NHL Hockey - Winnipeg Jets at Tampa Bay Lightning" - was never
 * listed. Every gate between that row and the card drops it silently, so the
 * first attempt at an explanation was a theory (a weak-name grading rule) that
 * the row itself refuted. The contracts below pin the two things that make the
 * next miss explainable instead of arguable:
 *
 *  1. the grades the case turned on (both teams STRONG, a city alone WEAK),
 *  2. a ledger and a log line that NAME the gate that dropped a row, and a
 *     second search stage for the branch where the index answered nothing.
 *
 * There is no TV and no guide in CI, so the diagnostics wiring is read out of
 * the source, the way the hub's other contracts are.
 */
class SportsTier1TraceTest {

    private val puckDrop = 1_700_000_000_000L
    private val minute = 60_000L

    // ── The grades the field case turned on ───────────────────────────

    @Test
    fun `the row the photo showed grades STRONG, in either team order`() {
        val jetsFirst = tier1For(title = "NHL Hockey - Winnipeg Jets at Tampa Bay Lightning")
        assertEquals("both teams by name is the STRONG grade the field row earned", 1, jetsFirst.strong)
        assertEquals(0, jetsFirst.weak)
        assertEquals(0, jetsFirst.near)
        assertEquals(
            "and the feed it airs on is attributed to the guide's title",
            listOf("TSN3" to "guide-title"),
            jetsFirst.listed
        )

        val lightningFirst = tier1For(
            title = "NHL Hockey - Tampa Bay Lightning at Winnipeg Jets",
            away = lightning(),
            home = jets(),
        )
        assertEquals("the row names both sides whatever order the feed writes them in", 1, lightningFirst.strong)
    }

    @Test
    fun `a city-only title stays WEAK, which is the distinction the miss turned on`() {
        val trace = tier1For(title = "NHL Hockey - Winnipeg at Tampa Bay")
        assertEquals(
            "a city alone is not the game's name: the matcher grades it WEAK and keeps it behind real hits",
            1,
            trace.weak
        )
        assertEquals(0, trace.strong)
        assertEquals(
            "and a card playing it is labelled with that weaker grade, not as a title hit",
            listOf("TSN3" to "guide-weak"),
            trace.listed
        )
    }

    @Test
    fun `a row that names one side is recorded as a near miss, not silently dropped`() {
        val trace = tier1For(title = "NHL Hockey - Jets")
        assertEquals("one side named is NONE for matching purposes, and counted as the near miss it is", 1, trace.near)
        assertEquals(
            "and the row is named in the trace, with the grade as the gate: it is the shape a viewer expects to have matched",
            listOf(Tier1Drop(TIER1_GATE_NONE_GRADE, "tsn3", "NHL Hockey - Jets")),
            trace.drops
        )
    }

    @Test
    fun `a row whose channel is not in the playlist is a named drop`() {
        val trace = tier1For(
            title = "NHL Hockey - Winnipeg Jets at Tampa Bay Lightning",
            playlistChannel = null,
            programChannelId = "g-tsn",
        )
        assertEquals(
            "the guide has the row and the pass cannot resolve it - the gate that has to be visible",
            listOf(
                Tier1Drop(
                    TIER1_GATE_CHANNEL_BY_ID,
                    "g-tsn",
                    "NHL Hockey - Winnipeg Jets at Tampa Bay Lightning",
                )
            ),
            trace.drops
        )
        assertEquals("nothing is listed for a card with no resolvable feed", emptyList<String>(), trace.listed.map { it.first })
    }

    @Test
    fun `the tier that produced each listed feed is named`() {
        // A broadcast-name feed and a matchup-named feed, neither of which has a
        // guide row: exactly the ESPN+ style hits the field case listed beside
        // the one that was missing.
        val network = channel("espn", "ESPN")
        val matchup = channel("lp", "Jets @ Lightning")
        val fixture = game(broadcasts = listOf("ESPN"))
        val trace = SportsChannelMatcher.traceTier1(
            game = fixture,
            programs = emptyList(),
            channelById = mapOf(network.id to network, matchup.id to matchup),
            variants = variants(fixture),
            channelIndex = SportsChannelMatcher.ChannelIndex.of(listOf(network, matchup)),
            listed = listOf(network, matchup),
        )
        assertEquals("no guide rows at all means no grades", 0, trace.strong + trace.weak + trace.near)
        assertEquals(
            "the named network is attributed to the broadcast list, the matchup name to the channel",
            listOf("ESPN" to "network", "Jets @ Lightning" to "name-matchup"),
            trace.listed
        )
    }

    // ── The ledger ────────────────────────────────────────────────────

    @Test
    fun `the ledger reports exact totals and caps the detail, newest first`() {
        val ledger = SportsTier1Ledger()
        repeat(SportsTier1Ledger.MAX_LINES + 4) { index ->
            ledger.matchup(
                label = "matchup-$index",
                terms = listOf("a", "b"),
                rows = 2,
                kept = 1,
                fallback = index == 0,
                drops = listOf(Tier1Drop("GUIDE_INDEX", "ch-$index", "title-$index")),
            )
        }
        val block = ledger.render()
        assertTrue("the header is the block's first line", block.startsWith("sports tier1: matchups=14"))
        assertTrue("totals are exact, not capped", block.contains("rows=28") && block.contains("kept=14"))
        assertTrue("dropped counts every gate", block.contains("dropped=14"))
        assertTrue("and the fallback stage is counted", block.contains("fallback=1"))
        assertTrue(
            "the newest matchup is the first detail line, because a capture is about the game just missed",
            block.indexOf("matchup-13") in 0 until block.indexOf("matchup-12")
        )
        assertTrue("and the oldest are the ones left out", !block.contains("matchup-0 "))
        assertTrue(
            "a dropped row names its gate, its channel and its title",
            // ch=ch-13: the fixture's channel id IS "ch-13", and the line names
            // the row's own channel verbatim (a guide id, as the field rows are).
            // The old expectation said "ch=13", which the renderer never printed,
            // so this assertion failed every release build from v0.9.107 on.
            block.contains("drop GUIDE_INDEX ch=ch-13 \"title-13\"")
        )
    }

    @Test
    fun `a game's trace is rendered with its grades and its listed feeds`() {
        val ledger = SportsTier1Ledger()
        ledger.game(
            "Jets @ Lightning",
            Tier1GameTrace(
                strong = 2,
                weak = 1,
                near = 3,
                drops = emptyList(),
                listed = listOf("TSN3" to "guide-title", "ESPN+3" to "network"),
            )
        )
        val block = ledger.render()
        assertTrue(block.contains("game Jets @ Lightning strong=2 weak=1 near=3"))
        assertTrue(block.contains("TSN3=guide-title"))
        assertTrue(block.contains("ESPN+3=network"))
    }

    @Test
    fun `the last pass's block is what diagnostics reads`() {
        SportsTier1Trace.clear()
        assertNull("a process that has not matched yet has no block", SportsTier1Trace.block())
        SportsTier1Trace.record("sports tier1: matchups=0 games=0")
        assertEquals("sports tier1: matchups=0 games=0", SportsTier1Trace.block())
        SportsTier1Trace.record("sports tier1: matchups=1 games=1")
        assertEquals(
            "recording a pass replaces the previous one - the ledger promises the last pass only",
            "sports tier1: matchups=1 games=1",
            SportsTier1Trace.block()
        )
        SportsTier1Trace.clear()
    }

    // ── The LIKE fallback ─────────────────────────────────────────────

    @Test
    fun `the scan runs only when the index answered nothing`() = runBlocking {
        var scans = 0
        val served = EpgTermLookup(
            indexed = { _, _ -> listOf(row("c1", "NHL Hockey - Winnipeg Jets at Tampa Bay Lightning")) },
            like = { _, _ -> scans++; emptyList() },
        )
        val hit = served.rows(listOf("jets", "lightning"), 60)
        assertEquals("a matchup the index served keeps its rows", 1, hit.rows.size)
        assertFalse("and pays nothing for a scan", hit.fallback)
        assertEquals("no LIKE query is issued at all", 0, scans)

        var scanned: List<String>? = null
        val missed = EpgTermLookup(
            indexed = { _, _ -> emptyList() },
            like = { terms, limit -> scans++; scanned = terms; listOf(row("c2", "NHL Hockey - Winnipeg Jets at Tampa Bay Lightning")) },
        )
        val fallback = missed.rows(listOf("jets", "lightning"), 60)
        assertEquals("the empty answer is retried", 1, fallback.rows.size)
        assertTrue("and the stage that answered is reported", fallback.fallback)
        assertEquals("with the same terms the index was asked", listOf("jets", "lightning"), scanned)
    }

    @Test
    fun `the scan is bounded per pass, and never runs for a single team`() = runBlocking {
        var scans = 0
        val lookup = EpgTermLookup(
            indexed = { _, _ -> emptyList() },
            like = { _, _ -> scans++; emptyList() },
            maxFallbacks = 1,
        )
        repeat(3) { lookup.rows(listOf("jets", "lightning"), 60) }
        assertEquals("one scan a pass, however many matchups come back empty", 1, scans)
        assertEquals(0, lookup.fallbacksRemaining)

        val single = EpgTermLookup(
            indexed = { _, _ -> scans++; emptyList() },
            like = { _, _ -> scans++; emptyList() },
        )
        single.rows(listOf("jets"), 60)
        assertEquals("one team is not a matchup question: neither stage runs", 1, scans)

        lookup.resetBudget()
        assertEquals("a new pass gets its budget back", 1, lookup.fallbacksRemaining)
    }

    @Test
    fun `the LIKE statement asks for every term, in the title or the description`() {
        val query = likeTermsQuery(listOf("jets", "lightning"), fromMillis = 1_000L, limit = 60)
        assertTrue("a single-term query is not a matchup question", likeTermsQuery(listOf("jets"), 1_000L, 60) == null)
        val prepared = query!!
        assertEquals(
            "one title-or-description test per term, ANDed, so a row must name both sides somewhere",
            2,
            Regex("""\(p\.title LIKE \? ESCAPE '\\' OR p\.description LIKE \? ESCAPE '\\'\)""")
                .findAll(prepared.sql).count()
        )
        assertTrue("only programs that have not finished", prepared.sql.contains("p.endUtcMillis > ?"))
        assertTrue("soonest first, and limited, exactly as the indexed stage is", prepared.sql.contains("ORDER BY p.startUtcMillis ASC") && prepared.sql.contains("LIMIT ?"))
        assertTrue(
            "the bind arguments are the clock, then a pattern per column per term, then the limit",
            prepared.args.toList() == listOf<Any?>(1_000L, "%jets%", "%jets%", "%lightning%", "%lightning%", 60)
        )
    }

    // ── Wiring (read out of the source, as the hub's contracts do) ─────

    @Test
    fun `the hub traces every card and publishes the ledger`() {
        val viewModel = flat(VIEW_MODEL)
        assertTrue(
            "the pass owns one ledger, and the lookup's scan budget starts with it",
            viewModel.contains("val tier1 = SportsTier1Ledger()") && viewModel.contains("tier1Lookup.resetBudget()")
        )
        assertTrue(
            "every game is traced, matched or not - a miss is what the trace is for",
            viewModel.contains("tier1.game(") && viewModel.contains("SportsChannelMatcher.traceTier1(")
        )
        assertTrue(
            "the guide-index gate names what it dropped instead of returning silently",
            viewModel.contains("Tier1Drop(TIER1_GATE_GUIDE_INDEX, row.channelId, row.title)")
        )
        assertTrue(
            "and the finished pass is what the report reads",
            viewModel.contains("SportsTier1Trace.record(tier1.render())")
        )
        assertTrue(
            "the per-matchup lookup goes through the two-stage lookup, not the index alone",
            viewModel.contains("tier1Lookup.rows(terms, EPG_SEARCH_LIMIT)")
        )
        assertTrue(
            "and both stages answer into the SAME row list, so a fallback row is graded exactly like an indexed one",
            viewModel.contains("val rows = abbreviations?.rows?.takeIf { it.isNotEmpty() } ?: hit.rows")
        )
    }

    @Test
    fun `the report carries the block`() {
        val diagnostics = flat(DIAGNOSTICS)
        assertTrue(
            "COPY DIAGNOSTICS includes the ledger's block",
            diagnostics.contains("SportsTier1Trace.block()?.lines()?.forEach")
        )
        val ledger = flat(LEDGER)
        assertTrue(
            "and the drop log line names the gate, the channel and the title, so live logcat is as decisive as the report",
            ledger.contains("Log.d(TAG") && ledger.contains(": channel=") && ledger.contains("title=")
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private fun channel(id: String, name: String) = IptvChannel(
        id = id,
        name = name,
        displayName = name,
        streamUrl = "http://playlist.test/$id",
        groupTitle = null,
        logoUrl = null,
        tvgId = null,
        tvgName = null,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null,
    )

    private fun team(abbreviation: String, name: String, shortName: String, home: Boolean) = SportsTeam(
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = home,
        record = null,
        shortName = shortName,
        league = "hockey/nhl",
    )

    private fun jets() = team("WPG", "Winnipeg Jets", "Jets", home = false)

    private fun lightning() = team("TB", "Tampa Bay Lightning", "Lightning", home = true)

    private fun game(
        away: SportsTeam = jets(),
        home: SportsTeam = lightning(),
        broadcasts: List<String> = listOf("ESPN"),
    ) = SportsGame(
        id = "401",
        league = "hockey/nhl",
        name = "${away.displayName} at ${home.displayName}",
        dateMs = puckDrop,
        state = GameState.UPCOMING,
        statusDetail = "7:00 PM ET",
        away = away,
        home = home,
        broadcastNames = broadcasts,
    )

    private fun variants(game: SportsGame) = TeamVariants(
        away = SportsChannelMatcher.strongVariants(game.away),
        home = SportsChannelMatcher.strongVariants(game.home),
    )

    private fun program(title: String, channelId: String = "tsn3") = MatcherProgram(
        channelId = channelId,
        title = title,
        startMs = puckDrop,
        endMs = puckDrop + 150 * minute,
        description = null,
    )

    /** The trace for the field row, with the TSN feed in (or out of) the playlist. */
    private fun tier1For(
        title: String,
        playlistChannel: IptvChannel? = channel("tsn3", "TSN3"),
        programChannelId: String = "tsn3",
        away: SportsTeam = jets(),
        home: SportsTeam = lightning(),
    ): Tier1GameTrace {
        val fixture = game(away = away, home = home)
        val byId = playlistChannel?.let { mapOf(it.id to it) }.orEmpty()
        return SportsChannelMatcher.traceTier1(
            game = fixture,
            programs = listOf(program(title, channelId = programChannelId)),
            channelById = byId,
            variants = variants(fixture),
            channelIndex = SportsChannelMatcher.ChannelIndex.of(byId.values.toList()),
            listed = byId.values.toList(),
        )
    }

    private fun row(channelId: String, title: String) = EpgProgramRow(
        channelId = channelId,
        title = title,
        description = null,
        category = null,
        startUtcMillis = puckDrop,
        endUtcMillis = puckDrop + 150 * minute,
    )

    private fun flat(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val LEDGER = "com/kennyb1201/kbstream/data/sports/SportsTier1Trace.kt"
    }
}
