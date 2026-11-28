package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import com.kennyb1201.kbstream.data.settings.prefs.Prefs
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * The durable side of [GuideImportCheckpoint], in the same profile-scoped
 * `iptv_prefs` file that already holds [GuideCacheHeaders] and
 * [GuideSourceStamps].
 *
 * One key per source, because that is the unit a checkpoint belongs to: a
 * stopped pass may have landed one source and died inside another, and the
 * second one's rows are the ones worth keeping. Being profile-scoped is the
 * same argument the validators have — a checkpoint that outlived the rows it
 * describes would skip into a guide whose database no longer holds them.
 *
 * The encoding and the validity rules live in [GuideImportCheckpointCodec] and
 * [resumeCheckpoint], so they are tested on the JVM; this object only binds
 * them to prefs.
 */
internal object GuideImportCheckpointStore {

    internal const val KEY_PREFIX = "epg_import_checkpoint_"

    private fun prefs(context: Context) =
        Prefs.file(context, ProfileStorage.prefsName(context, GuideFiles.PREFS))

    fun get(context: Context, url: String): GuideImportCheckpoint? =
        prefs(context).getString(KEY_PREFIX + url, null)
            ?.let(GuideImportCheckpointCodec::decode)

    /**
     * A sink that records [GuideImportCheckpoint]s for one source as the import
     * flushes, anchored to [createdAtMs] so repeated writes keep the attempt's
     * own window rather than drifting with each batch.
     */
    fun sink(
        context: Context,
        url: String,
        windowStartMs: Long,
        windowEndMs: Long,
        etag: String?,
        lastModified: String?,
        matchKeysFingerprint: String,
        createdAtMs: Long = System.currentTimeMillis()
    ): GuideImportCheckpointSink = GuideImportCheckpointSink { channels, programs, bytesRead ->
        put(
            context,
            url,
            GuideImportCheckpoint(
                createdAtMs = createdAtMs,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                stagedChannels = channels,
                stagedPrograms = programs,
                bytesRead = bytesRead,
                etag = etag,
                lastModified = lastModified,
                matchKeysFingerprint = matchKeysFingerprint
            )
        )
    }

    fun put(context: Context, url: String, checkpoint: GuideImportCheckpoint) {
        prefs(context).edit()
            .putString(KEY_PREFIX + url, GuideImportCheckpointCodec.encode(checkpoint))
            .apply()
    }

    /** Drops [url]'s checkpoint: the import completed, or it is no longer wanted. */
    fun clear(context: Context, url: String) {
        prefs(context).edit().remove(KEY_PREFIX + url).apply()
    }
}
