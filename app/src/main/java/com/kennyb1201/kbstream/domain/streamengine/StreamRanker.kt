package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * Orders a fetched source list best-first.
 *
 * This list is what the picker shows and what auto-play takes the head of, so a
 * wrong head is not cosmetic: it is the stream that starts by itself - and then
 * stalls, or opens the wrong thing - without the viewer ever choosing it.
 *
 * Priority, highest first:
 *
 *  1. **Playability here.** An http(s) URL opens right now; an infoHash-only
 *     entry has no engine in this app at all, so it may never outrank something
 *     that does, whatever its labels say.
 *  2. **Known-bad releases.** A CAM / telecine / screener copy, a 3D pair, a
 *     sample/trailer clip, or a foreign-dubbed or hardcoded-subtitle copy sits
 *     under every honest source. A 2160p CAM is still a CAM, which is exactly
 *     how a high resolution label used to carry one to the top. This is a tier
 *     rather than a score penalty for a reason: as points a CAM penalty had to
 *     out-shout the bonuses below, and the cache bonus that arrived with the
 *     debrid addons could hand a *cached* CAM right back over an honest 480p.
 *  3. **The episode itself.** When the request names a series episode, a
 *     source whose own text declares another episode of that season sits under
 *     every source that declares the episode asked for or declares nothing at
 *     all. A file the app *knows* holds something else is not a worse copy of
 *     the right thing - it is the wrong thing, and the picker's head is what
 *     auto-play starts. See [EpisodeMatch].
 *  4. **Debrid-served links.** A link served by the viewer's own debrid service
 *     comes before a plain hoster link, whatever that one is labeled: the
 *     URL's host is the service itself, or the entry carries that service's own
 *     completion tag. It sits above availability because it is a fact about the
 *     URL rather than a claim in a title - an addon writes its own titles, so a
 *     scraper addon can print "Instant" beside a hoster link as readily as a
 *     debrid addon can write "cached", but it cannot print someone else's
 *     domain into the link it serves the file from. That is the difference
 *     between a completed file on a CDN and one hoster's copy of it, and it is
 *     the one this ranker kept missing: a scraper addon's 4K direct link kept
 *     taking the head of the list from the TorBox copy the viewer's own addon
 *     had sent, because only the quality labels were ever compared.
 *  5. **Availability.** A copy the debrid service already holds starts now; an
 *     uncached one of the same title waits for peers, whatever its resolution
 *     label says. AIOStreams sorts on exactly this first, and it is the one
 *     criterion a re-sort by quality labels alone got backwards - which is how
 *     a 4K that had to find its swarm ended up heading a list whose addon had
 *     deliberately put a cached 1080p there.
 *  6. Resolution, HDR/DV, release type, size, seeders and a resolution label
 *     its own file size contradicts, in that order of weight.
 *
 * Every rule but the debrid one above reads the stream's *text*, and that text
 * is every field the addon
 * sent plus the filename its link carries - not just `title ?: description ?:
 * name`. Two consequences of that were visible in the app: an addon that puts
 * the release name in `description` while `title` holds something short scored
 * as if it declared nothing, and direct-link addons - which routinely put the
 * resolution only in the path (`/Movies/Some Film (2024) 1080p BluRay.mkv`) -
 * never scored for their quality at all. That second one is why a hash-only
 * 4K entry could sit above a real 1080p link.
 */
object StreamRanker {

    /** Episode tiers of [episodeRank], best first. */
    private const val EPISODE_MATCH = 0
    private const val EPISODE_NONE = 1
    private const val EPISODE_OTHER = 2

    /**
     * Releases that are not the film, or not a copy of it worth watching: a
     * CAM/telecine/screener capture, or a sample/trailer clip. All of them are
     * what a viewer means by "a problematic stream".
     *
     * WEBRip is deliberately NOT in this group. It is an ordinary source, and
     * penalizing it the way a CAM is penalized pushed good web releases below
     * unlabeled ones.
     */
    private val UNWATCHABLE_RELEASE =
        Regex(
            """\b(cam|hdcam|newcam|webcam|camrip|hdts|telesync|telecine|tc|ts|r5|r6|r7|screener|scr|dvdscr|predvd|pdvd|sample|trailer|teaser)\b"""
        )

