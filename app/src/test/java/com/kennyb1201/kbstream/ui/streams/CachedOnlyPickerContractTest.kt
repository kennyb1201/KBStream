package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seams the cached-only filter is wired through (spec T3, T6, T7, T8).
 *
 * The rule and the group bookkeeping are unit tested ([TorBoxCachedOnlyTest],
 * [CachedOnlyVisibleTest]); what is left is the part a pure test cannot see: WHEN
 * the filter is allowed to act, which publication sites it passes through, that
 * the toggle is a re-publish rather than a re-fetch, and that both pickers get
 * it from the one shared ViewModel.
 *
 * The fail-open direction is the one that matters. A wrong filter here hides a
 * source the viewer could have played; a filter that acts when it should not -
 * setting off, no key, a late answer for the previous title - empties a picker.
 */
class CachedOnlyPickerContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt"
        const val TV_SCREEN = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
        const val MOBILE_SCREEN = "com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }

    // ── T3: fail-open, in one expression ─────────────────────────────────────

    @Test
    fun `the filter is inactive unless the setting is on AND the answer fits this fetch`() {
        val gate = source(VIEW_MODEL).substringAfter("private fun activeCachedSet(")
            .substringBefore("private fun publishStreams(")
        assertTrue(
            "no answer yet is no filter - not an empty one",
            gate.contains("val answered = cachedAnswer ?: return null")
        )
        assertTrue(
            "a late answer for the previous title must never filter the new one",
            gate.contains("if (cachedAnswerGeneration != fetchGeneration) return null")
        )
        assertTrue(
            "the setting is the switch, and OFF is a true no-op",
            gate.contains("if (!AppPreferences.getTorboxCachedOnly(getApplication())) return null")
        )
        assertTrue(
            "and only then does the answered set reach the rows (an EMPTY answered " +
                "set is deliberately not null here: it hides hash rows)",
            gate.contains("return answered") &&
                gate.indexOf("return answered") > gate.indexOf("getTorboxCachedOnly")
        )
    }

    @Test
    fun `UNAVAILABLE leaves the picker alone`() {
        val badge = source(VIEW_MODEL).substringAfter("private fun requestCachedBadges(")
            .substringBefore("private fun loadAddon(")
        assertTrue(
            "a check that could not answer returns before anything is published",
            badge.contains("TorBoxCachedAnswer.Unavailable -> return@launch")
        )
        assertTrue(
            "and the publish is what the filter rides, so no publish means no filter",
            badge.indexOf("Unavailable -> return@launch") < badge.indexOf("publishStreams(")
        )
    }

    @Test
    fun `an answered-empty set is not treated as a failure`() {
        val badge = source(VIEW_MODEL).substringAfter("private fun requestCachedBadges(")
            .substringBefore("private fun loadAddon(")
        assertFalse(
            "the old early return on an empty set was harmless for chips and would " +
                "be a silent no-op for the filter: 'none cached' must still filter",
            badge.contains("if (cached.isEmpty())")
        )
    }

    // ── T6: the generation guard covers badges AND filter ────────────────────

    @Test
    fun `the generation guard stands between the answer and both effects`() {
        val badge = source(VIEW_MODEL).substringAfter("private fun requestCachedBadges(")
            .substringBefore("private fun loadAddon(")
        val guard = badge.indexOf("if (generation != fetchGeneration) return@launch")
        assertTrue("the guard exists", guard >= 0)
        assertTrue(
            "the cached set is only recorded for the generation it answered",
            guard < badge.indexOf("cachedAnswerGeneration = generation")
        )
        assertTrue(
            "and both publications happen after it, in the same guarded block",
            guard < badge.indexOf("publishStreams(") && guard < badge.indexOf("publishGroups(")
        )
        assertTrue(
            "the rows are marked on the raw list, so a chip survives a later toggle",
            badge.contains("publishStreams(TorBoxCachedBadges.mark(unfilteredStreams, cached)")
        )
    }

    // ── one filter site, over the raw rows ───────────────────────────────────

    @Test
    fun `every publication goes through the filter helpers`() {
        val model = source(VIEW_MODEL)
        assertEquals(
            "the merged list may only be written where the raw rows are recorded",
            1,
            Regex("_streams\\.value =").findAll(model).count()
        )
        assertEquals(1, Regex("_sourceAddons\\.value =").findAll(model).count())
        assertEquals(1, Regex("_addonGroups\\.value =").findAll(model).count())
        assertTrue(
            "and the helper keeps the raw rows, which is what makes the toggle a " +
                "re-publish instead of a re-fetch",
            model.contains("unfilteredStreams = streams") &&
                model.contains("unfilteredAddons = addons") &&
                model.contains("unfilteredGroups = groups")
        )
    }

    @Test
    fun `the add-on names are thinned with their rows`() {
        assertTrue(
            "a filtered row shifted a name onto the next add-on's source, and the " +
                "player then blamed the wrong add-on for a dead link",
            source(VIEW_MODEL).contains(
                "CachedOnlyVisible.alongside(addons, streams.size, kept)"
            )
        )
    }

    // ── T7: toggling is a re-publish, never a re-fetch ───────────────────────

    @Test
    fun `a setting change re-publishes the held rows`() {
        val model = source(VIEW_MODEL)
        assertTrue(
            "the pref is synced, so it can move from Settings here or a sibling TV; " +
                "either way the ViewModel hears it",
            model.contains("PrefsPayloadApplier.revision.collect { reapplyCachedOnlyFilter() }")
        )
        val reapply = model.substringAfter("fun reapplyCachedOnlyFilter()")
            .substringBefore("\n    /**")
        assertTrue(
            "re-publish both lists from the raw rows", 
            reapply.contains("publishStreams(unfilteredStreams, unfilteredAddons)") &&
                reapply.contains("publishGroups(unfilteredGroups)")
        )
        listOf("fetch(", "load(", "TorBoxClient", "checkCached").forEach { io ->
            assertFalse(
                "toggling must not cost a network round trip or a re-fetch: $io",
                reapply.contains(io)
            )
        }
    }

    @Test
    fun `the setting is off by default, synced, and never device-local`() {
        val prefs = source(PREFS)
        assertTrue(
            "default OFF, by the one constant a later flip would change",
            prefs.contains("KEY_TORBOX_CACHED_ONLY = \"torbox_cached_only\"") &&
                prefs.contains("getBoolean(KEY_TORBOX_CACHED_ONLY, false)")
        )
        assertTrue(
            "and every setter reports the change, which is the picker's cue",
            prefs.substringAfter("fun setTorboxCachedOnly(")
                .substringBefore("\n    }")
                .contains("syncDisplayPrefsBlob(context)")
        )
        val payload = source(PAYLOAD)
        // The key sets are long and their comments carry parentheses of their
        // own, so the block is taken by its opening literal and its closing line
        // rather than by the first ')' after the marker.
        fun block(marker: String): String {
            val start = payload.indexOf(marker)
            assertTrue("block missing: $marker", start >= 0)
            val end = payload.indexOf("\n    )", start)
            return payload.substring(start, if (end > start) end else payload.length)
        }
        assertTrue(
            "the setting rides the display-prefs blob",
            block("internal val SYNCED_PREF_KEYS = setOf(").contains("\"torbox_cached_only\"")
        )
        assertFalse(
            "and is not classified device-local",
            block("internal val EXCLUDED_PREF_KEYS = setOf(").contains("\"torbox_cached_only\"")
        )
    }

    @Test
    fun `both settings screens offer the row`() {
        assertTrue(
            "the TV pane renders it beside the other TorBox rows",
            source(TV_SETTINGS).contains("label = \"Cached sources only\"") &&
                source(TV_SETTINGS).contains("AppPreferences.setTorboxCachedOnly(context, it)")
        )
        assertTrue(
            "and the phone pane too - one setting, not one per form factor",
            source(MOBILE_SETTINGS).contains("label = \"Cached sources only\"") &&
                source(MOBILE_SETTINGS).contains("AppPreferences::setTorboxCachedOnly")
        )
    }

    // ── T8: one filter, both pickers ─────────────────────────────────────────

    @Test
    fun `both pickers read the shared ViewModel and neither filters on its own`() {
        val mobile = source(MOBILE_SCREEN)
        assertTrue(
            "the phone picker is the same ViewModel, which is why the filter needed " +
                "no second implementation",
            mobile.contains("import com.kennyb1201.kbstream.ui.streams.StreamsViewModel") &&
                mobile.contains("viewModel: StreamsViewModel")
        )
        listOf(source(TV_SCREEN), mobile).forEach { screen ->
            assertFalse(
                "a screen-side filter would disagree with the tabs and the counts",
                screen.contains("TorBoxCachedOnly") || screen.contains("CachedOnlyVisible")
            )
        }
    }

    @Test
    fun `the empty state names the setting, and the counts are the shown counts`() {
        listOf(source(TV_SCREEN), source(MOBILE_SCREEN)).forEach { screen ->
            assertTrue(
                "an all-uncached title says why the list is empty (spec F4)",
                screen.contains("No cached sources") &&
                    screen.contains("cachedOnly.hidden > 0") &&
                    screen.contains("cachedOnly.keyMissing")
            )
            assertTrue(
                "and the header counts the rows on screen, filtered or not",
                screen.contains("\${visibleStreams.size} sources found")
            )
        }
    }

    // ── The toggle collector is constructed LAST ─────────────────────────────

    @Test
    fun `the revision collector is constructed after every published state flow`() {
        val viewModel = source(VIEW_MODEL)
        val init = viewModel.indexOf("init {")
        val collector = viewModel.indexOf("PrefsPayloadApplier.revision.collect")
        val lastFlow = viewModel.lastIndexOf("= MutableStateFlow(")
        assertTrue("the collector is gone from the ViewModel", collector > 0)
        assertTrue(
            "`revision` is a StateFlow, so collect emits at once and that first pass " +
                "publishes through _streams/_sourceAddons/_addonGroups/_cachedOnly. The " +
                "init block has to sit AFTER the last MutableStateFlow declaration, or " +
                "construction dereferences a null field: Sentry ANDROID-15 on 0.9.141, " +
                "a fatal NPE in publishStreams on every picker open.",
            init > lastFlow && collector > init
        )
    }

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
}
