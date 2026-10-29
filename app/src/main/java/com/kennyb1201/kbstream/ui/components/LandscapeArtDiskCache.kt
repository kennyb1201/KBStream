package com.kennyb1201.kbstream.ui.components

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * The landscape-card memo's disk key, TTL, freshness rules and codec.
 *
 * `GlobalLandscapeArtCache` (see `GlobalLandscapeArt.kt`) used to be session-only,
 * so the first browse visit after a cold start resolved every card's TMDB art
 * again - one enriched-metadata call, parse and merge per card, all behind a
 * six-permit semaphore. Revisits in the same session were fast because the
 * memo answered; a cold start paid the whole queue again (field 2026-10-29).
 *
 * What is stored is the PRE-MERGE TMDB pair (`tmdbArt`), never the merged entry:
 * the add-on backdrop fallback and the `tmdbOnly` rule are merge-time inputs
 * (`landscapeArtEntry`), so a changed add-on background or flag can never be
 * served stale from disk. The disk key therefore carries neither of them - one
 * row serves every surface showing the title.
 *
 * The rules live here, free of Android types, so the key, the two TTLs and the
 * codec are pinnable from a JVM test (`LandscapeArtDiskCacheTest`) without
 * standing up a repository. `TmdbRepository` owns the disk table itself (the
 * shared JSON cache and its byte budget); `GlobalLandscapeArtCache` owns when
 * the layer is consulted and what is merged from it.
 */

/**
 * The disk-key prefix; bumping it drops every standing card entry at once.
 *
 * New generation: no row with this prefix has ever been written, so there is
 * nothing to migrate.
 */
internal const val LANDSCAPE_ART_DISK_PREFIX = "landscape_art_v1:"

/**
 * How long an entry WITH artwork is trusted: 30 days, the hero artwork cache's
 * span and for the same reason - a title's alternate backdrop or wordmark
 * changes about as often as TMDB gains an entry.
 */
internal const val LANDSCAPE_ART_DISK_TTL_MS = 30L * 24L * 60L * 60L * 1000L

/**
 * How long a both-null answer is trusted: 24 hours, the entity-logo pair's
 * negative TTL.
 *
 * "TMDB holds no alternate backdrop and no English wordmark" is a real answer -
 * the card must not re-ask on every visit - but a weaker one than artwork, so
 * it expires a day later and artwork uploaded in the meantime appears without a
 * reinstall.
 */
internal const val LANDSCAPE_ART_MISS_TTL_MS = 24L * 60L * 60L * 1000L

/**
 * One card's PRE-MERGE TMDB pair, as it sits on disk.
 *
 * Both halves are nullable and both absences are meaningful: a title TMDB has no
 * alternate backdrop for still has a wordmark, and the reverse. A both-null
 * entry is a real answer under the miss TTL, and it still merges to the
 * request's add-on fallback at read time.
 *
 * Defaults so a row missing a key (an older writer, a hand-edited row) decodes
 * to null rather than throwing - a miss, not a crash.
 */
@JsonClass(generateAdapter = true)
internal data class LandscapeArtDiskEntry(
    val backdrop: String? = null,
    val logo: String? = null
) {

    /** Whether TMDB answered nothing at all - the answer that takes the miss TTL. */
    val isEmpty: Boolean get() = backdrop == null && logo == null

    /** The pair [landscapeArtEntry] merges from. */
    fun toPair(): Pair<String?, String?> = backdrop to logo

    companion object {
        /** The row for one resolve's TMDB answer. */
        fun of(tmdbArt: Pair<String?, String?>): LandscapeArtDiskEntry =
            LandscapeArtDiskEntry(backdrop = tmdbArt.first, logo = tmdbArt.second)
    }
}

/**
 * The disk row key for the card filed under [key] (`landscapeArtKey`).
 *
 * The prefix is versioned and separate from every other cache generation, so a
 * bad generation of card art can be dropped without touching a title's detail
 * row or a brand's logo row.
 */
internal fun landscapeArtDiskKey(key: String): String = LANDSCAPE_ART_DISK_PREFIX + key

/** The disk TTL for an answer: a month with artwork, a day without. */
internal fun landscapeArtDiskTtlMs(entry: LandscapeArtDiskEntry): Long =
    if (entry.isEmpty) LANDSCAPE_ART_MISS_TTL_MS else LANDSCAPE_ART_DISK_TTL_MS

/**
 * Whether a disk entry of [ageMs] may answer.
 *
 * A negative age is a clock that moved backwards, which is not evidence of
 * freshness - the same rule as the entity-logo and hero caches.
 */
internal fun landscapeArtDiskEntryIsFresh(ageMs: Long, entry: LandscapeArtDiskEntry): Boolean =
    ageMs >= 0 && ageMs < landscapeArtDiskTtlMs(entry)

/**
 * The one Moshi adapter this codec needs.
 *
 * The model is annotated, so KSP emits `LandscapeArtDiskEntryJsonAdapter` and
 * Moshi resolves it by name - no reflective factory, which is what
 * `MoshiCodegenTest` pins for the rest of the app. `app/proguard-rules.pro`
 * keeps `**JsonAdapter` and the annotated class's name, so the release build
 * resolves it too.
 */
private val landscapeArtDiskAdapter: JsonAdapter<LandscapeArtDiskEntry> by lazy {
    Moshi.Builder().build().adapter(LandscapeArtDiskEntry::class.java)
}

/** The row's JSON. Always writes both keys, so a null half round-trips as null. */
internal fun encodeLandscapeArtDiskEntry(entry: LandscapeArtDiskEntry): String =
    landscapeArtDiskAdapter.toJson(entry)

/**
 * The entry in [json], or null when the row cannot answer.
 *
 * A corrupt row (truncated write, a hand-edited value, a generation that no
 * longer parses) reads as a miss: the caller re-fetches and overwrites it. It
 * must never be a crash - this is a cache, and the answer behind it is always
 * obtainable.
 */
internal fun decodeLandscapeArtDiskEntry(json: String): LandscapeArtDiskEntry? =
    runCatching { landscapeArtDiskAdapter.fromJson(json) }.getOrNull()
