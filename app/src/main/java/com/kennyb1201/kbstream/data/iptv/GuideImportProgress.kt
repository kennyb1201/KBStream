package com.kennyb1201.kbstream.data.iptv

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val startedAtMs: Long = 0L
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

    /** Closes the pass. A finished import shows nothing at all. */
    fun finish() {
        _state.value = null
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
        return buildList {
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
