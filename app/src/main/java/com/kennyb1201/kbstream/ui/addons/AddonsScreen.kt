package com.kennyb1201.kbstream.ui.addons

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.home.HomeRailManagerRequest
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

// This file owns the add-ons screen and the dialogs it raises directly (add /
// rename / remove an add-on). The "HOME MANAGER" dialog — the merged
// collection+catalog rail list with its reorder, pin and hide controls —
// lives in AddonsHomeManagerDialog.kt; the screen reaches it through the
// three `internal` composables that file exports.

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddonsScreen(
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
    val catalogConfigurations by viewModel.catalogConfigurations.collectAsStateWithLifecycle()
    // Other profiles, for the copy flow (this profile's own add-ons are the list
    // on screen; there is nothing to copy from itself).
    val profiles by com.kennyb1201.kbstream.data.sync.ProfileManager.profiles
        .collectAsStateWithLifecycle()
    val activeProfile by com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
        .collectAsStateWithLifecycle()

    var urlInput by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showAddPanel by remember { mutableStateOf(false) }
    var showRenamePanel by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    // Destructive-action guard for collection sources: OK on a chip only
    // ARMS removal; the confirm dialog below completes it.
    var pendingCollectionRemoveUrl by remember { mutableStateOf<String?>(null) }
    var filterQuery by remember { mutableStateOf("") }
    var showFilterDialog by remember { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf("") }
    var showCatalogManager by remember { mutableStateOf(false) }
    // Home's "all rails are hidden" card asks for this dialog and then
    // navigates here (HD-P2-5). Consume the one-shot request as the screen
    // appears: the flag is cleared either way, so it can never re-open the
    // manager on a later, unrelated visit to Add-ons.
    LaunchedEffect(Unit) {
        if (HomeRailManagerRequest.consume()) showCatalogManager = true
    }
    var showCopyDialog by remember { mutableStateOf(false) }
    var renameCatalogTarget by remember { mutableStateOf<CatalogConfiguration?>(null) }
    var renameCatalogDraft by remember { mutableStateOf("") }
    // A BUILT-IN rail being renamed (Continue Watching, Upcoming). Kept apart
    // from the catalog target above because a built-in has no catalog to write
    // to - its name lives in the arrangement's rename map.
    var renameBuiltinKey by remember { mutableStateOf<String?>(null) }
    var renameBuiltinDraft by remember { mutableStateOf("") }
    var renameBuiltinHasOverride by remember { mutableStateOf(false) }

    val selectedAddon = addons.firstOrNull { it.id == selectedId }

    val filteredAddons = remember(addons, filterQuery) {
        val q = filterQuery.trim().lowercase()
        val matching =
            if (q.isEmpty()) {
                addons
            } else {
                addons.filter { addon ->
                    addon.displayName.lowercase().contains(q) ||
                        addon.id.lowercase().contains(q) ||
                        addon.resources.any { it.lowercase().contains(q) }
                }
            }
        // One row per manifest URL, and the rows below are KEYED by it - see
        // distinctAddonRows for why the manifest id is not unique enough to
        // keep a LazyColumn from throwing.
        distinctAddonRows(matching)
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
            // Title on the left at its OWN width, action buttons wrapping on the
            // right. The title used to be the weighted cell - the one that
            // absorbs whatever the buttons leave - so on a set whose width in dp
            // is small (this TV reports 960dp) six buttons squeezed "ADD-ONS"
            // down to "ADD-O…", and the buttons at the end of the row
            // (COPY FROM PROFILE, BACK) were laid out past the right edge with
            // no way to reach them: there is no sideways scroll on a TV remote,
            // so nothing may be laid out off the end of a row. A FlowRow wraps
            // them onto a second line instead. Same fix, same reasoning as
            // LanguageChipGrid in SettingsScreen.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(end = 16.dp)) {
                    KBPageTitle(text = "ADD-ONS")
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

                // Keeps the buttons right-aligned while they fit on one line;
                // a wrapping FlowRow takes the width itself when they do not.
                Spacer(modifier = Modifier.weight(1f))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton(
                        label = if (refreshing) "REFRESHING" else "REFRESH ALL",
                        icon = Icons.Filled.Refresh,
                        enabled = !refreshing && !isLoading,
                        onClick = viewModel::refreshAllManifests
                    )
                    ActionButton(
                        label = if (checkingHealth) "CHECKING" else "CHECK HEALTH",
                        icon = Icons.Filled.CheckCircle,
                        enabled = !checkingHealth && !refreshing && !isLoading,
                        onClick = viewModel::checkHealth
                    )
                    ActionButton(
                        label = "ADD",
                        icon = Icons.Filled.Add,
                        onClick = { showAddPanel = true }
                    )
                    ActionButton(
                        label = "HOME / COLLECTIONS",
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = { showCatalogManager = true }
                    )
                    ActionButton(
                        label = "COPY FROM PROFILE",
                        icon = Icons.AutoMirrored.Filled.List,
                        enabled = profiles.any { it.id != activeProfile?.id },
                        onClick = { showCopyDialog = true }
                    )
                    ActionButton(label = "BACK", onClick = onBack)
                }
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
                        shape = KBShapePanel,
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
                                    key = { addon -> addon.manifestUrl }
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
                        shape = KBShapePanel,
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
                                onToggleEnabled = { enabled ->
                                    viewModel.setAddonEnabled(selectedAddon.id, enabled)
                                },
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

    // Collection-source removal confirm (armed by OK on a source chip in
    // the Catalog Manager). Sits at the same level as the add-on remove
    // dialog; CANCEL takes initial focus so an accidental double-OK can't
    // delete — you must deliberately move to REMOVE.
    pendingCollectionRemoveUrl?.let { url ->
        ConfirmRemoveCollectionDialog(
            url = url,
            onDismiss = { pendingCollectionRemoveUrl = null },
            onConfirm = {
                pendingCollectionRemoveUrl = null
                viewModel.removeCollectionProfileUrl(url)
            }
        )
    }

    val collectionsState by viewModel.collections.collectAsStateWithLifecycle()
    val homeOrderVersion by viewModel.homeOrderVersion.collectAsStateWithLifecycle()
    var collectionUrlInput by remember { mutableStateOf("") }
    // Collections profile file import. TV ROMs (Fire TV, some Google TVs)
    // ship without the system DocumentsUI picker, so SAF can throw
    // ActivityNotFoundException at launch time. We try SAF first, then a
    // generic GET_CONTENT, and if neither resolves we surface TV-specific
    // guidance instead of the system "no app to do this" toast.
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

    if (showCatalogManager) {
        CatalogManagerDialog(
            configurations = catalogConfigurations,
            collectionsState = collectionsState,
            homeOrderVersion = homeOrderVersion,
            collectionUrlInput = collectionUrlInput,
            onCollectionUrlChange = { collectionUrlInput = it },
            onImportCollectionUrl = {
                if (collectionUrlInput.isNotBlank()) {
                    viewModel.addCollectionProfileUrl(collectionUrlInput.trim())
                    collectionUrlInput = ""
                }
            },
            onPickCollectionFile = { launchCollectionFilePicker() },
            onRemoveCollectionProfile = { url ->
                // Destructive: require an explicit confirm instead of the
                // old single-OK-removes-it behavior.
                pendingCollectionRemoveUrl = url
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
            onCatalogMove = { config, delta ->
                // Arrangement move (merged visible rails: collections +
                // catalogs, exactly what this dialog and Home render).
                // delta: -1/+1 step, Int.MIN_VALUE very top, Int.MAX_VALUE
                // very bottom.
                viewModel.moveCatalogArrangement(config, delta)
            },
            onCollectionPin = { key -> viewModel.toggleCollectionPinned(key) },
            onCollectionHide = { key -> viewModel.toggleCollectionHidden(key) },
            onCollectionMove = { key, delta -> viewModel.moveCollection(key, delta) },
            onBuiltinHide = { key -> viewModel.toggleBuiltinRailHidden(key) },
            onBuiltinMove = { key, delta -> viewModel.moveBuiltinRail(key, delta) },
            onBuiltinRename = { key ->
                val order = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                    .get(context)
                renameBuiltinKey = key
                // Seed the field with what the viewer sees now, so renaming a
                // renamed rail edits the name instead of starting blank.
                renameBuiltinDraft = order.renames[key]
                    ?: com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                        .builtinDefaultTitle(key).orEmpty()
                renameBuiltinHasOverride = order.renames[key] != null
            },
            onRename = { config ->
                renameCatalogDraft = config.catalog.displayName
                renameCatalogTarget = config
            },
            onDismiss = { showCatalogManager = false }
        )
    }

    if (showCopyDialog) {
        CopyAddonsDialog(
            profiles = profiles.filter { it.id != activeProfile?.id },
            loadAddons = { profileId ->
                com.kennyb1201.kbstream.data.addon.AddonManager
                    .getInstance(context)
                    .installedAddonsFor(profileId)
            },
            onImport = { picked ->
                com.kennyb1201.kbstream.data.addon.AddonManager
                    .getInstance(context)
                    .importAddons(picked)
                // The list on screen is the active profile's; re-read it so the
                // copied add-ons appear immediately.
                viewModel.refresh()
                showCopyDialog = false
            },
            onDismiss = { showCopyDialog = false }
        )
    }

    renameBuiltinKey?.let { builtinKey ->
        RenameCatalogDialog(
            currentName = renameBuiltinDraft,
            hasCustomName = renameBuiltinHasOverride,
            heading = "RENAME RAIL",
            onNameChange = { renameBuiltinDraft = it },
            onDismiss = { renameBuiltinKey = null },
            onSave = {
                if (renameBuiltinDraft.trim().isNotEmpty()) {
                    viewModel.renameRail(builtinKey, renameBuiltinDraft)
                }
                renameBuiltinKey = null
            },
            onReset = {
                // Clearing the override; an empty name is how the store spells
                // "no custom title" and the default comes straight back.
                viewModel.renameRail(builtinKey, "")
                renameBuiltinKey = null
            }
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
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
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
                        .background(KBSurfaceRaised, KBShapeCard)
                        .border(1.dp, KBAccent.copy(alpha = 0.32f), KBShapeCard)
                        .padding(12.dp)
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR code",
                            modifier = Modifier
                                .size(120.dp)
                                .background(Color.White, KBShapeSmall)
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
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
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
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
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
internal fun UrlField(
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
