package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules decide whether a playlist channel matches a guide channel, and a
 * wrong answer is invisible: the channel shows no programs at all, or borrows
 * another channel's. M3U and XMLTV spellers disagree about case, decorations,
 * `&`/`+` and punctuation, so each of those cases is pinned.
 */
class EpgKeysTest {

    // ── Lookup keys (what a channel is matched by) ────────────────────────

    @Test
    fun `lookup keys fold case and remove whitespace outright`() {
        // Whitespace is deleted, not collapsed: "BBC One" and "BBCOne" must
        // reach the same key, because guides use both spellings.
        assertEquals("bbcone", epgLookupKey("  BBC   One  "))
        assertEquals(epgLookupKey("Sky Sports F1"), epgLookupKey("skysports f1"))
    }

    @Test
    fun `lookup keys drop bracketed and parenthesized decorations`() {
        assertEquals("amc", epgLookupKey("AMC (US) [HD]"))
        assertEquals("bbcone", epgLookupKey("BBC One [East]"))
    }

    @Test
    fun `lookup keys expand ampersands and pluses before stripping`() {
        // Both spellings have to land on the same key or the channel is
        // unmatched, so the expansion happens before whitespace is removed.
        assertEquals(epgLookupKey("A and E"), epgLookupKey("A&E"))
        assertEquals(epgLookupKey("Discovery plus"), epgLookupKey("Discovery+"))
        assertEquals("aande", epgLookupKey("A&E"))
        assertEquals("discoveryplus", epgLookupKey("Discovery+"))
    }

    @Test
    fun `lookup keys keep dots so dotted channel ids stay addressable`() {
        assertEquals("bbc1.uk", epgLookupKey("bbc1.uk"))
        assertEquals("bbc1.co.uk", epgLookupKey(" BBC1.CO.UK "))
    }

    @Test
    fun `lookup keys drop other punctuation`() {
        assertEquals("skysports1", epgLookupKey("Sky Sports 1!"))
        assertEquals("attheraces", epgLookupKey("At the Races?"))
    }

    // ── Alias keys (every spelling a guide channel is findable under) ─────

    @Test
    fun `alias keys cover the raw id, the normalized id and each display name`() {
        val keys = epgAliasKeys("BBC1.uk", listOf("BBC One", "BBC1"))

        assertTrue(keys.contains("BBC1.uk"))
        assertTrue(keys.contains("bbc1.uk"))
        assertTrue(keys.contains("BBC One"))
        assertTrue(keys.contains("bbc one"))
        // "BBC One" simplifies to a run-together form, which is what catches
        // the single-word spellings guides actually use.
        assertTrue(keys.contains("bbcone"))
    }

    @Test
    fun `alias keys drop blanks and collapse duplicates`() {
        val keys = epgAliasKeys("id", listOf("", "   ", "ID", "id"))

        assertEquals(listOf("id", "ID"), keys)
    }

    @Test
    fun `simplified names strip decorations and quality qualifiers`() {
        assertEquals("bbcone", simplifyEpgChannelName("BBC One HD (East)"))
        assertEquals("bbcone", simplifyEpgChannelName("BBC ONE [SD]"))
        assertEquals("discoveryplus", simplifyEpgChannelName("Discovery+ 4K"))
        // Region and country suffixes are qualifiers too, so a US/UK feed folds
        // onto the same simplified key as the base channel.
        assertEquals("amc", simplifyEpgChannelName("AMC US"))
        assertEquals("bbc", simplifyEpgChannelName("BBC UK"))
    }

    @Test
    fun `a name with nothing left to key on returns null`() {
        assertNull(simplifyEpgChannelName(""))
        assertNull(simplifyEpgChannelName("   "))
        assertNull(simplifyEpgChannelName("[HD]"))
        assertNull(simplifyEpgChannelName("(US) 4K"))
    }

    @Test
    fun `normalized channel keys only fold case and whitespace`() {
        // Deliberately milder than the simplified form: a guide may legitimately
        // list "Sky Sports 1" separately from "Sky Sports 2".
        assertEquals("sky sports 1", normalizeEpgChannelKey(" Sky Sports 1 "))
        assertEquals("sky sports 1", normalizeEpgChannelKey("SKY SPORTS 1"))
    }

