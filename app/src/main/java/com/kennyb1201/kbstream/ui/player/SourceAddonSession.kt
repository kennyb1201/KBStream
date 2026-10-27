package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreference
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Who is dead for THIS playback session, and how the next source is chosen
 * around them.
 *
 * The reported problem: when a source fails to open, "try next source" walks
 * the ranked list in order - and that list is typically headed by several more
 * links from the SAME dead addon, so the viewer watches three or four failures
 * from one addon before reaching a working source from another. The persistent
 * memory ([com.kennyb1201.kbstream.data.player.SourceAddonMemory]) records the
 * failure for FUTURE sessions, but the current one learned nothing, and the
 * first failure was always user-visible: the player tried, timed out, and only
 * then moved on.
 *
 * This object is the in-session half. A set of normalized addon names, filled
 * two ways - by the pre-playback probe ([SourcePlaybackProbe]), which checks
 * the top-ranked sources before the player is asked to open one, and by the
 * open-failure paths, which mark an addon dead the moment a file fails to
 * open during playback.
 *
 * Deliberately NOT persisted: [com.kennyb1201.kbstream.data.player.SourceAddonMemory]
 * owns the cross-session record (per title, per profile, expiring). This owns
 * the next ten seconds of one session, and a new session starts empty.
 */
internal object SourceAddonSession {

    /**
     * How many candidates the probe will test, counting from the head of the
     * ranked list. Three, so the worst case is three short ranged GETs rather
     * than a walk down the whole list.
     */
    const val MAX_PROBES = 3

    /** The same normalization the persistent memory uses, for the same reason. */
    fun normalize(addon: String?): String = SourceAddonPreference.normalize(addon)

    /**
     * Marks [addon] dead for this session. A blank or null name is never added:
     * an unattributable failure must not be blamed on whatever source happens
     * to be first.
     *
     * @return true when the name was newly added, false when it was blank or
     * already dead.
     */
    fun markDead(dead: MutableSet<String>, addon: String?): Boolean {
        val key = normalize(addon)
        if (key.isEmpty()) return false
        return dead.add(key)
    }

    /** True when the source at [index] belongs to an addon dead this session. */
    fun isDead(addons: List<String?>, index: Int, dead: Set<String>): Boolean {
        if (dead.isEmpty()) return false
        val key = normalize(addons.getOrNull(index))
        return key.isNotEmpty() && key in dead
    }

    /**
     * The index the advance should land on, scanning from [fromIndex].
     *
     * Two passes, in this order:
     *
     *  1. the first source from [fromIndex] on whose addon is NOT dead - the
     *     whole point, so a run of dead links from one addon is skipped at once
     *     and the advance lands on the first source from a different addon;
     *  2. failing that, [fromIndex] itself - plain order, because a dead link
     *     is still better than "no sources".
     *
     * Only ever FILTERS: the surviving indices keep their relative order, so
     * the ranker's own order is preserved inside what is left. An addon the app
     * has no name for (a null/blank entry, an extra that was absent or short)
     * is never skipped - [isDead] answers false for it.
     *
     * @return null when [fromIndex] is outside the list (nothing left to try).
     */
    fun nextIndex(addons: List<String?>, fromIndex: Int, dead: Set<String>): Int? {
        if (fromIndex < 0 || fromIndex >= addons.size) return null
        for (i in fromIndex until addons.size) {
            if (!isDead(addons, i, dead)) return i
        }
        return fromIndex
    }

    /**
     * The index a TAB-SCOPED advance lands on, or null when [tab] has nothing
     * left to give.
     *
     * The manual/automatic split. A press that names the tab the viewer is
     * looking at walks ONLY that tab's rows, so the advance cannot jump into
     * another add-on's links behind their back; every automatic recovery walks
     * the merged list ([nextIndex]) and crosses add-ons freely, because a dead
     * add-on must not strand recovery inside a tab.
     *
     * [nextIndex] is the merged half of the rule and this is the tabbed half,
     * and the differences are deliberate:
     *
     *  1. membership - an index counts only when its add-on normalizes
     *     ([normalize]) to [tab]'s name. A null/blank entry (an add-on the
     *     payload did not describe) is never a member of anything, and a [tab]
     *     matching no entry at all answers null at once: a stale name means the
     *     picker's tab is gone and the in-player SOURCES picker is the answer.
     *  2. no second pass - [nextIndex] falls back to plain order so that a dead
     *     link still beats "no sources". Inside one add-on every row IS that
     *     add-on, so that fallback could only hand back a link already known
     *     dead; this walk stops instead, and the engines surface the picker
     *     rather than switch to it.
     *
     * @param usable whether index `i` may be landed on at all - the engines'
     *   pre-existing blank-URL / same-URL guard. A row that fails it is stepped
     *   past exactly as the merged walk steps past it, so a hash row in the tab
     *   is not read as the tab's end while a url-bearing row still follows.
     * @return null when [tab] is blank, when [fromIndex] is outside the list,
     *   or when no member of the tab survives the dead-skip and [usable].
     */
    fun tabbedNextIndex(
        addons: List<String?>,
        fromIndex: Int,
        dead: Set<String>,
        tab: String?,
        usable: (Int) -> Boolean
    ): Int? {
        val key = normalize(tab)
        if (key.isEmpty()) return null
        if (fromIndex < 0) return null
        for (i in fromIndex until addons.size) {
            if (normalize(addons.getOrNull(i)) != key) continue
            if (isDead(addons, i, dead)) continue
            if (!usable(i)) continue
            return i
        }
        return null
    }
}

