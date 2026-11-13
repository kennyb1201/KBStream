package com.kennyb1201.kbstream.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One TV found on the current Wi-Fi. */
internal data class RemoteDevice(
    val name: String,
    val host: String,
    val port: Int
)

internal enum class RemotePhase {
    /** Nothing has been searched for yet. */
    IDLE,

    /** Discovery is running (or just started). */
    SEARCHING,

    /** Discovery finished with nothing, or we are not on Wi-Fi. */
    NO_DEVICE,

    /** A socket is being opened. */
    CONNECTING,

    /** The TV answered: keys and titles can be sent. */
    CONNECTED,

    /** The socket died and the client is retrying without asking the viewer. */
    RECONNECTING,

    /** The TV asked for the PIN. */
    NEEDS_PIN
}

internal data class RemoteClientState(
    val phase: RemotePhase = RemotePhase.IDLE,
    val devices: List<RemoteDevice> = emptyList(),
    val device: RemoteDevice? = null,
    /** The TV's own name once it has greeted us. */
    val tvName: String? = null,
    /** Set when the PIN the viewer entered was refused. */
    val pinRejected: Boolean = false,
    /** A transient line the screen shows under the header. */
    val message: String? = null
)

/**
 * The phone end of the remote channel (spec 2026-11-13, F1/F2/F3).
 *
 * Discovery uses Android NSD for the same service type the TV registers, and it
 * runs ONLY on Wi-Fi: S6's "no TV found on this Wi-Fi" is a capability check
 * before discovery, not an empty list afterwards, so a phone on mobile data says
 * so immediately and never broadcasts.
 *
 * The socket is OkHttp's - already in the tree - with a ping every 15s so a dead
 * TV is noticed rather than waited on. A drop reconnects quietly with backoff and
 * re-presents the stored token, which is why the viewer never re-pairs (S5). If
 * the TV forgot this phone, the TV answers `auth_required` and the screen asks
 * for the PIN again - the one case where the viewer has to do something.
 *
 * Pairings are filed per TV NAME (the only identity the advertisement carries),
 * and the phone keeps its own id so the TV can tell this phone from another.
 */
internal class RemoteClient(context: Context) {

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val state = MutableStateFlow(RemoteClientState())

    val stateFlow: StateFlow<RemoteClientState> = state.asStateFlow()

    private var discovery: NsdManager.DiscoveryListener? = null

    private var socket: WebSocket? = null

    private var reconnectJob: Job? = null

    private var attempt = 0

    /** A PIN typed while the socket was down, sent as soon as it opens. */
    private var pendingPair: String? = null

    /** When true the viewer asked to be here; a drop retries, a manual stop does not. */
    private var wantConnection = false

    // ── Public surface ────────────────────────────────────────────────────

    fun start() {
        if (!onWifi()) {
            state.value = RemoteClientState(
                phase = RemotePhase.NO_DEVICE,
                message = NO_WIFI_MESSAGE
            )
            return
        }
        startDiscovery()
    }

    fun retry() {
        socket?.close(1000, "retry")
        socket = null
        // A discovery listener can outlive the window that gave up on it, so a
        // retry always stops the old one: otherwise "start" would find one
        // registered and do nothing at all.
        stopDiscovery()
        start()
    }