    // ── Program keys (what a program row is STORED and READ under) ────────

    @Test
    fun `program keys fold case and whitespace and nothing else`() {
        // This is the identity `epg_programs.channelId` holds: the importer's
        // own normalization. Punctuation, dots and spaces all survive, because
        // a read has to reproduce the write byte for byte.
        assertEquals("espn.us", epgProgramChannelKey("  ESPN.us "))
        assertEquals("bbc one hd", epgProgramChannelKey("BBC One HD"))
        assertEquals("a&e", epgProgramChannelKey("A&E"))
    }

    @Test
    fun `program keys are not the matcher's lookup keys`() {
        // The two must not be confused: matching may fold spaces and
        // punctuation away, reading back what the importer wrote may not.
        assertEquals("bbconehd", epgLookupKey("BBC One HD"))
        assertEquals("bbc one hd", epgProgramChannelKey("BBC One HD"))
        assertNotEquals(epgProgramChannelKey("BBC One HD"), epgLookupKey("BBC One HD"))
    }

    @Test
    fun `a mixed-case guide id only matches its programs through the program key`() {
        // The regression this exists for: programs are stored lowercased and
        // the DAO matches `channelId IN (...)` case-sensitively, so a query
        // handed the guide's raw id found nothing -- a matched channel whose
        // programs were imported, showing "No program data" forever.
        val rawGuideId = "ESPN.us"
        assertNotEquals(rawGuideId, epgProgramChannelKey(rawGuideId))
        assertEquals("espn.us", epgProgramChannelKey(rawGuideId))
    }

    // ── The matcher: which guide channel a playlist channel resolves to ───

    @Test
    fun `a decorated name falls through to the simplified pass`() {
        // The guide lists the bare name; the playlist carries a qualifier the
        // guide never spells. Pass 2 misses, pass 3 ("espn2") hits.
        val match = matchEpgChannel(
            idCandidates = listOf(null, null),
            nameCandidates = listOf("ESPN2 HD", "ESPN2 HD"),
            byId = emptyMap<String, String>(),
            byName = mapOf("espn2" to "guide-espn2")
        )

        assertEquals("guide-espn2", match?.first)
        assertEquals(EpgMatchKind.SIMPLIFIED, match?.second)
    }

    @Test
    fun `an id or an exact name still wins over the simplified pass`() {
        val byId = mapOf("bbc1.uk" to "guide-id")
        val byName = mapOf("bbcone" to "guide-simplified", "itv1" to "guide-itv")

        // tvg-id is the strongest signal there is.
        assertEquals(
            "guide-id" to EpgMatchKind.ID,
            matchEpgChannel(listOf("BBC1.uk"), listOf("BBC One"), byId, byName)
        )

        // No id hit: the exact name key wins, before the simplified form runs.
        assertEquals(
            "guide-itv" to EpgMatchKind.NAME,
            matchEpgChannel(listOf(null), listOf("ITV1", "ITV One"), byId, byName)
        )
    }

    @Test
    fun `the simplified pass still respects the guide's own spelling`() {
        // "Sky Sports 2 HD" must not fold onto "Sky Sports 1": only the exact
        // simplified key resolves, and a miss stays a miss (no wrong channel).
        val byName = mapOf("skysports1" to "guide-1", "skysports2" to "guide-2")

        assertEquals(
            "guide-2" to EpgMatchKind.SIMPLIFIED,
            matchEpgChannel(
                listOf(null), listOf("Sky Sports 2 HD"), emptyMap<String, String>(), byName
            )
        )
        assertNull(
            matchEpgChannel(
                listOf(null), listOf("Sky Sports 3 HD"), emptyMap<String, String>(), byName
            )
        )
    }

    @Test
    fun `blank candidates never match`() {
        assertNull(
            matchEpgChannel(
                idCandidates = listOf(null, "  "),
                nameCandidates = listOf(null, "", "   "),
                byId = mapOf("" to "x"),
                byName = mapOf("" to "x")
            )
        )
    }

    // ── Numbered parallel feeds (a channel that only exists with a number) ──

    private val abcNewsLive3 = channel(
        "anl3",
        name = "ABC News Live 3",
        tvgId = "ABCNewsLive3.us@SD"
    )

