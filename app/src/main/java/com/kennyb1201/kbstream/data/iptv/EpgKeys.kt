package com.kennyb1201.kbstream.data.iptv

import java.util.Locale

/**
 * Channel-key normalization for EPG matching, lifted out of [IptvRepository] so
 * the rules can be unit tested.
 *
 * These functions decide whether a playlist channel matches a guide channel,
 * and a wrong answer is invisible: the channel simply shows no programs (or,
 * worse, borrows another channel's). M3U and XMLTV spellers disagree about
 * everything — case, `[HD]`/`(US)` decorations, `&` vs "and", `+` vs "plus",
 * punctuation — so every one of those is stripped or expanded here rather than
 * left to a fuzzy comparison.
 *
 * Note the deliberate difference from [simplifyEpgChannelName]: this keeps dots
 * (channel ids like `bbc1.uk` stay addressable) while the alias builder drops
 * every non-alphanumeric character.
 */
internal fun epgLookupKey(value: String): String =
    value.trim()
        .lowercase(Locale.US)
        .replace(BRACKETED_TEXT, " ")
        .replace(PARENTHESIZED_TEXT, " ")
        .replace("&", " and ")
        .replace("+", " plus ")
        .replace(NON_LOOKUP_CHARACTERS, "")
        .trim()

/**
 * Every spelling under which a guide channel should be findable: the raw id, a
 * normalized id, each display name, its normalized form, and its simplified
 * form (decorations and quality qualifiers removed). Blank entries are dropped
 * and duplicates collapse, so a name repeated across spellings costs one key.
 */
internal fun epgAliasKeys(id: String, displayNames: List<String>): List<String> {
    val keys = LinkedHashSet<String>(2 + displayNames.size * 3)

    fun add(value: String?) {
        value?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
    }

    add(id)
    add(normalizeEpgChannelKey(id))
    displayNames.forEach { name ->
        add(name)
        add(normalizeEpgChannelKey(name))
        add(simplifyEpgChannelName(name))
    }
    return keys.toList()
}

internal fun normalizeEpgChannelKey(value: String): String =
    value.trim().lowercase(Locale.US)

/**
 * The key an imported program is STORED under, and therefore the only key a
 * program query may ask with.
 *
 * [XmltvImporter] writes `<programme channel=...>` through this function, so
 * `epg_programs.channelId` is always the trimmed, lowercased spelling. The
 * guide's own channel rows keep the raw `<channel id=...>` untouched, and the
 * DAO matches `channelId IN (:channelIds)` exactly (SQLite is case-sensitive
 * for TEXT unless a column says otherwise -- this one does not).
 *
 * So handing a program query the guide channel's raw id silently returns
 * nothing whenever the guide spells that id with any uppercase letter
 * (`ESPN.us`, `BBC.UK`, `Discovery.HD`), while its programs are imported and
 * sitting in the database: the channel shows "No program data" forever. That
 * is the whole reason this function exists next to [epgLookupKey]: matching is
 * deliberately fuzzy, but reading back what was written has to be exact.
 */
internal fun epgProgramChannelKey(value: String): String =
    value.trim().lowercase(Locale.US)

/**
 * Aggressive form used only for alias keys: strips bracket/parenthesis
 * decorations, quality qualifiers (HD, 4K, HEVC, ...), expands `+`, and drops
 * everything that is not alphanumeric — "BBC One HD (East)" becomes "bbcone".
 * Returns null when nothing is left, so callers never key on an empty string.
 */
internal fun simplifyEpgChannelName(value: String): String? {
    val simplified = value
        .lowercase(Locale.US)
        .replace(BRACKETED_TEXT, " ")
        .replace(PARENTHESIZED_TEXT, " ")
        .replace(CHANNEL_QUALIFIERS, " ")
        .replace("+", " plus ")
        .replace(NON_ALPHANUMERIC, "")
        .trim()
    return simplified.ifBlank { null }
}

