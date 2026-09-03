package com.kennyb1201.kbstream.ui.streams

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Stream
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
    private val repository = AddonRepository()
    private val addonManager = AddonManager.getInstance(application)

    private companion object {
        const val TAG = "KBStream"
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
            val streams = fetch(contentType, streamId, debugLines)

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
        debugLines: MutableList<String>
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
        val addons = addonManager.getInstalledAddons()
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

        val rankedStreams = StreamRanker.rank(allStreams)
        val rankedMsg = "ranked total = ${rankedStreams.size}"
        val topMsg = "top stream = ${rankedStreams.firstOrNull()?.name ?: "none"}"

        Log.e(TAG, rankedMsg)
        Log.e(TAG, topMsg)
        debugLines.add(rankedMsg)
        debugLines.add(topMsg)

        return rankedStreams
    }
}