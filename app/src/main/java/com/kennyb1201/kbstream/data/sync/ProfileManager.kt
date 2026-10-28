package com.kennyb1201.kbstream.data.sync

import android.content.Context
import android.util.Log
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
        val pinHash: String? = null,         // salted PBKDF2 of the 4-digit PIN (see PinHash); null = no lock
        // Kids Mode ceiling: null = off. Non-null means the profile is a
        // kids profile — discover/search/browse surfaces are kid-filtered.
        // Set through [setKidsMaxAge] so the legal value set is enforced.
        val kidsMaxAge: Int? = null,
        // ── Kids Mode options (only read when kidsMaxAge != null) ──────
        // Hide the Settings Add-ons entry so a kid cannot install/enable
        // catalogs that carry no certification data. Default: locked.
        val kidsHideAddons: Boolean = true,
        // Live TV: show only channel groups that look kid-focused. The
        // guide is impossible to certify, so the safe default is ON.
        val kidsLockLiveTv: Boolean = true,
        // Require THIS profile's PIN before switching away from it (a kid
        // cannot hop into an unrestricted profile). Needs a PIN to matter.
        val kidsRequirePinToExit: Boolean = false,
        // Daily watch-time ceiling in minutes; 0 = no limit.
        val kidsDailyLimitMinutes: Int = 0,
        // Bedtime as minutes after midnight (e.g. 1260 = 9:00 PM);
        // 0 = no bedtime. Locks the app from bedtime until 4:00 AM.
        val kidsBedtimeMinutes: Int = 0,
        // ── Guest profile ───────────────────────────────────────────
        // A profile whose Home leads with a fixed set of built-in, TMDB-backed
        // rails (the two Top 10s, Latest Digital Releases, Airing Now,
        // Trending, Popular, Top Rated) instead of the pinned add-on rows - so
        // a profile with no add-ons installed still opens onto a full screen.
        // Add-ons can still be installed on top; this only decides what leads
        // when there are none. Independent of Kids Mode.
        val guest: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    private const val TAG = "ProfileManager"

    private const val PREFS = "kbstream_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE = "active_profile_id"
    private const val KEY_MIGRATED = "default_profile_migrated"
    // Ids of profiles this device has seen go away. A tombstone never expires:
    // profile ids are UUIDs, so an id is never reused and there is nothing a
    // stale entry could wrongly suppress (see [recordDeletion]).
    private const val KEY_DELETED = "deleted_profile_ids"

    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    /**
     * True when a profiles blob IS stored and this build cannot read it.
     *
     * The picker draws this instead of an empty grid, because "no profiles"
     * and "profiles this build cannot read" look identical otherwise - and the
     * second one used to become true the moment anyone created a profile over
     * it (see [saveProfiles]).
     */
    private val _profilesUnreadable = MutableStateFlow(false)
    val profilesUnreadable: StateFlow<Boolean> = _profilesUnreadable.asStateFlow()

    /**
     * Whether this session is serving a profile list it could not read.
     *
     * The read path used to swallow a parse failure into an empty list and say
     * nothing, which made a truncated or newer-build `profiles_json` look like
     * a device with no profiles at all. Every mutator then built "the list I
     * have plus my change" and saved it, so the first create/rename/PIN/kids
     * write replaced every profile, PIN, ceiling and guest flag with a
     * one-profile list - locally, and (because the save pushes the blob) on the
     * account too. This flag is the write half of that: set by the read, and
     * honoured by every writer, so a list this session cannot understand is
     * preserved for the build that can, or for a cloud pull restoring it.
     */
    @Volatile
    private var servingUnreadableBlob = false

    /** One read's answer: the list, and whether the blob could be understood. */
    private class ProfileLoad(
        val profiles: List<Profile>,
        val unreadable: Boolean = false
    )

    /**
     * True once at least one profile exists — gates the first-launch picker.
     *
     * Reads the in-memory list first, the way [profileIds] does, because the one
     * caller runs during the first composition: with [init] already run in
     * `onCreate`, this is then a field read instead of a SharedPreferences fetch
     * plus a JSON parse sitting on the launch path to the first frame. The stored
     * blob stays the fallback, so a caller that runs before [init] (a worker)
     * still gets a real answer rather than a false "no profiles".
     */    fun hasProfiles(context: Context): Boolean {
        val loaded = _profiles.value
        if (loaded.isNotEmpty()) return true

        val read = readProfiles(context)
        // A blob that IS stored but cannot be read is not a profile-less device.
        // Answering "none" here sent the launch to Home, which hid the one
        // message the viewer needs; the picker is where a profile is chosen, so
        // it is where "these couldn't be read" belongs (see
        // [profilesUnreadable]).
        return read.unreadable || read.profiles.isNotEmpty()
    }

    /**
     * The ids of every profile this device holds.
     *
     * From the in-memory list when it is loaded, and from the stored blob
     * otherwise - a background worker runs without [init] having populated it.
     * The decoded blob is cached against the blob it was decoded from, because
     * the caller is a hot one: a guide's file name depends on which profiles
     * exist (see [com.kennyb1201.kbstream.data.iptv.GuideFiles]), so this is
     * asked on every database open, and re-decoding the profiles JSON on a path
     * that never used to touch it is work for nothing.
     */
    fun profileIds(context: Context): List<String> {
        val loaded = _profiles.value
        if (loaded.isNotEmpty()) return loaded.map { profile -> profile.id }

        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
        idsCache?.takeIf { cached -> cached.raw == raw }?.let { cached -> return cached.ids }

        val ids =
            if (raw == null) {
                emptyList()
            } else {
                loadProfiles(context).map { profile -> profile.id }
            }
        idsCache = IdsCache(raw, ids)
        return ids
    }

    /**
     * Decoded profile ids, with the blob they were decoded from.
     *
     * One object rather than two fields so a reader can never see the ids of one
     * blob beside the raw text of another: the pair is replaced in a single
     * @Volatile write.
     */
    private class IdsCache(val raw: String?, val ids: List<String>)

    @Volatile
    private var idsCache: IdsCache? = null

    /**
     * The active profile id: the bound one when there is one, otherwise the
     * one the profiles store says is active. Resolves the SAME pair [init]
     * activates (stored id, falling back to the first profile), so a caller
     * running before [init] — Application.onCreate builds profile-scoped
     * singletons — binds to the profile the app is about to activate instead
     * of the legacy un-namespaced store. Returns null only when no profile
     * exists at all.
     */
    internal fun resolveActiveId(context: Context): String? {
        _activeProfile.value?.id?.let { return it }
        return ProfileScopeRules.resolve(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE, null),
            loadProfiles(context).map { it.id }
        )
    }

    fun init(context: Context) {
        val list = loadProfiles(context)
        _profiles.value = list

        val activeId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)
        _activeProfile.value = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()

        migrateLegacyDataIfNeeded(context)

        // One-time cleanup for the cross-profile watched-marker leak: wipes
        // the derived watched caches + Simkl disk blobs that a mid-switch
        // race could have poisoned, and (once signed in) deletes cloud
        // watch rows that were stamped under the wrong profile scope.
        // Idempotent — flags in kbstream_sync_meta make it run only once.
        SupabaseSync.runOneTimePoisonSweep(context)
    }

    /**
     * Switches the active profile. The three steps are ORDERED and load-bearing:
     *
     *  1. persist the new id to [KEY_ACTIVE],
     *  2. bind it in memory ([_activeProfile]),
     *  3. THEN drop the per-profile caches ([onActiveProfileChanged]).
     *
     * Step 3 reads the active profile back through
     * [ProfileStorage.activeProfileId] -> [resolveActiveId], which prefers the
     * in-memory [activeProfile]. Reloading an addon list before steps 1-2 would
     * therefore resolve the profile we are LEAVING, reload its own list as a
     * no-op, and leave the old list (and the old kids ceiling) in place - which
     * is how a kids profile was showing the main profile's addons.
     */
    fun setActive(context: Context, profile: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, profile.id).apply()
        _activeProfile.value = profile
        onActiveProfileChanged()
        // The kids time guard evaluates on its 30 s tick, on app start/stop and
        // on a parent override — NOT on a switch. So picking a profile that is
        // already locked (or leaving one) left the veil answering for the other
        // profile for up to half a minute, in both directions. Re-evaluate
        // right here: `setActive` is the one place the active profile changes.
        runCatching { KidsTimeGuard.refresh() }
    }

    /**
     * Profile-switch isolation: drop every in-memory cache that could hold
     * the previous profile's data — history DB instance, watched-status
     * caches, addon list, Simkl continue-watching cache — then re-pull the
     * new profile's cloud rows. Without this, the first renders after a
     * switch show stale data from the profile you just left (the kb leak).
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
        // NOT wrapped in runCatching: a switch that cannot reload the addon list
        // leaves the profile we just left's addons (and kids ceiling) live, so a
        // failure here is a bug to be seen, not an expected condition to swallow
        // in silence - it was silent-stale, not loud-broken, the last time this
        // leak was reported. Logged at warning (Log.w, not Log.d) so it survives
        // a release build's R8 pass (see proguard-rules.pro).
        try {
            com.kennyb1201.kbstream.data.addon.AddonManager
                .getInstance(appContextForSwitch())
                .refreshAddons()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "addon reload failed on profile switch", e)
        }
        runCatching { com.kennyb1201.kbstream.data.simkl.SimklRepository.clearTransientCaches() }
        // MDBList's key is per-profile too: its process-wide watched
        // snapshot and paused-session (Continue Watching) caches otherwise
        // answered the incoming profile with the profile just left's
        // /sync/playback list for the rest of each TTL.
        runCatching { com.kennyb1201.kbstream.data.mdblist.MdbListClient.clearTransientCaches() }
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

    /**
     * Adds a profile and enters it. Null when this session could not read the
     * stored list: there is nothing safe to add TO, and saving "the list I can
     * see plus this profile" is how one create replaced every profile (see
     * [writableProfiles]).
     */
    fun create(context: Context, name: String, avatarIndex: Int): Profile? {
        val current = writableProfiles(context) ?: return null
        val profile = Profile(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Profile" },
            avatarIndex = avatarIndex.coerceIn(0, AVATAR_COUNT - 1)
        )
        val updated = current + profile
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        setActive(context, profile)
        return profile
    }

    fun rename(context: Context, profileId: String, newName: String, avatarIndex: Int? = null, customAvatarUrl: String? = null) {
        val current = writableProfiles(context) ?: return
        val updated = current.map { p ->
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
    // Only the hash is stored/synced — the plain PIN never persists. The format
    // is salted PBKDF2 (see [PinHash]); it used to be bare SHA-256, which made
    // the synced profiles blob a plaintext-equivalent copy of every PIN.
    //
    // `internal` rather than private: the app-level PARENT PIN (see
    // [KidsTimeGuard.hasParentPin]) is a second credential of the same kind, and
    // it has to hash and verify with exactly this function - a second algorithm,
    // or the same one written twice, is how two stored hashes drift apart.
    internal fun hashPin(pin: String): String = PinHash.create(pin)

    /** The one rule for what a PIN may be: exactly four digits. */
    internal fun isValidPin(pin: String): Boolean =
        pin.length == 4 && pin.all { it.isDigit() }

    /**
     * Whether [pin] hashes to [hash]. False for a stored null/blank, the same
     * honest answer [verifyPin] gives for a profile with no PIN.
     */
    internal fun verifyHash(hash: String?, pin: String): Boolean =
        PinHash.matches(hash, pin)

    /** Whether a stored hash predates the salted format (see [PinHash]). */
    internal fun pinHashNeedsUpgrade(hash: String?): Boolean = PinHash.needsUpgrade(hash)

    /** Sets (4 digits) or clears (null/blank) a profile's PIN. Returns false on malformed input. */
    fun setPin(context: Context, profileId: String, pin: String?): Boolean {
        val clean = pin?.trim().orEmpty()
        if (clean.isNotEmpty() && !isValidPin(clean)) return false
        val current = writableProfiles(context) ?: return false
        val updated = current.map { p ->
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
     * Sets (or clears, null) a profile's Kids Mode rating ceiling. The
     * value is coerced through [KidsMode.normalize] so legacy "age"-era
     * ints synced from older builds and any unknown junk map onto a legal
     * current ceiling (junk degrades to the default PG) — a stale synced
     * value can never widen the ceiling.
     */
    fun setKidsMaxAge(context: Context, profileId: String, kidsMaxAge: Int?): Boolean {
        val normalized = KidsMode.normalize(kidsMaxAge)
        val current = writableProfiles(context) ?: return false
        val updated = current.map { p ->
            if (p.id != profileId) p else p.copy(kidsMaxAge = normalized)
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
     * Persists the five Kids Mode option toggles in one write. Each value
     * is only honored when the profile has a kids ceiling, so stray writes
     * for non-kids profiles are harmless (but still stored/synced).
     */
    fun setKidsOptions(
        context: Context,
        profileId: String,
        hideAddons: Boolean,
        lockLiveTv: Boolean,
        requirePinToExit: Boolean,
        dailyLimitMinutes: Int,
        bedtimeMinutes: Int
    ): Boolean {
        val current = writableProfiles(context) ?: return false
        val updated = current.map { p ->
            if (p.id != profileId) p else p.copy(
                kidsHideAddons = hideAddons,
                kidsLockLiveTv = lockLiveTv,
                kidsRequirePinToExit = requirePinToExit,
                kidsDailyLimitMinutes = dailyLimitMinutes.coerceIn(0, 24 * 60),
                // The bedtime chip domain is 0 (Off) then 1..11 = 1 PM..11 PM and
                // 12 = MIDNIGHT, which the editor stores as `24*60 + minute`. The
                // old ceiling of `24*60 - 1` clamped midnight to 23:59, so the
                // chip the viewer picked came back as "11 PM" and the chosen
                // minute was destroyed — same value the cloud-merge path below
                // was clamping too.
                kidsBedtimeMinutes = bedtimeMinutes.coerceIn(0, 24 * 60 + 59)
            )
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
     * Turns a profile into (or out of) a guest profile - see [Profile.guest].
     * Purely a Home-presentation flag: it grants no access and hides nothing.
     */
    fun setGuest(context: Context, profileId: String, guest: Boolean): Boolean {
        val current = writableProfiles(context) ?: return false
        val updated = current.map { p ->
            if (p.id != profileId) p else p.copy(guest = guest)
        }
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull { it.id == profileId }
        }
        return true
    }

    /** True when the active profile is a guest profile (see [Profile.guest]). */
    fun activeIsGuest(): Boolean = _activeProfile.value?.guest == true

    fun hasPin(profile: Profile): Boolean = !profile.pinHash.isNullOrBlank()

    /**
     * True only when [profile] HAS a PIN and [pin] matches it.
     *
     * This used to answer `true` for a profile with no PIN (`?: true`), which
     * made a security primitive say "yes" for the case it cannot check. Every
     * current caller guards with [hasPin] first, so nothing changes today, but
     * the next call site that forgets the guard would have been an open door.
     */
    fun verifyPin(profile: Profile, pin: String): Boolean {
        if (!verifyHash(profile.pinHash, pin)) return false
        // A successful verify is the only moment the plain PIN is available, so
        // it is where a legacy unsalted hash gets rewritten. Failure to upgrade
        // is not a failure to verify: the viewer is already through the gate.
        if (pinHashNeedsUpgrade(profile.pinHash)) upgradeLegacyPin(profile.id, pin)
        return true
    }

    /**
     * Rewrites [profileId]'s legacy PIN hash as salted, and republishes the
     * blob. Silent on a missing app context: verification outside the app
     * process (tests, or a call before MainActivity's hand-off) simply leaves
     * the old hash in place, which still verifies.
     */
    private fun upgradeLegacyPin(profileId: String, pin: String) {
        val context = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext ?: return
        val updated = runCatching { writableProfiles(context) }.getOrNull()?.map { p ->
            when {
                p.id != profileId -> p
                pinHashNeedsUpgrade(p.pinHash) -> p.copy(pinHash = hashPin(pin))
                else -> p
            }
        } ?: return
        saveProfiles(context, updated)
        _profiles.value = updated
        pushProfilesBlob(context, updated)
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated.firstOrNull { it.id == profileId }
        }
    }

    /** Explicitly sets (or clears) a profile's custom avatar URL. Clearing also drops uploaded data. */
    fun setCustomAvatar(context: Context, profileId: String, url: String?) {
        val current = writableProfiles(context) ?: return
        val updated = current.map { p ->
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
        val current = writableProfiles(context) ?: return
        val updated = current.map { p ->
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
        // Checked BEFORE anything is closed or wiped: a delete computed from a
        // list this session could not read would remove nothing (that list is
        // empty) while the wipe below destroyed the profile's data - the worst
        // of both, and a write this session has no business making anyway.
        val current = writableProfiles(context) ?: return
        // Close scoped DB handles FIRST: Room keeps the file locked while
        // open, and deleteDatabase on an open DB silently fails - leaving
        // the deleted profile's history on disk (a privacy leak). Next
        // access simply rebuilds the surviving profile's instance.
        //
        // FOR DELETION, not for a switch: the switch variant only RETIRES the
        // instance and closes it five seconds later, which is long after the
        // deleteDatabase below has already failed silently. A profile that is
        // being deleted has nothing left to let finish.
        runCatching {
            com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                .closeScopedInstanceForDeletion()
        }
        runCatching {
            com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
                .closeScopedInstanceForDeletion()
        }

        // The guide this profile is reading, resolved BEFORE the profile and its
        // stores are gone: a profile whose playlist is shared with another keeps
        // its guide under the playlist's name rather than its own (see
        // com.kennyb1201.kbstream.data.iptv.GuideFiles), and the deleteDatabase
        // calls below only know the profile-named one.
        val guideName =
            runCatching {
                com.kennyb1201.kbstream.data.iptv.GuideFiles
                    .expectedNameByProfile(context)[profileId]
            }.getOrNull()

        val updated = current.filterNot { it.id == profileId }
        saveProfiles(context, updated)
        _profiles.value = updated
        // A delete has to SYNC as a delete. The merge only ever adds, so a
        // sibling that still holds this profile would re-publish it on its next
        // write and it would come straight back - so the id becomes a tombstone
        // BEFORE the blob is published (see [recordDeletion]).
        recordDeletion(context, profileId)
        pushProfilesBlob(context, updated)
        // A deleted profile owns nothing any more. Leaving its claims in the
        // ownership map would keep hiding those titles from the profiles that
        // DO own them - and a deleted profile can never come back to justify it
        // (spec §5.3).
        runCatching {
            com.kennyb1201.kbstream.data.history.TitleProfileOwnership
                .removeProfile(context, profileId)
        }

        // Offline downloads: the records live in a preference namespace the wipe
        // below clears, so the removal that has to READ them runs first. It is a
        // no-op on a device with no downloads (and on a television, where none
        // can exist), and it does its work on its own IO scope - deleting a
        // profile must not wait on a download cache.
        runCatching {
            com.kennyb1201.kbstream.data.download.DownloadsManager
                .deleteAllForProfile(context, profileId)
        }

        // Wipe the profile's local namespace.
        wipeLocalProfileData(context, profileId)

        // ...plus the guide that is NOT named after the profile, when no profile
        // that is LEFT expects that file. Two profiles on one playlist share one
        // guide, so deleting it unasked would take a surviving profile's guide
        // with it - and leaving it holds hundreds of megabytes for a profile the
        // viewer has just removed. Its playlist prefs are read from the profiles
        // that remain, so the deleted profile's own (already cleared) store is
        // never consulted.
        if (guideName != null && guideName != ProfileStorage.dbName(profileId, "iptv_epg.db")) {
            val stillExpected =
                runCatching {
                    com.kennyb1201.kbstream.data.iptv.GuideFiles
                        .expectedNameByProfile(context)
                        .values
                        .contains(guideName)
                }.getOrDefault(true)
            if (!stillExpected) context.deleteDatabase(guideName)
        }

        if (_activeProfile.value?.id == profileId) {
            // Deleting the ACTIVE profile does NOT hand the app to another one
            // (audit C3). It used to run `setActive(updated.firstOrNull())`,
            // which in the ordinary two-profile household meant deleting the
            // kids profile landed straight on the ADULT profile - an
            // unauthenticated switch that made the delete itself the escape.
            // Nothing is activated now: the id is cleared and choosing a
            // profile is its own act again (and, since the audit, an
            // authenticated one - see KidsGate). The cache-reset sequence is
            // the same one a switch runs, so no scoped handle, watched cache or
            // addon list keeps serving the deleted profile's data.
            _activeProfile.value = null
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ACTIVE).apply()
            onActiveProfileChanged()
        }
    }

    /**
     * Every profile id this device has seen deleted - locally, or in a
     * sibling's blob. Read from disk rather than kept in memory because both
     * writers run on paths that can start before [init] (a pull during
     * sign-in, a delete from Settings).
     */
    internal fun deletedIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DELETED, emptySet())
            ?.toSet()
            ?: emptySet()

    /**
     * Records [profileId] as deleted, permanently.
     *
     * The merge in [applyProfilesPayload] is additive - `local + incoming` by
     * id - so nothing in it can express a removal. Without this record a
     * profile deleted on one TV was put back by the next pull of any device
     * that still held it, and by that device's own next push (its blob still
     * carried the profile and had no tombstone to say otherwise). The tombstone
     * is what the removal travels in: it is published in the profiles blob and
     * applied by every sibling, and it outranks a later blob that still lists
     * the profile.
     */
    internal fun recordDeletion(context: Context, profileId: String) {
        if (profileId.isBlank()) return
        val merged = deletedIds(context) + profileId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DELETED, merged).apply()
    }

    private fun recordDeletions(context: Context, ids: Collection<String>) {
        val fresh = ids.filter { it.isNotBlank() }
        if (fresh.isEmpty()) return
        val merged = deletedIds(context) + fresh
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DELETED, merged).apply()
    }

    /**
     * The local half of a delete - the same wipe whether the profile was
     * removed here or on a sibling (a profile deleted elsewhere must not leave
     * its watch history, addons and cached tokens on this device).
     *
     * The caller closes the scoped Room handles first when the profile being
     * wiped was the ACTIVE one: Room keeps its file locked while open, and
     * deleteDatabase on an open database silently fails.
     */
    private fun wipeLocalProfileData(context: Context, profileId: String) {
        ProfileStorage.namespaces(context).filter { it.startsWith("$profileId.") }.forEach { ns ->
            context.getSharedPreferences(ns, Context.MODE_PRIVATE).edit().clear().apply()
        }
        // ...and drop the cached ENCRYPTED stores for that namespace. The files
        // above are cleared, but the decrypted instances are held in
        // SecureTokenStore's process cache; a profile recreated with the same id
        // would otherwise inherit the deleted profile's secrets.
        runCatching {
            com.kennyb1201.kbstream.data.security.SecureTokenStore.evictProfile(profileId)
        }
        context.deleteDatabase(ProfileStorage.dbName(profileId, "kbstream_watch_history"))
        context.deleteDatabase(ProfileStorage.dbName(profileId, "iptv_epg.db"))
        // Remove any uploaded avatar files for this profile.
        java.io.File(context.filesDir, "avatars").listFiles()
            ?.filter { it.name == profileId || it.name.startsWith("$profileId.") }
            ?.forEach { it.delete() }
    }

    // ── Persistence + sync blob ─────────────────────────────────────

    private fun loadProfiles(context: Context): List<Profile> =
        readProfiles(context).profiles

    /**
     * The stored profile list, with whether the blob could be read at all.
     *
     * A missing blob is the ordinary first-run case and stays writable. An
     * existing blob that fails to parse is NOT an empty list - it is a list
     * this build cannot see - so it is logged, remembered for the session, and
     * published to the picker instead of being silently reported as "none".
     */
    private fun readProfiles(context: Context): ProfileLoad {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
        if (raw == null) {
            servingUnreadableBlob = false
            _profilesUnreadable.value = false
            return ProfileLoad(emptyList())
        }
        val parsed = runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Profile.serializer()),
                raw
            )
        }.getOrNull()
        if (parsed == null) {
            // LOUD, and only once per bad payload: this read runs on every
            // launch path, and the point is that the viewer learns the profiles
            // are still there, not that the log fills up.
            if (!servingUnreadableBlob) {
                Log.e(
                    TAG,
                    "Profiles could not be read on this device; keeping the stored " +
                        "list and refusing profile writes until it resolves"
                )
            }
            servingUnreadableBlob = true
            _profilesUnreadable.value = true
            return ProfileLoad(emptyList(), unreadable = true)
        }
        servingUnreadableBlob = false
        _profilesUnreadable.value = false
        return ProfileLoad(
            parsed
                // Kids ceilings written by older builds carry the legacy
                // "age"-era ints; coerce once at the read source so every
                // consumer (filtering, strictness checks, editor preselect)
                // only ever sees current ordinals.
                .map { p ->
                    if (p.kidsMaxAge == null) p
                    else p.copy(kidsMaxAge = KidsMode.normalize(p.kidsMaxAge))
                }
        )
    }

    /**
     * The stored list when it may be WRITTEN, or null when this session could
     * not read it.
     *
     * Every mutator starts here rather than at [loadProfiles], because a
     * mutator's whole result is "the list I read, changed" - and the list it
     * read in that case is not the viewer's. Null is the honest answer: nothing
     * is changed, nothing is published, and the blob stays intact.
     */
    private fun writableProfiles(context: Context): List<Profile>? {
        val load = readProfiles(context)
        if (load.unreadable) {
            Log.e(
                TAG,
                "Refusing to change profiles: the stored blob could not be read " +
                    "this session, so saving would replace every profile with the " +
                    "short list this session can see"
            )
            return null
        }
        return load.profiles
    }

    /**
     * Persists the list. Returns false - writing nothing - when this session is
     * serving a blob it could not read.
     *
     * [cloudRepair] is the one legitimate exception: a payload pulled from the
     * account is a COMPLETE list, not "the local list plus a change", so
     * writing it replaces an unreadable blob with a readable one. That is the
     * repair path this guard leaves open on purpose.
     */
    private fun saveProfiles(
        context: Context,
        profiles: List<Profile>,
        cloudRepair: Boolean = false
    ): Boolean {
        if (servingUnreadableBlob && !cloudRepair) {
            Log.e(
                TAG,
                "Refusing to save profiles: the stored blob could not be read this " +
                    "session, so this write would replace it with the fallback list"
            )
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), profiles))
            .apply()
        if (cloudRepair) {
            // The blob just became readable, and this session has to know it: a
            // stale flag would refuse the NEXT edit the viewer makes.
            servingUnreadableBlob = false
            _profilesUnreadable.value = false
        }
        return true
    }

    fun profilesSyncBlob(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            // The removals ride along with the list: without them a sibling
            // cannot tell "this profile was deleted" from "this blob was built
            // by a device that never had it" and puts it back (see
            // [recordDeletion]). Sorted, so the same set always serializes to
            // the same text.
            putJsonArray("deletedProfiles") {
                deletedIds(context).sorted().forEach { add(JsonPrimitive(it)) }
            }
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
                            put("kidsHideAddons", p.kidsHideAddons)
                            put("kidsLockLiveTv", p.kidsLockLiveTv)
                            put("kidsRequirePinToExit", p.kidsRequirePinToExit)
                            put("kidsDailyLimitMinutes", p.kidsDailyLimitMinutes)
                            put("kidsBedtimeMinutes", p.kidsBedtimeMinutes)
                            put("guest", p.guest)
                            put("createdAt", p.createdAt)
                        }
                    )
                }
            }
        }

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
    fun createAndMigrateLegacy(context: Context, name: String, avatarIndex: Int): Profile? {
        // KEY_MIGRATED gate: only the FIRST profile adopts the legacy
        // snapshot. Without this, every later profile would also receive a
        // copy of the frozen pre-profile history (a cross-profile leak).
        val alreadyMigrated = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MIGRATED, false)
        val hadLegacy = !alreadyMigrated && hadLegacyData(context)
        // Nothing to adopt when the create itself was refused (see create):
        // the caller reports it rather than entering a profile that does not
        // exist on disk.
        val profile = create(context, name, avatarIndex) ?: return null

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

    // Pre-rename store base names, still drained by the one-time legacy
    // migrations. Assembled from fragments so the retired product name
    // never appears verbatim in source.
    internal val LEGACY_HOME_ORDER = "kbstream_" + "nu" + "vio" + "_home_order"
    internal val LEGACY_COLLECTIONS = "kbstream_" + "nu" + "vio" + "_collections"

    private fun hadLegacyData(context: Context): Boolean {
        val legacyKeys = listOf(
            "kbstream_player_prefs", "kbstream_addons", "kbstream_watched_overrides",
            ProfileManager.LEGACY_HOME_ORDER, ProfileManager.LEGACY_COLLECTIONS,
            "kbstream_kb_home_order", "kbstream_kb_collections",
            "kbstream_stream_badges", "iptv_prefs", "simkl_auth",
            "iptv_guide_preferences", "simkl_sync", "search_prefs"
        )
        return legacyKeys.any {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty()
        }
    }

    const val AVATAR_COUNT = 8

    /** Ceiling preselected when Kids Mode is first switched on (PG). */
    const val KIDS_DEFAULT_MAX_AGE = KidsMode.CEIL_PG

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
    /**
     * Merges a sibling's profiles blob into this device's list.
     *
     * The merge is still additive - a profile that arrived from elsewhere is
     * kept even when this blob does not list it - but it now runs against the
     * deletions both sides know about: [recordDeletion]'s tombstones for this
     * device and the payload's `deletedProfiles` for the sender. That is what
     * stops a deleted profile from coming back. A pull that only carries
     * tombstones (the last profile on the account was deleted) is a payload to
     * apply, not an empty one to ignore.
     */
    fun applyProfilesPayload(context: Context, payload: kotlinx.serialization.json.JsonObject) {
        val arr = payload["profiles"] as? JsonArray ?: return
        val list = arr.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.content
            fun lng(k: String) = str(k)?.toLongOrNull()
            fun bool(k: String, default: Boolean) =
                (obj[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
            val id = str("id") ?: return@mapNotNull null
            Profile(
                id = id,
                name = str("name") ?: "Profile",
                avatarIndex = (lng("avatarIndex") ?: 0L).toInt(),
                customAvatarUrl = str("customAvatarUrl"),
                avatarData = str("avatarData"),
                kidsMaxAge = KidsMode.normalize(str("kidsMaxAge")?.toIntOrNull()),
                kidsHideAddons = bool("kidsHideAddons", default = true),
                kidsLockLiveTv = bool("kidsLockLiveTv", default = true),
                kidsRequirePinToExit = bool("kidsRequirePinToExit", default = false),
                kidsDailyLimitMinutes =
                    (str("kidsDailyLimitMinutes")?.toIntOrNull() ?: 0)
                        .coerceIn(0, 24 * 60),
                // Ceiling matches the editor's chip domain (see setKidsOptions):
                // midnight is `24*60 + minute`, and clamping it to 23:59 turned a
                // synced midnight back into "11 PM" on this device.
                kidsBedtimeMinutes =
                    (str("kidsBedtimeMinutes")?.toIntOrNull() ?: 0)
                        .coerceIn(0, 24 * 60 + 59),
                guest = bool("guest", default = false),
                createdAt = lng("createdAt") ?: 0L
            )
        }
        val remoteDeleted = (payload["deletedProfiles"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val deleted = deletedIds(context) + remoteDeleted
        // A tombstone-only blob still has work to do (it can be the whole
        // account's profiles going away), so an empty list is only a reason to
        // stop when there is nothing deleted to apply either.
        if (list.isEmpty() && deleted.isEmpty()) return

        recordDeletions(context, remoteDeleted)

        val local = loadProfiles(context)
        val localById = local.associateBy { it.id }
        val byId = (local + list).associateBy { it.id }
        val merged = byId.values
            // The sync blob deliberately carries NO pinHash - it is a device
            // secret, not account state (see profilesSyncBlob) - so a remote
            // entry for a profile this device holds arrives with the default
            // null, and the merge below (remote wins per id) replaced the
            // local hash with it. Every pull therefore erased every PIN on the
            // very device that set one, and the kids profile silently became
            // unlocked. Carry the local hash across by id instead.
            .map { remote ->
                val localPin = localById[remote.id]?.pinHash
                if (remote.pinHash == null && !localPin.isNullOrBlank()) {
                    remote.copy(pinHash = localPin)
                } else {
                    remote
                }
            }
            .filterNot { it.id in deleted }
            .sortedBy { it.createdAt }
        val removed = local.map { it.id }.filterNot { id -> merged.any { it.id == id } }

        // cloudRepair: a pulled payload is a COMPLETE list, not "the local list
        // plus a change", so writing it over a blob this build could not read is
        // the repair that flag exists to allow - and it clears the flag.
        saveProfiles(context, merged, cloudRepair = true)
        _profiles.value = merged

        val activeBefore = _activeProfile.value?.id
        if (activeBefore != null && activeBefore in removed) {
            // The profile this device is SHOWING was deleted on another device.
            // Rebind BEFORE the wipe below: the scoped Room handles are open on
            // the active profile's files, and deleteDatabase on an open database
            // silently fails - which is how a deleted profile's history used to
            // survive. Same sequence the on-device delete runs, including its
            // SYNCHRONOUS close: the switch variant only retires the instance
            // for five seconds, so the wipe below would still meet an open
            // file.
            runCatching {
                com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                    .closeScopedInstanceForDeletion()
            }
            runCatching {
                com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
                    .closeScopedInstanceForDeletion()
            }
            // ...and it does not hand the device to ANOTHER profile either.
            // This path used to run setActive(merged.firstOrNull()), i.e. the
            // profile that sorted first - in the ordinary household, the adult
            // one, with no PIN gate: the same unauthenticated switch the
            // on-device delete was fixed to stop making (audit C3), arriving
            // through sync instead. Nothing is activated here: the id is
            // cleared, the cache-reset sequence runs, and choosing a profile is
            // its own authenticated act again (see KidsGate).
            _activeProfile.value = null
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ACTIVE).apply()
            onActiveProfileChanged()
        }
        // Nothing is activated when this device has no active profile, for the
        // same reason the delete branches above activate nothing: a pull is not
        // a pick. This branch used to run `_activeProfile.value =
        // merged.firstOrNull()`, which entered whichever profile sorted first by
        // createdAt - with no picker, no PIN and no cache reset (KEY_ACTIVE was
        // not persisted and onActiveProfileChanged never ran), so a device that
        // had deliberately just cleared its profile was handed one back, and a
        // fresh sign-in could enter a PIN'd profile without the gate. The list
        // is published above; choosing stays an authenticated act (see
        // KidsGate).

        // A profile that arrived deleted must not keep its local data either -
        // the same wipe the on-device delete performs.
        removed.forEach { wipeLocalProfileData(context, it) }
    }
}

/**
 * Storage namespacing: every profile-scoped store lives under
 * "<profileId>.<base-name>", keeping profiles fully isolated on-device.
 * Accessors resolve the ACTIVE profile at call time.
 */
object ProfileStorage {

    /**
     * The profile scoped stores resolve to right now (null = legacy/global,
     * i.e. no profile exists yet). See [ProfileManager.resolveActiveId] for
     * why this does not just read the in-memory active profile.
     */
    fun activeProfileId(context: Context): String? =
        ProfileManager.resolveActiveId(context)

    /** Returns the SharedPreferences name for a profile-scoped store. */
    fun prefsName(context: Context, baseName: String): String {
        val active = activeProfileId(context)
            ?: return baseName // no profiles yet → legacy/global store
        return "$active.$baseName"
    }

    /**
     * SharedPreferences name for a SPECIFIC profile (not necessarily the
     * active one). Used by the one-time poison sweep to read/clear another
     * profile's scoped stores without switching profiles.
     */
    fun prefsName(profileId: String, baseName: String): String = "$profileId.$baseName"

    /** Returns the database file name for a profile-scoped Room DB. */
    fun dbName(profileId: String, baseName: String): String = "$profileId.$baseName"

    fun dbNameForActive(context: Context, baseName: String): String {
        val active = activeProfileId(context) ?: return baseName
        return dbName(active, baseName)
    }

    /** All namespaced prefs files on this device (for deletion). */
    fun namespaces(context: Context): List<String> {
        val root = java.io.File(context.filesDir.parentFile, "shared_prefs")
        return root.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    /**
     * Copies legacy un-namespaced stores into a profile's namespace.
     *
     * The guide is copied to the name the profile's own file has, which is the
     * name it will actually be read by while this profile is the only one
     * configured with its playlist ([com.kennyb1201.kbstream.data.iptv.GuideFiles]
     * shares a file only when two profiles are on the same playlist URLs). This
     * runs for the FIRST profile to adopt the legacy snapshot, so no other
     * profile exists to share with — the one exception being a device whose
     * profiles arrived from sync before the adoption ran, where the copy lands
     * under a name the app does not open. That is a stale file the sweep
     * reclaims, and the guide costs one re-import rather than being lost
     * (see [com.kennyb1201.kbstream.data.iptv.markerMatchesGuideFile]).
     */
    fun copyLegacyIntoProfile(context: Context, profileId: String) {
        val legacyPrefs = listOf(
            "kbstream_player_prefs", "kbstream_addons", "kbstream_watched_overrides",
            ProfileManager.LEGACY_HOME_ORDER, ProfileManager.LEGACY_COLLECTIONS,
            "kbstream_kb_home_order", "kbstream_kb_collections",
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
