package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.metaLine
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.ui.actor.ACTOR_CREW_RAIL
import com.kennyb1201.kbstream.ui.actor.ACTOR_MOVIES_RAIL
import com.kennyb1201.kbstream.ui.actor.ACTOR_TV_RAIL
import com.kennyb1201.kbstream.ui.actor.ActorViewModel
import com.kennyb1201.kbstream.ui.actor.actorBrowseScreenKey
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberGlobalLandscape
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.MobileLoading
import com.kennyb1201.kbstream.ui.components.touch.MobileStatus
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterTile
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * An actor's page, for a finger.
 *
 * Same [ActorViewModel] as the TV page: the same `load(actorId)`, the same
 * sorted filmography (`sortedCredits`, so popularity order and the talk-show
 * filter are not re-derived here), the same resolved-id map and watched sets,
 * and the same `resolveAndNavigate` / mark-as-watched calls. The hidden-title
 * filter is the TV's too, with the resolved IMDb id in the tuple so a title
 * hidden from Home or the Library leaves the filmography as well.
 *
 * The TV page is a full-bleed still behind horizontal rails of credits. The
 * phone keeps the still as a header panel and lays each rail out as a titled
 * band in one vertical grid, which is the same adaptation the genre / studio /
 * decade pages use. A tap records the same [BrowseReturnTarget] the TV rail
 * records (rail key `moviesrow` / `tvrow` / `crewrow`, the credit's id plus its
 * role as the item), so backing out of a title's Detail page reopens the
 * filmography scrolled to that card. The record is honoured by scrolling: there
 * is no focus ring on a phone to put back.
 *
 * The long-press sheet is the shared one and offers what the TV menu offers:
 * Go to Details (recording the same place first), the watched toggle resolved
 * through the IMDb key with the credit's own `tmdb:<id>` fallback, the library
 * add and Hide everywhere.
 */
