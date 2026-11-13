package com.kennyb1201.kbstream.data.remote

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.kennyb1201.kbstream.MainActivity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The TV end of the phone-as-a-remote channel (spec 2026-11-13, F1–F3).
 *
 * ## One socket, on the LAN, nothing else
 *
 * A WebSocket server bound to the TV's OWN address - never `0.0.0.0` - so the
 * port exists only on the interface the TV is on. No cloud relay, no account, no
 * internet route: NSD advertises it to the same network and the phone connects
 * to the address it resolved. A peer that is not on a private address is refused
 * even if a hostile mDNS answer pointed the phone somewhere else.
 *
 * The server is started from [MainApplication] only on a TV build
 * ([FormFactor.isTv]); a phone running the same APK never opens a port.
 *
 * ## Auth
 *
 * An unauthenticated connection may do exactly one thing: present the PIN the TV
 * is showing. A correct PIN stores a per-phone token and replies with it; every
 * later command must present that token. After the first pairing a phone
 * reconnects with its token and never sees a PIN again - which is what makes S1
 * and S5 work without asking the viewer anything.
 *
 * ## Keys are the physical remote's
 *
 * A key message is handed to [RemoteKeyInjector], which dispatches the same
 * KeyEvent a remote would. The server does not know what screen is showing, and
 * never drives one itself.
 */
internal class RemoteServer(
    private val context: Context,
    host: String,
    port: Int,
    private val deviceName: String
) : NanoWSD(host, port) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val main = Handler(Looper.getMainLooper())

    /**
     * A plain GET answers who this is.
     *
     * Deliberately `serveHttp` and not `serve`: NanoWSD's own `serve` is what
     * upgrades a WebSocket request, and overriding it would answer the upgrade
     * with this text instead of the handshake. Only non-WebSocket requests reach
     * here.
     */
    override fun serveHttp(session: IHTTPSession): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/plain",
            "KBStream remote $deviceName"
        )

    override fun openWebSocket(handshake: IHTTPSession): WebSocket =
        Socket(handshake)

    private inner class Socket(handshake: IHTTPSession) : WebSocket(handshake) {

        private val peer: String? = handshake.remoteIpAddress

        private var authenticated = false

        override fun onOpen() {
            // Defence in depth: NSD only resolves LAN services, but a spoofed
            // record must not turn this into a listener for the internet.
            if (!RemoteRules.isLanHost(peer)) {
                Log.w(TAG, "refusing a non-LAN peer")
                runCatching { close(WebSocketFrame.CloseCode.PolicyViolation, "lan only", false) }
            }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) = Unit

        override fun onMessage(message: WebSocketFrame) {
            val text = runCatching { message.textPayload }.getOrNull() ?: return
            when (val incoming = RemoteProtocol.decode(text)) {
                is RemoteProtocol.Incoming.Hello -> hello(incoming)
                is RemoteProtocol.Incoming.Pair -> pair(incoming)
                is RemoteProtocol.Incoming.Key -> {
                    if (!authenticated) {
                        sendSafely(RemoteProtocol.authRequired())
                    } else {
                        RemoteKeyInjector.press(context, incoming.code)
                    }
                }
                is RemoteProtocol.Incoming.Play -> play(incoming)
                RemoteProtocol.Incoming.Unknown -> Unit
            }
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            Log.w(TAG, "socket closed: ${exception.javaClass.simpleName}")
        }

        private fun hello(message: RemoteProtocol.Incoming.Hello) {
            val stored = RemotePairings.tokenFor(context, message.phoneId)
            authenticated = RemoteRules.tokenMatches(stored, message.token)
            if (authenticated) {
                sendSafely(RemoteProtocol.ready(deviceName))
            } else {
                // Unpaired, or the TV forgot the phone (S5): the phone asks for
                // a PIN again, and a PIN is put on the TV's screen if none is
                // live so the viewer always has something to read. The TV also
                // says the code out loud (a toast over whatever is showing),
                // which is the spec's "prompt when a phone asks to pair" without
                // a dialog that would steal D-pad focus from the screen the
                // viewer is on.
                promptForPairing(RemotePairings.ensurePin())
                sendSafely(RemoteProtocol.authRequired())
            }
        }

        /**
         * Puts the code on the TV's screen the moment an unpaired phone asks.
         *
         * A toast rather than a dialog on purpose: it is additive, it cannot
         * take focus from a screen, and it disappears by itself. The row in
         * Settings stays the place the code can be read again.
         */
        private fun promptForPairing(pin: String) {
            main.post {
                runCatching {
                    Toast.makeText(
                        context,
                        "Phone wants to connect. Pair code $pin (Settings → Remote)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        private fun pair(message: RemoteProtocol.Incoming.Pair) {
            if (!RemotePairings.consumePin(message.pin)) {
                sendSafely(RemoteProtocol.pairFailed("pin"))
                return
            }
            val token = RemoteRules.newToken()
            RemotePairings.pair(context, message.phoneId, message.phoneName, token)
            authenticated = true
            Log.i(TAG, "paired a phone")
            sendSafely(RemoteProtocol.paired(token))
        }

        private fun play(message: RemoteProtocol.Incoming.Play) {
            if (!authenticated) {
                sendSafely(RemoteProtocol.authRequired())
                return
            }
            scope.launch {
                val plan = RemotePlayBus.resolve(context, message.id, message.mediaType)
                if (plan == null) {
                    sendSafely(RemoteProtocol.playResult(false, null, "unknown title"))
                    return@launch
                }
                RemotePlayBus.offer(plan)
                // The TV resolves add-ons, picks a source and resumes; the phone
                // is told which of those the plan is, never the stream itself.
                val mode = if (plan.resume != null) "resume" else "detail"
                sendSafely(RemoteProtocol.playResult(true, mode))
                startMainActivity()
            }
        }

        private fun startMainActivity() {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }.onFailure { error ->
                Log.w(TAG, "could not bring the app forward: ${error.javaClass.simpleName}")
            }
        }

        private fun sendSafely(payload: String) {
            runCatching { send(payload) }
        }
    }

    companion object {
        private const val TAG = "RemoteServer"
    }
}

/**
 * Advertises the TV on the local network (spec 2026-11-13, F1).
 *
 * The record carries the device name and the port, and nothing else: no user, no
 * profile, no id that could be tracked across a network change. Registration is
 * best-effort - a TV whose NSD fails still serves the phone that connects by
 * address from a previous session, and a failure is logged, never thrown.
 */
internal object RemoteAdvertiser {

    private const val TAG = "RemoteNsd"

    private var registration: NsdManager.RegistrationListener? = null

    fun advertise(context: Context, port: Int, deviceName: String) {
        if (registration != null) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = RemoteRules.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising '${info.serviceName}'")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advertisement failed: $errorCode")
                registration = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        }.onFailure { error ->
            Log.w(TAG, "could not advertise: ${error.javaClass.simpleName}")
        }
    }
}

