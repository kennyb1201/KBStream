package com.kennyb1201.kbstream.data.debrid

/**
 * The two decisions the "add TorBox cloud files to Library" sync makes about
 * each of the account's torrents, pulled out of the network code so they can be
 * unit tested: what the torrent's name says the title *is*, and which TMDB
 * match is that title.
 *
 * Both are deliberately conservative. A torrent name is a release name, not a
 * metadata record — it is full of quality/group noise and may name a title TMDB
 * spells differently — so anything this cannot resolve confidently is skipped
 * rather than guessed at. A wrong row in the viewer's Library is worse than a
 * missing one, and a skipped torrent simply stays for the next round.
 */
internal object TorBoxCloudRules {

    /** What a release name yields: a display title and, when stated, a year. */
    data class ParsedName(val title: String, val year: Int?)

    /** One TMDB search result, in the shape the match rule compares. */
    data class Candidate(
        val mediaType: String,
        val tmdbId: Int,
        val title: String,
        val year: Int?,
        val posterPath: String?,
        val voteAverage: Double?
    )

    /**
     * The containers a TorBox file can be played from.
     *
     * ONE list, deliberately: the picker chooses what to offer from it, the
     * resolver chooses the biggest playable file from it, and the cloud list
     * (Settings) decides what to show at all from it. Three copies of "what is
     * playable" is how the dialog came to list screenshots and .nfo files the
     * picker would never offer - so adding a container is now one line, in one
     * place, for all three.
     */
    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg", "flv"
    )

    /**
     * Whether [name] names a file a play can use: the extension after the last
     * dot, trimmed and lowercased, in [VIDEO_EXTENSIONS].
     *
     * A name with no extension is not playable. Case is ignored, because
     * release names are not consistent about it (`.MKV` is common).
     */
    fun isVideo(name: String): Boolean =
        name.substringAfterLast('.', "").trim().lowercase() in VIDEO_EXTENSIONS

    /** A four-digit year, as release names spell it. */
    private val YEAR = Regex("\\b(19\\d{2}|20\\d{2})\\b")

    /**
     * Where the title ends when a name states no year: the first quality,
     * source, codec or group token. Matched as whole words so a title containing
     * one ("Web", "Dts") is not cut short.
     */
    private val CUT_TOKENS = Regex(
        "\\b(2160p|1080p|720p|480p|4k|uhd|hdr10|hdr|dolby|dovi|remux|webrip|web-dl|" +
            "webdl|web|bluray|blu-ray|brrip|bdrip|hdtv|hdcam|x264|x265|h264|h265|" +
            "hevc|avc|aac|ac3|eac3|dts|ddp|dd5|proper|repack|extended|remastered)\\b",
        RegexOption.IGNORE_CASE
    )

    /** A season/episode token: `S01`, `S01E02`, `1x02`, or the word "season". */
    private val EPISODE = Regex(
        "\\b(s\\d{1,2}(e\\d{1,2})?|\\d{1,2}x\\d{1,2}|season\\s*\\d{1,2}|complete)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * The second half of an episode RANGE (`S01E01-E02`), left behind when
     * [EPISODE] removes the first marker.
     *
     * A two-part release names both episodes in its title, and cutting only the
     * first marker left "-E02" welded to the show's name - which made the title
     * comparison fail on exactly the files a range is meant to serve.
     */
    private val EPISODE_RANGE_TAIL = Regex("\\s*[-~]\\s*e\\d{1,3}\\b", RegexOption.IGNORE_CASE)

    private val BRACKETS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")

    /**
     * The title and year a release name states, or null when the name is too
     * sparse to search with.
     *
     * The LAST year in the name wins: a name like `Blade Runner 2049 2017 1080p`
     * carries the title's own year first and the release year second, and only
     * the latter belongs to the search.
     */
    fun parseName(raw: String): ParsedName? {
        val cleaned = BRACKETS.replace(raw.replace('.', ' ').replace('_', ' '), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return null

        val maxYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 2
        val yearMatch = YEAR.findAll(cleaned)
            .lastOrNull { (it.value.toIntOrNull() ?: 0) in 1900..maxYear }

        val title: String
        val year: Int?
        if (yearMatch != null) {
            title = cleaned.substring(0, yearMatch.range.first)
            year = yearMatch.value.toIntOrNull()
        } else {
            val cut = CUT_TOKENS.find(cleaned) ?: EPISODE.find(cleaned)
            title = if (cut != null) cleaned.substring(0, cut.range.first) else cleaned
            year = null
        }

        val bare = EPISODE_RANGE_TAIL
            .replace(EPISODE.replace(title, " "), " ")
            .trim()
            .trim('-', ' ', ':', '|')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (bare.length < 2 || bare.all { !it.isLetterOrDigit() }) return null
        return ParsedName(title = bare, year = year)
    }

    /** Letters/digits only, lowercased: how two titles are compared. */
    fun normalizedTitle(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    /** True when a release name names a series (a season/episode marker). */
    fun looksLikeSeries(raw: String): Boolean = EPISODE.containsMatchIn(raw)

    /**
     * Whether [parsed] names [title] with the same confidence [bestMatch]
     * demands of a TMDB candidate.
     *
     * The picker knows the title it is showing - it is the same title, from the
     * same metadata, that the sync would have searched TMDB for - so it does
     * not need a search to ask the question. It does need the SAME bar, though:
     * an exact normalized title and agreeing years when both sides state one,
     * because a cloud row on the wrong show is exactly the guess
     * [bestMatch] refuses to make. [score] is the one implementation; this is
     * that rule without a candidate to score.
     */
    fun confidentMatch(parsed: ParsedName, title: String, year: Int? = null): Boolean {
        val target = normalizedTitle(parsed.title)
        if (target.isBlank()) return false
        if (target != normalizedTitle(title)) return false
        return yearsAgree(parsed.year, year)
    }

    /**
     * The title part of a picker's own heading.
     *
     * A series picker is handed "Landman S1 E10 • The Crumbs of Hope" (see
     * StreamsTarget), which is not the title a release name states: the show
     * name is everything before the episode marker. A heading with no marker is
     * returned as it is, so a movie's title passes through untouched.
     */
    fun showTitleOf(pickerTitle: String): String =
        EPISODE_TAIL.replace(pickerTitle, "").trim().ifBlank { pickerTitle.trim() }

    private val EPISODE_TAIL = Regex(
        "\\s+s\\d{1,2}\\s*(?:e\\d{1,3}|\\s+e\\d{1,3})\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * The episode(s) a file name states, or null when it states none.
     *
     * Ranges are read as ranges (`S01E01-E02`, `E01-E02`), because a two-part
     * file holds both episodes and earns a row on each (spec F1a). [season] is
     * null for a marker that names an episode without a season (`E10`), which
     * the caller fills in from the torrent's own name when it has one.
     */
    data class EpisodeSpan(val season: Int?, val start: Int, val end: Int)

    private val SEASON_EPISODE_RANGE = Regex(
        "(?<![0-9a-z])s(\\d{1,2})\\s*e(\\d{1,3})\\s*[-~]\\s*(?:s\\d{1,2})?e?(\\d{1,3})(?![0-9])",
        RegexOption.IGNORE_CASE
    )
    private val EPISODE_RANGE = Regex(
        "(?<![0-9a-z])e(\\d{1,3})\\s*[-~]\\s*e(\\d{1,3})(?![0-9])",
        RegexOption.IGNORE_CASE
    )
    private val SEASON_EPISODE = Regex(
        "(?<![0-9a-z])s(\\d{1,2})\\s*[-_. ]?\\s*e(\\d{1,3})(?![0-9])",
        RegexOption.IGNORE_CASE
    )
    private val SEASON_X_EPISODE = Regex(
        "(?<![0-9a-z])(\\d{1,2})x(\\d{1,3})(?![0-9])"
    )
    private val SEASON_WORD_EPISODE = Regex(
        "(?<![0-9a-z])season\\s*(\\d{1,2})\\s*(?:episode|ep\\.?)\\s*(\\d{1,3})(?![0-9])",
        RegexOption.IGNORE_CASE
    )
    private val BARE_EPISODE = Regex(
        "(?<![0-9a-z])e(\\d{1,3})(?![0-9])",
        RegexOption.IGNORE_CASE
    )
    private val SEASON_ONLY = Regex(
        "(?<![0-9a-z])(?:s|season\\s*)(\\d{1,2})(?![0-9])",
        RegexOption.IGNORE_CASE
    )

    /** The episode span [raw] states, read on its own. */
    fun episodeSpan(raw: String): EpisodeSpan? {
        SEASON_EPISODE_RANGE.find(raw)?.let { match ->
            val a = match.groupValues[2].toIntOrNull() ?: return@let
            val b = match.groupValues[3].toIntOrNull() ?: return@let
            return EpisodeSpan(match.groupValues[1].toIntOrNull(), minOf(a, b), maxOf(a, b))
        }
        EPISODE_RANGE.find(raw)?.let { match ->
            val a = match.groupValues[1].toIntOrNull() ?: return@let
            val b = match.groupValues[2].toIntOrNull() ?: return@let
            return EpisodeSpan(null, minOf(a, b), maxOf(a, b))
        }
        SEASON_EPISODE.find(raw)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return@let
            return EpisodeSpan(season, episode, episode)
        }
        SEASON_WORD_EPISODE.find(raw)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return@let
            return EpisodeSpan(season, episode, episode)
        }
        SEASON_X_EPISODE.find(raw)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return@let
            // A resolution label (1920x1080) is not an episode marker; the
            // season half is bounded to two digits, which keeps it out.
            if (season == null || season > 99) return@let
            return EpisodeSpan(season, episode, episode)
        }
        BARE_EPISODE.find(raw)?.let { match ->
            val episode = match.groupValues[1].toIntOrNull() ?: return@let
            return EpisodeSpan(null, episode, episode)
        }
        return null
    }

    /** The season [raw] names on its own (`S01`, `Season 1`), or null. */
    fun seasonOf(raw: String): Int? = SEASON_ONLY.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The episodes a cloud file holds, from the file name and its torrent's.
     *
     * The FILE is the authority (spec F1a): a season pack's torrent name says
     * "S01" and nothing about an episode, so only a file that names one earns a
     * row. A single-file torrent is named for what it holds, so its own name is
     * read when the file name states no episode - and a season the file omits is
     * filled from the torrent name (`Show.S01.1080p/Show.E10.mkv`).
     */
    fun fileSpan(torrentName: String, fileName: String): EpisodeSpan? {
        val fromFile = episodeSpan(fileName)
        val fromTorrent = episodeSpan(torrentName)
        val span = fromFile ?: fromTorrent ?: return null
        if (span.season != null) return span
        return span.copy(season = fromTorrent?.season ?: seasonOf(torrentName))
    }

    /**
     * Whether [span] covers [episode] of [season].
     *
     * A span that names no season covers every season, because a bare `E10` is
     * how a file inside a season pack names itself; a span that names another
     * season does not.
     */
    fun spanCovers(span: EpisodeSpan?, season: Int?, episode: Int): Boolean {
        if (span == null) return false
        if (span.season != null && season != null && span.season != season) return false
        return episode in span.start..span.end
    }

    /**
     * The best TMDB candidate for [parsed], or null when none is a confident
     * match.
     *
     * Only an exact normalized title match qualifies — punctuation, spacing and
     * case are ignored, but a sequel or a remake does not match the original.
     * When both sides state a year they must agree within a year. Ties break on
     * vote average, then on the lower TMDB id, so the choice is stable.
     */
    fun bestMatch(parsed: ParsedName, candidates: List<Candidate>): Candidate? {
        val target = normalizedTitle(parsed.title)
        if (target.isBlank()) return null
        return candidates
            .mapNotNull { candidate ->
                score(candidate, target, parsed.year)?.let { candidate to it }
            }
            .maxWithOrNull(
                compareBy({ it.second }, { it.first.voteAverage ?: 0.0 }, { -it.first.tmdbId })
            )
            ?.first
    }

    /**
     * How many rounds an EMPTY search result is retried for.
     *
     * A search that came back empty is as likely to be a network blip or a
     * TMDB hiccup as a release name nothing will ever match, and the two need
     * opposite treatment. Marked handled on the first empty answer, one blip
     * lost the title for good (the field report: a TorBox film that never
     * appeared in the Library, though the same name resolved the next day).
     * Left unrecorded — which is what the sync did — every round spent a TMDB
     * search on every unmatched torrent in the account, forever, on every
     * launch and every schedule tick. Three rounds is the middle: a blip is
     * retried, and a name TMDB does not know stops costing lookups.
     */
    const val EMPTY_SEARCH_ATTEMPTS = 3

    /**
     * Whether a torrent whose search came back empty [attempts] rounds running
     * is still worth another look.
     */
    fun retriesEmptySearch(attempts: Int): Boolean = attempts < EMPTY_SEARCH_ATTEMPTS

    /**
     * Whether a torrent that searched FINE but produced no confident match
     * [attempts] rounds running is still worth another look.
     *
     * The same budget as [retriesEmptySearch], because it is the same
     * uncertainty: "TMDB did not match this" is a statement about one search
     * result, not about the title, and TMDB's coverage moves. Marked handled on
     * the first no-match, a title TMDB indexed a week later could never be
     * added without the viewer finding a button.
     */
    fun retriesNoMatch(attempts: Int): Boolean = attempts < EMPTY_SEARCH_ATTEMPTS

    /**
     * Whether a parsed year and a known year can be the same title. Silence on
     * either side agrees; two years that are not within one do not.
     */
    fun yearsAgree(parsedYear: Int?, year: Int?): Boolean {
        if (parsedYear == null || year == null) return true
        return kotlin.math.abs(parsedYear - year) <= 1
    }

    private fun score(candidate: Candidate, target: String, year: Int?): Double? {
        if (candidate.tmdbId <= 0) return null
        if (normalizedTitle(candidate.title) != target) return null
        if (!yearsAgree(candidate.year, year)) return null

        val candidateYear = candidate.year
        val yearScore = when {
            year == null || candidateYear == null -> 0.0
            year == candidateYear -> 30.0
            kotlin.math.abs(year - candidateYear) <= 1 -> 15.0
            else -> return null
        }

        val posterScore = if (candidate.posterPath != null) 5.0 else 0.0
        return 100.0 + yearScore + posterScore + (candidate.voteAverage ?: 0.0) / 10.0
    }
}
