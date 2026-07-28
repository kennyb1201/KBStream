package com.kennyb1201.kbstream.data.addon

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class AddonManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("kbstream_addons", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, InstalledAddon::class.java)
    private val adapter = moshi.adapter<List<InstalledAddon>>(listType)

    fun getInstalledAddons(): List<InstalledAddon> {
        val json = prefs.getString(KEY, null) ?: return defaultAddons().also { saveInstalledAddons(it) }
        return adapter.fromJson(json) ?: emptyList()
    }

    fun saveInstalledAddons(addons: List<InstalledAddon>) {
        prefs.edit().putString(KEY, adapter.toJson(addons)).apply()
    }

    fun removeAddon(id: String) {
        saveInstalledAddons(getInstalledAddons().filterNot { it.id == id })
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
        )
    )

    companion object {
        private const val KEY = "installed_addons_json"
    }
}
