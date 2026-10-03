package com.kennyb1201.kbstream.data.addon

import android.util.Log
import com.kennyb1201.kbstream.data.cache.DiskSweep
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * The on-disk tail of the add-on catalog cache.
 *
 * The in-memory cache in [AddonRepository] lives only for the process, so a
 * cold Home had nothing to paint a slow add-on's rails from and had to wait on
 * the add-on's own server — which for one field add-on was ~22 s, right against
 * the request timeout that would have dropped the rail outright. This keeps the
 * last page each catalog returned across restarts, so a cold Home can paint it
 * at once and refresh behind it.
 *
 * One small JSON file per catalog page, named from the page's cache key rather
 * than the clock so a re-use hits the same path. Written through a `.tmp` file
 * and renamed into place, so a write torn by process death leaves the previous
 * snapshot intact rather than a half-written one. Bounded by
 * [DiskSweep.sweepAddonCatalogSnapshots], which is the janitor for it.
 *
 * Deliberately best-effort throughout: a missing store, a full disk or a
 * corrupt file is silence, never an error the rail build can see — every caller
 * already has the network path to fall back on.
 */
internal object AddonCatalogSnapshotStore {

    private const val TAG = "ADDON_CATALOG_SNAPSHOT"

    /** One catalog page as it was served, with the stamp it was cached at. */
    @JsonClass(generateAdapter = true)
    internal data class CatalogSnapshot(
        val cachedAtMs: Long,
        val metas: List<MetaPreview>
    )

    private val adapter =
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(CatalogSnapshot::class.java)

    /** Where the snapshot for [cacheKey] lives under [dir]. */
    fun fileIn(dir: File, cacheKey: String): File =
        File(dir, "${DiskSweep.cacheKeyFor(cacheKey)}.json")

    /**
     * Reads the snapshot for [cacheKey] from [dir], or null when it is absent,
     * unreadable, or older than [DiskSweep.ADDON_CATALOG_MAX_AGE_MS].
     */
    fun loadFrom(dir: File, cacheKey: String): CatalogSnapshot? {
        val file =
            fileIn(dir, cacheKey)

        if (!file.isFile) {
            return null
        }

        val snapshot =
            runCatching {
                adapter.fromJson(file.readText())
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "unreadable snapshot ${file.name}: ${error.message}"
                )
            }.getOrNull()
                ?: return null

        // A next-day launch still gets something to paint with, but past this a
        // snapshot is stale enough to mislead rather than help.
        if (
            System.currentTimeMillis() - snapshot.cachedAtMs >
            DiskSweep.ADDON_CATALOG_MAX_AGE_MS
        ) {
            return null
        }

        return snapshot
    }

    /** Writes a snapshot for [cacheKey] into [dir]. Best effort. */
    fun saveTo(dir: File, cacheKey: String, metas: List<MetaPreview>, cachedAtMs: Long) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            return
        }

        val target =
            fileIn(dir, cacheKey)

        val temp =
            File(dir, "${target.name}.tmp")

        runCatching {
            temp.writeText(
                adapter.toJson(
                    CatalogSnapshot(
                        cachedAtMs = cachedAtMs,
                        metas = metas
                    )
                )
            )

            if (!temp.renameTo(target)) {
                temp.delete()
            }
        }.onFailure { error ->
            Log.w(
                TAG,
                "snapshot write failed for $cacheKey: ${error.message}"
            )
        }
    }

    /** The snapshot directory under the app cache, or null before startup. */
    private fun cacheDir(): File? =
        AppContextHolder.appContext
            ?.cacheDir
            ?.let { cacheRoot ->
                File(cacheRoot, DiskSweep.ADDON_CATALOG_DIR)
            }

    /** The snapshot for [cacheKey], using the app's cache directory. */
    fun load(cacheKey: String): CatalogSnapshot? =
        cacheDir()?.let { dir -> loadFrom(dir, cacheKey) }

    /** Persists the snapshot for [cacheKey] to the app's cache directory. */
    fun save(cacheKey: String, metas: List<MetaPreview>, cachedAtMs: Long) {
        cacheDir()?.let { dir ->
            saveTo(dir, cacheKey, metas, cachedAtMs)
        }
    }
}
