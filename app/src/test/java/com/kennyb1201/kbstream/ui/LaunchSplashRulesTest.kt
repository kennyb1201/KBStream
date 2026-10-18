package com.kennyb1201.kbstream.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the launch splash is allowed to be on screen.
 *
 * The rule is the whole point of the splash being "quick": it is dismissed by
 * content becoming ready and never by a clock, so every row below is a beat a
 * viewer would otherwise sit through — a picker that has no profiles yet, rails
 * that have not landed — plus the one case that is deliberately NOT a beat: a
 * load that never finishes, where holding the logo would trap the viewer with no
 * way forward.
 *
 * The stale-rail rows are the profile-switch half. A pick re-arms the splash
 * while the PREVIOUS profile's rails are still on screen and nothing is loading
 * yet, so "not loading + rails present" is exactly the shape the rule used to
 * read as ready — and the latch disarmed on that first frame, before the switch
 * propagated. Rails built for another profile are not content.
 */
class LaunchSplashRulesTest {

    /**
     * One row of the table: the six inputs, and whether the splash is still
     * covering the screen.
     */
    private data class Row(
        val target: LaunchSplashTarget,
        val profilesEmitted: Boolean,
        val homeLoading: Boolean,
        val railsPresent: Boolean,
        val elapsedMs: Long,
        val expected: Boolean,
        val why: String,
        val railsStale: Boolean = false,
    )

    private val table = listOf(
        // --- Launch -> Who's Watching ---------------------------------------
        Row(
            target = LaunchSplashTarget.PROFILE_PICKER,
            profilesEmitted = false,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = 0,
            expected = true,
            why = "the picker has nothing to show until the profile list emits"
        ),
        Row(
            target = LaunchSplashTarget.PROFILE_PICKER,
            profilesEmitted = true,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = 0,
            expected = false,
            why = "the list emitted, so this beat is over - Home's state is irrelevant here"
        ),
        Row(
            target = LaunchSplashTarget.PROFILE_PICKER,
            profilesEmitted = true,
            homeLoading = false,
            railsPresent = false,
            elapsedMs = 120,
            expected = false,
            why = "still dismissed: Home loading must not hold the picker's splash"
        ),

        // --- Launch -> Home (no profiles) / Profile select -> Home ----------
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = false,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = 0,
            expected = true,
            why = "the exact case the splash exists for: the skeleton stack's window"
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = 0,
            expected = true,
            why = "the same beat after a profile pick, when the list has long emitted"
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = false,
            homeLoading = true,
            railsPresent = true,
            elapsedMs = 0,
            expected = false,
            why = "rails are on screen, so the viewer has something to look at already"
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = false,
            homeLoading = false,
            railsPresent = false,
            elapsedMs = 0,
            expected = false,
            why = "the load ended with no rails: the empty / error / retry card is the content"
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = false,
            homeLoading = false,
            railsPresent = true,
            elapsedMs = 0,
            expected = false,
            why = "settled with rails: nothing left to cover"
        ),

