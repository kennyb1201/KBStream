package com.kennyb1201.kbstream.ui.home

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.YouTubeTrailerPlayer
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay

private val HomePosterWidth = 124.dp
private val HomePosterHeight = 180.dp
private val HomeRailGap = 12.dp
private const val HeroTrailerDwellMs = 4_000L

@Composable
private fun TopActionItem(label: String, onClick: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (focused) Color.White else Color.White.copy(alpha = .76f),
        fontSize = 13.sp,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color.White.copy(alpha = .16f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDismiss()
                    true
                } else false
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun TopActionBar(onSearch: () -> Unit, onOpenGuide: () -> Unit, onManageAddons: () -> Unit, onOpenSimkl: () -> Unit, firstActionFocusRequester: FocusRequester, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), Arrangement.End, Alignment.CenterVertically) {
        TopActionItem("SEARCH", onSearch, onDismiss, Modifier.focusRequester(firstActionFocusRequester).padding(end = 8.dp))
        TopActionItem("TV GUIDE", onOpenGuide, onDismiss, Modifier.padding(end = 8.dp))
        TopActionItem("ADD-ONS", onManageAddons, onDismiss, Modifier.padding(end = 8.dp))
        TopActionItem("SIMKL", onOpenSimkl, onDismiss)
    }
}

@Composable
private fun HomeHero(preview: MetaPreview, meta: Meta?, trailerKey: String?, autoPlayTrailer: Boolean) {
    val context = LocalContext.current
    val title = meta?.name ?: preview.name
    val backdrop = meta?.background ?: preview.background ?: meta?.poster ?: preview.poster
    val clearLogo = meta?.logo ?: preview.logo
    val trailerPlaying = !trailerKey.isNullOrBlank() && autoPlayTrailer
    val year = meta?.releaseInfo?.let { Regex("""\b(?:19|20)\d{2}\b""").find(it)?.value }
    val rating = meta?.releaseInfo?.let { Regex("""\b(?:G|PG|PG-13|R|NC-17|TV-Y7|TV-Y|TV-G|TV-PG|TV-14|TV-MA)\b""").find(it.uppercase())?.value }
    val imdb = meta?.imdbRating?.trim()?.takeIf { it.isNotBlank() }?.let { "IMDb $it" }
    val runtime = meta?.runtime?.trim()?.takeIf { it.isNotBlank() }
    val genre = meta?.genres?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    val heroInfo = listOfNotNull(imdb, year, rating, runtime, genre).joinToString("  •  ")
    Box(Modifier.fillMaxWidth().height(330.dp).background(Color.Black)) {
        if (trailerPlaying) {
            YouTubeTrailerPlayer(videoId = trailerKey, modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(backdrop).crossfade(true).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(colorStops = arrayOf(0f to Color.Black.copy(.98f), .22f to Color.Black.copy(.92f), .46f to Color.Black.copy(.62f), .72f to Color.Black.copy(.14f), 1f to Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colorStops = arrayOf(0f to Color.Transparent, .58f to Color.Transparent, 1f to Color.Black.copy(.88f)))))
        Column(Modifier.align(Alignment.BottomStart).width(480.dp).padding(start = 32.dp, bottom = 24.dp)) {
            if (!clearLogo.isNullOrBlank()) AsyncImage(ImageRequest.Builder(context).data(clearLogo).crossfade(true).build(), title, Modifier.width(290.dp).height(86.dp), contentScale = ContentScale.Fit) else Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (heroInfo.isNotBlank()) Text(heroInfo, color = Color.White.copy(.94f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            (meta?.description ?: preview.description)?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White.copy(.8f), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, color = Color.White.copy(.94f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)) }

@Composable
private fun CompactUpNextCard(item: UpNextItem, onClick: () -> Unit, onFocus: () -> Unit = {}, onUpPressed: () -> Unit = {}, focusRequester: FocusRequester? = null, badgeColor: Color, badgeText: String) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "homeCardScale")
    KBCard(onClick, Modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).width(HomePosterWidth).scale(scale).padding(end = HomeRailGap).onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { onUpPressed(); true } else false }) {
        Column { Box(Modifier.width(HomePosterWidth).height(HomePosterHeight).clip(RoundedCornerShape(8.dp)).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }.focusable().clickable(onClick = onClick)) {
            AsyncImage(ImageRequest.Builder(LocalContext.current).data(item.poster).crossfade(true).build(), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (item.badge != UpNextBadge.NEXT_UP && item.badge != UpNextBadge.CONTINUE_WATCHING) Text(badgeText, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).background(badgeColor).padding(horizontal = 5.dp, vertical = 3.dp))
            item.progressPercent?.takeIf { item.badge == UpNextBadge.CONTINUE_WATCHING && it > 0f }?.let { progress -> Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp).height(4.dp).background(Color.White.copy(.28f))) { Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(KBAccent)) } }
        }; item.subtitle?.let { Text(it, color = KBTextLo, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) } }
    }
}

