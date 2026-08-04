package com.kennyb1201.kbstream.ui.streams

import android.app.Application
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

class StreamsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)

    private val _streams = MutableStateFlow<List<Stream>>(emptyList())
    val streams: StateFlow<List<Stream>> = _streams.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _debug = MutableStateFlow<List<String>>(emptyList())
    val debug: StateFlow<List<String>> = _debug.asStateFlow()

    private sealed class AddonLoadResult {
        data class Success(val addonName: String, val streams: List<Stream>) : AddonLoadResult()
        data class Failure(val addonName: String, val message: String?) : AddonLoadResult()
    }

    fun load(contentType: String, streamId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _streams.value = emptyList()
            _debug.value = emptyList()

            val allStreams = mutableListOf<Stream>()
            val debugLines = mutableListOf<String>()
            val addons = addonManager.getInstalledAddons()
            val streamAddons = addons.filter { it.resources.contains("stream") }

            if (streamAddons.isEmpty()) {
                debugLines.add("No installed addon offers a 'stream' resource -- add one via Manage Add-ons.")
                _debug.value = debugLines
                _isLoading.value = false
                return@launch
            }

            val results: List<AddonLoadResult> = supervisorScope {
                streamAddons.map { addon ->
                    async {
                        try {
                            val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                            val result = repository.getStreams(baseUrl, contentType, streamId)
                            AddonLoadResult.Success(addon.name, result)
                        } catch (e: Exception) {
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

            _debug.value = debugLines
            _streams.value = StreamRanker.rank(allStreams)
            _isLoading.value = false
        }
    }
}
