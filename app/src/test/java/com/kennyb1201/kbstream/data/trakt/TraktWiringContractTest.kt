package com.kennyb1201.kbstream.data.trakt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase A's wiring promises, read out of the sources they live in.
 *
 * Two of them are the plan's invariants rather than features:
 *
 *  - **A disconnect is Trakt-only.** Simkl's unlink wipes the local watch corpus
 *    (audit m10); Trakt's must not, so the repository is checked for the data
 *    layer it would need to do that and found without it.
 *  - **The token store is its own file.** Diagnostics and the sync readers
 *    address these stores by name, so a shared name would make a Trakt token
 *    read as a Simkl session.
 *
 * The rest pins the screen into the app: a route, a settings row on both form
 * factors, a touch twin, and the parent-PIN gate on a kids profile.
 */
class TraktWiringContractTest {

    private fun mainRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun main(relative: String): String = File(mainRoot(), relative).readText()

    private fun project(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }

    private val repoPath = "com/kennyb1201/kbstream/data/trakt/TraktRepository.kt"
    private val storePath = "com/kennyb1201/kbstream/data/trakt/TraktTokenStore.kt"

    @Test
    fun `a disconnect clears the Trakt session and nothing else`() {
        val src = main(repoPath)

        listOf("WatchHistory", "watchHistoryDao", "deleteAll", "clearLocalWatchState", "Resume")
            .forEach { forbidden ->
                assertFalse(
                    "TraktRepository must not touch local watch data ($forbidden)",
                    src.contains(forbidden)
                )
            }
        assertTrue(
            "the disconnect ends in the store's own clear",
            src.contains("store.clear()")
        )
    }

    @Test
    fun `the token store has its own name and is encrypted without a plaintext path`() {
        val trakt = main(storePath)
        val simkl = main("com/kennyb1201/kbstream/data/simkl/SimklRepository.kt")

        assertTrue(
            "Trakt's own store name",
            trakt.contains("const val PREFS_NAME = \"trakt_auth\"")
        )
        assertTrue(
            "and Simkl's is a different one",
            simkl.contains("\"simkl_auth\"")
        )
        assertTrue("encrypted at rest", trakt.contains("SecureTokenStore.prefs("))
        assertFalse(
            "no plaintext migration: nothing predates this store",
            trakt.contains("legacyPlaintext = true")
        )
    }

    @Test
    fun `the page is routed, reachable from both settings pages and has a touch twin`() {
        val mainActivity = main("com/kennyb1201/kbstream/MainActivity.kt")
        val nav = main("com/kennyb1201/kbstream/NavStateSerialization.kt")
        val settings = main("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
        val settingsMobile = main("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")
        val screen = main("com/kennyb1201/kbstream/ui/trakt/TraktConnectScreen.kt")

        assertTrue("a Trakt screen exists", mainActivity.contains("data class Trakt("))
        assertTrue("and renders", mainActivity.contains("is Screen.Trakt ->"))
        assertTrue("and survives a restore", nav.contains("\"trakt\" ->"))
        assertTrue("a row on the TV settings page", settings.contains("onOpenTrakt"))
        assertTrue("a row on the phone settings page", settingsMobile.contains("onOpenTrakt"))
        assertTrue(
            "the page hands a phone to its touch twin",
            screen.contains("TraktConnectScreenMobile(")
        )
    }

    @Test
    fun `a kids profile needs the parent PIN before a code is generated`() {
        val vm = main("com/kennyb1201/kbstream/ui/trakt/TraktViewModel.kt")

        assertTrue("kids are recognised", vm.contains("kidsMaxAge"))
        assertTrue("a PIN must exist", vm.contains("guard.hasParentPin()"))

        val start = vm.indexOf("fun submitParentPin(pin: String) {")
        assertTrue("the PIN entry exists", start >= 0)
        val rest = vm.substring(start)
        val verifyAt = rest.indexOf("guard.verifyParentPin(pin)")
        val flowAt = rest.indexOf("beginDeviceFlow()")
        assertTrue("the PIN is verified", verifyAt >= 0)
        assertTrue("and the flow starts after it, in the same block", flowAt > verifyAt)
    }

    @Test
    fun `the build carries a client id and never a client secret`() {
        val gradle = project("app/build.gradle.kts")
        val config = main("com/kennyb1201/kbstream/data/trakt/TraktConfig.kt")
        val models = main("com/kennyb1201/kbstream/data/trakt/TraktModels.kt")

        assertTrue(gradle.contains("TRAKT_CLIENT_ID"))
        assertFalse(
            "a compiled-in secret is recoverable from every published APK",
            gradle.contains("TRAKT_CLIENT_SECRET")
        )
        assertFalse("and the config exposes none", config.contains("clientSecret"))
        assertFalse(
            "nor does any Trakt request carry one",
            models.contains("@Json(name = \"client_secret\")") ||
                models.contains("clientSecret")
        )
    }
}