    @Test
    fun `a numbered feed reaches the base channel's guide entry`() {
        // Field case (2026-10-25, phone): ten distinct ABC News Live feeds with
        // ten distinct streams, and one guide channel named "ABC News Live".
        // Passes 1-3 all miss on the trailing digit, and there is no callsign
        // here to offer - the base forms have to be.
        assertEquals(emptySet<String>(), playlistCallsignKeys(abcNewsLive3))

        assertEquals(
            "guide-anl" to EpgMatchKind.NUMBERED_VARIANT,
            matchEpgChannel(
                idCandidates = listOf(abcNewsLive3.tvgId),
                nameCandidates = listOf(abcNewsLive3.tvgName, abcNewsLive3.name),
                byId = mapOf("abcnewslive.us" to "guide-anl"),
                byName = mapOf("abcnewslive" to "guide-anl")
            )
        )
    }

    @Test
    fun `a two-digit feed number is stripped as one run`() {
        val anl10 = channel("anl10", name = "ABC News Live 10", tvgId = "ABCNewsLive10.us@SD")

        assertEquals(
            "guide-anl" to EpgMatchKind.NUMBERED_VARIANT,
            matchEpgChannel(
                idCandidates = listOf(anl10.tvgId),
                nameCandidates = listOf(anl10.tvgName, anl10.name),
                byId = emptyMap(),
                // The guide carries the base channel only, never `...10`.
                byName = mapOf("abcnewslive" to "guide-anl")
            )
        )
    }

    @Test
    fun `amp 2 reaches the guide's amp channel`() {
        val amp2 = channel("amp2", name = "AMP 2", tvgId = "AMP2.us@SD")

        assertEquals(
            "guide-amp" to EpgMatchKind.NUMBERED_VARIANT,
            matchEpgChannel(
                idCandidates = listOf(amp2.tvgId),
                nameCandidates = listOf(amp2.tvgName, amp2.name),
                byId = emptyMap(),
                byName = mapOf("amp" to "guide-amp")
            )
        )
    }

    @Test
    fun `a channel with its own guide entry keeps it`() {
        // The counter-rule the exact-name pass exists for: a trailing number IS
        // identity for these, so ESPN2 and Sky Sports 1 must never fall through
        // to `espn` / `skysports`.
        val espn2 = channel("espn2", name = "ESPN2", tvgId = "ESPN2.us")
        assertEquals(
            "guide-espn2" to EpgMatchKind.NAME,
            matchEpgChannel(
                idCandidates = listOf(espn2.tvgId),
                nameCandidates = listOf(espn2.tvgName, espn2.name),
                byId = emptyMap(),
                byName = mapOf("espn2" to "guide-espn2", "espn" to "guide-espn")
            )
        )

        val sky1 = channel("sky1", name = "Sky Sports 1", tvgId = "SkySports1.uk")
        assertEquals(
            "guide-sky1" to EpgMatchKind.NAME,
            matchEpgChannel(
                idCandidates = listOf(sky1.tvgId),
                nameCandidates = listOf(sky1.tvgName, sky1.name),
                byId = emptyMap(),
                byName = mapOf("skysports1" to "guide-sky1", "skysports2" to "guide-sky2")
            )
        )
    }

    @Test
    fun `a stripped base that is not a guide channel matches nothing`() {
        // The last-resort pass only ever reaches a channel that exists: it
        // invents no guide entry, so a miss stays a miss.
        val mystery = channel("mystery3", name = "Mystery Feed 3", tvgId = "MysteryFeed3.us")

        assertNull(
            matchEpgChannel(
                idCandidates = listOf(mystery.tvgId),
                nameCandidates = listOf(mystery.tvgName, mystery.name),
                byId = emptyMap(),
                byName = mapOf("otherchannel" to "guide-other")
            )
        )
    }

    @Test
    fun `a stripped base shorter than three characters is not a key`() {
        assertEquals(emptyList<String>(), numberedVariantKeys("TV 5"))
        assertEquals(emptyList<String>(), numberedVariantKeys("7"))
        assertEquals("no trailing digits, so nothing to restate", emptyList<String>(), numberedVariantKeys("AMC"))
        // Documented edge: `Channel 5` with no guide entry of its own may borrow
        // a guide channel literally named "Channel".
        assertEquals(listOf("channel"), numberedVariantKeys("Channel 5"))
    }

