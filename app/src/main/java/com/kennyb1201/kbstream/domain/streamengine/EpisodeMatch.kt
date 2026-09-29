package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * Whether a source is the episode that was asked for.
 *
 * Reported bug: "Paw Patrol is still playing the wrong episodes" — with a
 * diagnostics report that could not explain it, because the app is internally
 * consistent. Every session line agreed with the id its stream was resolved
 * for, so the identity plumbing was never the problem: the *file* the add-on
 * handed back for that id was another episode.
 *
 * That is what happens wherever a show's episode numbering disagrees with the
 * numbering its releases carry. Paw Patrol is the textbook case. TMDB — and so
 * this app, and so the add-on's own metadata, which is TMDB-derived too —
 * splits season three into single 12-minute segments, fifty of them, while
 * every release of that season is named for a broadcast half-hour; the file
 * that holds segment 30 is named "S03E15". An add-on asked for "S03E30" can
 * therefore match no release name at all, and whatever its search does return
 * is handed straight to auto-play, which takes the head of the list without
 * ever asking whether the file claims to be the episode.
 *
 * So this is the question auto-play and the picker now ask first: does the
 * source say it is this episode? The answer is read from the source's own text
 * — its title, description, name, filename hint, or the filename in its link —
 * and never guessed from the title of the show:
 *
 *  - [Verdict.MATCHES]: the text names the requested season and episode.
 *  - [Verdict.DIFFERENT]: the text names the requested season and *another*
 *    episode. This is the answer that must never play by itself in silence.
 *  - [Verdict.UNKNOWN]: the text says nothing about the episode, or names a
 *    numbering that cannot be compared with the request at all — a different
 *    season (releases that number a show's episodes absolutely, "S01E1100" for
 *    what the metadata calls S22E17, are the everyday case), or a bare "E15"
 *    with no season next to it. Deliberately conservative: only a plain
 *    contradiction counts as [Verdict.DIFFERENT], because a file that does not
 *    contradict the request may well *be* it, and treating unlabeled sources
 *    as wrong would break auto-play for every show whose releases are simply
 *    unnamed.
 */
object EpisodeMatch {

    /** What a source's own text says about the episode it holds. */
    enum class Verdict {
        /** The text names the requested season and episode. */
        MATCHES,

        /** The text names the requested season and a different episode. */
        DIFFERENT,

        /** The text names no episode, or one that cannot be compared. */
        UNKNOWN
    }

    /**
     * The (season, episode) a source declares, or null when it declares none.
     *
     * [season] is null for a bare "E15", which names an episode without saying
     * which season it belongs to.
     */
    data class Declared(val season: Int?, val episode: Int)

    /**
     * The separators release names use between the parts of an episode
     * marker — "S03E15", "S03.E15", "S03 E15", "S03 - E15", "S03_E15" —
     * collapsed to a single space before the patterns below are run. One
     * normalization instead of a separator class repeated in every pattern,
     * which is also what makes "S03 - E15" (whose marker spaces straddle the
     * dash) read the same as "S03-E15".
     */
    private val SEPARATORS = Regex("""[\s._-]+""")

    // "S03E15", "s3e15" and — after normalization — "S03 E15", "S03-E15" and
    // "S03 - E15", which all arrive as "s03e15" or "s03 e15". Lowercase,
    // because the search is run against lowercased text.
    private val SEASON_EPISODE =
        Regex("""(?<![0-9a-z])s(\d{1,2}) ?e(\d{1,3})(?![0-9])""")

    // "3x15". Bounded on both sides, so the "1920x1080" in a resolution label
    // is not read as season 20 of episode 108.
    private val SEASON_X_EPISODE =
        Regex("""(?<![0-9a-z])(\d{1,2})x(\d{1,3})(?![0-9])""")

    // "Season 3 Episode 15", "Season 3 Ep. 15".
    private val SEASON_WORD_EPISODE =
        Regex(
            """(?<![0-9a-z])season ?(\d{1,2}) ?(?:episode|ep\.?) ?(\d{1,3})(?![0-9])"""
        )

    /**
     * The highest rate worth entertaining for one episode, in Mbps: above the
     * UHD Blu-ray maximum, so nothing a consumer release of that length
     * carries reaches it.
     *
     * A size says nothing on its own — it has to be read against a length — and
     * this is the ceiling that turns the pair into a yes or no. Deliberately
     * generous: it exists to catch a whole season sitting where an episode
     * should be (a 12-minute episode cannot be 13 GB without 150 Mbps), never
     * to second-guess an unusual but honest release.
     */
    private const val MAX_EPISODE_MBPS = 100.0

