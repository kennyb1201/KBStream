package com.kennyb1201.kbstream.data.bingecat

/**
 * Public-lists browse and import, with no network and no Android in it.
 *
 * Five decisions decide what the surface offers and what it says afterwards;
 * each one is wrong silently if it drifts, so they are pure here and pinned by
 * a unit test rather than by a TV:
 *
 *  - **Which import modes a list may be offered.** The contract takes five
 *    (`movie` / `series` / `split` / `combined` / `anime`) and answers
 *    `unsupported_import_mode` for a list that cannot take one, so the picker
 *    only offers what the list's own counts and content type support - and
 *    `anime` is hidden, never disabled, when the list is not anime.
 *  - **What a per-list verdict means.** The import answers about the ONE list
 *    sent: `already_imported` is a satisfied state, `not_found` /
 *    `not_importable` are a failed import, and a job is a started one.
 *  - **When an import has finished.** The job's statuses are the contract's
 *    (`queued`/`running`/`succeeded`/`partial`/`failed`/`cancelled`) plus a
 *    stable failure code, never a message.
 *  - **How long to wait between polls**, from the job's own `Retry-After`.
 *  - **Attribution**: every card names the list as a BingeCat community list
 *    and its owner, because a public list is nobody's editorial pick - and it
 *    is never presented as one.
 */

/** One import mode, in the contract's wire spelling. */
enum class BingeCatImportMode(val wireValue: String) {
    COMBINED("combined"),
    SPLIT("split"),
    MOVIE("movie"),
    SERIES("series"),
    ANIME("anime")
}

/** The search screen's own type filter (the contract's `type` enum here). */
enum class BingeCatSearchType(val wireValue: String, val label: String) {
    ALL("all", "ALL"),
    MOVIE("movie", "MOVIES"),
    SERIES("series", "SERIES"),
    ANIME("anime", "ANIME")
}

/**
 * One public list as the screen draws it, whichever route found it.
 *
 * The two discovery routes carry the same card in different shapes (a row names
 * its owner as a string and identifies itself by `key`; a search result nests
 * the owner and identifies itself by `id`), so both are levelled into this one
 * type the moment they arrive. [importId] is what an import sends - the
 * search-result `id` the contract documents as the selection key.
 */
data class BingeCatPublicListCard(
    val importId: String,
    val name: String,
    val description: String? = null,
    val owner: String? = null,
    val provider: String? = null,
    val contentType: String? = null,
    val itemCount: Int? = null,
    val movieCount: Int? = null,
    val seriesCount: Int? = null,
    val likes: Int? = null,
    val addedCount: Int? = null,
    val isOfficial: Boolean = false,
    val thumbnailUrl: String? = null,
    val coverPosters: List<String> = emptyList(),
    val sourceUrl: String? = null
)

/** What the import route said about the one list that was sent. */
sealed interface BingeCatImportFeedback {
    /** Nothing was imported by THIS call: a job is queued or running. */
    data class Started(val jobUrl: String?, val jobId: String?) : BingeCatImportFeedback

    /** The list is already in this profile's lists - a satisfied state. */
    data object AlreadyImported : BingeCatImportFeedback

    /** The list no longer exists on BingeCat's side. */
    data object NotFound : BingeCatImportFeedback

    /** The list exists and this profile may not import it. */
    data class NotImportable(val detail: String) : BingeCatImportFeedback
}

/** What a job poll said. */
sealed interface BingeCatJobOutcome {
    /** `queued` or `running`: keep polling (bounded by the caller). */
    data object Pending : BingeCatJobOutcome

    /**
     * The job finished. [partial] is the contract's `partial` (finished, not
     * fully applied), [detail] its stable code when it carried one.
     */
    data class Finished(val partial: Boolean, val detail: String?) : BingeCatJobOutcome

    /** The job ended without importing: status `failed`/`cancelled`, or a code. */
    data class Failed(val reason: String) : BingeCatJobOutcome
}

object BingeCatPublicListRules {

    /** The mode the confirmation starts on when the list supports it. */
    val DEFAULT_MODE = BingeCatImportMode.COMBINED

    /** The contract's `search:read` scope name, for the screen's gate text. */
    const val PUBLIC_LISTS_SCOPE = BingeCatConfig.PUBLIC_LISTS_SCOPE

