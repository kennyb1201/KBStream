package com.kennyb1201.kbstream.data.anime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AniList code-grant switch, wired (spec 2026-11-14, F1/F2/F3).
 *
 * [AniListConnectRulesTest] covers the flow's shape. This pins what a JVM test
 * cannot run and every silent failure lives in: that the secret is read by the
 * exchange and by nothing else, that a callback is verified before it is
 * exchanged, that the exchange is only trusted once AniList accepts the token it
 * issued, and that the app receives the redirect at all - cold and warm, once per
 * code, in a scope the warm intent-consumer cannot cancel.
 *
 * Pinned by reading the sources, like the rest of these contracts: the layer talks
 * to Android, OkHttp and the sync object, so nothing here can be driven from a
 * plain JVM test.
 */
class AniListCodeGrantWiringContractTest {

    private val auth by lazy { source(ANILIST_AUTH) }
    private val config by lazy { source(ANILIST_CONFIG) }
    private val activity by lazy { source(MAIN_ACTIVITY) }
    private val dialog by lazy { source(ANILIST_DIALOG) }
    private val manifest by lazy { source(MANIFEST) }

    // ── F1: one secret, one place ─────────────────────────────────────────

    @Test
    fun `the secret is read by the exchange and by nothing else`() {
        val readers = mainSources()
            .filter { file -> file.readText().contains("clientSecret") }
            .map { it.name }
            .sorted()
        assertEquals(
            "a second reader is how a build secret ends up in a log line, a " +
                "diagnostic paste or a sync payload: the build value, the exchange " +
                "that reads it, and the form builder that places it",
            listOf("AniListAuth.kt", "AniListConfig.kt", "AniListConnectRules.kt"),
            readers
        )
        val rules = source(ANILIST_RULES)
        assertTrue(
            "the form builder places it in the body, and that is the only placement",
            rules.contains("\"client_secret\" to clientSecret")
        )
        assertFalse(
            "the flow's rules never log, so the secret cannot reach a log line from here",
            rules.contains("Log.")
        )

        val body = functionBody(auth, "private suspend fun exchange(code: String): Exchange =")
        assertTrue(
            "the store lives in AniListConfig, and the exchange is its one consumer",
            body.contains("val secret = AniListConfig.clientSecret")
        )
        assertTrue(
            "the form body is the only place it is ever placed",
            body.contains("clientSecret = secret")
        )
        assertFalse(
            "the form (which contains the secret) must never be logged",
            body.contains("Log.w(TAG, form") || body.contains("Log.i(TAG, form")
        )
        assertEquals(
            "and the BuildConfig field itself is behind the config object",
            0,
            mainSources().count { file ->
                file.name != "AniListConfig.kt" &&
                    file.readText().contains("BuildConfig.ANILIST_CLIENT_SECRET")
            }
        )
    }

    @Test
    fun `an unconfigured build can neither show nor run the connect`() {
        assertTrue(
            "the exchange needs id AND secret",
            config.contains(
                "val canConnect: Boolean get() = clientId.isNotBlank() && " +
                    "clientSecret.isNotBlank()"
            )
        )
        val authorize = functionBody(config, "fun authorizeUrl(state: String? = null): String? {")
        assertTrue(
            "no dead authorize URL is handed out",
            authorize.contains("if (!canConnect) return null")
        )
        assertTrue(
            "and the inert row names the missing secret rather than the id",
            config.contains("val missingClientSecret: Boolean get() =")
        )
    }

    // ── F2/F3: the callback, in order ─────────────────────────────────────

    @Test
    fun `a state is stored before the authorize URL leaves this device`() {
        val begin = functionBody(auth, "fun beginConnect(context: Context): String? {")
        val saved = begin.indexOf("savePendingState(state)")
        val handedOut = begin.indexOf("AniListConfig.authorizeUrl(state)")
        assertTrue("the state must be stored", saved >= 0)
        assertTrue(
            "stored BEFORE the browser can come back, or a redirect into a fresh " +
                "process refuses its own callback",
            handedOut > saved
        )
    }

    @Test
    fun `a callback is verified, exchanged, validated, stored and published - in that order`() {
        val complete = functionBody(
            auth,
            "suspend fun completeConnect(context: Context, rawRedirect: String?): ConnectResult {"
        )
        assertTrue(
            "the state check is the rule the redirect parser applies",
            complete.contains("AniListConnectRules.redirectOutcome(rawRedirect, store.pendingState())")
        )
        assertTrue("a code goes to the one exchange path", complete.contains("finishConnect(context, outcome.code)"))
        assertTrue(
            "a refusal is named and clears the pending flow",
            complete.contains("is AniListRedirect.Refused ->") &&
                complete.contains("store.clearPendingState()")
        )
        assertFalse(
            "a refused or unusable link must never reach the exchange",
            functionBody(auth, "is AniListRedirect.Ignored -> ConnectResult.Failed(")
                .contains("finishConnect(")
        )

        val finish = functionBody(auth, "private suspend fun finishConnect(context: Context, code: String): ConnectResult {")
        val exchanged = finish.indexOf("exchange(code)")
        val validated = finish.indexOf("AniListClient.viewer(token)")
        val saved = finish.indexOf("store.save(")
        val published = finish.indexOf("publishAuthToSync(context)")
        assertTrue("the exchange runs", exchanged >= 0)
        assertTrue(
            "AniList has to accept the token before anything is written - otherwise " +
                "the row says Connected for a grant every write refuses",
            validated > exchanged && saved > validated
        )
        assertTrue(
            "and the session travels at connect time: there is no refresh to carry " +
                "it later",
            published > saved
        )
        assertTrue(
            "a failed exchange returns before the save",
            finish.contains("is Exchange.Failed -> return ConnectResult.Failed(exchange.reason)")
        )
    }

