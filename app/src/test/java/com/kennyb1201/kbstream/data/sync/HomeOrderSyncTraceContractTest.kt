package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home order's sync legs, named per key (spec 2026-11-27).
 *
 * A rail arrangement edited on the phone that the TV never reflects has two
 * possible culprits and the report used to read the same for both: the phone
 * never published the row, or the TV pulled it and its last-write-wins guard
 * dropped it as older than its own stamp. These cases pin the record and the
 * `sync home-order:` line that separates them - a push's stamp, a rejected
 * pull with both stamps, and an adopted pull with the stamp the store now
 * holds.
 */
class HomeOrderSyncTraceContractTest {

    private fun time(ms: Long): String = "t$ms"

    private fun line(
        localStampMs: Long = 0L,
        pushedUpdatedAtMs: Long = 0L
    ): String =
        homeOrderSyncLine(
            localStampMs = localStampMs,
            pushedUpdatedAtMs = pushedUpdatedAtMs,
            lastPull = HomeOrderSyncTrace.lastPull(),
            time = ::time
        )

    @Test
    fun `a push records the stamp it published`() {
        HomeOrderSyncTrace.reset()

        HomeOrderSyncTrace.record(
            side = HomeOrderSyncTrace.PUSH,
            updatedAt = 1_700_000_000_000L,
            localStamp = 1_700_000_000_000L,
            decision = HomeOrderSyncTrace.PUBLISHED,
            profileId = "profile-a"
        )

        val event = HomeOrderSyncTrace.events().single()
        assertEquals(HomeOrderSyncTrace.PUSH, event.side)
        assertEquals(HomeOrderSyncTrace.PUBLISHED, event.decision)
        assertEquals(1_700_000_000_000L, event.updatedAt)
        assertEquals("profile-a", event.profileId)

        val line = line(
            localStampMs = 1_700_000_000_000L,
            pushedUpdatedAtMs = 1_700_000_000_000L
        )
        assertTrue("the published stamp is on the line: $line", line.contains("pushed=t1700000000000"))
        assertTrue("and this device's own stamp too: $line", line.contains("local=t1700000000000"))
        assertTrue("no pull happened yet: $line", line.contains("pulled=none"))
    }

    @Test
    fun `a pull older than the local stamp records rejected-stale with both stamps`() {
        HomeOrderSyncTrace.reset()

        HomeOrderSyncTrace.record(
            side = HomeOrderSyncTrace.PULL,
            updatedAt = 1_700_000_000_000L,
            localStamp = 1_700_000_060_000L,
            decision = HomeOrderSyncTrace.REJECTED_STALE,
            profileId = "profile-a"
        )

        val event = HomeOrderSyncTrace.lastPull()
        assertTrue("the pull is the last pull: $event", event != null)
        assertEquals(HomeOrderSyncTrace.REJECTED_STALE, event?.decision)
        assertEquals(1_700_000_000_000L, event?.updatedAt)
        assertEquals(1_700_000_060_000L, event?.localStamp)

        val line = line(
            localStampMs = 1_700_000_060_000L,
            pushedUpdatedAtMs = 1_700_000_060_000L
        )
        assertTrue(
            "the decision and both stamps are on one line, so a real newer edit " +
                "and clock skew can be told apart: $line",
            line.contains(
                "pulled=rejected-stale remote=t1700000000000 local=t1700000060000"
            )
        )
    }

    @Test
    fun `a pull newer than the local stamp records adopted with the stamp now held`() {
        HomeOrderSyncTrace.reset()

        HomeOrderSyncTrace.record(
            side = HomeOrderSyncTrace.PULL,
            updatedAt = 1_700_000_120_000L,
            localStamp = 1_700_000_000_000L,
            decision = HomeOrderSyncTrace.ADOPTED,
            profileId = "profile-a"
        )

        // The store now holds the remote stamp: a merge never stamps `now`.
        val line = line(
            localStampMs = 1_700_000_120_000L,
            pushedUpdatedAtMs = 1_700_000_000_000L
        )
        assertTrue(
            "the adopted decision, the remote stamp it came from, and the local " +
                "stamp at the time: $line",
            line.contains("pulled=adopted remote=t1700000120000 local=t1700000000000")
        )
        assertTrue(
            "the line reports the store's stamp after the adoption, not the old one: $line",
            line.contains("sync home-order: local=t1700000120000")
        )
    }

    @Test
    fun `a blank pull is its own decision, not an absent one`() {
        HomeOrderSyncTrace.reset()

        HomeOrderSyncTrace.record(
            side = HomeOrderSyncTrace.PULL,
            updatedAt = 0L,
            localStamp = 1_700_000_000_000L,
            decision = HomeOrderSyncTrace.BLANK_IGNORED,
            profileId = null
        )

        val line = line(localStampMs = 1_700_000_000_000L)
        assertTrue(
            "a pull that carried no arrangement says so: $line",
            line.contains("pulled=blank-ignored remote=never local=t1700000000000")
        )
    }

    @Test
    fun `the trace is bounded and never carries the arrangement`() {
        HomeOrderSyncTrace.reset()

        repeat(30) { index ->
            HomeOrderSyncTrace.record(
                side = HomeOrderSyncTrace.PUSH,
                updatedAt = index.toLong(),
                localStamp = index.toLong(),
                decision = HomeOrderSyncTrace.PUBLISHED,
                profileId = "p"
            )
        }

        val events = HomeOrderSyncTrace.events()
        assertEquals("the record keeps the last 24 touches", 24, events.size)
        assertEquals("oldest dropped first", 6L, events.first().updatedAt)
        assertEquals("newest kept last", 29L, events.last().updatedAt)

        val line = line(localStampMs = 29L, pushedUpdatedAtMs = 29L)
        assertFalse("rail keys and names stay out of the line", line.contains("rail"))
        assertFalse("and so does the arrangement blob", line.contains("{"))
    }

    @Test
    fun `the three recording points are wired`() {
        val root = sourceRoot()
        val applier = File(root, "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt").readText()
        val sync = File(root, "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt").readText()
        val diagnostics = File(root, "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt").readText()

        assertTrue(
            "the push is recorded where the row enters the outbox",
            sync.contains("if (prefKey == PrefsPayloadBuilder.KEY_HOME_ORDER)") &&
                sync.contains("side = HomeOrderSyncTrace.PUSH")
        )
        listOf(
            "HomeOrderSyncTrace.REJECTED_STALE",
            "HomeOrderSyncTrace.ADOPTED",
            "HomeOrderSyncTrace.MERGED",
            "HomeOrderSyncTrace.BLANK_IGNORED"
        ).forEach { decision ->
            assertTrue("the pull records $decision", applier.contains(decision))
        }
        assertTrue(
            "the guard's decision is unchanged - the record sits beside it",
            applier.contains("remoteUpdated < localStamp")
        )
        assertTrue(
            "the diagnostics carry the line",
            diagnostics.contains("homeOrderSyncLine(") &&
                diagnostics.contains("SupabaseSync.lastPushedHomeOrderAt(") &&
                diagnostics.contains("PrefsPayloadBuilder.homeOrderSyncedAt(")
        )
    }

    private fun sourceRoot(): File {
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
