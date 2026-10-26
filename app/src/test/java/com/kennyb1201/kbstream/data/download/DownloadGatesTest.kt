package com.kennyb1201.kbstream.data.download

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamDrm
import com.kennyb1201.kbstream.data.debrid.TorBoxResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate matrix of §5, one test per row of it.
 *
 * These are the decisions a viewer cannot be shown a wrong answer to: a live
 * channel that offers a Download, a protected title that starts one anyway, or
 * an offline press that silently does nothing while the viewer stands in a dead
 * zone wondering whether the app heard them.
 */
class DownloadGatesTest {

    private fun stream(
        url: String? = "https://host/file.mkv",
        infoHash: String? = null,
        drm: StreamDrm? = null
    ) = Stream(name = "row", url = url, infoHash = infoHash, drm = drm)

    @Test
    fun `a live channel has no download action anywhere`() {
        val gate = DownloadGates.evaluate(
            stream = stream(),
            isLiveChannel = true,
            online = true
        )
        assertEquals(DownloadGate.Hidden, gate)
        assertFalse(DownloadGates.isAllowed(gate))
    }

    @Test
    fun `a live channel with drm is still hidden rather than disabled`() {
        // Live outranks protected: there is no download of a stream with no end,
        // so the DRM reason would be an answer to a question this row never asks.
        val gate = DownloadGates.evaluate(
            stream = stream(drm = StreamDrm(licenseUrl = "https://drm/licence")),
            isLiveChannel = true,
            online = true
        )
        assertEquals(DownloadGate.Hidden, gate)
    }

    @Test
    fun `no row at all is hidden`() {
        assertEquals(
            DownloadGate.Hidden,
            DownloadGates.evaluate(stream = null, isLiveChannel = false, online = true)
        )
    }

    @Test
    fun `a protected row is disabled with the reason a viewer can read`() {
        val gate = DownloadGates.evaluate(
            stream = stream(drm = StreamDrm(licenseUrl = "https://drm/licence")),
            isLiveChannel = false,
            online = true
        )
        assertEquals(DownloadGate.Disabled(DownloadGates.DRM_REASON), gate)
        assertTrue(DownloadGates.DRM_REASON.contains("protected"))
        assertFalse(DownloadGates.isAllowed(gate))
    }

    @Test
    fun `an offline press is refused in words rather than queued`() {
        val gate = DownloadGates.evaluate(
            stream = stream(),
            isLiveChannel = false,
            online = false
        )
        assertEquals(DownloadGate.Offline, gate)
        assertEquals("Connect to download", DownloadGates.CONNECT_TO_DOWNLOAD)
        assertFalse(DownloadGates.isAllowed(gate))
    }

    @Test
    fun `a hash-only row is allowed and asks to resolve first`() {
        val gate = DownloadGates.evaluate(
            stream = stream(url = null, infoHash = "0123456789abcdef"),
            isLiveChannel = false,
            online = true
        )
        assertTrue(DownloadGates.isAllowed(gate))
        assertEquals(DownloadGate.Allowed(needsResolve = true), gate)
    }

    @Test
    fun `a row with a url needs no resolve`() {
        val gate = DownloadGates.evaluate(
            stream = stream(url = "https://host/file.mkv"),
            isLiveChannel = false,
            online = true
        )
        assertEquals(DownloadGate.Allowed(needsResolve = false), gate)
    }

    @Test
    fun `a row with neither url nor hash needs no resolve and has nothing to download`() {
        // The enqueue path refuses it with its own words (no playable link); the
        // gate's job is only to say the action may be offered.
        val gate = DownloadGates.evaluate(
            stream = stream(url = null, infoHash = null),
            isLiveChannel = false,
            online = true
        )
        assertEquals(DownloadGate.Allowed(needsResolve = false), gate)
    }

    @Test
    fun `the resolve rule is the resolver's own, pinned`() {
        // The gate re-states TorBoxResolve.isUnresolved rather than calling it
        // (the resolver reaches the network and the prefs, and this object is
        // pure). This walks a matrix through both so the two cannot drift.
        val cases = listOf(
            stream(url = "https://host/file.mkv", infoHash = null),
            stream(url = null, infoHash = "0123456789abcdef"),
            stream(url = null, infoHash = null),
            stream(url = "", infoHash = "")
        )
        cases.forEach { case ->
            assertEquals(
                "needsResolve and TorBoxResolve.isUnresolved disagree for $case",
                TorBoxResolve.isUnresolved(case),
                DownloadGates.needsResolve(case)
            )
        }
    }
}
