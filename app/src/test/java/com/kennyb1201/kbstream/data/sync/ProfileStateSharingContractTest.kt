package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the profile blob now carries BESIDE the profile itself (spec audit
 * 2026-11-18: Settings M1, Settings M2, Data M5).
 *
 * Three facts that only mean anything on a SECOND device, which is exactly why
 * they were wrong for a year without anyone seeing it:
 *
 *  - Settings M1 - the kids daily budget's USED counter. It lived in this
 *    device's own prefs, so Willow could hit her limit on the Onn and start
 *    again from zero on the phone. The counter is the profile's now and rides
 *    the blob; the merge is MAX, because usage only grows inside a day.
 *  - Settings M2 - the profile PIN's hash. It was deliberately device-local
 *    ("a device secret"), so a PIN set on one TV left the same child's tile open
 *    on every other one. Reversed on Kenny's call: it is account state.
 *  - Data M5 - MDBList's daily spend. It is one key with one ~1000/day
 *    allowance, and the ledger was per device, so three devices each believed
 *    they had the whole day while the key was already out.
 */
class ProfileStateSharingContractTest {

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

    private val manager = squash(source("com/kennyb1201/kbstream/data/sync/ProfileManager.kt"))
    private val guard = squash(source("com/kennyb1201/kbstream/data/sync/KidsTimeGuard.kt"))
    private val mdbList = squash(source("com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"))

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing anchor: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing end anchor: $to", end > start)
        return text.substring(start, end)
    }

    private val blob = slice(manager, "fun profilesSyncBlob(", "private fun pushProfilesBlob(")
    private val apply = slice(manager, "fun applyProfilesPayload(", "val activeBefore =")

    @Test
    fun `the PIN hash is account state now, and an older blob cannot erase it`() {
        assertTrue(
            "the blob carries the hash, which is what makes one PIN cover the household",
            blob.contains("p.pinHash?.takeIf { it.isNotBlank() }?.let { put(\"pinHash\", it) }")
        )
        assertTrue(
            "and the apply side reads it",
            apply.contains("pinHash = str(\"pinHash\")?.takeIf { it.isNotBlank() },")
        )
        assertTrue(
            "a blob from a build that predates this keeps this device's own hash " +
                "instead of clearing it",
            apply.contains("if (remote.pinHash == null && !localPin.isNullOrBlank())")
        )
        assertFalse(
            "the old 'device secret, never synced' claim must not still be in the blob's prose",
            blob.contains("NO pinHash") || blob.contains("device secret, not account state")
        )
    }

    @Test
    fun `today's kids usage travels with the profile and merges by MAX`() {
        assertTrue(
            "the blob publishes the counter with the day it belongs to",
            blob.contains("put(\"kidsUsedDay\", usage.day)") &&
                blob.contains("put(\"kidsUsedSeconds\", usage.seconds)")
        )
        assertTrue(
            "only a kids profile has a budget to publish",
            blob.contains("if (p.kidsMaxAge != null) { KidsTimeGuard.usageSnapshot(context, p.id)")
        )
        val shared = slice(guard, "internal fun applySharedUsage(", "private fun bumpLiveCounter(")
        assertTrue(
            "a sibling's number is taken only for TODAY: another day's counter is another budget",
            shared.contains("if (day != today) return")
        )
        assertTrue(
            "and only when it is HIGHER - usage grows, so an overwrite would hand time back",
            shared.contains("if (seconds <= localSeconds) {")
        )
        assertTrue(
            "the live counter is bumped so the limit applies without waiting for a tick",
            shared.contains("bumpLiveCounter(profileId, day, seconds)") &&
                guard.contains("if (profileIdForCounter != profileId || dayKey != day) return")
        )
        assertTrue(
            "and the apply side runs it for every profile the blob carried",
            apply.contains("KidsTimeGuard.applySharedUsage(context, id, day, seconds)")
        )
    }

    @Test
    fun `the counter is published as it accrues, not only when a setting is edited`() {
        assertTrue(
            "the ticker publishes on a usage threshold",
            guard.contains("publishUsageIfDue(profile.id)")
        )
        // It is the last thing in the file, so there is no end anchor to slice to.
        val publish = guard.substringAfter("private fun publishUsageIfDue(")
        assertTrue(
            "every five minutes of viewing, not every 30-second tick",
            guard.contains("USAGE_PUBLISH_EVERY_SECONDS = 5 * 60") &&
                publish.contains("val bucket = todaySeconds / USAGE_PUBLISH_EVERY_SECONDS")
        )
        assertTrue(
            "through the profiles blob, which is the key the applier reads",
            publish.contains("PrefsPayloadBuilder.KEY_PROFILES") &&
                publish.contains("ProfileManager.profilesSyncBlob(context)")
        )
    }

    @Test
    fun `the MDBList ledger is the profile's, and it is shared too`() {
        assertTrue(
            "the bucket is scoped to the active profile, not to the device",
            mdbList.contains("private fun profileScopedTag(keyTag: String): String = " +
                "(activeProfileId() ?: DEVICE_SCOPE) + \"|\" + keyTag")
        )
        assertTrue(
            "a rollover starts from the higher of this device's count and a sibling's",
            mdbList.contains("val shared = if (context != null) sharedSpend(context, profileOfTag(tag), today) else 0") &&
                mdbList.contains("budget.count.set(maxOf(stored, shared))")
        )
        assertTrue(
            "the profile's own aggregate is what the blob publishes",
            mdbList.contains("putString(profileDayKey(profileId), budget.day)") &&
                blob.contains("MdbListClient.spendForActiveProfile(context)")
        )
        assertTrue(
            "the receiving side merges by MAX, same day only",
            mdbList.contains("internal fun applySharedSpend(context: Context, profileId: String, day: String, count: Int)") &&
                mdbList.contains("if (day != utcDay(System.currentTimeMillis())) return") &&
                mdbList.contains("if (count <= known) return")
        )
        assertTrue(
            "and the apply side runs it for every profile the blob carried",
            apply.contains("MdbListClient.applySharedSpend(context, id, day, count)")
        )
        assertTrue(
            "spend is published every 50 requests, so the other devices learn before the " +
                "allowance is gone rather than after",
            mdbList.contains("private const val BUDGET_PUBLISH_EVERY = 50") &&
                mdbList.contains("if (count > 0 && count % BUDGET_PUBLISH_EVERY == 0)")
        )
    }

    @Test
    fun `sharing a ledger can never RAISE what a device thinks it has left`() {
        // The direction that matters: both merges are max(), never assignment.
        // If either became an overwrite, a device that had spent less could
        // publish a smaller number and hand the profile its budget back - on a
        // limit that only exists to stop exactly that.
        val kids = slice(guard, "internal fun applySharedUsage(", "private fun bumpLiveCounter(")
        assertTrue("kids usage never lowers the counter", kids.contains("if (seconds <= localSeconds)"))
        assertTrue(
            "MDBList spend never lowers the counter",
            mdbList.contains("if (budget.day == day && budget.count.get() < count) budget.count.set(count)")
        )
    }
}
