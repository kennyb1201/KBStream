package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-21 screensaver spec, wired: genres that survive a cold start and
 * the show that now covers Settings too.
 *
 * The module has no Compose harness, so the joins that would fail silently on a
 * Fire Stick are read from the sources: a cache that is written but never
 * seeded, a slide label that waits on the network instead of appearing when it
 * lands, a Settings host with no pool or no gate, and an overlay drawn under
 * the dialogs it is supposed to cover.
 */
class ScreensaverGenrePersistenceContractTest {

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
        throw AssertionError("main source root not found from ${System.getProperty("user.dir")}")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val prefs = source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")
    private val cache =
        source("com/kennyb1201/kbstream/data/settings/ScreensaverGenreCache.kt")
    private val homeVm = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    private val home = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val detail = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
    private val overlay = source("com/kennyb1201/kbstream/ui/home/HomeScreensaver.kt")
    private val settings = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val gate = source("com/kennyb1201/kbstream/ui/settings/SettingsModalGate.kt")
    private val timeoutRow =
        source("com/kennyb1201/kbstream/ui/settings/ScreensaverTimeoutRow.kt")
    private val host = source("com/kennyb1201/kbstream/ui/screensaver/ScreensaverHost.kt")

    @Test
    fun `resolved genres are stored per profile and seeded before the first slide`() {
        assertTrue(
            "one key, read and written through the same profile store every other pref uses",
            prefs.contains("KEY_SCREENSAVER_GENRES = \"screensaver_genres\"") &&
                prefs.contains("fun getScreensaverGenres(context: Context): Map<String, String>") &&
                prefs.contains("decodeScreensaverGenres(prefs(context).getString(") &&
                prefs.contains("fun setScreensaverGenre(context: Context, key: String, genre: String)") &&
                squash(prefs).contains(
                    "putString(KEY_SCREENSAVER_GENRES, encodeScreensaverGenres(next))"
                )
        )
        assertTrue(
            "the cap is the codec's, so the disk cannot grow forever",
            cache.contains("SCREENSAVER_GENRE_CACHE_LIMIT = 400") &&
                squash(cache).contains("while (out.size > limit && out.isNotEmpty())")
        )
        assertTrue(
            "the in-memory map is SEEDED from disk at construction - the cold-start fix",
            squash(homeVm).contains(
                "private val resolvedHeroGenres = androidx.compose.runtime.mutableStateMapOf<String, String>().apply {"
            ) &&
                squash(homeVm).contains(
                    "putAll(AppPreferences.getScreensaverGenres(getApplication()))"
                )
        )
        assertTrue(
            "and a profile switch drops the old profile's lines and seeds the incoming one",
            squash(homeVm).contains("resolvedHeroGenres.clear()") &&
                squash(homeVm).contains(
                    "resolvedHeroGenres.putAll( AppPreferences.getScreensaverGenres(getApplication()) )"
                )
        )
    }

    @Test
    fun `every resolved genre is written through, from both resolve paths`() {
        assertTrue(
            "one write path: memory and disk cannot disagree",
            squash(homeVm).contains(
                "private fun rememberResolvedScreensaverGenre(key: String, genre: String?) {"
            ) &&
                squash(homeVm).contains("resolvedHeroGenres[key] = line") &&
                squash(homeVm).contains(
                    "AppPreferences.setScreensaverGenre(getApplication(), key, line)"
                )
        )
        assertTrue(
            "the hero's own resolve writes through",
            squash(homeVm).contains("rememberResolvedScreensaverGenre( \"\$requestedId:\$requestedType\", screensaverGenre(finalMeta.genres) )")
        )
        assertTrue(
            "and so does the idle wait's pass",
            squash(homeVm).contains("rememberResolvedScreensaverGenre( key, screensaverGenre(detail?.genres?.map { it.name }) )")
        )
    }

    @Test
    fun `a slide drawn with no genre asks once, and the label lands on that slide`() {
        assertTrue(
            "the overlay takes a missing-genre request and keys it on the slide AND its genre",
            squash(overlay).contains("onMissingGenre: (MetaPreview) -> Unit = {},") &&
                squash(overlay).contains("val genre = genreOf(slide)") &&
                squash(overlay).contains("LaunchedEffect(shownCycle, slide.id, slide.type, genre)") &&
                squash(overlay).contains("if (genre == null) onMissingGenre(slide)")
        )
        assertTrue(
            "the lookup is the idle wait's own detail path, silent and cache-first",
            squash(homeVm).contains("internal fun requestScreensaverGenre(item: MetaPreview)") &&
                squash(homeVm).contains("safeSuspend { screensaverDetailOf(item) }")
        )
        assertTrue(
            "bounded to one: a known genre never asks, and a new slide cancels the old request",
            squash(homeVm).contains("if (resolvedHeroGenres[key] != null) return") &&
                squash(homeVm).contains("screensaverGenreJob?.cancel()")
        )
        assertTrue(
            "and never blocks the show - it only writes the snapshot map the slide reads",
            squash(homeVm).contains("screensaverGenreJob = viewModelScope.launch {") &&
                squash(homeVm).contains(
                    "rememberResolvedScreensaverGenre( key, screensaverGenre(detail?.genres?.map { it.name }) )"
                )
        )
        assertTrue(
            "Home asks through the ViewModel",
            squash(home).contains(
                "onMissingGenre = { meta -> viewModel.requestScreensaverGenre(meta) },"
            )
        )
    }