    @Test
    fun `the paste fallback refuses a token and keeps the state check`() {
        val connect = functionBody(auth, "suspend fun connect(context: Context, pasted: String): ConnectResult {")
        assertTrue(
            "the whole redirect URL is parsed exactly like a callback",
            connect.contains("is AniListPastedValue.Redirect -> completeConnect(context, value.url)")
        )
        assertTrue(
            "a bare code exchanges",
            connect.contains("is AniListPastedValue.Code -> finishConnect(context, value.code)")
        )
        assertTrue(
            "a token is refused by name, not sent to the exchange",
            connect.contains("ConnectResult.Failed(AniListConnectRules.TOKEN_PASTED_MESSAGE)")
        )
    }

    // ── F3: both arrivals, one exchange, no cancellation ──────────────────

    @Test
    fun `the router survives the warm intent clear and exchanges a code once`() {
        assertTrue(
            "an unkeyed scope: the warm consumer clearing `incomingIntent` restarts " +
                "keyed effects and would cancel the exchange (the MAL race)",
            activity.contains("val anilistRouteScope = rememberCoroutineScope()")
        )
        val router = functionBody(activity, "fun routeAniListLink(link: String?) {")
        assertTrue("the link must be this callback", router.contains("AniListLinks.isCallback(link)"))
        assertTrue(
            "deduped by code: AniList's codes are single-use, so a second exchange " +
                "fails and reports a failure for a connect that already worked",
            router.contains("handledAniListCode == identity") &&
                router.contains("getQueryParameter(\"code\")")
        )
        assertTrue(
            "the exchange runs in the unkeyed scope, not in the effect that noticed it",
            router.contains("anilistRouteScope.launch {")
        )
        assertTrue(
            "the failure is logged rather than swallowed",
            router.contains("AniListAuth.completeConnect(context, link)")
        )
        assertTrue(
            "the warm arrival hands it the running activity's new intent",
            activity.contains("routeAniListLink(incomingIntent?.data?.toString())")
        )
        assertTrue(
            "and the cold launch hands it the activity's own intent",
            activity.contains("routeAniListLink(hostActivity?.intent?.data?.toString())")
        )
        assertEquals(
            "two arrivals and one declaration - no per-shape copy to forget",
            3,
            countOf(activity, "routeAniListLink(")
        )
    }

    @Test
    fun `the manifest receives the app scheme for AniList`() {
        val filter = manifest.substringAfter("kbstream://oauth/anilist?code=")
            .substringBefore("</intent-filter>")
        assertTrue(
            "without this the browser has nowhere to send the code",
            filter.contains("android:action") || filter.contains("android.intent.action.VIEW")
        )
        assertTrue(filter.contains("android:scheme=\"kbstream\""))
        assertTrue(filter.contains("android:host=\"oauth\""))
        assertTrue(filter.contains("android:pathPrefix=\"/anilist\""))
        assertTrue(
            "BROWSABLE is what lets a browser hand the redirect back",
            filter.contains("android.intent.category.BROWSABLE")
        )
    }

    // ── the dialog ────────────────────────────────────────────────────────

    @Test
    fun `the dialog offers the browser on the phone and a code as the fallback`() {
        assertTrue(
            "the phone starts the flow here",
            dialog.contains("AniListLinks.openAuthorize(context)")
        )
        assertTrue(
            "the field is a code, never a token",
            dialog.contains("AniList code")
        )
        assertFalse(
            "a token is the flow AniList removed, and the field must not invite one",
            dialog.contains("AniList token")
        )
        assertTrue(
            "the inert build names the secret it lacks",
            dialog.contains("This build has no AniList app secret")
        )
        assertTrue(
            "and the no-id sentence stays for the other build problem",
            dialog.contains("This build has no AniList app id")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun mainSources(): List<File> =
        File(findSourceRoot(), "com/kennyb1201/kbstream").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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

    private fun countOf(text: String, token: String): Int {
        var found = 0
        var from = text.indexOf(token)
        while (from >= 0) {
            found++
            from = text.indexOf(token, from + token.length)
        }
        return found
    }

    /** [header] through the function's own closing brace. */
    private fun functionBody(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("header not found: $header", start >= 0)
        val stop = source.indexOf("\n    }\n", start + header.length)
        assertTrue("body end not found for: $header", stop > start)
        return source.substring(start, stop)
    }

    private companion object {
        const val ANILIST_AUTH = "com/kennyb1201/kbstream/data/anime/AniListAuth.kt"
        const val ANILIST_RULES = "com/kennyb1201/kbstream/data/anime/AniListConnectRules.kt"
        const val ANILIST_CONFIG = "com/kennyb1201/kbstream/data/anime/AniListConfig.kt"
        const val ANILIST_DIALOG = "com/kennyb1201/kbstream/ui/settings/AniListConnectDialog.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"

        /** The manifest sits beside the java source root, not inside it. */
        const val MANIFEST = "../AndroidManifest.xml"
    }
}
