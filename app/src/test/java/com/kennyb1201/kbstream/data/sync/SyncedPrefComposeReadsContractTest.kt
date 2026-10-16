package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The class rule behind "I changed a display setting on the other TV and this
 * one did not move": every Compose read of a
 * [PrefsPayloadBuilder.SYNCED_PREF_KEYS]-backed pref goes through
 * [PrefsPayloadApplier.revision] - collected directly, or as the key of the
 * `remember` that caches the read - never a bare `remember`.
 *
 * Why a bare `remember` cannot work: Compose recomposes on observed snapshot
 * STATE, and a SharedPreferences lookup is not state. A pref read at first
 * composition is frozen until something ELSE invalidates the composable, so the
 * only thing that can make a synced pref land live is the State object the
 * applier bumps. `revision` is that object, and it ticks once per real change
 * to the profile on screen.
 *
 * Both halves of the rule are DERIVED from the sources rather than restated
 * here: the synced key set is parsed out of
 * [PrefsPayloadBuilder.SYNCED_PREF_KEYS], and each `AppPreferences.get…` is
 * bound to the key it reads, so a key added to the synced set brings its getter
 * under the rule automatically. That is what makes this the enforcement point
 * instead of a list somebody has to remember to extend - the failure it exists
 * for ("it didn't sync") is a silent no-op, which is exactly the kind of bug
 * that has to be caught by the tree walking itself.
 *
 * The reverse rule has teeth too (see
 * [device-local prefs are never keyed on the sync revision]): a DECODER or
 * buffer pref keyed on the revision would drag the other TV's answer onto this
 * box, which is why [PrefsPayloadBuilder.EXCLUDED_PREF_KEYS] exists and why
 * keying everything would not be a fix.
 *
 * The scan can only see reads that are REMEMBERED (it cannot tell a composable's
 * own read from an event handler's), so the surfaces that read a synced pref
 * PLAIN are pinned by name: a plain read is not a reactive read either.
 */
class SyncedPrefComposeReadsContractTest {

    // ── The general rule: a synced read is never a bare remember ─────────────

    @Test
    fun `no Compose read of a synced pref is remembered without the sync revision`() {
        val payloads = readSource(SYNC_PREFS_PAYLOAD)
        val synced = setLiterals(payloads, "internal val SYNCED_PREF_KEYS = setOf(")
        assertTrue(
            "the synced key set must have been parsed, not silently empty: $synced",
            synced.size > 40
        )
        val keyOf = prefsKeyByGetter(readSource(APP_PREFERENCES))
        val governed = keyOf.filterValues { it in synced }.keys
        assertTrue(
            "each getter must resolve to the key it reads, or the rule has " +
                "nothing to walk",
            governed.size > 40
        )

        val bare = mutableSetOf<String>()
        sourceFiles().forEach { file ->
            val src = file.readText()
            governed.forEach { getter ->
                Regex("AppPreferences\\.$getter\\(").findAll(src).forEach { read ->
                    val rememberKeys = rememberKeysBefore(src, read.range.first) ?: return@forEach
                    if (rememberKeys.lowercase().contains("revision")) return@forEach
                    bare += "${file.name}:$getter"
                }
            }
        }

        assertEquals(
            "a synced pref read must be keyed on PrefsPayloadApplier.revision - " +
                "a bare remember keeps the value it read at first composition, " +
                "so a change made on another device (or pulled in live) never " +
                "reaches the screen that is already open",
            ALLOWED_BARE_SEEDS,
            bare
        )
    }

    /**
     * Deliberate exceptions, each with the reason it is one. Kept EQUAL to what
     * the scan finds, so an exception that is no longer needed fails here
     * rather than sitting in the list as camouflage for a real bare read.
     */
    private val ALLOWED_BARE_SEEDS = setOf(
        // The colour mixer's SEED, not the applied accent: the bars are an edit
        // in progress, so they are seeded once from where the viewer left off.
        // A live change re-seeding them would yank the sliders out from under a
        // viewer mid-mix. The applied accent beside it IS keyed (selectedIndex),
        // and the theme mirrors repaint on their own.
        "SettingsScreen.kt:getCustomAccent"
    )

    // ── The teeth: device-local prefs stay device-local ─────────────────────

    @Test
    fun `device-local prefs are never keyed on the sync revision`() {
        val payloads = readSource(SYNC_PREFS_PAYLOAD)
        val excluded = setLiterals(payloads, "internal val EXCLUDED_PREF_KEYS = setOf(")
        assertTrue(
            "the excluded key set must have been parsed: $excluded",
            excluded.size > 10
        )
        val keyOf = prefsKeyByGetter(readSource(APP_PREFERENCES))
        val deviceLocal = keyOf.filterValues { it in excluded }.keys
        assertTrue(deviceLocal.isNotEmpty())

        val dragged = mutableSetOf<String>()
        sourceFiles().forEach { file ->
            val src = file.readText()
            deviceLocal.forEach { getter ->
                Regex("AppPreferences\\.$getter\\(").findAll(src).forEach { read ->
                    val rememberKeys = rememberKeysBefore(src, read.range.first) ?: return@forEach
                    if (rememberKeys.lowercase().contains("revision")) {
                        dragged += "${file.name}:$getter"
                    }
                }
            }
        }

        assertEquals(
            "these describe what THIS box can decode or display, so the other " +
                "TV's answer must never move them: keying one on the revision " +
                "would select a decoder this device may not have",
            emptySet<String>(),
            dragged
        )
    }

    // ── The shared surfaces, pinned by name ─────────────────────────────────

    @Test
    fun `the shared card composables read their synced prefs through the revision`() {
        // These are the tiles every grid and rail draws, and their pref reads
        // are plain (not remembered) in the original: a plain read is not a
        // reactive read either, so each of them has to collect the revision to
        // repaint when a display pref changes on another device.
        listOf(
            POSTER_CARD to "fun PosterCard(",
            LANDSCAPE_CARD to "fun LandscapeCard(",
            POSTER_CAPTIONS to "fun PosterCaptions(",
            POSTER_BORDER to "fun posterBorderModifier()",
            POSTER_EDGE to "fun posterEdgeShape()"
        ).forEach { (path, header) ->
            val body = functionBody(readSource(path), header)
            assertTrue(
                "$header draws a tile that reads a synced pref (the eye badge, " +
                    "the captions, the edge), so it must collect the revision: $body",
                body.contains("PrefsPayloadApplier.revision")
            )
            assertTrue(
                "and cache its read on it, so the tile repaints on a real " +
                    "change instead of at the next rebuild",
                body.contains("remember(syncedRevision)")
            )
        }
    }

    @Test
    fun `the surfaces that read a synced pref PLAIN collect the revision too`() {
        // A plain read is no more reactive than a bare `remember`: Compose
        // recomposes on observed state, and a SharedPreferences lookup is not
        // state either way. The scan above can only see the REMEMBERED seeds
        // (it cannot tell a composable's own read from a handler's), so the
        // plain-read surfaces are pinned here by name instead - the folder
        // hero's trailer controls and the folder grid's landscape choice, whose
        // siblings elsewhere in the same file already gate on the revision.
        listOf(
            KB_FOLDER_LAYOUTS to "private fun FolderHero(",
            KB_FOLDER_LAYOUTS to "private fun FollowHomeLayout(",
            KB_FOLDER_LAYOUTS to "private fun GridLayout("
        ).forEach { (path, header) ->
            val body = functionBody(readSource(path), header)
            assertTrue(
                "$header reads a synced pref, so it has to collect the sync " +
                    "revision to repaint when that pref changes elsewhere: $body",
                body.contains("PrefsPayloadApplier")
            )
            assertTrue(
                "and its remembered read has to be keyed on it",
                body.contains("remember(syncedRevision)") ||
                    body.contains("remember(syncRevision)")
            )
        }
    }

    @Test
    fun `the search rail toggles are keyed on the revision and the settings controls are too`() {
        val search = readSource(SEARCH_SCREEN)
        assertTrue(
            "both search rail toggles are synced prefs, so the screen has to " +
                "collect the revision",
            search.contains("PrefsPayloadApplier.revision")
        )
        assertTrue(
            "and key the remembered reads on it",
            search.contains("remember(syncedRevision)")
        )

        // The settings screen is the surface a synced pref is most likely to be
        // observed landing ON, so it is the one that must not be frozen: the
        // switches it shows are mirrors of the account's values. The counts are
        // deliberately loose - the rule above is what has teeth - but a screen
        // that stopped keying its controls entirely would slip past a
        // contains() check, so the number of keyed seeds is asserted too.
        val settings = readSource(SETTINGS_SCREEN)
        val keyed = Regex("remember\\(syncedRevision\\)").findAll(settings).count()
        assertTrue(
            "the settings controls are mirrors of the account's prefs: $keyed of " +
                "them are keyed on the sync revision, which is far too few",
            keyed >= 40
        )
        assertTrue(
            "and the revision itself is collected once for the screen",
            settings.contains("PrefsPayloadApplier\n        .revision.collectAsStateWithLifecycle()")
        )
    }

    // ── Source scanning ─────────────────────────────────────────────────────

    /** The `remember(…)` keys of the seed enclosing a read at [index], or null. */
    private fun rememberKeysBefore(source: String, index: Int): String? {
        val pre = source.substring((index - 400).coerceAtLeast(0), index)
        return REMEMBER_SEED.findAll(pre).lastOrNull()?.groupValues?.get(1)
    }

    /** [marker] through the first following [end] marker. */
    private fun slice(source: String, marker: String, end: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val stop = source.indexOf(end, start + marker.length)
        assertTrue("end marker not found after $marker: $end", stop > start)
        return source.substring(start, stop)
    }

    /** A top-level function's body: [header] up to the next column-0 brace. */
    private fun functionBody(source: String, header: String): String =
        slice(source, header, "\n}\n")

    private fun setLiterals(source: String, marker: String): Set<String> {
        val start = source.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val end = source.indexOf("\n    )", start)
        assertTrue("the set literal never closes after $marker", end > start)
        return Regex("\"([a-z0-9_]+)\"")
            .findAll(source.substring(start, end))
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Each `get…` accessor and the pref key it reads, from the `KEY_*` constant
     * in its own body (the next accessor bounds it, so one getter's constant
     * cannot be read as another's).
     */
    private fun prefsKeyByGetter(source: String): Map<String, String> {
        val consts = Regex("const val (KEY_[A-Z0-9_]+) = \"([a-z0-9_]+)\"")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val getters = Regex("fun (get[A-Za-z0-9]+)\\(").findAll(source).toList()
        val out = mutableMapOf<String, String>()
        getters.forEachIndexed { i, getter ->
            val bodyEnd = getters.getOrNull(i + 1)?.range?.first ?: source.length
            val key = Regex("KEY_[A-Z0-9_]+")
                .find(source.substring(getter.range.last, bodyEnd))
                ?.value
                ?: return@forEachIndexed
            consts[key]?.let { out[getter.groupValues[1]] = it }
        }
        return out
    }

    private fun sourceFiles(): List<File> = File(mainRoot(), PACKAGE)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun readSource(path: String): String {
        val file = File(mainRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun mainRoot(): File {
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

    private companion object {
        /** `by remember(keys) {` / `= remember(keys) {` and the keys it captured. */
        val REMEMBER_SEED = Regex("(?:by|=)\\s*remember(?:\\(([^()]*)\\))?\\s*\\{")

        const val PACKAGE = "com/kennyb1201/kbstream"
        const val SYNC_PREFS_PAYLOAD = "$PACKAGE/data/sync/SyncPrefsPayload.kt"
        const val APP_PREFERENCES = "$PACKAGE/data/settings/AppPreferences.kt"
        const val POSTER_CARD = "$PACKAGE/ui/components/PosterCard.kt"
        const val LANDSCAPE_CARD = "$PACKAGE/ui/components/LandscapeCard.kt"
        const val POSTER_CAPTIONS = "$PACKAGE/ui/components/PosterCaptions.kt"
        const val POSTER_BORDER = "$PACKAGE/ui/components/PosterBorder.kt"
        const val POSTER_EDGE = "$PACKAGE/ui/components/PosterEdge.kt"
        const val KB_FOLDER_LAYOUTS = "$PACKAGE/ui/kb/KBFolderLayouts.kt"
        const val SEARCH_SCREEN = "$PACKAGE/ui/search/SearchScreen.kt"
        const val SETTINGS_SCREEN = "$PACKAGE/ui/settings/SettingsScreen.kt"
    }
}
