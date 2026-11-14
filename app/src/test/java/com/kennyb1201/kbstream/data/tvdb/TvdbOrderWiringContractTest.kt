package com.kennyb1201.kbstream.data.tvdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the TVDB order feature is wired (spec 2026-11-13).
 *
 * The rules themselves are pinned by [TvdbOrdersTest]; this pins the properties
 * that only exist across files: that the key is a project key and never a
 * secret, that nothing is fetched until a screen asks, that the chosen order
 * changes DISPLAY and the add-on request and nothing else, and that every
 * failure path ends in "exactly as today".
 */
class TvdbOrderWiringContractTest {

    private val client by lazy { source(TVDB_API_CLIENT) }
    private val repository by lazy { source(TVDB_ORDER_REPOSITORY) }
    private val store by lazy { source(TVDB_ORDER_STORE) }
    private val tokenStore by lazy { source(TVDB_TOKEN_STORE) }
    private val display by lazy { source(TVDB_EPISODE_DISPLAY) }
    private val config by lazy { source(TVDB_CONFIG) }
    private val addons by lazy { source(ADDON_REPOSITORY) }
    private val tmdb by lazy { source(TMDB_REPOSITORY) }
    private val detailVm by lazy { source(DETAIL_VIEW_MODEL) }
    private val tvScreen by lazy { source(DETAIL_SCREEN) }
    private val phoneScreen by lazy { source(PHONE_DETAIL_SCREEN) }
    private val picker by lazy { source(EPISODE_ORDER_SECTION) }
    private val prefs by lazy { source(APP_PREFERENCES) }
    private val gradle by lazy {
        repoFile("app/build.gradle.kts", "build.gradle.kts")
    }
    private val workflow by lazy {
        repoFile(".github/workflows/build.yml", "../.github/workflows/build.yml")
    }

    // ── Credentials ──────────────────────────────────────────────────────

    @Test
    fun `the key is a project key wired like TMDB's, and there is no secret`() {
        assertTrue(
            "the build reads TVDB_API_KEY from local.properties or the environment",
            gradle.contains("localProps.getProperty(\"TVDB_API_KEY\")") &&
                gradle.contains("System.getenv(\"TVDB_API_KEY\")")
        )
        assertTrue(
            "and compiles it under that exact name",
            gradle.contains("buildConfigField(\"String\", \"TVDB_API_KEY\", \"\\\"\$tvdbApiKey\\\"\")")
        )
        // The rule is SimklClientConfigTest's, and it has exactly ONE named
        // exception (spec 2026-11-14, F1): ANILIST_CLIENT_SECRET. AniList removed
        // the implicit grant its connect was built on (its authorize endpoint
        // answers unsupported_grant_type for response_type=token), so the connect
        // is the Authorization Code grant and the exchange needs the app secret;
        // KBStream has no backend to hold one, so Kenny decided on 2026-11-14 to
        // bundle it. Every other provider still ships no secret - an unexpected
        // field fails this, which is what the assertion below checks.
        val shippedSecrets = Regex(
            "buildConfigField\\(\\s*\"String\",\\s*\"([A-Z_]*SECRET[A-Z_]*)\""
        ).findAll(gradle).map { it.groupValues[1] }.toSet()
        assertEquals(
            "a *SECRET* BuildConfig field must never exist beyond AniList's cited " +
                "exception (SimklClientConfigTest's rule): $shippedSecrets",
            setOf("ANILIST_CLIENT_SECRET"),
            shippedSecrets
        )
        assertTrue(
            "the release workflow passes the secret through",
            workflow.contains("TVDB_API_KEY: \${{ secrets.TVDB_API_KEY }}")
        )
        assertTrue(
            "and the config reads it from BuildConfig, blank meaning not configured",
            config.contains("val apiKey: String get() = BuildConfig.TVDB_API_KEY") &&
                config.contains("val isConfigured: Boolean get() = apiKey.isNotBlank()")
        )
    }

    @Test
    fun `the subscriber PIN stays in the encrypted key store, never the APK`() {
        assertTrue(
            "the PIN is stored like every other credential, in apiKeyPrefs",
            prefs.contains("private const val KEY_TVDB_PIN = \"tvdb_pin\"") &&
                prefs.contains("apiKeyPrefs(context).getString(KEY_TVDB_PIN")
        )
        assertFalse(
            "and nothing ships it: the project key alone is what the build carries",
            config.contains("pin: String get() = BuildConfig")
        )
        assertTrue(
            "the token store keeps only a token and its deadline",
            tokenStore.contains("const val KEY_ACCESS_TOKEN") &&
                tokenStore.contains("const val KEY_EXPIRES_AT") &&
                !tokenStore.contains("pin")
        )
        assertTrue(
            "the login body adds pin ONLY when one is configured",
            client.contains("val pin = runCatching { AppPreferences.getTvdbPin(context) }.getOrDefault(\"\")") &&
                client.contains("if (pin.isNotBlank()) payload.put(\"pin\", pin)")
        )
    }

