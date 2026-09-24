package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.kennyb1201.kbstream.ui.settings.AppPreferences

/**
 * The "External player" playback engine: the title is handed to whatever video
 * app the viewer already has (VLC, MX Player, Kodi, ...) instead of being
 * decoded in-app.
 *
 * This file owns the two things that are genuinely device-specific: which apps
 * are installed and can take a stream URL, and the intent that asks one of them
 * to play it. The session itself — progress, scrobbling, the end-of-episode
 * panels — stays ours and lives in
 * [com.kennyb1201.kbstream.ui.player.ExternalPlayerActivity].
 *
 * SCOPE: this is deliberately resolution-side only. Addons can hand back custom
 * request headers (a Referer, a User-Agent) and the de-facto standard extras
 * below are best effort, not a contract — a player that ignores them may refuse
 * a stream this app could have played. That is the trade the setting makes, and
 * the reason ExoPlayer stays the default.
 */
object ExternalPlayer {

    /** One installed app that can play a stream URL. */
    data class Installed(
        val packageName: String,
        val label: String
    )

    /**
     * What we look for: any activity that accepts a video URL. The wildcard
     * video type is wide enough to catch the real players - VLC, MX Player and
     * Kodi all advertise it - without dragging in browsers that only handle
     * `text/html` over `http`.
     */
    private val PROBE_MIME_TYPES = arrayOf(
        "video/*",
        "video/mp4",
        "application/x-mpegURL"
    )

    /**
     * Every installed app that responds to a video VIEW, minus ourselves, with
     * duplicates collapsed: a player that registers several activities (MX
     * Player registers a main one and a Pro one, VLC registers a browser and a
     * player) would otherwise appear several times in the picker.
     *
     * Ordered by label so the picker is stable between launches. Returns an
     * empty list on a box with no such app at all, which is what makes the
     * External engine fall back to ExoPlayer instead of offering a dead button.
     */
    fun installed(context: Context): List<Installed> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>()

