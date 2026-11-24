package com.kennyb1201.kbstream.di

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graph delegates to the legacy accessors, and only the singles are scoped.
 *
 * Spec 2026-11-22, item 3. Two things have to stay true for the migration to be
 * safe to do class-by-class: every provider hands out the one instance the
 * process uses - the `getInstance` accessor's while it still exists, and its own
 * `@Singleton` construction once the accessor has been retired (otherwise a
 * screen and its repository can hold two copies of the same cache) - and nothing
 * that follows the active profile is pinned to `@Singleton` (otherwise a profile
 * switch hands the next profile the previous one's database).
 *
 * Pinned at the source because instantiating the graph needs an Application and
 * a running Hilt component; the shape is the contract.
 */
class LegacySingletonsModuleContractTest {

    private val module: String by lazy { source(MODULE) }

    @Test
    fun `every provider is a delegation to the legacy accessor`() {
        val providers = Regex("@Provides").findAll(module).count()
        val delegations = Regex("getInstance\\(").findAll(module).count()

        assertEquals("one provider per legacy singleton", 12, providers)
        assertEquals(
            "nine providers still hand out their accessor's instance, not a new one",
            9,
            delegations
        )
    }

    @Test
    fun `only the profile-scoped databases are unscoped`() {
        // Line-anchored: the KDoc above the exceptions says `@Singleton` in prose.
        val singletons = Regex("^\\s*@Singleton\\s*$", RegexOption.MULTILINE).findAll(module).count()
        assertEquals("ten process-wide singletons", 10, singletons)

        listOf("watchHistoryDatabase", "iptvDatabase").forEach { name ->
            val head = module.substringBefore("fun $name")
            val annotation = head.substringAfterLast("@Provides")
            assertTrue(
                "$name follows the active profile and must not be @Singleton",
                !annotation.contains("@Singleton")
            )
        }
    }

    /** The bodies the delegation names, so a rename cannot silently pass. */
    @Test
    fun `the delegation targets are the classes the graph exposes`() {
        listOf(
            "TmdbRepository.getInstance(",
            "SimklRepository.getInstance(",
            "TraktRepository.getInstance(",
            "BingeCatRepository.getInstance(",
            "AddonManager.getInstance(",
            "AddonRepository.getInstance(",
            "FanartArtRepository.getInstance(",
            "WatchHistoryDatabase.getInstance(",
            "IptvDatabase.getInstance("
        ).forEach { call ->
            assertTrue("missing delegation: $call", module.contains(call))
        }
    }

    /**
     * `FanartArtRepository` was the one class absent from this module, because it
     * was `internal` and neither a provider nor a `@HiltViewModel` parameter may
     * expose an internal type. It is public now, and it is in - which is what let
     * Home stop being the migration's last legacy accessor call. Pinned so that
     * re-narrowing its visibility, and silently dropping it back out of the
     * graph, cannot pass.
     */
    @Test
    fun `the formerly internal repository is a provider too`() {
        assertTrue(
            "FanartArtRepository is a provider now, and it delegates",
            module.contains("fun fanartArtRepository(") &&
                module.contains("FanartArtRepository.getInstance(context)")
        )
        val companion =
            source("com/kennyb1201/kbstream/data/fanart/FanartArtRepository.kt")
        assertTrue(
            "public, because the graph names it and Home takes it as a parameter",
            companion.contains("class FanartArtRepository private constructor(context: Context)")
        )
    }

    /**
     * The endgame, exercised on the classes it has reached so far. Once nothing
     * outside the graph called their accessor, the companion was deleted rather
     * than kept as a second owner - the module constructs the instance instead,
     * scoped `@Singleton`. Pinned so a convenience accessor cannot grow back and
     * give a screen a second HTTP cache, a second air-date cache, or a second
     * extractor transport.
     */
    @Test
    fun `the retired accessors are deleted, and the graph constructs them`() {
        listOf(
            "com/kennyb1201/kbstream/data/kb/KBRepository.kt" to "KBRepository(context)",
            "com/kennyb1201/kbstream/data/airdates/TvmazeAirDateRepository.kt" to
                "TvmazeAirDateRepository(context)",
            "com/kennyb1201/kbstream/data/youtube/KBStreamDownloader.kt" to
                "KBStreamDownloader()"
        ).forEach { (path, construction) ->
            val source = source(path)
            assertFalse("$path still exposes a legacy accessor", source.contains("fun getInstance("))
            assertFalse("$path still holds the accessor's instance field", source.contains("private var instance"))
            assertTrue(
                "the graph constructs $construction, or nothing does",
                module.contains(construction)
            )
        }
    }

    /**
     * The downloader's real caller is an `object`, so it cannot take the
     * transport as a constructor parameter. It is installed instead, from the
     * application's injected instance - and the class that used to build its own
     * must not be able to: a second OkHttp stack is the exact duplicate the
     * delegation phase existed to prevent.
     */
    @Test
    fun `the extractor is handed the graph's transport, not one of its own`() {
        val extractor = source("com/kennyb1201/kbstream/data/youtube/NewPipeManager.kt")
        assertFalse(
            "NewPipeManager must not reach for the downloader's accessor any more",
            extractor.contains("KBStreamDownloader.getInstance(")
        )
        assertTrue(
            "and it installs the instance it is handed",
            extractor.contains("fun install(downloader: KBStreamDownloader)")
        )
        val application = source("com/kennyb1201/kbstream/MainApplication.kt")
        assertTrue(
            "the application is where the graph's instance arrives",
            application.contains("@Inject lateinit var kbStreamDownloader: KBStreamDownloader") &&
                application.contains("NewPipeManager.install(kbStreamDownloader)")
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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
        const val MODULE = "com/kennyb1201/kbstream/di/LegacySingletonsModule.kt"
    }
}
