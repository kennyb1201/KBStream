package com.kennyb1201.kbstream.data.addon

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AddonManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(
            "kbstream_addons",
            Context.MODE_PRIVATE
        )

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listType =
        Types.newParameterizedType(
            List::class.java,
            InstalledAddon::class.java
        )

    private val adapter =
        moshi.adapter<List<InstalledAddon>>(listType)

    private val _installedAddons =
        MutableStateFlow<List<InstalledAddon>>(emptyList())

    val installedAddons: StateFlow<List<InstalledAddon>> =
        _installedAddons.asStateFlow()

    private val addonScope =
        CoroutineScope(
            Dispatchers.Default + SupervisorJob()
        )

    val streamAddons: StateFlow<List<InstalledAddon>> =
        _installedAddons
            .map { list ->
                list.filter {
                    "stream" in it.resources
                }
            }
            .stateIn(
                scope = addonScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    val catalogAddons: StateFlow<List<InstalledAddon>> =
        _installedAddons
            .map { list ->
                list.filter {
                    "catalog" in it.resources
                }
            }
            .stateIn(
                scope = addonScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    init {
        _installedAddons.value =
            loadFromPreferencesOrDefaults()
    }

    fun hasAddon(id: String): StateFlow<Boolean> {
        return _installedAddons
            .map { list ->
                list.any { it.id == id }
            }
            .stateIn(
                scope = addonScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    _installedAddons.value.any {
                        it.id == id
                    }
            )
    }

    private fun loadFromPreferencesOrDefaults():
            List<InstalledAddon> {

        val json = prefs.getString(KEY, null)

        if (json.isNullOrBlank()) {
            return defaultAddons().also {
                saveToPrefs(it)
            }
        }

        return try {
            adapter.fromJson(json)
                ?: emptyList()
        } catch (_: Exception) {
            defaultAddons().also {
                saveToPrefs(it)
            }
        }
    }

    private fun saveToPrefs(
        addons: List<InstalledAddon>
    ) {
        prefs.edit()
            .putString(
                KEY,
                adapter.toJson(addons)
            )
            .apply()
    }

    fun getInstalledAddons(): List<InstalledAddon> {
        return _installedAddons.value.ifEmpty {
            loadFromPreferencesOrDefaults()
                .also {
                    _installedAddons.value = it
                }
        }
    }

    fun saveInstalledAddons(
        addons: List<InstalledAddon>
    ) {
        val normalized = addons.map { addon ->
            addon.copy(
                catalogs = normalizeCatalogOrder(
                    addon.catalogs
                )
            )
        }

        saveToPrefs(normalized)
        _installedAddons.value = normalized
    }

    fun removeAddon(id: String) {
        saveInstalledAddons(
            getInstalledAddons()
                .filterNot { it.id == id }
        )
    }

    fun renameAddon(
        id: String,
        newName: String?
    ) {
        val cleaned =
            newName
                ?.trim()
                .orEmpty()

        val updated =
            getInstalledAddons().map { addon ->
                if (addon.id == id) {
                    addon.copy(
                        customName =
                            cleaned.takeIf {
                                it.isNotBlank()
                            }
                    )
                } else {
                    addon
                }
            }

        saveInstalledAddons(updated)
    }

    /**
     * Move an addon itself up/down.
     */
    fun moveAddon(
        id: String,
        direction: Int
    ) {
        val current =
            getInstalledAddons()
                .toMutableList()

        val index =
            current.indexOfFirst {
                it.id == id
            }

        if (index < 0) return

        val target =
            index + direction

        if (target !in current.indices) {
            return
        }

        val item =
            current.removeAt(index)

        current.add(target, item)

        saveInstalledAddons(current)
    }

    /**
     * Update a single catalog's Home visibility.
     */
    fun setCatalogHomeVisibility(
        addonId: String,
        catalogType: String,
        catalogId: String,
        showOnHome: Boolean
    ) {
        val updated =
            getInstalledAddons().map { addon ->

                if (addon.id != addonId) {
                    return@map addon
                }

                val catalogs =
                    addon.catalogs.map { catalog ->

                        if (
                            catalog.type == catalogType &&
                            catalog.id == catalogId
                        ) {
                            catalog.copy(
                                showOnHome = showOnHome
                            )
                        } else {
                            catalog
                        }
                    }

                addon.copy(
                    catalogs = catalogs
                )
            }

        saveInstalledAddons(updated)
    }

    /**
     * Move one catalog up/down within its addon.
     */
    fun moveCatalog(
        addonId: String,
        catalogType: String,
        catalogId: String,
        direction: Int
    ) {
        val addons =
            getInstalledAddons()
                .toMutableList()

        val addonIndex =
            addons.indexOfFirst {
                it.id == addonId
            }

        if (addonIndex < 0) {
            return
        }

        val addon =
            addons[addonIndex]

        val catalogs =
            addon.catalogs
                .sortedBy { it.order }
                .toMutableList()

        val index =
            catalogs.indexOfFirst {
                it.type == catalogType &&
                    it.id == catalogId
            }

        if (index < 0) {
            return
        }

        val target =
            index + direction

        if (target !in catalogs.indices) {
            return
        }

        val item =
            catalogs.removeAt(index)

        catalogs.add(target, item)

        val reordered =
            catalogs.mapIndexed { index, catalog ->
                catalog.copy(
                    order = index
                )
            }

        addons[addonIndex] =
            addon.copy(
                catalogs = reordered
            )

        saveInstalledAddons(addons)
    }

    /**
     * Replace an addon manifest while preserving
     * KBStream-specific catalog settings.
     *
     * This is what you want when an addon manifest
     * is refreshed.
     */
    fun updateAddonFromManifest(
        manifestUrl: String,
        manifest: AddonManifest
    ) {
        val existing =
            getInstalledAddons()
                .firstOrNull {
                    it.id == manifest.id ||
                        it.manifestUrl == manifestUrl
                }

        val existingCatalogs =
            existing
                ?.catalogs
                .orEmpty()

        val existingByKey =
            existingCatalogs.associateBy {
                catalogKey(
                    it.type,
                    it.id
                )
            }

        val mergedCatalogs =
            manifest.catalogs
                .mapIndexed { index, manifestCatalog ->

                    val previous =
                        existingByKey[
                            catalogKey(
                                manifestCatalog.type,
                                manifestCatalog.id
                            )
                        ]

                    manifestCatalog.copy(
                        showOnHome =
                            previous?.showOnHome
                                ?: true,

                        order =
                            previous?.order
                                ?: index
                    )
                }
                .sortedBy { it.order }
                .mapIndexed { index, catalog ->
                    catalog.copy(
                        order = index
                    )
                }

        val updatedAddon =
            InstalledAddon(
                manifestUrl = manifestUrl,
                id = manifest.id,
                name = manifest.name,
                resources = manifest.resources,
                catalogs = mergedCatalogs,
                customName = existing?.customName,
                version = manifest.version,
                description = manifest.description,
                types = manifest.types
            )

        val current =
            getInstalledAddons()
                .toMutableList()

        val index =
            current.indexOfFirst {
                it.id == updatedAddon.id ||
                    it.manifestUrl == manifestUrl
            }

        if (index >= 0) {
            current[index] = updatedAddon
        } else {
            current.add(updatedAddon)
        }

        saveInstalledAddons(current)
    }

    /**
     * Remove a catalog from the saved configuration.
     */
    fun removeCatalog(
        addonId: String,
        catalogType: String,
        catalogId: String
    ) {
        val updated =
            getInstalledAddons().map { addon ->

                if (addon.id != addonId) {
                    return@map addon
                }

                val catalogs =
                    addon.catalogs
                        .filterNot {
                            it.type == catalogType &&
                                it.id == catalogId
                        }

                addon.copy(
                    catalogs =
                        normalizeCatalogOrder(catalogs)
                )
            }

        saveInstalledAddons(updated)
    }

    /**
     * Returns every configured catalog in the
     * current saved order.
     */
    fun getCatalogConfigurations():
            List<CatalogConfiguration> {

        return getInstalledAddons()
            .flatMap { addon ->

                addon.catalogs
                    .sortedBy { it.order }
                    .map { catalog ->

                        CatalogConfiguration(
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalog = catalog
                        )
                    }
            }
    }

    /**
     * Returns only catalogs that should appear
     * on Home.
     */
    fun getHomeCatalogConfigurations():
            List<CatalogConfiguration> {

        return getCatalogConfigurations()
            .filter {
                it.catalog.showOnHome
            }
    }

    fun refreshAddons() {
        _installedAddons.value =
            loadFromPreferencesOrDefaults()
    }

    private fun normalizeCatalogOrder(
        catalogs: List<ManifestCatalog>
    ): List<ManifestCatalog> {
        return catalogs
            .sortedBy { it.order }
            .mapIndexed { index, catalog ->
                catalog.copy(
                    order = index
                )
            }
    }

    private fun catalogKey(
        type: String,
        id: String
    ): String {
        return "${type.lowercase()}::$id"
    }

    private fun defaultAddons():
            List<InstalledAddon> {

        return listOf(

            InstalledAddon(
                manifestUrl =
                    "https://v3-cinemeta.strem.io/manifest.json",

                id =
                    "com.linvo.cinemeta",

                name =
                    "Cinemeta",

                resources =
                    listOf(
                        "catalog",
                        "meta"
                    ),

                catalogs =
                    listOf(
                        ManifestCatalog(
                            type = "movie",
                            id = "top",
                            name = "Top Movies",
                            showOnHome = true,
                            order = 0
                        ),

                        ManifestCatalog(
                            type = "series",
                            id = "top",
                            name = "Top Series",
                            showOnHome = true,
                            order = 1
                        )
                    )
            ),

            InstalledAddon(
                manifestUrl =
                    "http://132.145.137.148:8080/stremio/67f82e67-ed57-4cef-bf0b-32b7386fae01/eyJpIjoiamlEcExKUGljdnpZUkRHUEcxWTRuUT09IiwiZSI6Ik9FeEFFY0QxYlpXMktzVkV3UGo4YUY4MUdtY2w4ZEVwT2hEWlo3enNBQ3M9IiwidCI6ImEifQ/manifest.json",

                id =
                    "aiostreams",

                name =
                    "AIOStreams",

                resources =
                    listOf("stream"),

                catalogs =
                    emptyList()
            )
        )
    }

    companion object {
        private const val KEY =
            "installed_addons_json"
    }
}

/**
 * A catalog plus the addon it belongs to.
 */
data class CatalogConfiguration(
    val addonId: String,
    val addonName: String,
    val catalog: ManifestCatalog
)
