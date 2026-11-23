package com.kennyb1201.kbstream.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.tink.AeadSerializer
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.kennyb1201.kbstream.data.settings.prefs.DataStorePrefs
import com.kennyb1201.kbstream.data.settings.prefs.Prefs
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.buffer
import okio.sink
import okio.source

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
 * [prefs] now returns a [SharedPreferences] over a DataStore file whose bytes
 * are encrypted with Tink AES-GCM, under a keyset that lives in the
 * AndroidKeyStore and never leaves the device. Nothing above this object
 * changed: the callers still call `getString`/`edit()` on a
 * [SharedPreferences], because the sync facade is the same one every other
 * store in the app uses ([DataStorePrefs]).
 *
 * ## Why DataStore and not EncryptedSharedPreferences
 *
 * `androidx.security:security-crypto` is deprecated and its 1.1.0 release never
 * left beta. Its replacement is the pair this uses: `datastore-tink` for the
 * encrypted serializer, and a Tink keyset in the AndroidKeyStore for the key.
 * The file format changes; the store identity does not (see below).
 *
 * ## Store names are unchanged, and the old store is adopted
 *
 * Callers keep the exact store name they used before (for example
 * `"<profileId>.simkl_auth"`). That matters: profile adoption, profile
 * deletion and the cloud blobs that mirror these stores all address them by
 * that name. So the first time a name is opened, the values in its
 * EncryptedSharedPreferences file (`shared_prefs/<name>.xml`) are read with the
 * SAME master key alias that wrote them, copied into the new encrypted store,
 * and only then is the old file deleted. Nobody signs in again: what the
 * previous build wrote is what this one reads.
 *
 * The marker for that ("done for this name") lives in a plain store, because it
 * is a boolean per store name and never a secret.
 *
 * ## Files
 *
 * `files/secure/<name>.secure` — the encrypted store.
 * `files/secure/<name>.plain`  — the fallback, used only when no key is
 * available at all (see below).
 *
 * Two files rather than one so a device that loses its keystore can still run
 * without ever reading ciphertext as preferences, or preferences as ciphertext.
 *
 * ## Why there is a plaintext fallback
 *
 * Every failure mode here is a device the app must still run on: a KeyStore
 * that will not hand out the keyset on a damaged/flashed box, or a Robolectric
 * JVM with no keystore at all. Refusing to return a store would turn a token
 * read into a crash on those devices, so a failure logs and falls back to a
 * plain DataStore file. That is a deliberate degradation: it is exactly the
 * old fallback (a plain store when encryption was unavailable), and the
 * alternative is an app that cannot sign in.
 *
 * ## Caching
 *
 * Building the keyset does KeyStore work and is far too expensive for the
 * per-access getters that read these stores, so instances are cached by
 * resolved name for the life of the process. The map is keyed by the
 * fully-resolved store name (profile-scoped names included), so a profile
 * switch reads a different entry rather than a stale one.
 */
object SecureTokenStore {

    private const val TAG = "SECURE_PREFS"

    /**
     * Master-key alias. Versioned so a future rotation can introduce
     * `..._v2` and re-encrypt without colliding with the existing key.
     *
     * The same alias the EncryptedSharedPreferences store was written under:
     * that is what makes adoption possible without a re-login.
     */
    private const val MASTER_KEY_ALIAS = "kbstream_secure_prefs_v1"

    /** Where Tink keeps the keyset, wrapped by that AndroidKeyStore key. */
    private const val KEYSET_NAME = "kbstream_secure_keyset"

    /** The keyset's AndroidKeyStore URI. */
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    /**
     * Plaintext bookkeeping for the one-time migrations. Holds only booleans
     * per store name — never a secret — so it is safe to keep in the clear. It
     * goes through the ordinary prefs shim, so a device that already has the
     * old XML is adopted there too.
     */
    private const val MIGRATION_PREFS = "kbstream_secure_migration"

    private const val DIR = "secure"

    /**
     * The reserved entry EncryptedSharedPreferences writes its keyset under,
     * inside the same XML as the ciphertext. Its presence is what makes a file
     * an ESP store rather than a plain one - see [isEncryptedSharedPreferences].
     */
    private const val KEYSET_MARKER = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

