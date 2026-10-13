package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.net.URLEncoder

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
        val label: String,
        /**
         * The scheme this app has to be handed through instead of a plain
         * video VIEW, or null for a player that answers a normal VIEW (which
         * is every player found by [PROBE_MIME_TYPES]).
         *
         * Apps like this register NO `ACTION_VIEW` filter for video MIME types
         * at all - they publish a private URL scheme and expect to be called
         * through it - so they can never turn up in a mime probe, however wide
         * it is. See [SCHEME_PLAYERS] for the one that matters today.
         */
        val scheme: String? = null
    )

    /**
     * What we look for: any activity that accepts a video URL. The wildcard
     * video type catches the players that advertise it - VLC, MX Player and
     * Kodi all do - without dragging in browsers that only handle `text/html`
     * over `http`. The specific types are for the apps that instead list only
     * the containers and playlist formats they take, which the video wildcard
     * alone does not match: a player that registers a bare `.m3u8` handler is
     * still a player, and the viewer expects it in the picker.
     *
     * The manifest's <queries> element declares the same intents, because
     * Android 11+ filters this query by what the app is allowed to see.
     */
    private val PROBE_MIME_TYPES = arrayOf(
        "video/*",
        "video/mp4",
        "video/x-matroska",
        "video/mp2t",
        "application/x-mpegURL",
        "application/vnd.apple.mpegurl",
        "application/dash+xml"
    )

    /**
     * Packages that answer the video probe without being video players.
     *
     * A file manager registers `ACTION_VIEW` for every file type there is, the
     * video wildcard included, so it matches a video probe by construction and
     * turned up in the external-player picker as an app to hand a title to.
     * Picking it handed the stream to a file browser.
     *
     * The list is exact because the general rule ([FILE_MANAGER_LABEL_KEYWORDS])
     * cannot cover every one of them: "File Manager +", "CX File Explorer" and
     * "ES File Explorer" name themselves, but a manager is free to call itself
     * anything. Add reported packages here.
     */
    private val FILE_MANAGER_BLOCKLIST = setOf(
        "com.marc.files",                 // File Manager +
        "com.alphainventor.filemanager",  // File Manager
        "com.cx.fileexplorer",            // CX File Explorer
        "com.estrongs.android.pop",       // ES File Explorer
        // Files by Google: registered for every file type too, and its own
        // label ("Files") is only one word, so the keyword rule below catches it
        // as well - it is here because a launcher may localize that label.
        "com.google.android.apps.nbu.files"
    )

    /**
     * What a file manager calls itself, matched case-insensitively against the
     * resolved label. The general half of the filter: any current or future
     * manager whose own name says what it is is dropped without needing an
     * entry in the blocklist.
     */
    private val FILE_MANAGER_LABEL_KEYWORDS = listOf(
        "file manager", "file explorer", "files"
    )

    /**
     * Real players, which the label rule must never drop.
     *
     * The escape hatch the keyword list needs: a player is free to carry a name
     * the rule would otherwise read as a manager's (a build badged "… Files"),
     * and removing a player the viewer can actually play with is a worse
     * failure than listing one entry too many. The probe found these through a
     * video MIME filter, which is the evidence that they are players - the
     * label is only a heuristic on top of it.
     */
    private val KNOWN_VIDEO_PLAYERS = setOf(
        "org.videolan.vlc",              // VLC
        "com.mxtech.videoplayer.ad",     // MX Player
        "com.mxtech.videoplayer.pro",    // MX Player Pro
        "org.xbmc.kodi",                 // Kodi
        "com.brouken.player",            // Just Player
        "com.vimu.player",               // Vimu
        "com.plexapp.android",           // Plex
        "org.jellyfin.androidtv",        // Jellyfin (TV)
        "org.jellyfin.mobile",           // Jellyfin (mobile)
        "com.archos.mediacenter.video"   // Archos Video Player
    )

    /**
     * True when a probe match is a file manager rather than a video player.
     *
     * The blocklist is checked first and is not escapable - a package named
     * here is not a player, whatever it is called. The label rule is applied
     * to everything else, minus [KNOWN_VIDEO_PLAYERS], which is checked before
     * it because a manager's own name can be anything.
     *
     * Pure, so the rule is pinned by a unit test instead of by a device with
     * four file managers installed.
     */
    internal fun isFileManager(packageName: String, label: String): Boolean {
        if (packageName in FILE_MANAGER_BLOCKLIST) return true
        if (packageName in KNOWN_VIDEO_PLAYERS) return false
        return FILE_MANAGER_LABEL_KEYWORDS.any { label.contains(it, ignoreCase = true) }
    }

    /**
     * A player that is reachable only through its own URL scheme.
     *
     * [probe] is the exact request URI from the player's own integration docs,
     * used as an `ACTION_VIEW` data URI: whatever app resolves it is that
     * player, so this needs no package name and cannot break when the package
     * is renamed or the app is sideloaded under a variant id. It does need a
     * matching `<queries>` entry in the manifest, because Android 11+ hides
     * exactly these resolvers from the app.
     */
    private data class SchemePlayer(val scheme: String, val probe: String)

    /**
     * Known scheme-only players.
     *
     * VidHub is the case that matters: on Android and Android TV it registers
     * no `ACTION_VIEW` video filter, so it never appeared in the external-player
     * picker however many MIME types were probed - the app that a viewer most
     * wants to hand a title to was invisible to the engine. It publishes
     * `open-vidhub://x-callback-url/play` for third-party integration instead
     * (see https://vidhub.okaapps.com/3rd-party-app-integration/), which is
     * what this probes for and what [launchIntent] builds.
     */
    private val SCHEME_PLAYERS = listOf(
        SchemePlayer(scheme = "open-vidhub", probe = VIDHUB_PLAY_PROBE)
    )

    /** VidHub's documented request URI, and the scheme it is addressed with. */
    internal const val VIDHUB_SCHEME = "open-vidhub"
    internal const val VIDHUB_PLAY_PROBE = "open-vidhub://x-callback-url/play"

    /** The scheme registered by the apps named in [SCHEME_PLAYERS]. */
    internal val schemePlayerSchemes: List<String> = SCHEME_PLAYERS.map { it.scheme }

    /**
     * Every installed app that can be handed a stream, minus ourselves, with
     * duplicates collapsed: a player that registers several activities (MX
     * Player registers a main one and a Pro one, VLC registers a browser and a
     * player) would otherwise appear several times in the picker.
     *
     * Two kinds are found, which is why the picker needs no knowledge of
     * either: apps that answer a video VIEW (the MIME probes above) and
     * scheme-only apps that answer nothing else (see [SCHEME_PLAYERS]). Each
     * entry records which kind it is, because the hand-off differs.
     *
     * Ordered by label so the picker is stable between launches. Returns an
     * empty list on a box with no such app at all, which is what makes the
     * External engine fall back to ExoPlayer instead of offering a dead button.
     */
    fun installed(context: Context): List<Installed> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Installed>()

        /**
         * One match, deduped by package. A player that answers BOTH ways -
         * a normal video VIEW and its own scheme - keeps the plain VIEW: the
         * VIEW is the path every player's integration is written against and
         * the one whose extras are standardized, so the scheme is only the
         * fallback for a player that has nothing else.
         */
        fun remember(pkg: String, label: String, scheme: String?) {
            if (found.containsKey(pkg)) return
            if (isStubLabel(pkg, label)) return
            found[pkg] = Installed(pkg, label, scheme)
        }

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
                val label = labelFor(resolve, pm)
                // A file manager answers this probe for every file type it
                // indexes (they all register `*/*`), which is how one came to
                // be offered as a player. The scheme probe below is not filtered
                // the same way: a private URL scheme is not something a file
                // manager registers.
                if (isFileManager(pkg, label)) return@forEach
                remember(pkg, label, scheme = null)
            }
        }

        // Scheme-only players: nothing above can see them, because they
        // register no video MIME filter at all.
        SCHEME_PLAYERS.forEach { player ->
            schemeMatches(pm, player.probe).forEach { resolve ->
                val pkg = resolve.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName) return@forEach
                remember(pkg, labelFor(resolve, pm), scheme = player.scheme)
            }
        }

        return found.values.sortedBy { it.label.lowercase() }
    }

    /**
     * Every installed app the viewer could open, TV and phone alike: what the
     * "choose custom app" picker lists.
     *
     * [installed] only finds apps that ANSWER a video request, which is exactly
     * what a TV build of a player may not do. VidHub is the case that raised
     * this: the phone APK declares the `ACTION_VIEW` video filter and was found,
     * while the Play Store TV APK - same app, different manifest - declares
     * none, so no probe of any width could ever see it and the viewer had no way
     * to say "use this one". This is that way.
     *
     * "Has a launcher entry" is the honest definition of an app someone can
     * pick, and it is also the cheap one: it needs no `QUERY_ALL_PACKAGES`
     * (Play-restricted), only the LAUNCHER / LEANBACK_LAUNCHER declarations in
     * the manifest, and a player nobody can open is not one anybody can use.
     * Both categories are queried because a TV build usually registers only the
     * leanback one.
     *
     * Ourselves and label-less stubs are dropped by the same rules the probe
     * uses; unlike the probe, nothing here is filtered by [isFileManager] - this
     * list is "any app", and a pick is the viewer's to make.
     */
    fun allApps(context: Context): List<Installed> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Installed>()
        APP_CHOICE_CATEGORIES.forEach { category ->
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val matches = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            }.getOrNull().orEmpty()

            matches.forEach { resolve ->
                val info = resolve.activityInfo ?: return@forEach
                val pkg = info.packageName
                if (pkg == context.packageName) return@forEach
                if (found.containsKey(pkg)) return@forEach
                val label = appLabel(info.applicationInfo, pm, resolve, pkg)
                if (isStubLabel(pkg, label)) return@forEach
                found[pkg] = Installed(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /**
     * The app the viewer picked by hand, or null when none is set or the pick is
     * no longer installed.
     *
     * Stored separately from the probe's pick on purpose: clearing a custom
     * choice has to fall back to detection, not forget both. A package that has
     * since been uninstalled resolves to null, which is the whole rollback - the
     * probe takes over again with no prompt and no dead entry in Settings.
     */
    private fun customPlayer(context: Context): Installed? {
        val pkg = AppPreferences.getCustomExternalPlayerPackage(context) ?: return null
        val pm = context.packageManager
        // getApplicationInfo, not getLaunchIntentForPackage: the pick is the
        // viewer's, and a chosen app with no launcher entry is still one they
        // can expect to be used (getLaunchIntentForPackage answers null for it).
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val label = AppPreferences.getCustomExternalPlayerLabel(context)
            ?: appLabel(info, pm, resolve = null, packageName = pkg)
        return Installed(pkg, label)
    }

    /**
     * The launcher categories [allApps] asks for: the phone one and the TV one.
     * A TV build usually registers only the leanback category, so leaving that
     * one out would lose exactly the app the picker exists for.
     */
    private val APP_CHOICE_CATEGORIES = arrayOf(
        Intent.CATEGORY_LAUNCHER,
        Intent.CATEGORY_LEANBACK_LAUNCHER
    )

    /**
     * The name to show for an app: the app's own label first, then its resolved
     * activity's, then the package name - never blank, so a row in the picker
     * always reads as something.
     */
    private fun appLabel(
        info: android.content.pm.ApplicationInfo?,
        pm: PackageManager,
        resolve: android.content.pm.ResolveInfo?,
        packageName: String
    ): String {
        info?.let { app ->
            runCatching { pm.getApplicationLabel(app).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        resolve?.let { matched -> return labelFor(matched, pm) }
        return packageName
    }

    /**
     * A resolved activity's own label, falling back to its package name.
     */
    private fun labelFor(
        resolve: android.content.pm.ResolveInfo,
        pm: PackageManager
    ): String = runCatching { resolve.loadLabel(pm).toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: resolve.activityInfo?.packageName.orEmpty()

    /**
     * True when the "label" is the component's own class name rather than a
     * human name - the entry is a stub that answered the probe (Amazon's dummy
     * gallery shows up as "amazon.dummy.gallery.DummyGalleryActivity"), not a
     * player the viewer could meaningfully choose.
     *
     * The probe cannot tell a stub from a player, but the label can, and no
     * hardcoded package exception is needed: any current or future stub with no
     * real label is dropped by the same rule. A label the app actually set is
     * never prefixed this way, and a bare package name - [labelFor]'s fallback
     * for an app with no label at all - does not match either.
     */
    internal fun isStubLabel(packageName: String, label: String): Boolean =
        label.startsWith("$packageName.")

    /**
     * The apps that resolve [probe], queried with NO categories.
     *
     * Deliberately category-free: an intent's categories must all appear in a
     * filter for it to match, so asking with none matches the widest set of
     * filters - including a player whose own filter omits `CATEGORY_DEFAULT`.
     * The mime probes above ask with DEFAULT because that is how the hand-off
     * launches a plain VIEW; a scheme player is launched without it (see
     * [launchIntent]), so discovery and launch agree.
     */
    private fun schemeMatches(
        pm: PackageManager,
        probe: String
    ): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse(probe))
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrNull().orEmpty()
    }

    /**
     * True when this box has at least one app the engine could hand a title to:
     * a probe match, or a pick the viewer made by hand.
     *
     * The custom pick counts, and has to: [PlayerEngine.selected] falls back to
     * ExoPlayer when this is false, so a box whose detection found nothing - the
     * case the custom pick exists for - would otherwise never reach the engine
     * the viewer just configured.
     */
    fun isAvailable(context: Context): Boolean =
        customPlayer(context) != null || installed(context).isNotEmpty()

    /**
     * The app to hand the next title to.
     *
     * A custom pick wins outright: it is the viewer saying which app to use when
     * the probe could not work it out, so it is the most specific answer there
     * is. Otherwise the remembered choice when it is still installed - a viewer
     * who picked VLC means VLC - and otherwise the first installed candidate, so
     * a box that has exactly one player works with no setup at all. Null when
     * there is nothing to hand a title to.
     */
    fun target(context: Context): Installed? {
        customPlayer(context)?.let { return it }
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
     * candidate and no usable choice has been made yet.
     *
     * "Usable" matters: a stored pick for an app that has since been uninstalled
     * is not a choice any more, and neither is a custom pick whose app is gone.
     * The single-candidate case stays silent either way, because asking when
     * there is exactly one player to pick is noise - [target] falls back to it.
     */
    fun shouldPrompt(context: Context): Boolean {
        if (AppPreferences.getExternalPlayerAsk(context)) return true
        // A custom pick IS a choice, so it is never "nothing picked yet": the
        // viewer named the app they want, and a chooser would ignore that.
        if (customPlayer(context) != null) return false
        val candidates = installed(context)
        val remembered = AppPreferences.getExternalPlayerPackage(context)
        if (remembered != null && candidates.any { it.packageName == remembered }) return false
        return candidates.size > 1
    }

    /**
     * The launch that opens [packageName] itself, with no stream attached: the
     * fallback for a custom pick whose manifest declares no video request at
     * all.
     *
     * A TV build that cannot take a URL can still be opened, and a pick that
     * ends in "Could not open …" is a dead press. Opening the app is not a
     * hand-off of the stream - the viewer chooses the title there - but the
     * session is unaffected: the playhead is measured while the app is in front
     * (see ExternalPlayerActivity), so history, Continue Watching and
     * scrobbling still land. Null when the app has no launcher entry, which is
     * when there is genuinely nothing to open.
     *
     * FLAG_ACTIVITY_NEW_TASK is stripped: the platform sets it on this intent,
     * and a new task drops the Activity Result the wrapper needs to see the
     * viewer come back at all - the same rule the scheme hand-off follows.
     */
    fun appLaunchIntent(context: Context, packageName: String?): Intent? {
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }
            .getOrNull()
            ?.apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
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
        target: Installed?,
        headers: Map<String, String> = emptyMap()
    ): Intent {
        if (target?.scheme == VIDHUB_SCHEME) {
            return schemeLaunchIntent(url, title, positionMs, target)
        }
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
        target?.packageName?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        return intent
    }

    /**
     * The hand-off to a scheme-only player, by its own documented URL.
     *
     * VidHub's `/play` takes the media URL, a starting position in SECONDS, and
     * a display name as query parameters of the `open-vidhub` URL - there are no
     * extras to set, which is the whole difference from a VIEW player.
     *
     * Two deliberate differences from the VIEW path:
     *
     *  - No `FLAG_ACTIVITY_NEW_TASK`. VidHub's own Android example adds it only
     *    when the caller is not an Activity, and this caller always is. Keeping
     *    the hand-off inside our task is also what lets the viewer's exit come
     *    back to the wrapper at all: a new task drops the Activity Result.
     *  - No `CATEGORY_DEFAULT`, for the same reason [schemeMatches] asks without
     *    it: the app was found without categories, so it is launched without
     *    them, and an intent with no categories resolves against every matching
     *    filter.
     *
     * The request headers an addon asked for are NOT passed: this URL has no
     * field for them, so a header-gated source is more likely to be refused
     * here than by a VIEW player that reads the `headers` extra. That is the
     * same best-effort trade the engine already documents.
     */
    private fun schemeLaunchIntent(
        url: String,
        title: String?,
        positionMs: Long,
        target: Installed
    ): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(vidHubPlayUrl(url, title, positionMs))
        }
        if (target.packageName.isNotBlank()) intent.setPackage(target.packageName)
        return intent
    }

    /**
     * VidHub's `/play` request URL: the media URL, the starting position in
     * seconds, and the name to show on its now-playing card.
     *
     * Every value is percent-encoded with the platform encoder
     * ([URLEncoder]); the position is clamped to the range VidHub's docs
     * accept (0 through 31,536,000 seconds - one year), because an out-of-range
     * value is answered with its error 102 instead of playing anything.
     *
     * Pure and Android-free, so the exact URL can be pinned by a unit test:
     * getting this wrong is invisible on the device (the app simply never
     * opens) which is the same class of failure this integration exists to
     * fix.
     */
    internal fun vidHubPlayUrl(url: String, title: String?, positionMs: Long): String = buildString {
        append(VIDHUB_PLAY_PROBE)
        append("?url=").append(encodeQueryValue(url))
        append("&position=").append(vidHubPositionSeconds(positionMs))
        title?.takeIf { it.isNotBlank() }
            ?.let { append("&filename=").append(encodeQueryValue(it)) }
        append("&x-source=").append(encodeQueryValue(VIDHUB_SOURCE_LABEL))
    }

    /**
     * The position as VidHub wants it: whole seconds in its accepted range.
     *
     * Milliseconds are floored, never rounded up - rounding up could send a
     * resume point past the end of the file - and a negative position is a
     * start from the beginning rather than an error.
     */
    internal fun vidHubPositionSeconds(positionMs: Long): String =
        (positionMs.coerceIn(0L, VIDHUB_MAX_POSITION_MS) / 1_000L).toString()

    /** Percent-encoding for a query VALUE: space as %20, never as "+". */
    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /**
     * The position an external player reported back, in milliseconds, or null
     * when it reported nothing usable.
     *
     * MX Player and VLC both answer with `position`; the value arrives as an
     * Int from either, but reading it through Bundle.get() covers whichever
     * type a given build uses instead of throwing on a Long.
     */
    @Suppress("DEPRECATION") // Bundle.get: the read is deliberately untyped, see above.
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
    @Suppress("DEPRECATION") // Bundle.get: same untyped read as reportedPositionMs.
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

    /** The name a scheme player is told is calling (VidHub's `x-source`). */
    internal const val VIDHUB_SOURCE_LABEL = "KBStream"

    /**
     * The largest position VidHub accepts, in milliseconds.
     *
     * One year, from its own documented position range (0 through 31,536,000
     * seconds). A value outside it is refused with error 102 rather than
     * clamped by the player, so the clamp is ours to make.
     */
    private const val VIDHUB_MAX_POSITION_MS = 31_536_000_000L

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
