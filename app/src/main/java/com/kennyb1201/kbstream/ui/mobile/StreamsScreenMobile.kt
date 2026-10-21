package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.reporting.StreamRankReport
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.StreamBadgeRow
import com.kennyb1201.kbstream.ui.components.formatRuntimeMinutes
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.streams.StreamItemKeys
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.OswaldFamily

/** The "All" tab's index; every real tab is an index into the add-on groups. */
private const val ALL_ADDONS_TAB = -1

/**
 * The source picker, for a finger.
 *
 * The TV picker is a D-pad list over a full-bleed backdrop: entry focus lands on
 * the first source, the add-on chips adopt on focus, and REFRESH is a focusable
 * chip with an entry-focus fallback. None of that transfers. This screen keeps
 * the *rules* exactly where they are - the same [StreamsViewModel], the same
 * auto-play decision (`EpisodeMatch.autoplayPick` + `AutoPlayQuality.cappedPick`
 * and the same rank report), the same manual-pick and refresh suppression, the
 * same per-add-on tabs and the same "the file says another episode" labelling -
 * and rebuilds only the presentation:
 *
 *  - Back is the platform's own dispatcher, so the picker needs no new route
 *    callback (the TV screen has none either);
 *  - REFRESH is a button, not a chip that has to be focused to exist;
 *  - the add-on tabs are a touch [FilterChip] row where a tap IS the choice,
 *    instead of adopting on focus;
 *  - one vertical list of source cards on the void, with the still behind the
 *    header rather than the whole screen (a solid plate keeps long release names
 *    readable on a small panel).
 *
 * Deliberately *not* here (noted, not silently dropped): the TV's focus-entry
 * latch and scroll-to-first-source effects (there is no focus to place), the
 * spinning refresh mark (the button's label carries the state), and the
 * screen-reader grouping the TV card gets from tv-material3's Card semantics.
 * Tracked, with the rest of the port, in `docs/mobile_port.md`.
 */
