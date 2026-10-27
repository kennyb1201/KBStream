package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.db.retryOnDatabaseSwap
import com.kennyb1201.kbstream.data.db.DB_SWAP_RETRY_LONG_ATTEMPTS
import com.kennyb1201.kbstream.data.db.isTransientDatabaseError
import com.kennyb1201.kbstream.data.db.withDatabaseSwapRetry
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.data.iptv.PlaylistSources
import com.kennyb1201.kbstream.data.iptv.EpgImportWatchdog
import com.kennyb1201.kbstream.data.iptv.EpgStalledException
import com.kennyb1201.kbstream.data.iptv.EpgWindow
import com.kennyb1201.kbstream.data.iptv.FreeLineup
import com.kennyb1201.kbstream.data.iptv.XumoGuide
import com.kennyb1201.kbstream.data.iptv.GuideImportOutcome
import com.kennyb1201.kbstream.data.iptv.GuideImportProgress
import com.kennyb1201.kbstream.data.iptv.GuideRefreshPolicy
import com.kennyb1201.kbstream.data.iptv.GuideRefreshStatus
import com.kennyb1201.kbstream.data.iptv.GuideSourceStamps
import com.kennyb1201.kbstream.data.iptv.epgEntryImportCoolingDown
import com.kennyb1201.kbstream.data.iptv.GuideImportState
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import com.kennyb1201.kbstream.data.iptv.guideWindowFingerprint
import com.kennyb1201.kbstream.data.reporting.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kennyb1201.kbstream.data.runCatchingCancellable

class IptvViewModel(private val app: Application) : AndroidViewModel(app) {

