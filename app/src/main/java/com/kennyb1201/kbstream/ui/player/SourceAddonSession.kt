package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreference
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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
    private val isServable: (url: String, headers: Map<String, String>) -> Boolean,
) {

    /** The production probe: a ranged GET on the shared app HTTP client. */
    constructor(dead: MutableSet<String>, client: OkHttpClient) : this(
        dead,
        { url, headers -> rangedGetServable(client, url, headers) }
    )

    /**
     * Returns the first source whose probe succeeds, or the top source if all
     * probes fail (the probe can be wrong; the player is the real test).
     * Probe failures mark the addon dead for the session.
     *
     * Runs on [Dispatchers.IO], sequentially - one host at a time avoids
     * tripping rate limits, and three short GETs are fast enough.
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
            var probes = 0
            for (i in candidates.indices) {
                if (probes >= SourceAddonSession.MAX_PROBES) break
                val candidate = candidates[i]
                val url = candidate.url
                if (url.isNullOrBlank()) continue
                probes += 1
                val servable = runCatching { isServable(url, headersFor(candidate)) }
                    .getOrDefault(false)
                if (servable) return@withContext candidate
                SourceAddonSession.markDead(dead, addons.getOrNull(i))
            }
            candidates.first()
        }
    }

    companion object {
        /** Per-probe ceiling, so one hanging host cannot stall the launch. */
        const val TIMEOUT_MS = 2500L

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
            headers: Map<String, String>
        ): Boolean = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Range", RANGE_VALUE)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .get()
                .build()
            client.newBuilder()
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }
}

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
