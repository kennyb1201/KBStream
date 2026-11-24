package com.kennyb1201.kbstream.di

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ViewModel that has moved onto the graph stays moved, and stays correct.
 *
 * Spec 2026-11-22, item 3, F1. The migration is one class per commit, so this
 * file grows a name at a time: [MIGRATED] is the set that must be graph-built,
 * and every entry is checked for the two things that could quietly regress - a
 * screen falling back to the default factory (which would build a SECOND
 * instance beside the injected one) and a move that takes a class's
 * profile-scoped reads with it.
 *
 * Pinned at the source, like the module contract beside it: instantiating a
 * `@HiltViewModel` needs a running Hilt component.
 */
class HiltViewModelMigrationContractTest {

    @Test
    fun `every migrated view model is graph-built`() {
        MIGRATED.forEach { name ->
            val source = source("$UI/$name.kt")
            assertTrue("$name is @HiltViewModel", source.contains("@HiltViewModel"))
            assertTrue(
                "$name's constructor is the graph's",
                source.contains("@Inject constructor(")
            )
        }
    }

    /**
     * The first move, and the reason this file exists: the class that moved is
     * the one whose reads follow the active profile. Construction was all that
     * was supposed to change.
     */
    @Test
    fun `the first move left the profile rules where they were`() {
        val viewModel = source("$UI/settings/YearReviewViewModel.kt")
        assertTrue(
            "the scoped database is still resolved per load",
            viewModel.contains("getInstanceScoped")
        )
        assertTrue(
            "the active profile is still read at load time",
            viewModel.contains("ProfileStorage.activeProfileId")
        )
    }