@Composable
fun StreamsScreenMobile(
    title: String,
    displayName: String,
    season: Int?,
    episode: Int?,
    runtimeMinutes: Int?,
    backdropUrl: String?,
    tmdbId: Int?,
    clearLogoUrl: String?,
    suppressAutoSelect: Boolean,
    onStreamSelected: (
        selected: Stream,
        allSources: List<Stream>,
        sourceAddons: List<String?>
    ) -> Unit,
    viewModel: StreamsViewModel
) {
    MobileTheme {
        val streams by viewModel.streams.collectAsStateWithLifecycle()
        val sourceAddons by viewModel.sourceAddons.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val loadedKey by viewModel.loadedKey.collectAsStateWithLifecycle()
        val addonGroups by viewModel.addonGroups.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

        // A DRM stream cannot play on the MPV engine, so picking one drops the
        // anime verdict published for this request (see the TV picker).
        fun selectSource(selected: Stream, allSources: List<Stream>, addons: List<String?>) {
            if (selected.drm?.licenseUrl != null) {
                PlayerEngine.clearLaunchAnime()
            }
            onStreamSelected(selected, allSources, addons)
        }

        // "Play Manually" (or an episode long-press) opens THIS picker and must
        // not be auto-selected past; MainActivity marks those targets.
        val manualPick = ManualSourceSelection.isPendingPickFor(loadedKey)

        // A viewer's own REFRESH must not relaunch the player: the reload runs
        // the same loading transition auto-play fires on. One press suppresses
        // auto-select for the rest of this picker's life.
        var refreshed by remember(loadedKey) { mutableStateOf(false) }
        val autoSelectSuppressed = suppressAutoSelect || refreshed

        // Auto-play, with the TV's exact decision chain: the episode pick first
        // (skip blank placeholders and files that declare another episode),
        // then the viewer's quality ceiling, then the rank report that says what
        // was started - or why nothing was.
        LaunchedEffect(isLoading, streams, manualPick) {
            if (
                !isLoading && streams.isNotEmpty() && !autoSelectSuppressed &&
                !manualPick && AppPreferences.getAutoSelectStream(context)
            ) {
                val top = AutoPlayQuality.cappedPick(
                    pick = EpisodeMatch.autoplayPick(streams, season, episode, runtimeMinutes),
                    candidates = streams,
                    season = season,
                    episode = episode,
                    runtimeMinutes = runtimeMinutes,
                    cap = AppPreferences.getMaxAutoPlayQuality(context)
                )
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

        val episodeTitle = remember(title, season, episode) {
            episodeTitleFrom(title, season, episode)
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

        // Per-add-on tabs, only when more than one stream add-on answered: with
        // one, the merged list IS that add-on's list.
        var selectedAddonTab by remember(loadedKey) { mutableIntStateOf(ALL_ADDONS_TAB) }
        val visibleStreams =
            if (addonGroups.size > 1 && selectedAddonTab in addonGroups.indices) {
                addonGroups[selectedAddonTab].streams
            } else {
                streams
            }
        val streamKeys = remember(visibleStreams) { StreamItemKeys.of(visibleStreams) }

        // The one case where auto-play deliberately starts nothing.
        val mismatchNotice = remember(streams, season, episode) {
            if (
                season != null && episode != null &&
                EpisodeMatch.onlyOtherEpisodes(streams, season, episode)
            ) {
                "None of these sources holds S%02dE%02d - each says it holds a "
                    .format(season, episode) + "different episode of this season."
            } else {
                null
            }
        }

        // The other case auto-play starts nothing on purpose: every source is
        // taller than the viewer's auto-play ceiling (see AutoPlayQuality), so
        // the list opens instead of the player. The same words the TV picker
        // uses, from the same rule.
        val ceilingNotice = remember(streams, season, episode) {
            AutoPlayQuality.ceilingNotice(
                streams = streams,
                season = season,
                episode = episode,
                runtimeMinutes = runtimeMinutes,
                cap = AppPreferences.getMaxAutoPlayQuality(context)
            )
        }

        // The still the viewer chose for this title's streams page, when they
        // chose one - the same override the TV screen honours.
        val overridePaths = rememberImageOverridePaths()
        val backdrop = imageOverrideBackdropUrl(
            paths = overridePaths,
            tmdbId = tmdbId,
            screen = ImageOverrides.SCREEN_STREAMS,
            fallbackUrl = backdropUrl
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "header") {
                MobileStreamsHeader(
                    displayName = displayName,
                    backdropUrl = backdrop,
                    clearLogoUrl = clearLogoUrl,
                    episodeLabel = episodeLabel,
                    episodeTitle = episodeTitle,
                    runtimeMinutes = runtimeMinutes,
                    sourceLabel = sourceLabel,
                    mismatchNotice = mismatchNotice ?: ceilingNotice,
                    onBack = { backDispatcher?.onBackPressed() }
                )
            }

            item(key = "controls") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Deliberately pressable while a fetch is in flight: a
                    // provider that never answers is exactly when a viewer
                    // reaches for REFRESH, and a second press supersedes the
                    // first rather than stacking on it (see
                    // StreamsViewModel.load). Disabling it here would strand a
                    // hanging picker with Back as its only way out.
                    KBButton(
                        label = if (refreshed && isLoading) "REFRESHING…" else "REFRESH",
                        onClick = {
                            refreshed = true
                            viewModel.refresh()
                        }
                    )
                    if (addonGroups.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") {
                                MobileAddonChip(
                                    label = "All",
                                    selected = selectedAddonTab == ALL_ADDONS_TAB,
                                    onClick = { selectedAddonTab = ALL_ADDONS_TAB }
                                )
                            }
                            itemsIndexed(
                                items = addonGroups,
                                key = { index, group -> "addon:" + index + ":" + group.addonName }
                            ) { index, group ->
                                MobileAddonChip(
                                    label = group.addonName,
                                    selected = selectedAddonTab == index,
                                    onClick = { selectedAddonTab = index }
                                )
                            }
                        }
                    }
                }
            }

            when {
                isLoading -> item(key = "loading") {
                    MobileStreamsHeroState(
                        title = "FINDING SOURCES",
                        message = "Checking installed stream add-ons.",
                        loading = true
                    )
                }

                visibleStreams.isEmpty() -> item(key = "empty") {
                    MobileStreamsHeroState(
                        title = "NO SOURCES FOUND",
                        message = "Try another add-on or check that the provider is online."
                    )
                }

                else -> itemsIndexed(
                    items = visibleStreams,
                    key = { index, _ -> streamKeys[index] }
                ) { _, stream ->
                    MobileStreamCard(
                        stream = stream,
                        badgesAbove = AppPreferences.getBadgesAboveFile(context),
                        declaredLabel = declaredLabelFor(stream, season, episode),
                        onClick = { selectSource(stream, streams, sourceAddons) }
                    )
                }
            }
        }
    }
}

