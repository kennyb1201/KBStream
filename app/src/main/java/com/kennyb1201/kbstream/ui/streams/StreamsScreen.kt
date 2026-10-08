package com.kennyb1201.kbstream.ui.streams

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.reporting.StreamRankReport
import com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.StreamBadgeRow
import com.kennyb1201.kbstream.ui.components.formatRuntimeMinutes
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel

/** The "All" tab's index; every real tab is an index into the add-on groups. */
private const val ALL_ADDONS_TAB = -1

@Composable
fun StreamsScreen(
    title: String,
    displayName: String,
    season: Int?,
    episode: Int?,
    runtimeMinutes: Int? = null,
    backdropUrl: String?,
    clearLogoUrl: String?,
    suppressAutoSelect: Boolean = false,
    onStreamSelected: (
        selected: Stream,
        allSources: List<Stream>,
        sourceAddons: List<String?>
    ) -> Unit,
    viewModel: StreamsViewModel = viewModel()
) {
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    // The add-on behind each source, in the same order as [streams]: it goes to
    // the player so an add-on whose links are dead for that session can be
    // skipped when the viewer asks for the next source (see SourceAddonSession).
    val sourceAddons by viewModel.sourceAddons.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadedKey by viewModel.loadedKey.collectAsStateWithLifecycle()
    val addonGroups by viewModel.addonGroups.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * A DRM stream cannot play on the MPV engine (NativePlayerActivity's own
     * handoff excludes DRM for the same reason), so picking one has to drop the
     * anime verdict published for this request - otherwise the anime rule would
     * send a protected source to an engine that cannot open it.
     */
    fun selectSource(selected: Stream, allSources: List<Stream>, addons: List<String?>) {
        if (selected.drm?.licenseUrl != null) {
            PlayerEngine.clearLaunchAnime()
        }
        onStreamSelected(selected, allSources, addons)
    }

    // A picker opened by "Play Manually" (or by the detail screen's episode
    // long-press) is the ONE place Auto-select must not fire: the viewer asked
    // to choose a source, and auto-selecting the top result instead just
    // flashed the picker and jumped into the player. MainActivity marks the
    // targets it resolves in the background and the Continue Watching route; this
    // covers the routes that only open the picker. [loadedKey] is the same
    // "contentType:streamId" key the request carried.
    val manualPick = ManualSourceSelection.isPendingPickFor(loadedKey)

    // Auto-play: when streams finish loading and autoplay is on, auto-select the
    // top result. Fire only once per target: after the user backs out of the
    // player, MainActivity marks this target as already-played and passes
    // suppressAutoSelect=true so the player isn't relaunched in a loop.
    LaunchedEffect(isLoading, streams, manualPick) {
        if (!isLoading && streams.isNotEmpty() && !suppressAutoSelect && !manualPick && AppPreferences.getAutoSelectStream(context)) {
            // Skip dead placeholder streams (blank URLs) at the top of the
            // list — picking one would silently do nothing and look like
            // auto-select is broken — and skip a source that declares another
            // episode of this season, which is the same mistake in a form the
            // viewer cannot see: the file is a different episode, and starting
            // it by itself is the "playing the wrong episodes" report. Every
            // source in that state means no auto-select at all, with the cards
            // below naming what each file says it is.
            // Then the viewer's own quality ceiling (see AutoPlayQuality):
            // the episode rules above decide *which* episode, this decides how
            // tall a copy auto-play may start without a press. The picker still
            // shows every source; only the automatic head is capped.
            val top = AutoPlayQuality.cappedPick(
                pick = EpisodeMatch.autoplayPick(streams, season, episode, runtimeMinutes),
                candidates = streams,
                season = season,
                episode = episode,
                runtimeMinutes = runtimeMinutes,
                cap = AppPreferences.getMaxAutoPlayQuality(context)
            )
            // What auto-play did, recorded rather than inferred: the rank block
            // names the HEAD of the list, and the head is not always what a pick
            // takes - it skips every source that declares another episode and
            // every one that is a whole season, and it decides nothing at all
            // when the viewer presses a card instead.
            StreamRankReport.noteAutoPlay(
                top?.let { "started · ${StreamRanker.labelOf(it)}" }
                    ?: "none - the picker is showing instead " +
                    "(episode length ${runtimeMinutes?.let { "$it min" } ?: "unknown"})"
            )
            if (top != null) {
                selectSource(top, streams, sourceAddons)
            }
        }
    }

    // DetailScreen passes episode titles in the form:
    // "Show Name S1 E2 • Episode Name". Extract everything after the
    // season/episode marker so the guide can show the real episode name.
    //
    // Remembered: constructing a Regex compiles the pattern, and this screen
    // recomposes on every streams-load state change and focus move. Keyed on
    // the values the pattern is built from, so an unchanged title/season/
    // episode reuses the compiled regex instead of recompiling it.
    val episodeTitle = remember(title, season, episode) {
        if (season != null && episode != null) {
            val paddedSeason = season.toString().padStart(2, '0')
            val paddedEpisode = episode.toString().padStart(2, '0')
            val episodeMarker = Regex("S\\s*(?:${season}|$paddedSeason)\\s*E\\s*(?:${episode}|$paddedEpisode)\\b", RegexOption.IGNORE_CASE)
            episodeMarker.find(title)?.let { match ->
                // Trim first, then strip a single leading separator: titles
                // arrive as "Show S4 E4 • Episode Name", so the remainder
                // after the marker starts with a space before the bullet.
                // Stripping before trimming never matches (the string leads
                // with whitespace) and the bullet then leaks into the name,
                // rendering a doubled separator ("S04 · E04 · • Karambits").
                title.substring(match.range.last + 1)
                    .trim()
                    .removePrefix("•")
                    .removePrefix("-")
                    .removePrefix("·")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        } else {
            null
        }
    }

    val episodeLabel = if (season != null && episode != null) {
        "S%02d · E%02d".format(season, episode)
    } else {
        null
    }

    val sourceLabel = when {
        isLoading -> "Finding sources"
        streams.isNotEmpty() -> "${streams.size} sources found"
        else -> "No sources found"
    }

    // Per-add-on tabs. Shown only when more than one stream add-on actually
    // answered: with a single add-on the merged list IS that add-on's list, and
    // a lone "All" tab would be a control that does nothing. -1 is All.
    var selectedAddonTab by remember(loadedKey) { mutableIntStateOf(ALL_ADDONS_TAB) }
    val visibleStreams =
        if (addonGroups.size > 1 && selectedAddonTab in addonGroups.indices) {
            addonGroups[selectedAddonTab].streams
        } else {
            streams
        }

    // The list's own scroll position, owned here so a provider switch can put
    // the new list back at its first source. The chips are a filter over ONE
    // list, so a viewer who was part way down the old provider's sources would
    // otherwise land in the middle of the new provider's list, with the sources
    // that provider ranks best scrolled off the top - the part of the switch
    // they were actually looking for. Keyed on the tab AND the request: a
    // reload is a new list too.
    val streamListState = rememberLazyListState()
    LaunchedEffect(selectedAddonTab, loadedKey) {
        streamListState.scrollToItem(0)
    }

    // The one case where auto-play deliberately starts nothing: every playable
    // source declares another episode of this season. Said in words, because a
    // picker that opens for no visible reason reads as a bug of its own.
    val mismatchNotice = remember(streams, season, episode) {
        if (
            season != null && episode != null &&
            EpisodeMatch.onlyOtherEpisodes(streams, season, episode)
        ) {
            "None of these sources is S%02dE%02d - each says it holds a different "
                .format(season, episode) + "episode of this season."
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(backdropUrl)
                    // Full-screen hero backdrop: the one image on the
                    // full-bleed backdrop.
                    .crossfade(true)
                    .build(),
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Translucent scrim: light enough that the backdrop clearly reads
        // through, while the glass stream cards carry their own background so
        // text stays legible. The bottom stop is the darkest (the list sits
        // there) but no longer approaches opaque - artwork stays visible
        // edge to edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            KBVoid.copy(alpha = 0.10f),
                            KBVoid.copy(alpha = 0.34f),
                            KBVoid.copy(alpha = 0.74f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            StreamsHeader(
                displayName = displayName,
                clearLogoUrl = clearLogoUrl,
                episodeLabel = episodeLabel,
                episodeTitle = episodeTitle,
                runtimeMinutes = runtimeMinutes,
                sourceLabel = sourceLabel,
                mismatchNotice = mismatchNotice
            )

            if (addonGroups.size > 1) {
                AddonTabs(
                    addonNames = addonGroups.map { it.addonName },
                    selectedIndex = selectedAddonTab,
                    onSelect = { selectedAddonTab = it }
                )
            }

            LazyColumn(
                state = streamListState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 22.dp,
                    bottom = 28.dp
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusGroup()
            ) {
                when {
                    isLoading -> {
                        item {
                            StreamsHeroState(
                                title = "FINDING SOURCES",
                                message = "Checking installed stream add-ons."
                            )
                        }
                    }

                    visibleStreams.isEmpty() -> {
                        item {
                            StreamsHeroState(
                                title = "NO SOURCES FOUND",
                                message = "Try another add-on or check that the provider is online."
                            )
                        }
                    }

                    else -> {
                        items(
                            items = visibleStreams,
                            key = { stream ->
                                listOf(
                                    stream.url.orEmpty(),
                                    stream.title.orEmpty(),
                                    stream.name.orEmpty(),
                                    stream.infoHash.orEmpty(),
                                    stream.fileIdx?.toString().orEmpty()
                                ).joinToString("|")
                            }
                        ) { stream ->
                            StreamCard(
                                stream = stream,
                                // Chips above the file name by default; the
                                // "Badges above the file name" setting (and the
                                // player's source picker) shares this pref.
                                badgesAbove = AppPreferences.getBadgesAboveFile(context),
                                declaredLabel = declaredLabelFor(stream, season, episode),
                                onClick = {
                                    selectSource(stream, streams, sourceAddons)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a source's own text says about the episode, when it says another one.
 *
 * Only the contradiction is labeled: a source that names the requested
 * episode needs no note, and one that names nothing at all is the ordinary
 * unlabeled release — marking those would put a line on every card in the
 * picker and say nothing.
 */
private fun declaredLabelFor(stream: Stream, season: Int?, episode: Int?): String? =
    EpisodeMatch.declaredLabel(stream).takeIf {
        EpisodeMatch.isOtherEpisode(stream, season, episode)
    }

@Composable
private fun StreamsHeader(
    displayName: String,
    clearLogoUrl: String?,
    episodeLabel: String?,
    episodeTitle: String?,
    runtimeMinutes: Int?,
    sourceLabel: String,
    mismatchNotice: String?
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!clearLogoUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(clearLogoUrl)
                    // The title logo over that backdrop, fading in with it.
                    .crossfade(true)
                    .build(),
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
            )
        } else {
            Text(
                text = displayName,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineLarge.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.65f),
                        blurRadius = 18f
                    )
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        episodeLabel?.let { label ->
            Text(
                text = buildString {
                    append(label)
                    episodeTitle?.let { append(" · $it") }
                    // Runtime rides on the episode line so the meta line below
                    // stays a single clean "N sources found".
                    runtimeMinutes?.let { append(" · ${formatRuntimeMinutes(it)}") }
                },
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.60f),
                        blurRadius = 14f
                    )
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        mismatchNotice?.let { notice ->
            Text(
                text = notice,
                color = KBAccent,
                style = MaterialTheme.typography.bodyMedium.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        blurRadius = 12f
                    )
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .background(KBSurface.copy(alpha = 0.82f), KBShapePanel)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Text(
            // Episodes already carry the runtime on the title line; movies
            // (no episode label) keep it on this meta line instead.
            text = if (episodeLabel != null) {
                sourceLabel
            } else {
                listOfNotNull(
                    runtimeMinutes?.let(::formatRuntimeMinutes),
                    sourceLabel
                ).joinToString(" · ")
            },
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium.copy(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    blurRadius = 12f
                )
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun StreamCard(
    stream: Stream,
    badgesAbove: Boolean,
    declaredLabel: String?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    // The card's growth and press-in come from KBCard (the card step of the
    // shared KBFocus* scale). What is left here is the opacity dim that keeps
    // the focused source crisp against a busy frame. The `scale` animation
    // that used to sit here eased to a constant 1f and fed nothing -- the
    // graphicsLayer below had long since been pinned to literal 1f -- so it
    // is gone rather than left running on every focus change.
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.97f,
        animationSpec = tween(140),
        label = "streamCardAlpha"
    )

    KBCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
            }
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(KBShapeCard)
                // Glass cards: translucent so the backdrop shows through,
                // with the focused row noticeably more opaque so the selected
                // source stays crisp against a busy frame.
                .background(
                    if (isFocused) {
                        KBSurfaceRaised.copy(alpha = 0.86f)
                    } else {
                        KBSurface.copy(alpha = 0.58f)
                    }
                )
                .border(
                    width = if (isFocused) 1.dp else 0.dp,
                    color = if (isFocused) {
                        KBAccent.copy(alpha = 0.38f)
                    } else {
                        Color.Transparent
                    },
                    shape = KBShapeCard
                )
                .padding(horizontal = 18.dp, vertical = 15.dp)
        ) {
            if (badgesAbove) {
                StreamBadgeRow(
                    badges = stream.badges,
                    modifier = Modifier.padding(bottom = if (stream.badges.isEmpty()) 0.dp else 6.dp)
                )
            }

            stream.name
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    Text(
                        text = name,
                        color = if (isFocused) KBAccent else KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            declaredLabel?.let { label ->
                // The file names its own episode and it is not the one asked
                // for. On the card, above the release name, so a source can be
                // told apart from the others before it is played.
                Text(
                    text = "The file says $label",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        top = if (stream.name.isNullOrBlank()) 0.dp else 5.dp
                    )
                )
            }

            Text(
                text = stream.displayText(),
                color = if (isFocused) KBAccent else KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    top = if (stream.name.isNullOrBlank() && declaredLabel == null) 0.dp else 5.dp
                )
            )

            if (!badgesAbove) {
                StreamBadgeRow(
                    badges = stream.badges,
                    modifier = Modifier.padding(top = if (stream.badges.isEmpty()) 0.dp else 6.dp)
                )
            }
        }
    }
}

private fun Stream.displayText(): String {
    return listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        description?.takeIf { it.isNotBlank() }
    )
        .distinct()
        .joinToString(separator = "\n")
        .ifBlank { "Stream details unavailable" }
}

/**
 * The add-on filter chips: "All" then one per answering stream add-on. Only
 * rendered when at least two add-ons answered (see [StreamsScreen]). Moving ONTO
 * a chip swaps the list below it - the whole point of the row is to show what
 * each provider offers, and making the viewer press Select on a chip they are
 * already sitting on reads as a chip that does nothing; the chips scroll
 * horizontally so a viewer with many installed add-ons can still reach every
 * one.
 *
 * Select still works: it lands on the tab that was adopted when focus arrived.
 */
@Composable
private fun AddonTabs(
    addonNames: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StreamTabChip(
            label = "All",
            selected = selectedIndex == ALL_ADDONS_TAB,
            onSelect = { onSelect(ALL_ADDONS_TAB) }
        )
        addonNames.forEachIndexed { index, name ->
            StreamTabChip(
                label = name,
                selected = selectedIndex == index,
                onSelect = { onSelect(index) }
            )
        }
    }
}

/**
 * One add-on filter chip, glass-styled to match the source cards below it.
 *
 * [onSelect] fires on focus as well as on the press: the chip row is a picker,
 * and the screen it picks for is the list below it. The chips are NOT the list
 * being swapped - they are a sibling row above it - so adopting a tab changes
 * only the LazyColumn's items and cannot take focus off the chip the viewer is
 * on. A press still lands on the tab focus already adopted.
 */
@Composable
private fun StreamTabChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    KBCard(
        onClick = onSelect,
        // Focus on a KBCard lives on the Card's own focus target, so a
        // focus observer has to sit on the modifier handed to the Card - the
        // same shape the source cards below use to read their own focus.
        modifier = Modifier.onFocusChanged { if (it.isFocused) onSelect() }
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (selected) KBAccent.copy(alpha = 0.22f) else KBSurface.copy(alpha = 0.82f),
                    KBShapeCard
                )
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                color = if (selected) KBAccent else KBTextHi,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StreamsHeroState(
    title: String,
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 44.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    KBSurface.copy(alpha = 0.82f),
                    KBShapePanel
                )
                .border(
                    1.dp,
                    KBTextLo.copy(alpha = 0.14f),
                    KBShapePanel
                )
                .padding(horizontal = 26.dp, vertical = 22.dp)
        ) {
            Text(
                text = title,
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = message,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
