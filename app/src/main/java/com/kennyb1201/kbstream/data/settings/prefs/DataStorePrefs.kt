package com.kennyb1201.kbstream.data.settings.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Every preference store in the app, moved onto DataStore without moving the
 * callers.
 *
 * The app reads and writes preferences through the `SharedPreferences` API in
 * about a thousand places - `getBoolean`, `edit().putString(..).apply()`, and
 * so on - and every one of those lines stays exactly as it is. What changes is
 * where the file lives and how it is written: [file] hands back a
 * [SharedPreferences] whose storage is a DataStore file
 * (`files/datastore/<name>.preferences_pb`) instead of
 * `shared_prefs/<name>.xml`.
 *
 * ## Why a shim and not a rewrite
 *
 * A `SharedPreferences`-shaped store is synchronous by contract: `getString`
 * returns a value, not a `Flow`, and `commit()` reports whether the write
 * landed. DataStore is the opposite - every read and write is suspending. The
 * shim bridges the two by keeping the decoded preference set in memory: reads
 * are served from that snapshot, `apply()` updates it and queues the disk write,
 * and `commit()` updates it and blocks until the queue has written that batch.
 * The snapshot is loaded (and the XML migrated) the first time a store is
 * touched, so a read before [Prefs.warm] has warmed it still returns the right
 * value - it just pays for the load on the calling thread, exactly as opening a
 * `SharedPreferences` file always did.
 *
 * ## The one-time migration
 *
 * [SharedPreferencesMigration] imports every key from the existing
 * `shared_prefs/<name>.xml` into the DataStore file on its first open and
 * deletes the emptied XML. Store NAMES are unchanged, because profile scoping
 * (`ProfileStorage.prefsName`), profile deletion
 * (`ProfileManager.namespaces`) and backups all address these stores by name;
 * only the file format moves.
 *
 * ## Identity and lifetime
 *
 * DataStore refuses to let two instances own one file, so stores are cached per
 * file path for the life of the process ([Prefs.file] returns the same instance
 * for the same name). [Prefs.delete] cancels that instance before removing the
 * file, which is what keeps the "delete a profile, then recreate it with the
 * same id" path working.
 */

private const val TAG = "PREF"

/** Writes are queued in call order; one consumer applies them one at a time. */
private class Write(
    val values: Map<String, Any?>,
    val clear: Boolean,
    val done: CompletableDeferred<Boolean>?,
)

/**
 * A [SharedPreferences] view over one DataStore preferences file.
 *
 * Internal on purpose: callers get theirs from [Prefs.file], which also owns the
 * one-instance-per-file rule.
 */
