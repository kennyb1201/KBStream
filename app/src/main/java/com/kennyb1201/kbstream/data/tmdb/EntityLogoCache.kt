package com.kennyb1201.kbstream.data.tmdb

/**
 * The header entity-logo cache's key, TTL and freshness rules.
 *
 * The Browse headers draw a network / studio / service brand mark
 * (`TmdbRepository.getEntityLogoUrls`), and that resolution walks up to five
 * fallback steps, every one of them a network call: the entity's own logos, the
 * brand's other entity, the id-space twin, the watch-provider mark, and a
 * company-name search. It had no cache anywhere in its path, so a screen
 * re-entry paid for the whole walk again - the reported "the logos in Browse are
 * slow to fill in" (field 2026-10-28: the HEADER marks, not the cards' corner
 * clearlogos, which have their own artwork path).
 *
 * The rules live here, free of Android types, so the keying and the two TTLs
 * are pinnable from a JVM test (see `EntityLogoCacheTest`) without standing up a
 * repository. [TmdbRepository] owns the memory memo, the disk rows and the
 * in-flight dedup.
 */

/** The disk-key prefix; bumping it drops every standing entry at once. */
internal const val ENTITY_LOGO_DISK_PREFIX = "entity_logos_v1:"

/**
 * How long a resolution that FOUND artwork is trusted, in memory (12h) and on
 * disk (30 days) - the hero artwork cache's pair, for the same reason: a brand's
 * mark changes about as often as TMDB gains an entry.
 */
internal const val ENTITY_LOGO_MEMORY_TTL_MS = 12L * 60L * 60L * 1000L
internal const val ENTITY_LOGO_DISK_TTL_MS = 30L * 24L * 60L * 60L * 1000L

/**
 * How long a PROVEN logo-less brand is remembered: 24 hours.
 *
 * Long enough that a brand TMDB holds no artwork for does not re-walk five
 * fallbacks on every screen load, short enough that artwork uploaded next week
 * appears without a reinstall (the MobLand lesson recorded in
 * `TmdbHeroArtworkRepository`).
 */
internal const val ENTITY_LOGO_MISS_TTL_MS = 24L * 60L * 60L * 1000L

/** Session memo bound, matching the hero cache's. */
internal const val ENTITY_LOGO_MEMORY_MAX_ENTRIES = 128

/**
 * The cache key for one resolution.
 *
 * Every argument that can change the answer is in it: a different provider or
 * originals-company id, a different name, and - crucially - the two id spaces,
 * because network 41 is TNT and company 41 is Orion Pictures. The number alone
 * proves no relation, which is the same trap `getEntityLogoUrls`' twin step
 * guards against, so the `isNetwork` bit is spelled into the key rather than
 * folded away.
 *
 * The name is lowercased and trimmed (brand names reach here from a route label
 * or a chip), and an absent or blank name is a single `-` spelling: a caller
 * that supplies no name lets the repository read the entity's own, which is one
 * resolution either way.
 */
internal fun entityLogoCacheKey(
    entityId: Int,
    isNetwork: Boolean,
    providerId: Int?,
    originalsCompanyId: Int?,
    name: String?
): String {
    val normalizedName = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "-"
    return ENTITY_LOGO_DISK_PREFIX +
        "$entityId:" +
        (if (isNetwork) "network" else "company") + ":" +
        (providerId?.toString() ?: "-") + ":" +
        (originalsCompanyId?.toString() ?: "-") + ":" +
        normalizedName
}

/** The disk TTL for an answer: a month with artwork, a day without. */
internal fun entityLogoDiskTtlMs(urls: List<String>): Long =
    if (urls.isEmpty()) ENTITY_LOGO_MISS_TTL_MS else ENTITY_LOGO_DISK_TTL_MS

/**
 * Whether a disk entry of [ageMs] may answer, given what it holds.
 *
 * An empty list is a real answer - TMDB resolved every step and had nothing -
 * but a weaker one than artwork, so it expires a day later instead of a month.
 */
internal fun entityLogoDiskEntryIsFresh(ageMs: Long, urls: List<String>): Boolean =
    ageMs >= 0 && ageMs < entityLogoDiskTtlMs(urls)

/** Whether a memory entry of [ageMs] may answer. Flat: the memo holds either answer. */
internal fun entityLogoMemoryEntryIsFresh(ageMs: Long): Boolean =
    ageMs >= 0 && ageMs < ENTITY_LOGO_MEMORY_TTL_MS
