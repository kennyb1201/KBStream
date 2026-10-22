package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.format.DateFormats
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How an import ENDED - the class a finished pass reports, because "back to
 * zero" is not an outcome.
 *
 * The field report behind this: every refresh and every manual press ended as a
 * silent return to "0 programs" whether the guide had been re-downloaded, the
 * server had answered `304 Not Modified` (and been honored for a guide with no
 * programs at all), or the download had failed. These three are the answers the
 * viewer and the support capture actually need.
 */
sealed interface GuideImportOutcome {
    /** A body was parsed and promoted; [programs] is what the guide now holds. */
    data class Imported(val programs: Int) : GuideImportOutcome

    /**
     * The server said `304 Not Modified` and the stored guide could satisfy it.
     *
     * [channels] and [programsInWindow] are the counts that satisfied it - the
     * evidence that "unchanged" is the right answer rather than a stale
     * validator hiding an empty guide.
     */
    data class Unchanged(
        val channels: Int,
        val programsInWindow: Int
    ) : GuideImportOutcome

    /** Nothing landed; [message] names the class (an HTTP code when there is one). */
    data class Failed(val message: String) : GuideImportOutcome

    /**
     * The feed answered with a body that held NO programs, and the live guide
     * had some, so the import was refused and the existing guide kept (see the
     * empty-feed guard in [XmltvImporter]).
     *
     * Deliberately an outcome and not a [Failed]: nothing went wrong on this
     * device, the provider's guide is empty. Saying so is the whole point - the
     * field case this comes from read as a silent two-second no-op at "0
     * programs" through three rounds of triage, while the app had quietly kept
     * (or, before this guard, discarded) the guide underneath.
     */
    data object EmptyFeedKeptLive : GuideImportOutcome
}

/**
 * The phase of a guide import, as far as anyone outside it can tell.
 *
 * Only two, because they are the two a viewer can tell apart: the long
 * streaming parse, and the single re-key that promotes what was parsed into
 * the live guide. The swap is one `@Transaction` and reports nothing from
 * inside, so it is exactly the stretch that needs saying out loud — on a
 * several-hundred-megabyte guide it runs for a minute or more with the UI
 * otherwise silent.
 */
enum class GuideImportPhase { READING, SAVING }

/**
 * What the guide import is doing right now.
 *
 * [bytesRead] and [rowsParsed] are for the source being read, so they restart
 * (legitimately) when a multi-source import moves to the next guide. The
 * elapsed clock is [startedAtMs], the whole pass, so it never restarts.
 */
data class GuideImportState(
    val sourceIndex: Int = 1,
    val sourceCount: Int = 1,
    val bytesRead: Long = 0L,
    val rowsParsed: Int = 0,
    val phase: GuideImportPhase = GuideImportPhase.READING,
    val startedAtMs: Long = 0L,
    /**
     * Null while the pass is running; set on the terminal state published by
     * [GuideImportProgress.finish] so the status line can state what happened.
     */
    val outcome: GuideImportOutcome? = null,
    /**
     * The newest program end the LIVE guide held when the pass finished.
     *
     * Carried on the terminal state - not recomputed by the screen - because it
     * is a fact about the import's own moment: a feed that is frozen shows up
     * as a date in the past, next to the outcome that just told the viewer
     * nothing landed. Null when the guide has never held a program (nothing to
     * say) and while a pass is running.
     */
    val guideEndsAtMs: Long? = null
)

/**
 * Live progress for the guide (EPG) import, so "IMPORTING…" can say what it is
 * importing and how far it has got.
 *
 * The report came from a field TV: the user pressed IMPORT EPG, the button read
 * "IMPORTING..." for five minutes and there was no way to tell a slow import
 * from a stuck one. Nothing in the app could tell them either — the importer
 * logs `IMPORT START` / `IMPORT PROGRESS` / `IMPORT END elapsedMs=...` to
 * logcat, where no viewer will ever see it. A guide from a live provider is
 * routinely a few hundred megabytes across hundreds of thousands of
 * `<programme>` entries, parsed and inserted on a TV whose storage is slow and
 * whose heap is small, so "several minutes" is normal — and an import that IS
 * wedged (a stalled socket, a download that has silently restarted) looks
 * exactly the same from the outside.
 *
 * So the numbers the importer already computes are published here instead of
 * only logged, and the setup screen shows them. This object holds state, not
 * policy: when a pass is not open every write is a no-op, which keeps the
 * background worker's imports from publishing a phantom pass nobody asked for.
 */
object GuideImportProgress {

    private val _state = MutableStateFlow<GuideImportState?>(null)

    /** Null when no import is running; the status line then shows nothing. */
    val state: StateFlow<GuideImportState?> = _state.asStateFlow()

    private val _waiting = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The sources currently BLOCKED on the import mutex, by source URL.
     *
     * Imports serialize on one process-wide mutex, but this object's counters
     * belong to whichever import is READING. A queued source therefore
     * publishes nothing while it waits, which its own stall watchdog read as a
     * wedge - it aborted at three minutes, retried onto the same mutex, and
     * surfaced EpgStalledException for a healthy provider.
     *
     * Per SOURCE, not a count: a watchdog may only pause for its OWN queueing.
     * A count pauses the reader's watchdog too whenever a second source is
     * queued, which would hide a genuinely wedged download behind the import
     * that is waiting for it. See [com.kennyb1201.kbstream.data.iptv.EpgImportWatchdog].
     */
    val waiting: StateFlow<Set<String>> = _waiting.asStateFlow()

    fun beginWaiting(sourceUrl: String) {
        val key = sourceUrl.trim()
        if (key.isEmpty()) return
        _waiting.value = _waiting.value + key
    }

