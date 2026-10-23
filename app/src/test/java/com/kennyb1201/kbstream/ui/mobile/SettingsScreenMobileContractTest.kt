package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings' turn at Phase 2's rule: one `FormFactor` branch in the TV file, the
 * phone layout in a new tv-free file, and the same `AppPreferences` keys behind
 * both.
 *
 * The pane IA is the TV's own (`SettingsPane` / `SettingsGroup`), so the test
 * also pins that every pane with phone content has a branch and that the one
 * pane deliberately left informational is not offered as a destination.
 */
class SettingsScreenMobileContractTest {

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

    private fun settingsScreen(): String =
        source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")

    private fun mobileSettings(): String =
        source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    /**
     * How many key cards the caller's (already whitespace-squashed) source
     * composes. The call form is `KeyCardMobile( title = ...`; the composable's
     * own definition declares `title: String`, so it is not one of them.
     */
    private fun keyCardCalls(source: String): Int =
        source.split("KeyCardMobile( title =").size - 1

    @Test
    fun `settings dispatch on the form factor at the entry point`() {
        val screen = settingsScreen()
        val dispatch = screen.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = screen.indexOf("SettingsScreenMobile(")
        assertTrue("SettingsScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call SettingsScreenMobile", mobileCall > dispatch)
        val branch = screen.substring(dispatch, mobileCall + 420)
        listOf(
            "onBack = onBack",
            "onOpenAddons = onOpenAddons",
            "onOpenSimkl = onOpenSimkl",
            "onOpenProfiles = onOpenProfiles",
            "onOpenCatalogManager = onOpenCatalogManager",
            "onOpenCatalogBuilder = onOpenCatalogBuilder",
            "onOpenStats = onOpenStats"
        ).forEach { callback ->
            assertTrue("the branch must forward $callback", branch.contains(callback))
        }
        assertTrue(
            "the branch must return before the TV rail composes",
            branch.contains("return")
        )
        assertTrue(
            "the TV page must still own its panes",
            screen.substringAfter("SettingsScreenMobile(").contains("SettingsPane.INTEGRATIONS")
        )
    }

