package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tag that routes an addon ASS track to the libass overlay. Both halves
 * fail silently in the field — an untagged offer just renders flattened, and a
 * mistyped lookup would leave the overlay dark — so the round-trip is pinned.
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
    fun `a non-ass mime is not tagged`() {
        assertNull(AddonAssTracks.configIdFor(MimeTypes.APPLICATION_SUBRIP, "https://subs/1.srt"))
        assertNull(AddonAssTracks.configIdFor(MimeTypes.TEXT_VTT, "https://subs/1.vtt"))
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

    @Test
    fun `the fallback key is language and label, lowercased`() {
        assertEquals("en|default", AddonAssTracks.languageKey("EN", "Default"))
        assertEquals("|", AddonAssTracks.languageKey(null, null))
    }
}