    @Test
    fun `Detail labels its own first slide and reads the persisted map for the rest`() {
        assertTrue(
            "the current title's genres come from the detail already on the page",
            squash(detail).contains(
                "val screensaverCurrentGenre = screensaverGenre(tmdbDetail?.genres?.map { it.name })"
            ) &&
                squash(detail).contains(
                    "\"\${meta.id}:\${meta.type}\" == screensaverCurrentKey"
                )
        )
        assertTrue(
            "the related slides read the same persisted map the idle wait writes",
            squash(detail).contains(
                "AppPreferences.getScreensaverGenres(screensaverContext)"
            ) &&
                squash(detail).contains(
                    "screensaverPersistedGenres[\"\${meta.id}:\${meta.type}\"]"
                )
        )
        assertFalse(
            "and no fetch is started from Detail's overlay",
            squash(detail).contains("onMissingGenre =")
        )
    }

    @Test
    fun `Settings hosts the same show, with Home's pool through the active ceiling`() {
        assertTrue(
            "Settings arms the shared host, with Home's own warm and resolve passes",
            squash(settings).contains("val screensaver = rememberScreensaver(") &&
                squash(settings).contains(
                    "warmPool = { items -> screensaverHomeViewModel.warmScreensaverPool(items) },"
                ) &&
                squash(settings).contains(
                    "screensaverCleanSlides(items) { meta -> screensaverHomeViewModel.screensaverCleanBackdrop(meta.id, meta.type) }"
                )
        )
        assertTrue(
            "the pool is the rails this profile would draw, through its ceiling",
            squash(homeVm).contains(
                "internal suspend fun screensaverPoolForSettings(): List<MetaPreview>"
            ) &&
                squash(homeVm).contains(
                    "screensaverSlides(_rails.value.flatMap { rail -> rail.items })"
                ) &&
                squash(homeVm).contains("tmdbRepository.kidsFilterMetas(pool)")
        )
        // Borrowed from the activity's entry point since spec 2026-11-22, item 3:
        // the same activity-scoped instance, not a second feed.
        assertTrue(
            "the screen borrows the activity-scoped Home view model rather than building a second feed",
            squash(settings).contains("val screensaverHomeViewModel: HomeViewModel = hiltViewModel()") &&
                squash(settings).contains(
                    "screensaverPool = screensaverHomeViewModel.screensaverPoolForSettings()"
                )
        )
        assertTrue(
            "and the dismissing press still lands on the row that held focus",
            squash(settings).contains("captureFocus = { null },") &&
                squash(settings).contains("restoreFocus = {}") &&
                squash(settings).contains(".focusRestorer()") &&
                squash(settings).contains("if (event.type == KeyEventType.KeyDown)") &&
                squash(settings).contains("screensaver.onActivity()")
        )
    }

    @Test
    fun `an open dialog or focused text field holds the gate`() {
        assertTrue(
            "the gate is composed above the rail and the panes",
            squash(settings).contains(
                "CompositionLocalProvider(LocalSettingsModalGate provides settingsModalGate)"
            )
        )
        assertTrue(
            "and is one of the host's blocked inputs",
            squash(settings).contains("blocked = settingsModalGate.blocked ||")
        )
        assertTrue(
            "a dialog announces itself for exactly as long as it is composed",
            gate.contains("internal class SettingsModalGate") &&
                gate.contains("fun holdDialog(key: Any)") &&
                squash(gate).contains("DisposableEffect(gate) {") &&
                squash(gate).contains("onDispose { gate.releaseDialog(key) }")
        )
        assertTrue(
            "every Settings dialog carries the hold - the shared ones too",
            listOf(
                "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt",
                "com/kennyb1201/kbstream/ui/settings/AniListConnectDialog.kt",
                "com/kennyb1201/kbstream/ui/settings/AnimeUsernameDialog.kt",
                "com/kennyb1201/kbstream/ui/settings/TorBoxReviewDialog.kt",
                "com/kennyb1201/kbstream/ui/settings/TorBoxCloudListDialog.kt",
                "com/kennyb1201/kbstream/ui/settings/MalConnectDialog.kt",
                "com/kennyb1201/kbstream/ui/settings/KitsuConnectDialog.kt"
            ).all { path -> source(path).contains("SettingsModalHold()") }
        )
        assertTrue(
            "a focused text field holds it, and disposal releases it",
            squash(gate).contains("internal fun Modifier.settingsTextEntryHold(): Modifier") &&
                squash(gate).contains("hold(state.hasFocus)") &&
                squash(gate).contains("onDispose { gate?.setTextEntryFocused(key, false) }")
        )
        assertTrue(
            "the key fields and the search box use the hold",
            settings.split(".settingsTextEntryHold()").size - 1 >= 9
        )
    }

    @Test
    fun `the show is drawn last, and a window dialog is held by the gate instead`() {
        val tail = settings.substringAfter("if (showClearHistoryConfirm) {")
        assertTrue(
            "the overlay is emitted after the last dialog, so it covers them",
            tail.contains("HomeScreensaver(") &&
                tail.contains("slides = screensaver.slides,")
        )
        assertTrue(
            "Back exits the overlay instead of leaving Settings",
            tail.contains("BackHandler(enabled = screensaver.visible)") &&
                tail.contains("screensaver.dismiss()")
        )
        assertTrue(
            "the host still owns the wait: Settings keeps no timer of its own",
            !squash(settings).contains("LaunchedEffect(screensaverArmed") &&
                squash(host).contains("slidesShown = arming.shuffled()")
        )
    }

    @Test
    fun `the copy names all three surfaces`() {
        assertTrue(
            "the toggle's description names Home, a title's page and Settings",
            squash(settings).contains(
                "When Home, a title's page or Settings is left idle, fill "
            )
        )
        assertTrue(
            "and the wait row agrees",
            squash(timeoutRow).contains(
                "How long Home, a title's page or Settings must sit untouched before"
            )
        )
    }
}
