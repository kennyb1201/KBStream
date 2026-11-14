package com.kennyb1201.kbstream.ui.publiclists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.anime.AnimeLists
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

    /**
     * The username box the anime vendors open on (spec F2).
     *
     * There is no trending-lists directory for an anime tracker, so browsing is
     * by profile name: the box is the level, the cards below it are that
     * profile's lists, and each card opens the same detail level as a Trakt or
     * MDBList card does.
     */
    data object Username : PublicListLevel

    data object Search : PublicListLevel

    /** One list, with its titles and its save action. */
    data class Detail(val card: PublicListCard) : PublicListLevel
}

/** What the detail screen knows about a list's titles. */
sealed interface PublicListTitlesState {
    data object Loading : PublicListTitlesState

    /**
     * The rows that can be drawn, and how many fetched titles could not be.
     *
     * [droppedCount] is the reconciliation the header needs: it counts the
     * provider's full `itemCount`, while a title with no IMDb and no TMDB id has
     * no [PublicListTitle.detailId] to open and is skipped by the detail body
     * (spec F6). Without it the header said "42 titles" over 38 rows with
     * nothing to explain the gap.
     */
    data class Titles(
        val rows: List<PublicListTitle>,
        val droppedCount: Int = 0
    ) : PublicListTitlesState

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
    /** Null on a by-username vendor: it has no directory tabs at all. */
    val directory: PublicListDirectory? = null,
    val cards: List<PublicListCard> = emptyList(),
    /** The username box's text, on a [PublicListVendor.byUsername] vendor. */
    val usernameQuery: String = "",
    /** The last username READ, so the empty state can name it. */
    val username: String? = null,
    val usernameLists: List<PublicListCard> = emptyList(),
    val isReadingUsername: Boolean = false,
    val usernameError: String? = null,
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
    /** The level being drawn. Falls back to the vendor's root level. */
    val level: PublicListLevel
        get() = levels.lastOrNull()
            ?: (directory?.let { PublicListLevel.Directory(it) } ?: PublicListLevel.Username)

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
    private var usernameGeneration = 0

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
                readStoredUsername()
            }
        }
    }

    private fun initialState(vendor: PublicListVendor): PublicListsUiState {
        val first = PublicListDirectory.forVendor(vendor).firstOrNull()
        val saved = storedUsername(vendor).orEmpty()
        return PublicListsUiState(
            vendor = vendor,
            levels = listOf(
                first?.let { PublicListLevel.Directory(it) } ?: PublicListLevel.Username
            ),
            directory = first,
            // The username box opens on the username this profile already reads
            // its own lists with, so browsing your own profile is one press.
            usernameQuery = saved,
            username = saved.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * The username this profile already saved for [vendor], if any.
     *
     * AniList and Kitsu keep theirs in Settings (it is the same name their own
     * Library lists are read with); MAL has no username - its identity is the
     * connection - so its box starts empty.
     */
    private fun storedUsername(vendor: PublicListVendor): String? = when (vendor) {
        PublicListVendor.ANILIST ->
            com.kennyb1201.kbstream.data.settings.AppPreferences
                .getAniListUsername(context)

        PublicListVendor.KITSU ->
            com.kennyb1201.kbstream.data.settings.AppPreferences
                .getKitsuUsername(context)

        else -> null
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
        // A profile already set up reads its own lists immediately; a by-username
        // vendor with no saved name waits for one rather than spending a read on
        // an empty name.
        readStoredUsername()
    }

    private fun readStoredUsername() {
        val state = _uiState.value
        if (!state.vendor.byUsername) return
        if (state.usernameQuery.isBlank()) return
        readUsername()
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

    /**
     * Whether this vendor can answer at all.
     *
     * AniList and Kitsu need no account (a public profile is readable without
     * one), so they are always ready - the username box is their gate. MAL's
     * list routes are OAuth-only, so its own session is the gate; Trakt and
     * MDBList keep the account rule they always had.
     */
    private fun connected(): Boolean = when (_uiState.value.vendor) {
        PublicListVendor.TRAKT ->
            traktRepository.isConfigured() && traktRepository.hasToken()

        PublicListVendor.MDBLIST -> MdbListClient.isConfigured(context)

        PublicListVendor.ANILIST, PublicListVendor.KITSU -> true

        PublicListVendor.MAL -> runCatching {
            val store = com.kennyb1201.kbstream.data.mal.MalTokenStore(context)
            store.hasSession() && !store.needsReconnect()
        }.getOrDefault(false)
    }

    /** Paints the cached directory, then refetches it (spec F2's SWR). */
    private fun loadDirectory(directory: PublicListDirectory?) {
        directory ?: return
        val vendor = _uiState.value.vendor
        if (vendor.byUsername) return
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

            // Not reachable: a by-username vendor returned above.
            else -> null
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
                else -> PublicListsAnswer.NotConnected
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
                // Directory search is the two directory vendors' surface; an
                // anime vendor browses by username instead.
                else -> PublicListsAnswer.Failed("not-a-directory-vendor")
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

    // ── By username (anime vendors, F2) ──────────────────────────────────

    fun setUsernameQuery(query: String) {
        _uiState.value = _uiState.value.copy(usernameQuery = query)
    }

    /**
     * Reads [usernameQuery]'s public lists on the anime vendor.
     *
     * The gate comes first: MAL cannot read ANY list without this profile's
     * connection, so its box is never offered a read that would fail. AniList
     * and Kitsu answer a public profile with no account of ours at all.
     */
    fun readUsername() {
        val state = _uiState.value
        val vendor = state.vendor
        if (!vendor.byUsername) return
        val username = state.usernameQuery.trim().removePrefix("@")
        if (!PublicListRules.usernameEnabled(username) || state.isReadingUsername) return
        if (!connected()) {
            _uiState.value = state.copy(gate = PublicListGate.NOT_CONNECTED)
            return
        }

        _uiState.value = state.copy(
            usernameQuery = username,
            username = username,
            usernameLists = emptyList(),
            isReadingUsername = true,
            usernameError = null,
            gate = null,
            notice = null
        )
        val generation = ++usernameGeneration
        val profile = profileGeneration
        viewModelScope.launch {
            val answer = AnimeLists.groupsFor(context, vendor, username)
            if (generation != usernameGeneration || profile != profileGeneration) {
                return@launch
            }
            when (answer) {
                is AnimeLists.AnimeGroupsRead.Ok ->
                    _uiState.value = _uiState.value.copy(
                        usernameLists = AnimeLists.cardsFor(vendor, username, answer.groups),
                        isReadingUsername = false,
                        usernameError = null
                    )

                is AnimeLists.AnimeGroupsRead.Unreadable ->
                    _uiState.value = _uiState.value.copy(
                        isReadingUsername = false,
                        usernameError = PublicListRules.unreadableUsernameText(vendor, username)
                    )

                AnimeLists.AnimeGroupsRead.NotConfigured ->
                    _uiState.value = _uiState.value.copy(
                        isReadingUsername = false,
                        gate = PublicListGate.NOT_CONNECTED
                    )

                is AnimeLists.AnimeGroupsRead.Failed ->
                    _uiState.value = _uiState.value.copy(
                        isReadingUsername = false,
                        usernameError = PublicListRules.failureText(vendor, "NETWORK")
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
            val answer = if (vendor.byUsername) {
                // As live as the other vendors' detail read: the list is read
                // again by the username on the card, so an edit the owner made
                // since the card was drawn is what opens.
                AnimeLists.titles(context, card)
            } else {
                when (vendor) {
                    PublicListVendor.TRAKT -> traktRepository.publicListTitles(card)
                    PublicListVendor.MDBLIST -> MdbListClient.publicListTitles(context, card)
                    else -> PublicListsAnswer.NotConnected
                }
            }
            if (generation != titlesGeneration || profile != profileGeneration) return@launch
            when (answer) {
                is PublicListsAnswer.Loaded -> {
                    // The active profile's ceiling is the gate, exactly as it is
                    // for Library rows: a community list is not a promise that
                    // its titles are age-screened (spec F5).
                    val allowed = tmdbRepository.kidsFilter(answer.value) { row -> row.ceilingKey }
                    // F6: counted where the rows are built, so the detail body
                    // never has to guess why its rows and the header disagree.
                    _uiState.value = _uiState.value.copy(
                        titles = PublicListTitlesState.Titles(
                            rows = allowed,
                            droppedCount = allowed.count { it.detailId == null }
                        )
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
            val outcome = if (vendor.byUsername) {
                // No anime tracker has a "save somebody's list" write, so the
                // save is a COPY into this profile's local Library - snapshot,
                // named after the vendor (spec F2).
                AnimeLists.copyToLibrary(context, card)
            } else {
                when (vendor) {
                    PublicListVendor.TRAKT -> traktRepository.likePublicList(card)
                    PublicListVendor.MDBLIST -> MdbListClient.likePublicList(context, card)
                    else -> PublicListSaveOutcome.Failed("not-a-public-vendor")
                }
            }
            if (profile != profileGeneration) return@launch
            when (outcome) {
                PublicListSaveOutcome.Saved -> {
                    val text = if (vendor.byUsername) {
                        PublicListRules.copySavedText(vendor)
                    } else {
                        PublicListRules.savedText(vendor)
                    }
                    setSavePhase(card.key, PublicListSavePhase.Done(text))
                    _uiState.value = _uiState.value.copy(notice = text)
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