    /** Every name EncryptedSharedPreferences reserves for itself in a store. */
    private const val RESERVED_PREFIX = "__androidx_security_crypto_encrypted_prefs_"

    private const val ENCRYPTED_SUFFIX = ".secure"

    private const val PLAIN_SUFFIX = ".plain"

    /** Writes are not on any caller's critical path; IO is right for them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One open store per name, plus the Application that opened it.
     *
     * The owner matters for the same reason it does in the prefs registry: a
     * process has one Application, but a test runner hands out a new one per
     * test over the same files. The cached store would serve the previous
     * test's contents, and DataStore will not let a second owner open a file the
     * old instance still holds - so a different owner closes it and reopens.
     */
    private class Open(val prefs: DataStorePrefs, val owner: Context)

    private val cache = ConcurrentHashMap<String, Open>()

    /**
     * False once a keyset could not be built on this device. Process-wide: the
     * keystore does not come back within a run, and paying for it on every
     * store would slow down exactly the devices that are already broken.
     */
    @Volatile
    private var encryptionAvailable = true

    /**
     * The store for [name]: encrypted, or a plain store if the platform cannot
     * provide a key. Never throws.
     *
     * Pass [legacyPlaintext] `true` for a store that predates encryption and
     * held a secret in plain XML; any leftover file from that migration is
     * drained into the new store once.
     */
    fun prefs(
        context: Context,
        name: String,
        legacyPlaintext: Boolean = false
    ): SharedPreferences {
        val appContext = context.applicationContext
        cache[name]?.let { if (it.owner === appContext) return it.prefs }
        return synchronized(cache) {
            val existing = cache[name]
            if (existing != null && existing.owner === appContext) {
                existing.prefs
            } else {
                existing?.prefs?.close()
                open(appContext, name, legacyPlaintext).also {
                    cache[name] = Open(it, appContext)
                }
            }
        }
    }

    /**
     * Drops every cached store belonging to [profileId] (its resolved names are
     * `"<profileId>.<base>"`). Called when a profile is deleted: the cached
     * instance would otherwise keep serving that profile's secrets from memory,
     * and a profile later recreated with the same id would get the stale store
     * instead of a fresh one.
     */
    fun evictProfile(profileId: String) {
        if (profileId.isBlank()) return
        val prefix = "$profileId."
        cache.keys.toList().forEach { key ->
            if (key.startsWith(prefix)) evict(key)
        }
    }

    /**
     * [evictProfile] AND its files: a deleted profile's tokens must not stay on
     * disk. The files are outside the ordinary prefs directory, so a profile
     * delete has to sweep them explicitly - that is what this is for.
     */
    fun discardProfile(context: Context, profileId: String) {
        if (profileId.isBlank()) return
        val appContext = context.applicationContext
        val prefix = "$profileId."
        evictProfile(profileId)
        runCatching {
            secureDir(appContext).listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }.onFailure { e ->
            Log.w(TAG, "could not delete secure files for a profile: ${e.message}")
        }
    }

    /**
     * Drops one cached store belonging to [name] and deletes its file.
     *
     * The recovery half of a store that will not open: the ciphertext on disk
     * outlives the key that decrypts it (a restored backup, a re-flashed device,
     * a keyset an earlier build left half-written), and a store like that throws
     * on every open forever. Deleting the file is the only way back for a caller
     * that can accept the loss - a session it is about to write fresh anyway.
     * Evicting first matters: a cached instance would otherwise keep serving the
     * deleted file's contents.
     *
     * Returns true when there was a file to delete.
     */
    fun discard(context: Context, name: String): Boolean {
        val appContext = context.applicationContext
        evict(name)
        return runCatching {
            val dir = secureDir(appContext)
            val encrypted = File(dir, "$name$ENCRYPTED_SUFFIX")
            val plain = File(dir, "$name$PLAIN_SUFFIX")
            val existed = encrypted.exists() || plain.exists()
            encrypted.delete()
            plain.delete()
            existed
        }.getOrDefault(false)
    }

    // ── Opening ──────────────────────────────────────────────────────

    private fun open(context: Context, name: String, legacyPlaintext: Boolean): DataStorePrefs {
        val store = encrypted(context, name) ?: plain(context, name)
        adopt(context, name, store, legacyPlaintext)
        return store
    }

