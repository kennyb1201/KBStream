package com.kennyb1201.kbstream.ui.mobile

import android.widget.Toast
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kennyb1201.kbstream.data.debrid.TorBoxResolve
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.download.DownloadGate
import com.kennyb1201.kbstream.data.download.DownloadGates
import com.kennyb1201.kbstream.data.download.DownloadIdentity
import com.kennyb1201.kbstream.data.download.DownloadTarget
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.download.DownloadsManager
import com.kennyb1201.kbstream.data.reporting.StreamRankReport
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.streamNavigationKey
import com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality
import com.kennyb1201.kbstream.domain.streamengine.AutoplayWalk
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.StreamBadgeRow
import com.kennyb1201.kbstream.ui.components.formatRuntimeMinutes
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.MobileFilterChip
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.streams.StreamItemKeys
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import com.kennyb1201.kbstream.ui.home.failedAddonsMessage
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.OswaldFamily
import kotlinx.coroutines.launch

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
    /**
     * The target on screen - the route's own contentType/streamId. REFRESH re-asks
     * for THIS title instead of replaying one remembered in the ViewModel (see
     * StreamsViewModel.refresh).
     */
    contentType: String,
    streamId: String,
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
        sourceAddons: List<String?>,
        // The add-on tab the viewer had selected when they tapped this row
        // (null for All, or for a launch with no picker at all). It travels
        // ONLY on this manual-tap path: the player keeps a manual "next
        // source" inside that tab while every automatic recovery stays merged.
        // The name, never a tab index - the groups can reorder (see
        // StreamsViewModel.addonGroups).
        selectedAddon: String?
    ) -> Unit,
    viewModel: StreamsViewModel
) {
    MobileTheme {
        val streams by viewModel.streams.collectAsStateWithLifecycle()
        val sourceAddons by viewModel.sourceAddons.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val loadedKey by viewModel.loadedKey.collectAsStateWithLifecycle()
        val addonGroups by viewModel.addonGroups.collectAsStateWithLifecycle()
        // The add-ons that did not answer this request, named above the rows
        // they would have added - the same line the television picker shows.
        // A failed add-on contributes no sources, so without it a picker short
        // one provider's rows read exactly like a title with thin sources.
        val addonFailures by viewModel.addonFailures.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        // This phone's downloads for the profile on screen. The rows read their
        // own download state from here - queued, transferring with its own
        // percentage, paused, here - so the picker says what is true about each
        // source rather than offering the same button for all of them (§4.1).
        val downloads = rememberDownloadRecords()
        // The row whose download menu is open, if any. Held by the screen rather
        // than by the card: a [PosterContextMenu] is its own window layer, and
        // one sheet for the list is what every other phone row menu does.
        var menuRow by remember(loadedKey) { mutableStateOf<MobileStreamMenuRow?>(null) }
        val routeKey = remember(contentType, streamId) {
            streamNavigationKey(contentType, streamId)
        }

        // A DRM stream cannot play on the MPV engine, so picking one drops the
        // anime verdict published for this request (see the TV picker).
        //
        // A row with no URL of its own is a torrent hash, which this app cannot
        // play by itself (see the TV picker's note): when TorBox holds it, ask
        // for the CDN link first and play that, else say so instead of doing
        // nothing when the card is pressed.
        val resolveScope = rememberCoroutineScope()

        fun selectSource(
            selected: Stream,
            allSources: List<Stream>,
            addons: List<String?>,
            selectedAddon: String?
        ) {
            if (selected.drm?.licenseUrl != null) {
                PlayerEngine.clearLaunchAnime()
            }
            if (!TorBoxResolve.isUnresolved(selected)) {
                onStreamSelected(selected, allSources, addons, selectedAddon)
                return
            }
            resolveScope.launch {
                val resolved = TorBoxResolve.playableStream(context, selected)
                if (resolved == null) {
                    Toast.makeText(
                        context,
                        "TorBox could not prepare this source",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                onStreamSelected(
                    resolved,
                    allSources.map { if (it === selected) resolved else it },
                    addons,
                    selectedAddon
                )
            }
        }

        // "Play Manually" (or an episode long-press) opens THIS picker and must
        // not be auto-selected past; MainActivity marks those targets.
        val manualPick = ManualSourceSelection.isPendingPickFor(loadedKey)

        // A viewer's own REFRESH must not relaunch the player: the reload runs
        // the same loading transition auto-play fires on. One press suppresses
        // auto-select for the rest of this picker's life.
        var refreshed by remember(loadedKey) { mutableStateOf(false) }
        val autoSelectSuppressed = suppressAutoSelect || refreshed

        /*
         * Auto-play's candidate rules, as a function of what is LEFT: the walk
         * removes a hash row that would not prepare and picks again, so the
         * episode rules, the season-pack test and the ceiling are re-applied to
         * the remainder (see AutoplayWalk).
         */
        // One capped pick for the whole phone (see autoplayPickFor), so "the row
        // auto-play would play" is one function rather than several opinions.
        // The Detail page no longer downloads, so this picker is its only
        // caller.
        fun autoplayPickFrom(remaining: List<Stream>): Stream? =
            autoplayPickFor(context, remaining, season, episode, runtimeMinutes)

        /*
         * Auto-play's start, the TV picker's exact route: a url-bearing pick
         * starts at once, a hash row goes through TorBox, and a failure walks to
         * the next candidate - bounded. A press on a card still takes
         * selectSource's one attempt and its message.
         */
        fun autoplayStart(sources: List<Stream>, addons: List<String?>) {
            resolveScope.launch {
                val start = AutoplayWalk.start(
                    candidates = sources,
                    pick = { remaining -> autoplayPickFrom(remaining) },
                    resolve = { TorBoxResolve.playableStream(context, it) }
                )
                StreamRankReport.noteAutoPlay(
                    start?.let { picked ->
                        "started · ${StreamRanker.labelOf(picked.stream)}" +
                            if (picked.resolved) " (prepared from its hash)" else ""
                    } ?: "none - the picker is showing instead " +
                        "(episode length ${runtimeMinutes?.let { "$it min" } ?: "unknown"})"
                )
                // Auto-play carries no tab: it is not a tab selection at all,
                // so the player's manual advance must stay merged if the viewer
                // reaches for it.
                start?.let { onStreamSelected(it.stream, it.sources, addons, null) }
            }
        }

        // One autoplay attempt per target: the walk makes network calls, so a
        // stream update landing mid-walk must not start a second one.
        var autoplayHandled by remember(loadedKey) { mutableStateOf(false) }

        // Auto-play, with the TV's exact decision chain: the episode pick first
        // (skip dead placeholders and files that declare another episode), then
        // the viewer's quality ceiling, then the walk, then the rank report that
        // says what was started - or why nothing was.
        LaunchedEffect(isLoading, streams, manualPick) {
            if (
                !isLoading && streams.isNotEmpty() && !autoSelectSuppressed &&
                !manualPick && AppPreferences.getAutoSelectStream(context)
            ) {
                if (autoplayHandled) return@LaunchedEffect
                autoplayHandled = true
                val top = autoplayPickFrom(streams)
                if (top == null) {
                    StreamRankReport.noteAutoPlay(
                        "none - the picker is showing instead " +
                            "(episode length ${runtimeMinutes?.let { "$it min" } ?: "unknown"})"
                    )
                } else {
                    autoplayStart(streams, sourceAddons)
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
        // Per-add-on tabs, only when more than one stream add-on answered: with
        // one, the merged list IS that add-on's list.
        // Remembered by add-on NAME, not by index. REFRESH keeps loadedKey, so an
        // index survived while addonGroups could reorder or shrink - and the same
        // index then pointed at a DIFFERENT add-on's streams, or fell out of
        // range (all streams shown with no chip selected).
        var selectedAddonName by remember(loadedKey) { mutableStateOf<String?>(null) }
        val selectedAddonIndex = selectedAddonName
            ?.let { name -> addonGroups.indexOfFirst { it.addonName == name } }
            ?: -1
        val visibleStreams =
            if (addonGroups.size > 1 && selectedAddonIndex >= 0) {
                addonGroups[selectedAddonIndex].streams
            } else {
                streams
            }
        val streamKeys = remember(visibleStreams) { StreamItemKeys.of(visibleStreams) }

        // The count follows the list on screen: on a tab it counts that add-on's
        // rows, not All's - the merged count read as a miscount of the rows
        // below it.
        val sourceLabel = when {
            isLoading -> "Finding sources"
            visibleStreams.isNotEmpty() -> "${visibleStreams.size} sources found"
            else -> "No sources found"
        }

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
                cap = AppPreferences.getEffectiveAutoPlayQuality(context)
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
                    // Both can apply at once, and the ceiling notice is the
                    // actionable half - so stack them rather than letting the
                    // other-episode notice hide the one that names the setting.
                    notices = listOfNotNull(mismatchNotice, ceilingNotice),
                    onBack = { backDispatcher?.onBackPressed() }
                )
            }

            if (addonFailures.isNotEmpty()) {
                item(key = "addon-failures") {
                    Text(
                        text = failedAddonsMessage(addonFailures, surface = "this list"),
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    )
                }
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
                            viewModel.refresh(contentType, streamId, runtimeMinutes)
                        }
                    )
                    if (addonGroups.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") {
                                MobileAddonChip(
                                    label = "All",
                                    selected = selectedAddonIndex < 0,
                                    onClick = { selectedAddonName = null }
                                )
                            }
                            itemsIndexed(
                                items = addonGroups,
                                key = { index, group -> "addon:" + index + ":" + group.addonName }
                            ) { index, group ->
                                MobileAddonChip(
                                    label = group.addonName,
                                    selected = selectedAddonIndex == index,
                                    onClick = { selectedAddonName = group.addonName }
                                )
                            }
                        }
                    }
                }
            }

            when {
                // Rows render as add-ons answer (the ViewModel publishes them
                // per add-on); only an empty hand shows the hero, so a 1 s
                // first answer is not hidden behind a 15 s second one.
                visibleStreams.isEmpty() && isLoading -> item(key = "loading") {
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

                else -> {
                    itemsIndexed(
                        items = visibleStreams,
                        key = { index, _ -> streamKeys[index] }
                    ) { _, stream ->
                        // The id the player will carry as `parent_id` for this
                        // title, so a download made here lands on the same
                        // watch-history row a streamed play does. Published by
                        // the route on a phone only; a picker with no route
                        // memory (a direct call in a test, say) falls back to
                        // the target's own id rather than refusing to offer a
                        // download.
                        val route = CurrentStreamsRoute.current(routeKey)
                        val parentId = route?.parentId?.takeIf { it.isNotBlank() } ?: streamId
                        val parentType = route?.parentType?.takeIf { it.isNotBlank() } ?: contentType
                        val gate = downloadGate(context, stream, isLiveChannel = false)
                        val target = downloadTargetFor(
                            parentId = parentId,
                            parentType = parentType,
                            title = displayName,
                            qualityLabel = StreamRanker.labelOf(stream),
                            episodeLabel = episodeLabel,
                            season = season,
                            episode = episode,
                            posterUrl = route?.posterUrl,
                            episodeStreamId = streamId
                        )
                        MobileStreamCard(
                            stream = stream,
                            badgesAbove = AppPreferences.getBadgesAboveFile(context),
                            declaredLabel = declaredLabelFor(stream, season, episode),
                            // A tap still plays the row. The hold is the phone's
                            // download menu (spec §2), and a row that has no
                            // download at all (a live channel) has no hold
                            // either: there is nothing it could open.
                            onLongClick = if (gate == DownloadGate.Hidden) {
                                null
                            } else {
                                {
                                    menuRow = MobileStreamMenuRow(
                                        stream = stream,
                                        gate = gate,
                                        target = target,
                                        // The row's existing media key - the
                                        // same one DownloadsManager.enqueue
                                        // writes - so the menu reads the same
                                        // record the row always has (spec §2).
                                        mediaKey = DownloadIdentity.mediaKey(
                                            parentId,
                                            parentId
                                        )
                                    )
                                }
                            },
                            // The tab the viewer is looking at goes with the
                            // tap and with nothing else: an auto-play start
                            // carries no tab (see autoplayStart).
                            onClick = {
                                selectSource(
                                    stream,
                                    streams,
                                    sourceAddons,
                                    selectedAddonName
                                )
                            }
                        )
                    }
                    // The final ranked list replaces these rows when the last
                    // add-on answers, so name the state instead of letting a
                    // settled-looking list reorder under the thumb.
                    if (isLoading) {
                        item(key = "loading-more") {
                            Row(
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = KBAccent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.height(14.dp)
                                )
                                Text(
                                    text = "Still finding sources…",
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // The row's download menu.
        //
        // It is the same touch [PosterContextMenu] every other phone row menu
        // uses, so a hold looks and dismisses the same here as on a poster -
        // and its rows are [downloadPlan]'s, the state machine the chip used to
        // draw, so a state cannot mean one thing here and another in the
        // Downloads screen.
        menuRow?.let { row ->
            // Read live, not at press time: the menu is open while the
            // download it started changes state, and it must say what is true
            // now rather than what was true when the finger went down.
            val record = downloadFor(downloads, row.mediaKey, season, episode)
            val plan = when (val gate = row.gate) {
                // Unreachable from this screen (it passes isLiveChannel = false),
                // and handled anyway so a live row can never grow a download.
                DownloadGate.Hidden -> emptyList()

                is DownloadGate.Disabled -> downloadPlan(
                    record = record,
                    idleLabel = DownloadLabel,
                    disabledReason = gate.reason,
                    inert = false
                )

                DownloadGate.Offline -> downloadPlan(
                    record = record,
                    idleLabel = DownloadGates.CONNECT_TO_DOWNLOAD,
                    disabledReason = null,
                    inert = true
                )

                is DownloadGate.Allowed -> downloadPlan(
                    record = record,
                    idleLabel = DownloadLabel,
                    disabledReason = null,
                    inert = false
                )
            }
            val label = StreamRanker.labelOf(row.stream)
            val heading = row.stream.name?.takeIf { it.isNotBlank() } ?: label
            PosterContextMenu(
                title = heading,
                // The release name only when it is not already the heading: a
                // sheet that prints one line twice says nothing.
                subtitle = label.takeIf { it != heading },
                actions = plan.map { item ->
                    PosterContextAction(
                        // The reason is only drawn on a disabled row, where it
                        // is the whole point of showing the row at all (DRM).
                        description = item.reason,
                        label = item.label,
                        isDestructive = item.kind == DownloadActionKind.DELETE,
                        isEnabled = item.enabled,
                        onClick = {
                            // The menu closes on press; the row's own state (and
                            // the manager's Toast) is what says it landed.
                            menuRow = null
                            when (item.kind) {
                                DownloadActionKind.DOWNLOAD -> downloadPress(
                                    context = context,
                                    scope = resolveScope,
                                    stream = row.stream,
                                    record = record,
                                    target = row.target
                                )

                                DownloadActionKind.PLAY ->
                                    record?.let { playDownloaded(context, it) }

                                DownloadActionKind.RESUME ->
                                    record?.let { DownloadsManager.resume(context, it.id) }

                                DownloadActionKind.DELETE ->
                                    record?.let { DownloadsManager.delete(context, it.id) }

                                // A state word is not an action: the row is
                                // drawn disabled and pressing it does nothing.
                                DownloadActionKind.STATE -> Unit
                            }
                        }
                    )
                },
                onDismiss = { menuRow = null }
            )
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
    // Every explanation that applies, stacked (see the TV picker's note).
    notices: List<String>,
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
            MobileBackButton(
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            )
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

            notices.forEach { notice ->
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
    MobileFilterChip(label = label, selected = selected, onClick = onClick)
}

/**
 * One source. The same content as the TV card - badges above or below the file
 * name per preference, the release name, the declared-episode warning, and the
 * title/description block - on a touch plate instead of a focus surface.
 *
 * The card carries no download control of its own any more: [onLongClick] is the
 * hold that opens the row's download menu, and the row's text is left to say
 * what the source is. The card is the touch [KBCard], which already accepts the
 * hold, so a second card type was not needed.
 */
@Composable
private fun MobileStreamCard(
    stream: Stream,
    badgesAbove: Boolean,
    declaredLabel: String?,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
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

/**
 * The row a download menu was opened on, and everything its menu needs that the
 * card does not know: the gate it was drawn under, the target enqueue takes, and
 * the media key its record is filed under.
 */
private data class MobileStreamMenuRow(
    val stream: Stream,
    val gate: DownloadGate,
    val target: DownloadTarget,
    val mediaKey: String
)

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
