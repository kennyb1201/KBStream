package com.kennyb1201.kbstream.ui.streams

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.data.debrid.TorBoxCachedBadges
import com.kennyb1201.kbstream.data.debrid.TorBoxClient
import com.kennyb1201.kbstream.data.player.DolbyVisionCapability
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.player.SourceAddonMemory
import com.kennyb1201.kbstream.data.reporting.StreamRankReport
import com.kennyb1201.kbstream.data.reporting.TorBoxTrace
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality
import com.kennyb1201.kbstream.domain.streamengine.BingeGroupResolver
import com.kennyb1201.kbstream.domain.streamengine.DebridAddons
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreference
import com.kennyb1201.kbstream.domain.streamengine.StreamDedup
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
    val addons: List<String?>,
    /**
     * Whether EVERY stream add-on was collected for this answer.
     *
     * Auto-play stops collecting after the viewer's own Auto-play Wait once a
     * STRONG answer is in hand (see [AppPreferences.getAutoPlayWaitMs]), so its
     * result can be missing add-ons entirely. A partial answer may paint the picker - it is
     * a true list of real sources - but it may NOT stand as the target's
     * finished load: the fallback picker opened on one and never fetched again, 
     * so a viewer with a Plex-class add-on first and AIOStreams second saw one
     * add-on's rows, no tabs and no way out, however long they waited.
     */
    val complete: Boolean = true,
    /**
     * The add-ons that FAILED for this answer, by their own names.
     *
     * Distinct from an empty success: an add-on that answered with nothing is
     * working, one that threw is not, and the picker says so (see the notice in
     * StreamsScreen) instead of leaving the viewer to wonder why a source they
     * expected is never in the list.
     */
    val failedAddons: List<String> = emptyList()
)

class StreamsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository.getInstance()
    private val addonManager = AddonManager.getInstance(application)

    /**
     * Whether a Dolby Vision label is worth anything on this box, resolved once
     * and reused by every rank call below.
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
    private fun debridBackedAddons(): Set<String> =
        DebridAddons.servedIds(hasDebridServiceConfigured())

    private companion object {
        const val TAG = "KBStream"

        /** Keeps the log line readable when a title is a full release name. */
        const val DESCRIBE_MAX_LABEL = 90
        const val DESCRIBE_MAX_HASH = 12

        /** Sources named in the diagnostics report, head of the list first. */
        const val RANK_REPORT_TOP = 3

        /** One add-on's own ceiling. Unchanged; the overall wait used to be this. */
        const val ADDON_TIMEOUT_MS = 15_000L

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

    /**
     * The add-ons that FAILED for the streams on screen, by name.
     *
     * Published with the list rather than derived on the screen, because the
     * failure is only known here, where each add-on's own result still exists
     * (see [ResolvedSources.failedAddons]).
     */
    private val _addonFailures = MutableStateFlow<List<String>>(emptyList())
    val addonFailures: StateFlow<List<String>> = _addonFailures.asStateFlow()

    // The last target ("contentType:streamId") this ViewModel fetched. Lets the
    // streams screen skip a redundant re-fetch when it re-opens a target that
    // was just resolved in the background (autoselect path), so the picker
    // shows the existing result instantly instead of flashing a loading state.
    private val _loadedKey = MutableStateFlow<String?>(null)
    val loadedKey: StateFlow<String?> = _loadedKey.asStateFlow()

    /**
     * The request this ViewModel last served - stamped by BOTH publish paths
     * ([load] and [resolve]) through [noteRequest], so it cannot disagree with
     * [_loadedKey].
     *
     * Reported: a title whose sources arrived through the autoplay resolver left
     * this pointing at whatever title a picker load had served last, and
     * `refresh()` replayed exactly that - the previous title's sources, from the
     * previous profile. Neither record drives a fetch any more (the pickers hand
     * [refresh] the route target they are displaying), but the pair stays honest
     * because a second identity record that can lie is how the bug started.
     */
    private var lastRequest: StreamsRequest? = null

    private data class StreamsRequest(
        val contentType: String,
        val streamId: String,
        val runtimeMinutes: Int?,
        /**
         * The binge identity the request was made with, so REFRESH keeps it.
         *
         * A refresh re-asks for the SAME title; dropping the context there
         * would put the list back in ranker order on the one press a viewer
         * makes when they are looking for the provider they were bingeing on
         * (audit S-F5). Read only when the target matches, the same discipline
         * that keeps refresh from replaying another title's sources.
         */
        val bingeGroup: String? = null,
        val bingeAddonName: String? = null
    )

    /** The in-flight [load], so a second one supersedes it instead of racing it. */
    private var loadJob: Job? = null

    /**
     * Which fetch the published state belongs to. A cosmetic pass that finishes
     * after a new target was requested must not paint its badges onto the new
     * list (see [requestCachedBadges]).
     */
    private var fetchGeneration = 0

    /**
     * True while [ticket] is still the picker's current request.
     *
     * [fetch] runs off the UI and every publication it makes is a repaint of the
     * picker that asked for it, so each one has to be conditional on the request
     * still being the current one: a newer load (another title) or a profile
     * switch (both tickets bumped, see [resetForProfileChange]) makes the older
     * answer unrepresentable rather than merely late.
     */
    private fun isCurrentRequest(ticket: Int): Boolean =
        ticket == stateGeneration && ticket == fetchGeneration

    /**
     * Owns the published state, and nothing else.
     *
     * [load] (the picker) and [resolve] (auto-play) publish to the SAME
     * _streams/_sourceAddons/_loadedKey/_isLoading, and neither path checked
     * which request it was: whichever fetch finished LAST won, regardless of
     * which target was asked for last. Auto-play resolving in the background
     * while the picker opened for another title could therefore repaint the
     * picker with the first title's sources. A publish only lands when its
     * ticket is still the newest.
     */
    private var stateGeneration = 0

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
    fun load(
        contentType: String,
        streamId: String,
        runtimeMinutes: Int? = null,
        bingeGroup: String? = null,
        bingeAddonName: String? = null
    ) {
        // One load at a time: a second request - the picker's REFRESH, or a new
        // target - supersedes the first. Without this the two coroutines
        // interleave, and because each one appends to `_streams` through the
        // per-add-on callback, the list could end up showing a mix of the two
        // requests' sources.
        loadJob?.cancel()
        noteRequest(contentType, streamId, runtimeMinutes, bingeGroup, bingeAddonName)
        val generation = ++stateGeneration
        // The fetch's own ticket, handed in rather than created inside [fetch], so
        // [resolve] can check the one its publish belongs to.
        val ticket = ++fetchGeneration
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
                ticket = ticket,
                contentType = contentType,
                streamId = streamId,
                debugLines = debugLines,
                onAddonResult = { incoming ->
                    if (generation == stateGeneration) {
                        _streams.value = _streams.value + incoming
                    }
                },
                runtimeMinutes = runtimeMinutes,
                bingeGroup = bingeGroup,
                bingeAddonName = bingeAddonName
            )

            // Cancellation alone does not cover this: there is no suspension
            // between the fetch returning and these writes, so a superseded
            // load could still publish over the newer one.
            if (generation != stateGeneration) return@launch
            _debug.value = debugLines
            _streams.value = resolved.streams
            _sourceAddons.value = resolved.addons
            _addonFailures.value = resolved.failedAddons
            _loadedKey.value = "$contentType:$streamId"
            _isLoading.value = false
        }
    }

    /**
     * Re-asks for the target the picker is DISPLAYING - the streams screen's
     * refresh button.
     *
     * The target is a parameter rather than replayed from [lastRequest]: a target
     * the picker is showing is a fact about the route, and its own record is what
     * let a refresh answer another title's sources (see [lastRequest]). Passing it
     * in makes a wrong replay impossible by construction instead of depending on
     * two books kept in step.
     *
     * A fresh request, not a cache read: the picker is open BECAUSE the viewer
     * wants sources, and a provider that answered thin - or that did not answer
     * in time - a moment ago is exactly what they are pressing for. Sources
     * appear per add-on as they arrive, the same as the first load.
     */
    fun refresh(contentType: String, streamId: String, runtimeMinutes: Int? = null) {
        // The binge context is kept from the request for THIS target, and only
        // from that one: the guard is the same identity check the doc above
        // makes about the sources themselves.
        val sameTarget = lastRequest
            ?.takeIf { it.contentType == contentType && it.streamId == streamId }
        load(
            contentType = contentType,
            streamId = streamId,
            runtimeMinutes = runtimeMinutes,
            bingeGroup = sameTarget?.bingeGroup,
            bingeAddonName = sameTarget?.bingeAddonName
        )
    }

    /**
     * Records the target a publish is about to serve.
     *
     * Called by [load] and [resolve] alike, BEFORE they fetch: every path that
     * publishes picker state stamps both identity slots ([_loadedKey] at the
     * publish, this one here), so the two cannot split again.
     */
    private fun noteRequest(
        contentType: String,
        streamId: String,
        runtimeMinutes: Int?,
        bingeGroup: String? = null,
        bingeAddonName: String? = null
    ) {
        lastRequest = StreamsRequest(
            contentType,
            streamId,
            runtimeMinutes,
            bingeGroup,
            bingeAddonName
        )
    }

    /**
     * Drops the picker's state because the active PROFILE changed.
     *
     * This ViewModel is activity-scoped and shared by the TV and phone pickers,
     * and nothing reset it on a profile switch, so a picker's target, sources and
     * request crossed the profile boundary - the reported case had a refresh on
     * profile 2's title answer with profile 1's streams. Hooked where the
     * ViewModel is owned (MainActivity), keyed on the profile ID so a sync update
     * to the same profile's object does not wipe a live picker.
     *
     * Both tickets are bumped, not just the state one: an in-flight [load] is
     * cancelled, but a [resolve] that is still collecting add-ons publishes
     * unconditionally at its end and would re-stamp the cleared state with the
     * pre-switch title - it checks [fetchGeneration] for exactly this.
     */
    fun resetForProfileChange() {
        loadJob?.cancel()
        loadJob = null
        stateGeneration++
        fetchGeneration++
        _streams.value = emptyList()
        _addonGroups.value = emptyList()
        _sourceAddons.value = emptyList()
        _debug.value = emptyList()
        _loadedKey.value = null
        lastRequest = null
        _isLoading.value = true
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
        val generation = ++stateGeneration
        val ticket = ++fetchGeneration
        // A resolve publishes the SAME state a load does, so it stamps the same
        // identity records a load does. It used to publish without recording its
        // request, which is how the two records split.
        noteRequest(contentType, streamId, runtimeMinutes)
        val debugLines = mutableListOf<String>()
        val resolved = fetch(
            ticket = ticket,
            contentType = contentType,
            streamId = streamId,
            debugLines = debugLines,
            runtimeMinutes = runtimeMinutes,
            // The press-of-Play path: this resolve is on the critical path to
            // the first frame, so it stops collecting a grace after the first
            // STRONG answer instead of waiting out the slowest add-on.
            //
            // The grace is the viewer's own setting (Settings > Stream Picks &
            // Auto-play > Auto-play Wait), not a constant: it is the one number
            // that decides how much of the list Play waits for, and a viewer
            // whose add-ons are slow or whose list is deep is the one who knows
            // whether 4 s or 30 s is right. One read per press, so a change made
            // in Settings applies to the next Play without a restart.
            firstResultGraceMs = AppPreferences.getAutoPlayWaitMs(getApplication())
        )

        // The result still goes back to the caller - auto-play needs it - but
        // it only becomes the picker's state if this resolve is still the most
        // recent request. A picker load that started meanwhile owns the state,
        // and a profile switch has dropped it entirely ([resetForProfileChange]
        // bumps both tickets for this check).
        if (generation == stateGeneration && ticket == fetchGeneration) {
            _debug.value = debugLines
            _streams.value = resolved.streams
            _sourceAddons.value = resolved.addons
            _addonFailures.value = resolved.failedAddons
            // Only a COMPLETE collection may tell the picker this target is
            // loaded: that stamp is the picker's licence to skip its own fetch
            // (see MainActivity's Screen.Streams), so stamping a truncated
            // answer is what froze one add-on's rows into the picker forever.
            // A partial answer still paints - it is a real list - and the
            // picker that opens on it loads in full.
            if (resolved.complete) {
                _loadedKey.value = "$contentType:$streamId"
            }
            _isLoading.value = false
        }
        return resolved
    }

    /**
     * No matter how many add-ons are installed, a single add-on's own ceiling
     * bounds the whole wait: they all start together.
     */
    private suspend fun fetch(
        ticket: Int,
        contentType: String,
        streamId: String,
        debugLines: MutableList<String>,
        onAddonResult: ((List<Stream>) -> Unit)? = null,
        runtimeMinutes: Int? = null,
        firstResultGraceMs: Long? = null,
        bingeGroup: String? = null,
        bingeAddonName: String? = null
    ): ResolvedSources {
        val generation = ticket
        // A new target's groups replace the last one's as soon as this fetch
        // starts, so a background resolve cannot leave stale tabs behind.
        //
        // Gated like every other publication of this list (see [isCurrentRequest]):
        // a fetch that is no longer the picker's current request - the viewer
        // moved to another title, or a profile switch bumped both tickets - must
        // not clear the tabs the CURRENT request is drawing (spec C8).
        if (isCurrentRequest(generation)) {
            _addonGroups.value = emptyList()
        }
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
        // screen the caller opens: publish the anime verdict for that decision
        // here, while the request's id and media type are both in hand, so the
        // launch does not have to resolve it itself (see
        // PlayerEngine.publishLaunchAnime - a warm-up, not the launch's only
        // source). Live channels took the branch above - there is no title to
        // ask about.
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

        // Whether the collection below stopped before every add-on answered.
        // Declared outside the scope so the returned answer can say so.
        var complete = true

        val results: List<AddonLoadResult> = supervisorScope {
            // A channel rather than awaitAll: the caller may be allowed to stop
            // collecting early (auto-play), and awaitAll cannot express "these
            // answered, those are still out". Every add-on is still STARTED
            // together, so this is not serialising anything.
            //
            // Each answer is tagged with the add-on's position in
            // [streamAddons] - which IS the settings order, because that list
            // is [AddonManager.getEnabledAddons] (the manager's own managed
            // order). Answers land in whatever order the network returns them,
            // and that used to be the order the picker published: the add-on
            // chips and the per-add-on slices came out in reply order, so
            // moving an add-on up in Settings changed nothing on the streams
            // screen. Tagged here, sorted below.
            val outcomes = Channel<Pair<Int, AddonLoadResult>>(Channel.UNLIMITED)
            val jobs = streamAddons.mapIndexed { index, addon ->
                launch {
                    // trySend, not send: a straggler may finish in the window
                    // between the cancel below and the channel closing, and a
                    // late answer nobody wants must not throw out of the scope.
                    outcomes.trySend(
                        index to
                            loadAddon(addon, contentType, streamId, debugLines, onAddonResult)
                    )
                }
            }

            val collected = mutableListOf<Pair<Int, AddonLoadResult>>()
            while (collected.size < streamAddons.size) {
                // The wait-all case (the picker) keeps waiting for every
                // add-on, each of which is already bounded by its own 15 s
                // timeout. The auto-play case stops [firstResultGraceMs] after
                // the collection holds a row it could start with a LINK.
                //
                // The grace is decided per WAIT from the hand in front of it,
                // not set once from the first answer: a hash-first answer used
                // to hold the collection open to the full window, so a second
                // add-on answering with a playable row at 9 s still paid the
                // rest of a 17 s wait before auto-play could start. It is also
                // earned by the RIGHT KIND of row now (2026-10-27 weak-head
                // amendment): a hand whose best row is a hash - or one the
                // episode rules or the viewer's ceiling exclude - is WEAK, and
                // stays open for the add-on still out, because a slower add-on's
                // direct row beats resolving the fastest add-on's hash, and that
                // resolve is a round trip the grace exists to keep off the path
                // to the first frame. Strong = short grace; empty or weak = keep
                // listening (each add-on still bounded by its own 15 s) until
                // something startable is there or everyone has answered. The
                // picker's own load passes no grace at all, so its wait is the
                // full window whatever the head is.
                val graceMs =
                    if (firstResultGraceMs != null &&
                        collected.isNotEmpty() &&
                        hasStartableRow(collected) &&
                        hasStrongHead(collected, streamId, runtimeMinutes)
                    ) {
                        firstResultGraceMs
                    } else {
                        null
                    }
                val next =
                    if (graceMs != null) {
                        withTimeoutOrNull(graceMs) { outcomes.receive() }
                    } else {
                        withTimeoutOrNull(ADDON_TIMEOUT_MS + 2_000L) { outcomes.receive() }
                    }
                if (next == null) {
                    // An empty or weak hand waited the full window (every add-on
                    // has now either answered or run out its own 15 s), and a
                    // strong hand had its grace. Either way nobody is still
                    // answering that this collection should wait for.
                    complete = false
                    break
                }
                collected += next
            }
            if (collected.size < streamAddons.size) {
                // Stragglers are no longer waited for, so stop their work too:
                // a request nobody will read must not keep a network call (or
                // this scope) alive on the path to the first frame.
                jobs.forEach { it.cancel() }
            }
            outcomes.close()
            // Settings order, not answer order (see the tag above). A partial
            // collection - auto-play stopped after the first answer - sorts the
            // same way, so its slices are still the leading add-ons' own.
            collected.sortedBy { it.first }.map { it.second }
        }

        if (!complete) {
            val line = "collection stopped early: ${results.size}/${streamAddons.size} addons answered"
            Log.w(TAG, line)
            debugLines.add(line)
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
        val addonOutcomes = SourceAddonMemory.outcomes(getApplication(), streamId)
        val preferredStreams =
            SourceAddonPreference.ordered(
                streams = preppedStreams,
                addonOf = addonOf,
                outcomes = addonOutcomes
            )
        // Whether that reorder actually moved a row, and by which tier. The
        // diagnostics block explains the ranker, and the reorder runs after it:
        // without this line a capture showing a surprising head cannot say
        // whether the ranker put it there or the add-on memory lifted it there,
        // which is the question the report exists to answer.
        val memoryReorder =
            if (
                preferredStreams.size == preppedStreams.size &&
                preferredStreams.indices.any { preferredStreams[it] !== preppedStreams[it] }
            ) {
                "add-on memory · ${addonOutcomes.describe()}"
            } else {
                null
            }

        // Binge continuation in the PICKER's OWN list (audit S-F5). Auto-play
        // has always ordered its hand by the group/addon the last episode
        // played with; the picker did not, so with source auto-select OFF the
        // viewer was shown ranker order and the provider they had been
        // bingeing on was anywhere in it. Same resolver, same rule, same
        // settings toggles - the two surfaces cannot disagree about what the
        // binge continues with. It only reorders; nothing is hidden, and with
        // no context handed over the list is untouched.
        val bingeOutcomes = bingeGroup to bingeAddonName
        val bingeOrdered =
            if (bingeGroup.isNullOrBlank() && bingeAddonName.isNullOrBlank()) {
                preferredStreams
            } else {
                BingeGroupResolver.orderedForNextEpisode(
                    context = getApplication(),
                    streams = preferredStreams,
                    previousBingeGroup = bingeGroup,
                    previousAddonName = bingeAddonName
                )
            }
        if (
            bingeOrdered.size == preferredStreams.size &&
            bingeOrdered.indices.any { bingeOrdered[it] !== preferredStreams[it] }
        ) {
            debugLines.add(
                "  binge: promoted the continued source (group=${bingeOutcomes.first ?: "-"}," +
                    " addon=${bingeOutcomes.second ?: "-"})"
            )
        }

        // The same torrent served by several addons is ONE choice: collapse the
        // repeats into the best-placed row (see StreamDedup). Deliberately after
        // the reorder above, so the survivor is the addon the viewer would have
        // picked from anyway - the one that last worked this title - and before
        // the badges below, so the surviving row keeps them.
        val deduped = StreamDedup.collapse(bingeOrdered)

        // KB-compatible badge packs: attach matched badge chips before
        // the list reaches the UI.
        val withBadges = StreamBadgeEngine.apply(deduped, getApplication())

        // Which copies the viewer's own debrid account already holds (see
        // TorBoxClient): a cached hash starts instantly off the CDN, with no
        // peers to find. Attached after the pack badges because the pack
        // replaces a stream's badge list rather than extending it.
        //
        // Audit P8: this used to AWAIT the TorBox round-trip on the resolve
        // path, so a keyed account paid a network call - and with a list past
        // the 100-hash batch, several serial ones - before playback could
        // start, for a chip in the picker. Answers the process already holds are
        // applied now (no I/O at all); anything else is requested beside the
        // return and folded in if it lands while this fetch is the current one.
        val badgeHashes = withBadges.map { it.infoHash }
        val knownCached = TorBoxClient.knownCached(badgeHashes)
        val markedStreams = TorBoxCachedBadges.mark(withBadges, knownCached)

        // Per-add-on groupings for the picker's tabs: each add-on's own list,
        // prepared by the SAME rules and in the same order as the merged one -
        // rank, the title's addon memory, the torrent dedup, badges, cached
        // marks - so a tab is a filtered view of All rather than a second
        // opinion about where a row belongs. It used to rank and badge only, so
        // a tab's head could be a row the merged list had demoted (an addon
        // whose links are dead for this title) and an add-on returning the same
        // torrent twice showed it twice in its tab but once in All. Empty
        // groups are dropped - a tab with nothing under it is not a tab.
        // Same ticket guard as the streams themselves: this write used to be the
        // one publication of the resolve that nothing checked, so a resolve that
        // finished after a profile switch repainted the cleared picker with the
        // pre-switch title's tabs (spec C8).
        val addonGroupList = results
            .filterIsInstance<AddonLoadResult.Success>()
            .map { result ->
                // This add-on's own identity, not the merged map's: two add-ons
                // can serve the same stream key, and the map keeps only the
                // last one, which would tell the memory tier and the debrid
                // tier the wrong add-on's name inside this tab.
                val addonOfGroup: (Stream) -> String? = { result.addonName }
                val prepared =
                    if (useRanker) {
                        StreamRanker.rank(
                            result.streams,
                            requestedEpisode,
                            dolbyVisionUseful,
                            runtimeMinutes,
                            addonOf = addonOfGroup,
                            debridAddons = debridAddons
                        )
                    } else {
                        result.streams
                    }
                val ordered = SourceAddonPreference.ordered(
                    streams = prepared,
                    addonOf = addonOfGroup,
                    outcomes = addonOutcomes
                )
                StreamAddonGroup(
                    addonName = result.addonName,
                    streams = TorBoxCachedBadges.mark(
                        StreamBadgeEngine.apply(StreamDedup.collapse(ordered), getApplication()),
                        knownCached
                    )
                )
            }
            .filter { it.streams.isNotEmpty() }

        // Same ticket guard as the streams themselves - see [isCurrentRequest].
        if (isCurrentRequest(generation)) {
            _addonGroups.value = addonGroupList
        }

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
                debridAddons = debridAddons,
                reordered = memoryReorder
            )
        )

        if (hasDebridServiceConfigured()) {
            // A list with NO infohash in it gets no check to perform, so the
            // client's own trace never runs and the report would say nothing at
            // all - which is exactly the capture that cannot answer "why is
            // there never a Cached chip". Recorded here instead.
            if (badgeHashes.any { !it.isNullOrBlank() }) {
                requestCachedBadges(generation, badgeHashes)
            } else if (badgeHashes.isNotEmpty()) {
                TorBoxTrace.note(
                    "${badgeHashes.size} sources · none carried an infohash"
                )
            }
        }

        // The parallel add-on list for the final order: each surviving row's
        // own add-on, looked up by the stream identity the ranking map was
        // built from. Dedup keeps one representative of a repeated torrent and
        // the badge pass only adds chips, so every row here resolves.
        return ResolvedSources(
            streams = markedStreams,
            addons = markedStreams.map { addonByStream[streamKey(it)] },
            complete = complete,
            // Which add-ons did not answer at all. A failed add-on contributes
            // no rows, so a picker built from three add-ons where one is down
            // looked exactly like a title whose third source had nothing today
            // - the failure itself was only ever in debugLines.
            failedAddons = results
                .filterIsInstance<AddonLoadResult.Failure>()
                .map { it.addonName }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        )
    }

    /**
     * Whether anything collected so far could be STARTED - a url-bearing row,
     * or a hash row the walk can ask TorBox to prepare.
     *
     * Deliberately no I/O: this is asked on the press-of-Play path, where the
     * whole point of the grace is that nobody waits on a network call. A hash
     * the process has never seen is still startable - the walk resolves it,
     * which is what the walk is for - because the one memory that could say
     * otherwise ([TorBoxClient.knownCached]) is filled AFTER collection, by
     * [requestCachedBadges]. Consulting it here made a cold start's hash-first
     * answer unstartable by construction: the grace was held open and the
     * common case (cold process, CW autoplay, hash-first add-on) became a
     * wait-all for sources that would have started at once.
     */
    private fun hasStartableRow(collected: List<Pair<Int, AddonLoadResult>>): Boolean =
        collected.any { (_, result) ->
            result is AddonLoadResult.Success &&
                result.streams.any {
                    !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank()
                }
        }

    /**
     * Whether the hand in front of the loop holds a row auto-play could start
     * with a LINK - the test that EARNS the short grace, where
     * [hasStartableRow] only says the hand is not empty.
     *
     * The rule itself lives in [AutoPlayQuality.hasStrongHead], applied here to
     * the raw collected rows with the episode identity this request carries: the
     * grace's question and autoplay's own pick have to be the same question, or
     * collection could close on a hand autoplay would then have to walk past.
     *
     * Deliberately no I/O: a hash row's cached-ness is the walk's business (see
     * [AutoplayWalk]), and asking about it here would put a network round trip
     * on the path to the first frame - the cost this grace exists to bound.
     */
    private fun hasStrongHead(
        collected: List<Pair<Int, AddonLoadResult>>,
        streamId: String,
        runtimeMinutes: Int?
    ): Boolean {
        val rows = collected.flatMap { (_, result) ->
            if (result is AddonLoadResult.Success) result.streams else emptyList()
        }
        val episode = EpisodeMatch.requestedFrom(streamId)
        return AutoPlayQuality.hasStrongHead(
            rows = rows,
            season = episode?.first,
            episode = episode?.second,
            runtimeMinutes = runtimeMinutes,
            cap = AppPreferences.getEffectiveAutoPlayQuality(getApplication())
        )
    }

    /**
     * Requests the TorBox cached-hash answer without anyone waiting for it, then
     * folds the result into the published picker state if this fetch is still
     * the current one (audit P8).
     *
     * Marking is idempotent ([TorBoxCachedBadges.mark] never doubles a chip), so
     * re-applying over a list that may have grown is safe; the generation check
     * is what keeps a late answer for a previous title off a new target's list.
     */
    private fun requestCachedBadges(generation: Int, hashes: List<String?>) {
        viewModelScope.launch {
            val cached = TorBoxClient.checkCached(getApplication(), hashes)
            if (cached.isEmpty() || generation != fetchGeneration) return@launch
            _streams.value = TorBoxCachedBadges.mark(_streams.value, cached)
            _addonGroups.value = _addonGroups.value.map { group ->
                group.copy(streams = TorBoxCachedBadges.mark(group.streams, cached))
            }
        }
    }

    /** One add-on's slice of a fetch, in its own failure domain. */
    private suspend fun loadAddon(
        addon: InstalledAddon,
        contentType: String,
        streamId: String,
        debugLines: MutableList<String>,
        onAddonResult: ((List<Stream>) -> Unit)?
    ): AddonLoadResult {
        return try {
            val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
            val requestMsg = "${addon.name}: requesting $contentType/$streamId"
            Log.e(TAG, requestMsg)
            debugLines.add(requestMsg)

            val result = withTimeout(ADDON_TIMEOUT_MS) {
                repository.getStreams(baseUrl, contentType, streamId)
            }

            val returnedMsg = "${addon.name}: returned ${result.size} streams"
            Log.e(TAG, returnedMsg)
            debugLines.add(returnedMsg)

            // Progressive publish: hand this addon's streams to the caller the
            // moment they land instead of holding the picker empty until the
            // slowest addon answers (15s timeout).
            onAddonResult?.invoke(result)

            AddonLoadResult.Success(addon.name, result)
        } catch (e: Exception) {
            val failMsg = "${addon.name}: FAILED -- ${e.message}"
            Log.e(TAG, failMsg, e)
            debugLines.add(failMsg)

            AddonLoadResult.Failure(addon.name, e.message)
        }
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
        debridAddons: Set<String>,
        // The reorder that ran after the ranker, when it moved a row (see
        // [SourceAddonPreference]). Null = nothing moved, so the ranker's own
        // lines are the whole explanation.
        reordered: String? = null
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
            reordered?.let { add("  reorder: $it") }
            streams.take(RANK_REPORT_TOP).forEach { stream ->
                add(
                    "  ${addonOf(stream) ?: "?"} · " +
                        StreamRanker.explain(
                            stream,
                            requestedEpisode,
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