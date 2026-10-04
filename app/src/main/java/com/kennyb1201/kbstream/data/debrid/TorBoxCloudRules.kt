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

        val bare = EPISODE.replace(title, " ")
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

    private fun score(candidate: Candidate, target: String, year: Int?): Double? {
        if (candidate.tmdbId <= 0) return null
        if (normalizedTitle(candidate.title) != target) return null

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
