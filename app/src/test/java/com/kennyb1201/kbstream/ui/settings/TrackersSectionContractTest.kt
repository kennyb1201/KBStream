package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Integrations pane's Trackers section (decision, 2026-10-20).
 *
 * Simkl, Trakt, the MDBList key and the Resume Source picker are one subject -
 * the services that hold this account's viewing state, plus the tie-break
 * between them - so they live in one section between Profiles & Accounts and
 * API Keys instead of being split across two sections and two panes. Nothing
 * about the four items changed: same rows, same card, same pref.
 *
 * The guardrails are the reason this is a test and not just a move:
 *  - the MDBList key is still a secret, so its FIELD is still hidden on a kids
 *    profile (the card keeps its own `kidsKeysHidden` behaviour);
 *  - Resume Source is a behaviour preference, not a secret, so it must NOT
 *    inherit that treatment and stays usable on a kids profile;
 *  - the old "Continue Watching" section header is deleted with its only row,
 *    never left behind empty.
 */
class TrackersSectionContractTest {

    private val tv = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val mobile = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    @Test
    fun `the tv pane puts Trackers between Profiles & Accounts and API Keys`() {
        val profiles = tv.indexOf("SettingsSectionHeader(\"Profiles & Accounts\")")
        val trackers = tv.indexOf("SettingsSectionHeader(\"Trackers\")")
        val keys = tv.indexOf("SettingsSectionHeader(\"API Keys\")")

        assertTrue("all three sections must exist", profiles >= 0 && trackers >= 0 && keys >= 0)
        assertTrue("Trackers follows Profiles & Accounts", trackers > profiles)
        assertTrue("and precedes API Keys", trackers < keys)
    }

    @Test
    fun `the tv Trackers section holds the four items in order`() {
        val trackers = tv.indexOf("SettingsSectionHeader(\"Trackers\")")
        val keys = tv.indexOf("SettingsSectionHeader(\"API Keys\")")
        val simkl = tv.indexOf("label = \"Simkl\"")
        val trakt = tv.indexOf("label = \"Trakt\"")
        // The card's own connected/not-connected line: the field lives in it.
        val mdbList = tv.indexOf("\"MDBList — Connected\"")
        val resume = tv.indexOf("ResumeAuthorityRow(")

        listOf(simkl, trakt, mdbList, resume).forEach { index ->
            assertTrue("every item must be inside the Trackers section", index in trackers until keys)
        }
        assertTrue("Simkl, then Trakt", simkl < trakt)
        assertTrue("then the MDBList key card", trakt < mdbList)
        assertTrue("then Resume Source as the last row", mdbList < resume)
    }

    @Test
    fun `the tv MDBList card moved intact, secret field and focus hook included`() {
        val card = tv.substring(
            tv.indexOf("// MDBList API key: mdblist.com key enables the critic ratings"),
            tv.indexOf("// Resume Source: which tracker's resume point wins")
        )
        assertTrue(
            "the card's click must still raise the field's focus requester",
            card.contains("val mdbListFocusRequester = remember { FocusRequester() }") &&
                card.contains("focusRequester = mdbListFocusRequester")
        )
        assertTrue(
            "and the secret field is still gated on a kids profile - the card " +
                "hides its own field in its new home too",
            card.contains("if (!kidsKeysHidden) KBSecretField(")
        )
        assertTrue(
            "the save/verify wiring is unchanged",
            card.contains("AppPreferences.setMdbListApiKey(context, mdbListKeyInput)") &&
                card.contains("MdbListClient.verifyKey(context)")
        )
        assertTrue(
            "and it is no longer under the API Keys heading",
            tv.indexOf("\"MDBList — Connected\"") < tv.indexOf("SettingsSectionHeader(\"API Keys\")")
        )
    }

    @Test
    fun `Resume Source is not gated like a secret on either surface`() {
        // A behaviour pref, not a credential: it must stay visible and usable on
        // a kids profile, so nothing between the row and the next section may
        // be a kidsKeysHidden/keysHidden wrapper.
        val tvRow = tv.indexOf("ResumeAuthorityRow(")
        val tvAfter = tv.substring(tvRow, tv.indexOf("SettingsSectionHeader(\"API Keys\")", tvRow))
        assertFalse(
            "TV: the resume row must not sit behind the API Keys kids guard: $tvAfter",
            tvAfter.contains("kidsKeysHidden")
        )

        val mobileRow = mobile.indexOf("ResumeAuthorityMobile(")
        val mobileTail = mobile.substring(
            mobile.indexOf("savedDescription = \"Key saved — ratings appear on the next title you open.\""),
            mobileRow
        )
        assertFalse(
            "phone: the resume row must sit OUTSIDE the MDBList keys-hidden branch, " +
                "which has to close before it: $mobileTail",
            mobileTail.contains("keysHidden")
        )
        assertTrue(
            "phone: and that branch really is closed by the time the row composes",
            mobileTail.contains("}")
        )
    }

    @Test
    fun `the mobile pane mirrors the structure`() {
        // No closing paren: the first section carries `first = true`.
        val profiles = mobile.indexOf("SettingsSectionMobile(\"Profiles & Accounts\"")
        val trackers = mobile.indexOf("SettingsSectionMobile(\"Trackers\")")
        val keys = mobile.indexOf("SettingsSectionMobile(\"API Keys\")")

        assertTrue("all three sections must exist", profiles >= 0 && trackers >= 0 && keys >= 0)
        assertTrue("Trackers follows Profiles & Accounts", trackers > profiles)
        assertTrue("and precedes API Keys", trackers < keys)

        val simkl = mobile.indexOf("label = \"Simkl\"")
        val trakt = mobile.indexOf("label = \"Trakt\"")
        val mdbList = mobile.indexOf("title = \"MDBList API Key\"")
        val resume = mobile.indexOf("ResumeAuthorityMobile(")
        listOf(simkl, trakt, mdbList, resume).forEach { index ->
            assertTrue("every item must be inside the Trackers section", index in trackers until keys)
        }
        assertTrue("Simkl, then Trakt, then MDBList, then Resume", simkl < trakt && trakt < mdbList)
        assertTrue("with Resume Source last", mdbList < resume)

        assertTrue(
            "and MDBList left API Keys",
            mobile.indexOf("title = \"MDBList API Key\"") <
                mobile.indexOf("SettingsSectionMobile(\"API Keys\")")
        )
        assertFalse(
            "phone: no orphaned Continue Watching header",
            mobile.contains("SettingsSectionMobile(\"Continue Watching\")")
        )
    }

    @Test
    fun `the kids add-on lock and the tracker rows still hide as before`() {
        // Whatever gating these had, they keep: the Add-ons door is still behind
        // the profile's own lock on both surfaces.
        assertTrue(
            "TV: the Add-ons row stays behind kidsAddonLock",
            tv.contains("if (!kidsAddonLock) {") &&
                tv.contains("profile?.kidsMaxAge != null && profile.kidsHideAddons")
        )
        assertTrue(
            "phone: the same lock",
            mobile.contains("if (!kidsAddonLock) {") &&
                mobile.contains("activeProfile?.kidsMaxAge != null && activeProfile?.kidsHideAddons == true")
        )
        // The tracker rows themselves are unconditional in both panes; the
        // connect screens own their own kids/PIN gating.
        listOf("Simkl", "Trakt").forEach { label ->
            assertTrue("TV: $label is an unconditional row", tv.contains("label = \"$label\","))
            assertTrue("phone: $label is an unconditional row", mobile.contains("label = \"$label\","))
        }
    }

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findMainSourceRoot(): File {
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
}
