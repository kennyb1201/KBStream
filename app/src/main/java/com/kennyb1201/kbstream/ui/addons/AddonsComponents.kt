package com.kennyb1201.kbstream.ui.addons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSuccess
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Presentational pieces of the Add-ons screen: the list card, the detail panel
 * and its rows/actions, the icon tiles, the filter field and the small dialogs.
 *
 * Split out of AddonsScreen.kt, which had grown past the size where a single
 * file stayed editable. These are pure composables — they receive everything
 * through parameters and hold no state of their own beyond local focus/scroll
 * remember — so the screen keeps owning the state and call sites are unchanged
 * (the functions only widened from file-private to module-internal).
 */

@Composable
internal fun AddonListCard(
    addon: InstalledAddon,
    selected: Boolean,
    onClick: () -> Unit,
    health: AddonsViewModel.AddonHealth? = null,
    onRight: (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) KBSurfaceRaised else KBSurfaceRaised.copy(alpha = 0.72f),
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusRow,
            pressedScale = KBFocusPressed
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) KBAccent.copy(alpha = 0.4f) else Color.Transparent
                ),
                shape = KBShapeCard
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = KBShapeCard
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = KBAccent, elevation = KBFocusGlowSmall)
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
                    overflow = TextOverflow.Ellipsis,
                    color = if (addon.enabled) KBTextHi else KBTextLo
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

            if (!addon.enabled) {
                Box(
                    modifier = Modifier
                        .background(KBTextLo.copy(alpha = 0.14f), KBShapePill)
                        .border(1.dp, KBTextLo.copy(alpha = 0.4f), KBShapePill)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "OFF",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (addon.catalogs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .background(KBAccent.copy(alpha = 0.16f), KBShapePill)
                        .border(1.dp, KBAccent.copy(alpha = 0.45f), KBShapePill)
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
internal fun HealthBadge(healthy: Boolean) {
    val color = if (healthy) KBSuccess else KBDanger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(color.copy(alpha = 0.16f), KBShapePill)
            .border(1.dp, color.copy(alpha = 0.5f), KBShapePill)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, KBShapePill)
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
internal fun AddonDetails(
    addon: InstalledAddon,
    health: AddonsViewModel.AddonHealth?,
    refreshing: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onOpenManifest: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    firstActionFocusRequester: FocusRequester? = null
) {
    val catalogScrollState = rememberScrollState()

    // Pinned identity header + scrollable body. The whole panel used to be
    // one scrollable column, so the moment D-pad focus landed on an action
    // button below the fold, Compose scrolled the focused button into view
    // and the add-on logo/title scrolled off the top — the header was gone
    // exactly when the user was working with that add-on. Now the header is
    // OUTSIDE the scroll container (always visible), and only the details +
    // actions scroll beneath it; focusable children still bring themselves
    // into view within the body's own scroll.
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 12.dp)
                .verticalScroll(catalogScrollState)
        ) {

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
            when {
                !addon.enabled -> "DISABLED — kept installed, not used anywhere"
                health == null -> "Unknown — CHECK HEALTH to verify"
                health.healthy -> "Online"
                else -> "Offline"
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
            // Soft on/off: a disabled add-on stays installed (backups, sync,
            // order) but vanishes from catalogs/streams/search/player until
            // re-enabled.
            SmallAction(
                label = if (addon.enabled) "DISABLE" else "ENABLE",
                icon = if (addon.enabled) Icons.Filled.Close else Icons.Filled.Check,
                onClick = { onToggleEnabled(!addon.enabled) },
                modifier = Modifier.weight(1f)
            )
            SmallAction(
                label = if (refreshing) "REFRESHING..." else "REFRESH",
                icon = Icons.Filled.Refresh,
                enabled = !refreshing,
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ActionButton(
                label = "REMOVE",
                icon = Icons.Filled.Delete,
                onClick = onRemove,
                modifier = Modifier.weight(1f),
                horizontalPadding = 14.dp
            )
            Spacer(modifier = Modifier.weight(1f))
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
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
}

@Composable
internal fun DetailLine(
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
internal fun ActionButton(
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
                        // Icons render in the app text color, not the dark
                        // default - same treatment as CatalogIconButton in
                        // the home manager.
                        tint = KBTextHi,
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
            shape = KBShapeCard,
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
                        tint = KBTextLo.copy(alpha = 0.55f),
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
internal fun SmallAction(
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
                    tint = KBTextHi,
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
            shape = KBShapeCard,
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
                    tint = KBTextLo.copy(alpha = 0.55f),
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

internal val AddonPresets = listOf(
    "Cinemeta" to "https://v3-cinemeta.strem.io/manifest.json",
    "WatchHub" to "https://watchhub.strem.fun/manifest.json"
)

@Composable
internal fun AddonTile(
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
            .clip(KBShapeCard)
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
internal fun AddonTileLetter(
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
internal fun AddonFilterField(
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
internal fun FilterAddonsDialog(
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
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
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
internal fun StatusBanner(
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
internal fun EmptyAddons(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.22f), KBShapePanel)
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
internal fun AddonDetailPlaceholder() {
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