/**
 * The pre-playback probe: one cheap ranged GET against the head of the ranked
 * list, before the player is asked to open anything.
 *
 * A ranged GET, not HEAD: some hosts answer HEAD 200 and GET 500 (or refuse
 * HEAD outright), and one byte proves servability where a header reply may not.
 * Success is any 2xx (200 or 206); anything else - 4xx, 5xx, timeout,
 * connection failure - is a probe failure.
 *
 * The probe is a HINT, never a verdict:
 *
 *  - it walks [MAX_PROBES] candidates at most, in rank order, and stops at the
 *    first success;
 *  - every failure marks that candidate's addon dead for the session, so the
 *    advance that follows skips the rest of that addon's links without needing
 *    a user-visible failure;
 *  - when every probe fails it returns the top-ranked candidate anyway and lets
 *    the player try - the player is the real test, and the probe can be wrong;
 *  - it NEVER writes the persistent [com.kennyb1201.kbstream.data.player.SourceAddonMemory]:
 *    a transient 500 during a probe must not demote an addon for a month. Only
 *    an actual player open failure does that (existing behavior, unchanged);
 *  - a candidate with no URL cannot be probed at all - a torrent/infoHash-only
 *    row, say - so it is skipped without being counted or blamed, which is what
 *    keeps an unattributable link from demoting its addon.
 *
 * Debrid note: the probe spends a few seconds of a time-limited link's life.
 * That is accepted: the alternative is spending ten or more seconds on a player
 * timeout against a link that was already dead.
 */
internal class SourcePlaybackProbe(
    private val dead: MutableSet<String>,
    private val isServable: (url: String, headers: Map<String, String>, timeoutMs: Long) -> Boolean,
) {

    /** The production probe: a ranged GET on the shared app HTTP client. */
    constructor(dead: MutableSet<String>, client: OkHttpClient) : this(
        dead,
        { url, headers, timeoutMs -> rangedGetServable(client, url, headers, timeoutMs) }
    )

    /**
     * Returns the first source whose probe succeeds, or the top source if all
     * probes fail (the probe can be wrong; the player is the real test).
     * Probe failures mark the addon dead for the session.
     *
     * Runs on [Dispatchers.IO], all probes AT ONCE (audit P2).
     *
     * The probes used to run one after another, so a launch paid their sum -
     * up to three ceilings, ~9 s, before the player was even asked to open
     * anything. They are independent one-byte GETs against (usually) different
     * hosts, so the whole search now costs the SLOWEST probe rather than the
     * total. Rank order still decides the winner: the earliest index that
     * answered is the pick, so this can never prefer a lower-ranked source over
     * a higher-ranked one that also answered.
     */
    suspend fun pickLiveSource(
        candidates: List<Stream>,
        addons: List<String?>,
        headersFor: (Stream) -> Map<String, String>,
    ): Stream {
        // Nothing to choose between: a single source is what the player would
        // open anyway, so no request is spent proving it.
        if (candidates.size < 2) return candidates.first()

        return withContext(Dispatchers.IO) {
            // Index 0 is the source this session is about to open - the
            // viewer's explicit choice when they made one, since
            // [probeCandidates] pins it to the head - so it is the one
            // candidate probed with the larger ceiling (see
            // [probeTimeoutMsFor]). Without that, a slow-but-alive tapped
            // source answered just past the ranked ceiling, was read as dead,
            // and the session opened a DIFFERENT source than the one the viewer
            // tapped: the bug the pinning was added to fix, arriving through
            // the timeout instead of through rank order.
            val probed = candidates.indices
                .filter { !candidates[it].url.isNullOrBlank() }
                .take(SourceAddonSession.MAX_PROBES)

            val outcomes = coroutineScope {
                probed.map { index ->
                    val candidate = candidates[index]
                    val url = candidate.url.orEmpty()
                    val timeoutMs = probeTimeoutMsFor(isPinned = index == 0)
                    async {
                        index to runCatching {
                            isServable(url, headersFor(candidate), timeoutMs)
                        }.getOrDefault(false)
                    }
                }.awaitAll()
            }

            // Every candidate that was probed and did not answer is dead for the
            // session, exactly as the sequential walk marked each one it passed.
            outcomes.forEach { (index, servable) ->
                if (!servable) SourceAddonSession.markDead(dead, addons.getOrNull(index))
            }
            // Rank order decides the winner, not completion order: the first
            // index that answered wins over a lower-ranked faster answer.
            outcomes.firstOrNull { it.second }?.let { (index, _) ->
                return@withContext candidates[index]
            }
            candidates.first()
        }
    }

    companion object {
        /** Per-probe ceiling, so one hanging host cannot stall the launch. */
        const val TIMEOUT_MS = 2500L

        /**
         * The ceiling for the PINNED candidate - the source the viewer chose.
         *
         * The ranked ceiling above is right for a candidate the probe is
         * choosing between: a host that has not answered in 2.5s is not worth
         * the launch. It is wrong for the one candidate whose answer decides
         * whether the viewer's own choice is honoured, because the failure
         * modes it produces are false negatives rather than dead links: a cold
         * TLS handshake plus a debrid redirect chain routinely lands between
         * 2.5s and 4s on the first request of a session, and the box is on a
         * home connection. A link that is genuinely dead does not answer at 4s
         * either, so this costs the extra second and a half only in the case
         * where the session was about to override the tap.
         */
        const val PINNED_TIMEOUT_MS = 4000L

        /** One byte proves the file is servable; nothing more is transferred. */
        private const val RANGE_VALUE = "bytes=0-0"

        /**
         * True when a ranged GET of [url] answers 2xx.
         *
         * The call gets its own timeout so a slow host cannot hold the shared
         * client's defaults hostage; the connection pool is still shared.
         */
        fun rangedGetServable(
            client: OkHttpClient,
            url: String,
            headers: Map<String, String>,
            timeoutMs: Long = TIMEOUT_MS
        ): Boolean = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Range", RANGE_VALUE)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .get()
                .build()
            client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }
}

