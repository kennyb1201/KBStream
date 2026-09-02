package com.kennyb1201.kbstream.ui.addons

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.addon.ManifestCatalog
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.SuppressImeWhileFocused
import com.kennyb1201.kbstream.ui.theme.KBAccent
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

    var urlInput by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showAddPanel by remember { mutableStateOf(false) }
    var showRenamePanel by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var filterQuery by remember { mutableStateOf("") }
    var showFilterDialog by remember { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf("") }

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
                    label = "ADD",
                    icon = Icons.Filled.Add,
                    onClick = { showAddPanel = true }
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
                                        selected = addon.id == selectedId,
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
                                onToggleCatalog = { catalogId, showOnHome ->
                                    viewModel.setCatalogShowOnHome(
                                        selectedAddon.id,
                                        catalogId,
                                        showOnHome
                                    )
                                },
                                onMoveCatalog = { catalogId, action ->
                                    when (action) {
                                        CatalogMoveAction.TOP ->
                                            viewModel.moveCatalogToTop(selectedAddon.id, catalogId)

                                        CatalogMoveAction.UP ->
                                            viewModel.moveCatalogUp(selectedAddon.id, catalogId)

                                        CatalogMoveAction.DOWN ->
                                            viewModel.moveCatalogDown(selectedAddon.id, catalogId)

                                        CatalogMoveAction.BOTTOM ->
                                            viewModel.moveCatalogToBottom(selectedAddon.id, catalogId)
                                    }
                                }
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
}

@Composable
private fun AddonListCard(
    addon: InstalledAddon,
    selected: Boolean,
    onClick: () -> Unit
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
        modifier = Modifier.fillMaxWidth()
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
        }
    }
}

@Composable
private fun AddonDetails(
    addon: InstalledAddon,
    refreshing: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onOpenManifest: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onToggleCatalog: (catalogId: String, showOnHome: Boolean) -> Unit,
    onMoveCatalog: (catalogId: String, action: CatalogMoveAction) -> Unit
) {
    val catalogScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
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
                modifier = Modifier.weight(1f)
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

        if (addon.catalogs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "CATALOGS ON HOME",
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Only the catalog list scrolls; header + actions stay put.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(catalogScrollState)
            ) {
                val sortedCatalogs =
                    addon.catalogs.sortedBy { it.order }

                sortedCatalogs.forEachIndexed { index, catalog ->
                    CatalogToggleRow(
                        catalog = catalog,
                        isFirst = index == 0,
                        isLast = index == sortedCatalogs.lastIndex,
                        onToggle = {
                            onToggleCatalog(
                                catalog.id,
                                !catalog.showOnHome
                            )
                        },
                        onMove = { action ->
                            onMoveCatalog(catalog.id, action)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "This add-on has no catalogs.",
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

private val AddonTileColors = listOf(
    Color(0xFF3D6B99),
    Color(0xFF8A5A99),
    Color(0xFF4C7A6B),
    Color(0xFF997A4C),
    Color(0xFF9A5A4C),
    Color(0xFF4C6B99),
    Color(0xFF6B4C99),
    Color(0xFF99705A)
)

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

    val color =
        remember(name) {
            val hash = name.hashCode().let { if (it < 0) -it else it }
            AddonTileColors[hash % AddonTileColors.size]
        }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(color),
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
        color = Color.White,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun CatalogToggleRow(
    catalog: ManifestCatalog,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onMove: (CatalogMoveAction) -> Unit
) {
    // The toggle card and the four arrow buttons are SIBLINGS, not nested:
    // nested clickables inside a TV focusable card can't be reached with a
    // D-pad (the outer card swallows focus), so arrows must be peers of the
    // toggle card to stay focusable.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        KBCard(
            onClick = onToggle,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = catalog.name.ifBlank { catalog.id },
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = catalog.type.uppercase(),
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }

                Text(
                    text = if (catalog.showOnHome) "ON HOME" else "HIDDEN",
                    color = if (catalog.showOnHome) KBAccent else KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        CatalogMoveButton(
            icon = Icons.Filled.KeyboardDoubleArrowUp,
            contentDescription = "Move to top",
            enabled = !isFirst,
            onClick = { onMove(CatalogMoveAction.TOP) }
        )
        CatalogMoveButton(
            icon = Icons.Filled.ArrowUpward,
            contentDescription = "Move up",
            enabled = !isFirst,
            onClick = { onMove(CatalogMoveAction.UP) }
        )
        CatalogMoveButton(
            icon = Icons.Filled.ArrowDownward,
            contentDescription = "Move down",
            enabled = !isLast,
            onClick = { onMove(CatalogMoveAction.DOWN) }
        )
        CatalogMoveButton(
            icon = Icons.Filled.KeyboardDoubleArrowDown,
            contentDescription = "Move to bottom",
            enabled = !isLast,
            onClick = { onMove(CatalogMoveAction.BOTTOM) }
        )
    }
}

@Composable
private fun CatalogMoveButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val buttonModifier = Modifier
        .padding(start = 6.dp)
        .size(34.dp)

    if (enabled) {
        KBCard(
            onClick = onClick,
            modifier = buttonModifier
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = KBTextHi,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.40f)
            ),
            modifier = buttonModifier
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
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
                ActionButton(
                    label = "PASTE",
                    icon = Icons.Filled.ContentPaste,
                    enabled = !isLoading,
                    onClick = {
                        pasteFromClipboard(context) { pasted ->
                            onUrlChange(pasted)
                            // Drop focus right after pasting so the leanback
                            // IME can't re-cover the screen.
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }
                )
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
    var focused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    SuppressImeWhileFocused(focused)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = KBTextHi,
            fontSize = MaterialTheme.typography.bodyLarge.fontSize
        ),
        cursorBrush = SolidColor(KBAccent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        ),
        modifier = (if (focusRequester != null) {
            modifier.focusRequester(focusRequester)
        } else {
            modifier
        })
            .clip(RoundedCornerShape(12.dp))
            .background(KBSurfaceRaised)
            .border(
                1.dp,
                if (focused) KBAccent.copy(alpha = 0.72f)
                else KBTextLo.copy(alpha = 0.20f),
                RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        decorationBox = { innerTextField ->
            if (value.isBlank()) {
                Text(
                    text = placeholder,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            innerTextField()
        }
    )
}

/**
 * Reads plain text from the system clipboard (the TV leanback keyboard has
 * no paste action, so the ADD dialog gets an explicit PASTE button). Returns
 * null silently when the clipboard is empty/unreadable.
 */
private fun pasteFromClipboard(
    context: android.content.Context,
    onPasted: (String) -> Unit
) {
    // `::class.java` is required: getSystemService's Class overload
    // takes a class literal, not a bare class name expression.
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return

    val text = runCatching {
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    }.getOrNull()

    text?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(onPasted)
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