    /**
     * A non-English dub, or a copy whose subtitles are burned into the picture.
     *
     * This is an English-first app — Settings → Browse & discover is English-only
     * by default and the discover rails ask TMDB for `with_original_language=en`
     * — and a foreign-dubbed or hardcoded-sub copy of an English title is a
     * common head of the picker: it still carries every quality label (1080p
     * WEB-DL, a large size) so it scored like a real release while playing the
     * wrong audio.
     *
     * The markers are deliberately audio/subtitle-specific. Bare Western language
     * names ("italian", "french", "german") are NOT listed because they are also
     * film titles ("The Italian Job"), and demoting those would sink the real
     * release. The Indic dub tags and the explicit "dubbed"/"dublado" tags have
     * no such collision.
     */
    private val FOREIGN_OR_HARDSUBBED_RELEASE =
        Regex(
            """\b(hindi|tamil|telugu|malayalam|kannada|punjabi|bengali|marathi|urdu|dubbed|dublado|latino|castellano|vostfr|truefrench|hc|hardsub|hardcoded|esub|esubs|korsub)\b"""
        )

    /**
     * A 3D encode (side-by-side or over-under, including the half variants):
     * without a 3D mode this is a doubled, squashed picture - the other way a
     * well-labeled stream turns out to be unwatchable.
     */
    private val THREE_D_RELEASE =
        Regex("""\b(3d|sbs|hsbs|half-sbs|ou|hou|half-ou)\b""")

    /**
     * HDR / Dolby Vision markers. Word-bounded on purpose: `"dv" in text` also
     * matches "DVDRip", which handed a plain DVD rip the Dolby Vision bonus.
     */
    private val HDR_RELEASE =
        Regex("""\b(hdr10\+?|hdr|dolby\s?vision|dv)\b""")

    /** Release types worth a nudge, best tier first. */
    private val RELEASE_TIERS = listOf(
        Regex("""\bremux\b""") to 30,
        Regex("""\b(blu-?ray|bdrip|brrip)\b""") to 20,
        Regex("""\bweb-?dl\b""") to 15,
        Regex("""\b(webrip|hdtv)\b""") to 5
    )

    /**
     * A source that starts playing now instead of hunting for peers: the addon
     * says the copy is cached, or marks it the way Torrentio and AIOStreams mark
     * a completed download (`\u26a1`, `[RD+]`, `[TB+]`).
     *
     * `cached` is word-bounded here, not the bare substring it used to be.
     * "Uncached" contains "cached", so every line that spelled out that the copy
     * was NOT ready collected the bonus for saying it was — the exact opposite
     * of the claim, and in the one place it matters most, since this tier is
     * what decides the head of an AIOStreams list.
     *
     * The bracketed tag is spelled with whatever debrid service the viewer
     * configured — `[RD+]` for Real-Debrid, but equally `[PM+]`, `[AD+]`,
     * `[DL+]`, `[OC+]`, `[ED+]`, `[EZ+]` — so it is matched by SHAPE (a short
     * tag, a plus) rather than by naming two of them. Only RD and TB used to
     * count, which meant a Premiumize or AllDebrid account got no availability
     * signal at all: its cached copies fell back to being ranked on labels, and
     * a plain 4K direct link headed the list over the cached copy the viewer's
     * own addon had deliberately put first.
     */
    private val INSTANT_HINT = Regex(
        """\b(cached|instant)\b|\u26a1|\[\s?[a-z]{2,5}\s?\+\s?\]|\b(?:rd|tb)\+"""
    )

    /**
     * The links a debrid service serves itself: the URLs those services hand
     * back once they have resolved a torrent, and the only links in a source
     * list whose bytes come from a CDN the viewer already pays for.
     *
     * Matched on the category word and on the services whose names do not carry
     * it, so one rule covers whichever account the viewer configured instead of
     * a list that has to be extended per service; the word may sit anywhere in
     * the host, so a service whose name carries another's (alldebrid.com) still
     * counts, where a leading word boundary would have missed it. Read on the
     * URL's HOST (see [hostOf]): an addon writes its own titles, but it cannot
     * print someone else's domain into the link it serves the file from.
     */
    private val DEBRID_HOST = Regex("""(debrid|torbox|premiumize|offcloud|put\.io|seedr)\b""")

    /**
     * The completion tag of a debrid service, spelled with the abbreviation of
     * whichever service the viewer configured - `[RD+]` Real-Debrid, `[TB+]`
     * TorBox, `[PM+]` Premiumize, `[AD+]` AllDebrid, `[DL+]` Debrid-Link,
     * `[OC+]` Offcloud - plus the bare `rd+` and `tb+` spellings some addons
     * use where there is no bracket to put a tag in.
     *
     * Kept apart from [INSTANT_HINT], which is deliberately shape-based: there
     * the only question is whether the addon claims the copy is ready, here it
     * is which service is serving it.
     */
    private val DEBRID_TAG =
        Regex("""\[\s?(rd|tb|pm|ad|dl|oc|ed|ez)\s?\+\s?\]|\b(?:rd|tb)\+""")

