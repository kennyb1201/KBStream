package com.kennyb1201.kbstream.data.iptv

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How long a guide source may make NO progress at all before it is treated as
 * wedged.
 *
 * Three minutes, deliberately well under both bounds that would otherwise have
 * to catch it: the background refresh's per-source budget
 * ([com.kennyb1201.kbstream.work.WorkPolicies.GUIDE_SOURCE_BUDGET_MS], 8 min)
 * and the HTTP client's whole-call timeout
 * ([IptvHttpClient.CALL_TIMEOUT_MS], 20 min). A merely-slow guide is never
 * killed by this: any byte read or any program parsed resets the clock, so a
 * several-hundred-megabyte guide arriving at a crawl keeps its connection.
 *
 * What it does catch is the field failure behind this watchdog: the setup screen
 * sat on "IMPORTING..." with "0.0 MB · 0 programs" for twenty minutes, because
 * the provider had stopped sending while the connection stayed open.
 */
const val EPG_STALL_TIMEOUT_MS = 3L * 60L * 1000L

/**
 * How long the guide's automatic entry import stays quiet after the watchdog
 * failed a source loudly.
 *
 * The wedged run never writes the freshness marker (`KEY_EPG_UPDATED_AT`), so
 * without this every later entry to the guide saw a stale guide and started
 * ANOTHER import, which wedged again — the "it reimports a lot" loop reported
 * alongside the stall itself. Half an hour bounds the attempts while still
 * giving a provider that has recovered a fresh try without the viewer pressing
 * anything.
 */
const val EPG_ENTRY_RETRY_COOLDOWN_MS = 30L * 60L * 1000L

/**
 * A guide download that stopped sending data and did not recover on a fresh
 * connection.
 *
 * Thrown instead of leaving the import sitting silently at zero: it reaches the
 * screen's `_guideError` banner, which is the only thing the viewer can act on.
 */
class EpgStalledException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * True while the automatic entry import must NOT re-attempt a source the
 * watchdog just failed.
 *
 * `lastStallAtMs` of 0 means no stall was ever recorded. A timestamp in the
 * future is a clock that has been put back (a box syncing its clock), and it is
 * treated as "just now" rather than disabling the cooldown for however long the
 * clock is behind.
 */
fun epgEntryImportCoolingDown(
    lastStallAtMs: Long,
    nowMs: Long
): Boolean {
    if (lastStallAtMs <= 0L) return false
    return nowMs - lastStallAtMs < EPG_ENTRY_RETRY_COOLDOWN_MS
}

/**
 * Watches one guide source being imported, and aborts it when it stops moving.
 *
 * A wedged guide source is indistinguishable from a slow one from the outside —
 * [GuideImportProgress] exists precisely because of that — so the decision is
 * made on the numbers the importer already publishes: if neither `bytesRead` nor
 * `rowsParsed` has advanced for [EPG_STALL_TIMEOUT_MS], the fetch is abandoned
 * and re-opened ONCE on a fresh connection. A second stall is a real failure and
 * is thrown as [EpgStalledException] rather than swallowed: the alternative is
 * the field report's twenty minutes at "0 programs".
 *
 * The watchdog only applies to the READING phase. The save that promotes what
 * was parsed into the live guide is one transaction which reports nothing from
 * inside by design (a minute or more on a large guide), so the clock is
 * suspended for it rather than being given a second budget.
 */
class EpgImportWatchdog(
    private val progress: StateFlow<GuideImportState?> = GuideImportProgress.state,
    private val stallTimeoutMs: Long = EPG_STALL_TIMEOUT_MS,
    private val pollMs: Long = STALL_POLL_MS
) {

    /**
     * Runs [importSource] under the watchdog. It receives the attempt number,
     * 0 for the first run and 1 for the retry after a stall, because the retry
     * has to open a connection of its own: joining the attempt that was just
     * aborted would wait for the corpse.
     */
    suspend fun <T> runWithStallRetry(importSource: suspend (attempt: Int) -> T): T {
        var lastStall: EpgStallAbort? = null
        for (attempt in 0 until ATTEMPTS) {
            try {
                return supervise { importSource(attempt) }
            } catch (stall: EpgStallAbort) {
                lastStall = stall
            }
        }
        throw EpgStalledException(
            String.format(
                Locale.US,
                "EPG download stalled: no data for %d minutes",
                stallTimeoutMs / 60_000L
            ),
            lastStall
        )
    }

    /**
     * One attempt, with the importer's own counters as the heartbeat.
     *
     * The abort is a cancellation of the work's own job, and it is deliberately
     * not allowed to surface as the caller's cancellation: a `CancellationException`
     * that reaches the import pass is read as "the screen went away", and the
     * viewer would get the silence this whole fix is about. The caller's own
     * cancellation is still rethrown as itself, via `ensureActive()`.
     */
    private suspend fun <T> supervise(workBlock: suspend () -> T): T = coroutineScope {
        val aborted = AtomicBoolean(false)
        val work = async { workBlock() }

        val watcher = launch {
            var lastBytes: Long? = null
            var lastRows: Int? = null
            var lastSource: Int? = null
            var lastAdvanceAtMs = 0L
            var elapsedMs = 0L
            while (isActive) {
                delay(pollMs)
                elapsedMs += pollMs
                val state = progress.value
                if (state == null || state.phase == GuideImportPhase.SAVING) {
                    // No pass open (a joined worker import publishes nothing),
                    // or the one-transaction save: neither counter is expected
                    // to move, so this is not a stall.
                    lastAdvanceAtMs = elapsedMs
                    continue
                }
                val advanced = state.bytesRead != lastBytes ||
                    state.rowsParsed != lastRows ||
                    state.sourceIndex != lastSource
                lastBytes = state.bytesRead
                lastRows = state.rowsParsed
                lastSource = state.sourceIndex
                if (advanced) {
                    lastAdvanceAtMs = elapsedMs
                    continue
                }
                if (elapsedMs - lastAdvanceAtMs >= stallTimeoutMs) {
                    aborted.set(true)
                    work.cancel()
                    return@launch
                }
            }
        }

        try {
            work.await()
        } catch (error: Throwable) {
            // The caller's cancellation always wins: it is not a stall, and it
            // must reach the import pass as the cancellation it is.
            currentCoroutineContext().ensureActive()
            if (!aborted.get()) throw error
            throw EpgStallAbort()
        } finally {
            watcher.cancel()
        }
    }

    /** The watchdog's own signal: an attempt it gave up on. */
    private class EpgStallAbort : Exception("guide import stalled")

    private companion object {
        /** First run plus one retry on a fresh connection. */
        const val ATTEMPTS = 2

        /**
         * How often the counters are read. A fifth of the timeout, so the
         * abort lands within a poll interval of the deadline and a pass that
         * advances counters costs five prefs reads a second at worst.
         */
        const val STALL_POLL_MS = 5_000L
    }
}
