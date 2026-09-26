package com.kennyb1201.kbstream.data.addon

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AddonManager(
    private val context: Context
) {

    /**
     * The addon store for a SPECIFIC profile; `null` means the legacy
     * un-namespaced store, which only applies while no profile exists at all.
     *
     * Reads and writes name the profile explicitly rather than resolving the
     * ambient active profile at write time. The active profile can change
     * between a mutation's read and its write (a switch landing during a
     * manifest fetch), and resolving it late is how one profile's addon list
     * got persisted into a sibling profile's store — the addons that showed up
     * on another profile, or vanished from this one when they were removed
     * there.
     */
    private fun addonPrefs(profileId: String?): android.content.SharedPreferences {
        val appContext = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
            ?: context.applicationContext
        val name = when (profileId) {
            null -> "kbstream_addons"
            else -> com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                profileId,
                "kbstream_addons"
            )
        }
        return appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /**
     * Profile the in-memory list was loaded from. Every mutation is pinned to
     * it, so a write can never land in a different profile than the list it
     * was computed from.
     */
    private var loadedProfileId: String? = null

    /** Depth of an in-progress read-modify-write; 0 at the outermost entry. */
    private var mutationDepth = 0

    /**
     * The profile the app is currently showing. Falls back to the profile
     * persisted in the profiles store, because Application.onCreate builds
     * this singleton before ProfileManager.init binds the active profile —
     * without it, the launch-time load and the launch-time manifest refresh
     * both resolved the legacy un-namespaced store instead of the profile the
     * user was actually in.
     */
    private fun activeStoreProfileId(): String? {
        val appContext = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
            ?: context.applicationContext
        return com.kennyb1201.kbstream.data.sync.ProfileStorage.activeProfileId(appContext)
    }

    /** The active profile's addon store (scoped launch-time bookkeeping). */
    private val prefs
        get() = addonPrefs(activeStoreProfileId())

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

    // Manifest refreshes / applies run here (including the launch-time
    // refresh kicked from Application.onCreate). The handler keeps an
    // escaping Throwable (Error subclasses slip past the runCatching blocks
    // around individual fetches) from killing the whole process.
    private val addonScope =
        CoroutineScope(
            Dispatchers.Default + SupervisorJob() +
                CoroutineExceptionHandler { _, t ->
                    Log.e("ADDON_SCOPE", "addon task failed hard", t)
                    com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                        t, mapOf("source" to "addon_manager_scope")
                    )
                }
        )

    val streamAddons: StateFlow<List<InstalledAddon>> =
        _installedAddons
            .map { list ->
                list.filter {
                    it.enabled && "stream" in it.resources
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
                    it.enabled && "catalog" in it.resources
                }
            }
            .stateIn(
                scope = addonScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    init {
        loadedProfileId = activeStoreProfileId()
        _installedAddons.value =
            loadFromPreferencesOrDefaults(loadedProfileId)
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

    /**
     * Guard for every read-modify-write of the addon list (UI mutators, the
     * background manifest apply, the cloud apply). The profile sync check runs
     * only at the outermost entry, so a profile switch landing mid-mutation
     * cannot swap the base list — or the store it will be written to — out
     * from under the transform.
     */
    private inline fun mutate(block: () -> Unit) {
        synchronized(stateLock) {
            ensureProfileSyncedLocked()
            mutationDepth++
            try {
                block()
            } finally {
                mutationDepth--
            }
        }
    }

    /**
     * Reloads whenever the active profile changed while this singleton was
     * alive, so no consumer keeps serving — or persisting — the profile it
     * just left. Caller holds [stateLock].
     */
    private fun ensureProfileSyncedLocked() {
        if (mutationDepth > 0) return
        val activeId = activeStoreProfileId()
        if (activeId == loadedProfileId) return
        loadForLocked(activeId)
    }

    /** Loads [profileId]'s addon list into the singleton. Caller holds [stateLock]. */
    private fun loadForLocked(profileId: String?) {
        loadedProfileId = profileId
        _installedAddons.value =
            loadFromPreferencesOrDefaults(profileId)
        _catalogOrderVersion.value += 1
    }

    private fun loadFromPreferencesOrDefaults(
        profileId: String?
    ): List<InstalledAddon> {

        val store = addonPrefs(profileId)

        val json =
            store.getString(
                KEY,
                null
            )

        if (json.isNullOrBlank()) {

            val defaults =
                defaultAddons()

            saveToPrefs(defaults, profileId)

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
                saveToPrefs(normalized, profileId)
            }

            normalized

        } catch (_: Exception) {

            val defaults =
                defaultAddons()

            saveToPrefs(defaults, profileId)

            defaults
        }
    }

    private fun saveToPrefs(
        addons: List<InstalledAddon>,
        profileId: String?
    ) {

        addonPrefs(profileId).edit()
            .putString(
                KEY,
                adapter.toJson(addons)
            )
            .apply()
    }

    fun getInstalledAddons():
            List<InstalledAddon> {
        synchronized(stateLock) {

        ensureProfileSyncedLocked()

        return _installedAddons.value.ifEmpty {

            // Straight reload, no version bump: a profile that genuinely has
            // no addons would otherwise bump the order version on every read.
            _installedAddons.value =
                loadFromPreferencesOrDefaults(loadedProfileId)

            _installedAddons.value
        }
        }
    }

    fun saveInstalledAddons(
        addons: List<InstalledAddon>,
        // True for a user-visible change (rename/reorder/hide/install). The
        // background manifest refresh passes false: it preserves the user's
        // catalog settings, so it must never claim a newer sync timestamp and
        // overwrite a sibling device's configuration.
        userEdit: Boolean = true
    ) {
        synchronized(stateLock) {
        // Pin the profile this list belongs to. The ambient active profile is
        // deliberately NOT consulted here: it may already have moved on, and
        // resolving it late would persist this profile's addons into that one.
        val profileId = loadedProfileId

        val normalized =
            normalizeGlobalCatalogOrder(
                addons
            )

        val json =
            adapter.toJson(normalized)

        val store = addonPrefs(profileId)

        store.edit()
            .putString(KEY, json)
            .apply()

        // Record the user's configuration edit time (not the push time) so a
        // sibling device can tell a real edit from a plain re-publish.
        recordConfigWrite(normalized, profileId, userEdit)

        _installedAddons.value =
            normalized

        // Cross-device sync: push the full addon set (small JSON blob) under
        // the SAME profile it was just written for, stamped with the
        // configuration's edit time.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_ADDONS,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildAddons(
                    json,
                    store.getLong(KEY_CONFIG_EDITED_AT, 0L)
                ),
                profileId
            )
        }
        }
    }

    /**
     * Atomic read-modify-write helper for callers that need to compute the
     * next addon list from the CURRENT one (add/refresh flows that fetch a
     * manifest first, then mutate). The transform runs under the same
     * [stateLock] as every other mutator, so a background manifest apply or
     * cloud-sync apply that lands between the caller's fetch and this call
     * can no longer be clobbered by a stale list written back afterwards.
     *
     * Return null from [transform] to abort without writing (e.g. the addon
     * vanished while the manifest was downloading).
     */
    fun updateInstalled(
        transform: (List<InstalledAddon>) -> List<InstalledAddon>?
    ) {
        mutate {
            val next = transform(getInstalledAddons())
            if (next != null) {
                saveInstalledAddons(next)
            }
        }
    }

    fun removeAddon(
        id: String
    ) {
        mutate {

        saveInstalledAddons(
            getInstalledAddons()
                .filterNot {
                    it.id == id
                }
        )
        }
    }

    /**
     * Soft-disable toggle: a disabled addon stays installed (kept on disk,
     * in backups, and in cloud sync) but drops out of every runtime
     * consumer via [getEnabledAddons]. No-op when already in the wanted
     * state, so double-presses don't rewrite prefs.
     */
    fun setAddonEnabled(id: String, enabled: Boolean) {
        mutate {
            val current = getInstalledAddons()
            val target = current.firstOrNull { it.id == id } ?: return
            if (target.enabled == enabled) return
            saveInstalledAddons(
                current.map {
                    if (it.id == id) it.copy(enabled = enabled) else it
                }
            )
        }
    }

    /**
     * Runtime view of the installed addons: only the enabled ones, in the
     * same order management returns. Consumers that actually fetch data
     * (catalogs, streams, meta, search, subtitles) must use this; screens
     * that manage addons keep using [getInstalledAddons] so disabled addons
     * remain visible and re-toggleable.
     */
    fun getEnabledAddons(): List<InstalledAddon> =
        getInstalledAddons().filter { it.enabled }

    fun renameAddon(
        id: String,
        newName: String?
    ) {
        mutate {

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
        mutate {

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
        mutate {

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
        mutate {

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
        mutate {

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
        mutate {

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
        mutate {

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

        // Identity-free replacement pairing for THIS addon (see
        // pairReplacedCatalogs): dynamic addons swap catalog ids as their
        // content changes, and the id-key is exactly what breaks. Used below
        // so a replacement inherits the removed catalog's local settings
        // (showOnHome / customName) and its Home arrangement slot instead of
        // appearing as a brand-new, default-positioned rail. Sorted by the
        // saved order so the pairing matches the freed-slot order above.
        val replacementPairs =
            pairReplacedCatalogs(
                existingCatalogs.sortedBy { it.order },
                manifest.catalogs
            )
        val replacementByKey =
            replacementPairs.associate { (added, removed) ->
                catalogKey(
                    manifest.id,
                    added.type,
                    added.id
                ) to removed
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

        // Catalogs this addon HAD that the fresh manifest no longer lists.
        // Dynamic addons (BingeCat's because-you-watched rails, curated
        // lists) frequently swap catalog ids as their content changes —
        // conceptually the new catalog REPLACES the removed one, so the
        // replacement inherits the removed slot's order index instead of
        // appending to the bottom of Home. Freed slots are handed out in
        // ascending order so one removed slot absorbs exactly one newcomer.
        val manifestKeys =
            manifest.catalogs.mapTo(mutableSetOf()) {
                catalogKey(
                    manifest.id,
                    it.type,
                    it.id
                )
            }

        val freedOrderSlots =
            existingGlobalOrder.entries
                .filter { (key, _) -> key !in manifestKeys }
                .map { it.value }
                .sorted()

        val fallbackOrderStart =
            (existingGlobalOrder.values.maxOrNull() ?: -1) + 1

        var freedSlotCursor = 0
        var overflowOffset = 0

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

                // A swapped catalog id: fall back to the catalog this one
                // replaced so its local settings carry over.
                val inherited =
                    previous ?: replacementByKey[key]

                val existingOrder =
                    existingGlobalOrder[key]

                manifestCatalog.copy(
                    // User's pinned/unpinned choice wins for existing
                    // catalogs; a brand-new catalog honors the manifest's
                    // KB hints (showInHome/isSearch) so hidden-by-design
                    // rails (director/seed catalogs, search placeholders)
                    // don't flood Home on install. This is only the DEFAULT:
                    // the user can still pin any of them via the manager.
                    showOnHome =
                        inherited?.showOnHome
                            ?: manifestCatalog.defaultShowOnHome,

                    // The display-name override is KBStream-local state, not
                    // part of the manifest — a refresh must carry it forward.
                    // Dropping it here is what made a renamed catalog lose its
                    // name locally on the next launch, and then push the
                    // nameless copy to every other device.
                    customName =
                        inherited?.customName
                            ?: manifestCatalog.customName,

                    order =
                        existingOrder
                            ?: (
                                freedOrderSlots.getOrNull(
                                    freedSlotCursor++
                                )
                                    ?: (
                                        fallbackOrderStart +
                                            overflowOffset++
                                        )
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

            // Apply-only: never INSERT here. This runs from the manifest
            // refresh, which iterates the list captured when the refresh
            // STARTED. An addon missing from the current list therefore means
            // the user removed it while its manifest was downloading (adding
            // would resurrect it) or the profile changed mid-refresh (adding
            // would leak one profile's addons into another's).
            return
        }

        // Carry the user's HOME ARRANGEMENT onto any catalog this refresh
        // REPLACED: the arrangement key embeds the catalog id, so without
        // this a reordered dynamic rail (BingeCat) silently dropped back to
        // the default tail the moment its id changed. Done here because this
        // is the one place that sees both the old and the new ids.
        if (replacementPairs.isNotEmpty()) {
            val remap = replacementPairs.associate { (added, removed) ->
                com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.addonKeyFromManifest(
                    manifestUrl,
                    removed.type,
                    removed.id
                ) to com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.addonKeyFromManifest(
                    manifestUrl,
                    added.type,
                    added.id
                )
            }
            runCatching {
                com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                    .remapAddonKeys(context, remap)
            }
        }

        saveInstalledAddons(current, userEdit = false)
        }
    }

    /**
     * Remove a catalog from the saved configuration.
     */
    fun removeCatalog(
        addonId: String,
        catalogType: String,
        catalogId: String
    ) {
        mutate {

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

    /** Force a reload from the ACTIVE profile's store (called on a switch). */
    fun refreshAddons() {
        synchronized(stateLock) {
            loadForLocked(activeStoreProfileId())
        }
}

    /**
     * Launch-time auto-update entry point. No-ops unless at least
     * [LAUNCH_REFRESH_MIN_INTERVAL_MS] has passed since the last attempt —
     * the app process is frequently recreated (Fire TV aggressively kills
     * backgrounded activities), and re-fetching every manifest on each
     * recreation would be wasteful. The timestamp lives in the (scoped)
     * addon prefs, so it naturally tracks the active profile's addon set.
     */
    fun maybeRefreshOnLaunch(context: Context) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_AUTO_REFRESH_KEY, 0L)
        if (now - last < LAUNCH_REFRESH_MIN_INTERVAL_MS) return

        prefs.edit().putLong(LAST_AUTO_REFRESH_KEY, now).apply()
        refreshInstalledAddons()
    }

    /**
     * Auto-update: re-fetch every installed add-on's manifest and apply it.
     *
     * Addon developers evolve their manifests (new catalogs, fixed stream
     * config, renamed entries). Without this the installed snapshot stays
     * frozen at install time until the user manually re-adds the addon.
     *
     * Behavior:
     *  - Fetches run in PARALLEL on [addonScope]; one failing manifest never
     *    blocks or damages the others (offline-safe: failures are skipped).
     *  - Applies run SEQUENTIALLY through [applyMutex]: each apply is a
     *    read-modify-write of the addon list, so parallel applies would race
     *    and the last writer would clobber the other addons' updates.
     *  - [updateAddonFromManifest] preserves global catalog order and the
     *    user's show/hide + custom-name settings for existing catalogs.
     *  - Unchanged manifests are detected BEFORE saving (version + catalog
     *    set + resource set), so a no-op refresh does not dirty prefs, bump
     *    [catalogOrderVersion], or enqueue a pointless sync upload.
     *
     * Fire-and-forget: call from application startup or the periodic worker.
     */
    // Guards every read-modify-write of the addon list (UI mutators on the
    // main thread AND background writers like auto-update / cloud-sync
    // apply). Without it, a UI mutation that read the list before a
    // background manifest apply finishes would write back a stale copy and
    // silently drop that apply (lost update). JVM monitors are reentrant, so
    // nested locked calls (moveCatalog -> getCatalogConfigurations ->
    // saveInstalledAddons) are safe. Every mutator enters through [mutate],
    // which pins the operation to ONE profile: the sync check runs at the
    // outermost entry only, and the write names that same profile.
    private val stateLock = Any()

    private val applyMutex = Mutex()

    /**
     * Apply a cloud-synced addon set. Serialized through the same
     * [applyMutex] as auto-update applies: both replace/merge the full addon
     * list, and unserialized writers would clobber each other's updates.
     */
    suspend fun applySyncedAddons(addonsJson: String, remoteEditedAt: Long = 0L) {
        applyMutex.withLock {
            // The pull filters cloud rows by profile scope, so this blob
            // belongs to whichever profile is active now. Resolve it here and
            // name it explicitly instead of resolving the ambient profile at
            // prefs-write time — the row was fetched earlier.
            val profileId = activeStoreProfileId()
            val store = addonPrefs(profileId)
            val localEditedAt = store.getLong(KEY_CONFIG_EDITED_AT, 0L)

            // Never let an older remote configuration replace a newer local
            // one: the configured device keeps its order/names/visibility and
            // re-publishes them. A device with no local configuration
            // (localEditedAt == 0) always accepts the cloud copy, which is how
            // a fresh sibling gets set up. See [AddonsConfigRules].
            if (localEditedAt > 0L &&
                !com.kennyb1201.kbstream.data.sync.AddonsConfigRules
                    .remoteConfigWins(remoteEditedAt, localEditedAt)
            ) {
                return@withLock
            }

            store.edit().putString(KEY, addonsJson).apply()

            // Adopt: mirror the fingerprint and demote this device's edit
            // stamp to the remote one, so the just-adopted configuration is
            // not treated as a local edit and echoed straight back. The cloud
            // stamp itself was already folded in by
            // [observeAddonsCloudEditedAt] before this ran.
            val adopted = runCatching { adapter.fromJson(addonsJson) }.getOrNull()
            val adoption =
                store.edit()
                    .putLong(KEY_CONFIG_EDITED_AT, remoteEditedAt)
            if (adopted != null) {
                adoption.putString(
                    KEY_CONFIG_SIG,
                    com.kennyb1201.kbstream.data.sync.AddonsConfigRules
                        .signature(configView(adopted))
                )
            }
            adoption.apply()

            synchronized(stateLock) {
                loadForLocked(profileId)
            }
        }
    }

    fun refreshInstalledAddons() {
        // Pin the profile this pass is for: the fetches below outlive a
        // profile switch, and applying one profile's manifest onto another
        // profile's list is how addons used to appear in profiles that never
        // installed them.
        val profileId = activeStoreProfileId()
        val addons = getInstalledAddons()
        if (addons.isEmpty()) return

        val repository = com.kennyb1201.kbstream.data.addon.AddonRepository.getInstance()

        addons.forEach { addon ->
            addonScope.launch {
                runCatching {
                    if (activeStoreProfileId() != profileId) return@runCatching

                    val manifest = repository.fetchManifest(addon.manifestUrl)

                    // Change detection: skip saving when nothing visible to
                    // the user actually changed. id/name/version plus the
                    // catalog (type,id,name) set and resource set cover the
                    // fields the rest of the app renders.
                    val catalogsChanged =
                        manifest.catalogs.map { "${it.type}:${it.id}:${it.name}" }
                            .toSet() !=
                            addon.catalogs.map { "${it.type}:${it.id}:${it.name}" }
                                .toSet()
                    val resourcesChanged =
                        manifest.resources.toSet() != addon.resources.toSet()
                    val versionChanged =
                        manifest.version != addon.version
                    val nameChanged =
                        manifest.name != addon.name

                    val unchanged = !catalogsChanged && !resourcesChanged &&
                        !versionChanged && !nameChanged

                    if (unchanged) {
                        Log.d(TAG_AUTO_UPDATE, "unchanged: ${addon.id}")
                    } else {
                        applyMutex.withLock {
                            if (activeStoreProfileId() == profileId) {
                                updateAddonFromManifest(addon.manifestUrl, manifest)
                            }
                        }
                        Log.i(
                            TAG_AUTO_UPDATE,
                            "updated: ${addon.id} " +
                                "(catalogs=${manifest.catalogs.size}, " +
                                "version=${manifest.version ?: "?"})"
                        )
                    }
                }.onFailure {
                    Log.w(TAG_AUTO_UPDATE, "refresh failed for ${addon.id}: ${it.message}")
                }
            }
        }
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

    /** Sync-relevant projection of the addon list (see [AddonsConfigRules]). */
    private fun configView(
        addons: List<InstalledAddon>
    ): List<com.kennyb1201.kbstream.data.sync.AddonsConfigRules.Addon> =
        addons.map { addon ->
            com.kennyb1201.kbstream.data.sync.AddonsConfigRules.Addon(
                id = addon.id,
                enabled = addon.enabled,
                // The addon's own rename counts too (see
                // [AddonsConfigRules.Addon.customName]).
                customName = addon.customName,
                catalogs =
                    addon.catalogs.map { catalog ->
                        com.kennyb1201.kbstream.data.sync.AddonsConfigRules.Catalog(
                            type = catalog.type,
                            id = catalog.id,
                            order = catalog.order,
                            customName = catalog.customName,
                            // Only a catalog the user hid AWAY from its
                            // manifest default is a configuration; addons
                            // that ship catalogs hidden by design must not
                            // make a fresh box look configured.
                            userHidden =
                                !catalog.showOnHome && catalog.defaultShowOnHome
                        )
                    }
            )
        }

    /**
     * Records the configuration this device now holds. Only a write the user
     * caused — or the very first observation of an already-configured device,
     * the rollout case — may claim an edit timestamp. A manifest refresh, a
     * reload and an adopted cloud blob all update the fingerprint without
     * claiming an edit, so an untouched device can never out-stamp a
     * configured one.
     */
    private fun recordConfigWrite(
        addons: List<InstalledAddon>,
        profileId: String?,
        userEdit: Boolean
    ) {
        val view = configView(addons)
        val rules = com.kennyb1201.kbstream.data.sync.AddonsConfigRules
        val signature = rules.signature(view)
        val store = addonPrefs(profileId)
        val previous = store.getString(KEY_CONFIG_SIG, null)
        val editor = store.edit().putString(KEY_CONFIG_SIG, signature)
        if (rules.shouldStampEdit(
                previousSignature = previous,
                signature = signature,
                userEdit = userEdit,
                configured = rules.looksConfigured(view)
            )
        ) {
            editor.putLong(KEY_CONFIG_EDITED_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    /**
     * The active profile's catalog-configuration edit stamp, or 0 when this
     * device has no deliberate configuration to share.
     */
    fun addonsConfigEditedAt(): Long =
        addonPrefs(activeStoreProfileId()).getLong(KEY_CONFIG_EDITED_AT, 0L)

    /** Edit stamp of the account copy this device last adopted (0 if never). */
    fun addonsConfigCloudAt(): Long =
        addonPrefs(activeStoreProfileId()).getLong(KEY_CONFIG_CLOUD_AT, 0L)

    /**
     * Learns the account's configuration edit stamp from a pulled row, so the
     * publish gate stops re-sending a configuration the cloud already holds.
     * This is deliberately fed by PULLS (the authoritative cloud copy) rather
     * than advanced at push time: a push that never reached the cloud (an app
     * kill mid-flush) must not silence the next attempt.
     */
    fun observeAddonsCloudEditedAt(remoteEditedAt: Long) {
        val store = addonPrefs(activeStoreProfileId())
        if (remoteEditedAt > store.getLong(KEY_CONFIG_CLOUD_AT, 0L)) {
            store.edit().putLong(KEY_CONFIG_CLOUD_AT, remoteEditedAt).apply()
        }
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

        // Shadow keys holding the sync bookkeeping for the catalog
        // configuration in [KEY]. Never user data, never published:
        //  - KEY_CONFIG_SIG: fingerprint of the last observed configuration,
        //  - KEY_CONFIG_EDITED_AT: when the user last changed it deliberately,
        //  - KEY_CONFIG_CLOUD_AT: edit stamp of the account copy last adopted.
        private const val KEY_CONFIG_SIG = "addons_config_sig"
        private const val KEY_CONFIG_EDITED_AT = "addons_config_edited_at"
        private const val KEY_CONFIG_CLOUD_AT = "addons_config_cloud_at"

        private const val TAG_AUTO_UPDATE =
            "ADDON_AUTO_UPDATE"

        private const val LAST_AUTO_REFRESH_KEY =
            "last_addon_auto_refresh_ms"

        /** Minimum gap between launch-time manifest refresh attempts. */
        private const val LAUNCH_REFRESH_MIN_INTERVAL_MS =
            6L * 60L * 60L * 1000L

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
