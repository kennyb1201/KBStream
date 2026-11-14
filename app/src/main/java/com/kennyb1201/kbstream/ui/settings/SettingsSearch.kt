package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.compositionLocalOf

/**
 * The settings screen's search index.
 *
 * The rail is a list of eleven panes; this is the list underneath it, of the
 * individual ROWS, so "where is the frame-rate switch" is answerable without
 * remembering which of eleven panes it lives in. A result is navigation only: it
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
 * Entries whose row is drawn on the TV page only.
 *
 * Match Content Frame Rate switches the HDMI panel's display mode and the
 * "Prefer 24 Hz" row refines that switch, so neither has a phone row that could
 * do anything (settings reorg §4.6, D2). The phone's search filters these out.
 */
internal val TV_ONLY_SEARCH_KEYS: Set<String> = setOf(
    "playback.match-frame-rate",
    "playback.prefer-whole-frame-rate",
    // The screensaver is Home's TV overlay: the phone screen draws neither its
    // toggle nor its timeout row, so the phone's results must not name it.
    "interface.tv-screensaver",
    // The Spotlight layout is a TV Home: its rail cards expand from D-pad focus,
    // which a phone does not have, and its rows are drawn in this pane only. The
    // phone Home is unchanged and offers no layout choice at all.
    "interface.home-layout",
    "interface.card-expand-delay",
    "interface.billboard-catalogs",
    "interface.billboard-hold",
    "interface.billboard-trailer",
    // Tunneled playback keeps A/V in sync over an HDMI link into an AVR, which
    // a phone does not have - so the phone draws no row for it (the stored pref
    // is left alone for the TV that shares the profile).
    "video.tunneling"
)

/**
 * Entries whose row is drawn on the phone only (settings reorg §8.7).
 *
 * The TV Detail page does not read the episode-layout key, so the TV page draws
 * no such row - and must not offer it in search either. The cellular data saver
 * is the same shape for a different reason: a television has no metered
 * connection to save, so the switch is the phone's alone.
 */
