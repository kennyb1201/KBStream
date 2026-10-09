package com.kennyb1201.kbstream.ui.addons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Catalog Builder's wiring across files, pinned by reading the source.
 *
 * This module carries no Compose or Robolectric harness for these screens, so
 * the contract is read from the source - the same approach the settings, sync-row
 * and add-on contracts take. Everything here fails SILENTLY on a TV: a filter
 * field the editor never writes is a rule the viewer cannot set (the request was
 * "all the filters available"), a rail the merge cannot key is a rail that
 * disappears when it is moved, and a rule set that never reaches the synced
 * payload is one that stays on the TV it was built on.
 */
class CatalogBuilderContractTest {

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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun builder(): String = source(BUILDER_SCREEN)

    private fun viewModel(): String = source(BUILDER_VM)

    private companion object {
        const val BUILDER_SCREEN =
            "com/kennyb1201/kbstream/ui/addons/CatalogBuilderScreen.kt"
        const val BUILDER_VM =
            "com/kennyb1201/kbstream/ui/addons/CatalogBuilderViewModel.kt"
        const val BUILDER_MODELS =
            "com/kennyb1201/kbstream/ui/addons/CatalogBuilderModels.kt"
        const val STORE = "com/kennyb1201/kbstream/data/catalogs/CustomCatalog.kt"
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val TMDB_API = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val HOME_RAILS = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
        const val HOME_SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
        const val ADDONS_VM = "com/kennyb1201/kbstream/ui/addons/AddonsViewModel.kt"
        const val MANAGER = "com/kennyb1201/kbstream/ui/addons/AddonsHomeManagerDialog.kt"
        const val ADDONS_SCREEN = "com/kennyb1201/kbstream/ui/addons/AddonsScreen.kt"
        const val SETTINGS_SCREEN =
            "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NAV = "com/kennyb1201/kbstream/NavStateSerialization.kt"
        const val SYNC_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val SYNC_RULES = "com/kennyb1201/kbstream/data/sync/SyncRules.kt"
        const val SYNC_MAIN = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val ORDER_PREFS = "com/kennyb1201/kbstream/data/kb/KBHomeOrderPrefs.kt"
    }

    // ---------------------------------------------------- every filter field --

    /**
     * Every field of [com.kennyb1201.kbstream.data.kb.KBFilters], each of which
     * the editor has to be able to SET. "All the filters available" is exactly
     * this list: an omitted field is a rule the viewer can see in the model and
     * never reach from the couch.
     */
    private val filterFields = listOf(
        "year",
        "withGenres",
        "watchRegion",
        "voteCountGte",
        "withKeywords",
        "withNetworks",
        "withCompanies",
        "withoutGenres",
        "releaseDateGte",
        "releaseDateLte",
        "voteAverageGte",
        "voteAverageLte",
        "withoutKeywords",
        "withoutCompanies",
        "withOriginCountry",
        "withWatchProviders",
        "withOriginalLanguage",
        "withoutWatchProviders",
        "withRuntimeGte",
        "withRuntimeLte",
        "withCast",
        "certificationCountry",
        "certification",
        "certificationLte",
        "withStatus",
        "withType",
        "withoutNetworks",
        "withReleaseType"
    )

