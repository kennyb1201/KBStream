package com.kennyb1201.kbstream.ui.publiclists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListDirectory
import com.kennyb1201.kbstream.data.publiclists.PublicListRules
import com.kennyb1201.kbstream.data.publiclists.PublicListSaveOutcome
import com.kennyb1201.kbstream.data.publiclists.PublicListTitle
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import com.kennyb1201.kbstream.data.publiclists.PublicListsAnswer
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One level of the browse surface; the stack of these is the Back path. */
sealed interface PublicListLevel {

    /** A directory's cards (the tab bar switches which directory this is). */
    data class Directory(val directory: PublicListDirectory) : PublicListLevel

    data object Search : PublicListLevel

    /** One list, with its titles and its save action. */
    data class Detail(val card: PublicListCard) : PublicListLevel
}

/** What the detail screen knows about a list's titles. */
sealed interface PublicListTitlesState {
    data object Loading : PublicListTitlesState

    data class Titles(val rows: List<PublicListTitle>) : PublicListTitlesState

    data class Failed(val message: String) : PublicListTitlesState
}

/** One card's save, as the card draws it. */
sealed interface PublicListSavePhase {
    data object Saving : PublicListSavePhase

    /** [already] is the second-save answer ("Already in your lists"). */
    data class Done(val message: String, val already: Boolean = false) : PublicListSavePhase

    data class Failed(val message: String) : PublicListSavePhase
}

/** The states that stop the surface before a request: each has its own prompt. */
enum class PublicListGate {
    /** No account for this vendor on this profile: connecting is the answer. */
    NOT_CONNECTED,

    /** MDBList's daily allowance is gone: browsing would spend nothing it has. */
    ALLOWANCE_SPENT
}

/**
 * The browse-and-save surface's whole state.
 *
 * One state object for the reason the BingeCat browser has one: the surface has
 * mutually exclusive shapes (gate / directory / search / detail) and a
 * half-updated pair of flags is how a save button ends up drawn over a screen
 * that is not connected.
 */
data class PublicListsUiState(
    val vendor: PublicListVendor = PublicListVendor.TRAKT,
    val levels: List<PublicListLevel> = emptyList(),
    val gate: PublicListGate? = null,
    val directory: PublicListDirectory = PublicListDirectory.TRAKT_POPULAR,
    val cards: List<PublicListCard> = emptyList(),
    val isLoadingCards: Boolean = false,
    val cardsError: String? = null,
    val searchQuery: String = "",
    val searchResults: List<PublicListCard> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val titles: PublicListTitlesState = PublicListTitlesState.Loading,
    val savePhases: Map<String, PublicListSavePhase> = emptyMap(),
    val notice: String? = null
) {
    /** The level being drawn. Falls back to the active directory. */
    val level: PublicListLevel
        get() = levels.lastOrNull() ?: PublicListLevel.Directory(directory)

    /** Whether Back has somewhere to go inside the surface. */
    val canGoBack: Boolean get() = levels.size > 1

    /** The tabs this vendor has, in the spec's order. */
    val directories: List<PublicListDirectory>
        get() = PublicListDirectory.forVendor(vendor)
}

/**
 * Browsing and saving public lists, for whichever vendor opened the surface.
 *
 * Three rules shape it, and all three are the spec's:
 *
 *  - **Gates before requests.** No account is a PROMPT, and nothing is called;
 *    a spent MDBList allowance is its own prompt rather than a retryable error.
 *  - **A save is a like.** One write, the list stays live, and a second save is
 *    "Already in your lists" - never an error, never a second write.
 *  - **Titles are only shown when a read produced them.** A list whose titles
 *    the provider will not serve says so instead of drawing an empty grid.
 */
class PublicListsViewModel(app: Application) : AndroidViewModel(app) {

    private val traktRepository = TraktRepository.getInstance(app.applicationContext)

    /** MDBList is an object, not an injected client; kept for symmetry. */
    private val context = app.applicationContext

