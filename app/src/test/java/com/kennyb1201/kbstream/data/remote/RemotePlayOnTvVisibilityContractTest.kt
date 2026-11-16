package com.kennyb1201.kbstream.data.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAY ON TV shows only while a TV is connected (spec 2026-11-16).
 *
 * The phone's Detail button rendered unconditionally and answered a tap with
 * "Connect to your TV first" - a promise the screen had no business making, since
 * the connection only ever lived while the Remote screen was open. Gating the
 * button alone would have been wrong in both directions: the phase can still read
 * CONNECTED after the screen closes (stop() never reset it) while the socket is
 * gone, and it reads IDLE for a paired TV the viewer has not opened Settings for
 * in this session. So the connection itself now outlives the screen for a PAIRED
 * TV, and the button reads the same flow the Remote screen reads.
 *
 * Source-reading, like the rest of this module's contracts: the socket needs a
 * Context, a LAN and a network stack.
 */
class RemotePlayOnTvVisibilityContractTest {

    private val client by lazy { read(CLIENT) }
    private val screen by lazy { read(SCREEN) }
    private val detail by lazy { read(DETAIL) }
    private val app by lazy { read(APP) }

    private val keepConnected by lazy {
        client.slice("fun keepConnected() {", "fun leaveScreen() {")
    }
    private val leaveScreen by lazy {
        client.slice("fun leaveScreen() {", "fun retry() {")
    }
    private val prefs by lazy {
        client.substringAfter("internal object RemotePhonePrefs {")
    }

    // ── F1: a paired TV stays connected outside the Remote screen ─────────

    @Test
    fun `a paired phone resumes its TV at launch, an unpaired one starts nothing`() {
        assertTrue(
            "the launch hook is phone-only - the TV is the server end of this channel",
            app.contains("if (FormFactor.isTv) {") &&
                app.substringAfter("if (FormFactor.isTv) {").substringBefore("} else {")
                    .contains("RemoteControlRuntime") &&
                app.substringAfter("} else {").substringBefore("}.onFailure")
                    .contains("RemoteClientProvider") &&
                app.contains(".keepConnected()")
        )
        assertTrue(
            "no pairing, no socket: the phone starts nothing at all",
            keepConnected.indexOf("lastTv(appContext) ?: return") < keepConnected.indexOf("wantConnection = true") &&
                keepConnected.contains("if (RemotePhonePrefs.token(appContext, tvName) == null) return")
        )
        assertTrue(
            "and mobile data is not a network to try on",
            keepConnected.startsWith("fun keepConnected() {") &&
                keepConnected.indexOf("if (!onWifi()) return") < keepConnected.indexOf("lastTv(appContext)")
        )
        assertTrue(
            "a remembered address opens a socket without scanning first",
            keepConnected.contains("RemotePhonePrefs.address(appContext, tvName)") &&
                keepConnected.contains("openSocket(known)")
        )
        assertTrue(
            "and only a pairing from before the address existed falls back to a quiet " +
                "resolve of the KNOWN TV - never a visible scan",
            keepConnected.contains("startDiscovery(quiet = true)")
        )
        assertTrue(
            "the socket it opens is the screen's own: same ping, same reconnect",
            client.contains(".pingInterval(15, TimeUnit.SECONDS)") &&
                client.contains("private fun scheduleReconnect(device: RemoteDevice)")
        )
    }

    @Test
    fun `the remembered TV is written on a handshake, and gone on a forget`() {
        assertTrue(
            "a fresh pairing and a Ready both remember the TV it reached",
            client.contains("is RemoteProtocol.Reply.Paired -> {") &&
                client.contains("is RemoteProtocol.Reply.Ready -> {") &&
                Regex("RemotePhonePrefs\\.rememberTv\\(appContext, device\\)").findAll(client).count() == 2
        )
        assertTrue(
            "the name and the address are what the next launch resumes",
            prefs.contains("fun rememberTv(context: Context, device: RemoteDevice)") &&
                prefs.contains("putString(KEY_LAST_TV, device.name)") &&
                prefs.contains("putString(KEY_ADDRESS_PREFIX + key(device.name)")
        )
        assertTrue(
            "forgetting clears the token, the address AND the name - anything left behind " +
                "is a background client resuming a TV the viewer dropped",
            prefs.contains("fun forgetTv(context: Context, tvName: String)") &&
                prefs.contains("remove(KEY_TOKEN_PREFIX + key(tvName))") &&
                prefs.contains("remove(KEY_ADDRESS_PREFIX + key(tvName))") &&
                prefs.contains("remove(KEY_LAST_TV)")
        )
        assertTrue(
            "and the client's own forget closes the socket and stops wanting it",
            client.substringAfter("fun forgetTv() {").substringBefore("// ── Discovery")
                .let { body ->
                    body.contains("wantConnection = false") &&
                        body.contains("socket?.close(1000, \"forget\")")
                }
        )
    }