    /**
     * Peers a release reports, in any of the shapes addons print them: the
     * `\uD83D\uDC65 42` marker, and plain "42 seeders" / "Seeders: 42".
     */
    private val SEEDERS = Regex(
        """(?:\uD83D\uDC65\s*(\d+))|(?:(\d+)\s*seeders?\b)|(?:\bseeders?\s*[:=]?\s*(\d+))""",
        RegexOption.IGNORE_CASE
    )

    /** A size written into the release name, in whichever unit it used. */
    private val SIZE_IN_TEXT = Regex("""(\d+(?:\.\d+)?)\s?(mb|gb|tb)\b""")

    /**
     * Smallest plausible file size, in GB, for the resolution a release claims.
     * A "2160p"/"4K" tag on a file below [MIN_PLAUSIBLE_4K_GB] — or a "1080p"
     * tag below [MIN_PLAUSIBLE_1080P_GB] — is an upscale or a mislabel: the file
     * is not the resolution it advertises, the other way a garbage entry carries
     * a good-looking label to the top.
     */
    private const val MIN_PLAUSIBLE_4K_GB = 1.5
    private const val MIN_PLAUSIBLE_1080P_GB = 0.25

    /**
     * The 4K bonus on a device that cannot decode it: deliberately BELOW the
     * 1080p bonus, so a well-served 1080p outranks a 4K remux the box will
     * stall on. It still beats 720p, so 4K is preferred over genuinely lower
     * resolutions rather than vetoed.
     */
    private const val CONSTRAINED_4K_BONUS = 90

    /**
     * On a constrained device, a 4K/high-bitrate file at or above this size is
     * a stall rather than a treat: the box has neither the decode headroom nor
     * the buffer to ride out its bitrate. A 4K file this large also implies a
     * bitrate (tens of Mbps) the tuner-class hardware manages poorly.
     */
    private const val CONSTRAINED_HEAVY_4K_GB = 12.0
    private const val CONSTRAINED_HEAVY_PENALTY = 120

    /**
     * One stream and the facts the rules read, worked out once per stream. The
     * comparator runs O(n log n) times, so the text join, the URL decode and
     * the host parse must not happen inside a selector.
     */
    private class Candidate(
        val stream: Stream,
        val text: String,
        val debridServed: Boolean,
        val episodeRank: Int
    )

    /**
     * Orders a fetched list best-first, for the picker and for auto-play.
     *
     * [episode] is the (season, episode) the request was for, when it names
     * one: the comparator then keeps a source that declares another episode of
     * that season under every other source. Null (a movie, a live channel, an
     * id that carries no episode) leaves the order exactly as it was.
     */
    fun rank(
        streams: List<Stream>,
        episode: Pair<Int, Int>? = null,
        constrainedDevice: Boolean = false
    ): List<Stream> =
        streams
            .filter { stream -> isPlayable(stream) || !stream.infoHash.isNullOrBlank() }
            // The text every rule reads, whether the debrid service the viewer
            // pays for is the one serving the link, and what the source itself
            // says about the episode, all worked out once per stream.
            .map { stream ->
                val text = searchableText(stream)
                Candidate(
                    stream = stream,
                    text = text,
                    debridServed = isDebridServed(stream, text),
                    episodeRank = episodeRank(stream, episode)
                )
            }
            // Playability is its own tier, not a score bonus: no combination of
            // quality labels may lift an entry this app cannot open over one it
            // can. Kotlin's sort is stable, so within a tier the addon's own
            // order is kept for equal scores - and that is the whole contract
            // for an addon that already sorted and filtered its own results
            // (AIOStreams with a regex + SEL config): the ranker only speaks
            // where it has something to say.
            .sortedWith(
                compareByDescending<Candidate> { if (isPlayable(it.stream)) 1 else 0 }
                    // Known-bad copies sink as a class, above every question of
                    // quality or availability - the only way a cached CAM stays
                    // under an honest 480p, which no score bonus can promise.
                    .thenBy { if (isKnownBad(it.text)) 1 else 0 }
                    // The episode the request is for, then a source that says
                    // nothing about the episode, then one that names another.
                    // Where the known-bad tier keeps a bad copy of the right
                    // thing out of the head, this keeps the wrong thing out of
                    // it - content before labels, the same argument.
                    .thenBy { it.episodeRank }
                    // Served by the debrid service the viewer pays for. Its own
                    // tier, and above availability, because it is a fact about
                    // the link rather than a claim in a title: a scraper addon
                    // can call a hoster link instant, but it cannot put someone
                    // else's domain in the URL it hands over (see
                    // [isDebridServed]).
                    .thenByDescending { if (it.debridServed) 1 else 0 }
                    // Availability, because it is what the sorted addons sort on
                    // first and what the viewer's configuration asked for.
                    .thenByDescending { if (INSTANT_HINT.containsMatchIn(it.text)) 1 else 0 }
                    .thenByDescending { score(it.stream, it.text, constrainedDevice) }
            )
            .map { it.stream }

