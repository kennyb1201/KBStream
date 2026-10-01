package com.kennyb1201.kbstream.ui.kb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts
import com.kennyb1201.kbstream.data.kb.KBCollectionProfile
import com.kennyb1201.kbstream.data.kb.chipKey
import com.kennyb1201.kbstream.data.kb.KBHomeOrder
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.KBRepository
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.BrowseShortcutArt
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How many browse-shortcut art lookups run at once on a cold row. */
private const val BROWSE_ART_CONCURRENCY = 3

/**
 * Home-side state: imported KB COLLECTIONS (each renders as a titled row
 * of folder tiles on Home) plus the user's rail arrangement for
 * interleaving with addon catalog rails. Cache-first so returning Home is
 * instant.
 */
class KBHomeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val collections: List<KBCollectionProfile> = emptyList(),
        val arrangement: KBHomeOrder = KBHomeOrder(),
        /** Browse chips mirrored to Home, rendered as one shared row. */
        val browseShortcuts: List<BrowseHomeShortcut> = emptyList(),
        /**
         * Resolved tile/hero artwork per browse shortcut, keyed by
         * [BrowseHomeShortcut.chipKey]. Filled in after the row has already
         * drawn, so a tile that has no art yet (or none at all) keeps its name
         * instead of holding the rail back.
         */
        val browseShortcutArt: Map<String, BrowseShortcutArt> = emptyMap(),
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val repository = KBRepository.getInstance(application)

    /**
     * Session memo of resolved artwork, so a Home resume republishes the tiles
     * it already knows instead of re-walking TMDB for every shortcut.
     */
    private val browseArtCache = mutableMapOf<String, BrowseShortcutArt>()

    private var browseArtJob: Job? = null

    init {
        load()
        observeProfileSwitches()
    }

    /**
     * Cache-first load; re-run on Home resume to pick up manager edits.
     *
     * The result is dropped when the active profile changed while the read
     * was in flight: the collection list and the rail arrangement are
     * profile-scoped prefs, so publishing a stale read would repaint the
     * profile the user just left over the new one.
     */
    fun load() {
        val requestedProfileId =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
        viewModelScope.launch {
            val arrangement = KBHomeOrderPrefs.get(getApplication())
            // The Browse row is part of the same arrangement Home renders, so
            // it is read in the same pass: adding a chip on Search and coming
            // back to Home has to show the tile without a second refresh path.
            val browseShortcuts = BrowseHomeShortcuts.list(getApplication())
            val collections = runCatchingCancellable { repository.loadProfiles() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.pinToTop }
            if (
                requestedProfileId !=
                com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
            ) {
                return@launch
            }
            _state.value = _state.value.copy(
                arrangement = arrangement,
                collections = collections,
                browseShortcuts = browseShortcuts,
                isLoading = false
            )
            // After the row is on screen, not before it: the tiles must not
            // wait on TMDB to appear.
            resolveBrowseShortcutArt(browseShortcuts)
        }
    }

    /**
     * Resolves the artwork for the Browse row's tiles in the background, one
     * shortcut at a time as each answer lands.
     *
     * The work is a single discover page (plus a brand logo, for a service or
     * studio) per shortcut - see [TmdbRepository.getBrowseShortcutArt] - and it
     * is deliberately NOT awaited by [load]: Home's first frame draws the name
     * tiles and each one upgrades to its backdrop (and, for a service or
     * studio, its brand mark) when its art arrives.
     */
    private fun resolveBrowseShortcutArt(
        shortcuts: List<BrowseHomeShortcut>
    ) {
        browseArtJob?.cancel()

        // Publish whatever the session already resolved before starting new
        // work, so a resume does not flash the name fallback over a tile whose
        // art is sitting in the cache.
        if (browseArtCache.isNotEmpty()) {
            _state.value = _state.value.copy(
                browseShortcutArt = browseArtCache.toMap()
            )
        }

        val missing = shortcuts.filter { it.chipKey() !in browseArtCache }
        if (missing.isEmpty()) return

        val requestedProfileId =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id

        browseArtJob = viewModelScope.launch {
            val tmdb = TmdbRepository.getInstance(getApplication())
            val semaphore = Semaphore(BROWSE_ART_CONCURRENCY)

            missing.forEach { shortcut ->
                launch {
                    semaphore.withPermit {
                        val art = runCatchingCancellable {
                            tmdb.getBrowseShortcutArt(
                                categoryKey = shortcut.categoryKey,
                                entryId = shortcut.id,
                                providerId = shortcut.providerId,
                                networkOrCompanyId = shortcut.networkOrCompanyId,
                                networkIsCompany = shortcut.networkIsCompany,
                                // The chip's label and its production company:
                                // the two things the brand-logo fallbacks
                                // need, and the chip already carries both.
                                name = shortcut.name,
                                originalsCompanyId = shortcut.originalsCompanyId
                            )
                        }.getOrNull() ?: return@withPermit

                        // A profile switch clears the row and resets the
                        // state; a late answer must not paint the outgoing
                        // profile's tile into the incoming one.
                        if (
                            requestedProfileId !=
                            com.kennyb1201.kbstream.data.sync.ProfileManager
                                .activeProfile.value?.id
                        ) {
                            return@withPermit
                        }

                        browseArtCache[shortcut.chipKey()] = art
                        _state.value = _state.value.copy(
                            browseShortcutArt = browseArtCache.toMap()
                        )
                    }
                }
            }
        }
    }

    /**
     * Profile-switch isolation: KB collection rails are interleaved with the
     * addon rails on Home, so the previous profile's collections must be
     * dropped the moment the profile changes — Home otherwise interleaves
     * them with the incoming profile's rails until this reload lands (the
     * "old rails flash" right after a switch).
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var appliedProfileId =
                com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collect { profile ->
                    val id = profile?.id
                    if (id == appliedProfileId) return@collect
                    appliedProfileId = id
                    _state.value = UiState()
                    load()
                }
        }
    }
}
