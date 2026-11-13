package com.kennyb1201.kbstream.data.remote

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the phone remote is wired (spec 2026-11-13).
 *
 * [RemoteRulesTest] pins the rules; this pins the properties that only exist
 * across files, and those are the ones a refactor breaks silently:
 *
 * - the TV's changes are additive (a server, an advertisement, a key dispatch)
 *   and never a second navigation path;
 * - the channel is LAN-only on both ends, with no cloud, no account and no
 *   token beyond the pairing's own;
 * - the phone's door is its Settings row, not Home and not the bottom bar;
 * - "Play on TV" reuses the existing Detail + pendingTarget resume path;
 * - and both ends stay fail-soft: nothing throws at the viewer.
 */
class RemoteControlWiringContractTest {

    private val server by lazy { source(REMOTE_SERVER) }
    private val rules by lazy { source(REMOTE_RULES) }
    private val protocol by lazy { source(REMOTE_PROTOCOL) }
    private val pairings by lazy { source(REMOTE_PAIRINGS) }
    private val injector by lazy { source(REMOTE_INJECTOR) }
    private val playBus by lazy { source(REMOTE_PLAY_BUS) }
    private val client by lazy { source(REMOTE_CLIENT) }
    private val screen by lazy { source(REMOTE_SCREEN) }
    private val tvSection by lazy { source(REMOTE_TV_SECTION) }
    private val activity by lazy { source(MAIN_ACTIVITY) }
    private val application by lazy { source(MAIN_APPLICATION) }
    private val tvSettings by lazy { source(TV_SETTINGS) }
    private val phoneSettings by lazy { source(PHONE_SETTINGS) }
    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml") }
    private val catalog by lazy { repoFile("gradle/libs.versions.toml") }
    private val gradle by lazy { repoFile("app/build.gradle.kts") }
    private val notices by lazy { repoFile("THIRD_PARTY_NOTICES.md") }
    private val player by lazy { source(MPV_PLAYER) }

    // ── F1: discovery and pairing ──────────────────────────────────────────

    @Test
    fun `the TV advertises the spec's service type and nothing but its name`() {
        assertTrue(
            "the NSD service type is the spec's",
            rules.contains("const val SERVICE_TYPE = \"_kbstream-remote._tcp\"")
        )
        assertTrue(
            "the TV registers it",
            server.contains("manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)")
        )
        assertTrue(
            "and advertises under the device's own model name",
            server.contains("serviceName = deviceName") &&
                server.contains("Build.MODEL")
        )
        assertFalse(
            "no profile, account or user id rides the advertisement",
            Regex("serviceInfo\\s*\\.\\s*setAttribute|NsdServiceInfo\\(\\)[\\s\\S]{0,400}setAttribute")
                .containsMatchIn(server)
        )
    }

    @Test
    fun `the channel exists only on the TV's own LAN address`() {
        assertTrue(
            "the server binds a specific LAN address, never 0.0.0.0",
            server.contains("RemoteServer(context.applicationContext, host, 0, name)") &&
                server.contains("val host = lanAddress()")
        )
        assertTrue(
            "and a peer off the LAN is refused",
            server.contains("if (!RemoteRules.isLanHost(peer))")
        )
        assertTrue(
            "the phone refuses to resolve such an address too",
            client.contains("if (!RemoteRules.isLanHost(host)) return")
        )
        assertFalse(
            "nothing here talks to a cloud, an account or a relay",
            listOf(server, client, rules, protocol).any {
                it.contains("Supabase") || it.contains("retrofit") || it.contains("https://")
            }
        )
    }

    @Test
    fun `pairing is a four-digit code, five minutes, once per phone, remembered by token`() {
        assertTrue(
            "the code is four digits",
            rules.contains("const val PIN_DIGITS = 4")
        )
        assertTrue(
            "five minutes",
            rules.contains("const val PIN_TTL_MS = 5L * 60L * 1000L")
        )
        assertTrue(
            "a used code is consumed, not reused",
            pairings.contains("fun consumePin(") && pairings.contains("if (accepted) pinState.value = null")
        )
        assertTrue(
            "a correct code stores a per-phone token",
            server.contains("RemotePairings.pair(context, message.phoneId, message.phoneName, token)")
        )
        assertTrue(
            "and every later command must present it",
            server.contains("RemoteRules.tokenMatches(stored, message.token)")
        )
        assertTrue(
            "the PIN lives in memory, never on disk (it would outlive its expiry)",
            pairings.contains("private val pinState = MutableStateFlow<RemotePin?>")
        )
        assertTrue(
            "pairings are device-local and never synced",
            !pairings.contains("SYNCED") && pairings.contains("deliberately NOT profile-scoped")
        )
    }

