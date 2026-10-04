package com.kennyb1201.kbstream.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app self-update for sideloaded installs.
 *
 * KBStream is not distributed through an app store, so updates come from this
 * repo's GitHub releases: CI publishes a release on a version tag, or on a
 * hand-run workflow, carrying the signed APK plus a
 * metadata.json ({versionCode, versionName}).
 * The updater compares that versionCode against the installed one — strictly
 * increasing run numbers, so any newer release wins.
 *
 * The publish side is guarded against the trap that makes a run number unsafe
 * as a version: re-running an OLDER commit still gets a HIGHER run number, so
 * publishing on every push could have offered an old build to every install as
 * an upgrade. The workflow refuses to publish unless the run's versionCode is
 * above the published one AND the published commit is already contained in the
 * commit being published.
 *
 * Install strategy: a PackageInstaller session first (silently replaces the
 * app when this install owns the package, e.g. after the first in-app
 * update). The session's status callback is delivered as a broadcast to
 * [InstallStatusReceiver]: when Android answers EXTRA_STATUS_PENDING_USER_ACTION
 * (the normal case — it wants the user to confirm the install), the receiver
 * launches the confirmation dialog. Without it the "handing to installer"
 * state would hang forever, because commit()'s IntentSender is ONLY the
 * status callback — it is never shown to the user. If the system denies the
 * session (Android 12+ update-ownership rule when the app was last installed
 * via adb), it falls back to the system ACTION_INSTALL_PACKAGE prompt. On
 * success the system relaunches KBStream — the app process is killed during
 * an install, and the confirmation activity relaunches us afterward.
 *
 * Reporting an install is therefore split across two launches, because the
 * process that starts one never lives to see how it ended: before the handoff
 * the target version is written to prefs ([recordPendingInstall]), and every
 * later launch checks whether the running build is that version
 * ([confirmInstallOnLaunch]) and raises a one-shot [UpdateState.Updated]. That
 * marker is also what makes the download leg visible at all — [UpdateState]
 * carries percent and byte counts, so the UI can show the fetch and the
 * install instead of going silent the moment the user taps Install.
 */
object AppUpdater {

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState

        /** A newer release exists on GitHub. */
        data class Available(
            val versionName: String,
            val versionCode: Long,
            val notes: String,
            val downloadUrl: String,
            val sizeBytes: Long,
            /**
             * SHA-256 of the APK asset, from the release's metadata.json.
             * Null for a release published before the field existed, in which
             * case the download cannot be verified (and is also unverifiable
             * older-format data, never the current build).
             */
            val sha256: String? = null
        ) : UpdateState

        /**
         * The APK is being fetched. [downloadedBytes]/[totalBytes] are carried
         * alongside the percentage so the UI can show real amounts ("53 of
         * 126 MB") instead of a bar with no scale — [totalBytes] is -1 when
         * the server sent no Content-Length, in which case the byte count is
         * the only honest thing to show.
         */
        data class Downloading(
            val percent: Int,
            val downloadedBytes: Long = 0L,
            val totalBytes: Long = -1L,
            /** Version being fetched, so the dialog can keep naming it. */
            val versionName: String = "",
            val versionCode: Long = 0L
        ) : UpdateState

        /**
         * Downloaded and handed to the system installer. This is the state an
         * install sits in while Android runs it — and it is where the user
         * sees progress at all, because a successful install kills this
         * process without ever reporting back to it.
         */
        data class ReadyToInstall(
            val apkFile: File,
            val versionName: String = "",
            val versionCode: Long = 0L
        ) : UpdateState

        data object UpToDate : UpdateState
        data class Failed(val message: String) : UpdateState

