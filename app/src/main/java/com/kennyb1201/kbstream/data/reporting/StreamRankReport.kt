package com.kennyb1201.kbstream.data.reporting

/**
 * What the last source fetches ranked, and why — one block each for the
 * diagnostics report.
 *
 * "The ranker keeps putting <add-on A> above <add-on B>" is unanswerable from
 * the outside by construction: the picker shows the ordered list and never the
 * reasoning, and the reasoning — which tiers an entry cleared, and the score it
 * earned — is the only thing that says WHICH rule produced that order. The two
 * rules that outrank every quality label (playability, then availability) are
 * invisible in the list itself, so a stream can sit at the top for a reason the
 * viewer cannot see and cannot argue with.
 *
 * Written once per fetch by the source picker (see StreamsViewModel), then
 * annotated by whoever auto-played it (see [noteAutoPlay]), and read by
 * [Diagnostics]. Holds plain strings, so nothing here can fail a report.
 */
internal object StreamRankReport {

    /** Lines kept per fetch, so a long picker session cannot grow this unboundedly. */
    private const val MAX_LINES = 12

    /**
     * Fetches kept, oldest first.
     *
     * One is not enough to read a capture, and the block that answers the
     * question is usually not the newest. A viewer who plays two episodes in a
     * row fetches twice, and a complaint of the form "it played the wrong
     * episode" is decided by comparing the two: whether the add-on offered the
     * requested episode at all, and whether the numbering its releases use
     * agrees with the app's, are both questions about the pair rather than
     * either half. Keeping only the newest block threw exactly that away.
     */
    private const val MAX_BLOCKS = 3

    /**
     * What [noteAutoPlay] writes, and the marker that makes it replaceable: the
     * choice is made by the screen that auto-played the list, which can run its
     * effect more than once for one fetch.
     */
    private const val AUTOPLAY_PREFIX = "auto-play:"

    private val lock = Any()

    @Volatile
    private var blocks: List<List<String>> = emptyList()

    fun record(block: List<String>) {
        synchronized(lock) {
            blocks = (blocks + listOf(block.take(MAX_LINES))).takeLast(MAX_BLOCKS)
        }
    }

    /**
     * Records what auto-play did with the list it was just handed — the one fact
     * the block cannot report about itself, because the block is written when
     * the sources arrive and the choice is made afterwards.
     *
     * It exists because a capture otherwise cannot answer "which file did it
     * actually play?". The block names the head of the list, and the head is
     * only what a *pick* would have taken: [EpisodeMatch.autoplayPick] skips
     * most of it, and a viewer who pressed a card in the picker instead never
     * consulted the order at all. Reading the choice out of the report was
     * inference, and it is the inference that decides whether the app or the
     * add-on is at fault.
     *
     * Replaces any line a previous call left, so one fetch reports one choice.
     * A no-op when nothing has been fetched — there is no block to annotate.
     */
    fun noteAutoPlay(line: String) {
        val marked = if (line.startsWith(AUTOPLAY_PREFIX)) line else "$AUTOPLAY_PREFIX $line"
        synchronized(lock) {
            val newest = blocks.lastOrNull() ?: return
            blocks = blocks.dropLast(1) +
                listOf(newest.filterNot { it.startsWith(AUTOPLAY_PREFIX) } + marked)
        }
    }

    /** Every kept block, oldest first, blank-line separated. Empty before the first fetch. */
    fun lines(): List<String> {
        val out = mutableListOf<String>()
        blocks.forEachIndexed { index, block ->
            if (index > 0) out.add("")
            out.addAll(block)
        }
        return out
    }
}