    /**
     * A name that pins a season without ever naming an episode of it — "S03",
     * "Season 3", "S03 COMPLETE". Read only when [declared] found no episode,
     * since "S03E15" names the season too and is emphatically not a pack.
     */
    private val SEASON_MARKER =
        Regex("""(?<![0-9a-z])(?:s|season ?)(\d{1,2})(?![0-9])""")

    // A bare "E15". Read, but it can never contradict a request: see [verdict].
    // The digit cap is what keeps an absolutely-numbered "E1100" out of this -
    // it is not an episode number this app's metadata will ever name, and
    // guessing a truncated 110 out of it would invent the mismatch this whole
    // object exists to catch.
    private val BARE_EPISODE =
        Regex("""(?<![0-9a-z])e(\d{1,3})(?![0-9])""")

    /**
     * The (season, episode) a play request's id names, or null when it names
     * none.
     *
     * A series video id is "<show id>:<season>:<episode>", where the show part
     * is whatever the add-on calls the show ("tt...", "kitsu:..." — anything),
     * so only the last two colon-separated segments are read, and only when
     * both of them are numbers. A movie id, a show-level id, or anything else
     * answers null — which callers must read as "the id does not say" rather
     * than "the id says nothing is there".
     */
    fun requestedFrom(streamId: String?): Pair<Int, Int>? {
        val parts = streamId?.trim()?.split(':') ?: return null
        if (parts.size < 3) return null
        val episode = parts[parts.size - 1].toIntOrNull() ?: return null
        val season = parts[parts.size - 2].toIntOrNull() ?: return null
        return season to episode
    }

    /**
     * The episode [stream]'s own text declares, or null when it declares none.
     *
     * The fields are read in the order a viewer reads them on the picker card
     * (title, description, name) and the two filename sources last, and the
     * first thing that names an episode wins — matching how the ranker reads
     * the same text. A season-level "S03" with no episode after it names
     * nothing, which is the correct answer for a season pack file.
     */
    fun declared(stream: Stream): Declared? {
        for (text in textFields(stream)) {
            SEASON_EPISODE.find(text)?.let { match ->
                return Declared(
                    season = match.groupValues[1].toInt(),
                    episode = match.groupValues[2].toInt()
                )
            }
            SEASON_WORD_EPISODE.find(text)?.let { match ->
                return Declared(
                    season = match.groupValues[1].toInt(),
                    episode = match.groupValues[2].toInt()
                )
            }
            SEASON_X_EPISODE.find(text)?.let { match ->
                return Declared(
                    season = match.groupValues[1].toInt(),
                    episode = match.groupValues[2].toInt()
                )
            }
            BARE_EPISODE.find(text)?.let { match ->
                return Declared(season = null, episode = match.groupValues[1].toInt())
            }
        }
        return null
    }

    /**
     * Whether [stream] is the episode [season]/[episode] asked for.
     *
     * A declaration of another season is [Verdict.UNKNOWN] rather than
     * [Verdict.DIFFERENT]: two numbering schemes that disagree about which
     * season an episode is in cannot be compared, and guessing would refuse to
     * auto-play exactly the shows whose releases are numbered absolutely.
     */
    fun verdict(stream: Stream, season: Int, episode: Int): Verdict {
        val declared = declared(stream) ?: return Verdict.UNKNOWN

        if (declared.episode != episode) {
            return if (declared.season == season) Verdict.DIFFERENT else Verdict.UNKNOWN
        }
        return if (declared.season == null || declared.season == season) {
            Verdict.MATCHES
        } else {
            Verdict.UNKNOWN
        }
    }

    /**
     * The declaration in the form the picker and the diagnostics report show
     * it: "S03E15", or "E15" when the source named no season.
     */
    fun declaredLabel(stream: Stream): String? =
        declared(stream)?.let { declared ->
            val season = declared.season
            if (season == null) {
                "E%02d".format(declared.episode)
            } else {
                "S%02dE%02d".format(season, declared.episode)
            }
        }

    /** True when [verdict] would refuse to play [stream] by itself. */
    fun isOtherEpisode(stream: Stream, season: Int?, episode: Int?): Boolean =
        season != null && episode != null && verdict(stream, season, episode) == Verdict.DIFFERENT

