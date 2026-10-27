package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Detail deep dive's findings, pinned where a unit test can reach them.
 *
 * The crash, the unaired marks and the leaked tab are all Compose behaviour -
 * the pure rules live in [DetailSeasonEpisodesTest] and [EpisodeRowKeyTest],
 * and this file pins the WIRING: the screens and the ViewModel have to call
 * those rules, or every rule test stays green while the bug is still on screen.
 */
class DetailAuditContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** Whitespace-insensitive view, so an assertion survives re-indentation. */
    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ")

    private val tv by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt") }
    private val mobile by lazy { source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt") }
    private val touch by lazy { source("com/kennyb1201/kbstream/ui/components/touch/KBEpisodeTouch.kt") }
    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt") }
    private val libraryAdds by lazy {
        source("com/kennyb1201/kbstream/ui/components/LibraryAdds.kt")
    }

    // ── C1: mobile episode rows are keyed by the episode ────────────────────

    @Test
    fun `the phone keys BOTH episode presentations by the episode, not the file`() {
        val src = normalized(mobile)
        assertEquals(
            "the vertical list and the sideways rail both render the same viewModel.episodes " +
                "list, so both need the file-id-proof key",
            2,
            count(src, "episodeRowKey(id, currentSeason, episode.episodeNumber)")
        )
        assertFalse(
            "a key of the stream id is FILE numbering and repeats on segmented shows: $src",
            src.contains("key = { \"ep:\" + it.streamId }")
        )
    }

    // ── C2: the TV episode menu uses the filtered number list ───────────────

    @Test
    fun `the TV episode menu marks the same aired-only numbers the chip menu does`() {
        val src = normalized(tv)
        assertTrue(
            "the episode-card menu must not pass the raw listing: $src",
            src.contains(
                "seasonEpisodeNumbers = seasonEpisodeNumbersFor( seasonForMenu ) .orEmpty()"
            )
        )
    }

    @Test
    fun `the ViewModel gates every mark on the aired rule`() {
        val src = normalized(viewModel)
        assertTrue(
            "the per-season write filters its numbers through the shared rule",
            src.contains("airedEpisodeNumbers(season, episodeNumbers)")
        )
        assertTrue(
            "and the whole-series map does too, season by season",
            src.contains("season to airedEpisodeNumbers(season, episodes)")
        )
    }

    // ── C3: unaired cards are not press targets on the phone ────────────────

    @Test
    fun `both phone episode layouts gate the tap and the hold on availability`() {
        listOf("mobile list row" to mobile, "touch rail card" to touch).forEach { (name, file) ->
            val src = normalized(file)
            assertTrue(
                "$name must not open an unaired episode: $src",
                src.contains("onClick = { if (!unavailable) onClick() }")
            )
            assertTrue(
                "$name must not raise the menu for one either: $src",
                src.contains("onLongClick = if (unavailable) null else onLongClick")
            )
        }
        assertTrue(
            "and the menu's own Play rows go through the same guard",
            normalized(mobile).contains(
                "episodeSeason !in staleSeasons && isEpisodeUnavailable(episode.airDate, today)"
            )
        )
    }

    // ── C4: the Media tab belongs to a title ────────────────────────────────

    @Test
    fun `the TV media tab is keyed by the title, like the phone's`() {
        val src = normalized(tv)
        assertTrue(
            "a drill-down reuses this composition: $src",
            src.contains("var mediaTabIndex by remember(type, id)")
        )
        assertFalse(
            "an unkeyed remember carried title A's tab onto title B",
            src.contains("var mediaTabIndex by remember {")
        )
    }

    // ── C5 / S1: dual roles survive, exact duplicates do not crash ──────────

    @Test
    fun `the TV cast rail dedupes by credit, so a second role is not dropped`() {
        val src = normalized(tv)
        assertTrue(src.contains("distinctBy { it.id to it.character.orEmpty() }"))
        assertFalse(
            "the bare id dropped a voice actor's second character",
            src.contains("distinctBy { it.id }.take(25)")
        )
    }

    @Test
    fun `the phone cast rail dedupes by the same composite its key uses`() {
        val src = normalized(mobile)
        assertTrue(src.contains("cast.distinctBy { it.id to it.character.orEmpty() }"))
    }

    // ── C6: a dead press says so ────────────────────────────────────────────

    @Test
    fun `an unresolvable poster press reports itself instead of doing nothing`() {
        assertEquals(
            "both rails (collection and More Like This)",
            2,
            count(normalized(tv), "Couldn't open this title")
        )
        assertEquals(
            "and both phone rails",
            2,
            count(normalized(mobile), "Couldn't open this title")
        )
    }

    // ── S2: sibling-profile ownership reaches Detail's badges ───────────────

    @Test
    fun `Detail applies the Home ownership rule to its badge sets`() {
        val src = normalized(viewModel)
        assertTrue(src.contains("private fun detailOwnedByAnotherProfile()"))
        assertTrue(
            "the title's episode ticks are withheld from a profile that does not own it",
            src.contains("if (detailOwnedByAnotherProfile()) {") &&
                src.contains("_watchedEpisodeKeys.value = emptySet()")
        )
        assertTrue(
            "and the poster rows filter per poster title",
            src.contains("filterNot { item -> item.tmdbId in ownedElsewhere }")
        )
    }

    // ── S3 / I2: rail requesters are disposed with their cards ──────────────

    @Test
    fun `rail requesters are registered by DisposableEffect and removed on dispose`() {
        val src = normalized(tv)
        assertTrue(src.contains("private fun RegisterRailRequester("))
        assertTrue(src.contains("if (requesters[key] === requester) requesters.remove(key)"))
        assertEquals(
            "every direct write site now goes through the helper",
            0,
            count(src, "railFocusRequesters[chipKey] =")
        )
        assertFalse(
            "the old direct-assignment registration",
            src.contains("railFocusRequesters[cardKey] = cardFocusRequester")
        )
    }

    // ── I1: the title's own page can save it ────────────────────────────────

    @Test
    fun `the title's own page offers the Library rows`() {
        assertTrue(
            "the Play long-press menu adds them through the shared menu",
            count(normalized(tv), "libraryTarget = LibraryAddTarget(") >= 2
        )
        assertFalse(
            "the phone's add-only row is gone: the action row's toggle saves AND " +
                "unsaves, so a full-width ADD TO LIBRARY next to it would be a " +
                "second, weaker control for the same thing",
            normalized(mobile).contains("ADD TO LIBRARY")
        )
        assertEquals(
            "the phone's action row carries the toggle, one mark per state",
            1,
            count(normalized(mobile), "R.drawable.ic_detail_bookmark_outline")
        )
        assertEquals(
            "and the saved state's mark",
            1,
            count(normalized(mobile), "R.drawable.ic_detail_bookmark_filled")
        )
    }

    // ── The action row's Library toggle ─────────────────────────────────────

    @Test
    fun `both screens save and unsave the title from the one toggle`() {
        val tvSrc = normalized(tv)
        val phoneSrc = normalized(mobile)

        assertTrue(
            "the TV button toggles instead of only adding",
            tvSrc.contains("LibraryAdds.toggle(context, libraryButtonTarget)")
        )
        assertTrue(
            "and the phone's is the same action on the same target shape",
            phoneSrc.contains("LibraryAdds.toggle(context, libraryTarget)")
        )

        listOf("the TV button" to tvSrc, "the phone's" to phoneSrc).forEach { (who, src) ->
            assertTrue(
                "$who paints an outline bookmark when the title is not saved",
                src.contains("R.drawable.ic_detail_bookmark_outline")
            )
            assertTrue(
                "$who paints a filled bookmark once it is",
                src.contains("R.drawable.ic_detail_bookmark_filled")
            )
            assertTrue(
                "$who repaints from the store, so an add made in the long-press menu " +
                    "- which lands after that menu is gone - still flips it",
                src.contains("LibraryAdds.revision")
            )
        }

        assertTrue(
            "the removal half is the Library tab's own remove, so the trackers lose " +
                "their watchlist copy instead of re-adding the title on the next merge",
            normalized(libraryAdds).contains("LibraryMirror.removeFromLibrary(")
        )
    }

    @Test
    fun `a hold on the toggle raises the app's shared title menu`() {
        assertEquals(
            "the TV's hold passes this page's target to the shared menu exactly once",
            1,
            count(normalized(tv), "libraryTarget = libraryButtonTarget")
        )
        assertTrue(
            "and it is the menu, not a second hand-built row list",
            normalized(tv).contains("if (libraryButtonMenu) { PosterContextMenu(")
        )
        assertEquals(
            "the phone's hold does the same through the touch sheet",
            1,
            count(normalized(mobile), "libraryTarget = libraryTarget")
        )
        assertTrue(
            "with the same target the page's other menus use",
            normalized(mobile).contains("if (libraryMenu) { PosterContextMenu(")
        )
    }

    // ── I3: an unknown season size is not guessed at ────────────────────────

    @Test
    fun `a season no source can size does not offer a truncated mark`() {
        val src = normalized(tv)
        assertFalse(
            "the old fallback marked 1..highest already watched",
            src.contains("maxOrNull() ?: 0")
        )
        assertTrue(
            "the gesture is withheld when there is no episode set",
            src.contains("episodeNumbers.isNullOrEmpty()")
        )
    }

    // ── The toggle's mark is a bookmark pair, not plus/minus ────────────────

    /**
     * The saved-state mark used to be a bare "−", which reads at TV distance as
     * a stray dash: nothing in it says "library". The pair is one bookmark that
     * fills, and because it is the whole state signal, the OLD drawables must be
     * gone - a leftover call site would leave one surface still showing the dash.
     */
    @Test
    fun `the plus and minus drawables are gone with every reference to them`() {
        listOf("ic_detail_add.xml", "ic_detail_remove.xml").forEach { name ->
            assertFalse(
                "$name is superseded by the bookmark pair and must be deleted",
                drawable(name).isFile
            )
        }
        productionSources().forEach { (path, text) ->
            listOf("ic_detail_add", "ic_detail_remove").forEach { dead ->
                assertFalse(
                    "$path still references $dead",
                    text.contains(dead)
                )
            }
        }
    }

    /**
     * The pair is checked as files, since no unit test renders Compose: the two
     * marks have to share a viewport, a colour and the bookmark body, and differ
     * in fill - the outline carrying the interior cut the filled mark lacks.
     */
    @Test
    fun `the bookmark pair shares its box and colour and differs only in fill`() {
        val outline = drawable("ic_detail_bookmark_outline.xml").readText()
        val filled = drawable("ic_detail_bookmark_filled.xml").readText()

        listOf("the outline" to outline, "the filled" to filled).forEach { (who, xml) ->
            listOf(
                "android:width=\"24dp\"",
                "android:height=\"24dp\"",
                "android:viewportWidth=\"24\"",
                "android:viewportHeight=\"24\"",
                "#FFF3EFE4",
                "v16l7"
            ).forEach { part ->
                assertTrue("$who bookmark is missing $part", xml.contains(part))
            }
            assertTrue(
                "$who bookmark must have a drawn path",
                xml.contains("android:pathData=")
            )
        }

        assertFalse(
            "the filled mark is solid: it has no interior cut",
            filled.contains("M17,18")
        )
        assertTrue(
            "the outline mark is the same silhouette with the interior cut",
            outline.contains("M17,18")
        )
        assertTrue(
            "the outline is drawn path-level distinct from the filled mark, " +
                "not a copy of it",
            outline != filled
        )
    }

    /** The watched badge owns the checkmark; the Library toggle must not borrow it. */
    @Test
    fun `neither toggle paints a checkmark`() {
        listOf("the TV button" to tv, "the phone's" to mobile).forEach { (who, src) ->
            listOf("ic_check", "Icons.Filled.Check", "\u2713").forEach { check ->
                assertFalse(
                    "$who must not use $check - a tick here would read as watched, " +
                        "not saved",
                    src.contains(check)
                )
            }
        }
    }

    // ── Taste: the thumbs the action row gained ─────────────────────────────

    /**
     * The two signals sit on the title's own page, after the Library toggle, and
     * in that order: like first, dislike beside it. The ORDER is what is pinned
     * here - the row is the page's primary control strip, and a re-ordered row is
     * how a viewer presses the wrong thumb.
     */
    @Test
    fun `the TV action row runs play, random, trailer, bookmark, like, dislike`() {
        val src = normalized(tv)
        val rowStart = src.indexOf("KBCard( onClick = openStreams,")
        val rowEnd = src.indexOf(
            "Column( modifier = Modifier .padding( start = 24.dp,",
            rowStart.coerceAtLeast(0)
        )
        assertTrue("the action row could not be located", rowStart >= 0 && rowEnd > rowStart)
        val row = src.substring(rowStart, rowEnd)

        val order = listOf(
            "R.drawable.ic_brand_play",
            "R.drawable.ic_detail_shuffle",
            "R.drawable.ic_detail_trailer",
            "R.drawable.ic_detail_bookmark_outline",
            "pressTaste(TasteSignals.LIKE)",
            "pressTaste(TasteSignals.DISLIKE)"
        ).map { needle ->
            row.indexOf(needle).also { at ->
                assertTrue("the row no longer carries $needle", at >= 0)
            }
        }
        assertEquals("the controls have to stay in reading order", order.sorted(), order)
    }

    /**
     * Both rows paint four marks, and paint them from the STORE: a thumb set from
     * a poster menu (or the other screen) has to flip this one without a revisit,
     * and a press may never claim a signal that did not persist.
     */
    @Test
    fun `both Detail rows carry all four thumb marks, painted from the store`() {
        listOf("the TV row" to normalized(tv), "the phone's" to normalized(mobile))
            .forEach { (who, src) ->
                listOf(
                    "R.drawable.ic_detail_thumb_up_outline",
                    "R.drawable.ic_detail_thumb_up_filled",
                    "R.drawable.ic_detail_thumb_down_outline",
                    "R.drawable.ic_detail_thumb_down_filled"
                ).forEach { mark ->
                    assertTrue("$who no longer paints $mark", src.contains(mark))
                }
                assertTrue(
                    "$who has to read the signal from the store",
                    src.contains("rememberTasteSignal(")
                )
                assertTrue(
                    "$who has to turn the press into the next signal through the " +
                        "shared rule (the active thumb clears, the other flips)",
                    src.contains("TasteSignals.nextSignal(")
                )
                assertTrue(
                    "and report the outcome",
                    src.contains("Couldn't save your rating")
                )
            }
    }

    /**
     * The phone's added row is its own: PLAY and TRAILER already take the full
     * width of the row above, so the thumbs sit in a second row between it and
     * the START OVER button rather than being squeezed in beside them.
     */
    @Test
    fun `the phone gives the thumbs their own row under the action row`() {
        val src = normalized(mobile)
        val bookmark = src.indexOf("R.drawable.ic_detail_bookmark_outline")
        val like = src.indexOf("pressTaste(TasteSignals.LIKE)")
        val dislike = src.indexOf("pressTaste(TasteSignals.DISLIKE)")
        val startOver = src.indexOf("label = \"START OVER\"")

        assertTrue("the thumbs follow the Library toggle", bookmark >= 0 && like > bookmark)
        assertTrue("like and dislike stay in that order", dislike > like)
        assertTrue("and the pair stays above the row's later controls", startOver > dislike)
    }

    /**
     * Nothing else grows a thumb. Every rail reaches the signal through the
     * poster menu's rows, not through a glyph on the card - the watched
     * checkmark/eye badge family owns card corners - so the only sources that
     * may name these drawables are the two Detail screens.
     */
    @Test
    fun `the thumbs exist only in the two Detail action rows`() {
        assertEquals(
            "a thumb anywhere else is a surface the spec did not put one on",
            listOf(
                "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt",
                "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
            ),
            productionSources()
                .filter { (_, text) -> text.contains("ic_detail_thumb") }
                .map { (path, _) -> path }
                .sorted()
        )
    }

    /**
     * v1 is local, and that is a property of the code, not a promise: neither
     * tracker API this app talks to has a rating write, the sync payload has no
     * carrier for the signals, and there is no preference gating them. Any of the
     * three appearing means the feature grew an endpoint, a carrier or a switch
     * nobody asked for.
     */
    @Test
    fun `no tracker rating write, no sync key and no preference for taste`() {
        listOf(SIMKL_API, TRAKT_API).forEach { path ->
            assertFalse(
                "$path gained a rating call - a thumb press must not claim a " +
                    "tracker action it did not take",
                normalized(source(path)).contains("rating")
            )
        }
        val mdbList = normalized(source(MDBLIST))
        listOf("sync/ratings", "@POST(", "/rate").forEach { write ->
            assertFalse("MDBList is read-only here; it gained $write", mdbList.contains(write))
        }
        assertFalse(
            "the signals are device-local in v1: a sync key means a carrier was added",
            normalized(source(SYNC_PAYLOAD)).contains("taste")
        )
        assertFalse(
            "and there is no taste preference to switch them off with",
            normalized(source(APP_PREFS)).lowercase().contains("taste")
        )
    }

    /**
     * The store is one more profile-scoped blob, not a new kind of store: its
     * file has to come from `ProfileStorage`, which is what keeps a kids or guest
     * profile's signals to itself, and its state has to re-read on a switch.
     */
    @Test
    fun `the taste store is scoped like every other profile store`() {
        val store = normalized(source(TASTE_STORE))
        assertTrue(
            "the store must resolve its file through the profile namespacing",
            store.contains("ProfileStorage.prefsName(")
        )
        assertTrue(
            "and a profile switch has to be noticed",
            store.contains("private var loadedFor")
        )
    }

    /**
     * The four marks are one glyph in two states each: same box, same baked
     * colour, and an outline that is its filled mark's own open variant rather
     * than a copy of it. Copied from Google's published Material paths (the
     * bookmark pair's rule), so the anchors below are segments those paths carry.
     */
    @Test
    fun `the thumb pairs share their box and colour and differ by state`() {
        val upOutline = drawable("ic_detail_thumb_up_outline.xml").readText()
        val upFilled = drawable("ic_detail_thumb_up_filled.xml").readText()
        val downOutline = drawable("ic_detail_thumb_down_outline.xml").readText()
        val downFilled = drawable("ic_detail_thumb_down_filled.xml").readText()

        listOf(
            "the up outline" to upOutline,
            "the up filled" to upFilled,
            "the down outline" to downOutline,
            "the down filled" to downFilled
        ).forEach { (who, xml) ->
            listOf(
                "android:width=\"24dp\"",
                "android:height=\"24dp\"",
                "android:viewportWidth=\"24\"",
                "android:viewportHeight=\"24\"",
                "#FFF3EFE4",
                "android:pathData=\"M"
            ).forEach { part ->
                assertTrue("$who thumb is missing $part", xml.contains(part))
            }
        }

        assertTrue(
            "the filled up mark keeps Material's solid body",
            upFilled.contains("M1 21h4V9H1v12z")
        )
        assertTrue(
            "the outline up mark is the published open variant, not the filled path",
            upOutline.contains("M14 2L7.59 8.41") &&
                !upOutline.contains("M1 21h4V9H1v12z")
        )
        assertTrue(
            "the filled down mark is the published mirror of it",
            downFilled.contains("M15 3H6c-.83 0-1.54.5-1.84 1.22")
        )
        assertTrue(
            "and the outline down mark is its open variant",
            downOutline.contains("M10 22l6.41-6.41") &&
                !downOutline.contains("M15 3H6c-.83")
        )
        assertNotEquals("a state pair cannot be one file", upOutline, upFilled)
        assertNotEquals("a state pair cannot be one file", downOutline, downFilled)
    }

    /** `src/main` for this module, the root the drawable directory hangs off. */
    private val mainRoot: File get() = sourceRoot.parentFile

    private fun drawable(name: String): File = File(mainRoot, "res/drawable/$name")

    /** Every production source file, so a deleted drawable's references can't hide. */
    private fun productionSources(): List<Pair<String, String>> {
        val root = File(mainRoot, "java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it.readText() }
            .toList()
    }

    private fun count(text: String, needle: String): Int {
        var from = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    private companion object {
        const val SIMKL_API = "com/kennyb1201/kbstream/data/simkl/SimklApiService.kt"
        const val TRAKT_API = "com/kennyb1201/kbstream/data/trakt/TraktApiService.kt"
        const val MDBLIST = "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
        const val SYNC_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val APP_PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val TASTE_STORE = "com/kennyb1201/kbstream/data/library/TasteSignals.kt"
    }
}