/** Which of the matcher's passes produced a match. */
internal enum class EpgMatchKind { ID, NAME, SIMPLIFIED, CALLSIGN, NUMBERED_VARIANT }

/**
 * The callsigns a playlist channel answers to, as matcher keys.
 *
 * Field report (2026-10-25, phone): the epgshare01 US locals guide was
 * configured, IMPORT GUIDE succeeded, and every local affiliate still read "No
 * program data" - ABC KERO-TV, KGTV, KGUN-TV, KITV ...; CBS KMTV-TV, KUTV ...
 * The guide identifies a local by its CALLSIGN alone (`<channel id="KMTV">`),
 * while iptv-org's playlist spells the same station `CBS.us@KMTVTV` and calls it
 * `CBS KMTV-TV` - and neither spelling, at any of the matcher's three passes,
 * intersects `kmtv`. Two characters of station suffix and one word of network
 * are the whole difference.
 *
 * So the identity this returns is the callsign and only the callsign: FCC
 * callsigns name one station each, which is why `WFTV` stays `WFTV` whatever
 * network it carries this season. The network is in the playlist's own tvg-id
 * and is never part of a key here.
 *
 * Every spelling of a station suffix is returned, not just the one the
 * playlist used, so a guide that omits it, carries it, or carries a different
 * one still hits: `WFTV` answers `wftv`, `wftvtv`, `wftvdt`, `wftvld` and
 * `wftvcd`, which is what makes `WFTV` reach a guide's `WFTV-DT` (the shape the
 * US locals guide actually ships). See [callsignKeysOf] for the cap and the
 * order.
 */
internal fun playlistCallsignKeys(channel: IptvChannel): Set<String> =
    epgCallsignKeys(
        tvgId = channel.tvgId,
        names = listOf(channel.tvgName, channel.displayName, channel.name)
    )

/**
 * The callsign keys of one channel identity, independent of where it is stored.
 *
 * [playlistCallsignKeys] is this over an [IptvChannel]; the in-player guide and
 * catch-up resolve hold a published lineup entry instead and pass the same
 * fields, so the guide screen and the player cannot disagree about a local.
 *
 *  - **The tvg-id feed suffix is the primary source.** iptv-org spells a local
 *    `<Network>.us@<CALLSIGN>` (`ABC.us@KGTV`, `CBS.us@KMTVTV`), so everything
 *    after the first `@` is the callsign.
 *  - **Only with no `@` at all** does the last whitespace token of the first
 *    non-blank name stand in - the fallback for provider playlists that carry
 *    no iptv-org ids (`CBS KMTV-TV`). It never runs when a feed suffix exists,
 *    so a name can never override the id's callsign.
 *  - **The guard is the tier's whole safety.** A key must look like a US
 *    callsign: `k`/`w` first, 3-6 characters. That is what keeps `East`, `HD`,
 *    `ABC`, `CBS` and a Mexican `XH...` station out - those keep today's
 *    behaviour rather than gaining a wrong match.
 */
internal fun epgCallsignKeys(tvgId: String?, names: List<String?>): Set<String> {
    val raw = tvgId?.takeIf { it.contains('@') }?.substringAfter('@')
        ?: names.firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.split(WHITESPACE)
            ?.lastOrNull()
        ?: return emptySet()

    return callsignKeysOf(raw)
}

