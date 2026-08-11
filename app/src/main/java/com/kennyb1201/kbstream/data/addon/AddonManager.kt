package com.kennyb1201.kbstream.data.addon

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AddonManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("kbstream_addons", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, InstalledAddon::class.java)
    private val adapter = moshi.adapter<List<InstalledAddon>>(listType)

    private val _installedAddons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val installedAddons: StateFlow<List<InstalledAddon>> = _installedAddons.asStateFlow()

    init {
        // Load initial state into the hot flow immediately upon creation
        _installedAddons.value = loadFromPreferencesOrDefaults()
    }

    private fun loadFromPreferencesOrDefaults(): List<InstalledAddon> {
        val json = prefs.getString(KEY, null)
        if (json.isNullOrBlank()) {
            return defaultAddons().also { saveToPrefs(it) }
        }
        return adapter.fromJson(json) ?: emptyList()
    }

    private fun saveToPrefs(addons: List<InstalledAddon>) {
        prefs.edit().putString(KEY, adapter.toJson(addons)).apply()
    }

    fun getInstalledAddons(): List<InstalledAddon> {
        // Return current hot flow value instantly, fallback/initialize if empty
        return _installedAddons.value.ifEmpty {
            loadFromPreferencesOrDefaults().also { _installedAddons.value = it }
        }
    }

    fun saveInstalledAddons(addons: List<InstalledAddon>) {
        saveToPrefs(addons)
        // Push fresh state to all collectors instantly
        _installedAddons.value = addons
    }

    fun removeAddon(id: String) {
        val updated = getInstalledAddons().filterNot { it.id == id }
        saveInstalledAddons(updated)
    }

    fun refreshAddons() {
        _installedAddons.value = loadFromPreferencesOrDefaults()
    }

    private fun defaultAddons(): List<InstalledAddon> = listOf(
        InstalledAddon(
            manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
            id = "com.linvo.cinemeta",
            name = "Cinemeta",
            resources = listOf("catalog", "meta"),
            catalogs = listOf(
                ManifestCatalog(type = "movie", id = "top", name = "Top Movies"),
                ManifestCatalog(type = "series", id = "top", name = "Top Series")
            )
        ),
        InstalledAddon(
            manifestUrl = "http://132.145.137.148:8080/stremio/67f82e67-ed57-4cef-bf0b-32b7386fae01/eyJpIjoiamlEcExKUGljdnpZUkRHUEcxWTRuUT09IiwiZSI6Ik9FeEFFY0QxYlpXMktzVkV3UGo4YUY4MUdtY2w4ZEVwT2hEWlo3enNBQ3M9IiwidCI6ImEifQ/manifest.json",
            id = "aiostreams",
            name = "AIOStreams",
            resources = listOf("stream"),
            catalogs = emptyList()
        )
    )

    companion object {
        private const val KEY = "installed_addons_json"
    }
}