    fun endWaiting(sourceUrl: String) {
        val key = sourceUrl.trim()
        if (key.isEmpty()) return
        _waiting.value = _waiting.value - key
    }

    /** Opens a pass over [sourceCount] guide sources. */
    fun begin(
        sourceCount: Int,
        startedAtMs: Long = System.currentTimeMillis()
    ) {
        _state.value = GuideImportState(
            sourceIndex = 1,
            sourceCount = sourceCount.coerceAtLeast(1),
            startedAtMs = startedAtMs
        )
    }

    /**
     * Moves to source [sourceIndex] (1-based). Bytes and rows count from zero
     * again: they describe the guide being read, not the pass.
     */
    fun startSource(sourceIndex: Int) {
        update { state ->
            state.copy(
                sourceIndex = sourceIndex.coerceAtLeast(1),
                bytesRead = 0L,
                rowsParsed = 0,
                phase = GuideImportPhase.READING
            )
        }
    }

    fun bytes(total: Long) {
        update { state -> state.copy(bytesRead = total.coerceAtLeast(0L)) }
    }

    fun rows(parsed: Int) {
        update { state -> state.copy(rowsParsed = parsed.coerceAtLeast(0)) }
    }

    fun phase(phase: GuideImportPhase) {
        update { state -> state.copy(phase = phase) }
    }

    /**
     * Closes the pass, reporting [outcome] when the caller has one.
     *
     * With no outcome this CLEARS the line, which is what the background worker
     * wants (it publishes nothing at all). A pass the viewer started reports its
     * class instead: a download that completed must not read as a silent return
     * to zero, and a `304` the store could satisfy must say so - it is the
     * outcome most easily mistaken for "nothing happened".
     *
     * [guideEndsAtMs] is the live guide's newest program end, published so a
     * frozen feed reads as one (see [label]).
     */
    fun finish(outcome: GuideImportOutcome? = null, guideEndsAtMs: Long? = null) {
        _state.value = outcome?.let {
            GuideImportState(outcome = it, guideEndsAtMs = guideEndsAtMs)
        }
    }

    private fun update(block: (GuideImportState) -> GuideImportState) {
        _state.value = _state.value?.let(block)
    }

    /**
     * The status line: what is being imported and how far along it is, e.g.
     *
     *     source 2/3 · 34.2 MB · 128,400 programs · 4:12
     *     saving 128,400 programs · 4:40
     *
     * Empty when no pass is open. [nowMs] is a parameter so the elapsed half is
     * testable; the screen passes the clock.
     *
     * The wording is the viewer's, not the parser's: "program" here, "No program
     * data" on the guide screen. Only the source this reads spells the word the
     * XMLTV way, as `<programme>`.
     */
    fun label(
        state: GuideImportState?,
        nowMs: Long
    ): String {
        if (state == null) return ""
        val text = state.outcome?.let { outcomeLabel(it) } ?: buildList {
            if (state.sourceCount > 1) {
                add("source ${state.sourceIndex}/${state.sourceCount}")
            }
            if (state.phase == GuideImportPhase.SAVING) {
                add("saving ${grouped(state.rowsParsed)} programs")
            } else {
                add("${megabytes(state.bytesRead)} MB")
                add("${grouped(state.rowsParsed)} programs")
            }
            add(elapsed(state.startedAtMs, nowMs))
        }.joinToString(" · ")
        return withStaleness(text, state.guideEndsAtMs, nowMs)
    }

    /**
     * The staleness fact appended to a finished pass: when the newest program
     * the live guide holds ENDED in the past, the guide is frozen, and that is
     * the one sentence that would have closed the field case immediately (the
     * guide read "No program data" on every channel while the provider, TiviMate
     * on the same feed and the app all agreed the file was there).
     *
     * Only ever past: a guide that runs to next Thursday says nothing here, and
     * neither does a source that has never held a program.
     */
    internal fun withStaleness(text: String, guideEndsAtMs: Long?, nowMs: Long): String {
        if (guideEndsAtMs == null || guideEndsAtMs <= 0L || guideEndsAtMs >= nowMs) return text
        val ended = DateFormats.time(guideEndsAtMs, DateFormats.MONTH_DAY_CLOCK_12H)
        return "$text · Guide data ended $ended"
    }

    /**
     * The finished pass in the viewer's words.
     *
     * "programs", not "programmes": the guide screen says "No program data" and
     * the button says IMPORT EPG, so the status line says programs too. Only the
     * importer's own log spells it the XMLTV way.
     */
    internal fun outcomeLabel(outcome: GuideImportOutcome): String = when (outcome) {
        is GuideImportOutcome.Imported ->
            "Imported ${grouped(outcome.programs)} programs"

        is GuideImportOutcome.Unchanged ->
            "Guide unchanged on server (${grouped(outcome.channels)} channels · " +
                "${grouped(outcome.programsInWindow)} programs)"

        is GuideImportOutcome.EmptyFeedKeptLive ->
            "Feed returned no programs — kept your existing guide"

        is GuideImportOutcome.Failed -> outcome.message
    }

    /** One decimal place, so 0.4 MB is visibly moving while a guide arrives. */
    private fun megabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes.coerceAtLeast(0L) / 1_048_576.0)

    private fun grouped(count: Int): String =
        String.format(Locale.US, "%,d", count.coerceAtLeast(0))

    /** `m:ss`, or `h:mm:ss` once an import runs past the hour. */
    private fun elapsed(startedAtMs: Long, nowMs: Long): String {
        if (startedAtMs <= 0L) return "0:00"
        val totalSeconds = ((nowMs - startedAtMs) / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