    fun stop() {
        wantConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "leaving")
        socket = null
        stopDiscovery()
    }

    /** Connects to [device]; a stored token means no PIN is asked for. */
    fun connect(device: RemoteDevice) {
        wantConnection = true
        attempt = 0
        state.value = state.value.copy(
            phase = RemotePhase.CONNECTING,
            device = device,
            pinRejected = false,
            message = null
        )
        openSocket(device)
    }

    /** Answers the TV's PIN prompt. */
    fun pair(pin: String) {
        val device = state.value.device ?: return
        val code = pin.trim()
        if (code.length != RemoteRules.PIN_DIGITS) return
        val payload = RemoteProtocol.pairRequest(phoneId(), phoneName(), code)
        val live = socket
        if (live == null) {
            // Forgetting a TV closes the socket, and the prompt that follows is
            // answered on a fresh one: hold the code until the handshake is out,
            // then send it.
            pendingPair = payload
            wantConnection = true
            openSocket(device)
            return
        }
        runCatching { live.send(payload) }
    }

    fun press(code: String) {
        val normalised = RemoteRules.normaliseKey(code) ?: return
        send(RemoteProtocol.key(normalised))
    }

    /**
     * Sends a title for the TV to open.
     *
     * The phone shows the TV's own verdict: `resume` (it is continuing from the
     * stored playhead), `detail` (the title had no progress, so its page opened),
     * or the refusal it named.
     */
    fun playOnTv(id: String, mediaType: String?): Boolean {
        if (state.value.phase != RemotePhase.CONNECTED) {
            state.value = state.value.copy(message = NOT_CONNECTED_MESSAGE)
            return false
        }
        return send(RemoteProtocol.play(id, mediaType))
    }

    /** Forgets the stored token for this TV, so the next connect asks for a PIN. */
    fun forgetTv() {
        val device = state.value.device ?: return
        RemotePhonePrefs.clearToken(appContext, device.name)
        // The socket goes too: it is authenticated as the pairing just removed,
        // and holding it open would keep the old freedom alive behind a screen
        // that says the phone is unpaired.
        wantConnection = false
        socket?.close(1000, "forget")
        socket = null
        pendingPair = null
        state.value = state.value.copy(
            phase = RemotePhase.NEEDS_PIN,
            pinRejected = false,
            message = null
        )
    }

    // ── Discovery ─────────────────────────────────────────────────────────

    /**
     * Starts discovery.
     *
     * [quiet] is the reconnect loop's mode: it re-resolves the TV to pick up a new
     * address after a Wi-Fi flap without stamping the screen over the
     * "reconnecting" state the viewer is already looking at.
     */
    @Suppress("DEPRECATION")
    private fun startDiscovery(quiet: Boolean = false) {
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            if (!quiet) state.value = RemoteClientState(phase = RemotePhase.NO_DEVICE)
            return
        }
        if (discovery != null) return

        if (!quiet) state.value = state.value.copy(phase = RemotePhase.SEARCHING, message = null)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith(RemoteRules.SERVICE_TYPE)) return
                resolve(manager, info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = RemoteRules.deviceLabel(info.serviceName)
                state.value = state.value.copy(
                    devices = state.value.devices.filterNot { it.name == name }
                )
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery did not start: $errorCode")
                discovery = null
                state.value = state.value.copy(phase = RemotePhase.NO_DEVICE)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovery = null
            }
        }

        runCatching {
            manager.discoverServices(RemoteRules.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovery = listener
        }.onFailure { error ->
            Log.w(TAG, "discovery failed: ${error.javaClass.simpleName}")
            state.value = state.value.copy(phase = RemotePhase.NO_DEVICE)
        }

        // A network with no TV on it must say so rather than spin forever.
        scope.launch {
            delay(DISCOVERY_WINDOW_MS)
            if (!quiet && state.value.phase == RemotePhase.SEARCHING &&
                state.value.devices.isEmpty()
            ) {
                state.value = state.value.copy(
                    phase = RemotePhase.NO_DEVICE,
                    message = NO_TV_MESSAGE
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        if (!RemoteRules.isLanHost(host)) return
                        val device = RemoteDevice(
                            name = RemoteRules.deviceLabel(serviceInfo.serviceName),
                            host = host,
                            port = serviceInfo.port
                        )
                        val devices = state.value.devices
                            .filterNot { it.name == device.name }
                            .plus(device)
                        state.value = state.value.copy(devices = devices)
                        // A TV this phone already paired with is entered on its
                        // own: the viewer opened the Remote screen to use it, not
                        // to tap through a list. While reconnecting, only a RECORD
                        // THAT MOVED re-enters - the same address is the retry
                        // loop's business, and a resolve on every attempt would
                        // reset its backoff and hammer a sleeping TV.
                        val current = state.value
                        val moved = current.device?.let {
                            it.host != device.host || it.port != device.port
                        } ?: true
                        if (RemotePhonePrefs.token(appContext, device.name) != null &&
                            current.phase != RemotePhase.CONNECTED &&
                            current.phase != RemotePhase.CONNECTING &&
                            (current.phase != RemotePhase.RECONNECTING || moved)
                        ) {
                            connect(device)
                        }
                    }
                }
            )
        }.onFailure { error ->
            Log.w(TAG, "resolve threw: ${error.javaClass.simpleName}")
        }
    }

    private fun stopDiscovery() {
        val listener = discovery ?: return
        discovery = null
        runCatching {
            val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            manager?.stopServiceDiscovery(listener)
        }
    }

    // ── The socket ────────────────────────────────────────────────────────

    private fun openSocket(device: RemoteDevice) {
        socket?.close(1000, "reconnect")
        socket = null

        val request = Request.Builder()
            .url("ws://${device.host}:${device.port}${RemoteRules.WEBSOCKET_PATH}")
            .build()

        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                webSocket.send(
                    RemoteProtocol.hello(
                        phoneId(),
                        RemotePhonePrefs.token(appContext, device.name)
                    )
                )
                pendingPair?.let { held ->
                    pendingPair = null
                    webSocket.send(held)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleReply(device, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failed: ${t.javaClass.simpleName}")
                scheduleReconnect(device)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect(device)
            }
        })
    }

    private fun handleReply(device: RemoteDevice, raw: String) {
        when (val reply = RemoteProtocol.parseReply(raw)) {
            is RemoteProtocol.Reply.Paired -> {
                RemotePhonePrefs.saveToken(appContext, device.name, reply.token)
                state.value = state.value.copy(
                    phase = RemotePhase.CONNECTED,
                    device = device,
                    pinRejected = false,
                    message = null
                )
            }

            is RemoteProtocol.Reply.PairFailed -> {
                state.value = state.value.copy(
                    phase = RemotePhase.NEEDS_PIN,
                    pinRejected = true,
                    message = "That PIN was not accepted. Check the code on the TV."
                )
            }

            is RemoteProtocol.Reply.Ready -> {
                state.value = state.value.copy(
                    phase = RemotePhase.CONNECTED,
                    device = device,
                    tvName = RemoteRules.deviceLabel(reply.name),
                    pinRejected = false,
                    message = null
                )
            }

            RemoteProtocol.Reply.AuthRequired ->
                state.value = state.value.copy(
                    phase = RemotePhase.NEEDS_PIN,
                    device = device,
                    pinRejected = false,
                    message = null
                )

            is RemoteProtocol.Reply.PlayResult -> {
                val line = when {
                    reply.ok && reply.mode == "resume" -> "Playing on TV"
                    reply.ok -> "Opened on TV"
                    else -> "The TV could not open that title"
                }
                state.value = state.value.copy(message = line)
            }

            null -> Unit
        }
    }

    private fun scheduleReconnect(device: RemoteDevice) {
        // A socket the viewer closed (leaving the screen, forgetting the TV) is
        // not a drop: whatever state replaced it stays.
        if (!wantConnection) return
        // Nothing to reconnect WITH until this phone has paired: retrying a TV
        // that never answered would leave the screen saying "reconnecting"
        // forever, so the list comes back with the reason instead.
        if (RemotePhonePrefs.token(appContext, device.name) == null) {
            wantConnection = false
            state.value = state.value.copy(
                phase = RemotePhase.NO_DEVICE,
                message = "Couldn't reach ${device.name}. Check the TV is open on the same Wi-Fi."
            )
            return
        }
        state.value = state.value.copy(phase = RemotePhase.RECONNECTING, device = device)
        // The TV can come back on a DIFFERENT address (a Wi-Fi flap re-leases
        // it, and the server's port is per process), so the retry re-resolves as
        // well as re-opening the socket. A fresh record re-enters through
        // resolve(); a moved one replaces the target the retry is holding.
        stopDiscovery()
        startDiscovery(quiet = true)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attempt += 1
            val waitMs = RETRY_MS[minOf(attempt - 1, RETRY_MS.lastIndex)]
            delay(waitMs)
            if (!wantConnection) return@launch
            openSocket(state.value.device ?: device)
        }
    }

    /**
     * Writes one frame, or drops it.
     *
     * A send before the socket exists is legal and lost: the phone is still
     * connecting, the screen already says so, and a queued press would arrive on
     * a TV nobody was looking at any more. Returns whether it was written.
     */
    private fun send(payload: String, device: RemoteDevice? = null): Boolean {
        val target = device ?: state.value.device ?: return false
        val live = socket ?: return false
        return runCatching { live.send(payload) }.getOrElse {
            scheduleReconnect(target)
            false
        }
    }

    private fun phoneId(): String = RemotePhonePrefs.phoneId(appContext)

    private fun phoneName(): String = RemotePhonePrefs.phoneName()

    /** Wi-Fi (or Ethernet, which is still the household LAN) only - never mobile data. */
    private fun onWifi(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private companion object {
        const val TAG = "RemoteClient"

        const val NO_WIFI_MESSAGE = "No TV found on this Wi-Fi"
        const val NO_TV_MESSAGE = "No TV found on this Wi-Fi. Make sure the TV app is open on the same network."
        const val NOT_CONNECTED_MESSAGE = "Connect to your TV first."

        /** How long discovery may run before the screen says nothing was found. */
        const val DISCOVERY_WINDOW_MS = 6_000L

        val RETRY_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L)

        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}