    // ── Laziness / fail-soft ─────────────────────────────────────────────

    @Test
    fun `nothing is fetched until a screen asks, and a movie never asks`() {
        assertTrue(
            "the ViewModel's loader is gated on a TV page that has painted",
            detailVm.contains("private fun loadEpisodeOrders()") &&
                detailVm.contains("if (!TvdbConfig.isConfigured) return") &&
                detailVm.contains("if (_isSyntheticDetail.value || mediaType != \"series\") return")
        )
        assertTrue(
            "and the call site is the detail paint, not a sweep",
            detailVm.contains("_tmdbDetail.value = shownDetail") &&
                detailVm.contains("loadEpisodeOrders()")
        )
        assertTrue(
            "the repository refuses early with no key or no tvdb id",
            repository.contains("if (!TvdbConfig.isConfigured) return emptyList()") &&
                repository.contains("val id = tvdbId?.takeIf { it > 0 } ?: return emptyList()")
        )
        assertTrue(
            "a series with no alternate order is one option, which is no picker",
            repository.contains("return if (options.size > 1) options else emptyList()")
        )
    }

    @Test
    fun `an empty episode answer is its own fact and never renumbers`() {
        assertTrue(
            "the client reports Empty rather than an empty list",
            client.contains("internal sealed interface TvdbEpisodesRead") &&
                client.contains("data object Empty : TvdbEpisodesRead") &&
                client.contains("if (out.isEmpty()) return TvdbEpisodesRead.Empty")
        )
        assertTrue(
            "and the repository refuses to build a catalogue from it",
            repository.contains("TvdbEpisodesRead.Empty ->") &&
                repository.contains("refusing to renumber")
        )
        assertTrue(
            "a join that maps nothing is refused too, and never stored",
            repository.contains("if (!catalogue.mapsAnything)") &&
                store.contains("if (!decoded.mapsAnything) return null")
        )
        assertTrue(
            "and a failed fetch never overwrites a good catalogue",
            repository.contains("if (catalogue.mapsAnything) TvdbOrderStore.putCatalogue")
        )
    }

    @Test
    fun `401 re-logins once, 429 honours Retry-After, and neither loops`() {
        assertTrue(
            "one single-flight login gate",
            client.contains("private val loginGate = Mutex()") &&
                client.contains("loginGate.withLock")
        )
        assertTrue(
            "a 401 retries the call exactly once",
            client.contains("if (first is Get.Unauthorized)") &&
                client.contains("forceLogin = true")
        )
        assertTrue(
            "a 429 parks the leg for the header's own window",
            client.contains("response.code == 429") &&
                client.contains("retryAfterMs(response.header(\"Retry-After\"))") &&
                client.contains("parkedUntilMs = System.currentTimeMillis() + parkMs")
        )
        assertTrue(
            "and the token is re-minted a week inside the promised month",
            tokenStore.contains("TOKEN_LIFETIME_MS = 25L * 24L * 60L * 60L * 1000L")
        )
    }

    @Test
    fun `the paginating endpoint is used, not the translated one whose page is ignored`() {
        assertTrue(
            "the request is /series/{id}/episodes/{type}?page=N",
            client.contains("\"\$TvdbConfig.API_BASE/series/\$id/episodes/\$type?page=\$page\"")
        )
        assertFalse(
            "the {seasonType}/{lang} variant is not used (thetvdb/v4-api#74)",
            client.contains("/episodes/\$type/\$")
        )
        assertTrue(
            "paging follows links.next",
            client.contains("optJSONObject(\"links\")?.optString(\"next\")")
        )
        assertTrue(
            "and stops at a hard page ceiling",
            client.contains("private const val MAX_PAGES")
        )
    }

    // ── Where the order applies, and where it does not ───────────────────

    @Test
    fun `display numbers are applied on every season read, and are never the identity`() {
        assertTrue(
            "the season reader stamps display numbers on all three return paths",
            tmdb.contains("return seasonWithOrderNumbers(named, season, orderCatalogue)") &&
                tmdb.contains("return seasonWithOrderNumbers(episodes, season, orderCatalogue)")
        )
        assertTrue(
            "and the order is NOT part of the cache key, so a switch is picked up at once",
            tmdb.contains("val key = \"\$tvId:\$season:\$streamRootId:\${episodeSchemeTag(streamRootId, tvId)}\"") &&
                !tmdb.contains("episodeOrderTag")
        )
        assertTrue(
            "the display fields are separate from episodeNumber and streamId",
            display.contains("displayEpisode ?: episodeNumber") &&
                display.contains("displaySeason ?: listedSeason")
        )
        assertTrue(
            "and the TMDB number, the stream id and the watched key are never rewritten",
            !display.contains("episodeNumber =") && !display.contains("streamId =")
        )
    }

