package com.kennyb1201.kbstream.data.iptv

import android.content.SharedPreferences

/**
 * When each configured guide source last LANDED an import — one map, one key,
 * written by everyone who imports.
 *
 * Landed, not merely completed: a `304`, a refused empty feed and a zero-program
 * promote all return without an exception and store nothing readable, so none of
 * them stamps (see [GuideImportOutcome.landed]). Treating them as imports is
 * what marked a blank guide fresh for 12h at a time and left it blank until a
 * manual reimport.
 *
 * Both import paths stamp here: the background refresh worker (which owns the
 * pass's global freshness marker) and the in-app import (which publishes what
 * it stored to the setup screen). It used to be the worker's private business,
 * which meant an in-app import left Playlist Setup unable to say when a source
 * had last succeeded — the very line that makes a guide going stale visible
 * where the viewer goes to press IMPORT GUIDE.
 *
 * The format is the worker's original `"<epochMs>|<url>"` lines, unchanged, so
 * an install that already carries stamps keeps reading them; a URL is a whole
 * line's tail, so a query string containing '|' stays intact.
 *
 * The GLOBAL marker (`epg_updated_at`) is deliberately not written here: it is
 * the OLDEST stamp among the configured sources, and only the worker derives it
 * (see `EpgRefreshWorker.stampImportedSource`). A source recorded here does
 * count towards that derivation, which is right — it really did import.
 */
internal object GuideSourceStamps {

    /** The prefs key, spelled exactly as the worker has always written it. */
    const val KEY = "epg_source_stamps"

    /** `url -> last successful import`, from the persisted lines. */
    fun read(prefs: SharedPreferences): Map<String, Long> =
        prefs.getString(KEY, "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val at = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                val key = line.substringAfter('|', "")
                if (key.isBlank()) null else key to at
            }
            .toMap()

    /**
     * Records one source's success and returns the updated map.
     *
     * The caller gets the map back because the worker needs it to re-derive the
     * global marker in the same pass; the in-app import ignores the result.
     */
    fun recordSuccess(
        prefs: SharedPreferences,
        url: String,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, Long> {
        val key = url.trim()
        if (key.isEmpty()) return read(prefs)
        val stamps = read(prefs) + (key to nowMs)
        write(prefs, stamps)
        return stamps
    }

    /** The oldest stamp among [urls], with a never-imported source counting as 0. */
    fun oldest(stamps: Map<String, Long>, urls: List<String>): Long =
        urls.minOfOrNull { stamps[it] ?: 0L } ?: 0L

    private fun write(prefs: SharedPreferences, stamps: Map<String, Long>) {
        prefs.edit()
            .putString(
                KEY,
                stamps.entries.joinToString("\n") { (key, at) -> "$at|$key" }
            )
            .apply()
    }
}
