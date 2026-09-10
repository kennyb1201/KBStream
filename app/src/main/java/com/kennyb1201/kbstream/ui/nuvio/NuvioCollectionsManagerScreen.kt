package com.kennyb1201.kbstream.ui.nuvio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import androidx.compose.ui.platform.LocalContext

/**
 * Collections manager: import Nuvio profile URLs and arrange the merged
 * Home rails (collections + addon catalogs) — pin to top, move up/down,
 * hide/show. Mirrors the catalog manager's controls.
 */
@Composable
fun NuvioCollectionsManagerScreen(
    onBack: () -> Unit,
    viewModel: NuvioHomeManagerViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var urlFieldFocused by remember { mutableStateOf(false) }
    val urlFocusRequester = remember { FocusRequester() }

    // Re-focus the import field once imports land.
    androidx.compose.runtime.LaunchedEffect(state.profileUrls.size) {
        if (state.profileUrls.isEmpty()) {
            urlFocusRequester.requestFocus()
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = "Collections",
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Nuvio collections on Home — import, pin, reorder, hide.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        item(key = "import") {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Import profile URL",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall
                )
                BasicTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = KBTextHi,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(KBAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (urlInput.isNotBlank()) {
                                viewModel.addProfileUrl(urlInput.trim())
                                urlInput = ""
                            }
                        }
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(KBSurface, RoundedCornerShape(8.dp))
                                .border(
                                    width = if (urlFieldFocused) 2.dp else 1.dp,
                                    color = if (urlFieldFocused) KBAccent
                                    else KBTextLo.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (urlInput.isBlank()) {
                                Text(
                                    text = "https://…/nuvio-collections.json",
                                    color = KBTextLo.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(urlFocusRequester)
                        .onFocusChanged { urlFieldFocused = it.isFocused }
                )
                state.statusMessage?.let { message ->
                    Text(
                        text = message,
                        color = if (message.startsWith("Import failed")) KBTextLo else KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Configured profile URLs (removable).
        itemsIndexed(
            items = state.profileUrls,
            key = { index, _ -> "url:$index" }
        ) { _, url ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = url.substringAfterLast('/').ifBlank { url },
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = url,
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ManagerActionButton(
                    label = "Remove",
                    onClick = { viewModel.removeProfileUrl(url) }
                )
            }
        }

        item(key = "rails_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Home rails (${state.rails.count { it.key !in state.hidden }} shown)",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                ManagerActionButton(
                    label = if (state.reorderMode) "Done" else "Reorder",
                    onClick = { viewModel.toggleReorderMode() }
                )
            }
        }

        if (state.isLoading) {
            item(key = "loading") {
                Text(
                    text = "Loading…",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            val visibleRails = state.rails.filter { it.key !in state.hidden }
            itemsIndexed(
                items = visibleRails,
                key = { _, rail -> "rail:${rail.key}" }
            ) { index, rail ->
                RailManagerRow(
                    rail = rail,
                    index = index,
                    count = visibleRails.size,
                    isPinned = rail.key in state.pinned,
                    reorderMode = state.reorderMode,
                    onPin = { viewModel.togglePin(rail.key) },
                    onHide = { viewModel.toggleHidden(rail.key) },
                    onMoveUp = { viewModel.move(rail.key, -1) },
                    onMoveDown = { viewModel.move(rail.key, +1) }
                )
            }

            // Hidden rails: bring back with Show.
            val hiddenRails = state.rails.filter { it.key in state.hidden }
            if (hiddenRails.isNotEmpty()) {
                item(key = "hidden_header") {
                    Text(
                        text = "Hidden",
                        color = KBTextLo,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                itemsIndexed(
                    items = hiddenRails,
                    key = { _, rail -> "hidden:${rail.key}" }
                ) { _, rail ->
                    RailManagerRow(
                        rail = rail,
                        index = -1,
                        count = -1,
                        isPinned = false,
                        reorderMode = false,
                        isHidden = true,
                        onPin = { viewModel.togglePin(rail.key) },
                        onHide = { viewModel.toggleHidden(rail.key) },
                        onMoveUp = {},
                        onMoveDown = {}
                    )
                }
            }
        }

        item(key = "footer") {
            Spacer(modifier = Modifier.height(24.dp))
            ManagerActionButton(
                label = "Reset arrangement",
                onClick = {
                    NuvioHomeOrderPrefs.reset(context)
                    viewModel.reload()
                }
            )
        }
    }

    androidx.activity.compose.BackHandler {
        onBack()
    }
}

@Composable
private fun RailManagerRow(
    rail: NuvioHomeManagerViewModel.ManagedRail,
    index: Int,
    count: Int,
    isPinned: Boolean,
    reorderMode: Boolean,
    isHidden: Boolean = false,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isHidden) 0.55f else 1f)
            .background(KBSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) {
                    Text(
                        text = "📌 ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = rail.title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            rail.subtitle?.let {
                Text(
                    text = listOfNotNull(
                        if (rail.isCollection) "Collection" else "Add-on",
                        it
                    ).joinToString(" · "),
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (!isHidden) {
            ManagerActionButton(
                label = if (isPinned) "Unpin" else "Pin",
                onClick = onPin
            )
        }
        if (reorderMode && !isHidden) {
            ManagerActionButton(
                label = "▲",
                enabled = index > 0,
                onClick = onMoveUp
            )
            ManagerActionButton(
                label = "▼",
                enabled = index in 0 until (count - 1),
                onClick = onMoveDown
            )
        }
        ManagerActionButton(
            label = if (isHidden) "Show" else "Hide",
            onClick = onHide
        )
    }
}

@Composable
private fun ManagerActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        Box(
            modifier = Modifier
                .background(KBSurfaceRaised, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                color = KBTextHi,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}