    /**
     * One line saying why [rank] placed a stream where it did: the tiers it
     * cleared, the score it earned, and the numbers behind that score.
     *
     * For the diagnostics report. "The ranker keeps putting this add-on above
     * that one" cannot be answered from the ordered list alone — the list shows
     * where an entry landed and never which rule put it there — and the two
     * rules that outrank every label (playability and the debrid link) are
     * invisible in the picker. Read-only: it walks exactly the paths [rank]
     * does.
     */
    internal fun explain(
        stream: Stream,
        episode: Pair<Int, Int>? = null,
        constrainedDevice: Boolean = false
    ): String {
        val text = searchableText(stream)
        return buildString {
            append(if (isPlayable(stream)) "playable" else "hash-only")
            if (isKnownBad(text)) append(" known-bad")
            if (episodeRank(stream, episode) == EPISODE_OTHER) append(" other-episode")
            if (isDebridServed(stream, text)) append(" debrid-served")
            if (INSTANT_HINT.containsMatchIn(text)) append(" instant")
            if (constrainedDevice) append(" constrained-device")
            append(" score=").append(score(stream, text, constrainedDevice))
            sizeInGb(stream, text)?.let { size ->
                append(" size=")
                    .append(String.format(java.util.Locale.US, "%.1f", size))
                    .append("GB")
            }
            seeders(text)?.let { peers -> append(" peers=").append(peers) }
            append(" · ").append(labelOf(stream))
            // What the file says it is, and what that means for the request:
            // "[S03E15 != S03E30]" is a whole report in one line, and it is the
            // line that was missing when the complaint was "it plays the wrong
            // episodes" and every session line agreed with itself.
            EpisodeMatch.declaredLabel(stream)?.let { declared ->
                append(" [").append(declared)
                if (episode != null) {
                    val requested = "S%02dE%02d".format(episode.first, episode.second)
                    if (declared != requested) append(" != ").append(requested)
                }
                append(']')
            }
        }
    }

    /**
     * Where a source belongs relative to the episode the request named:
     * [EPISODE_MATCH] for the episode itself, [EPISODE_NONE] for a source that
     * says nothing about it, [EPISODE_OTHER] for one that names another
     * episode of the same season. All three collapse to [EPISODE_MATCH] when
     * the request named no episode, so a movie's order is untouched.
     */
    private fun episodeRank(stream: Stream, episode: Pair<Int, Int>?): Int {
        if (episode == null) return EPISODE_MATCH
        return when (EpisodeMatch.verdict(stream, episode.first, episode.second)) {
            EpisodeMatch.Verdict.MATCHES -> EPISODE_MATCH
            EpisodeMatch.Verdict.UNKNOWN -> EPISODE_NONE
            EpisodeMatch.Verdict.DIFFERENT -> EPISODE_OTHER
        }
    }

    /**
     * The stream's own label for a log or report line.
     *
     * `title` alone was read here, and the add-ons this app is configured with
     * (AIOStreams above all) leave it empty while the release name sits in
     * `description` - so every reported source line read "(no title)", which
     * is exactly the field that would have answered "which file did it play?".
     */
    internal fun labelOf(stream: Stream): String =
        listOfNotNull(
            stream.title,
            stream.description,
            stream.name,
            stream.behaviorHints?.filename
        )
            .firstOrNull { it.isNotBlank() }
            ?.take(60)
            ?: "(no title)"

    /** True when this app can open the stream directly. */
    private fun isPlayable(stream: Stream): Boolean {
        val url = stream.url?.lowercase() ?: return false
        return url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("rtmp://")
    }

