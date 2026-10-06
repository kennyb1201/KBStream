package com.kennyb1201.kbstream.ui.addons

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.catalogs.CATALOG_DEFAULT_REGION
import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_MOVIE
import com.kennyb1201.kbstream.data.catalogs.CustomCatalog
import com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore
import com.kennyb1201.kbstream.data.catalogs.csvAdd
import com.kennyb1201.kbstream.data.catalogs.csvRemove
import com.kennyb1201.kbstream.data.catalogs.csvToggle
import com.kennyb1201.kbstream.data.catalogs.codeListToggle
import com.kennyb1201.kbstream.data.catalogs.isSaveableCatalog
import com.kennyb1201.kbstream.data.catalogs.newCatalogId
import com.kennyb1201.kbstream.data.catalogs.normalizedFilters
import com.kennyb1201.kbstream.data.catalogs.yearFilterValue
import com.kennyb1201.kbstream.data.kb.KBFilters
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchKeywordResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Items the builder's preview row draws. One discover page, like the rail. */
private const val PREVIEW_LIMIT = 20

/** How many keyword suggestions one search shows. */
private const val KEYWORD_SUGGESTION_LIMIT = 12

/** How many cast suggestions one search shows. */
private const val PERSON_SUGGESTION_LIMIT = 12

/**
 * State behind the Catalog Builder: the saved catalogs, the draft being edited,
 * and the preview row for that draft.
 *
 * Nothing here is a snapshot of TMDB — a catalog stores RULES. The only thing
 * fetched is the preview, which is explicitly asked for (the PREVIEW button) and
 * thrown away when the draft changes, so editing a rule never fires a query.
 */
class CatalogBuilderViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val catalogs: List<CustomCatalog> = emptyList(),
        /** Arrangement keys this device hid from Home (see the show/hide toggle). */
        val hiddenKeys: Set<String> = emptySet(),
        val isLoading: Boolean = true
    )

    data class PreviewState(
        val loading: Boolean = false,
        val items: List<MetaPreview> = emptyList(),
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow<CustomCatalog?>(null)
    val draft: StateFlow<CustomCatalog?> = _draft.asStateFlow()

    /**
     * True while the draft has never been saved. The editor's primary action
     * says CREATE for one and SAVE for the other, so a new catalog cannot be
     * mistaken for an edit of an existing rule set.
     */
    private val _isNewDraft = MutableStateFlow(false)
    val isNewDraft: StateFlow<Boolean> = _isNewDraft.asStateFlow()

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    private val _keywordSuggestions = MutableStateFlow<List<TmdbSearchKeywordResult>>(emptyList())
    val keywordSuggestions: StateFlow<List<TmdbSearchKeywordResult>> =
        _keywordSuggestions.asStateFlow()

    /**
     * Names for the keyword ids this session picked, so the picked list can label
     * them ("Zombie") instead of showing a bare id. Session-scoped on purpose: a
     * keyword id cannot be resolved back to a name from a static list (TMDB
     * keyword ids are not enumerable), so a catalog adopted from another device
     * shows the ids it knows until the viewer touches that rule again.
     */
    private val _keywordNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val keywordNames: StateFlow<Map<Int, String>> = _keywordNames.asStateFlow()

    /** True while keyword picks go into `withKeywords`, false for `withoutKeywords`. */
    private val _keywordsInclude = MutableStateFlow(true)
    val keywordsInclude: StateFlow<Boolean> = _keywordsInclude.asStateFlow()

    /** Cast suggestions for the current query (see [searchPeople]). */
    private val _peopleSuggestions =
        MutableStateFlow<List<TmdbSearchPersonResult>>(emptyList())
    val peopleSuggestions: StateFlow<List<TmdbSearchPersonResult>> =
        _peopleSuggestions.asStateFlow()

    /**
     * Names for the person ids this session picked, so the picked list reads
     * "Tom Hanks" rather than "#31". Session-scoped for the same reason
     * [keywordNames] is: a person id cannot be resolved back to a name from a
     * static list.
     */
    private val _castNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val castNames: StateFlow<Map<Int, String>> = _castNames.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var previewJob: Job? = null
    private var keywordJob: Job? = null
    private var peopleJob: Job? = null

    private val tmdbRepository = TmdbRepository.getInstance(application)

    init {
        refresh()
        observeProfileSwitches()
    }

    // ---------------------------------------------------------------- list --

    fun refresh() {
        val context = getApplication<Application>()
        _state.value = _state.value.copy(
            catalogs = CustomCatalogStore.list(context),
            hiddenKeys = KBHomeOrderPrefs.get(context).hiddenSet,
            isLoading = false
        )
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Opens a fresh draft.
     *
     * The id is minted here rather than on save so the Home arrangement key
     * (derived from it) is stable across the whole editing session — a save that
     * renamed a catalog therefore cannot move it.
     */
    fun startNew() {
        val existing = _state.value.catalogs.mapTo(mutableSetOf()) { it.id }
        _draft.value = CustomCatalog(
            id = newCatalogId(System.currentTimeMillis(), existing),
            name = "",
            mediaType = CATALOG_MEDIA_MOVIE,
            filters = KBFilters(watchRegion = CATALOG_DEFAULT_REGION)
        )
        _isNewDraft.value = true
        resetPreview()
        resetKeywords()
        resetPeople()
    }

    fun edit(id: String) {
        val catalog = _state.value.catalogs.firstOrNull { it.id == id } ?: return
        _draft.value = catalog
        _isNewDraft.value = false
        resetPreview()
        resetKeywords()
        resetPeople()
    }

    fun cancelEdit() {
        _draft.value = null
        resetPreview()
        resetKeywords()
        resetPeople()
    }

    /** Applies a pure transform to the draft; a no-op when nothing is open. */
    fun updateDraft(transform: (CustomCatalog) -> CustomCatalog) {
        val current = _draft.value ?: return
        val next = transform(current)
        if (next == current) return
        _draft.value = next
        // The preview is a snapshot of the old rules: drop it rather than leave
        // a row that no longer matches what the editor says.
        resetPreview()
    }

    /** Media type, with the genres a switch invalidates pruned off the rules. */
    fun setMediaType(mediaType: String) {
        updateDraft { withCatalogMediaType(it, mediaType) }
    }

    fun setSort(sort: String) {
        updateDraft { it.copy(sort = sort) }
    }

    fun setName(name: String) {
        updateDraft { it.copy(name = name) }
    }

    fun setFilters(transform: (KBFilters) -> KBFilters) {
        updateDraft { catalog ->
            catalog.copy(filters = normalizedFilters(transform(catalog.filters)))
        }
    }

    fun saveDraft() {
        val catalog = _draft.value ?: return
        if (!isSaveableCatalog(catalog)) {
            _message.value = "Give the catalog a name first"
            return
        }
        val saved = catalog.copy(name = catalog.name.trim())
        CustomCatalogStore.upsert(getApplication(), saved)
        refresh()
        _draft.value = null
        resetPreview()
        resetKeywords()
        resetPeople()
        _message.value = if (_isNewDraft.value) {
            "\"${saved.name}\" added to Home"
        } else {
            "\"${saved.name}\" updated"
        }
        _isNewDraft.value = false
    }

    fun delete(id: String) {
        val name = _state.value.catalogs.firstOrNull { it.id == id }?.name.orEmpty()
        CustomCatalogStore.remove(getApplication(), id)
        if (_draft.value?.id == id) {
            _draft.value = null
            resetPreview()
        }
        refresh()
        _message.value = "Removed \"$name\""
    }

    fun move(id: String, delta: Int) {
        CustomCatalogStore.move(getApplication(), id, delta)
        refresh()
    }

    /**
     * Shows or hides one catalog's rail on Home.
     *
     * The flag lives in the shared home arrangement (`toggleRailHidden`) and not
     * on the catalog, so the builder's switch and the Home manager's hide toggle
     * are the SAME state — a rail hidden in one place is hidden in the other,
     * and the loader skips it without a second source of truth to disagree with.
     */
    fun toggleHidden(id: String) {
        val context = getApplication<Application>()
        val key = KBHomeOrderPrefs.customCatalogKey(id)
        val updated = KBHomeOrderPrefs.toggleRailHidden(
            KBHomeOrderPrefs.get(context),
            key
        )
        KBHomeOrderPrefs.save(context, updated)
        _state.value = _state.value.copy(hiddenKeys = updated.hiddenSet)
    }

    // ------------------------------------------------------------- preview --

    private fun resetPreview() {
        previewJob?.cancel()
        _preview.value = PreviewState()
    }

    /**
     * Runs the draft's rules once and shows what they return.
     *
     * This is the whole point of building a catalog on a TV remote: "Action +
     * Netflix + 8.0+" is a sentence, and the only way to know what it means is to
     * see the shelf. A null result means the query itself failed (no TMDB key, no
     * network); an empty list means the rules are simply too narrow, which is
     * also worth saying out loud.
     */
    fun runPreview() {
        val catalog = _draft.value ?: return
        previewJob?.cancel()
        _preview.value = PreviewState(loading = true)
        previewJob = viewModelScope.launch {
            val items = runCatchingCancellable {
                tmdbRepository.discoverKB(
                    mediaType = catalog.mediaType,
                    page = 1,
                    sortBy = catalog.tmdbSortKey(),
                    filters = catalog.effectiveFilters()
                )
            }.getOrNull()

            val metas = items.orEmpty().take(PREVIEW_LIMIT).map { item ->
                MetaPreview(
                    id = "tmdb:" + item.id,
                    type = catalog.railType,
                    name = item.name ?: item.title.orEmpty(),
                    poster = item.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { TmdbRepository.POSTER_BASE + it },
                    background = item.backdropPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { TmdbRepository.BACKDROP_BASE + it },
                    releaseInfo = (item.firstAirDate ?: item.releaseDate)
                        ?.takeIf { it.length >= 4 }
                        ?.take(4)
                )
            }

            _preview.value = when {
                items == null -> PreviewState(message = "Preview unavailable right now")
                metas.isEmpty() -> PreviewState(message = "No titles match these rules")
                else -> PreviewState(items = metas)
            }
        }
    }

    // ------------------------------------------------------------ keywords --

    private fun resetKeywords() {
        keywordJob?.cancel()
        _keywordSuggestions.value = emptyList()
        _keywordsInclude.value = true
    }

    fun setKeywordsInclude(include: Boolean) {
        _keywordsInclude.value = include
    }

    fun searchKeywords(query: String) {
        keywordJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _keywordSuggestions.value = emptyList()
            return
        }
        keywordJob = viewModelScope.launch {
            _keywordSuggestions.value = tmdbRepository.searchKeywords(trimmed)
                .take(KEYWORD_SUGGESTION_LIMIT)
        }
    }

    /** Adds one keyword to the include/exclude list the picker is set to. */
    fun pickKeyword(keyword: TmdbSearchKeywordResult) {
        val include = _keywordsInclude.value
        _keywordNames.value = _keywordNames.value + (keyword.id to keyword.name)
        setFilters { filters ->
            if (include) {
                filters.copy(withKeywords = csvAdd(filters.withKeywords, keyword.id))
            } else {
                filters.copy(withoutKeywords = csvAdd(filters.withoutKeywords, keyword.id))
            }
        }
        _keywordSuggestions.value = emptyList()
    }

    /** Drops one keyword from the list it sits in. */
    fun removeKeyword(id: Int, include: Boolean) {
        setFilters { filters ->
            if (include) {
                filters.copy(withKeywords = csvRemove(filters.withKeywords, id))
            } else {
                filters.copy(withoutKeywords = csvRemove(filters.withoutKeywords, id))
            }
        }
    }

    // ---------------------------------------------------------------- cast --

    private fun resetPeople() {
        peopleJob?.cancel()
        _peopleSuggestions.value = emptyList()
    }

    /**
     * Cast search. The rules store an ID, so a typed name has to be resolved
     * through TMDB's person search first - exactly like a keyword.
     */
    fun searchPeople(query: String) {
        peopleJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _peopleSuggestions.value = emptyList()
            return
        }
        peopleJob = viewModelScope.launch {
            _peopleSuggestions.value = tmdbRepository.searchPerson(trimmed)
                .take(PERSON_SUGGESTION_LIMIT)
        }
    }

    fun pickPerson(person: TmdbSearchPersonResult) {
        _castNames.value = _castNames.value + (person.id to person.name)
        setFilters { filters ->
            filters.copy(withCast = csvAdd(filters.withCast, person.id))
        }
        _peopleSuggestions.value = emptyList()
    }

    fun removePerson(id: Int) {
        setFilters { filters ->
            filters.copy(withCast = csvRemove(filters.withCast, id))
        }
    }

    // ------------------------------------------------- new filter setters ---

    /** Runtime window in minutes; 0 clears the bound. */
    fun setRuntimeMin(value: Int) {
        setFilters { filters -> filters.copy(withRuntimeGte = value.takeIf { it > 0 }) }
    }

    fun setRuntimeMax(value: Int) {
        setFilters { filters -> filters.copy(withRuntimeLte = value.takeIf { it > 0 }) }
    }

    /**
     * The country whose age-rating scale is being asked for. Kept even before a
     * value is picked, so the value chips below it have a scale to be read
     * against - and so the picker reopens on the scale the viewer last used.
     */
    fun setCertificationCountry(code: String) {
        setFilters { filters -> filters.copy(certificationCountry = code) }
    }

    /**
     * One age rating, or none when it is the value already set (tap to clear).
     *
     * The country is seeded with the rating: TMDB resolves `certification`
     * against `certification_country`, so a rating written alone would be a
     * filter that silently matches nothing.
     */
    fun setCertification(code: String) {
        setFilters { filters ->
            if (filters.certification == code) {
                filters.copy(certification = null, certificationCountry = null)
            } else {
                filters.copy(
                    certification = code,
                    certificationCountry = filters.certificationCountry
                        ?: CATALOG_DEFAULT_CERTIFICATION_COUNTRY
                )
            }
        }
    }

    /** TMDB's TV status code (0-5); tapping the selected one clears it. */
    fun setTvStatus(code: String) {
        setFilters { filters ->
            filters.copy(withStatus = code.takeIf { it != filters.withStatus })
        }
    }

    /** TMDB's TV type code (0-6); tapping the selected one clears it. */
    fun setTvType(code: String) {
        setFilters { filters ->
            filters.copy(withType = code.takeIf { it != filters.withType })
        }
    }

    /** Networks this catalog must NOT come from (TV). */
    fun toggleExcludedNetwork(networkId: Int) {
        setFilters { filters ->
            filters.copy(withoutNetworks = csvToggle(filters.withoutNetworks, networkId))
        }
    }

    // ------------------------------------------------------------ helpers ---

    fun toggleGenre(include: Boolean, id: Int) {
        setFilters { filters ->
            if (include) {
                filters.copy(withGenres = csvToggle(filters.withGenres, id))
            } else {
                filters.copy(withoutGenres = csvToggle(filters.withoutGenres, id))
            }
        }
    }

    fun toggleService(include: Boolean, providerId: Int) {
        setFilters { filters ->
            if (include) {
                val selected = com.kennyb1201.kbstream.data.catalogs
                    .splitIds(filters.withWatchProviders)
                val next = if (selected.contains(providerId)) {
                    selected - providerId
                } else {
                    selected + providerId
                }
                filters.copy(
                    withWatchProviders = next
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(","),
                    // A provider rule without a region resolves to nothing, so
                    // the first provider pick seeds the region.
                    watchRegion = filters.watchRegion
                        ?: CATALOG_DEFAULT_REGION
                )
            } else {
                filters.copy(
                    withoutWatchProviders = csvToggle(
                        filters.withoutWatchProviders,
                        providerId
                    )
                )
            }
        }
    }

    fun toggleStudio(include: Boolean, companyId: Int) {
        setFilters { filters ->
            if (include) {
                filters.copy(withCompanies = csvToggle(filters.withCompanies, companyId))
            } else {
                filters.copy(withoutCompanies = csvToggle(filters.withoutCompanies, companyId))
            }
        }
    }

    fun toggleNetwork(networkId: Int) {
        setFilters { filters ->
            filters.copy(withNetworks = csvToggle(filters.withNetworks, networkId))
        }
    }

    fun toggleLanguage(code: String) {
        setFilters { filters ->
            filters.copy(
                withOriginalLanguage = codeListToggle(filters.withOriginalLanguage, code)
            )
        }
    }

    fun toggleCountry(code: String) {
        setFilters { filters ->
            filters.copy(withOriginCountry = codeListToggle(filters.withOriginCountry, code))
        }
    }

    fun setRegion(code: String) {
        setFilters { filters -> filters.copy(watchRegion = code) }
    }

    /**
     * Single-select decade -> the "1990-1999" year range the loader understands.
     *
     * Clearing the explicit release window is deliberate: `discoverKB` prefers
     * the year range over the release bounds, so a decade picked on top of a
     * window would leave the window set and silently ignored (a filter that does
     * nothing while looking active). The two controls write one date rule
     * between them - see [setReleasedAfter].
     */
    fun setDecade(startYear: Int?) {
        setFilters { filters ->
            filters.copy(
                year = startYear?.let { yearFilterValue(it, it + 9) },
                releaseDateGte = null,
                releaseDateLte = null
            )
        }
    }

    /**
     * The explicit "released on/after this year" bound (TMDB
     * `release_date.gte` / `first_air_date.gte`). A null [year] clears it.
     *
     * Mutually exclusive with the DECADE row, which writes `year`: see
     * [setDecade] for why only one of the two may be set.
     */
    fun setReleasedAfter(year: Int?) {
        setFilters { filters ->
            filters.copy(
                year = null,
                releaseDateGte = year?.let { "$it-01-01" }
            )
        }
    }

    /** The matching "released on/before this year" bound; null clears it. */
    fun setReleasedBefore(year: Int?) {
        setFilters { filters ->
            filters.copy(
                year = null,
                releaseDateLte = year?.let { "$it-12-31" }
            )
        }
    }

    fun setMinRating(value: Int) {
        setFilters { filters -> filters.copy(voteAverageGte = value.takeIf { it > 0 }) }
    }

    fun setMaxRating(value: Int) {
        setFilters { filters -> filters.copy(voteAverageLte = value.takeIf { it > 0 }) }
    }

    fun setMinVotes(value: Int) {
        setFilters { filters -> filters.copy(voteCountGte = value.takeIf { it > 0 }) }
    }

    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var applied = ProfileManager.activeProfile.value?.id
            ProfileManager.activeProfile.collect { profile ->
                val id = profile?.id
                if (id == applied) return@collect
                applied = id
                _draft.value = null
                resetPreview()
                resetKeywords()
                refresh()
            }
        }
    }
}
