package com.kennyb1201.kbstream.data.iptv

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
}
