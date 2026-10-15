package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which OpenSubtitles hit gets attached without a press.
 *
 * The mistakes worth pinning: drawing a mis-tagged row in the wrong language
 * while a right one sat below it, drawing a FOREIGN track at all when the
 * viewer's language is simply not in the results (an automatic fetch must not
 * put another language over the picture - the picker still lists everything),
 * and picking anything but the most downloaded hit when there is no language
 * to match on.
 */
class AutoSubtitleRulesTest {

    private fun hit(id: Long, language: String, downloads: Int) =
        SubtitleSearchResult(
            fileId = id,
            fileName = "sub-$id.srt",
            language = language,
            downloads = downloads
        )

    @Test
    fun `a language match beats a more downloaded foreign row`() {
        val results = listOf(
            hit(1, "es", downloads = 5000),
            hit(2, "en", downloads = 10)
        )
        assertEquals(2L, AutoSubtitleRules.pick(results, "en")?.fileId)
    }

    @Test
    fun `a regional tag still matches on the language stem`() {
        val results = listOf(
            hit(1, "en-US", downloads = 1),
            hit(2, "fr", downloads = 900)
        )
        assertEquals(1L, AutoSubtitleRules.pick(results, "en-GB")?.fileId)
    }

    @Test
    fun `with no preferred language the most downloaded row wins`() {
        val results = listOf(
            hit(1, "en", downloads = 10),
            hit(2, "fr", downloads = 900)
        )
        assertEquals(2L, AutoSubtitleRules.pick(results, "")?.fileId)
    }

    @Test
    fun `with no language match nothing is attached`() {
        // The preferred language is absent from the results: attaching the
        // most-downloaded row here put Portuguese subtitles over an English
        // release. The picker is still the manual route, so a null costs the
        // viewer nothing they cannot ask for.
        val results = listOf(
            hit(1, "es", downloads = 30),
            hit(2, "pt", downloads = 800)
        )
        assertNull(AutoSubtitleRules.pick(results, "en"))
    }

    @Test
    fun `among matching rows the most downloaded one wins`() {
        // "Preferring a language match, then downloads": the language narrows
        // the field, and downloads pick inside it - not the first row the API
        // happened to list.
        val results = listOf(
            hit(1, "en-GB", downloads = 12),
            hit(2, "en", downloads = 640),
            hit(3, "es", downloads = 5000)
        )
        assertEquals(2L, AutoSubtitleRules.pick(results, "en")?.fileId)
    }

    @Test
    fun `an empty result set yields nothing`() {
        assertNull(AutoSubtitleRules.pick(emptyList(), "en"))
    }
}
