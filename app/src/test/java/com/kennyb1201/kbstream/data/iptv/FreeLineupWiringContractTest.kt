package com.kennyb1201.kbstream.data.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free lineup is installed as an EXTRA source, loaded with no primary
 * playlist, and offered from the three doors the spec names.
 *
 * All three are wiring facts about files this JVM test cannot instantiate (an
 * Android ViewModel, two Compose screens per form factor), so they are read from
 * the sources the way this tree's other wiring contracts are - the failure they
 * pin is a plausible one: a future edit that "simplifies" the install by filling
 * the primary playlist slot, or one that drops the extras-only load path and
 * leaves a profile with a lineup it can never show.
 */
class FreeLineupWiringContractTest {

    private val viewModel by lazy { read(VIEW_MODEL) }
    private val tvGuide by lazy { read(GUIDE_TV) }
    private val mobileGuide by lazy { read(GUIDE_MOBILE) }
    private val tvHub by lazy { read(HUB_TV) }
    private val mobileHub by lazy { read(HUB_MOBILE) }

    @Test
    fun `the install never writes the primary playlist slot`() {
        // The extras are the whole point: a profile with its own playlist is
        // untouched, the free channels merge with namespaced ids, and removal
        // returns the profile to its no-playlist state.
        assertTrue(
            "the install must write both extra lists through one edit",
            viewModel.contains("private fun writeSources(extraPlaylistUrls: String, extraEpgUrls: String)")
        )
        val install = viewModel
            .substringAfter("fun installFreeLineup(")
            .substringBefore("fun removeFreeLineup(")
        assertTrue(
            "and it must use FreeLineup's own rules",
            install.contains("FreeLineup.install(")
        )
        assertFalse(
            "the install must never touch the primary playlist slot",
            install.contains("onPlaylistUrlChanged") ||
                install.contains("KEY_PLAYLIST_URL,")
        )
        assertTrue(
            "removal takes both halves out",
            viewModel.contains("FreeLineup.remove(")
        )
    }

    @Test
    fun `an older install's dead guide address is repaired on reconciliation`() {
        // The field case: the previous build installed a guide path that now
        // 404s. The swap has to happen where the lineup state is first read -
        // construction, and again when a profile switch makes a new profile's
        // sources this ViewModel's - so no viewer has to edit a URL.
        assertTrue(
            "the repair delegates to FreeLineup's own rule",
            viewModel.contains("FreeLineup.repairEpgEntry(")
        )
        assertTrue(
            "it writes the blob it repaired",
            viewModel.contains("private fun repairFreeLineupGuideEntry()")
        )
        assertEquals(
            "called at construction and on every profile switch",
            2,
            Regex("\\n\\s+repairFreeLineupGuideEntry\\(\\)").findAll(viewModel).count()
        )
    }

    @Test
    fun `a lineup can be loaded with no primary playlist`() {
        // Extras merge onto a primary, so with no primary the loads have to come
        // from the extras themselves - the free-lineup shape. Without this the
        // install would leave a lineup nothing can read.
        assertTrue(
            "the effective URL falls back to the first extra",
            viewModel.contains("private fun effectiveLineupUrl(): String")
        )
        assertTrue(
            "the network load has an extras-only path",
            viewModel.contains("private suspend fun loadExtrasOnlyLineup(): IptvPlaylist")
        )
        assertTrue(
            "and so does the cold-start cache read",
            viewModel.contains("private suspend fun loadCachedExtrasLineup(): IptvPlaylist?")
        )
        assertTrue(
            "applyPlaylist must not merge (and re-fetch) a lineup that already IS the extras",
            viewModel.contains("val merged = if (_playlistUrl.value.isBlank()) {")
        )
        assertTrue(
            "the restore path reads through the same fallback",
            viewModel.contains("val url = effectiveLineupUrl()")
        )
    }

    @Test
    fun `a failure is honest and stays removable`() {
        assertTrue(
            "the install reports its own outcome instead of assuming success",
            viewModel.contains("_freeLineupFailed.value = loadLineup() == LoadOutcome.FAILED")
        )
        assertTrue(
            "a failed load caches nothing, so the lineup stays MISSING",
            viewModel.contains("if (!hasPlaylist) _playlist.value = null")
        )
        assertTrue(
            "and the install stays in place, removable from the form",
            viewModel.contains("fun removeFreeLineup()")
        )
    }

    @Test
    fun `the three doors all call the one entry point`() {
        listOf(
            "the TV Live TV form" to tvGuide,
            "the phone Live TV form" to mobileGuide,
            "the TV Sports Hub's missing-lineup notice" to tvHub,
            "the phone Sports Hub's missing-lineup notice" to mobileHub
        ).forEach { (door, source) ->
            assertTrue(
                "$door must offer the free lineup through installFreeLineup()",
                source.contains("installFreeLineup")
            )
        }
        assertTrue(
            "the no-playlist state offers it as the primary action",
            tvGuide.contains("WATCH FREE CHANNELS")
        )
        assertTrue(
            "with adding your own playlist kept as the secondary action",
            tvGuide.contains("ADD YOUR PLAYLIST")
        )
        assertTrue(
            "the installed entry is labelled built-in and can be removed",
            tvGuide.contains("FreeLineup.INSTALLED_LABEL") &&
                tvGuide.contains("\"REMOVE\"")
        )
        assertTrue(
            "the phone form says the same thing",
            mobileGuide.contains("FreeLineup.INSTALLED_LABEL") &&
                mobileGuide.contains("\"REMOVE FREE TV\"")
        )
    }

