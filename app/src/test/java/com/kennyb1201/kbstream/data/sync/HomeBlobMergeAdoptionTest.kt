package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.addon.AppContextHolder
import com.kennyb1201.kbstream.data.kb.KBHomeOrder
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.library.LibraryHomeRailPin
import com.kennyb1201.kbstream.data.library.LibraryHomeRails
import com.kennyb1201.kbstream.data.library.LibrarySource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a pulled blob WRITES once it has been adopted (see
 * `PrefsPayloadApplier.applyHomeOrder` / `applyLibraryHomeRails`).
 *
 * Kenny, 2026-11-09: in one session, on both devices, the Trakt and MDBList
 * pinned lists vanished from Home and the arranged BingeCat rail moved to the
 * end. The guards were never the problem - the adoptions replaced wholesale, so
 * a stale copy deleted a placement and a pin it had never heard of. These tests
 * pin the merge, both blobs, at the applier's own entry point: the guards are
 * unchanged (an older stamped copy is still rejected whole, a blank blob is
 * still never adopted), only the decision to adopt now merges.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = HomeBlobMergeAdoptionTest.NoopApplication::class)
class HomeBlobMergeAdoptionTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // The stores mirror to the account on a successful write; this test is
        // about what lands locally, not about WorkManager.
        AppContextHolder.appContext = null
        orderPrefs().edit().clear().commit()
        railsPrefs().edit().clear().commit()
        libraryPrefs().edit().clear().commit()
    }

    private fun prefs(baseName: String) =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, baseName),
            Context.MODE_PRIVATE
        )

    private fun orderPrefs() = prefs("kbstream_kb_home_order")

    private fun railsPrefs() = prefs(LibraryHomeRails.SYNC_STORE)

    private fun libraryPrefs() = prefs("kbstream_library")

    private fun apply(prefKey: String, payload: JsonObject) = runBlocking {
        PrefsPayloadApplier.apply(context, prefKey, payload)
    }

    // --- the Home order -------------------------------------------------

    private fun orderPayload(blob: KBHomeOrder, updatedAt: Long?) = buildJsonObject {
        if (updatedAt != null) put("updatedAt", updatedAt)
        put("home_order_json", KBHomeOrderPrefs.encodeBlob(blob))
    }

    @Test
    fun `an adopted order keeps a locally placed rail the remote never knew and takes the remote's move`() {
        KBHomeOrderPrefs.save(
            context,
            KBHomeOrder(
                order = listOf(
                    KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING,
                    "list:BINGECAT_LIST|arranged"
                )
            )
        )
        val remoteAt = System.currentTimeMillis() + 5_000

        apply(
            PrefsPayloadBuilder.KEY_HOME_ORDER,
            orderPayload(
                KBHomeOrder(
                    order = listOf(
                        KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU,
                        KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING
                    )
                ),
                remoteAt
            )
        )

        // The moved rail took the remote's slot, the rail only the remote has
        // arrived, and the BingeCat rail the stale blob never knew kept its
        // place instead of dropping to the end.
        assertEquals(
            listOf(
                KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU,
                KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING,
                "list:BINGECAT_LIST|arranged"
            ),
            KBHomeOrderPrefs.get(context).order
        )
    }

    @Test
    fun `an older stamped order is still rejected whole`() {
        KBHomeOrderPrefs.save(
            context,
            KBHomeOrder(order = listOf(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING))
        )
        val localStamp = orderPrefs().getLong("home_order_synced_at", 0L)

        apply(
            PrefsPayloadBuilder.KEY_HOME_ORDER,
            orderPayload(
                KBHomeOrder(order = listOf(KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE)),
                localStamp - 5_000
            )
        )

        assertEquals(
            listOf(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING),
            KBHomeOrderPrefs.get(context).order
        )
        assertEquals(localStamp, orderPrefs().getLong("home_order_synced_at", 0L))
    }

    @Test
    fun `a merged adoption stamps the remote's time, never now`() {
        KBHomeOrderPrefs.save(
            context,
            KBHomeOrder(order = listOf(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING))
        )
        val localStamp = orderPrefs().getLong("home_order_synced_at", 0L)
        val remoteAt = localStamp + 60_000

        apply(
            PrefsPayloadBuilder.KEY_HOME_ORDER,
            orderPayload(
                KBHomeOrder(
                    order = listOf(
                        KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING,
                        KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU
                    )
                ),
                remoteAt
            )
        )

        // Equal stamps adopt, so the sibling pulling this merged copy next is
        // not told it is older than what this device holds.
        assertEquals(remoteAt, orderPrefs().getLong("home_order_synced_at", 0L))
    }

    @Test
    fun `reset publishes nothing so the merge has no wipe to exempt`() {
        KBHomeOrderPrefs.save(
            context,
            KBHomeOrder(order = listOf(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING))
        )

        KBHomeOrderPrefs.reset(context)

        // Reset is a local discard: it removes the blob and never enqueues an
        // empty-but-stamped one, so there is no deliberate-wipe shape for the
        // merge to preserve. It does not publish, as before.
        assertEquals(
            "",
            (PrefsPayloadBuilder.buildHomeOrder(context)["home_order_json"]
                as kotlinx.serialization.json.JsonPrimitive).content
        )
        assertFalse(
            HomeListBlobRules.shouldPublishHomeOrder(
                (PrefsPayloadBuilder.buildHomeOrder(context)["home_order_json"]
                    as kotlinx.serialization.json.JsonPrimitive).content
            )
        )
    }

    // --- the pins -------------------------------------------------------

    private fun pin(source: String, id: String, name: String) =
        LibraryHomeRailPin(source = source, id = id, name = name)

    private fun railsPayload(pins: List<LibraryHomeRailPin>, updatedAt: Long?) =
        buildJsonObject {
            if (updatedAt != null) put("updatedAt", updatedAt)
            put(
                "rails",
                buildJsonArray {
                    pins.forEach { pin ->
                        add(
                            buildJsonObject {
                                put("source", pin.source)
                                put("id", pin.id)
                                put("name", pin.name)
                            }
                        )
                    }
                }
            )
        }

    private val traktPin = pin(LibrarySource.TRAKT_LIST.name, "my-shows", "Shows")
    private val mdblistPin = pin(LibrarySource.MDBLIST_LIST.name, "id:8123", "Films")

    @Test
    fun `an unstamped pins pull keeps a local pin the remote never knew`() {
        LibraryHomeRails.write(context, listOf(traktPin))
        val localStamp = railsPrefs().getLong(LibraryHomeRails.SYNCED_AT_KEY, 0L)

        apply(
            PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS,
            railsPayload(listOf(mdblistPin), updatedAt = null)
        )

        assertEquals(listOf(traktPin, mdblistPin), LibraryHomeRails.pins(context))
        // And it still does not claim an edit: the stamp cannot go backwards,
        // and a null remote has nothing newer to move it forward to.
        assertEquals(localStamp, railsPrefs().getLong(LibraryHomeRails.SYNCED_AT_KEY, 0L))
    }

    @Test
    fun `an unstamped pins pull takes the remote's title for the same key`() {
        LibraryHomeRails.write(context, listOf(traktPin.copy(name = "Old title")))

        apply(
            PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS,
            railsPayload(listOf(traktPin), updatedAt = null)
        )

        assertEquals(listOf(traktPin), LibraryHomeRails.pins(context))
    }

    @Test
    fun `a stamped pins pull drops a pin the remote omits`() {
        LibraryHomeRails.write(context, listOf(traktPin, mdblistPin))
        val localStamp = railsPrefs().getLong(LibraryHomeRails.SYNCED_AT_KEY, 0L)

        apply(
            PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS,
            railsPayload(listOf(traktPin), updatedAt = localStamp + 60_000)
        )

        // A stamped remote wrote its pins after this device's; the omission IS
        // the unpin (no tombstone records pin events), so it must propagate.
        assertEquals(listOf(traktPin), LibraryHomeRails.pins(context))
    }

    @Test
    fun `a pin for a list this device deleted is not adopted`() {
        val deleted = pin(LibrarySource.LOCAL_LIST.name, "id:42", "My list")
        libraryPrefs().edit().putString("personal_lists_deleted", "{\"42\":1}").commit()

        apply(
            PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS,
            railsPayload(listOf(deleted, traktPin), updatedAt = null)
        )

        assertEquals(listOf(traktPin), LibraryHomeRails.pins(context))
    }

    @Test
    fun `an older stamped pins pull is still rejected whole`() {
        LibraryHomeRails.write(context, listOf(traktPin, mdblistPin))
        val localStamp = railsPrefs().getLong(LibraryHomeRails.SYNCED_AT_KEY, 0L)

        apply(
            PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS,
            railsPayload(listOf(traktPin), updatedAt = localStamp - 5_000)
        )

        assertEquals(listOf(traktPin, mdblistPin), LibraryHomeRails.pins(context))
        assertTrue(railsPrefs().getLong(LibraryHomeRails.SYNCED_AT_KEY, 0L) >= localStamp)
    }
}
