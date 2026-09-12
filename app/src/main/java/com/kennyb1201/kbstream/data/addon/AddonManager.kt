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

class AddonManager(
    context: Context
) {

    private val prefs =
        context.applicationContext.getSharedPreferences(
            "kbstream_addons",
            Context.MODE_PRIVATE
        )

    private val moshi =
        Moshi.Builder()
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
        MutableStateFlow<List<InstalledAddon>>(
            emptyList()
        )

        private val _catalogOrderVersion = MutableStateFlow(0)
val catalogOrderVersion: StateFlow<Int> = _catalogOrderVersion.asStateFlow()

    val installedAddons: StateFlow<List<InstalledAddon>> =
        _installedAddons.asStateFlow()

    /**
     * Orders meta addon candidates for a given raw id so probes hit the most
     * likely source first:
     *
     *  1. addons whose manifest declares a matching idPrefix (e.g. a TVDB
     *     addon for "tvdb:12345", Cinemeta for "tt1234567"),
     *  2. addons whose manifest declares NO idPrefixes (legacy accept-all),
     *  3. addons whose declared prefixes don't match the id (unlikely to
     *     resolve, asked last only as a safety net).
     *
     * This keeps TVDB-sourced titles (and anime addons that emit anime ids)
     * resolving from the right addon without breaking legacy manifests that
     * never declared prefixes. Additive: null prefixes keep the old order.
     */
    fun orderMetaAddonsForId(
        addons: List<InstalledAddon>,
        rawId: String,
        type: String
    ): List<InstalledAddon> {
        val normalizedType = when (type.lowercase().trim()) {
            "tv", "show" -> "series"
            else -> type.lowercase().trim()
        }
        val idLower = rawId.trim().lowercase()
        val candidates = addons.filter { addon ->
            "meta" in addon.resources &&
                (addon.types.isEmpty() ||
                    normalizedType in addon.types.map { t ->
                        when (t.lowercase().trim()) {
                            "tv", "show" -> "series"
                            else -> t.lowercase().trim()
                        }
                    })
        }
        val (matching, rest) = candidates.partition { addon ->
            val prefixes = addon.idPrefixes.orEmpty()
            prefixes.isNotEmpty() && prefixes.any { prefix ->
                idLower.startsWith(prefix.trim().lowercase())
            }
        }
        val (legacy, mismatched) = rest.partition { addon ->
            addon.idPrefixes.isNullOrEmpty()
        }
        return matching + legacy + mismatched
    }

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

    fun hasAddon(
        id: String
    ): StateFlow<Boolean> {

        return _installedAddons
            .map { list ->
                list.any {
                    it.id == id
                }
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

        val json =
            prefs.getString(
                KEY,
                null
            )

        if (json.isNullOrBlank()) {

            val defaults =
                defaultAddons()

            saveToPrefs(defaults)

            return defaults
        }

        return try {

            val loaded =
                adapter.fromJson(json)
                    ?: emptyList()

            /*
             * IMPORTANT:
             *
             * Older KBStream versions stored
             * catalog.order per addon.
             *
             * Convert that saved configuration
             * into one global ordering now.
             */
            val normalized =
                normalizeGlobalCatalogOrder(
                    loaded
                )

            if (normalized != loaded) {
                saveToPrefs(normalized)
            }

            normalized

        } catch (_: Exception) {

            val defaults =
                defaultAddons()

            saveToPrefs(defaults)

            defaults
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

    fun getInstalledAddons():
            List<InstalledAddon> {

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

        val normalized =
            normalizeGlobalCatalogOrder(
                addons
            )

        saveToPrefs(normalized)

        _installedAddons.value =
            normalized
    }

    fun removeAddon(
        id: String
    ) {

        saveInstalledAddons(
            getInstalledAddons()
                .filterNot {
                    it.id == id
                }
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
            getInstalledAddons()
                .map { addon ->

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
     *
     * This remains addon ordering.
     * Catalog ordering is handled separately.
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

        if (index < 0) {
            return
        }

        val target =
            index + direction

        if (target !in current.indices) {
            return
        }

        val item =
            current.removeAt(index)

        current.add(
            target,
            item
        )

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
            getInstalledAddons()
                .map { addon ->

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
                                    showOnHome =
                                        showOnHome
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
     * Move a catalog globally.
     *
     * Catalogs from ALL addons participate
     * in the same ordering.
     */
    fun moveCatalog(
        addonId: String,
        catalogType: String,
        catalogId: String,
        direction: Int
    ) {

        val configurations =
            getCatalogConfigurations()
                .toMutableList()

        val index =
            configurations.indexOfFirst {
                it.addonId == addonId &&
                    it.catalog.type == catalogType &&
                    it.catalog.id == catalogId
            }

        if (index < 0) {
            return
        }

        val target =
            index + direction

        if (target !in configurations.indices) {
            return
        }

        val item =
            configurations.removeAt(index)

        configurations.add(
            target,
            item
        )

        saveGlobalCatalogOrder(
            configurations
        )

        refreshAddons()
    }

    /**
     * Move a catalog directly to a global position.
     */
    fun moveCatalogToPosition(
        addonId: String,
        catalogType: String,
        catalogId: String,
        targetIndex: Int
    ) {

        val configurations =
            getCatalogConfigurations()
                .toMutableList()

        val currentIndex =
            configurations.indexOfFirst {
                it.addonId == addonId &&
                    it.catalog.type == catalogType &&
                    it.catalog.id == catalogId
            }

        if (currentIndex < 0) {
            return
        }

        if (
            targetIndex !in
            configurations.indices
        ) {
            return
        }

        if (
            currentIndex == targetIndex
        ) {
            return
        }

        val item =
            configurations.removeAt(
                currentIndex
            )

        configurations.add(
            targetIndex,
            item
        )

        saveGlobalCatalogOrder(
            configurations
        )
          refreshAddons()
    }

    /**
     * Set (or clear, with null) the user-facing display name of one
     * catalog. Global order, visibility and every other setting are
     * untouched.
     */
    fun setCatalogCustomName(
        addonId: String,
        catalogType: String,
        catalogId: String,
        name: String?
    ) {

        val updated =
            getInstalledAddons()
                .map { addon ->

                    if (
                        addon.id != addonId
                    ) {
                        return@map addon
                    }

                    addon.copy(
                        catalogs =
                            addon.catalogs.map { catalog ->

                                if (
                                    catalog.type == catalogType &&
                                    catalog.id == catalogId
                                ) {
                                    catalog.copy(
                                        customName =
                                            name?.trim()
                                                ?.takeIf {
                                                    it.isNotBlank()
                                                }
                                    )
                                } else {
                                    catalog
                                }
                            }
                    )
                }

        saveInstalledAddons(
            updated
        )

        refreshAddons()
    }

    /**
     * Replace an addon manifest while preserving
     * KBStream-specific catalog settings.
     *
     * Existing catalogs keep their GLOBAL order.
     *
     * New catalogs are appended to the end.
     */
    fun updateAddonFromManifest(
        manifestUrl: String,
        manifest: AddonManifest
    ) {

        val current =
            getInstalledAddons()
                .toMutableList()

        val existing =
            current.firstOrNull {
                it.id == manifest.id ||
                    it.manifestUrl == manifestUrl
            }

        val existingCatalogs =
            existing?.catalogs.orEmpty()

        val existingByKey =
            existingCatalogs.associateBy {
                catalogKey(
                    manifest.id,
                    it.type,
                    it.id
                )
            }

        /*
         * Existing catalogs retain their current
         * global order.
         */
        val existingGlobalOrder =
            getCatalogConfigurations()
                .mapIndexed { index, configuration ->
                    catalogKey(
                        configuration.addonId,
                        configuration.catalog.type,
                        configuration.catalog.id
                    ) to index
                }
                .toMap()

        val nextOrderStart =
            getCatalogConfigurations().size

        var newCatalogOffset = 0

        val mergedCatalogs =
            manifest.catalogs.map { manifestCatalog ->

                val key =
                    catalogKey(
                        manifest.id,
                        manifestCatalog.type,
                        manifestCatalog.id
                    )

                val previous =
                    existingByKey[key]

                val existingOrder =
                    existingGlobalOrder[key]

                manifestCatalog.copy(
                    showOnHome =
                        previous?.showOnHome
                            ?: true,

                    order =
                        existingOrder
                            ?: (
                                nextOrderStart +
                                    newCatalogOffset++
                                )
                )
            }

        val updatedAddon =
            InstalledAddon(
                manifestUrl =
                    manifestUrl,

                id =
                    manifest.id,

                name =
                    manifest.name,

                resources =
                    manifest.resources,

                catalogs =
                    mergedCatalogs,

                customName =
                    existing?.customName,

                version =
                    manifest.version,

                description =
                    manifest.description,

                types =
                    manifest.types,

                idPrefixes =
                    manifest.idPrefixes,

                logo =
                    manifest.logo
                        ?: manifest.icon
            )

        val index =
            current.indexOfFirst {
                it.id == updatedAddon.id ||
                    it.manifestUrl == manifestUrl
            }

        if (index >= 0) {

            current[index] =
                updatedAddon

        } else {

            current.add(
                updatedAddon
            )
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
            getInstalledAddons()
                .map { addon ->

                    if (addon.id != addonId) {
                        return@map addon
                    }

                    addon.copy(
                        catalogs =
                            addon.catalogs
                                .filterNot {
                                    it.type == catalogType &&
                                        it.id == catalogId
                                }
                    )
                }

        saveInstalledAddons(updated)
    }

    /**
     * Returns every configured catalog in
     * ONE GLOBAL saved order.
     */
    fun getCatalogConfigurations():
            List<CatalogConfiguration> {

        return getInstalledAddons()
            .flatMap { addon ->

                addon.catalogs.map { catalog ->                    CatalogConfiguration(
                        addonId =
                            addon.id,
                        addonName =
                            addon.displayName,
                        addonManifestUrl =
                            addon.manifestUrl,
                        catalog =
                            catalog
                    )
                }
            }
            .sortedBy {
                it.catalog.order
            }
    }

    /**
     * Returns only catalogs that should appear
     * on Home, while preserving GLOBAL order.
     */
    fun getHomeCatalogConfigurations():
            List<CatalogConfiguration> {

        return getCatalogConfigurations()
            .filter {
                it.catalog.showOnHome
            }
    }

    fun refreshAddons() {
    _installedAddons.value = loadFromPreferencesOrDefaults()
    _catalogOrderVersion.value += 1
}

    /**
     * Rebuild one global sequence:
     *
     * 0
     * 1
     * 2
     * 3
     * ...
     *
     * across every addon.
     */
    private fun normalizeGlobalCatalogOrder(
        addons: List<InstalledAddon>
    ): List<InstalledAddon> {

        // Sort GLOBALLY by order (stable sort keeps addon-grouped relative
        // order for ties/legacy states). Sorting per-addon here would clamp
        // globally-moved catalogs back to their own addon's group — which is
        // exactly why "move to bottom" previously stopped at the addon
        // boundary instead of the end of the list.
        val orderedCatalogs =
            addons
                .flatMapIndexed { addonIndex, addon ->

                    addon.catalogs
                        .map { catalog ->

                            Triple(
                                addonIndex,
                                addon.id,
                                catalog
                            )
                        }
                }
                .sortedBy {
                    it.third.order
                }

        return addons.map { addon ->
            addon.copy(
                catalogs =
                    orderedCatalogs
                        .withIndex()
                        .filter {
                            it.value.second == addon.id
                        }
                        .map { numbered ->

                            numbered.value.third.copy(
                                order = numbered.index
                            )
                        }
            )
        }
    }

    /**
     * Writes a CatalogConfiguration list back
     * into the nested addon structure.
     */

    private fun saveGlobalCatalogOrder(
    configurations: List<CatalogConfiguration>
) {

    val orderByKey =
        configurations
            .mapIndexed { index, configuration ->

                catalogKey(
                    configuration.addonId,
                    configuration.catalog.type,
                    configuration.catalog.id
                ) to index

            }
            .toMap()

    val updated =
        getInstalledAddons()
            .map { addon ->

                addon.copy(
                    catalogs =
                        addon.catalogs.map { catalog ->

                            val key =
                                catalogKey(
                                    addon.id,
                                    catalog.type,
                                    catalog.id
                                )

                            catalog.copy(
                                order =
                                    orderByKey[key]
                                        ?: catalog.order
                            )
                        }
                )
            }

    saveInstalledAddons(updated)
}

    private fun catalogKey(
        addonId: String,
        type: String,
        id: String
    ): String {

        return "${addonId}::${type.lowercase()}::$id"
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
            )
        )
    }

    companion object {

        private const val KEY =
            "installed_addons_json"

        @Volatile
        private var INSTANCE: AddonManager? = null

        fun getInstance(
            context: Context
        ): AddonManager {

            return INSTANCE
                ?: synchronized(this) {

                    INSTANCE
                        ?: AddonManager(
                            context.applicationContext
                        ).also {
                            INSTANCE = it
                        }
                }
        }
    }
}

/**
 * A catalog plus the addon it belongs to.
 */
data class CatalogConfiguration(
    val addonId: String,
    val addonName: String,
    val addonManifestUrl: String,
    val catalog: ManifestCatalog
)