    @Test
    fun `the hub re-reads the lineup when the install finishes`() {
        // A callback, not a delay: the hub's own read comes from the cache the
        // install has just written, so a match can appear in the same visit.
        assertTrue(
            "the TV hub hands the install its retry",
            tvHub.contains("iptvViewModel.installFreeLineup(onReady = onLineupInstalled)")
        )
        assertTrue(
            "and the notice is wired to the hub's own lineup re-read",
            tvHub.contains("onLineupInstalled = viewModel::retryLineup")
        )
        assertTrue(
            "the phone hub does the same",
            mobileHub.contains("iptvViewModel.installFreeLineup(onReady = onLineupInstalled)") &&
                mobileHub.contains("onLineupInstalled = viewModel::retryLineup")
        )
    }

    @Test
    fun `the hub keeps namespacing every non-primary source`() {
        // Numbering by POSITION would renumber the free lineup's channels the
        // moment a primary playlist appeared, breaking favorites and zap history
        // that point at them. The rule is the URL, not the index.
        val hub = read(HUB_VIEW_MODEL)
        assertTrue(
            "the primary is identified by its configured URL",
            hub.contains("val primaryUrl = prefs.getString(KEY_PLAYLIST_URL, \"\").orEmpty()")
        )
        assertTrue(
            "and only that URL's channels stay raw",
            hub.contains(
                "if (primaryUrl.isNotEmpty() && url.equals(primaryUrl, ignoreCase = true))"
            )
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    /**
     * The lineup is US channels only, applied where its cache is WRITTEN.
     *
     * `us.m3u` is a country list that still carries international feeds. The
     * filter has to be a load rule at the one choke point every load of that URL
     * passes through - the built-in install, a refresh, or the same URL pasted
     * into the primary or an extras slot - because that is what makes it
     * unavoidable and what makes a refresh re-apply it. See
     * [FreeLineup.isUsChannel] for the decision itself, which is unit-tested.
     */
    @Test
    fun `the us filter sits where the lineup's cache is written`() {
        val repository = read(REPOSITORY)
        val load = repository
            .substringAfter("suspend fun loadPlaylist(")
            .substringBefore("suspend fun importGuide(")

        assertTrue(
            "the filter keys off the built-in lineup's URL, not the install path",
            load.contains("if (normalizedUrl.equals(FreeLineup.PLAYLIST_URL, ignoreCase = true)) {")
        )
        assertTrue(
            "it drops channels through FreeLineup's own rule",
            load.contains("val kept = parsed.channels.filter(FreeLineup::isUsChannel)")
        )
        assertTrue(
            "and every row written to the cache is the filtered list",
            load.contains("channels = playlist.channels.mapIndexed { index, channel ->")
        )
        assertTrue(
            "kept/dropped counts are logged with the URL redacted, like the " +
                "cache write logs beside them",
            load.contains("\"PLAYLIST US FILTER kept=") &&
                load.contains("Redaction.url(normalizedUrl)")
        )
    }

    /**
     * An install from before the US filter still has its old, unfiltered rows
     * cached - and the filter only runs as a load rewrites that cache, so the
     * update alone cleans nothing. The entry restore reloads the lineup once and
     * remembers it; a reload that did not land leaves the flag unset so the next
     * entry tries again.
     */
    @Test
    fun `an older install is reloaded once through the us filter`() {
        assertTrue(
            "the migration reloads the built-in lineup through the ordinary load path",
            viewModel.contains("loadPlaylistResilient(FreeLineup.PLAYLIST_URL, FreeLineup.NAME)")
        )
        val restore = viewModel
            .substringAfter("restoreJob = viewModelScope.launch {")
            .substringBefore("} catch (t: Throwable)")
        assertTrue(
            "and the cached restore runs it BEFORE reading the cache back",
            restore.indexOf("migrateFreeLineupUsFilter()") in 0 until
                restore.indexOf("loadCachedExtrasLineup()")
        )
        assertTrue(
            "the flag is per profile, in the same store as the playlist config",
            viewModel.contains(
                "const val KEY_FREE_TV_US_FILTER_APPLIED = \"free_tv_us_filter_applied\""
            )
        )
        assertTrue(
            "a reload that did not land leaves the flag unset",
            viewModel.contains("if (reloaded == null) {")
        )
        assertTrue(
            "a fresh install's own load already wrote the filtered cache",
            viewModel.contains("if (!_freeLineupFailed.value) markFreeLineupUsFilterApplied()")
        )
    }

    /** The form says the lineup is US channels only, on both form factors. */
    @Test
    fun `both forms say the lineup is us channels only`() {
        listOf("the TV form" to tvGuide, "the phone form" to mobileGuide).forEach { (form, src) ->
            assertTrue(
                "$form must explain that the built-in lineup is US channels only, so " +
                    "a channel the viewer finds on iptv-org but not here is not a mystery",
                src.contains("US channels only:")
            )
        }
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/iptv/IptvRepository.kt"
        const val GUIDE_TV = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val GUIDE_MOBILE = "com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt"
        const val HUB_TV = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val HUB_MOBILE = "com/kennyb1201/kbstream/ui/mobile/SportsHubScreenMobile.kt"
        const val HUB_VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
    }
}