@Composable
fun HomeScreen(onItemClick: (MetaPreview) -> Unit, onOpenDetailTarget: (MetaPreview, StreamsTarget, String?) -> Unit, onManageAddons: () -> Unit, onSearch: () -> Unit = {}, onOpenGuide: () -> Unit = {}, onOpenSimkl: () -> Unit, viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val rails by viewModel.rails.collectAsStateWithLifecycle(); val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle(); val upNext by viewModel.upNext.collectAsStateWithLifecycle(); val isLoading by viewModel.isLoading.collectAsStateWithLifecycle(); val error by viewModel.error.collectAsStateWithLifecycle(); val heroMeta by viewModel.heroMeta.collectAsStateWithLifecycle(); val heroTrailerKey by viewModel.heroTrailerKey.collectAsStateWithLifecycle()
    var showTopBar by remember { mutableStateOf(false) }; val topBarFocusRequester = remember { FocusRequester() }; var lastPosterFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }; val firstHomeItem = remember(rails) { rails.asSequence().flatMap { it.items.asSequence() }.firstOrNull() }; var focusedItem by remember { mutableStateOf<MetaPreview?>(firstHomeItem) }; var heroTrailerReady by remember { mutableStateOf(false) }
    fun openTopBar(requester: FocusRequester) { lastPosterFocusRequester = requester; showTopBar = true }; fun dismissTopBar() { showTopBar = false; lastPosterFocusRequester?.requestFocus() }; fun selectHero(item: MetaPreview) { focusedItem = item }
    LaunchedEffect(showTopBar) { if (showTopBar) topBarFocusRequester.requestFocus() }; LaunchedEffect(firstHomeItem?.id, firstHomeItem?.type) { if (focusedItem == null && firstHomeItem != null) focusedItem = firstHomeItem }; LaunchedEffect(focusedItem?.id, focusedItem?.type) { heroTrailerReady = false; focusedItem?.let { viewModel.resolveHeroMeta(it); delay(HeroTrailerDwellMs); heroTrailerReady = true } }; LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshUpNext(); viewModel.refreshWatchedStatusForCurrentRails() }
    fun openUpNext(item: UpNextItem) { val parentId = item.parentId; val parentType = item.parentType; if (parentId.isNullOrBlank() || parentType.isNullOrBlank()) return; val detail = MetaPreview(parentId, parentType, item.title, item.poster); val target = StreamsTarget(parentType, item.episodeStreamId ?: item.parentId ?: item.id, item.title, item.title, item.season, item.episode, item.startPositionMs); selectHero(detail); onOpenDetailTarget(detail, target, item.poster) }
    Box(Modifier.fillMaxSize().background(Color.Black)) { Column(Modifier.fillMaxSize()) { focusedItem?.let { HomeHero(it, heroMeta, heroTrailerKey, heroTrailerReady) }; LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (upNext.isNotEmpty()) item(key = "continue_watching") { Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp)) { SectionTitle("Continue Watching"); LazyRow(contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) { items(upNext, key = { it.id }) { item -> val requester = remember { FocusRequester() }; val hero = MetaPreview(item.parentId ?: item.id, item.parentType ?: "movie", item.title, item.poster); CompactUpNextCard(item, { openUpNext(item) }, { selectHero(hero) }, { openTopBar(requester) }, requester, when(item.badge) { UpNextBadge.CONTINUE_WATCHING -> KBAccent; UpNextBadge.NEXT_UP -> Color(0xFF2E5BFF); UpNextBadge.NEW_EPISODE -> Color(0xFF2E7D32); UpNextBadge.NEW_SEASON -> Color(0xFF6A1B9A) }, when(item.badge) { UpNextBadge.CONTINUE_WATCHING -> "CONTINUE"; UpNextBadge.NEXT_UP -> "NEXT UP"; UpNextBadge.NEW_EPISODE -> "NEW"; UpNextBadge.NEW_SEASON -> "NEW SEASON" }) } } } }
        when { isLoading -> item(key="loading") { Text("Loading catalogs...", modifier=Modifier.padding(24.dp)) }; error != null -> item(key="error") { Text("Error: $error", modifier=Modifier.padding(24.dp)) }; rails.isEmpty() -> item(key="empty") { Text("No catalogs available. Add an addon to get started.", modifier=Modifier.padding(24.dp)) }; else -> items(rails, key={ "${it.addonName}:${it.catalogName}:${it.type}" }) { rail -> Column(Modifier.padding(start=16.dp, top=4.dp, bottom=6.dp)) { SectionTitle(rail.catalogName.replace("_", " ").split(" ").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }); LazyRow(contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) { items(rail.items, key={ "${it.type}:${it.id}" }) { meta -> val requester = remember { FocusRequester() }; val watched = viewModel.watchedKey(meta.id, meta.type) in watchedKeys; PosterCard(posterUrl=meta.poster, contentDescription=meta.name, isWatched=watched, onClick={ selectHero(meta); onItemClick(meta) }, modifier=Modifier.focusRequester(requester).width(HomePosterWidth).height(HomePosterHeight).padding(end=HomeRailGap).onFocusChanged { if (it.isFocused) selectHero(meta) }, onPosterError={ throwable -> Log.e("HOME_UI", "Catalog poster load failed, title=${meta.name}, poster=${meta.poster}", throwable) }) } } } } }
        item(key="bottom_spacer") { Spacer(Modifier.height(36.dp)) }
    } }
    AnimatedVisibility(visible=showTopBar, enter=fadeIn(), exit=fadeOut(), modifier=Modifier.align(Alignment.TopCenter).fillMaxWidth()) { Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(colors=listOf(Color.Black.copy(.96f), Color.Black.copy(.72f), Color.Transparent)))) { TopActionBar(onSearch, onOpenGuide, onManageAddons, onOpenSimkl, topBarFocusRequester) { dismissTopBar() } } }
    }
}