@Composable
fun ActorScreenMobile(
    actorId: Int,
    onBack: () -> Unit,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = viewModel()
) {
    val person by viewModel.person.collectAsStateWithLifecycle()
    val resolvedCreditIds by viewModel.resolvedCreditIds.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val topWorkBackdropUrl by viewModel.topWorkBackdropUrl.collectAsStateWithLifecycle()
    val topWorkCredit by viewModel.topWorkCredit.collectAsStateWithLifecycle()

    val overridePaths = rememberImageOverridePaths()
    val hiddenTitleKeys = rememberHiddenTitleKeys()

    val screenKey = remember(actorId) { actorBrowseScreenKey(actorId) }
    // A saveable scroll record, scoped to this actor: rotation keeps the place,
    // a different actor opens at the top.
    val gridState = key(actorId) { rememberLazyGridState() }
    var returnTarget by remember(actorId) {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    var menu by remember(actorId) {
        mutableStateOf<Pair<TmdbPersonCredit, BrowseReturnTarget>?>(null)
    }

    fun creditHidden(credit: TmdbPersonCredit, normalizedType: String): Boolean =
        HiddenTitles.hides(
            hiddenTitleKeys,
            normalizedType,
            credit.title ?: credit.name,
            (credit.releaseDate ?: credit.firstAirDate)?.take(4)?.toIntOrNull(),
            credit.id.toString(),
            resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedType)]
        )

    val movies = remember(person, resolvedCreditIds, hiddenTitleKeys) {
        val sorted = viewModel.sortedCredits(person)
        sorted.filter { it.mediaType == "movie" && !creditHidden(it, "movie") }
    }
    val tvShows = remember(person, resolvedCreditIds, hiddenTitleKeys) {
        val sorted = viewModel.sortedCredits(person)
        sorted.filter { it.mediaType == "tv" && !creditHidden(it, "series") }
    }
    // The directing/writing work, hidden-filtered like the cast rails. A
    // director or writer tapped from Detail's people rail used to land on a
    // cast-only page; this is the section that shows the work they were
    // tapped for.
    val crewCredits = remember(person, resolvedCreditIds, hiddenTitleKeys) {
        viewModel.sortedCrewCredits(person).filter { credit ->
            val normalizedType = if (credit.mediaType == "tv") "series" else "movie"
            !creditHidden(credit, normalizedType)
        }
    }

    LaunchedEffect(actorId) {
        viewModel.load(actorId)
        returnTarget = BrowseReturnFocus.consume(screenKey)
    }

    // Puts the grid back on the credit the Detail page was opened from. The
    // credits can land after this grid composes, so the record is held until
    // the card is actually in the list; a record for a rail this page does not
    // paint falls through to the first credit.
    LaunchedEffect(returnTarget, movies, tvShows, crewCredits) {
        val target = returnTarget ?: return@LaunchedEffect
        // Grid positions, laid out exactly as the items below are added: the
        // header, the optional biography, a label for each non-empty rail, and
        // the cards. The first card is the fallthrough for a record this page
        // cannot name (the credit is gone), which is where an ordinary open
        // starts anyway.
        //
        // Keyed by the same composite item id the record carries (id + role),
        // so a dual-role credit's two cards do not overwrite each other and the
        // return lands on the role that was tapped.
        val positions = LinkedHashMap<Pair<String, String>, Int>()
        var next = 1 + (if (person?.biography.orEmpty().isNotBlank()) 1 else 0)
        if (movies.isNotEmpty()) {
            next += 1
            movies.forEachIndexed { index, credit ->
                positions[ACTOR_MOVIES_RAIL to viewModel.creditItemId(credit)] = next + index
            }
            next += movies.size
        }
        if (tvShows.isNotEmpty()) {
            next += 1
            tvShows.forEachIndexed { index, credit ->
                positions[ACTOR_TV_RAIL to viewModel.creditItemId(credit)] = next + index
            }
            next += tvShows.size
        }
        if (crewCredits.isNotEmpty()) {
            next += 1
            crewCredits.forEachIndexed { index, credit ->
                positions[ACTOR_CREW_RAIL to viewModel.creditItemId(credit)] = next + index
            }
        }
        val recordedIndex = target.railKey?.let { railKey ->
            positions[railKey to target.itemId]
        }
        val index = recordedIndex
            ?: positions.values.firstOrNull()
            ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(index) }
        returnTarget = null
    }

    fun creditWatched(credit: TmdbPersonCredit, normalizedType: String): Boolean {
        val imdbKey = resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedType)]
            ?.let { viewModel.watchedKey(it, normalizedType) }
        return (imdbKey != null && imdbKey in watchedKeys) ||
            viewModel.watchedKey(WatchedStatusKeys.tmdbKeyId(credit.id), normalizedType) in
            watchedKeys
    }

    fun creditPartiallyWatched(credit: TmdbPersonCredit, normalizedType: String): Boolean {
        if (creditWatched(credit, normalizedType)) return false
        val imdbKey = resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedType)]
            ?.let { viewModel.watchedKey(it, normalizedType) }
        return (imdbKey != null && imdbKey in partialWatchedKeys) ||
            viewModel.watchedKey(WatchedStatusKeys.tmdbKeyId(credit.id), normalizedType) in
            partialWatchedKeys
    }

    fun openCredit(credit: TmdbPersonCredit, target: BrowseReturnTarget) {
        BrowseReturnFocus.record(target)
        viewModel.resolveAndNavigate(
            credit.id,
            credit.mediaType ?: "movie",
            onNavigateDetail
        )
    }

    MobileTheme {
        // Landscape cards are wider than posters, so the columns widen with
        // them - the same 168dp the catalog grid uses.
        val landscape = rememberGlobalLandscape()
        val posterSize = rememberTouchPosterSize()
        val tileWidth =
            if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width
        // Three poster columns in portrait, more when the phone is turned.
        val columns = rememberMobileGridColumns(
            preferredTileWidth = tileWidth,
            landscape = landscape
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 28.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    val loaded = person
                    Column {
                        // Top-left of the header, on its own line: the hero
                        // below starts with the portrait, so an overlaid arrow
                        // would sit on the actor's face.
                        MobileBackButton(onBack = onBack)
                        ActorHeader(
                            name = loaded?.name ?: "Actor",
                            meta = loaded?.metaLine().orEmpty(),
                            knownFor = topWorkCredit?.let { it.title ?: it.name },
                            portraitUrl = loaded?.profilePath
                                ?.let { TmdbRepository.PROFILE_BASE + it },
                            backdropUrl = imageOverrideBackdropUrl(
                                paths = overridePaths,
                                tmdbId = topWorkCredit?.id,
                                screen = ImageOverrides.SCREEN_ALL,
                                fallbackUrl = topWorkBackdropUrl
                            )
                        )
                    }
                }

                when {
                    isLoading && person == null -> item(
                        key = "loading",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        MobileLoading()
                    }

                    error != null && person == null -> item(
                        key = "error",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        MobileStatus(
                            message = "Error: $error",
                            actionLabel = "TRY AGAIN",
                            onAction = { viewModel.load(actorId) }
                        )
                    }

                    person != null -> {
                        val biography = person?.biography.orEmpty()
                        if (biography.isNotBlank()) {
                            item(key = "biography", span = { GridItemSpan(maxLineSpan) }) {
                                BiographyBlock(biography)
                            }
                        }

                        if (movies.isNotEmpty()) {
                            item(key = "moviesheader", span = { GridItemSpan(maxLineSpan) }) {
                                SectionLabel("MOVIES")
                            }
                            itemsIndexed(
                                items = movies,
                                key = { _, credit ->
                                    "movie:${credit.id}:${credit.character.orEmpty()}"
                                },
                                // One reuse pool per section: a film credit and
                                // a series credit are different cells, and a
                                // shared pool made the composables swap
                                // contents across the grid's boundary (the TV
                                // rail's rule).
                                contentType = { _, _ -> "actor-movie" }
                            ) { index, credit ->
                                CreditTile(
                                    // The two credit sections fill in as the
                                    // person loads; each cell fades into its
                                    // slot rather than the grid appearing.
                                    modifier = Modifier.animateItem(),
                                    credit = credit,
                                    isWatched = creditWatched(credit, "movie"),
                                    isPartiallyWatched =
                                        creditPartiallyWatched(credit, "movie"),
                                    onClick = {
                                        openCredit(
                                            credit,
                                            BrowseReturnTarget(
                                                screenKey = screenKey,
                                                itemId = viewModel.creditItemId(credit),
                                                position = index,
                                                railKey = ACTOR_MOVIES_RAIL
                                            )
                                        )
                                    },
                                    onLongClick = {
                                        menu = credit to BrowseReturnTarget(
                                            screenKey = screenKey,
                                            itemId = viewModel.creditItemId(credit),
                                            position = index,
                                            railKey = ACTOR_MOVIES_RAIL
                                        )
                                    }
                                )
                            }
                        }

                        if (tvShows.isNotEmpty()) {
                            item(key = "tvheader", span = { GridItemSpan(maxLineSpan) }) {
                                SectionLabel("TV SHOWS")
                            }
                            itemsIndexed(
                                items = tvShows,
                                key = { _, credit ->
                                    "tv:${credit.id}:${credit.character.orEmpty()}"
                                },
                                contentType = { _, _ -> "actor-series" }
                            ) { index, credit ->
                                CreditTile(
                                    modifier = Modifier.animateItem(),
                                    credit = credit,
                                    isWatched = creditWatched(credit, "series"),
                                    isPartiallyWatched =
                                        creditPartiallyWatched(credit, "series"),
                                    onClick = {
                                        openCredit(
                                            credit,
                                            BrowseReturnTarget(
                                                screenKey = screenKey,
                                                itemId = viewModel.creditItemId(credit),
                                                position = index,
                                                railKey = ACTOR_TV_RAIL
                                            )
                                        )
                                    },
                                    onLongClick = {
                                        menu = credit to BrowseReturnTarget(
                                            screenKey = screenKey,
                                            itemId = viewModel.creditItemId(credit),
                                            position = index,
                                            railKey = ACTOR_TV_RAIL
                                        )
                                    }
                                )
                            }
                        }

                        if (crewCredits.isNotEmpty()) {
                            item(key = "crewheader", span = { GridItemSpan(maxLineSpan) }) {
                                SectionLabel("DIRECTING & WRITING")
                            }
                            itemsIndexed(
                                items = crewCredits,
                                // The role (job) half of the key, so a title the
                                // person both acted in and wrote/directed is not
                                // a duplicate key against a cast row.
                                key = { _, credit -> "crew:${credit.id}:${credit.job.orEmpty()}" },
                                contentType = { _, _ -> "actor-crew" }
                            ) { index, credit ->
                                // The crew rail mixes films and series, so the
                                // badge type comes off the credit itself.
                                val creditType =
                                    if (credit.mediaType == "tv") "series" else "movie"
                                CreditTile(
                                    modifier = Modifier.animateItem(),
                                    credit = credit,
                                    isWatched = creditWatched(credit, creditType),
                                    isPartiallyWatched =
                                        creditPartiallyWatched(credit, creditType),
                                    onClick = {
                                        openCredit(
                                            credit,
                                            BrowseReturnTarget(
                                                screenKey = screenKey,
                                                itemId = viewModel.creditItemId(credit),
                                                position = index,
                                                railKey = ACTOR_CREW_RAIL
                                            )
                                        )
                                    },
                                    onLongClick = {
                                        menu = credit to BrowseReturnTarget(
                                            screenKey = screenKey,
                                            itemId = viewModel.creditItemId(credit),
                                            position = index,
                                            railKey = ACTOR_CREW_RAIL
                                        )
                                    }
                                )
                            }
                        }

                        if (movies.isEmpty() && tvShows.isEmpty() && crewCredits.isEmpty()) {
                            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                MobileStatus("No credits to show for this person.")
                            }
                        }
                    }

                    // No person and no error: the load answered nothing (a
                    // blank API key, or a person TMDB returned null for). The
                    // header above drew a name-less stub; this names the state
                    // instead of leaving the grid empty.
                    else -> item(
                        key = "no-person",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        MobileStatus(
                            message = "Couldn't load this person.",
                            actionLabel = "TRY AGAIN",
                            onAction = { viewModel.load(actorId) }
                        )
                    }
                }
            }

            menu?.let { (credit, target) ->
                val normalizedType = when (credit.mediaType?.lowercase()) {
                    "tv", "series" -> "series"
                    else -> "movie"
                }
                val watched = creditWatched(credit, normalizedType)
                PosterContextMenu(
                    title = credit.title ?: credit.name ?: "",
                    subtitle = (credit.releaseDate ?: credit.firstAirDate)
                        ?.take(4)
                        ?.let { year ->
                            listOfNotNull(
                                year,
                                credit.character?.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                        },
                    libraryTarget = LibraryAddTarget(
                        mediaType = normalizedType,
                        imdbId = resolvedCreditIds[
                            viewModel.creditLookupKey(credit.id, normalizedType)
                        ],
                        tmdbId = credit.id,
                        title = credit.title ?: credit.name ?: "Untitled",
                        year = (credit.releaseDate ?: credit.firstAirDate)
                            ?.take(4)?.toIntOrNull(),
                        posterUrl = credit.posterPath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.POSTER_BASE + it }
                    ),
                    hideTarget = hideTarget(
                        credit.title ?: credit.name ?: "",
                        credit.mediaType ?: "movie",
                        credit.posterPath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                        listOf(
                            credit.id.toString(),
                            resolvedCreditIds[
                                viewModel.creditLookupKey(credit.id, normalizedType)
                            ]
                        ),
                        year = (credit.releaseDate ?: credit.firstAirDate)
                            ?.take(4)?.toIntOrNull()
                    ),
                    actions = listOf(
                        PosterContextAction(
                            label = "Go to Details",
                            description = "Open this title's detail page"
                        ) {
                            menu = null
                            // The menu opens the same Detail page a card press
                            // does, so it raises the same return record.
                            BrowseReturnFocus.record(target)
                            viewModel.resolveAndNavigate(
                                credit.id,
                                credit.mediaType ?: normalizedType,
                                onNavigateDetail
                            )
                        },
                        PosterContextAction(
                            label = watchedMenuLabel(
                                isWatched = watched,
                                mediaType = credit.mediaType ?: normalizedType
                            ),
                            description = watchedMenuDescription(
                                isWatched = watched,
                                mediaType = credit.mediaType ?: normalizedType
                            )
                        ) {
                            menu = null
                            if (watched) {
                                viewModel.markUnwatched(
                                    credit.id,
                                    credit.mediaType ?: normalizedType
                                )
                            } else {
                                viewModel.markAsWatched(
                                    credit.id,
                                    credit.mediaType ?: normalizedType
                                )
                            }
                        }
                    ),
                    onDismiss = { menu = null }
                )
            }
        }
    }
}

