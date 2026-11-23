package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * The episode numbers a season-wide MARK may cover, for BOTH screens and for
 * the ViewModel that writes them.
 *
 * This is the WRITE rule, and it fails closed (spec 2026-11-23, F3). A mark
 * writes a completed local row and fans out to Simkl, MDBList and Trakt, so an
 * episode may only be marked when something PROVES it aired. The display rule
 * is deliberately the other shape - [isEpisodeUnavailable] treats a missing
 * date as "aired", because a card with no date should not be labelled as
 * unreleased - and it stays that way: this file decides writes, not what the
 * cards say.
 *
 * The three holes this closes, all of which swept next week's episode into the
 * trackers:
 *
 *  - a loaded listing whose dates are contradicted by `next_episode_to_air` was
 *    passed WHOLE, future rows included;
 *  - an unloaded season with no pointer fell through to `1..episode_count`, the
 *    scheduled count, for a show that is still airing;
 *  - a missing or unreadable date counted as aired.
 *
 * What proves an episode aired:
 *
 *  - the loaded listing, per episode: a parseable date on or before today;
 *  - the show-level pointer: every episode of a season BEFORE the pointer's
 *    season, and every episode below the pointer in its own season. This is the
 *    only evidence that survives a stale season row;
 *  - a finished show (status ended/canceled, or its last episode already
 *    aired): its declared count, for a season with no other evidence.
 *
 * Anything else marks nothing. Null still means "no source knows how many
 * episodes there are", and the marking callers treat that as nothing too.
 */
internal object DetailSeasonEpisodes {

    fun numbers(
        season: Int,
        loadedSeason: Int?,
        episodes: List<ResolvedEpisode>,
        episodeCount: Int?,
        seasonAirDate: String?,
        nextEpisodeToAir: TmdbEpisodeAirInfo?,
        airDatesTrusted: Boolean,
        finished: Boolean,
        today: LocalDate = LocalDate.now()
    ): List<Int>? {
        val pointerSeason = nextEpisodeToAir?.seasonNumber?.takeIf { it > 0 }
        val pointerEpisode = nextEpisodeToAir?.episodeNumber?.takeIf { it > 0 }

        // A season whose own row says it has not premiered has nothing to mark,
        // however many episodes it is scheduled to have. Only a parseable date
        // proves that, same as below.
        if (provablyFuture(seasonAirDate, today)) return emptyList()

        /**
         * The show-level cap: the pointer says what has aired so far, so a
         * season after it has aired nothing and the pointer's own season stops
         * at its episode.
         */
        fun capped(numbers: List<Int>): List<Int> = when {
            pointerSeason == null -> numbers
            season > pointerSeason -> emptyList()
            season == pointerSeason ->
                if (pointerEpisode == null) emptyList() else numbers.filter { it < pointerEpisode }
            else -> numbers
        }

        if (season == loadedSeason && episodes.isNotEmpty()) {
            if (!airDatesTrusted) {
                // The listing's dates are contradicted by the pointer, so they
                // prove nothing. Mark by the show-level cap instead - never by
                // the stale row. With no cap, a season of a show still airing
                // has nothing provable in it.
                if (pointerSeason != null) {
                    val count = episodeCount?.takeIf { it > 0 } ?: return emptyList()
                    return capped((1..count).toList())
                }
                if (!finished) return emptyList()
                val count = episodeCount?.takeIf { it > 0 } ?: return null
                return (1..count).toList()
            }
            // A trusted listing proves each episode on its own date. A missing
            // or unreadable date proves nothing and is not marked.
            return capped(
                episodes
                    .filter { provablyAired(it.airDate, today) }
                    .map { it.episodeNumber }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
            )
        }

        // Not loaded: the pointer, then the show's own finished-ness, are the
        // only evidence there is.
        if (pointerSeason != null) {
            if (season > pointerSeason) return emptyList()
            if (season == pointerSeason) {
                val next = pointerEpisode ?: return emptyList()
                return if (next > 1) (1 until next).toList() else emptyList()
            }
            return (episodeCount?.takeIf { it > 0 } ?: return null).let { count ->
                (1..count).toList()
            }
        }

        // No pointer. A finished show's declared count stands for its whole
        // range; a show still airing cannot prove an unloaded season aired, so
        // nothing is marked.
        if (!finished) return emptyList()
        return episodeCount?.takeIf { it > 0 }?.let { count -> (1..count).toList() }
    }

