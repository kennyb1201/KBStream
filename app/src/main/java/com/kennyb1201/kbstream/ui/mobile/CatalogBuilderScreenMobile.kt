package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_MOVIE
import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_TV
import com.kennyb1201.kbstream.data.catalogs.CustomCatalog
import com.kennyb1201.kbstream.data.catalogs.MAX_CUSTOM_CATALOGS
import com.kennyb1201.kbstream.data.catalogs.splitCodes
import com.kennyb1201.kbstream.data.catalogs.splitIds
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchKeywordResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.ui.addons.CATALOG_CERTIFICATION_COUNTRY_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_COUNTRY_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_DECADE_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_DEFAULT_CERTIFICATION_COUNTRY
import com.kennyb1201.kbstream.ui.addons.CATALOG_LANGUAGE_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_MAX_RATING_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_MIN_RATING_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_MIN_VOTE_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_NETWORK_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_REGION_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_RELEASE_TYPE_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_RUNTIME_MAX_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_RUNTIME_MIN_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_SERVICE_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_SORT_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_STUDIO_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_TV_STATUS_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_TV_TYPE_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CATALOG_YEAR_BOUND_OPTIONS
import com.kennyb1201.kbstream.ui.addons.CatalogBuilderViewModel
import com.kennyb1201.kbstream.ui.addons.CatalogCodeOption
import com.kennyb1201.kbstream.ui.addons.CatalogFilterOption
import com.kennyb1201.kbstream.ui.addons.CatalogIdField
import com.kennyb1201.kbstream.ui.addons.catalogCertificationOptions
import com.kennyb1201.kbstream.ui.addons.catalogGenreOptions
import com.kennyb1201.kbstream.ui.addons.catalogGenreSummary
import com.kennyb1201.kbstream.ui.addons.catalogOptionsWithCustom
import com.kennyb1201.kbstream.ui.addons.catalogSummaryLine
import com.kennyb1201.kbstream.ui.addons.parseCustomIds
import com.kennyb1201.kbstream.ui.addons.parseVoteCountInput
import com.kennyb1201.kbstream.ui.addons.voteCountOptionsWithCustom
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/*
 * The Catalog Builder, for a finger.
 *
 * Same activity-scoped [CatalogBuilderViewModel] and the same rule vocabulary:
 * every control writes the one `KBFilters` object the imported collection
 * profiles use and `TmdbRepository.discoverKB` reads, through the view model's
 * own setters. The TV page's helpers - the option lists, the custom-id escape
 * hatch and the summary line - are imported rather than re-derived, so a phone
 * catalog and a TV catalog mean exactly the same thing.
 *
 * The shape: the list is the page (tap a catalog to edit, status pill, move /
 * hide / delete), and the editor is a pushed page with the preview pinned above
 * the form - the TV page's own reason for pinning it, which a phone needs just
 * as much: a PREVIEW press has to change something visible.
 *
 * Nothing is deferred: the form carries every rule the TV editor carries (type,
 * sort, runtime, genres, services, region, studios, networks, TV status/type,
 * keywords, cast, decade, release bounds/type, language, country, both age
 * rules, ratings, votes), including the hand-typed id and vote-count dialogs.
 */