    @Test
    fun `the TV can forget a phone, and the phone can forget a TV`() {
        assertTrue(
            "the TV's Settings row forgets a paired phone",
            tvSection.contains("RemotePairings.forget(context, phone.id)")
        )
        assertTrue(
            "the phone can drop its stored token",
            client.contains("fun forgetTv()") && client.contains("RemotePhonePrefs.clearToken")
        )
        assertTrue(
            "an unpaired phone's arrival puts the code on the TV's screen",
            server.contains("promptForPairing(RemotePairings.ensurePin())") &&
                server.contains("Settings → Remote")
        )
        assertTrue(
            "and after a forget the TV asks for a PIN again",
            server.contains("RemotePairings.ensurePin()") &&
                server.contains("RemoteProtocol.authRequired()")
        )
    }

    // ── F2: keys ───────────────────────────────────────────────────────────

    @Test
    fun `a key press is the same KeyEvent a physical remote sends`() {
        assertTrue(
            "dispatched to the activity in front, not routed by the server",
            injector.contains("activity.dispatchKeyEvent(down)") &&
                injector.contains("activity.dispatchKeyEvent(up)")
        )
        assertTrue(
            "the D-pad maps to the D-pad codes",
            injector.contains("\"UP\" -> KeyEvent.KEYCODE_DPAD_UP") &&
                injector.contains("\"OK\" -> KeyEvent.KEYCODE_DPAD_CENTER") &&
                injector.contains("\"BACK\" -> KeyEvent.KEYCODE_BACK")
        )
        assertTrue(
            "transport maps to the codes the player already handles",
            injector.contains("\"PLAY_PAUSE\" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE") &&
                injector.contains("\"SEEK_BACK\" -> KeyEvent.KEYCODE_MEDIA_REWIND") &&
                injector.contains("\"SEEK_FORWARD\" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD") &&
                player.contains("KEYCODE_MEDIA_REWIND")
        )
        assertTrue(
            "HOME goes to the launcher, because an app cannot dispatch a system key",
            injector.contains("Intent.CATEGORY_HOME")
        )
        assertTrue(
            "volume and mute go through AudioManager, for the same reason",
            injector.contains("AudioManager.ADJUST_RAISE") &&
                injector.contains("AudioManager.ADJUST_LOWER") &&
                injector.contains("AudioManager.ADJUST_TOGGLE_MUTE")
        )
        assertTrue(
            "the activity tracker is installed once, from the application",
            application.contains("RemoteKeyInjector.install(this)")
        )
        assertTrue(
            "with nothing in front a press is dropped, never an error",
            injector.contains("val activity = current?.get() ?: return")
        )
        assertFalse(
            "the server never drives a screen itself - it only dispatches keys",
            server.contains("screen = Screen.") || server.contains("navTo")
        )
    }

    @Test
    fun `the phone's entry is its Settings row, not Home and not the bottom bar`() {
        assertTrue(
            "a Remote row exists in the phone's Settings",
            phoneSettings.contains("label = \"Remote\"") &&
                phoneSettings.contains("onClick = onOpenRemote")
        )
        assertTrue(
            "the TV's Settings page owns the pairing code",
            tvSettings.contains("RemoteSection()")
        )
        assertTrue(
            "and it navigates to Screen.Remote",
            activity.contains("onOpenRemote = { screen = Screen.Remote }") &&
                activity.contains("object Remote : Screen()")
        )
        assertTrue(
            "reached from the Settings route only",
            activity.contains("is Screen.Remote ->")
        )
        assertFalse(
            "no Remote entry in the bottom bar",
            activity.contains("MobileNavDestination.REMOTE")
        )
        assertFalse(
            "and no Remote card on Home",
            playBus.contains("onOpenRemote") || screen.contains("BottomBar")
        )
    }

    @Test
    fun `the remote screen has every key the spec lists, with haptics`() {
        listOf(
            "UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME",
            "PLAY_PAUSE", "SEEK_BACK", "SEEK_FORWARD",
            "VOLUME_UP", "VOLUME_DOWN", "MUTE"
        ).forEach { key ->
            assertTrue("$key is on the screen", screen.contains("press(\"$key\")"))
        }
        assertTrue(
            "every press is haptic",
            screen.contains("HapticFeedbackConstants.KEYBOARD_TAP")
        )
    }

