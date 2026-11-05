package com.kennyb1201.kbstream.ui.bingecat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.bingecat.BingeCatImportAttempt
import com.kennyb1201.kbstream.data.bingecat.BingeCatImportFeedback
import com.kennyb1201.kbstream.data.bingecat.BingeCatImportMode
import com.kennyb1201.kbstream.data.bingecat.BingeCatJobOutcome
import com.kennyb1201.kbstream.data.bingecat.BingeCatJobPoll
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicBrowse
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicCategory
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicListCard
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicListRules
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.bingecat.BingeCatRow
import com.kennyb1201.kbstream.data.bingecat.BingeCatSearchType
import com.kennyb1201.kbstream.data.bingecat.BingeCatTitleProbe
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One level of the browse surface; the stack of these is the Back path. */
sealed interface BingeCatPublicLevel {
    data object Categories : BingeCatPublicLevel

    /** A category's own children, drawn as tiles before any lists are read. */
    data class Subcategories(
        val parentSlug: String,
        val parentName: String,
        val children: List<BingeCatPublicCategory>
    ) : BingeCatPublicLevel

    /** One category's (or subcategory's) list cards. */
    data class Lists(
        val slug: String,
        val subcategory: String?,
        val title: String
    ) : BingeCatPublicLevel

    data object Search : BingeCatPublicLevel

    /** One public list, with its import action. */
    data class Detail(val card: BingeCatPublicListCard) : BingeCatPublicLevel
}

/** What the detail screen knows about a list's titles (spec Q4). */
sealed interface BingeCatTitlesState {
    data object Loading : BingeCatTitlesState

    /** Q4a: the list's ref resolved, so its titles can be shown before import. */
    data class Titles(val rows: List<BingeCatRow>) : BingeCatTitlesState

    /** Q4b: the ref is not addressable - titles appear after import, in Library. */
    data object AfterImport : BingeCatTitlesState

    data class Failed(val message: String) : BingeCatTitlesState
}

/** One card's import, as the card draws it. */
sealed interface BingeCatImportPhase {
    data object Importing : BingeCatImportPhase

    /** `partial` is the job's own "finished, not fully applied". */
    data class Done(val message: String, val partial: Boolean = false) : BingeCatImportPhase

    data class Failed(val message: String) : BingeCatImportPhase
}

/** The states that stop the surface before a request: each has its own prompt. */
enum class BingeCatGate {
    /** No BingeCat session on this profile: the connect page is the answer. */
    NOT_CONNECTED,

    /** A grant approved before `search:read` was asked for: reconnect. */
    NEEDS_REAPPROVAL,

    /** Connected, but this KBStream profile is not mapped: import has nowhere to go. */
    NEEDS_MAPPING
}

/**
 * The browse-and-import surface's whole state.
 *
 * One state object for the reason the connect screen has one: the surface has
 * mutually exclusive shapes (gate / categories / subcategories / list cards /
 * search / detail) and a half-updated pair of flags is how it ends up drawing
 * import buttons over a screen with no mapping.
 *
 * `browsing` is the entry/exit switch instead of a new Screen: the surface is a
 * level stack INSIDE Settings → BingeCat, so Back walks the stack and the
 * connect page stays one press away - and opening a title and coming back
 * returns to the level the viewer left, not to the connect page.
 */
data class BingeCatPublicListsUiState(
    val browsing: Boolean = false,
    val levels: List<BingeCatPublicLevel> = listOf(BingeCatPublicLevel.Categories),
    val gate: BingeCatGate? = null,
    val categories: List<BingeCatPublicCategory> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val categoriesError: String? = null,
    val cards: List<BingeCatPublicListCard> = emptyList(),
    val isLoadingCards: Boolean = false,
    val cardsError: String? = null,
    val searchQuery: String = "",
    val searchType: BingeCatSearchType = BingeCatSearchType.ALL,
    val searchResults: List<BingeCatPublicListCard> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val titles: BingeCatTitlesState = BingeCatTitlesState.Loading,
    val importTarget: BingeCatPublicListCard? = null,
    val importMode: BingeCatImportMode = BingeCatImportMode.COMBINED,
    val importPhases: Map<String, BingeCatImportPhase> = emptyMap(),
    val notice: String? = null
) {
    /** The level being drawn. The stack is never empty. */
    val level: BingeCatPublicLevel get() = levels.lastOrNull() ?: BingeCatPublicLevel.Categories

    /** Whether Back has somewhere to go inside the surface. */
    val canGoBack: Boolean get() = levels.size > 1

    /** The modes the open confirmation offers, from the list it is for. */
    val importModes: List<BingeCatImportMode>
        get() = importTarget?.let {
            BingeCatPublicListRules.modesFor(it.contentType, it.movieCount, it.seriesCount)
        }.orEmpty()
}