    /** How long to wait when a job's acceptance carried no `Retry-After`. */
    const val DEFAULT_RETRY_AFTER_SECONDS = 5L

    /** A job is polled for this long before the screen reports it. */
    const val JOB_MAX_WAIT_SECONDS = 180L

    /** True when a list's content type names anime (mode `anime` is hidden otherwise). */
    fun isAnime(contentType: String?): Boolean =
        contentType?.trim()?.lowercase()?.contains("anime") == true

    /**
     * The import modes this list may be offered.
     *
     * `combined` always, `split` only when the list holds both halves (splitting
     * a movie-only list would produce an empty second list), and each
     * single-type mode only when the list is not known to lack that type. An
     * unknown count (`null`) does not hide a mode: the contract's counts are
     * nullable, and hiding a mode on a missing count would withhold the one the
     * viewer wanted. `anime` is offered ONLY for an anime list.
     */
    fun modesFor(
        contentType: String?,
        movieCount: Int? = null,
        seriesCount: Int? = null
    ): List<BingeCatImportMode> {
        val modes = mutableListOf(BingeCatImportMode.COMBINED)
        val movies = movieCount?.takeIf { it > 0 } ?: 0
        val series = seriesCount?.takeIf { it > 0 } ?: 0
        if (movieCount == null || seriesCount == null || (movies > 0 && series > 0)) {
            modes += BingeCatImportMode.SPLIT
        }
        if (movieCount == null || movies > 0) modes += BingeCatImportMode.MOVIE
        if (seriesCount == null || series > 0) modes += BingeCatImportMode.SERIES
        if (isAnime(contentType)) modes += BingeCatImportMode.ANIME
        return modes
    }

    /** The mode a fresh confirmation starts on: the default when offered. */
    fun initialMode(contentType: String?, movieCount: Int?, seriesCount: Int?): BingeCatImportMode {
        val offered = modesFor(contentType, movieCount, seriesCount)
        return if (DEFAULT_MODE in offered) DEFAULT_MODE else offered.first()
    }

    /** What one mode's button says. */
    fun modeLabel(mode: BingeCatImportMode): String = when (mode) {
        BingeCatImportMode.COMBINED -> "ONE COMBINED LIST"
        BingeCatImportMode.SPLIT -> "SPLIT MOVIES + SERIES"
        BingeCatImportMode.MOVIE -> "MOVIES ONLY"
        BingeCatImportMode.SERIES -> "SERIES ONLY"
        BingeCatImportMode.ANIME -> "ANIME"
    }

    /** What pressing that mode will do, in the confirmation's own words. */
    fun modeDescription(mode: BingeCatImportMode): String = when (mode) {
        BingeCatImportMode.COMBINED -> "One list holding the movies and the shows."
        BingeCatImportMode.SPLIT -> "Two lists: one for movies, one for shows."
        BingeCatImportMode.MOVIE -> "One list holding only the movies."
        BingeCatImportMode.SERIES -> "One list holding only the shows."
        BingeCatImportMode.ANIME -> "One list holding the anime."
    }

    /**
     * A list card's title counts: the split form when both halves are known, the
     * total otherwise, and nothing at all when the list did not say.
     */
    fun countsLabel(movieCount: Int?, seriesCount: Int?, itemCount: Int?): String? {
        val movies = movieCount?.takeIf { it >= 0 }
        val series = seriesCount?.takeIf { it >= 0 }
        if (movies != null && series != null && movies + series > 0) {
            return listOfNotNull(
                movies.takeIf { it > 0 }?.let { "$it movie${plural(it)}" },
                series.takeIf { it > 0 }?.let { "$it series" }
            ).joinToString(" · ")
        }
        val total = itemCount?.takeIf { it > 0 } ?: return null
        return "$total title${plural(total)}"
    }

    /** A category tile's list count, from the contract's open-keyed map. */
    fun categoryCountLabel(counts: Map<String, Int>): String? {
        val total = counts["all"]?.takeIf { it > 0 }
            ?: listOf("movies", "shows", "anime")
                .mapNotNull { counts[it] }
                .takeIf { it.isNotEmpty() }
                ?.sum()
                ?.takeIf { it > 0 }
            ?: counts.values.maxOrNull()?.takeIf { it > 0 }
            ?: return null
        return "$total list${plural(total)}"
    }

