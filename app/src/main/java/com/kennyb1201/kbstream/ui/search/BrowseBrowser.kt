package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import com.kennyb1201.kbstream.ui.components.KB_ALPHA_FADE_MS
import com.kennyb1201.kbstream.ui.components.KB_EASE
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import kotlinx.coroutines.launch
import com.kennyb1201.kbstream.ui.components.rememberLongPressModifier
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_WAIT_MS
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.android.awaitFrame

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

// The gap BETWEEN cells, as opposed to the inset at the grid's edge.
//
// It is bigger than KBFocusChipInset on purpose. The edge inset only has to
// cover ONE focused chip's overhang into its own clip; a shared gap has to
// cover the overhang of the chip on each side of it, and two neighbours can be
// focused at different times rather than at once, so the room has to fit the
// limit case: 3% of a 216dp cell (6.5dp) of focus growth plus the 8dp
// KBFocusGlowSmall glow drawn outside the chip = 14.5dp. 16dp clears it and is
// still visibly looser than the 8dp this grid had before the focus cue.
private val SUBMENU_CHIP_GAP = 16.dp

// The grid is a lazy scroll container sitting inside the screen's LazyColumn,
// so its height must be definite: an unbounded (infinite) height would make
// Compose refuse to measure it. Tall enough to read as the submenu's own
// scroll surface, short enough to leave the page above it in view.
private val SUBMENU_CHIP_MAX_HEIGHT = 360.dp

/**
 * Frames to keep asking for focus when a closed menu hands it back to a
 * category tab. The request can land before the strip has attached, and an
 * unattached request is silently missed - the same retry the status card uses.
 */
