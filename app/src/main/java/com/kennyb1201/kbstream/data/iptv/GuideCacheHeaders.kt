package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * The HTTP validators a guide fetch can be resumed from.
 *
 * Both are nullable because servers differ: some send only an `ETag`, some only
 * a `Last-Modified`, and plenty send neither, in which case there is nothing to
 * store and every refresh stays unconditional (as it always was).
 */
internal data class GuideCacheValidators(
    val etag: String?,
    val lastModified: String?
)

/**
 * Remembers, per guide URL, what the server said the file was last time.
 *
 * A guide is 10-100 MB and a refresh runs every 6h whether or not anything
 * changed, so the download is the expensive half of the refresh. These two
 * strings are what turn that into a `304 Not Modified` with no body at all.
 *
 * Lives in the ACTIVE profile's guide prefs (`iptv_prefs`, the same file
 * [GuideFiles] reads its playlist URLs from), so a profile switch takes its own
 * validators with it: the guide rows are in a profile-scoped database, and a
 * validator that outlived its rows would suppress the import those rows need.
 * That failure mode is why the caller checks the rows exist before acting on a
 * 304 — see [com.kennyb1201.kbstream.data.iptv.IptvRepository.importGuide].
 *
 * Keys carry the URL itself. That is a long key, but it cannot collide and the
 * file already stores playlist URLs; a hash would be shorter and would make a
 * stale entry indistinguishable from a live one.
 */
internal object GuideCacheHeaders {

    private const val ETAG_PREFIX = "guide_etag_"
    private const val LASTMOD_PREFIX = "guide_last_modified_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, GuideFiles.PREFS),
            Context.MODE_PRIVATE
        )

    /** The stored validators for [url], or null when this guide has none yet. */
    fun get(context: Context, url: String): GuideCacheValidators? {
        val prefs = prefs(context)
        val etag = prefs.getString(ETAG_PREFIX + url, null)
        val lastModified = prefs.getString(LASTMOD_PREFIX + url, null)
        if (etag == null && lastModified == null) return null
        return GuideCacheValidators(etag, lastModified)
    }

    /**
     * Records what the server reported for [url].
     *
     * A blank or absent validator REMOVES the stored one rather than storing an
     * empty string: a server that stops sending an `ETag` must stop being asked
     * to match it, and an empty `If-None-Match` is a malformed request.
     */
    fun put(context: Context, url: String, etag: String?, lastModified: String?) {
        val editor = prefs(context).edit()
        if (etag.isNullOrBlank()) {
            editor.remove(ETAG_PREFIX + url)
        } else {
            editor.putString(ETAG_PREFIX + url, etag)
        }
        if (lastModified.isNullOrBlank()) {
            editor.remove(LASTMOD_PREFIX + url)
        } else {
            editor.putString(LASTMOD_PREFIX + url, lastModified)
        }
        editor.apply()
    }

    /** Drops [url]'s validators, so the next fetch is unconditional. */
    fun clear(context: Context, url: String) {
        prefs(context)
            .edit()
            .remove(ETAG_PREFIX + url)
            .remove(LASTMOD_PREFIX + url)
            .apply()
    }
}
