package com.kennyb1201.kbstream.data.reporting

/**
 * One `cwload:` line per Continue Watching load, and what the rail did with it.
 *
 * The report (spec 2026-11-24) could not answer its own question. `homeload:`
 * joins `PerfTrace.latestByPrefix("home.")` - the LATEST value per label, not one
 * load's timeline - so a 59.7s `catalogPaint` can sit beside a 345ms `pinned`
 * from a different build, and nothing recorded when the rail's cards landed at
 * all. "Sometimes it loads fully, sometimes it takes 3 min, sometimes it never
 * loads" is three different failures - the upNext list never published, it
 * published without art, or the rail never composed - and the paste could not
 * tell them apart.
 *
 * So each record carries its OWN build id and offset: one Home entry's CW load is
 * a group of lines sharing `build=N`, and the gaps between them are the load's
 * timeline. A build with a `seed`/`publish` line and no `firstPaint` never
 * composed; a build whose `firstPaint` carries `art=0` painted cards without art;
 * a build with nothing at all never published.
 *
 * Text rather than [PerfTrace] samples:
 * these are facts about ONE load (counts, and which stage it reached), and
 * PerfTrace's latest-per-label view is exactly what mixed the builds.
 */
internal object CwLoadTrace {

    /** Lines kept. A few entries, not a session's worth: the last entry is the report. */
    private const val MAX_LINES = 24

    private val lines = ArrayDeque<String>()

    /**
     * Records [what] for the load [buildId], [atMs] after that load started.
     *
     * Safe from any thread: the rail reports its first paint from the composition
     * and the ViewModel reports the publishes from its own coroutine.
     */
    fun note(buildId: Long, atMs: Long, what: String) {
        synchronized(lines) {
            lines.addLast("build=$buildId t=+${atMs}ms $what")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** The recorded loads, oldest first. */
    fun lines(): List<String> = synchronized(lines) { lines.toList() }
}
