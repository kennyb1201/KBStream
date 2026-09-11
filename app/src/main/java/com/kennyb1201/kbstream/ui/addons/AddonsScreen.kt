package com.kennyb1201.kbstream.ui.addons

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.addon.ManifestCatalog
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrderPrefs
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSuccess
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/** Actions for reordering a catalog within its addon. */
private enum class CatalogMoveAction {
    TOP,
    UP,
    DOWN,
    BOTTOM
}

/**
 * Per-row focus anchors for the catalog manager dialog. Reordering a row
 * recomposes it under a new item key (often off-screen), which disposes the
 * focused button and throws D-pad focus back at the dialog header. After a
 * move we scroll to the row's new index and re-focus the SAME button there.
 */
private class CatalogRowFocus {
    enum class Slot { TOGGLE, TOP, UP, DOWN, BOTTOM }

    val toggle = FocusRequester()
    val top = FocusRequester()
    val up = FocusRequester()
    val down = FocusRequester()
    val bottom = FocusRequester()

    fun of(slot: Slot): FocusRequester? = when (slot) {
        Slot.TOGGLE -> toggle
        Slot.TOP -> top
        Slot.UP -> up
        Slot.DOWN -> down
        Slot.BOTTOM -> bottom
    }
}


@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    viewModel: AddonsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val addons by viewModel.addons.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val status by viewModel.status.collectAsState()
    val health by viewModel.health.collectAsState()
    val checkingHealth by viewModel.checkingHealth.collectAsState()
    val catalogConfigurations by viewModel.catalogConfigurations.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showAddPanel by remember { mutableStateOf(false) }
    var showRenamePanel by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var filterQuery by remember { mutableStateOf("") }
    var showFilterDialog by remember { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf("") }
    var showCatalogManager by remember { mutableStateOf(false) }
    var renameCatalogTarget by remember { mutableStateOf<CatalogConfiguration?>(null) }
    var renameCatalogDraft by remember { mutableStateOf("") }

    val selectedAddon = addons.firstOrNull { it.id == selectedId }

    val filteredAddons = remember(addons, filterQuery) {
        val q = filterQuery.trim().lowercase()
        if (q.isEmpty()) {
            addons
        } else {
            addons.filter { addon ->
                addon.displayName.lowercase().contains(q) ||
                    addon.id.lowercase().contains(q) ||
                    addon.resources.any { it.lowercase().contains(q) }
            }
        }
    }

    // Right from an add-on list card must land on the detail panel's first
    // action button (UP), never the top-bar REFRESH ALL: with a tall detail
    // panel the spatial search used to find the top bar as "nearest right".
    val firstActionFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 30.dp, vertical = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ADD-ONS",
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when (addons.size) {
                            0 -> "No add-ons installed"
                            1 -> "1 add-on installed"
                            else -> "${addons.size} add-ons installed"
                        },
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }

                ActionButton(
                    label = if (refreshing) "REFRESHING" else "REFRESH ALL",
                    icon = Icons.Filled.Refresh,
                    enabled = !refreshing && !isLoading,
                    onClick = viewModel::refreshAllManifests
                )
                Spacer(modifier = Modifier.width(8.dp))
                ActionButton(
                    label = if (checkingHealth) "CHECKING" else "CHECK HEALTH",
                    icon = Icons.Filled.CheckCircle,
                    enabled = !checkingHealth && !refreshing && !isLoading,
                    onClick = viewModel::checkHealth
                )
                Spacer(modifier = Modifier.width(8.dp))
                ActionButton(
                    label = "ADD",
                    icon = Icons.Filled.Add,
                    onClick = { showAddPanel = true }
                )
                Spacer(modifier = Modifier.width(8.dp))
                ActionButton(
                    label = "HOME",
                    icon = Icons.Filled.List,
                    onClick = { showCatalogManager = true }
                )
                Spacer(modifier = Modifier.width(8.dp))
                ActionButton(label = "BACK", onClick = onBack)
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(KBAccent.copy(alpha = 0.35f))
            )

            error?.let {
                StatusBanner(
                    text = it,
                    isError = true,
                    onDismiss = viewModel::clearError,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            status?.let {
                StatusBanner(
                    text = it,
                    isError = false,
                    onDismiss = viewModel::clearStatus,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (addons.isEmpty()) {
                EmptyAddons(
                    onAdd = { showAddPanel = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AddonFilterField(
                    query = filterQuery,
                    onClick = {
                        filterDraft = filterQuery
                        showFilterDialog = true
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Surface(
                        colors = SurfaceDefaults.colors(
                            containerColor = KBSurface.copy(alpha = 0.94f),
                            contentColor = KBTextHi
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(0.55f).fillMaxHeight()
                    ) {
                        if (filteredAddons.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No add-ons match the filter",
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.fillMaxSize().focusGroup()
                            ) {
                                items(
                                    items = filteredAddons,
                                    key = { addon -> addon.id }
                                ) { addon ->
                                    AddonListCard(
                                        addon = addon,
                                        health = health[addon.id],
                                        selected = addon.id == selectedId,
                                        onRight = {
                                            // Deterministic Right: straight to the
                                            // detail panel's first action button.
                                            runCatching {
                                                firstActionFocusRequester.requestFocus()
                                            }.onFailure {
                                                // Panel not composed (shouldn't happen
                                                // with a selected addon) — fall back to
                                                // normal spatial navigation.
                                                focusManager.moveFocus(FocusDirection.Right)
                                            }
                                        },
                                        onClick = {
                                            selectedId = addon.id
                                            renameText = addon.customName ?: addon.name
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        colors = SurfaceDefaults.colors(
                            containerColor = KBSurface.copy(alpha = 0.94f),
                            contentColor = KBTextHi
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(0.45f).fillMaxHeight()
                    ) {
                        if (selectedAddon == null) {
                            AddonDetailPlaceholder()
                        } else {
                            AddonDetails(
                                addon = selectedAddon,
                                health = health[selectedAddon.id],
                                refreshing = refreshing,
                                onMoveUp = { viewModel.moveAddonUp(selectedAddon.id) },
                                onMoveDown = { viewModel.moveAddonDown(selectedAddon.id) },
                                onRename = {
                                    renameText = selectedAddon.customName ?: selectedAddon.name
                                    showRenamePanel = true
                                },
                                onOpenManifest = {
                                    openManifest(context, selectedAddon.manifestUrl) {
                                        viewModel.clearError()
                                    }
                                },
                                onRefresh = { viewModel.refreshManifest(selectedAddon.id) },
                                onRemove = { showRemoveConfirm = true },
                                firstActionFocusRequester = firstActionFocusRequester
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterDialog) {
        FilterAddonsDialog(
            query = filterDraft,
            onQueryChange = { filterDraft = it },
            onApply = {
                filterQuery = filterDraft.trim()
                showFilterDialog = false
            },
            onClear = {
                filterQuery = ""
                filterDraft = ""
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )
    }

    if (showAddPanel) {
        AddAddonDialog(
            url = urlInput,
            isLoading = isLoading,
            onUrlChange = { urlInput = it },
            onDismiss = {
                showAddPanel = false
                urlInput = ""
            },
            onAdd = {
                viewModel.addAddon(urlInput)
                showAddPanel = false
                urlInput = ""
            },
            onAddPreset = { presetUrl ->
                viewModel.addAddon(presetUrl)
                showAddPanel = false
                urlInput = ""
            }
        )
    }

    if (showRenamePanel && selectedAddon != null) {
        RenameAddonDialog(
            currentName = renameText,
            onNameChange = { renameText = it },
            onDismiss = { showRenamePanel = false },
            onSave = {
                viewModel.renameAddon(selectedAddon.id, renameText)
                showRenamePanel = false
            }
        )
    }

    if (showRemoveConfirm && selectedAddon != null) {
        ConfirmRemoveDialog(
            addonName = selectedAddon.displayName,
            onDismiss = { showRemoveConfirm = false },
            onConfirm = {
                viewModel.removeAddon(selectedAddon.id)
                selectedId = null
                showRemoveConfirm = false
            }
        )
    }

    val collectionsState by viewModel.collections.collectAsState()
    var collectionUrlInput by remember { mutableStateOf("") }
    val collectionFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val json = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()
            if (json != null) {
                viewModel.importCollectionProfileJson(json)
            } else {
                viewModel.onCollectionImportFileError()
            }
        }
    }

    if (showCatalogManager) {
        CatalogManagerDialog(
            configurations = catalogConfigurations,
            collectionsState = collectionsState,
            collectionUrlInput = collectionUrlInput,
            onCollectionUrlChange = { collectionUrlInput = it },
            onImportCollectionUrl = {
                if (collectionUrlInput.isNotBlank()) {
                    viewModel.addCollectionProfileUrl(collectionUrlInput.trim())
                    collectionUrlInput = ""
                }
            },
            onPickCollectionFile = {
                collectionFilePicker.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
            },
            onRemoveCollectionProfile = { url ->
                viewModel.removeCollectionProfileUrl(url)
            },
            onToggle = { config, show ->
                viewModel.setCatalogShowOnHome(
                    config.addonId,
                    config.catalog.type,
                    config.catalog.id,
                    show
                )
            },
            onToggleAll = { show ->
                viewModel.setAllCatalogsShowOnHome(show)
            },
            onMove = { config, action ->
                when (action) {
                    CatalogMoveAction.TOP ->
                        viewModel.moveCatalogGlobalToPosition(
                            config.addonId,
                            config.catalog.type,
                            config.catalog.id,
                            0
                        )

                    CatalogMoveAction.UP ->
                        viewModel.moveCatalogGlobal(
                            config.addonId,
                            config.catalog.type,
                            config.catalog.id,
                            -1
                        )

                    CatalogMoveAction.DOWN ->
                        viewModel.moveCatalogGlobal(
                            config.addonId,
                            config.catalog.type,
                            config.catalog.id,
                            1
                        )

                    CatalogMoveAction.BOTTOM ->
                        viewModel.moveCatalogGlobalToPosition(
                            config.addonId,
                            config.catalog.type,
                            config.catalog.id,
                            (catalogConfigurations.size - 1).coerceAtLeast(0)
                        )
                }
            },
            onCollectionPin = { key -> viewModel.toggleCollectionPinned(key) },
            onCollectionHide = { key -> viewModel.toggleCollectionHidden(key) },
            onCollectionMove = { key, delta -> viewModel.moveCollection(key, delta) },
            onRename = { config ->
                renameCatalogDraft = config.catalog.displayName
                renameCatalogTarget = config
            },
            onDismiss = { showCatalogManager = false }
        )
    }

    renameCatalogTarget?.let { target ->
        RenameCatalogDialog(
            currentName = renameCatalogDraft,
            hasCustomName = target.catalog.customName != null,
            onNameChange = { renameCatalogDraft = it },
            onDismiss = { renameCatalogTarget = null },
            onSave = {
                if (renameCatalogDraft.trim().isNotEmpty()) {
                    viewModel.renameCatalog(
                        target.addonId,
                        target.catalog.type,
                        target.catalog.id,
                        renameCatalogDraft
                    )
                }
                renameCatalogTarget = null
            },
            onReset = {
                viewModel.clearCatalogName(
                    target.addonId,
                    target.catalog.type,
                    target.catalog.id
                )
                renameCatalogTarget = null
            }
        )
    }
}

@Composable
private fun AddonListCard(
    addon: InstalledAddon,
    selected: Boolean,
    onClick: () -> Unit,
    health: AddonsViewModel.AddonHealth? = null,
    onRight: (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) KBSurfaceRaised else KBSurfaceRaised.copy(alpha = 0.72f),
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) KBAccent.copy(alpha = 0.4f) else Color.Transparent
                ),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = KBAccent, elevation = 6.dp)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onRight != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        val handler = onRight
                        if (handler != null &&
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionRight
                        ) {
                            // Deterministic Right → detail panel's action buttons,
                            // never spatial-drifts to REFRESH ALL.
                            handler()
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            AddonTile(
                name = addon.displayName,
                logoUrl = addon.logo,
                size = 42.dp,
                fontSize = 20.sp
            )

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(
                    text = addon.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = addon.resources
                        .map { it.uppercase() }
                        .joinToString("  •  ")
                        .ifBlank { "NO RESOURCES" },
                    style = MaterialTheme.typography.labelSmall,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            if (addon.catalogs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .background(KBAccent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                        .border(1.dp, KBAccent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${addon.catalogs.size} CATALOG${if (addon.catalogs.size == 1) "" else "S"}",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            health?.let { h ->
                HealthBadge(healthy = h.healthy)
            }
        }
    }
}

@Composable
private fun HealthBadge(healthy: Boolean) {
    val color = if (healthy) KBSuccess else KBDanger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, RoundedCornerShape(50))
        )
        Text(
            text = if (healthy) "OK" else "OFFLINE",
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}

@Composable
private fun AddonDetails(
    addon: InstalledAddon,
    health: AddonsViewModel.AddonHealth?,
    refreshing: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onOpenManifest: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    firstActionFocusRequester: FocusRequester? = null
) {
    val catalogScrollState = rememberScrollState()

    // One scrollable column for the whole panel: with a big description or
    // a long TYPES line the fixed layout pushed the action buttons past the
    // panel's bottom edge and clipped them. verticalScroll + focusable
    // children means Compose scrolls the focused button into view — actions
    // are always reachable no matter how much info the add-on carries.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .verticalScroll(catalogScrollState)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            AddonTile(
                name = addon.displayName,
                logoUrl = addon.logo,
                size = 56.dp,
                fontSize = 26.sp
            )

            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = addon.displayName,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (addon.customName != null) {
                    Text(
                        text = "CUSTOM NAME",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        addon.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailLine(
                label = "ID",
                value = addon.id,
                modifier = Modifier.weight(1f)
            )
            DetailLine(
                label = "VERSION",
                value = addon.version ?: "—",
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailLine(
                label = "RESOURCES",
                value = addon.resources.joinToString(", ").ifBlank { "—" },
                modifier = Modifier.weight(1f)
            )
            DetailLine(
                label = "CATALOGS",
                value = addon.catalogs.size.toString(),
                modifier = Modifier.weight(1f)
            )
        }
        DetailLine("TYPES", addon.types.joinToString(", ").ifBlank { "—" })
        DetailLine(
            "STATUS",
            when (health) {
                null -> "Unknown — CHECK HEALTH to verify"
                else -> if (health.healthy) "Online" else "Offline"
            }
        )

        // Action buttons stay pinned above the catalog list so they're always
        // reachable even with dozens of catalogs.
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ADD-ON ACTIONS",
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SmallAction(
                label = "UP",
                icon = Icons.Filled.ArrowUpward,
                onClick = onMoveUp,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (firstActionFocusRequester != null) {
                            Modifier.focusRequester(firstActionFocusRequester)
                        } else {
                            Modifier
                        }
                    )
            )
            SmallAction(
                label = "DOWN",
                icon = Icons.Filled.ArrowDownward,
                onClick = onMoveDown,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SmallAction(
                label = "RENAME",
                icon = Icons.Filled.Edit,
                onClick = onRename,
                modifier = Modifier.weight(1f)
            )
            SmallAction(
                label = "OPEN URL",
                icon = Icons.Filled.OpenInNew,
                onClick = onOpenManifest,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SmallAction(
                label = if (refreshing) "REFRESHING..." else "REFRESH",
                icon = Icons.Filled.Refresh,
                enabled = !refreshing,
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = "REMOVE",
                icon = Icons.Filled.Delete,
                onClick = onRemove,
                modifier = Modifier.weight(1f),
                horizontalPadding = 14.dp
            )
        }

        if (addon.catalogs.isEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "This add-on has no catalogs. Use HOME to manage them.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "MANIFEST",
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = addon.manifestUrl,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(84.dp)
        )
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp = 13.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 6.dp,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge
) {
    if (enabled) {
        KBCard(
            onClick = onClick,
            modifier = modifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding
                )
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }
                Text(
                    text = label,
                    style = textStyle,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            ),
            modifier = modifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding
                )
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }
                Text(
                    text = label,
                    style = textStyle,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SmallAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (enabled) {
        KBCard(onClick = onClick, modifier = modifier) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            ),
            modifier = modifier
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }
    }
}

private val AddonPresets = listOf(
    "Cinemeta" to "https://v3-cinemeta.strem.io/manifest.json",
    "WatchHub" to "https://watchhub.strem.fun/manifest.json"
)

@Composable
private fun AddonTile(
    name: String,
    logoUrl: String?,
    size: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val initial =
        remember(name) {
            name.trim()
                .firstOrNull()
                ?.uppercase()
                ?: "?"
        }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(KBVoid),
        contentAlignment = Alignment.Center
    ) {
        if (logoUrl.isNullOrBlank()) {
            // No icon in the manifest — fall back to the initial letter.
            AddonTileLetter(
                initial = initial,
                fontSize = fontSize
            )
        } else {
            // Use the addon's icon in place of the letter. The letter only
            // shows while the image loads or if it fails to load.
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(logoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.16f),
                loading = {
                    AddonTileLetter(
                        initial = initial,
                        fontSize = fontSize
                    )
                },
                error = {
                    AddonTileLetter(
                        initial = initial,
                        fontSize = fontSize
                    )
                }
            )
        }
    }
}

@Composable
private fun AddonTileLetter(
    initial: String,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    Text(
        text = initial,
        color = KBTextHi,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun AddonFilterField(
    query: String,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = KBTextLo,
                modifier = Modifier.size(18.dp)
            )

            Text(
                text = query.ifBlank { "Filter add-ons by name, id, or resource" },
                color = if (query.isBlank()) KBTextLo else KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
            )

            if (query.isNotBlank()) {
                Text(
                    text = "FILTERING",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun FilterAddonsDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                text = "FILTER ADD-ONS",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Filter by name, id, or resource.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            UrlField(query, onQueryChange, "Filter add-ons")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                ActionButton(
                    label = "APPLY",
                    enabled = query.trim().isNotEmpty(),
                    onClick = onApply
                )
                ActionButton(label = "CLEAR", onClick = onClear)
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun StatusBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    KBCard(onClick = onDismiss, modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = if (isError) KBAccent else KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun EmptyAddons(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                .padding(horizontal = 30.dp, vertical = 28.dp)
        ) {
            Text(
                text = "NO ADD-ONS INSTALLED",
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Add a Stremio manifest URL to get started.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 7.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            ActionButton(
                label = "ADD ADD-ON",
                icon = Icons.Filled.Add,
                onClick = onAdd
            )
        }
    }
}

@Composable
private fun AddonDetailPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(30.dp)
        ) {
            Text(
                text = "SELECT AN ADD-ON",
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Select an add-on to manage its order and settings.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable
private fun AddAddonDialog(
    url: String,
    isLoading: Boolean,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onAddPreset: (String) -> Unit
) {
    var phoneReceived by remember { mutableStateOf(false) }
    val pairPort = remember { mutableStateOf(0) }

    // LAN pairing server: lets a phone browser send the manifest URL to the
    // TV so it doesn't have to be typed with the remote. Only lives while
    // this dialog is open.
    val phoneServer = remember {
        PhoneUrlServer(
            onUrlReceived = onUrlChange,
            onReceived = { phoneReceived = true }
        )
    }

    DisposableEffect(Unit) {
        // Binding failure (rare) shouldn't crash the dialog — the UI just
        // shows the same-Wi-Fi hint instead of a pairing URL.
        runCatching { phoneServer.start() }
        pairPort.value = phoneServer.port
        onDispose { phoneServer.stop() }
    }

    val tvIp = remember { localIpv4Address() }
    val pairUrl =
        if (tvIp != null && pairPort.value > 0) {
            "http://$tvIp:${pairPort.value}"
        } else {
            null
        }
    val qrBitmap = remember(pairUrl) {
        pairUrl?.let { qrcodeBitmap(it, 256) }
    }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // TV escape hatch: the leanback IME swallows BACK while it's open, which
    // can trap the user in the keyboard with the dialog unreachable behind it.
    // Always clear focus + hide the IME, then dismiss — one press gets out.
    BackHandler {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismiss()
    }

    val urlFocusRequester = remember { FocusRequester() }

    // Auto-focus the URL box when the dialog opens: it's the paste target
    // (PASTE button, phone pairing page, or atvTools' "Send text" on the
    // phone while this box is focused), and the IME suppression keeps the
    // leanback keyboard from covering the screen.
    LaunchedEffect(Unit) {
        urlFocusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                text = "ADD ADD-ON",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Paste the add-on's manifest.json URL — or quick-add a popular one below.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                UrlField(
                    value = url,
                    onValueChange = onUrlChange,
                    placeholder = "https://example.com/manifest.json",
                    modifier = Modifier.weight(1f),
                    focusRequester = urlFocusRequester
                )
                Spacer(modifier = Modifier.width(8.dp))
                KBPasteChip(onPaste = { pasted ->
                    onUrlChange(pasted)
                })
            }

            Text(
                text = "TIP: With the URL box in focus you can also paste from atvTools on your phone.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )

            if (pairUrl != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KBSurfaceRaised, RoundedCornerShape(12.dp))
                        .border(1.dp, KBAccent.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR code",
                            modifier = Modifier
                                .size(120.dp)
                                .background(Color.White, RoundedCornerShape(8.dp))
                                .padding(6.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .padding(start = 14.dp)
                            .weight(1f)
                    ) {
                        Text(
                            text = "SEND FROM PHONE",
                            color = KBAccent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (phoneReceived) {
                                "URL received — press ADD to install."
                            } else {
                                "Scan the code (or open the address) on your phone, paste the manifest URL, then press Send."
                            },
                            color = if (phoneReceived) KBAccent else KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = pairUrl,
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Connect this TV and your phone to the same Wi-Fi to use \u201cSend from phone\u201d.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "QUICK-ADD",
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AddonPresets.forEach { (name, presetUrl) ->
                    ActionButton(
                        label = name,
                        icon = Icons.Filled.Add,
                        enabled = !isLoading,
                        onClick = { onAddPreset(presetUrl) }
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                ActionButton(
                    label = if (isLoading) "ADDING..." else "ADD",
                    icon = Icons.Filled.Add,
                    enabled = !isLoading && url.isNotBlank(),
                    onClick = onAdd
                )
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun RenameAddonDialog(
    currentName: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Same escape hatch as the ADD dialog (see AddAddonDialog).
    BackHandler {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                text = "RENAME ADD-ON",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Change the name shown inside KBStream.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            UrlField(currentName, onNameChange, "Add-on name")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                ActionButton(
                    label = "SAVE",
                    enabled = currentName.trim().isNotEmpty(),
                    onClick = onSave
                )
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ConfirmRemoveDialog(
    addonName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                text = "REMOVE ADD-ON?",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Remove $addonName from KBStream?",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                ActionButton(label = "REMOVE", onClick = onConfirm)
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun UrlField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    focusRequester: FocusRequester? = null
) {
    // One shared field everywhere: identical styling, IME behavior, TV
    // D-pad escape, and Enter handling (see KBTextField).
    KBTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        focusRequester = focusRequester
    )
}

private fun openManifest(
    context: android.content.Context,
    url: String,
    onFailure: () -> Unit
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        onFailure()
    }
}

@Composable
private fun CatalogManagerDialog(
    configurations: List<CatalogConfiguration>,
    collectionsState: com.kennyb1201.kbstream.ui.addons.AddonsViewModel.CollectionUiState,
    collectionUrlInput: String,
    onCollectionUrlChange: (String) -> Unit,
    onImportCollectionUrl: () -> Unit,
    onPickCollectionFile: () -> Unit,
    onRemoveCollectionProfile: (String) -> Unit,
    onToggle: (CatalogConfiguration, Boolean) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onMove: (CatalogConfiguration, CatalogMoveAction) -> Unit,
    onCollectionPin: (String) -> Unit,
    onCollectionHide: (String) -> Unit,
    onCollectionMove: (String, Int) -> Unit,
    onRename: (CatalogConfiguration) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    // Focus pinning for the reorder arrows: a move relocates the pressed
    // row (items are keyed by identity) and off-screen relocations dispose
    // the focused button, throwing D-pad focus back to the header. After
    // each move, scroll to the index the row landed on and re-focus the
    // SAME button there, so repeated presses just keep working. If that
    // button is disabled at its new position, fall back to the row's
    // toggle card.
    val listState = rememberLazyListState()
    val rowRequesters = remember { mutableMapOf<String, CatalogRowFocus>() }
    var pendingFocus by remember {
        mutableStateOf<Pair<Int, CatalogRowFocus.Slot>?>(null)
    }

    // One flat list: addon catalog rows first-class alongside collection
    // rows, arranged by the merged home order the ViewModel owns.
    val rows: List<CatalogManagerDialogRow> = remember(configurations, collectionsState) {
        val collectionByKey = collectionsState.collections.associateBy { it.key }
        val addonByKey = configurations.associateBy {
            "${it.addonId}::${it.catalog.type}::${it.catalog.id}"
        }
        val known = buildList {
            configurations.forEach {
                add(NuvioHomeOrderPrefs.addonKey(it.addonId, it.catalog.type, it.catalog.id))
            }
            collectionsState.collections.forEach { add(it.key) }
        }.toSet()
        // Best-effort merged order read (same prefs the ViewModel writes);
        // keys not found keep their default slot at the end.
        val prefs = NuvioHomeOrderPrefs.readOrder()
        val orderedKeys = buildList {
            prefs.pinned.filter { it in known }.forEach { add(it) }
            prefs.order.filter { it in known && it !in prefs.pinned }.forEach { add(it) }
            known.forEach { if (it !in this) add(it) }
        }

        orderedKeys.mapNotNull { key ->
            if (key.startsWith("nuvio:")) {
                val collection = collectionByKey[key] ?: return@mapNotNull null
                CatalogManagerDialogRow(
                    key = key,
                    isCollection = true,
                    config = null,
                    collectionKey = key,
                    title = collection.title,
                    subtitle = "Collection · ${collection.folderCount} folders",
                    isPinned = collection.isPinned,
                    isHidden = collection.isHidden
                )
            } else {
                val config = addonByKey.values.firstOrNull {
                    NuvioHomeOrderPrefs.addonKey(it.addonId, it.catalog.type, it.catalog.id) == key
                } ?: return@mapNotNull null
                CatalogManagerDialogRow(
                    key = "${config.addonId}::${config.catalog.type}::${config.catalog.id}",
                    isCollection = false,
                    config = config,
                    collectionKey = null,
                    title = config.catalog.displayName.ifBlank { config.catalog.id },
                    subtitle = "${config.catalog.type} · ${config.addonName}",
                    isPinned = false,
                    isHidden = !config.catalog.showOnHome
                )
            }
        }
    }

    val visibleRows = rows.filter { !it.isHidden }
    val hiddenRows = rows.filter { it.isHidden }
    val allCatalogsVisible = configurations.all { it.catalog.showOnHome }

    fun moveRow(row: Row, slot: CatalogRowFocus.Slot, delta: Int) {
        if (row.isCollection) {
            pendingFocus = null
            onCollectionMove(row.key, delta)
            return
        }
        val fromIndex = visibleRows.indexOfFirst { it.key == row.key }
        if (fromIndex < 0) return
        pendingFocus = when {
            delta < 0 -> (fromIndex - 1).coerceAtLeast(0) to slot
            delta > 0 -> (fromIndex + 1).coerceAtMost(visibleRows.lastIndex) to slot
            else -> null
        }
    }

    LaunchedEffect(configurations, collectionsState) {
        val target = pendingFocus ?: return@LaunchedEffect
        pendingFocus = null
        val (index, slot) = target
        runCatching { listState.animateScrollToItem(index) }
        val row = visibleRows.getOrNull(index) ?: return@LaunchedEffect
        val focus = rowRequesters[row.key] ?: return@LaunchedEffect
        focus.of(slot)?.let { requester ->
            runCatching { requester.requestFocus() }
        } ?: run { runCatching { focus.toggle.requestFocus() } }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(860.dp)
                .fillMaxHeight(0.88f)
                .background(KBVoid, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HOME MANAGER",
                        color = KBAccent,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Arrange collections and catalogs. Changes are instant.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (configurations.isNotEmpty()) {
                    ActionButton(
                        label = if (allCatalogsVisible) "HIDE ALL" else "SHOW ALL",
                        onClick = { onToggleAll(!allCatalogsVisible) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                ActionButton(label = "DONE", onClick = onDismiss)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Import section
            Text(
                text = "IMPORT COLLECTIONS",
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
            ) {
                KBTextField(
                    value = collectionUrlInput,
                    onValueChange = onCollectionUrlChange,
                    placeholder = "https://…/nuvio-collections.json",
                    modifier = Modifier.weight(1f),
                    onDone = onImportCollectionUrl
                )
                Spacer(modifier = Modifier.width(6.dp))
                KBPasteChip(onPaste = onCollectionUrlChange)
                Spacer(modifier = Modifier.width(6.dp))
                ActionButton(label = "ADD", onClick = onImportCollectionUrl)
                Spacer(modifier = Modifier.width(6.dp))
                ActionButton(label = "FILE", onClick = onPickCollectionFile)
            }
            collectionsState.statusMessage?.let { message ->
                Text(
                    text = message,
                    color = if (message.startsWith("Import failed")) KBTextLo else KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Imported profile sources (removable)
            if (collectionsState.profileUrls.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                ) {
                    collectionsState.profileUrls.forEach { url ->
                        val isFile = url.startsWith("local:")
                        ActionButton(
                            label = (if (isFile) "📎 " else "") +
                                url.substringAfterLast('/').ifBlank { url },
                            onClick = { onRemoveCollectionProfile(url) }
                        )
                    }
                }
                Text(
                    text = "Press OK on a source to remove it",
                    color = KBTextLo.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "HOME RAILS (${visibleRows.size} shown)",
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusGroup()
            ) {
                itemsIndexed(
                    items = visibleRows,
                    key = { _, row -> row.key }
                ) { index, row ->
                    val rowFocus = remember(row.key) { CatalogRowFocus() }
                    rowRequesters[row.key] = rowFocus
                    UnifiedManagerRow(
                        row = row,
                        position = index,
                        total = visibleRows.size,
                        rowFocus = rowFocus,
                        onToggle = {
                            if (row.isCollection) {
                                onCollectionHide(row.collectionKey.orEmpty())
                            } else {
                                onToggle(row.config!!, !row.config.catalog.showOnHome)
                            }
                        },
                        onPin = {
                            if (row.isCollection) {
                                onCollectionPin(row.collectionKey.orEmpty())
                            }
                        },
                        onMove = { slot, delta -> moveRow(row, slot, delta) },
                        onRename = {
                            if (!row.isCollection) onRename(row.config!!)
                        }
                    )
                }

                if (hiddenRows.isNotEmpty()) {
                    item(key = "hidden_header") {
                        Text(
                            text = "HIDDEN — press SHOW to bring back",
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    itemsIndexed(
                        items = hiddenRows,
                        key = { _, row -> "hidden:${row.key}" }
                    ) { _, row ->
                        UnifiedManagerRow(
                            row = row,
                            position = -1,
                            total = -1,
                            rowFocus = remember(row.key) { CatalogRowFocus() },
                            onToggle = {
                                if (row.isCollection) {
                                    onCollectionHide(row.collectionKey.orEmpty())
                                } else {
                                    onToggle(row.config!!, !row.config.catalog.showOnHome)
                                }
                            },
                            onPin = {},
                            onMove = { _, _ -> },
                            onRename = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedManagerRow(
    row: CatalogManagerDialogRow,
    position: Int,
    total: Int,
    rowFocus: CatalogRowFocus,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    onMove: (CatalogRowFocus.Slot, Int) -> Unit,
    onRename: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(KBSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Line 1: position + pin indicator + name + show/hide switch
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (position >= 0) (position + 1).toString() else "—",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(24.dp)
            )
            if (row.isPinned) {
                Text(
                    text = "📌",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(22.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(22.dp))
            }
            Text(
                text = row.title,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            CatalogToggle(
                checked = !row.isHidden,
                onClick = onToggle,
                modifier = Modifier.focusRequester(rowFocus.toggle)
            )
        }

        // Line 2: subtitle + actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Text(
                text = row.subtitle,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            if (!row.isCollection) {
                CatalogIconButton(
                    icon = Icons.Filled.Edit,
                    onClick = onRename,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            CatalogIconButton(
                icon = Icons.Filled.PushPin,
                enabled = row.isCollection && position >= 0,
                onClick = onPin,
                modifier = Modifier.size(38.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            CatalogIconButton(
                icon = Icons.Filled.ArrowUpward,
                enabled = position > 0,
                onClick = { onMove(CatalogRowFocus.Slot.TOP, -1) },
                modifier = Modifier.size(38.dp).focusRequester(rowFocus.up)
            )
            Spacer(modifier = Modifier.width(4.dp))
            CatalogIconButton(
                icon = Icons.Filled.ArrowDownward,
                enabled = position in 0 until (total - 1),
                onClick = { onMove(CatalogRowFocus.Slot.DOWN, +1) },
                modifier = Modifier.size(38.dp).focusRequester(rowFocus.down)
            )
        }
    }
}

/** Plain data holder for a unified manager row. */
private data class CatalogManagerDialogRow(
    val key: String,
    val isCollection: Boolean,
    val config: CatalogConfiguration?,
    val collectionKey: String?,
    val title: String,
    val subtitle: String,
    val isPinned: Boolean,
    val isHidden: Boolean
)

@Composable
private fun CatalogIconButton(
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (enabled) {
        KBCard(onClick = onClick, modifier = modifier) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            ),
            modifier = modifier
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CatalogToggle(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // One D-pad target (the whole capsule) — the state is read from the
    // track color/knob position instead of ON/OFF text.
    KBCard(onClick = onClick, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            // Track
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (checked) {
                            KBAccent.copy(alpha = 0.30f)
                        } else {
                            KBSurfaceRaised.copy(alpha = 0.60f)
                        }
                    )
                    .border(
                        1.dp,
                        if (checked) {
                            KBAccent
                        } else {
                            KBTextLo.copy(alpha = 0.45f)
                        },
                        RoundedCornerShape(13.dp)
                    )
            ) {
                // Knob
                Box(
                    modifier = Modifier
                        .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (checked) KBAccent else KBTextLo)
                )
            }
        }
    }
}

@Composable
private fun RenameCatalogDialog(
    currentName: String,
    hasCustomName: Boolean,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Same escape hatch as the rename/ADD dialogs: BACK clears focus and
    // hides the IME before dismissing so the leanback keyboard can never
    // trap the user.
    BackHandler {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text(
                text = "RENAME CATALOG",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Change the rail name shown on Home.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            UrlField(currentName, onNameChange, "Catalog name")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                ActionButton(
                    label = "SAVE",
                    enabled = currentName.trim().isNotEmpty(),
                    onClick = onSave
                )
                if (hasCustomName) {
                    ActionButton(label = "RESET NAME", onClick = onReset)
                }
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}
