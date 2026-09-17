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
import kotlinx.serialization.json.JsonObject
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
        val customAvatarUrl: String? = null, // remote https URL (if ever used)
        val avatarData: String? = null,      // uploaded avatar as data:image/jpeg;base64 — syncs with the blob
        val pinHash: String? = null,         // SHA-256 of the 4-digit PIN; null = no lock
        // Kids Mode ceiling: null = off. Non-null means the profile is a
        // kids profile — discover/search/browse surfaces are kid-filtered.
        // Set through [setKidsMaxAge] so the legal value set is enforced.
        val kidsMaxAge: Int? = null,
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
        onActiveProfileChanged()
    }

    /**
     * Profile-switch isolation: drop every in-memory cache that could hold
     * the previous profile's data — history DB instance, watched-status
     * caches, addon list, Simkl continue-watching cache — then re-pull the
     * new profile's cloud rows. Without this, the first renders after a
     * switch show stale data from the profile you just left (the nuvio leak).
     */
    private fun onActiveProfileChanged() {
        runCatching {
            com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                .closeScopedInstanceForSwitch()
        }
        runCatching {
            com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
                .closeScopedInstanceForSwitch()
        }
        runCatching { com.kennyb1201.kbstream.data.watched.WatchedStatusRepository.invalidateAllCaches() }
        runCatching { com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(appContextForSwitch()).refreshAddons() }
        runCatching { com.kennyb1201.kbstream.data.simkl.SimklRepository.clearTransientCaches() }
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            SupabaseSync.onProfileSwitched(appContext)
            // The TV-launcher Watch Next channel is a GLOBAL OS surface but
            // its rows mirror the ACTIVE profile's continue-watching. Without
            // this republish the launcher keeps showing the profile you just
            // left until the new profile writes history.
            SupabaseSync.launchLauncherRepublish(appContext)
        }
    }

    private fun appContextForSwitch(): android.content.Context {
        val ctx = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
        if (ctx != null) return ctx
        error("ProfileManager not initialized")
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

    // ── Profile PIN (Who's Watching lock) ────────────────────────────
    // Only the SHA-256 hash is stored/synced — the plain PIN never persists.
    private fun hashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Sets (4 digits) or clears (null/blank) a profile's PIN. Returns false on malformed input. */
    fun setPin(context: Context, profileId: String, pin: String?): Boolean {
        val clean = pin?.trim().orEmpty()
        if (clean.isNotEmpty() && (clean.length != 4 || clean.any { !it.isDigit() })) return false
        val updated = loadProfiles(context).map { p ->
            if (p.id != profileId) p else p.copy(pinHash = clean.takeIf { it.isNotEmpty() }?.let { hashPin(it) })
        }
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull { it.id == profileId }
        }
        return true
    }

    /**
     * Sets (or clears, null) a profile's Kids Mode rating ceiling. Only
     * the three levels the profile builder offers are legal: PG-13/PG/G
     * ("or lower"). Returns false on an unsupported value.
     */
    fun setKidsMaxAge(context: Context, profileId: String, kidsMaxAge: Int?): Boolean {
        if (kidsMaxAge != null &&
            kidsMaxAge != KidsMode.MAX_AGE_PG13 &&
            kidsMaxAge != KidsMode.MAX_AGE_PG &&
            kidsMaxAge != KidsMode.MAX_AGE_G
        ) {
            return false
        }
        val updated = loadProfiles(context).map { p ->
            if (p.id != profileId) p else p.copy(kidsMaxAge = kidsMaxAge)
        }
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull { it.id == profileId }
        }
        return true
    }

    fun hasPin(profile: Profile): Boolean = !profile.pinHash.isNullOrBlank()

    fun verifyPin(profile: Profile, pin: String): Boolean =
        profile.pinHash?.let { hashPin(pin) == it } ?: true

    /** Explicitly sets (or clears) a profile's custom avatar URL. Clearing also drops uploaded data. */
    fun setCustomAvatar(context: Context, profileId: String, url: String?) {
        val updated = loadProfiles(context).map { p ->
            when {
                p.id != profileId -> p
                url == null -> p.copy(customAvatarUrl = null, avatarData = null)
                else -> p.copy(customAvatarUrl = url)
            }
        }
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull { it.id == profileId }
        }
    }

    /** Sets the uploaded-avatar payload (data URL) for a profile. */
    fun setAvatarData(context: Context, profileId: String, dataUrl: String?) {
        val updated = loadProfiles(context).map { p ->
            if (p.id != profileId) p else p.copy(avatarData = dataUrl)
        }
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull { it.id == profileId }
        }
    }

    /**
     * Copies a picked image into a pending cache slot and returns a file://
     * URL for preview. The file is NOT yet bound to a profile — call
     * [finalizeAvatar] on save (also handles remote https URLs as-is).
     */
    fun importAvatarImage(context: Context, source: android.net.Uri): String? {
        return runCatching {
            val dir = java.io.File(context.cacheDir, "avatars_pending").apply { mkdirs() }
            val tmp = java.io.File(dir, "pending_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(source)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (tmp.length() == 0L) {
                tmp.delete()
                return null
            }
            android.net.Uri.fromFile(tmp).toString()
        }.getOrNull()
    }

    /**
     * Binds a pending avatar (or remote URL) to a profile on save.
     * Local images are downscaled to a small square JPEG and stored as a
     * base64 data URL directly on the profile — so the avatar syncs to
     * every device inside the profiles blob (no file sharing needed).
     */
    fun finalizeAvatar(context: Context, profileId: String, pendingUrl: String?) {
        when {
            pendingUrl == null -> return
            pendingUrl.startsWith("file://") -> {
                val src = android.net.Uri.parse(pendingUrl).path?.let { java.io.File(it) }
                if (src == null || !src.exists()) return
                val dataUrl = encodeAvatarDataUrl(src)
                src.delete()
                if (dataUrl != null) setAvatarData(context, profileId, dataUrl)
            }
            else -> setCustomAvatar(context, profileId, pendingUrl)
        }
    }

    /** Downscales to ≤192px and encodes as a JPEG data URL (≈10–20 KB). */
    private fun encodeAvatarDataUrl(file: java.io.File): String? {
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 192) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(
                file.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    fun delete(context: Context, profileId: String) {
        // Close scoped DB handles FIRST: Room keeps the file locked while
        // open, and deleteDatabase on an open DB silently fails - leaving
        // the deleted profile's history on disk (a privacy leak). Next
        // access simply rebuilds the surviving profile's instance.
        runCatching {
            com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                .closeScopedInstanceForSwitch()
        }
        runCatching {
            com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
                .closeScopedInstanceForSwitch()
        }

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

        // Remove any uploaded avatar files for this profile.
        java.io.File(context.filesDir, "avatars").listFiles()
            ?.filter { it.name == profileId || it.name.startsWith("$profileId.") }
            ?.forEach { it.delete() }

        if (_activeProfile.value?.id == profileId) {
            val next = updated.firstOrNull()
            _activeProfile.value = next
            if (next != null) {
                setActive(context, next)
            } else {
                // Deleted the LAST profile: persist the cleared active id
                // and run the same cache-reset sequence a switch performs,
                // so UI state (watched caches, addons, Simkl, scoped DB
                // handles) can't keep serving the deleted profile's data.
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_ACTIVE).apply()
                onActiveProfileChanged()
            }
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
                            p.avatarData?.let { put("avatarData", it) }
                            p.kidsMaxAge?.let { put("kidsMaxAge", it) }
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
        // KEY_MIGRATED gate: only the FIRST profile adopts the legacy
        // snapshot. Without this, every later profile would also receive a
        // copy of the frozen pre-profile history (a cross-profile leak).
        val alreadyMigrated = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MIGRATED, false)
        val hadLegacy = !alreadyMigrated && hadLegacyData(context)
        val profile = create(context, name, avatarIndex)

        if (hadLegacy) {
            // Copy the legacy (un-namespaced) stores into the new profile's
            // namespace BEFORE anything binds against it - copyLegacyIntoProfile
            // runs while the new profile is already active, so the copied rows
            // are immediately the ones the scoped stores resolve.
            ProfileStorage.copyLegacyIntoProfile(context, profile.id)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MIGRATED, true).apply()
            // setActive() already ran inside create(); re-run the switch
            // sequence so every singleton (watch history DB handle, watched
            // caches, addon list, Simkl CW cache, sync pull) rebinds against
            // the profile that now holds the migrated data.
            onActiveProfileChanged()
        }
        return profile
    }

    private fun hadLegacyData(context: Context): Boolean {
        val legacyKeys = listOf(
            "kbstream_player_prefs", "kbstream_addons", "kbstream_watched_overrides",
            "kbstream_nuvio_home_order", "kbstream_nuvio_collections",
            "kbstream_stream_badges", "iptv_prefs", "simkl_auth",
            "iptv_guide_preferences", "simkl_sync", "search_prefs"
        )
        return legacyKeys.any {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty()
        }
    }

    const val AVATAR_COUNT = 8

    /** Ceiling preselected when Kids Mode is first switched on (PG). */
    const val KIDS_DEFAULT_MAX_AGE = KidsMode.MAX_AGE_PG

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
                avatarData = str("avatarData"),
                kidsMaxAge = str("kidsMaxAge")?.toIntOrNull(),
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
            "iptv_guide_preferences", "simkl_sync", "search_prefs"
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
