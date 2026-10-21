package com.kennyb1201.kbstream

import com.kennyb1201.kbstream.ui.settings.SettingsPane
import com.kennyb1201.kbstream.ui.settings.SettingsSearchIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KBStream Bug Sweep 5 — the fixes that are wiring, not arithmetic.
 *
 * The MAJOR batch is covered where it belongs: the Moshi annotation pass by
 * [com.kennyb1201.kbstream.data.MoshiCodegenTest] (a codegen-only Moshi cannot
 * adapt an unannotated model at all), the locale fix by
 * [com.kennyb1201.kbstream.data.iptv.CatchupUrlsTest], and the EPG freshness
 * marker by [com.kennyb1201.kbstream.data.iptv.GuideFetchBudgetContractTest].
 *
 * What is left is the class of fix whose failure mode is silence: a mutex that
 * exists but does not wrap the exchange, a paged read that a pull calls once,
 * a lazy key that drops the half that made it unique, or a CI step that builds
 * the wrong variant. None of those throw, and none of them are reachable from a
 * JVM test through the real collaborator (a live Trakt refresh, a PostgREST
 * server, a LazyRow's key set). They are pinned at the source, exactly like the
 * other wiring contracts in this suite, so a later edit that undoes one of them
 * fails here instead of in the field.
 */
class Sweep5ReleaseReadinessContractTest {

    // ── M4: the token refresh is serialized, with the check re-done ──────

    @Test
    fun `trakt refresh runs under a mutex and re-checks inside it`() {
        val source = read("data/trakt/TraktRepository.kt")

        assertTrue(
            "the refresh needs its own lock - the snapshot mutex guards a different thing",
            source.contains("private val refreshMutex = Mutex()")
        )
        assertTrue(
            "the exchange must be inside the lock",
            source.contains("return refreshMutex.withLock { refreshIfDueLocked(nowMs) }")
        )
        // Two due-checks: the cheap pre-check outside the lock and the
        // authoritative one inside it. A queued caller that finds a fresh pair
        // must return without spending the single-use refresh token.
        assertEquals(
            "a refresh must be re-checked under the lock as well as before it",
            2,
            Regex("TraktDeviceFlowRules\\.refreshDue\\(token\\.expiresAtEpochMillis, nowMs\\)")
                .findAll(source)
                .count()
        )
        assertTrue(
            "the locked body is what does the exchange",
            source.contains("private suspend fun refreshIfDueLocked(nowMs: Long): Boolean")
        )
    }

    // ── M4/M5: Trakt playback sessions are paged ─────────────────────────

    @Test
    fun `trakt playback sessions walk every page`() {
        val repository = read("data/trakt/TraktRepository.kt")
        val api = read("data/trakt/TraktApiService.kt")

        assertTrue(
            "the read needs a page parameter or the walk cannot move",
            api.contains("@Query(\"page\") page: Int = 1")
        )
        assertTrue(
            "playback must be read through the page walk, not with one call",
            repository.contains("val movies = readAllPlaybackRows(authorization) { page, limit ->") &&
                repository.contains("val episodes = readAllPlaybackRows(authorization) { page, limit ->")
        )
        assertTrue(
            "a failed page must not answer a truncated resume list",
            repository.contains("private suspend fun readAllPlaybackRows(")
        )
    }

    // ── M5: the sync pulls cannot be capped by max-rows ──────────────────

    @Test
    fun `every supabase pull walks pages instead of one bare select`() {
        val source = read("data/sync/SupabaseSync.kt")

        assertTrue(
            "the walk needs an explicit range",
            source.contains("range(from, from + PULL_PAGE_SIZE - 1)")
        )
        assertTrue(
            "and a stable order, or pages can repeat or skip rows",
            source.contains("order(keyColumn, Order.ASCENDING)")
        )
        listOf(
            "pullRows(TABLE_HISTORY, \"item_id\")",
            "pullRows(TABLE_WATCHED, \"item_key\")",
            "pullRows(TABLE_OVERRIDES, KEY_OVERRIDES)",
            "pullRows(TABLE_PREFS, \"pref_key\")"
        ).forEach { call ->
            assertTrue("$call must use the paged read", source.contains(call))
        }
        assertTrue(
            "the restore path enumerates history through the walk too",
            source.contains("pullRows(TABLE_HISTORY, \"item_id\")\n                        .mapNotNull { row -> row.itemId }")
        )
    }

    // ── M6 + minor 13: no lazy list keys a person by bare TMDB id ────────

    @Test
    fun `cast and filmography rows key by person AND character`() {
        val mobileDetail = read("ui/mobile/DetailScreenMobile.kt")
        assertTrue(
            "the mobile cast rail must use TV's composite key",
            mobileDetail.contains("\"person\${member.id}\${member.character.orEmpty()}\"")
        )

        val tvActor = read("ui/actor/ActorScreen.kt")
        val mobileActor = read("ui/mobile/ActorScreenMobile.kt")
        assertTrue(
            "TV filmography rows must not collide on a dual role",
            tvActor.contains("\"credit:\${credit.id}:\${credit.character.orEmpty()}\"")
        )
        assertTrue(
            "and neither may the phone's",
            mobileActor.contains("\"movie:\${credit.id}:\${credit.character.orEmpty()}\"") &&
                mobileActor.contains("\"tv:\${credit.id}:\${credit.character.orEmpty()}\"")
        )
    }

    // ── Minors 3/4: idempotent transport keys, guarded external pause ────

    @Test
    fun `mpv treats the dedicated transport keys as idempotent`() {
        val source = read("ui/player/MpvPlayerActivity.kt")

        assertTrue(
            "a plain PLAY must not toggle",
            source.contains("KeyEvent.KEYCODE_MEDIA_PLAY -> {") &&
                source.contains("surface?.setPaused(false)")
        )
        assertTrue(
            "a plain PAUSE must not toggle either",
            source.contains("KeyEvent.KEYCODE_MEDIA_PAUSE -> {") &&
                source.contains("if (!sessionHasPlayed()) return true")
        )
        assertTrue(
            "an external transport pause must not park the session before frame 1",
            source.contains("onPause = {") &&
                source.contains("if (sessionHasPlayed()) {\n                    surface?.setPaused(true)")
        )
    }

    // ── Minor 5 + improvement 9: the drag path allocates and double-taps ─

    @Test
    fun `the level hint is gated and a double-tap owns its gesture`() {
        val source = read("ui/player/PlayerTouchGestures.kt")

        assertTrue(
            "the level label must not be rebuilt on every motion event",
            source.contains("private fun showLevelHint(label: String, level: Float)") &&
                source.contains("if (percent == hintPercent) return")
        )
        assertTrue(
            "a confirmed double-tap must not also commit a drag seek",
            source.contains("doubleTapOwnsGesture") &&
                source.contains("if (!doubleTapOwnsGesture &&") &&
                source.contains("if (doubleTapOwnsGesture) return true")
        )
    }

    // ── Minor 6/7: the sports page's two scroll and poll rules ───────────

    @Test
    fun `favourites re-aim the live poll and a league switch resets the scroll`() {
        val viewModel = read("ui/sports/SportsHubViewModel.kt")
        assertTrue(
            "setTeamFavorite must re-aim the poll, not only the reminder round",
            viewModel.contains("SportsNotificationWorker.syncScheduleForPrefs(getApplication())\n" +
                "        // And the live tick is re-aimed") &&
                viewModel.contains("syncLivePoll()\n    }")
        )

        val mobile = read("ui/mobile/SportsHubScreenMobile.kt")
        assertTrue(
            "the phone hub must put a newly entered league at its own top",
            mobile.contains("LaunchedEffect(selectedPath) {") &&
                mobile.contains("if (switched || freshWithLive) listState.scrollToItem(0)")
        )
    }

    // ── Minor 8: catch-up dates are Locale.US (behavior) ────────────────

    @Test
    fun `catch-up date tokens pin Locale US`() {
        val source = read("data/iptv/CatchupUrls.kt")
        assertTrue(
            "a default-locale format renders non-ASCII digits on ar/hi/fa phones",
            source.contains("String.format(Locale.US, \"%04d\", start.year)") &&
                !source.contains("\"%04d\".format(")
        )
    }

    // ── Minor 10/11: MDBList failure and Retry-After handling ───────────

    @Test
    fun `a failed mdblist page serves the last good snapshot`() {
        val source = read("data/mdblist/MdbListClient.kt")
        assertTrue(
            "the walk must record a mid-walk failure",
            source.contains("var failedMidWalk = false")
        )
        assertTrue(
            "and serve the stale copy rather than an empty snapshot",
            source.contains("if (failedMidWalk) {") &&
                source.contains("cachedSnapshot?.takeIf { cachedSnapshotKey == apiKey }")
        )
        assertTrue(
            "a non-daily 429 must honour the server's Retry-After",
            source.contains("val retryAfterSeconds = response.header(\"Retry-After\")")
        )
    }

    // ── Minor 12: the mobile Trakt button is disabled while polling ──────

    @Test
    fun `mobile trakt generate-code cannot restart mid-poll`() {
        val source = read("ui/mobile/TraktConnectScreenMobile.kt")
        assertTrue(
            "a second tap would invalidate the code on screen",
            source.contains("enabled = !uiState.isLoading && !uiState.isPolling,")
        )
    }

    // ── Minor 1: Settings search knows Trakt ─────────────────────────────

    @Test
    fun `settings search indexes the Trakt row`() {
        val entry = SettingsSearchIndex.entryFor("integrations.trakt")
        assertNotNull("a key nobody indexes is a dead destination", entry)
        assertEquals("Trakt", entry?.label)
        assertEquals(SettingsPane.INTEGRATIONS, entry?.pane)
        // And by the label the row actually draws, which is how every
        // NavigationRow binds itself (no searchKey override needed).
        assertEquals(entry, SettingsSearchIndex.entryForLabel("Trakt"))
    }

    // ── Improvement 7: the phone can open the verification page ─────────

    @Test
    fun `the mobile connect screens can open their verification page`() {
        listOf(
            "ui/mobile/TraktConnectScreenMobile.kt",
            "ui/mobile/SimklConnectScreenMobile.kt"
        ).forEach { path ->
            val source = read(path)
            assertTrue(
                "$path must offer a browser hand-off - a QR is unscannable on the phone showing it",
                source.contains("OPEN VERIFICATION PAGE") &&
                    source.contains("Intent.ACTION_VIEW")
            )
        }
    }

    // ── Improvement 8: the Home collection key is unique ────────────────

    @Test
    fun `home collection sections key by id AND title`() {
        val source = read("ui/mobile/HomeScreenMobile.kt")
        assertTrue(
            "`id ?: title` collides for two null-id imports with the same name",
            source.contains("key = \"kb:\${entry.collection.id}:\${entry.collection.title}\"")
        )
    }

    // ── Minors 14-17 + improvements 1-4: the release pipeline ───────────

    @Test
    fun `the play bundle is built with the play overlay`() {
        val workflow = readRoot(".github/workflows/build.yml")
        assertTrue(
            "the AAB is the Play artifact and must not keep REQUEST_INSTALL_PACKAGES",
            workflow.contains("./gradlew bundleRelease -PplayBuild")
        )
        assertTrue(
            "the self-update APK stays on the default variant",
            workflow.contains("./gradlew assembleRelease -x uploadSentryProguardMappingsRelease")
        )
    }

    @Test
    fun `a dispatch build cannot publish itself as an update`() {
        val workflow = readRoot(".github/workflows/build.yml")
        assertTrue(
            "a dispatch carries 0.0.0-dev and the guard must refuse it",
            workflow.contains("if [[ \"\${VERSION_NAME}\" == *-dev ]]; then") &&
                workflow.contains("Publishing is a\n          # release act")
        )
    }

    @Test
    fun `ci gates the release variant and the release build gates its natives`() {
        val workflow = readRoot(".github/workflows/build.yml")
        assertTrue(
            "the debug gate cannot see the R8/codegen class of defect",
            workflow.contains("./gradlew testReleaseUnitTest")
        )

        val build = readRoot("app/build.gradle.kts")
        assertTrue(
            "native symbols must be uploaded, or native crashes arrive as addresses",
            build.contains("uploadNativeSymbols.set(true)")
        )
        assertTrue(
            "a local release APK must not ship without libass/libdovi",
            build.contains("it.name == \"preReleaseBuild\"") &&
                build.contains("\"libassjni.so\" to \"bash scripts/build_libass.sh\"")
        )
    }

    @Test
    fun `the manifest declares no picture-in-picture permission`() {
        val manifest = readRoot("app/src/main/AndroidManifest.xml")
        assertTrue(
            "PiP is per-activity (supportsPictureInPicture), not a permission",
            !manifest.contains("android.permission.PICTURE_IN_PICTURE")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun read(path: String): String =
        File(sourceRoot(), path).also {
            assertTrue("source missing: $it", it.isFile)
        }.readText()

    private fun readRoot(path: String): String =
        File(repoRoot(), path).also {
            assertTrue("source missing: $it", it.isFile)
        }.readText()

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, ".github/workflows/build.yml").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repository root not found walking up from " + System.getProperty("user.dir"))
    }

    private fun sourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found walking up from " + System.getProperty("user.dir"))
    }
}