    /** A row's owner (`owner` is a plain string there), or null when unnamed. */
    fun ownerLabel(owner: String?): String? = owner?.trim()?.takeIf { it.isNotBlank() }

    /** A search result's owner: display name first, username as the fallback. */
    fun ownerLabel(owner: BingeCatPublicOwner?): String? =
        owner?.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: owner?.username?.trim()?.takeIf { it.isNotBlank() }

    /**
     * The attribution line every public-list card and detail carries.
     *
     * A public list is a BingeCat community list, and saying so - with its
     * owner - is what keeps it from reading as KBStream's own pick. `official`
     * marks BingeCat's own lists, which are still named as BingeCat's, not
     * KBStream's.
     */
    fun attribution(
        owner: String?,
        isOfficial: Boolean = false,
        provider: String? = null
    ): String {
        val ownerName = ownerLabel(owner)
        val kind = if (isOfficial) {
            "BingeCat official list"
        } else {
            "BingeCat community list"
        }
        val source = provider?.trim()?.takeIf { it.isNotBlank() }
        val origin = if (source != null) " · from $source" else ""
        return if (ownerName != null) "$kind by $ownerName$origin" else "$kind$origin"
    }

    /** "12 likes", or null when the API carried none. */
    fun likesLabel(likes: Int?): String? =
        likes?.takeIf { it > 0 }?.let { "$it like${plural(it)}" }

    /** "added by 3 profiles", the search result's secondary line. */
    fun addedByLabel(addedCount: Int?): String? =
        addedCount?.takeIf { it > 0 }?.let { "added by $it profile${plural(it)}" }

    /** One row card, levelled into the screen's card. Null when it cannot be shown. */
    fun cardFromRow(row: BingeCatPublicListRow): BingeCatPublicListCard? {
        val id = row.key?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val name = row.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return BingeCatPublicListCard(
            importId = id,
            name = name,
            description = row.description?.trim()?.takeIf { it.isNotBlank() },
            owner = ownerLabel(row.owner),
            contentType = row.contentType,
            itemCount = row.itemCount,
            movieCount = row.movieCount,
            seriesCount = row.seriesCount,
            likes = row.likes,
            thumbnailUrl = row.thumbnailUrl,
            coverPosters = row.coverPosters,
            provider = row.source
        )
    }

    /** One search result card, levelled into the screen's card. */
    fun cardFromSearch(result: BingeCatPublicSearchResult): BingeCatPublicListCard? {
        val id = result.id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val name = result.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return BingeCatPublicListCard(
            importId = id,
            name = name,
            description = result.description?.trim()?.takeIf { it.isNotBlank() },
            owner = ownerLabel(result.owner),
            provider = result.provider,
            contentType = result.contentType,
            itemCount = result.itemCount,
            movieCount = result.movieCount,
            seriesCount = result.seriesCount,
            likes = result.likes,
            addedCount = result.addedCount,
            isOfficial = result.isOfficial,
            thumbnailUrl = result.thumbnailUrl,
            coverPosters = result.coverPosters,
            sourceUrl = result.sourceUrl
        )
    }

    /** True when a value would be a category tile: a slug and a name. */
    fun isDrawable(category: BingeCatPublicCategory): Boolean =
        category.slug?.trim()?.isNotBlank() == true &&
            category.name?.trim()?.isNotBlank() == true

    /**
     * What the import route's answer means for the [importId] that was sent.
     *
     * The arrays are the contract's per-list verdicts and they name the list by
     * the id sent, which is why v1 imports ONE list per action: with a batch,
     * a verdict could not be attributed to the card the viewer pressed.
     */
    fun feedbackFor(
        accepted: BingeCatPublicListImportAccepted,
        importId: String
    ): BingeCatImportFeedback {
        if (accepted.alreadyImported.contains(importId)) return BingeCatImportFeedback.AlreadyImported
        if (accepted.notFound.contains(importId)) return BingeCatImportFeedback.NotFound
        if (accepted.notImportable.contains(importId)) {
            return BingeCatImportFeedback.NotImportable("BingeCat says this list cannot be imported")
        }
        val job = accepted.job
        if (job?.statusUrl?.isNotBlank() == true || job?.id?.isNotBlank() == true) {
            return BingeCatImportFeedback.Started(
                jobUrl = job.statusUrl?.takeIf { it.isNotBlank() },
                jobId = job.id?.takeIf { it.isNotBlank() }
            )
        }
        // 200 with no job and no verdict: nothing was left to import, and the
        // contract gives no reason - the honest answer is a failed import, not
        // a fake success.
        return BingeCatImportFeedback.NotImportable("BingeCat returned no import job")
    }

