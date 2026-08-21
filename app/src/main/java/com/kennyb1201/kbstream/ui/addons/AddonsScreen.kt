package com.kennyb1201.kbstream.ui.addons

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focus.onFocusChanged
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.InstalledAddon
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
    viewModel: AddonsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
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

    val selectedAddon = addons.firstOrNull { it.id == selectedId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 30.dp, vertical = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ---------------------------------------------------------
            // HEADER
            // ---------------------------------------------------------

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
                    onClick = { viewModel.refreshAllManifests() }
                )

                Spacer(modifier = Modifier.width(8.dp))

                ActionButton(
                    label = "CATALOGS",
                    onClick = onOpenCatalogManager
                )

                Spacer(modifier = Modifier.width(8.dp))

                ActionButton(
                    label = "ADD",
                    icon = Icons.Filled.Add,
                    onClick = { showAddPanel = true }
                )

                Spacer(modifier = Modifier.width(8.dp))

                ActionButton(
                    label = "BACK",
                    onClick = onBack
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(KBAccent.copy(alpha = 0.35f))
            )

            // ---------------------------------------------------------
            // STATUS
            // ---------------------------------------------------------

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

            // ---------------------------------------------------------
            // CONTENT
            // ---------------------------------------------------------

            if (addons.isEmpty()) {

                EmptyAddons(
                    onAdd = { showAddPanel = true },
                    modifier = Modifier.fillMaxSize()
                )

            } else {

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {

                    // -------------------------------------------------
                    // ADD-ON LIST
                    // -------------------------------------------------

                    Surface(
                        colors = SurfaceDefaults.colors(
                            containerColor = KBSurface.copy(alpha = 0.94f),
                            contentColor = KBTextHi
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(0.55f).fillMaxHeight()
                    ) {

                        LazyColumn(
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                            modifier = Modifier.fillMaxSize().focusGroup()
                        ) {

                            items(
                                items = addons,
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

                    // -------------------------------------------------
                    // DETAILS
                    // -------------------------------------------------

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
                                onRemove = { showRemoveConfirm = true }
                            )
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------
    // ADD DIALOG
    // -------------------------------------------------------------

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
            }
        )
    }

    // -------------------------------------------------------------
    // RENAME DIALOG
    // -------------------------------------------------------------

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

    // -------------------------------------------------------------
    // REMOVE DIALOG
    // -------------------------------------------------------------

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

// =====================================================================
// ADD-ON LIST CARD  (fixed: no index number, no grey focus box)
// =====================================================================

@Composable
private fun AddonListCard(
    addon: InstalledAddon,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                KBSurfaceRaised.copy(alpha = 1f)
            } else {
                KBSurfaceRaised.copy(alpha = 0.72f)
            },
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.015f
        ),
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
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = 6.dp
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

// =====================================================================
// ADD-ON DETAILS
// =====================================================================

@Composable
private fun AddonDetails(
    addon: InstalledAddon,
    refreshing: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onOpenManifest: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
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

        addon.description
            ?.takeIf { it.isNotBlank() }
            ?.let {
                Text(
                    text = it,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

        Spacer(modifier = Modifier.height(12.dp))

        DetailLine(label = "ID", value = addon.id)
        DetailLine(label = "VERSION", value = addon.version ?: "—")
        DetailLine(label = "RESOURCES", value = addon.resources.joinToString(", ").ifBlank { "—" })
        DetailLine(label = "TYPES", value = addon.types.joinToString(", ").ifBlank { "—" })
        DetailLine(label = "CATALOGS", value = addon.catalogs.size.toString())

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

        Card(
            onClick = onRemove,
            colors = CardDefaults.colors(
                containerColor = KBSurfaceRaised,
                contentColor = KBTextHi,
                focusedContainerColor = KBSurfaceRaised,
                focusedContentColor = KBTextHi
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = KBAccent,
                    modifier = Modifier.size(18.dp)
                )

                Text(
                    text = "REMOVE ADD-ON",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// =====================================================================
// DETAIL LINE
// =====================================================================

@Composable
private fun DetailLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
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

// =====================================================================
// TOP ACTION
// =====================================================================

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor =
                when {
                    !enabled -> KBSurface.copy(alpha = 0.50f)
                    focused -> KBAccent
                    else -> KBSurfaceRaised
                },
            contentColor =
                when {
                    !enabled -> KBTextLo.copy(alpha = 0.50f)
                    focused -> KBVoid
                    else -> KBTextHi
                }
        ),
        border = SurfaceDefaults.border(
            border =
                if (focused && enabled) {
                    Border(
                        border = BorderStroke(
                            width = 2.dp,
                            color = KBAccent
                        )
                    )
                } else {
                    Border.None
                }
        ),
        modifier = Modifier.onFocusChanged {
            focused = it.isFocused
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = 13.dp,
                vertical = 9.dp
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
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// =====================================================================
// SMALL ACTION
// =====================================================================

@Composable
private fun SmallAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.colors(
            containerColor = if (enabled) KBSurfaceRaised else KBSurface.copy(alpha = 0.5f),
            contentColor = if (enabled) KBTextHi else KBTextLo,
            focusedContainerColor = if (enabled) KBSurfaceRaised else KBSurface.copy(alpha = 0.5f),
            focusedContentColor = if (enabled) KBTextHi else KBTextLo
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

// =====================================================================
// STATUS
// =====================================================================

@Composable
private fun StatusBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onDismiss,
        colors = CardDefaults.colors(
            containerColor = KBSurfaceRaised,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi
        ),
        modifier = modifier.fillMaxWidth()
    ) {
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

// =====================================================================
// EMPTY STATE
// =====================================================================

@Composable
private fun EmptyAddons(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
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

// =====================================================================
// PLACEHOLDER
// =====================================================================

@Composable
private fun AddonDetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
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

// =====================================================================
// ADD DIALOG
// =====================================================================

@Composable
private fun AddAddonDialog(
    url: String,
    isLoading: Boolean,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(720.dp)
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
                text = "Paste the add-on's manifest.json URL.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            UrlField(
                value = url,
                onValueChange = onUrlChange,
                placeholder = "https://example.com/manifest.json"
            )

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

                ActionButton(
                    label = "CANCEL",
                    onClick = onDismiss
                )
            }
        }
    }
}

// =====================================================================
// RENAME DIALOG
// =====================================================================

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

            UrlField(
                value = currentName,
                onValueChange = onNameChange,
                placeholder = "Add-on name"
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                ActionButton(
                    label = "SAVE",
                    enabled = currentName.trim().isNotEmpty(),
                    onClick = onSave
                )

                ActionButton(
                    label = "CANCEL",
                    onClick = onDismiss
                )
            }
        }
    }
}

// =====================================================================
// REMOVE DIALOG
// =====================================================================

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
                ActionButton(
                    label = "REMOVE",
                    onClick = onConfirm
                )

                ActionButton(
                    label = "CANCEL",
                    onClick = onDismiss
                )
            }
        }
    }
}

// =====================================================================
// TEXT FIELD
// =====================================================================

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
                if (focused) {
                    KBAccent.copy(alpha = 0.72f)
                } else {
                    KBTextLo.copy(alpha = 0.20f)
                },
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

// =====================================================================
// OPEN MANIFEST
// =====================================================================

private fun openManifest(
    context: android.content.Context,
    url: String,
    onFailure: () -> Unit
) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        )
    } catch (_: ActivityNotFoundException) {
        onFailure()
    }
}
