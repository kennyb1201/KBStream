package com.kennyb1201.kbstream.data.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Play 24 fps titles in MPV" routes a title by a property of its FILE — the
 * rate and height read from its own head — not by its name or by a metadata
 * field.
 *
 * The rule's matrix lives in [PlayerEngineTest]; these pins cover the wiring a
 * matrix cannot see: the toggle is device-local and directly under the anime
 * row on both surfaces, the launch hands the engine the URL and headers it is
 * about to play, the launch probe is bounded by the launch budget rather than
 * the probe's own 6 s one, and a manifest is never probed at all.
 *
 * Read as source text, the way this tree's wiring contracts read: the decision
 * is a Context-and-prefs path with no device-free seam for the wiring itself.
 */
class FilmCadenceMpvRoutingContractTest {

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java/" + path)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private val engine: String
        get() = source("com/kennyb1201/kbstream/data/player/PlayerEngine.kt")

    private val prefs: String
        get() = source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")

    private val launchSite: String
        get() = source("com/kennyb1201/kbstream/MainActivity.kt")

    private val probe: String
        get() = source("com/kennyb1201/kbstream/ui/player/FrameRateProbe.kt")

    private val tvSettings: String
        get() = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")

    private val mobileSettings: String
        get() = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    @Test
    fun `the toggle is device-local, off by default`() {
        assertTrue(
            "the key is what makes the setting findable and clearable",
            prefs.contains("private const val KEY_MPV_FOR_24FPS_1080 = \"mpv_for_24fps_1080\"")
        )
        val getter = prefs.indexOf("fun getMpvFor24Fps1080(")
        assertTrue("the getter must exist", getter >= 0)
        assertTrue(
            "a box must not open 24 fps titles in MPV because it was asked to once",
            prefs.substring(getter, getter + 160)
                .contains("getBoolean(KEY_MPV_FOR_24FPS_1080, false)")
        )
        assertFalse(
            "what a box can decode is a property of that box: this must not sync",
            source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
                .contains("\"mpv_for_24fps_1080\"")
        )
    }

    @Test
    fun `the launch hands the engine the stream it is about to play`() {
        val call = launchSite.indexOf("PlayerEngine.prefersMpvForLaunch(")
        assertTrue("the player route must still ask the engine", call >= 0)
        val arguments = launchSite.substring(call, minOf(launchSite.length, call + 500))
        assertTrue(
            "the rule is a property of the file, so the launch must pass its URL",
            arguments.contains("streamUrl = current.url")
        )
        assertTrue(
            "and the same headers the player intent carries, or a signed source cannot be read",
            arguments.contains("streamHeaders = current.streamHeaders")
        )
        assertTrue(
            "a DRM stream still never asks at all",
            launchSite.indexOf("current.drmLicenseUrl == null &&") in 0 until call
        )
    }

    @Test
    fun `the launch probe is bounded by the launch budget, not the probe's own`() {
        val text = engine
        assertTrue(
            "the probe's 6 s default must not sit between the tap and the first frame",
            text.contains("budgetMs = LAUNCH_ANIME_BUDGET_MS")
        )
        assertTrue(
            "and the reading comes from the probe's full reading, height and DV included",
            text.contains("FrameRateProbe.read(")
        )
    }

    @Test
    fun `nothing is probed when the toggle is off, on a channel, or for a manifest`() {
        val text = engine
        val read = text.indexOf("FrameRateProbe.read(")
        assertTrue("the probe call must exist", read >= 0)
        val toggleGate = text.indexOf("if (!animeEnabled && !filmCadenceEnabled)")
        assertTrue(
            "both settings off must return before any head is fetched",
            toggleGate in 0 until read
        )
        assertTrue(
            "a live channel has no file to probe",
            text.contains("equals(\"channel\", ignoreCase = true)")
        )
        val arm = text.substring(text.indexOf("private suspend fun prefersMpvForFilmCadence"))
        assertTrue(
            "an unprobeable URL (a manifest included) is refused before the probe",
            arm.indexOf("!FrameRateProbe.isProbeable(url)") in 0 until arm.indexOf(
                "FrameRateProbe.read("
            )
        )
    }

    @Test
    fun `the probe reads height and Dolby Vision from the same video track`() {
        val text = probe
        assertTrue(
            "the reading has to carry all three facts",
            text.contains("val height: Int?") && text.contains("val isDolbyVision: Boolean")
        )
        assertTrue(
            "the height is the track's own declared one",
            text.contains("MediaFormat.KEY_HEIGHT")
        )
        assertTrue(
            "DV is the track's mime, not a release tag",
            text.contains("video/dolby-vision")
        )
        assertTrue(
            "the cache keeps the whole reading, so the player's later AFR probe is a hit",
            text.contains("LinkedHashMap<String, Reading>")
        )
    }

    @Test
    fun `the row sits directly under the anime row on both surfaces`() {
        val tv = tvSettings
        val animeRow = tv.indexOf("label = \"Play Anime in MPV\"")
        val filmRow = tv.indexOf("label = \"Play 24 fps titles in MPV\"")
        val videoOutput = tv.indexOf("text = \"MPV video output\"")
        assertTrue("the TV row must exist", filmRow >= 0)
        assertTrue("directly under the anime row, nothing between", animeRow in 0 until filmRow)
        assertTrue(
            "and still inside the libmpv gate, above the video-output section",
            filmRow < videoOutput
        )

        val mobile = mobileSettings
        val mobileAnime = mobile.indexOf("label = \"Open anime in MPV\"")
        val mobileFilm = mobile.indexOf("label = \"Open 24 fps titles in MPV\"")
        assertTrue("the phone row must exist", mobileFilm >= 0)
        assertTrue("directly under the anime row there too", mobileAnime in 0 until mobileFilm)
        assertTrue(
            "the phone's label differs from the index's, so the row passes its key",
            mobile.contains("searchKey = \"playback.24fps-mpv\"")
        )
        assertTrue(
            "and the key exists in the shared index",
            source("com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt")
                .contains("\"playback.24fps-mpv\"")
        )
    }
}
