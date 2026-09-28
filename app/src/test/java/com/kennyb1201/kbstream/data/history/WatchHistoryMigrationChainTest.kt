package com.kennyb1201.kbstream.data.history

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit finding this closes: watch history — resume points and watched
 * state, i.e. user data, not a cache like the guide database — sat one schema
 * change away from being wiped, because the version could be bumped without
 * anyone being able to tell whether the matching Migration existed.
 *
 * Three things are pinned here, all of them on the JVM so they run in the same
 * `./gradlew testDebugUnitTest` gate as everything else:
 *
 *  - every version above the wiped legacy range has a Migration, contiguously,
 *    up to [WATCH_HISTORY_DB_VERSION];
 *  - the destructive fallback that wipes those legacy versions cannot reach a
 *    version that has (or should have) a Migration — widening it is the one
 *    edit that drops a user's history and the durable sync_outbox with it;
 *  - the exported schema committed under app/schemas/ is the version the code
 *    declares, so the record a Migration is reviewed against is real.
 *
 * What this deliberately does NOT do is exercise the migrations against a real
 * database: that needs [androidx.room.testing.MigrationTestHelper] and an
 * Android runtime (Robolectric or an instrumented test), neither of which this
 * project has. Room itself is the runtime backstop — a missing migration throws
 * on open rather than silently dropping tables — and these tests are the
 * pre-merge one.
 */
class WatchHistoryMigrationChainTest {

    /** (from, to) for every registered migration, oldest first. */
    private val chain: List<Pair<Int, Int>>
        get() = WatchHistoryDatabase.migrations
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

    @Test
    fun `the chain starts immediately after the last wiped legacy version`() {
        assertTrue("no migrations are registered at all", chain.isNotEmpty())
        assertEquals(
            "the wiped legacy versions are ${WatchHistoryDatabase.legacyWipeVersions.toList()}, " +
                "so the first Migration must start at the version after them",
            WatchHistoryDatabase.legacyWipeVersions.max() + 1,
            chain.first().first
        )
    }

    @Test
    fun `the destructive fallback never reaches a version that must migrate`() {
        val oldestMigrated = WatchHistoryDatabase.migrations.minOf { it.startVersion }
        WatchHistoryDatabase.legacyWipeVersions.forEach { version ->
            assertTrue(
                "version $version is in fallbackToDestructiveMigrationFrom, but the chain " +
                    "migrates from $oldestMigrated — that overlap is a wipe of user data",
                version < oldestMigrated
            )
        }
    }

    @Test
    fun `the chain is contiguous, with no gaps and no duplicates`() {
        chain.zipWithNext().forEach { (older, newer) ->
            assertEquals(
                "migration ${newer.first}->${newer.second} does not continue from " +
                    "${older.first}->${older.second}: an install on version " +
                    "${older.second} would have no path forward",
                older.second,
                newer.first
            )
        }
        assertEquals(
            "the same step is registered twice, so one of the two is dead code",
            chain.distinct(),
            chain
        )
    }

    @Test
    fun `the chain ends at the declared schema version`() {
        assertEquals(
            "WATCH_HISTORY_DB_VERSION is " +
                "$WATCH_HISTORY_DB_VERSION but the newest Migration lands on " +
                "${chain.last().second}: bumping the version without adding its Migration " +
                "makes Room throw on open (a device with no history and no Continue Watching)",
            WATCH_HISTORY_DB_VERSION,
            chain.last().second
        )
    }

    @Test
    fun `the committed schema is the declared version`() {
        val dir = schemaDir()
        assertTrue(
            "app/schemas is missing. The exported schema is what makes a Migration " +
                "reviewable and a missing one visible, so the room.schemaLocation KSP " +
                "argument in app/build.gradle.kts must stay and the files it writes must " +
                "be committed.",
            dir != null
        )
        val file = File(dir, "${WatchHistoryDatabase::class.java.name}/$WATCH_HISTORY_DB_VERSION.json")
        assertTrue("no exported schema for version $WATCH_HISTORY_DB_VERSION at $file", file.isFile)
        val exported = JSONObject(file.readText())
            .getJSONObject("database")
            .getInt("version")
        assertEquals(
            "the committed schema says version $exported; re-run a build so Room exports " +
                "$WATCH_HISTORY_DB_VERSION and commit the file",
            WATCH_HISTORY_DB_VERSION,
            exported
        )
    }

    /**
     * The committed schema directory. Unit tests run with the module directory
     * as their working directory; the other two spellings keep this working if
     * that ever changes.
     */
    private fun schemaDir(): File? = listOf(
        File("schemas"),
        File("app/schemas"),
        File("../app/schemas")
    ).firstOrNull { it.isDirectory }
}
