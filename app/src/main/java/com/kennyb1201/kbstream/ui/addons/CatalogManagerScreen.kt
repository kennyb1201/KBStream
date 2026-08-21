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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
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

    fun reload() {
        catalogs = addonManager.getCatalogConfigurations()
    }

    LaunchedEffect(installedAddons) {
        reload()
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

            // HEADER
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
                        text = if (catalogs.isEmpty()) {
                            "No catalogs configured"
                        } else {
                            "${catalogs.size} catalog${if (catalogs.size == 1) "" else "s"}"
                        },
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                ManagerAction(
                    label = "REFRESH",
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        reload()
                    }
                )

                Spacer(modifier = Modifier.width(10.dp))

                ManagerAction(
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

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "NO CATALOGS CONFIGURED",
                        color = KBTextLo,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

            } else {

                Surface(
                    colors = SurfaceDefaults.colors(
                        containerColor =
                            KBSurface.copy(alpha = 0.94f),
                        contentColor = KBTextHi
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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

                            CatalogRow(
                                configuration = configuration,
                                index = index,
                                total = catalogs.size,

                                onMoveUp = {
                                    addonManager.moveCatalog(
                                        addonId =
                                            configuration.addonId,
                                        catalogType =
                                            configuration.catalog.type,
                                        catalogId =
                                            configuration.catalog.id,
                                        direction = -1
                                    )

                                    reload()
                                },

                                onMoveDown = {
                                    addonManager.moveCatalog(
                                        addonId =
                                            configuration.addonId,
                                        catalogType =
                                            configuration.catalog.type,
                                        catalogId =
                                            configuration.catalog.id,
                                        direction = 1
                                    )

                                    reload()
                                },

                                onMoveTop = {

                                    moveCatalogToTop(
                                        addonManager,
                                        configuration,
                                        catalogs
                                    )

                                    reload()
                                },

                                onMoveBottom = {

                                    moveCatalogToBottom(
                                        addonManager,
                                        configuration,
                                        catalogs
                                    )

                                    reload()
                                },

                                onToggleHome = {

                                    addonManager.setCatalogHomeVisibility(
                                        addonId =
                                            configuration.addonId,
                                        catalogType =
                                            configuration.catalog.type,
                                        catalogId =
                                            configuration.catalog.id,
                                        showOnHome =
                                            !configuration.catalog.showOnHome
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
    var focused by remember {
        mutableStateOf(false)
    }

    val catalog = configuration.catalog

    val canMoveUp = index > 0
    val canMoveDown = index < total - 1

    Card(
        onClick = onToggleHome,

        colors = CardDefaults.colors(
            containerColor =
                KBSurfaceRaised.copy(
                    alpha =
                        if (catalog.showOnHome) {
                            0.98f
                        } else {
                            0.78f
                        }
                ),

            contentColor = KBTextHi,

            focusedContainerColor =
                KBSurfaceRaised.copy(alpha = 0.98f),

            focusedContentColor = KBTextHi
        ),

        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
            }
            .border(
                width =
                    if (focused) {
                        1.dp
                    } else {
                        0.dp
                    },

                color =
                    if (focused) {
                        KBAccent.copy(alpha = 0.75f)
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
                    horizontal = 12.dp,
                    vertical = 10.dp
                )
        ) {

            // POSITION
            Text(
                text = "${index + 1}",
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(34.dp)
            )

            // CATALOG INFO
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
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
                    text =
                        "${configuration.addonName}  •  ${catalog.type}",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            // HOME VISIBILITY
            SmallCatalogButton(
                label =
                    if (catalog.showOnHome) {
                        "HOME"
                    } else {
                        "HIDDEN"
                    },

                icon = Icons.Filled.Home,

                enabled = true,

                onClick = onToggleHome
            )

            Spacer(modifier = Modifier.width(6.dp))

            // MOVE TO TOP
            SmallCatalogButton(
                label = "TOP",
                icon = Icons.Filled.KeyboardDoubleArrowUp,
                enabled = canMoveUp,
                onClick = onMoveTop
            )

            Spacer(modifier = Modifier.width(6.dp))

            // UP
            SmallCatalogButton(
                label = "UP",
                icon = Icons.Filled.ArrowUpward,
                enabled = canMoveUp,
                onClick = onMoveUp
            )

            Spacer(modifier = Modifier.width(6.dp))

            // DOWN
            SmallCatalogButton(
                label = "DOWN",
                icon = Icons.Filled.ArrowDownward,
                enabled = canMoveDown,
                onClick = onMoveDown
            )

            Spacer(modifier = Modifier.width(6.dp))

            // MOVE TO BOTTOM
            SmallCatalogButton(
                label = "BOTTOM",
                icon = Icons.Filled.KeyboardDoubleArrowDown,
                enabled = canMoveDown,
                onClick = onMoveBottom
            )
        }
    }
}

@Composable
private fun SmallCatalogButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = if (enabled) {
            onClick
        } else {
            {}
        },

        colors = CardDefaults.colors(
            containerColor =
                if (enabled) {
                    KBSurfaceRaised
                } else {
                    KBSurface.copy(alpha = 0.45f)
                },

            contentColor =
                if (enabled) {
                    KBTextHi
                } else {
                    KBTextLo.copy(alpha = 0.45f)
                },

            focusedContainerColor =
                if (enabled) {
                    KBSurfaceRaised
                } else {
                    KBSurface.copy(alpha = 0.45f)
                },

            focusedContentColor =
                if (enabled) {
                    KBTextHi
                } else {
                    KBTextLo.copy(alpha = 0.45f)
                }
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = 9.dp,
                vertical = 8.dp
            )
        ) {
            androidx.compose.material3.Icon(
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

@Composable
private fun ManagerAction(
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

private fun moveCatalogToTop(
    addonManager: AddonManager,
    configuration: CatalogConfiguration,
    allCatalogs: List<CatalogConfiguration>
) {
    val sameAddonCatalogs =
        allCatalogs.filter {
            it.addonId == configuration.addonId
        }

    val position =
        sameAddonCatalogs.indexOfFirst {
            catalogKey(it) == catalogKey(configuration)
        }

    if (position <= 0) {
        return
    }

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
    val sameAddonCatalogs =
        allCatalogs.filter {
            it.addonId == configuration.addonId
        }

    val position =
        sameAddonCatalogs.indexOfFirst {
            catalogKey(it) == catalogKey(configuration)
        }

    if (position < 0) {
        return
    }

    val moves =
        sameAddonCatalogs.lastIndex - position

    if (moves <= 0) {
        return
    }

    repeat(moves) {
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
): String {
    return buildString {
        append(configuration.addonId)
        append("::")
        append(configuration.catalog.type.lowercase())
        append("::")
        append(configuration.catalog.id)
    }
}