private const val TAB_RETURN_FOCUS_STEPS = 12

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
    /**
     * The category TAB to return focus to when a long-press menu opened from
     * the strip itself was just closed, named by its key.
     *
     * [returnChipName] handles the menus opened from a chip in the grid below;
     * this is the same hand-back for the strip, where the tab's own "Unhide
     * all" menu lives. Both are consumed once focus has really landed, so
     * neither can re-grab later.
     */
    returnCategoryKey: String? = null,
    onReturnCategoryKeyConsumed: () -> Unit = {},
    /**
     * A chip was long-pressed. The chip's own [FocusRequester] comes with it,
     * so the menu that opens can hand focus straight back to that chip when it
     * closes - see [SearchChip.chipFocusRequester].
     */
    onChipLongPress: ((String, BrowseEntry, FocusRequester) -> Unit)? = null,
    onCategoryLongPress: ((BrowseCategory) -> Unit)? = null,
    /**
     * One requester per chip, keyed by [BrowseChipVisibility.key]. Owned by the
     * CALLER (SearchScreen) rather than made here, because the Hide action runs
     * outside this browser and has to reach the chip that takes a hidden one's
     * place - a node this grid never names outside its own composition.
     */
    chipFocusRequesters: MutableMap<String, FocusRequester> = mutableMapOf()
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

    // One requester per tab, so a closed menu can put focus back on the tab
    // that raised it. Focus is requested across frames because the request can
    // land before the strip has attached (this browser enters the tree in the
    // frame the search settles), and a request for an unattached node is
    // silently missed - the same retry the status card's action uses.
    val tabFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(returnCategoryKey) {
        val key = returnCategoryKey ?: return@LaunchedEffect
        // Two budgets, not one - the same rule the browse pages' own hand-back
        // states (see [browseReturnDecision]): a tab whose requester has not
        // been REGISTERED yet is an arrival, not a missed landing. Spending an
        // attempt on it burned the twelve-frame budget on the frames before
        // the strip composed, and the arm was then left unconsumed - which is
        // the tab menu closing with the D-pad nowhere. The arrival is waited
        // for on TIME instead; only a request made against a node that exists
        // spends an attempt (and an unattached request answers false rather
        // than throwing, which is why the answer is read).
        var focused = false
        var attempts = 0
        val waitStartMs = System.currentTimeMillis()
        while (!focused && attempts < TAB_RETURN_FOCUS_STEPS) {
            val requester = tabFocusRequesters[key]
            if (requester == null) {
                if (System.currentTimeMillis() - waitStartMs > BROWSE_RETURN_WAIT_MS) {
                    break
                }
                awaitFrame()
                continue
            }
            awaitFrame()
            focused = runCatching { requester.requestFocus() }.getOrDefault(false)
            attempts++
        }
        if (focused) onReturnCategoryKeyConsumed()
    }

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
                val tabFocusRequester = remember(category.key) { FocusRequester() }
                tabFocusRequesters[category.key] = tabFocusRequester

                BrowseCategoryTab(
                    category = category,
                    selected = activeCategory?.key == category.key,
                    onClick = { viewModel.selectBrowseCategory(category.key) },
                    onLongClick = onCategoryLongPress?.let { handler ->
                        { handler(category) }
                    },
                    modifier = Modifier.focusRequester(tabFocusRequester)
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

        /*
         * The submenu's four states - resolving, nothing here, nothing matching
         * the filter, chips - used to replace each other in a single frame,
         * including while the viewer filters as they type. The branch is
         * identified once and the new one fades and rises into place; the state
         * itself is still read live, so nothing about what each branch renders
         * changes.
         */
        val branchKey = when {
            submenuLoading && category.entries.isEmpty() -> "resolving:" + category.key
            category.entries.isEmpty() -> "empty:" + category.key
            visibleEntries.isEmpty() -> "filtered:" + category.key
            else -> "chips:" + category.key
        }
        val branchReducedMotion = rememberReducedMotion()
        val branchProgress = remember { Animatable(1f) }
        LaunchedEffect(branchKey) {
            if (branchReducedMotion) {
                branchProgress.snapTo(1f)
            } else {
                branchProgress.snapTo(0f)
                branchProgress.animateTo(
                    1f,
                    tween(durationMillis = KB_ALPHA_FADE_MS, easing = KB_EASE)
                )
            }
        }

        Box(
            modifier = Modifier.graphicsLayer {
                alpha = branchProgress.value
                translationY = (1f - branchProgress.value) * 8.dp.toPx()
            }
        ) {
            when {
                submenuLoading && category.entries.isEmpty() -> {
                    Text(
                        text = "Resolving ${category.label.lowercase()}…",
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
                    // The grid owns its focus reveals; the screen's own
                    // LazyColumn never participates (see
                    // [NO_CHIP_REVEAL_SPEC]). Scoped to the grid alone, so
                    // every reveal on the screen outside it is unaffected.
                    ChipRevealScope {
                        SubmenuChipGrid(
                            entries = visibleEntries,
                            categoryKey = category.key,
                            chipFocusRequesters = chipFocusRequesters,
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
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
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
            modifier = modifier
                .then(rememberLongPressModifier(onLongClick))
                .onFocusChanged { focused = it.isFocused }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 12x7, the inset the search screen's own chips use (see
                // SearchChip's label padding): these two chip families sit on
                // the same screens, and a 1dp difference between them was the
                // whole of it - the browse chips read 1dp roomier than the
                // chips they hand off to.
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
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
        // Focus raises the plate and rings it; it does NOT flood the chip with
        // accent and swap the label to the void tone. That inversion was the
        // one place in the app where focus changed a surface's colour instead
        // of lighting its border, so a chip the viewer merely crossed looked
        // pressed. Accent fill stays reserved for a chip that is SELECTED
        // (see BrowseCategoryTab).
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextLo,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBAccent.copy(alpha = 0.30f),
            pressedContentColor = KBTextHi
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
    onEntryLongClick: ((String, BrowseEntry, FocusRequester) -> Unit)? = null,
    armedChipName: String?,
    onReturnChipConsumed: () -> Unit,
    /**
     * The caller's chip requesters, keyed by [BrowseChipVisibility.key] - the
     * one registry both this grid and the Hide action (which runs outside it)
     * can name a chip by.
     */
    chipFocusRequesters: MutableMap<String, FocusRequester> = mutableMapOf()
) {
    // Keyed on the armed chip, not a plain `remember`: consuming one arm used
    // to latch this flag for the rest of the composition, so the NEXT arm -
    // which hideBrowseChip sets to move focus onto a hidden chip's neighbor -
    // found `returnChipConsumed` already true and never grabbed focus. Only
    // the first Hide could place focus; every later one fell back to the
    // strip's first chip again.
    var returnChipConsumed by remember(armedChipName) { mutableStateOf(false) }

    val revealScope = rememberCoroutineScope()

    // Keyed on the category: the grid shares one composition slot per submenu,
    // so an unkeyed state carried the previous category's scroll position into
    // the next one - opening mid-list instead of at the top (see LS-P2-4).
    val gridState = remember(categoryKey) { LazyGridState() }
    // The room a focused chip's own cue needs past the grid's edges: the trust
    // the reveal below and the row measurement are in raw pixels. Read here
    // because a suspend reveal function has no composition to read it from.
    val chipCueRoomPx = with(LocalDensity.current) { KBFocusChipInset.roundToPx() }
    val armedIndex = armedChipName
        ?.takeIf { !returnChipConsumed }
        ?.let { name ->
            entries.indexOfFirst { it.name == name }.takeIf { it >= 0 }
        }

    // A lazy grid composes only the cells it shows, so a returning chip deep
    // in a big submenu is NOT in the composition when the submenu re-opens:
    // its grabInitialFocus effect would never run and focus would fall back
    // to the first chip instead of the one the viewer left. Scroll the grid to
    // it first; the chip's own effect then places focus once it composes.
    //
    // Only when it is NOT already showing, though: the chip a viewer just
    // long-pressed is by definition on screen, and scrolling to it anyway put
    // that chip in the rail's first row - the jump out of "Add to Home" and
    // back (see browseChipNeedsReveal).
    LaunchedEffect(armedIndex) {
        val index = armedIndex ?: return@LaunchedEffect
        if (index !in entries.indices) return@LaunchedEffect
        val showing = gridState.layoutInfo.visibleItemsInfo.map { it.index }
        if (browseChipNeedsReveal(index, showing)) {
            runCatching { gridState.scrollToItem(index) }
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
        //
        // The two arrange-ments are load-bearing too, and for the same reason
        // one axis in. A chip grows by KBFocusChip per side and draws a
        // KBFocusGlowSmall glow OUTSIDE its own bounds, so a neighbour glued
        // 8dp away was painted over by both: the focused pill's border ran into
        // the next pill's, which is the "clipped tile" the grid was reported
        // for. KBFocusChipInset is the number the chip rows use for the room at
        // an EDGE, and the shared gap between cells needs more than that: the
        // two neighbours' overhangs both land in it (see SUBMENU_CHIP_GAP).
        contentPadding = PaddingValues(
            top = SUBMENU_CHIP_GAP,
            bottom = SUBMENU_CHIP_GAP,
            start = SEARCH_RAIL_EDGE_PADDING,
            end = SEARCH_RAIL_EDGE_PADDING
        ),
        horizontalArrangement = Arrangement.spacedBy(SUBMENU_CHIP_GAP),
        verticalArrangement = Arrangement.spacedBy(SUBMENU_CHIP_GAP),
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
            // Handed BOTH to the chip and to the long-press handler: the menu
            // that handler opens is drawn over the page and takes the focus
            // with it, so a request from the menu's own action - naming this
            // node - is the only thing that puts focus back without the focus
            // system first falling back to the page's first focusable and
            // scrolling the browser up to it.
            //
            // Taken from the caller's registry rather than a local remember, so
            // the Hide action - which runs in SearchScreen's scope and cannot
            // see inside this lazy grid - can hand focus to the chip that takes
            // a hidden one's place before that chip leaves (see
            // BrowseChipVisibility.successorKey).
            val chipKey = BrowseChipVisibility.key(categoryKey, entry.name)
            // The registry lookup itself is REMEMBERED, not just the requester
            // inside it: creating a FocusRequester during composition without
            // remember is what lint's RememberInComposition rule rejects, and a
            // memoising map is not something it can see through. Behaviour is
            // unchanged - the registry is handed the one instance per chip, so
            // the menu's Hide can still name a chip it is not looking at.
            val chipFocusRequester = remember(chipKey) {
                chipFocusRequesters.getOrPut(chipKey) { FocusRequester() }
            }

            SearchChip(
                label = entry.name,
                onClick = { onEntryClicked(categoryKey, entry) },
                onLongClick = onEntryLongClick?.let { handler ->
                    { handler(categoryKey, entry, chipFocusRequester) }
                },
                chipFocusRequester = chipFocusRequester,
                // With the automatic reveal neutralized by the spec above,
                // this observer is the reveal: a nearest-edge scroll that
                // moves only this grid.
                modifier = Modifier.onFocusChanged { state ->
                    if (state.isFocused) {
                        revealScope.launch {
                            revealFocusedChipRow(gridState, entryIndex, chipCueRoomPx)
                        }
                    }
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

/**
 * The bring-into-view spec that keeps the framework from scrolling this grid.
 *
 * Foundation's FocusableNode asks for a bring-into-view on every focus change
 * (`ModifierNode.bringIntoView()`, which walks outward to the nearest responder
 * - verified in the compose-foundation 1.12.1 artifact:
 * FocusableNode.onFocusStateChange calls
 * BringIntoViewModifierNodeKt.bringIntoView). Without anything of its own, a
 * chip's request travels through the grid and on up to the screen's
 * LazyColumn, and that nested request is what reset this grid to the top when
 * focus reached the first row that ever needed revealing (row 3) - the focused
 * chip ended up off-screen.
 *
 * The chip cannot swallow the request itself: `BringIntoViewRequester` is a
 * SEALED interface in this version, so a no-op implementation does not even
 * compile, and a responder is no help either - BringIntoViewResponderNode
 * re-enters the walk from its own node, so the request continues upward through
 * the grid's responder to the column's. The lever that DOES reach the grid's
 * responder is the spec it derives every scroll distance from:
 * ContentInViewNode reads LocalBringIntoViewSpec and calls
 * calculateScrollDistance for each axis. Answering 0 therefore turns the
 * framework's automatic reveal inside the grid into a no-op, which is what
 * leaves the grid's own reveal (see [revealFocusedChipRow]) as the only scroll
 * it performs.
 *
 * Two things it deliberately does NOT touch:
 *
 *  - everything outside the grid, because the local is provided around this
 *    grid alone - the category tabs and the filter field keep the framework's
 *    reveal;
 *  - the direct scrolls, because the spec is consulted only by the
 *    bring-into-view path (its animation spec is read by ContentInViewNode's
 *    own animation), while the armed-chip `scrollToItem` and the reveal's
 *    `animateScrollToItem` never consult it.
 */
private val NO_CHIP_REVEAL_SPEC: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float = 0f
}

/**
 * [NO_CHIP_REVEAL_SPEC], provided around the submenu grid and nothing else.
 *
 * The opt-in lives here rather than on the whole browser: it is
 * [LocalBringIntoViewSpec] that is experimental, and this wrapper is the only
 * place it is touched.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChipRevealScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides NO_CHIP_REVEAL_SPEC,
        content = content
    )
}

/**
 * Reveals one chip's row inside the submenu grid - and nowhere else.
 *
 * This is the reveal the automatic request used to (mis)perform, now that
 * [NO_CHIP_REVEAL_SPEC] has made that request a no-op inside the grid. The
 * decision itself is pure ([chipRevealTarget], so it is pinned by unit tests);
 * this half only reads the grid's live layout and animates:
 *
 *  - the row's own measured height comes from `visibleItemsInfo`, never a
 *    constant, because a two-line curated chip is taller than a one-line one;
 *  - `scrollOffset` is negative for the bottom case (the item lands short of
 *    the viewport's top edge by its own height minus the viewport's), which is
 *    how `animateScrollToItem` aligns an item to the BOTTOM edge - the
 *    nearest-edge reveal the framework used to get right for rows 4+. The room
 *    a focused chip draws its border and glow into is taken off the edge it is
 *    landed on ([edgeInsetPx]), so a row the viewer is ON is revealed with its
 *    whole cue instead of flush with the clip.
 *
 * A row that is not composed cannot be measured, and a cell that is not
 * composed cannot be focused either, so that case simply does nothing rather
 * than guessing a scroll from a constant.
 */
private suspend fun revealFocusedChipRow(
    state: LazyGridState,
    index: Int,
    /** The cue room in raw pixels, resolved by the caller from the theme. */
    edgeInsetPx: Int
) {
    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        ?: return
    when (
        val target = chipRevealTarget(
            index = index,
            itemTop = info.offset.y,
            itemSize = info.size.height,
            viewportStart = state.layoutInfo.viewportStartOffset,
            viewportEnd = state.layoutInfo.viewportEndOffset,
            edgeInset = edgeInsetPx
        )
    ) {
        ChipReveal.None -> Unit
        is ChipReveal.Top -> runCatching {
            state.animateScrollToItem(target.index, target.scrollOffset)
        }
        is ChipReveal.Bottom -> runCatching {
            state.animateScrollToItem(target.index, target.scrollOffset)
        }
    }
}
