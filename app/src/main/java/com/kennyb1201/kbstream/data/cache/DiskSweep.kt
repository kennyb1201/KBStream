package com.kennyb1201.kbstream.data.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The small, unbounded cache directories — the ones that grow by file COUNT
 * rather than by bytes, which is why the size budget in
 * [TmdbJsonCacheMaintenance] does not cover them.
 *
 * Both of these leaked for the same reason: a cache file named after the
 * CLOCK. `addon_sub_${System.nanoTime()}.srt` can never collide with an
 * earlier download of the same subtitle, and neither can a document pick named
 * `sidecar-${System.nanoTime()}-<name>`, so the same track was re-copied to a
 * new path every time it was used and nothing ever overwrote anything. Files
 * that can never collide can never be reused either, so the naming cost the
 * work twice over — a fresh download and a fresh file each time.
 *
 * [cacheKeyFor] is the fix: a name derived from the SOURCE, so a re-use hits
 * the same path. [sweepSubtitleCache] and [sweepPendingAvatars] are the
 * janitor for what the old naming left behind and for the ordinary case where
 * a file is simply never needed again.
 */
internal object DiskSweep {

    private const val TAG = "DISK_SWEEP"

    /** Shared by the online-download and sidecar subtitle paths. */
    const val SUBTITLE_DIR = "kbstream_subs"

    /**
     * Extensions a subtitle file may carry. Anything else — a query string
     * that ends in a number, an addon that serves `.php` — is stored as `.srt`,
     * which is what the players already assume.
     */
    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ssa", "ass")

    /**
     * Subtitles are re-fetchable in a second and are only needed while an
     * episode is playing, so a week is generous; the count cap is what matters,
     * because a binge of 6 episodes with 8 candidate tracks is 50 files.
     */
    private const val SUBTITLE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val SUBTITLE_MAX_FILES = 200

    /**
     * A picked avatar that was never saved. The save path deletes its pending
     * file, so anything still here after a day was abandoned — the user
     * backed out of the profile editor.
     */
    private const val PENDING_AVATAR_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /** Naming used before the hash: left in the cache root by older builds. */
    private const val LEGACY_ADDON_SUB_PREFIX = "addon_sub_"

    /**
     * A stable, collision-free file name for one subtitle SOURCE.
     *
     * SHA-1 rather than the source string's `hashCode()`: a 32-bit hash
     * collision here would hand the player a DIFFERENT track's subtitles —
     * wrong text under the right show is far worse than a cache that misses.
     * Truncated to 16 hex characters because this is a cache key, not a
     * security boundary.
     */
    fun cacheKeyFor(source: String): String = runCatching {
        MessageDigest.getInstance("SHA-1")
            .digest(source.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
    }.getOrElse { source.hashCode().toString() }

    /** The shared subtitle directory, created if needed. */
    fun subtitleDir(context: Context): File =
        File(context.cacheDir, SUBTITLE_DIR).apply { mkdirs() }

    /** Where a subtitle for [sourceKey] is written. */
    fun targetSubtitleFile(context: Context, sourceKey: String, extension: String): File =
        File(subtitleDir(context), "sub_${cacheKeyFor(sourceKey)}.$extension")

    /**
     * The cache file for [sourceKey], or null when it is not downloaded yet.
     *
     * Reuse is deliberate: the same source always yields the same file, so a
     * subtitle that is still in the cache costs nothing to re-open.
     *
     * A hit is TOUCHED, because age here has to mean "not used since" rather
     * than "written since": a sidecar the user re-opens every week is still in
     * use, and leaving its timestamp alone would let the sweep delete it out
     * from under a playing episode. (The count cap orders by the same stamp,
     * so a file just used is also the last one that cap would reach.)
     */
    fun existingSubtitleFile(context: Context, sourceKey: String, extension: String): File? =
        targetSubtitleFile(context, sourceKey, extension)
            .takeIf { it.isFile && it.length() > 0L }
            ?.also { it.setLastModified(System.currentTimeMillis()) }

    /**
     * Prunes the subtitle cache to [SUBTITLE_MAX_FILES] files and
     * [SUBTITLE_MAX_AGE_MS], and deletes the pre-hash `addon_sub_*` files an
     * older build left in the cache root. Returns how many files went.
     */
    suspend fun sweepSubtitleCache(context: Context): Int = withContext(Dispatchers.IO) {
        var removed = sweepDirectory(
            dir = File(context.cacheDir, SUBTITLE_DIR),
            maxAgeMs = SUBTITLE_MAX_AGE_MS,
            maxFiles = SUBTITLE_MAX_FILES
        )

        // One-off cleanup: those files are never written again (the addon
        // subtitle path now shares [SUBTITLE_DIR]), so every one of them is
        // unreachable rather than merely old — hence no age filter.
        val legacy = context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(LEGACY_ADDON_SUB_PREFIX) }
            .orEmpty()
        legacy.forEach { file -> if (file.delete()) removed++ }

        if (removed > 0) Log.i(TAG, "subtitle cache: removed $removed file(s)")
        removed
    }

    /** Deletes abandoned pending avatars. Returns how many files went. */
    suspend fun sweepPendingAvatars(context: Context): Int = withContext(Dispatchers.IO) {
        val removed = sweepDirectory(
            dir = File(context.cacheDir, "avatars_pending"),
            maxAgeMs = PENDING_AVATAR_MAX_AGE_MS,
            maxFiles = Int.MAX_VALUE
        )
        if (removed > 0) Log.i(TAG, "pending avatars: removed $removed file(s)")
        removed
    }

    /**
     * Deletes every file in [dir] older than [maxAgeMs], then the oldest files
     * beyond [maxFiles].
     *
     * Age first, count second: an install that picked up 300 subtitles in one
     * evening has none of them "old", and the count cap is what keeps that
     * evening bounded. Both are needed — a cap alone would throw away a
     * subtitle from ten minutes ago to keep one from last month.
     *
     * Nothing here is recursive. These directories are flat by construction,
     * and a recursive delete is not something to hand a cache janitor.
     */
    private fun sweepDirectory(dir: File, maxAgeMs: Long, maxFiles: Int): Int {
        if (!dir.isDirectory) return 0
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return 0

        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        val survivors = ArrayList<File>(files.size)
        for (file in files) {
            if (file.lastModified() < cutoff) {
                if (file.delete()) removed++
            } else {
                survivors += file
            }
        }

        if (survivors.size > maxFiles) {
            survivors.sortBy { it.lastModified() }
            survivors.take(survivors.size - maxFiles).forEach { file ->
                if (file.delete()) removed++
            }
        }
        return removed
    }
}