        /**
         * One-shot confirmation that an install landed: the build named here
         * is the one now running.
         *
         * An install kills this process, so the session that started it can
         * never report success — the launch *after* it is the only place that
         * can, which is why [confirmInstallOnLaunch] raises this from a marker
         * written before the handoff. Consumed once by the UI, then Idle.
         */
        data class Updated(val versionName: String, val versionCode: Long) : UpdateState
    }

    private const val REPO_OWNER = "kennyb1201"
    private const val REPO_NAME = "KBStream"
    private const val LATEST_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    /** versionCode the user dismissed the update banner for (no re-nagging). */
    private const val KEY_DISMISSED_CODE = "dismissed_version_code"

    /**
     * The install handed to the system installer: which version, and when.
     * Written before the handoff and read by the next launch, which is the
     * only place an install's success can be observed (see
     * [confirmInstallOnLaunch]).
     */
    private const val KEY_PENDING_INSTALL_CODE = "pending_install_code"
    private const val KEY_PENDING_INSTALL_NAME = "pending_install_name"
    private const val KEY_PENDING_INSTALL_AT = "pending_install_at"

    /**
     * How long a handoff stays claimable. After this the marker cannot be
     * assumed to describe what the user just launched into — a prompt waved
     * away and forgotten must not announce a success weeks later.
     */
    private const val PENDING_INSTALL_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** Byte spacing for progress when the server sends no Content-Length. */
    private const val UNKNOWN_TOTAL_STEP_BYTES = 4L * 1024 * 1024

    /**
     * How much of the release body the update row shows. The publish workflow
     * writes a commit list into it and Settings renders it under the version
     * line, where an unbounded body would push the Install action off the
     * screen — so the text is cut here rather than trusted to stay short.
     */
    private const val MAX_NOTES_CHARS = 800

    /** Room for the Settings → About changelog card (see [releaseChangelog]). */
    private const val MAX_CHANGELOG_CHARS = 2400

    private val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private const val REQUEST_CODE_INSTALL = 4242

    /** Broadcast action the session status callback is delivered to. */
    private const val ACTION_INSTALL_STATUS =
        "com.kennyb1201.kbstream.action.INSTALL_STATUS"

    /**
     * Session status callback. PackageInstaller sends EXTRA_STATUS here after
     * commit(): PENDING_USER_ACTION means "show the confirmation dialog whose
     * intent is in EXTRA_INTENT", SUCCESS means the app was replaced (the
     * process is being killed — nothing to do), anything else is a failure.
     * Must be a declared receiver (the system targets it explicitly).
     */
    class InstallStatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // IntentCompat, not Intent.getParcelableExtra(String, Class):
                    // the two-arg form is API 33, and this app's floor is 26.
                    val confirm = IntentCompat.getParcelableExtra(
                        intent,
                        Intent.EXTRA_INTENT,
                        Intent::class.java
                    )
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(confirm)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Install confirmation failed to start", t)
                            state.value = UpdateState.Failed(
                                "Couldn't open the install prompt: ${t.message ?: "unknown"}"
                            )
                        }
                    } else {
                        state.value = UpdateState.Failed(
                            "Installer asked for confirmation but sent no dialog"
                        )
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    // App was replaced; the process dies around now. Nothing
                    // to clean up — the next launch reads the pending-install
                    // marker and reports the result.
                }
                // The two ways a user-facing install stops short. Both are
                // the user's own action (or the device's policy), so they get
                // a sentence instead of the installer's raw status text.
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    Log.i(TAG, "install canceled by the user")
                    state.value = UpdateState.Failed("Install canceled")
                }
                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    Log.w(TAG, "install blocked by the device")
                    state.value = UpdateState.Failed(
                        "This device blocked the install. Allow KBStream to install " +
                            "updates, then try again."
                    )
                }
                else -> {
                    val message =
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "Install failed (status $status)"
                    Log.e(TAG, "PackageInstaller status $status: $message")
                    state.value = UpdateState.Failed(message)
                }
            }
        }
    }

    private const val TAG = "AppUpdater"

    /** Where a downloaded APK waits for the installer. */
    private const val UPDATES_DIR = "updates"

    /**
     * `...-build<versionCode>.apk`, the name CI publishes and the name the
     * updater writes — parsed on both sides so a staged file can be matched
     * against the installed build (see [clearStaleStagedApk]).
     */
    private val BUILD_NUMBER = Regex("build(\\d+)")

    // Derived from the process-wide base client: the long read window this
    // needs for an APK download stays its own, the sockets and threads do not.
    private val client = BaseHttpClient.derived {
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
    }

    // Launch {} failures funnel here: without a handler an uncaught
    // Throwable on this scope kills the process (the updater runs at every
    // cold start, so a network-stack Error would make the app unlaunchable).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "update task failed hard", t)
            state.value = UpdateState.Failed(t.message ?: "Update error")
            com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                t, mapOf("source" to "app_updater_scope")
            )
        }
    )

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /**
     * The latest release's changelog, kept even when this build is already
     * current. [UpdateState.Available] carries notes only when there IS an
     * update to offer, but Settings → About shows the last changelog either
     * way, so it is captured on every successful check and cleared when the
     * feed has nothing (a 404, or a release with no body).
     */
    val latestChangelog = MutableStateFlow("")

    /**
     * True when the launch-time check found a newer release that the user has
     * NOT dismissed. The Settings row shows the update regardless of dismissal;
     * this gate is for the app-wide popup so "Later" actually stays quiet
     * (same version) instead of popping up on every launch.
     */
    fun isPopupEligible(context: Context): Boolean {
        val s = state.value
        if (s !is UpdateState.Available) return false
        val dismissed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DISMISSED_CODE, 0L)
        return s.versionCode > dismissed
    }

    /** Remember the offered versionCode so the popup stops re-appearing. */
    fun dismissPopup(context: Context, available: UpdateState.Available) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DISMISSED_CODE, available.versionCode).apply()
    }

    /** Silent launch-time check, throttled to once per 12h. Failures are quiet. */
    fun maybeAutoCheck(context: Context) {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_MS, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        when (state.value) {
            is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed -> Unit
            else -> return
        }
        checkForUpdate(context)
    }

    /**
     * Launch-time check from MainActivity: fires whenever this process hasn't
     * checked yet (state still Idle), so reopening the app surfaces a fresh
     * release even when the Application-level 12h throttle would skip. A no-op
     * once any check/update is already in flight (the state guard in
     * [checkForUpdate] makes the race with Application.onCreate harmless).
     */
    fun checkOnLaunch(context: Context) {
        if (state.value != UpdateState.Idle) return
        checkForUpdate(context)
    }

    fun checkForUpdate(context: Context) {
        when (state.value) {
            is UpdateState.Checking, is UpdateState.Downloading -> return
            is UpdateState.ReadyToInstall -> return // APK already staged
            else -> Unit
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
        state.value = UpdateState.Checking
        scope.launch {
            try {
                val installedCode = installedVersionCode(context)
                val release = fetchLatestRelease()
                if (release == null) {
                    latestChangelog.value = ""
                    state.value = UpdateState.UpToDate
                    return@launch
                }
                // Read the changelog before any "nothing to install" exit
                // below: About shows it regardless of whether this build is
                // behind the release.
                latestChangelog.value = releaseChangelog(release)
                val apkAsset = assets(release)
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                if (apkAsset == null) {
                    state.value = UpdateState.UpToDate
                    return@launch
                }
                val meta = fetchMetadata(release)
                if (meta == null || meta.versionCode <= installedCode) {
                    state.value = UpdateState.UpToDate
                    return@launch
                }
                state.value = UpdateState.Available(
                    versionName = meta.versionName,
                    versionCode = meta.versionCode,
                    notes = releaseNotes(release),
                    downloadUrl = apkAsset.getString("browser_download_url"),
                    sizeBytes = apkAsset.optLong("size", -1L),
                    sha256 = meta.sha256
                )
            } catch (t: Throwable) {
                // A canceled check is not a failed one: let the cancellation
                // through instead of parking a Failed state the UI would show.
                if (t is CancellationException) throw t
                state.value = UpdateState.Failed(t.message ?: "Network error")
            }
        }
    }

    fun downloadAndInstall(context: Context, available: UpdateState.Available) {
        state.value = UpdateState.Downloading(
            percent = 0,
            versionName = available.versionName,
            versionCode = available.versionCode
        )
        scope.launch {
            try {
                val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                // Whatever was pending is being replaced by this download.
                clearPendingInstall(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                val apk = File(
                    dir,
                    "kbstream-${available.versionName}-build${available.versionCode}.apk"
                )
                download(
                    url = available.downloadUrl,
                    target = apk,
                    expectedSha256 = available.sha256,
                    versionName = available.versionName,
                    versionCode = available.versionCode
                )
                state.value = UpdateState.ReadyToInstall(
                    apkFile = apk,
                    versionName = available.versionName,
                    versionCode = available.versionCode
                )
                installApk(context, apk)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                state.value = UpdateState.Failed(t.message ?: "Download failed")
            }
        }
    }

    /**
     * Installs a downloaded APK. PackageInstaller session first; falls back to
     * the system install prompt when the session is denied (update-ownership).
     */
    fun installApk(context: Context, apk: File) {
        // The install kills this process. Persist the CURRENT Supabase
        // refresh token first — a background auto-refresh since the last
        // save would leave the stored token spent, and the post-update cold
        // start would then fail refresh-token reuse detection and sign the
        // user out.
        com.kennyb1201.kbstream.data.sync.SupabaseSync.persistSessionBeforeProcessExit()
        // Remember what is being installed BEFORE the handoff: whether this
        // install works is only knowable from the next launch, and this marker
        // is what lets that launch say so.
        recordPendingInstall(context, apk)
        try {
            sessionInstall(context, apk)
        } catch (denied: SecurityException) {
            intentInstall(context, apk)
        } catch (denied: IllegalStateException) {
            intentInstall(context, apk)
        }
    }

    /**
     * Called once per launch, before any update check: if the running build is
     * at least the one that was handed to the installer, that install landed —
     * raise a one-shot [UpdateState.Updated] so the UI can say so, and clear
     * the marker.
     *
     * Silent when the handoff is still pending (the prompt is waiting, or the
     * user waved it away) and when the marker is older than
     * [PENDING_INSTALL_TTL_MS]. Reads the package manager, so a failure there
     * leaves the marker alone rather than guessing.
     */
    fun confirmInstallOnLaunch(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingCode = prefs.getLong(KEY_PENDING_INSTALL_CODE, 0L)
        if (pendingCode <= 0L) return
        val stampedAt = prefs.getLong(KEY_PENDING_INSTALL_AT, 0L)
        if (System.currentTimeMillis() - stampedAt > PENDING_INSTALL_TTL_MS) {
            clearPendingInstall(prefs)
            return
        }
        val installed = runCatching { installedVersionCode(context) }.getOrNull() ?: return
        if (installed < pendingCode) return // handed off, not landed yet
        val versionName = prefs.getString(KEY_PENDING_INSTALL_NAME, null).orEmpty()
        clearPendingInstall(prefs)
        state.value = UpdateState.Updated(
            versionName = versionName.ifBlank { "build $pendingCode" },
            versionCode = installed
        )
    }

    /**
     * Consumes a state the user has finished with — the one-shot [Updated]
     * confirmation, an [ReadyToInstall] handoff they want off the screen (the
     * package-installer fallback reports nothing back, so that dialog would
     * otherwise have no way out), or a [Failed] they have read. A no-op for
     * everything else: [Available], [Downloading] and [Checking] are states
     * the app owns, not the user.
     */
    fun acknowledge() {
        when (state.value) {
            is UpdateState.Updated,
            is UpdateState.ReadyToInstall,
            is UpdateState.Failed -> state.value = UpdateState.Idle
            else -> Unit
        }
    }

    /**
     * Records the build being handed to the installer, keyed off the staged
     * file's own name (`kbstream-<versionName>-build<versionCode>.apk` — the
     * name [downloadAndInstall] writes, and the one CI publishes). A name that
     * carries no build number is left unrecorded: there is nothing to verify
     * against later, and a guess would announce the wrong version.
     *
     * Internal, with [atMs] injectable, so the confirmation's two-sided
     * contract (report a landed install, stay quiet about a pending or expired
     * one) can be pinned without an installer.
     */
    internal fun recordPendingInstall(
        context: Context,
        apk: File,
        atMs: Long = System.currentTimeMillis()
    ) {
        val code = BUILD_NUMBER.find(apk.name)?.groupValues?.get(1)?.toLongOrNull() ?: return
        val name = apk.name.removePrefix("kbstream-").substringBefore("-build")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_PENDING_INSTALL_CODE, code)
            .putString(KEY_PENDING_INSTALL_NAME, name)
            .putLong(KEY_PENDING_INSTALL_AT, atMs)
            .apply()
    }

    private fun clearPendingInstall(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove(KEY_PENDING_INSTALL_CODE)
            .remove(KEY_PENDING_INSTALL_NAME)
            .remove(KEY_PENDING_INSTALL_AT)
            .apply()
    }

    // ── Internals ──────────────────────────────────────────────────

    /**
     * Deletes a staged update APK that is not newer than the installed build,
     * returning whether anything went.
     *
     * `cacheDir/updates` is cleared before each download, so it only ever holds
     * one file — but that one file is a whole signed APK (tens of megabytes,
     * and an unsigned debug build is over a hundred), and it survives the case
     * that matters: the download succeeded, the user dismissed the install
     * prompt, and the build was installed by some other route afterwards. That
     * leftover is never downloaded again and never needed, because an update is
     * only ever offered when it is strictly newer than the installed code.
     *
     * Refuses to touch anything while a download or an install is pending:
     * the staged file is handed to PackageInstaller by path, so deleting it
     * under an in-flight install would break the install rather than save the
     * space. The comparison is `<=` on purpose — an APK for exactly the
     * installed build is already spent.
     */
    fun clearStaleStagedApk(context: Context): Boolean {
        val current = state.value
        if (current is UpdateState.Downloading || current is UpdateState.ReadyToInstall) {
            return false
        }
        val dir = File(context.cacheDir, UPDATES_DIR)
        if (!dir.isDirectory) return false
        val installed = runCatching { installedVersionCode(context) }.getOrNull() ?: return false

        var removed = false
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            // An unreadable build number goes too: nothing can install from a
            // name the updater itself could not parse, and that is exactly what
            // a half-written or renamed leftover looks like.
            val built = BUILD_NUMBER.find(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if ((built == null || built <= installed) && file.delete()) removed = true
        }
        if (removed) Log.i(TAG, "cleared a staged update APK (installed=$installed)")
        return removed
    }

    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun assets(release: JSONObject): List<JSONObject> {
        val arr = release.getJSONArray("assets")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * The release body, as the update row's notes.
     *
     * Trimmed, with the literal `null` org.json hands back for a release that
     * has no body at all treated as no notes — otherwise the row would offer
     * to install the word "null". Clamped to [MAX_NOTES_CHARS] for the reason
     * that constant documents.
     */
    private fun releaseNotes(release: JSONObject): String =
        releaseBody(release, MAX_NOTES_CHARS)

    /**
     * The same release body, with more room for the About card.
     *
     * [releaseNotes] is clamped tight because the update row sits beside an
     * Install action in a fixed-height row; About is a scrolling pane, so the
     * commit list there may run longer before it is cut.
     */
    private fun releaseChangelog(release: JSONObject): String =
        releaseBody(release, MAX_CHANGELOG_CHARS)

    /** The release body, trimmed and clamped to [maxChars]; "null"/blank = "". */
    private fun releaseBody(release: JSONObject, maxChars: Int): String {
        val body = release.optString("body", "").trim()
        if (body.isEmpty() || body == "null") return ""
        return if (body.length <= maxChars) {
            body
        } else {
            body.take(maxChars).trimEnd() + "…"
        }
    }

    /** Latest non-prerelease release JSON, or null when none exists (404). */
    private fun fetchLatestRelease(): JSONObject? {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub API HTTP ${response.code}")
            val body = response.body ?: return null
            return JSONObject(body.string())
        }
    }

    /** versionCode / versionName / APK hash from the release's metadata.json. */
    private data class ReleaseMeta(
        val versionCode: Long,
        val versionName: String,
        val sha256: String?
    )

    /**
     * versionCode + versionName from the release's metadata.json asset, with a
     * filename fallback (...-buildN.apk) if that asset is missing or broken.
     * sha256 is the APK's own hash, verified after download; a null here (the
     * filename fallback, i.e. no metadata.json at all) is refused at install
     * time rather than installed unverified — see [download].
     */
    private fun fetchMetadata(release: JSONObject): ReleaseMeta? {
        val metaAsset = assets(release)
            .firstOrNull { it.getString("name") == "metadata.json" }
        if (metaAsset != null) {
            try {
                val request = Request.Builder()
                    .url(metaAsset.getString("browser_download_url"))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val obj = JSONObject(response.body?.string().orEmpty())
                        val code = obj.getLong("versionCode")
                        val name = obj.optString("versionName", "unknown")
                        val sha = obj.optString("sha256", "").trim()
                        return ReleaseMeta(code, name, sha.takeIf { it.isNotBlank() })
                    }
                }
            } catch (_: Throwable) {
                // Fall through to the filename parse.
            }
        }
        val apkName = assets(release)
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?.getString("name") ?: return null
        val match = BUILD_NUMBER.find(apkName) ?: return null
        val code = match.groupValues[1].toLongOrNull() ?: return null
        // No metadata.json means no published hash: filename fallback only.
        return ReleaseMeta(code, apkName.substringBefore("-build"), null)
    }

    /**
     * Streams [url] to [target], hashing as it goes, then checks the result
     * against [expectedSha256]. Both a mismatch and a MISSING hash delete the
     * file and throw, so an unverified APK never reaches the installer: the CI
     * release always publishes the hash in metadata.json, so a feed without one
     * is a tampered or broken release rather than an ordinary case, and
     * refusing the update is the safer failure.
     */
    private fun download(
        url: String,
        target: File,
        expectedSha256: String?,
        versionName: String,
        versionCode: Long
    ) {
        val request = Request.Builder().url(url).build()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download body")
            val total = body.contentLength()
            var done = 0L
            var lastPercent = -1
            var lastEmitBytes = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        // With a known size, one emission per whole percent (a
                        // hundredth of the window). Without one the percentage
                        // would never move at all, so fall back to byte spacing
                        // and let the UI show the running total instead.
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        val emit = if (total > 0) {
                            percent != lastPercent
                        } else {
                            done - lastEmitBytes >= UNKNOWN_TOTAL_STEP_BYTES
                        }
                        if (emit) {
                            lastPercent = percent
                            lastEmitBytes = done
                            state.value = UpdateState.Downloading(
                                percent = percent,
                                downloadedBytes = done,
                                totalBytes = total,
                                versionName = versionName,
                                versionCode = versionCode
                            )
                        }
                    }
                }
            }
        }

        if (!expectedSha256.isNullOrBlank()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                target.delete()
                throw IOException(
                    "Update failed its integrity check (SHA-256 mismatch)"
                )
            }
            Log.i(TAG, "update APK verified (sha256=$actual)")
        } else {
            // Fail closed. See the note above: the publish workflow always
            // writes sha256 into metadata.json, so its absence means the feed
            // was tampered with or is broken — not a reason to install a build
            // we cannot verify.
            target.delete()
            throw IOException(
                "Update refused: the release published no SHA-256 to verify against"
            )
        }
    }

    private fun sessionInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("kbstream.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // Status callback: the system reports back to the receiver, which
            // launches the confirmation dialog when required. (commit()'s
            // IntentSender is NOT shown to the user — that was the old bug.)
            val statusIntent = Intent(ACTION_INSTALL_STATUS)
                .setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_INSTALL,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
        }
    }

    // ACTION_INSTALL_PACKAGE is deprecated (the PackageInstaller session above
    // is the modern path) but it is the only fallback when a session could not
    // be created, and it still works on every API this app ships to.
    @Suppress("DEPRECATION")
    private fun intentInstall(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updateprovider", apk
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        context.startActivity(intent)
    }
}
