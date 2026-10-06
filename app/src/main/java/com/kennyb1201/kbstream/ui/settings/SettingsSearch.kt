package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.compositionLocalOf

/**
 * The settings screen's search index.
 *
 * The rail is a list of ten panes; this is the list underneath it, of the
 * individual ROWS, so "where is the frame-rate switch" is answerable without
 * remembering which of ten panes it lives in. A result is navigation only: it
 * switches to the row's pane, scrolls the row into view and flashes it. It can
 * never change a setting.
 *
 * Why the catalogue is declared here and not collected as each pane composes:
 * a pane's content only composes while that pane is SELECTED, so a
 * composition-time registry would only ever know the rows of the panes the
 * viewer had already visited - and finding a setting in a pane they have never
 * opened is the whole point. Declaring the rows once also lets the index be
 * unit-tested without a Compose harness, which this module does not carry.
 *
 * Binding: a row is searchable when its own `label` is in this list - the row
 * primitives look their entry up by label, so a new ToggleRow becomes
 * searchable by declaring it here and nowhere else. That is deliberate: a
 * per-call `searchKey` would have to be threaded through ~70 call sites, and
 * the failure mode of a missed one is a row the viewer can see and search can
 * never find. A rewording that drops a row off the index is caught by
 * `SettingsSearchContractTest`, which fails unless every row label declared
 * here also appears in `SettingsScreen.kt`.
 *
 * Two entries are PANES rather than rows: Sync Health and Version and Updates
 * have no row of their own, because those panes are one block of live status.
 * Their keys carry [SECTION_KEY_PREFIX] and they are anchored to the block
 * itself rather than to a row, so the contract test checks them the other way
 * round - the entry has to be attached with `searchAnchor(entryFor(...))`.
 */

/**
 * Key prefix marking an entry that stands for a whole pane, not a row.
 *
 * A section has a label to show in the results list but no row to scroll to or
 * flash, so both the anchoring and the contract test treat it differently.
 */
internal const val SECTION_KEY_PREFIX = "section."

/**
 * One searchable settings row.
 *
 * @property key the row's stable identity; also how the pane-level sections
 * (which have no row of their own) find their anchor.
 * @property pane where the row lives, so a result can switch to it.
 * @property label what the row calls itself - must match the row verbatim.
 * @property keywords extra words the viewer might search for that the label
 * does not contain ("judder" for the frame-rate switch, "debrid" for TorBox).
 * @property anchor attached to the row itself, so a result can scroll it in.
 * Held here rather than by the row so the index can own it: the row is only
 * composed while its pane is open, and the requester has to survive that.
 */
internal class SettingSearchEntry(
    val key: String,
    val pane: SettingsPane,
    val label: String,
    val keywords: String = ""
) {
    val anchor: BringIntoViewRequester = BringIntoViewRequester()

    /**
     * True for a pane-level entry (Sync Health, Version and Updates): a label
     * for the results list, but no row of its own to scroll to and flash.
     */
    val isSection: Boolean get() = key.startsWith(SECTION_KEY_PREFIX)
}

/**
 * The rows matching [query], or an empty list for a blank one.
 *
 * Blank is deliberately NOT "everything": the caller renders the ordinary rail
 * for an empty query, and returning the whole index would put the search
 * results list on screen before the viewer had typed anything.
 *
 * Case-insensitive substring on the label or the keywords. Pure, so the
 * matching rule is pinned by a unit test rather than by a device.
 */
internal fun filterSettingsSearch(
    entries: List<SettingSearchEntry>,
    query: String
): List<SettingSearchEntry> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return entries.filter { entry ->
        entry.label.contains(q, ignoreCase = true) ||
            entry.keywords.contains(q, ignoreCase = true)
    }
}

