package com.kennyb1201.kbstream.data.kb

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * User-managed home rail ordering that spans BOTH addon catalog rails and
 * KB collection rails, so a collection can sit between two addon
 * catalogs (collection, addon catalog, collection, ...).
 *
 * Stored as one JSON blob:
 *  - [order]: every managed rail key in display order (addons and
 *    collections mixed). Keys missing from it keep their default position
 *    (addon global order, then collection import order).
 *  - [pinned]: rail keys pinned to the top (right after Continue Watching),
 *    in pin order.
 *  - [hidden]: rail keys removed from Home.
 */
@JsonClass(generateAdapter = true)
data class KBHomeOrder(
    val order: List<String> = emptyList(),
    val pinned: List<String> = emptyList(),
    val hidden: List<String> = emptyList()
) {
    val hiddenSet: Set<String> get() = hidden.toSet()
}

/**
 * Pinned is COLLECTIONS-ONLY, so "is this key a collection" is decided in one
 * place. Catalog rails ("addon:...") get their position from [KBHomeOrder.order]
 * alone; the highest one can sit is the head of that block, below any pinned
 * collections.
 */
internal const val KB_COLLECTION_KEY_PREFIX = "kb:"

/**
 * Top/bottom moves on the merged arrangement.
 *
 * TOP pins a COLLECTION to the head of the pinned block (the absolute first
 * rail), but only moves a CATALOG to the head of the order block — a catalog
 * parked in the pinned list could never be unpinned, because the manager has
 * no pin control on a catalog row, so it stayed above every pinned collection
 * forever. Any catalog found in the pinned list here is lifted out on the way.
 *
 * BOTTOM unpins either kind and appends it after everything else.
 */
internal fun moveRailToEnd(
    value: KBHomeOrder,
    key: String,
    toTop: Boolean
): KBHomeOrder {
    // Start from a repaired arrangement so the result can never carry a stray
    // catalog in pinned (see [normalizeHomeOrder]) — whatever the caller read.
    val prefs = normalizeHomeOrder(value)
    val hidden = prefs.hidden - key
    return when {
        !toTop ->
            prefs.copy(
                pinned = prefs.pinned - key,
                order = (prefs.order - key) + key,
                hidden = hidden
            )

        KBHomeOrderPrefs.isCollectionKey(key) ->
            prefs.copy(
                pinned = listOf(key) + prefs.pinned.filter { it != key },
                order = prefs.order - key,
                hidden = hidden
            )

        else ->
            prefs.copy(
                pinned = prefs.pinned - key,
                order = listOf(key) + prefs.order.filter { it != key },
                hidden = hidden
            )
    }
}

/**
 * Pin or unpin one COLLECTION. Non-collection keys are returned unchanged
 * (a catalog has no pin control, and one sitting in `pinned` could never be
 * taken back out — see [moveRailToEnd]).
 *
 * Pinning lifts the key to the head of the pinned block and takes it out of the
 * order block. Unpinning puts it back at the HEAD of the order block, which is
 * directly under the rails still pinned — where the user was looking.
 *
 * That unpin half is the part worth spelling out, because getting it wrong is
 * silent: a key in NEITHER list is what "never arranged" means, and a
 * never-arranged collection is hidden by default. So an unpin that merely
 * removed the key from [KBHomeOrder.pinned] did not un-pin the rail, it deleted
 * it from Home and dropped its manager row into the HIDDEN section.
 */
internal fun toggleCollectionPin(value: KBHomeOrder, key: String): KBHomeOrder {
    if (!KBHomeOrderPrefs.isCollectionKey(key)) return value
    val prefs = normalizeHomeOrder(value)
    return if (key in prefs.pinned) {
        prefs.copy(
            pinned = prefs.pinned - key,
            order = listOf(key) + prefs.order.filter { it != key }
        )
    } else {
        prefs.copy(
            pinned = prefs.pinned + key,
            order = prefs.order - key
        )
    }
}

/**
 * Read-time repair for arrangements written before pinned was
 * collections-only: catalog keys sitting in [KBHomeOrder.pinned] are moved to
 * the head of the order block, in the sequence they were pinned in.
 *
 * This is what makes an already-stuck catalog movable again without the user
 * having to reset their whole arrangement — and the catalog keeps the high
 * position it was given, just below pinned collections instead of above them.
 * Duplicates within either list are dropped (first occurrence wins).
 */
internal fun normalizeHomeOrder(value: KBHomeOrder): KBHomeOrder {
    val pinned = value.pinned
        .filter { KBHomeOrderPrefs.isCollectionKey(it) }
        .distinct()
    val strays = value.pinned
        .filterNot { KBHomeOrderPrefs.isCollectionKey(it) }
        .distinct()
    val order = value.order
        .filterNot { it in pinned }
        .distinct()
    val healedOrder = strays + order.filterNot { it in strays }

    if (pinned == value.pinned && healedOrder == value.order) return value
    return value.copy(pinned = pinned, order = healedOrder)
}

/**
 * Rewrites addon arrangement keys after a manifest refresh replaced a catalog
 * (see `pairReplacedCatalogs` in data/addon).
 *
 * Dynamic addons (BingeCat) swap catalog ids as their content changes, and the
 * arrangement key embeds the id — so without this the position the user gave a
 * rail was orphaned the moment its id changed and the rail dropped to the
 * default tail. Pure so the rename rules are unit tested.
 */
internal fun remapAddonOrderKeys(
    value: KBHomeOrder,
    remap: Map<String, String>
): KBHomeOrder {
    if (remap.isEmpty()) return value
    fun swap(key: String): String = remap[key] ?: key
    // distinct(): if both the old and new key were somehow present, the
    // rewrite would otherwise leave the same rail twice in one list.
    val rewritten = value.copy(
        order = value.order.map(::swap).distinct(),
        pinned = value.pinned.map(::swap).distinct(),
        hidden = value.hidden.map(::swap).distinct()
    )
    return normalizeHomeOrder(rewritten)
}