    @Test
    fun `the callsign still wins over a numbered variant`() {
        // A numbered local feed whose base name also resolves: which station
        // beats which feed family, every time - the callsign runs first.
        val wftv2 = channel("wftv2", name = "ABC WFTV 2", tvgId = "ABC.us@WFTV2")
        val callsignKeys = playlistCallsignKeys(wftv2).toList()
        assertEquals(listOf("wftv2"), callsignKeys)

        assertEquals(
            "guide-callsign" to EpgMatchKind.CALLSIGN,
            matchEpgChannel(
                idCandidates = listOf(wftv2.tvgId),
                nameCandidates = listOf(wftv2.tvgName, wftv2.name),
                byId = mapOf("wftv2" to "guide-callsign"),
                byName = mapOf("abcwftv" to "guide-base"),
                callsignCandidates = callsignKeys
            )
        )
    }

    @Test
    fun `a numbered feed keeps the base guide channel through the prune`() {
        val keys = playlistEpgMatchKeys(listOf(abcNewsLive3))

        assertTrue("abcnewslive" in keys)
        assertTrue(
            "the guide's only reachable alias is its display name",
            guideChannelCanMatch(
                channelId = "ABCNewsLive.us",
                aliasKeys = epgAliasKeys("ABCNewsLive.us", listOf("ABC News Live")),
                playlistKeys = keys
            )
        )
        // Load-bearing, not incidental: without the stripped key the importer
        // drops the channel's programmes and pass 5 finds nothing to match.
        assertFalse(
            guideChannelCanMatch(
                channelId = "ABCNewsLive.us",
                aliasKeys = epgAliasKeys("ABCNewsLive.us", listOf("ABC News Live")),
                playlistKeys = keys - "abcnewslive"
            )
        )
    }

    // ── Callsign keys (locals, and only locals) ──────────────────────────

    @Test
    fun `a local's callsign comes from its tvg-id feed suffix`() {
        // Field report (2026-10-25): the locals guide identifies a station by
        // callsign alone, and iptv-org spells the same station
        // `<Network>.us@<CALLSIGN>` - so the suffix is the callsign.
        assertEquals(
            setOf("kmtv", "kmtvtv", "kmtvdt", "kmtvld", "kmtvcd"),
            playlistCallsignKeys(channel("c1", name = "CBS KMTV-TV", tvgId = "CBS.us@KMTVTV"))
        )
        assertEquals(
            "KGTV strips to kg, below the floor, so kgtv is the core",
            setOf("kgtv", "kgtvtv", "kgtvdt", "kgtvld", "kgtvcd"),
            playlistCallsignKeys(channel("c2", name = "ABC KGTV", tvgId = "ABC.us@KGTV"))
        )
        assertEquals(
            setOf("wftv", "wftvtv", "wftvdt", "wftvld", "wftvcd"),
            playlistCallsignKeys(channel("c3", name = "ABC WFTV", tvgId = "ABC.us@WFTV"))
        )
    }

    @Test
    fun `the keys are ordered core, then tv, dt, ld, cd`() {
        // Load-bearing: the matcher takes the first candidate that hits, and a
        // guide carrying both WFTV-DT and WFTV-LD must resolve the full-power
        // DT feed. Sorting these alphabetically would put `cd` before `dt`
        // and silently change which feed wins.
        assertEquals(
            listOf("wftv", "wftvtv", "wftvdt", "wftvld", "wftvcd"),
            playlistCallsignKeys(channel("c", name = "ABC WFTV", tvgId = "ABC.us@WFTV")).toList()
        )
    }

    @Test
    fun `a national feed's suffix is never a callsign`() {
        // `East`, `HD` and a bare network are what a national feed carries; the
        // k/w guard is what keeps the tier from keying on them.
        assertEquals(
            emptySet<String>(),
            playlistCallsignKeys(channel("c4", name = "ABC East", tvgId = "ABC.us@East"))
        )
        assertEquals(
            emptySet<String>(),
            playlistCallsignKeys(channel("c5", name = "A&E East", tvgId = "AE.us@East"))
        )
        assertEquals(
            emptySet<String>(),
            playlistCallsignKeys(channel("c6", name = "Channel HD", tvgId = "Channel.uk@HD"))
        )
    }

