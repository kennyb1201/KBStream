package com.kennyb1201.kbstream.ui.streams

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.data.debrid.TorBoxCachedBadges
import com.kennyb1201.kbstream.data.debrid.TorBoxClient
import com.kennyb1201.kbstream.data.device.DeviceCapability
import com.kennyb1201.kbstream.data.player.DolbyVisionCapability
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.player.SourceAddonMemory
import com.kennyb1201.kbstream.data.reporting.StreamRankReport
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.domain.streamengine.DebridAddons
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreference
import com.kennyb1201.kbstream.domain.streamengine.StreamDedup
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/**
 * One stream add-on's own results, beside the merged list the picker ranks.
 *
 * The picker's main list mixes every add-on's sources and re-ranks them, so
 * once it is built "which add-on offered the file I wanted" is not readable
 * from the list at all. This keeps each add-on's contribution intact so the
 * screen can offer a tab per add-on (plus All) when more than one answered.
 */
data class StreamAddonGroup(val addonName: String, val streams: List<Stream>)

/**
 * A resolved request: the ranked source list and, in parallel, the add-on each
 * source came from (null for an unknown one).
 *
 * The two travel together because the order of the list is only ever afterwards
 * filtered or re-ordered as a pair - a player that skips an addon whose links
 * are dead for this session (see [com.kennyb1201.kbstream.ui.player.SourceAddonSession])
 * needs to know whose link each row is, and that fact is only known here, where
 * the add-ons' own results are still separate.
 */
data class ResolvedSources(
    val streams: List<Stream>,
    val addons: List<String?>
)

class StreamsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository.getInstance()
    private val addonManager = AddonManager.getInstance(application)

    // Resolved once: the ranker demotes 4K on a box that cannot decode it, so
    // the fact about this device is passed into every rank call below.
    private val constrainedDevice: Boolean by lazy {
        DeviceCapability.constrainedStreamDevice(application)
    }

    /**
     * Whether a Dolby Vision label is worth anything on this box, resolved once
     * like [constrainedDevice].
     *
     * Two things have to be true for it to be: the device must advertise a DV
     * decoder to MediaCodec, and the viewer must not have told Settings that
     * their display has none (DV compat "Strip All" = "my display has no DV",
     * per DolbyVisionCapability's own note). Otherwise a DV copy is not the
     * better of two releases - it is the one the player has to strip, or a
     * pink/green screen - so the ranker must not promote it on the label.
     */
    private val dolbyVisionUseful: Boolean by lazy {
        DolbyVisionCapability.supportsNativeDolbyVision &&
            AppPreferences.getDvCompatMode(application) != AppPreferences.DV_COMPAT_ALL
    }

    /**
     * Whether a debrid service is configured at all.
     *
     * The same check [TorBoxClient] makes before it will talk to the API (see
     * [AppPreferences.getTorboxApiKey]): TorBox is the one debrid account this
     * app holds a key for, and the cached-status badges in the picker are
     * already inert without it. Reused rather than re-derived, so "does the
     * viewer have debrid" cannot mean two things.
     */
    private fun hasDebridServiceConfigured(): Boolean =
        AppPreferences.getTorboxApiKey(getApplication()).isNotBlank()

    /**
     * The add-ons whose streams the ranker should treat as debrid-served.
     *
     * Both halves are required. The add-on list alone would promote an add-on
     * that cannot serve anything (a debrid-first add-on with no account
     * configured resolves no infoHash at all), and the account alone says
     * nothing about which add-on's links reach it.
     */
    private fun debridBackedAddons(): Set<String> {
        if (!hasDebridServiceConfigured()) return emptySet()
        return DebridAddons.normalizedIds()
    }

    private companion object {
        const val TAG = "KBStream"

        /** Keeps the log line readable when a title is a full release name. */
        const val DESCRIBE_MAX_LABEL = 90
        const val DESCRIBE_MAX_HASH = 12

        /** Sources named in the diagnostics report, head of the list first. */
        const val RANK_REPORT_TOP = 3
    }

    private val _streams = MutableStateFlow<List<Stream>>(emptyList())
    val streams: StateFlow<List<Stream>> = _streams.asStateFlow()

    /**
     * Each stream add-on's own sources, in the order the add-on returned them
     * (re-ranked by the same rules). Empty for the live-TV path, which has no
     * add-on, and for a single answering add-on the screen shows no tabs.
     */
    private val _addonGroups = MutableStateFlow<List<StreamAddonGroup>>(emptyList())
    val addonGroups: StateFlow<List<StreamAddonGroup>> = _addonGroups.asStateFlow()

    /**
     * The add-on each entry of [streams] came from, in the same order and the
     * same length (null when unknown). Published beside [streams] so a caller
     * can hand a player both halves of one list.
     */
    private val _sourceAddons = MutableStateFlow<List<String?>>(emptyList())
    val sourceAddons: StateFlow<List<String?>> = _sourceAddons.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _debug = MutableStateFlow<List<String>>(emptyList())
    val debug: StateFlow<List<String>> = _debug.asStateFlow()

    // The last target ("contentType:streamId") this ViewModel fetched. Lets the
    // streams screen skip a redundant re-fetch when it re-opens a target that
    // was just resolved in the background (autoselect path), so the picker
    // shows the existing result instantly instead of flashing a loading state.
    private val _loadedKey = MutableStateFlow<String?>(null)
    val loadedKey: StateFlow<String?> = _loadedKey.asStateFlow()

    /**
     * The request [load] was last given, so [refresh] can ask for the same
     * target again.
     *
     * Kept here rather than handed back in by the route: the picker's refresh
     * button has no target of its own - it re-runs the fetch this ViewModel
     * already knows how to make - and the navigation's target is the
     * navigation's business.
     */
    private var lastRequest: StreamsRequest? = null

    private data class StreamsRequest(
        val contentType: String,
        val streamId: String,
        val runtimeMinutes: Int?
    )

    /** The in-flight [load], so a second one supersedes it instead of racing it. */
    private var loadJob: Job? = null

    private sealed class AddonLoadResult {
        data class Success(val addonName: String, val streams: List<Stream>) : AddonLoadResult()
        data class Failure(val addonName: String, val message: String?) : AddonLoadResult()
    }

    /**
     * [runtimeMinutes] is the title's own length when the screen knows it: the
     * ranker scores size as a density (GB/hour) with it and as bulk without it,
     * so a 45-minute episode's 4 GB and a two-hour film's 4 GB are not read the
     * same way (see StreamRanker).
     */
    fun load(contentType: String, streamId: String, runtimeMinutes: Int? = null) {
        // One load at a time: a second request - the picker's REFRESH, or a new
        // target - supersedes the first. Without this the two coroutines
        // interleave, and because each one appends to `_streams` through the
        // per-add-on callback, the list could end up showing a mix of the two
        // requests' sources.
        loadJob?.cancel()
        lastRequest = StreamsRequest(contentType, streamId, runtimeMinutes)
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _streams.value = emptyList()
            _sourceAddons.value = emptyList()
            _addonGroups.value = emptyList()
            _debug.value = emptyList()

            val debugLines = mutableListOf<String>()

            // Streams appear as each addon answers; the final ranked/badged
            // list replaces the raw accumulation when every addon finished.
            val resolved = fetch(
                contentType,
                streamId,
                debugLines,
                onAddonResult = { incoming ->
                    _streams.value = _streams.value + incoming
                },
                runtimeMinutes = runtimeMinutes
            )

            _debug.value = debugLines
            _streams.value = resolved.streams
            _sourceAddons.value = resolved.addons
            _loadedKey.value = "$contentType:$streamId"
            _isLoading.value = false
        }
    }

    /**
     * Re-asks for the target [load] last resolved - the streams screen's
     * refresh button.
     *
     * A fresh request, not a cache read: the picker is open BECAUSE the viewer
     * wants sources, and a provider that answered thin - or that did not answer
     * in time - a moment ago is exactly what they are pressing for. Sources
     * appear per add-on as they arrive, the same as the first load.
     *
     * A no-op only when nothing has been loaded yet, which is a state the
     * button cannot be drawn in.
     */
    fun refresh() {
        val request = lastRequest ?: return
        load(request.contentType, request.streamId, request.runtimeMinutes)
    }

    // Resolve sources for a target in the background (autoselect flow) and
    // publish the result to the picker state so the streams screen can show it
    // instantly — or skip a re-fetch entirely — if it is ever opened for this
    // target afterwards.
    suspend fun resolve(
        contentType: String,
        streamId: String,
        runtimeMinutes: Int? = null
    ): ResolvedSources {
        val debugLines = mutableListOf<String>()
        val resolved = fetch(
            contentType,
            streamId,
            debugLines,
            runtimeMinutes = runtimeMinutes
        )

        _debug.value = debugLines
        _streams.value = resolved.streams
        _sourceAddons.value = resolved.addons
        _loadedKey.value = "$contentType:$streamId"
        _isLoading.value = false
        return resolved
    }

    private suspend fun fetch(
        contentType: String,
        streamId: String,
        debugLines: MutableList<String>,
        onAddonResult: ((List<Stream>) -> Unit)? = null,
        runtimeMinutes: Int? = null
    ): ResolvedSources {
        // A new target's groups replace the last one's as soon as this fetch
        // starts, so a background resolve cannot leave stale tabs behind.
        _addonGroups.value = emptyList()
        if (contentType == "channel") {
            val directStream = Stream(
                name = "Live TV",
                title = "Live TV",
                url = streamId
            )
            val directMsg = "Direct IPTV channel playback: bypassing add-ons"
            Log.e(TAG, directMsg)
            debugLines.add(directMsg)
            debugLines.add("contentType=channel")
            debugLines.add("streamUrl=$streamId")
            return ResolvedSources(streams = listOf(directStream), addons = listOf(null))
        }

        // The engine this request will play on is chosen later, by the player
        // screen the caller opens, from a Context alone: publish the anime
        // verdict for that decision here, while the request's id and media
        // type are both in hand (see PlayerEngine.publishLaunchAnime). Live
        // channels took the branch above - there is no title to ask about.
        PlayerEngine.publishLaunchAnime(
            context = getApplication(),
            streamId = streamId,
            contentType = contentType
        )

        val allStreams = mutableListOf<Stream>()
        val addons = addonManager.getEnabledAddons()
        val streamAddons = addons.filter { it.resources.contains("stream") }

        if (streamAddons.isEmpty()) {
            debugLines.add("No installed addon offers a 'stream' resource -- add one via Manage Add-ons.")
            return ResolvedSources(streams = emptyList(), addons = emptyList())
        }

        val results: List<AddonLoadResult> = supervisorScope {
            streamAddons.map { addon ->
                async {
                    try {
                        val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                        val requestMsg = "${addon.name}: requesting $contentType/$streamId"
                        Log.e(TAG, requestMsg)
                        debugLines.add(requestMsg)

                        val result = withTimeout(15000) {
                            repository.getStreams(baseUrl, contentType, streamId)
                        }

                        val returnedMsg = "${addon.name}: returned ${result.size} streams"
                        Log.e(TAG, returnedMsg)
                        debugLines.add(returnedMsg)

                        // Progressive publish: hand this addon's streams to
                        // the caller the moment they land instead of holding
                        // the picker empty until the slowest addon answers
                        // (15s timeout).
                        onAddonResult?.invoke(result)

                        AddonLoadResult.Success(addon.name, result)
                    } catch (e: Exception) {
                        val failMsg = "${addon.name}: FAILED -- ${e.message}"
                        Log.e(TAG, failMsg, e)
                        debugLines.add(failMsg)

                        AddonLoadResult.Failure(addon.name, e.message)
                    }
                }
            }.awaitAll()
        }

        results.forEach { result ->
            when (result) {
                is AddonLoadResult.Success -> {
                    debugLines.add("${result.addonName}: ${result.streams.size} result(s) for $contentType/$streamId")
                    allStreams += result.streams
                }
                is AddonLoadResult.Failure -> {
                    debugLines.add("${result.addonName}: FAILED -- ${result.message}")
                }
            }
        }

        val useRanker = AppPreferences.getUseStreamRanker(getApplication())
        // The episode this request is for, when its id names one: the ranker
        // keeps a source that declares another episode of the same season out
        // of the head of the list, which is the position the picker and
        // auto-play both take from.
        val requestedEpisode = EpisodeMatch.requestedFrom(streamId)

        // Which add-on each source came from, by the same stream identity the
        // ranking map is built from. Hoisted above the rank call because both
        // consumers need it now: the memory tier reorders by it, and the ranker
        // reads it to recognize a debrid-backed add-on whose links name no
        // debrid host (see [DebridAddons]). One lambda, two consumers - a second
        // copy would be a second answer to "whose link is this".
        val addonByStream =
            results
                .filterIsInstance<AddonLoadResult.Success>()
                .flatMap { result ->
                    result.streams.map { stream -> streamKey(stream) to result.addonName }
                }
                .toMap()
        val addonOf: (Stream) -> String? = { stream -> addonByStream[streamKey(stream)] }
        // Empty unless a debrid service is configured: an add-on that is
        // debrid-first serves nothing without an account, so it must not be
        // lifted for being one.
        val debridAddons = debridBackedAddons()

        val preppedStreams =
            if (useRanker) {
                StreamRanker.rank(
                    allStreams,
                    requestedEpisode,
                    constrainedDevice,
                    dolbyVisionUseful,
                    runtimeMinutes,
                    addonOf = addonOf,
                    debridAddons = debridAddons
                )
            } else {
                allStreams
            }

        // What this TITLE's addons last did (see [SourceAddonMemory]). The
        // reported case: a show whose every AIOStreams link sat dead while
        // another addon played it - so the next episode put the same
        // unplayable links back at the head of the list, which is also the
        // copy the picker's All tab showed first and auto-play took. An addon
        // that opened a file here now leads, one whose link would not open
        // goes behind everything else, and an addon nothing is known about is
        // left exactly where the ranker put it. Applied to the ranked list
        // itself, so the picker, the tabs' parent list and auto-play all see
        // the same order. It only reorders - nothing is hidden, and a failure
        // expires on its own ([SourceAddonMemory.TTL_MS]).
        val preferredStreams =
            SourceAddonPreference.ordered(
                streams = preppedStreams,
                addonOf = addonOf,
                outcomes = SourceAddonMemory.outcomes(getApplication(), streamId)
            )

        // The same torrent served by several addons is ONE choice: collapse the
        // repeats into the best-placed row (see StreamDedup). Deliberately after
        // the reorder above, so the survivor is the addon the viewer would have
        // picked from anyway - the one that last worked this title - and before
        // the badges below, so the surviving row keeps them.
        val deduped = StreamDedup.collapse(preferredStreams)

        // KB-compatible badge packs: attach matched badge chips before
        // the list reaches the UI.
        val withBadges = StreamBadgeEngine.apply(deduped, getApplication())

        // Which copies the viewer's own debrid account already holds (see
        // TorBoxClient): a cached hash starts instantly off the CDN, with no
        // peers to find. One batched check for the whole list, inert without a
        // key, and cached for an hour. Attached after the pack badges because
        // the pack replaces a stream's badge list rather than extending it.
        val cachedHashes = TorBoxClient.checkCached(
            getApplication(),
            withBadges.map { it.infoHash }
        )
        val markedStreams = TorBoxCachedBadges.mark(withBadges, cachedHashes)

        // Per-add-on groupings for the picker's tabs: each add-on's own list,
        // prepared by the same rules as the merged one (ranked, badged) so a
        // tab reads like a filtered view of All rather than a raw dump. Empty
        // groups are dropped - a tab with nothing under it is not a tab.
        _addonGroups.value = results
            .filterIsInstance<AddonLoadResult.Success>()
            .map { result ->
                val prepared =
                    if (useRanker) {
                        StreamRanker.rank(
                            result.streams,
                            requestedEpisode,
                            constrainedDevice,
                            dolbyVisionUseful,
                            runtimeMinutes,
                            addonOf = addonOf,
                            debridAddons = debridAddons
                        )
                    } else {
                        result.streams
                    }
                StreamAddonGroup(
                    addonName = result.addonName,
                    streams = TorBoxCachedBadges.mark(
                        StreamBadgeEngine.apply(prepared, getApplication()),
                        cachedHashes
                    )
                )
            }
            .filter { it.streams.isNotEmpty() }
        val rankedMsg = if (useRanker) "ranked total = ${markedStreams.size}" else "unranked total = ${markedStreams.size}"
        val topMsg = "top stream = ${markedStreams.firstOrNull()?.let(::describeStream) ?: "none"}"

        Log.e(TAG, rankedMsg)
        Log.e(TAG, topMsg)
        debugLines.add(rankedMsg)
        debugLines.add(topMsg)

        // Into the diagnostics report as well: the picker shows the order but
        // never the rule that produced it, and the two rules that outrank every
        // quality label are invisible in the list itself.
        StreamRankReport.record(
            rankReportLines(
                streams = markedStreams,
                addonOf = addonOf,
                ranked = useRanker,
                requestedEpisode = requestedEpisode,
                runtimeMinutes = runtimeMinutes,
                debridAddons = debridAddons
            )
        )

        // The parallel add-on list for the final order: each surviving row's
        // own add-on, looked up by the stream identity the ranking map was
        // built from. Dedup keeps one representative of a repeated torrent and
        // the badge pass only adds chips, so every row here resolves.
        return ResolvedSources(
            streams = markedStreams,
            addons = markedStreams.map { addonByStream[streamKey(it)] }
        )
    }

    /**
     * The diagnostics block for one fetch: how many sources came back, and for
     * the head of the list, which add-on sent each one and why it is there.
     *
     * The add-on is carried alongside because the ranked list mixes every
     * add-on's results into one — once the ranker reorders them, "whose link is
     * this" stops being readable from the list, which is the first question a
     * complaint about the order asks.
     */
    private fun rankReportLines(
        streams: List<Stream>,
        addonOf: (Stream) -> String?,
        ranked: Boolean,
        requestedEpisode: Pair<Int, Int>?,
        runtimeMinutes: Int?,
        debridAddons: Set<String>
    ): List<String> {
        if (streams.isEmpty()) return listOf("streams: none returned")

        return buildList {
            add(
                "streams: ${streams.size} source(s), " +
                    (if (ranked) "ranked" else "add-on order") +
                    // The request the block belongs to, named here rather than
                    // inferred from a source's "[declared != requested]"
                    // bracket: when nothing in the list declares an episode
                    // there is no bracket at all, and the one block per episode
                    // is otherwise indistinguishable from its neighbors.
                    (requestedEpisode?.let { " for S%02dE%02d".format(it.first, it.second) } ?: "") +
                    ", top $RANK_REPORT_TOP:"
            )
            streams.take(RANK_REPORT_TOP).forEach { stream ->
                add(
                    "  ${addonOf(stream) ?: "?"} · " +
                        StreamRanker.explain(
                            stream,
                            requestedEpisode,
                            constrainedDevice,
                            // The parameters the order was actually built with,
                            // not the defaults: a report that printed a DV
                            // bonus for a box that cannot show DV - or a
                            // density score for a runtime the request did not
                            // carry, or a debrid tier for an add-on set that
                            // never reached the ranker - would explain a
                            // different list.
                            dolbyVisionUseful,
                            runtimeMinutes,
                            addonOf = addonOf,
                            debridAddons = debridAddons
                        )
                )
            }
            // Why auto-play did or did not start a source, in the report's own
            // words: with a series episode in the request the head of this
            // list is not always the one it plays, and "it went to the picker"
            // has to be readable from the report rather than from the TV.
            if (requestedEpisode != null && EpisodeMatch.onlyOtherEpisodes(streams, requestedEpisode.first, requestedEpisode.second)) {
                add(
                    "  every source declares another episode of S%02d - auto-play stops"
                        .format(requestedEpisode.first)
                )
            }
        }
    }

    /** A stream's identity for the report: the link it plays, or its hash. */
    private fun streamKey(stream: Stream): String =
        stream.url ?: stream.infoHash ?: stream.title ?: stream.name ?: ""

    /**
     * One-line identity for the stream that was picked, for both the log and
     * the on-screen debug lines.
     *
     * The line used to print `name` alone, which most Stremio addons leave
     * blank — so `top stream = ` with nothing after it was the normal output,
     * and a bad playback could not be traced back to the result it came from.
     * The fallbacks are ordered by how reliably they identify the stream: the
     * declared name, the display title, the server's filename hint, then the
     * URL's own filename — which is the same string the player logs as
     * `uri=...`, so the two log lines can be lined up. The host and the
     * info-hash cover the cases where the addon sends no name at all.
     */
    private fun describeStream(stream: Stream): String {
        val label = stream.name?.takeIf { it.isNotBlank() }
            ?: stream.title?.takeIf { it.isNotBlank() }
            ?: stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }
            ?: stream.url?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "(unnamed)"

        val host = stream.url
            ?.takeIf { it.startsWith("http") }
            ?.let { android.net.Uri.parse(it).host }

        return listOfNotNull(
            label.take(DESCRIBE_MAX_LABEL),
            host,
            stream.infoHash?.take(DESCRIBE_MAX_HASH)?.let { "hash=$it" },
            stream.badges
                .takeIf { it.isNotEmpty() }
                ?.joinToString("/") { it.name.take(20) }
        ).joinToString(" | ")
    }
}