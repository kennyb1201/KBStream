package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The match pass, made measurable and progressive.
 *
 * `resolveMatches` is the one place in the app that can take 40-60 seconds on a
 * large provider, and the spec's answer is a profile-then-fix: four named stages,
 * counted the same way in the live log and in the diagnostics report, and cards
 * that flip to their channel as each match lands rather than after the last one.
 *
 * Two invariants are pinned hardest here, because both are easy to break by
 * accident later. The matcher itself must not change - same matches, just faster
 * - so the search entry point added for the hub's search field is checked to be
 * a separate door onto the same variant list rather than a new tier. And the
 * progressive publish must ADD to what is on screen instead of clearing it, or
 * every 30-second live tick would blink every settled card back to "Finding
 * channel…" while it re-matched.
 *
 * There is no TV in CI, so the wiring is read out of the source.
 */
class SportsMatchSpeedContractTest {

    private val model: String by lazy { flat(VIEW_MODEL) }
    private val matcher: String by lazy { flat(MATCHER) }
    private val perfTrace: String by lazy { flat(PERF_TRACE) }
    private val diagnostics: String by lazy { flat(DIAGNOSTICS) }

    /** A literal `$` in the Kotlin source being asserted about. */
    private val d = '$'

    private val resolve: String by lazy {
        slice(model, "private suspend fun resolveMatches(", "private suspend fun cachedGuideIndex(")
    }

    @Test
    fun `every stage is timed and logged under one greppable prefix`() {
        assertTrue(
            "the lineup read",
            resolve.contains("SPORTS PERF channels=${d}{channels.size} took=${d}{channelsMs}ms")
        )
        assertTrue(
            "the guide index",
            resolve.contains("SPORTS PERF guideIndex=${d}{guideIndex.size} took=${d}{guideMs}ms")
        )
        assertTrue(
            "the EPG query",
            resolve.contains("SPORTS PERF programs=${d}{programs.size} took=${d}{epgMs}ms")
        )
        assertTrue(
            "and the match loop",
            resolve.contains("SPORTS PERF matched=${d}{found.size} took=${d}{matchMs}ms")
        )
        assertTrue(
            "the live view stays, because it is how the next slowdown is diagnosed",
            resolve.contains("SPORTS MATCHES cards=${d}{games.size + events.size} matched=${d}{found.size}")
        )
    }

    @Test
    fun `all seven stages are recorded into PerfTrace, from the same measurements`() {
        listOf("sports.match.channels", "sports.match.guide_index", "sports.match.epg_query", "sports.match.match_loop")
            .forEach { label ->
                assertTrue(
                    "$label must be recorded for the diagnostics report",
                    resolve.contains("PerfTrace.record(\"$label\",")
                )
            }
        listOf("cards", "matched", "programs").forEach { label ->
            assertTrue(
                "$label is a count, and counts are recorded as counts",
                resolve.contains("PerfTrace.recordCount(\"sports.match.$label\",")
            )
        }
        assertTrue(
            "one measurement feeds both sinks, so the log and the report cannot disagree",
            resolve.contains("PerfTrace.record(\"sports.match.channels\", channelsMs)") &&
                resolve.contains("PerfTrace.record(\"sports.match.match_loop\", matchMs)")
        )
        assertTrue(
            "elapsedRealtime, not the wall clock: an NTP correction must not print a negative stage",
            resolve.contains("SystemClock.elapsedRealtime()") &&
                !resolve.contains("System.currentTimeMillis()")
        )
    }

    @Test
    fun `counts are recorded as counts so they cannot pose as slow durations`() {
        val recordCount = slice(perfTrace, "fun recordCount(", "private fun add(")
        assertFalse(
            "a card count of thousands is not a multi-second stall and must not be forwarded as one",
            recordCount.contains("SentryPerf")
        )
        val record = slice(perfTrace, "fun record(label: String, ms: Long", "fun recordCount(")
        assertTrue(
            "while a real duration still is (the slow-sample forward stays on record)",
            record.contains("SentryPerf.slowSample(label, ms)")
        )
    }