    @Test
    fun `a name token stands in only when the id has no feed suffix`() {
        assertEquals(
            setOf("kmtv", "kmtvtv", "kmtvdt", "kmtvld", "kmtvcd"),
            playlistCallsignKeys(channel("c7", name = "CBS KMTV-TV", tvgId = null))
        )
        // The id's suffix is authoritative: a name can never override it.
        assertEquals(
            setOf("kgtv", "kgtvtv", "kgtvdt", "kgtvld", "kgtvcd"),
            playlistCallsignKeys(channel("c8", name = "CBS KMTV-TV", tvgId = "ABC.us@KGTV"))
        )
    }

    @Test
    fun `the guard keeps fragments and non-US shapes out`() {
        assertEquals(
            "two characters is a fragment, not a callsign",
            emptySet<String>(),
            playlistCallsignKeys(channel("c9", name = "KW", tvgId = "Net.us@KW"))
        )
        assertEquals(
            "longer than a callsign plus its station suffix",
            emptySet<String>(),
            playlistCallsignKeys(channel("c10", name = "Too Long", tvgId = "Net.us@KMTVTVX"))
        )
        assertEquals(
            "a Mexican station keeps today's behavior: no key, no regression",
            emptySet<String>(),
            playlistCallsignKeys(channel("c11", name = "XEW", tvgId = "Net.mx@XEW"))
        )
        assertEquals(
            "and the network name is not a callsign either",
            emptySet<String>(),
            playlistCallsignKeys(channel("c12", name = "ABC", tvgId = "ABC.us@ABC"))
        )
    }

    @Test
    fun `every station suffix a guide may spell is offered`() {
        listOf(
            "KMTVTV" to "kmtv",
            "WABC-DT" to "wabc",
            "KNBC-LD" to "knbc",
            "KCAL-CD" to "kcal",
            "WTVQDT" to "wtvq",
            "WDIODT" to "wdio",
            "KSTPTV" to "kstp",
            "WSYX" to "wsyx"
        ).forEach { (callsign, core) ->
            assertEquals(
                "$callsign answers every suffix spelling of $core",
                setOf(core, core + "tv", core + "dt", core + "ld", core + "cd"),
                playlistCallsignKeys(channel("c", name = callsign, tvgId = "Net.us@$callsign"))
            )
        }
    }

    @Test
    fun `a core too long for a suffix is keyed bare`() {
        // Five characters plus even the shortest suffix is seven, above the
        // six-character cap - so only the core itself may be offered.
        assertEquals(
            setOf("kabce"),
            playlistCallsignKeys(channel("c", name = "KABCE", tvgId = "Net.us@KABCE"))
        )
    }

    // ── The callsign is the matcher's LAST pass ───────────────────────────

    private val kmtv = channel("kmtv", name = "CBS KMTV-TV", tvgId = "CBS.us@KMTVTV")

    private fun callsigns(channel: IptvChannel) = playlistCallsignKeys(channel).toList()

    @Test
    fun `an exact id beats the callsign`() {
        assertEquals(
            "guide-id" to EpgMatchKind.ID,
            matchEpgChannel(
                idCandidates = listOf(kmtv.tvgId),
                nameCandidates = listOf(kmtv.name),
                byId = mapOf("cbs.uskmtvtv" to "guide-id", "kmtv" to "guide-callsign"),
                byName = emptyMap(),
                callsignCandidates = callsigns(kmtv)
            )
        )
    }

    @Test
    fun `an exact name beats the callsign`() {
        assertEquals(
            "guide-name" to EpgMatchKind.NAME,
            matchEpgChannel(
                idCandidates = listOf(kmtv.tvgId),
                nameCandidates = listOf(kmtv.name),
                byId = emptyMap(),
                byName = mapOf("cbskmtvtv" to "guide-name", "kmtv" to "guide-callsign"),
                callsignCandidates = callsigns(kmtv)
            )
        )
    }

    @Test
    fun `the simplified name beats the callsign`() {
        assertEquals(
            "guide-simplified" to EpgMatchKind.SIMPLIFIED,
            matchEpgChannel(
                idCandidates = listOf(kmtv.tvgId),
                nameCandidates = listOf("CBS KMTV-TV HD"),
                byId = emptyMap(),
                byName = mapOf("cbskmtvtv" to "guide-simplified", "kmtv" to "guide-callsign"),
                callsignCandidates = callsigns(kmtv)
            )
        )
    }

