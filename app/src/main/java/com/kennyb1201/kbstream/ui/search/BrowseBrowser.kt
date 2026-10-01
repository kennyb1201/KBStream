package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.rememberLongPressModifier
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

// ---------------------------------------------------------------------------
// The Search screen's browse browser.
//
// This is what fills the old "no results" whitespace: a category strip over a
// submenu of chips, each chip opening its own discover screen. It is the
// app's deepest door into the catalog (387 keywords, 279 collections) and it
// used to read as six plain chips above a bare wall of more chips - nothing
// said where a tab went, how much was behind it, or how to find one name in
// three hundred. The tab now carries an icon, its entry count and an accent
// rule under the open one; the submenu names itself, says where it goes and
// how much it holds, and (past BROWSE_FILTER_MIN_ENTRIES) gets a filter field
// and a "Surprise me" shuffle.
//
// The rules behind the counts, the blurbs and the filter live in BrowseRules.kt
// so they are reachable by a test; this file is layout.
// ---------------------------------------------------------------------------

// Browse-submenu lazy grid. The cell floor is wide enough that the longest
// curated name - "The Sisterhood of the Traveling Pants Collection", 48
// characters - wraps onto a second line instead of being ellipsized away,
// which is the trade a uniform-cell grid makes for laziness.
private val SUBMENU_CHIP_MIN_WIDTH = 216.dp

// The grid is a lazy scroll container sitting inside the screen's LazyColumn,
// so its height must be definite: an unbounded (infinite) height would make
// Compose refuse to measure it. Tall enough to read as the submenu's own
// scroll surface, short enough to leave the page above it in view.
private val SUBMENU_CHIP_MAX_HEIGHT = 360.dp

