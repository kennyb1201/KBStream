package com.kennyb1201.kbstream.ui.home

import android.content.Context
import com.kennyb1201.kbstream.data.settings.prefs.Prefs

/**
 * The CATALOG rails as the last completed build left them (spec 2026-11-21,
 * Home load F2).
 *
 * Pinned rails already painted from a snapshot ([HomePinnedSnapshot]); the
 * catalog rows did not. They were fetched live every launch and streamed into
 * their slots as they resolved, so the screen was visibly incomplete for the
 * first seconds of every launch: `firstRail=1968ms` / `railsReady=4401ms` with
 * `catalogFetch=4342ms` on the 0.9.440 paste.
 *
 * This is the same discipline as the pinned snapshot, deliberately reused
 * rather than reimplemented: per profile (and kids ceiling), the same hand-rolled
 * codec, and the same 40-item cap per rail. A catalog set can be longer than the
 * pinned batch's twelve rails, so it gets its own (larger) rail bound.
 *
 * Paint it, then run the normal fan-out behind it. A fresh rail replaces its
 * snapshot slot in place; a rail whose refresh fails keeps its snapshot row for
 * the session, because stale beats empty. The file is rewritten only from a
 * COMPLETED fresh set, after that set's verdicts have answered - so the next
 * launch cannot paint an un-released title the filter would have hidden.
 */
internal object HomeCatalogSnapshot {

    private const val PREFS = "kbstream_home_catalog_snapshot"

    /**
     * At most this many catalog rails. Larger than the pinned batch's cap because
     * a viewer with many add-ons can have more catalogs than Home has pinned
     * "Top ... Today" rows; still bounded so the blob cannot grow without end.
     */
    private const val MAX_RAILS = 32

    /** The scope key: one profile's ceiling is the other's poison. */
    fun scopeOf(profileId: String, kidsCeiling: Boolean): String =
        "$profileId|kids=$kidsCeiling"

    /** The rails last written for [scope], or empty when there is none. */
    fun load(context: Context, scope: String): List<Rail> {
        val blob = runCatching {
            Prefs.file(context, PREFS)
                .getString("scope:" + scope, null)
        }.getOrNull() ?: return emptyList()
        return decode(blob)
    }

    /**
     * Writes [rails] as the scope's paint-first set. Unlike the pinned snapshot,
     * an EMPTY set clears the row: the viewer removing every catalog is a
     * completed fresh set, and repainting the old rows next launch would show
     * catalogs they no longer have.
     */
    fun save(context: Context, scope: String, rails: List<Rail>) {
        runCatching {
            val editor = Prefs.file(context, PREFS).edit()
            if (rails.isEmpty()) {
                editor.remove("scope:" + scope)
            } else {
                editor.putString("scope:" + scope, encode(rails))
            }
            editor.apply()
        }
    }

    // ── The blob, pure so a JVM test can round-trip it ────────────────────

    fun encode(rails: List<Rail>): String =
        HomePinnedSnapshot.encode(rails, maxRails = MAX_RAILS)

    fun decode(blob: String?): List<Rail> = HomePinnedSnapshot.decode(blob)
}
