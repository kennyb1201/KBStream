package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 24-hour clock setting reaches every clock the app draws (settings ->
 * Interface -> 24-hour clock).
 *
 * The setting is older than this coverage: the player overlay read it from the
 * day it was added, so the field's reasonable conclusion was that the app had a
 * 24-hour clock. It did not - the guide's cells, its ruler, its header, the
 * sports cards and the catch-up caption all formatted their own fixed 12-hour
 * shape, so a viewer who turned the toggle on got a 24-hour player clock over a
 * 12-hour guide.
 *
 * Pinned from the source because the failure is a wiring one: a screen that
 * forgets the flag still compiles, still looks right in 12-hour, and only
 * disagrees with the clock above it after the viewer has chosen the other
 * convention. The rules' own shapes are unit-tested where they live
 * (`GuideRulesTest`, `GuideGridRulesTest`, `SportsDetailRulesTest`); this holds
 * the wiring.
 */
class ClockPreferenceCoverageContractTest {

    private val root: File by lazy {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun read(relative: String): String = File(root, relative).readText()

    private fun sources(): List<File> =
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    @Test
    fun `one composable reads the setting, keyed on the sync revision`() {
        val helper = read("com/kennyb1201/kbstream/ui/components/ClockPreference.kt")
        assertTrue(
            "the pref is the source of truth, not a second copy of the answer",
            helper.contains("AppPreferences.getUse24HourClock(context)")
        )
        assertTrue(
            "and a value that syncs in from the account repaints an open screen",
            helper.contains("PrefsPayloadApplier.revision") &&
                helper.contains("collectAsStateWithLifecycle")
        )
    }

    @Test
    fun `the guide's cells, ruler, header and caption read the setting`() {
        assertTrue(
            "a program's slot takes the flag and the 24-hour half is a real format",
            read("com/kennyb1201/kbstream/ui/iptv/GuideRules.kt")
                .contains("internal fun formatTimeRange(startMillis: Long, endMillis: Long, use24Hour: Boolean)")
        )
        assertTrue(
            "the grid's hour ruler takes it too, or the strip says 3 PM over cells that say 15:00",
            read("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt")
                .contains("internal fun hourMarks(window: GridWindow, use24Hour: Boolean)")
        )
        assertTrue(
            "the TV header clock re-renders when the convention changes",
            read("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
                .contains("rememberCurrentTimeLabel(rememberUse24HourClock())")
        )
        assertTrue(
            "the phone header clock comes from the shared formatter, not a private pattern",
            read("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")
                .contains("DateFormats.dayClock(use24Hour)")
        )
    }

    @Test
    fun `the sports cards and the zap banner read it`() {
        assertTrue(
            "a card's start time is the same clock the hub's header shows",
            read("com/kennyb1201/kbstream/ui/sports/SportsDetailRules.kt")
                .contains("use24Hour: Boolean): String = when (game.state)")
        )
        assertTrue(
            "and both hub surfaces pass it",
            read("com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt")
                .contains("SportsDetailRules.statusLine(game, rememberUse24HourClock())") &&
                read("com/kennyb1201/kbstream/ui/mobile/SportsHubScreenMobile.kt")
                    .contains("SportsDetailRules.statusLine(game, rememberUse24HourClock())")
        )
        assertTrue(
            "the player's zap banner is not the one clock left on 12-hour",
            read("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
                .contains("if (AppPreferences.getUse24HourClock(this)) zapTimeFormat24 else zapTimeFormat12")
        )
    }

    @Test
    fun `no production code formats a clock from a fixed 12-hour pattern`() {
        // The two halves live in DateFormats and are chosen by the flag (or, in
        // the guide's hot path, by `formatTimeRange`'s own two cached
        // formatters). A call site anywhere else is a clock that cannot follow
        // the setting, which is exactly the bug this coverage exists for.
        val allowed = setOf(
            "com/kennyb1201/kbstream/data/format/DateFormats.kt",
            "com/kennyb1201/kbstream/ui/iptv/GuideRules.kt"
        )
        val offenders = sources().mapNotNull { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (relative in allowed) return@mapNotNull null
            val text = file.readText()
            val hits = listOf(
                "DateFormats.CLOCK_12H",
                "DateFormats.WEEKDAY_CLOCK_12H",
                "DateFormats.MONTH_DAY_CLOCK_12H",
                "DateFormats.WEEKDAY_DATE_CLOCK_12H"
            ).filter { text.contains(it) }
            if (hits.isEmpty()) null else "$relative: ${hits.joinToString()}"
        }
        assertTrue(
            "use DateFormats.clock/dayClock/weekdayClock/monthDayClock with the " +
                "viewer's setting instead:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
