package com.kennyb1201.kbstream.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local profile management on top of ONE Supabase account.
 *
 * Model: every device signed into the account sees the same profile list
 * (synced via the "profiles" prefs blob). All LOCAL user data — watch
 * history DB, watched overrides, addons, prefs, IPTV, Simkl token — is
 * namespaced under the active profile id, so profiles on the same device
 * are fully isolated from each other.
 *
 * Cloud isolation: sync rows carry profile_id; RLS policies restrict access
 * to rows whose account owns the profile, so profile A's rows can never be
 * read or written by profile B (see the Supabase SQL for `sync_profiles`).
 */
object ProfileManager {

    @Serializable
    data class Profile(
        val id: String,
        val name: String,
        val avatarIndex: Int = 0,      // 0..7 generic avatar
        val customAvatarUrl: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    private const val PREFS = "kbstream_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE = "active_profile_id"
    private const val KEY_MIGRATED = "default_profile_migrated"

    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    /** True once at least one profile exists — gates the first-launch picker. */
    fun hasProfiles(context: Context): Boolean = loadProfiles(context).isNotEmpty()

    fun init(context: Context) {
        val list = loadProfiles(context)
        _profiles.value = list

        val activeId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)
        _activeProfile.value = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()

        migrateLegacyDataIfNeeded(context)
    }

    fun setActive(context: Context, profile: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, profile.id).apply()
        _activeProfile.value = profile
    }

    fun create(context: Context, name: String, avatarIndex: Int): Profile {
        val profile = Profile(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Profile" },
            avatarIndex = avatarIndex.coerceIn(0, AVATAR_COUNT - 1)
        )
        val updated = loadProfiles(context) + profile
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        setActive(context, profile)
        return profile
    }

    fun rename(context: Context, profileId: String, newName: String, avatarIndex: Int? = null, customAvatarUrl: String? = null) {
        val updated = loadProfiles(context).map { p ->
            when {
                p.id != profileId -> p
                else -> p.copy(
                    name = newName.trim().ifBlank { p.name },
                    avatarIndex = avatarIndex ?: p.avatarIndex,
                    customAvatarUrl = customAvatarUrl ?: p.customAvatarUrl
                )
            }
        }
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull { it.id == profileId }
        }
    }

    fun delete(context: Context, profileId: String) {
        val updated = loadProfiles(context).filterNot { it.id == profileId }
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)

        // Wipe the profile's local namespace.
        ProfileStorage.namespaces(context).filter { it.startsWith("$profileId.") }.forEach { ns ->
            context.getSharedPreferences(ns, Context.MODE_PRIVATE).edit().clear().apply()
        }
        context.deleteDatabase(ProfileStorage.dbName(profileId, "kbstream_watch_history"))
        context.deleteDatabase(ProfileStorage.dbName(profileId, "iptv_epg.db"))

        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull()
            updated.firstOrNull()?.let { setActive(context, it) }
        }
    }

    // ── Persistence + sync blob ─────────────────────────────────────

    private fun loadProfiles(context: Context): List<Profile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun saveProfiles(context: Context, profiles: List<Profile>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), profiles))
            .apply()
    }

    fun applySyncedBlob(context: Context, blobJson: String) {
        val synced = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), blobJson)
        }.getOrDefault(emptyList())
        if (synced.isEmpty()) return

        val local = loadProfiles(context)
        // Merge by id, newest createdAt wins; keep local-only profiles too.
        val byId = (local + synced).associateBy { it.id }
        val merged = byId.values.sortedBy { it.createdAt }
        saveProfiles(context, merged)
        _profiles.value = merged
        if (_activeProfile.value == null) {
            _activeProfile.value = merged.firstOrNull()
        }
    }

    fun profilesSyncBlob(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            putJsonArray("profiles") {
                loadProfiles(context).forEach { p ->
                    add(
                        buildJsonObject {
                            put("id", p.id)
                            put("name", p.name)
                            put("avatarIndex", p.avatarIndex)
                            p.customAvatarUrl?.let { put("customAvatarUrl", it) }
                            put("createdAt", p.createdAt)
                        }
                    )
                }
            }
        }

    fun syncedBlobJson(context: Context): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Profile.serializer()),
            loadProfiles(context)
        )

    private fun pushProfilesBlob(context: Context, profiles: List<Profile>) {
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            SupabaseSync.enqueuePrefs(
                appContext,
                PrefsPayloadBuilder.KEY_PROFILES,
                profilesSyncBlob(appContext)
            )
        }
    }

    /**
     * One-time migration: an existing install has data in the un-namespaced
     * stores. Copy it into the first created profile so nothing is lost.
     */
    private fun migrateLegacyDataIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        if (loadProfiles(context).isNotEmpty()) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }
        // Marked when the first profile is created (see createAndMigrate).
    }

    /**
     * First-profile creation path for existing installs: seeds the new
     * profile's namespace from the legacy (un-namespaced) stores.
     */
    fun createAndMigrateLegacy(context: Context, name: String, avatarIndex: Int): Profile {
        val hadLegacy = hadLegacyData(context)
        val profile = create(context, name, avatarIndex)

        if (hadLegacy) {
            ProfileStorage.copyLegacyIntoProfile(context, profile.id)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MIGRATED, true).apply()
        }
        return profile
    }

    private fun hadLegacyData(context: Context): Boolean {
        val legacyKeys = listOf(
            "kbstream_player_prefs", "kbstream_addons", "kbstream_watched_overrides",
            "kbstream_nuvio_home_order", "kbstream_nuvio_collections",
            "kbstream_stream_badges", "iptv_prefs", "simkl_auth"
        )
        return legacyKeys.any {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty()
        }
    }

    const val AVATAR_COUNT = 8

    // Avatar hue pairs (background, accent) for the 8 generic avatars.
    val AVATAR_COLORS: List<Pair<Long, Long>> = listOf(
        0xFFE8A33D to 0xFF1A1208L, // brass
        0xFF4C9BE8 to 0xFF081426L, // blue
        0xFF58C470 to 0xFF0A1F10L, // green
        0xFFE85D5D to 0xFF260A0AL, // red
        0xFFB06AE8 to 0xFF180A26L, // violet
        0xFFE8B84C to 0xFF261A08L, // amber
        0xFF4CC8C4 to 0xFF082020L, // teal
        0xFFE86AA6 to 0xFF260A18L  // pink
    )

    // Used by SupabaseSync apply path.
    fun applyProfilesPayload(context: Context, payload: kotlinx.serialization.json.JsonObject) {
        val arr = payload["profiles"] as? JsonArray ?: return
        val list = arr.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.content
            fun lng(k: String) = str(k)?.toLongOrNull()
            val id = str("id") ?: return@mapNotNull null
            Profile(
                id = id,
                name = str("name") ?: "Profile",
                avatarIndex = (lng("avatarIndex") ?: 0L).toInt(),
                customAvatarUrl = str("customAvatarUrl"),
                createdAt = lng("createdAt") ?: 0L
            )
        }
        if (list.isEmpty()) return

        val local = loadProfiles(context)
        val byId = (local + list).associateBy { it.id }
        val merged = byId.values.sortedBy { it.createdAt }
        saveProfiles(context, merged)
        _profiles.value = merged
        if (_activeProfile.value == null) {
            _activeProfile.value = merged.firstOrNull()
        }
    }
}