    /** The encrypted store, or null when this device cannot provide a key. */
    private fun encrypted(context: Context, name: String): DataStorePrefs? {
        if (!encryptionAvailable) return null
        val aead = runCatching { aead(context) }.getOrElse { e ->
            Log.w(
                TAG,
                "no usable keyset for '$name' (${e.javaClass.simpleName}: ${e.message}) " +
                    "- using the plain store"
            )
            encryptionAvailable = false
            return null
        }
        val file = File(secureDir(context), "$name$ENCRYPTED_SUFFIX")
        return DataStorePrefs(name = name, parent = scope) { child ->
            DataStoreFactory.create(
                serializer = AeadSerializer(aead, PreferencesStreamSerializer, name.toByteArray()),
                scope = child,
                produceFile = { file },
            )
        }
    }

    private fun plain(context: Context, name: String): DataStorePrefs {
        val file = File(secureDir(context), "$name$PLAIN_SUFFIX")
        return DataStorePrefs(name = name, parent = scope) { child ->
            DataStoreFactory.create(
                serializer = PreferencesStreamSerializer,
                scope = child,
                produceFile = { file },
            )
        }
    }

    /** AES-GCM under a keyset that lives in the AndroidKeyStore. */
    private fun aead(context: Context): Aead {
        AeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, MASTER_KEY_URI)
            .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    // ── One-time adoption ────────────────────────────────────────────

    /**
     * Moves the values this name had before it lived in [target].
     *
     * Two shapes of old store, both keyed by the same file name:
     *  - an EncryptedSharedPreferences file (ciphertext XML) — the store this
     *    replaces. Read with the same master key alias, copied, then deleted.
     *  - a plain XML file from before encryption existed. Its values are copied
     *    and the file is removed; [legacyPlaintext] also covers the
     *    `<name>.legacy.xml` a previous build may have left mid-migration.
     *
     * Each is marked done in [MIGRATION_PREFS] only after the copy COMMITS, so a
     * failure retries on the next open instead of silently losing a session.
     */
    private fun adopt(
        context: Context,
        name: String,
        target: SharedPreferences,
        legacyPlaintext: Boolean
    ) {
        val marker = Prefs.file(context, MIGRATION_PREFS)
        if (legacyPlaintext) drainRetiredPlaintext(context, name, target, marker)
        adoptEncryptedPrefs(context, name, target, marker)
    }

    /**
     * The `<name>.legacy.xml` half of the pre-encryption migration: an older
     * build renamed the plaintext file aside and could have died before
     * draining it.
     */
    private fun drainRetiredPlaintext(
        context: Context,
        name: String,
        target: SharedPreferences,
        marker: SharedPreferences
    ) {
        val key = "retired:$name"
        if (marker.getBoolean(key, false)) return
        val retiredName = "$name.legacy"
        val retired = File(prefsDir(context), "$retiredName.xml")
        if (!retired.exists()) {
            marker.edit().putBoolean(key, true).apply()
            return
        }
        // Through the shim: it adopts the XML (and deletes it), so this is a
        // plain read of a file that is about to stop existing.
        val source = Prefs.file(context, retiredName)
        if (!copyInto(target, source.all)) return
        Prefs.delete(context, retiredName)
        marker.edit().putBoolean(key, true).apply()
        Log.i(TAG, "drained the retired plaintext file for '$name'")
    }

