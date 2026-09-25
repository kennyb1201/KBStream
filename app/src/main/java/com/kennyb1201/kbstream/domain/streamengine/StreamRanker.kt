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
 *  3. **Availability.** A copy the debrid service already holds starts now; an
 *     uncached one of the same title waits for peers, whatever its resolution
 *     label says. AIOStreams sorts on exactly this first, and it is the one
 *     criterion a re-sort by quality labels alone got backwards - which is how
 *     a 4K that had to find its swarm ended up heading a list whose addon had
 *     deliberately put a cached 1080p there.
 *  4. Resolution, HDR/DV, release type, size, seeders and a resolution label
 *     its own file size contradicts, in that order of weight.
 *
 * Every rule reads the stream's *text*, and that text is every field the addon
 * sent plus the filename its link carries - not just `title ?: description ?:
 * name`. Two consequences of that were visible in the app: an addon that puts
 * the release name in `description` while `title` holds something short scored
 * as if it declared nothing, and direct-link addons - which routinely put the
 * resolution only in the path (`/Movies/Some Film (2024) 1080p BluRay.mkv`) -
 * never scored for their quality at all. That second one is why a hash-only
 * 4K entry could sit above a real 1080p link.
 */
object StreamRanker {

    /**
     * Releases that are not the film, or not a copy of it worth watching: a
     * CAM/telecine/screener capture, or a sample/trailer clip. All of them are
     * what a viewer means by "a problematic stream".
     *
     * WEBRip is deliberately NOT in this group. It is an ordinary source, and
     * penalising it the way a CAM is penalised pushed good web releases below
     * unlabelled ones.
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
     * well-labelled stream turns out to be unwatchable.
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
     */
    private val INSTANT_HINT = Regex("""\b(cached|instant)\b|\u26a1|\b(?:rd|tb)\+""")

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

    fun rank(streams: List<Stream>): List<Stream> =
        streams
            .filter { stream -> isPlayable(stream) || !stream.infoHash.isNullOrBlank() }
            // The text every rule reads, built once per stream. The comparator
            // below runs O(n log n) times, so joining and URL-decoding inside a
            // selector would repeat that work on every comparison.
            .map { stream -> stream to searchableText(stream) }
            // Playability is its own tier, not a score bonus: no combination of
            // quality labels may lift an entry this app cannot open over one it
            // can. Kotlin's sort is stable, so within a tier the addon's own
            // order is kept for equal scores - and that is the whole contract
            // for an addon that already sorted and filtered its own results
            // (AIOStreams with a regex + SEL config): the ranker only speaks
            // where it has something to say.
            .sortedWith(
                compareByDescending<Pair<Stream, String>> { if (isPlayable(it.first)) 1 else 0 }
                    // Known-bad copies sink as a class, above every question of
                    // quality or availability - the only way a cached CAM stays
                    // under an honest 480p, which no score bonus can promise.
                    .thenBy { if (isKnownBad(it.second)) 1 else 0 }
                    // Availability, because it is what the sorted addons sort on
                    // first and what the viewer's configuration asked for.
                    .thenByDescending { if (INSTANT_HINT.containsMatchIn(it.second)) 1 else 0 }
                    .thenByDescending { score(it.first, it.second) }
            )
            .map { it.first }

    /** True when this app can open the stream directly. */
    private fun isPlayable(stream: Stream): Boolean {
        val url = stream.url?.lowercase() ?: return false
        return url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("rtmp://")
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

    private fun score(stream: Stream, text: String): Int {
        var score = 0

        // --- Signals from the stream's own fields ---

        // Widevine: the main player negotiates the licence, so DRM stays a
        // (small) quality signal - deliberately nowhere near big enough to lift
        // a DRM entry over a better-labelled open one, since it also cannot be
        // handed to the MPV engine at all.
        if (stream.drm != null) score += 60

        // Separate audio track = often higher quality or proper muxing
        if (!stream.audioUrl.isNullOrBlank()) score += 40

        // --- Resolution ---
        when {
            "2160p" in text || "4k" in text -> score += 200
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
        sizeInGb(stream, text)?.let { score += (it.coerceAtMost(20.0) * 1.5).toInt() }

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
     * [MIN_PLAUSIBLE_1080P_GB]. Zero when the size is unknown — an unlabelled
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
     * - so a "700 MB" and a "1.2 TB" release both scored as unlabelled.
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
}