internal class DataStorePrefs(
    context: Context,
    private val name: String,
    file: File,
    parent: CoroutineScope,
) : SharedPreferences {

    /** Own job so [close] can release the file for a later instance. */
    private val job = SupervisorJob(parent.coroutineContext[Job])

    private val scope = CoroutineScope(parent.coroutineContext + job)

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(context, name)),
        scope = scope,
        produceFile = { file },
    )

    private val listeners =
        CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    private val writes = Channel<Write>(Channel.UNLIMITED)

    private val loaded = AtomicBoolean(false)

    /**
     * The decoded store. Replaced wholesale, never mutated in place, so a read
     * on any thread sees one consistent map.
     */
    @Volatile
    private var snapshot: Map<String, Any?> = emptyMap()

    init {
        scope.launch { drainWrites() }
    }

    // ── Reads ────────────────────────────────────────────────────────

    override fun getAll(): Map<String, *> {
        ensureLoaded()
        return HashMap(snapshot)
    }

    override fun getString(key: String?, defValue: String?): String? =
        stored(key) as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        val values = stored(key) as? Set<*> ?: return defValues
        return LinkedHashSet(values.filterIsInstance<String>())
    }

    override fun getInt(key: String?, defValue: Int): Int = stored(key) as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = stored(key) as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        stored(key) as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        stored(key) as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = stored(key) != null

    private fun stored(key: String?): Any? {
        if (key == null) return null
        ensureLoaded()
        return snapshot[key]
    }

    // ── Writes ───────────────────────────────────────────────────────

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener == null) return
        listeners.addIfAbsent(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener == null) return
        listeners.remove(listener)
    }

    /** One staged batch; nothing is visible until `apply`/`commit`. */
    private inner class Editor : SharedPreferences.Editor {

        private val values = LinkedHashMap<String, Any?>()

        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            stage(key, value)

        override fun putStringSet(
            key: String?,
            value: MutableSet<String>?,
        ): SharedPreferences.Editor = stage(key, value?.let { LinkedHashSet(it) })

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            stage(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            stage(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            stage(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            stage(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = stage(key, null)

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            clear = true
            return this
        }

        override fun commit(): Boolean {
            val done = CompletableDeferred<Boolean>()
            submit(values.toMap(), clear, done)
            return runBlocking { done.await() }
        }

        override fun apply() {
            submit(values.toMap(), clear, null)
        }

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) values[key] = value
            return this
        }
    }

    /**
     * Publishes [values] to the snapshot immediately (so the caller's next read
     * sees its own write, as `SharedPreferences` promises), then hands the batch
     * to the writer.
     */
    private fun submit(values: Map<String, Any?>, clear: Boolean, done: CompletableDeferred<Boolean>?) {
        ensureLoaded()
        val changed = synchronized(this) {
            val next = if (clear) LinkedHashMap() else LinkedHashMap(snapshot)
            values.forEach { (key, value) ->
                if (value == null) next.remove(key) else next[key] = value
            }
            snapshot = next
            values.keys
        }
        if (clear) {
            writes.trySend(Write(values, true, done))
            notifyListeners(snapshot.keys + values.keys)
            return
        }
        writes.trySend(Write(values, false, done))
        notifyListeners(changed)
    }

    /** Applies queued batches in order, one at a time. */
    private suspend fun drainWrites() {
        for (write in writes) {
            val ok = try {
                store.edit { prefs ->
                    if (write.clear) prefs.clear()
                    write.values.forEach { (key, value) ->
                        if (value == null) {
                            remove(prefs, key)
                        } else {
                            prefs.putValue(key, value)
                        }
                    }
                }
                true
            } catch (cancellation: CancellationException) {
                // [close] cancels this job; a caller already blocked in
                // commit() must be released rather than wait for a writer that
                // is never coming back.
                write.done?.complete(false)
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "could not write '$name': ${error.message}")
                false
            }
            // The in-memory snapshot is the only copy a reader trusts; if the
            // write failed, re-read the store so memory stops disagreeing with
            // disk instead of silently claiming a value that was never saved.
            if (!ok) reload()
            write.done?.complete(ok)
        }
    }

    private fun remove(prefs: MutablePreferences, key: String) {
        prefs -= stringPreferencesKey(key)
        prefs -= intPreferencesKey(key)
        prefs -= longPreferencesKey(key)
        prefs -= floatPreferencesKey(key)
        prefs -= booleanPreferencesKey(key)
        prefs -= stringSetPreferencesKey(key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.putValue(key: String, value: Any) {
        val typed = when (value) {
            is String -> stringPreferencesKey(key)
            is Int -> intPreferencesKey(key)
            is Long -> longPreferencesKey(key)
            is Float -> floatPreferencesKey(key)
            is Boolean -> booleanPreferencesKey(key)
            is Set<*> -> stringSetPreferencesKey(key) as Preferences.Key<Any>
            else -> stringPreferencesKey(key)
        }
        val stored = if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
        this[typed as Preferences.Key<Any>] = stored
    }

    private fun notifyListeners(keys: Set<String>) {
        if (keys.isEmpty() || listeners.isEmpty()) return
        val delivery = Runnable {
            listeners.forEach { listener ->
                keys.forEach { listener.onSharedPreferenceChanged(this, it) }
            }
        }
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) {
            delivery.run()
        } else {
            Handler(main).post(delivery)
        }
    }

    // ── Loading ──────────────────────────────────────────────────────

    /** Blocks until the store has been decoded, migrations included. */
    internal fun preload() {
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            snapshot = readStore()
            loaded.set(true)
        }
    }

    private fun reload() {
        val reread = readStore()
        synchronized(this) {
            snapshot = reread
        }
    }

    private fun readStore(): Map<String, Any?> {
        val decoded = try {
            runBlocking { store.data.first() }
        } catch (e: Exception) {
            // A store that cannot be read must not take the caller down with
            // it: a preference read is never worth a crash, and the next write
            // recreates the file.
            Log.w(TAG, "could not read '$name': ${e.message}")
            return emptyMap()
        }
        return decoded.asMap().entries.associate { (key, value) -> key.name to value }
    }

    internal fun close() {
        job.cancel()
    }
}