internal val MOBILE_ONLY_SEARCH_KEYS: Set<String> = setOf(
    "interface.episode-layout",
    "playback.cellular-data-saver"
)

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
            "integrations.who-is-watching",
            SettingsPane.INTEGRATIONS,
            "Who's Watching on Launch",
            "profile picker startup launch last used skip who is watching"
        ),
        SettingSearchEntry(
            ADDONS_ENTRY_KEY,
            SettingsPane.INTEGRATIONS,
            "Add-ons",
            "addon add-on extension repository manifest collection import"
        ),
        // Index order follows composition order (settings reorg §14.2, U3): the
        // pane draws Simkl first and Trakt second, and this list had them the
        // other way round.
        SettingSearchEntry(
            "integrations.simkl",
            SettingsPane.INTEGRATIONS,
            "Simkl",
            "scrobble tracker account connect"
        ),
        SettingSearchEntry(
            "integrations.trakt",
            SettingsPane.INTEGRATIONS,
            "Trakt",
            "scrobble tracker account connect watched history sync"
        ),
        // Beside Trakt in composition order (it is drawn right after it), and
        // registered for the same reason: the row has no key of its own, so it
        // is searched by its label.
        SettingSearchEntry(
            "integrations.bingecat",
            SettingsPane.INTEGRATIONS,
            "BingeCat",
            "bingecat tracker watchlist saved list lists watched history tracking scrobble " +
                "record plays devices link public lists browse import community"
        ),
        // The Public lists section follows the trackers it browses for, and its
        // rows are in Library source order (spec F1). Three entries rather than
        // one, because each is a different browser: Trakt and MDBList share the
        // vendor screen, BingeCat has its own, and saving is a like for the
        // first two and an import for the third. "public lists" finds all
        // three, which is the query the old placement made a hunt.
        SettingSearchEntry(
            "integrations.trakt-public-lists",
            SettingsPane.INTEGRATIONS,
            "Trakt public lists",
            "public lists browse trakt community popular trending search save like library " +
                "discover"
        ),
        SettingSearchEntry(
            "integrations.mdblist-public-lists",
            SettingsPane.INTEGRATIONS,
            "MDBList public lists",
            "public lists browse mdblist top curated official search save like library " +
                "discover"
        ),
        SettingSearchEntry(
            "integrations.bingecat-public-lists",
            SettingsPane.INTEGRATIONS,
            "BingeCat public lists",
            "public lists browse bingecat community search import library discover"
        ),
        // The anime vendors (spec 2026-11-13 F2) browse by USERNAME rather than a
        // directory, but they are rows in the same section and must be findable
        // there: a row the index does not know is visible and unfindable.
        SettingSearchEntry(
            "integrations.anilist-public-lists",
            SettingsPane.INTEGRATIONS,
            "AniList public lists",
            "public lists browse anilist anime username profile copy library discover"
        ),
        SettingSearchEntry(
            "integrations.kitsu-public-lists",
            SettingsPane.INTEGRATIONS,
            "Kitsu public lists",
            "public lists browse kitsu anime username profile copy library discover"
        ),
        SettingSearchEntry(
            "integrations.mal-public-lists",
            SettingsPane.INTEGRATIONS,
            "MyAnimeList public lists",
            "public lists browse myanimelist mal anime username profile copy library " +
                "discover"
        ),
        // Moved to the Subtitles pane (settings reorg §7.1, §14.1). The key stays
        // `integrations.*` because it is the row's identity, not its address; the
        // pane field is what a result groups under and jumps to.
        SettingSearchEntry(
            "integrations.autofetch-subs",
            SettingsPane.SUBTITLES,
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
            "integrations.torbox-cached-only",
            SettingsPane.INTEGRATIONS,
            "Cached sources only",
            "torbox debrid cached uncached cache hide filter source picker list hash " +
                "instant comet only"
        ),
        SettingSearchEntry(
            "integrations.torbox-add-missing",
            SettingsPane.INTEGRATIONS,
            // Cached only (spec S2/D7): the name says what the API does, not
            // what "add missing" promised. The old name stays in the keywords so
            // a viewer searching for the word they remember still finds it.
            "Add cached torrents to TorBox on play",
            "torbox debrid resolve instant cached hash torrent add missing cloud quota " +
                "instant playback"
        ),
        // The account's own cloud FILES in the picker, and the list of them.
        // Two entries because they are two doors: the switch decides whether a
        // matching file is offered, the list is where a viewer looks for a file
        // the picker could not place.
        SettingSearchEntry(
            "integrations.torbox-cloud-files",
            SettingsPane.INTEGRATIONS,
            "TorBox cloud files in stream picker",
            "torbox debrid cloud file files picker episode movie download offer source " +
                "sources my account own"
        ),
        SettingSearchEntry(
            "integrations.torbox-cloud-list",
            SettingsPane.INTEGRATIONS,
            "TorBox cloud files",
            "torbox debrid cloud file files list account cloud download release episode " +
                "open browse"
        ),
        SettingSearchEntry(
            "integrations.badges-above",
            SettingsPane.INTEGRATIONS,
            "Badges above the file name",
            "badge badges position above below source picker file name"
        ),

        // ── Collections & Catalogs ────────────────────────────────────────────
        // Both rows moved here from the Add-ons screen's header, which is now
        // add-on management only. The manager dialog itself is still hosted by
        // Add-ons (it is wired to that screen's view model), but the door is a
        // settings row like any other, so it belongs in the index.
        SettingSearchEntry(
            "collections.home-manager",
            SettingsPane.COLLECTIONS,
            "Home / Collections",
            "collection collections profile import arrange pin hide reorder home rail " +
                "rails manager"
        ),
        SettingSearchEntry(
            "collections.catalog-builder",
            SettingsPane.COLLECTIONS,
            "Catalogs",
            "catalog catalogs build builder rule rules filter filters sort smart custom rail " +
                "create"
        ),

        // ── Playback ──────────────────────────────────────────────────────────
        // The engine chips lead the pane, and had no entry at all: the one row
        // that decides which player opens a title was the pane's least findable
        // setting. Drawn as bare text + chips on both pages (the phone calls it
        // "Engine"), so both call sites carry their own anchor and key.
        SettingSearchEntry(
            "playback.engine",
            SettingsPane.PLAYBACK,
            "Playback engine",
            "engine player exoplayer mpv libmpv external vlc mx player backup decoder " +
                "which player"
        ),
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
            "playback.autoplay-wait",
            SettingsPane.PLAYBACK,
            "Auto-play Wait",
            "autoplay auto play wait seconds delay slow addon source pick best start"
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
        SettingSearchEntry(
            "playback.prefer-whole-frame-rate",
            SettingsPane.PLAYBACK,
            "Prefer 24 Hz Over 23.976 Hz",
            "frame rate refresh hz judder 23.976 24 panel mode fractional " +
                "whole rate"
        ),

        // ── Video & Audio ─────────────────────────────────────────────────────
        // Fourteen rows that were drawn, labelled and reachable by scrolling -
        // and findable by no query at all: the pane the viewer goes to for
        // "why does this sound wrong" had nothing in the index. Order is the
        // pane's own (settings reorg §14.2, U3): audio first, then buffering,
        // then picture. Every one of these is a chip group or a custom row, so
        // each carries an explicit `searchAnchor` at its call site.
        SettingSearchEntry(
            "video.audio-decoder",
            SettingsPane.VIDEO,
            "Audio Decoder",
            "audio decoder codec ffmpeg dts truehd device app priority decoders"
        ),
        SettingSearchEntry(
            "video.audio-output",
            SettingsPane.VIDEO,
            "Audio Output",
            "audio output passthrough decode bitstream pcm receiver avr soundbar"
        ),
        SettingSearchEntry(
            "video.audio-downmix",
            SettingsPane.VIDEO,
            "Audio Downmix",
            "audio downmix stereo surround 5.1 7.1 fold channels matrix"
        ),
        SettingSearchEntry(
            "video.volume-boost",
            SettingsPane.VIDEO,
            "Volume Boost",
            "volume boost gain louder quiet limiter dynamics loudness"
        ),
        SettingSearchEntry(
            "video.network-buffer",
            SettingsPane.VIDEO,
            "Network Buffer",
            "network buffer buffering cache latency buffering stutter spinner stream"
        ),
        SettingSearchEntry(
            "video.tunneling",
            SettingsPane.VIDEO,
            "Tunneled Playback",
            "tunneled tunneling tunnel hdmi avr sync passthrough audio video"
        ),
        SettingSearchEntry(
            "video.pip",
            SettingsPane.VIDEO,
            "Picture-in-Picture",
            "picture in picture pip window floating multitask home leave app"
        ),
        SettingSearchEntry(
            "video.aspect-ratio",
            SettingsPane.VIDEO,
            "Default Aspect Ratio",
            "aspect ratio fit zoom fill 16:9 4:3 stretch crop picture"
        ),

        // ── Language ─────────────────────────────────────────────────────────
        // The pane is one chip grid (audio language only since the subtitle
        // language moved to Subtitles), so it had no entry of its own.
        SettingSearchEntry(
            "language.audio",
            SettingsPane.LANGUAGE,
            "Preferred Audio Language",
            "audio language preferred track dub spoken dialogue subtitle language " +
                "original"
        ),

        // ── Subtitles ────────────────────────────────────────────────────────
        // Auto-fetch is declared with the Integrations rows it used to live
        // with; the pane's own rows follow here, five of them chip groups.
        SettingSearchEntry(
            "subtitles.language",
            SettingsPane.SUBTITLES,
            "Preferred Subtitle Language",
            "subtitle language preferred captions subtitles language track"
        ),
        SettingSearchEntry(
            "subtitles.mode",
            SettingsPane.SUBTITLES,
            "Mode",
            "subtitle mode subtitles off forced on always captions"
        ),
        SettingSearchEntry(
            "subtitles.clean-sdh",
            SettingsPane.SUBTITLES,
            "Clean SDH",
            "clean sdh captions deaf hard of hearing sound effects sdh subtitles"
        ),
        SettingSearchEntry(
            "subtitles.size",
            SettingsPane.SUBTITLES,
            "Default Size",
            "subtitle size subtitle size small normal large text captions font"
        ),
        SettingSearchEntry(
            "subtitles.background",
            SettingsPane.SUBTITLES,
            "Default Background",
            "subtitle background box semi solid text captions backdrop"
        ),
        SettingSearchEntry(
            "subtitles.position",
            SettingsPane.SUBTITLES,
            "Position",
            "subtitle position low mid high captions lift letterbox bottom"
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
        // TV-only, and searchable on the TV page: a viewer who saw the overlay
        // once (or wants it gone) looks for "screensaver", not for the pane it
        // lives in. The phone page draws no such row and filters this key out.
        SettingSearchEntry(
            "interface.tv-screensaver",
            SettingsPane.INTERFACE,
            "TV Screensaver",
            "screensaver screensaver ambient idle daydream burn in slideshow backdrop " +
                "logo timeout home tv"
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
        // The second Home layout and its card dwell (spec: Home second layout -
        // Spotlight). TV only: the phone Home has no rail focus to expand a card
        // from and offers neither row (see TV_ONLY_SEARCH_KEYS).
        SettingSearchEntry(
            "interface.home-layout",
            SettingsPane.INTERFACE,
            "Home layout",
            "home layout spotlight modern billboard hero expanding card preview " +
                "netflix style home screen"
        ),
        SettingSearchEntry(
            "interface.card-expand-delay",
            SettingsPane.INTERFACE,
            "Card expand delay",
            "home card expand delay hover preview spotlight seconds no expand"
        ),
        // The Spotlight hero's controls, the phone's Home-manager set on the TV.
        SettingSearchEntry(
            "interface.billboard-catalogs",
            SettingsPane.INTERFACE,
            "Billboard catalogs",
            "billboard hero catalog feeds trending top picks continue watching " +
                "upcoming spotlight"
        ),
        SettingSearchEntry(
            "interface.billboard-hold",
            SettingsPane.INTERFACE,
            "Billboard hold",
            "billboard hero cycle interval hold seconds rotation spotlight"
        ),
        SettingSearchEntry(
            "interface.billboard-trailer",
            SettingsPane.INTERFACE,
            "Billboard trailer",
            "billboard hero trailer auto manual off spotlight preview"
        ),
        SettingSearchEntry(
            "interface.hide-unreleased",
            SettingsPane.INTERFACE,
            "Hide Unreleased Titles",
            "hide unreleased upcoming not yet aired"
        ),
        // The phone's row verbatim (see MOBILE_ONLY_SEARCH_KEYS): the TV pane
        // draws no data-saver row, so this label has to match the one screen that
        // does.
        SettingSearchEntry(
            "playback.cellular-data-saver",
            SettingsPane.PLAYBACK,
            "Cellular data saver",
            "cellular data saver metered mobile network data plan"
        ),
        SettingSearchEntry(
            "interface.hide-watched-home",
            SettingsPane.INTERFACE,
            "Hide Watched Titles on Home Rails",
            "hide watched finished completed home rail"
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
        // MOBILE ONLY (settings reorg §8.7, §15.2.7): the TV Detail page does
        // not read the key, so the TV pane carries no such row and the TV search
        // must never offer it. Its pane is the same one, so the entry is shared
        // and the surface filter below does the excluding.
        SettingSearchEntry(
            "interface.episode-layout",
            SettingsPane.INTERFACE,
            "Episode layout",
            "episode layout list rail vertical sideways detail season"
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
            "data.viewing-stats",
            SettingsPane.DATA,
            "Viewing stats",
            "stats statistics viewing finished runtime streak episodes movies hours watched " +
                "history"
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

    /**
     * The rows matching [query] (see [filterSettingsSearch]).
     *
     * [kidsAddonLock] drops the one entry that has no row to jump to under the
     * kids profile's add-on lock: the Integrations pane does not compose its
     * Add-ons row in that state, so a result naming it could only switch the
     * pane and burn its whole scroll budget trying to bring an anchor into view
     * that was never attached - a dead-end result, not a jump.
     */
    fun search(
        query: String,
        kidsAddonLock: Boolean = false,
        mobile: Boolean = false
    ): List<SettingSearchEntry> {
        var candidates = entries
        if (kidsAddonLock) candidates = candidates.filterNot { it.key == ADDONS_ENTRY_KEY }
        // One entry per row, keyed once - so a row that exists on only ONE of
        // the two screens is excluded by surface here rather than by a second
        // entry or a renamed label (settings reorg §15.2.6/§15.2.7). A result
        // that names a row the current screen does not draw could only switch
        // the pane and jump nowhere.
        candidates = if (mobile) {
            candidates.filterNot { it.key in TV_ONLY_SEARCH_KEYS }
        } else {
            candidates.filterNot { it.key in MOBILE_ONLY_SEARCH_KEYS }
        }
        return filterSettingsSearch(candidates, query)
    }

    /** The search key of the Integrations "Add-ons" row (see [search]). */
    internal const val ADDONS_ENTRY_KEY = "integrations.addons"
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