    @Test
    fun `the mobile settings page is built on the touch twins`() {
        val mobile = mobileSettings()
        assertTrue("the page must apply MobileTheme", mobile.contains("MobileTheme {"))
        listOf(
            "ui.components.touch.KBButton",
            "ui.components.touch.KBCard",
            "ui.components.touch.KBTextField",
            "ui.components.touch.KBPasteChip",
            "ui.components.touch.KBDialogPanel"
        ).forEach { widget ->
            assertTrue("the page must use $widget", mobile.contains(widget))
        }
        assertFalse(
            "the mobile settings page must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the phone keeps the tv pane IA and offers every pane with content`() {
        val mobile = mobileSettings()
        assertTrue(
            "the grouped nav is the TV's own enum",
            mobile.contains("SettingsGroup.entries") &&
                mobile.contains("SettingsPane.entries")
        )
        listOf(
            "SettingsPane.INTEGRATIONS ->",
            "SettingsPane.COLLECTIONS ->",
            "SettingsPane.HIDDEN ->",
            "SettingsPane.PLAYBACK ->",
            "SettingsPane.VIDEO ->",
            "SettingsPane.LANGUAGE ->",
            "SettingsPane.SUBTITLES ->",
            "SettingsPane.INTERFACE ->",
            "SettingsPane.DATA ->",
            "SettingsPane.ABOUT ->",
            "SettingsPane.SYNC ->"
        ).forEach { pane ->
            assertTrue("missing a branch for $pane", mobile.contains(pane))
        }
        assertTrue(
            "every pane is a real destination now - Sync included, since it is ported",
            mobile.contains("val panes = remember { SettingsPane.entries }") &&
                mobile.contains("SettingsPane.SYNC -> SyncPaneMobile()")
        )
        assertTrue(
            "BACK returns to the pane list before leaving Settings",
            mobile.contains("if (pane != null) pane = null else onBack()")
        )
    }

    @Test
    fun `the sync pane reports the same health and offers the same actions`() {
        val mobile = mobileSettings()
        listOf(
            "SupabaseSync.authState",
            "SupabaseSync.lastPullAtMs",
            "SupabaseSync.lastPushAtMs",
            "SupabaseSync.pendingOutboxCount",
            "SupabaseSync.realtimeStatus",
            "SupabaseSync.realtimeChannelCount",
            "SupabaseSync.isSyncing",
            "SupabaseSync.poisonSweepStatus(context)",
            "syncRelativeTime(lastPull)",
            "syncRelativeTime(lastPush)",
            "WatchHistoryDatabase"
        ).forEach { read ->
            assertTrue("the sync pane must read $read", mobile.contains(read))
        }
        listOf(
            "\"ACCOUNT\"",
            "\"PROFILE\"",
            "\"LAST PULL\"",
            "\"LAST PUSH\"",
            "\"PENDING UPLOADS\"",
            "\"REALTIME\"",
            "\"LOCAL DATA\"",
            "\"CLEANUP\""
        ).forEach { row ->
            assertTrue("the phone must keep the TV's sync row $row", mobile.contains(row))
        }
        assertTrue(
            "force full resync is the same call, gated while a sync runs",
            mobile.contains("SupabaseSync.forceFullResync(context)") &&
                mobile.contains("enabled = !syncing")
        )
        assertTrue(
            "the eye-badge clear is still two taps and still confirmed by its result",
            mobile.contains("SupabaseSync.clearInProgressForActiveProfile(context)") &&
                mobile.contains("TAP AGAIN TO CONFIRM")
        )
        assertTrue(
            "diagnostics are built and copied by the same helpers",
            mobile.contains("Diagnostics") && mobile.contains("copyToClipboard(context, report)")
        )
        assertTrue(
            "the relative-time formatter is imported from the TV page, not re-derived",
            mobile.contains("import com.kennyb1201.kbstream.ui.settings.syncRelativeTime")
        )
    }

    @Test
    fun `the numeric choices are chips over the tv option lists`() {
        val mobile = mobileSettings()
        // Same stored values and same option band: the lists, formatters and
        // stepping rule come from AppPreferences, not a phone-only copy.
        listOf(
            "AppPreferences.getNextEpisodePopupPointTenths",
            "AppPreferences.setNextEpisodePopupPointTenths",
            "AppPreferences.getBecauseYouWatchedPointTenths",
            "AppPreferences.setBecauseYouWatchedPointTenths",
            "AppPreferences.getStillThereEpisodes",
            "AppPreferences.setStillThereEpisodes",
            "AppPreferences.getHeroTrailerDelayMs",
            "AppPreferences.setHeroTrailerDelayMs",
            "AppPreferences.END_PANEL_PERCENT_OPTIONS",
            "AppPreferences.endPanelPointLabel",
            "AppPreferences.stepEndPanelPoint",
            "AppPreferences.HERO_TRAILER_DELAY_OPTIONS_MS",
            "AppPreferences.heroTrailerDelayLabel"
        ).forEach { read ->
            assertTrue("the numeric rows must use $read", mobile.contains(read))
        }
        assertTrue(
            "the still-there count offers the TV band (2/3/5/8)",
            mobile.contains("listOf(2L, 3L, 5L, 8L)")
        )
        assertTrue(
            "the point rows are greyed while their panel is off, like the TV row",
            mobile.contains("val dim = if (enabled) 1f else 0.35f") &&
                mobile.contains("if (enabled) onPick(")
        )
    }

    @Test
    fun `the audio tuning and external player are the tv pane's own`() {
        val mobile = mobileSettings()
        listOf(
            "AppPreferences.getAudioDialogueBoost",
            "AppPreferences.setAudioDialogueBoost",
            "AppPreferences.getAudioVolumeBoostDb",
            "AppPreferences.setAudioVolumeBoostDb",
            "PlayerAudioTuning.dialogueLevelText",
            "PlayerAudioTuning.DIALOGUE_MAX",
            "PlayerAudioTuning.VOLUME_OPTIONS"
        ).forEach { read ->
            assertTrue("the audio rows must use $read", mobile.contains(read))
        }
        assertTrue(
            "dialogue boost is a level with minus/plus, not three pills",
            mobile.contains("private fun DialogueBoostMobile") &&
                mobile.contains("(level - 1).coerceAtLeast(0)") &&
                mobile.contains("(level + 1).coerceAtMost(PlayerAudioTuning.DIALOGUE_MAX)")
        )

        listOf(
            "ExternalPlayer.installed(context)",
            "ExternalPlayer.allApps(context)",
            "AppPreferences.getExternalPlayerPackage",
            "AppPreferences.setExternalPlayer(",
            "AppPreferences.getCustomExternalPlayerPackage",
            "AppPreferences.setCustomExternalPlayer(",
            "AppPreferences.getExternalPlayerAsk",
            "AppPreferences.setExternalPlayerAsk(",
            "AppPreferences.getExternalTrustReturn",
            "AppPreferences.setExternalTrustReturn(",
            "AppPreferences.PLAYER_ENGINE_EXTERNAL"
        ).forEach { read ->
            assertTrue("the external-player block must use $read", mobile.contains(read))
        }
        assertTrue(
            "the External engine is not offered on a kids profile, and a stored pick " +
                "falls back to ExoPlayer",
            mobile.contains("kidsProfile && value == AppPreferences.PLAYER_ENGINE_EXTERNAL") &&
                mobile.contains("kidsProfile && playerEngine == AppPreferences.PLAYER_ENGINE_EXTERNAL")
        )
        assertTrue(
            "picking the engine asks which app when there is a choice",
            mobile.contains("externalPlayers.size > 1") &&
                mobile.contains("showExternalPlayerPicker = true")
        )
        assertTrue(
            "a hand-picked app outranks the probe, so a detected chip clears it",
            mobile.contains("AppPreferences.setCustomExternalPlayer(context, null, null)")
        )
    }

    @Test
    fun `mpv output and stream badges use the tv panes' own calls`() {
        val mobile = mobileSettings()
        listOf(
            "AppPreferences.getMpvVoMode",
            "AppPreferences.setMpvVoMode",
            "AppPreferences.MPV_VO_AUTO",
            "AppPreferences.MPV_VO_GPU",
            "AppPreferences.MPV_VO_MEDIACODEC_EMBED",
            "AppPreferences.MPV_VO_GPU_VULKAN",
            "StreamBadgeEngine.getPackUrl",
            "StreamBadgeEngine.importFromUrl",
            "StreamBadgeEngine.hasPack",
            "StreamBadgeEngine.filterCount",
            "StreamBadgeEngine.clearPack",
            "AppPreferences::setBadgesAboveFile"
        ).forEach { read ->
            assertTrue("the pane must use $read", mobile.contains(read))
        }
        assertTrue(
            "the MPV video-output row is only offered where libmpv exists",
            mobile.contains("if (PlayerEngine.isMpvAvailable()) {")
        )
        assertTrue(
            "the badge pack import is a coroutine and reports the imported count",
            mobile.contains("badgeScope.launch {") &&
                mobile.contains("filters active")
        )
    }

    @Test
    fun `the accent mixer uses the theme's palette, index and maths`() {
        val mobile = mobileSettings()
        listOf(
            "KBAccentPalette",
            "AppPreferences.getAccentIndex",
            "AppPreferences.setAccentIndex",
            "AppPreferences.setCustomAccent",
            "kbCustomAccentState",
            "CUSTOM_ACCENT_INDEX",
            "DEFAULT_ACCENT_INDEX",
            "hsvFromArgb(",
            "argbFromHsv(",
            "refreshThemeMirrors(",
            "AccentSliderMobile"
        ).forEach { read ->
            assertTrue("the accent mixer must use $read", mobile.contains(read))
        }
        assertTrue(
            "a slider writes the pref on release, not on every drag frame",
            mobile.contains("onValueChangeFinished = onFinished")
        )
        assertTrue(
            "picking the Custom swatch adopts the mixed colour instead of being a no-op",
            mobile.contains("onClick = {") && mobile.contains("applyCustom()")
        )
    }

    @Test
    fun `tv-only frame-rate controls are not drawn on the phone`() {
        val mobile = mobileSettings()
        // The words may appear in the file's own comment explaining why the
        // card is absent; what must not appear is any code that draws or writes
        // the TV-only control.
        listOf(
            "FrameRateDiagnostics",
            "setMatchFrameRate",
            "getMatchFrameRate",
            "FrameRateDiagnosticRow"
        ).forEach { tvOnly ->
            assertFalse(
                "$tvOnly reports an HDMI panel the phone does not have",
                mobile.contains(tvOnly)
            )
        }
    }

    @Test
    fun `the phone writes the same preference keys as the tv page`() {
        val mobile = mobileSettings()
        listOf(
            "AppPreferences.setMdbListApiKey",
            "AppPreferences.setOmdbApiKey",
            "AppPreferences.setOpensubtitlesApiKey",
            "AppPreferences.setTorboxApiKey",
            "AppPreferences::setAutoFetchSubtitles",
            "AppPreferences::setTorboxLibrarySync",
            "AppPreferences.setPlayerEngine",
            "AppPreferences.setMpvForAnime",
            "AppPreferences.setDvCompatMode",
            "AppPreferences.setConvertP5To81",
            "AppPreferences.setStripHdr10Plus",
            "AppPreferences.setAutoSelectStream",
            "AppPreferences.setUseStreamRanker",
            "AppPreferences.setMaxAutoPlayQuality",
            "AppPreferences.setAutoPlayNext",
            "AppPreferences.setNextEpisodePopup",
            "AppPreferences.setBecauseYouWatched",
            "AppPreferences.setBingeGroupPrefer",
            "AppPreferences.setBingeGroupReuse",
            "AppPreferences.setBingeGroupFallback",
            "AppPreferences.setStillTherePrompt",
            "AppPreferences.setAutoSkipIntro",
            "AppPreferences.setAutoSkipCredits",
            "AppPreferences.setAudioDecoder",
            "AppPreferences.setAudioOutput",
            "AppPreferences.setAudioDownmix",
            "AppPreferences.setDefaultBufferMode",
            "AppPreferences.setEnablePip",
            "AppPreferences.setDefaultAspectRatio",
            "AppPreferences.setPreferredAudioLanguage",
            "AppPreferences.setPreferredSubtitleLanguage",
            "AppPreferences.setSubtitleMode",
            "AppPreferences.setDefaultSubtitleSize",
            "AppPreferences.setDefaultSubtitleBackground",
            "AppPreferences.setDefaultSubtitlePosition",
            "AppPreferences.setCleanSdhCaptions",
            "AppPreferences.setNewEpisodeNotifications",
            "AppPreferences.setLiveReminderNotifications",
            "AppPreferences.setAmoledBlack",
            "AppPreferences.setPureBlackSurface",
            "AppPreferences.setHeroTrailerAutoplay",
            "AppPreferences.setHeroTrailerMuted",
            "AppPreferences.setUse24HourClock",
            "AppPreferences.setHomeRailShowCatalogType",
            "AppPreferences.setHomeRailShowAddonName",
            "AppPreferences.setSearchRailShowCatalogType",
            "AppPreferences.setSearchRailShowAddonName",
            "AppPreferences.setPosterCaptionTitle",
            "AppPreferences.setPosterCaptionYear",
            "AppPreferences.setPosterCaptionRating",
            "AppPreferences.setSpoilerFree",
            "AppPreferences.setPosterSize",
            "AppPreferences.setPosterBorderStrength",
            "AppPreferences.setPosterEdge",
            "AppPreferences.setHomeLandscapeCards",
            "AppPreferences.setLandscapePosters",
            "AppPreferences.setPosterPartialWatchBadge",
            "AppPreferences.setHomeRailHideUpcoming",
            "AppPreferences.setBrowseEnglishOnly"
        ).forEach { write ->
            assertTrue("mobile Settings must write $write", mobile.contains(write))
        }
        assertTrue(
            "the audio option lists are the shared ones, not a second copy",
            mobile.contains("PlayerAudioTuning.AUDIO_OUTPUT_OPTIONS") &&
                mobile.contains("PlayerAudioTuning.DOWNMIX_OPTIONS")
        )
    }

    @Test
    fun `tv-only playback settings are not offered on a phone`() {
        val mobile = mobileSettings()
        val tv = settingsScreen()
        // Two settings exist to drive a TV's HDMI link: display-mode switching
        // (which blanks the panel) and A/V tunnelling into an AVR. Neither has
        // anything to do on a phone, so neither is drawn there - and the stored
        // preference is left untouched, so a profile shared with a TV keeps the
        // TV's own answer.
        listOf(
            "Match Content Frame Rate" to "AppPreferences.setMatchFrameRate",
            "Tunneled Playback" to "AppPreferences.setEnableTunneling"
        ).forEach { (label, write) ->
            assertTrue(
                "the TV page must still offer \"$label\"",
                tv.contains(label) && tv.contains(write)
            )
            assertFalse(
                "the phone page must not offer \"$label\"",
                mobile.contains("label = \"$label\"")
            )
            assertFalse(
                "and must not write $write",
                mobile.contains(write)
            )
        }
        assertTrue(
            "the phone page says why they are absent, so nobody re-adds them",
            mobile.contains("deliberately absent")
        )
    }

    @Test
    fun `the phone keeps the safety rules the tv pane carries`() {
        val mobile = mobileSettings()

        // Kids profile: the key cards are COMPOSED (a key can be entered or
        // replaced there) but never revealed - the phone's field has no reveal
        // chip on any profile - and the add-on door is hidden by the profile's
        // own lock. They used to be dropped entirely, which left a kids profile
        // unable to add or correct a key (reported 2026-10-22).
        assertTrue(
            "the kids rule is still read from the profile's ceiling",
            mobile.contains("activeProfile?.kidsMaxAge != null") &&
                mobile.contains("val keysHidden = activeProfile?.kidsMaxAge != null")
        )
        assertTrue(
            "the key cards are composed, one per credential",
            4 == keyCardCalls(mobile) // was: Regex("KeyCardMobile\\(\\s*\\n\\s*title =").findAll(mobile).count()
        )
        listOf(
            "API keys are hidden on a kids profile",
            "The MDBList key is hidden on a kids profile"
        ).forEach { gone ->
            assertFalse(
                "the old caption is gone with the branch: $gone",
                mobile.contains(gone)
            )
        }
        assertTrue(
            "a locked add-on entry stays hidden",
            mobile.contains("activeProfile?.kidsHideAddons == true")
        )

        // Clear history: cloud first and awaited, then local rows, then watched
        // markers - the TV page's order, which is what stops a pull merge from
        // restoring the rows the wipe just removed.
        val cloud = mobile.indexOf("SupabaseSync.clearWatchStateForActiveProfile()")
        val rows = mobile.indexOf("WatchHistoryRepository(context).clearAll()")
        val markers = mobile.indexOf("clearLocalWatchState(clearSimklAuth = false)")
        assertTrue("the clear must take the cloud half first", cloud >= 0)
        assertTrue("then the local rows", rows > cloud)
        assertTrue("then the watched markers", markers > rows)
        assertTrue(
            "clearing is confirmed before it runs",
            mobile.contains("Clear Continue Watching?") && mobile.contains("confirmClear = true")
        )

        // Hidden titles are enumerated and reversibly restored.
        assertTrue(
            "the hidden pane loads the store on entry",
            mobile.contains("HiddenTitles.ensureLoaded(context)")
        )
        assertTrue(
            "each row restores its own keys",
            mobile.contains("HiddenTitles.unhide(context, entry.keys)")
        )
        assertTrue(
            "and there is one row that restores everything",
            mobile.contains("HiddenTitles.unhideAll(context)")
        )

        // Backup goes through the shared manager and SAF, with a picker-missing
        // fallback rather than a crash.
        assertTrue(
            "export and import use the shared manager",
            mobile.contains("BackupManager.export(context, uri)") &&
                mobile.contains("BackupManager.import(context, uri)")
        )
        assertTrue(
            "a missing picker is reported, not crashed on",
            mobile.contains("No file picker on this device")
        )

        // About: the updater row is the TV one, with the debug/Play gate.
        assertTrue(
            "checking and installing updates go through AppUpdater",
            mobile.contains("AppUpdater.checkForUpdate(context)") &&
                mobile.contains("AppUpdater.downloadAndInstall(context, s)")
        )
        assertTrue(
            "the update row is not offered on a Play or debug build",
            mobile.contains("!BuildConfig.PLAY_BUILD && !BuildConfig.DEBUG")
        )
    }

    @Test
    fun `the mobile quality ceiling names the top tier 4K`() {
        val mobile = mobileSettings()
        val options = mobile
            .substringAfter("label = \"Maximum auto-play quality\"")
            .substringBefore("description =")

        assertTrue(
            "the top tier must read 4K, not its pixel count",
            options.contains("\"4K\" to AutoPlayQuality.CAP_2160")
        )
        assertFalse(
            "2160p must not be offered as the label on a phone",
            options.contains("\"2160p\" to")
        )
        assertTrue(
            "the stored cap is unchanged, so the ranking rule still sees CAP_2160",
            options.contains("AutoPlayQuality.CAP_2160") &&
                options.contains("AutoPlayQuality.CAP_1080") &&
                options.contains("AutoPlayQuality.CAP_720")
        )
    }

    @Test
    fun `the About pane ends on the author credit`() {
        val mobile = mobileSettings()
        val about = mobile
            .substringAfter("private fun AboutPaneMobile()")
            .substringBefore("private fun SyncedToggleMobile(")

        assertTrue(
            "the phone's About pane must carry the TV pane's author credit: $about",
            about.contains("Made with ❤️ by Kenny B")
        )
        assertTrue(
            "centred and muted, as on the TV",
            about.contains("textAlign = TextAlign.Center") &&
                about.contains("KBTextLo.copy(alpha = 0.7f)")
        )
        assertTrue(
            "and the TV pane must keep the same line, so the two cannot drift",
            settingsScreen().contains("Made with ❤️ by Kenny B")
        )
    }
}
