package com.kennyb1201.kbstream.ui.addons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_MOVIE
import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_TV
import com.kennyb1201.kbstream.data.catalogs.CustomCatalog
import com.kennyb1201.kbstream.data.catalogs.MAX_CUSTOM_CATALOGS
import com.kennyb1201.kbstream.data.catalogs.splitCodes
import com.kennyb1201.kbstream.data.catalogs.splitIds
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

// The Catalog Builder: a rule-based "smart catalog" editor.
//
// A catalog is a saved TMDB /discover query — media type, sort, and any subset
// of the app's filter set (genres, services, studios, networks, keywords,
// decade, languages, countries, rating, vote count). It is re-run on every Home
// load, so the rail is never a snapshot, and it is arranged like every other
// rail (the same `custom:` arrangement key the Home manager moves and hides).
//
// The editor's every control writes ONE `KBFilters`, the same object the
// imported collection profiles use and `TmdbRepository.discoverKB` consumes —
// which is what makes "all the filters the app has" true rather than a lookalike
// subset. The preview button is the sentence's meaning: it runs the rules once
// and draws the shelf they return.

@Composable
fun CatalogBuilderScreen(
    onBack: () -> Unit,
    viewModel: CatalogBuilderViewModel = viewModel()
) {
    BackHandler(onBack = onBack)

    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isNewDraft by viewModel.isNewDraft.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<CustomCatalog?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Header(
            title = if (draft != null) {
                if (isNewDraft) "NEW CATALOG" else "EDIT CATALOG"
            } else {
                "CATALOGS"
            },
            subtitle = if (draft != null) {
                draft?.let { catalogSummaryLine(it) }.orEmpty()
            } else {
                when (state.catalogs.size) {
                    0 -> "No catalogs yet"
                    1 -> "1 catalog"
                    else -> "${state.catalogs.size} catalogs"
                }
            },
            editing = draft != null,
            canSave = draft !== null,
            onNew = viewModel::startNew,
            onBack = if (draft != null) viewModel::cancelEdit else onBack,
            onSave = viewModel::saveDraft,
            onPreview = viewModel::runPreview,
            saveLabel = if (isNewDraft) "CREATE" else "SAVE"
        )

        message?.let { banner ->
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = KBShapeSmall,
                colors = SurfaceDefaults.colors(
                    containerColor = KBAccent.copy(alpha = 0.18f),
                    contentColor = KBAccent
                )
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val current = draft
        if (current == null) {
            CatalogList(
                catalogs = state.catalogs,
                hiddenKeys = state.hiddenKeys,
                onEdit = viewModel::edit,
                onToggleHidden = viewModel::toggleHidden,
                onMove = viewModel::move,
                onDelete = { pendingDelete = it }
            )
        } else {
            CatalogEditor(
                catalog = current,
                preview = preview,
                keywordSuggestions = viewModel.keywordSuggestions
                    .collectAsStateWithLifecycle().value,
                keywordNames = viewModel.keywordNames.collectAsStateWithLifecycle().value,
                keywordsInclude = viewModel.keywordsInclude.collectAsStateWithLifecycle().value,
                peopleSuggestions = viewModel.peopleSuggestions
                    .collectAsStateWithLifecycle().value,
                castNames = viewModel.castNames.collectAsStateWithLifecycle().value,
                onNameChange = viewModel::setName,
                onMediaType = viewModel::setMediaType,
                onSort = viewModel::setSort,
                onGenre = viewModel::toggleGenre,
                onService = viewModel::toggleService,
                onStudio = viewModel::toggleStudio,
                onNetwork = viewModel::toggleNetwork,
                onRegion = viewModel::setRegion,
                onDecade = viewModel::setDecade,
                onReleasedAfter = viewModel::setReleasedAfter,
                onReleasedBefore = viewModel::setReleasedBefore,
                onLanguage = viewModel::toggleLanguage,
                onCountry = viewModel::toggleCountry,
                onMinRating = viewModel::setMinRating,
                onMaxRating = viewModel::setMaxRating,
                onMinVotes = viewModel::setMinVotes,
                onKeywordsInclude = viewModel::setKeywordsInclude,
                onKeywordQuery = viewModel::searchKeywords,
                onPickKeyword = viewModel::pickKeyword,
                onRemoveKeyword = viewModel::removeKeyword,
                onPeopleQuery = viewModel::searchPeople,
                onPickPerson = viewModel::pickPerson,
                onRemovePerson = viewModel::removePerson,
                onRuntimeMin = viewModel::setRuntimeMin,
                onRuntimeMax = viewModel::setRuntimeMax,
                onCertificationCountry = viewModel::setCertificationCountry,
                onCertification = viewModel::setCertification,
                onTvStatus = viewModel::setTvStatus,
                onTvType = viewModel::setTvType,
                onExcludedNetwork = viewModel::toggleExcludedNetwork
            )
        }
    }

    pendingDelete?.let { target ->
        ConfirmDeleteDialog(
            name = target.name,
            onConfirm = {
                viewModel.delete(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    title: String,
    subtitle: String,
    editing: Boolean,
    canSave: Boolean,
    saveLabel: String,
    onNew: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onPreview: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.padding(end = 16.dp)) {
                KBPageTitle(text = title)
                Text(
                    text = subtitle,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (editing) {
                    ActionButton(
                        label = "PREVIEW",
                        icon = Icons.Filled.Refresh,
                        enabled = canSave,
                        onClick = onPreview
                    )
                    ActionButton(
                        label = saveLabel,
                        icon = Icons.Filled.Add,
                        enabled = canSave,
                        onClick = onSave
                    )
                    ActionButton(label = "CANCEL", onClick = onBack)
                } else {
                    ActionButton(
                        label = "NEW CATALOG",
                        icon = Icons.Filled.Add,
                        onClick = onNew
                    )
                    ActionButton(label = "BACK", onClick = onBack)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(KBAccent.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun CatalogList(
    catalogs: List<CustomCatalog>,
    hiddenKeys: Set<String>,
    onEdit: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (CustomCatalog) -> Unit
) {
    if (catalogs.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KBShapeCard,
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface,
                contentColor = KBTextLo
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Build a rail from filters",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "A catalog is a saved set of rules — genres, services, " +
                        "studios, networks, keywords, decade, language, rating — that " +
                        "Home runs fresh every load. Press NEW CATALOG to start.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        catalogs.forEachIndexed { index, catalog ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = KBShapeCard,
                colors = SurfaceDefaults.colors(
                    containerColor = KBSurface,
                    contentColor = KBTextHi
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = catalog.name.ifBlank { "Untitled catalog" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = KBShapeSmall,
                                colors = SurfaceDefaults.colors(
                                    containerColor = if (
                                        hiddenKeys.contains(
                                            com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                                                .customCatalogKey(catalog.id)
                                        )
                                    ) {
                                        KBSurfaceRaised.copy(alpha = 0.70f)
                                    } else {
                                        KBAccent.copy(alpha = 0.22f)
                                    },
                                    contentColor = if (
                                        hiddenKeys.contains(
                                            com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                                                .customCatalogKey(catalog.id)
                                        )
                                    ) {
                                        KBTextLo
                                    } else {
                                        KBAccent
                                    }
                                )
                            ) {
                                Text(
                                    text = if (
                                        hiddenKeys.contains(
                                            com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                                                .customCatalogKey(catalog.id)
                                        )
                                    ) {
                                        "HIDDEN"
                                    } else {
                                        "ON HOME"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(
                                        horizontal = 6.dp,
                                        vertical = 2.dp
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = catalogSummaryLine(catalog),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val genres = catalogGenreSummary(catalog.filters)
                        if (genres.isNotEmpty()) {
                            Text(
                                text = genres,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CatalogIconButton(
                            icon = if (
                                hiddenKeys.contains(
                                    com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                                        .customCatalogKey(catalog.id)
                                )
                            ) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            tint = KBTextHi,
                            contentDescription = "Show on Home",
                            onClick = { onToggleHidden(catalog.id) }
                        )
                        CatalogIconButton(
                            icon = Icons.Filled.ArrowUpward,
                            enabled = index > 0,
                            contentDescription = "Move up",
                            onClick = { onMove(catalog.id, -1) }
                        )
                        CatalogIconButton(
                            icon = Icons.Filled.ArrowDownward,
                            enabled = index < catalogs.lastIndex,
                            contentDescription = "Move down",
                            onClick = { onMove(catalog.id, 1) }
                        )
                        CatalogIconButton(
                            icon = Icons.Filled.Edit,
                            contentDescription = "Edit",
                            onClick = { onEdit(catalog.id) }
                        )
                        CatalogIconButton(
                            icon = Icons.Filled.Delete,
                            tint = KBDanger,
                            contentDescription = "Delete",
                            onClick = { onDelete(catalog) }
                        )
                    }
                }
            }
        }
        if (catalogs.size >= MAX_CUSTOM_CATALOGS) {
            Text(
                text = "That is the maximum of $MAX_CUSTOM_CATALOGS catalogs.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun CatalogEditor(
    catalog: CustomCatalog,
    preview: CatalogBuilderViewModel.PreviewState,
    keywordSuggestions: List<com.kennyb1201.kbstream.data.tmdb.TmdbSearchKeywordResult>,
    keywordNames: Map<Int, String>,
    keywordsInclude: Boolean,
    peopleSuggestions: List<com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult>,
    castNames: Map<Int, String>,
    onNameChange: (String) -> Unit,
    onMediaType: (String) -> Unit,
    onSort: (String) -> Unit,
    onGenre: (Boolean, Int) -> Unit,
    onService: (Boolean, Int) -> Unit,
    onStudio: (Boolean, Int) -> Unit,
    onNetwork: (Int) -> Unit,
    onRegion: (String) -> Unit,
    onDecade: (Int?) -> Unit,
    onReleasedAfter: (Int?) -> Unit,
    onReleasedBefore: (Int?) -> Unit,
    onLanguage: (String) -> Unit,
    onCountry: (String) -> Unit,
    onMinRating: (Int) -> Unit,
    onMaxRating: (Int) -> Unit,
    onMinVotes: (Int) -> Unit,
    onKeywordsInclude: (Boolean) -> Unit,
    onKeywordQuery: (String) -> Unit,
    onPickKeyword: (com.kennyb1201.kbstream.data.tmdb.TmdbSearchKeywordResult) -> Unit,
    onRemoveKeyword: (Int, Boolean) -> Unit,
    onPeopleQuery: (String) -> Unit,
    onPickPerson: (com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult) -> Unit,
    onRemovePerson: (Int) -> Unit,
    onRuntimeMin: (Int) -> Unit,
    onRuntimeMax: (Int) -> Unit,
    onCertificationCountry: (String) -> Unit,
    onCertification: (String) -> Unit,
    onTvStatus: (String) -> Unit,
    onTvType: (String) -> Unit,
    onExcludedNetwork: (Int) -> Unit
) {
    val filters = catalog.filters
    var keywordQuery by remember(catalog.id) { mutableStateOf("") }
    var castQuery by remember(catalog.id) { mutableStateOf("") }
    val isTv = catalog.mediaType == CATALOG_MEDIA_TV

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        KBTextField(
            value = catalog.name,
            onValueChange = onNameChange,
            placeholder = "Catalog name (e.g. \"Cosy Sci-Fi Nights\")",
            modifier = Modifier.fillMaxWidth(),
            // Long TV form: focus alone must not raise the full-screen IME.
            openKeyboardOnFocus = false
        )

        EditorSection(title = "Type") {
            ChoiceRow(
                options = listOf(
                    CATALOG_MEDIA_MOVIE to "Movies",
                    CATALOG_MEDIA_TV to "Series"
                ),
                selectedId = catalog.mediaType,
                onSelect = onMediaType
            )
        }

        EditorSection(title = "Sort by") {
            ChoiceRow(
                options = CATALOG_SORT_OPTIONS.map { it.id to it.label },
                selectedId = catalog.sort,
                onSelect = onSort
            )
        }

        EditorSection(title = "Runtime") {
            // Two rows, one rule: how short/long the thing is. On a series
            // this is the episode runtime, which is the length that decides
            // whether a title fits an evening.
            NumberChipRow(
                options = CATALOG_RUNTIME_MIN_OPTIONS,
                selected = filters.withRuntimeGte ?: 0,
                labelOf = { if (it == 0) "Any length" else "$it min+" },
                onSelect = onRuntimeMin
            )
            Spacer(modifier = Modifier.height(6.dp))
            NumberChipRow(
                options = CATALOG_RUNTIME_MAX_OPTIONS,
                selected = filters.withRuntimeLte ?: 0,
                labelOf = { if (it == 0) "Any length" else "up to $it min" },
                onSelect = onRuntimeMax
            )
        }

        EditorSection(title = "Genres") {
            ChipRow(
                options = catalogGenreOptions(catalog.mediaType),
                isSelected = { splitIds(filters.withGenres).contains(it) },
                onToggle = { onGenre(true, it) }
            )
        }

        EditorSection(title = "Exclude genres") {
            ChipRow(
                options = catalogGenreOptions(catalog.mediaType),
                isSelected = { splitIds(filters.withoutGenres).contains(it) },
                onToggle = { onGenre(false, it) }
            )
        }

        EditorSection(title = "Services") {
            ChipRow(
                options = CATALOG_SERVICE_OPTIONS,
                isSelected = { splitIds(filters.withWatchProviders).contains(it) },
                onToggle = { onService(true, it) }
            )
        }

        EditorSection(title = "Exclude services") {
            ChipRow(
                options = CATALOG_SERVICE_OPTIONS,
                isSelected = { splitIds(filters.withoutWatchProviders).contains(it) },
                onToggle = { onService(false, it) }
            )
        }

        EditorSection(title = "Watch region") {
            CodeChipRow(
                options = CATALOG_REGION_OPTIONS,
                isSelected = { it == (filters.watchRegion ?: "") },
                onToggle = onRegion
            )
        }

        EditorSection(title = "Studios") {
            ChipRow(
                options = CATALOG_STUDIO_OPTIONS,
                isSelected = { splitIds(filters.withCompanies).contains(it) },
                onToggle = { onStudio(true, it) }
            )
        }

        EditorSection(title = "Exclude studios") {
            ChipRow(
                options = CATALOG_STUDIO_OPTIONS,
                isSelected = { splitIds(filters.withoutCompanies).contains(it) },
                onToggle = { onStudio(false, it) }
            )
        }

        EditorSection(title = "Networks") {
            ChipRow(
                options = CATALOG_NETWORK_OPTIONS,
                isSelected = { splitIds(filters.withNetworks).contains(it) },
                onToggle = onNetwork
            )
        }

        EditorSection(title = "Exclude networks") {
            ChipRow(
                options = CATALOG_NETWORK_OPTIONS,
                isSelected = { splitIds(filters.withoutNetworks).contains(it) },
                onToggle = onExcludedNetwork
            )
        }

        // TV shape. Drawn only for a series catalog: /discover/movie has no
        // status, type or network filter, and a chip that writes a rule the
        // endpoint ignores is worse than no chip (the media-type switch prunes
        // them off too - see pruneMediaTypeFilters).
        if (isTv) {
            EditorSection(title = "Status") {
                CodeChipRow(
                    options = CATALOG_TV_STATUS_OPTIONS,
                    isSelected = { it == (filters.withStatus ?: "") },
                    onToggle = onTvStatus
                )
            }

            EditorSection(title = "Show type") {
                CodeChipRow(
                    options = CATALOG_TV_TYPE_OPTIONS,
                    isSelected = { it == (filters.withType ?: "") },
                    onToggle = onTvType
                )
            }
        }

        EditorSection(title = "Keywords") {
            ChoiceRow(
                options = listOf("include" to "MATCH", "exclude" to "EXCLUDE"),
                selectedId = if (keywordsInclude) "include" else "exclude",
                onSelect = { onKeywordsInclude(it == "include") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            KBTextField(
                value = keywordQuery,
                onValueChange = {
                    keywordQuery = it
                    onKeywordQuery(it)
                },
                placeholder = "Search keywords (\"time travel\", \"zombie\")",
                modifier = Modifier.fillMaxWidth(),
                openKeyboardOnFocus = false
            )
            if (keywordSuggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ChipRow(
                    options = keywordSuggestions.map {
                        CatalogFilterOption(it.id, it.name)
                    },
                    isSelected = { id ->
                        val list = if (keywordsInclude) {
                            filters.withKeywords
                        } else {
                            filters.withoutKeywords
                        }
                        splitIds(list).contains(id)
                    },
                    onToggle = { id ->
                        keywordSuggestions.firstOrNull { it.id == id }
                            ?.let(onPickKeyword)
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            PickedFilterRow(
                includeLabel = "Matched",
                excludeLabel = "Excluded",
                includeIds = splitIds(filters.withKeywords),
                excludeIds = splitIds(filters.withoutKeywords),
                labelOf = { id -> keywordNames[id] ?: "Keyword #$id" },
                onRemove = onRemoveKeyword
            )
        }

        // Cast is a people picker exactly like Keywords: the rules store an id,
        // so a typed name is resolved through TMDB's person search first.
        // Movie-only - /discover/tv has no person filter.
        if (!isTv) {
            EditorSection(title = "Cast") {
                KBTextField(
                    value = castQuery,
                    onValueChange = {
                        castQuery = it
                        onPeopleQuery(it)
                    },
                    placeholder = "Search actors (\"Tom Hanks\")",
                    modifier = Modifier.fillMaxWidth(),
                    openKeyboardOnFocus = false
                )
                if (peopleSuggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ChipRow(
                        options = peopleSuggestions.map {
                            CatalogFilterOption(it.id, it.name)
                        },
                        isSelected = { id -> splitIds(filters.withCast).contains(id) },
                        onToggle = { id ->
                            peopleSuggestions.firstOrNull { it.id == id }
                                ?.let(onPickPerson)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                PickedFilterRow(
                    includeLabel = "Cast",
                    excludeLabel = "",
                    includeIds = splitIds(filters.withCast),
                    excludeIds = emptyList(),
                    labelOf = { id -> castNames[id] ?: "Person #$id" },
                    emptyText = "No cast picked yet.",
                    onRemove = { id, _ -> onRemovePerson(id) }
                )
            }
        }

        EditorSection(title = "Decade") {
            val decadeStart = (filters.year as? String)
                ?.substringBefore('-')
                ?.trim()
                ?.toIntOrNull()
            ChipRow(
                options = listOf(CatalogFilterOption(0, "Any")) + CATALOG_DECADE_OPTIONS,
                isSelected = { if (it == 0) decadeStart == null else it == decadeStart },
                onToggle = { onDecade(if (it == 0) null else it) }
            )
        }

        EditorSection(title = "Released after") {
            val afterYear = (filters.releaseDateGte as? String)
                ?.take(4)
                ?.toIntOrNull()
            NumberChipRow(
                options = CATALOG_YEAR_BOUND_OPTIONS,
                selected = afterYear ?: 0,
                labelOf = { if (it == 0) "Any" else "$it+" },
                onSelect = { onReleasedAfter(if (it == 0) null else it) }
            )
        }

        EditorSection(title = "Released before") {
            val beforeYear = (filters.releaseDateLte as? String)
                ?.take(4)
                ?.toIntOrNull()
            NumberChipRow(
                options = CATALOG_YEAR_BOUND_OPTIONS,
                selected = beforeYear ?: 0,
                labelOf = { if (it == 0) "Any" else "before $it" },
                onSelect = { onReleasedBefore(if (it == 0) null else it) }
            )
        }

        EditorSection(title = "Original language") {
            CodeChipRow(
                options = CATALOG_LANGUAGE_OPTIONS,
                isSelected = { splitCodes(filters.withOriginalLanguage).contains(it) },
                onToggle = onLanguage
            )
        }

        EditorSection(title = "Origin country") {
            CodeChipRow(
                options = CATALOG_COUNTRY_OPTIONS,
                isSelected = { splitCodes(filters.withOriginCountry).contains(it) },
                onToggle = onCountry
            )
        }

        EditorSection(title = "Age rating") {
            // Scale first, then the value read against it: TMDB resolves
            // `certification` per country, so the two chips are one rule.
            CodeChipRow(
                options = CATALOG_CERTIFICATION_COUNTRY_OPTIONS,
                isSelected = {
                    it == (filters.certificationCountry
                        ?: CATALOG_DEFAULT_CERTIFICATION_COUNTRY)
                },
                onToggle = onCertificationCountry
            )
            Spacer(modifier = Modifier.height(6.dp))
            CodeChipRow(
                options = catalogCertificationOptions(catalog.mediaType),
                isSelected = { it == (filters.certification ?: "") },
                onToggle = onCertification
            )
        }

        EditorSection(title = "Minimum rating") {
            NumberChipRow(
                options = CATALOG_MIN_RATING_OPTIONS,
                selected = filters.voteAverageGte ?: 0,
                labelOf = { if (it == 0) "Any" else "$it+" },
                onSelect = onMinRating
            )
        }

        EditorSection(title = "Maximum rating") {
            NumberChipRow(
                options = CATALOG_MAX_RATING_OPTIONS,
                selected = filters.voteAverageLte ?: 0,
                labelOf = { if (it == 0) "Any" else "up to $it" },
                onSelect = onMaxRating
            )
        }

        EditorSection(title = "Minimum votes") {
            NumberChipRow(
                options = CATALOG_MIN_VOTE_OPTIONS,
                selected = filters.voteCountGte ?: 0,
                labelOf = { if (it == 0) "Any" else "$it+" },
                onSelect = onMinVotes
            )
        }

        EditorSection(title = "Preview") {
            PreviewRow(preview = preview)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** One titled block of the editor, with a hairline above it. */
@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .background(KBAccent, KBShapeSmall)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = title.uppercase(),
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    content()
}

/** A horizontally scrolling row of multi-select id chips. */
@Composable
private fun ChipRow(
    options: List<CatalogFilterOption>,
    isSelected: (Int) -> Boolean,
    onToggle: (Int) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(items = options, key = { it.id }) { option ->
            BuilderChip(
                label = option.label,
                selected = isSelected(option.id),
                onClick = { onToggle(option.id) }
            )
        }
    }
}

/** A horizontally scrolling row of multi-select code chips. */
@Composable
private fun CodeChipRow(
    options: List<CatalogCodeOption>,
    isSelected: (String) -> Boolean,
    onToggle: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(items = options, key = { it.code }) { option ->
            BuilderChip(
                label = option.label,
                selected = isSelected(option.code),
                onClick = { onToggle(option.code) }
            )
        }
    }
}

/** A horizontally scrolling row of single-select chips. */
@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(items = options, key = { it.first }) { (id, label) ->
            BuilderChip(
                label = label,
                selected = id == selectedId,
                onClick = { onSelect(id) }
            )
        }
    }
}

/** A horizontally scrolling row of single-select numeric chips. */
@Composable
private fun NumberChipRow(
    options: List<Int>,
    selected: Int,
    labelOf: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(items = options, key = { it }) { value ->
            BuilderChip(
                label = labelOf(value),
                selected = value == selected,
                onClick = { onSelect(value) }
            )
        }
    }
}

/**
 * The one chip the whole editor is built from.
 *
 * Selected state is drawn on the capsule itself (accent tint + accent border)
 * rather than only on focus, because a rule set is read at a glance: which
 * genres are on has to be visible while the D-pad is somewhere else.
 */
@Composable
private fun BuilderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeSmall),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                KBAccent.copy(alpha = 0.26f)
            } else {
                KBSurface.copy(alpha = 0.80f)
            },
            contentColor = if (selected) KBAccent else KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.04f,
            pressedScale = KBFocusPressed
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (selected) KBAccent else Color.Transparent
                ),
                shape = KBShapeSmall
            )
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/**
 * The picked keyword chips, in their two lists, each removable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickedFilterRow(
    includeLabel: String,
    excludeLabel: String,
    includeIds: List<Int>,
    excludeIds: List<Int>,
    labelOf: (Int) -> String,
    onRemove: (Int, Boolean) -> Unit,
    emptyText: String = "No keywords picked yet."
) {
    if (includeIds.isEmpty() && excludeIds.isEmpty()) {
        Text(
            text = emptyText,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(includeLabel to includeIds, excludeLabel to excludeIds).forEach { (label, ids) ->
            if (ids.isEmpty()) return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(74.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ids.forEach { id ->
                        BuilderChip(
                            label = labelOf(id) + "  \u00d7",
                            selected = true,
                            onClick = { onRemove(id, label == includeLabel) }
                        )
                    }
                }
            }
        }
    }
}

/** The preview shelf: what the current rules actually return. */
@Composable
private fun PreviewRow(preview: CatalogBuilderViewModel.PreviewState) {
    when {
        preview.loading -> Text(
            text = "Running the rules\u2026",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )

        preview.message != null -> Text(
            text = preview.message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )

        preview.items.isEmpty() -> Text(
            text = "Press PREVIEW to see what these rules return.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )

        else -> {
            val context = LocalContext.current
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = preview.items, key = { it.id }) { meta ->
                    PreviewCard(meta = meta, context = context)
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(meta: MetaPreview, context: android.content.Context) {
    Column(modifier = Modifier.width(112.dp)) {
        Surface(
            shape = KBShapeSmall,
            colors = SurfaceDefaults.colors(containerColor = KBSurfaceRaised)
        ) {
            if (meta.poster != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(meta.poster)
                        .crossfade(true)
                        .build(),
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(112.dp)
                        .height(168.dp)
                        .clip(KBShapeSmall)
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(112.dp)
                        .height(168.dp)
                        .background(KBSurface)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = meta.name,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Icon button for the catalog rows. Disabled state is a dimmed, non-clickable
 * capsule rather than a hidden control, so the row's buttons never reflow.
 */
@Composable
private fun CatalogIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = KBTextHi
) {
    if (enabled) {
        KBCard(onClick = onClick) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(38.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else {
        Surface(
            shape = KBShapeCard,
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            ),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = KBTextLo.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(KBSurface, KBShapeCard)
                .border(1.dp, KBDanger.copy(alpha = 0.45f), KBShapeCard)
                .padding(20.dp)
        ) {
            Text(
                text = "DELETE CATALOG",
                color = KBDanger,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Remove \"$name\" and its rail from Home?",
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(label = "DELETE", onClick = onConfirm)
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}