    @Test
    fun `a dead channel is a message with a retry, never a crash`() {
        assertTrue(
            "no TV found says so",
            screen.contains("\"No TV found on this Wi-Fi\"") &&
                client.contains("NO_TV_MESSAGE")
        )
        assertTrue(
            "with a retry",
            screen.contains("KBButton(label = \"TRY AGAIN\"")
        )
        assertTrue(
            "mobile data is refused before discovery even starts",
            client.contains("TRANSPORT_WIFI") &&
                client.contains("TRANSPORT_ETHERNET") &&
                client.contains("if (!onWifi())")
        )
        assertTrue(
            "a dropped socket retries by itself and says reconnecting",
            client.contains("RemotePhase.RECONNECTING") &&
                client.contains("private fun scheduleReconnect(") &&
                screen.contains("Reconnecting")
        )
        assertTrue(
            "animating and joining are wrapped, never thrown at the viewer",
            client.contains("runCatching {") && server.contains("runCatching {")
        )
    }

    // ── F3: play on TV ─────────────────────────────────────────────────────

    @Test
    fun `play on TV reuses the existing resume path instead of a second player route`() {
        assertTrue(
            "the phone sends only a title id and its type",
            client.contains("RemoteProtocol.play(id, mediaType)") &&
                protocol.contains("const val TYPE_PLAY = \"play\"")
        )
        assertTrue(
            "the TV resolves the stored resume row",
            playBus.contains("dao.getResumeForParents(listOf(parentId))")
        )
        assertTrue(
            "and routes through the Continue Watching destination builder",
            activity.contains("cwWidgetDetailScreen(") &&
                activity.contains("RemotePlayBus.plans.collect")
        )
        assertTrue(
            "a title without progress opens its Detail page instead",
            activity.contains("Screen.Detail(\n                    type = plan.mediaType,")
        )
        assertTrue(
            "the phone is told which of the two happened",
            server.contains("val mode = if (plan.resume != null) \"resume\" else \"detail\"") &&
                screen.contains("Connected to")
        )
        assertTrue(
            "the confirmation the viewer sees",
            client.contains("\"Playing on TV\"")
        )
        assertFalse(
            "no stream URL and no add-on call crosses the channel",
            protocol.contains("streamUrl") || protocol.contains("addon")
        )
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    @Test
    fun `the dependency is declared once and credited`() {
        assertTrue(
            "the catalog carries NanoHTTPD's WebSocket module",
            catalog.contains("nanohttpd-websocket = { module = \"org.nanohttpd:nanohttpd-websocket\"")
        )
        assertTrue(
            "and the module uses it",
            gradle.contains("implementation(libs.nanohttpd.websocket)")
        )
        assertTrue(
            "with its licence named in the notices",
            notices.contains("NanoHTTPD") || notices.contains("nanohttpd")
        )
    }

    @Test
    fun `the manifest gains only what the channel needs`() {
        assertTrue(
            "network state, so the phone knows it is on Wi-Fi",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE")
        )
        assertTrue(
            "and multicast, which NSD uses on some networks",
            manifest.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE")
        )
        assertFalse(
            "and nothing that would expose the TV beyond the LAN",
            manifest.contains("android.permission.ACCESS_WIFI_STATE")
        )
    }

    @Test
    fun `the TV starts the server, a phone never does`() {
        assertTrue(
            "started from the application, gated on the TV form factor",
            application.contains("if (FormFactor.isTv)") &&
                application.contains("RemoteControlRuntime")
        )
        assertTrue(
            "off the launch path",
            application.contains("startupScope.launch {\n                    com.kennyb1201.kbstream.data.remote.RemoteControlRuntime")
        )
        assertTrue(
            "and the TV is the only side that advertises",
            server.contains("fun advertise(") && !client.contains("registerService(")
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun repoFile(vararg candidates: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            candidates.forEach { relative ->
                val file = File(dir, relative)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo file not found: ${candidates.joinToString()}")
    }

    private companion object {
        const val REMOTE = "com/kennyb1201/kbstream/data/remote/"
        const val REMOTE_SERVER = "${REMOTE}RemoteServer.kt"
        const val REMOTE_RULES = "${REMOTE}RemoteRules.kt"
        const val REMOTE_PROTOCOL = "${REMOTE}RemoteProtocol.kt"
        const val REMOTE_PAIRINGS = "${REMOTE}RemotePairings.kt"
        const val REMOTE_INJECTOR = "${REMOTE}RemoteKeyInjector.kt"
        const val REMOTE_PLAY_BUS = "${REMOTE}RemotePlayBus.kt"
        const val REMOTE_CLIENT = "${REMOTE}RemoteClient.kt"
        const val REMOTE_SCREEN = "com/kennyb1201/kbstream/ui/remote/RemoteScreen.kt"
        const val REMOTE_TV_SECTION = "com/kennyb1201/kbstream/ui/settings/RemoteSection.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val MAIN_APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val PHONE_SETTINGS =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val MPV_PLAYER =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
