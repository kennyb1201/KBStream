package com.kennyb1201.kbstream.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place the app's bearer credentials are read and written.
 *
 * The Supabase refresh token and the Simkl access token are live credentials:
 * whoever reads one can act as the user against that service until it is
 * revoked. They used to sit in ordinary `SharedPreferences` files, which on a
 * rooted or adb-enabled box are plain XML — a single `cat` on
 * `/data/data/<pkg>/shared_prefs/` recovered a working session, and a cloud
 * backup or a device transfer carried it off the device intact.
 *
 * [prefs] returns an [EncryptedSharedPreferences] keyed by a master key that
 * lives in the AndroidKeyStore and never leaves the device, so the on-disk file
 * is ciphertext.
 *
 * ## Store names are unchanged
 *
 * Callers keep the exact store name they used before (for example
 * `"<profileId>.simkl_auth"`). That matters: profile adoption, profile
 * deletion (`ProfileManager.namespaces`) and the cloud blobs that mirror these
 * stores all address them by that name. Encryption is a change of FILE FORMAT,
 * not of identity, so [legacyPlaintext] moves the old plaintext file aside and
 * reuses the name rather than introducing a parallel `.secure` store every one
 * of those paths would have to learn about.
 *
 * ## Why there is a plaintext fallback
 *
 * Every failure mode here is a device the app must still run on: a KeyStore
 * that will not hand out the master key on a damaged/flashed box, a corrupted
 * keyset file, or a Robolectric JVM with no keystore at all. Refusing to
 * return a store would turn a token read into a crash on those devices, so a
 * failure logs and falls back to a plain store. That is a deliberate
 * degradation: it is never worse than the previous behavior, and the
 * alternative is an app that cannot sign in.
 *
 * ## Caching
 *
 * [EncryptedSharedPreferences.create] does KeyStore work and is far too
 * expensive for the per-access getters that read these stores, so instances
 * are cached by resolved name for the life of the process. The map is keyed by
 * the fully-resolved store name (profile-scoped names included), so a profile
 * switch reads a different entry rather than a stale one.
 */
object SecureTokenStore {

    private const val TAG = "SecureTokenStore"

    /**
     * Master-key alias. Versioned so a future rotation can introduce
     * `..._v2` and re-encrypt without colliding with the existing key.
     */
    private const val MASTER_KEY_ALIAS = "kbstream_secure_prefs_v1"

