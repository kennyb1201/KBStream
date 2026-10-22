package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tag that routes an addon ASS track to the libass overlay. Both halves
 * fail silently in the field — an untagged offer just renders flattened, and a
 * wrongly claimed track renders nothing at all — so the round-trip and the
 * routing decision are pinned.
 *
 * The routing tests exist because of a field case: a container track and an
 * addon offer that share a language and a label ("English (SDH)") were treated
 * as the same subtitle, so the embedded track was claimed by the overlay, the
 * offer's script was loaded, and every cue of the real track was swallowed.
 * Language and label are not a basis for a claim, and these tests say so.
 */
class AddonAssTracksTest {

    @Test
    fun `an ass mime is tagged with the url`() {
        assertEquals(
            AddonAssTracks.CONFIG_ID_PREFIX + "https://subs/1.ass",
            AddonAssTracks.configIdFor(MimeTypes.TEXT_SSA, "https://subs/1.ass")
        )
    }

    @Test
    fun `a non-ass mime is not tagged as ass`() {
        assertNull(AddonAssTracks.configIdFor(MimeTypes.APPLICATION_SUBRIP, "https://subs/1.srt"))
        assertNull(AddonAssTracks.configIdFor(MimeTypes.TEXT_VTT, "https://subs/1.vtt"))
    }

    @Test
    fun `a non-ass mime is still tagged as an addon track`() {
        assertEquals(
            AddonAssTracks.SUBTITLE_CONFIG_ID_PREFIX + "https://subs/1.srt",
            AddonAssTracks.trackConfigIdFor(MimeTypes.APPLICATION_SUBRIP, "https://subs/1.srt")
        )
    }

    @Test
    fun `every addon offer is tagged, whatever its type`() {
        assertEquals(
            AddonAssTracks.configIdFor(MimeTypes.TEXT_SSA, "https://subs/1.ass"),
            AddonAssTracks.trackConfigIdFor(MimeTypes.TEXT_SSA, "https://subs/1.ass")
        )
        assertEquals(
            AddonAssTracks.SUBTITLE_CONFIG_ID_PREFIX + "https://subs/1.vtt",
            AddonAssTracks.trackConfigIdFor(MimeTypes.TEXT_VTT, "https://subs/1.vtt")
        )
        assertNull(
            "an offer with no url has nothing to name",
            AddonAssTracks.trackConfigIdFor(MimeTypes.APPLICATION_SUBRIP, "")
        )
    }

    @Test
    fun `a blank url is not tagged`() {
        assertNull(AddonAssTracks.configIdFor(MimeTypes.TEXT_SSA, ""))
    }

    @Test
    fun `a tagged id round-trips to its url`() {
        val id = AddonAssTracks.configIdFor(MimeTypes.TEXT_SSA, "https://subs/2.ass")
        assertEquals("https://subs/2.ass", AddonAssTracks.urlFromConfigId(id))
    }

    @Test
    fun `an untagged or empty id yields no url`() {
        assertNull(AddonAssTracks.urlFromConfigId(null))
        assertNull(AddonAssTracks.urlFromConfigId("some-other-id"))
        assertNull(AddonAssTracks.urlFromConfigId(AddonAssTracks.CONFIG_ID_PREFIX))
    }

    // --- Routing ----------------------------------------------------------

    @Test
    fun `an ass config id claims exactly its own url`() {
        val format = Format.Builder()
            .setId(AddonAssTracks.configIdFor(MimeTypes.TEXT_SSA, "https://subs/3.ass"))
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setLanguage("en")
            .setLabel("English (SDH)")
            .build()

        assertEquals("https://subs/3.ass", AddonAssTracks.assSourceFor(format))
    }

    @Test
    fun `an embedded track that looks like a published offer is not claimed`() {
        // The field case, as a regression: same MIME type, same language, same
        // label as a live ASS offer, and no addon tag. Only the config id can
        // make a track an addon track.
        val embedded = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setLanguage("en")
            .setLabel("English (SDH)")
            .build()

        assertNull(
            "language + label must never stand in for a config id",
            AddonAssTracks.assSourceFor(embedded)
        )
    }

    @Test
    fun `a non-ass addon track is identified but never claimed`() {
        val format = Format.Builder()
            .setId(
                AddonAssTracks.trackConfigIdFor(
                    MimeTypes.APPLICATION_SUBRIP,
                    "https://subs/4.srt"
                )
            )
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("en")
            .setLabel("English")
            .build()

        assertEquals(
            AddonAssTracks.SUBTITLE_CONFIG_ID_PREFIX + "https://subs/4.srt",
            format.id
        )
        assertNull(
            "a flattened addon track belongs to the text renderer, not the overlay",
            AddonAssTracks.assSourceFor(format)
        )
    }

    @Test
    fun `the user's own sidecar is never an addon claim`() {
        val sidecar = Format.Builder()
            .setId("kbstream.sidecar")
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setLanguage("und")
            .build()

        assertNull(AddonAssTracks.assSourceFor(sidecar))
    }
}
