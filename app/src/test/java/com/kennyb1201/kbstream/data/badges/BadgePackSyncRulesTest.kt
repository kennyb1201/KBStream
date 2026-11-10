package com.kennyb1201.kbstream.data.badges

import com.kennyb1201.kbstream.data.badges.BadgePackSyncRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The badge pack's sync decision, on its own.
 *
 * The two shapes it has to tell apart are indistinguishable inside the pack - a
 * device that never configured one and a device whose pack was deliberately
 * removed both publish an empty `badge_pack_json` - and the two outcomes are
 * opposite: ignore, or clear the other side. These cases are the whole rule.
 */
class BadgePackSyncRulesTest {

    private val somePack = """{"filters":[{"name":"4K","pattern":"4K"}]}"""
    private val otherPack = """{"filters":[{"name":"HDR","pattern":"HDR"}]}"""

    @Test
    fun `a blank pack with no removal behind it is ignored`() {
        assertEquals(
            "a device that never had a pack must not be able to wipe another's",
            Decision.Ignore,
            BadgePackSyncRules.decide(
                remoteClearedAt = 0L,
                remotePackJson = "",
                localPackJson = somePack,
                localStamp = 1_000L
            )
        )
    }

    @Test
    fun `a pack this device does not have is adopted`() {
        assertEquals(
            Decision.AdoptPack,
            BadgePackSyncRules.decide(
                remoteClearedAt = 0L,
                remotePackJson = otherPack,
                localPackJson = somePack,
                localStamp = 1_000L
            )
        )
    }

    @Test
    fun `the same pack is not rewritten`() {
        assertEquals(
            Decision.Ignore,
            BadgePackSyncRules.decide(
                remoteClearedAt = 0L,
                remotePackJson = somePack,
                localPackJson = somePack,
                localStamp = 1_000L
            )
        )
    }

    @Test
    fun `a removal newer than this device's pack is adopted`() {
        assertEquals(
            "the removal is the newer statement, and the whole point of the stamp",
            Decision.AdoptRemoval,
            BadgePackSyncRules.decide(
                remoteClearedAt = 2_000L,
                remotePackJson = "",
                localPackJson = somePack,
                localStamp = 1_000L
            )
        )
    }

    @Test
    fun `a removal on a device that had no pack is harmless but recorded`() {
        assertEquals(
            "adopting it writes the stamp, so the next payload compares against it",
            Decision.AdoptRemoval,
            BadgePackSyncRules.decide(
                remoteClearedAt = 2_000L,
                remotePackJson = "",
                localPackJson = "",
                localStamp = 0L
            )
        )
    }

    @Test
    fun `a removal older than a pack imported after it does not undo the import`() {
        assertEquals(
            "the sibling has not seen the re-import yet; honouring its removal would " +
                "delete a pack the viewer deliberately put back",
            Decision.Ignore,
            BadgePackSyncRules.decide(
                remoteClearedAt = 1_000L,
                remotePackJson = "",
                localPackJson = somePack,
                localStamp = 2_000L
            )
        )
    }

    @Test
    fun `a payload with an older removal but a pack still adopts the pack`() {
        assertEquals(
            Decision.AdoptPack,
            BadgePackSyncRules.decide(
                remoteClearedAt = 1_000L,
                remotePackJson = otherPack,
                localPackJson = somePack,
                localStamp = 2_000L
            )
        )
    }

    @Test
    fun `a removal at exactly the local stamp is not newer`() {
        assertEquals(
            "strictly newer, the same rule the other blobs' stamps use",
            Decision.Ignore,
            BadgePackSyncRules.decide(
                remoteClearedAt = 1_000L,
                remotePackJson = "",
                localPackJson = somePack,
                localStamp = 1_000L
            )
        )
    }
}