    /**
     * Plaintext bookkeeping for the one-time migration. Holds only a boolean
     * per store name — never a secret — so it is safe to keep in the clear.
     */
    private const val MIGRATION_PREFS = "kbstream_secure_migration"

    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    /**
     * The store for [name]: encrypted, or a plain store if the platform cannot
     * provide one. Never throws.
     *
     * Pass [legacyPlaintext] `true` for a store that existed before this
     * change and held a secret; its old plaintext file is drained into the
     * encrypted store once and then removed.
     */
    fun prefs(
        context: Context,
        name: String,
        legacyPlaintext: Boolean = false
    ): SharedPreferences {
        val appContext = context.applicationContext
        cache[name]?.let { return it }

        // Step 1, BEFORE create: retire the old plaintext file so the encrypted
        // store can take the name. Must happen first - `create` cannot adopt a
        // file that already holds plaintext XML.
        if (legacyPlaintext) retirePlaintextFile(appContext, name)

        val resolved = runCatching {
            EncryptedSharedPreferences.create(
                appContext,
                name,
                masterKey(appContext),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { e ->
            Log.w(
                TAG,
                "encrypted prefs unavailable for '$name' " +
                    "(${e.javaClass.simpleName}: ${e.message}) — using the plain store"
            )
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        }

        // Step 2, AFTER create: move the retired entries into the encrypted
        // store and drop the plaintext file.
        if (legacyPlaintext) drainRetiredFile(appContext, name, resolved)

        return cache.computeIfAbsent(name) { resolved }
    }

    /**
     * Renames a pre-encryption plaintext file for [name] to
     * `<name>.legacy.xml`, freeing the original name for the encrypted store.
     *
     * Guarded by a marker in [MIGRATION_PREFS], and by a content check: only a
     * file that actually holds plaintext XML is touched. Both guards matter
     * because the encrypted store uses the SAME file name, so a rename of the
     * wrong file would destroy a live session. The content check is the
     * backstop for a process death between `create` and the marker write — the
     * file on disk would then be ciphertext, and renames are refused.
     */
    private fun retirePlaintextFile(context: Context, name: String) {
        val marker = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean(markerKey(name), false)) return

        runCatching {
            val live = File(prefsDir(context), "$name.xml")
            if (!live.exists()) return@runCatching
            if (!looksLikePlaintext(live)) {
                // Already ciphertext: nothing to retire. Deliberately does NOT
                // set the marker - the marker means "migration finished", and
                // this run may be the one that still has a retired
                // `.legacy.xml` to drain (a previous process died between
                // `create` and the drain). [drainRetiredFile] sets it once the
                // entries are actually across.
                return@runCatching
            }

            val legacy = File(prefsDir(context), "$name.legacy.xml")
            if (legacy.exists()) legacy.delete()
            live.renameTo(legacy)
        }.onFailure { e ->
            Log.w(TAG, "could not retire plaintext store '$name': ${e.message}")
        }
    }

    /**
     * Copies the retired plaintext entries into the encrypted [target], then
     * deletes the plaintext file and marks the migration done.
     *
     * Values already present in [target] win, and the marker is written only
     * after the copy is committed: a failure leaves the marker unset and the
     * plaintext file in place, so the next call retries rather than losing the
     * session.
     */
    private fun drainRetiredFile(
        context: Context,
        name: String,
        target: SharedPreferences
    ) {
        val marker = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean(markerKey(name), false)) return

        runCatching {
            val legacy = File(prefsDir(context), "$name.legacy.xml")
            if (!legacy.exists()) {
                marker.edit().putBoolean(markerKey(name), true).apply()
                return@runCatching
            }

            // Read through the ordinary prefs API, under the name the file was
            // renamed to. This is a plaintext read of a file about to be
            // deleted, and the entries are copied straight into the encrypted
            // store.
            val source = context.getSharedPreferences("$name.legacy", Context.MODE_PRIVATE)
            val entries = source.all
            if (entries.isNotEmpty()) {
                val editor = target.edit()
                entries.forEach { (k, v) ->
                    if (target.contains(k)) return@forEach
                    when (v) {
                        is String -> editor.putString(k, v)
                        is Boolean -> editor.putBoolean(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is Float -> editor.putFloat(k, v)
                        is Set<*> -> @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(k, v as Set<String>)
                    }
                }
                editor.apply()
                source.edit().clear().apply()
            }
            legacy.delete()
            marker.edit().putBoolean(markerKey(name), true).apply()
            Log.i(TAG, "migrated plaintext store '$name' to encrypted")
        }.onFailure { e ->
            Log.w(TAG, "plaintext migration for '$name' failed: ${e.message}")
        }
    }

    private fun markerKey(name: String) = "migrated:$name"

    private fun prefsDir(context: Context): File =
        File(context.filesDir.parentFile, "shared_prefs")

    /**
     * A plain SharedPreferences file is XML; an encrypted one is ciphertext.
     * Cheap prefix check, only ever run on a file this process is about to
     * rename.
     */
    internal fun looksLikePlaintext(file: File): Boolean = runCatching {
        val head = ByteArray(64)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) return@runCatching false
        val text = String(head, 0, read, Charsets.ISO_8859_1)
        text.contains("<map") || text.startsWith("<?xml")
    }.getOrDefault(false)

    private fun masterKey(context: Context): MasterKey =
        MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
}
