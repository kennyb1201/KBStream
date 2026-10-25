package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A list typed one per line imports exactly like one typed with semicolons.
 *
 * Report (2026-10-25, Kenny): the Extra EPG Sources field says "one per line"
 * and Enter could not start a second line. The workaround given at the time was
 * `;`, which is why both spellings have to keep parsing: the field is multiline
 * now, and a value typed the old way must import unchanged.
 *
 * [GuideFiles.guideUrls] is the one place the extras textarea is read for an
 * import (the worker and the ViewModel both go through it - see the comment on
 * it), so this runs it for real against the real prefs store rather than pinning
 * the split from source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideSourceListSplitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val guide = "https://epg.kanya.io/us/guide.xml.gz"
    private val second = "https://example.com/second-guide.xml"

    @Test
    fun `newlines and semicolons are the same list`() {
        assertEquals(
            listOf(guide, second),
            extraGuideUrls("$guide\n$second")
        )
        assertEquals(
            "a list typed with ';' before the field could take a newline still imports",
            extraGuideUrls("$guide\n$second"),
            extraGuideUrls("$guide;$second")
        )
    }

    @Test
    fun `blank lines and repeats are dropped, not fetched`() {
        assertEquals(
            listOf(guide, second),
            extraGuideUrls("$guide\n\n   \n$second\n$guide")
        )
    }

    /** Writes the extras textarea and reads back the guide sources it means. */
    private fun extraGuideUrls(extraEpgUrls: String): List<String> {
        prefs()
            .edit()
            .putString(KEY_PRIMARY_EPG, "")
            .putString(KEY_EXTRA_EPG_URLS, extraEpgUrls)
            .commit()
        return GuideFiles.guideUrls(context)
    }

    private fun prefs() = context.getSharedPreferences(
        ProfileStorage.prefsName(context, GuideFiles.PREFS),
        Context.MODE_PRIVATE
    )

    private companion object {
        // Spelled as IptvViewModel writes them; GuideFiles keeps its own copies
        // private, and a wrong key here would leave every list empty, which the
        // first assertion catches.
        const val KEY_PRIMARY_EPG = "epg_url"
        const val KEY_EXTRA_EPG_URLS = "extra_epg_urls"
    }
}