    @Test
    fun `the callsign resolves the channel the first three passes miss`() {
        // The field case: the guide's channel id is `KMTV`, the playlist spells
        // the station `CBS.us@KMTVTV` / `CBS KMTV-TV`, and nothing above hits.
        assertEquals(
            "guide-kmtv" to EpgMatchKind.CALLSIGN,
            matchEpgChannel(
                idCandidates = listOf(kmtv.tvgId),
                nameCandidates = listOf(kmtv.name),
                byId = mapOf("kmtv" to "guide-kmtv"),
                byName = emptyMap(),
                callsignCandidates = callsigns(kmtv)
            )
        )
    }

    @Test
    fun `another station's callsign does not match`() {
        val wftv = channel("wftv", name = "ABC WFTV", tvgId = "ABC.us@WFTV")

        assertNull(
            "two affiliates of one network live in different markets",
            matchEpgChannel(
                idCandidates = listOf(wftv.tvgId),
                nameCandidates = listOf(wftv.name),
                byId = mapOf("kgtv" to "guide-kgtv"),
                byName = mapOf("kgtv" to "guide-kgtv"),
                callsignCandidates = callsigns(wftv)
            )
        )
    }

    @Test
    fun `a bare playlist callsign reaches the guide's suffixed display name`() {
        // The exact field case (2026-10-25, 09:04): the US locals guide ships
        // `<channel id="WFTV-DT.us_locals1">`, display `WFTV-DT`, so its id
        // looks up as `wftvdt.uslocals1` and its display as `wftvdt`. The
        // playlist says `ABC.us@WFTV` / `ABC WFTV`. Nothing above the callsign
        // pass hits, and only the generated `wftvdt` spelling reaches it.
        val wftv = channel("wftv", name = "ABC WFTV", tvgId = "ABC.us@WFTV")

        // The guide's id lookup is `wftvdt.uslocals1`, which no callsign key
        // ever equals - so a test that put `wftvdt` in byId would not prove
        // this fix. The display-name index is the one that has to hit.
        assertFalse("wftvdt.uslocals1" in callsigns(wftv))

        assertEquals(
            "guide-wftvdt" to EpgMatchKind.CALLSIGN,
            matchEpgChannel(
                idCandidates = listOf(wftv.tvgId),
                nameCandidates = listOf(wftv.name),
                byId = mapOf("wftvdt.uslocals1" to "guide-wftvdt"),
                byName = mapOf("wftvdt" to "guide-wftvdt"),
                callsignCandidates = callsigns(wftv)
            )
        )
    }

    @Test
    fun `the DT feed wins over the LD feed and LD is the fallback`() {
        val wftv = channel("wftv", name = "ABC WFTV", tvgId = "ABC.us@WFTV")
        val byName = mapOf("wftvdt" to "guide-dt", "wftvld" to "guide-ld")

        assertEquals(
            "a full-power station's DT feed is its schedule, not a coin flip",
            "guide-dt" to EpgMatchKind.CALLSIGN,
            matchEpgChannel(
                idCandidates = listOf(wftv.tvgId),
                nameCandidates = listOf(wftv.name),
                byId = emptyMap(),
                byName = byName,
                callsignCandidates = callsigns(wftv)
            )
        )
        assertEquals(
            "with no DT channel in the guide, the low-power LD one is used",
            "guide-ld" to EpgMatchKind.CALLSIGN,
            matchEpgChannel(
                idCandidates = listOf(wftv.tvgId),
                nameCandidates = listOf(wftv.name),
                byId = emptyMap(),
                byName = mapOf("wftvld" to "guide-ld"),
                callsignCandidates = callsigns(wftv)
            )
        )
    }

