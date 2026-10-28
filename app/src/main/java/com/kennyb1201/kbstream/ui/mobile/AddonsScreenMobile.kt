package com.kennyb1201.kbstream.ui.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.home.HomeRailManagerRequest
import com.kennyb1201.kbstream.ui.addons.AddonPresets
import com.kennyb1201.kbstream.ui.addons.AddonsViewModel
import com.kennyb1201.kbstream.ui.addons.distinctAddonRows
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBPanelHeading
import com.kennyb1201.kbstream.ui.components.touch.KBPasteChip
import com.kennyb1201.kbstream.ui.components.touch.KBSheetContent
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.MobileFilterChip
import com.kennyb1201.kbstream.ui.components.touch.MobileStatus
import com.kennyb1201.kbstream.ui.components.touch.MobileStatusTone
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSuccess
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/*
 * Add-on management, for a finger.
 *
 * The TV page is a master-detail pair - a list pane and a pinned detail pane
 * with the actions - because a D-pad needs both sides visible at once. A phone
 * has no room for that: the list is the page, and choosing a row raises the
 * detail as a bottom sheet. Everything in that sheet is the TV's own action set
 * on the same activity-scoped [AddonsViewModel]: order up/down, rename, open
 * the manifest, soft enable/disable, refresh, remove (confirmed), plus the
 * health line CHECK HEALTH fills in.
 *
 * Reached from Settings, and rendered only on a phone by the one dispatch at
 * the top of `ui/addons/AddonsScreen.kt`; no TV composable is edited.
 *
 * The Home rail manager is hosted here too ([HomeManagerDialogMobile]): the
 * phone Home offers no manager of its own, and neither does the phone Add-ons
 * page need a second wiring - the dialog rides this screen's view model, the
 * same one the TV dialog rides. It opens from Settings' Collections pane and
 * from Home's all-hidden card, both through the one-shot
 * [HomeRailManagerRequest] the TV uses.
 *
 * Deferred on purpose (recorded in docs/mobile_port.md):
 *  - the "SEND FROM PHONE" LAN pairing server in the ADD dialog - it exists so a
 *    TV can receive a URL the viewer pasted on a phone. On a phone the field is
 *    already next to the keyboard and has PASTE, so it would be a phone asking a
 *    phone for a URL.
 */

/**
 * The rows the phone lists for a filter query.
 *
 * Deliberately calls [distinctAddonRows] instead of filtering by id: that is the
 * rule that keeps a profile with two installs reporting the same manifest id
 * from crashing the list with a duplicate key, and the phone must not grow a
 * second reading of it. Matching is the TV's: display name, manifest id, or any
 * resource.
 */
internal fun mobileAddonRows(
    addons: List<InstalledAddon>,
    query: String
): List<InstalledAddon> {
    val q = query.trim().lowercase()
    val matching = if (q.isEmpty()) {
        addons
    } else {
        addons.filter { addon ->
            addon.displayName.lowercase().contains(q) ||
                addon.id.lowercase().contains(q) ||
                addon.resources.any { it.lowercase().contains(q) }
        }
    }
    return distinctAddonRows(matching)
}

