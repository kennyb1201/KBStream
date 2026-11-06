package com.kennyb1201.kbstream.ui.addons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned Library rails in the Home manager, and the art their cards draw.
 *
 * Three field gaps, one set of pins:
 *
 *  1. A pinned BingeCat list showed on Home but was not in the catalog manager,
 *     so it could not be moved, renamed or removed from the surface a viewer
 *     rearranges Home on. Its row is the PIN (`list:<source>|<id>`), its rename
 *     writes the pin's stored name and its remove unpins. Its arrows write the
 *     HOME ORDER, like every other row's: a pinned list is a rail of the one
 *     column since the pinned lists stopped being a block of their own at the
 *     foot of Home.
 *  2. BingeCat's wire media carries no art, so the rail's cards drew their
 *     titles while Trakt rails (whose rows ship images) looked right. A row with
 *     no art is resolved by its id; a row WITH art resolves nothing.
 *  3. The stale pin a rename left behind (the ghost rail) had no door: no
 *     Library row offers Remove for it and it was not in the manager. The
 *     manager's row removes it by its stored key, whether or not the list loads.
 *
 * Source text is what a JVM test can see here; the layout is exercised on a TV.
 */
class HomeRailManagerLibraryRowsContractTest {

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
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private val rows =
        source("com/kennyb1201/kbstream/ui/addons/HomeRailManagerRows.kt")
    private val dialog =
        source("com/kennyb1201/kbstream/ui/addons/AddonsHomeManagerDialog.kt")
    private val screen =
        source("com/kennyb1201/kbstream/ui/addons/AddonsScreen.kt")
    private val store =
        source("com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
    private val viewModel =
        source("com/kennyb1201/kbstream/ui/addons/AddonsViewModel.kt")
    private val home =
        source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val mobileHome =
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    private val posterArt =
        source("com/kennyb1201/kbstream/ui/components/TitlePosterArt.kt")
    private val landscapeArt =
        source("com/kennyb1201/kbstream/ui/components/GlobalLandscapeArt.kt")

    @Test
    fun `the pinned rails are listed in the one column, as the pins stand`() {
        assertTrue(
            "the manager must be told the pins (a surface that does not manage " +
                "them passes none)",
            rows.contains("libraryPins: List<LibraryHomeRailPin> = emptyList()")
        )
        assertTrue(
            "one row per pin, in the columns's order - the pins are what the " +
                "column appends when the Home order has not placed one yet",
            rows.contains("val libraryRows = column.mapNotNull { key ->") &&
                rows.contains("val pin = pinByKey[key] ?: return@mapNotNull null")
        )
        assertTrue(
            "interleaved with them, in the ONE column Home draws - the manager must " +
                "list the order the arrows act on, not the arrangement plus a tail",
            rows.contains("val column = homeColumnRailKeys(") &&
                rows.contains("val rowByKey = (arrangementRows + libraryRows).associateBy") &&
                rows.contains("return column.mapNotNull { key -> rowByKey[key] }") &&
                !rows.contains("return arrangementRows + libraryRows")
        )
        assertTrue(
            "the row's key is the pin's stable key, the one the Home rail is " +
                "keyed by",
            rows.contains("key = pin.key")
        )
        assertTrue("its title is the pin's stored name", rows.contains("title = pin.name"))
        assertTrue(
            "and its subtitle names the service",
            rows.contains("subtitle = \"Library · \" + libraryRailSourceLabel(pin.source)")
        )
        assertTrue(
            "a Library row is not an arrangement pin: the manager's pin flag " +
                "means the OTHER store",
            rows.contains("isPinned = false") && rows.contains("isLibraryRail = true")
        )
        assertTrue(
            "its arrows are exact against the COLUMN - the same ordering every " +
                "other row's are decided against, so an up that crosses a catalog is " +
                "offered and an up at the top of Home is not",
            rows.contains("canMoveUp = railMoveChangesOrder(prefs, column, pin.key, -1)") &&
                rows.contains(
                    "canMoveTop = railMoveChangesOrder(prefs, column, pin.key, Int.MIN_VALUE)"
                ) &&
                !rows.contains("pinsMoveChangesOrder(")
        )
    }

    @Test
    fun `the manager's row for a Library rail moves, renames and removes the pin`() {
        assertTrue(
            "the dialog takes the pins and hands them to the row builder",
            dialog.contains("libraryPins: List<com.kennyb1201.kbstream.data.library.LibraryHomeRailPin>") &&
                dialog.contains("libraryPins = libraryPins")
        )
        // A pin is its own blob and nothing in the arrangement state bumps when
        // one is added, moved, renamed or removed, so the pins have to be one of
        // the memo keys here as well (the phone dialog had the same gap).
        assertTrue(
            "a newly pinned list appears without reopening the dialog, on the TV too",
            dialog.contains(
                "remember(configurations, collectionsState, homeOrderVersion, libraryPins)"
            )
        )
        assertTrue(
            "a move goes to the pin, not to an arrangement slot",
            dialog.contains(
                "row.isLibraryRail -> row.libraryPin?.let { pin -> onLibraryMove(pin, delta) }"
            )
        )
        assertTrue(
            "a rename edits the pin's stored name",
            dialog.contains("row.isLibraryRail -> row.libraryPin?.let(onLibraryRename)")
        )
        assertTrue(
            "a remove unpins the stored key",
            dialog.contains("onRemove = { removeLibraryRow(row) }") &&
                dialog.contains("onLibraryRemove(pin)")
        )
        assertTrue(
            "the row carries a REMOVE button, tinted as the destructive action it is",
            dialog.contains("icon = Icons.Filled.Delete") &&
                dialog.contains("contentDescription = \"Remove from Home\"") &&
                dialog.contains("tint = KBDanger")
        )
        assertTrue(
            "and is labelled as a Library rail, not a catalog",
            dialog.contains("isLibraryRow = row.isLibraryRail") &&
                dialog.contains("isLibraryRow -> \"LIBRARY\"")
        )
        // Hide is the thing that must NOT be offered: the pins blob has no hidden
        // flag, and hiding must not be smuggled in as an unpin.
        val toggleSlot = dialog.substringAfter("if (row.isLibraryRail) {")
        assertTrue(
            "no SHOW/HIDE toggle is drawn for a Library row",
            toggleSlot.substringBefore("} else if (isHiddenSection) {").isNotBlank() &&
                !toggleSlot.substringBefore("} else if (isHiddenSection) {").contains("CatalogToggle(")
        )
        // The pin slot is where a collection draws its arrangement pin; a
        // Library row draws the DELETE button there instead, so the two stores
        // cannot be mistaken for each other by the row's own controls.
        val pinSlot = dialog
            .substringAfter("Spacer(modifier = Modifier.width(4.dp)) if (row.isLibraryRail) {")
            .substringBefore("} else if (row.isCollection) {")
        assertTrue(
            "and no arrangement pin button either - its slot carries REMOVE",
            pinSlot.contains("Icons.Filled.Delete") &&
                pinSlot.contains(".focusRequester(rowFocus.remove)") &&
                !pinSlot.contains("Icons.Filled.PushPin")
        )
    }

    @Test
    fun `a move writes the home order and the other two writes go through the pins store`() {
        assertTrue(
            "a move is an arrangement edit: it writes the ONE home order, through " +
                "the same core a catalog's arrows use, and never the pins blob",
            screen.contains("onLibraryMove = { pin, delta -> viewModel.moveLibraryRail(pin.key, delta) }") &&
                viewModel.contains("fun moveLibraryRail(key: String, delta: Int) {") &&
                !store.contains("fun movePinned(")
        )
        assertTrue(
            "a Library row's rename is a same-key replace, never an arrangement " +
                "title override",
            store.contains("fun renamePinned(") &&
                store.contains("setPinned(context, pin.copy(name = trimmed), true)")
        )
        assertTrue(
            "the screen reads the pins when the manager opens and re-reads on " +
                "every edit",
            screen.contains(
                "LibraryHomeRails.pins(context)"
            ) && screen.contains("libraryPinsRevision += 1")
        )
        assertTrue(
            "remove is the existing unpin, by the pin's stored key",
            screen.contains("LibraryHomeRails .setPinned(context, pin, false)") ||
                screen.contains(".setPinned(context, pin, false)")
        )
        assertTrue(
            "the rename dialog is the shared one, headed for this row",
            screen.contains("heading = \"RENAME LIBRARY RAIL\"") &&
                screen.contains(".renamePinned(context, pin, renameLibraryDraft)")
        )
        assertTrue(
            "and removal never waits for the list to load: it takes the row's pin",
            !dialog.substringAfter("fun removeLibraryRow(")
                .substringBefore("onLibraryRemove(pin)")
                .contains("LibraryHomeRailItems")
        )
    }

    @Test
    fun `a rail row with no art is drawn by its id`() {
        assertTrue(
            "the card only resolves when the row has no art of its own",
            home.contains("val hasOwnArt = !meta.poster.isNullOrBlank()") &&
                home.contains("enabled = !hasOwnArt")
        )
        assertTrue(
            "the resolved poster is the portrait fallback, and the title is " +
                "still the last resort",
            home.contains("fallbackUrl = meta.poster ?: resolvedPoster")
        )
        assertTrue(
            "the landscape card takes the resolved backdrop and clearlogo",
            home.contains("fallbackUrl = resolvedLandscapeArt.first ?: meta.poster ?: resolvedPoster") &&
                home.contains("logoUrl = resolvedLandscapeArt.second")
        )
        assertTrue(
            "an IMDb row keeps the lookup it always made",
            posterArt.contains("fetchEnrichedMetaCached(imdbId = id, type = type)") &&
                posterArt.contains("TmdbRepository.POSTER_BASE + path")
        )
        // The rail's id is `imdb ?: "tmdb:<n>"`, and the two namespaces need
        // two different calls: /find?external_source=imdb_id has nothing to
        // find for a "tmdb:<n>" id, which is the hole that left a BingeCat rail
        // (TMDB identity, often no IMDb id) title-only.
        assertTrue(
            "a \"tmdb:<n>\" row resolves by its TMDB id, through the numeric-id " +
                "detail call rather than the IMDb lookup",
            posterArt.contains("val tmdbId = ImageOverrides.tmdbIdOf(id)") &&
                posterArt.contains("repository.getDetailByTmdbId(tmdbId, type)")
        )
        assertTrue(
            "and the landscape resolver takes the same branch, so a tmdb-only row " +
                "gets its backdrop and wordmark too - one fix for poster and still",
            landscapeArt.contains("val tmdbId = ImageOverrides.tmdbIdOf(request.id)") &&
                landscapeArt.contains("repository.getDetailByTmdbId(tmdbId, request.type)")
        )
        assertTrue(
            "both doors file under one cache key, so a title resolved by either is " +
                "served to both and neither repeats a fetch",
            posterArt.contains("ImageOverrides.tmdbIdOf(") &&
                landscapeArt.contains("ImageOverrides.tmdbIdOf(")
        )
        assertTrue(
            "and an id TMDB cannot draw leaves the card's own title, never a blank " +
                "tile and never a dropped rail",
            posterArt.contains("?.takeIf { it.isNotBlank() }") &&
                posterArt.contains("poster = detail?.posterPath")
        )
        assertTrue(
            "the phone's pinned rail makes the same two resolutions, so the two " +
                "screens draw the same cards",
            mobileHome.contains("val hasOwnArt = !meta.poster.isNullOrBlank()") &&
                mobileHome.contains("fallbackUrl = meta.poster ?: resolvedPoster")
        )
        assertFalse(
            "and a row that HAS art (Trakt, MDBList) is not looked up at all - " +
                "its rail renders exactly as it did",
            mobileHome.contains("enabled = true") && posterArt.contains("enabled = true")
        )
    }

    @Test
    fun `nothing else about the manager moved`() {
        assertTrue(
            "the arrangement rows are still built from the merged order - now the " +
                "whole column, so the arrows cross to a pinned list and back",
            rows.contains("val column = homeColumnRailKeys(") &&
                rows.contains("val arrangementKeys = column.filterNot { key -> pinByKey.containsKey(key) }") &&
                rows.contains("val arrangementRows = arrangementKeys.mapNotNull { key ->")
        )
        assertTrue(
            "with the same exactness for every row's arrows",
            rows.contains("canMoveUp = railMoveChangesOrder(prefs, column, key, -1)")
        )
        assertTrue(
            "and the same kind chip for every other row",
            dialog.contains("isCustomCatalogRow -> \"CUSTOM\"") &&
                dialog.contains("isBuiltinRow -> \"BUILT-IN\"") &&
                dialog.contains("isBrowseRow -> \"BROWSE\"") &&
                dialog.contains("isCollection -> \"COLLECTION\"")
        )
        assertEquals(
            "the loader the rails are drawn from is untouched: art is the card's " +
                "job, not a new row field",
            0,
            Regex("TmdbRepository").findAll(
                source("com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
                    .substringAfter("object LibraryHomeRailItems")
            ).count()
        )
    }
}
