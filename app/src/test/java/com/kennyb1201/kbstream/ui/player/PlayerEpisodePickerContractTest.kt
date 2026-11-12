package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player season + episode picker, read out of the source.
 *
 * The player's only way to reach another episode used to be the Up Next
 * countdown, which knows one pair. This spec adds a list - a season switcher and
 * one row per episode, with a still and a short description - and wires a pick
 * into the launch that already exists, on both engines.
 *
 * Source-reading, like the rest of this module's contracts: the panel lives in
 * two Android-bound activities and its data comes from a shared loader.
 */
class PlayerEpisodePickerContractTest {

    private val chromeXml by lazy { resource("layout/player_chrome.xml") }
    private val rowXml by lazy { resource("layout/picker_item.xml") }
    private val chrome by lazy { source(PLAYER_CHROME) }
    private val exo by lazy { source(EXO) }
    private val mpv by lazy { source(MPV) }
    private val adapter by lazy { source(PICKER_ADAPTER) }
    private val loader by lazy { source(PLAYER_EPISODES) }
    private val rows by lazy { source(PLAYER_EPISODE_PICKER) }

    // ── F1: the button, series only ────────────────────────────────────────

    @Test
    fun `the chrome offers Episodes to a series and nothing else`() {
        assertTrue(
            "F1: the bar carries an Episodes entry, next to the picker entries",
            chromeXml.contains("android:id=\"@+id/chrome_btn_episodes\"")
        )
        assertTrue(
            "F1: and it starts hidden, so a movie never flashes it",
            chromeXml.substringAfter("chrome_btn_episodes")
                .substringBefore("/>")
                .contains("android:visibility=\"gone\"")
        )
        assertTrue(
            "F1: the shared chrome owns the gate",
            chrome.contains("fun setEpisodesAvailable(available: Boolean)")
        )
        assertTrue(
            "F1: the press goes through the host, like every other button",
            chrome.contains("host.onChromeOpenEpisodes()") &&
                chrome.contains("fun onChromeOpenEpisodes()")
        )
        assertTrue(
            "F1: a series shows it; a movie and a live channel do not",
            exo.contains(
                "chrome?.setEpisodesAvailable(!isLiveChannel && parentType.equals(\"series\", ignoreCase = true))"
            )
        )
        assertTrue(
            "F1: MPV is the same rule - it has no isLiveChannel field, so the " +
                "channel type is read directly",
            mpv.contains("parentType != \"channel\" && parentType.equals(\"series\", ignoreCase = true)")
        )
        assertTrue(
            "F1: both engines open it from the chrome",
            exo.contains("override fun onChromeOpenEpisodes() = showEpisodePicker()") &&
                mpv.contains("override fun onChromeOpenEpisodes() = showEpisodePicker()")
        )
    }

    @Test
    fun `both engines open the one episode panel`() {
        listOf(exo to "Exo", mpv to "MPV").forEach { (source, engine) ->
            val open = source.indexOf("private fun showEpisodePicker()")
            assertTrue("F2: $engine opens the episode panel", open >= 0)
            val body = source.substring(open).substringBefore("private fun requestEpisodeSeasons")
            assertTrue(
                "F2: $engine keeps the panel to a series",
                body.contains("series\", ignoreCase = true") || body.contains("parentType == \"series\"")
            )
            assertTrue(
                "F2: $engine builds rows BEFORE anything becomes visible (the picker's " +
                    "own empty-panel rule)",
                body.contains("openPickerPanel(episodePickerItems())")
            )
            assertTrue(
                "F2: $engine re-binds the same panel for updates rather than growing a " +
                    "second one",
                source.contains("private fun bindEpisodePicker()") &&
                    source.contains("pickerList.adapter = PickerAdapter(items)") ||
                    source.contains("pickerList?.adapter = PickerAdapter(items)")
            )
        }
    }

    // ── F2: the rows ───────────────────────────────────────────────────────