    @Test
    fun `the loop's per-pass invariants are hoisted out of it`() {
        assertTrue(
            "the pass's channel id map is built once, not per game - 60+ rebuilds of the " +
                "same map was the first of the two costs",
            resolve.contains("val channelById = channels.associateBy { it.id }")
        )
        assertTrue(
            "and a game's two name-form lists once per game, not once per program",
            resolve.contains("SportsChannelMatcher.strongVariants(game.away)") &&
                resolve.contains("SportsChannelMatcher.strongVariants(game.home)")
        )
        val beforeLoops = resolve.substringBefore("games.forEach")
        assertTrue(
            "both live BEFORE the first card is matched",
            beforeLoops.contains("val channelById = channels.associateBy { it.id }") &&
                beforeLoops.contains("SportsChannelMatcher.strongVariants(game.away)")
        )
        assertFalse(
            "and the per-card loops never rebuild a team's forms",
            resolve.substringAfter("games.forEach").contains("strongVariants(")
        )
        assertTrue(
            "a superseded pass stops at the top of each iteration rather than running to the end",
            resolve.contains("games.forEach { game -> ensureActive()") &&
                resolve.contains("events.forEach { event -> ensureActive()")
        )
    }

    @Test
    fun `the tier that cost a second a card normalizes the playlist once a pass`() {
        // The measured bug this pins: the per-card `net=` stage of the SPORTS PERF
        // line was read as a network call, and it is not one - it is the
        // broadcast/RSN tier, a local scan of the channel list, and it cost 1-3
        // SECONDS a card because every card re-normalized every channel's name
        // before comparing anything. On the 40k-channel playlist the benchmark
        // runs over, sixty cards went from ~11s to ~10ms of tier time once the
        // names were hoisted out of the loop (plus ~120ms once for the index).
        assertTrue(
            "the pass's channel names are normalized once, beside the id map it already builds once",
            resolve.contains("val channelIndex = SportsChannelMatcher.ChannelIndex.of(channels)")
        )
        val beforeLoops = resolve.substringBefore("games.forEach")
        assertTrue(
            "and it is built before the first card is matched",
            beforeLoops.contains("val channelIndex = SportsChannelMatcher.ChannelIndex.of(channels)")
        )
        assertTrue(
            "both card shapes hand that one index to the matcher rather than letting each call build its own",
            resolve.contains(
                "matches(game, channels, programs, remembered, channelById, " +
                    "gameVariants.getValue(game.id), channelIndex)"
            ) &&
                resolve.contains(
                    "matches(event, channels, programs, remembered, channelById, channelIndex)"
                )
        )
        assertTrue(
            "and the broadcast tier reads the index it was handed",
            matcher.contains("game.broadcastNames + rsnNetworks(game.home) + rsnNetworks(game.away)") &&
                matcher.contains("networks.flatMap { channelIndex.channelsFor(it) }") &&
                matcher.contains("event.broadcastNames.flatMap { channelIndex.channelsFor(it) }")
        )
        val index = slice(matcher, "internal class ChannelIndex", "private val RSN_BRANDS")
        assertTrue(
            "the names are normalized in the index's own build, from the same fields the tier used",
            index.contains("canonicalNetwork(name)") &&
                index.contains("words(name).firstOrNull()") &&
                index.contains("compact(name).length")
        )
        val scan = slice(index, "private fun networkChannels(network: String)", "companion object {")
        assertTrue(
            "while the per-channel scan compares precomputed strings",
            scan.contains("fact.strength(target)") && scan.contains("fact.length")
        )
        assertFalse(
            "and never re-derives a name it was already handed: that loop is the whole cost",
            scan.contains("compact(") || scan.contains("words(") || scan.contains("ifBlank")
        )
        assertTrue(
            "a network family is answered once a pass, not once per card that names it",
            index.contains("byNetwork.getOrPut(network) { networkChannels(network) }")
        )
    }

    @Test
    fun `the diagnostics report carries the sports line`() {
        assertTrue(
            "the line is built from the same label family",
            diagnostics.contains("internal fun sportsLine(): String?") &&
                diagnostics.contains("PerfTrace.latestByPrefix(SPORTS_PREFIX)")
        )
        assertTrue(
            "and a report includes it, so a couch-side issue arrives with the numbers",
            diagnostics.contains("sportsLine()?.let { report.appendLine(it) }")
        )
        assertTrue(
            "counts and timings only: never a channel name or a game title",
            diagnostics.contains("cards, ") && diagnostics.contains("programs, ") &&
                !diagnostics.contains("channelName")
        )
    }