/**
 * The registry: one [DataStorePrefs] per store name per process.
 *
 * Stores are keyed by the resolved DataStore file path rather than by name, so
 * two `Context`s with different data directories never share a store, and the
 * same `Context` never opens one file twice. An Application that is not the one
 * that opened a store (a test runner hands out a new one per test) replaces it
 * instead of inheriting it - see [Open].
 */
object Prefs {

    private const val DIR = "datastore"

    private const val SUFFIX = ".preferences_pb"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One open store per file, plus the Application that opened it.
     *
     * The owner is not bookkeeping: a process has exactly one Application, but a
     * test runner hands out a new one per test while reusing the data directory.
     * The cached store would then serve the previous test's snapshot, and
     * DataStore will not let a second owner open the file the old one still
     * holds - so a different owner means the old store is closed and reopened.
     */
    private class Open(val prefs: DataStorePrefs, val owner: Context)

    private val stores = ConcurrentHashMap<String, Open>()

    /** The store called [name], opened (and its legacy XML migrated) on first read. */
    fun file(context: Context, name: String): SharedPreferences = instance(context, name)

    internal fun instance(context: Context, name: String): DataStorePrefs {
        val app = context.applicationContext
        val target = fileFor(app, name)
        val key = target.absolutePath
        stores[key]?.let { if (it.owner === app) return it.prefs }
        return synchronized(stores) {
            val existing = stores[key]
            if (existing != null && existing.owner === app) {
                existing.prefs
            } else {
                existing?.prefs?.close()
                DataStorePrefs(
                    context = app,
                    name = name,
                    file = target,
                    parent = scope,
                ).also { stores[key] = Open(it, app) }
            }
        }
    }

    /**
     * Decodes [names] off the main thread so the first read of a store the UI
     * draws from does not have to.
     */
    fun warm(context: Context, vararg names: String) {
        val app = context.applicationContext
        names.forEach { name ->
            scope.launch { runCatching { instance(app, name).preload() } }
        }
    }

    /** Warms every store this device has, migrated or not. */
    fun warmAll(context: Context) {
        warm(context, *names(context).toTypedArray())
    }

    /**
     * Store names present on this device, in either format. A name appears once
     * whether its data is still in `shared_prefs/<name>.xml` or already in
     * `files/datastore/<name>.preferences_pb`.
     */
    fun names(context: Context): List<String> {
        val app = context.applicationContext
        val datastore = File(app.filesDir, DIR)
            .listFiles()
            ?.mapNotNull { file ->
                file.name.takeIf { it.endsWith(SUFFIX) }?.removeSuffix(SUFFIX)
            }
            .orEmpty()
        val legacy = File(app.filesDir.parentFile, "shared_prefs")
            .listFiles()
            ?.filter { it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            .orEmpty()
        return (datastore + legacy).distinct()
    }

    /**
     * Drops store [name] and its file. Cancels the instance first: DataStore
     * refuses to hand the same file to a second owner, and a profile recreated
     * with the same id must get a new, empty store rather than the deleted
     * one's cached contents.
     */
    fun delete(context: Context, name: String): Boolean {
        val target = fileFor(context, name)
        synchronized(stores) {
            stores.remove(target.absolutePath)?.prefs?.close()
        }
        return runCatching { target.delete() }.getOrDefault(false)
    }

    private fun fileFor(context: Context, name: String): File =
        File(File(context.applicationContext.filesDir, DIR), "$name$SUFFIX")
}
