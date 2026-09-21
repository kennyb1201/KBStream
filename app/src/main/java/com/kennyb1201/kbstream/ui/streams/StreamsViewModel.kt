package com.kennyb1201.kbstream.ui.streams

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

class StreamsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository.getInstance()
    private val addonManager = AddonManager.getInstance(application)

    private companion object {
        const val TAG = "KBStream"

        /** Keeps the log line readable when a title is a full release name. */
        const val DESCRIBE_MAX_LABEL = 90
        const val DESCRIBE_MAX_HASH = 12
    }

    private val _streams = MutableStateFlow<List<Stream>>(emptyList())
    val streams: StateFlow<List<Stream>> = _streams.asStateFlow()

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

    private sealed class AddonLoadResult {
        data class Success(val addonName: String, val streams: List<Stream>) : AddonLoadResult()
        data class Failure(val addonName: String, val message: String?) : AddonLoadResult()
    }

    fun load(contentType: String, streamId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _streams.value = emptyList()
            _debug.value = emptyList()

            val debugLines = mutableListOf<String>()

            // Streams appear as each addon answers; the final ranked/badged
            // list replaces the raw accumulation when every addon finished.
            val streams = fetch(
                contentType,
                streamId,
                debugLines,
                onAddonResult = { incoming ->
                    _streams.value = _streams.value + incoming
                }
            )

            _debug.value = debugLines
            _streams.value = streams
            _loadedKey.value = "$contentType:$streamId"
            _isLoading.value = false
        }
    }

    // Resolve sources for a target in the background (autoselect flow) and
    // publish the result to the picker state so the streams screen can show it
    // instantly — or skip a re-fetch entirely — if it is ever opened for this
    // target afterwards.
    suspend fun resolve(contentType: String, streamId: String): List<Stream> {
        val debugLines = mutableListOf<String>()
        val streams = fetch(contentType, streamId, debugLines)

        _debug.value = debugLines
        _streams.value = streams
        _loadedKey.value = "$contentType:$streamId"
        _isLoading.value = false
        return streams
    }

    private suspend fun fetch(
        contentType: String,
        streamId: String,
        debugLines: MutableList<String>,
        onAddonResult: ((List<Stream>) -> Unit)? = null
    ): List<Stream> {
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
            return listOf(directStream)
        }

        val allStreams = mutableListOf<Stream>()
        val addons = addonManager.getEnabledAddons()
        val streamAddons = addons.filter { it.resources.contains("stream") }

        if (streamAddons.isEmpty()) {
            debugLines.add("No installed addon offers a 'stream' resource -- add one via Manage Add-ons.")
            return emptyList()
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
        val preppedStreams = if (useRanker) StreamRanker.rank(allStreams) else allStreams
        // KB-compatible badge packs: attach matched badge chips before
        // the list reaches the UI.
        val withBadges = StreamBadgeEngine.apply(preppedStreams, getApplication())
        val rankedMsg = if (useRanker) "ranked total = ${withBadges.size}" else "unranked total = ${withBadges.size}"
        val topMsg = "top stream = ${withBadges.firstOrNull()?.let(::describeStream) ?: "none"}"

        Log.e(TAG, rankedMsg)
        Log.e(TAG, topMsg)
        debugLines.add(rankedMsg)
        debugLines.add(topMsg)

        return withBadges
    }

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