    /**
     * The check this migration needed and did not have: a `@HiltViewModel` built
     * by the DEFAULT factory compiles and then throws when the screen opens,
     * because the factory looks for a constructor that takes an Application and
     * nothing else. It is not a screen-level rule - the phone wrappers create
     * these classes too, and the first pass moved the outer screen while the
     * `*ScreenMobile` beside it still asked the default factory.
     *
     * So the scan is every main source file, and the test fails on a line that
     * names a migrated class AND calls `viewModel(...)`. `hiltViewModel(...)`
     * does not match (the bridge's `V` is capital), which is what makes the
     * check usable as a grep.
     */
    @Test
    fun `no migrated view model is built by the default factory`() {
        val offenders = mutableListOf<String>()
        sourceFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (!line.contains("viewModel(") && !line.contains("viewModel<")) return@forEachIndexed
                MIGRATED.forEach { name ->
                    if (line.contains(name.substringAfterLast('/'))) {
                        offenders += "${file.name}:${index + 1} -> ${name.substringAfterLast('/')}"
                    }
                }
            }
        }
        assertTrue(
            "these still ask the default factory, which cannot inject: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * The rule the whole migration turns on: a class that has moved owns no
     * accessor call any more. If one comes back, the class and the graph can
     * each believe they own the singleton, which is the one thing delegating
     * first was supposed to make impossible.
     */
    @Test
    fun `no migrated class reaches for a legacy accessor, except where it must`() {
        MIGRATED.forEach { name ->
            val actual = Regex("\\w+\\.getInstance\\(")
                .findAll(source("$UI/$name.kt"))
                .map { it.value }
                .sorted()
                .toList()
            assertEquals(
                "$name's accessor calls are not the ones this migration left it",
                KNOWN_ACCESSOR_CALLS[name].orEmpty().sorted(),
                actual
            )
        }
    }

    /**
     * The second move, and the one that proves the delegation: this class used
     * to construct `TmdbRepository` itself. It is now handed the same instance
     * from the graph, so the call is gone from the class while the shared cache
     * the call used to guarantee is unchanged.
     */
    @Test
    fun `a migrated class no longer constructs its repository`() {
        val actor = source("$UI/actor/ActorViewModel.kt")
        // assertTrue(String, Boolean) is the only message-bearing form JUnit 4
        // offers; assertFalse has no message overload.
        assertTrue(
            "the graph constructs it now",
            !actor.contains("TmdbRepository.getInstance(")
        )
        assertTrue(
            "and it is still the same repository, injected",
            actor.contains("private val tmdbRepository: TmdbRepository")
        )
    }

    @Test
    fun `the screen asks the graph instead of the default factory`() {
        SCREENS.forEach { path ->
            val screen = source("$UI/$path")
            // The call, not its spelling: one screen wraps the default onto the
            // next line, and Detail keys it (`hiltViewModel(key = ...)`), which is
            // the same resolution with the key it has always used.
            assertTrue(
                "$path resolves its view model from the entry point",
                screen.contains("hiltViewModel(")
            )
            assertTrue(
                "$path's default factory would build a second instance beside the injected one",
                !screen.contains("= viewModel()")
            )
        }
    }

    /**
     * Home composes two ViewModels and has moved one of them so far, so it
     * cannot join [SCREENS] - that list forbids any `= viewModel()` at all,
     * which is the right rule for a screen that has finished moving.
     */
    @Test
    fun `Home resolves its KB collections from the graph`() {
        val home = source("$UI/home/HomeScreen.kt")
        assertTrue(
            "the KB view model comes from the entry point",
            home.contains("KBHomeViewModel =\n        hiltViewModel()")
        )
    }

    @Test
    fun `the activity hosting it is an entry point`() {
        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue(
            "hiltViewModel() resolves through the activity's entry point",
            activity.contains("@AndroidEntryPoint\nclass MainActivity")
        )
    }

    /**
     * The navigation-free bridge, deliberately: `hilt-navigation-compose` exists
     * and would work, but it pulls navigation-compose in, and the relay's order
     * puts the navigation migration (item 5) after Cast. A migration must not
     * hand the next spec its dependencies early.
     *
     * The navigation migration has since started (item 5, F0 pinned
     * navigation-compose itself), so the guard has moved one step out: the
     * BRIDGE is still the navigation-free artifact - the one that would have
     * pulled the dependency in months early - while the dependency now stands on
     * its own, put there by the spec that owns it.
     */
    @Test
    fun `the bridge is a declared dependency, and it is the navigation-free one`() {
        assertTrue(
            "the catalog names androidx.hilt:hilt-lifecycle-viewmodel-compose",
            rootText("gradle/libs.versions.toml")
                .contains("androidx.hilt:hilt-lifecycle-viewmodel-compose")
        )
        assertTrue(
            "the app depends on it",
            rootText("app/build.gradle.kts")
                .contains("libs.androidx.hilt.lifecycle.viewmodel.compose")
        )
        assertTrue(
            "the bridge stays the navigation-free artifact: hilt-navigation-compose is " +
                "the dependency that would have pulled navigation in early",
            // The catalog names it in a comment explaining why it is absent, so
            // this asks for a module ENTRY, not for the string.
            !rootText("gradle/libs.versions.toml").contains("hilt-navigation-compose = {") &&
                !rootText("app/build.gradle.kts").contains("hilt.navigation.compose")
        )
        assertTrue(
            "and navigation-compose is present because item 5 started, not because " +
                "this migration dragged it in",
            rootText("app/build.gradle.kts").contains("libs.androidx.navigation.compose")
        )
    }

    /**
     * The first collaborator that is not a legacy singleton to move: it was a
     * plain class that built its own TMDB/addon/Trakt accessors from the
     * Application handed to it. Now the graph builds it, and its one caller
     * takes it as a parameter - the shape every remaining helper needs.
     */
    @Test
    fun `KBContentLoader is constructed by the graph`() {
        val loader = source("com/kennyb1201/kbstream/data/kb/KBContentLoader.kt")
        assertTrue(
            "the loader takes its repositories instead of reaching for them",
            loader.contains("@Singleton\nclass KBContentLoader @Inject constructor(") &&
                loader.contains("private val tmdbRepository: TmdbRepository,") &&
                loader.contains("private val addonManager: AddonManager") &&
                !loader.contains("getInstance(")
        )
        val folder = source("$UI/kb/KBFolderViewModel.kt")
        assertTrue(
            "and its caller is handed it",
            folder.contains("private val contentLoader: KBContentLoader") &&
                !folder.contains("KBContentLoader(application)")
        )
    }

    /**
     * The player picker's loader: an `object` that reached for TMDB, Simkl and
     * Trakt on every season load. It is graph-built now, and the two engines
     * are handed it - the player's half of "construction moves class-by-class",
     * where the destination has no view model to hoist into.
     */
    @Test
    fun `the player picker's loader is constructed by the graph`() {
        val loader = source("com/kennyb1201/kbstream/ui/player/PlayerEpisodes.kt")
        assertTrue(
            "the loader takes its three readers instead of reaching for them",
            loader.contains("@Singleton\nclass PlayerEpisodes @Inject constructor(") &&
                loader.contains("private val tmdbRepository: TmdbRepository,") &&
                loader.contains("private val traktRepository: TraktRepository,") &&
                loader.contains("private val simklRepository: SimklRepository") &&
                !loader.contains("TmdbRepository.getInstance(") &&
                !loader.contains("TraktRepository.getInstance(") &&
                !loader.contains("SimklRepository.getInstance(")
        )
        listOf("ui/player/NativePlayerActivity.kt", "ui/player/MpvPlayerActivity.kt")
            .forEach { path ->
                val activity = source("com/kennyb1201/kbstream/$path")
                assertTrue(
                    "$path is handed the loader, so a season load cannot build a second one",
                    activity.contains("@Inject lateinit var playerEpisodes: PlayerEpisodes") &&
                        activity.contains("playerEpisodes.season(") &&
                        !activity.contains("PlayerEpisodes.season(")
                )
            }
    }

    /**
     * The same move for the two collaborators that were never legacy singletons:
     * the hero-artwork resolver and the awards repository were built by their
     * view models, from an Application, with no companion in sight. The graph
     * builds them now, and their construction sites are gone - which is the half
     * of "construction moves class-by-class" the accessor list cannot see.
     */
    @Test
    fun `the per-screen collaborators are graph-built too`() {
        listOf(
            "com/kennyb1201/kbstream/data/tmdb/TmdbHeroArtworkRepository.kt",
            "com/kennyb1201/kbstream/data/omdb/OmdbRepository.kt"
        ).forEach { path ->
            assertTrue(
                "$path has an @Inject constructor",
                source(path).contains("@Inject constructor(")
            )
        }
        listOf("$UI/home/HomeViewModel.kt", "$UI/kb/KBFolderViewModel.kt")
            .forEach { path ->
                val vm = source(path)
                val handed = vm.contains("tmdbHeroArtworkRepository: TmdbHeroArtworkRepository") ||
                    vm.contains("heroArtworkRepository: TmdbHeroArtworkRepository")
                assertTrue(
                    "$path is handed the hero resolver",
                    handed && !vm.contains("TmdbHeroArtworkRepository(application)")
                )
            }
        val detail = source("$UI/detail/DetailViewModel.kt")
        assertTrue(
            "Detail is handed OMDB instead of building it",
            detail.contains("internal val omdbRepository: OmdbRepository") &&
                !detail.contains("OmdbRepository(app)")
        )
    }

    /**
     * The engines have no view model to hoist their collaborators into, so each
     * activity is an injection point of its own. Pinned because the alternative
     * - a `getInstance` per tracker - is invisible in a diff and would quietly
     * give a playback session a second instance of a cache.
     */
    @Test
    fun `the player activities are injection points, not call sites`() {
        listOf(
            "ui/player/NativePlayerActivity.kt",
            "ui/player/MpvPlayerActivity.kt",
            "ui/player/ExternalPlayerActivity.kt"
        ).forEach { path ->
            val activity = source("com/kennyb1201/kbstream/$path")
            assertTrue("$path is a Hilt entry point", activity.contains("@AndroidEntryPoint\n"))
            assertTrue(
                "$path is handed TMDB, Trakt, Simkl and BingeCat",
                activity.contains("@Inject lateinit var tmdbRepository: TmdbRepository") &&
                    activity.contains("@Inject lateinit var traktRepository: TraktRepository") &&
                    activity.contains("@Inject lateinit var simklRepository: SimklRepository") &&
                    activity.contains("@Inject lateinit var bingecatRepository: BingeCatRepository")
            )
            if (path != "ui/player/ExternalPlayerActivity.kt") {
                assertTrue(
                    "$path is handed fanart too, so the clearlogo chain it calls " +
                        "never builds a second instance of it",
                    activity.contains(
                        "@Inject lateinit var fanartArtRepository: FanartArtRepository"
                    )
                )
            }
            assertTrue(
                "$path constructs none of them any more",
                !Regex("\\w+Repository\\.getInstance\\(").containsMatchIn(activity)
            )
        }
    }

    /**
     * The search activity resolves a spoken title before any screen exists, so it
     * has no view model to hoist TMDB into either - it is an entry point of its
     * own. Pinned for the same reason as the players: an accessor here is
     * invisible in a diff and would hand the pre-screen lookup a second instance.
     */
    @Test
    fun `the search activity is an injection point too`() {
        val activity = source("com/kennyb1201/kbstream/ui/search/VoiceSearchActivity.kt")
        assertTrue(
            "the voice-search activity is a Hilt entry point",
            activity.contains("@AndroidEntryPoint\nclass VoiceSearchActivity")
        )
        assertTrue(
            "it is handed TMDB instead of constructing it",
            activity.contains("@Inject\n    lateinit var tmdbRepository: TmdbRepository")
        )
        assertFalse(
            "it constructs nothing of its own",
            Regex("TmdbRepository\\.getInstance\\(").containsMatchIn(activity)
        )
    }

    /**
     * Add-on subtitles are resolved and downloaded before any view model exists,
     * so the controller takes the graph's instances from the activity that
     * builds it. Pinned because the object that does the fetching is
     * process-wide and could quietly regrow an accessor that a second copy of
     * the transport would answer from.
     */
    @Test
    fun `the subtitle pipeline is handed its collaborators`() {
        val subtitles = source("com/kennyb1201/kbstream/ui/player/AddonSubtitles.kt")
        listOf("AddonManager", "AddonRepository", "TmdbRepository").forEach { type ->
            assertFalse(
                "AddonSubtitles still reaches for $type's legacy accessor",
                Regex("$type\\.getInstance\\(").containsMatchIn(subtitles)
            )
        }
        assertTrue(
            "the controller is handed the add-on list, transport and TMDB instance",
            subtitles.contains("private val addonManager: AddonManager") &&
                subtitles.contains("private val addonRepository: AddonRepository") &&
                subtitles.contains("private val tmdbRepository: TmdbRepository")
        )
        listOf(
            "ui/player/NativePlayerActivity.kt",
            "ui/player/MpvPlayerActivity.kt"
        ).forEach { path ->
            val activity = source("com/kennyb1201/kbstream/$path")
            assertTrue(
                "$path hands the transport in instead of letting the object fetch it",
                activity.contains("@Inject lateinit var addonRepository: AddonRepository")
            )
        }
    }

    /**
     * Launch housekeeping that reads two services is the same move as a screen's:
     * the object stays an `object`, but the instances arrive from the one place
     * that already holds them - the Application, which is an entry point. Pinned
     * because the pass is `runCatching`-wrapped at the call site, so a second
     * instance built here would fail silently and only show as a reconcile that
     * never prunes.
     */
    @Test
    fun `the launch reconcile pass is handed the services it reads`() {
        val reconcile = source("com/kennyb1201/kbstream/data/library/LibraryHomeRailReconcile.kt")
        assertFalse(
            "the pass still reaches for an accessor",
            reconcile.contains("getInstance(")
        )
        assertTrue(
            "it takes both services as parameters",
            reconcile.contains("traktRepository: TraktRepository") &&
                reconcile.contains("bingecatRepository: BingeCatRepository")
        )
        val app = source("com/kennyb1201/kbstream/MainApplication.kt")
        assertTrue(
            "and the Application passes the fields it injects",
            app.contains("@Inject lateinit var traktRepository: TraktRepository") &&
                app.contains("@Inject lateinit var bingecatRepository: BingeCatRepository") &&
                app.contains("traktRepository,\n                                bingecatRepository")
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceFiles(): List<File> =
        findSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

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

    /** Read as text, not handed back as a [File]: these assertions scan it. */
    private fun rootText(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val settings = File(dir, "settings.gradle.kts")
            if (settings.isFile) {
                val file = File(dir, path)
                assertTrue("file missing: $file", file.isFile)
                return file.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "repository root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val UI = "com/kennyb1201/kbstream/ui"

        /**
         * The accessor calls each moved class is still allowed to make.
         *
         * Empty, deliberately, and it stays empty: Home held the last one, for
         * `FanartArtRepository`, which could not be a `@HiltViewModel` parameter
         * while it was `internal`. It is a provider in the graph now (see
         * LegacySingletonsModule), so every migrated class owns no accessor call
         * at all - and a call coming back is two owners for one singleton, which
         * is the bug the delegation order exists to prevent.
         */
        val KNOWN_ACCESSOR_CALLS = emptyMap<String, List<String>>()

        /** The classes that have moved onto the graph, in migration order. */
        val MIGRATED = listOf(
            "settings/YearReviewViewModel",
            "actor/ActorViewModel",
            "tag/TagViewModel",
            "collection/CollectionViewModel",
            "decade/DecadeViewModel",
            "studio/StudioViewModel",
            "addons/CatalogBuilderViewModel",
            "simkl/SimklViewModel",
            "trakt/TraktViewModel",
            "bingecat/BingeCatViewModel",
            "publiclists/PublicListsViewModel",
            "bingecat/BingeCatPublicListsViewModel",
            "streams/StreamsViewModel",
            "settings/StatsViewModel",
            "iptv/IptvViewModel",
            "sports/SportsHubViewModel",
            "search/SearchViewModel",
            "addons/AddonsViewModel",
            "library/LibraryCalendarViewModel",
            "library/LibraryViewModel",
            "kb/KBHomeViewModel",
            "kb/KBFolderViewModel",
            "detail/DetailViewModel"
        )

        val SCREENS = listOf(
            "settings/YearReviewScreen.kt",
            "actor/ActorScreen.kt",
            "tag/TagScreen.kt",
            "collection/CollectionScreen.kt",
            "decade/DecadeScreen.kt",
            "studio/StudioScreen.kt",
            "addons/CatalogBuilderScreen.kt",
            "simkl/SimklConnectScreen.kt",
            "trakt/TraktConnectScreen.kt",
            "bingecat/BingeCatConnectScreen.kt",
            "publiclists/PublicListsScreen.kt",
            "bingecat/BingeCatPublicListsScreen.kt",
            "streams/StreamsScreen.kt",
            "settings/StatsScreen.kt",
            "iptv/GuideScreen.kt",
            "sports/SportsHubScreen.kt",
            "search/SearchScreen.kt",
            "addons/AddonsScreen.kt",
            "library/LibraryScreen.kt",
            "library/LibraryCalendarPane.kt",
            "kb/KBFolderScreen.kt",
            "detail/DetailScreen.kt"
        )
    }
}
