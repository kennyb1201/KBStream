package com.kennyb1201.kbstream.di

import android.content.Context
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.airdates.TvmazeAirDateRepository
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.fanart.FanartArtRepository
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import com.kennyb1201.kbstream.data.kb.KBRepository
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.data.youtube.KBStreamDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The dependency graph's first job: hand out the instances the legacy
 * `getInstance` accessors already own.
 *
 * Spec 2026-11-22, item 3. The migration is a strangler, and its invariant is
 * that there is exactly ONE instance of each of these in the process at every
 * point of it. So the graph is introduced first, delegating to the accessors -
 * a class that moves onto `@Inject` tomorrow receives the same object the
 * static call site receives today, which is what makes the move reviewable
 * class-by-class instead of in one leap.
 *
 * The companion objects are deleted LAST, once nothing calls them: until then
 * they remain the single owner, and these providers are a view of them.
 *
 * ## The first two companions are gone
 *
 * [KBRepository] and [TvmazeAirDateRepository] reached the point where the only
 * caller of their accessor was this module - every screen had already moved to a
 * constructor parameter - so their `getInstance` companions are deleted and the
 * providers construct them directly. Their `@Singleton` scope carries the
 * one-instance guarantee the companion used to: one HTTP cache, one memory map,
 * one failure backoff. Every other provider below still delegates.
 *
 * ## The two exceptions, and why they are not `@Singleton`
 *
 * [WatchHistoryDatabase] and [IptvDatabase] are profile-scoped in a way a
 * singleton cannot express: `getInstance` resolves the ACTIVE profile's file at
 * call time, so the instance is expected to differ across a profile switch. A
 * `@Singleton` provider would freeze whichever profile was active at the first
 * injection and serve its database to the next one - the exact class of bug a
 * profile switch is supposed to be unable to cause. They are unscoped here so
 * every resolution still asks the accessor, and their migration (a resolver or
 * a per-profile component) is deliberately left for its own phase.
 *
 * ## The last class in, and why it needed a visibility change
 *
 * `FanartArtRepository` was `internal`, so it could not be a provider's return
 * type ("'public' function exposes its 'internal' return type") and could not be
 * a `@HiltViewModel` parameter either. Home held the migration's last legacy
 * accessor call in order to reach it. The class is public now - it is the graph
 * that names it, not the app doing so casually - and this module delegates to
 * its accessor like all the others, which is what emptied the migration's list of
 * allowed accessor calls.
 */
@Module
@InstallIn(SingletonComponent::class)
object LegacySingletonsModule {

    @Provides
    @Singleton
    fun tmdbRepository(@ApplicationContext context: Context): TmdbRepository =
        TmdbRepository.getInstance(context)

    @Provides
    @Singleton
    fun simklRepository(@ApplicationContext context: Context): SimklRepository =
        SimklRepository.getInstance(context)

    @Provides
    @Singleton
    fun traktRepository(@ApplicationContext context: Context): TraktRepository =
        TraktRepository.getInstance(context)

    @Provides
    @Singleton
    fun bingecatRepository(@ApplicationContext context: Context): BingeCatRepository =
        BingeCatRepository.getInstance(context)

    @Provides
    @Singleton
    fun addonManager(@ApplicationContext context: Context): AddonManager =
        AddonManager.getInstance(context)

    @Provides
    @Singleton
    fun addonRepository(): AddonRepository = AddonRepository.getInstance()

    @Provides
    @Singleton
    fun kbRepository(@ApplicationContext context: Context): KBRepository =
        KBRepository(context)

    @Provides
    @Singleton
    fun tvmazeAirDateRepository(@ApplicationContext context: Context): TvmazeAirDateRepository =
        TvmazeAirDateRepository(context)

    @Provides
    @Singleton
    fun kbStreamDownloader(): KBStreamDownloader = KBStreamDownloader.getInstance()

    @Provides
    @Singleton
    fun fanartArtRepository(@ApplicationContext context: Context): FanartArtRepository =
        FanartArtRepository.getInstance(context)

    /** Unscoped on purpose: see the class comment. */
    @Provides
    fun watchHistoryDatabase(@ApplicationContext context: Context): WatchHistoryDatabase =
        WatchHistoryDatabase.getInstance(context)

    /** Unscoped on purpose: see the class comment. */
    @Provides
    fun iptvDatabase(@ApplicationContext context: Context): IptvDatabase =
        IptvDatabase.getInstance(context)
}