/**
 * The keys one raw callsign token is addressable under, or none when it is not
 * a US callsign (see [epgCallsignKeys] for the guard).
 *
 * The guide and the playlist disagree about the station SUFFIX far more often
 * than about the callsign: iptv-org has `ABC.us@WFTV` (bare) and
 * `CBS.us@WTVQDT` (suffixed), while the US locals guide ships `WFTV-DT`,
 * `WFTV-LD`, `WTVQ-DT`. Stripping a suffix only helps the already-suffixed
 * playlist rows, so every playlist row whose guide spelling carries a suffix
 * the playlist omitted still read "No program data" (field report 2026-10-25: 1484
 * channels, only WTVQ-DT and WDIO-DT filled). So a core is derived first and
 * then spelled with every suffix the tier knows.
 *
 * Order is load-bearing: the core comes first, then `tv`, `dt`, `ld`, `cd` in
 * [CALLSIGN_STATION_SUFFIXES] order, and the matcher takes the first candidate
 * that hits. A guide carrying both `WFTV-DT` and `WFTV-LD` must resolve the
 * full-power `-DT` feed; `-LD` is the fallback when no `-DT` exists. Do not
 * sort these keys alphabetically at any call site — `cd` would come before
 * `dt` and change which feed wins.
 */
private fun callsignKeysOf(raw: String?): Set<String> {
    val base = raw
        ?.lowercase(Locale.US)
        ?.replace(NON_ALPHANUMERIC, "")
        ?.takeIf { it.isNotEmpty() }
        ?: return emptySet()

    // `k`/`w` and 3-6 characters: the shape of a US call sign, and nothing else.
    if (base.length !in CALLSIGN_LENGTH..CALLSIGN_MAX_LENGTH) return emptySet()
    if (base[0] != 'k' && base[0] != 'w') return emptySet()

    // The callsign without the station suffix. Stripping is only allowed when
    // the remainder still looks like a callsign on its own, so `WFTV` does not
    // become a two-letter key that would collide with everything.
    val strippedSuffix = CALLSIGN_STATION_SUFFIXES.firstOrNull { base.endsWith(it) }
    val core = if (strippedSuffix != null && base.length - strippedSuffix.length >= CALLSIGN_LENGTH) {
        base.dropLast(strippedSuffix.length)
    } else {
        base
    }

    // The core, then every suffix spelling of it that still fits a US callsign.
    // The base spelling is always among them by construction: either it IS the
    // core, or it is the core plus the suffix it originally carried.
    val keys = LinkedHashSet<String>(1 + CALLSIGN_STATION_SUFFIXES.size)
    keys.add(core)
    CALLSIGN_STATION_SUFFIXES.forEach { suffix ->
        val spelled = core + suffix
        if (spelled.length <= CALLSIGN_MAX_LENGTH) keys.add(spelled)
    }
    return keys
}

/**
 * The keys a numbered parallel feed answers to: the channel's name spellings
 * with a trailing run of digits removed.
 *
 * Field report (2026-10-25, phone): ABC News Live 1-10 and AMP 1-2 all read "No
 * program data" on a guide that does carry the base channel, while the majors
 * were filled - so the import and the matcher both work. The playlist spells
 * each feed as its own channel (`ABCNewsLive3.us@SD` / "ABC News Live 3", ten
 * distinct ids and ten distinct streams) while the guide has one channel named
 * "ABC News Live", and no pass can reach `abcnewslive` from `abcnewslive3`: all
 * ten miss on the trailing digit alone.
 *
 * Both spellings the matcher would otherwise probe are stripped - the
 * simplified form and the lookup form - because a guide may key a channel by
 * either: "ABC News Live 3" reduces to `abcnewslive` both ways, while a dotted
 * name keeps its dots in one form only. A base shorter than
 * [NUMBERED_BASE_MIN_LENGTH] characters is never offered (`Channel 5` must not
 * produce `chan`, and a name that IS digits produces nothing), and a name with
 * no trailing digits yields no keys at all, so this can never restate a
 * spelling an earlier pass already probed.
 */
internal fun numberedVariantKeys(name: String): List<String> {
    val forms = listOfNotNull(
        simplifyEpgChannelName(name),
        epgLookupKey(name).takeIf { it.isNotEmpty() }
    )
    val keys = LinkedHashSet<String>(forms.size)
    forms.forEach { form ->
        val stripped = form.replace(TRAILING_DIGITS, "")
        if (stripped != form && stripped.length >= NUMBERED_BASE_MIN_LENGTH) keys.add(stripped)
    }
    return keys.toList()
}