/**
 * Starts the TV side once per process.
 *
 * Nothing here is a lifecycle service: the channel lives exactly as long as the
 * app's process does, which is what "the TV advertises while it is running"
 * means, and a force-stopped app is correctly unreachable.
 */
internal object RemoteControlRuntime {

    private const val TAG = "RemoteControl"

    @Volatile
    private var server: RemoteServer? = null

    @Volatile
    var isServing: Boolean = false
        private set

    fun start(context: Context) {
        if (server != null) return
        val host = lanAddress() ?: run {
            Log.i(TAG, "no LAN address; the remote stays off")
            return
        }
        val name = deviceName()
        val started = runCatching {
            RemoteServer(context.applicationContext, host, 0, name).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            }
        }.getOrElse { error ->
            Log.w(TAG, "server did not start: ${error.javaClass.simpleName}")
            null
        } ?: return
        server = started
        isServing = true
        RemoteAdvertiser.advertise(context.applicationContext, started.listeningPort, name)
        Log.i(TAG, "serving on $host:${started.listeningPort}")
    }

    /** The name the phone lists: the TV's own model, which is what a viewer calls it. */
    fun deviceName(): String = RemoteRules.deviceLabel(Build.MODEL)

    /**
     * The address the server binds to.
     *
     * Wi-Fi first (the spec's case), then any other private IPv4 - a TV on an
     * Ethernet cable is still the same household network, and refusing to serve
     * there would make the feature look broken on a wired box. Never `0.0.0.0`:
     * the point is that no port exists off the LAN interface.
     */
    private fun lanAddress(): String? = runCatching {
        val candidates = ArrayList<Pair<String, String>>()
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
            if (!nif.isUp || nif.isLoopback) return@forEach
            nif.inetAddresses.toList().orEmpty().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress &&
                    RemoteRules.isLanHost(address.hostAddress)
                ) {
                    candidates += nif.name.orEmpty() to address.hostAddress.orEmpty()
                }
            }
        }
        candidates.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: candidates.firstOrNull()?.second
    }.getOrNull()
}