    @Test
    fun `the panel opens on the playing season with that episode selected and in view`() {
        assertTrue(
            "F2: the season default is the one playing",
            exo.contains("episodePickerSeason = episodePickerSeason ?: season") &&
                mpv.contains("episodePickerSeason = episodePickerSeason ?: season")
        )
        assertTrue(
            "F2: the playing episode is the selected row",
            rows.contains("val isCurrent = listedSeason != null &&") &&
                rows.contains("playingSeason == listedSeason") &&
                rows.contains("playingEpisode == episode.episodeNumber") &&
                rows.contains("isSelected = isCurrent")
        )
        listOf(exo to "Exo", mpv to "MPV").forEach { (source, engine) ->
            val at = source.indexOf("private fun episodeCurrentRowIndex()")
            assertTrue("F2: $engine knows where the playing row is", at >= 0)
            assertTrue(
                "F2: $engine scrolls the panel to it on open",
                source.contains("if (currentAt >= 0) pickerList.scrollToPosition(currentAt)") ||
                    source.contains("if (currentAt >= 0) pickerList?.scrollToPosition(currentAt)")
            )
        }
    }

    @Test
    fun `every episode row carries its still, its title and a capped description`() {
        assertTrue(
            "F2: the still is the episode's own thumbnail, with EpisodeCard's " +
                "title-art fallback",
            rows.contains("thumbnail = episode.thumbnail") &&
                rows.contains("fallbackImageUrl = fallbackImageUrl") &&
                adapter.contains("val url = item.thumbnail?.takeIf { it.isNotBlank() }") &&
                adapter.contains("?: item.fallbackImageUrl?.takeIf { it.isNotBlank() }")
        )
        assertTrue(
            "F2: a still that fails to load drops the slot instead of leaving a broken box",
            adapter.contains("onError = { _, _ -> holder.thumb.visibility = View.GONE }")
        )
        assertTrue(
            "F2: the row's title is S<n>E<n> plus the episode's name",
            rows.contains("append(episodeLabel(listedSeason ?: selectedSeason ?: 0, episode.episodeNumber))") &&
                rows.contains("append(name)")
        )
        assertTrue(
            "F2: the overview is the row's description",
            rows.contains("description = episode.overview")
        )
        assertTrue(
            // Three lines, not two: the 2026-11-12 layout spec raises the cap by
            // one now that the panel is wider - still a panel, not a second
            // Detail page, which is what the cap is for.
            "F2: capped at three lines with an ellipsis - a panel, not a second Detail page",
            rowXml.contains("android:id=\"@+id/picker_item_desc\"") &&
                rowXml.substringAfter("picker_item_desc")
                    .substringBefore("</TextView>")
                    .contains("android:maxLines=\"3\"") &&
                rowXml.substringAfter("picker_item_desc")
                    .substringBefore("</TextView>")
                    .contains("android:ellipsize=\"end\"")
        )
        assertTrue(
            "F2: an episode with no overview leaves the slot out, never blank",
            adapter.contains("view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE")
        )
        assertTrue(
            "F2: a watched episode carries the checkmark, and an unaired one the " +
                "UNAVAILABLE chip",
            rows.contains("watched = watched.contains(episode.episodeNumber)") &&
                adapter.contains("\"\\u2713 ${'$'}{item.label}\"") &&
                adapter.contains("internal const val UNAVAILABLE_LABEL = \"UNAVAILABLE\"")
        )
    }

    // ── F3: one loader, cached ─────────────────────────────────────────────

    @Test
    fun `one loader serves both engines`() {
        assertTrue(
            "F3: the shared loader exists",
            loader.contains("internal object PlayerEpisodes")
        )
        assertTrue(
            "F3: it reads the season list the same way Detail does - the repository's " +
                "own season listing",
            loader.contains("repository.getSeasonEpisodes(tmdbId, season, parentId)") &&
                loader.contains("namedEpisodesOnly()")
        )
        assertTrue(
            "F3: seasons come from the enriched meta the player already fetches",
            loader.contains("fetchEnrichedMetaCached(parentId, parentType)") &&
                loader.contains("summary.seasonNumber")
        )
        listOf(exo to "Exo", mpv to "MPV").forEach { (source, engine) ->
            val load = source.substringAfter("private fun loadEpisodeSeason")
                .substringBefore("private fun selectEpisodeSeason")
            assertTrue(
                "F3: $engine's season load asks the loader, not a repository of its own",
                load.contains("PlayerEpisodes.season(") &&
                    load.contains("PlayerEpisodes.watchedEpisodes(")
            )
        }
        listOf(exo to "Exo", mpv to "MPV").forEach { (source, engine) ->
            assertTrue(
                "F3: $engine asks the one loader",
                source.contains("PlayerEpisodes.seasons(") &&
                    source.contains("PlayerEpisodes.season(")
            )
        }
    }