/**
 * Picks the guide channel for one playlist channel, trying the spellings in the
 * order that loses the least information first.
 *
 *  1. **The channel's own id** (`tvg-id` / provider id) against the guide's
 *     channel ids — exact, so it wins whenever it hits.
 *  2. **The display names as spelled**, normalized (case/decoration/punctuation
 *     only). This keeps `Sky Sports 1` separate from `Sky Sports 2`.
 *  3. **The names simplified**, with quality qualifiers stripped, so
 *     `BBC One HD` still finds `BBC One`. This pass is deliberately after the
 *     exact one: it folds `HD`, `US`, `4K` and the like, which can rarely
 *     collide two genuinely different channels, so it only runs when nothing
 *     above matched.
 *  4. **The callsign**, [playlistCallsignKeys], so a local affiliate whose
 *     guide channel is identified by callsign alone (`KMTV`) or by a suffixed
 *     spelling of it (`WFTV-DT`) is reachable from a playlist that spells it
 *     `CBS.us@KMTVTV` / `ABC.us@WFTV`. Last for the
 *     same reason: an exact or name match must never be displaced, and the
 *     callsign is the weaker claim (it says which station, not which feed).
 *  5. **The name with its trailing feed number stripped**,
 *     [numberedVariantKeys], so a numbered parallel feed
 *     (`ABC News Live 3`, `AMP 2`) reaches the base channel's guide entry
 *     instead of staying blank forever. Last of all, and never applied to the
 *     candidates above: stripping digits up front would collapse `Sky Sports
 *     1` onto `Sky Sports` for every pass, so a channel that has its own guide
 *     entry keeps it and this pass can only rescue a channel that has none.
 *     It runs after the callsign because a callsign says which STATION, which
 *     is a stronger claim than "a feed family with this base name".
 *
 * Without pass 3 the alias keys built by [epgAliasKeys] were half-wired: the
 * guide's simplified spelling was stored, but the playlist side was only ever
 * looked up by its normalized spelling, so a channel carrying a qualifier the
 * guide did not (`ESPN2 HD` vs `ESPN2`) showed "no program data" forever.
 *
 * Returns the matched value together with the pass that matched it, or null.
 */
internal fun <T> matchEpgChannel(
    idCandidates: List<String?>,
    nameCandidates: List<String?>,
    byId: Map<String, T>,
    byName: Map<String, T>,
    /**
     * Callsign keys ([playlistCallsignKeys] of a local), tried AFTER the
     * exact-id and name passes - see the fourth pass below. Empty by default,
     * so a caller that has no callsigns to offer (and every existing test)
     * behaves exactly as before.
     */
    callsignCandidates: List<String?> = emptyList()
): Pair<T, EpgMatchKind>? {
    for (candidate in idCandidates) {
        val key = candidate?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::epgLookupKey)
            ?: continue
        byId[key]?.let { return it to EpgMatchKind.ID }
    }

    for (candidate in nameCandidates) {
        val key = candidate?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::epgLookupKey)
            ?: continue
        byName[key]?.let { return it to EpgMatchKind.NAME }
    }

    for (candidate in nameCandidates) {
        val key = candidate?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::simplifyEpgChannelName)
            ?: continue
        byName[key]?.let { return it to EpgMatchKind.SIMPLIFIED }
    }

    // Pass 4: the callsign. LAST, deliberately - a channel that matched on its
    // own id or its own name keeps that match, so a national feed that already
    // resolves to its network's guide channel is never pulled onto a local
    // affiliate's schedule, and two affiliates of one network in different
    // markets cannot collapse onto one guide channel (their callsigns differ,
    // and any exact match they have would already have won).
    //
    // Callsign keys carry no dots, so lookup and simplified spellings coincide
    // here; both indexes are still probed because a guide may key a local by
    // its id (`<channel id="KMTV">`) or only by a display name.
    for (candidate in callsignCandidates) {
        val key = candidate?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::epgLookupKey)
            ?: continue
        byId[key]?.let { return it to EpgMatchKind.CALLSIGN }
        byName[key]?.let { return it to EpgMatchKind.CALLSIGN }
    }

    // Pass 5: the name with a trailing feed number stripped. Only the NAME
    // index is probed: the keys are name spellings, so a guide's id index
    // never holds them, and reconstructing an id from a stripped name (which
    // would mean guessing the guide's country suffix) is the kind of
    // reconstruction this file forbids elsewhere.
    //
    // This is an ADDITIONAL candidate list, never a normalization of the ones
    // above: the passes 2-4 spellings are what keep `Sky Sports 1` apart from
    // `Sky Sports 2`, and reaching this pass at all means none of them hit.
    for (candidate in nameCandidates) {
        val name = candidate?.trim()?.takeIf(String::isNotEmpty) ?: continue
        for (key in numberedVariantKeys(name)) {
            byName[key]?.let { return it to EpgMatchKind.NUMBERED_VARIANT }
        }
    }

    return null
}

