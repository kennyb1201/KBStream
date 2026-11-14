package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OAuth trace, wired (spec 2026-11-14, F1/F2/F3).
 *
 * [OAuthTraceTest] proves the strings are shape-only. This pins where they are
 * written, because the field failure was an ABSENCE: on 0.9.310 both connects
 * failed with nothing on screen and "recent errors: none this session" in the
 * paste. The arrival must be recorded before any routing (including when there is
 * no data at all), each router's verdict must be recorded - duplicates included -
 * and an AniList exchange failure must reach the same show + record path MAL's
 * does.
 *
 * The events go to `CrashReporter.recordEvent`, which is exactly what
 * `Diagnostics.appendRecentErrors` prints under "recent errors" - so a line
 * recorded here IS a line in the next COPY DIAGNOSTICS paste.
 */
class OAuthArrivalTracingContractTest {

    private val activity by lazy { source(ACTIVITY) }

    // ── F1: every arrival, before routing ─────────────────────────────────

    @Test
    fun `the warm arrival is recorded in onNewIntent`() {
        val body = functionBody(activity, "override fun onNewIntent(intent: Intent) {")
        assertTrue(
            "the arrival is recorded where the activity receives it: $body",
            body.contains("recordOAuthArrival(warm = true, link = intent.data?.toString())")
        )
        val setIntent = body.indexOf("incomingIntent = intent")
        val record = body.indexOf("recordOAuthArrival(")
        assertTrue("the intent is kept first", setIntent >= 0)
        assertTrue(
            "and the arrival recorded after it is stored, before compose can route",
            record > setIntent
        )
    }

    @Test
    fun `the cold arrival is recorded before either router runs`() {
        // Several effects are LaunchedEffect(Unit); this slices the one that
        // records the cold arrival by finding it first.
        val marker = "recordOAuthArrival(warm = false, link = hostActivity?.intent?.data?.toString())"
        val at = activity.indexOf(marker)
        assertTrue("the cold launch must record its arrival", at >= 0)
        val start = activity.lastIndexOf("LaunchedEffect(Unit) {", at)
        assertTrue("the record must live in a cold-launch effect", start >= 0 && start < at)
        val cold = activity.substring(start, activity.indexOf("\n    }\n", at))
        assertTrue(
            "the cold launch records its own arrival",
            cold.contains("recordOAuthArrival(warm = false, link = hostActivity?.intent?.data?.toString())")
        )
        val record = cold.indexOf("recordOAuthArrival(warm = false")
        val mal = cold.indexOf("routeMalLink(")
        val anilist = cold.indexOf("routeAniListLink(")
        assertTrue("both routers still run", mal > record && anilist > record)
        assertTrue(
            "the record precedes the routing, so a link refused by both is still named",
            record < mal && record < anilist
        )
    }

    @Test
    fun `the helper records the shape through CrashReporter and skips ordinary deep links`() {
        val body = functionBody(activity, "private fun recordOAuthArrival(warm: Boolean, link: String?) {")
        assertTrue(
            "a non-OAuth deep link is ordinary use, not an arrival",
            body.contains("if (link != null && !OAuthTrace.isOAuthLink(link)) return")
        )
        assertTrue(
            "the line is built by the trace helper, never concatenated here",
            body.contains("CrashReporter.recordEvent(\"oauth\", OAuthTrace.arrivalLine(warm, link))")
        )
    }

    // ── F2: the routers' verdicts, duplicates included ────────────────────

    @Test
    fun `the MAL router records its verdict on every branch`() {
        val router = functionBody(activity, "fun routeMalLink(link: String?) {")
        listOf(
            "duplicate callback ignored",
            "callback, exchange started",
            "start, browser opened",
            "start failed: \$reason"
        ).forEach { verdict ->
            assertTrue("the MAL router must record \"$verdict\": $router", router.contains(verdict))
        }
        // The duplicate record lives inside the guard that swallows the repeat,
        // not somewhere a successful first exchange could reach.
        val guard = router.substringAfter("if (handledMalCallback == link) {")
            .substringBefore("\n            }")
        assertTrue(
            "a repeated delivery must say so where it is swallowed",
            guard.contains("duplicate callback ignored")
        )
        val start = router.substringAfter("if (!MalLinks.isStart(link)) {")
            .substringBefore("return\n        }")
        assertTrue(
            "and a MAL link the router cannot classify is named with its params",
            start.contains("OAuthTrace.notARouteVerdict(link)") &&
                start.contains("OAuthTrace.isFor(link, \"mal\")")
        )
    }

