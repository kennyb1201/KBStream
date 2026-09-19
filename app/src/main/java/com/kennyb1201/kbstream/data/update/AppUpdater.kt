package com.kennyb1201.kbstream.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app self-update for sideloaded installs.
 *
 * KBStream is not distributed through an app store, so updates come from this
 * repo's GitHub releases: CI publishes every main-branch build as a release
 * containing the signed APK plus a metadata.json ({versionCode, versionName}).
 * The updater compares that versionCode against the installed one — strictly
 * increasing run numbers, so any newer release wins.
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
            val sizeBytes: Long
        ) : UpdateState

        data class Downloading(val percent: Int) : UpdateState
        data class ReadyToInstall(val apkFile: File) : UpdateState
        data object UpToDate : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private const val REPO_OWNER = "kennyb1201"
    private const val REPO_NAME = "KBStream"
    private const val LATEST_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    /** versionCode the user dismissed the update banner for (no re-nagging). */
    private const val KEY_DISMISSED_CODE = "dismissed_version_code"
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
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
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
                    // to clean up — cache is wiped on the next run.
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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
                    state.value = UpdateState.UpToDate
                    return@launch
                }
                val apkAsset = assets(release)
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                if (apkAsset == null) {
                    state.value = UpdateState.UpToDate
                    return@launch
                }
                val meta = fetchMetadata(release)
                if (meta == null || meta.first <= installedCode) {
                    state.value = UpdateState.UpToDate
                    return@launch
                }
                state.value = UpdateState.Available(
                    versionName = meta.second,
                    versionCode = meta.first,
                    notes = release.optString("body").orEmpty(),
                    downloadUrl = apkAsset.getString("browser_download_url"),
                    sizeBytes = apkAsset.optLong("size", -1L)
                )
            } catch (t: Throwable) {
                state.value = UpdateState.Failed(t.message ?: "Network error")
            }
        }
    }

    fun downloadAndInstall(context: Context, available: UpdateState.Available) {
        state.value = UpdateState.Downloading(0)
        scope.launch {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val apk = File(
                    dir,
                    "kbstream-${available.versionName}-build${available.versionCode}.apk"
                )
                download(available.downloadUrl, apk)
                state.value = UpdateState.ReadyToInstall(apk)
                installApk(context, apk)
            } catch (t: Throwable) {
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
        try {
            sessionInstall(context, apk)
        } catch (denied: SecurityException) {
            intentInstall(context, apk)
        } catch (denied: IllegalStateException) {
            intentInstall(context, apk)
        }
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun assets(release: JSONObject): List<JSONObject> {
        val arr = release.getJSONArray("assets")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
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

    /**
     * versionCode + versionName from the release's metadata.json asset, with a
     * filename fallback (...-buildN.apk) if that asset is missing or broken.
     */
    private fun fetchMetadata(release: JSONObject): Pair<Long, String>? {
        val metaAsset = assets(release)
            .firstOrNull { it.getString("name") == "metadata.json" }
        if (metaAsset != null) {
            try {
                val request = Request.Builder()
                    .url(metaAsset.getString("browser_download_url"))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val obj = JSONObject(response.body!!.string())
                        val code = obj.getLong("versionCode")
                        val name = obj.optString("versionName", "unknown")
                        return code to name
                    }
                }
            } catch (_: Throwable) {
                // Fall through to the filename parse.
            }
        }
        val apkName = assets(release)
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?.getString("name") ?: return null
        val match = Regex("build(\\d+)").find(apkName) ?: return null
        val code = match.groupValues[1].toLongOrNull() ?: return null
        return code to apkName.substringBefore("-build")
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download body")
            val total = body.contentLength()
            var done = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                state.value = UpdateState.Downloading(percent)
                            }
                        }
                    }
                }
            }
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