/**
 * Storage namespacing: every profile-scoped store lives under
 * "<profileId>.<base-name>", keeping profiles fully isolated on-device.
 * Accessors resolve the ACTIVE profile at call time.
 */
object ProfileStorage {

    /** Returns the SharedPreferences name for a profile-scoped store. */
    fun prefsName(context: Context, baseName: String): String {
        val active = ProfileManager.activeProfile.value?.id
            ?: return baseName // no profiles yet → legacy/global store
        return "$active.$baseName"
    }

    /** Returns the database file name for a profile-scoped Room DB. */
    fun dbName(profileId: String, baseName: String): String = "$profileId.$baseName"

    fun dbNameForActive(context: Context, baseName: String): String {
        val active = ProfileManager.activeProfile.value?.id ?: return baseName
        return dbName(active, baseName)
    }

    /** All namespaced prefs files on this device (for deletion). */
    fun namespaces(context: Context): List<String> {
        val root = java.io.File(context.filesDir.parentFile, "shared_prefs")
        return root.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    /** Copies legacy un-namespaced stores into a profile's namespace. */
    fun copyLegacyIntoProfile(context: Context, profileId: String) {
        val legacyPrefs = listOf(
            "kbstream_player_prefs", "kbstream_addons", "kbstream_watched_overrides",
            "kbstream_nuvio_home_order", "kbstream_nuvio_collections",
            "kbstream_stream_badges", "iptv_prefs", "simkl_auth",
            "iptv_guide_preferences", "simkl_sync"
        )
        legacyPrefs.forEach { base ->
            val src = context.getSharedPreferences(base, Context.MODE_PRIVATE)
            if (src.all.isEmpty()) return@forEach
            val dst = context.getSharedPreferences("$profileId.$base", Context.MODE_PRIVATE).edit()
            src.all.forEach { (k, v) ->
                when (v) {
                    is String -> dst.putString(k, v)
                    is Boolean -> dst.putBoolean(k, v)
                    is Int -> dst.putInt(k, v)
                    is Long -> dst.putLong(k, v)
                    is Float -> dst.putFloat(k, v)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") dst.putStringSet(k, v as Set<String>)
                }
            }
            dst.apply()
        }

        // Room DBs: plain file copy (both are SQLite files).
        val dbDir = context.getDatabasePath("x").parentFile
        listOf("kbstream_watch_history" to "kbstream_watch_history", "iptv_epg.db" to "iptv_epg.db")
            .forEach { (legacyName, targetBase) ->
                val src = java.io.File(dbDir, legacyName)
                if (src.exists()) {
                    src.copyTo(java.io.File(dbDir, "$profileId.$targetBase"), overwrite = true)
                    java.io.File(dbDir, "$legacyName-wal").takeIf { it.exists() }?.copyTo(
                        java.io.File(dbDir, "$profileId.$targetBase-wal"), overwrite = true
                    )
                    java.io.File(dbDir, "$legacyName-shm").takeIf { it.exists() }?.copyTo(
                        java.io.File(dbDir, "$profileId.$targetBase-shm"), overwrite = true
                    )
                }
            }
    }
}