/**
 * How long the probe waits for the candidate at a given position.
 *
 * Only the head candidate is "pinned" - [probeCandidates] puts the source this
 * session is about to open there - and only that one gets
 * [SourcePlaybackProbe.PINNED_TIMEOUT_MS]; every other candidate the probe is
 * merely choosing between gets the shorter [SourcePlaybackProbe.TIMEOUT_MS].
 * Exposed as a rule so the policy is testable without an HTTP client.
 */
internal fun probeTimeoutMsFor(isPinned: Boolean): Long =
    if (isPinned) SourcePlaybackProbe.PINNED_TIMEOUT_MS else SourcePlaybackProbe.TIMEOUT_MS

/**
 * The order the pre-playback probe should test sources in, for one launch.
 *
 * [currentUrl] is the source this session is about to open - the explicitly
 * chosen one, whether it came from a "Play manually" tap or a played-link cache
 * hit. The probe walks its list from the head and takes the first live
 * candidate, so leaving the initial source wherever the ranker put it let the
 * probe override an explicit choice with a higher-ranked (but different) source
 * ("probe picked a different head source"). Pinning [currentUrl] to the head
 * makes the probe test the choice first: when it is live the probe returns it
 * and no override happens, and when it is dead the probe falls through to rank
 * order exactly as before - now genuinely because the pick was dead.
 *
 * The returned addons are index-aligned with the returned sources. When
 * [currentUrl] is not in [sources] at all (a cached URL absent from a fresh
 * resolve) a candidate is synthesized carrying the launch headers, with a null
 * addon so nothing is demoted off a stale name.
 */
internal fun probeCandidates(
    sources: List<Stream>,
    sourceAddons: List<String?>,
    currentUrl: String,
    currentAudioUrl: String?,
    streamHeaders: Map<String, String>
): Pair<List<Stream>, List<String?>> {
    if (currentUrl.isBlank()) return sources to sourceAddons
    val idx = sources.indexOfFirst { it.url == currentUrl }
    if (idx >= 0) {
        val candidates = sources.toMutableList()
        val addons = sourceAddons.toMutableList()
        val source = candidates.removeAt(idx)
        val addon = if (idx < addons.size) addons.removeAt(idx) else null
        return (listOf(source) + candidates) to (listOf(addon) + addons)
    }
    // Initial URL not in the list: synthesize a candidate carrying the launch
    // headers. Addon unknown -> null, so nothing is demoted off a stale name.
    val current = Stream(
        url = currentUrl,
        audioUrl = currentAudioUrl,
        headers = streamHeaders
    )
    return (listOf(current) + sources) to (listOf(null) + sourceAddons)
}
