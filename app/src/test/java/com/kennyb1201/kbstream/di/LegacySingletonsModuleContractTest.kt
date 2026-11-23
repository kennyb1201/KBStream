package com.kennyb1201.kbstream.di

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graph delegates to the legacy accessors, and only the singles are scoped.
 *
 * Spec 2026-11-22, item 3. Two things have to stay true for the migration to be
 * safe to do class-by-class: every provider returns the instance the
 * `getInstance` accessor owns (otherwise a screen and its repository can hold
 * two copies of the same cache), and nothing that follows the active profile is
 * pinned to `@Singleton` (otherwise a profile switch hands the next profile the
 * previous one's database).
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
            "every provider must hand out the accessor's instance, not a new one",
            providers,
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
            "FanartArtRepository.getInstance(",
            "SimklRepository.getInstance(",
            "TraktRepository.getInstance(",
            "BingeCatRepository.getInstance(",
            "AddonManager.getInstance(",
            "AddonRepository.getInstance(",
            "KBRepository.getInstance(",
            "TvmazeAirDateRepository.getInstance(",
            "KBStreamDownloader.getInstance(",
            "WatchHistoryDatabase.getInstance(",
            "IptvDatabase.getInstance("
        ).forEach { call ->
            assertTrue("missing delegation: $call", module.contains(call))
        }
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
