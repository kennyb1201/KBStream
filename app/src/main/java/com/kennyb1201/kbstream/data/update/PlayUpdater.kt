package com.kennyb1201.kbstream.data.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.FormFactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-app updates for the Play-distributed build.
 *
 * A Play install must not run [AppUpdater], and it cannot: `-PplayBuild` strips
 * `REQUEST_INSTALL_PACKAGES` from the merged manifest, so the GitHub-release
 * offer could only end in a denied install. That build's updates come from the
 * store instead, through Play's own In-App Updates API - this object.
 *
 * The two updaters are deliberately separate objects rather than one with a
 * branch. [AppUpdater] owns an APK: a download URL, a SHA-256, a staged file, a
 * PackageInstaller session. None of that exists here - Play downloads and
 * installs, and the app only asks it to. Sharing a state type would force every
 * APK-shaped field on a flow that has no APK, so the state model is its own
 * too, and the About row picks the one that matches the build.
 *
 * Flow: FLEXIBLE, not immediate. The download runs in the background (Play
 * shows the progress inside the app's own UI through [state]), the viewer keeps
 * using the app, and Play installs the build once it has the whole file and the
 * viewer accepts the restart prompt. An immediate update would take the screen
 * away mid-browse, which is the wrong trade for a media app whose next launch
 * is never urgent.
 *
 * Phone-only, and that is a guard rather than a property of the API: a TV
 * carries no touchscreen to answer Play's update sheet with, and the relay spec
 * puts Play updates on phones. [isEnabled] is what every entry point checks, so
 * a television that somehow runs this build does nothing at all here.
 *
 * No throttle, unlike [AppUpdater]'s 12-hour one: `appUpdateInfo` is answered
 * from Play's local cache, not from the network, and Play's own guidance is to
 * ask whenever the app comes back to the foreground - so a cached answer that
 * goes stale is worse than the cheap call that refreshes it.
 *
 * Verification needs a Play install: Play reads the signed AAB's versionCode
 * from the track it was installed from, so a sideloaded APK always answers
 * "no update" (Play has no record of it). That is why the field test for this
 * lands only on an internal-test-track install.
 */
object PlayUpdater {

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState

        /** Play has a newer build than the one installed. */
        data class Available(val versionCode: Int) : UpdateState

        /**
         * Play is fetching the update in the background. [percent] is -1 until
         * Play reports a byte total, so the row can show motion without
         * inventing a scale.
         */
        data class Downloading(val percent: Int) : UpdateState

        /**
         * The whole update is downloaded and waiting for the restart Play asks
         * for. Nothing else is needed from the app: Play installs it when the
         * viewer says so (see [promptOpen]).
         *
         * [versionCode] is the build that will land, so the prompt can name it;
         * 0 when Play had not named one by the time the status arrived.
         */
        data class ReadyToInstall(val versionCode: Int = 0) : UpdateState

        data object UpToDate : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private const val TAG = "PlayUpdater"

    /** Request code for Play's flexible-flow sheet; any stable value works. */
    private const val REQUEST_CODE_UPDATE = 1742

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /**
     * Whether the "Update ready" prompt is up.
     *
     * The download finishing is not a reason to restart the app: this is the
     * viewer's door. It is opened when Play reports the build downloaded, closed
     * by Open (which is what calls [completeUpdate]) or by Close, and re-opened
     * by the About row's tap - so a dismissed prompt is one tap away, and the
     * next launch re-adopts the downloaded build and offers it again.
     */
    private val _promptOpen = MutableStateFlow(false)
    val promptOpen: StateFlow<Boolean> = _promptOpen

    /** Written at launch on the IO thread, read from the UI thread. */
    @Volatile
    private var manager: AppUpdateManager? = null

    /** The info the flow sheet needs; kept from the last successful check. */
    private var availableInfo: AppUpdateInfo? = null

    /**
     * Play delivers install progress to a registered listener. Registering the
     * same listener twice is a warning and duplicate deliveries, so it happens
     * once per process.
     */
    @Volatile
    private var listenerRegistered = false

    private val installListener = InstallStateUpdatedListener { install ->
        val total = install.totalBytesToDownload()
        val done = install.bytesDownloaded()
        val next = stateForInstallStatus(
            status = install.installStatus(),
            percent = if (total > 0) ((done * 100) / total).toInt() else -1,
            // The callback does not name the build; the info the last check kept
            // does, and the prompt names what Open installs.
            versionCode = availableInfo?.availableVersionCode() ?: 0
        )
        if (next != null) {
            state.value = next
            // Downloaded is a moment to OFFER the restart, not to take it:
            // handing the update to Play here restarted the app the instant the
            // download finished (and, in the field, did not bring it back).
            if (next is UpdateState.ReadyToInstall) _promptOpen.value = true
        }
    }

    /**
     * The state one install-status callback means, or null when the status is
     * one this app does not model (an in-flight install, or a story of Play's
     * that changes nothing on screen).
     *
     * A pure mapping on purpose: the DOWNLOADED branch used to also call
     * [completeUpdate], so the restart was a side effect of the download
     * finishing rather than the viewer's choice. Keeping the mapping free of
     * side effects is what lets the contract test pin that - the prompt's Open
     * is the only caller of [completeUpdate] now.
     */
    internal fun stateForInstallStatus(
        status: Int,
        percent: Int,
        versionCode: Int
    ): UpdateState? = when (status) {
        InstallStatus.DOWNLOADING -> UpdateState.Downloading(percent = percent)
        InstallStatus.DOWNLOADED -> UpdateState.ReadyToInstall(versionCode = versionCode)
        InstallStatus.PENDING -> UpdateState.Downloading(percent = 0)
        InstallStatus.FAILED -> UpdateState.Failed(
            "Play could not download the update. Try again from the Play Store."
        )
        InstallStatus.CANCELED -> UpdateState.Idle
        else -> null
    }

    /**
     * True only where Play updates exist: the Play build, on a phone.
     *
     * A debug build is excluded for the same reason [AppUpdater] excludes it -
     * it is a side-by-side install with its own applicationId, and Play has no
     * install to update. A television is excluded because Play's update sheet
     * is a touch surface.
     */
    fun isEnabled(): Boolean =
        BuildConfig.PLAY_BUILD && !BuildConfig.DEBUG && !FormFactor.isTv

    /**
     * Launch-time hook, called from the application's start-up chain: adopts
     * an update Play downloaded while the app was closed. A flexible
     * download survives the process, so without this the update would sit
     * downloaded and uninstalled until the viewer visited Settings.
     */
    fun begin(context: Context) {
        if (!isEnabled()) return
        val updateManager = manager(context) ?: return
        registerListener(updateManager)
        updateManager.appUpdateInfo
            .addOnSuccessListener { info -> adopt(info) }
            .addOnFailureListener { t -> Log.w(TAG, "update info unavailable at launch", t) }
    }

    /**
     * Asks Play whether this install is behind. Called when the About row is
     * composed, which is the only place the answer is shown.
     */
    fun checkForUpdate(context: Context) {
        if (!isEnabled()) return
        when (state.value) {
            // A downloaded update is the answer to this question, and asking
            // again would replace the ready state (and reopen the prompt) for a
            // status Play would report as DOWNLOADED anyway.
            is UpdateState.Checking,
            is UpdateState.Downloading,
            is UpdateState.ReadyToInstall -> return
            else -> Unit
        }
        val updateManager = manager(context) ?: return
        registerListener(updateManager)
        state.value = UpdateState.Checking
        updateManager.appUpdateInfo
            .addOnSuccessListener { info -> adopt(info) }
            .addOnFailureListener { t ->
                Log.w(TAG, "update info unavailable", t)
                state.value = UpdateState.Failed(
                    "Couldn't reach the Play Store. Check again in a moment."
                )
            }
    }

    /**
     * Starts Play's flexible download. Play draws its own sheet over the app,
     * so there is nothing to compose here - the [installListener] reports the
     * download, and a cancelled sheet simply leaves the offer standing.
     */
    fun startUpdate(context: Context) {
        if (!isEnabled()) return
        val info = availableInfo
        val updateManager = manager(context)
        val activity = context.hostActivity()
        if (info == null || updateManager == null || activity == null) {
            state.value = UpdateState.Failed("Couldn't open the Play update screen.")
            return
        }
        try {
            updateManager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                REQUEST_CODE_UPDATE
            )
        } catch (t: Throwable) {
            Log.e(TAG, "flexible update flow failed to start", t)
            state.value = UpdateState.Failed(
                "Couldn't start the update: ${t.message ?: "unknown"}"
            )
        }
    }

    /**
     * Opens the prompt from the About row. The row used to call
     * [completeUpdate] directly, which turned a tap on an informational row into
     * an unannounced restart; it is a door to the same prompt everywhere else on
     * the app draws it.
     *
     * A no-op unless the update really is downloaded: a prompt with nothing
     * behind it has no Open to offer.
     */
    fun requestPrompt() {
        if (state.value is UpdateState.ReadyToInstall) _promptOpen.value = true
    }

    /**
     * Close/Back on the prompt: hide it and keep the downloaded build. Play has
     * the file; the next launch adopts it and offers the prompt again, and the
     * About row reopens it in this one.
     */
    fun dismissPrompt() {
        _promptOpen.value = false
    }

    /**
     * Hands a fully downloaded update to Play, which installs it and restarts
     * the app. Safe to call when nothing is downloaded: Play answers with a
     * failed task, which is not worth a dialog.
     *
     * Reached only from the prompt's Open (see [stateForInstallStatus]); the
     * prompt closes with it, because Play takes the screen for the restart.
     */
    fun completeUpdate() {
        _promptOpen.value = false
        val updateManager = manager ?: return
        updateManager.completeUpdate()
            .addOnFailureListener { t -> Log.w(TAG, "completeUpdate failed", t) }
    }

    // ── Internals ──────────────────────────────────────────────────

    /**
     * Reads one [info] into [state], whichever entry point asked. Play answers
     * with the install status as well as the availability, and the status wins:
     * an update already downloading is not an offer to re-offer.
     */
    private fun adopt(info: AppUpdateInfo) {
        when (info.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                availableInfo = info
                state.value = UpdateState.ReadyToInstall(info.availableVersionCode())
                // Offer it. An update that finished downloading while the app
                // was closed is the prompt's next chance, never a silent
                // restart: nothing hands the update to Play on this path either.
                _promptOpen.value = true
            }
            InstallStatus.DOWNLOADING ->
                state.value = UpdateState.Downloading(percent = -1)
            InstallStatus.PENDING -> state.value = UpdateState.Downloading(percent = 0)
            InstallStatus.INSTALLING ->
                state.value = UpdateState.ReadyToInstall(info.availableVersionCode())
            else -> {
                val available =
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                        )
                if (available) {
                    availableInfo = info
                    state.value = UpdateState.Available(info.availableVersionCode())
                } else {
                    availableInfo = null
                    state.value = UpdateState.UpToDate
                }
            }
        }
    }

    private fun manager(context: Context): AppUpdateManager? {
        manager?.let { return it }
        return try {
            AppUpdateManagerFactory.create(context.applicationContext).also { manager = it }
        } catch (t: Throwable) {
            // A device without Play, or a Play build that has never been
            // through the store: the updater simply does not exist there.
            Log.i(TAG, "Play in-app updates unavailable", t)
            null
        }
    }

    private fun registerListener(updateManager: AppUpdateManager) {
        if (listenerRegistered) return
        listenerRegistered = true
        updateManager.registerListener(installListener)
    }

    /**
     * `LocalContext` is the activity in this app, but a context handed in from
     * a service or the application is not - and Play's flow needs an activity
     * to draw its sheet in. Walking the wrapper chain covers both without the
     * caller having to know which one it has.
     */
    private fun Context.hostActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
