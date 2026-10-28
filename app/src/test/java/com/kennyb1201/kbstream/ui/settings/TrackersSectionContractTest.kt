package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Integrations pane's Trackers section, as the reorg left it.
 *
 * The section used to hold four items - Simkl, Trakt, the MDBList key and the
 * Resume Source picker. The reorg split that by what each item IS (settings
 * reorg §3.2, §3.3): the two trackers and the tie-break between them stay in
 * Trackers, and every credential moved into one contiguous API Keys block, so
 * "where do I paste a key" has one answer instead of two.
 *
 * The guardrails are the reason this is a test and not just a move:
 *  - the MDBList key is still a secret, so its FIELD can never be REVEALED on a
 *    kids profile (the card keeps its own `kidsKeysHidden` behaviour, as
 *    `revealable = false` - the field itself stays so a profile holding no key
 *    can paste one);
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
        // No closing paren on the first one: that section carries `first = true`.
        val profiles = tv.indexOf("SettingsSectionHeader(\"Profiles & Accounts\"")
        val trackers = tv.indexOf("SettingsSectionHeader(\"Trackers\")")
        val keys = tv.indexOf("SettingsSectionHeader(\"API Keys\")")

        assertTrue("all three sections must exist", profiles >= 0 && trackers >= 0 && keys >= 0)
        assertTrue("Trackers follows Profiles & Accounts", trackers > profiles)
        assertTrue("and precedes API Keys", trackers < keys)
    }

    @Test
    fun `the tv Trackers section holds Simkl, Trakt and Resume Source in order`() {
        val trackers = tv.indexOf("SettingsSectionHeader(\"Trackers\")")
        val keys = tv.indexOf("SettingsSectionHeader(\"API Keys\")")
        val simkl = tv.indexOf("label = \"Simkl\"")
        val trakt = tv.indexOf("label = \"Trakt\"")
        val resume = tv.indexOf("ResumeAuthorityRow(")

        listOf(simkl, trakt, resume).forEach { index ->
            assertTrue("every item must be inside the Trackers section", index in trackers until keys)
        }
        assertTrue("Simkl, then Trakt", simkl < trakt)
        assertTrue(
            "then Resume Source as the last row - the tie-break the two above feed " +
                "(settings reorg §3.2)",
            trakt < resume
        )
        assertFalse(
            "and the MDBList card is NOT in this section any more: it moved into " +
                "the API Keys block (§3.3)",
            tv.indexOf("\"MDBList — Connected\"") in trackers until keys
        )
    }

    @Test
    fun `the tv MDBList card moved into API Keys intact, focus hook and kids rule included`() {
        val keys = tv.indexOf("SettingsSectionHeader(\"API Keys\")")
        val cardStart = tv.indexOf("// MDBList API key: mdblist.com key enables the critic ratings")
        val cardEnd = tv.indexOf("// ── OMDB KEY (awards on the detail screen)")
        assertTrue("the card and the OMDb block must both exist", cardStart > keys && cardEnd > cardStart)

        val card = tv.substring(cardStart, cardEnd)
        assertTrue(
            "the card's click must still raise the field's focus requester",
            card.contains("val mdbListFocusRequester = remember { FocusRequester() }") &&
                card.contains("focusRequester = mdbListFocusRequester")
        )
        assertTrue(
            "and the secret field is still gated on a kids profile - the card " +
                "keeps its own kids rule in its new home: composed so a key can " +
                "be pasted, but never revealable",
            card.contains("revealable = !kidsKeysHidden")
        )
        assertFalse(
            "the field itself must not be left out again: a kids profile with no " +
                "key had no way to enter one, and the card's click raised a focus " +
                "requester attached to nothing",
            card.contains("if (!kidsKeysHidden) KBSecretField(")
        )
        assertTrue(
            "the save/verify wiring is unchanged",
            card.contains("AppPreferences.setMdbListApiKey(context, mdbListKeyInput)") &&
                card.contains("MdbListClient.verifyKey(context)")
        )
        assertTrue(
            "and it leads the block, before OMDb (§3.3)",
            cardStart in keys until cardEnd
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

        val trackers = mobile.indexOf("SettingsSectionMobile(\"Trackers\")")
        val keys = mobile.indexOf("SettingsSectionMobile(\"API Keys\")")
        val mobileTail = mobile.substring(trackers, keys)
        assertFalse(
            "phone: the resume row must not sit behind a keys-hidden branch: $mobileTail",
            mobileTail.contains("keysHidden")
        )
        assertTrue(
            "phone: and it is composed once, at pane level, closing Trackers " +
                "before the key block",
            mobileTail.trimEnd().contains("ResumeAuthorityMobile(") &&
                mobileTail.split("ResumeAuthorityMobile(").size == 2
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
        val resume = mobile.indexOf("ResumeAuthorityMobile(")
        listOf(simkl, trakt, resume).forEach { index ->
            assertTrue("every item must be inside the Trackers section", index in trackers until keys)
        }
        assertTrue("Simkl, then Trakt, then Resume Source", simkl < trakt && trakt < resume)

        // The MDBList card, by contrast, is inside the key block now.
        val mdbList = mobile.indexOf("title = \"MDBList API Key\"")
        val omdb = mobile.indexOf("title = \"OMDb API Key\"")
        assertTrue("the MDBList card lives inside API Keys", mdbList in keys until mobile.length)
        assertTrue("and leads it, before OMDb", mdbList < omdb)
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