    /**
     * The resolution tier a stream's own text claims: 5 = 2160p/4K, 4 = 1440p,
     * 3 = 1080p, 2 = 720p, 1 = 480p, and 0 when it claims no resolution at all.
     *
     * Read from the same text every scoring rule reads ([searchableText]), and
     * using the same tokens [score] awards the resolution bonus on, so the
     * auto-play quality ceiling ([AutoPlayQuality]) cannot disagree with the
     * order the ranker produced. Exposed rather than private so the ceiling is
     * decided from one reading of the resolution instead of a second, drifting
     * copy of these tokens.
     */
    internal fun resolutionRank(stream: Stream): Int {
        val text = searchableText(stream)
        return when {
            "2160p" in text || "4k" in text -> 5
            "1440p" in text -> 4
            "1080p" in text -> 3
            "720p" in text -> 2
            "480p" in text -> 1
            else -> 0
        }
    }

    /**
     * True when the link is being served by a debrid service: the URL's host is
     * one of the services themselves (see [DEBRID_HOST]), or the entry carries
     * that service's own completion tag (see [DEBRID_TAG]).
     *
     * The distinction is not how good the file looks but who is on the other end
     * of the socket. A resolved debrid link is a completed file on a CDN that is
     * paid to serve video; a scraper addon's direct link is one hoster's copy of
     * the film on a host that throttles, expires its links and stalls. Compared
     * on labels alone the two were interchangeable, and the bigger label won -
     * which is how a 4K hoster link kept taking the head of the list from the
     * copy the viewer's own debrid service was already holding.
     */
    internal fun isDebridServed(stream: Stream, text: String): Boolean =
        DEBRID_HOST.containsMatchIn(hostOf(stream.url)) ||
            DEBRID_TAG.containsMatchIn(text)

    /**
     * The URL's host, lowercased, or an empty string when it has none.
     *
     * The one rule that reads the link rather than the text reads it here: the
     * host is the part of a stream an addon cannot fabricate, since a link that
     * streams from a debrid service has to point at that service to work.
     */
    private fun hostOf(url: String?): String {
        val authority = url?.substringAfter("://", "")?.substringBefore('/') ?: return ""
        return authority.substringAfterLast('@').lowercase()
    }

    /**
     * True for a copy a viewer would call broken: a CAM / telecine / screener,
     * a sample or trailer clip, a 3D pair, a foreign dub, or a copy whose
     * subtitles are burned into the picture.
     *
     * Checked as a tier in [rank] rather than subtracted from the score, so the
     * class keeps its promise ("under every honest source") no matter how the
     * bonuses below are weighted.
     */
    private fun isKnownBad(text: String): Boolean =
        UNWATCHABLE_RELEASE.containsMatchIn(text) ||
            THREE_D_RELEASE.containsMatchIn(text) ||
            FOREIGN_OR_HARDSUBBED_RELEASE.containsMatchIn(text)

    private fun score(stream: Stream, text: String, constrainedDevice: Boolean): Int {
        var score = 0

        // --- Signals from the stream's own fields ---

        // Widevine: the main player negotiates the license, so DRM stays a
        // (small) quality signal - deliberately nowhere near big enough to lift
        // a DRM entry over a better-labeled open one, since it also cannot be
        // handed to the MPV engine at all.
        if (stream.drm != null) score += 60

        // Separate audio track = often higher quality or proper muxing
        if (!stream.audioUrl.isNullOrBlank()) score += 40

        // --- Resolution ---
        //
        // On a memory-constrained box the 4K bonus drops below the 1080p one:
        // the +200 that was unconditional is what put a 40 Mbps remux at the
        // head of auto-play on hardware that cannot decode it. A capable device
        // is untouched.
        when {
            "2160p" in text || "4k" in text ->
                score += if (constrainedDevice) CONSTRAINED_4K_BONUS else 200
            "1440p" in text -> score += 175
            "1080p" in text -> score += 150
            "720p" in text -> score += 100
            "480p" in text -> score += 50
        }

        if (HDR_RELEASE.containsMatchIn(text)) score += 30

        // Only the best matching tier counts: a "WEB-DL BluRay REMUX" is a
        // remux, not three bonuses.
        RELEASE_TIERS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }
            ?.let { (_, bonus) -> score += bonus }