    // Shared with the player: the in-player guide resolves matches against the
    // same memoized guide snapshot this screen reads, instead of building a
    // second copy of the whole guide (see IptvRepository.shared).
    private val repository = IptvRepository.shared(app)
    private val prefs
        get() = app.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                app, PREFS_NAME
            ),
            Context.MODE_PRIVATE
        )

    private val _playlistUrl = MutableStateFlow(prefs.getString(KEY_PLAYLIST_URL, "").orEmpty())
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val _epgUrl = MutableStateFlow(prefs.getString(KEY_EPG_URL, "").orEmpty())
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    /**
     * Secondary EPG sources (newline-separated URLs) merged with the
     * primary guide. Channels missing from one source can still match the
     * other; matching keys off each source's own URL in the cache tables,
     * so any number of sources coexist without clobbering each other.
     */
    private val _extraEpgUrls = MutableStateFlow(
        prefs.getString(KEY_EXTRA_EPG_URLS, "").orEmpty()
    )
    val extraEpgUrls: StateFlow<String> = _extraEpgUrls.asStateFlow()

    /** Primary + extras, in order, trimmed, no blanks. */
    private fun allEpgUrls(): List<String> =
        (_epgUrl.value.trim().takeIf(String::isNotEmpty)?.let(::listOf).orEmpty() +
            extraEpgUrlList() +
            adapterGuideUrls()).distinct()

    /**
     * The installed guide ADAPTERS, always LAST in source order.
     *
     * Order is load-bearing and unchanged by this: guide sources are read in
     * order and each channel keeps the FIRST source that resolves it, so Xumo
     * after kanya/locals/Pluto/Samsung/Plex/Roku can only ADD channels none of
     * them answer. Xumo's channels are platform-distinct, so going last leaves
     * every existing source's precedence exactly as it was.
     *
     * These are not lines in [extraEpgUrls]: the adapter is a managed installed
     * source, so it appears here by flag rather than by text. That is also why
     * this list - not the field - is what Playlist Setup, the freshness stamps
     * and the diagnostics all read: a key present here is reported per source
     * everywhere those already work.
     */
    private fun adapterGuideUrls(): List<String> =
        if (prefs.getBoolean(KEY_FREE_TV_XUMO_EPG_APPLIED, false)) {
            listOf(XumoGuide.SOURCE_KEY)
        } else {
            emptyList()
        }

    private val _playlistName = MutableStateFlow(prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty())
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    /**
     * Additional M3U sources (newline-separated URLs) merged into the
     * lineup alongside the primary playlist. Each entry may also carry a
     * "|name" suffix ("<url>|<display name>"). EPG matching for merged
     * channels keys off each playlist's own URL in the cache tables.
     */
    private val _extraPlaylistUrls = MutableStateFlow(
        prefs.getString(KEY_EXTRA_PLAYLIST_URLS, "").orEmpty()
    )
    val extraPlaylistUrls: StateFlow<String> = _extraPlaylistUrls.asStateFlow()

    // ── Guide-wide program search ─────────────────────────────────
    // Debounced: typing on a TV remote fires a keystroke per D-pad press, and
    // each one would otherwise run a LIKE scan over the whole EPG table.
    private val _programSearchResults = MutableStateFlow<List<EpgProgramRow>>(emptyList())
    val programSearchResults: StateFlow<List<EpgProgramRow>> =
        _programSearchResults.asStateFlow()
    private var programSearchJob: Job? = null

    fun searchPrograms(query: String) {
        programSearchJob?.cancel()
        if (query.trim().length < 2) {
            _programSearchResults.value = emptyList()
            return
        }
        programSearchJob = viewModelScope.launch {
            delay(250)
            val hits = runCatchingCancellable { repository.searchPrograms(query) }.getOrDefault(emptyList())
            _programSearchResults.value = hits
        }
    }

    fun onExtraPlaylistUrlsChanged(value: String) {
        _extraPlaylistUrls.value = value
        saveInputs()
    }

    // ── The free lineup (see FreeLineup) ──────────────────────────────────
    //
    // Installed INTO the extra-source slots, never the primary playlist slot: a
    // profile that has its own playlist is byte-for-byte unaffected, and the
    // free channels merge with namespaced ids exactly as any second provider
    // does. Per-profile by construction - these are the profile-scoped prefs,
    // so installing under one profile touches no other.

    /** True while a free-lineup install is fetching and applying. */
    private val _freeLineupInstalling = MutableStateFlow(false)
    val freeLineupInstalling: StateFlow<Boolean> = _freeLineupInstalling.asStateFlow()

    /**
     * Set when the free sources could not be read at install time.
     *
     * The install STAYS - it is removable and retryable - but nothing is cached
     * as a lineup, so the screens keep their no-lineup state and say the FREE
     * LINEUP failed to load instead of pretending the profile has channels it
     * cannot show.
     */
    private val _freeLineupFailed = MutableStateFlow(false)
    val freeLineupFailed: StateFlow<Boolean> = _freeLineupFailed.asStateFlow()

    /** Whether the built-in lineup is among this profile's sources. */
    val freeLineupInstalled: StateFlow<Boolean> = _extraPlaylistUrls
        .map { FreeLineup.isInstalled(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FreeLineup.isInstalled(_extraPlaylistUrls.value)
        )

    private var installJob: Job? = null

    /**
     * Installs the free lineup and loads it - one tap, one prefs edit.
     *
     * Idempotent, and deliberately re-entrant: pressed on an already-installed
     * lineup it retries the load, which is the state a failed install leaves
     * behind - so the same offer reads as both "watch free channels" and "try
     * again".
     *
     * [onReady] runs when the attempt is over, success or not: the Sports Hub
     * re-reads the lineup from that callback rather than guessing how long a
     * multi-thousand-channel fetch takes.
     */
    fun installFreeLineup(onReady: () -> Unit = {}) {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
            _freeLineupInstalling.value = true
            // A tap that wrote a guide line must import it: the opt-in is a
            // source-list edit PLUS the import behind it (§3.4), or the new
            // feeds' channels stay blank until somebody presses IMPORT GUIDE.
            var guideLineWritten = false
            try {
                if (!FreeLineup.isInstalled(_extraPlaylistUrls.value)) {
                    val guidesBefore = _extraEpgUrls.value
                    val (playlists, guides) = FreeLineup.install(
                        extraPlaylistUrls = _extraPlaylistUrls.value,
                        extraEpgUrls = _extraEpgUrls.value
                    )
                    guideLineWritten = guides != guidesBefore
                    writeSources(playlists, guides)
                    // This install wrote every guide line (kanya, locals, then
                    // the four FAST platforms), so the one-time migrations have
                    // nothing to add for this profile and must never resurrect a
                    // line the viewer deletes later. Marked only in this branch:
                    // a tap on an ALREADY-installed lineup is a reload, not a
                    // re-install.
                    markFreeLineupLocalsEpgApplied()
                    markFreeLineupFastEpgApplied()
                }
                // An in-flight import is not a failure for the install either:
                // the load below reads the live guide and the pass refreshes the
                // lineup when it lands, so the install stays and the next load is
                // not a retry of anything.
                _freeLineupFailed.value =
                    loadLineup(forceGuideRefresh = guideLineWritten) == LoadOutcome.FAILED
                // The load this install just ran wrote the filtered cache, so
                // the one-time migration has nothing to do for this profile.
                if (!_freeLineupFailed.value) markFreeLineupUsFilterApplied()
            } finally {
                _freeLineupInstalling.value = false
                withContext(NonCancellable) {
                    Log.i(
                        TAG,
                        "FREE LINEUP install finished failed=${_freeLineupFailed.value}"
                    )
                    onReady()
                }
            }
        }
    }

    /**
     * Removes both halves of the built-in lineup. Everything else the viewer
     * configured is kept, and with nothing left to load the profile is back to
     * its exact no-playlist state.
     */
    fun removeFreeLineup() {
        val (playlists, guides) = FreeLineup.remove(
            extraPlaylistUrls = _extraPlaylistUrls.value,
            extraEpgUrls = _extraEpgUrls.value
        )
        if (playlists == _extraPlaylistUrls.value && guides == _extraEpgUrls.value) return
        writeSources(playlists, guides)
        _freeLineupFailed.value = false
        forgetPlaylistRows(FreeLineup.PLAYLIST_URL)
        forgetXumoGuideAdapter()
        afterSourceRemoval()
    }

    /**
     * Takes the Xumo adapter out with the lineup it exists for.
     *
     * [FreeLineup.remove] cannot do this by deleting text, because there is no
     * line to delete - so removal is the flag plus the rows. Clearing the flag
     * is what makes a later re-install re-apply the adapter: nothing about the
     * removal is remembered beyond the fact that it happened.
     */
    private fun forgetXumoGuideAdapter() {
        if (!prefs.getBoolean(KEY_FREE_TV_XUMO_EPG_APPLIED, false)) return
        prefs.edit().putBoolean(KEY_FREE_TV_XUMO_EPG_APPLIED, false).apply()
        viewModelScope.launch {
            runCatchingCancellable { repository.forgetGuideSource(XumoGuide.SOURCE_KEY) }
            _guideRefreshTick.value += 1
        }
        Log.i(TAG, "FREE LINEUP XUMO EPG adapter removed with the lineup")
    }

    /**
     * Swaps a free-lineup install's dead guide address for the living one.
     *
     * Earlier builds installed a guide at an iptv-org path that no longer
     * exists (every guide path on that host is a 404), which is what put
     * "Guide download failed: HTTP 404" above the whole guide. The address the
     * playlist itself declares now works, and this is where an install from an
     * older build is brought forward: on the same read that derives
     * [freeLineupInstalled], one write replaces that line.
     *
     * Idempotent, and deliberately narrow - it only fires when the free
     * playlist entry is installed, so a legacy line standing alone (which may be
     * the viewer's own manual entry) is left exactly as typed. Afterwards the
     * ordinary guide machinery fetches the living guide on its own.
     */
    private fun repairFreeLineupGuideEntry() {
        val repaired = FreeLineup.repairEpgEntry(
            extraPlaylistUrls = _extraPlaylistUrls.value,
            extraEpgUrls = _extraEpgUrls.value
        ) ?: return
        _extraEpgUrls.value = repaired
        saveInputs()
        Log.i(TAG, "FREE LINEUP guide source upgraded to the current address")
    }

    /**
     * The one-time reload an install from before the US filter needs.
     *
     * The filter is a LOAD rule (see [IptvRepository.loadPlaylist]): it rewrites
     * the cache as the playlist is read. An app update alone therefore purges
     * nothing - the foreign rows an older build cached are still there, and the
     * cached restore reads them straight back. On the first entry into a profile
     * that has the built-in lineup installed, this reloads that one playlist
     * through the ordinary path, whose cache write re-applies the filter (behind
     * [com.kennyb1201.kbstream.data.iptv.EpgWriteGate], so it waits out playback
     * like every other write), and then remembers that it did.
     *
     * A reload that fails leaves the flag UNSET: the next entry tries again, and
     * the old rows stay until one lands - the honest state, rather than a claim
     * that the update cleaned something it did not.
     */
    private suspend fun migrateFreeLineupUsFilter() {
        if (prefs.getBoolean(KEY_FREE_TV_US_FILTER_APPLIED, false)) return
        if (!FreeLineup.isInstalled(_extraPlaylistUrls.value)) return
        // A guide import owns the database's write window; the same reason
        // refreshPlaylistInBackground skips itself. The flag stays unset, so
        // the next entry (after the import) runs this then.
        if (_isImportingGuide.value) return
        Log.i(TAG, "FREE LINEUP US FILTER migration: reloading the installed lineup once")
        val reloaded = runCatchingCancellable {
            loadPlaylistResilient(FreeLineup.PLAYLIST_URL, FreeLineup.NAME)
        }.getOrNull()
        if (reloaded == null) {
            Log.w(
                TAG,
                "FREE LINEUP US FILTER migration did not land; retrying on the next entry"
            )
            return
        }
        markFreeLineupUsFilterApplied()
        Log.i(TAG, "FREE LINEUP US FILTER migration done channels=${reloaded.channels.size}")
    }

    /** Records that this profile's cache has been through the US filter. */
    private fun markFreeLineupUsFilterApplied() {
        prefs.edit().putBoolean(KEY_FREE_TV_US_FILTER_APPLIED, true).apply()
    }

    /**
     * The one-time locals guide migration for installs that predate it.
     *
     * The built-in lineup's own guide (kanya) answers the national feeds and
     * nothing for a local affiliate, so an install from before
     * [FreeLineup.LOCALS_EPG_URL] existed shows every local as "No program
     * data" unless the viewer pastes a second guide by hand - which is exactly
     * what the field report had to do. On the first entry into such a profile
     * the locals line is appended where the field already points, and the
     * profile remembers it did.
     *
     * [FreeLineup.localsGuideDecision] owns the rules; this applies them. Two
     * guards live here because they are this screen's state: a guide import owns
     * the fetch/write window (the flag stays UNSET so the next entry retries),
     * and an already-applied profile is never touched - so a viewer who deletes
     * the line keeps it deleted, and the only way back is a fresh install.
     *
     * Nothing is imported HERE. A line that was actually written is reported
     * back to the restore, which starts the import the new list needs (§3.4) -
     * otherwise the locals fill only on the next IMPORT GUIDE, which is exactly
     * the "added the line, still blank" gap this migration used to leave.
     *
     * @return true when a NEW guide line was written into the field.
     */
    private fun migrateFreeLineupLocalsEpg(): Boolean {
        val applied = prefs.getBoolean(KEY_FREE_TV_LOCALS_EPG_APPLIED, false)
        if (applied) return false
        if (_isImportingGuide.value) return false
        when (
            val decision = FreeLineup.localsGuideDecision(
                extraPlaylistUrls = _extraPlaylistUrls.value,
                extraEpgUrls = _extraEpgUrls.value,
                alreadyApplied = applied
            )
        ) {
            FreeLineup.LocalsGuideDecision.Skip -> Unit
            FreeLineup.LocalsGuideDecision.RecordOnly -> {
                // Pasted by hand already: record it, do not duplicate or move it.
                // Nothing new to import either - the line was already there.
                markFreeLineupLocalsEpgApplied()
            }
            is FreeLineup.LocalsGuideDecision.Append -> {
                _extraEpgUrls.value = decision.extraEpgUrls
                saveInputs()
                markFreeLineupLocalsEpgApplied()
                Log.i(TAG, "FREE LINEUP LOCALS EPG migration: locals guide source added")
                return true
            }
        }
        return false
    }

    /** Records that this profile has been offered the locals guide. */
    private fun markFreeLineupLocalsEpgApplied() {
        prefs.edit().putBoolean(KEY_FREE_TV_LOCALS_EPG_APPLIED, true).apply()
    }

    /**
     * The one-time FAST-platform-guides migration for installs that predate it.
     *
     * kanya is a broadcast/cable guide and the locals file carries affiliates,
     * so neither answers a FAST channel - after both, the blanks left on the
     * field device were the Pluto / Samsung / Plex / Roku blocks, which is
     * precisely what four platform feeds fill. On the first entry into an
     * install that predates them, the missing lines are appended where the field
     * already points, and the profile remembers it did.
     *
     * [FreeLineup.fastGuideDecision] owns the rules; this applies them, with the
     * same two guards the locals migration has and for the same reasons: a guide
     * import owns the fetch/write window (the flag stays UNSET so the next entry
     * retries), and an already-applied profile is never touched - so a viewer who
     * deletes a FAST line keeps it deleted, and the only way back is a fresh
     * install.
     *
     * Nothing is imported HERE: a write is reported back to the restore, which
     * starts the import the new list needs (§3.4) instead of leaving the FAST
     * channels on "added the line, still blank until import".
     *
     * @return true when at least one NEW guide line was written into the field.
     */
    private fun migrateFreeLineupFastEpg(): Boolean {
        val applied = prefs.getBoolean(KEY_FREE_TV_FAST_EPG_APPLIED, false)
        if (applied) return false
        if (_isImportingGuide.value) return false
        when (
            val decision = FreeLineup.fastGuideDecision(
                extraPlaylistUrls = _extraPlaylistUrls.value,
                extraEpgUrls = _extraEpgUrls.value,
                alreadyApplied = applied
            )
        ) {
            FreeLineup.FastGuideDecision.Skip -> Unit
            FreeLineup.FastGuideDecision.RecordOnly -> {
                // Pasted by hand already: record it, do not duplicate or move it.
                markFreeLineupFastEpgApplied()
            }
            is FreeLineup.FastGuideDecision.Append -> {
                _extraEpgUrls.value = decision.extraEpgUrls
                saveInputs()
                markFreeLineupFastEpgApplied()
                Log.i(TAG, "FREE LINEUP FAST EPG migration: platform guide sources added")
                return true
            }
        }
        return false
    }

    /** Records that this profile has been offered the FAST platform guides. */
    private fun markFreeLineupFastEpgApplied() {
        prefs.edit().putBoolean(KEY_FREE_TV_FAST_EPG_APPLIED, true).apply()
    }

    /**
     * The one-time Xumo guide-adapter migration, on the FAST migration's trigger.
     *
     * Same entry point and same guard as its neighbours: a guide import owns the
     * fetch/write window, so the flag stays UNSET and the next entry retries.
     * Nothing is written into the Extra EPG field - the adapter has no line -
     * so "applying" is only the per-profile flag plus the single-source import
     * the caller starts.
     *
     * @return true when the adapter was registered by THIS call.
     */
    private fun migrateFreeLineupXumoEpg(): Boolean {
        val applied = prefs.getBoolean(KEY_FREE_TV_XUMO_EPG_APPLIED, false)
        if (_isImportingGuide.value) return false
        if (!FreeLineup.shouldInstallXumoGuide(
                extraPlaylistUrls = _extraPlaylistUrls.value,
                alreadyApplied = applied
            )
        ) {
            return false
        }
        prefs.edit().putBoolean(KEY_FREE_TV_XUMO_EPG_APPLIED, true).apply()
        Log.i(TAG, "FREE LINEUP XUMO EPG migration: guide adapter registered")
        return true
    }

    /**
     * Imports the Xumo adapter ALONE, and nothing else.
     *
     * Registering the adapter must not re-download the other six sources: their
     * guides are already stored, and a full pass would re-fetch ~100k programs
     * to add one platform. [IptvRepository.importGuide] is already a
     * single-source entry point (it is what the worker loop calls per URL), so
     * the spec's preferred outcome is the one implemented here - no fallback.
     *
     * The stamp is recorded exactly as the loop records it, so Playlist Setup
     * says when this source last succeeded, and a failure lands as that source's
     * own failure: a non-fatal log plus no stamp, leaving its rows as they were.
     */
    private fun importXumoAdapterOnly() {
        viewModelScope.launch {
            runCatchingCancellable {
                repository.importGuide(XumoGuide.SOURCE_KEY)
                GuideSourceStamps.recordSuccess(prefs, XumoGuide.SOURCE_KEY)
                _guideRefreshTick.value += 1
            }.onFailure { error ->
                // Cancellation is already rethrown by runCatchingCancellable;
                // what lands here is a real source failure, and it stays one:
                // no stamp is recorded, its rows are left as they were, and the
                // other sources are untouched.
                Log.e(
                    TAG,
                    "XUMO GUIDE IMPORT FAILED source=${Redaction.url(XumoGuide.SOURCE_KEY)}",
                    Redaction.throwable(error)
                )
            }
        }
    }

    /**
     * Removes the PRIMARY playlist - the URL and the name, nothing else.
     *
     * The extras, the guide sources and the free lineup are separate sources and
     * are left exactly as they are (a profile whose only real playlist was the
     * primary keeps loading its extras, through the same extras-only path a
     * free-lineup install uses). When the primary was the last source, the
     * profile lands back in the no-playlist state, with the Free TV offer.
     *
     * One tap, no confirmation: the form's own precedent (CLEAR is one tap) and
     * a removed playlist is one paste away from being back. The reload that
     * follows is the feedback.
     */
    fun removeMainPlaylist() {
        if (_playlistUrl.value.isBlank() && _playlistName.value.isBlank()) return
        val removedUrl = _playlistUrl.value.trim()
        _playlistUrl.value = ""
        _playlistName.value = ""
        saveInputs()
        forgetPlaylistRows(removedUrl)
        afterSourceRemoval()
    }

    /**
     * Removes ONE extra playlist by its URL.
     *
     * Its line leaves the extras blob (the rest of the blob is byte-for-byte
     * what the viewer typed), the blob persists, and the lineup reloads - the
     * same tap/reload/gone shape as [removeFreeLineup]. The guide SOURCE is a
     * separate setting and stays: an Extra EPG line may serve the other
     * playlists. What goes with the playlist is its own storage: cached channels
     * and guide matches (see [forgetPlaylistRows]), so a removed source cannot
     * answer a read or leave rows in the guide.
     */
    fun removeExtraPlaylist(url: String) {
        val wanted = url.trim()
        if (wanted.isEmpty()) return
        val remaining = PlaylistSources.removeEntry(_extraPlaylistUrls.value, wanted)
        if (remaining == _extraPlaylistUrls.value) return
        // One edit, and the EPG blob is passed through untouched: guide sources
        // are not playlists.
        writeSources(remaining, _extraEpgUrls.value)
        forgetPlaylistRows(wanted)
        afterSourceRemoval()
    }

    /**
     * Drops a removed playlist's own storage after its entry is gone.
     *
     * Fire-and-forget on purpose: the reload must not wait on a delete to start,
     * and the delete is idempotent (a source that is re-added re-caches on its
     * next load, which is the same path every load takes). The guide tick after
     * it is what makes the rows disappear without waiting for an import.
     */
    private fun forgetPlaylistRows(url: String) {
        val wanted = url.trim()
        if (wanted.isEmpty()) return
        viewModelScope.launch {
            runCatchingCancellable { repository.forgetPlaylist(wanted) }
            _guideRefreshTick.value += 1
        }
    }

    /**
     * The shared tail of every source removal - the free lineup, the primary
     * playlist, one extra - so all of them behave identically to the viewer:
     * tap, reload, gone.
     *
     * Nothing left to load means the profile returns to its exact no-playlist
     * state rather than leaving the removed lineup's rows on screen as if the
     * source were still configured. Otherwise the reload is the feedback.
     */
    private fun afterSourceRemoval() {
        if (effectiveLineupUrl().isBlank()) {
            restoreJob?.cancel()
            loadJob?.cancel()
            _playlist.value = null
            _guideChannelIds.value = emptySet()
            _guideItemsByChannelId.value = emptyMap()
            _pendingGuideChannelIds.value = emptySet()
            _error.value = null
            _guideRefreshTick.value += 1
        } else {
            load()
        }
    }

    /**
     * One prefs edit writes both source lists - never a half-installed state -
     * and then the flows every screen and the guide import reads.
     */
    private fun writeSources(extraPlaylistUrls: String, extraEpgUrls: String) {
        _extraPlaylistUrls.value = extraPlaylistUrls
        _extraEpgUrls.value = extraEpgUrls
        saveInputs()
    }

    /** `<url>|<name>` extras, trimmed, blanks dropped. */
    private fun extraPlaylistEntries(): List<Pair<String, String?>> =
        _extraPlaylistUrls.value
            .split('\n', ';')
            .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() } }
            .map { entry ->
                val parts = entry.split('|', limit = 2)
                parts[0].trim() to parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }
            .filter { (url, _) -> url.isNotBlank() }

    /**
     * The URL a load should read.
     *
     * A profile can own extras WITHOUT a primary playlist - that is the shape of
     * a free-lineup install - and the playlist slot stays empty on purpose, so
     * the primary is borrowed from the first extra for LOADING only. Nothing is
     * ever written back to `playlist_url`.
     */
    private fun effectiveLineupUrl(): String =
        _playlistUrl.value.trim().ifBlank {
            extraPlaylistEntries().firstOrNull()?.first.orEmpty()
        }

    /** The name to load under: the typed one, else the first extra's label. */
    private fun lineupLoadName(): String? {
        val typed = _playlistName.value.trim()
        if (typed.isNotEmpty()) return typed
        if (_playlistUrl.value.isNotBlank()) return null
        return extraPlaylistEntries().firstOrNull()?.second ?: FreeLineup.NAME
    }

    /**
     * One extra source's channels, ids prefixed with the source's hash so two
     * providers that both emit "channel_1" cannot collide (dedupe + EPG keys).
     *
     * ONE rule, used by every merge: the guide's, the extras-only lineup's and
     * (in the hub's own copy) the Sports Hub's, so a channel a card names is the
     * channel the guide would have launched.
     */
    private fun namespacedExtraChannels(
        url: String,
        channels: List<IptvChannel>
    ): List<IptvChannel> {
        val ns = (url.hashCode().toUInt() and 0xFFFFFFu).toString(16)
        return channels.map { channel ->
            if (channel.id.isBlank()) channel else channel.copy(id = "x$ns:${channel.id}")
        }
    }

    private val _playlist = MutableStateFlow<IptvPlaylist?>(null)
    val playlist: StateFlow<IptvPlaylist?> = _playlist.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Whether a guide import is running - a MIRROR of [GuideImportProgress.isRunning],
     * never a fact of its own.
     *
     * It used to be the fact: this ViewModel set it at the start of its own pass
     * and cleared it at the end, while the numbers the screens show come from
     * [GuideImportProgress]. The two could disagree - a pass opened by another
     * instance, or a stopped pass's `finally` clearing a newer one's line, left
     * the setup screen saying IMPORTING… with no progress behind it, or refusing
     * LOAD with a bare "EPG import is in progress" line. Now there is one fact,
     * and this is it echoed: written from [GuideImportProgress] at both ends of a
     * pass this ViewModel runs, and kept in step by [observeGuideImportPass] for a
     * pass it did not start.
     */
    private val _isImportingGuide = MutableStateFlow(GuideImportProgress.isRunning)
    val isImportingGuide: StateFlow<Boolean> = _isImportingGuide.asStateFlow()

    /**
     * How far the guide import has got, for the setup screen's status line.
     * Null whenever nothing is importing — see [GuideImportProgress].
     */
    val guideImport: StateFlow<GuideImportState?> = GuideImportProgress.state

    private val _guideStatus = MutableStateFlow(GuideRefreshStatus())

    /**
     * Whether this profile's guide is being kept fresh: which source last
     * imported and when, and whether the background refresh has given up (see
     * [GuideRefreshStatus]).
     *
     * Republished on every entry check and after every import pass — the two
     * moments the answer can change. A background pass that runs while no
     * screen is open is picked up by the next entry, which is exactly when a
     * viewer could read it.
     */
    internal val guideStatus: StateFlow<GuideRefreshStatus> = _guideStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _guideError = MutableStateFlow<String?>(null)
    val guideError: StateFlow<String?> = _guideError.asStateFlow()

    private val _hiddenChannelIds = MutableStateFlow(
        prefs.getStringSet(KEY_HIDDEN_CHANNEL_IDS, emptySet()).orEmpty().toSet()
    )
    val hiddenChannelIds: StateFlow<Set<String>> = _hiddenChannelIds.asStateFlow()

    /**
     * Re-reads the hidden set when it changes on disk. The sync applier writes
     * this key straight from the cloud, and without a listener the ViewModel
     * keeps the set it was constructed with — so a synced hide never shows up,
     * and the next local edit saves that stale set back and pushes it, which
     * erases the hidden channels on the other device too.
     */
    private val hiddenIdsPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != KEY_HIDDEN_CHANNEL_IDS) return@OnSharedPreferenceChangeListener
            val stored = prefs.getStringSet(KEY_HIDDEN_CHANNEL_IDS, emptySet()).orEmpty().toSet()
            if (stored != _hiddenChannelIds.value) _hiddenChannelIds.value = stored
        }

    /** Store the listener is bound to; re-bound when the profile changes. */
    private var hiddenIdsPrefs: SharedPreferences? = null

    private fun observeHiddenChannelIdsPref() {
        val current = prefs
        if (hiddenIdsPrefs === current) return
        hiddenIdsPrefs?.unregisterOnSharedPreferenceChangeListener(hiddenIdsPrefListener)
        current.registerOnSharedPreferenceChangeListener(hiddenIdsPrefListener)
        hiddenIdsPrefs = current
    }

    override fun onCleared() {
        hiddenIdsPrefs?.unregisterOnSharedPreferenceChangeListener(hiddenIdsPrefListener)
        hiddenIdsPrefs = null
        super.onCleared()
    }

    /** Past programs with playable DVR URLs for the selected channel. */
    private val _catchupPrograms = MutableStateFlow<List<com.kennyb1201.kbstream.data.iptv.CatchupProgram>>(emptyList())
    val catchupPrograms: StateFlow<List<com.kennyb1201.kbstream.data.iptv.CatchupProgram>> =
        _catchupPrograms.asStateFlow()

    /** True while a catch-up list is loading, so the dialog does not read the
     *  empty (cleared) list as "this channel has no catch-up". */
    private val _catchupLoading = MutableStateFlow(false)
    val catchupLoading: StateFlow<Boolean> = _catchupLoading.asStateFlow()

    /**
     * Why the last catch-up read failed, or null when it answered.
     *
     * A failed read used to publish an empty list and nothing else, so the
     * dialog's "no recent programs, try again after the EPG imports" spoke for
     * a provider that never answered - the fourth instance of failure-reads-as-
     * empty in this tree (Browse C4, Search C4, Library C4). The dialog prints
     * this instead, and the import advice is kept for a channel that really has
     * no history.
     */
    private val _catchupError = MutableStateFlow<String?>(null)
    val catchupError: StateFlow<String?> = _catchupError.asStateFlow()

    private var catchupJob: Job? = null

    /**
     * Bumped by every catch-up load. A cancelled load's `finally` still runs -
     * AFTER its replacement has set the flag true - so without this the old job
     * cleared the new one's loading flag and the dialog showed "No recent
     * programs available" until the real results landed (see HD-P2-7).
     */
    private var catchupGeneration = 0

    /**
     * Loads the catch-up (DVR) list for [channel]. An empty result means
     * the channel carries no catch-up attributes or has no EPG history —
     * the UI hides the section rather than showing dead entries.
     */
    fun loadCatchupPrograms(channel: IptvChannel) {
        catchupJob?.cancel()
        val generation = ++catchupGeneration
        // Drop the previous channel's list and flag the load: the dialog reads
        // this state the instant it opens, and leaving it populated flashed the
        // last channel's programs before the new ones landed.
        _catchupPrograms.value = emptyList()
        _catchupError.value = null
        _catchupLoading.value = true
        catchupJob = viewModelScope.launch {
            try {
                // Guide data may live under any of the configured EPG
                // sources; the first one that yields programs wins.
                var found: List<com.kennyb1201.kbstream.data.iptv.CatchupProgram> = emptyList()
                for (url in allEpgUrls()) {
                    val rows = repository.getRecentCatchupPrograms(
                        channel = channel,
                        epgUrl = url
                    )
                    if (rows.isNotEmpty()) {
                        found = rows
                        break
                    }
                }
                // The LIST is generation-guarded too, not just the spinner: the
                // reason the generation exists is that a superseded job still
                // reaches its own resume points, and an unguarded write here put
                // channel A's DVR programs in channel B's dialog.
                if (generation == catchupGeneration) _catchupPrograms.value = found
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "CATCHUP LOAD FAILED channel=${channel.id}: ${t.message}")
                if (generation == catchupGeneration) {
                    _catchupPrograms.value = emptyList()
                    // The dialog's empty state has to be able to tell this apart
                    // from a channel with no DVR history: "wait for the EPG to
                    // import" is the wrong advice for a provider that failed.
                    _catchupError.value = CATCHUP_FAILED_LABEL
                }
            } finally {
                // Only the CURRENT load may clear the flag (see
                // [catchupGeneration]).
                if (generation == catchupGeneration) _catchupLoading.value = false
            }
        }
    }

    private val _guideRefreshTick = MutableStateFlow(0)

    // Bumped by the periodic now/next refresh WHEN that refresh has to go back
    // to the database. It is part of the query request (so a clock refresh can
    // never be conflated away by distinctUntilChanged) but deliberately NOT part
    // of the guide source key: the loaded programs are still valid, only "now"
    // moved. A tick the loaded programs can answer in memory leaves the request
    // untouched, so distinctUntilChanged() conflates it away and nothing runs.
    private val _guideClockTick = MutableStateFlow(0)

    // Persistent "already requested/cached" set. NEVER fed into combine() below —
    // that was the bug. Only clearGuideMemory() resets this, and it does so without
    // being observed by the flow that reads it, so resetting it can't self-trigger
    // a cancel-and-restart loop.
    private val _guideChannelIds = MutableStateFlow<Set<String>>(emptySet())

    // The actual combine() trigger: just the newly-queued batch for this request.
    // Cleared back to emptySet() only after a successful merge, not as a side
    // effect of clearing the cache above.
    private val _pendingGuideChannelIds = MutableStateFlow<Set<String>>(emptySet())

    private val _guideItemsByChannelId =
        MutableStateFlow<Map<String, IptvChannelWithEpg>>(emptyMap())

    private var loadedGuideSourceKey: String? = null

    /**
     * Channels whose loaded programs have run out and whose re-query already
     * came back with nothing better.
     *
     * A provider whose listing stops at midnight leaves every channel in that
     * state for hours, and asking again on the clock's cadence is exactly the
     * cost [bumpGuideClock] exists to remove. A channel leaves this set as soon
     * as its rows can answer the clock again - a fresh import clears the set,
     * and a re-query that lands with a longer listing drops out of it by itself.
     */
    private var exhaustedGuideChannelIds: Set<String> = emptySet()

    /**
     * [guideWindowFingerprint] of the playlist slice the loaded guide was built
     * from, so a playlist refresh that changed nothing guide-relevant can keep
     * what is already on screen instead of re-querying every program batch.
     */
    private var loadedGuideFingerprint: String? = null
    private var loadJob: Job? = null

    /**
     * The ONE guide import, whichever path started it.
     *
     * Both launch sites - the IMPORT EPG button and the entry auto-refresh -
     * go through [launchGuideImport], which is what makes "IMPORT EPG always
     * stops the running import" true for an auto-refresh import too. It used to
     * be two handles: the button cancelled its own private one while the entry
     * import ran on `refreshJob`, so a stuck auto-import could not be cancelled
     * from the button at all - the manual import merely joined it through
     * [IptvRepository]'s coalescing and sat at the same 0 programs.
     */
    private var guideImportJob: Job? = null

    /**
     * [GuideImportProgress]'s id for the pass [guideImportJob] is running, so
     * Cancel and the pass's own `finally` can both speak about it without either
     * one clobbering a newer pass's status line (see
     * [GuideImportProgress.finish]).
     */
    private var guideImportPassId: Long = 0L

    private var refreshJob: Job? = null

    /**
     * The cached-playlist restore in flight.
     *
     * It used to be an anonymous launch, so a profile switch could not cancel
     * it: the old profile's restore then applied its lineup over the new
     * profile's cleared state (the guide showing the previous profile's
     * channels), refreshed against the new profile's prefs, and cleared the
     * spinner while the new restore was still running. Named like its
     * siblings, and cancelled with them, so `loadJob`/`guideImportJob`/
     * `refreshJob` are no longer the only jobs the switch knows about.
     */
    private var restoreJob: Job? = null

    /**
     * Aborts a source that stops sending data, so a wedged download is a visible
     * error in three minutes rather than twenty minutes at "0 programs" (see
     * [EpgImportWatchdog]).
     */
    private val epgImportWatchdog = EpgImportWatchdog()

    private val playlistOnlyLineup: StateFlow<List<IptvChannelWithEpg>> = _playlist
        .map { currentPlaylist ->
            currentPlaylist?.let(::buildPlaylistOnlyLineup).orEmpty()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList()
        )

    // flatMapLatest is still a @ExperimentalCoroutinesApi API; opting in here
    // rather than at file scope keeps the acceptance to this one property.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val lineupSource: StateFlow<List<IptvChannelWithEpg>> = combine(
        combine(
            _playlist,
            _epgUrl,
            _extraEpgUrls
        ) { playlist, epgUrl, extraEpgRaw ->
            // Pair the playlist with its guide-source list (primary first,
            // then extras). Inner combine's typed overload handles 3 flows;
            // the outer one would exceed the 5-flow typed limit otherwise.
            val guideUrls = (epgUrl.trim().takeIf(String::isNotEmpty)
                ?.let(::listOf).orEmpty() +
                extraEpgRaw.split('\n', ';')
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) })
                .distinct()
            playlist to guideUrls
        },
        _guideRefreshTick,
        _pendingGuideChannelIds,
        _guideClockTick
    ) { playlistAndGuides, refreshTick, channelIds, clockTick ->
        val (currentPlaylist, guideUrls) = playlistAndGuides
        GuideRequest(
            playlist = currentPlaylist,
            guideUrls = guideUrls,
            refreshTick = refreshTick,
            clockTick = clockTick,
            channelIds = channelIds
        )
    }
        // The request carries more than the query reads: _playlist changes for
        // reasons that cannot move a single program row (a logo URL, a
        // channel outside the guide window), and the import flag used to flip
        // twice per EPG import — each flip canceling and re-running the whole
        // lineup query for identical data. The refresh and clock ticks stay
        // explicit request inputs, so real refreshes still land.
        .distinctUntilChanged()
        .flatMapLatest { request ->
        val currentPlaylist = request.playlist

        when {
            currentPlaylist == null -> flowOf(emptyList())
            request.guideUrls.isEmpty() -> flowOf(emptyList())
            request.channelIds.isEmpty() -> flowOf(emptyList())
            // NOTE: no isImportingGuide guard here. While a stale-EPG
            // background refresh runs, the previous import's programs are
            // still in the DB — blanking the lineup for the whole import made
            // the guide show "no program data" for minutes on entry (it only
            // recovered when the import finished). Query the cached data
            // right away; importGuideInternal() bumps the refresh tick when
            // done, which re-runs this query against the fresh import.
            // A guide read can lose its database mid-query: the guide file is
            // per-profile and its instance is retired across a profile change
            // (see data/db/DatabaseSwapRetry.kt). Retrying HERE, inside the
            // request, re-runs the query through a freshly resolved DAO and
            // keeps this flow alive. Without it the exception took the whole
            // lineup flow down — and the lineup flow is what the guide screen
            // shows, so it stayed empty for the rest of the screen's life.
            else -> observeGuideRequest(currentPlaylist, request)
                .retryOnDatabaseSwap { attempt, error ->
                    Log.w(
                        TAG,
                        "GUIDE QUERY RETRY $attempt after database swap",
                        error
                    )
                }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        emptyList()
    )

    val allChannels: StateFlow<List<IptvChannelWithEpg>> = combine(
        playlistOnlyLineup,
        _guideItemsByChannelId
    ) { playlistOnly, guideByChannelId ->
        mergePlaylistWithGuide(playlistOnly, guideByChannelId)
    }.flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList()
        )

    val visibleChannels: StateFlow<List<IptvChannelWithEpg>> = combine(
        allChannels,
        _hiddenChannelIds
    ) { channels, hiddenIds ->
        val base =
            if (hiddenIds.isEmpty()) channels else channels.filterNot { it.channel.id in hiddenIds }
        kidsFilterChannels(base)
    }.flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList()
        )

    /**
     * Channel ids whose guide row has already been RESOLVED by a lineup
     * query — the keys of the guide cache above.
     *
     * A channel missing from here has simply not been queried yet: the
     * guide window only covers the channels the user has actually scrolled
     * past, so every group switch swaps in a screenful of channels whose
     * EPG is still on its way. That is a different state from a resolved
     * channel that matched no XMLTV channel at all, and the guide must not
     * confuse the two — without the distinction the new group rendered a
     * false "No program data" on every row for the second the first query
     * for it took to land. The UI renders the first state as a quiet
     * loading placeholder and only the second as "No program data".
     */
    val resolvedGuideChannelIds: StateFlow<Set<String>> = _guideItemsByChannelId
        .map { it.keys }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /**
     * The guide rows resolved so far, keyed by playlist channel id: the guide's
     * visible rows plus anything [updateGuideChannels] has been asked for.
     *
     * Read by the search overlay so a channel hit can say what is on now and
     * next before it is opened, and without a second query - the rows the
     * search asked for are already the ones the guide loaded.
     */
    val loadedGuideItems: StateFlow<Map<String, IptvChannelWithEpg>> =
        _guideItemsByChannelId.asStateFlow()

    /**
     * Kids Mode Live TV filter (profile toggle "Kid-safe Live TV"). An
     * M3U guide can't be rated, so when the active kids profile opts in,
     * only channel groups that LOOK kid-focused survive — group titles
     * are matched loosely (case, spaces, separators) against kid brand
     * words. Groups with no recognizable kid signal are hidden: for a
     * curated playlist a parent can turn the toggle off; for a random
     * playlist the safe direction is hiding the unclassifiable.
     */
    private fun kidsFilterChannels(
        channels: List<IptvChannelWithEpg>
    ): List<IptvChannelWithEpg> {
        // The rule itself moved to KidsLiveTvFilter so the SPORTS hub's WATCH
        // can ask the same question (audit C7): it was the second door into the
        // live player, and it used the raw playlist.
        val profile = com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value
        if (profile?.kidsMaxAge == null || !profile.kidsLockLiveTv) return channels
        return channels.filter { ch ->
            com.kennyb1201.kbstream.data.iptv.KidsLiveTvFilter.keep(
                profile,
                ch.channel.displayName,
                ch.channel.groupTitle
            )
        }
    }

    init {
        repairFreeLineupGuideEntry()
        observeGuideImportPass()
        viewModelScope.launch {
            lineupSource.collect { lineup ->
                if (lineup.isNotEmpty()) {
                    mergeGuideItems(lineup)
                    // Drop only the ids this merge answered. A superseded
                    // request never emits, so its ids are still outstanding
                    // and must survive to be re-issued; clearing the whole set
                    // here is what left those channels pending forever.
                    _pendingGuideChannelIds.value = GuideRequestQueue.clearAnswered(
                        _pendingGuideChannelIds.value,
                        _guideItemsByChannelId.value.keys
                    )
                }
            }
        }
        observeHiddenChannelIdsPref()
        startGuideClockRefresh()
        restoreCachedPlaylist()
        observeProfileSwitches()
    }

    /**
     * Keeps [isImportingGuide] equal to the pass actually open in
     * [GuideImportProgress], including a pass this ViewModel did not start.
     *
     * Every place that asks "is an import running" - the setup screen's button,
     * the guide's status line, the Load guard, the migrations - asks this
     * ViewModel, and the pass can outlive the instance that opened it (two
     * screens hold their own copy, an activity-scoped one survives navigation).
     * Mirroring the shared object is what makes them all one answer; the two
     * ends of a locally-run pass also write the mirror directly, so a guard read
     * on the same frame as `begin()`/`finish()` is never a frame behind.
     */
    private fun observeGuideImportPass() {
        viewModelScope.launch {
            GuideImportProgress.state.collect { state ->
                _isImportingGuide.value = state != null && state.outcome == null
            }
        }
    }

    /**
     * Profile-switch reset: the ViewModel is created once (activity store)
     * and survives switches, but its source config is per-profile (scoped
     * prefs) and its in-memory playlist/EPG state belongs to whichever
     * profile loaded it. Without this, after a switch the guide shows the
     * previous profile's channels and actions persist into the wrong
     * profile's prefs.
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collect {
                    if (first) {
                        first = false
                        return@collect
                    }
                    loadJob?.cancel()
                    guideImportJob?.cancel()
                    refreshJob?.cancel()
                    restoreJob?.cancel()

                    // Re-read the incoming profile's scoped config.
                    _playlistUrl.value = prefs.getString(KEY_PLAYLIST_URL, "").orEmpty()
                    _epgUrl.value = prefs.getString(KEY_EPG_URL, "").orEmpty()
                    _playlistName.value = prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty()
                    _extraPlaylistUrls.value = prefs.getString(KEY_EXTRA_PLAYLIST_URLS, "").orEmpty()
                    _extraEpgUrls.value = prefs.getString(KEY_EXTRA_EPG_URLS, "").orEmpty()
                    // The incoming profile may be an install from an older build.
                    repairFreeLineupGuideEntry()
                    _hiddenChannelIds.value =
                        prefs.getStringSet(KEY_HIDDEN_CHANNEL_IDS, emptySet()).orEmpty().toSet()
                    // Re-bind: the listener has to follow the new profile's store.
                    observeHiddenChannelIdsPref()

                    // Drop the previous profile's in-memory content.
                    _playlist.value = null
                    _guideItemsByChannelId.value = emptyMap()
                    _guideChannelIds.value = emptySet()
                    _pendingGuideChannelIds.value = emptySet()
                    exhaustedGuideChannelIds = emptySet()
                    loadedGuideSourceKey = null
                    _guideRefreshTick.value += 1

                    restoreCachedPlaylist()
                }
        }
    }

    /**
     * Keeps NOW/NEXT/Upcoming from going stale while the guide is open. The
     * lineup flow computes nowUtcMillis and the query window once per run and
     * only re-runs when playlist/EPG/tick/channel-batch changes -- left alone,
     * a channel whose query ran at 10:00 keeps claiming the 10:00 program
     * is "on now" well past its end.
     *
     * Each tick advances the loaded programs in memory ([bumpGuideClock]), and
     * only the channels it cannot advance any further are re-queued through
     * _pendingGuideChannelIds for a fresh query -- so a tick costs a walk over
     * the rows already on screen, and the flow itself does not run at all.
     *
     * Periodic while the VM is alive (subscribers stop within STOP_TIMEOUT_MS
     * of leaving the guide, and the VM dies with it) plus one shot on start
     * so returning to the guide after a while shows current programs
     * immediately instead of the last session's snapshot.
     *
     * The same loop is where guide FRESHNESS gets re-checked, on a slow
     * multiple of the clock cadence. Staleness used to be evaluated exactly
     * once, on the cache-restore path at construction, so an app left running
     * (a TV box can stay open for days) never revisited the question and its
     * guide aged out from under it - showing yesterday's schedule until the
     * process was restarted.
     */
    private fun startGuideClockRefresh() {
        viewModelScope.launch {
            // First pass: catch up after returning to the screen. Small delay
            // so the initial pipeline (restore + initial window request) gets
            // going first and this rides along after it.
            delay(GUIDE_CLOCK_FIRST_REFRESH_MS)
            bumpGuideClock()

            var ticks = 0
            while (isActive) {
                delay(GUIDE_CLOCK_REFRESH_INTERVAL_MS)
                bumpGuideClock()
                if (++ticks % GUIDE_STALENESS_CHECK_EVERY_TICKS == 0) refreshIfNeeded()
            }
        }
    }

    /**
     * One clock tick: move NOW/NEXT forward from the programs the guide has
     * already loaded, and go back to the database ONLY for the channels whose
     * loaded programs have run out.
     *
     * This used to re-issue the whole loaded batch — a lineup query for up to 80
     * channels x 960 programs every two minutes, for as long as the guide was
     * open, which was the heaviest thing the app repeated. It was also almost
     * entirely wasted: the programs are already in memory, and the clock moving
     * only changes which of them is "on now". The database is needed exactly
     * when a channel's next program has already ended, which is a schedule
     * boundary rather than a two-minute grid (see [GuideClockAdvance]).
     */
    private fun bumpGuideClock() {
        val loaded = _guideItemsByChannelId.value
        val now = System.currentTimeMillis()

        // Forget every channel that can answer the clock again — a fresh import
        // landing, or a re-query that brought back a longer listing — and every
        // channel that is no longer loaded at all.
        if (exhaustedGuideChannelIds.isNotEmpty()) {
            exhaustedGuideChannelIds = exhaustedGuideChannelIds
                .filter { channelId ->
                    loaded[channelId]?.let { !GuideClockAdvance.canAnswer(it, now) } == true
                }
                .toSet()
        }

        if (loaded.isEmpty()) return

        val step = GuideClockAdvance.step(loaded, now)

        if (step.advanced.isNotEmpty()) {
            val updated = HashMap(loaded)
            step.advanced.forEach { (channelId, item) -> updated[channelId] = item }
            _guideItemsByChannelId.value = updated
        }

        // A row that has run out is re-queried once, not once per tick.
        val toRequery = step.expiredIds - exhaustedGuideChannelIds
        if (toRequery.isEmpty()) return

        exhaustedGuideChannelIds = exhaustedGuideChannelIds + toRequery
        // ADD to the outstanding set, for the same reason updateGuideChannels()
        // does: flatMapLatest cancels an in-flight query the moment a newer
        // batch arrives, and a cancelled query never emits, so replacing the set
        // would strand whatever was already being answered.
        _pendingGuideChannelIds.value =
            GuideRequestQueue.enqueue(_pendingGuideChannelIds.value, toRequery)
        // The re-issued batch can be the same ids it was last time, so the tick
        // is what makes the request distinct — without it distinctUntilChanged()
        // would swallow it and the channel would keep its last known program.
        _guideClockTick.value += 1
    }

    fun onPlaylistUrlChanged(value: String) {
        _playlistUrl.value = value
        saveInputs()
    }

    fun onEpgUrlChanged(value: String) {
        _epgUrl.value = value
        saveInputs()

        val playlist = _playlist.value
        if (playlist != null) {
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = playlist,
                    guideUrls = allEpgUrls(),
                    refreshTick = _guideRefreshTick.value + 1
                )
            )
        } else {
            clearGuideMemory()
        }
        _guideRefreshTick.value += 1
    }

    /** Parsed extra-EPG textarea: trimmed, one URL per line or ';'. */
    private fun extraEpgUrlList(): List<String> =
        _extraEpgUrls.value
            .split('\n', ';')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

    fun onExtraEpgUrlsChanged(value: String) {
        _extraEpgUrls.value = value
        saveInputs()

        val playlist = _playlist.value
        if (playlist != null) {
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = playlist,
                    guideUrls = allEpgUrls(),
                    refreshTick = _guideRefreshTick.value + 1
                )
            )
        } else {
            clearGuideMemory()
        }
        _guideRefreshTick.value += 1
    }

    fun onPlaylistNameChanged(value: String) {
        _playlistName.value = value
        saveInputs()
    }

    fun hideChannel(channelId: String) {
        if (channelId.isBlank() || channelId in _hiddenChannelIds.value) return
        _hiddenChannelIds.value = _hiddenChannelIds.value + channelId
        saveHiddenChannelIds()
        _guideChannelIds.value = _guideChannelIds.value - channelId
        _pendingGuideChannelIds.value = _pendingGuideChannelIds.value - channelId
        _guideItemsByChannelId.value = _guideItemsByChannelId.value - channelId
    }

    fun unhideChannel(channelId: String) {
        if (channelId.isBlank() || channelId !in _hiddenChannelIds.value) return
        _hiddenChannelIds.value = _hiddenChannelIds.value - channelId
        saveHiddenChannelIds()
    }

    fun unhideAllChannels() {
        if (_hiddenChannelIds.value.isEmpty()) return
        _hiddenChannelIds.value = emptySet()
        saveHiddenChannelIds()
    }

    fun updateGuideChannels(visibleChannelIds: List<String>) {
        if (visibleChannelIds.isEmpty()) return
        val playlist = _playlist.value ?: return
        val hiddenIds = _hiddenChannelIds.value
        val queuedIds = _guideChannelIds.value
        val knownIds = playlist.channels.asSequence().map { it.id }.toHashSet()
        val newIds = visibleChannelIds.asSequence()
            .filter { it in knownIds && it !in hiddenIds && it !in queuedIds }
            .take(MAX_GUIDE_CHANNELS_PER_REQUEST)
            .toSet()
        if (newIds.isEmpty()) return
        _guideChannelIds.value = queuedIds + newIds
        // ADD to the outstanding set, don't replace it. The lineup flow
        // cancels an in-flight query the moment a newer batch arrives, and a
        // cancelled query never emits, so the batch it was working on would
        // otherwise vanish from here while staying in _guideChannelIds - no
        // longer pending (never re-issued) and never resolved, which is the
        // row stuck on "Loading guide...".
        _pendingGuideChannelIds.value = GuideRequestQueue.enqueue(
            _pendingGuideChannelIds.value,
            newIds
        )
        Log.d(TAG, "GUIDE CHANNELS QUEUED total=${_guideChannelIds.value.size} added=${newIds.size}")
    }

    private fun observeGuideRequest(
        currentPlaylist: IptvPlaylist,
        request: GuideRequest
    ): Flow<List<IptvChannelWithEpg>> {
        val sourceKey = buildGuideSourceKey(
            currentPlaylist, request.guideUrls, request.refreshTick
        )

        // NOTE: this branch should rarely fire in practice now, because applyPlaylist()/
        // onEpgUrlChanged()/importGuideInternal() precompute and set loadedGuideSourceKey
        // BEFORE bumping _guideRefreshTick. It stays here only as a safety net for a
        // source-key mismatch we didn't anticipate. It intentionally does NOT touch
        // _guideChannelIds/_pendingGuideChannelIds (those aren't part of the key), so
        // it can't create the old self-clearing feedback loop.
        if (loadedGuideSourceKey != sourceKey) {
            loadedGuideSourceKey = sourceKey
            _guideItemsByChannelId.value = emptyMap()
        }

        val channelsById = currentPlaylist.channels.associateBy { it.id }
        val channelsToLoad = request.channelIds.asSequence()
            .mapNotNull(channelsById::get)
            .take(MAX_GUIDE_CHANNELS_PER_REQUEST)
            .toList()

        Log.w(TAG, "GUIDE QUERY requested=${request.channelIds.size} channels=${channelsToLoad.size}")
        if (channelsToLoad.isEmpty()) return flowOf(emptyList())

        val now = System.currentTimeMillis()
        return repository.observeLineupWithGuides(
            playlist = currentPlaylist.copy(channels = channelsToLoad),
            epgUrls = request.guideUrls,
            windowStart = now - GUIDE_PAST_WINDOW_MS,
            windowEnd = now + GUIDE_FUTURE_WINDOW_MS,
            limit = VISIBLE_GUIDE_PROGRAM_LIMIT
        )
    }

    private fun requestInitialGuideWindow() {
        val hiddenIds = _hiddenChannelIds.value
        val initialChannelIds = _playlist.value?.channels?.asSequence()
            ?.filterNot { it.id in hiddenIds }
            ?.take(INITIAL_GUIDE_WINDOW_SIZE)
            ?.map { it.id }
            ?.toList()
            .orEmpty()
        updateGuideChannels(initialChannelIds)
    }

    private fun restoreCachedPlaylist() {
        val url = effectiveLineupUrl()
        val name = lineupLoadName()
        if (url.isBlank()) {
            Log.d(TAG, "CACHE RESTORE SKIPPED no playlist sources are configured")
            return
        }
        restoreJob?.cancel()
        _isLoading.value = true
        // Playlist URLs go through Redaction.url: Xtream-style providers put
        // the account username and password in the query string.
        Log.w(TAG, "CACHE RESTORE START source=${Redaction.url(url)}")
        restoreJob = viewModelScope.launch {
            try {
                // An install from before the locals guide carries only kanya:
                // add the second source first, so the guide machinery (and the
                // import the viewer may hit next) already sees it.
                val localsLineAdded = migrateFreeLineupLocalsEpg()
                // An install from before the FAST platform guides carries only
                // kanya (+ locals): add the missing platform feeds too, before
                // the cache read, for the same reason.
                val fastLinesAdded = migrateFreeLineupFastEpg()
                // The Xumo adapter has no line to write, so it is registered by
                // flag and imported on its OWN - deliberately not folded into
                // guideSourceAdded, which forces a full pass over every source.
                val xumoAdapterAdded = migrateFreeLineupXumoEpg()
                // A line that was just written is not in any guide yet: the
                // import below (or after the load) reads the list (§3.4).
                val guideSourceAdded = localsLineAdded || fastLinesAdded
                if (xumoAdapterAdded) importXumoAdapterOnly()
                // An install from before the US filter still holds its old,
                // unfiltered rows: rewrite the cache before this restore reads
                // it (see [migrateFreeLineupUsFilter], spec §3).
                migrateFreeLineupUsFilter()
                // No primary playlist means the extras ARE the lineup, so the
                // cache read is the extras' own (see loadCachedExtrasLineup).
                val cachedPlaylist = if (_playlistUrl.value.isBlank()) {
                    loadCachedExtrasLineup()
                } else {
                    repository.loadCachedPlaylist(url, name)
                }
                // A profile switch rewrote the pref while this was reading, and
                // its own restore is the one that owns the state now. Applying
                // here would put the PREVIOUS profile's lineup under the new
                // profile - the reported cross-profile guide.
                if (effectiveLineupUrl() != url) {
                    Log.w(TAG, "CACHE RESTORE DROPPED: profile/URL changed mid-read")
                    return@launch
                }
                if (cachedPlaylist != null) {
                    // Extras must survive app restarts too — otherwise the
                    // cached restore shows only the primary playlist until
                    // the user manually reloads (applyPlaylist merges them).
                    applyPlaylist(cachedPlaylist)
                    Log.w(TAG, "CACHE RESTORE HIT channels=${cachedPlaylist.channels.size} source=${Redaction.url(url)}")
                    refreshIfNeeded(forceGuide = guideSourceAdded)
                } else {
                    // URL is configured but nothing cached (first entry after
                    // clearing app storage, a provider purge, or a profile
                    // switch before the first successful load). Falling back
                    // to showing the setup screen with a dead action row made
                    // the guide LOOK broken; a configured URL is an explicit
                    // instruction to load, so fetch it now. The UI keeps the
                    // guide usable meanwhile: channels appear the moment the
                    // fetch + cache write finish.
                    Log.w(TAG, "CACHE RESTORE MISS source=${Redaction.url(url)} — auto-loading playlist")
                    if (guideSourceAdded) loadWithGuideRefresh() else load()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (effectiveLineupUrl() != url) return@launch
                reportFailure(_error, t)
                Log.e(TAG, "CACHE RESTORE FAILED source=${Redaction.url(url)}", Redaction.throwable(t))
            } finally {
                // Only the restore that still owns the page may stop its
                // spinner: clearing it here for a superseded one stopped the
                // new profile's restore mid-flight.
                if (effectiveLineupUrl() == url) _isLoading.value = false
            }
        }
    }

    /**
     * The setup panel's RELOAD: [load], plus a guide import whatever the
     * freshness window says.
     *
     * RELOAD and LOAD were the same call, so a viewer whose guide had gone
     * stale pressed RELOAD and got a fresh lineup behind the SAME old programs -
     * "RELOAD didn't fix it". The force-refresh path already existed for the
     * Free TV opt-in (a source the app had just written); this is the same path
     * on demand, which is what the second button means.
     */
    fun reload() {
        loadWithGuideRefresh()
    }

    fun load() {
        // Deliberately NOT gated on a running guide import. The pass writes only
        // its own source's STAGED rows and promotes them in one transaction on a
        // complete parse (see XmltvImporter), so the live guide a load reads is
        // always a whole guide, and the lineup query has never blanked itself for
        // an import (see the lineage note in `lineupSource`). Refusing the load
        // instead left Playlist Setup as a dead end: the viewer could not reach
        // the guide whose progress line would have told them the import was
        // healthy, and LOAD was the only way out of the screen.
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadLineup() }
    }

    /**
     * [load] for a load that must be followed by a guide import whatever the
     * window says - the Free TV opt-in, whose tap just wrote new guide lines
     * (see [startGuideImportForNewSource]).
     */
    private fun loadWithGuideRefresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadLineup(forceGuideRefresh = true) }
    }

    /**
     * The one load pipeline, answering whether a lineup is now in hand.
     *
     * Suspending rather than fire-and-forget so an install can report its own
     * outcome (see [installFreeLineup]): the free lineup's screens have to say
     * whether it worked, and a launcher cannot tell.
     */
    private suspend fun loadLineup(forceGuideRefresh: Boolean = false): LoadOutcome {
        val url = effectiveLineupUrl()
        val name = lineupLoadName()
        // No primary playlist and no extras: there is nothing to load at all.
        if (url.isBlank()) {
            _error.value = "Playlist URL is required"
            return LoadOutcome.FAILED
        }
        val hasPlaylist = _playlist.value != null
        _isLoading.value = !hasPlaylist
        _error.value = null
        return try {
            // With no primary playlist the extras ARE the lineup (see
            // [effectiveLineupUrl]): they are loaded and namespaced here, and
            // not merged a second time by [applyPlaylist].
            val loadedPlaylist = if (_playlistUrl.value.isBlank()) {
                loadExtrasOnlyLineup()
            } else {
                loadPlaylistResilient(url, name)
            }
            applyPlaylist(loadedPlaylist)
            markUpdated(KEY_PLAYLIST_UPDATED_AT)
            Log.d(TAG, "PLAYLIST LOAD SUCCESS channels=${loadedPlaylist.channels.size} source=${Redaction.url(url)}")
            // The cache-MISS path (fresh install, cleared storage, a
            // profile switch before the first load) ends up here, and the
            // synced EPG URLs are already configured — so opening the
            // Guide used to show channels with "No program data" until
            // IMPORT EPG was pressed. The cache-HIT path above already
            // asks, and this is the same ask after the fresh channel list
            // is in place. refreshIfNeeded's own guards make it safe: it
            // no-ops with no EPG URL, with a fresh guide, while an import
            // is in flight, and during the stall cooldown.
            refreshIfNeeded(forceGuide = forceGuideRefresh)
            LoadOutcome.LOADED
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportFailure(_error, t)
            Log.e(TAG, "PLAYLIST LOAD FAILED source=${Redaction.url(url)}", Redaction.throwable(t))
            if (!hasPlaylist) _playlist.value = null
            LoadOutcome.FAILED
        } finally {
            _isLoading.value = false
        }
    }

    /** How one load pipeline ended - see [loadLineup]. */
    private enum class LoadOutcome {
        /** A lineup is in hand (or an already-loaded one was kept). */
        LOADED,

        /** The sources could not be read; nothing new was applied. */
        FAILED,
    }

    /**
     * The lineup built from the extra sources ALONE - the free-lineup shape,
     * where nothing occupies the primary slot.
     *
     * Every entry is treated as an extra, so its ids are namespaced exactly as
     * they are once a primary playlist appears: adding your own playlist later
     * must not renumber channels that favorites, zap history and remembered
     * sports picks already point at.
     */
    private suspend fun loadExtrasOnlyLineup(): IptvPlaylist {
        val entries = extraPlaylistEntries()
        check(entries.isNotEmpty()) { "No playlist sources are configured" }
        val channels = ArrayList<IptvChannel>()
        var fetched = 0
        entries.forEach { (url, name) ->
            val loaded = repository.loadPlaylist(url, name)
            fetched += loaded.channels.size
            channels += namespacedExtraChannels(url, loaded.channels)
        }
        check(fetched > 0) { "The playlist sources returned no channels" }
        return IptvPlaylist(
            name = entries.firstOrNull()?.second ?: FreeLineup.NAME,
            sourceUrl = entries.first().first,
            epgUrl = null,
            channels = channels.distinctBy { it.id }
        )
    }

    /**
     * The same lineup, read from the playlist CACHE instead of the network -
     * the cold-start path, where the free channels must come back without a
     * fetch. Null when no extra has a cached channel list yet.
     */
    private suspend fun loadCachedExtrasLineup(): IptvPlaylist? {
        val entries = extraPlaylistEntries()
        if (entries.isEmpty()) return null
        val channels = ArrayList<IptvChannel>()
        var found = false
        entries.forEach { (url, name) ->
            val cached = runCatchingCancellable {
                repository.loadCachedPlaylist(url, name)
            }.getOrNull() ?: return@forEach
            if (cached.channels.isEmpty()) return@forEach
            found = true
            channels += namespacedExtraChannels(url, cached.channels)
        }
        if (!found) return null
        return IptvPlaylist(
            name = entries.firstOrNull()?.second ?: FreeLineup.NAME,
            sourceUrl = entries.first().first,
            epgUrl = null,
            channels = channels.distinctBy { it.id }
        )
    }

    /**
     * Parses the extra-playlist textarea and merges every additional M3U
     * into [primary]'s channel list. Failures are non-fatal: a merged
     * source that cannot load logs and is skipped so the primary playlist
     * still comes up. Channel ids are namespaced by source URL to keep
     * dedupe/EPG-match keys stable across providers.
     */
    private suspend fun mergeWithExtraPlaylists(primary: IptvPlaylist): IptvPlaylist {
        val entries = extraPlaylistEntries()
        if (entries.isEmpty()) return primary

        var added = 0
        val extraChannelLists = mutableListOf<List<IptvChannel>>()
        for ((extraUrl, extraName) in entries) {
            if (extraUrl.equals(primary.sourceUrl?.trim(), ignoreCase = true)) {
                continue
            }
            try {
                val extra = repository.loadPlaylist(extraUrl, extraName)
                extraChannelLists += namespacedExtraChannels(extraUrl, extra.channels)
                added += extra.channels.size
            } catch (t: Throwable) {
                // Failures are non-fatal: the primary playlist still loads.
                if (t is CancellationException) throw t
                Log.w(
                    TAG,
                    "EXTRA PLAYLIST SKIPPED source=${Redaction.url(extraUrl)} " +
                        "error=${Redaction.text(t.message)}"
                )
            }
        }
        if (added == 0) return primary

        Log.d(TAG, "EXTRA PLAYLIST MERGE added=$added sources=${extraChannelLists.size}")
        return primary.copy(
            channels = (extraChannelLists + listOf(primary.channels))
                .flatten()
                .distinctBy { it.id },
            name = primary.name
        )
    }

    fun importGuide() {
        if (allEpgUrls().isEmpty()) {
            _guideError.value = "EPG URL is required"
            return
        }
        if (_isLoading.value) {
            _guideError.value = "Playlist loading is in progress. Please wait before importing EPG."
            return
        }
        // Always forces the attempt: this is the button the viewer presses
        // BECAUSE the last one wedged, so it ignores the stall cooldown and
        // cancels whatever pass is running, whichever path started it.
        //
        // And it forces a FRESH fetch (see launchGuideImport): a recovery press
        // that goes out with the stored validators gets the same `304` the
        // automatic refresh already got, which is the two-second no-op the
        // field report came from.
        launchGuideImport(forceFresh = true)
    }

    /**
     * Stops the running pass because the viewer asked - Playlist Setup's Cancel.
     *
     * Not a re-press: nothing is restarted, and the cancelled pass writes no
     * freshness marker, so the next attempt is decided by the ordinary
     * freshness rules rather than by this tap. What it replaces is the only
     * way out the setup screen used to have - force-stop the app - for a pass the
     * viewer could see running but could not stop (the field report: a six-source
     * import on a TV guest, with LOAD refused for its whole duration).
     *
     * The pass is cancelled through the SAME handle every path starts it on
     * ([launchGuideImport]), so an entry-, migration- or line-triggered import is
     * as cancellable as a button press. The cancelled state is published here, on
     * the viewer's behalf, BEFORE the coroutine unwinds: the pass's own `finally`
     * then finds its id already closed and leaves that line alone
     * (see [GuideImportProgress.finish]), which is what keeps a cancel from
     * reading as a silent return to zero. No row is lost either way - the importer
     * only promotes a source on a complete parse, so the live guide is exactly
     * what it was, and the in-flight source's staged rows are never promoted.
     */
    fun cancelGuideImport() {
        val job = guideImportJob ?: return
        if (!job.isActive) return
        val passId = guideImportPassId
        Log.i(TAG, "GUIDE IMPORT CANCELLED by the viewer")
        job.cancel()
        // The handle is deliberately KEPT: a press of IMPORT EPG right after a
        // cancel must cancel-and-join this pass before its replacement starts
        // (see launchGuideImport), and dropping the reference here would let the
        // two unwind at the same time over one status line.
        GuideImportProgress.finish(passId, GuideImportOutcome.Cancelled)
        _isImportingGuide.value = GuideImportProgress.isRunning
    }

    /**
     * Starts the one guide import, after stopping any pass already running.
     *
     * The cancelled pass is JOINED before the replacement starts rather than
     * left to unwind underneath it: its `finally` closes the shared
     * [GuideImportProgress] pass (which would blank the new pass's status line)
     * and its repository entry is only dropped once its fetch has actually let
     * go, so a replacement that started first would join an import that is
     * already dead and inherit its cancellation.
     */
    private fun launchGuideImport(forceFresh: Boolean = false) {
        val previous = guideImportJob
        previous?.cancel()
        guideImportJob = viewModelScope.launch {
            previous?.join()
            importGuideInternal(
                epgUrl = allEpgUrls().firstOrNull().orEmpty(),
                forceFresh = forceFresh
            )
        }
    }

    /**
     * Imports one EPG source, re-running it while the failure is the
     * profile-scoped guide database being swapped underneath the import.
     *
     * A running import holds a database connection for minutes, and the guide
     * database retires an instance across a profile change (or a tombstone
     * re-request). When that lands mid-import the batch write dies with Room's
     * "connection pool has been closed" / "already-closed object" / SQLITE_BUSY,
     * which surfaced as a permanent error banner and no guide. Re-running is
     * safe: the import stages every row under its own source key and only
     * promotes on a complete parse, and by a later attempt the database layer
     * has settled on one instance, so the attempt finishes the import.
     *
     * It used to retry exactly ONCE, which is not enough for a source whose
     * import is long enough to outlive more than one swap: the second failure
     * became the banner the user was left holding, with the guide still empty
     * on every entry. Only swap symptoms are retried, and only a bounded
     * number of times — a real failure (network, malformed guide) still fails
     * loudly on its first attempt.
     */
    private suspend fun importGuideSource(
        url: String,
        freshConnection: Boolean = false,
        forceFresh: Boolean = false
    ): GuideImportOutcome {
        return withDatabaseSwapRetry(
            attempts = DB_SWAP_RETRY_LONG_ATTEMPTS,
            onRetry = { attempt, error ->
                Log.w(
                    TAG,
                    "GUIDE IMPORT RETRY $attempt after database swap " +
                        "source=${Redaction.url(url)}",
                    Redaction.throwable(error)
                )
            }
        ) {
            // A first attempt shares whatever import is already running for
            // this URL (the worker's); the watchdog's retry must not, because
            // the attempt it replaces is the one it just aborted.
            repository.importGuide(
                epgUrl = url,
                joinActiveImport = !freshConnection,
                forceFresh = forceFresh
            )
        }
    }

    /**
     * A playlist load wrapped for the profile-scoped database swap.
     *
     * The load WRITES: it replaces every cached channel row in one transaction
     * ([IptvRepository.loadPlaylist] -> `replaceCachedPlaylistChannels`), so it
     * dies exactly the way the guide import used to when its instance is
     * retired underneath it - "attempt to re-open an already-closed object".
     * The guide import got a retry wrapper for that; the playlist paths did
     * not, which is why an automatic refresh could leave the setup panel
     * holding that exception while the cached playlist and the guide both kept
     * working. Same treatment, same over-sized budget.
     */
    private suspend fun loadPlaylistResilient(
        url: String,
        name: String?
    ): IptvPlaylist = withDatabaseSwapRetry(
        attempts = DB_SWAP_RETRY_LONG_ATTEMPTS,
        onRetry = { attempt, error ->
            Log.w(
                TAG,
                "PLAYLIST LOAD RETRY $attempt after database swap " +
                    "source=${Redaction.url(url)}",
                Redaction.throwable(error)
            )
        }
    ) {
        repository.loadPlaylist(url, name)
    }

    /**
     * Publishes a failed operation to its banner - unless the failure is a
     * database-swap symptom.
     *
     * A swap is the guide/playlist database being retired under running work,
     * not something the user can act on: the next attempt resolves a fresh
     * instance (see [withDatabaseSwapRetry]) and the cached playlist and guide
     * stay live. Reported anyway it read as
     *
     *   IllegalStateException: attempt to re-open an already-closed object:
     *     SQLiteDatabase: /data/.../<profile>.iptv_epg.db
     *
     * - a banner the user had to clear by hand while everything on the screen
     * worked. It is logged instead, so the race stays visible in logcat.
     */
    private fun reportFailure(
        target: MutableStateFlow<String?>,
        error: Throwable,
        prefix: String = ""
    ) {
        if (isTransientDatabaseError(error)) {
            Log.w(TAG, "IGNORED DATABASE SWAP FAILURE", error)
            return
        }
        target.value = prefix + buildMessage(error)
    }

    private suspend fun refreshIfNeeded(forceGuide: Boolean = false) {
        if (refreshJob?.isActive == true) {
            // A newly added guide line still gets its import: the pass already
            // running owns the playlist half, not this source.
            if (forceGuide) startGuideImportForNewSource()
            return
        }
        val playlistNeedsRefresh = isStale(KEY_PLAYLIST_UPDATED_AT, PLAYLIST_REFRESH_MS)
        // Where the stored window actually ENDS decides. The age gate below is
        // the secondary signal: it is what lets a guide sit 12 hours old
        // against a 20-hour window, and it says nothing at all about one whose
        // window has already run out (see GuideRefreshPolicy).
        val guideEndsAtMs = if (allEpgUrls().isNotEmpty()) guideDataEndsAt() else null
        publishGuideStatus(guideEndsAtMs)
        val guideUrgency = GuideRefreshPolicy.urgency(
            endsAtMs = guideEndsAtMs,
            nowMs = System.currentTimeMillis(),
            ageStale = isStale(KEY_EPG_UPDATED_AT, EPG_REFRESH_MS) || guideFileMoved()
        )
        // forceGuide: a NEW source line was just written and this profile has
        // not imported since, so the list it would match against has changed
        // (see startGuideImportForNewSource and the Free TV opt-in).
        val guideNeedsRefresh = allEpgUrls().isNotEmpty() &&
            (forceGuide || GuideRefreshPolicy.needsImport(guideUrgency))
        // A source the watchdog just failed stays quiet on ENTRY: the failed
        // run could not write KEY_EPG_UPDATED_AT, so every later entry saw a
        // stale guide and started another import, which wedged again. The
        // banner from that failure is what the viewer keeps seeing; the manual
        // button is how they ask for another attempt, and it skips this.
        // A newly written source line is not a provider that just wedged, so
        // the cooldown does not hold it back: an import already in flight is
        // the only reason to skip that one (§3.4).
        val guideCoolingDown = guideNeedsRefresh && !forceGuide && guideStalledRecently()
        // An import already in flight is not restarted by a staleness tick: the
        // guide is stale precisely BECAUSE that import has not finished, and
        // replacing it would restart a multi-minute download every half hour.
        // (This used to be implicit - the import shared the refresh job's
        // lifetime - and stays explicit now that it has a handle of its own.)
        // The flag half covers a pass the repository itself is running (the
        // worker's), which this ViewModel has no handle for.
        val guideBusy = guideImportJob?.isActive == true || _isImportingGuide.value
        val canImportGuide = guideNeedsRefresh && !guideCoolingDown && !guideBusy
        if (!playlistNeedsRefresh && !canImportGuide) return
        if (canImportGuide) {
            // The line the field check greps: what the window looked like and
            // how urgent it was. An entry import is never deferred by playback
            // (only the SAVE phase is paced), so URGENT means "the grid is
            // thinning right now" - import anyway.
            Log.i(
                TAG,
                "GUIDE ENTRY REFRESH urgency=$guideUrgency endsAt=$guideEndsAtMs " +
                    "forced=$forceGuide sources=${allEpgUrls().size}"
            )
        }
        refreshJob = viewModelScope.launch {
            if (playlistNeedsRefresh) {
                runCatchingCancellable { refreshPlaylistInBackground() }.onFailure { error ->
                    if (error is CancellationException) throw error
                    reportFailure(_error, error)
                    Log.e(TAG, "BACKGROUND PLAYLIST REFRESH FAILED", error)
                }
            }
            // On the shared guide handle, so the button can stop it - the
            // playlist half above keeps running on this job either way.
            if (canImportGuide) launchGuideImport()
        }
    }

    private fun guideStalledRecently(): Boolean =
        epgEntryImportCoolingDown(
            prefs.getLong(KEY_EPG_LAST_STALL_AT, 0L),
            System.currentTimeMillis()
        )

    /**
     * Starts a guide import because a NEW source line was just written - the
     * Free TV opt-in and the one-time source migrations (§3.4).
     *
     * Writing a guide line imports nothing by itself: the importer reads the
     * source LIST when a pass starts, so a list edit with no pass behind it
     * leaves the new feed's channels blank until somebody presses IMPORT GUIDE.
     * That is the gap that made "install Free TV and locals just fill in" false
     * even with the lines in place, on a profile whose guide was otherwise
     * fresh enough that the entry refresh correctly declined.
     *
     * An import already in flight IS the import - it is the same pass the
     * button would start, and it re-reads the list when it opens - so this does
     * not start a second one. Everything else about the pass is the ordinary
     * in-app import: no worker budget, progress shown on the setup screen.
     */
    private fun startGuideImportForNewSource() {
        if (allEpgUrls().isEmpty()) return
        if (_isImportingGuide.value || guideImportJob?.isActive == true) {
            Log.i(TAG, "GUIDE IMPORT for a new source skipped: a pass is already running")
            return
        }
        Log.i(TAG, "GUIDE IMPORT started for a newly added source sources=${allEpgUrls().size}")
        launchGuideImport()
    }

    /**
     * Republishes [guideStatus] from this profile's prefs and the window end.
     *
     * Called at the two moments the answer can change: an entry check (which
     * already read the window end to decide whether to import) and the end of
     * an import pass. Cheap on purpose - a pref read and a map walk.
     */
    private fun publishGuideStatus(endsAtMs: Long?) {
        val urls = allEpgUrls()
        val stamps = GuideSourceStamps.read(prefs)
        val newest = stamps.maxByOrNull { entry -> entry.value }
        _guideStatus.value = GuideRefreshStatus(
            endsAtMs = endsAtMs,
            lastGoodSource = newest?.key,
            lastGoodAtMs = newest?.value,
            failing = prefs.getBoolean(KEY_EPG_REFRESH_FAILING, false),
            sourceCount = urls.size
        )
    }

    private suspend fun refreshPlaylistInBackground() {
        val url = effectiveLineupUrl()
        val name = lineupLoadName()
        if (url.isBlank() || _isImportingGuide.value) return
        val refreshedPlaylist = if (_playlistUrl.value.isBlank()) {
            loadExtrasOnlyLineup()
        } else {
            loadPlaylistResilient(url, name)
        }
        applyPlaylist(refreshedPlaylist)
        markUpdated(KEY_PLAYLIST_UPDATED_AT)
        // applyPlaylist merged the extras in, so report what was applied.
        Log.d(
            TAG,
            "BACKGROUND PLAYLIST REFRESH END channels=" +
                "${_playlist.value?.channels?.size ?: refreshedPlaylist.channels.size}"
        )
    }

    private suspend fun importGuideInternal(
        epgUrl: String,
        forceFresh: Boolean = false
    ) {
        if (epgUrl.isBlank()) return
        _guideError.value = null
        val urls = allEpgUrls()
        // The pass opens FIRST and the flag mirrors it, so "an import is running"
        // and "here is how far it has got" are one fact rather than two that can
        // drift (see the field note on [isImportingGuide]).
        val passId = GuideImportProgress.begin(sourceCount = urls.size)
        guideImportPassId = passId
        _isImportingGuide.value = GuideImportProgress.isRunning
        // What this pass will say when it ends (see GuideImportProgress.finish):
        // "Imported N programs", "Guide unchanged on server" or the failure.
        // Null means the pass never got a usable answer to report, and the
        // status line clears exactly as before.
        var outcome: GuideImportOutcome? = null
        // Where the live guide's schedule actually stops, published with the
        // outcome: a finished import that says nothing landed must also say the
        // guide itself is frozen (see GuideImportProgress.withStaleness).
        var guideEndsAtMs: Long? = null
        try {
            var lastError: Throwable? = null
            var imported = 0
            val outcomes = mutableListOf<GuideImportOutcome>()
            for (url in urls) {
                GuideImportProgress.startSource(imported + 1)
                try {
                    // Watched: a source that stops sending data is aborted and
                    // re-opened once on a fresh connection, and a second stall
                    // is thrown as EpgStalledException - a banner the viewer
                    // can act on instead of a silent twenty minutes at zero.
                    // The URL is handed over so the watchdog can tell "queued
                    // behind someone else's import" from "reading": a queued
                    // source publishes nothing, and its clock must not run
                    // while it waits.
                    outcomes += epgImportWatchdog.runWithStallRetry(url) { attempt ->
                        importGuideSource(
                            url = url,
                            freshConnection = attempt > 0,
                            // The button's whole point survives the watchdog's
                            // retry: a retried recovery press is still a press.
                            forceFresh = forceFresh
                        )
                    }
                    imported++
                    // Per SOURCE, at once: Playlist Setup's "last imported
                    // from" line reads these, and a source that really landed
                    // must be credited even when a later one in the same pass
                    // fails (§3.6).
                    GuideSourceStamps.recordSuccess(prefs, url)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is EpgStalledException) {
                        // Read by refreshIfNeeded(): the entry stops
                        // re-attempting this provider for the cooldown window.
                        markUpdated(KEY_EPG_LAST_STALL_AT)
                    }
                    lastError = t
                    Log.e(TAG, "GUIDE IMPORT FAILED source=${Redaction.url(url)}", Redaction.throwable(t))
                }
            }
            if (imported == 0) {
                // Nothing landed: the window is unchanged, and the status line
                // has to show what it still is rather than go blank.
                guideEndsAtMs = guideDataEndsAt()
                lastError?.let { throw it }
                return
            }
            if (lastError != null) {
                // At least one source made it in; surface the failures as a
                // non-fatal warning but keep the successful guide data.
                reportFailure(
                    _guideError,
                    lastError,
                    prefix = "Some EPG sources failed: "
                )
                outcome = GuideImportOutcome.Failed(guideFailureLabel(lastError))
            } else {
                outcome = mergeOutcomes(outcomes)
            }
            // The freshness marker is stamped only when EVERY source imported.
            // It was unconditional ("at least one made it"), so one source
            // timing out left the failed source marked fresh for the whole
            // 12 h refresh window - its channels carried no programs until the
            // next scheduled pass, and `refreshIfNeeded` would not retry. A
            // partial run therefore leaves the marker alone: the next pass
            // re-imports the set, which costs a re-download but is the only way
            // the missing source gets another answer.
            if (lastError == null) {
                markUpdated(KEY_EPG_UPDATED_AT)
                markGuideFile()
            } else {
                Log.w(
                    TAG,
                    "GUIDE IMPORT PARTIAL: freshness marker left alone so the failed source retries"
                )
            }
            guideEndsAtMs = guideDataEndsAt()
            val playlist = _playlist.value
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = playlist,
                    guideUrls = urls,
                    refreshTick = _guideRefreshTick.value + 1
                ),
                // The import swapped its rows in atomically (see
                // XmltvImporter), so what is on screen is still a real guide -
                // keep it while the window is re-requested rather than
                // blanking every row.
                keepItems = true
            )
            _guideRefreshTick.value += 1
            requestInitialGuideWindow()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportFailure(_guideError, t)
            outcome = GuideImportOutcome.Failed(guideFailureLabel(t))
            guideEndsAtMs = guideDataEndsAt()
            Log.e(TAG, "GUIDE IMPORT FAILED", t)
        } finally {
            // Scoped to THIS pass: a pass that was stopped (Cancel, a restart, a
            // profile switch) reaches here with a newer pass possibly already
            // open, and it must not blank that one's progress.
            GuideImportProgress.finish(passId, outcome, guideEndsAtMs)
            if (guideImportPassId == passId) guideImportPassId = 0L
            // Mirrored, not asserted: a newer pass still running is still true.
            _isImportingGuide.value = GuideImportProgress.isRunning
            // The pass just moved the window (or failed to): the setup screen's
            // freshness line is republished from what it left behind.
            publishGuideStatus(guideEndsAtMs)
        }
    }

    /**
     * The newest program end the live guide holds, or null when it holds none
     * (or the read itself fails - the status line then simply omits the fact).
     */
    private suspend fun guideDataEndsAt(): Long? =
        runCatching { repository.newestGuideProgramEndMs() }.getOrNull()

    /**
     * N sources' outcomes as the one line the status shows.
     *
     * A pass is one action to the viewer, so anything that actually imported
     * wins (with the total it stored); a pass where every source answered
     * `304` reports that instead, with the counts that satisfied them; and a
     * pass whose only answers were empty feeds says so - "unchanged" would be
     * a lie there, because the server's answer was a new, empty body.
     */
    private fun mergeOutcomes(outcomes: List<GuideImportOutcome>): GuideImportOutcome? {
        val imported = outcomes.filterIsInstance<GuideImportOutcome.Imported>()
        if (imported.isNotEmpty()) {
            return GuideImportOutcome.Imported(imported.sumOf { it.programs })
        }
        val unchanged = outcomes.filterIsInstance<GuideImportOutcome.Unchanged>()
        if (unchanged.isNotEmpty()) {
            return GuideImportOutcome.Unchanged(
                channels = unchanged.sumOf { it.channels },
                programsInWindow = unchanged.sumOf { it.programsInWindow }
            )
        }
        if (outcomes.any { it is GuideImportOutcome.EmptyFeedKeptLive }) {
            return GuideImportOutcome.EmptyFeedKeptLive
        }
        return null
    }

    /**
     * The failure in the viewer's words.
     *
     * The HTTP code when there is one - `Guide download failed: HTTP 404` is
     * the field case that started this (a provider answering 404 for any URL
     * but the exact signed one), and it is the only part of the provider's
     * answer the viewer can act on. Anything else keeps the message, so a stall
     * or a parse failure still reads as itself rather than as "failed".
     */
    private fun guideFailureLabel(error: Throwable): String {
        val message = error.message.orEmpty()
        val httpCode = Regex("HTTP (\\d{3})").find(message)?.value
        return when {
            httpCode != null -> "Guide download failed: $httpCode"
            message.isNotBlank() -> "Guide download failed: " + message.take(120)
            else -> "Guide download failed: " + error.javaClass.simpleName
        }
    }

    /**
     * Applies a freshly obtained playlist to the guide.
     *
     * Runs on [Dispatchers.Default] deliberately. The extra-playlist merge is
     * a network load plus a list concat, and the two fingerprint comparisons
     * below walk the whole channel list — a provider playlist is a 10k+ channel
     * corpus of strings. This used to run on the caller's dispatcher, which
     * through viewModelScope means the MAIN thread, so a cached restore during
     * app startup blocked frames for the duration of both passes.
     */
    private suspend fun applyPlaylist(newPlaylist: IptvPlaylist) =
        withContext(Dispatchers.Default) {
            // Merged here rather than at the call sites so the concat (and the
            // extra playlists' own loads) happen off the main thread too. With
            // no primary playlist the caller already built the extras into the
            // lineup ([loadExtrasOnlyLineup] / [loadCachedExtrasLineup]): those
            // channels are ALREADY namespaced, so merging again would fetch
            // them a second time and namespace the prefix twice.
            val merged = if (_playlistUrl.value.isBlank()) {
                newPlaylist
            } else {
                mergeWithExtraPlaylists(newPlaylist)
            }
            val guideUrls = allEpgUrls()
            // A background playlist refresh usually returns the same channels, and
            // resetting the guide for it re-ran the entire lineup query (snapshot
            // rebuild + every program batch) for rows that could not have differed.
            // Compare the slice the query actually reads before deciding.
            // The SAME fixed slice requestInitialGuideWindow() asks for - not
            // _guideChannelIds, which only ever GROWS as the viewer scrolls
            // (updateGuideChannels adds ids). A fingerprint stored at load time
            // (the initial 80 ids) could therefore never match one computed
            // later with a few hundred, so after any scrolling the "playlist
            // unchanged" fast path below was dead: every 6 h background refresh
            // paid the full guide reset and lineup re-query it exists to avoid.
            val fingerprintIds = merged.channels.asSequence()
                .filterNot { it.id in _hiddenChannelIds.value }
                .take(INITIAL_GUIDE_WINDOW_SIZE)
                .map { it.id }
                .toSet()
            val fingerprint = guideWindowFingerprint(
                sourceUrl = merged.sourceUrl,
                guideUrls = guideUrls,
                channelIds = fingerprintIds,
                channels = merged.channels
            )
            val guideStillValid = fingerprint != null && fingerprint == loadedGuideFingerprint

            _playlist.value = merged
            removeMissingHiddenChannelIds(merged)

            if (guideStillValid) {
                Log.w(TAG, "PLAYLIST APPLIED keeping loaded guide window (channels unchanged)")
                return@withContext
            }

            // Precompute the sourceKey using refreshTick + 1 BEFORE bumping the tick,
            // so loadedGuideSourceKey already matches once lineupSource re-evaluates.
            // This is what stops observeGuideRequest() from seeing a mismatch and
            // wiping guide state again right after we just populated it.
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = merged,
                    guideUrls = guideUrls,
                    refreshTick = _guideRefreshTick.value + 1
                ),
                // A background playlist reload lands here. The channels the
                // guide already shows are overwhelmingly the ones it will show
                // again, so blanking them first is what turned a silent refresh
                // into a visible "back to loading" stall.
                keepItems = true
            )
            _guideRefreshTick.value += 1
            requestInitialGuideWindow()
            loadedGuideFingerprint = guideWindowFingerprint(
                sourceUrl = merged.sourceUrl,
                guideUrls = guideUrls,
                // Same fixed slice as above, or the two reads can never agree.
                channelIds = fingerprintIds,
                channels = merged.channels
            )
        }

    private fun mergeGuideItems(lineup: List<IptvChannelWithEpg>) {
        val existing = _guideItemsByChannelId.value
        val updated = HashMap(existing)
        var changed = false
        lineup.forEach { item ->
            if (updated[item.channel.id] != item) {
                updated[item.channel.id] = item
                changed = true
            }
        }
        if (changed) _guideItemsByChannelId.value = updated
    }

    /**
     * Drops the guide's request bookkeeping, and - unless [keepItems] - the
     * loaded rows too.
     *
     * [keepItems] is for the REFRESH paths (a background playlist reload, an
     * EPG import finishing). Those re-request the window and overwrite the rows
     * as the new query lands, so dropping the rows first only blanked a guide
     * that was already correct: every row flipped back to "Loading guide…" for
     * as long as the rebuild took, which on a large guide is minutes - exactly
     * the "it is populated, then it goes back to loading for a minute" report.
     * The rows for channels the new playlist no longer carries simply stop
     * being drawn (mergePlaylistWithGuide reads the live playlist), so keeping
     * them can only ever show a program that is about to be replaced.
     *
     * The HARD paths (a changed source URL, a profile switch) still drop the
     * rows: there the old programs belong to a different guide entirely and
     * must not be shown for a moment.
     */
    private fun clearGuideMemory(sourceKey: String? = null, keepItems: Boolean = false) {
        if (!keepItems) _guideItemsByChannelId.value = emptyMap()
        _guideChannelIds.value = emptySet()
        _pendingGuideChannelIds.value = emptySet()
        // A new source, import or playlist can hold programs a run-out channel
        // did not have, so nothing stays written off as exhausted.
        exhaustedGuideChannelIds = emptySet()
        loadedGuideSourceKey = sourceKey
        // Nothing is loaded any more, so there is no fingerprint to compare a
        // subsequent playlist refresh against (null forces the full reset).
        loadedGuideFingerprint = null
    }

    private fun removeMissingHiddenChannelIds(playlist: IptvPlaylist) {
        val validChannelIds = playlist.channels.asSequence().map { it.id }.toSet()
        // Never prune against a playlist that carries no channels: an empty or
        // half-parsed load would otherwise delete every hidden channel locally
        // and push that deletion to the other devices.
        if (validChannelIds.isEmpty()) return
        val cleanedIds = _hiddenChannelIds.value.intersect(validChannelIds)
        if (cleanedIds != _hiddenChannelIds.value) {
            _hiddenChannelIds.value = cleanedIds
            saveHiddenChannelIds()
        }
    }

    private fun saveHiddenChannelIds() {
        prefs.edit().putStringSet(KEY_HIDDEN_CHANNEL_IDS, _hiddenChannelIds.value).apply()
        // Sync push: hiding channels must cross devices immediately instead
        // of riding the next unrelated iptv_config flush.
        val appCtx = app.applicationContext
        com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
            appCtx,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_IPTV,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildIptv(appCtx)
        )
    }

    private fun buildGuideSourceKey(
        playlist: IptvPlaylist?,
        guideUrls: List<String>,
        refreshTick: Int
    ): String =
        "${playlist?.sourceUrl}|${guideUrls.joinToString(",")}|$refreshTick"

    /**
     * True when the guide the app would read is not the one the freshness marker
     * was written about.
     *
     * A profile whose playlist is shared with another profile reads a guide
     * named after the playlist instead of after itself (see
     * [com.kennyb1201.kbstream.data.iptv.GuideFiles]), and the file that name
     * points at is created empty the first time it is opened — so without this
     * the marker would keep saying the guide is fresh while the guide has no
     * rows in it. The cost is the one-time re-import that sharing a guide file
     * is documented to cost, paid on the next visit to Live TV instead of up to
     * a refresh interval later; after that import the marker records the file it
     * wrote and this stops firing.
     */
    private fun guideFileMoved(): Boolean =
        !com.kennyb1201.kbstream.data.iptv.markerMatchesGuideFile(
            recordedName = prefs.getString(KEY_EPG_DB_NAME, null),
            activeName =
                com.kennyb1201.kbstream.data.iptv.db.IptvDatabase.activeFileName(app),
            profileId = com.kennyb1201.kbstream.data.sync.ProfileStorage.activeProfileId(app)
        )

    /**
     * Records the guide file [markUpdated] has just marked fresh, so a later
     * check can tell whether the marker is about the file being read
     * ([guideFileMoved]).
     */
    private fun markGuideFile() {
        prefs.edit()
            .putString(
                KEY_EPG_DB_NAME,
                com.kennyb1201.kbstream.data.iptv.db.IptvDatabase.activeFileName(app)
            )            .apply()
    }

    private fun isStale(key: String, maxAgeMs: Long): Boolean {
        val updatedAt = prefs.getLong(key, 0L)
        return updatedAt == 0L || System.currentTimeMillis() - updatedAt >= maxAgeMs
    }

    private fun markUpdated(key: String) {
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    private fun buildPlaylistOnlyLineup(playlist: IptvPlaylist): List<IptvChannelWithEpg> =
        playlist.channels.map(::playlistOnlyItem)

    private fun mergePlaylistWithGuide(
        playlistOnly: List<IptvChannelWithEpg>,
        guideByChannelId: Map<String, IptvChannelWithEpg>
    ): List<IptvChannelWithEpg> = playlistOnly.map { item ->
        guideByChannelId[item.channel.id] ?: item
    }

    private fun playlistOnlyItem(channel: IptvChannel): IptvChannelWithEpg =
        IptvChannelWithEpg(
            channel = channel,
            epgChannel = null,
            epgMatchType = EpgMatchType.NO_MATCH,
            now = null,
            next = null,
            upcoming = emptyList()
        )

    /**
     * A message for the SCREEN, not a log header.
     *
     * This used to lead with the exception's class name, so the stall chip the
     * viewer reads on the TV said "EpgStalledException: EPG download stalled: no
     * data for 3 minutes" — the failure's own text is already written for a
     * viewer, and the class name told them nothing they could act on. Only a
     * throwable with nothing to say falls back to its type.
     */
    private fun buildMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName

    private fun saveInputs() {
        prefs.edit()
            .putString(KEY_PLAYLIST_URL, _playlistUrl.value)
            .putString(KEY_EPG_URL, _epgUrl.value)
            .putString(KEY_EXTRA_EPG_URLS, _extraEpgUrls.value)
            .putString(KEY_PLAYLIST_NAME, _playlistName.value)
            .putString(KEY_EXTRA_PLAYLIST_URLS, _extraPlaylistUrls.value)
            .apply()

        // Cross-device sync: share the IPTV source config.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_IPTV,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildIptv(appContext)
            )
        }
    }

    private data class GuideRequest(
        val playlist: IptvPlaylist?,
        val guideUrls: List<String>,
        val refreshTick: Int,
        val clockTick: Int,
        val channelIds: Set<String>
    )

    private companion object {
        const val TAG = "IptvViewModel"

        /**
         * What the catch-up dialog says when the read FAILED, as opposed to
         * finding nothing (see [_catchupError]). Actionable, because a failed
         * provider is usually a connection or a source that is down.
         */
        const val CATCHUP_FAILED_LABEL =
            "Couldn't load this channel's catch-up — the guide source didn't answer. " +
                "Check your connection and try again."

        /** Substrings that mark a channel/group as plausibly kid-focused. */
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_EXTRA_EPG_URLS = "extra_epg_urls"
        const val KEY_PLAYLIST_NAME = "playlist_name"
        const val KEY_EXTRA_PLAYLIST_URLS = "extra_playlist_urls"
        const val KEY_PLAYLIST_APPLIED_URL = "playlist_applied_url"
        const val KEY_HIDDEN_CHANNEL_IDS = "hidden_channel_ids"
        const val KEY_PLAYLIST_UPDATED_AT = "playlist_updated_at"

        /**
         * Whether this profile's cached Free TV lineup has been through the
         * US-only filter. Device-local and profile-scoped: it is about the rows
         * in THIS profile's cache, not a preference that travels.
         */
        const val KEY_FREE_TV_US_FILTER_APPLIED = "free_tv_us_filter_applied"

        /**
         * Whether this profile has been offered the Free TV LOCALS guide.
         *
         * Device-local and profile-scoped, beside the US-filter flag and for
         * the same reason: it records what happened to THIS profile's source
         * list, not a preference that travels. Once set, the one-time migration
         * never re-adds a locals line the viewer deleted.
         */
        const val KEY_FREE_TV_LOCALS_EPG_APPLIED = "free_tv_locals_epg_applied"

        /**
         * Whether this profile has been offered the Free TV FAST platform
         * guides (Pluto, Samsung, Plex, Roku).
         *
         * Device-local and profile-scoped, beside the locals flag and for the
         * same reason: it records what happened to THIS profile's source list,
         * not a preference that travels - and once set, the one-time migration
         * never re-adds a FAST line the viewer deleted.
         */
        const val KEY_FREE_TV_FAST_EPG_APPLIED = "free_tv_fast_epg_applied"

        /**
         * The Xumo guide ADAPTER's per-profile one-time flag.
         *
         * A sibling of the two migration flags above, but with a difference
         * that matters: those record "the lines are in the field now", and this
         * one records "this profile owns the adapter at all" - because there is
         * no line whose presence could serve as the record.
         */
        const val KEY_FREE_TV_XUMO_EPG_APPLIED = FreeLineup.XUMO_EPG_APPLIED_KEY
        const val KEY_EPG_UPDATED_AT = "epg_updated_at"

        /**
         * When the stall watchdog last gave up on a guide source. Written only
         * on a loud stall failure (see [EpgStalledException]) and read only by
         * [guideStalledRecently] - profile-scoped, in the same store as the
         * freshness marker it stands in for while the provider is broken.
         */
        const val KEY_EPG_LAST_STALL_AT = "epg_last_stall_at"

        /**
         * The guide FILE [KEY_EPG_UPDATED_AT] was written about. Read only
         * together with [guideFileMoved]: a marker about a different file than
         * the app would open is a guide that has not been re-imported under its
         * new name yet.
         */
        const val KEY_EPG_DB_NAME = "epg_db_name"

        /**
         * Whether the background guide refresh has hit its bound of empty
         * passes, written by `EpgRefreshWorker` and read by the setup screen's
         * status line (§3.6).
         *
         * The schedule is never cancelled for it (that was the bug: a week of
         * failures used to end the periodic work), so this is the honest state
         * a viewer can act on rather than a silent stop.
         */
        const val KEY_EPG_REFRESH_FAILING = "epg_refresh_failing"
        const val STOP_TIMEOUT_MS = 5_000L
        const val INITIAL_GUIDE_WINDOW_SIZE = 80
        const val MAX_GUIDE_CHANNELS_PER_REQUEST = 80

        // Was 240 -- too small a shared budget for a full 80-channel guide
        // load. IptvRepository targets 12 programs/channel, so a full
        // batch wants up to 80*12=960; anything less than that causes
        // loadProgramsChunked() to exhaust its budget partway through the
        // channel list and skip programs for whichever channels come
        // later in the batch order, even though their own schedules have
        // plenty of programs in the window. This covers a full request
        // with headroom.
        const val VISIBLE_GUIDE_PROGRAM_LIMIT = 960

        // Both of these are the READ half of [EpgWindow]'s contract: what the
        // grid, the detail panel and the guide search ask for. They live
        // there now so the import window cannot drift away from them again —
        // it was storing six times this window, in every profile's own guide.
        const val GUIDE_PAST_WINDOW_MS = EpgWindow.READ_PAST_MS

        // The future half. 8 hours comfortably covers 4 back-to-back ~2-hour
        // blocks so the "coming up" row (which only shows 4) has something to
        // fill it regardless of how long each channel's program blocks run.
        // This was once 2 hours, which was too narrow for exactly that; going
        // the other way (storing far beyond it) is what the guide database
        // paid for.
        const val GUIDE_FUTURE_WINDOW_MS = EpgWindow.READ_FUTURE_MS
        const val PLAYLIST_REFRESH_MS = 6 * 60 * 60 * 1000L

        /**
         * The AGE half of the entry refresh, and since guide windows decide
         * first it is the SECONDARY signal (see [GuideRefreshPolicy]): it only
         * has to catch what window end cannot - a guide that holds nothing at
         * all, and a partial pass that left one source behind while its
         * siblings kept the window ahead.
         */
        const val EPG_REFRESH_MS = 12 * 60 * 60 * 1000L

        // First catch-up refresh after the guide pipeline starts, and the
        // recurring cadence for recomputing now/next with a fresh clock.
        const val GUIDE_CLOCK_FIRST_REFRESH_MS = 15_000L
        const val GUIDE_CLOCK_REFRESH_INTERVAL_MS = 2 * 60 * 1000L

        /**
         * Guide freshness is re-checked every 15th clock tick, i.e. every
         * 30 minutes. The check is one window-end read plus a pref read, and it
         * no-ops while the guide's schedule still reaches past
         * [GuideRefreshPolicy.LEAD_MS]; the worker normally refreshes it first,
         * so this fires roughly never - it is the safety net for a process that
         * stays up (and for a worker Android deferred), which is exactly the
         * TV that used to sit on a guide that had aged out from under it.
         */
        const val GUIDE_STALENESS_CHECK_EVERY_TICKS = 15
    }
}