        PROBE_MIME_TYPES.forEach { mime ->
            val probe = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("https://example.invalid/kbstream-probe.mp4"), mime)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            // The two overloads cannot share a local: the flags type differs
            // per API level, so each branch calls its own. The deprecated form
            // is what a pre-33 box still resolves against.
            val matches = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(probe, 0)
                }
            }.getOrNull().orEmpty()

            matches.forEach { resolve ->
                val pkg = resolve.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName) return@forEach
                if (found.containsKey(pkg)) return@forEach
                val label = runCatching { resolve.loadLabel(pm).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: pkg
                found[pkg] = label
            }
        }

        return found.entries
            .map { Installed(it.key, it.value) }
            .sortedBy { it.label.lowercase() }
    }

    /** True when this box has at least one app the engine could hand a title to. */
    fun isAvailable(context: Context): Boolean = installed(context).isNotEmpty()

    /**
     * The app to hand the next title to.
     *
     * The remembered choice when it is still installed — a viewer who picked
     * VLC means VLC — otherwise the first installed candidate, so a box that
     * has exactly one player works with no setup at all. Null when nothing is
     * installed.
     */
    fun target(context: Context): Installed? {
        val candidates = installed(context)
        if (candidates.isEmpty()) return null
        val remembered = AppPreferences.getExternalPlayerPackage(context)
        return candidates.firstOrNull { it.packageName == remembered }
            ?: candidates.first()
    }

    /**
     * The label to SHOW for the remembered app, which is not always the app
     * that has to be used: a stored pick that was uninstalled still names
     * itself here, so the settings row can say what the viewer chose.
     */
    fun rememberedLabel(context: Context): String? =
        AppPreferences.getExternalPlayerLabel(context)

    /**
     * True when playback should ask which app to use rather than going straight
     * to [target]: the viewer asked to be prompted, or there is more than one
     * candidate and nothing has been chosen yet.
     */
    fun shouldPrompt(context: Context): Boolean {
        if (AppPreferences.getExternalPlayerAsk(context)) return true
        if (AppPreferences.getExternalPlayerPackage(context) != null) return false
        return installed(context).size > 1
    }

    /**
     * The intent that asks [packageName] to play [url] from [positionMs].
     *
     * The extras are the ones the popular players actually read, which is why
     * they are a short, deliberate list rather than everything anyone has ever
     * passed:
     *
     *  - `title` — the name shown on the player's own now-playing card.
     *  - `position` — resume point in milliseconds (MX Player and VLC both read
     *    this, and both return the same key from `startActivityForResult`).
     *  - `return_result` — asks MX Player to `setResult` when it exits instead
     *    of just finishing, which is how the playhead comes back to us.
     *  - `headers` — the request headers the addon asked for, as "Key: Value"
     *    lines. Not universally understood; players that ignore it simply make
     *    their own request.
     */
    fun launchIntent(
        url: String,
        title: String?,
        positionMs: Long,
        packageName: String?,
        headers: Map<String, String> = emptyMap()
    ): Intent {
        val mime = mimeTypeFor(url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!title.isNullOrBlank()) putExtra("title", title)
            putExtra("position", positionMs.coerceAtLeast(0L).toInt())
            putExtra("return_result", true)
            putExtra("from_start", positionMs < RESUME_MIN_MS)
            if (headers.isNotEmpty()) {
                putExtra(
                    "headers",
                    headers.entries.map { "${it.key}: ${it.value}" }.toTypedArray()
                )
            }
        }
        if (!packageName.isNullOrBlank()) intent.setPackage(packageName)
        return intent
    }

    /**
     * The position an external player reported back, in milliseconds, or null
     * when it reported nothing usable.
     *
     * MX Player and VLC both answer with `position`; the value arrives as an
     * Int from either, but reading it through Bundle.get() covers whichever
     * type a given build uses instead of throwing on a Long.
     */
    fun reportedPositionMs(result: Intent?): Long? {
        val extras = result?.extras ?: return null
        val raw = runCatching { extras.get("position") }.getOrNull() ?: return null
        val ms = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is String -> raw.toLongOrNull()
            else -> null
        }
        return ms?.takeIf { it >= 0L }
    }

    /** The duration an external player reported back, in milliseconds. */
    fun reportedDurationMs(result: Intent?): Long? {
        val extras = result?.extras ?: return null
        val raw = runCatching { extras.get("duration") }.getOrNull() ?: return null
        val ms = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is String -> raw.toLongOrNull()
            else -> null
        }
        return ms?.takeIf { it > 0L }
    }

    /**
     * The MIME type handed to the external app, from the URL's extension.
     *
     * Players gate on this: MX Player and VLC both refuse an `ACTION_VIEW` whose
     * type does not match what they expect, and a Stremio addon URL very often
     * carries no useful extension at all (a bare `/stream/xyz`). Hence the
     * wildcard video type as the fallback, which every player accepts.
     */
    fun mimeTypeFor(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXTENSION[extension] ?: "video/*"
    }

    /** Below this a "resume" is really a restart, so the intent says so. */
    private const val RESUME_MIN_MS = 10_000L

    private val MIME_BY_EXTENSION = mapOf(
        // Live TV and any adaptive stream an addon hands back: the player has
        // to be told these are playlists, not files, or it may refuse them
        // outright (which is what the wrapper's refused-stream card reports).
        "m3u8" to "application/x-mpegURL",
        "m3u" to "application/x-mpegURL",
        "mpd" to "application/dash+xml",
        "mkv" to "video/x-matroska",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv",
        "asf" to "video/x-ms-asf",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "m2v" to "video/mpeg",
        "3gp" to "video/3gpp",
        "ogv" to "video/ogg",
        "rmvb" to "application/vnd.rn-realmedia-vbr",
        "strm" to "video/*"
    )
}