@Composable
fun CatalogBuilderScreenMobile(
    onBack: () -> Unit,
    viewModel: CatalogBuilderViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isNewDraft by viewModel.isNewDraft.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<CustomCatalog?>(null) }

    BackHandler {
        if (draft != null) viewModel.cancelEdit() else onBack()
    }

    MobileTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                BuilderHeaderMobile(
                    title = when {
                        draft == null -> "CATALOGS"
                        isNewDraft -> "NEW CATALOG"
                        else -> "EDIT CATALOG"
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
                    isNewDraft = isNewDraft,
                    onBack = if (draft != null) viewModel::cancelEdit else onBack,
                    onNew = viewModel::startNew,
                    onSave = viewModel::saveDraft,
                    onPreview = viewModel::runPreview
                )

                message?.let { banner ->
                    Text(
                        text = banner,
                        color = KBAccent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .background(KBAccent.copy(alpha = 0.14f), KBShapeCard)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                val current = draft
                if (current == null) {
                    CatalogListMobile(
                        catalogs = state.catalogs,
                        hiddenKeys = state.hiddenKeys,
                        onEdit = viewModel::edit,
                        onToggleHidden = viewModel::toggleHidden,
                        onMove = viewModel::move,
                        onDelete = { pendingDelete = it }
                    )
                } else {
                    PreviewPanelMobile(preview = preview)
                    CatalogEditorMobile(
                        catalog = current,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        pendingDelete?.let { target ->
            Dialog(onDismissRequest = { pendingDelete = null }) {
                KBDialogPanel(title = "Delete catalog") {
                    Text(
                        text = "Remove \"${target.name.ifBlank { "Untitled catalog" }}\" and " +
                            "its rail from Home?",
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        KBButton(
                            label = "CANCEL",
                            modifier = Modifier.weight(1f),
                            onClick = { pendingDelete = null }
                        )
                        KBButton(
                            label = "DELETE",
                            danger = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.delete(target.id)
                                pendingDelete = null
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderHeaderMobile(
    title: String,
    subtitle: String,
    editing: Boolean,
    isNewDraft: Boolean,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onSave: () -> Unit,
    onPreview: () -> Unit
) {        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            KBButton(label = "BACK", onClick = onBack)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = KBAccent,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            if (editing) {
                KBButton(label = "PREVIEW", onClick = onPreview)
                KBButton(
                    label = if (isNewDraft) "CREATE" else "SAVE",
                    onClick = onSave
                )
                KBButton(label = "CANCEL", onClick = onBack)
            } else {
                KBButton(label = "NEW CATALOG", onClick = onNew)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(KBAccent.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun CatalogListMobile(
    catalogs: List<CustomCatalog>,
    hiddenKeys: Set<String>,
    onEdit: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (CustomCatalog) -> Unit
) {
    if (catalogs.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(KBSurface, KBShapeCard)
                .padding(18.dp)
        ) {
            Text(
                text = "Build a rail from filters",
                color = KBTextHi,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "A catalog is a saved set of rules — genres, services, " +
                    "studios, networks, keywords, decade, language, rating — that Home " +
                    "runs fresh every load. Press NEW CATALOG to start.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = catalogs, key = { it.id }) { catalog ->
            val index = catalogs.indexOf(catalog)
            val hidden = hiddenKeys.contains(KBHomeOrderPrefs.customCatalogKey(catalog.id))
            CatalogRowMobile(
                catalog = catalog,
                hidden = hidden,
                canMoveUp = index > 0,
                canMoveDown = index < catalogs.lastIndex,
                onEdit = { onEdit(catalog.id) },
                onToggleHidden = { onToggleHidden(catalog.id) },
                onMoveUp = { onMove(catalog.id, -1) },
                onMoveDown = { onMove(catalog.id, 1) },
                onDelete = { onDelete(catalog) }
            )
        }
        if (catalogs.size >= MAX_CUSTOM_CATALOGS) {
            item(key = "max") {
                Text(
                    text = "That is the maximum of $MAX_CUSTOM_CATALOGS catalogs.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogRowMobile(
    catalog: CustomCatalog,
    hidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onToggleHidden: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    KBCard(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = catalog.name.ifBlank { "Untitled catalog" },
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (hidden) "HIDDEN" else "ON HOME",
                    color = if (hidden) KBTextLo else KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(
                            if (hidden) KBTextHi.copy(alpha = 0.10f) else KBAccent.copy(alpha = 0.18f),
                            KBShapeCard
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text(
                text = catalogSummaryLine(catalog),
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                KBButton(
                    label = if (hidden) "SHOW ON HOME" else "HIDE FROM HOME",
                    onClick = onToggleHidden
                )
                KBButton(label = "UP", enabled = canMoveUp, onClick = onMoveUp)
                KBButton(label = "DOWN", enabled = canMoveDown, onClick = onMoveDown)
                KBButton(label = "DELETE", danger = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun PreviewPanelMobile(preview: CatalogBuilderViewModel.PreviewState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(KBSurface, KBShapeCard)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = if (preview.items.isEmpty()) {
                "PREVIEW"
            } else {
                "PREVIEW · ${preview.items.size} TITLES"
            },
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        when {
            preview.loading -> Text(
                text = "Running the rules…",
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

            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = preview.items, key = { it.id }) { meta ->
                    Column(modifier = Modifier.width(112.dp)) {
                        if (meta.poster != null) {
                            AsyncImage(
                                model = meta.poster,
                                contentDescription = meta.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(112.dp)
                                    .height(168.dp)
                                    .background(KBSurface)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(112.dp)
                                    .height(168.dp)
                                    .background(KBSurface)
                            )
                        }
                        Text(
                            text = meta.name,
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogEditorMobile(
    catalog: CustomCatalog,
    viewModel: CatalogBuilderViewModel,
    modifier: Modifier = Modifier
) {
    val filters = catalog.filters
    val keywordSuggestions by viewModel.keywordSuggestions.collectAsStateWithLifecycle()
    val keywordNames by viewModel.keywordNames.collectAsStateWithLifecycle()
    val keywordsInclude by viewModel.keywordsInclude.collectAsStateWithLifecycle()
    val peopleSuggestions by viewModel.peopleSuggestions.collectAsStateWithLifecycle()
    val castNames by viewModel.castNames.collectAsStateWithLifecycle()

    var keywordQuery by remember(catalog.id) { mutableStateOf("") }
    var castQuery by remember(catalog.id) { mutableStateOf("") }
    var customField by remember(catalog.id) { mutableStateOf<CatalogIdField?>(null) }
    var customVotes by remember(catalog.id) { mutableStateOf(false) }

    val isTv = catalog.mediaType == CATALOG_MEDIA_TV

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        KBTextField(
            value = catalog.name,
            onValueChange = viewModel::setName,
            placeholder = "Catalog name (e.g. \"Cosy Sci-Fi Nights\")",
            modifier = Modifier.fillMaxWidth()
        )

        BuilderSectionMobile("Type") {
            BuilderChoicesMobile(
                options = listOf(
                    CATALOG_MEDIA_MOVIE to "Movies",
                    CATALOG_MEDIA_TV to "Series"
                ),
                selected = catalog.mediaType,
                onSelect = viewModel::setMediaType
            )
        }

        BuilderSectionMobile("Sort by") {
            BuilderChoicesMobile(
                options = CATALOG_SORT_OPTIONS.map { it.id to it.label },
                selected = catalog.sort,
                onSelect = viewModel::setSort
            )
        }

        BuilderSectionMobile("Runtime") {
            BuilderNumbersMobile(
                options = CATALOG_RUNTIME_MIN_OPTIONS,
                selected = filters.withRuntimeGte ?: 0,
                labelOf = { if (it == 0) "Any length" else "$it min+" },
                onSelect = viewModel::setRuntimeMin
            )
            Spacer(modifier = Modifier.height(6.dp))
            BuilderNumbersMobile(
                options = CATALOG_RUNTIME_MAX_OPTIONS,
                selected = filters.withRuntimeLte ?: 0,
                labelOf = { if (it == 0) "Any length" else "up to $it min" },
                onSelect = viewModel::setRuntimeMax
            )
        }

        BuilderSectionMobile("Genres") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    catalogGenreOptions(catalog.mediaType),
                    splitIds(filters.withGenres)
                ),
                picked = splitIds(filters.withGenres),
                onToggle = { viewModel.toggleGenre(true, it) },
                onCustom = { customField = CatalogIdField.GENRES }
            )
        }

        BuilderSectionMobile("Exclude genres") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    catalogGenreOptions(catalog.mediaType),
                    splitIds(filters.withoutGenres)
                ),
                picked = splitIds(filters.withoutGenres),
                onToggle = { viewModel.toggleGenre(false, it) },
                onCustom = { customField = CatalogIdField.EXCLUDE_GENRES }
            )
        }

        BuilderSectionMobile("Services") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    CATALOG_SERVICE_OPTIONS,
                    splitIds(filters.withWatchProviders)
                ),
                picked = splitIds(filters.withWatchProviders),
                onToggle = { viewModel.toggleService(true, it) },
                onCustom = { customField = CatalogIdField.SERVICES }
            )
        }

        BuilderSectionMobile("Exclude services") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    CATALOG_SERVICE_OPTIONS,
                    splitIds(filters.withoutWatchProviders)
                ),
                picked = splitIds(filters.withoutWatchProviders),
                onToggle = { viewModel.toggleService(false, it) },
                onCustom = { customField = CatalogIdField.EXCLUDE_SERVICES }
            )
        }

        BuilderSectionMobile("Watch region") {
            BuilderCodesMobile(
                options = CATALOG_REGION_OPTIONS,
                selected = filters.watchRegion ?: "",
                onSelect = viewModel::setRegion
            )
        }

        BuilderSectionMobile("Studios") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    CATALOG_STUDIO_OPTIONS,
                    splitIds(filters.withCompanies)
                ),
                picked = splitIds(filters.withCompanies),
                onToggle = { viewModel.toggleStudio(true, it) },
                onCustom = { customField = CatalogIdField.STUDIOS }
            )
        }

        BuilderSectionMobile("Exclude studios") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    CATALOG_STUDIO_OPTIONS,
                    splitIds(filters.withoutCompanies)
                ),
                picked = splitIds(filters.withoutCompanies),
                onToggle = { viewModel.toggleStudio(false, it) },
                onCustom = { customField = CatalogIdField.EXCLUDE_STUDIOS }
            )
        }

        BuilderSectionMobile("Networks") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    CATALOG_NETWORK_OPTIONS,
                    splitIds(filters.withNetworks)
                ),
                picked = splitIds(filters.withNetworks),
                onToggle = viewModel::toggleNetwork,
                onCustom = { customField = CatalogIdField.NETWORKS }
            )
        }

        BuilderSectionMobile("Exclude networks") {
            BuilderFilterChipsMobile(
                options = catalogOptionsWithCustom(
                    CATALOG_NETWORK_OPTIONS,
                    splitIds(filters.withoutNetworks)
                ),
                picked = splitIds(filters.withoutNetworks),
                onToggle = viewModel::toggleExcludedNetwork,
                onCustom = { customField = CatalogIdField.EXCLUDE_NETWORKS }
            )
        }

        if (isTv) {
            BuilderSectionMobile("Status") {
                BuilderCodesMobile(
                    options = CATALOG_TV_STATUS_OPTIONS,
                    selected = filters.withStatus ?: "",
                    onSelect = viewModel::setTvStatus
                )
            }
            BuilderSectionMobile("Show type") {
                BuilderCodesMobile(
                    options = CATALOG_TV_TYPE_OPTIONS,
                    selected = filters.withType ?: "",
                    onSelect = viewModel::setTvType
                )
            }
        }

        BuilderSectionMobile("Keywords") {
            BuilderChoicesMobile(
                options = listOf("include" to "MATCH", "exclude" to "EXCLUDE"),
                selected = if (keywordsInclude) "include" else "exclude",
                onSelect = { viewModel.setKeywordsInclude(it == "include") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            KBTextField(
                value = keywordQuery,
                onValueChange = {
                    keywordQuery = it
                    viewModel.searchKeywords(it)
                },
                placeholder = "Search keywords (\"time travel\", \"zombie\")",
                modifier = Modifier.fillMaxWidth()
            )
            if (keywordSuggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    keywordSuggestions.forEach { keyword: TmdbSearchKeywordResult ->
                        val list = if (keywordsInclude) {
                            filters.withKeywords
                        } else {
                            filters.withoutKeywords
                        }
                        BuilderChipMobile(
                            label = keyword.name,
                            selected = splitIds(list).contains(keyword.id),
                            onClick = { viewModel.pickKeyword(keyword) }
                        )
                    }
                }
            }
            PickedChipsMobile(
                includeLabel = "Matched",
                excludeLabel = "Excluded",
                includeIds = splitIds(filters.withKeywords),
                excludeIds = splitIds(filters.withoutKeywords),
                labelOf = { id -> keywordNames[id] ?: "Keyword #$id" },
                onRemove = viewModel::removeKeyword
            )
        }

        if (!isTv) {
            BuilderSectionMobile("Cast") {
                KBTextField(
                    value = castQuery,
                    onValueChange = {
                        castQuery = it
                        viewModel.searchPeople(it)
                    },
                    placeholder = "Search actors (\"Tom Hanks\")",
                    modifier = Modifier.fillMaxWidth()
                )
                if (peopleSuggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        peopleSuggestions.forEach { person: TmdbSearchPersonResult ->
                            BuilderChipMobile(
                                label = person.name,
                                selected = splitIds(filters.withCast).contains(person.id),
                                onClick = { viewModel.pickPerson(person) }
                            )
                        }
                    }
                }
                PickedChipsMobile(
                    includeLabel = "Cast",
                    excludeLabel = "",
                    includeIds = splitIds(filters.withCast),
                    excludeIds = emptyList(),
                    labelOf = { id -> castNames[id] ?: "Person #$id" },
                    emptyText = "No cast picked yet.",
                    onRemove = { id, _ -> viewModel.removePerson(id) }
                )
            }
        }

        BuilderSectionMobile("Decade") {
            val decadeStart = (filters.year as? String)
                ?.substringBefore('-')
                ?.trim()
                ?.toIntOrNull()
            BuilderFilterChipsMobile(
                options = listOf(CatalogFilterOption(0, "Any")) + CATALOG_DECADE_OPTIONS,
                picked = listOf(decadeStart ?: 0),
                onToggle = { id ->
                    viewModel.setDecade(if (id == 0) null else id)
                },
                onCustom = null
            )
        }

        BuilderSectionMobile("Released after") {
            val afterYear = (filters.releaseDateGte as? String)
                ?.take(4)
                ?.toIntOrNull()
            BuilderNumbersMobile(
                options = CATALOG_YEAR_BOUND_OPTIONS,
                selected = afterYear ?: 0,
                labelOf = { if (it == 0) "Any" else "$it+" },
                onSelect = { viewModel.setReleasedAfter(if (it == 0) null else it) }
            )
        }

        BuilderSectionMobile("Released before") {
            val beforeYear = (filters.releaseDateLte as? String)
                ?.take(4)
                ?.toIntOrNull()
            BuilderNumbersMobile(
                options = CATALOG_YEAR_BOUND_OPTIONS,
                selected = beforeYear ?: 0,
                labelOf = { if (it == 0) "Any" else "before $it" },
                onSelect = { viewModel.setReleasedBefore(if (it == 0) null else it) }
            )
        }

        if (!isTv) {
            BuilderSectionMobile("Release type") {
                BuilderCodesMobile(
                    options = CATALOG_RELEASE_TYPE_OPTIONS,
                    selected = filters.withReleaseType.orEmpty(),
                    onSelect = viewModel::setReleaseType
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Digital release is the one that means a title is " +
                        "watchable at home rather than only in cinemas.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        BuilderSectionMobile("Original language") {
            BuilderCodesMobile(
                options = CATALOG_LANGUAGE_OPTIONS,
                selectedCodes = splitCodes(filters.withOriginalLanguage).toSet(),
                onSelect = viewModel::toggleLanguage
            )
        }

        BuilderSectionMobile("Origin country") {
            BuilderCodesMobile(
                options = CATALOG_COUNTRY_OPTIONS,
                selectedCodes = splitCodes(filters.withOriginCountry).toSet(),
                onSelect = viewModel::toggleCountry
            )
        }

        BuilderSectionMobile("Age rating") {
            BuilderCodesMobile(
                options = CATALOG_CERTIFICATION_COUNTRY_OPTIONS,
                selected = filters.certificationCountry
                    ?: CATALOG_DEFAULT_CERTIFICATION_COUNTRY,
                onSelect = viewModel::setCertificationCountry
            )
            Spacer(modifier = Modifier.height(6.dp))
            BuilderCodesMobile(
                options = catalogCertificationOptions(catalog.mediaType),
                selected = filters.certification ?: "",
                onSelect = viewModel::setCertification
            )
        }

        BuilderSectionMobile("Age rating or milder") {
            BuilderCodesMobile(
                options = catalogCertificationOptions(catalog.mediaType),
                selected = filters.certificationLte ?: "",
                onSelect = viewModel::setCertificationCeiling
            )
        }

        BuilderSectionMobile("Minimum rating") {
            BuilderNumbersMobile(
                options = CATALOG_MIN_RATING_OPTIONS,
                selected = filters.voteAverageGte ?: 0,
                labelOf = { if (it == 0) "Any" else "$it+" },
                onSelect = viewModel::setMinRating
            )
        }

        BuilderSectionMobile("Maximum rating") {
            BuilderNumbersMobile(
                options = CATALOG_MAX_RATING_OPTIONS,
                selected = filters.voteAverageLte ?: 0,
                labelOf = { if (it == 0) "Any" else "up to $it" },
                onSelect = viewModel::setMaxRating
            )
        }

        BuilderSectionMobile("Minimum votes") {
            BuilderNumbersMobile(
                options = voteCountOptionsWithCustom(
                    CATALOG_MIN_VOTE_OPTIONS,
                    filters.voteCountGte
                ),
                selected = filters.voteCountGte ?: 0,
                labelOf = { if (it == 0) "Any" else "$it+" },
                onSelect = viewModel::setMinVotes,
                onCustom = { customVotes = true }
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }

    customField?.let { field ->
        CustomIdDialogMobile(
            field = field,
            onAdd = { ids ->
                viewModel.addCustomIds(field, ids)
                customField = null
            },
            onDismiss = { customField = null }
        )
    }

    if (customVotes) {
        CustomVoteCountDialogMobile(
            initial = filters.voteCountGte,
            onSet = { value ->
                viewModel.setMinVotes(value)
                customVotes = false
            },
            onDismiss = { customVotes = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderSectionMobile(title: String, content: @Composable () -> Unit) {
    Text(
        text = title.uppercase(),
        color = KBAccent,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
    content()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderFilterChipsMobile(
    options: List<CatalogFilterOption>,
    picked: List<Int>,
    onToggle: (Int) -> Unit,
    onCustom: (() -> Unit)?
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            BuilderChipMobile(
                label = option.label,
                selected = option.id in picked,
                onClick = { onToggle(option.id) }
            )
        }
        if (onCustom != null) {
            BuilderChipMobile(
                label = "+ OTHER ID",
                selected = false,
                onClick = onCustom
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderCodesMobile(
    options: List<CatalogCodeOption>,
    selected: String,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            BuilderChipMobile(
                label = option.label,
                selected = option.code == selected,
                onClick = { onSelect(option.code) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderCodesMobile(
    options: List<CatalogCodeOption>,
    selectedCodes: Set<String>,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            BuilderChipMobile(
                label = option.label,
                selected = option.code in selectedCodes,
                onClick = { onSelect(option.code) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderChoicesMobile(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            BuilderChipMobile(
                label = label,
                selected = value == selected,
                onClick = { onSelect(value) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderNumbersMobile(
    options: List<Int>,
    selected: Int,
    labelOf: (Int) -> String,
    onSelect: (Int) -> Unit,
    onCustom: (() -> Unit)? = null
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { value ->
            BuilderChipMobile(
                label = labelOf(value),
                selected = value == selected,
                onClick = { onSelect(value) }
            )
        }
        if (onCustom != null) {
            BuilderChipMobile(label = "+ CUSTOM", selected = false, onClick = onCustom)
        }
    }
}

@Composable
private fun BuilderChipMobile(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        },
        shape = KBShapeCard,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = KBTextHi.copy(alpha = 0.06f),
            labelColor = KBTextLo,
            selectedContainerColor = KBAccent.copy(alpha = 0.24f),
            selectedLabelColor = KBAccent
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickedChipsMobile(
    includeLabel: String,
    excludeLabel: String,
    includeIds: List<Int>,
    excludeIds: List<Int>,
    labelOf: (Int) -> String,
    onRemove: (Int, Boolean) -> Unit,
    emptyText: String = "Nothing picked yet."
) {
    if (includeIds.isEmpty() && excludeIds.isEmpty()) {
        Text(
            text = emptyText,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        includeIds.forEach { id ->
            BuilderChipMobile(
                label = "× $includeLabel: ${labelOf(id)}",
                selected = true,
                onClick = { onRemove(id, true) }
            )
        }
        excludeIds.forEach { id ->
            BuilderChipMobile(
                label = "× $excludeLabel: ${labelOf(id)}",
                selected = true,
                onClick = { onRemove(id, false) }
            )
        }
    }
}

@Composable
private fun CustomIdDialogMobile(
    field: CatalogIdField,
    onAdd: (List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val ids = parseCustomIds(text)

    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = "Enter an ID — ${field.label}",
            subtitle = "TMDB id(s) for this filter. \"8\", \"#8\" and a TMDB page URL " +
                "all work; separate several with commas."
        ) {
            KBTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "e.g. 8, 337",
                onDone = { if (ids.isNotEmpty()) onAdd(ids) }
            )
            Text(
                text = if (ids.isEmpty()) {
                    "No ids in that yet."
                } else {
                    "Adds: " + ids.joinToString(", ") { "#$it" }
                },
                color = if (ids.isEmpty()) KBTextLo else KBAccent,
                style = MaterialTheme.typography.labelSmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                KBButton(
                    label = "ADD",
                    enabled = ids.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = { onAdd(ids) }
                )
                KBButton(
                    label = "CANCEL",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun CustomVoteCountDialogMobile(
    initial: Int?,
    onSet: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember {
        mutableStateOf(initial?.takeIf { it > 0 }?.toString().orEmpty())
    }
    val value = parseVoteCountInput(text)

    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = "Minimum votes — your own",
            subtitle = "A high floor hides obscure titles with a handful of votes, " +
                "which is what makes a rating filter trustworthy."
        ) {
            KBTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "e.g. 2500",
                keyboardType = KeyboardType.Number,
                onDone = { value?.let(onSet) }
            )
            Text(
                text = value?.let { "Titles with at least $it votes." }
                    ?: "Type a positive number of votes.",
                color = if (value == null) KBTextLo else KBAccent,
                style = MaterialTheme.typography.labelSmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                KBButton(
                    label = "SET",
                    enabled = value != null,
                    modifier = Modifier.weight(1f),
                    onClick = { value?.let(onSet) }
                )
                KBButton(
                    label = "CANCEL",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
            }
        }
    }
}
