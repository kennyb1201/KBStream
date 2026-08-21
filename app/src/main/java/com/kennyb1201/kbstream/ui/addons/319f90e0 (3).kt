package com.kennyb1201.kbstream.ui.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun CatalogManagerScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val addonManager = remember(context.applicationContext) {
        AddonManager(context.applicationContext)
    }
    val installedAddons by addonManager.installedAddons.collectAsState()
    var catalogs by remember { mutableStateOf<List<CatalogConfiguration>>(emptyList()) }

    fun reload() {
        catalogs = addonManager.getCatalogConfigurations()
    }

    LaunchedEffect(installedAddons) { reload() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CATALOG MANAGER",
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (catalogs.isEmpty()) "No catalogs configured"
                        else "${catalogs.size} catalog${if (catalogs.size == 1) "" else "s"}",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                ManagerAction("REFRESH", Icons.Filled.Refresh) { reload() }
                Spacer(modifier = Modifier.width(10.dp))
                ManagerAction("BACK", onClick = onBack)
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(KBAccent.copy(alpha = 0.38f))
            )
            Spacer(modifier = Modifier.height(14.dp))

            if (catalogs.isEmpty()) {
                EmptyCatalogs(modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                Surface(
                    colors = SurfaceDefaults.colors(
                        containerColor = KBSurface.copy(alpha = 0.94f),
                        contentColor = KBTextHi
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().focusGroup()
                    ) {
                        itemsIndexed(catalogs, key = { _, item -> catalogKey(item) }) { index, configuration ->
                            CatalogRow(
                                configuration = configuration,
                                index = index,
                                total = catalogs.size,
                                onMoveUp = {
                                    addonManager.moveCatalog(
                                        addonId = configuration.addonId,
                                        catalogType = configuration.catalog.type,
                                        catalogId = configuration.catalog.id,
                                        direction = -1
                                    )
                                    reload()
                                },
                                onMoveDown = {
                                    addonManager.moveCatalog(
                                        addonId = configuration.addonId,
                                        catalogType = configuration.catalog.type,
                                        catalogId = configuration.catalog.id,
                                        direction = 1
                                    )
                                    reload()
                                },
                                onMoveTop = {
                                    moveCatalogToTop(addonManager, configuration, catalogs)
                                    reload()
                                },
                                onMoveBottom = {
                                    moveCatalogToBottom(addonManager, configuration, catalogs)
                                    reload()
                                },
                                onToggleHome = {
                                    addonManager.setCatalogHomeVisibility(
                                        addonId = configuration.addonId,
                                        catalogType = configuration.catalog.type,
                                        catalogId = configuration.catalog.id,
                                        showOnHome = !configuration.catalog.showOnHome
                                    )
                                    reload()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    configuration: CatalogConfiguration,
    index: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveTop: () -> Unit,
    onMoveBottom: () -> Unit,
    onToggleHome: () -> Unit
) {
    val catalog = configuration.catalog
    val canMoveUp = index > 0
    val canMoveDown = index < total - 1
    val shape = RoundedCornerShape(14.dp)

    Surface(
        shape = shape,
        colors = SurfaceDefaults.colors(
            containerColor = KBSurfaceRaised.copy(
                alpha = if (catalog.showOnHome) 0.98f else 0.78f
            ),
            contentColor = KBTextHi
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (catalog.showOnHome) {
                    KBAccent.copy(alpha = 0.25f)
                } else {
                    KBTextLo.copy(alpha = 0.14f)
                },
                shape = shape
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = catalog.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${configuration.addonName}  •  ${catalog.type}",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            SmallCatalogButton(
                label = if (catalog.showOnHome) "HOME" else "HIDDEN",
                icon = Icons.Filled.Home,
                enabled = true,
                onClick = onToggleHome
            )
            Spacer(modifier = Modifier.width(6.dp))
            SmallCatalogButton("TOP", Icons.Filled.KeyboardDoubleArrowUp, canMoveUp, onMoveTop)
            Spacer(modifier = Modifier.width(6.dp))
            SmallCatalogButton("UP", Icons.Filled.ArrowUpward, canMoveUp, onMoveUp)
            Spacer(modifier = Modifier.width(6.dp))
            SmallCatalogButton("DOWN", Icons.Filled.ArrowDownward, canMoveDown, onMoveDown)
            Spacer(modifier = Modifier.width(6.dp))
            SmallCatalogButton("BOTTOM", Icons.Filled.KeyboardDoubleArrowDown, canMoveDown, onMoveBottom)
        }
    }
}

@Composable
private fun SmallCatalogButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (enabled) {
        KBCard(onClick = onClick) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.45f),
                contentColor = KBTextLo.copy(alpha = 0.45f)
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ManagerAction(
    label: String,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    KBCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyCatalogs(
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(KBSurface, RoundedCornerShape(18.dp))
                .padding(horizontal = 30.dp, vertical = 28.dp)
        ) {
            Text(
                text = "NO CATALOGS CONFIGURED",
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Install an add-on with catalog resources to manage Home rows.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

private fun moveCatalogToTop(
    addonManager: AddonManager,
    configuration: CatalogConfiguration,
    allCatalogs: List<CatalogConfiguration>
) {
    val position = allCatalogs.indexOfFirst { catalogKey(it) == catalogKey(configuration) }
    if (position <= 0) return

    repeat(position) {
        addonManager.moveCatalog(
            addonId = configuration.addonId,
            catalogType = configuration.catalog.type,
            catalogId = configuration.catalog.id,
            direction = -1
        )
    }
}

private fun moveCatalogToBottom(
    addonManager: AddonManager,
    configuration: CatalogConfiguration,
    allCatalogs: List<CatalogConfiguration>
) {
    val position = allCatalogs.indexOfFirst { catalogKey(it) == catalogKey(configuration) }
    if (position < 0) return

    repeat(allCatalogs.lastIndex - position) {
        addonManager.moveCatalog(
            addonId = configuration.addonId,
            catalogType = configuration.catalog.type,
            catalogId = configuration.catalog.id,
            direction = 1
        )
    }
}

private fun catalogKey(
    configuration: CatalogConfiguration
): String = buildString {
    append(configuration.addonId)
    append("::")
    append(configuration.catalog.type.lowercase())
    append("::")
    append(configuration.catalog.id)
}