    @Test
    fun `expanded keys still never collapse two markets`() {
        // The suffixes are shared across every station, so this is the check
        // that a generated key cannot match another callsign's guide channel:
        // WFTV's `wftvdt` is not KGTV's `kgtvdt`.
        val wftv = channel("wftv", name = "ABC WFTV", tvgId = "ABC.us@WFTV")
        val kgtv = channel("kgtv", name = "ABC KGTV", tvgId = "ABC.us@KGTV")

        assertNull(
            matchEpgChannel(
                idCandidates = listOf(wftv.tvgId),
                nameCandidates = listOf(wftv.name),
                byId = emptyMap(),
                byName = mapOf("kgtvdt" to "guide-kgtvdt"),
                callsignCandidates = callsigns(wftv)
            )
        )
        assertNull(
            matchEpgChannel(
                idCandidates = listOf(kgtv.tvgId),
                nameCandidates = listOf(kgtv.name),
                byId = emptyMap(),
                byName = mapOf("wftvdt" to "guide-wftvdt"),
                callsignCandidates = callsigns(kgtv)
            )
        )
    }

    // ── Import-time pruning has to carry the same keys ────────────────────

    @Test
    fun `a suffixed locals guide channel is kept by the generated keys`() {
        // The prune half of the field bug: the guide id is
        // `WFTV-DT.us_locals1` and its only reachable alias is the display
        // `WFTV-DT`. With no `wftvdt` in the playlist key set its programmes
        // were dropped before the matcher could ever see the channel.
        val keys = playlistEpgMatchKeys(
            listOf(channel("c1", name = "ABC WFTV", tvgId = "ABC.us@WFTV"))
        )
        assertTrue("wftvdt" in keys)
        assertTrue(
            guideChannelCanMatch(
                channelId = "WFTV-DT.us_locals1",
                aliasKeys = epgAliasKeys("WFTV-DT.us_locals1", listOf("WFTV-DT")),
                playlistKeys = keys
            )
        )
    }

    @Test
    fun `a callsign-only guide channel is kept for a local`() {
        // The other half of the field bug: with no callsign key in the playlist
        // set, the importer pruned these programs before the matcher could ever
        // see the channel - a successful import reporting a small kept count.
        val keys = playlistEpgMatchKeys(listOf(kmtv))

        assertTrue("kmtvtv" in keys)
        assertTrue("kmtv" in keys)
        assertTrue(
            "the guide's bare callsign",
            guideChannelCanMatch("KMTV", listOf("KMTV"), keys)
        )
        assertTrue(
            "and the guide's suffixed spelling of it",
            guideChannelCanMatch("KMTV-TV", listOf("KMTV-TV"), keys)
        )
    }

    @Test
    fun `a callsign never keeps another station's channel`() {
        val keys = playlistEpgMatchKeys(
            listOf(channel("c1", name = "ABC WFTV", tvgId = "ABC.us@WFTV"))
        )
        assertFalse(guideChannelCanMatch("KMTV", listOf("KMTV"), keys))

        // And the network name is not a callsign: the guide's bare "ABC"
        // channel must not be pulled in by this tier.
        val kgtv = playlistEpgMatchKeys(
            listOf(channel("c2", name = "ABC KGTV", tvgId = "ABC.us@KGTV"))
        )
        assertFalse(guideChannelCanMatch("ABC", listOf("ABC"), kgtv))
    }

    // ── Guide-window fingerprint ─────────────────────────────────────────

    private val loaded = setOf("bbc1", "itv1")

    private fun fingerprint(
        channels: List<IptvChannel>,
        ids: Set<String> = loaded,
        guideUrls: List<String> = listOf("https://guide.example/epg.xml")
    ) = guideWindowFingerprint(
        sourceUrl = "https://provider.example/playlist.m3u",
        guideUrls = guideUrls,
        channelIds = ids,
        channels = channels
    )

    // ── Import-time pruning (does a guide channel have anywhere to go?) ────

    @Test
    fun `playlist keys carry every spelling the matcher probes`() {
        val keys = playlistEpgMatchKeys(
            listOf(channel("c1", name = "BBC One HD", tvgId = "bbc1.uk"))
        )
        // The id pass, as normalized as the matcher normalizes it.
        assertTrue("bbc1.uk" in keys)
        // The name pass, and the simplified pass the decorated spelling needs.
        assertTrue(epgLookupKey("BBC One HD") in keys)
        assertTrue("bbcone" in keys)
    }

    @Test
    fun `no playlist means nothing may be pruned`() {
        // An empty key set is "cannot decide", never "nothing matches": a
        // background import with no readable cached playlist must keep the
        // guide exactly as it was.
        assertEquals(emptySet<String>(), playlistEpgMatchKeys(emptyList()))
        assertTrue(guideChannelCanMatch("anything", listOf("anything"), emptySet()))
    }

