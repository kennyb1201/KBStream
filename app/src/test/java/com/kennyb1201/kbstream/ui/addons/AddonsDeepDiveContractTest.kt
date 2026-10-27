package com.kennyb1201.kbstream.ui.addons

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Addons / Catalog Builder / Home Manager deep dive's fixes, pinned where a
 * device would meet them.
 *
 * Four of these are "a control said something that was not true": a builder
 * move arrow that reordered nothing the viewer could see, SHOW ALL that restored
 * only add-on catalogs, a catalog reported "added to Home" while the store
 * dropped it at the cap, and an import-style save that would have replaced a
 * blob this build could not read. The rest are state that went stale or a
 * failure that was only logged.
 *
 * This module carries no Compose harness, so the wiring is read from source -
 * the same approach the other deep-dive contract tests take. The behavioural
 * halves live beside the data they pin: `AddonConfigReadSafetyTest` for the
 * add-on write guard and `CustomCatalogStoreReadSafetyTest` for the catalog
 * one.
 */
class AddonsDeepDiveContractTest {

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

    private companion object {
        const val ADDON_MANAGER = "com/kennyb1201/kbstream/data/addon/AddonManager.kt"
        const val CATALOG_STORE = "com/kennyb1201/kbstream/data/catalogs/CustomCatalog.kt"
        const val ADDONS_VM = "com/kennyb1201/kbstream/ui/addons/AddonsViewModel.kt"
        const val BUILDER_VM = "com/kennyb1201/kbstream/ui/addons/CatalogBuilderViewModel.kt"
        const val BUILDER_TV = "com/kennyb1201/kbstream/ui/addons/CatalogBuilderScreen.kt"
        const val BUILDER_MOBILE = "com/kennyb1201/kbstream/ui/mobile/CatalogBuilderScreenMobile.kt"
        const val MANAGER_TV = "com/kennyb1201/kbstream/ui/addons/AddonsHomeManagerDialog.kt"
        const val MANAGER_MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeManagerDialogMobile.kt"
        const val ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
    }

    /** Everything between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    /**
     * One function's body. The parameter list is walked first, because a default
     * value can spell an empty lambda and counting from the first brace of the
     * header would take that pair for the body.
     */
    private fun functionBody(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("missing function: $header", start >= 0)
        var i = source.indexOf('(', start)
        assertTrue("missing parameter list: $header", i >= 0)
        var parens = 0
        while (i < source.length) {
            when (source[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) {
                        i++
                        break
                    }
                }
            }
            i++
        }
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    // ── A1: an unreadable add-on blob cannot be written over ──────────

    @Test
    fun `an unreadable add-on blob refuses mutating saves for the session`() {
        val manager = source(ADDON_MANAGER)
        assertTrue(
            "the read path flags the fallback instead of only logging it",
            manager.contains("private var unreadableBlobProfileId: String? = null") &&
                manager.contains("private var servingUnreadableBlob = false") &&
                manager.contains("return AddonLoad(defaultAddons(), unreadableBlob = true)")
        )
        assertTrue(
            "a blank store is NOT a failure: it is the legitimate factory seed",
            manager.contains("return AddonLoad(defaults)")
        )

        val save = functionBody(manager, "fun saveInstalledAddons(")
        assertTrue(
            "the write refuses, and says why, before it touches prefs — on the flag, " +
                "not on the profile id, which is null in a no-profile store",
            save.contains("if (servingUnreadableBlob)") &&
                save.contains("Refusing to save add-ons for profile")
        )
        assertTrue(
            "and the refusal comes before the write",
            save.indexOf("Refusing to save add-ons") <
                save.indexOf("store.edit()")
        )
        assertTrue(
            "a sibling profile's unreadable blob must not lock the viewer's own " +
                "editing out, so the copy read does not arm the guard",
            manager.contains("return loadFromPreferencesOrDefaults(profileId).addons")
        )
        assertTrue(
            "a load of the ACTIVE profile re-answers the question (a cloud pull " +
                "that adopts a readable set repairs the profile)",
            functionBody(manager, "private fun loadForLocked(")
                .contains("servingUnreadableBlob = load.unreadableBlob")
        )
    }

    // ── A2: a refresh re-answers the health badge ────────────────────

    @Test
    fun `a manifest refresh updates the health badge it just proved`() {
        val vm = source(ADDONS_VM)
        val all = functionBody(vm, "fun refreshAllManifests()")
        assertTrue(
            "REFRESH ALL records what its own fetches answered",
            all.contains("health[old.id] = AddonHealth(healthy = true, checkedAt = healthCheckedAt)") &&
                all.contains("health[old.id] = AddonHealth(") &&
                all.contains("_health.value = health")
        )
        assertTrue(
            "a failing manifest marks that add-on unhealthy in the same pass",
            all.contains("healthy = false")
        )
        assertTrue(
            "one map is built for the pass and published once, rather than " +
                "re-fetching every manifest a second time to learn what the " +
                "fetches already answered",
            all.contains("val health = _health.value.toMutableMap()")
        )

        val one = functionBody(vm, "fun refreshManifest(id: String)")
        assertTrue(
            "the single-add-on refresh does the same",
            one.contains("_health.value = _health.value + (") &&
                one.contains("healthy = true")
        )
        assertTrue(
            "including when the URL now serves something that is not an add-on",
            one.contains("healthy = false")
        )
    }

    // ── B1: an unreadable catalog blob cannot be written over ────────