@Composable
internal fun SearchBrowseBrowser(
    viewModel: SearchViewModel,
    categories: List<BrowseCategory>,
    submenuLoading: Boolean,
    /**
     * The chip a just-closed long-press menu wants focus back on, as
     * (category key, chip name).
     *
     * A chip menu is drawn over the screen in its own focus group, so closing
     * it leaves nothing focused and the category strip takes focus instead -
     * which is why "Add to Home", an action that does not move the chip at
     * all, still threw the viewer to the top of the browser. This is the
     * caller naming the chip to return to; it is consumed once the grid has
     * placed focus, so it cannot re-grab later.
     */
    returnChipName: Pair<String, String>? = null,
    onReturnChipNameConsumed: () -> Unit = {},
    onChipLongPress: ((String, BrowseEntry) -> Unit)? = null,
    onCategoryLongPress: ((BrowseCategory) -> Unit)? = null
) {
    // Selected category lives in the activity-scoped ViewModel, so backing
    // out of a discover screen re-opens the same submenu instead of the
    // browser resetting to no selection.
    val selectedKey by viewModel.selectedBrowseCategoryKey.collectAsStateWithLifecycle()
    val activeCategory = categories.firstOrNull { it.key == selectedKey }

    // Re-focus the chip that launched the discover screen we just backed out
    // of, or the one a long-press menu was just closed over. The ViewModel's
    // stored arm is (category, index-within-the-category), and the index is
    // resolved to the chip's NAME here: the grid below renders a FILTERED
    // list, whose numbering has nothing to do with the category's, so an index
    // passed straight through would land on an unrelated chip. A filtered-out
    // arm simply finds no cell to focus, which is correct - the chip is not on
    // screen to be returned to.
    //
    // The menu's arm already names the chip, and it is preferred: it is the
    // more recent intent, and an action that leaves the list alone ("Add to
    // Home") would otherwise have nothing observable to recompose on.
    val armedChipName = returnChipName
        ?.takeIf { it.first == activeCategory?.key }
        ?.second
        ?: viewModel.browseReturnChip
            ?.takeIf { it.first == activeCategory?.key }
            ?.let { (_, index) -> activeCategory?.entries?.getOrNull(index)?.name }

    // Filtering is per-category: opening another tab starts a clean list.
    var filterQuery by remember(activeCategory?.key) { mutableStateOf("") }

    val visibleEntries = remember(activeCategory?.entries, filterQuery) {
        filterBrowseEntries(
            entries = activeCategory?.entries.orEmpty(),
            query = filterQuery
        )
    }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SEARCH_RAIL_EDGE_PADDING)
        ) {
            KBSectionHeader(title = "Browse")
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${categories.size} categories",
                style = MaterialTheme.typography.labelMedium,
                color = KBTextLo,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // The category strip. One scrollable row, full-bleed: the tabs' focus
        // glow needs to reach the row's own edges, and a parent padding would
        // sit outside the clip that would cut it (see KBFocusChipInset).
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = SEARCH_RAIL_EDGE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            categories.forEach { category ->
                BrowseCategoryTab(
                    category = category,
                    selected = activeCategory?.key == category.key,
                    onClick = { viewModel.selectBrowseCategory(category.key) },
                    onLongClick = onCategoryLongPress?.let { handler ->
                        { handler(category) }
                    }
                )
            }
        }

        val category = activeCategory
        if (category == null) return@Column

        // Hairline between the picker and the thing it picked; without it the
        // tabs and the grid read as one undifferentiated block of boxes.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(1.dp)
                .background(KBTextLo.copy(alpha = 0.15f))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SEARCH_RAIL_EDGE_PADDING,
                    end = SEARCH_RAIL_EDGE_PADDING,
                    top = 12.dp
                )
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = KBTextHi
                )
                Text(
                    text = browseCategoryBlurb(category.key),
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = browseCountLabel(
                    shown = visibleEntries.size,
                    total = category.entries.size,
                    key = category.key
                ),
                style = MaterialTheme.typography.labelLarge,
                color = KBAccent,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        // Filter + shuffle. "Surprise me" is always offered - it is the one
        // way to browse a 279-chip category without reading all of it - while
        // the field only appears where a filter would pay for itself.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SEARCH_RAIL_EDGE_PADDING,
                    end = SEARCH_RAIL_EDGE_PADDING,
                    top = 10.dp
                )
        ) {
            if (category.entries.size >= BROWSE_FILTER_MIN_ENTRIES) {
                KBTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = "Filter ${browseCategoryNounPlural(category.key)}",
                    // Manual mode: focus alone must not throw the leanback IME
                    // over the grid. The field takes focus silently, OK starts
                    // editing and Done ends it, which is the same contract the
                    // long TV forms (the profile editor) rely on.
                    openKeyboardOnFocus = false,
                    leading = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = KBTextLo,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            if (filterQuery.isBlank()) {
                BrowseActionChip(
                    label = "Surprise me",
                    icon = Icons.Filled.Shuffle,
                    onClick = {
                        category.entries.randomOrNull()?.let { entry ->
                            viewModel.onBrowseEntryClicked(category.key, entry)
                        }
                    },
                    modifier = Modifier.padding(start = 10.dp)
                )
            } else {
                BrowseActionChip(
                    label = "Clear",
                    icon = Icons.Filled.Clear,
                    onClick = { filterQuery = "" },
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        when {
            submenuLoading && category.entries.isEmpty() -> {
                Text(
                    text = "Resolving ${category.label.lowercase()}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    modifier = Modifier.padding(
                        top = 12.dp,
                        start = SEARCH_RAIL_EDGE_PADDING
                    )
                )
            }

            category.entries.isEmpty() -> {
                Text(
                    text = "Nothing to browse here yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    modifier = Modifier.padding(
                        top = 12.dp,
                        start = SEARCH_RAIL_EDGE_PADDING
                    )
                )
            }

            visibleEntries.isEmpty() -> {
                KBStatusMessage(
                    icon = KB_STATUS_ICON_EMPTY,
                    message = "No ${browseCategoryNounPlural(category.key)} match " +
                        "\"${filterQuery.trim()}\"",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                )
            }

            else -> {
                SubmenuChipGrid(
                    entries = visibleEntries,
                    categoryKey = category.key,
                    onEntryClicked = viewModel::onBrowseEntryClicked,
                    onEntryLongClick = onChipLongPress,
                    armedChipName = armedChipName,
                    onReturnChipConsumed = {
                        viewModel.browseReturnChip = null
                        onReturnChipNameConsumed()
                    }
                )
            }
        }
    }
}

/**
 * The glyph for a browse category's tab in the browser strip. Unknown keys fall
 * back to a plain tag.
 */