/**
 * Browsing and importing BingeCat public lists, as Settings drives it.
 *
 * Three rules shape it:
 *
 *  - **Gates before requests.** No session, no `search:read`, or (for an
 *    import) no mapping is a PROMPT, and nothing is called: the repository
 *    enforces the same rule, so a race cannot turn it into a 403.
 *  - **A job is polled, never assumed.** An accepted import is a job; the card
 *    says "Importing…" until the job's own status says otherwise, and the
 *    polling wait is the job's `Retry-After` - bounded by
 *    [BingeCatPublicListRules.JOB_MAX_WAIT_SECONDS], so no card can sit on
 *    "Importing…" forever.
 *  - **Titles are only shown when a read produced them.** The detail screen
 *    probes `/lists/{ref}/items` once (spec Q4) and says which world it is in;
 *    it never draws a grid from `cover_posters`.
 */
class BingeCatPublicListsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = BingeCatRepository.getInstance(app.applicationContext)

    /** The rating ceiling's source - the same filter Library and the rails use. */
    private val tmdbRepository = TmdbRepository.getInstance(app.applicationContext)

    private val _uiState = MutableStateFlow(BingeCatPublicListsUiState())
    val uiState: StateFlow<BingeCatPublicListsUiState> = _uiState.asStateFlow()

    private var importJob: Job? = null

    init {
        observeProfileSwitches()
    }

    /**
     * A profile switch re-derives the surface: the session, the mapping and the
     * grant are all per KBStream profile, so a switch can turn a usable screen
     * into a prompt - and an import poll must not keep reporting into a profile
     * the viewer has left.
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            ProfileManager.activeProfile.collect {
                if (first) {
                    first = false
                    return@collect
                }
                importJob?.cancel()
                val browsing = _uiState.value.browsing
                _uiState.value = BingeCatPublicListsUiState(
                    browsing = browsing,
                    levels = listOf(BingeCatPublicLevel.Categories)
                )
                if (browsing) loadCategories()
            }
        }
    }

    /** Enters the surface (from the connect page) and reads the tree. */
    fun open() {
        if (_uiState.value.browsing) return
        _uiState.value = BingeCatPublicListsUiState(browsing = true)
        loadCategories()
    }

    /** Leaves the surface, back to the BingeCat connect page. */
    fun close() {
        importJob?.cancel()
        _uiState.value = _uiState.value.copy(
            browsing = false,
            levels = listOf(BingeCatPublicLevel.Categories),
            importTarget = null
        )
    }

    /** One Back: up a level, or out of the surface at the root. */
    fun back() {
        val state = _uiState.value
        if (!state.browsing) return
        if (!state.canGoBack) {
            close()
            return
        }
        val target = state.levels.dropLast(1)
        // A detail screen's probe is abandoned with its level, and the open
        // confirmation belongs to the level that raised it.
        _uiState.value = state.copy(
            levels = target,
            importTarget = null,
            cardsError = null,
            searchError = null
        )
    }

    // ── Categories ───────────────────────────────────────────────────────

    private fun connectGate(): BingeCatGate? = when {
        !repository.isConfigured() || !repository.hasToken() -> BingeCatGate.NOT_CONNECTED
        !repository.hasPublicListsScope() -> BingeCatGate.NEEDS_REAPPROVAL
        else -> null
    }

    fun loadCategories() {
        val gate = connectGate()
        if (gate != null) {
            _uiState.value = _uiState.value.copy(
                gate = gate,
                isLoadingCategories = false,
                categoriesError = null
            )
            return
        }
        // Paint what this process already read before asking again (F2's
        // stale-while-revalidate, kept on screen across a failed refresh).
        val cached = repository.cachedPublicCategories()
        _uiState.value = _uiState.value.copy(
            gate = null,
            categories = cached ?: _uiState.value.categories,
            isLoadingCategories = cached == null,
            categoriesError = null
        )
        viewModelScope.launch {
            when (val result = repository.publicCategories()) {
                is BingeCatPublicBrowse.Loaded ->
                    _uiState.value = _uiState.value.copy(
                        categories = result.value,
                        isLoadingCategories = false,
                        categoriesError = null
                    )

                BingeCatPublicBrowse.NeedsReapproval ->
                    _uiState.value = _uiState.value.copy(
                        gate = BingeCatGate.NEEDS_REAPPROVAL,
                        isLoadingCategories = false
                    )

                BingeCatPublicBrowse.NotReady ->
                    _uiState.value = _uiState.value.copy(
                        gate = BingeCatGate.NOT_CONNECTED,
                        isLoadingCategories = false
                    )

                is BingeCatPublicBrowse.Failed ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingCategories = false,
                        categoriesError = BingeCatPublicListRules.failureText(result.reason)
                    )
            }
        }
    }

    fun openCategory(category: BingeCatPublicCategory) {
        val slug = category.slug?.takeIf { it.isNotBlank() } ?: return
        val name = category.name?.takeIf { it.isNotBlank() } ?: slug
        val children = category.children.filter { BingeCatPublicListRules.isDrawable(it) }
        if (children.isNotEmpty()) {
            push(BingeCatPublicLevel.Subcategories(slug, name, children))
            return
        }
        push(BingeCatPublicLevel.Lists(slug = slug, subcategory = null, title = name))
        loadCards(slug, null)
    }

    fun openSubcategory(parent: BingeCatPublicLevel.Subcategories, child: BingeCatPublicCategory) {
        val childSlug = child.slug?.takeIf { it.isNotBlank() } ?: return
        val childName = child.name?.takeIf { it.isNotBlank() } ?: childSlug
        push(
            BingeCatPublicLevel.Lists(
                slug = parent.parentSlug,
                subcategory = childSlug,
                title = "${parent.parentName} · $childName"
            )
        )
        loadCards(parent.parentSlug, childSlug)
    }

    private fun loadCards(slug: String, subcategory: String?) {
        val gate = connectGate()
        if (gate != null) {
            _uiState.value = _uiState.value.copy(gate = gate)
            return
        }
        _uiState.value = _uiState.value.copy(
            cards = emptyList(),
            isLoadingCards = true,
            cardsError = null,
            gate = null
        )
        viewModelScope.launch {
            when (val result = repository.publicListsInCategory(slug, subcategory)) {
                is BingeCatPublicBrowse.Loaded ->
                    _uiState.value = _uiState.value.copy(
                        cards = result.value.mapNotNull { BingeCatPublicListRules.cardFromRow(it) },
                        isLoadingCards = false,
                        cardsError = null
                    )

                BingeCatPublicBrowse.NeedsReapproval ->
                    _uiState.value = _uiState.value.copy(
                        gate = BingeCatGate.NEEDS_REAPPROVAL,
                        isLoadingCards = false
                    )

                BingeCatPublicBrowse.NotReady ->
                    _uiState.value = _uiState.value.copy(
                        gate = BingeCatGate.NOT_CONNECTED,
                        isLoadingCards = false
                    )

                is BingeCatPublicBrowse.Failed ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingCards = false,
                        cardsError = BingeCatPublicListRules.failureText(result.reason)
                    )
            }
        }
    }

    // ── Search ───────────────────────────────────────────────────────────

    fun openSearch() {
        push(BingeCatPublicLevel.Search)
        val gate = connectGate()
        if (gate != null) {
            _uiState.value = _uiState.value.copy(gate = gate)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSearchType(type: BingeCatSearchType) {
        _uiState.value = _uiState.value.copy(searchType = type)
    }

    /** Runs the query on submit; a query below the two-character floor does nothing. */
    fun runSearch() {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        if (!BingeCatPublicListRules.searchEnabled(query) || state.isSearching) return
        val gate = connectGate()
        if (gate != null) {
            _uiState.value = state.copy(gate = gate)
            return
        }
        _uiState.value = state.copy(isSearching = true, searchError = null)
        viewModelScope.launch {
            when (val result = repository.searchPublicLists(query, _uiState.value.searchType)) {
                is BingeCatPublicBrowse.Loaded ->
                    _uiState.value = _uiState.value.copy(
                        searchResults = result.value.mapNotNull {
                            BingeCatPublicListRules.cardFromSearch(it)
                        },
                        isSearching = false,
                        searchError = null
                    )

                BingeCatPublicBrowse.NeedsReapproval ->
                    _uiState.value = _uiState.value.copy(
                        gate = BingeCatGate.NEEDS_REAPPROVAL,
                        isSearching = false
                    )

                BingeCatPublicBrowse.NotReady ->
                    _uiState.value = _uiState.value.copy(
                        gate = BingeCatGate.NOT_CONNECTED,
                        isSearching = false
                    )

                is BingeCatPublicBrowse.Failed ->
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchError = BingeCatPublicListRules.failureText(result.reason)
                    )
            }
        }
    }

    // ── Detail (and the Q4 title probe) ──────────────────────────────────

    fun openDetail(card: BingeCatPublicListCard) {
        push(BingeCatPublicLevel.Detail(card))
        probeTitles(card)
    }

    private fun probeTitles(card: BingeCatPublicListCard) {
        _uiState.value = _uiState.value.copy(titles = BingeCatTitlesState.Loading)
        viewModelScope.launch {
            when (val probe = repository.publicListTitles(card.importId)) {
                is BingeCatTitleProbe.Titles -> {
                    // The active profile's ceiling is the gate, exactly as it is
                    // for Library rows: a public list is BingeCat's, not a
                    // promise that its titles are age-screened.
                    val allowed = tmdbRepository.kidsFilter(probe.rows) { row ->
                        (row.tmdbId ?: -1) to (if (row.isMovie) "movie" else "series")
                    }
                    _uiState.value = _uiState.value.copy(
                        titles = BingeCatTitlesState.Titles(allowed)
                    )
                }

                // Q4b: the key is not a list ref, so the titles live behind the
                // import. Metadata and the Import action stay.
                BingeCatTitleProbe.NotAddressable ->
                    _uiState.value = _uiState.value.copy(titles = BingeCatTitlesState.AfterImport)

                is BingeCatTitleProbe.Failed ->
                    _uiState.value = _uiState.value.copy(
                        titles = BingeCatTitlesState.Failed(
                            BingeCatPublicListRules.failureText(probe.reason)
                        )
                    )
            }
        }
    }

    // ── Import ───────────────────────────────────────────────────────────

    /**
     * Opens the mode confirmation for [card], after the gates.
     *
     * Import creates a list inside the mapped BingeCat profile, so an unmapped
     * profile gets the mapping prompt and no button - a button that can only
     * fail is worse than the sentence that explains the fix.
     */
    fun startImport(card: BingeCatPublicListCard) {
        val gate = connectGate()
        if (gate != null) {
            _uiState.value = _uiState.value.copy(gate = gate, notice = null)
            return
        }
        if (repository.mappedProfileId() == null) {
            _uiState.value = _uiState.value.copy(gate = BingeCatGate.NEEDS_MAPPING, notice = null)
            return
        }
        if (!repository.hasListsWriteScope()) {
            _uiState.value = _uiState.value.copy(
                gate = BingeCatGate.NEEDS_REAPPROVAL,
                notice = null
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            importTarget = card,
            importMode = BingeCatPublicListRules.initialMode(
                card.contentType,
                card.movieCount,
                card.seriesCount
            ),
            gate = null,
            notice = null
        )
    }

    fun chooseImportMode(mode: BingeCatImportMode) {
        _uiState.value = _uiState.value.copy(importMode = mode)
    }

    fun cancelImport() {
        _uiState.value = _uiState.value.copy(importTarget = null, notice = null)
    }

    /** Confirms the open import, then follows its job. */
    fun confirmImport() {
        val card = _uiState.value.importTarget ?: return
        val mode = _uiState.value.importMode
        _uiState.value = _uiState.value.copy(
            importTarget = null,
            notice = null,
            importPhases = _uiState.value.importPhases + (card.importId to BingeCatImportPhase.Importing)
        )
        viewModelScope.launch {
            when (val attempt = repository.importPublicList(card.importId, mode)) {
                is BingeCatImportAttempt.Answered -> when (val feedback = attempt.feedback) {
                    is BingeCatImportFeedback.Started ->
                        followJob(card, feedback, attempt.retryAfterSeconds)

                    BingeCatImportFeedback.AlreadyImported -> setPhase(
                        card.importId,
                        BingeCatImportPhase.Done("Already in your lists")
                    )

                    BingeCatImportFeedback.NotFound -> setPhase(
                        card.importId,
                        BingeCatImportPhase.Failed("BingeCat no longer has this list")
                    )

                    is BingeCatImportFeedback.NotImportable -> setPhase(
                        card.importId,
                        BingeCatImportPhase.Failed(feedback.detail)
                    )
                }

                BingeCatImportAttempt.NeedsReapproval -> {
                    setPhase(
                        card.importId,
                        BingeCatImportPhase.Failed("Reconnect BingeCat to import lists")
                    )
                    _uiState.value = _uiState.value.copy(gate = BingeCatGate.NEEDS_REAPPROVAL)
                }

                BingeCatImportAttempt.NotReady -> {
                    setPhase(
                        card.importId,
                        BingeCatImportPhase.Failed("Connect BingeCat and pick a profile first")
                    )
                    _uiState.value = _uiState.value.copy(gate = BingeCatGate.NEEDS_MAPPING)
                }

                is BingeCatImportAttempt.Failed -> setPhase(
                    card.importId,
                    BingeCatImportPhase.Failed(
                        BingeCatPublicListRules.importFailureText(attempt.reason)
                    )
                )
            }
        }
    }

    /**
     * Follows an accepted import, one wait at a time.
     *
     * The wait is the job's own `Retry-After` (5s when it has none) and the
     * whole follow is bounded: a job that is still not done when the budget runs
     * out ends as "still importing" rather than an "Importing…" card that no
     * answer ever clears.
     */
    private fun followJob(
        card: BingeCatPublicListCard,
        started: BingeCatImportFeedback.Started,
        firstWaitSeconds: Long
    ) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            val jobUrl = started.jobUrl
            if (jobUrl.isNullOrBlank()) {
                // Accepted, but with nothing to poll: the list is being built,
                // and the profile's list read (not this card) is what shows it.
                setPhase(
                    card.importId,
                    BingeCatImportPhase.Done("Import started - it will appear in your BingeCat lists")
                )
                return@launch
            }
            var waited = 0L
            var wait = firstWaitSeconds
            var failures = 0
            while (isActive && waited < BingeCatPublicListRules.JOB_MAX_WAIT_SECONDS) {
                delay(wait * 1_000L)
                waited += wait
                when (val poll = repository.pollImportJob(jobUrl)) {
                    is BingeCatJobPoll.Status -> when (val outcome = poll.outcome) {
                        BingeCatJobOutcome.Pending -> {
                            wait = poll.retryAfterSeconds
                            failures = 0
                        }

                        is BingeCatJobOutcome.Finished -> {
                            setPhase(
                                card.importId,
                                if (outcome.partial) {
                                    BingeCatImportPhase.Done(
                                        outcome.detail?.let {
                                            BingeCatPublicListRules.jobFailureReason(it)
                                        } ?: "Imported, but BingeCat applied only part of the list",
                                        partial = true
                                    )
                                } else {
                                    BingeCatImportPhase.Done("Imported into your BingeCat lists")
                                }
                            )
                            return@launch
                        }

                        is BingeCatJobOutcome.Failed -> {
                            setPhase(card.importId, BingeCatImportPhase.Failed(outcome.reason))
                            return@launch
                        }
                    }

                    is BingeCatJobPoll.Failed -> {
                        // A dropped session ends the follow; a blip gets three
                        // tries before the card reports it.
                        if (poll.reason == "401" || poll.reason == "403-scope") {
                            setPhase(
                                card.importId,
                                BingeCatImportPhase.Failed("Reconnect BingeCat to import lists")
                            )
                            return@launch
                        }
                        failures += 1
                        if (failures >= 3) {
                            setPhase(
                                card.importId,
                                BingeCatImportPhase.Failed(
                                    BingeCatPublicListRules.importFailureText(poll.reason)
                                )
                            )
                            return@launch
                        }
                        wait = BingeCatPublicListRules.DEFAULT_RETRY_AFTER_SECONDS
                    }
                }
            }
            if (isActive) {
                setPhase(
                    card.importId,
                    BingeCatImportPhase.Failed(
                        "BingeCat is still importing - check your BingeCat lists in a minute"
                    )
                )
            }
        }
    }

    private fun setPhase(importId: String, phase: BingeCatImportPhase) {
        _uiState.value = _uiState.value.copy(
            importPhases = _uiState.value.importPhases + (importId to phase)
        )
    }

    private fun push(level: BingeCatPublicLevel) {
        _uiState.value = _uiState.value.copy(
            levels = _uiState.value.levels + level,
            notice = null
        )
    }
}