    @Test
    fun `an unreadable catalog blob is reported and protected`() {
        val store = source(CATALOG_STORE)
        assertTrue(
            "the read distinguishes a parse failure from an empty store",
            store.contains("fun isBlobUnreadable(context: Context): Boolean") &&
                store.contains("return Reading(emptyList(), unreadable = true)")
        )
        assertTrue(
            "and says so loudly, once per bad payload rather than once per read",
            store.contains("Custom catalogs could not be parsed on this device") &&
                store.contains("if (loggedUnreadableBlob != raw)")
        )

        val save = functionBody(store, "fun save(context: Context, catalogs: List<CustomCatalog>)")
        assertTrue(
            "the write refuses instead of replacing every built catalog",
            save.contains("if (stored.unreadable)") &&
                save.contains("Refusing to save custom catalogs")
        )
        assertTrue(
            "and the refusal comes before the blob write",
            save.indexOf("Refusing to save custom catalogs") <
                save.indexOf(".putString(KEY_BLOB")
        )

        val draft = functionBody(source(BUILDER_VM), "fun saveDraft()")
        assertTrue(
            "the builder says so rather than offering a save it knows will fail",
            draft.contains("CustomCatalogStore.isBlobUnreadable(context)") &&
                draft.contains("could not be read on this device")
        )
    }

    // ── B2: the cap is reported, not faked ───────────────────────────

    @Test
    fun `the builder reports the catalog cap instead of a false success`() {
        val draft = functionBody(source(BUILDER_VM), "fun saveDraft()")
        assertTrue(
            "the message is derived from what was STORED, not from the press",
            draft.contains("if (stored.none { it.id == saved.id })") &&
                draft.contains("is the catalog limit")
        )
        assertTrue(
            "and the draft is kept, so the rules just set are not lost",
            draft.indexOf("is the catalog limit") < draft.indexOf("_draft.value = null")
        )
        assertTrue(
            "the success line is still there for the case that actually stored it",
            draft.contains("added to Home")
        )
    }

    // ── B3: the builder's arrows say what they order ────────────────

    @Test
    fun `the builder move arrows are labelled as list order only`() {
        listOf(BUILDER_TV, BUILDER_MOBILE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path: the list says where its order applies - and where it does " +
                    "not, since a rail the manager has arranged stops following it",
                screen.contains("The arrows set this list's order") &&
                    screen.contains("set in the home manager")
            )
        }
        assertTrue(
            "the TV arrows' own descriptions name the list",
            source(BUILDER_TV).contains("contentDescription = \"Move up in this list\"") &&
                source(BUILDER_TV).contains("contentDescription = \"Move down in this list\"")
        )
        assertTrue(
            "the phone's UP/DOWN buttons read as moves, not as bare directions",
            source(BUILDER_MOBILE).contains("label = \"MOVE UP\"") &&
                source(BUILDER_MOBILE).contains("label = \"MOVE DOWN\"")
        )
    }

    // ── B4: one query per burst, not per keystroke ──────────────────

    @Test
    fun `the keyword and cast searches are debounced`() {
        val vm = source(BUILDER_VM)
        assertTrue(
            "the wait is declared once, with its reason",
            vm.contains("private const val SUGGESTION_DEBOUNCE_MS = 300L")
        )
        listOf(
            "fun searchKeywords(query: String)",
            "fun searchPeople(query: String)"
        ).forEach { header ->
            val body = functionBody(vm, header)
            assertTrue(
                "$header sends one query per burst: the next keystroke cancels " +
                    "the job that is waiting",
                body.contains("delay(SUGGESTION_DEBOUNCE_MS)")
            )
        }
        assertTrue(
            "a pick cancels a search still waiting, so the suggestion row cannot " +
                "re-open over the pick",
            functionBody(vm, "fun pickKeyword(keyword: TmdbSearchKeywordResult)")
                .contains("keywordJob?.cancel()") &&
                functionBody(vm, "fun pickPerson(person: TmdbSearchPersonResult)")
                    .contains("peopleJob?.cancel()")
        )
    }

    // ── H1: SHOW ALL says which rails it means ──────────────────────

    @Test
    fun `show all in the home manager names its scope`() {
        listOf(MANAGER_TV to "AddonsHomeManagerDialog", MANAGER_MOBILE to "manager").forEach {
            (path, label) ->
            val dialog = source(path)
            assertTrue(
                "$label: the button that only ever touched add-on catalogs must " +
                    "say so — the header promises the rail list",
                dialog.contains("\"SHOW ALL CATALOGS\"") &&
                    dialog.contains("\"HIDE ALL CATALOGS\"")
            )
            assertFalse(
                "$label: the unscoped label is what over-promised",
                dialog.contains("\"SHOW ALL\"") || dialog.contains("\"HIDE ALL\"")
            )
        }
    }

    // ── H2: the third door to add-on management is gated too ────────

    @Test
    fun `manage rails applies the kids add-on lock before it navigates`() {
        val activity = source(ACTIVITY)
        val block = between(activity, "onManageRails = {", "is Screen.Settings ->")
        assertTrue(
            "the door checks the lock the other two doors check",
            block.contains("active?.kidsMaxAge != null && active.kidsHideAddons")
        )
        assertTrue(
            "and the check comes BEFORE the request, so a blocked press cannot " +
                "leave a one-shot that opens the manager on a later visit",
            block.indexOf("kidsHideAddons") <
                block.indexOf("HomeRailManagerRequest.request()")
        )
    }
}
