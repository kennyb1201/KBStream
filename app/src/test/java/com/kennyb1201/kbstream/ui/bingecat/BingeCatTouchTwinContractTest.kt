package com.kennyb1201.kbstream.ui.bingecat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BingeCat page on a phone, and the mapping's entry path.
 *
 * Two reports, one file, because both fail silently on a device: a page whose
 * controls are D-pad cards simply does nothing when pressed (it looks like a
 * hung app, not like a missing twin), and a mapping that never leaves the
 * device that chose it looks exactly like a profile nobody mapped.
 *
 * The wiring is asserted against the real sources, the way the sibling
 * BingeCat contract tests do.
 */
class BingeCatTouchTwinContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun assertContains(haystack: String, needle: String, why: String) {
        assertTrue("$why - not found: $needle", squash(haystack).contains(squash(needle)))
    }

    private val tv = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt")
    private val phone = source("com/kennyb1201/kbstream/ui/mobile/BingeCatConnectScreenMobile.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatViewModel.kt")
    private val repository = source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")

    @Test
    fun `the connect page hands a phone to its touch twin`() {
        // The same shape Trakt and Simkl use: one branch at the top of the TV
        // page, the SAME ViewModel and the same callbacks, so the device flow,
        // the mapping step and the disconnect are one implementation.
        assertContains(
            tv,
            "if (!com.kennyb1201.kbstream.FormFactor.isTv) { " +
                "com.kennyb1201.kbstream.ui.mobile.BingeCatConnectScreenMobile( " +
                "modifier = modifier, onBack = onBack, " +
                "onBrowsePublicLists = onBrowsePublicLists, vm = vm )",
            "a phone must not get the D-pad page, and the twin must share the ViewModel"
        )
        // The twin is touch-only: touch widgets, mobile theme, no TV import.
        assertContains(phone, "MobileTheme {", "the phone page applies the mobile theme")
        assertContains(
            phone,
            "import com.kennyb1201.kbstream.ui.components.touch.KBButton",
            "the controls must be the touch twins or a tap does nothing"
        )
        assertFalse(
            "a phone page must not import a tv-material3 widget: $phone",
            phone.contains("import androidx.tv.material3.")
        )
        assertContains(phone, "MobileBackButton(onBack = onBack)", "one back arrow, as everywhere")
    }

    @Test
    fun `the phone page covers every state the TV page does`() {
        listOf(
            "GENERATE CODE",
            "CHANGE PROFILE",
            "BROWSE PUBLIC LISTS",
            "NOT NOW",
            "RECONNECT",
            "MAPPED"
        ).forEach { label ->
            assertContains(phone, label, "the touch layout is missing $label")
        }
        assertContains(
            phone,
            "KBDialogPanel(title = \"Disconnect from BingeCat?\")",
            "unlinking an account by mis-tap is worth one question"
        )
        // A QR is unscannable on the phone showing it, so the browser hand-off is
        // how a phone actually approves the code (Trakt's twin already does).
        assertContains(phone, "OPEN APPROVAL PAGE", "the phone needs a way into the approval page")
        assertContains(phone, "Intent.ACTION_VIEW", "and it opens the browser")
        assertContains(
            phone,
            "bingecatActivationUrlForQr(url, code)",
            "the hand-off must carry the code, or the viewer has to type it anyway"
        )
        // Same entry checks as the TV page: the store is the truth, and an
        // unmapped profile fetches the picker instead of drawing an empty one.
        assertContains(phone, "LaunchedEffect(Unit) { vm.refreshConnectionState() }", "entry check")
        assertContains(
            phone,
            "LaunchedEffect(uiState.isConnected, uiState.isMapped) { " +
                "if (uiState.isConnected && !uiState.isMapped) vm.loadProfiles() }",
            "the unmapped branch must fetch the picker"
        )
    }

    @Test
    fun `opening the page re-asserts the mapping and asks the account before the picker`() {
        // A profile mapped by a build that predates the mapping row has nothing
        // that would ever publish it: the bulk push only runs on sign-in / "Sync
        // now", so every sibling kept its picker. Opening the page is the trigger.
        val reassert = repository
            .substringAfter("fun publishMappingIfMapped() {")
            .substringBefore("}")
        assertContains(
            reassert,
            "if (mapping.mappedProfileId() == null) return",
            "an unmapped profile must never publish a blank over a sibling's mapping"
        )
        assertContains(
            reassert,
            "publishMappingToSync()",
            "and a mapped one publishes the same row setMappedProfile does"
        )
        val refresh = viewModel
            .substringAfter("fun refreshConnectionState() {")
            .substringBefore("private fun pullAccountMapping()")
        assertContains(
            refresh,
            "runCatching { repository.publishMappingIfMapped() }",
            "the page's entry check is where the mapping is re-asserted"
        )
        assertContains(
            refresh,
            "if (next.isMapped) loadProfiles() else pullAccountMapping()",
            "an unmapped profile asks the account before it asks the viewer"
        )
        val pull = viewModel
            .substringAfter("private fun pullAccountMapping() {")
            .substringBefore("/** Asks for a code")
        assertContains(
            pull,
            "if (!SupabaseSync.isSignedIn()) return",
            "no account, nothing to ask"
        )
        assertContains(pull, "SupabaseSync.pullPrefsNow(appContext)", "the awaited prefs read")
        assertContains(
            pull,
            "val state = initialState()",
            "and the page re-derives the mapped state from the store the pull wrote"
        )
        assertContains(pull, "isMapped = state.isMapped", "the picker must go once it arrives")
    }
}
