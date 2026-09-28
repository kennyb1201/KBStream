package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three numbers that decide how big every profile's guide database gets.
 *
 * They used to live apart and drifted to a factor of six — the grid rendered 8
 * hours while an import stored 48, for every channel of a playlist that can
 * hold tens of thousands — which is how the guides became 982 MB of the app's
 * 1.34 GB of data. These assertions state the relationship rather than the
 * values, so raising the guide's read window (or the refresh cadence) fails
 * here instead of silently going back to storing a schedule nobody can open.
 *
 * The last group is the same rule applied to a single row: [EpgWindow] also
 * decides how much of a program's description is worth storing, against what
 * the three screens that draw one can show.
 */
class EpgWindowTest {

    private val hour = 60L * 60L * 1000L

    /**
     * A guide that has just been refreshed must still reach the end of what the
     * grid renders when its next refresh does not happen: at least one full
     * refresh interval of slack beyond the read window.
     */
    @Test
    fun theStoredWindowOutlastsTheReadWindowByAFullRefreshInterval() {
        assertTrue(
            "stored future ${EpgWindow.FUTURE_MS / hour}h must cover the " +
                "${EpgWindow.READ_FUTURE_MS / hour}h read window plus one " +
                "${EpgWindow.REFRESH_INTERVAL_HOURS}h refresh interval",
            EpgWindow.FUTURE_MS >= EpgWindow.READ_FUTURE_MS + EpgWindow.REFRESH_INTERVAL_MS
        )
    }

    /**
     * ...and not by much more. Slack is what the guide databases are made of,
     * so this is the other half of the same contract: the store may not be
     * worth a multiple of what it is standing behind.
     */
    @Test
    fun theStoredWindowIsNotMoreThanTwiceWhatTheSlackNeeds() {
        val needed = EpgWindow.READ_FUTURE_MS + EpgWindow.REFRESH_INTERVAL_MS
        assertTrue(
            "stored future ${EpgWindow.FUTURE_MS / hour}h against ${needed / hour}h of " +
                "read window plus slack",
            EpgWindow.FUTURE_MS <= needed * 2
        )
    }

    /** The past edge the guide leans on has to be stored. */
    @Test
    fun theStoredPastCoversWhatTheGuideReadsBehindTheClock() {
        assertTrue(
            "stored past ${EpgWindow.PAST_MS / 60_000}min must cover the " +
                "${EpgWindow.READ_PAST_MS / 60_000}min read window",
            EpgWindow.PAST_MS >= EpgWindow.READ_PAST_MS
        )
    }

    /**
     * The two halves are sized against each other, so the millisecond forms
     * have to agree with the hour they are named for — the scheduler reads the
     * hour, the window is built from the milliseconds.
     */
    @Test
    fun theRefreshIntervalAgreesWithItsHourlyForm() {
        assertTrue(
            "REFRESH_INTERVAL_MS must be REFRESH_INTERVAL_HOURS hours",
            EpgWindow.REFRESH_INTERVAL_MS == EpgWindow.REFRESH_INTERVAL_HOURS * hour
        )
    }

    // ---- what a single stored row is worth -------------------------------

    /** A description no screen could finish drawing is stored whole. */
    @Test
    fun aDescriptionShortEnoughToDrawIsStoredWhole() {
        val text = "A detective investigates a disappearance in a coastal town."
        assertEquals(text, epgDescriptionForStorage(text))
        assertEquals(
            text,
            epgDescriptionForStorage("  $text\n")
        )
    }

    /** Blank is nothing, not an empty string: the columns and the UI both
     *  treat "no description" and "an empty one" the same way, and only one of
     *  them is worth a row. */
    @Test
    fun blankDescriptionsAreStoredAsNothing() {
        assertNull(epgDescriptionForStorage(null))
        assertNull(epgDescriptionForStorage(""))
        assertNull(epgDescriptionForStorage("   \n\t "))
    }

    /**
     * The clip lands beyond anything any screen could have drawn, which is the
     * whole point: a description is the largest column in the guide and it was
     * stored at whatever length the provider wrote.
     */
    @Test
    fun theClipClearsTheWidestScreenWithHeadroom() {
        assertTrue(
            "stored ${EpgWindow.MAX_DESCRIPTION_CHARS} chars against the widest " +
                "${EpgWindow.DISPLAY_CLAMP_CHARS} chars any screen draws",
            EpgWindow.MAX_DESCRIPTION_CHARS > EpgWindow.DISPLAY_CLAMP_CHARS
        )
        assertTrue(
            "headroom must not be so thin that the cut lands inside text the " +
                "guide would have shown",
            EpgWindow.MAX_DESCRIPTION_CHARS >= (EpgWindow.DISPLAY_CLAMP_CHARS * 3) / 2
        )
    }

    /**
     * ...and where it lands is a real, visible edge: exactly the cap, ending in
     * an ellipsis so a clipped description reads as clipped rather than as the
     * provider's own text.
     */
    @Test
    fun aLongDescriptionIsClippedToTheCapWithAnEllipsis() {
        val stored = epgDescriptionForStorage("The quiet town of " + "a".repeat(600))!!

        assertEquals(EpgWindow.MAX_DESCRIPTION_CHARS, stored.length)
        assertTrue(stored.endsWith("…"))
        // No space left dangling before the ellipsis - the maintenance pass
        // rtrims for the same reason (see GuideStorage.trimDescriptions).
        assertFalse(stored.contains(" …"))
    }

    /**
     * The clip is character-safe. Kotlin's `take` counts UTF-16 units, so a cut
     * could land between the halves of a surrogate pair; a lone surrogate cannot
     * be encoded and would reach SQLite as U+FFFD. (The maintenance pass, which
     * clips in SQL, is safe by construction - SQLite's `substr` counts
     * characters - so this is the branch that needed the guard.)
     */
    @Test
    fun theClipNeverCutsASurrogatePairInHalf() {
        val stored = epgDescriptionForStorage("🎬".repeat(300))!!

        assertFalse(
            "the character before the ellipsis must not be half a pair",
            stored[stored.length - 2].isHighSurrogate()
        )
        assertTrue(stored.endsWith("…"))
    }
}