    @Test
    fun `the add-on is asked for the order's number at the one request boundary`() {
        assertTrue(
            "the request id is the translated one",
            addons.contains("val queryId =") &&
                addons.contains("orderTranslatedId(")
        )
        assertTrue(
            "and both the URL and the cache key follow it",
            addons.contains("\"\$base/stream/\$type/\$queryId.json\"") &&
                addons.contains("\"\$base|\$type|\$queryId\"")
        )
        assertTrue(
            "only a three-part id is translated, and the root is kept",
            addons.contains("if (parts.size != 3) return id") &&
                addons.contains("stableShowId(parts[0], null)")
        )
        assertTrue(
            "a failed lookup leaves the source request exactly as it was",
            addons.contains("}.getOrNull()") &&
                addons.contains("?: return id")
        )
    }

    @Test
    fun `both screens offer the row only when there is a real choice, and picking reloads`() {
        assertTrue(
            "the shared row draws nothing for one option",
            picker.contains("if (options.size <= 1) return")
        )
        listOf("TV" to tvScreen, "phone" to phoneScreen).forEach { (name, src) ->
            assertTrue(
                "$name: the row is gated on an actual alternate order",
                src.contains("if (episodeOrders.size > 1)") || src.contains("if (episodeOrders.size > 1)")
            )
            assertTrue(
                "$name: and a pick goes through the ViewModel",
                src.contains("EpisodeOrderSection(") &&
                    src.contains("viewModel.selectEpisodeOrder(type)")
            )
        }
        assertTrue(
            "the ViewModel re-resolves the season in view, no restart needed",
            detailVm.contains("fun selectEpisodeOrder(seasonType: String?)") &&
                detailVm.contains("_loadedSeason.value?.let { season -> loadEpisodesForSeason(season) }")
        )
        assertTrue(
            "an order change clears nothing else about the show",
            !detailVm.contains("EpisodeSchemeStore.put(")
        )
    }

    @Test
    fun `the choice is per profile and device-local, keyed like the scheme store`() {
        assertTrue(
            "one store, named as documented",
            store.contains("const val PREFS = \"kbstream_episode_order\"")
        )
        assertTrue(
            "profile-scoped, the same way every per-profile store is",
            store.contains("ProfileStorage.prefsName(app, PREFS)")
        )
        assertTrue(
            "keyed by stableShowId, the scheme store's own key",
            detailVm.contains("EpisodeSchemeStore.stableShowId(rootId, tmdbId)") &&
                tmdb.contains("EpisodeSchemeStore.stableShowId(streamRootId, tvId)")
        )
        assertFalse(
            "and never synced: an order picked on the TV must not renumber a phone",
            store.contains("SYNCED_API_KEYS")
        )
        assertTrue(
            "clearing writes no entry at all",
            store.contains("KEY_ORDER_PREFIX + key)") &&
                store.contains("remove(KEY_CATALOGUE_PREFIX + key)")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    /** A repository file (the module's Gradle script, the release workflow). */
    private fun repoFile(vararg candidates: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            candidates.forEach { relative ->
                val file = File(dir, relative)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo file not found: ${candidates.joinToString()}")
    }

    private companion object {
        const val TVDB = "com/kennyb1201/kbstream/data/tvdb/"
        const val TVDB_API_CLIENT = "${TVDB}TvdbApiClient.kt"
        const val TVDB_ORDER_REPOSITORY = "${TVDB}TvdbOrderRepository.kt"
        const val TVDB_ORDER_STORE = "${TVDB}TvdbOrderStore.kt"
        const val TVDB_TOKEN_STORE = "${TVDB}TvdbTokenStore.kt"
        const val TVDB_EPISODE_DISPLAY = "${TVDB}TvdbEpisodeDisplay.kt"
        const val TVDB_CONFIG = "${TVDB}TvdbConfig.kt"
        const val ADDON_REPOSITORY = "com/kennyb1201/kbstream/data/addon/AddonRepository.kt"
        const val TMDB_REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val DETAIL_VIEW_MODEL = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val DETAIL_SCREEN = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val PHONE_DETAIL_SCREEN = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        const val EPISODE_ORDER_SECTION = "com/kennyb1201/kbstream/ui/detail/EpisodeOrderSection.kt"
        const val APP_PREFERENCES = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    }
}