    /** What one job poll says, from its `status` and stable `error` code. */
    fun jobOutcome(status: String?, error: String?): BingeCatJobOutcome {
        val code = error?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
        return when (status?.trim()?.lowercase()) {
            "succeeded" -> BingeCatJobOutcome.Finished(partial = false, detail = code)
            "partial" -> BingeCatJobOutcome.Finished(partial = true, detail = code)
            "failed" -> BingeCatJobOutcome.Failed(jobFailureReason(code))
            "cancelled" -> BingeCatJobOutcome.Failed("BingeCat cancelled the import")
            // `queued`, `running`, and any status a newer server adds: keep
            // waiting (the caller bounds the wait, so an unknown status cannot
            // spin forever).
            else -> BingeCatJobOutcome.Pending
        }
    }

    /** The contract's job failure codes, as a line a viewer can act on. */
    fun jobFailureReason(code: String?): String = when (code) {
        "list_limit_reached" ->
            "BingeCat's list limit for this account is reached, so nothing was imported"
        "interrupted" -> "BingeCat interrupted the import before it finished"
        "partial" -> "BingeCat imported only part of the list"
        "failed" -> "BingeCat could not import this list"
        else -> "BingeCat could not import this list"
    }

    /** Seconds to wait before the next poll: the job's `Retry-After`, or 5s. */
    fun pollSeconds(retryAfterHeader: String?): Long {
        val parsed = retryAfterHeader?.trim()?.toLongOrNull()
        return (parsed ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1L, 60L)
    }

    /**
     * One discovery read's failure, as a line a viewer can act on.
     *
     * The reasons are the repository's own vocabulary (`429`, `network`, `401`,
     * `403-scope`, `no-token`, `http:<code>`), so the wording lives here and the
     * repository stays free of user-facing copy.
     */
    fun failureText(reason: String): String = when {
        reason == "429" -> "BingeCat is rate-limiting this device - try again in a minute."
        reason == "network" -> "Could not reach BingeCat. Check the connection and try again."
        reason == "401" || reason == "no-token" ->
            "BingeCat refused this session. Reconnect from the BingeCat page."
        reason == "403-scope" ->
            "This connection cannot browse public lists. Reconnect to allow it."
        reason.startsWith("http:") -> "BingeCat answered ${reason.removePrefix("http:")}."
        else -> "BingeCat could not answer ($reason)."
    }

    /** One import failure, as a line a viewer can act on. */
    fun importFailureText(reason: String): String = when (reason) {
        "429" -> "BingeCat is rate-limiting imports - try again in a minute."
        "network" -> "Could not reach BingeCat. Check the connection and try again."
        "401", "no-token" ->
            "BingeCat refused this session. Reconnect from the BingeCat page."
        "403-scope" ->
            "This connection cannot import lists. Reconnect to allow it."
        "import-running" ->
            "BingeCat is already importing another list for this profile - try again " +
                "when it finishes."
        "unsupported_import_mode", "invalid_request" ->
            "BingeCat does not support that import mode for this list - pick another one."
        "empty" -> "BingeCat accepted the request but sent no answer. Try again."
        "list_limit_reached" -> jobFailureReason("list_limit_reached")
        else -> failureText(reason)
    }

    /** The category type filter's wire value (v1 does not offer one; "all"). */
    fun categoryTypeWire(): String = "all"

    /** Search is disabled on a blank query, and never sent with one. */
    fun searchEnabled(query: String): Boolean = query.trim().length >= 2

    private fun plural(value: Int): String = if (value == 1) "" else "s"
}