object KBHomeOrderPrefs {

    private const val PREFS_NAME = "kbstream_kb_home_order"
    private const val KEY_BLOB = "home_order_json"

    /**
     * Last arrangement this device considers already synced (mirrors the key
     * [PrefsPayloadApplier.applyHomeOrder] reads). Written on every local save
     * so our own edit cannot be judged "older than remote" and reverted.
     */
    private const val KEY_SYNCED_AT = "home_order_synced_at"

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(KBHomeOrder::class.java)

    /** Stable key for one KB collection rail on Home. */
    fun collectionKey(collectionId: String?, title: String?): String =
        KB_COLLECTION_KEY_PREFIX +
            (collectionId?.takeIf { it.isNotBlank() } ?: title.orEmpty())

    /** Title-only alias key, used to detect id changes on re-uploaded profiles. */
    fun collectionTitleAliasKey(title: String?): String =
        KB_COLLECTION_KEY_PREFIX + title.orEmpty()

    /** True for a collection arrangement key (catalog keys are "addon:..."). */
    fun isCollectionKey(key: String): Boolean =
        key.startsWith(KB_COLLECTION_KEY_PREFIX)

    /**
     * Resolves the arrangement key for a collection, honoring history: when
     * the collection's id-key has never been arranged but a previous upload
     * of the SAME TITLE was (its title-alias key exists in the saved sets —
     * e.g. BingeCat profiles re-exported with a new id), the new id-key is
     * migrated onto the old slot so the collection keeps its Home position,
     * pin, and visibility instead of dropping to the default-hidden tail.
     * One-time: writes the migrated key back into prefs.
     */
    fun resolveArrangementKey(context: Context, collectionId: String?, title: String?): String {
        val idKey = collectionKey(collectionId, title)
        val aliasKey = collectionTitleAliasKey(title)
        if (aliasKey == idKey) return idKey

        val prefs = get(context)
        val arranged = prefs.pinned.toSet() + prefs.order.toSet() + prefs.hiddenSet
        if (idKey in arranged || aliasKey !in arranged) return idKey

        // Migrate: replace the alias key with the id-key wherever it appears.
        val migrated = prefs.copy(
            pinned = prefs.pinned.map { if (it == aliasKey) idKey else it },
            order = prefs.order.map { if (it == aliasKey) idKey else it },
            hidden = prefs.hidden.map { if (it == aliasKey) idKey else it }
        )
        save(context, migrated)
        return idKey
    }

    /** Stable key for one addon catalog rail on Home (must match HomeScreen's rail fields). */
    fun addonKey(baseUrl: String?, type: String, catalogId: String?): String =
        "addon:${baseUrl.orEmpty()}:$type:${catalogId.orEmpty()}"

    /**
     * The exact addon rail key Home renders, derived from the manifest URL
     * the same way HomeViewModel derives Rail.baseUrl (manifest URL minus
     * the "/manifest.json" suffix and any trailing slash). Every manager
     * write path must use this so arrangement keys match the rails Home
     * actually builds — keying by addon id or by the raw manifest URL
     * silently writes keys Home can never match.
     */
    fun addonKeyFromManifest(manifestUrl: String?, type: String, catalogId: String?): String =
        addonKey(
            manifestUrl.orEmpty()
                .removeSuffix("/manifest.json")
                .removeSuffix("/"),
            type,
            catalogId
        )

    /** Context-free read: uses the most recent get(context) caller's app context. */
    @Volatile
    private var lastContext: Context? = null

    fun readOrder(): KBHomeOrder =
        lastContext?.let { get(it) } ?: KBHomeOrder()

    fun get(context: Context): KBHomeOrder {
        lastContext = context.applicationContext
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return KBHomeOrder()
        val parsed = runCatching { adapter.fromJson(raw) }.getOrNull()
            ?: KBHomeOrder()
        // Repairs the catalogs an older build pinned, on every read path
        // (manager dialog, Home's merged order, the cloud blob): they come
        // back out of pinned and take the top of the order block instead.
        return normalizeHomeOrder(parsed)
    }

    fun save(context: Context, value: KBHomeOrder) {
        // Write the local blob FIRST. buildHomeOrder() below reads this same
        // pref, so enqueuing before the write pushed the PREVIOUS arrangement
        // to the cloud — and because it stamped it "now", the next pull
        // judged that stale copy newer than what the user had just set and
        // reverted the edit (every reorder looked like it did not stick).
        //
        // KEY_SYNCED_AT is stamped too, so the pull's "is the remote older
        // than my last sync?" guard treats this edit as already synced and an
        // older sibling-device copy can never clobber it.
        prefs(context).edit()
            .putString(KEY_BLOB, adapter.toJson(value) ?: "{}")
            .putLong(KEY_SYNCED_AT, System.currentTimeMillis())
            .apply()

        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_HOME_ORDER,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildHomeOrder(appContext)
            )
        }
    }

    /**
     * Rewrites arrangement keys after a manifest refresh replaced catalogs (see
     * [remapAddonOrderKeys]). Called from the addon merge, the one place that
     * sees both the old and the new catalog ids.
     */
    fun remapAddonKeys(context: Context, remap: Map<String, String>) {
        if (remap.isEmpty()) return
        val current = get(context)
        val updated = remapAddonOrderKeys(current, remap)
        if (updated != current) save(context, updated)
    }

    /** Drop every manual arrangement (used by "Reset order" in Settings). */
    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_BLOB).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, PREFS_NAME),
            Context.MODE_PRIVATE
        )
}
