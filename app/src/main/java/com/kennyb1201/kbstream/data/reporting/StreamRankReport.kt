package com.kennyb1201.kbstream.data.reporting

/**
 * What the last source fetch ranked, and why — one block for the diagnostics
 * report.
 *
 * "The ranker keeps putting <add-on A> above <add-on B>" is unanswerable from
 * the outside by construction: the picker shows the ordered list and never the
 * reasoning, and the reasoning — which tiers an entry cleared, and the score it
 * earned — is the only thing that says WHICH rule produced that order. The two
 * rules that outrank every quality label (playability, then availability) are
 * invisible in the list itself, so a stream can sit at the top for a reason the
 * viewer cannot see and cannot argue with.
 *
 * Written once per fetch by the source picker (see StreamsViewModel) and read
 * by [Diagnostics]. Holds plain strings, so nothing here can fail a report.
 */
internal object StreamRankReport {

    /** Lines kept, so a long picker session cannot grow this unboundedly. */
    private const val MAX_LINES = 12

    private val lock = Any()

    @Volatile
    private var lines: List<String> = emptyList()

    fun record(block: List<String>) {
        synchronized(lock) { lines = block.take(MAX_LINES) }
    }

    /** The last block, empty when nothing has been fetched this session. */
    fun lines(): List<String> = lines
}
