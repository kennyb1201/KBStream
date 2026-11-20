package com.kennyb1201.kbstream.data.network

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.data.anime.AniListClient
import com.kennyb1201.kbstream.data.anime.kitsuReadPark
import com.kennyb1201.kbstream.data.bingecat.bingeCatReadGuard
import com.kennyb1201.kbstream.data.bingecat.bingeCatWriteGuard
import com.kennyb1201.kbstream.data.kitsu.KitsuLibrary
import com.kennyb1201.kbstream.data.mal.malRateGuard
import com.kennyb1201.kbstream.data.trakt.TraktRepository

/**
 * Installs the park that survives process death for every rate-limited leg but
 * Simkl (which has had its own since 2026-11-19).
 *
 * Called once at app start. One prefs file, one key per leg: the guards are
 * deliberately separate per service and per read/write leg, and the KEYS are
 * separate for the same reason - a park on BingeCat's read bucket must never be
 * written over by a Trakt write, which is what a shared key would do.
 *
 * A SharedPreferences read is cheap and the only write is one per 429.
 */
internal fun installRateLimitParkStores(context: Context) {
    val prefs = context.applicationContext
        .getSharedPreferences(RATE_LIMIT_PARK_PREFS, Context.MODE_PRIVATE)

    bingeCatReadGuard.attachParkStore(prefsParkUntilStore(prefs, KEY_BINGECAT_READ))
    bingeCatWriteGuard.attachParkStore(prefsParkUntilStore(prefs, KEY_BINGECAT_WRITE))
    kitsuReadPark.attachParkStore(prefsParkUntilStore(prefs, KEY_KITSU_READ))
    KitsuLibrary.attachParkStore(prefsParkUntilStore(prefs, KEY_KITSU_WRITE))
    AniListClient.attachParkStore(prefsParkUntilStore(prefs, KEY_ANILIST))
    malRateGuard.attachParkStore(prefsParkUntilStore(prefs, KEY_MAL))
    TraktRepository.getInstance(context).guard
        .attachParkStore(prefsParkUntilStore(prefs, KEY_TRAKT))
}

/** One precedence key as a [ParkUntilStore]. */
internal fun prefsParkUntilStore(prefs: SharedPreferences, key: String): ParkUntilStore =
    object : ParkUntilStore {
        override fun readParkUntilMs(): Long = prefs.getLong(key, 0L)

        // apply(), not commit(): a 429 lands on a loader thread and nothing waits
        // on this value - the next read is the next process's.
        override fun writeParkUntilMs(untilMs: Long) {
            prefs.edit().putLong(key, untilMs).apply()
        }
    }

internal const val RATE_LIMIT_PARK_PREFS = "kbstream_rate_limit_parks"

private const val KEY_BINGECAT_READ = "bingecat_read_until"
private const val KEY_BINGECAT_WRITE = "bingecat_write_until"
private const val KEY_KITSU_READ = "kitsu_read_until"
private const val KEY_KITSU_WRITE = "kitsu_write_until"
private const val KEY_ANILIST = "anilist_until"
private const val KEY_MAL = "mal_until"
private const val KEY_TRAKT = "trakt_until"
