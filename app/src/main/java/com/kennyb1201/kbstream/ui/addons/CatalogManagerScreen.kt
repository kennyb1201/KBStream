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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.addon.ManifestCatalog
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

    val addonManager = remember(context) {
        AddonManager(context)
    }

    val installedAddons by addonManager.installedAddons.collectAsState()

    var catalogs by remember {
        mutableStateOf<List<CatalogConfiguration>>(emptyList())
    }

    var selectedCatalogKey by remember {
        mutableStateOf<String?>(null)
    }

    fun reload() {
        catalogs = addonManager.getCatalogConfigurations()
    }

    LaunchedEffect(installedAddons) {
        reload()

        val selectedStillExists = catalogs.any {
            catalogKey(it) == selectedCatalogKey
        }

        if (!selectedStillExists) {
            selectedCatalogKey = catalogs.firstOrNull()
                ?.let(::catalogKey)
        }
    }

    val selectedCatalog = catalogs.firstOrNull {
        catalogKey(it) == selectedCatalogKey
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(
                horizontal = 28.dp,
                vertical = 24.dp
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "CATALOG MANAGER",
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "${catalogs.size} configured catalog${if (catalogs.size == 1) "" else "s"}",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                CatalogManagerAction(
                    label = "REFRESH",
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        reload()
                    }
                )

                Spacer(modifier = Modifier.width(10.dp))

                CatalogManagerAction(
                    label = "BACK",
                    onClick = onBack
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        KBAccent.copy(alpha = 0.38f)
                    )
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (catalogs.isEmpty()) {
                EmptyCatalogManager(
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Surface(
                        colors = SurfaceDefaults.colors(
                            containerColor =
                                KBSurface.copy(alpha = 0.94f),
                            contentColor = KBTextHi
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(0.60f)
                            .fillMaxHeight()
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .focusGroup()
                        ) {
                            itemsIndexed(
                                items = catalogs,
                                key = { _, item ->
                                    catalogKey(item)
                                }
                            ) { index, configuration ->

                                CatalogListCard(
                                    configuration = configuration,
                                    index = index,
                                    selected =
                                        catalogKey(configuration) ==
                                            selectedCatalogKey,
                                    onClick = {
                                        selectedCatalogKey =
                                            catalogKey(configuration)
                                    }
                                )
                            }
                        }
                    }

                    Surface(
                        colors = SurfaceDefaults.colors(
                            containerColor =
                                KBSurface.copy(alpha = 0.94f),
                            contentColor = KBTextHi
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(0.40f)
                            .fillMaxHeight()
                    ) {
                        if (selectedCatalog == null) {
                            CatalogPlaceholder()
                        } else {
                            CatalogDetails(
                                configuration = selectedCatalog,
                                allCatalogs = catalogs,
                                onShowOnHomeChanged = { enabled ->
                                    addonManager.setCatalogHomeVisibility(
                                        addonId =
                                            selectedCatalog.addonId,
                                        catalogType =
                                            selectedCatalog.catalog.type,
                                        catalogId =
                                            selectedCatalog.catalog.id,
                                        showOnHome = enabled
                                    )

                                    reload()
                                },
                                onMoveUp = {
                                    addonManager.moveCatalog(
                                        addonId =
                                            selectedCatalog.addonId,
                                        catalogType =
                                            selectedCatalog.catalog.type,
                                        catalogId =
                                            selectedCatalog.catalog.id,
                                        direction = -1
                                    )

                                    reload()
                                },
                                onMoveDown = {
                                    addonManager.moveCatalog(
                                        addonId =
                                            selectedCatalog.addonId,
                                        catalogType =
                                            selectedCatalog.catalog.type,
                                        catalogId =
                                            selectedCatalog.catalog.id,
                                        direction = 1
                                    )

                                    reload()
                                },
                                onRemove = {
                                    addonManager.removeCatalog(
                                        addonId =
                                            selectedCatalog.addonId,
                                        catalogType =
                                            selectedCatalog.catalog.type,
                                        catalogId =
                                            selectedCatalog.catalog.id
                                    )

                                    reload()

                                    selectedCatalogKey =
                                        catalogs
                                            .filterNot {
                                                catalogKey(it) ==
                                                    catalogKey(
                                                        selectedCatalog
                                                    )
                                            }
                                            .firstOrNull()
                                            ?.let(::catalogKey)
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
private fun CatalogListCard(
    configuration: CatalogConfiguration,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember {
        mutableStateOf(false)
    }

    val catalog = configuration.catalog

    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) {
                KBSurfaceRaised.copy(alpha = 0.98f)
            } else {
                KBSurfaceRaised.copy(alpha = 0.78f)
            },
            contentColor = KBTextHi,
            focusedContainerColor =
                KBSurfaceRaised.copy(
                    alpha = if (selected) 0.98f else 0.88f
                ),
            focusedContentColor = KBTextHi
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
            }
            .border(
                width = if (focused || selected) 1.dp else 0.dp,
                color = if (focused || selected) {
                    KBAccent.copy(
                        alpha = if (focused) 0.75f else 0.42f
                    )
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 14.dp
                )
        ) {
            Text(
                text = "${index + 1}",
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(34.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = catalog.name,
                    color = KBTextHi,
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
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            HomeIndicator(
                visible = catalog.showOnHome
            )
        }
    }
}

@Composable
private fun HomeIndicator(
    visible: Boolean
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                color = if (visible) {
                    KBAccent.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Home,
            contentDescription = null,
            tint = if (visible) {
                KBAccent
            } else {
                KBTextLo.copy(alpha = 0.35f)
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun CatalogDetails(
    configuration: CatalogConfiguration,
    allCatalogs: List<CatalogConfiguration>,
    onShowOnHomeChanged: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val catalog = configuration.catalog

    val sameAddonCatalogs = allCatalogs.filter {
        it.addonId == configuration.addonId
    }

    val position = sameAddonCatalogs.indexOfFirst {
        catalogKey(it) == catalogKey(configuration)
    }

    val canMoveUp = position > 0
    val canMoveDown =
        position >= 0 &&
            position < sameAddonCatalogs.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = catalog.name,
            color = KBTextHi,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = configuration.addonName,
            color = KBAccent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(18.dp))

        CatalogDetailLine(
            label = "TYPE",
            value = catalog.type
        )

        CatalogDetailLine(
            label = "ID",
            value = catalog.id
        )

        CatalogDetailLine(
            label = "ORDER",
            value = catalog.order.toString()
        )

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "HOME VISIBILITY",
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        HomeToggleCard(
            enabled = catalog.showOnHome,
            onClick = {
                onShowOnHomeChanged(
                    !catalog.showOnHome
                )
            }
        )

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "ORDER",
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            CatalogAction(
                label = "UP",
                icon = Icons.Filled.ArrowUpward,
                enabled = canMoveUp,
                onClick = onMoveUp,
                modifier = Modifier.weight(1f)
            )

            CatalogAction(
                label = "DOWN",
                icon = Icons.Filled.ArrowDownward,
                enabled = canMoveDown,
                onClick = onMoveDown,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "CATALOG",
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "This catalog is supplied by the add-on manifest. " +
                "The Home visibility and ordering settings are stored locally by KBStream.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(18.dp))

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
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = KBAccent
                )

                Text(
                    text = "REMOVE CATALOG",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeToggleCard(
    enabled: Boolean,
    onClick: () -> Unit
) {
    var focused by remember {
        mutableStateOf(false)
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (enabled) {
                KBSurfaceRaised
            } else {
                KBSurface.copy(alpha = 0.72f)
            },
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
            }
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = if (focused) {
                    KBAccent.copy(alpha = 0.75f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 14.dp,
                    vertical = 12.dp
                )
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = null,
                tint = if (enabled) {
                    KBAccent
                } else {
                    KBTextLo
                },
                modifier = Modifier.size(20.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = if (enabled) {
                        "SHOWN ON HOME"
                    } else {
                        "HIDDEN FROM HOME"
                    },
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = if (enabled) {
                        "This catalog appears on the Home screen."
                    } else {
                        "This catalog remains available but is hidden from Home."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun CatalogAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = if (enabled) onClick else ({}),
        colors = CardDefaults.colors(
            containerColor = if (enabled) {
                KBSurfaceRaised
            } else {
                KBSurface.copy(alpha = 0.55f)
            },
            contentColor = if (enabled) {
                KBTextHi
            } else {
                KBTextLo
            },
            focusedContainerColor = if (enabled) {
                KBSurfaceRaised
            } else {
                KBSurface.copy(alpha = 0.55f)
            },
            focusedContentColor = if (enabled) {
                KBTextHi
            } else {
                KBTextLo
            }
        ),
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 10.dp,
                    vertical = 10.dp
                )
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
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

@Composable
private fun CatalogManagerAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KBSurfaceRaised,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 10.dp
            )
        ) {
            icon?.let {
                androidx.compose.material3.Icon(
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
private fun CatalogDetailLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(86.dp)
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
private fun CatalogPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(30.dp)
        ) {
            Text(
                text = "SELECT A CATALOG",
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Choose a catalog to manage its Home visibility and order.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun EmptyCatalogManager(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    KBSurface,
                    RoundedCornerShape(18.dp)
                )
                .border(
                    1.dp,
                    KBAccent.copy(alpha = 0.22f),
                    RoundedCornerShape(18.dp)
                )
                .padding(
                    horizontal = 30.dp,
                    vertical = 28.dp
                )
        ) {
            Text(
                text = "NO CATALOGS CONFIGURED",
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Install or refresh an add-on that provides catalogs.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            CatalogManagerAction(
                label = "BACK",
                onClick = onBack
            )
        }
    }
}

private fun catalogKey(
    configuration: CatalogConfiguration
): String {
    return buildString {
        append(configuration.addonId)
        append("::")
        append(configuration.catalog.type.lowercase())
        append("::")
        append(configuration.catalog.id)
    }
}
