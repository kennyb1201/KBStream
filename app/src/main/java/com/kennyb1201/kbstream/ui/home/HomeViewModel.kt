package com.kennyb1201.kbstream.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: AddonRepository = AddonRepository()
) : ViewModel() {

    private val _catalog = MutableStateFlow<List<MetaPreview>>(emptyList())
    val catalog: StateFlow<List<MetaPreview>> = _catalog.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            _isLoading.value = true
            _catalog.value = repository.getCatalog(type = "movie", catalogId = "top")
            _isLoading.value = false
        }
    }
}
