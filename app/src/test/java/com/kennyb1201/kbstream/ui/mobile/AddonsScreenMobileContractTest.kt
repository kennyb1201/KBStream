package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.ui.addons.AddonsViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Add-ons' turn at Phase 2's rule: one `FormFactor` branch in the TV file, the
 * phone layout in a new tv-free file, and the same activity-scoped
 * `AddonsViewModel` behind both.
 *
 * A JVM test cannot compose either layout, so the dispatch, the row rule, the
 * filter, the status wording, the management calls and the dialogs are pinned
 * at their contract level - plus the two pure helpers by behaviour.
 */
class AddonsScreenMobileContractTest {

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

    private fun addonsScreen(): String =
        source("com/kennyb1201/kbstream/ui/addons/AddonsScreen.kt")

    private fun mobileAddons(): String =
        source("com/kennyb1201/kbstream/ui/mobile/AddonsScreenMobile.kt")

    private fun addon(
        id: String,
        url: String,
        name: String = id,
        resources: List<String> = listOf("catalog"),
        enabled: Boolean = true
    ) = InstalledAddon(
        manifestUrl = url,
        id = id,
        name = name,
        resources = resources,
        catalogs = emptyList(),
        enabled = enabled
    )

    @Test
    fun `add-ons dispatch on the form factor at the entry point`() {
        val screen = addonsScreen()
        val dispatch = screen.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = screen.indexOf("AddonsScreenMobile(")
        assertTrue("AddonsScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call AddonsScreenMobile", mobileCall > dispatch)
        // The call is multi-line now that it carries the header arrow's
        // callback, so the branch runs to the if-block's own closing brace.
        val branch = screen.substring(dispatch, screen.indexOf("}", mobileCall))
        assertTrue(
            "the branch must hand over the same ViewModel",
            branch.contains("viewModel = viewModel")
        )
        assertTrue(
            "the branch must hand the phone the same pop system back runs",
            branch.contains("onBack = onBack")
        )
        assertTrue(
            "the branch must return, so the TV page never composes on a phone",
            branch.contains("return")
        )
        assertTrue(
            "the TV page must still own the master-detail list",
            screen.substringAfter("AddonsScreenMobile(").contains(
                "AddonListCard("
            )
        )
    }

    @Test
    fun `the mobile add-ons screen is built on the touch twins`() {
        val mobile = mobileAddons()
        assertTrue("the screen must apply MobileTheme", mobile.contains("MobileTheme {"))
        assertTrue(
            "the page must use the touch buttons and field",
            mobile.contains("com.kennyb1201.kbstream.ui.components.touch.KBButton") &&
                mobile.contains("com.kennyb1201.kbstream.ui.components.touch.KBTextField")
        )
        assertTrue(
            "the detail pane is the touch bottom sheet plate",
            mobile.contains("KBSheetContent(") && mobile.contains("ModalBottomSheet(")
        )
        assertTrue(
            "the dialogs are the touch panel",
            mobile.contains("KBDialogPanel(")
        )
        assertFalse(
            "the mobile add-ons screen must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the mobile add-ons screen drives every management call`() {
        val mobile = mobileAddons()
        listOf(
            "refreshAllManifests",
            "checkHealth",
            "addAddon(",
            "removeAddon(",
            "renameAddon(",
            "setAddonEnabled(",
            "moveAddonUp(",
            "moveAddonDown(",
            "refreshManifest(",
            "clearError",
            "clearStatus"
        ).forEach { call ->
            assertTrue("mobile Add-ons must call $call", mobile.contains(call))
        }
        assertTrue(
            "copying re-reads the active profile's list",
            mobile.contains("AddonManager.getInstance(context).importAddons(") &&
                mobile.contains("viewModel.refresh()")
        )
        assertTrue(
            "the add-on id is the health key, exactly as on TV",
            mobile.contains("health[addon.id]")
        )
    }

    @Test
    fun `the filter collapses duplicate manifests and matches name id or resource`() {
        val duplicated = listOf(
            addon(id = "cinemeta", url = "https://a/manifest.json", name = "Cinemeta"),
            addon(id = "cinemeta", url = "https://a/manifest.json", name = "Cinemeta again"),
            addon(
                id = "watchhub",
                url = "https://b/manifest.json",
                name = "WatchHub",
                resources = listOf("stream")
            )
        )

        assertEquals(
            "one row per manifest URL, in install order",
            listOf("https://a/manifest.json", "https://b/manifest.json"),
            mobileAddonRows(duplicated, "").map { it.manifestUrl }
        )
        assertEquals(
            "a name matches",
            listOf("https://b/manifest.json"),
            mobileAddonRows(duplicated, "watch").map { it.manifestUrl }
        )
        assertEquals(
            "a manifest id matches",
            listOf("https://a/manifest.json"),
            mobileAddonRows(duplicated, " CINEMETA ").map { it.manifestUrl }
        )
        assertEquals(
            "a resource matches",
            listOf("https://b/manifest.json"),
            mobileAddonRows(duplicated, "stream").map { it.manifestUrl }
        )
        assertTrue(
            "no match is an empty list, not the whole list",
            mobileAddonRows(duplicated, "nothing-here").isEmpty()
        )
    }

    @Test
    fun `the status line follows the tv wording`() {
        val installed = addon(id = "x", url = "https://x/manifest.json")
        assertEquals(
            "DISABLED — kept installed, not used anywhere",
            mobileAddonStatusLine(installed.copy(enabled = false), null)
        )
        assertEquals(
            "Unknown — CHECK HEALTH to verify",
            mobileAddonStatusLine(installed, null)
        )
        assertEquals(
            "Online",
            mobileAddonStatusLine(installed, AddonsViewModel.AddonHealth(true, 0L))
        )
        assertEquals(
            "Offline",
            mobileAddonStatusLine(installed, AddonsViewModel.AddonHealth(false, 0L))
        )
    }

    @Test
    fun `removing an add-on is confirmed before it happens`() {
        val mobile = mobileAddons()
        val confirm = mobile.indexOf("ConfirmRemoveAddonDialogMobile(")
        assertTrue("remove must raise a confirmation", confirm >= 0)
        val block = mobile.substring(confirm, mobile.length.coerceAtMost(confirm + 500))
        assertTrue(
            "the confirmation is what calls removeAddon",
            block.contains("viewModel.removeAddon(addon.id)")
        )
        assertTrue(
            "the sheet's REMOVE opens the confirmation instead of deleting",
            mobile.contains("removeTarget = addon") &&
                mobile.contains("danger = true")
        )
    }
}
