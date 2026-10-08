package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IPTV config blob's publish/apply rules.
 *
 * The failure this pins is the reported one: "the guest profile's IPTV didn't
 * sync, every other profile does". A guest profile is exactly the profile a
 * second device has nothing set up for, and this blob was the last full-replace
 * one publishing `updatedAt = now` from every bulk push with no gate — so the
 * device with no playlist re-stamped its EMPTY config and won the cloud race on
 * every push, and the applier's `iptv_synced_at` stamp (written even when the
 * apply changed nothing) blocked the real config from landing anywhere.
 */
class IptvConfigRulesTest {

    @Test
    fun `a playlist counts as configured, and so does an extra one`() {
        assertTrue(
            IptvConfigRules.looksConfigured("http://guide.test/x.m3u", emptyList())
        )
        assertTrue(
            IptvConfigRules.looksConfigured("", listOf("http://guide.test/y.m3u"))
        )
    }

    @Test
    fun `an empty profile is not configured`() {
        assertFalse(IptvConfigRules.looksConfigured("", emptyList()))
        assertFalse(IptvConfigRules.looksConfigured("   ", listOf("", "  ")))
    }

    @Test
    fun `the first sight of a real config claims an edit`() {
        // Upgrade case: a device with a playlist predates the change tracker, so
        // nobody has seen its signature yet - only a config that looks deliberate
        // may claim an edit there, never the empty default.
        assertTrue(
            IptvConfigRules.shouldStampEdit(
                previousSignature = null,
                signature = "http://guide.test/x.m3u",
                configured = true
            )
        )
        assertFalse(
            IptvConfigRules.shouldStampEdit(
                previousSignature = null,
                signature = "",
                configured = false
            )
        )
    }

    @Test
    fun `a changed config claims an edit, an unchanged rebuild does not`() {
        assertTrue(
            IptvConfigRules.shouldStampEdit(
                previousSignature = "a",
                signature = "b",
                configured = true
            )
        )
        // A clear is a change too: the arrangement went away deliberately, and
        // that has to travel (the next device, not this one, is the one that
        // must stop showing the old source).
        assertTrue(
            IptvConfigRules.shouldStampEdit(
                previousSignature = "a",
                signature = "",
                configured = false
            )
        )
        assertFalse(
            IptvConfigRules.shouldStampEdit(
                previousSignature = "a",
                signature = "a",
                configured = true
            )
        )
    }

    @Test
    fun `a device that never edited publishes nothing`() {
        // The whole report in one assertion: no local edit (0) means no publish,
        // whatever the account copy looks like - an empty config can never travel.
        assertFalse(IptvConfigRules.shouldPublish(localEditedAt = 0L, cloudEditedAt = 0L))
        assertFalse(IptvConfigRules.shouldPublish(localEditedAt = 0L, cloudEditedAt = 5L))
    }

    @Test
    fun `a device publishes only ahead of the copy it adopted`() {
        assertTrue(IptvConfigRules.shouldPublish(localEditedAt = 6L, cloudEditedAt = 5L))
        assertFalse(IptvConfigRules.shouldPublish(localEditedAt = 5L, cloudEditedAt = 5L))
        assertFalse(IptvConfigRules.shouldPublish(localEditedAt = 4L, cloudEditedAt = 5L))
    }

    @Test
    fun `a never-edited device adopts the account config`() {
        assertTrue(IptvConfigRules.shouldApply(remoteUpdated = 5L, localEditedAt = 0L))
    }

    @Test
    fun `the published stamp is the edit time, never the push time`() {
        assertEquals(1_000L, IptvConfigRules.publishStamp(editedAt = 1_000L))
        assertFalse(
            "a never-edited device must publish 0, not now",
            IptvConfigRules.publishStamp(editedAt = 0L) > 0L
        )
    }

    @Test
    fun `an older remote config never overwrites a newer local edit`() {
        assertFalse(IptvConfigRules.remoteConfigWins(remoteEditedAt = 4L, localEditedAt = 5L))
        assertFalse(IptvConfigRules.remoteConfigWins(remoteEditedAt = 5L, localEditedAt = 5L))
        assertTrue(IptvConfigRules.remoteConfigWins(remoteEditedAt = 6L, localEditedAt = 5L))
    }

    @Test
    fun `an older remote blob no longer outranks a local config`() {
        // The old guard compared the remote against `iptv_synced_at`, a key the
        // applier stamped even when it applied NOTHING - so an empty blob from a
        // device that had never configured IPTV could block the configured one.
        // The only question now is whether the remote edit beats OUR edit.
        assertFalse(IptvConfigRules.shouldApply(remoteUpdated = 5L, localEditedAt = 7L))
        assertFalse(
            "an equal stamp is not a new edit either",
            IptvConfigRules.shouldApply(remoteUpdated = 7L, localEditedAt = 7L)
        )
        assertTrue(IptvConfigRules.shouldApply(remoteUpdated = 8L, localEditedAt = 7L))
    }

    @Test
    fun `a blob from a build that predates the stamp is still applied`() {
        assertTrue(IptvConfigRules.shouldApply(remoteUpdated = null, localEditedAt = 7L))
    }
}