    @Test
    fun `switching seasons loads the new list and keeps the old rows up`() {
        listOf(exo to "Exo", mpv to "MPV").forEach { (source, engine) ->
            val select = source.indexOf("private fun selectEpisodeSeason(target: Int)")
            assertTrue("F3: $engine has a season chip handler", select >= 0)
            val load = source.indexOf("private fun loadEpisodeSeason(target: Int?)")
            val loadBody = source.substring(load).substringBefore("private fun selectEpisodeSeason")
            assertTrue(
                "F3: $engine marks the fetch in flight while the old rows stay bound",
                loadBody.contains("episodePickerLoading = true") &&
                    loadBody.indexOf("bindEpisodePicker()") <
                    loadBody.indexOf("PlayerEpisodes.season(")
            )
            assertTrue(
                "F3: and a superseded fetch cannot paint over the newer season",
                loadBody.contains("val request = ++episodePickerRequest") &&
                    loadBody.contains("if (request != episodePickerRequest || !")
            )
            assertTrue(
                "F3: the rows say which season they are really from",
                source.contains("episodePickerListedSeason = wanted")
            )
        }
    }

    // ── F4/F2: a pick launches the episode's own pair ──────────────────────

    @Test
    fun `a pick launches the episode's own pair and disarms the countdown`() {
        val exoPick = exo.substringAfter("private fun pickEpisode(episode: ResolvedEpisode)")
            .substringBefore("private fun episodePickerItems()")
        val disarm = exoPick.indexOf("nextUpHandoffArmed = false")
        val launch = exoPick.indexOf("launchNextEpisode(")
        assertTrue("F4: the pick disarms the Up Next countdown", disarm >= 0)
        assertTrue(
            "F4: and does it BEFORE the handoff - a stale tick would chain another launch",
            disarm < launch
        )
        assertTrue(
            "F4: the countdown callbacks are removed with the flag",
            exoPick.contains("nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)")
        )
        assertTrue(
            "F4: it launches through the existing handoff, with the episode's OWN pair " +
                "and never a file number or a list position",
            exoPick.contains("targetSeason,") &&
                exoPick.contains("episode.episodeNumber,") &&
                exoPick.contains("val targetSeason = episodePickerSeason ?: return")
        )
        assertTrue("F4: the picker closes on the pick", exoPick.contains("dismissPicker()"))

        val mpvPick = mpv.substringAfter("private fun pickEpisode(episode: ResolvedEpisode)")
            .substringBefore("private fun episodePickerItems()")
        assertTrue(
            "F4: MPV takes the same steps with its own disarm fields (it has no " +
                "nextUpHandoffArmed - its launch is guarded by the countdown latch)",
            mpvPick.contains("nextUpCountdownHeld = false") &&
                mpvPick.contains("nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)") &&
                mpvPick.indexOf("nextUpCountdownHeld = false") < mpvPick.indexOf("launchNextEpisode(") &&
                mpvPick.contains("episode.episodeNumber,") &&
                mpvPick.contains("dismissPicker()")
        )
    }

    @Test
    fun `an unaired episode is listed but not playable`() {
        assertTrue(
            "F2: the unavailability rule is Detail's own, not a second copy",
            rows.contains("import com.kennyb1201.kbstream.ui.detail.isEpisodeUnavailable") &&
                rows.contains("val unavailable = isEpisodeUnavailable(episode.airDate)")
        )
        assertTrue(
            "F2: the row still draws, and a press on it does nothing",
            rows.contains("onClick = { if (!unavailable) onPick(episode) }") &&
                rows.contains("unavailable = unavailable")
        )
    }

    // ── slices ─────────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun resource(relative: String): String {
        val root = findSourceRoot().parentFile ?: error("res root not found")
        val file = File(root, "res/$relative")
        assertTrue("resource missing: $file", file.isFile)
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
        const val PLAYER_CHROME = "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
        const val EXO = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val PICKER_ADAPTER = "com/kennyb1201/kbstream/ui/player/PickerAdapter.kt"
        const val PLAYER_EPISODES = "com/kennyb1201/kbstream/ui/player/PlayerEpisodes.kt"
        const val PLAYER_EPISODE_PICKER = "com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt"
    }
}