/** The actor header: still, portrait (or initials), name, facts, known-for. */
@Composable
private fun ActorHeader(
    name: String,
    meta: String,
    knownFor: String?,
    portraitUrl: String?,
    backdropUrl: String?
) {
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(KBShapeCard)
                .background(KBSurface)
        ) {
            if (!backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = knownFor ?: name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                KBVoid.copy(alpha = 0.92f),
                                KBVoid.copy(alpha = 0.55f)
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Portrait(portraitUrl = portraitUrl, name = name)
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = name,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = KBAccent,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                knownFor?.let { title ->
                    Text(
                        text = "Known for $title",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/** The 2:3 portrait plate, or the person's initials when TMDB has no image. */
@Composable
private fun Portrait(portraitUrl: String?, name: String) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(144.dp)
            .clip(KBShapeCard)
            .background(KBSurfaceRaised),
        contentAlignment = Alignment.Center
    ) {
        if (portraitUrl != null) {
            AsyncImage(
                model = portraitUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = remember(name) {
                    name.trim().split(" ")
                        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                        .take(2)
                        .joinToString("")
                },
                color = KBTextLo,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

/** The biography, four lines until expanded - the TV page's toggle, as a tap. */
@Composable
private fun BiographyBlock(biography: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Text(
            text = biography,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded && result.hasVisualOverflow) overflows = true
            }
        )
        if (overflows) {
            KBButton(
                label = if (expanded) "VIEW LESS" else "VIEW MORE",
                modifier = Modifier.padding(top = 8.dp)
            ) {
                expanded = !expanded
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** One filmography card, with the shared badges and caption block. */
@Composable
private fun CreditTile(
    credit: TmdbPersonCredit,
    isWatched: Boolean,
    isPartiallyWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Shape follows Settings' global landscape switch ("Landscape Posters
        // Everywhere"), like every other catalog surface on the phone.
        TouchGlobalPosterTile(
            posterUrl = credit.posterPath?.let { TmdbRepository.POSTER_BASE + it },
            contentDescription = credit.title ?: credit.name ?: "",
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            artId = credit.id.toString(),
            artType = credit.mediaType
                ?: if (credit.firstAirDate != null) "tv" else "movie",
            modifier = Modifier.fillMaxWidth()
        )
        // Shared caption block, so the poster-caption toggles reach a
        // filmography like they reach every other rail.
        TouchPosterCaptions(
            title = credit.title ?: credit.name ?: "Untitled",
            year = (credit.releaseDate ?: credit.firstAirDate)?.take(4),
            rating = credit.voteAverage
        )
    }
}