/**
 * Every key the matcher could look a playlist channel up under, in one set.
 *
 * [matchEpgChannel] probes a playlist channel's ids (tvg-id, provider id) and
 * its names (tvg-name, display name, name) against the guide's two indexes.
 * This collects the same probes in the same normalized forms, so the importer
 * can ask "could this guide channel ever be reached?" without a playlist in
 * hand ([guideChannelCanMatch]).
 *
 * An empty result means "no playlist known": callers must read that as "cannot
 * decide" and keep everything, never as "nothing matches".
 */
internal fun playlistEpgMatchKeys(channels: List<IptvChannel>): Set<String> {
    if (channels.isEmpty()) return emptySet()

    val keys = HashSet<String>(channels.size * 6)
    channels.forEach { channel ->
        // Pass 1: the ids, normalized exactly as the matcher normalizes them.
        listOf(channel.tvgId, channel.providerChannelId).forEach { candidate ->
            candidate?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { keys.add(epgLookupKey(it)) }
        }
        // Passes 2, 3 and 5: the names as spelled, simplified with quality
        // qualifiers stripped, and with a trailing feed number stripped - the
        // three spellings the matcher tries against the guide's name index.
        listOf(channel.tvgName, channel.displayName, channel.name).forEach { candidate ->
            candidate?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { name ->
                    keys.add(epgLookupKey(name))
                    simplifyEpgChannelName(name)?.let(keys::add)
                    // Pass 5: without these the matcher could reach a numbered
                    // parallel feed's base guide channel while the importer had
                    // already thrown its programs away - the same half-wiring
                    // that made a missing callsign key look like a guide gap.
                    keys.addAll(numberedVariantKeys(name))
                }
        }
        // Pass 4: the callsign, so a local's guide channel survives the prune.
        //
        // Without this the tier would be half-wired in the way that leaves no
        // trace: the matcher would be able to resolve `CBS.us@KMTVTV` to a
        // guide channel id `KMTV`, while the importer had already thrown that
        // channel's programs away because no playlist key reached it. The
        // field symptom is a successful import reporting a small kept count and
        // a lineup of locals reading "No program data".
        keys.addAll(playlistCallsignKeys(channel))
    }
    return keys
}

/**
 * Whether a guide channel could ever be matched by a playlist carrying
 * [playlistKeys], which is what decides whether its programs are worth
 * importing. True when [playlistKeys] is empty - "cannot decide" keeps the
 * old keep-everything behavior.
 *
 * Deliberately a SUPERSET test: it may keep a guide channel that would not
 * have matched, but it must never drop one that would. Both indexes the
 * matcher reads are mirrored here - `byId` is keyed by [epgLookupKey] of the
 * channel id, `byName` by [epgLookupKey] of every alias key - so a channel
 * whose ids or names intersect the playlist's probes is kept, and one whose do
 * not could only ever have shown programs no playlist row could reach.
 */