/**
 * What a source's own text says about the episode, when it says another one.
 * Only the contradiction is labelled (see the TV picker's rule).
 */
private fun declaredLabelFor(stream: Stream, season: Int?, episode: Int?): String? =
    EpisodeMatch.declaredLabel(stream).takeIf {
        EpisodeMatch.isOtherEpisode(stream, season, episode)
    }

/**
 * `"Show Name S4 E4 • Episode Name"` -> the episode name. Extracted from
 * everything after the season/episode marker, with one leading separator
 * stripped - the same rule the TV header uses, kept here as a pure function so
 * the phone does not re-parse the title differently.
 */
internal fun episodeTitleFrom(title: String, season: Int?, episode: Int?): String? {
    if (season == null || episode == null) return null
    val paddedSeason = season.toString().padStart(2, '0')
    val paddedEpisode = episode.toString().padStart(2, '0')
    val marker = Regex(
        "S\\s*(?:${season}|$paddedSeason)\\s*E\\s*(?:${episode}|$paddedEpisode)\\b",
        RegexOption.IGNORE_CASE
    )
    return marker.find(title)?.let { match ->
        title.substring(match.range.last + 1)
            .trim()
            .removePrefix("•")
            .removePrefix("-")
            .removePrefix("·")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}

/** The still, the logo or title, and the episode/meta lines. */
@Composable
private fun MobileStreamsHeader(
    displayName: String,
    backdropUrl: String?,
    clearLogoUrl: String?,
    episodeLabel: String?,
    episodeTitle: String?,
    runtimeMinutes: Int?,
    sourceLabel: String,
    mismatchNotice: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(21f / 9f)
        ) {
            if (!backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                KBVoid.copy(alpha = 0.55f),
                                KBVoid.copy(alpha = 0.96f)
                            )
                        )
                    )
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = KBTextHi
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!clearLogoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(clearLogoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    )
                } else {
                    Text(
                        text = displayName,
                        color = KBTextHi,
                        fontFamily = OswaldFamily,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            episodeLabel?.let { label ->
                Text(
                    text = buildString {
                        append(label)
                        episodeTitle?.let { append(" · $it") }
                        runtimeMinutes?.let { append(" · ${formatRuntimeMinutes(it)}") }
                    },
                    color = KBAccent,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            mismatchNotice?.let { notice ->
                Text(
                    text = notice,
                    color = KBAccent,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(KBSurface, KBShapePanel)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Text(
                text = if (episodeLabel != null) {
                    sourceLabel
                } else {
                    listOfNotNull(
                        runtimeMinutes?.let(::formatRuntimeMinutes),
                        sourceLabel
                    ).joinToString(" · ")
                },
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MobileAddonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false
            )
        },
        shape = KBShapeCard,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = KBSurface,
            labelColor = KBTextLo,
            selectedContainerColor = KBAccent.copy(alpha = 0.28f),
            selectedLabelColor = KBAccent
        )
    )
}

/**
 * One source. The same content as the TV card - badges above or below the file
 * name per preference, the release name, the declared-episode warning, and the
 * title/description block - on a touch plate instead of a focus surface.
 */
@Composable
private fun MobileStreamCard(
    stream: Stream,
    badgesAbove: Boolean,
    declaredLabel: String?,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            if (badgesAbove) {
                StreamBadgeRow(
                    badges = stream.badges,
                    modifier = Modifier.padding(
                        bottom = if (stream.badges.isEmpty()) 0.dp else 6.dp
                    )
                )
            }

            stream.name?.takeIf { it.isNotBlank() }?.let { name ->
                Text(
                    text = name,
                    color = KBTextHi,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            declaredLabel?.let { label ->
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
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    top = if (stream.name.isNullOrBlank() && declaredLabel == null) 0.dp else 5.dp
                )
            )

            if (!badgesAbove) {
                StreamBadgeRow(
                    badges = stream.badges,
                    modifier = Modifier.padding(
                        top = if (stream.badges.isEmpty()) 0.dp else 6.dp
                    )
                )
            }
        }
    }
}

private fun Stream.displayText(): String =
    listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        description?.takeIf { it.isNotBlank() }
    )
        .distinct()
        .joinToString(separator = "\n")
        .ifBlank { "Stream details unavailable" }

@Composable
private fun MobileStreamsHeroState(title: String, message: String, loading: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(KBSurface, KBShapePanel)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = KBAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp)
                )
            }
            Text(
                text = title,
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
