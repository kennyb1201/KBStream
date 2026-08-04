package com.kennyb1201.kbstream.ui.streams

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StreamsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)

    private val _streams = MutableStateFlow<List<Stream>>(emptyList())
    val streams: StateFlow<List<Stream>> = _streams.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _debug = MutableStateFlow<List<String>>(emptyList())
    val debug: StateFlow<List<String>> = _debug.asStateFlow()

    fun load(contentType: String, streamId: String) {
    viewModelScope.launch {
        _isLoading.value = true
        _streams.value = emptyList()
        _debug.value = emptyList()

        val addons = addonManager.getInstalledAddons()
        val streamAddons = addons.filter { it.resources.contains("stream") }

        val debugLines = mutableListOf<String>()
        if (streamAddons.isEmpty()) {
            debugLines.add("No installed addon offers a 'stream' resource -- add one via Manage Add-ons.")
            _debug.value = debugLines
            _isLoading.value = false
            return@launch
        }

        val results = kotlinx.coroutines.supervisorScope {
            streamAddons.map { addon ->
                async {
                    try {
                        val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                        val result = repository.getStreams(baseUrl, contentType, streamId)
                        Pair(addon.name, result)
                    } catch (e: Exception) {
                        Pair(addon.name, e)
                    }
                }
            }.awaitAll()
        }

        val allStreams = mutableListOf<Stream>()

        results.forEach { outcome ->
            when (outcome.second) {
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val streamList = outcome.second as List<Stream>
                    debugLines.add("${outcome.first}: ${streamList.size} result(s) for $contentType/$streamId")
                    allStreams += streamList
                }
                is Exception -> {
                    debugLines.add("${outcome.first}: FAILED -- ${(outcome.second as Exception).message}")
                }
            }
        }

        _debug.value = debugLines
        _streams.value = StreamRanker.rank(allStreams)
        _isLoading.value = false
    }
}
}