    @Test
    fun `the filter model still has exactly the fields this contract covers`() {
        val filters = source("com/kennyb1201/kbstream/data/kb/KBModels.kt")
        val block = between(filters, "data class KBFilters(", "\n)")
        val declared = Regex("val (\\w+):")
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "the builder's coverage list must track KBFilters itself",
            filterFields.sorted(),
            declared.sorted()
        )
    }

    @Test
    fun `the builder can set every filter field`() {
        val written = viewModel()
        val missing = filterFields.filterNot { field -> written.contains("filters.copy(") && written.contains("$field =") }
        assertTrue(
            "these KBFilters fields are unreachable from the builder: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `every filter row the model offers is drawn by the screen`() {
        val screen = squash(builder())
        listOf(
            "withGenres" to "Genres",
            "withoutGenres" to "Exclude genres",
            "withWatchProviders" to "Services",
            "withoutWatchProviders" to "Exclude services",
            "withCompanies" to "Studios",
            "withoutCompanies" to "Exclude studios",
            "withNetworks" to "Networks",
            "withKeywords" to "Keywords",
            "withoutNetworks" to "Exclude networks",
            "withRuntimeGte" to "Runtime",
            "withCast" to "Cast",
            "certification" to "Age rating",
            "certificationLte" to "Age rating or milder",
            "withStatus" to "Status",
            "withType" to "Show type",
            "withReleaseType" to "Release type"
        ).forEach { (_, label) ->
            assertTrue("the editor has no \"$label\" section", screen.contains("title = \"$label\""))
        }
    }

    @Test
    fun `the editor draws one chip row per filter dimension`() {
        val screen = builder()
        // Genres, exclude genres, services, exclude services, studios, exclude
        // studios, networks, keywords, decade, released after/before, languages,
        // countries, min/max rating, min votes and the preview.
        val sections = Regex("EditorSection\\(title = \"")
            .findAll(screen)
            .count()
        assertTrue(
            "expected an editor section per filter dimension, found $sections",
            sections >= 18
        )
        assertTrue(
            "the release window must be settable (releaseDateGte/Lte)",
            screen.contains("onReleasedAfter") && screen.contains("onReleasedBefore")
        )
    }

    @Test
    fun `the decade pick and the release window cannot both be set`() {
        // discoverKB prefers the year range over the release bounds, so a
        // decade picked on top of a window would leave a filter that looks
        // active and does nothing.
        val vm = squash(viewModel())
        assertTrue(
            "picking a decade must clear the explicit bounds",
            vm.contains("releaseDateGte = null, releaseDateLte = null")
        )
        assertTrue(
            "a release bound must clear the decade",
            vm.contains("year = null")
        )
    }

    @Test
    fun `the genre chips are pruned by media type, not merely labelled`() {
        val models = source(BUILDER_MODELS)
        assertTrue(
            "the media type switch must prune genres the new type cannot answer",
            models.contains("pruneGenreFilters")
        )
        assertTrue(
            "and it must run on the switch itself",
            models.contains("withCatalogMediaType")
        )
        val vm = squash(viewModel())
        assertTrue(
            "the screen's type chips must go through the pruning switch",
            vm.contains("withCatalogMediaType(it, mediaType)")
        )
    }

    // ------------------------------------------------------------- loader ---

    @Test
    fun `home loads a rail per catalog through the full filter discover`() {
        val home = squash(source(HOME_VM))
        assertTrue(
            "custom catalogs must run the same discover the collection profiles do",
            home.contains("tmdbRepository.discoverKB(")
        )
        assertTrue(
            "the loader must send the catalog's own rules",
            home.contains("filters = catalog.effectiveFilters()")
        )
        assertTrue(
            "and its media-agnostic sort translated for the media type",
            home.contains("sortBy = catalog.tmdbSortKey()")
        )
        assertTrue(
            "a hidden catalog must cost no request at all",
            home.contains("customCatalogKey(catalog.id) in hidden")
        )
        assertTrue(
            "the rail must carry the custom id so the merge can key it",
            home.contains("customCatalogId = catalog.id")
        )
        assertTrue(
            "one discover page per catalog",
            home.contains("CUSTOM_CATALOG_RAIL_LIMIT")
        )
        assertTrue(
            "the grid must not try to page a built catalog",
            home.contains("exhaustedRails.add(railKeyOf(rail))")
        )
    }

    @Test
    fun `every new filter reaches the discover request`() {
        // A field the builder can set but the loader never forwards is a filter
        // that looks active on the TV and narrows nothing - the failure mode
        // this whole screen is about. The chain is model field -> query param
        // on the API service -> named argument in discoverKB, and every link is
        // one line that can be dropped silently.
        val discover = squash(source(TMDB_REPO))
        val loader = between(discover, "suspend fun discoverKB(", "}.results")
        listOf(
            "withRuntimeGte = filters?.withRuntimeGte",
            "withRuntimeLte = filters?.withRuntimeLte",
            "withCast = filters?.withCast",
            "certificationCountry = filters?.certificationCountry",
            "certification = filters?.certification",
            "certificationLte = filters?.certificationLte",
            "withStatus = filters?.withStatus",
            "withType = filters?.withType",
            "withoutNetworks = filters?.withoutNetworks"
        ).forEach { forwarding ->
            assertTrue("discoverKB never forwards: $forwarding", loader.contains(forwarding))
        }

        val api = squash(source(TMDB_API))
        listOf(
            "with_runtime.gte",
            "with_runtime.lte",
            "with_cast",
            "certification_country",
            "certification",
            "certification.lte",
            "with_status",
            "with_type",
            "without_networks"
        ).forEach { param ->
            assertTrue(
                "the discover endpoints have no @Query(\"$param\")",
                api.contains("@Query(\"$param\")")
            )
        }
    }

    @Test
    fun `a rail knows which catalog it came from`() {
        val rail = source(HOME_RAILS)
        assertTrue(
            "Rail must carry the built catalog's id",
            rail.contains("val customCatalogId: String? = null")
        )
    }

    @Test
    fun `home rebuilds its rails when a catalog changes`() {
        val home = squash(source(HOME_VM))
        assertTrue(
            "the builder is a screen away, so a change has to rebuild Home",
            home.contains("CustomCatalogStore.revision")
        )
        assertTrue(
            "and it must be wired into init",
            home.contains("observeCustomCatalogChanges()")
        )
    }

    @Test
    fun `the release-type filter reaches discover as with_release_type and a region`() {
        // Movie-only in TMDB, and resolved against a REGION - so the chain is
        // field -> query param -> named argument on the MOVIE discover only,
        // and the region has to ride with it or the filter means nothing.
        val discover = squash(source(TMDB_REPO))
        val loader = between(discover, "suspend fun discoverKB(", "}.results")
        assertTrue(
            "discoverKB never forwards with_release_type",
            loader.contains("withReleaseType = filters?.withReleaseType")
        )
        assertTrue(
            "the release type has to be read against the viewer's country",
            loader.contains("region = releaseRegion")
        )
        assertTrue(
            "and the region is only sent when there is a release type to read",
            discover.contains(
                "val releaseRegion = filters?.withReleaseType?.let { filters.watchRegion }"
            )
        )
        val api = squash(source(TMDB_API))
        assertTrue(
            "the movie discover has no with_release_type query",
            api.contains("@Query(\"with_release_type\") withReleaseType: String? = null")
        )
        assertTrue(
            "the movie discover has no region query",
            api.contains("@Query(\"region\") region: String? = null")
        )
        assertFalse(
            "a release type on the TV discover is a param TMDB ignores",
            between(api, "suspend fun discoverTvGeneric(", "): TmdbDiscoverResponse")
                .contains("with_release_type")
        )
        assertTrue(
            "a media-type switch must drop the movie-only release filter",
            source(BUILDER_MODELS)
                .contains("withReleaseType = pruned.withReleaseType?.takeIf { !isTv }")
        )
        assertTrue(
            "and the row is only drawn for movies",
            squash(builder()).contains("if (!isTv) { EditorSection(title = \"Release type\")")
        )
    }

    // ------------------------------------------------------ focus and custom --

    @Test
    fun `a focused chip is drawn with a ring, not just a tint`() {
        // The whole builder is rows of same-shaped capsules and the D-pad is the
        // only pointer. Focus used to change a surface tint and grow the capsule
        // 4%, which is what "I cannot see what I am focused on" was about.
        val chip = between(builder(), "private fun BuilderChip(", "private fun PickedFilterRow(")
        assertTrue("no focused ring", chip.contains("focusedBorder ="))
        assertTrue("no focus glow", chip.contains("focusedGlow ="))
        assertTrue(
            "no focus growth",
            squash(chip).contains("focusedScale = KBFocusChip")
        )
        assertTrue(
            "the ring must be the accent the rest of the app rings focus with",
            squash(chip).contains("BorderStroke(2.dp, KBAccent)")
        )
    }

    @Test
    fun `every id row offers a way to enter an id its list does not carry`() {
        val screen = squash(builder())
        CatalogIdField.entries.forEach { field ->
            assertTrue(
                "${field.label} offers no way past its shipped chip list",
                screen.contains("customField = CatalogIdField.${field.name}")
            )
        }
        assertTrue("no id entry dialog", screen.contains("private fun CustomIdDialog("))
        assertTrue(
            "the dialog has to say what its ids are, or one is typed into the wrong row",
            screen.contains("private fun customIdHint(")
        )
        assertTrue(
            "the row's chip has to open that dialog",
            screen.contains("onAddCustom = { customField = it }")
        )
        assertTrue(
            "and what the viewer typed has to reach the view model",
            screen.contains("onAddCustomIds(field, ids)")
        )
    }

    @Test
    fun `a hand-typed id lands on the same list the chips read`() {
        // A second writer for the same list is how a typed id and a tapped chip
        // drift apart; there is one, and it is the field's own merge.
        val vm = squash(viewModel())
        assertTrue(
            "the typed ids must go through the field's merge",
            vm.contains("field.withIds(filters, ids)")
        )
        val models = source(BUILDER_MODELS)
        assertTrue(
            "the merge keeps order and drops duplicates",
            models.contains("val merged = (read(filters) + ids).distinct()")
        )
        assertTrue(
            "and the movie-only id rows are the fields the screen passes",
            models.contains("enum class CatalogIdField(")
        )
    }

    // ---------------------------------------------------------- arrangement --

    @Test
    fun `the merge keys a built rail by its own family and still arranges it`() {
        val slots = squash(source(HOME_SLOTS))
        assertTrue(
            "a built rail keys into custom:, not by a fake add-on URL",
            slots.contains("KBHomeOrderPrefs.customCatalogKey(it)")
        )
        // Every rail on Home is placed by the ONE merged arrangement now (the
        // hardcoded/app-built block that used to force a null-base-URL rail to
        // the top is gone), so a built catalog is arranged like anything else.
        assertTrue(
            "it must be arrangeable through the shared merged order",
            slots.contains("mergedHomeRailKeys(arrangement, defaults)")
        )
    }

    @Test
    fun `the home manager lists built catalogs beside every other rail`() {
        val vm = squash(source(ADDONS_VM))
        assertTrue(
            "the defaults drive the merged order the manager moves",
            vm.contains("KBHomeOrderPrefs.customCatalogKey(catalog.id)")
        )
        assertTrue(
            "the manager's snapshot must carry them",
            vm.contains("customCatalogs = CustomCatalogStore.list(context)")
        )
        val dialog = squash(source(MANAGER))
        assertTrue(
            "the dialog has to draw a row for a custom key",
            dialog.contains("KBHomeOrderPrefs.isCustomCatalogKey(key)")
        )
        assertTrue(
            "and it must not call a hand-composed catalog BUILT-IN",
            dialog.contains("isCustomCatalogRow -> \"CUSTOM\"")
        )
        val addons = squash(source(ADDONS_SCREEN))
        assertTrue(
            "renaming a built catalog starts from the name it was given",
            addons.contains("renameBuiltinDraft = order.renames[key] ?: customName")
        )
    }

    @Test
    fun `the builder's own show switch writes the shared arrangement`() {
        val vm = squash(viewModel())
        assertTrue(
            "the show/hide switch is the SAME state the manager edits",
            vm.contains("KBHomeOrderPrefs.toggleRailHidden(")
        )
        assertTrue(
            "and it saves through the arrangement store",
            vm.contains("KBHomeOrderPrefs.save(context, updated)")
        )
    }

    // ----------------------------------------------------------------- sync --

    @Test
    fun `a built catalog reaches the account`() {
        val payload = source(SYNC_PAYLOAD)
        assertTrue(payload.contains("const val KEY_CUSTOM_CATALOGS"))
        assertTrue(payload.contains("KEY_CUSTOM_CATALOGS to buildCustomCatalogs(context)"))
        assertTrue(payload.contains("fun buildCustomCatalogs(context: Context): JsonObject"))
        assertTrue(
            "an incoming blob has to be applied",
            payload.contains("PrefsPayloadBuilder.KEY_CUSTOM_CATALOGS -> applyCustomCatalogs(context, payload)")
        )
        assertTrue(
            "and a blank blob must never erase the account's catalogs",
            payload.contains("if (blob.isBlank()) return")
        )
        assertTrue(
            "the publish stamp follows the blob's own change time",
            source(SYNC_RULES).contains("shouldPublishCustomCatalogs")
        )
        assertTrue(
            "the bulk push must skip an empty list",
            source(SYNC_MAIN).contains("HomeListBlobRules.shouldPublishCustomCatalogs(blob)")
        )
    }

    @Test
    fun `the store writes the blob and its stamp before it enqueues the push`() {
        val store = source(STORE)
        val save = between(store, "fun save(context: Context, catalogs: List<CustomCatalog>)", "fun upsert(")
        val writeAt = save.indexOf("putString(KEY_BLOB")
        val stampAt = save.indexOf("putLong(SYNCED_AT_KEY")
        val enqueueAt = save.indexOf("SupabaseSync.enqueuePrefs(")
        assertTrue("the blob write must exist", writeAt > 0)
        assertTrue("the stamp must exist", stampAt > writeAt)
        assertTrue(
            "the push must be enqueued AFTER the local write, or it publishes the previous list",
            enqueueAt > stampAt
        )
    }

    @Test
    fun `the builder writes and the sync layer reads the same keys`() {
        val store = source(STORE)
        listOf("SYNC_STORE", "SYNC_BLOB_KEY", "SYNCED_AT_KEY").forEach { key ->
            assertTrue("the store must expose $key for the payload layer", store.contains("const val $key"))
        }
        val payload = source(SYNC_PAYLOAD)
        assertTrue(
            "the payload must read the store's own encoding",
            payload.contains("CustomCatalogStore.SYNC_BLOB_KEY")
        )
    }

    // -------------------------------------------------------------- routing --

    @Test
    fun `the builder is reachable from settings and back out of it lands there`() {
        val main = source(MAIN)
        assertTrue(main.contains("data class CatalogBuilder(val returnTo: Screen = Home) : Screen()"))
        assertTrue(
            "the screen has to be rendered",
            main.contains("com.kennyb1201.kbstream.ui.addons.CatalogBuilderScreen(")
        )
        val branch = between(main, "is Screen.CatalogBuilder ->", "is Screen.Search ->")
        assertTrue(
            "and Back must return to whatever opened the builder, keeping its return path",
            branch.contains("onBack = { screen = stableBackDestination(current.returnTo) }")
        )
        assertTrue(
            "Settings has to offer the door now that the Add-ons header does not",
            main.contains("screen = Screen.CatalogBuilder(returnTo = Screen.Settings)")
        )
        assertTrue(
            "and the settings pane is where the row lives",
            source(SETTINGS_SCREEN).contains("label = \"Catalogs\"")
        )
        assertFalse(
            "the door must have MOVED: a leftover Add-ons button is a second entry " +
                "point that can drift from this one",
            source(ADDONS_SCREEN).contains("label = \"CATALOGS\"")
        )
        assertTrue(
            "the two screen types must be distinguishable for the transition table",
            source(NAV).contains("is Screen.CatalogBuilder -> \"catalogBuilder\"")
        )
        val nav = source(NAV)
        assertTrue(
            "a restored navigation state has to decode it",
            nav.contains("\"catalogBuilder\" -> Screen.CatalogBuilder(")
        )
    }

    // ------------------------------------------------------------- preview --

    @Test
    fun `the preview runs the draft's own rules and shows the shelf`() {
        val vm = squash(viewModel())
        assertTrue(
            "the preview must be the same query the rail runs",
            vm.contains("sortBy = catalog.tmdbSortKey()") &&
                vm.contains("filters = catalog.effectiveFilters()")
        )
        val screen = squash(builder())
        assertTrue(
            "and the screen has to draw what came back",
            screen.contains("preview.items") && screen.contains("PreviewCard(")
        )
        assertTrue(
            "with something said about empty and failed results",
            screen.contains("\"No titles match these rules\"") ||
                vm.contains("\"No titles match these rules\"")
        )
    }

    @Test
    fun `editing a rule drops a stale preview instead of leaving it on screen`() {
        val vm = squash(viewModel())
        val update = between(vm, "fun updateDraft(", "fun setMediaType(")
        assertTrue(
            "the row must not outlive the rules that produced it",
            update.contains("resetPreview()")
        )
    }

    /** The block between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    @Test
    fun `the delete path cannot remove a catalog without a confirmation`() {
        val screen = squash(builder())
        assertTrue(
            "DELETE opens a confirm, it does not delete",
            screen.contains("onDelete = { pendingDelete = it }")
        )
        assertTrue(screen.contains("ConfirmDeleteDialog("))
        assertTrue(
            "and only the confirm calls the ViewModel",
            screen.contains("viewModel.delete(target.id)")
        )
        assertFalse(
            "no row may call delete directly",
            screen.contains("onClick = { viewModel.delete(")
        )
    }

    // -------------------------------------------------- focus vs the clip --

    @Test
    fun `every chip row leaves room for the focused chip inside its own clip`() {
        // A LazyRow clips its own content, so the chip the D-pad is on had its
        // growth, its ring and its glow cut flat along the row's edges - the
        // LAST chip worst of all, because focus only reaches it once the row
        // has scrolled to its end and it is sitting flush against the cut. The
        // room has to be spent INSIDE that clip and cancelled just outside it
        // (see KBFocusChipInset), on every row, or the button that is supposed
        // to show what is focused is the one thing the viewer cannot see.
        val screen = builder()
        listOf(
            "private fun ChipRow(" to "private fun CodeChipRow(",
            "private fun CodeChipRow(" to "private fun ChoiceRow(",
            "private fun ChoiceRow(" to "private fun NumberChipRow(",
            "private fun NumberChipRow(" to "private fun BuilderChip("
        ).forEach { (start, end) ->
            val row = between(screen, start, end)
            assertTrue(
                "$start leaves no room inside its clip for a focused chip",
                row.contains("horizontal = KBFocusChipInset")
            )
            assertTrue(
                "$start does not cancel that inset, so it would shift the row",
                row.contains("Modifier.offset(x = -KBFocusChipInset)")
            )
        }
    }

    @Test
    fun `a selected chip stays readable while the D-pad is on it`() {
        // A selected chip is an accent wash under an accent label. Focus used
        // to keep the label in the accent, which is one color on one color: the
        // rule set the viewer is reading at that exact moment is the one they
        // cannot read. Focus deepens the wash a step and puts the label in the
        // light tone instead (the same fix BrowseCategoryTab documents).
        val chip = between(
            builder(),
            "private fun BuilderChip(",
            "private fun PickedFilterRow("
        )
        val squashed = squash(chip)
        assertTrue(
            "a focused chip must not keep the accent label on the accent wash",
            squashed.contains("focusedContentColor = KBTextHi")
        )
        assertTrue(
            "and a focused SELECTED chip must deepen its wash, not flatten it",
            squashed.contains(
                "focusedContainerColor = if (selected) { KBAccent.copy(alpha = 0.32f) } " +
                    "else { KBSurfaceRaised }"
            )
        )
    }

    // --------------------------------------------------- preview feedback --

    @Test
    fun `the preview shelf is drawn under the header that fills it`() {
        // PREVIEW is pressed in the header. As the editor's last section the
        // shelf was decided correctly and drawn invisibly - several screens
        // below the button that filled it - which reads as a dead button AND
        // as rules that return nothing at all.
        val screen = builder()
        val panel = screen.indexOf("PreviewPanel(preview = preview)")
        val editor = screen.indexOf("CatalogEditor(")
        assertTrue("the screen does not draw the preview panel", panel > 0)
        assertTrue(
            "the preview has to be drawn above the editor's own scroll",
            editor > panel
        )
        assertTrue(
            "and it has to say how many titles came back, so an empty run " +
                "reads as empty rather than as never having run",
            screen.contains("preview.items.size")
        )
        assertFalse(
            "the shelf must not be back at the foot of the editor's scroll",
            squash(screen).contains("EditorSection(title = \"Preview\")")
        )
    }

    @Test
    fun `the preview predicts the rail Home will draw, not a richer row`() {
        // Home drops a built catalog's non-English titles before it draws the
        // rail - the English-only browse switch, on by default - so a preview
        // that skipped that pass reports titles for a rail that will not be
        // there. "I built a catalog and nothing was made" is exactly what that
        // gap looks like from the couch.
        val vm = squash(viewModel())
        val preview = between(vm, "fun runPreview(", "fun setKeywordsInclude(")
        assertTrue(
            "the preview never runs Home's own English-only pass",
            preview.contains("tmdbRepository.browseLanguage()")
        )
        assertTrue(
            "and it must read the field Home reads it from",
            preview.contains("item.originalLanguage")
        )
        assertTrue(
            "the pass the rail makes is the same one",
            squash(source(HOME_VM)).contains("tmdbRepository.browseLanguage()")
        )
        assertTrue(
            "a row emptied BY that pass has to say so, not read as no results",
            vm.contains("No English titles match these rules")
        )
    }
}
