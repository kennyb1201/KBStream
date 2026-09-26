package com.kennyb1201.kbstream.ui.streams

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.StreamBadgeRow
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel

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
    onStreamSelected: (selected: Stream, allSources: List<Stream>) -> Unit,
    viewModel: StreamsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadedKey by viewModel.loadedKey.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * A DRM stream cannot play on the MPV engine (NativePlayerActivity's own
     * handoff excludes DRM for the same reason), so picking one has to drop the
     * anime verdict published for this request - otherwise the anime rule would
     * send a protected source to an engine that cannot open it.
     */
    fun selectSource(selected: Stream, allSources: List<Stream>) {
        if (selected.drm?.licenseUrl != null) {
            PlayerEngine.clearLaunchAnime()
        }
        onStreamSelected(selected, allSources)
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
            // auto-select is broken.
            val top = streams.firstOrNull { !it.url.isNullOrBlank() }
            if (top != null) {
                selectSource(top, streams)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
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
                sourceLabel = sourceLabel
            )

            LazyColumn(
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

                    streams.isEmpty() -> {
                        item {
                            StreamsHeroState(
                                title = "NO SOURCES FOUND",
                                message = "Try another add-on or check that the provider is online."
                            )
                        }
                    }

                    else -> {
                        items(
                            items = streams,
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
                                onClick = {
                                    selectSource(stream, streams)
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
private fun StreamsHeader(
    displayName: String,
    clearLogoUrl: String?,
    episodeLabel: String?,
    episodeTitle: String?,
    runtimeMinutes: Int?,
    sourceLabel: String
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth()
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
                    runtimeMinutes?.let { append(" · ${formatStreamRuntime(it)}") }
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

        Text(
            // Episodes already carry the runtime on the title line; movies
            // (no episode label) keep it on this meta line instead.
            text = if (episodeLabel != null) {
                sourceLabel
            } else {
                listOfNotNull(
                    runtimeMinutes?.let(::formatStreamRuntime),
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

private fun formatStreamRuntime(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

@Composable
private fun StreamCard(
    stream: Stream,
    badgesAbove: Boolean,
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

            Text(
                text = stream.displayText(),
                color = if (isFocused) KBAccent else KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    top = if (stream.name.isNullOrBlank()) 0.dp else 5.dp
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