/**
 * The declared catalogue of searchable rows, and the two lookups the rows use:
 * by label (every ordinary row) and by key (the pane sections that have no row
 * of their own).
 *
 * Scope: the rows the shared row primitives draw (ToggleRow and NavigationRow),
 * plus the two panes that are a single block of live status. A composite card
 * (an API key, the badge importer) or a chip group (subtitle size, language) is
 * not an entry of its own - an unregistered row simply does not appear, rather
 * than appearing and jumping nowhere, and those blocks are one pane away.
 * `SettingsSearchTest` pins the covered panes, so dropping a whole pane's rows
 * is a decision rather than a drift.
 */
internal object SettingsSearchIndex {

    val entries: List<SettingSearchEntry> = listOf(
        // ── Integrations ──────────────────────────────────────────────────────
        SettingSearchEntry(
            "integrations.profiles",
            SettingsPane.INTEGRATIONS,
            "Profiles",
            "profile switch rename viewing who is watching account"
        ),
        SettingSearchEntry(
            "integrations.addons",
            SettingsPane.INTEGRATIONS,
            "Add-ons",
            "addon add-on extension repository manifest collection import"
        ),
        SettingSearchEntry(
            "integrations.simkl",
            SettingsPane.INTEGRATIONS,
            "Simkl",
            "scrobble tracker account connect"
        ),
        SettingSearchEntry(
            "integrations.autofetch-subs",
            SettingsPane.INTEGRATIONS,
            "Auto-fetch Subtitles",
            "subtitle subtitles opensubtitles automatic attach language download"
        ),
        SettingSearchEntry(
            "integrations.torbox-library",
            SettingsPane.INTEGRATIONS,
            "Add TorBox Cloud to Library",
            "torbox debrid cloud my list library torrent"
        ),
        SettingSearchEntry(
            "integrations.badges-above",
            SettingsPane.INTEGRATIONS,
            "Badges above the file name",
            "badge badges position above below source picker file name"
        ),

        // ── Playback ──────────────────────────────────────────────────────────
        SettingSearchEntry(
            "playback.ask-every-time",
            SettingsPane.PLAYBACK,
            "Ask every time",
            "source picker pick ask every time stream"
        ),
        SettingSearchEntry(
            "playback.still-there-as-end",
            SettingsPane.PLAYBACK,
            "Treat coming back as the end",
            "still there resume come back end finished"
        ),
        SettingSearchEntry(
            "playback.anime-mpv",
            SettingsPane.PLAYBACK,
            "Play Anime in MPV",
            "anime mpv engine player"
        ),
        // Dolby Vision's Profile 5 rewrite: drawn in the Playback pane's
        // "Dolby Vision & HDR" cluster, next to Strip HDR10+. The label keeps
        // the row's own spelling, arrow written as an escape, so it stays
        // verbatim with the row it points at.
        SettingSearchEntry(
            "playback.p5-hdr10",
            SettingsPane.PLAYBACK,
            "P5 \u2192 HDR10",
            "dolby vision profile 5 p5 icctp hdr10 convert green purple"
        ),
        SettingSearchEntry(
            "playback.strip-hdr10plus",
            SettingsPane.PLAYBACK,
            "Strip HDR10+",
            "hdr10+ hdr plus strip playback picture"
        ),
        SettingSearchEntry(
            "playback.auto-select-stream",
            SettingsPane.PLAYBACK,
            "Auto-select Stream",
            "auto select stream source best"
        ),
        SettingSearchEntry(
            "playback.stream-ranker",
            SettingsPane.PLAYBACK,
            "Stream Ranker",
            "stream ranker ranking order score sources"
        ),
        SettingSearchEntry(
            "playback.autoplay-next",
            SettingsPane.PLAYBACK,
            "Auto-play Next Episode",
            "autoplay auto play next episode continue binge"
        ),
        SettingSearchEntry(
            "playback.next-episode-popup",
            SettingsPane.PLAYBACK,
            "Next Episode Popup",
            "next episode popup prompt timing countdown"
        ),
        SettingSearchEntry(
            "playback.because-you-watched",
            SettingsPane.PLAYBACK,
            "Because You Watched",
            "because you watched credits recommendation panel"
        ),
        SettingSearchEntry(
            "playback.prefer-binge-group",
            SettingsPane.PLAYBACK,
            "Prefer Binge Group",
            "binge group prefer release pack"
        ),
        SettingSearchEntry(
            "playback.reuse-binge-group",
            SettingsPane.PLAYBACK,
            "Reuse Binge Group",
            "binge group reuse remember"
        ),
        SettingSearchEntry(
            "playback.binge-fallback",
            SettingsPane.PLAYBACK,
            "Binge Fallback",
            "binge group fallback when missing"
        ),
        SettingSearchEntry(
            "playback.still-there",
            SettingsPane.PLAYBACK,
            "Are You Still There",
            "still there idle pause prompt watch"
        ),
        SettingSearchEntry(
            "playback.skip-intros",
            SettingsPane.PLAYBACK,
            "Auto-skip Intros",
            "skip intro intros opening automatically"
        ),
        SettingSearchEntry(
            "playback.skip-credits",
            SettingsPane.PLAYBACK,
            "Auto-skip Credits",
            "skip credits outro ending automatically"
        ),
        SettingSearchEntry(
            "playback.match-frame-rate",
            SettingsPane.PLAYBACK,
            "Match Content Frame Rate",
            "frame rate refresh hz judder match content"
        ),

        // ── Interface ─────────────────────────────────────────────────────────
        SettingSearchEntry(
            "interface.new-episode-notifications",
            SettingsPane.INTERFACE,
            "New Episode Notifications",
            "notification notifications new episode alert"
        ),
        SettingSearchEntry(
            "interface.live-reminders",
            SettingsPane.INTERFACE,
            "Live TV Reminder Alerts",
            "live tv reminder alert notification channel"
        ),
        SettingSearchEntry(
            "interface.amoled",
            SettingsPane.INTERFACE,
            "AMOLED Black",
            "amoled black oled dark theme"
        ),
        SettingSearchEntry(
            "interface.pure-black",
            SettingsPane.INTERFACE,
            "Pure Black Surface",
            "pure black surface oled contrast theme"
        ),
        SettingSearchEntry(
            "interface.hero-trailer-autoplay",
            SettingsPane.INTERFACE,
            "Hero Trailer Autoplay",
            "hero trailer autoplay preview home"
        ),
        SettingSearchEntry(
            "interface.hero-trailer-muted",
            SettingsPane.INTERFACE,
            "Mute Hero Trailers",
            "hero trailer mute muted silent audio"
        ),
        SettingSearchEntry(
            "interface.24h-clock",
            SettingsPane.INTERFACE,
            "24-Hour Clock",
            "clock 24 hour time format"
        ),
        SettingSearchEntry(
            "interface.rail-catalog-type",
            SettingsPane.INTERFACE,
            "Show Catalog Type on Home Rails",
            "home rail catalog type label show"
        ),
        SettingSearchEntry(
            "interface.rail-addon-name",
            SettingsPane.INTERFACE,
            "Show Addon Name on Home Rails",
            "home rail addon name label show"
        ),
        SettingSearchEntry(
            "interface.search-rail-catalog-type",
            SettingsPane.INTERFACE,
            "Show Catalog Type on Search Rails",
            "search rail catalog type label show"
        ),
        SettingSearchEntry(
            "interface.search-rail-addon-name",
            SettingsPane.INTERFACE,
            "Show Addon Name on Search Rails",
            "search rail addon name label show"
        ),
        SettingSearchEntry(
            "interface.poster-titles",
            SettingsPane.INTERFACE,
            "Poster Titles",
            "poster title titles caption card"
        ),
        SettingSearchEntry(
            "interface.poster-years",
            SettingsPane.INTERFACE,
            "Poster Years",
            "poster year years caption card"
        ),
        SettingSearchEntry(
            "interface.poster-ratings",
            SettingsPane.INTERFACE,
            "Poster Star Ratings",
            "poster star rating ratings score caption card"
        ),
        SettingSearchEntry(
            "interface.spoiler-free",
            SettingsPane.INTERFACE,
            "Spoiler-free Episodes",
            "spoiler free episodes thumbnails still"
        ),
        SettingSearchEntry(
            "interface.hide-unreleased",
            SettingsPane.INTERFACE,
            "Hide Unreleased Titles",
            "hide unreleased upcoming not yet aired"
        ),
        SettingSearchEntry(
            "interface.english-only",
            SettingsPane.INTERFACE,
            "English-Only Browse & Discover",
            "english only language browse discover filter"
        ),
        SettingSearchEntry(
            "interface.landscape-home",
            SettingsPane.INTERFACE,
            "Landscape Cards on Home Rails",
            "landscape cards home rail wide backdrop"
        ),
        SettingSearchEntry(
            "interface.landscape-everywhere",
            SettingsPane.INTERFACE,
            "Landscape Posters Everywhere",
            "landscape posters everywhere wide backdrop card"
        ),
        SettingSearchEntry(
            "interface.eye-badge",
            SettingsPane.INTERFACE,
            "Eye Badge for In-Progress Shows",
            "eye badge in progress resume started watching"
        ),

        // ── Data & Backup ─────────────────────────────────────────────────────
        SettingSearchEntry(
            "data.export-backup",
            SettingsPane.DATA,
            "Export Backup",
            "backup export save file settings addons watched"
        ),
        SettingSearchEntry(
            "data.import-backup",
            SettingsPane.DATA,
            "Import Backup",
            "backup import restore file settings addons watched"
        ),
        SettingSearchEntry(
            "data.clear-continue-watching",
            SettingsPane.DATA,
            "Clear Continue Watching",
            "clear reset resume position progress watched history continue watching"
        ),

        // ── Pane sections (no row of their own: anchored by key) ──────────────
        SettingSearchEntry(
            "${SECTION_KEY_PREFIX}sync-health",
            SettingsPane.SYNC,
            "Sync Health",
            "sync cloud account profile pull push uploads realtime cleanup force resync"
        ),
        SettingSearchEntry(
            "${SECTION_KEY_PREFIX}about-updates",
            SettingsPane.ABOUT,
            "Version and Updates",
            "about version build update updater check release"
        )
    )

