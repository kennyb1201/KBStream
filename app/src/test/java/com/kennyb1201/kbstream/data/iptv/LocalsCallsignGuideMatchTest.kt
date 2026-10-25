package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local affiliates survive the import prune AND resolve in the lineup.
 *
 * Field report (2026-10-25, phone): the epgshare01 US locals guide was
 * configured as a second Extra EPG source, IMPORT GUIDE reported "Imported
 * 10,087 programs", and every local still read "No program data" - ABC KERO-TV,
 * KGTV, KGUN-TV, KITV ...; CBS KMTV-TV, KUTV ... The guide identifies a local by
 * callsign alone (`<channel id="KMTV">`), while iptv-org's playlist spells the
 * same station `CBS.us@KMTVTV` and calls it `CBS KMTV-TV`.
 *
 * Both halves of that failure are load-bearing and each hides the other:
 *
 *  - the importer PRUNES a guide channel no playlist key can reach, so those
 *    programs never reached the database at all (hence 10,087 rather than
 *    ~550k), and a matcher-only fix would change nothing;
 *  - the matcher needs the same key to attach what IS stored, or the prune
 *    keeps rows no lineup query can ever reach.
 *
 * So this runs the production path end to end against a real database: the real
 * `loadPlaylist` caches a locals playlist, the real `importGuide` derives its
 * prune keys from that cache, and the real lineup query matches and reads back.
 * Only the wire is synthetic.
 */
@RunWith(AndroidJUnit4::class)
class LocalsCallsignGuideMatchTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val playlistUrl = "https://provider.example/locals.m3u"
    private val guideUrl = "https://guides.example/locals.xml"

    /**
     * Three locals: two the guide carries by callsign, and one it does not (the
     * control that proves a callsign cannot invent a match).
     */
    private val m3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="ABC.us@KGTV" group-title="Locals",ABC KGTV
        https://stream.example/kgtv.m3u8
        #EXTINF:-1 tvg-id="CBS.us@KMTVTV" group-title="Locals",CBS KMTV-TV
        https://stream.example/kmtv.m3u8
        #EXTINF:-1 tvg-id="ABC.us@WFTV" group-title="Locals",ABC WFTV
        https://stream.example/wftv.m3u8
    """.trimIndent()

    @Test
    fun `callsign-only locals are imported and read back by the lineup`() = runBlocking {
        // On Dispatchers.IO throughout: the database here is the real
        // file-backed one, which refuses main-thread queries.
        withContext(Dispatchers.IO) { importAndReadLineup() }
    }

    private suspend fun importAndReadLineup() {
        // playlistGuideMatchKeys reads the profile's cached playlist from these
        // prefs - the same store the ViewModel writes when a playlist is set.
        prefs().edit().putString("playlist_url", playlistUrl).commit()

        val now = System.currentTimeMillis()
        val start = now - 30 * 60_000L
        val stop = now + 30 * 60_000L

        val repository = IptvRepository(context, clientServing(guide = guideXml(start, stop)))
        val playlist = repository.loadPlaylist(playlistUrl)
        assertEquals(3, playlist.channels.size)

        val outcome = repository.importGuide(guideUrl)

        val kept = (outcome as? GuideImportOutcome.Imported)?.programs
            ?: error("the import did not store a body: $outcome")
        assertTrue(
            "callsign-only guide channels must survive the prune, which is what " +
                "kept the field import at a ten-thousand-program count",
            kept >= 2
        )

        val rows = repository.observeLineupWithGuides(
            playlist = playlist,
            epgUrls = listOf(guideUrl),
            windowStart = now - 6 * 60 * 60_000L,
            windowEnd = now + 12 * 60 * 60_000L
        ).first()

        val byName = rows.associateBy { it.channel.name }

        val kgtv = byName.getValue("ABC KGTV")
        assertEquals(
            "a callsign match is not an ID match: the guide's id is the callsign",
            EpgMatchType.NAME_MATCH,
            kgtv.epgMatchType
        )
        assertEquals("KGTV News", kgtv.now?.title)

        // KMTVTV / KMTV-TV against the guide's `KMTV` is the station-suffix
        // case, and its OWN program is what proves there is no cross-market
        // borrowing: two affiliates of one network are different stations.
        val kmtv = byName.getValue("CBS KMTV-TV")
        assertEquals("KMTV News", kmtv.now?.title)

        val wftv = byName.getValue("ABC WFTV")
        assertEquals(
            "a station the guide does not carry stays unmatched",
            EpgMatchType.NO_MATCH,
            wftv.epgMatchType
        )
        assertNull(wftv.now)
    }

    private fun prefs() = context.getSharedPreferences(
        ProfileStorage.prefsName(context, GuideFiles.PREFS),
        Context.MODE_PRIVATE
    )

    /** Two callsign-identified channels, one program each, with distinct titles. */
    private fun guideXml(startMs: Long, stopMs: Long): String {
        fun at(millis: Long) = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss Z")
            .format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="KGTV"><display-name>KGTV</display-name></channel>
              <channel id="KMTV"><display-name>KMTV</display-name></channel>
              <programme start="${at(startMs)}" stop="${at(stopMs)}" channel="KGTV">
                <title>KGTV News</title>
              </programme>
              <programme start="${at(startMs)}" stop="${at(stopMs)}" channel="KMTV">
                <title>KMTV News</title>
              </programme>
            </tv>
        """.trimIndent()
    }

    /**
     * An OkHttp client that answers the playlist URL with the locals M3U and the
     * guide URL with [guide], so no test depends on the network.
     */
    private fun clientServing(guide: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val isPlaylist = url.contains("locals.m3u")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        (if (isPlaylist) m3u else guide)
                            .toResponseBody((if (isPlaylist) "text/plain" else "application/xml").toMediaType())
                    )
                    .build()
            }
            .build()
}