    @Test
    fun `leaving the screen keeps a paired socket and drops an unpaired one`() {
        assertTrue(
            "discovery is still on-demand: the screen starts it on entry",
            screen.contains("LaunchedEffect(client) { client.start() }")
        )
        assertTrue(
            "and its exit stops discovery, never a paired connection",
            screen.contains("onDispose { client.leaveScreen() }") &&
                leaveScreen.contains("stopDiscovery()")
        )
        assertTrue(
            "an unpaired client keeps the old rule exactly - nothing stays open",
            leaveScreen.contains("keepConnected()") &&
                leaveScreen.contains("if (!wantConnection) stop()")
        )
        assertTrue(
            "opening the screen on a live background connection does not re-handshake it",
            client.substringAfter("fun start() {")
                .substringBefore("fun keepConnected()")
                .contains(
                    "if (wantConnection && state.value.phase == RemotePhase.CONNECTED) return"
                )
        )
    }

    // ── F2: the button is the connection state ────────────────────────────

    @Test
    fun `the Detail button is absent unless the TV is connected`() {
        assertTrue(
            "the phone reads the same flow the Remote screen reads",
            detail.contains("RemoteClientProvider.get(context)") &&
                detail.contains("remoteClient.stateFlow.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "connected means exactly the phase the tap gates on",
            detail.contains("val remoteConnected = remoteState.phase == RemotePhase.CONNECTED")
        )
        val gate = detail.indexOf("if (remoteConnected) {")
        val button = detail.indexOf("label = \"PLAY ON TV\"")
        assertTrue(
            "the button is INSIDE that gate - absent, not disabled",
            gate in 0 until button
        )
        assertEquals(
            "one button, and every render of it goes through that gate",
            1,
            Regex("label = \"PLAY ON TV\"").findAll(detail).count()
        )
    }

    @Test
    fun `the tap is unchanged, including the race`() {
        assertTrue(
            "the press still sends through the same client",
            detail.contains("remoteClient.playOnTv(id, normalizedType)")
        )
        assertTrue(
            "and playOnTv still refuses unless CONNECTED, with the toast for the race " +
                "where the socket drops between the render and the tap",
            client.contains("if (state.value.phase != RemotePhase.CONNECTED) {") &&
                client.contains("message = NOT_CONNECTED_MESSAGE")
        )
    }

    // ── Constraints ───────────────────────────────────────────────────────

    @Test
    fun `no setting, and the TV side is untouched`() {
        assertFalse(
            "visibility is the connection, not a preference",
            detail.contains("AppPreferences.getRemote") ||
                detail.contains("prefs.getBoolean(\"remote")
        )
        assertFalse(
            "the TV's server end is not part of this change",
            client.substringAfter("fun keepConnected() {")
                .substringBefore("fun leaveScreen() {")
                .contains("RemoteServer")
        )
        assertTrue(
            "pairings stay per-TV-name, on this phone, never synced - the doc sits " +
                "above the object, the rule inside it",
            client.contains("Keyed by the TV's advertised NAME") &&
                prefs.contains(
                    "private const val PREFS = \"kbstream_remote_phone\""
                ) &&
                !prefs.contains("SYNCED")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val CLIENT = "com/kennyb1201/kbstream/data/remote/RemoteClient.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/remote/RemoteScreen.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        const val APP = "com/kennyb1201/kbstream/MainApplication.kt"
    }
}