/** The STATUS line in the sheet, worded exactly as the TV panel words it. */
internal fun mobileAddonStatusLine(
    addon: InstalledAddon,
    health: AddonsViewModel.AddonHealth?
): String = when {
    !addon.enabled -> "DISABLED — kept installed, not used anywhere"
    health == null -> "Unknown — CHECK HEALTH to verify"
    health.healthy -> "Online"
    else -> "Offline"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreenMobile(
    onBack: () -> Unit,
    viewModel: AddonsViewModel = viewModel()
) {
    val context = LocalContext.current
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val checkingHealth by viewModel.checkingHealth.collectAsStateWithLifecycle()
    val profiles by ProfileManager.profiles.collectAsStateWithLifecycle()
    val activeProfile by ProfileManager.activeProfile.collectAsStateWithLifecycle()

    var filterQuery by remember { mutableStateOf("") }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf("") }
    // The TV screen's fix, mirrored: the sheet stays up until the add ANSWERS,
    // so its own loading state and the error a refused manifest produces are
    // seen instead of a sheet that vanished on the press.
    var addPending by remember { mutableStateOf(false) }
    var addSawLoading by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<InstalledAddon?>(null) }
    var renameText by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<InstalledAddon?>(null) }
    var showCopyDialog by remember { mutableStateOf(false) }

    // ── Home rail manager ───────────────────────────────────────────
    //
    // Two callers leave a one-shot request behind and then navigate here:
    // Home's "all rails are hidden" card and Settings' "Home / Collections"
    // row. It is consumed on entry either way, so it can never re-open the
    // manager on a later, unrelated visit.
    var showCatalogManager by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (HomeRailManagerRequest.consume()) showCatalogManager = true
    }
    val catalogConfigurations by viewModel.catalogConfigurations
        .collectAsStateWithLifecycle()
    val collectionsState by viewModel.collections.collectAsStateWithLifecycle()
    val homeOrderVersion by viewModel.homeOrderVersion.collectAsStateWithLifecycle()
    var collectionUrlInput by remember { mutableStateOf("") }
    // A BUILT-IN rail being renamed (Continue Watching, Upcoming, a built
    // catalog). Kept apart from the catalog target below because a built-in has
    // no catalog to write to - its name lives in the arrangement's rename map.
    var renameBuiltinKey by remember { mutableStateOf<String?>(null) }
    var renameBuiltinDraft by remember { mutableStateOf("") }
    var renameBuiltinHasOverride by remember { mutableStateOf(false) }
    var renameCatalogTarget by remember { mutableStateOf<CatalogConfiguration?>(null) }
    var renameCatalogDraft by remember { mutableStateOf("") }

    // Collections profile import. The phone always has DocumentsUI, but the TV
    // screen's two-step fallback is kept: a custom ROM may still ship no
    // handler, and the view model has the message for it.
    fun readCollectionJson(uri: Uri) {
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

    val collectionFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readCollectionJson(uri)
    }

    val collectionFileGetContent = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) readCollectionJson(uri)
    }

    fun launchCollectionFilePicker() {
        val mimeTypes = arrayOf("application/json", "text/plain", "application/octet-stream")
        try {
            collectionFilePicker.launch(mimeTypes)
        } catch (_: ActivityNotFoundException) {
            try {
                collectionFileGetContent.launch("*/*")
            } catch (_: ActivityNotFoundException) {
                viewModel.onCollectionImportNoFilePicker()
            }
        }
    }

    val rows = remember(addons, filterQuery) {
        mobileAddonRows(addons, filterQuery)
    }
    val selectedAddon = addons.firstOrNull { it.manifestUrl == selectedUrl }

    MobileTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The shared arrow, top-left of the header. It runs the pop
                // AppRoot's BackHandler runs for this screen (returnTo), so
                // the two answers agree - the TV page's own BACK never did.
                MobileBackButton(onBack = onBack)
                Text(
                    text = "ADD-ONS",
                    color = KBAccent,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = when (addons.size) {
                        0 -> "NONE INSTALLED"
                        1 -> "1 INSTALLED"
                        else -> "${addons.size} INSTALLED"
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KBButton(
                    label = if (refreshing) "REFRESHING" else "REFRESH ALL",
                    enabled = !refreshing && !isLoading,
                    onClick = viewModel::refreshAllManifests
                )
                KBButton(
                    label = if (checkingHealth) "CHECKING" else "CHECK HEALTH",
                    enabled = !checkingHealth && !refreshing && !isLoading,
                    onClick = viewModel::checkHealth
                )
                KBButton(label = "ADD", onClick = { showAddDialog = true })
                KBButton(
                    label = "COPY FROM PROFILE",
                    enabled = profiles.any { it.id != activeProfile?.id },
                    onClick = { showCopyDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(KBAccent.copy(alpha = 0.35f))
            )
            Spacer(modifier = Modifier.height(12.dp))

            error?.let { text ->
                MobileAddonBanner(
                    text = text,
                    isError = true,
                    onDismiss = viewModel::clearError
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            status?.let { text ->
                MobileAddonBanner(
                    text = text,
                    isError = false,
                    onDismiss = viewModel::clearStatus
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (addons.isEmpty()) {
                EmptyAddonsMobile(
                    onAdd = { showAddDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                KBTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = "Filter add-ons by name, id, or resource",
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = KBTextLo,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (rows.isEmpty()) {
                    MobileStatus(
                        message = "No add-ons match the filter",
                        modifier = Modifier.weight(1f),
                        centeredVertically = true
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(
                            items = rows,
                            key = { addon -> addon.manifestUrl },
                            contentType = { "addon-row" }
                        ) { addon ->
                            // Typing in the filter, or removing an add-on,
                            // re-forms this list; a row now leaves and the rest
                            // close the gap instead of re-rendering in one
                            // frame.
                            Box(modifier = Modifier.animateItem()) {
                                AddonRowMobile(
                                    addon = addon,
                                    health = health[addon.id],
                                    onClick = { selectedUrl = addon.manifestUrl }
                                )
                            }
                        }
                    }
                }
            }
        }

        selectedAddon?.let { addon ->
            ModalBottomSheet(
                onDismissRequest = { selectedUrl = null },
                shape = KBShapeCard,
                containerColor = KBSurfaceRaised,
                contentColor = KBTextHi
            ) {
                KBSheetContent(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    AddonSheet(
                        addon = addon,
                        health = health[addon.id],
                        refreshing = refreshing,
                        onMoveUp = { viewModel.moveAddonUp(addon.id) },
                        onMoveDown = { viewModel.moveAddonDown(addon.id) },
                        onRename = {
                            renameText = addon.customName ?: addon.name
                            renameTarget = addon
                            selectedUrl = null
                        },
                        onOpenManifest = { openManifest(context, addon.manifestUrl) },
                        onRefresh = { viewModel.refreshManifest(addon.id) },
                        onToggleEnabled = { enabled ->
                            viewModel.setAddonEnabled(addon.id, enabled)
                        },
                        onRemove = {
                            removeTarget = addon
                            selectedUrl = null
                        }
                    )
                }
            }
        }

        if (showAddDialog) {
            AddAddonDialogMobile(
                url = addUrl,
                isLoading = isLoading,
                onUrlChange = { addUrl = it },
                onDismiss = {
                    showAddDialog = false
                    addPending = false
                    addSawLoading = false
                    addUrl = ""
                },
                onAdd = {
                    viewModel.addAddon(addUrl)
                    addPending = true
                },
                onAddPreset = { presetUrl ->
                    viewModel.addAddon(presetUrl)
                    addPending = true
                }
            )
        }

        // Closes on the RESULT: sooner hid the progress and the refusal, and a
        // failure keeps the URL in the field for a correction.
        LaunchedEffect(isLoading) {
            if (!addPending) return@LaunchedEffect
            if (isLoading) {
                addSawLoading = true
                return@LaunchedEffect
            }
            if (!addSawLoading) return@LaunchedEffect
            addSawLoading = false
            addPending = false
            showAddDialog = false
            if (error == null) addUrl = ""
        }

        renameTarget?.let { addon ->
            RenameAddonDialogMobile(
                currentName = renameText,
                onNameChange = { renameText = it },
                onDismiss = { renameTarget = null },
                onSave = {
                    viewModel.renameAddon(addon.id, renameText)
                    renameTarget = null
                }
            )
        }

        removeTarget?.let { addon ->
            ConfirmRemoveAddonDialogMobile(
                addonName = addon.displayName,
                onDismiss = { removeTarget = null },
                onConfirm = {
                    viewModel.removeAddon(addon.id)
                    removeTarget = null
                }
            )
        }

        if (showCopyDialog) {
            CopyAddonsDialogMobile(
                profiles = profiles.filter { it.id != activeProfile?.id },
                loadAddons = { profileId ->
                    AddonManager.getInstance(context).installedAddonsFor(profileId)
                },
                onImport = { picked ->
                    AddonManager.getInstance(context).importAddons(picked)
                    // The list on screen is this profile's; re-read it so the
                    // copied add-ons appear immediately.
                    viewModel.refresh()
                    showCopyDialog = false
                },
                onDismiss = { showCopyDialog = false }
            )
        }

        if (showCatalogManager) {
            HomeManagerDialogMobile(
                configurations = catalogConfigurations,
                collectionsState = collectionsState,
                homeOrderVersion = homeOrderVersion,
                // The manager matches what Home draws for this profile: a kids
                // profile has no Top Today built-ins but has its own kids
                // rails, and a guest profile has its own rails after them.
                kidsMaxAge = activeProfile?.kidsMaxAge,
                isGuest = activeProfile?.guest == true,
                collectionUrlInput = collectionUrlInput,
                onCollectionUrlChange = { collectionUrlInput = it },
                onImportCollectionUrl = {
                    if (collectionUrlInput.isNotBlank()) {
                        viewModel.addCollectionProfileUrl(collectionUrlInput.trim())
                        collectionUrlInput = ""
                    }
                },
                onPickCollectionFile = { launchCollectionFilePicker() },
                // The manager confirms the removal itself before calling this.
                onRemoveCollectionProfile = viewModel::removeCollectionProfileUrl,
                onToggle = { config, show ->
                    viewModel.setCatalogShowOnHome(
                        config.addonId,
                        config.catalog.type,
                        config.catalog.id,
                        show
                    )
                },
                onToggleAll = viewModel::setAllCatalogsShowOnHome,
                onCatalogMove = { config, delta ->
                    viewModel.moveCatalogArrangement(config, delta)
                },
                onCollectionPin = viewModel::toggleCollectionPinned,
                onCollectionHide = viewModel::toggleCollectionHidden,
                onCollectionMove = viewModel::moveCollection,
                onBuiltinHide = viewModel::toggleBuiltinRailHidden,
                onBuiltinMove = viewModel::moveBuiltinRail,
                onBuiltinRename = { key ->
                    val order = KBHomeOrderPrefs.get(context)
                    renameBuiltinKey = key
                    // Seed the field with what the viewer sees now, so renaming
                    // a renamed rail edits the name instead of starting blank.
                    val customName = collectionsState.customCatalogs
                        .firstOrNull { catalog ->
                            KBHomeOrderPrefs.customCatalogKey(catalog.id) == key
                        }
                        ?.name
                    renameBuiltinDraft = order.renames[key]
                        ?: customName
                        ?: KBHomeOrderPrefs.builtinDefaultTitle(key).orEmpty()
                    renameBuiltinHasOverride = order.renames[key] != null
                },
                onRename = { config ->
                    renameCatalogDraft = config.catalog.displayName
                    renameCatalogTarget = config
                },
                onDismiss = { showCatalogManager = false }
            )
        }

        renameBuiltinKey?.let { builtinKey ->
            RenameRailDialogMobile(
                heading = "RENAME RAIL",
                currentName = renameBuiltinDraft,
                hasCustomName = renameBuiltinHasOverride,
                onNameChange = { renameBuiltinDraft = it },
                onDismiss = { renameBuiltinKey = null },
                onSave = {
                    if (renameBuiltinDraft.trim().isNotEmpty()) {
                        viewModel.renameRail(builtinKey, renameBuiltinDraft)
                    }
                    renameBuiltinKey = null
                },
                onReset = {
                    // An empty name is how the store spells "no custom title".
                    viewModel.renameRail(builtinKey, "")
                    renameBuiltinKey = null
                }
            )
        }

        renameCatalogTarget?.let { target ->
            RenameRailDialogMobile(
                heading = "RENAME CATALOG",
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonRowMobile(
    addon: InstalledAddon,
    health: AddonsViewModel.AddonHealth?,
    onClick: () -> Unit
) {
    KBCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            AddonTileMobile(
                name = addon.displayName,
                logoUrl = addon.logo,
                size = 42,
                fontSize = 20
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
                        .joinToString(" • ")
                        .ifBlank { "NO RESOURCES" },
                    style = MaterialTheme.typography.labelSmall,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    if (!addon.enabled) {
                        AddonPill(text = "OFF", color = KBTextLo)
                    }
                    if (addon.catalogs.isNotEmpty()) {
                        AddonPill(
                            text = "${addon.catalogs.size} " +
                                if (addon.catalogs.size == 1) "CATALOG" else "CATALOGS",
                            color = KBAccent
                        )
                    }
                    health?.let { h ->
                        AddonPill(
                            text = if (h.healthy) "ONLINE" else "OFFLINE",
                            color = if (h.healthy) KBSuccess else KBDanger
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), KBShapePill)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun AddonSheet(
    addon: InstalledAddon,
    health: AddonsViewModel.AddonHealth?,
    refreshing: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onOpenManifest: () -> Unit,
    onRefresh: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AddonTileMobile(
            name = addon.displayName,
            logoUrl = addon.logo,
            size = 52,
            fontSize = 24
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f)
        ) {
            KBPanelHeading(title = addon.displayName)
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

    addon.description?.takeIf { it.isNotBlank() }?.let { description ->
        Text(
            text = description,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }

    AddonDetailLine(label = "ID", value = addon.id)
    AddonDetailLine(label = "VERSION", value = addon.version ?: "—")
    AddonDetailLine(
        label = "RESOURCES",
        value = addon.resources.joinToString(", ").ifBlank { "—" }
    )
    AddonDetailLine(label = "TYPES", value = addon.types.joinToString(", ").ifBlank { "—" })
    AddonDetailLine(label = "CATALOGS", value = addon.catalogs.size.toString())
    AddonDetailLine(label = "STATUS", value = mobileAddonStatusLine(addon, health))

    Text(
        text = "MANIFEST",
        color = KBTextLo,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
    Text(
        text = addon.manifestUrl,
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall
    )

    SheetActionRow(
        firstLabel = "MOVE UP",
        onFirst = onMoveUp,
        secondLabel = "MOVE DOWN",
        onSecond = onMoveDown
    )
    SheetActionRow(
        firstLabel = "RENAME",
        onFirst = onRename,
        secondLabel = "OPEN URL",
        onSecond = onOpenManifest
    )
    SheetActionRow(
        firstLabel = if (addon.enabled) "DISABLE" else "ENABLE",
        onFirst = { onToggleEnabled(!addon.enabled) },
        secondLabel = if (refreshing) "REFRESHING…" else "REFRESH",
        secondEnabled = !refreshing,
        onSecond = onRefresh
    )

    KBButton(
        label = "REMOVE",
        danger = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRemove
    )
}

@Composable
private fun SheetActionRow(
    firstLabel: String,
    onFirst: () -> Unit,
    secondLabel: String,
    onSecond: () -> Unit,
    secondEnabled: Boolean = true
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        KBButton(
            label = firstLabel,
            onClick = onFirst,
            modifier = Modifier.weight(1f)
        )
        KBButton(
            label = secondLabel,
            enabled = secondEnabled,
            onClick = onSecond,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AddonDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
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
private fun AddonTileMobile(
    name: String,
    logoUrl: String?,
    size: Int,
    fontSize: Int
) {
    val initial = remember(name) {
        name.trim().firstOrNull()?.uppercase() ?: "?"
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(KBShapeCard)
            .background(KBVoid),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = KBTextHi,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold
        )
        if (!logoUrl.isNullOrBlank()) {
            // The letter stays underneath: while the image loads, or if it
            // fails, the tile is still the add-on's initial.
            AsyncImage(
                model = logoUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size.dp * 0.16f)
            )
        }
    }
}

@Composable
private fun MobileAddonBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    KBCard(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = text,
                color = if (isError) KBAccent else KBTextHi,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "DISMISS",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

@Composable
private fun EmptyAddonsMobile(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        MobileStatus(
            title = "NO ADD-ONS INSTALLED",
            message = "Add a Stremio manifest URL to get started.",
            actionLabel = "ADD ADD-ON",
            onAction = onAdd,
            tone = MobileStatusTone.Card
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddAddonDialogMobile(
    url: String,
    isLoading: Boolean,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onAddPreset: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = "Add add-on",
            subtitle = "Paste the add-on's manifest.json URL, or quick-add one below."
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KBTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    placeholder = "https://example.com/manifest.json",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                KBPasteChip(onPaste = onUrlChange)
            }

            Text(
                text = "QUICK-ADD",
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AddonPresets.forEach { (name, presetUrl) ->
                    KBButton(
                        label = name.uppercase(),
                        enabled = !isLoading,
                        onClick = { onAddPreset(presetUrl) }
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                KBButton(
                    label = if (isLoading) "ADDING…" else "ADD",
                    enabled = !isLoading && url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = onAdd
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
private fun RenameAddonDialogMobile(
    currentName: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = "Rename add-on",
            subtitle = "Change the name shown inside KBStream."
        ) {
            KBTextField(
                value = currentName,
                onValueChange = onNameChange,
                placeholder = "Add-on name"
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                KBButton(
                    label = "SAVE",
                    enabled = currentName.trim().isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = onSave
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
private fun ConfirmRemoveAddonDialogMobile(
    addonName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Remove add-on?") {
            Text(
                text = "Remove $addonName from KBStream?",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                // CANCEL first, like every other destructive confirm in the
                // app: the safe choice is the left-hand one.
                KBButton(
                    label = "CANCEL",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                KBButton(
                    label = "REMOVE",
                    danger = true,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopyAddonsDialogMobile(
    profiles: List<ProfileManager.Profile>,
    loadAddons: (String) -> List<InstalledAddon>,
    onImport: (List<InstalledAddon>) -> Unit,
    onDismiss: () -> Unit
) {
    var sourceId by remember { mutableStateOf(profiles.firstOrNull()?.id) }
    // distinctAddonRows, like the main list: a profile CAN hold the same
    // manifestUrl twice (a hand-edited prefs blob, a profile merge), and this
    // list is keyed by manifestUrl - a duplicate key is a hard crash, and this
    // is the one surface that reads ANOTHER profile's installs, where the blob
    // was not written by this screen's own flow.
    val sourceAddons = remember(sourceId) {
        sourceId?.let(loadAddons).orEmpty().let(::distinctAddonRows)
    }
    var selected by remember(sourceId) {
        mutableStateOf(sourceAddons.map { it.manifestUrl }.toSet())
    }

    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = "Copy add-ons",
            subtitle = "Pick a profile, then the add-ons to add here. " +
                "Anything already installed is skipped."
        ) {
            if (profiles.isEmpty()) {
                Text(
                    text = "There is no other profile to copy from.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { profile ->
                        MobileFilterChip(
                            label = profile.name,
                            selected = profile.id == sourceId,
                            onClick = { sourceId = profile.id }
                        )
                    }
                }

                if (sourceAddons.isEmpty()) {
                    Text(
                        text = "That profile has no add-ons installed.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 240.dp)
                    ) {
                        items(
                            items = sourceAddons,
                            key = { it.manifestUrl },
                            contentType = { "addon-source-chip" }
                        ) { addon ->
                            val checked = addon.manifestUrl in selected
                            MobileFilterChip(
                                label = addon.displayName,
                                selected = checked,
                                onClick = {
                                    selected = if (checked) {
                                        selected - addon.manifestUrl
                                    } else {
                                        selected + addon.manifestUrl
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    KBButton(
                        label = "COPY SELECTED",
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onImport(sourceAddons.filter { it.manifestUrl in selected })
                        }
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
}

/** Opens the manifest in a browser; no browser means nothing to do. */
private fun openManifest(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser on this device: the URL is already visible in the sheet.
    }
}