internal fun guideChannelCanMatch(
    channelId: String,
    aliasKeys: List<String>,
    playlistKeys: Set<String>
): Boolean {
    if (playlistKeys.isEmpty()) return true
    if (epgLookupKey(channelId) in playlistKeys) return true
    return aliasKeys.any { alias -> epgLookupKey(alias) in playlistKeys }
}

/**
 * Fingerprint of everything a guide query reads out of a playlist: the source
 * URLs plus the identity of the channels currently loaded into the guide
 * window. A background playlist refresh almost always returns the same
 * channels, and treating it as a change wiped the loaded guide and re-ran the
 * whole lineup query (snapshot rebuild + every program batch) for rows that
 * could not have differed — visible as two identical `LINEUP QUERY` passes a
 * couple of seconds apart on guide entry.
 *
 * Callers compare the fingerprint taken before and after a refresh: equal
 * means the loaded guide is still valid. Returns null when nothing is loaded,
 * because then there is nothing to compare against (and the requery is cheap).
 */
internal fun guideWindowFingerprint(
    sourceUrl: String?,
    guideUrls: List<String>,
    channelIds: Set<String>,
    channels: List<IptvChannel>
): String? {
    if (channelIds.isEmpty()) return null

    val builder = StringBuilder(64 + channelIds.size * 64)
    builder.append(sourceUrl.orEmpty()).append(SEP_FIELD)
        .append(guideUrls.joinToString(",")).append(SEP_FIELD)

    var found = 0
    for (channel in channels) {
        if (channel.id !in channelIds) continue
        found++
        // Everything the matcher reads, and nothing else: a logo or stream URL
        // change must not invalidate programs that are already loaded.
        builder.append(channel.id).append(SEP_PART)
            .append(channel.name).append(SEP_PART)
            .append(channel.displayName).append(SEP_PART)
            .append(channel.groupTitle.orEmpty()).append(SEP_PART)
            .append(channel.tvgId.orEmpty()).append(SEP_PART)
            .append(channel.tvgName.orEmpty()).append(SEP_FIELD)
    }

    // A requested channel that vanished from the playlist shrinks the window,
    // which is a real change even when every surviving channel is identical.
    builder.append(SEP_FIELD).append(found)
    return builder.toString()
}

private const val SEP_FIELD = '\u0000'
private const val SEP_PART = '\u0001'

private val BRACKETED_TEXT = Regex("""\[[^\]]*]""")
private val PARENTHESIZED_TEXT = Regex("""\([^)]*\)""")
private val NON_LOOKUP_CHARACTERS = Regex("""[^a-z0-9.]+""")
private val NON_ALPHANUMERIC = Regex("""[^a-z0-9]+""")
private val CHANNEL_QUALIFIERS = Regex(
    """\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""
)

/**
 * The station suffixes a US call sign may carry, and the reason `KMTVTV`,
 * `KMTV-TV`, `KMTV-DT` and `KMTV` are one station: the guide and the playlist
 * spell the same call sign with and without the suffix, and often with
 * different ones, so every spelling is offered. Order matters - see
 * [callsignKeysOf].
 */
private val CALLSIGN_STATION_SUFFIXES = listOf("tv", "dt", "ld", "cd")

/** Shortest call sign the tier will key on; below this it is a fragment. */
private const val CALLSIGN_LENGTH = 3

/** Longest US call sign: four letters, plus the two-character station suffix. */
private const val CALLSIGN_MAX_LENGTH = 6

private val WHITESPACE = Regex("""\s+""")

/** The feed number a numbered parallel variant is distinguished by. */
private val TRAILING_DIGITS = Regex("""[0-9]+$""")

/**
 * Shortest base the numbered-variant pass will key on. Below this a stripped
 * name is a fragment (`Channel 5` -> `chan`), not a channel.
 */
private const val NUMBERED_BASE_MIN_LENGTH = 3
