package com.kennyb1201.kbstream.data.download

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Downloads screen's 2026-10-27 deep dive, where the failures were all quiet.
 *
 *  1. **An unreadable records blob (C1).** `read` turned a decode failure into
 *     the same empty list a fresh install has, and every download event persists
 *     a merge - so the next pause, completion or delete wrote that emptiness
 *     back and the whole queue was gone, with the bytes still in the downloader's
 *     index and no path back to them. The blob is now read RECORD BY RECORD when
 *     the list decoder rejects it, and the first unreadable blob is kept before
 *     any write replaces it.
 *  2. **Delete after finishing (C2).** The toggle matched on the record's media
 *     key, which on a series episode is the SHOW's - so finishing S01E01 deleted
 *     every completed episode of that show.
 *  3. **A refused Retry (C3).** The Downloads screen called the manager's retry
 *     and dropped the result, so a refusal said nothing at all.
 *  4. **The header total (C4).** It summed known lengths only, and a completed
 *     adaptive download has none - so the header said zero for bytes its own row
 *     displayed in full.
 *
 * The store cases run against real preferences (Robolectric, as the add-on and
 * built-catalog read-safety tests do); the rest are pure.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = DownloadDeepDiveContractTest.NoopApplication::class)
class DownloadDeepDiveContractTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** The store the app itself would use, profile scoping included. */
    private fun store() = context.getSharedPreferences(
        ProfileStorage.prefsName(context, DownloadsStore.PREFS_NAME),
        Context.MODE_PRIVATE
    )

    private fun rawBlob(): String = store().getString(DownloadsStore.KEY_RECORDS, null).orEmpty()

    private fun writeRaw(raw: String) {
        store().edit().putString(DownloadsStore.KEY_RECORDS, raw).commit()
    }

    @Before
    fun setUp() {
        store().edit().clear().commit()
    }

    private fun record(
        id: String,
        state: DownloadState = DownloadState.DOWNLOADED,
        addedAt: Long = 1L,
        episode: Int = 1
    ) = DownloadRecord(
        id = id,
        key = "key-$id",
        mediaKey = "show",
        season = 1,
        episode = episode,
        title = "Title $id",
        qualityLabel = "1080p",
        sourceUrl = "https://example.test/$id.mp4",
        state = state,
        addedAt = addedAt,
        updatedAt = addedAt
    )

    // ── C1: the store's read ────────────────────────────────────────

    @Test
    fun `a blob the list decoder rejects is still read record by record`() {
        DownloadsStore.putAll(context, listOf(record("a", addedAt = 1), record("b", addedAt = 2)))
        val whole = rawBlob()
        // The shape a write killed mid-flight leaves: the array cut off inside
        // its LAST record, so everything before it is intact JSON.
        val truncated = whole.substring(0, whole.indexOf("{\"id\":\"b\"") + 8)
        writeRaw(truncated)

        assertEquals(
            "the records the blob still holds are the records the viewer keeps",
            listOf("a"),
            DownloadsStore.records(context).map { it.id }
        )
        assertEquals(
            "and a read is not a write - the blob it could not read is left alone",
            truncated,
            rawBlob()
        )
    }

    @Test
    fun `one unreadable record does not take the readable ones with it`() {
        DownloadsStore.putAll(context, listOf(record("a", addedAt = 1), record("b", addedAt = 2)))
        writeRaw(rawBlob().replace("\"state\":\"DOWNLOADED\",\"percent\"", "\"state\":\"NONSENSE\",\"percent\""))

        assertEquals(
            "a value no build understands costs that record, not the queue",
            listOf("a"),
            DownloadsStore.records(context).map { it.id }
        )
    }

    @Test
    fun `a store with nothing written is not a broken one`() {
        assertTrue(DownloadsStore.records(context).isEmpty())

        DownloadsStore.putAll(context, listOf(record("a")))

        assertEquals(listOf("a"), DownloadsStore.records(context).map { it.id })
        assertNull(
            "there was nothing to preserve, so nothing was kept",
            store().getString(DownloadsStore.KEY_RECORDS_UNREADABLE, null)
        )
    }

    @Test
    fun `the first unreadable blob is kept before a write replaces it`() {
        val corrupt = "[{\"id\":\"a\",\"key\":\"k\",\"mediaKey\":\"m\",\"title\":\"T\",\"qualityLabel\":\"\",\"sourceUrl\":\"u\"},{"
        writeRaw(corrupt)

        DownloadsStore.putAll(context, listOf(record("c")))

        assertEquals(
            "the blob a repair would need is still on the device after the write",
            corrupt,
            store().getString(DownloadsStore.KEY_RECORDS_UNREADABLE, null)
        )
        assertEquals(
            "and the store carried on rather than locking the feature out",
            listOf("c"),
            DownloadsStore.records(context).map { it.id }
        )

        // A second unreadable blob is the same data as far as a repair goes, and
        // overwriting the kept copy with it would be the same loss one step
        // removed.
        writeRaw(corrupt + "\"id\":\"z\"")
        DownloadsStore.putAll(context, listOf(record("d")))

        assertEquals(
            "only the first copy is kept",
            corrupt,
            store().getString(DownloadsStore.KEY_RECORDS_UNREADABLE, null)
        )
    }

    @Test
    fun `the salvage walk is not fooled by braces inside a record's own text`() {
        val raw = "[{\"id\":\"a\",\"key\":\"k\",\"mediaKey\":\"m\"," +
            "\"title\":\"A {\\\"quoted\\\"} title\",\"qualityLabel\":\"\",\"sourceUrl\":\"u\"},{"

        assertEquals(
            "a title carrying braces and quotes is text, not structure",
            listOf("a"),
            DownloadsStore.salvageRecords(raw).map { it.id }
        )
    }

    // ── C2: delete after finishing ──────────────────────────────────

    @Test
    fun `finishing one episode cannot take its siblings`() {
        val first = record("ep1", addedAt = 2, episode = 1)
        val second = record("ep2", addedAt = 1, episode = 2)
        assertEquals(
            "the test needs the shape that broke it: one show, one media key",
            first.mediaKey,
            second.mediaKey
        )

        assertEquals(
            "exactly the download that finished is taken",
            listOf("ep1"),
            finishedDownloadIds(listOf(first, second), "ep1")
        )
    }

    @Test
    fun `a finished download that is not whole is not taken`() {
        val partial = record("ep1", state = DownloadState.PAUSED)

        assertTrue(
            "a retried row has no bytes to be finished with",
            finishedDownloadIds(listOf(partial), "ep1").isEmpty()
        )
        assertTrue(
            "and an id this phone does not have is a no-op",
            finishedDownloadIds(listOf(partial), "other").isEmpty()
        )
    }

    // ── C4: the header total ────────────────────────────────────────

    @Test
    fun `the header total counts a completed adaptive download's own bytes`() {
        val adaptive = record("hls").copy(
            totalBytes = DownloadSpace.UNKNOWN_LENGTH,
            bytesDownloaded = 5_000L
        )
        val known = record("mp4", addedAt = 2).copy(totalBytes = 1_000L, bytesDownloaded = 1_000L)
        val partial = record("part", state = DownloadState.DOWNLOADING, addedAt = 3)
            .copy(totalBytes = 9_000L, bytesDownloaded = 4_000L)

        assertTrue(
            "the row shows a size the header used to count as zero",
            adaptive.sizeLabel() != null
        )
        assertEquals(
            "completed rows count, and a row with no length counts its bytes",
            6_000L,
            completedDownloadBytes(listOf(adaptive, known, partial))
        )
        assertTrue(
            "partials never count (spec §7)",
            completedDownloadBytes(listOf(partial)) == 0L
        )
    }

    // ── The wiring that pins the three surfaces ─────────────────────

    @Test
    fun `the screen's retry speaks, and the finish path is scoped to one download`() {
        val store = flat("data/download/DownloadsStore.kt")
        val manager = flat("data/download/DownloadsManager.kt")
        val screen = flat("ui/mobile/DownloadsScreenMobile.kt")
        val actions = flat("ui/mobile/DownloadActions.kt")
        val player = flat("ui/player/NativePlayerActivity.kt")

        assertFalse(
            "a decode failure no longer reads as \"you never downloaded anything\"",
            store.contains("json.decodeFromString(serializer, raw) }.getOrElse { return emptyList() }")
        )
        assertTrue(
            "the salvage is what a rejected blob reads as instead",
            store.contains("val salvaged = salvageRecords(raw)") &&
                store.contains("internal fun salvageRecords(raw: String): List<DownloadRecord> {")
        )

        assertTrue(
            "the manager's finish rule is the record's own id",
            manager.contains("fun onTitleCompleted(context: Context, downloadId: String) {") &&
                manager.contains("finishedDownloadIds(DownloadsStore.records(app), downloadId)")
        )
        assertFalse(
            "the title-key filter that deleted the whole show is gone",
            manager.contains("filter { it.state.isComplete && it.mediaKey in keys }")
        )
        assertTrue(
            "and the player names the download that finished",
            player.contains("DownloadsManager.onTitleCompleted(this@NativePlayerActivity, id)") &&
                !player.contains("listOf(record.mediaKey)")
        )

        assertTrue(
            "the Downloads screen's retry goes through the shared answer",
            screen.contains("scope.launch { retryDownload(context, record) }")
        )
        assertFalse(
            "the call whose result was dropped is gone",
            screen.contains("DownloadsManager.retry(context, record.id)")
        )
        assertTrue(
            "which is one wording for both surfaces that offer a Retry",
            actions.contains(
                "internal suspend fun retryDownload(context: Context, record: DownloadRecord) {"
            ) &&
                actions.contains("if (result is DownloadEnqueueResult.Refused) {") &&
                actions.contains("retryDownload(context, record)")
        )
    }

    // ── Source reading ──────────────────────────────────────────────

    private fun flat(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

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
}
