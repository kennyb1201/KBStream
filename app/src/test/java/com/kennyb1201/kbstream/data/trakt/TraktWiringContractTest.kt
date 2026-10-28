package com.kennyb1201.kbstream.data.trakt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase A's wiring promises, read out of the sources they live in.
 *
 * Two of them are the plan's invariants rather than features:
 *
 *  - **A disconnect never wipes local data.** Simkl's unlink used to clear the
 *    local watch corpus (audit m10) and that was a regression, removed in the
 *    playback spec's C4/D1: BOTH trackers now drop only the link and their
 *    tracker-side caches. The repository is still checked for the data layer a
 *    wipe would need, and found without it.
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
            "the disconnect ends in the store's own clear, and leaves the " +
                "sign-out tombstone the session sync needs",
            src.contains("store.markSignedOut()")
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

    /**
     * The R8 failure of 0.9.4 was a model WITHOUT a generated adapter, so a
     * release build's reflective path threw on a defaulted constructor. A test
     * cannot run R8, but it can hold every wire model to codegen - which is the
     * property that made the reflective path unreachable - and hold the loop to
     * the evidence that stops a parse throw from being reported as an expiry.
     */
    @Test
    fun `every Trakt wire model has a generated adapter`() {
        val models = main("com/kennyb1201/kbstream/data/trakt/TraktModels.kt")
        val annotated = Regex("@JsonClass\\(generateAdapter = true\\)\\s*data class (\\w+)")
            .findAll(models)
            .map { it.groupValues[1] }
            .toSet()
        val declared = Regex("^data class (\\w+)", RegexOption.MULTILINE)
            .findAll(models)
            .map { it.groupValues[1] }
            .toSet()
        // The app's own stored pair: it is built from a parsed response, never
        // read from or written to the wire by Moshi.
        val local = setOf("TraktToken")

        assertTrue("no models were found, so this test proves nothing", declared.size > 20)
        assertEquals(
            "these wire models would fall back to reflection in a release build",
            emptySet<String>(),
            declared - annotated - local
        )
        assertTrue(
            "and the reflection factory stays registered as the fallback for " +
                "anything a later model forgets",
            main(repoPath).contains("addLast(KotlinJsonAdapterFactory())")
        )
    }

    @Test
    fun `a poll that threw in the flow cannot be reported as an expired code`() {
        val vm = main("com/kennyb1201/kbstream/ui/trakt/TraktViewModel.kt")
        val exchange = main("com/kennyb1201/kbstream/data/trakt/TraktPollExchange.kt")

        assertTrue(
            "the loop keeps the whole flow's evidence, not just the last read",
            vm.contains("val evidence = TraktFlowEvidence()")
        )
        assertTrue(
            "a poll that threw is recorded as such",
            vm.contains("evidence.recordThrow()")
        )
        assertTrue(
            "and every completed read records the status Trakt answered",
            vm.contains("evidence.recordPoll(result.statusCode)")
        )
        assertTrue(
            "the result must carry that status for the loop to record it",
            exchange.contains("statusCode = http.statusCode")
        )
        assertTrue(
            "the expired message is chosen from the flow, not from the last poll",
            vm.contains("if (evidence.expiredOutcome() == TraktDeviceFlowRules.Poll.FAILED)")
        )
        assertTrue(
            "and the honest outcome says the reply could not be read",
            vm.contains("Something went wrong reading Trakt's reply.")
        )
        assertTrue(
            "a 404 with no throw in the flow still means the code expired",
            vm.contains("fail(\"That code expired. Start again for a fresh one.\")")
        )
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
