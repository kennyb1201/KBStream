package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which guide files a maintenance pass may delete outright.
 *
 * This is the part of the sweep that has to be wrong-proof: it runs unattended
 * from a background worker, and every file it names is a whole guide a profile
 * would have to download again. The rest of [GuideStorage.sweep] is a prune and
 * a VACUUM, which either work or are skipped.
 */
class GuideStorageTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    private fun guide(name: String, ageMs: Long) =
        GuideFile(name = name, bytes = 10L * 1024 * 1024, lastModifiedMs = now - ageMs)

    private fun deletable(
        files: List<GuideFile>,
        activeFileName: String
    ): List<String> = guideFilesToDelete(files, activeFileName = activeFileName, now = now)

    @Test
    fun legacyGuideIsDroppedOnceAProfileHasItsOwn() {
        val files = listOf(
            guide("iptv_epg.db", day),
            guide("abc.iptv_epg.db", 60_000L)
        )

        assertEquals(listOf("iptv_epg.db"), deletable(files, "abc.iptv_epg.db").toList())
    }

    @Test
    fun legacyGuideIsKeptWhileItIsTheOnlyGuideThereIs() {
        val files = listOf(guide("iptv_epg.db", 400 * day))

        assertEquals(emptyList<String>(), deletable(files, "iptv_epg.db").toList())
    }

    @Test
    fun anIdleProfilesGuideIsDroppedOnceItIsPastTheWindow() {
        val files = listOf(
            guide("active.iptv_epg.db", 3_600_000L),
            guide("idle.iptv_epg.db", GuideStorage.IDLE_SWEEP_MS + day)
        )

        assertEquals(listOf("idle.iptv_epg.db"), deletable(files, "active.iptv_epg.db").toList())
    }

    @Test
    fun anIdleProfilesGuideInsideTheWindowIsKept() {
        val files = listOf(
            guide("active.iptv_epg.db", 3_600_000L),
            guide("other.iptv_epg.db", GuideStorage.IDLE_SWEEP_MS - day)
        )

        assertEquals(emptyList<String>(), deletable(files, "active.iptv_epg.db").toList())
    }

    @Test
    fun theActiveGuideIsNeverDroppedHoweverOldItIs() {
        val files = listOf(guide("active.iptv_epg.db", 900 * day))

        assertEquals(emptyList<String>(), deletable(files, "active.iptv_epg.db").toList())
    }

    @Test
    fun theLegacyDropAndTheIdleSweepCanLandInOnePass() {
        val files = listOf(
            guide("iptv_epg.db", 200 * day),
            guide("active.iptv_epg.db", 3_600_000L),
            guide("older-a.iptv_epg.db", 100 * day),
            guide("older-b.iptv_epg.db", 40 * day)
        )

        assertEquals(
            listOf("iptv_epg.db", "older-a.iptv_epg.db", "older-b.iptv_epg.db"),
            deletable(files, "active.iptv_epg.db").toList()
        )
    }

    @Test
    fun filesThatAreNotGuidesAreNeverNamed() {
        val files = listOf(
            GuideFile("kbstream_watch_history", 9L * 1024 * 1024, now - 900 * day),
            GuideFile("abc.kbstream_watch_history", 9L * 1024 * 1024, now - 400 * day),
            guide("iptv_epg.db", 900 * day)
        )

        // The other databases the app owns are written as it runs, and the
        // global history file is not a guide: only the legacy guide can be
        // named here, and only once a scoped guide exists — which it does not.
        assertEquals(emptyList<String>(), deletable(files, "abc.iptv_epg.db").toList())
    }
}
