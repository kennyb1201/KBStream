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

object KBHomeOrderPrefs {

    private const val PREFS_NAME = "kbstream_kb_home_order"
    private const val KEY_BLOB = "home_order_json"

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(KBHomeOrder::class.java)

    /** Stable key for one KB collection rail on Home. */
    fun collectionKey(collectionId: String?, title: String?): String =
        "kb:" + (collectionId?.takeIf { it.isNotBlank() } ?: title.orEmpty())

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
        return runCatching { adapter.fromJson(raw) }.getOrNull() ?: KBHomeOrder()
    }

    fun save(context: Context, value: KBHomeOrder) {
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_HOME_ORDER,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildHomeOrder(appContext)
            )
        }

        prefs(context).edit()
            .putString(KEY_BLOB, adapter.toJson(value) ?: "{}")
            .apply()
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
