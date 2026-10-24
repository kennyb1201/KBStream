package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-playlist Remove: one row per installed source, one tap, reload, gone.
 *
 * The field report is "there was no way to remove a playlist", and the failure
 * these assertions pin is the plausible simplification: a Remove that clears
 * EVERY slot (that is CLEAR's job), one that touches the guide sources (they are
 * not playlists), or one that only edits the field without reloading (the guide
 * keeps the removed channels until something else happens to load).
 *
 * The rules themselves - rows, which entry leaves the blob, what the remaining
 * lines look like - are pinned byte-for-byte by PlaylistSourcesTest; here the
 * wiring between the panel, the ViewModel and the store is read from the
 * sources, the way this tree's other wiring contracts are, because an Android
 * ViewModel and two Compose screens cannot be instantiated in this JVM test.
 */
class PlaylistSourceRemovalWiringContractTest {

    private val viewModel by lazy { read(VIEW_MODEL) }
    private val repository by lazy { read(REPOSITORY) }
    private val tvGuide by lazy { read(GUIDE_TV) }
    private val mobileGuide by lazy { read(GUIDE_MOBILE) }

    /** The primary-removal body, from its signature to the next function. */
    private val removeMain by lazy {
        viewModel.substringAfter("fun removeMainPlaylist()")
            .substringBefore("fun removeExtraPlaylist(")
    }

    /** The extra-removal body. */
    private val removeExtra by lazy {
        viewModel.substringAfter("fun removeExtraPlaylist(url: String)")
            .substringBefore("private fun forgetPlaylistRows(")
    }

    /** The free lineup's removal - the template every removal follows. */
    private val removeFree by lazy {
        viewModel.substringAfter("fun removeFreeLineup()")
            .substringBefore("fun removeMainPlaylist()")
    }

    @Test
    fun `every removal shares one tail - nothing left, or reload`() {
        assertTrue(
            "one shared tail, so tap/reload/gone reads the same whichever source went",
            viewModel.contains("private fun afterSourceRemoval()")
        )
        assertTrue(
            "the free lineup, the primary and one extra all hand off to it",
            removeFree.contains("afterSourceRemoval()") &&
                removeMain.contains("afterSourceRemoval()") &&
                removeExtra.contains("afterSourceRemoval()")
        )
        assertTrue(
            "and the tail is the state reset + reload shape the free lineup had",
            viewModel.substringAfter("private fun afterSourceRemoval()")
                .contains("if (effectiveLineupUrl().isBlank()) {") &&
                viewModel.substringAfter("private fun afterSourceRemoval()")
                    .contains("_playlist.value = null")
        )
        assertTrue(
            "the reload is the same load path LOAD uses",
            viewModel.substringAfter("private fun afterSourceRemoval()")
                .contains("load()")
        )
    }

    @Test
    fun `the primary removal clears its own two slots and nothing else`() {
        assertTrue(
            "the URL and the name, which are the primary source",
            removeMain.contains("_playlistUrl.value = \"\"") &&
                removeMain.contains("_playlistName.value = \"\"")
        )
        assertTrue(
            "and persists them before the reload, so a restart agrees",
            removeMain.contains("saveInputs()")
        )
        assertFalse(
            "the extras are separate sources and must survive it",
            removeMain.contains("_extraPlaylistUrls") ||
                removeMain.contains("onExtraPlaylistUrlsChanged")
        )
        assertFalse(
            "and so are the guide sources - CLEAR remains the whole-form reset",
            removeMain.contains("_epgUrl") ||
                removeMain.contains("_extraEpgUrls")
        )
    }

    @Test
    fun `an extra leaves the blob through the one rule, with the EPG blob untouched`() {
        assertTrue(
            "the blob edit is PlaylistSources' rule, not a second parser here",
            removeExtra.contains("PlaylistSources.removeEntry(")
        )
        assertTrue(
            "one edit, and the guide sources are passed through as they were",
            removeExtra.contains("writeSources(remaining, _extraEpgUrls.value)")
        )
        assertTrue(
            "an unknown URL is a no-op rather than a rewrite",
            removeExtra.contains("if (remaining == _extraPlaylistUrls.value) return")
        )
    }

    @Test
    fun `a removed playlist's own storage is dropped`() {
        assertTrue(
            "cached channels and recorded guide matches - the reads that do not go " +
                "through the configured URL list would otherwise answer for a source " +
                "the profile no longer has",
            viewModel.contains("repository.forgetPlaylist(")
        )
        assertTrue(
            "and the store deletes both inside the DAO's one transaction",
            repository.contains("suspend fun forgetPlaylist(playlistUrl: String)") &&
                repository.contains(
                    "channels = emptyList()"
                )
        )
        assertTrue(
            "the guide at large is re-read after it, without waiting for an import",
            viewModel.contains("_guideRefreshTick.value += 1") &&
                viewModel.substringAfter("private fun forgetPlaylistRows(")
                    .contains("_guideRefreshTick.value += 1")
        )
    }

    @Test
    fun `both panels list the sources and wire their Remove to the ViewModel`() {
        listOf(
            "the TV form" to tvGuide,
            "the phone form" to mobileGuide
        ).forEach { (panel, source) ->
            assertTrue(
                "$panel builds the rows from the same storage the fields edit",
                source.contains("PlaylistSources.rows(") &&
                    source.contains("onRemoveSource = { source ->")
            )
            assertTrue(
                "$panel routes the main source and an extra to their own removals",
                source.contains("if (source.isMain) viewModel.removeMainPlaylist()") &&
                    source.contains("else viewModel.removeExtraPlaylist(source.url)")
            )
        }
    }

    @Test
    fun `the rows sit above the fields, and the free lineup stays out of them`() {
        val tvPanel = tvGuide.substringAfter("private fun SetupPanel(")
            .substringBefore("private fun PlaylistSourceRows(")
        assertTrue(
            "the rows render above the first text field",
            tvPanel.contains("PlaylistSourceRows(sources = sources, onRemove = onRemoveSource)") &&
                tvPanel.indexOf("PlaylistSourceRows(") <
                tvPanel.indexOf("label = \"Playlist URL\"")
        )
        assertTrue(
            "the row list is built by the rule that leaves the free lineup out",
            tvGuide.contains("PlaylistSources.rows(")
        )

        val mobilePanel = mobileGuide.substringAfter("private fun MobileGuideSetup(")
        assertTrue(
            "the phone form lists them in the same place",
            mobilePanel.indexOf("sources.forEach { source ->") <
                mobilePanel.indexOf("label = \"PLAYLIST URL\"")
        )
    }

    @Test
    fun `CLEAR is still the whole-form reset`() {
        assertTrue(
            "untouched: it blanks every field at once, which is why the per-source " +
                "Remove exists instead of the viewer using CLEAR",
            tvGuide.contains("viewModel.onPlaylistUrlChanged(\"\")") &&
                tvGuide.contains("viewModel.onExtraPlaylistUrlsChanged(\"\")") &&
                tvGuide.contains("viewModel.onExtraEpgUrlsChanged(\"\")")
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

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/iptv/IptvRepository.kt"
        const val GUIDE_TV = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val GUIDE_MOBILE = "com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt"
    }
}