    /**
     * True only when a parseable air date proves the episode has aired.
     *
     * The write path's half of the date rule. [isEpisodeUnavailable] is the
     * display half and is one-sided on purpose; this one is not.
     */
    internal fun provablyAired(airDate: String?, today: LocalDate = LocalDate.now()): Boolean {
        val date = episodeAirDate(airDate) ?: return false
        return !date.isAfter(today)
    }

    /** True only when a parseable date proves the episode has not aired yet. */
    internal fun provablyFuture(airDate: String?, today: LocalDate = LocalDate.now()): Boolean {
        val date = episodeAirDate(airDate) ?: return false
        return date.isAfter(today)
    }

    /**
     * The calendar day [raw] names, or null when it names none.
     *
     * Two shapes reach an [ResolvedEpisode.airDate]: TMDB's plain `yyyy-MM-dd`,
     * and - for a title TMDB has no record for, whose seasons are synthesized
     * from the add-on's own `Meta.videos` - Stremio's full ISO-8601 stamp
     * (`2026-11-01T00:00:00.000Z`). An unreleased add-on episode's date used to
     * fail to parse and read as "aired"; accept either shape, and a local
     * timestamp with no offset too. A truly unreadable value stays null, never a
     * guess.
     *
     * It lives here, beside the two rules that read it, because the write rule
     * and the display rule must not disagree about what a date says.
     */
    internal fun episodeAirDate(raw: String?): LocalDate? {
        val trimmed = raw?.trim().orEmpty()

        if (trimmed.isEmpty()) {
            return null
        }

        return runCatching {
            LocalDate.parse(trimmed)
        }.recoverCatching {
            OffsetDateTime.parse(trimmed).toLocalDate()
        }.recoverCatching {
            LocalDateTime.parse(trimmed).toLocalDate()
        }.getOrNull()
    }

    /**
     * Whether the show is over, from the two fields TMDB gives: its status, or
     * a last episode that has already aired.
     *
     * A show with a pointer is still airing whatever its status says, so
     * callers that gate on this only reach it when there is no pointer.
     * Strictly before today: an episode airing today is not proof the run has
     * finished.
     */
    fun isFinished(
        status: String?,
        lastEpisodeAirDate: String?,
        today: LocalDate = LocalDate.now()
    ): Boolean {
        when (status?.trim()?.lowercase()) {
            "ended", "canceled", "cancelled" -> return true
        }
        val last = episodeAirDate(lastEpisodeAirDate) ?: return false
        return last.isBefore(today)
    }

    /**
     * The seasons whose season-row air date disagrees with the show-level
     * `next_episode_to_air` pointer, i.e. the seasons whose row is the stale
     * source.
     *
     * Per-episode dates for those seasons cannot be trusted either, so the
     * episode cards keep their date but drop the UNAVAILABLE verdict, and a
     * season mark/unmark goes by the show-level cap rather than the listing.
     */
    fun staleSeasons(detail: TmdbDetail?): Set<Int> {
        val pointer = detail?.nextEpisodeToAir
        val seasonNumber = pointer?.seasonNumber ?: return emptySet()
        if (pointer.airDate.isNullOrBlank()) return emptySet()
        val rowDate = detail.seasons
            .firstOrNull { it.seasonNumber == seasonNumber }
            ?.airDate
            ?: return emptySet()
        return if (rowDate != pointer.airDate) setOf(seasonNumber) else emptySet()
    }
}