    @Test
    fun `a guide channel a playlist can reach is kept`() {
        val keys = playlistEpgMatchKeys(
            listOf(channel("c1", name = "BBC One HD", tvgId = "bbc1.uk"))
        )
        // Reached through a display-name alias (the guide's own id is opaque).
        assertTrue(
            guideChannelCanMatch(
                channelId = "i.dish.1001",
                aliasKeys = listOf("i.dish.1001", "BBC One HD", "bbcone"),
                playlistKeys = keys
            )
        )
        // Reached through the guide's id, which is what a tvg-id refers to.
        assertTrue(
            guideChannelCanMatch(
                channelId = "bbc1.uk",
                aliasKeys = listOf("bbc1.uk"),
                playlistKeys = keys
            )
        )
    }

    @Test
    fun `a guide channel no playlist entry can reach is pruned`() {
        val keys = playlistEpgMatchKeys(
            listOf(channel("c1", name = "BBC One HD", tvgId = "bbc1.uk"))
        )
        assertFalse(
            guideChannelCanMatch(
                channelId = "de.premiere",
                aliasKeys = listOf("de.premiere", "Premiere One"),
                playlistKeys = keys
            )
        )
    }

    @Test
    fun `a qualifier the guide lacks still matches, so its programs stay`() {
        // The ESPN2 HD / ESPN2 case: matched through the SIMPLIFIED pass, so
        // the simplified spelling has to be one of the playlist's keys.
        val keys = playlistEpgMatchKeys(
            listOf(channel("c9", name = "ESPN2 HD", tvgId = null))
        )
        assertTrue("espn2" in keys)
        assertTrue(
            guideChannelCanMatch(
                channelId = "espn2",
                aliasKeys = listOf("espn2"),
                playlistKeys = keys
            )
        )
    }

    private fun channel(
        id: String,
        name: String = "Channel $id",
        tvgId: String? = id,
        logoUrl: String? = null,
        streamUrl: String = "http://host/$id.ts"
    ) = IptvChannel(
        id = id,
        name = name,
        displayName = name,
        streamUrl = streamUrl,
        groupTitle = "General",
        logoUrl = logoUrl,
        tvgId = tvgId,
        tvgName = name,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null
    )

    @Test
    fun `an empty guide window has no fingerprint to compare`() {
        assertNull(fingerprint(listOf(channel("bbc1")), ids = emptySet()))
    }

    @Test
    fun `the same channels produce the same fingerprint`() {
        val channels = listOf(channel("bbc1"), channel("itv1"), channel("other"))
        assertEquals(fingerprint(channels), fingerprint(channels.toList()))
    }

    @Test
    fun `channel details outside the guide window do not change it`() {
        // A background refresh routinely touches logos and stream URLs; that
        // must not throw away loaded programs and re-run every batch.
        val before = listOf(channel("bbc1"), channel("itv1"))
        val after = listOf(
            channel("bbc1", name = "Channel bbc1", logoUrl = "http://logo/new.png"),
            channel("itv1", name = "Channel itv1", streamUrl = "http://host/moved.ts")
        )
        assertEquals(fingerprint(before), fingerprint(after))
    }

    @Test
    fun `matcher inputs do change it`() {
        val before = listOf(channel("bbc1", name = "BBC One"), channel("itv1"))
        // Rename: the guide is matched by name, so programs may differ now.
        assertNotEquals(
            fingerprint(before),
            fingerprint(listOf(channel("bbc1", name = "BBC One HD"), channel("itv1")))
        )
        // tvg-id is the strongest match key there is.
        assertNotEquals(
            fingerprint(before),
            fingerprint(listOf(channel("bbc1", name = "BBC One", tvgId = "bbc1.uk"), channel("itv1")))
        )
    }

    @Test
    fun `a different guide source or a lost channel does change it`() {
        val before = listOf(channel("bbc1"), channel("itv1"))
        assertNotEquals(
            fingerprint(before),
            fingerprint(before, guideUrls = listOf("https://other.example/epg.xml"))
        )
        // Requested but no longer in the playlist: the window really shrinks.
        assertNotEquals(
            fingerprint(before),
            fingerprint(listOf(channel("bbc1")))
        )
    }
}