    /**
     * The source auto-play should start, or null when it should hand the viewer
     * to the picker instead.
     *
     * The episode the request is for wins outright; failing that, a source that
     * says nothing about the episode (the unlabeled release, the absolute
     * numbering) is the next best thing. A source that declares another episode
     * of the same season is never taken by itself — that is the whole point: it
     * is a file the app *knows* holds something else, and starting it silently
     * is the "playing the wrong episodes" report. Neither is a whole season: a
     * pack declares no episode, so it used to qualify as "says nothing" and be
     * taken, and it then plays from its own beginning — which is the same
     * complaint arriving by a different route, and the one a diagnostics
     * capture showed outright (11 sources for S03E35, and the head of the list
     * a 13 GB file against a 12-minute episode). When nothing is left, the
     * answer is null and the picker is shown, labeled, with the filenames in
     * front of the viewer.
     *
     * [runtimeMinutes] is the requested episode's own length, used only to read
     * a size: without one, a pack is recognized by the way it names itself
     * instead (see [isSeasonPack]).
     *
     * With no episode in the request (a movie, a live channel) this is the
     * first playable source, exactly as before.
     */
    fun autoplayPick(
        streams: List<Stream>,
        season: Int?,
        episode: Int?,
        runtimeMinutes: Int? = null
    ): Stream? {
        val playable = streams.filter { !it.url.isNullOrBlank() }
        if (season == null || episode == null) return playable.firstOrNull()

        var undeclared: Stream? = null
        for (stream in playable) {
            when (verdict(stream, season, episode)) {
                Verdict.MATCHES -> return stream
                Verdict.UNKNOWN -> {
                    if (undeclared == null && !isSeasonPack(stream, runtimeMinutes)) {
                        undeclared = stream
                    }
                }

                Verdict.DIFFERENT -> Unit
            }
        }
        return undeclared
    }

    /**
     * True when [stream] is a whole season (or a whole series) rather than one
     * episode.
     *
     * A pack is the one wrong file this object could not previously see: it
     * contradicts no episode, because it names none — so [verdict] calls it
     * [Verdict.UNKNOWN], the tier auto-play falls back to, and the file that
     * starts is a season beginning at its own first episode.
     *
     * Two readings, and the size wins when both are available. A size against a
     * length is a fact about the file: 13 GB in 12 minutes needs 150 Mbps, which
     * no release of that length carries, while a 700 MB file named for its
     * season is one episode whose name simply omitted the number. With no
     * length, or no size to read against it, the name is all there is: a source
     * that pins a season and never an episode is a pack.
     */
    fun isSeasonPack(stream: Stream, runtimeMinutes: Int?): Boolean {
        val runtime = runtimeMinutes?.takeIf { it > 0 }
        val sizeGb = if (runtime != null) StreamRanker.sizeGb(stream) else null
        if (runtime != null && sizeGb != null) {
            // GB to bits, over the episode's own seconds, in Mbps.
            return sizeGb * 8_000.0 / (runtime * 60.0) > MAX_EPISODE_MBPS
        }
        return declared(stream) == null &&
            textFields(stream).any { SEASON_MARKER.containsMatchIn(it) }
    }

    /**
     * True when the list holds playable sources and every one of them declares
     * another episode — the case the picker explains in words instead of
     * leaving the viewer to wonder why auto-play stopped.
     */
    fun onlyOtherEpisodes(streams: List<Stream>, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return false
        val playable = streams.filter { !it.url.isNullOrBlank() }
        return playable.isNotEmpty() &&
            playable.all { verdict(it, season, episode) == Verdict.DIFFERENT }
    }

    /**
     * The text of a source's own fields, lowercased and separator-normalized,
     * in reading order.
     */
    private fun textFields(stream: Stream): List<String> =
        listOfNotNull(
            stream.title,
            stream.description,
            stream.name,
            stream.behaviorHints?.filename,
            linkFileName(stream)
        )
            .filter { it.isNotBlank() }
            .map { SEPARATORS.replace(it.lowercase(), " ") }

    /**
     * The filename in the source's link, percent-decoded, or null for a link
     * that has none.
     *
     * The query string is dropped — it carries tokens rather than release
     * names — and the path is decoded so an encoded name reads like the plain
     * one the same add-on sends elsewhere. Read last: direct-link add-ons put
     * the whole release name here and nothing anywhere else, which is exactly
     * how a file that names its own episode went unread.
     */
    private fun linkFileName(stream: Stream): String? =
        stream.url
            ?.substringBefore('?')
            ?.substringAfterLast('/')
            ?.let { path ->
                runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
            }
}