    private val byKey: Map<String, SettingSearchEntry> =
        entries.associateBy { entry -> entry.key }

    private val byLabel: Map<String, SettingSearchEntry> =
        entries.associateBy { entry -> entry.label }

    /** The section entry for [key], or null when the key is not indexed. */
    fun entryFor(key: String): SettingSearchEntry? = byKey[key]

    /**
     * The row entry for a row that calls itself [label], or null when that row
     * is not searchable (diagnostics, sections, one-off cards).
     */
    fun entryForLabel(label: String): SettingSearchEntry? = byLabel[label]

    /** The rows matching [query] (see [filterSettingsSearch]). */
    fun search(query: String): List<SettingSearchEntry> =
        filterSettingsSearch(entries, query)
}

/** How many rows the rail lists for one query before it stops. */
internal const val SETTINGS_SEARCH_MAX_RESULTS = 30

/** How long the jumped-to row stays highlighted after a search jump. */
internal const val SETTINGS_SEARCH_FLASH_MS = 1_200L

/**
 * How long a jump keeps asking for its row to be scrolled into view.
 *
 * A fixed handful of frames was not enough on a cold pane: the freshly selected
 * pane has not composed when the jump runs, and BringIntoViewRequester is a
 * no-op until its node is attached, so a slow pane swallowed the whole budget,
 * switched the pane and never scrolled. Time, not a frame count, is the honest
 * bound - a frame is not a fixed amount of work.
 */
internal const val SETTINGS_SEARCH_SCROLL_BUDGET_MS = 250L

/**
 * The entry currently being flashed after a search jump, or null.
 *
 * A CompositionLocal rather than a parameter on every row: the flash is a
 * screen-level event and threading it through ~70 call sites would be noise.
 * Rows compare it by IDENTITY against their own entry, so only the jumped-to
 * row highlights.
 */
internal val LocalFlashingSetting = compositionLocalOf<SettingSearchEntry?> { null }