    /** Copies the EncryptedSharedPreferences store this name used to live in. */
    private fun adoptEncryptedPrefs(
        context: Context,
        name: String,
        target: SharedPreferences,
        marker: SharedPreferences
    ) {
        val key = "adopted:$name"
        if (marker.getBoolean(key, false)) return
        val legacy = File(prefsDir(context), "$name.xml")
        if (!legacy.exists()) {
            // Nothing to adopt. Mark it so this is not re-checked on every open.
            marker.edit().putBoolean(key, true).apply()
            return
        }
        if (!isEncryptedSharedPreferences(legacy)) {
            if (looksLikePlaintext(legacy)) {
                adoptPlaintextStore(context, name, target, marker, key)
            } else {
                // Neither shape this knows: a blob from some other tool. Leave
                // it exactly as it is rather than guess at it - and do not
                // mark it done, so a later build can still recognise it.
                Log.w(TAG, "unrecognised store file for '$name'; leaving it alone")
            }
            return
        }
        val entries = runCatching {
            val old = EncryptedSharedPreferences.create(
                context,
                name,
                masterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            old.all
        }.getOrElse { e ->
            // A store that will not open is the case the callers' discard()
            // path exists for. Leave the marker alone: if the key comes back,
            // the next process adopts it; if it never does, the caller rebuilds.
            Log.w(
                TAG,
                "could not read the encrypted store '$name' " +
                    "(${e.javaClass.simpleName}: ${e.message}) - will retry"
            )
            return
        }
        // ESP keeps its own keysets in this same map under reserved names; they
        // are state, not preferences, and copying them would leave junk in the
        // new store.
        val values = entries.filterKeys { !it.startsWith(RESERVED_PREFIX) }
        if (!copyInto(target, values)) return
        // Committed into the new store: the old file is now a second copy of
        // live credentials, so it goes. Deleting the file rather than
        // Context.deleteSharedPreferences because nothing in this process opens
        // that name any more, so there is no cached instance left to clear.
        runCatching { File(prefsDir(context), "$name.xml").delete() }
        marker.edit().putBoolean(key, true).apply()
        Log.i(TAG, "adopted the encrypted store '$name' (${entries.size} entries)")
    }

    /**
     * A name that was plaintext before encryption existed (the Simkl session
     * was). Its values are read through the ordinary prefs shim, which adopts
     * the XML, copied, and then the shim's copy is deleted: the plaintext file
     * must stop existing either way.
     */
    private fun adoptPlaintextStore(
        context: Context,
        name: String,
        target: SharedPreferences,
        marker: SharedPreferences,
        markerKey: String
    ) {
        val source = Prefs.file(context, name)
        if (!copyInto(target, source.all)) return
        Prefs.delete(context, name)
        marker.edit().putBoolean(markerKey, true).apply()
        Log.i(TAG, "adopted the plaintext store '$name'")
    }

    /** Writes [entries] into [target]; true only when the write committed. */
    private fun copyInto(target: SharedPreferences, entries: Map<String, *>): Boolean {
        if (entries.isEmpty()) return true
        val editor = target.edit()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
        }
        return editor.commit()
    }

    private fun evict(name: String) {
        cache.remove(name)?.prefs?.close()
    }

    private fun secureDir(context: Context): File =
        File(context.filesDir, DIR).also { it.mkdirs() }

    private fun prefsDir(context: Context): File =
        File(context.filesDir.parentFile, "shared_prefs")

    /**
     * Whether [file] is an EncryptedSharedPreferences store rather than a plain
     * XML one.
     *
     * The two look identical for the first byte: ESP keeps its ciphertext in an
     * ordinary `shared_prefs/<name>.xml`, with base64 key names and base64
     * values. What separates them is the keyset ESP embeds under a reserved
     * name. Reading an ESP file through the plain path would copy base64 as if
     * it were a token and then delete the only readable copy, which is a
     * sign-out - so this check runs first and the plain path is the fallback.
     */
    internal fun isEncryptedSharedPreferences(file: File): Boolean = runCatching {
        file.readText().contains(KEYSET_MARKER)
    }.getOrDefault(false)

    /**
     * Whether [file] holds ordinary preference XML. Only consulted for a file
     * this migration has already ruled out as an ESP store.
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

/**
 * The preferences protobuf as a stream, so Tink can wrap it.
 *
 * `PreferencesSerializer` speaks okio and `AeadSerializer` speaks
 * InputStream/OutputStream; this is the seam between them and nothing else.
 * Neither side is closed here - the streams belong to DataStore.
 */
private object PreferencesStreamSerializer : Serializer<Preferences> {

    override val defaultValue: Preferences = emptyPreferences()

    override suspend fun readFrom(input: InputStream): Preferences =
        PreferencesSerializer.readFrom(input.source().buffer())

    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        val sink = output.sink().buffer()
        PreferencesSerializer.writeTo(t, sink)
        sink.flush()
    }
}