private fun browseCategoryIcon(key: String): ImageVector = when (key.trim().lowercase()) {
    "genres" -> Icons.Filled.Movie
    "keywords" -> Icons.Filled.Tag
    "services" -> Icons.Filled.Tv
    "studios" -> Icons.Filled.Business
    "decades" -> Icons.Filled.CalendarMonth
    "collections" -> Icons.Filled.Collections
    else -> Icons.Filled.Category
}

/**
 * One category in the strip above the submenu: an icon, its name, how many
 * chips it holds, and - when it is the open one - an accent rule beneath it.
 *
 * The rule is what makes the open category readable while focus is down in
 * the grid: color alone was ambiguous with the focused tab, and the strip is
 * the only place the browser says which submenu is being shown.
 *
 * Still the shared long-press contract, because hiding chips (and unhiding
 * them) is reached from here: hold Select on a chip to hide it, hold Select
 * on its category to bring them all back.
 */
@Composable
private fun BrowseCategoryTab(
    category: BrowseCategory,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = KBShapeChip),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = when {
                    selected -> KBAccent.copy(alpha = 0.16f)
                    focused -> KBSurfaceRaised
                    else -> KBSurface
                },
                contentColor = when {
                    selected -> KBAccent
                    focused -> KBTextHi
                    else -> KBTextLo
                },
                // The open tab must not go AMBER ON AMBER while focused,
                // which is what brought the D-pad back up to it: an accent
                // wash under an accent label left the whole chip one color and
                // its name unreadable. Focus instead deepens the wash a step
                // and puts the label in the light tone, so the open tab keeps
                // its amber identity AND stays legible - and the accent rule
                // under it still says which submenu is showing, as it does
                // when focus is down in the grid.
                focusedContainerColor = if (selected) {
                    KBAccent.copy(alpha = 0.28f)
                } else {
                    KBSurfaceRaised
                },
                focusedContentColor = KBTextHi,
                pressedContainerColor = KBAccent.copy(alpha = 0.3f),
                pressedContentColor = KBTextHi
            ),
            scale = ClickableSurfaceDefaults.scale(
                focusedScale = KBFocusChip,
                pressedScale = KBFocusPressed
            ),
            // The border belongs to the Surface, not to a caller-side clip:
            // a clip pins the animated growth to the layout bounds and
            // swallows it (see GenreChipRow's DiscoverFilterChip).
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(
                        1.dp,
                        if (selected) {
                            KBAccent.copy(alpha = 0.5f)
                        } else {
                            KBTextLo.copy(alpha = 0.28f)
                        }
                    ),
                    shape = KBShapeChip
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, KBAccent),
                    shape = KBShapeChip
                )
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = KBAccent,
                    elevation = KBFocusGlowSmall
                )
            ),
            modifier = Modifier
                .then(rememberLongPressModifier(onLongClick))
                .onFocusChanged { focused = it.isFocused }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = browseCategoryIcon(category.key),
                    contentDescription = null,
                    // tv Surface drives tv-material's LocalContentColor, not
                    // material3's, so read it explicitly to follow the same
                    // content-color flips as the label.
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 7.dp)
                )
                // Count only once it is known: keyword and collection chips
                // resolve at runtime, and a "0" flashing beside them reads as
                // an empty category rather than a pending one.
                if (category.entries.isNotEmpty()) {
                    Text(
                        text = category.entries.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        // Follows the label's own tone rather than pinning
                        // amber: with the tab focused the count sits on the
                        // amber wash the label just left, and an accent count
                        // there was the other half of the unreadable chip.
                        color = when {
                            focused -> KBTextHi.copy(alpha = 0.8f)
                            selected -> KBAccent
                            else -> KBTextLo.copy(alpha = 0.75f)
                        },
                        modifier = Modifier.padding(start = 7.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (selected) KBAccent else Color.Transparent,
                    KBShapePill
                )
        )
    }
}

/**
 * A compact icon+label button beside the filter field: "Surprise me" (open a
 * random chip of this category) and "Clear" (drop the filter).
 *
 * Sized to the text field it sits next to - same vertical padding as
 * [KBTextField]'s decoration box - so the row reads as one control rather
 * than a field and a stray chip.
 */