/**
 * One client per process.
 *
 * The Remote screen and a title's "Play on TV" button must agree about which TV
 * is connected, so they share this instance rather than each discovering and
 * pairing on their own.
 */
internal object RemoteClientProvider {

    @Volatile
    private var client: RemoteClient? = null

    fun get(context: Context): RemoteClient =
        client ?: synchronized(this) {
            client ?: RemoteClient(context).also { client = it }
        }
}

/**
 * This phone's identity and the tokens it holds for each TV.
 *
 * Device-local: a pairing is a statement about this phone and that TV, and it
 * never rides the profile sync (the sync blob is account state; a LAN pairing is
 * not). Keyed by the TV's advertised NAME because that is the only identity the
 * advertisement carries.
 */
internal object RemotePhonePrefs {

    private const val PREFS = "kbstream_remote_phone"

    private const val KEY_PHONE_ID = "phone_id"
    private const val KEY_TOKEN_PREFIX = "token_"

    fun phoneId(context: Context): String {
        val store = prefs(context)
        val existing = store?.getString(KEY_PHONE_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val fresh = UUID.randomUUID().toString()
        runCatching { store?.edit()?.putString(KEY_PHONE_ID, fresh)?.apply() }
        return fresh
    }

    /** The phone's model, which is what the TV's paired list shows. */
    fun phoneName(): String = RemoteRules.deviceLabel(android.os.Build.MODEL)

    fun token(context: Context, tvName: String): String? =
        prefs(context)?.getString(KEY_TOKEN_PREFIX + key(tvName), null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun saveToken(context: Context, tvName: String, token: String) {
        runCatching {
            prefs(context)?.edit()?.putString(KEY_TOKEN_PREFIX + key(tvName), token)?.apply()
        }
    }

    fun clearToken(context: Context, tvName: String) {
        runCatching { prefs(context)?.edit()?.remove(KEY_TOKEN_PREFIX + key(tvName))?.apply() }
    }

    private fun key(tvName: String): String =
        tvName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").ifEmpty { "tv" }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