        // --- Profile switch: rails that belong to the profile being left ------
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = false,
            railsPresent = true,
            elapsedMs = 0,
            expected = true,
            why = "the reported bug: a pick has re-armed the splash and the OLD " +
                "profile's rails are still composed, so nothing is loading yet - " +
                "but those rows are not this viewer's content",
            railsStale = true
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = false,
            railsPresent = false,
            elapsedMs = 0,
            expected = true,
            why = "the switch's clear landed before the new build started: still " +
                "stale, still covered",
            railsStale = true
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = true,
            railsPresent = true,
            elapsedMs = 0,
            expected = true,
            why = "mid-switch with the old rails still painted and the new load " +
                "running: covered by both halves of the condition",
            railsStale = true
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = 0,
            expected = true,
            why = "the same profile's rails landing: loading with nothing on " +
                "screen, exactly as on a cold launch",
            railsStale = false
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = false,
            railsPresent = true,
            elapsedMs = 0,
            expected = false,
            why = "the new profile's rails have landed, so the ids match again " +
                "and there is nothing left to cover",
            railsStale = false
        ),
        Row(
            target = LaunchSplashTarget.PROFILE_PICKER,
            profilesEmitted = true,
            homeLoading = false,
            railsPresent = true,
            elapsedMs = 0,
            expected = false,
            why = "stale rails are a Home input only: Who's Watching is dismissed " +
                "by its own list and must not be held by Home state",
            railsStale = true
        ),

        // --- The failsafe ---------------------------------------------------
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = false,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = LAUNCH_SPLASH_TIMEOUT_MS - 1,
            expected = true,
            why = "a genuinely slow load keeps the splash right up to the deadline"
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = false,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = LAUNCH_SPLASH_TIMEOUT_MS,
            expected = false,
            why = "a STUCK load must never trap the viewer: dismiss at the deadline"
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = false,
            homeLoading = true,
            railsPresent = false,
            elapsedMs = 20_000,
            expected = false,
            why = "and it stays dismissed however long the load hangs"
        ),
        Row(
            target = LaunchSplashTarget.PROFILE_PICKER,
            profilesEmitted = false,
            homeLoading = false,
            railsPresent = false,
            elapsedMs = LAUNCH_SPLASH_TIMEOUT_MS,
            expected = false,
            why = "the failsafe outranks EVERY target, including the profile list"
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = false,
            railsPresent = true,
            elapsedMs = LAUNCH_SPLASH_TIMEOUT_MS - 1,
            expected = true,
            why = "a switch that is genuinely slow keeps the splash right up to " +
                "the deadline, however stale its rails are",
            railsStale = true
        ),
        Row(
            target = LaunchSplashTarget.HOME,
            profilesEmitted = true,
            homeLoading = false,
            railsPresent = true,
            elapsedMs = LAUNCH_SPLASH_TIMEOUT_MS,
            expected = false,
            why = "and stale rails do NOT outrank the failsafe: an airplane-mode " +
                "switch hands the viewer the retry UI at the deadline",
            railsStale = true
        ),
    )

    @Test
    fun `the dismissal table`() {
        table.forEachIndexed { index, row ->
            assertEquals(
                "row $index (${row.why}): target=${row.target} " +
                    "profilesEmitted=${row.profilesEmitted} " +
                    "homeLoading=${row.homeLoading} railsPresent=${row.railsPresent} " +
                    "railsStale=${row.railsStale} elapsedMs=${row.elapsedMs}",
                row.expected,
                launchSplashVisible(
                    target = row.target,
                    profilesEmitted = row.profilesEmitted,
                    homeLoading = row.homeLoading,
                    railsPresent = row.railsPresent,
                    elapsedMs = row.elapsedMs,
                    railsStale = row.railsStale
                )
            )
        }
    }

    /**
     * The bound is part of the promise ("~8 s"), so a change to it is a change
     * to behavior and should have to touch this line.
     */
    @Test
    fun `the failsafe is the documented eight seconds`() {
        assertEquals(8_000L, LAUNCH_SPLASH_TIMEOUT_MS)
    }

    /**
     * The one shape the table can never hit: no target is ready, yet the
     * splash is gone. That is only correct when the deadline has passed, so a
     * stuck load can never be covered forever.
     */
    @Test
    fun `nothing unready survives the deadline`() {
        LaunchSplashTarget.entries.forEach { target ->
            assertFalse(
                "$target must not hold the screen past the deadline",
                launchSplashVisible(
                    target = target,
                    profilesEmitted = false,
                    homeLoading = true,
                    railsPresent = false,
                    elapsedMs = LAUNCH_SPLASH_TIMEOUT_MS
                )
            )
        }
    }

    /**
     * The profile-switch bug stated on its own, so a regression fails here with
     * the report's own shape rather than only as a table row: the first frame
     * after a pick has the OLD profile's rails on screen and nothing loading.
     */
    @Test
    fun `rails from the previous profile keep the splash up`() {
        assertTrue(
            "a pick must not hand the viewer the profile they just left",
            launchSplashVisible(
                target = LaunchSplashTarget.HOME,
                profilesEmitted = true,
                homeLoading = false,
                railsPresent = true,
                elapsedMs = 0,
                railsStale = true
            )
        )
        assertFalse(
            "and once the new profile's rails are up it hands over immediately",
            launchSplashVisible(
                target = LaunchSplashTarget.HOME,
                profilesEmitted = true,
                homeLoading = false,
                railsPresent = true,
                elapsedMs = 0,
                railsStale = false
            )
        )
    }

    /**
     * And the inverse guard: under the deadline, the picker's beat ends ONLY on
     * the profile list emitting, so a Home that is loading or empty can never
     * hold Who's Watching - and a Home that is settled can never be hidden.
     */
    @Test
    fun `each target is dismissed by its own condition, not the other's`() {
        assertTrue(
            launchSplashVisible(
                target = LaunchSplashTarget.PROFILE_PICKER,
                profilesEmitted = false,
                homeLoading = false,
                railsPresent = true,
                elapsedMs = 0
            )
        )
        assertFalse(
            launchSplashVisible(
                target = LaunchSplashTarget.HOME,
                profilesEmitted = true,
                homeLoading = false,
                railsPresent = true,
                elapsedMs = 0
            )
        )
    }
}