@Composable
private fun BrowseActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeChip),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextLo,
            focusedContainerColor = KBAccent,
            focusedContentColor = KBVoid,
            pressedContainerColor = KBAccent,
            pressedContentColor = KBVoid
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.35f)),
                shape = KBShapeChip
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = KBShapeChip
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlowSmall
            )
        ),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 13.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 7.dp)
            )
        }
    }
}

/**
 * Wrapped multi-row chip grid for the browse submenu, composed lazily.
 *
 * One long horizontal row stops scaling once a category holds dozens of
 * entries: the Fire TV D-pad would need a right-press per chip to reach the
 * far end. Wrapping into rows keeps every entry a few presses away, and
 * vertical D-pad movement walks the rows naturally.
 *
 * This used to be a FlowRow, which wraps just as well but builds EVERY chip
 * on the frame the submenu opens. With the 2026-09 curated expansion the big
 * submenus hold hundreds of entries (387 keywords, 279 collections), so
 * opening one composed hundreds of focusable Cards at once and stuttered. A
 * lazy grid composes only the cells on screen, so the cost no longer grows
 * with the list.
 *
 * [entries] is what the filter left, and [armedChipName] is the returning
 * chip's identity (not its index): the two must be resolved against each
 * other, since the armed index belongs to the unfiltered list.
 */
@Composable
private fun SubmenuChipGrid(
    entries: List<BrowseEntry>,
    categoryKey: String,
    onEntryClicked: (String, BrowseEntry) -> Unit,
    onEntryLongClick: ((String, BrowseEntry) -> Unit)? = null,
    armedChipName: String?,
    onReturnChipConsumed: () -> Unit
) {
    // Keyed on the armed chip, not a plain `remember`: consuming one arm used
    // to latch this flag for the rest of the composition, so the NEXT arm -
    // which hideBrowseChip sets to move focus onto a hidden chip's neighbor -
    // found `returnChipConsumed` already true and never grabbed focus. Only
    // the first Hide could place focus; every later one fell back to the
    // strip's first chip again.
    var returnChipConsumed by remember(armedChipName) { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val armedIndex = armedChipName
        ?.takeIf { !returnChipConsumed }
        ?.let { name ->
            entries.indexOfFirst { it.name == name }.takeIf { it >= 0 }
        }

    // A lazy grid composes only the cells it shows, so a returning chip deep
    // in a big submenu is NOT in the composition when the submenu re-opens:
    // its grabInitialFocus effect would never run and focus would fall back
    // to the first chip instead of the one the viewer left. Scroll the grid
    // to it first; the chip's own effect then places focus once it composes.
    LaunchedEffect(armedIndex) {
        if (armedIndex != null && armedIndex in entries.indices) {
            runCatching { gridState.scrollToItem(armedIndex) }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = SUBMENU_CHIP_MIN_WIDTH),
        state = gridState,
        // Horizontal edge padding matches the category strip / poster rails
        // so chip borders never clip at the screen edge, and the top/bottom
        // room is load-bearing: a lazy grid clips its own viewport, so a
        // focused chip's scale-up and glow would be sliced flat along the
        // first and last row without it.
        contentPadding = PaddingValues(
            top = 10.dp,
            bottom = 18.dp,
            start = SEARCH_RAIL_EDGE_PADDING,
            end = SEARCH_RAIL_EDGE_PADDING
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SUBMENU_CHIP_MAX_HEIGHT)
    ) {
        itemsIndexed(
            items = entries,
            // The same identity the hide feature keys a chip by
            // ("category\u0001name"): names are unique within a category, so
            // two cells can never share a key (which a lazy grid rejects)
            // and a chip keeps its identity across a re-resolve.
            key = { _, entry -> BrowseChipVisibility.key(categoryKey, entry.name) }
        ) { entryIndex, entry ->
            SearchChip(
                label = entry.name,
                onClick = { onEntryClicked(categoryKey, entry) },
                onLongClick = onEntryLongClick?.let { handler ->
                    { handler(categoryKey, entry) }
                },
                grabInitialFocus = armedIndex == entryIndex,
                onInitialFocusConsumed = {
                    returnChipConsumed = true
                    onReturnChipConsumed()
                },
                labelMaxLines = 2,
                showChevron = true
            )
        }
    }
}
