package com.kennyb1201.kbstream.ui.iptv

import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import java.util.Calendar
import java.util.Locale

/**
 * The pure half of the TV guide: what a program card SAYS.
 *
 * The audit finding this answers is that `GuideScreen.kt` — 3,200 lines, the
 * one screen in the app with no test at all — kept its text rules inside
 * composables, where nothing could reach them. "3 hr 20 min left", "in <1 min",
 * "Today · 9:05pm–10:00pm" and the setup panel's diagnostic line are all
 * decisions, not layout, and every one of them has an edge case that a viewer
 * sees as a bug: a program that already ended, a start time under a minute
 * away, a window that began yesterday.
 *
 * They are `internal`, not `private`, so `GuideRulesTest` can call them
 * directly — the same split the home rail's rules use (`LocalNextUpRules.kt`),
 * and the reason this file has no Compose import: nothing here needs one.
 */

private const val DAY_MS = 86_400_000L

/**
 * Time left in the program now on air, or null when there is none to show.
 *
 * Rounds UP to the next whole minute, so the last 30 seconds of a program read
 * "1 min left" rather than "0 min left" — a zero would look like a bug and a
 * negative would be nonsense. Null (rather than "0 min left") is the signal to
 * render no badge at all.
 */
internal fun formatRemainingLabel(msUntilEnd: Long): String? {
    if (msUntilEnd <= 0L) return null
    val totalMinutes = ((msUntilEnd + 59_999L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours hr $minutes min left"
        hours > 0 -> "$hours hr left"
        else -> "$minutes min left"
    }
}

/**
 * How long until the next program starts, as the guide's "Up next" caption.
 *
 * The unit changes with the distance — minutes, then hours, then days — and
 * anything under a minute collapses to "in <1 min" instead of "in 0 min".
 * A start time that has already passed reads "starting": the schedule is
 * refreshed on a 30s tick, so a program whose start just went by is genuinely
 * starting, not late.
 */
internal fun formatStartsInLabel(msUntilStart: Long): String =
    when {
        msUntilStart <= 0L -> "starting"
        msUntilStart < 60_000L -> "in <1 min"
        msUntilStart < 3_600_000L -> "in ${msUntilStart / 60_000L} min"
        msUntilStart < 86_400_000L -> "in ${msUntilStart / 3_600_000L} hr"
        else -> "in ${msUntilStart / 86_400_000L} d"
    }

/** `9:05 AM - 10:00 AM`, in the device's own zone. */
internal fun formatTimeRange(startMillis: Long, endMillis: Long): String {
    return "${DateFormats.time(startMillis, DateFormats.CLOCK_12H)} - " +
        DateFormats.time(endMillis, DateFormats.CLOCK_12H)
}

/**
 * The catch-up dialog's window caption: `Today · 9:05pm–10:00pm`.
 *
 * The day is relative to *the current day*, not to the window, which is why
 * this one takes an explicit `nowMillis`: a caption that depends on the wall
 * clock cannot be tested, and this is exactly the label that says "Yesterday"
 * when it should say "Today" if the day boundary is computed wrongly. Callers
 * leave it at the default.
 *
 * The three-way split is today / yesterday / weekday, and it replaced a
 * yesterday / today / weekday split that could not label the past correctly.
 * The old order tested `start < midnight` first and answered "Yesterday" for
 * EVERY earlier day, so the weekday branch only ever ran for a start in the
 * future and a three-day-old catch-up programme - catch-up reaches back a week
 * - was captioned "Yesterday". Writing this file's test is what surfaced it:
 * the caption is shown in a dialog nobody diffs.
 *
 * The en dash and the middle dot are deliberate (`\u2013`, `\u00b7`) — the file
 * is ASCII and these are the characters the rest of the UI uses.
 */
internal fun formatCatchupWindow(
    startUtcMillis: Long,
    endUtcMillis: Long,
    nowMillis: Long = System.currentTimeMillis()
): String {
    fun fmt(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format(
            Locale.US,
            "%d:%02d%s",
            h12,
            cal.get(Calendar.MINUTE),
            if (hour >= 12) "pm" else "am"
        )
    }

    val now = Calendar.getInstance()
    now.timeInMillis = nowMillis
    val dayStart = now.clone() as Calendar
    dayStart.set(Calendar.HOUR_OF_DAY, 0)
    dayStart.set(Calendar.MINUTE, 0)
    dayStart.set(Calendar.SECOND, 0)
    dayStart.set(Calendar.MILLISECOND, 0)
    val today = dayStart.timeInMillis
    val dayLabel: String = when {
        // Today, then yesterday, then the weekday name for anything older - and
        // for anything in the future, which is what this branch did before the
        // past was handled properly.
        startUtcMillis >= today && startUtcMillis < today + DAY_MS -> "Today"
        startUtcMillis >= today - DAY_MS && startUtcMillis < today -> "Yesterday"
        else -> DateFormats.time(startUtcMillis, DateFormats.WEEKDAY)
    }
    return "$dayLabel \u00b7 ${fmt(startUtcMillis)}\u2013${fmt(endUtcMillis)}"
}

/**
 * The setup panel's single diagnostic line, e.g.
 * `Playlist ready  •  EPG provided  •  Extra EPG x2  •  Channels 412`.
 *
 * Only facts that are actually known are listed: a channel count with no
 * playlist loaded, or a name that is blank, would be noise. The extra-EPG
 * count splits on both newline and semicolon because that field is the user's
 * own paste of several URLs and they arrive in either shape.
 */
/**
 * The guide's request bookkeeping: which channel ids are waiting on an answer.
 *
 * Split out of [IptvViewModel] because the rule here is the difference between
 * a row that finishes loading and one that reads "Loading guide..." for the
 * rest of the session, and the guide's flow cannot be rendered in a unit test
 * (there is no Compose test infrastructure), so this is the seam the behaviour
 * is pinned at.
 *
 * The flow cancels an in-flight lineup query the moment a newer batch arrives
 * (`flatMapLatest`), and a superseded query never emits - so its channels are
 * never merged. The queue therefore has to hold un-answered ids ACROSS that
 * cancellation instead of keeping only the newest batch.
 */
internal object GuideRequestQueue {

    /**
     * Add a freshly visible batch to what is already outstanding. Addition,
     * not replacement: a batch whose query a later batch cancels is still
     * waiting and must ride the next request rather than being dropped.
     */
    fun enqueue(pending: Set<String>, requested: Set<String>): Set<String> =
        pending + requested

    /**
     * Drop only the ids the latest emission actually answered, leaving
     * anything still outstanding in the queue so the next request carries it.
     * Sweeping the queue empty here is what stranded every superseded batch.
     */
    fun clearAnswered(pending: Set<String>, resolved: Set<String>): Set<String> =
        pending - resolved
}

/**
 * State produced when a group chip gains focus.
 */
internal data class ChipFocusState(
    val selectedGroup: String,
    val moveFocusToChannelList: Boolean,
)

/**
 * LazyColumn key for one program search hit.
 *
 * Channel + start alone is NOT unique: a playlist with two EPG sources for one
 * channel (or an overlapping entry) holds two programmes at the same channel
 * and start, and a duplicate LazyColumn key throws
 * `IllegalArgumentException("Key ... was already used")` and takes the whole
 * screen down - Sentry ANDROID-S, from the guide's PROGRAM search results. The
 * end time separates the common overlap; [index] is the guaranteed tiebreaker
 * for an exact duplicate. Pure so [GuideRulesTest] can pin the uniqueness
 * instead of only the device.
 */
internal fun guideProgramHitKey(
    channelId: String,
    startUtcMillis: Long,
    endUtcMillis: Long,
    index: Int,
): String = "program|$channelId|$startUtcMillis|$endUtcMillis|$index"

/**
 * The group selection a chip's focus event must produce.
 *
 * Focus always wins: `selectedGroup` follows the focused chip, and a focus
 * event cancels any pending move-to-list transit that was waiting a frame to
 * pull focus into the list. The old `if (!moveFocusToChannelList)` guard
 * swallowed the update when Left/Right landed in that transit window, leaving
 * the focused chip and `selectedGroup` diverged: the chips row sat scrolled to
 * the focused chip while the selected group's chip was off-screen, and the
 * channel list's Up path could not reach it. Because a focus event also clears
 * the flag, the transit is cancelled by the same update.
 *
 * Pure so [GuideRulesTest] can pin the divergence fix without a Compose test
 * harness, which this module does not carry.
 */
internal fun chipFocusState(
    focusedGroup: String,
    moveFocusToChannelList: Boolean,
): ChipFocusState = ChipFocusState(
    selectedGroup = focusedGroup,
    moveFocusToChannelList = false,
)

internal fun buildSetupDiagnosticsText(
    playlistUrl: String,
    epgUrl: String,
    playlistName: String,
    playlist: IptvPlaylist?,
    channelCount: Int,
    isImportingGuide: Boolean,
    guideImportLabel: String,
    extraEpgUrls: String = ""
): String {
    return buildList {
        add(if (playlistUrl.isBlank()) "Playlist missing" else "Playlist ready")
        add(if (epgUrl.isBlank()) "EPG optional" else "EPG provided")
        val extraEpgCount = extraEpgUrls.split('\n', ';').count { it.isNotBlank() }
        if (extraEpgCount > 0) add("Extra EPG x$extraEpgCount")
        if (playlist != null) add("Channels $channelCount")
        if (guideImportLabel.isNotBlank()) {
            add("EPG importing: $guideImportLabel")
        } else if (isImportingGuide) {
            add("EPG importing")
        }
        if (playlistName.isNotBlank()) add("Name: $playlistName")
    }.joinToString("  \u2022  ")
}