    /** The rating ceiling's source - the same filter Library and the rails use. */
    private val tmdbRepository = TmdbRepository.getInstance(app.applicationContext)

    private val _uiState = MutableStateFlow(PublicListsUiState())
    val uiState: StateFlow<PublicListsUiState> = _uiState.asStateFlow()

    /**
     * Which profile's screen this state describes; bumped by every switch.
     *
     * A session, an API key, the ceiling and the liked set are all per KBStream
     * profile, so a read that started under the previous profile must not land on
     * the new one. Cancelling is cooperative, so every read checks this before
     * publishing.
     */
    private var profileGeneration = 0

    /** One counter per read surface (see the same rule in the BingeCat VM). */
    private var cardsGeneration = 0
    private var searchGeneration = 0
    private var titlesGeneration = 0

    init {
        observeProfileSwitches()
    }

    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            ProfileManager.activeProfile.collect {
                if (first) {
                    first = false
                    return@collect
                }
                profileGeneration++
                val vendor = _uiState.value.vendor
                _uiState.value = initialState(vendor)
                loadDirectory(_uiState.value.directory)
            }
        }
    }

    private fun initialState(vendor: PublicListVendor): PublicListsUiState {
        val first = PublicListDirectory.forVendor(vendor).first()
        return PublicListsUiState(
            vendor = vendor,
            levels = listOf(PublicListLevel.Directory(first)),
            directory = first
        )
    }

    /**
     * Enters the surface for [vendor].
     *
     * Called from the screen's launch effect with the route's vendor, so the
     * activity-scoped ViewModel (kept across a Detail round-trip, exactly like
     * the BingeCat browser's) rebuilds its state when the OTHER vendor is opened.
     */
    fun open(vendor: PublicListVendor) {
        val current = _uiState.value
        if (current.vendor == vendor && current.levels.isNotEmpty()) {
            // Already open for this vendor: a returning Detail trip must not
            // reset the level the viewer left.
            return
        }
        _uiState.value = initialState(vendor)
        loadDirectory(_uiState.value.directory)
    }

    /** One Back: up a level. At the root the screen leaves the surface. */
    fun back() {
        val state = _uiState.value
        if (!state.canGoBack) return
        _uiState.value = state.copy(
            levels = state.levels.dropLast(1),
            cardsError = null,
            searchError = null,
            notice = null
        )
    }

    /** The tab bar: one directory's cards, replacing the current level. */
    fun selectDirectory(directory: PublicListDirectory) {
        if (directory.vendor != _uiState.value.vendor) return
        _uiState.value = _uiState.value.copy(
            directory = directory,
            levels = listOf(PublicListLevel.Directory(directory)),
            cards = emptyList(),
            cardsError = null,
            notice = null
        )
        loadDirectory(directory)
    }

    // ── Directories ──────────────────────────────────────────────────────

    private fun connected(): Boolean = when (_uiState.value.vendor) {
        PublicListVendor.TRAKT ->
            traktRepository.isConfigured() && traktRepository.hasToken()

        PublicListVendor.MDBLIST -> MdbListClient.isConfigured(context)
    }

    /** Paints the cached directory, then refetches it (spec F2's SWR). */
    private fun loadDirectory(directory: PublicListDirectory) {
        val vendor = _uiState.value.vendor
        if (!connected()) {
            _uiState.value = _uiState.value.copy(
                gate = PublicListGate.NOT_CONNECTED,
                isLoadingCards = false,
                cardsError = null
            )
            return
        }
        val cached = when (vendor) {
            PublicListVendor.TRAKT ->
                traktRepository.cachedPublicLists(directory, allowStale = true)

            PublicListVendor.MDBLIST ->
                MdbListClient.cachedPublicLists(context, directory, allowStale = true)
        }
        _uiState.value = _uiState.value.copy(
            gate = null,
            cards = cached ?: _uiState.value.cards,
            isLoadingCards = cached == null,
            cardsError = null
        )
        val generation = ++cardsGeneration
        val profile = profileGeneration
        viewModelScope.launch {
            val answer = when (vendor) {
                PublicListVendor.TRAKT -> traktRepository.publicLists(directory)
                PublicListVendor.MDBLIST -> MdbListClient.publicLists(context, directory)
            }
            if (generation != cardsGeneration || profile != profileGeneration) return@launch
            applyCards(answer)
        }
    }

    private fun applyCards(answer: PublicListsAnswer<List<PublicListCard>>) {
        val vendor = _uiState.value.vendor
        when (answer) {
            is PublicListsAnswer.Loaded ->
                _uiState.value = _uiState.value.copy(
                    cards = answer.value,
                    isLoadingCards = false,
                    cardsError = null
                )

            is PublicListsAnswer.Failed ->
                // The cached cards stay on screen; this line says why they may
                // be old (spec F2: never a blank screen).
                _uiState.value = _uiState.value.copy(
                    isLoadingCards = false,
                    cardsError = PublicListRules.failureText(vendor, answer.reason)
                )

            PublicListsAnswer.NotConnected ->
                _uiState.value = _uiState.value.copy(
                    gate = PublicListGate.NOT_CONNECTED,
                    isLoadingCards = false
                )

            is PublicListsAnswer.Blocked ->
                _uiState.value = _uiState.value.copy(
                    gate = PublicListGate.ALLOWANCE_SPENT,
                    isLoadingCards = false,
                    cardsError = answer.reason
                )
        }
    }

    // ── Search ───────────────────────────────────────────────────────────

    fun openSearch() {
        _uiState.value = _uiState.value.copy(
            levels = _uiState.value.levels + PublicListLevel.Search,
            notice = null
        )
        if (!connected()) {
            _uiState.value = _uiState.value.copy(gate = PublicListGate.NOT_CONNECTED)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    /** Runs the query on submit only - a per-keystroke search spends MDBList quota. */
    fun runSearch() {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        if (!PublicListRules.searchEnabled(query) || state.isSearching) return
        if (!connected()) {
            _uiState.value = state.copy(gate = PublicListGate.NOT_CONNECTED)
            return
        }
        val vendor = state.vendor
        _uiState.value = state.copy(isSearching = true, searchError = null)
        val generation = ++searchGeneration
        val profile = profileGeneration
        viewModelScope.launch {
            val answer = when (vendor) {
                PublicListVendor.TRAKT -> traktRepository.searchPublicLists(query)
                PublicListVendor.MDBLIST -> MdbListClient.searchPublicLists(context, query)
            }
            if (generation != searchGeneration || profile != profileGeneration) {
                return@launch
            }
            when (answer) {
                is PublicListsAnswer.Loaded ->
                    _uiState.value = _uiState.value.copy(
                        searchResults = answer.value,
                        isSearching = false,
                        searchError = null
                    )

                is PublicListsAnswer.Failed ->
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchError = PublicListRules.failureText(vendor, answer.reason)
                    )

                PublicListsAnswer.NotConnected ->
                    _uiState.value = _uiState.value.copy(
                        gate = PublicListGate.NOT_CONNECTED,
                        isSearching = false
                    )

                is PublicListsAnswer.Blocked ->
                    _uiState.value = _uiState.value.copy(
                        gate = PublicListGate.ALLOWANCE_SPENT,
                        isSearching = false,
                        searchError = answer.reason
                    )
            }
        }
    }

    // ── Detail and titles ────────────────────────────────────────────────

    fun openDetail(card: PublicListCard) {
        _uiState.value = _uiState.value.copy(
            levels = _uiState.value.levels + PublicListLevel.Detail(card),
            notice = null
        )
        loadTitles(card)
    }

    private fun loadTitles(card: PublicListCard) {
        val state = _uiState.value
        _uiState.value = state.copy(titles = PublicListTitlesState.Loading)
        val vendor = state.vendor
        val generation = ++titlesGeneration
        val profile = profileGeneration
        viewModelScope.launch {
            val answer = when (vendor) {
                PublicListVendor.TRAKT -> traktRepository.publicListTitles(card)
                PublicListVendor.MDBLIST -> MdbListClient.publicListTitles(context, card)
            }
            if (generation != titlesGeneration || profile != profileGeneration) return@launch
            when (answer) {
                is PublicListsAnswer.Loaded -> {
                    // The active profile's ceiling is the gate, exactly as it is
                    // for Library rows: a community list is not a promise that
                    // its titles are age-screened (spec F5).
                    val allowed = tmdbRepository.kidsFilter(answer.value) { row -> row.ceilingKey }
                    _uiState.value = _uiState.value.copy(
                        titles = PublicListTitlesState.Titles(allowed)
                    )
                }

                is PublicListsAnswer.Failed ->
                    _uiState.value = _uiState.value.copy(
                        titles = PublicListTitlesState.Failed(
                            PublicListRules.failureText(vendor, answer.reason)
                        )
                    )

                PublicListsAnswer.NotConnected ->
                    _uiState.value = _uiState.value.copy(
                        gate = PublicListGate.NOT_CONNECTED,
                        titles = PublicListTitlesState.Failed(
                            PublicListRules.failureText(vendor, "401")
                        )
                    )

                is PublicListsAnswer.Blocked ->
                    _uiState.value = _uiState.value.copy(
                        gate = PublicListGate.ALLOWANCE_SPENT,
                        titles = PublicListTitlesState.Failed(answer.reason)
                    )
            }
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────

    /**
     * Saves [card] by liking it (spec F4's primary leg on both vendors).
     *
     * The gates are checked first and nothing is called when one holds, so a
     * save button can never be the thing that discovers the account is gone.
     */
    fun save(card: PublicListCard) {
        val state = _uiState.value
        if (state.savePhases[card.key] is PublicListSavePhase.Saving) return
        val vendor = state.vendor
        if (!connected()) {
            _uiState.value = state.copy(gate = PublicListGate.NOT_CONNECTED)
            return
        }
        if (vendor == PublicListVendor.MDBLIST &&
            MdbListClient.isLocalBudgetSpent(context)
        ) {
            _uiState.value = state.copy(
                gate = PublicListGate.ALLOWANCE_SPENT,
                notice = PublicListRules.allowanceText(vendor)
            )
            return
        }
        setSavePhase(card.key, PublicListSavePhase.Saving)
        val profile = profileGeneration
        viewModelScope.launch {
            val outcome = when (vendor) {
                PublicListVendor.TRAKT -> traktRepository.likePublicList(card)
                PublicListVendor.MDBLIST -> MdbListClient.likePublicList(context, card)
            }
            if (profile != profileGeneration) return@launch
            when (outcome) {
                PublicListSaveOutcome.Saved -> {
                    setSavePhase(
                        card.key,
                        PublicListSavePhase.Done(PublicListRules.savedText(vendor))
                    )
                    _uiState.value = _uiState.value.copy(notice = PublicListRules.savedText(vendor))
                }

                PublicListSaveOutcome.AlreadySaved -> setSavePhase(
                    card.key,
                    PublicListSavePhase.Done(PublicListRules.ALREADY_SAVED, already = true)
                )

                PublicListSaveOutcome.NotConnected -> {
                    setSavePhase(
                        card.key,
                        PublicListSavePhase.Failed(
                            PublicListRules.failureText(vendor, "401")
                        )
                    )
                    _uiState.value = _uiState.value.copy(gate = PublicListGate.NOT_CONNECTED)
                }

                is PublicListSaveOutcome.Refused -> setSavePhase(
                    card.key,
                    PublicListSavePhase.Failed(outcome.message)
                )

                is PublicListSaveOutcome.Failed -> setSavePhase(
                    card.key,
                    PublicListSavePhase.Failed(
                        PublicListRules.failureText(vendor, outcome.reason)
                    )
                )
            }
        }
    }

    private fun setSavePhase(key: String, phase: PublicListSavePhase) {
        _uiState.value = _uiState.value.copy(
            savePhases = _uiState.value.savePhases + (key to phase)
        )
    }
}