        // --- Peers, which only the uncached copies have to find ---
        //
        // AIOStreams sorts on seeders; this app read none of them, so two
        // uncached copies with the same labels were separated only by the
        // addon's own order. Tiered rather than linear: the gap between 4 and
        // 40 peers decides whether a stream starts at all, the gap between 400
        // and 900 does not.
        seeders(text)?.let { peers ->
            score += when {
                peers >= 100 -> 25
                peers >= 30 -> 18
                peers >= 10 -> 10
                peers >= 3 -> 4
                else -> 0
            }
        }

        // --- Size: bigger usually means less compressed, but it is a nudge
        // next to resolution/HDR and it is capped - past ~20 GB the file is
        // likelier to stall this device than to look better.
        val sizeGb = sizeInGb(stream, text)
        sizeGb?.let { score += (it.coerceAtMost(20.0) * 1.5).toInt() }

        // A heavy 4K on a constrained device is the stall this ranker was
        // picking: penalize it below the honest 1080p it was beating.
        if (constrainedDevice && sizeGb != null && sizeGb >= CONSTRAINED_HEAVY_4K_GB &&
            ("2160p" in text || "4k" in text)
        ) {
            score -= CONSTRAINED_HEAVY_PENALTY
        }

        // --- The one penalty left in the score ---
        //
        // The unwatchable, 3D and foreign-dub rules are tiers now (see
        // [isKnownBad]): as points each had to outweigh every bonus above, and
        // the answer - a bigger penalty - only ever met the next bonus head-on.
        // A contradicted resolution label is different in kind. The file still
        // plays; it simply is not the resolution it advertises, so it stays a
        // score term sitting next to the resolution it claims.
        score -= fakeQualityPenalty(stream, text)

        // Prefer streams that have a name (more metadata = more reliable source)
        if (!stream.name.isNullOrBlank()) score += 10
        if (!stream.title.isNullOrBlank()) score += 10

        return score
    }

    /**
     * Everything the rules may read, as one lowercase string.
     *
     * The link contributes its *filename*: the query string is dropped because
     * it carries tokens and signatures rather than release names, and the path
     * is percent-decoded so an encoded release name ("Some%20Film%201080p.mkv")
     * reads like the plain one the same addon sends elsewhere. [Stream.badges]
     * are not consulted - they are a user-imported pack, matched after ranking.
     */
    private fun searchableText(stream: Stream): String {
        val fromLink = stream.url
            ?.substringBefore('?')
            ?.let { path ->
                runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
            }

        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            fromLink
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()
    }

    /**
     * Peers the stream reports, or null when it reports none — an unknown swarm
     * is not an empty one, and guessing would demote honest sources.
     */
    private fun seeders(text: String): Int? =
        SEEDERS.find(text)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotEmpty() }
            ?.toIntOrNull()

    /**
     * Penalty for a resolution label the file's own size does not support:
     * "2160p" on a sub-[MIN_PLAUSIBLE_4K_GB] file, or "1080p" under
     * [MIN_PLAUSIBLE_1080P_GB]. Zero when the size is unknown — an unlabeled
     * size is not evidence of a fake, and guessing would demote honest sources.
     */
    private fun fakeQualityPenalty(stream: Stream, text: String): Int {
        val sizeGb = sizeInGb(stream, text) ?: return 0
        return when {
            ("2160p" in text || "4k" in text) && sizeGb < MIN_PLAUSIBLE_4K_GB -> 300
            "1080p" in text && sizeGb < MIN_PLAUSIBLE_1080P_GB -> 200
            else -> 0
        }
    }

    /**
     * The release's size in GB: the server's own hint when it sent one
     * (`behaviorHints.videoSize`, in bytes), otherwise the largest unit written
     * in the text. MB and TB used to be invisible to this - only "N gb" matched
     * - so a "700 MB" and a "1.2 TB" release both scored as unlabeled.
     */
    private fun sizeInGb(stream: Stream, text: String): Double? {
        stream.behaviorHints
            ?.videoSize
            ?.takeIf { it > 0 }
            ?.let { bytes -> return bytes / 1024.0 / 1024.0 / 1024.0 }

        val match = SIZE_IN_TEXT.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "mb" -> value / 1024.0
            "tb" -> value * 1024.0
            else -> value
        }
    }

    /**
     * The release's own size in GB, read from the text [rank] reads. Exposed
     * for [EpisodeMatch], which needs a size to decide whether a file can be
     * one episode at all — a different question from how good a copy it is,
     * which is all the scoring above asks.
     */
    internal fun sizeGb(stream: Stream): Double? = sizeInGb(stream, searchableText(stream))
}