    @Test
    fun `matches publish as they land, and never by clearing what is on screen`() {
        assertTrue(
            "a game's match is published the moment it is found",
            resolve.contains("_matches.update { it + (game.id to feeds) }")
        )
        assertTrue(
            "and a tournament's too",
            resolve.contains("_matches.update { it + (event.id to feeds) }")
        )
        assertTrue(
            "the ends of the pass still publish the whole map, which is what reconciles it",
            resolve.contains("_matches.value = matches")
        )
        val loop = slice(
            resolve,
            "val found = HashMap<String, List<IptvChannel>>()",
            "PerfTrace.recordCount(\"sports.match.cards\""
        )
        assertFalse(
            "the loop must never clear the map: on the live tick that would blink every settled card",
            loop.contains("_matches.value = emptyMap()")
        )
    }

    @Test
    fun `the lineup read starts with the ViewModel rather than behind the ESPN fetch`() {
        assertTrue(
            "the read is one shared job, so two passes cannot read the playlist twice",
            model.contains("private var channelsJob: Deferred<List<IptvChannel>>? = null")
        )
        assertTrue(
            "started from init, before the refresh that follows it",
            model.contains("init {") && model.contains("prewarmLineup() refresh()")
        )
        assertTrue(
            "armed before anything asks for it",
            model.contains("private fun prewarmLineup() { if (channelsJob != null || playlistChannels != null) return startLineupRead() }")
        )
        assertTrue(
            "and dropped by the NO LINEUP retry, so that button is a real re-read",
            model.contains("channelsJob = null prewarmLineup() refresh()")
        )
        assertTrue(
            "the guide index stays cached for the ViewModel's life - it was the second stage the spec named",
            model.contains("private suspend fun cachedGuideIndex(channels: List<IptvChannel>): Map<String, IptvChannel> { guideIndexCache?.let { return it }")
        )
    }

    @Test
    fun `the matcher is unchanged, and search is a separate door onto the same names`() {
        val tiers = slice(matcher, "fun matches( game: SportsGame,", "// ── Search")
        assertFalse(
            "the tiers know nothing about the search field",
            tiers.contains("matchesQuery")
        )
        // The variant list the tiers read DOES now include the feed's own short
        // name, and that is a deliberate reversal of what this used to pin. The
        // tiers previously read only the full name, the abbreviation and the
        // full name's last word - and for whole sports that is not the name a
        // guide uses: the last word of "Washington Huskies" is "Huskies", of
        // "Leeds United" it is "United", neither of which a guide titles a game
        // with. Those games could not be found on any channel, however many were
        // carrying them. The short name is ADDITIVE, so every spelling that
        // matched before still does.
        // `strongVariants` is internal now: the hub precomputes a game's forms
        // once per pass rather than letting the tiers rebuild them per program.
        val variants = slice(matcher, "fun strongVariants(", "private fun shortFormNames(")
        assertTrue(
            "the tiers must read the feed's own short name - a guide titles a game " +
                "\"Washington\", not \"Huskies\"",
            variants.contains("team.shortName")
        )
        assertTrue(
            "and it is additive: the full name and the last word are still read",
            variants.contains("team.displayName") &&
                variants.contains("team.displayName.trim().split(' ').lastOrNull()")
        )
        assertTrue(
            "the search entry point reuses that list rather than re-implementing it",
            matcher.contains("private fun searchVariants(team: SportsTeam): List<List<String>> = buildList { addAll(strongVariants(team))")
        )
        val searchDoor = slice(matcher, "private fun searchVariants(", "private fun matchesWords(")
        assertFalse(
            "and search does not keep a second, drifting copy of the short name now that the " +
                "tiers read it",
            searchDoor.contains("shortName")
        )
        assertEquals(
            "one search entry point per card shape, and no more",
            2,
            Regex("fun matchesQuery\\(").findAll(matcher).count()
        )
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
    }

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
        const val MATCHER = "com/kennyb1201/kbstream/data/sports/SportsChannelMatcher.kt"
        const val PERF_TRACE = "com/kennyb1201/kbstream/data/reporting/PerfTrace.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
    }
}