    @Test
    fun `the AniList router records its verdict and shows its failure`() {
        val router = functionBody(activity, "fun routeAniListLink(link: String?) {")
        listOf("duplicate callback ignored", "callback, exchange started").forEach { verdict ->
            assertTrue("the AniList router must record \"$verdict\": $router", router.contains(verdict))
        }
        assertTrue(
            "a repeated code is recorded where it is swallowed",
            router.substringAfter("if (handledAniListCode == identity) {")
                .substringBefore("\n        }")
                .contains("duplicate callback ignored")
        )
        assertTrue(
            "and a link this router cannot classify is named too",
            router.contains("OAuthTrace.notARouteVerdict(link)") &&
                router.contains("OAuthTrace.isFor(link, \"anilist\")")
        )
        assertTrue(
            "a failed exchange now goes through the shared failure surface",
            router.contains("showAniListFailure(result.reason)")
        )
        assertFalse(
            "the log-only path is what the spec is replacing",
            router.contains("android.util.Log.w(\"AniListLinks\", \"AniList connect failed")
        )
    }

    // ── F3: AniList's failure looks like MAL's ────────────────────────────

    @Test
    fun `an AniList failure logs, records and shows, exactly once per attempt`() {
        val helper = functionBody(activity, "fun showAniListFailure(reason: String) {")
        assertTrue(
            "one sentence builder, so screen and paste cannot differ",
            helper.contains("anilistFailureMessage(reason)")
        )
        assertTrue(
            "the paste carries it under the anilist source",
            helper.contains("CrashReporter.recordEvent(\"anilist\", message)")
        )
        assertTrue(
            "and it is shown app-wide with the toast fallback",
            helper.contains("host.show(message, isError = true)") &&
                helper.contains("Toast.makeText(context, message, Toast.LENGTH_LONG).show()")
        )
        assertTrue(
            "logcat keeps its record too",
            helper.contains("Log.w(\"AniListLinks\", message)")
        )
    }

    // ── privacy: no oauth record may be built anywhere but OAuthTrace ─────

    @Test
    fun `no oauth record is built from anything but the shape helper`() {
        val calls = Regex(
            "recordEvent\\(\\s*\"oauth\",\\s*([^\\)]*)",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).findAll(activity).toList()
        assertTrue("the trace must actually record something", calls.size >= 4)
        calls.forEach { call ->
            val argument = call.groupValues[1].trim()
            assertTrue(
                "every oauth record must be an OAuthTrace line, was: $argument",
                argument.startsWith("OAuthTrace.")
            )
        }
        listOf("recordEvent(\"oauth\", link", "recordEvent(\"oauth\", code").forEach { leak ->
            assertFalse("a raw value must never reach the record: $leak", activity.contains(leak))
        }
    }

    @Test
    fun `the paste really prints these lines`() {
        val diagnostics = source(DIAGNOSTICS)
        assertTrue(
            "recent errors is the section COPY DIAGNOSTICS prints",
            diagnostics.contains("recent errors (") && diagnostics.contains("error.source")
        )
        val reporter = source(REPORTER)
        assertTrue(
            "and recordEvent is the entry point it reads",
            reporter.contains("fun recordEvent(source: String, summary: String)") &&
                reporter.contains("fun recentErrors(): List<RecentError>")
        )
        assertTrue(
            "the oauth source is a plain tag, so a paste can be grepped for it",
            activity.contains("\"oauth\",")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private fun functionBody(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("header not found: $header", start >= 0)
        val stop = source.indexOf("\n    }\n", start + header.length)
        assertTrue("body end not found for: $header", stop > start)
        return source.substring(start, stop)
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

    private companion object {
        const val PKG = "com/kennyb1201/kbstream/"
        const val ACTIVITY = "${PKG}MainActivity.kt"
        const val DIAGNOSTICS = "${PKG}data/reporting/Diagnostics.kt"
        const val REPORTER = "${PKG}data/reporting/CrashReporter.kt"
    }
}
