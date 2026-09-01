package com.kennyb1201.kbstream.ui.addons

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.addon.ManifestCatalog
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    onOpenCatalogManager: () -> Unit,
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
                ActionButton(label = "CATALOGS", onClick = onOpenCatalogManager)
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
    onToggleCatalog: (catalogId: String, showOnHome: Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        DetailLine("ID", addon.id)
        DetailLine("VERSION", addon.version ?: "—")
        DetailLine("RESOURCES", addon.resources.joinToString(", ").ifBlank { "—" })
        DetailLine("TYPES", addon.types.joinToString(", ").ifBlank { "—" })
        DetailLine("CATALOGS", addon.catalogs.size.toString())

        if (addon.catalogs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "CATALOGS ON HOME",
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            addon.catalogs
                .sortedBy { it.order }
                .forEach { catalog ->
                    CatalogToggleRow(
                        catalog = catalog,
                        onToggle = {
                            onToggleCatalog(
                                catalog.id,
                                !catalog.showOnHome
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
        }

        Spacer(modifier = Modifier.height(12.dp))
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "ORDER",
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

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "MANAGE",
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
                label = "RENAME",
                icon = Icons.Filled.Edit,
                onClick = onRename,
                modifier = Modifier.weight(1f)
            )
            SmallAction(
                label = "MANIFEST",
                icon = Icons.Filled.OpenInNew,
                onClick = onOpenManifest,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        SmallAction(
            label = if (refreshing) "REFRESHING..." else "REFRESH ADD-ON",
            icon = Icons.Filled.Refresh,
            enabled = !refreshing,
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))
        ActionButton(
            label = "REMOVE ADD-ON",
            icon = Icons.Filled.Delete,
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 14.dp,
            verticalPadding = 11.dp,
            textStyle = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(82.dp)
        )
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
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
    verticalPadding: androidx.compose.ui.unit.Dp = 9.dp,
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
                        modifier = Modifier.size(17.dp)
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
                        modifier = Modifier.size(17.dp)
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
                    .padding(horizontal = 8.dp, vertical = 9.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
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
                    .padding(horizontal = 8.dp, vertical = 9.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
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
    onToggle: () -> Unit
) {
    KBCard(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
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
                fontWeight = FontWeight.Bold
            )
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
            UrlField(url, onUrlChange, "https://example.com/manifest.json")

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
    placeholder: String
) {
    var focused by remember { mutableStateOf(false) }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = KBTextHi,
            fontSize = MaterialTheme.typography.bodyLarge.fontSize
        ),
        cursorBrush = SolidColor(KBAccent),
        modifier = Modifier
            .fillMaxWidth()
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
