package com.kennyb1201.kbstream.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.library.LibraryHomeRailItems
import com.kennyb1201.kbstream.data.library.LibraryHomeRailPin
import com.kennyb1201.kbstream.data.library.LibraryHomeRails
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Library list the viewer pinned to Home, with its rows already fetched.
 *
 * The pin is kept beside the items because the title has to be drawable before
 * the fetch resolves (and after a fetch that failed): the rail's name is the
 * list's, not something the service tells us per item.
 *
 * Shared rather than the television's own, because the phone draws the same
 * rails: the pins are one prefs row on the account, so the two Home screens
 * must not disagree about which lists are on Home. Only the drawing differs
 * (the TV's focus-aware rail in HomeScreen, the phone's touch rail in
 * HomeScreenMobile) - what a rail IS, and which lists make one, is decided
 * here once.
 */
internal data class LibraryHomeRailUi(
    val pin: LibraryHomeRailPin,
    val items: List<MetaPreview>
)

/**
 * What the pinned-rail loader produced: the rails Home draws, and the pins whose
 * list could not be READ (spec audit 2026-11-18, Library L1).
 *
 * The two are separate because they are different messages: a rail with rows is
 * a rail, and a list that did not answer is a line naming it. A genuinely empty
 * list is in neither - it stays silent, as it always did.
 */
internal data class LibraryHomeRailsState(
    val rails: List<LibraryHomeRailUi> = emptyList(),
    val unreadable: List<String> = emptyList()
)

/**
 * The pinned Library lists, loaded off the main thread.
 *
 * Re-runs when the pinned set changes: [LibraryHomeRails.pins] is read on each
 * composition (a small prefs row, the same read the rail below does for other
 * arrangement data), so pinning a list in Library and backing out to Home shows
 * the rail without a restart. An empty list is the ordinary state - most
 * profiles never pin one - so nothing draws and nothing is fetched.
 *
 * This is deliberately the WHOLE loader for both Home screens, not a
 * TV-private copy with a phone twin: the empty-rail rule, the kids ceiling and
 * the id rule below are the reasons a list that cannot answer simply does not
 * appear, and a second copy is how one of them would drift.
 */
@Composable
internal fun rememberLibraryHomeRails(): State<List<LibraryHomeRailUi>> {
    // `derivedStateOf`, not a hand-rolled State: a custom State object would
    // read through without Compose tracking it, so Home would never recompose
    // when the pins resolve.
    val state = rememberLibraryHomeRailsState()
    return remember(state) { derivedStateOf { state.value.rails } }
}

/**
 * The pinned rails AND the pins that could not be read.
 *
 * One load, two answers: a second composable reading the pins separately would
 * fetch every pinned list twice.
 */
@Composable
internal fun rememberLibraryHomeRailsState(): State<LibraryHomeRailsState> {
    val context = LocalContext.current
    val pins = LibraryHomeRails.pins(context)
    return produceState(initialValue = LibraryHomeRailsState(), pins) {
        value = withContext(Dispatchers.IO) {
            val tmdb = TmdbRepository.getInstance(context)
            val rails = mutableListOf<LibraryHomeRailUi>()
            val unreadable = mutableListOf<String>()
            pins.forEach { pin ->
                // The Kids Mode ceiling applies here for the same reason it
                // applies to the Library's own remote rows: the list belongs to
                // the ACCOUNT, so a kids profile on that account must not see a
                // pinned list's adult titles on Home.
                val rows = LibraryHomeRailItems.loadOrNull(context, pin)
                if (rows == null) {
                    // Could not read: name it rather than returning a Home with
                    // one fewer row and no explanation (Library L1).
                    unreadable += pin.name
                    return@forEach
                }
                val items = tmdb.kidsFilterMetas(rows.mapNotNull { it.homeRailMeta() })
                // A list that answered with nothing is not a rail: an empty
                // heading is a promise Home cannot keep.
                if (items.isNotEmpty()) rails += LibraryHomeRailUi(pin, items)
            }
            LibraryHomeRailsState(rails = rails, unreadable = unreadable)
        }
    }
}

/**
 * The line Home draws for pinned lists that could not be read (Library L1).
 *
 * One function for both surfaces, for the reason [libraryWatchlistEmptyText]
 * exists: the TV's status card and the phone's line must not drift apart about
 * the same failure. The pin is never dropped, so this is a "not now", not an
 * "unpinned".
 */
internal fun libraryRailUnreadableLine(names: List<String>): String {
    if (names.isEmpty()) return ""
    val list = names.joinToString(", ")
    return if (names.size == 1) {
        "\"$list\" couldn't be loaded: the list did not answer, or it is too " +
            "large to read in one pass. It is still pinned and returns on its own."
    } else {
        "$list couldn't be loaded: those lists did not answer, or are too large " +
            "to read in one pass. They are still pinned and return on their own."
    }
}

/**
 * True when a pinned BingeCat list cannot load because BingeCat needs a
 * reconnect (spec F2).
 *
 * A pinned rail whose list cannot be read is dropped rather than drawn empty
 * (see [rememberLibraryHomeRails]), so without this line Home simply came up a
 * row short and only the Library said why. Read where the pins are read, and
 * re-read whenever the pinned set changes - which is also when the rail
 * disappears or comes back.
 */
@Composable
internal fun rememberBingeCatReconnectNotice(): Boolean {
    val context = LocalContext.current
    val pins = LibraryHomeRails.pins(context)
    return remember(pins) {
        pins.any { it.source == LibrarySource.BINGECAT_LIST.name } &&
            BingeCatRepository.getInstance(context).needsReconnect()
    }
}

/**
 * One Library row as a rail card.
 *
 * The id is what the detail screen is opened by, so it is the SAME id shape the
 * catalog rails carry: an IMDb id when the row has one (add-ons and every
 * tracker prefer it), otherwise `tmdb:<id>`. A row with neither cannot be
 * opened, so it is dropped rather than drawn as a dead card.
 */
private fun LibraryItem.homeRailMeta(): MetaPreview? {
    val imdb = imdbId?.takeIf { it.isNotBlank() }
    val tmdb = tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }
    val id = imdb ?: tmdb ?: return null
    return MetaPreview(
        id = id,
        type = mediaType,
        name = title,
        poster = posterUrl,
        releaseInfo = year?.toString()
    )
}
