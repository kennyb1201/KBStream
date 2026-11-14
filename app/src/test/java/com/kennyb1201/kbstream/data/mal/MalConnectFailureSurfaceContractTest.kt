package com.kennyb1201.kbstream.data.mal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed MAL connect says why - on screen and in the paste (spec 2026-11-14).
 *
 * On build 0.9.308 Kenny approved KBStream on MAL, was sent back to the app, and
 * was simply not connected. The reason existed exactly once, in logcat
 * (`Log.w("MalLinks", "MAL connect failed: …")`) - a surface he cannot reach -
 * because the callback branch of `routeMalLink` only logged, while the start
 * branch next to it already showed its failure. A dialog cannot carry the message
 * either: by the time the browser returns, the dialog is gone.
 *
 * So there is ONE sentence and ONE path: the same reason that lands on screen is
 * the one COPY DIAGNOSTICS prints, and nothing else from the link travels with it.
 * This suite pins both halves, plus the boundary - a link that is neither a
 * callback nor a start must show and record nothing, because "no message and no
 * paste entry" is its own diagnosis (the link never reached the router).
 */
class MalConnectFailureSurfaceContractTest {

    private val activity by lazy { source(ACTIVITY) }

    // ── F1: the callback branch shows its reason ──────────────────────────

    @Test
    fun `the callback branch shows its failure instead of only logging it`() {
        val router = functionBody(activity, "fun routeMalLink(link: String?) {")
        assertTrue(
            "the exchange's Failed result must reach the surface: $router",
            router.contains("showMalFailure(result.reason)")
        )
        assertTrue(
            "and only a Failed result does - a successful connect shows nothing",
            router.contains("if (result is MalAuth.ConnectResult.Failed)")
        )
        val dedupe = router.indexOf("handledMalCallback = link")
        val exchange = router.indexOf("malRouteScope.launch {")
        val show = router.indexOf("showMalFailure(result.reason)")
        assertTrue("the dedupe guard comes first", dedupe >= 0 && exchange > dedupe)
        assertTrue(
            "the show sits inside the one-exchange-per-link guard, so a warm+cold " +
                "double routing cannot show it twice",
            show > exchange
        )
    }

    @Test
    fun `both branches go through one surface, so they cannot drift apart`() {
        val router = functionBody(activity, "fun routeMalLink(link: String?) {")
        assertTrue(
            "the start branch's own sentence still comes from its rules",
            router.contains("malStartFailureMessage(")
        )
        assertTrue(
            "and it is shown by the same helper the callback uses",
            router.contains("showMalFailure(reason)")
        )
        assertEquals(
            "one sentence builder, one place - a second copy is how a branch goes " +
                "silent again",
            1,
            countOf(activity, "malFailureMessage(")
        )
    }

    @Test
    fun `the message is app-wide, with a toast where no shell exists`() {
        val helper = functionBody(activity, "fun showMalFailure(reason: String) {")
        assertTrue(
            "the feedback host is drawn above whatever screen the return landed on",
            helper.contains("val host = malFeedback") &&
                helper.contains("host.show(message, isError = true)")
        )
        assertTrue(
            "and a node with no shell still says it out loud",
            helper.contains("Toast.makeText(context, message, Toast.LENGTH_LONG).show()")
        )
        assertTrue(
            "logcat keeps its record too - it is just no longer the only one",
            helper.contains("Log.w(\"MalLinks\", message)")
        )
    }

    // ── F2: the same sentence reaches COPY DIAGNOSTICS ────────────────────

    @Test
    fun `the shown sentence and the recorded one are the same value`() {
        val helper = functionBody(activity, "fun showMalFailure(reason: String) {")
        val built = helper.indexOf("val message = ")
        val recorded = helper.indexOf("CrashReporter.recordEvent(\"mal\", message)")
        val shown = helper.indexOf("host.show(message, isError = true)")
        assertTrue("the sentence is built first", built >= 0)
        assertTrue(
            "the paste records exactly what the screen shows - the source is 'mal' " +
                "so it is findable under recent errors",
            recorded > built
        )
        assertTrue("and the screen shows that same value", shown > built)
    }

    @Test
    fun `a failing link's message carries nothing from the link`() {
        // The whole promise of S4: the code, the state and the expected state are
        // in the failing link, and none of them may appear in what is shown or
        // recorded. The reason is one of the flow's fixed sentences.
        val outcome = malRedirectOutcome(
            "kbstream://oauth/mal?code=THE_CODE_VALUE&state=THE_STATE_VALUE",
            expectedState = "EXPECTED_STATE_VALUE"
        )
        assertTrue("this link fails on the state", outcome is MalRedirect.Ignored)
        val message = malFailureMessage((outcome as MalRedirect.Ignored).reason)

        listOf("THE_CODE_VALUE", "THE_STATE_VALUE", "EXPECTED_STATE_VALUE", "code=", "state=")
            .forEach { secret ->
                assertFalse(
                    "the message must not carry '$secret': $message",
                    message.contains(secret)
                )
            }
        assertTrue(
            "it does name the actual problem",
            message.contains("MAL didn't connect") && message.contains("state")
        )
    }

    @Test
    fun `a refusal names the error and never the code`() {
        val outcome = malRedirectOutcome(
            "kbstream://oauth/mal?error=access_denied&code=THE_CODE_VALUE",
            expectedState = "anything"
        )
        assertTrue(outcome is MalRedirect.Refused)
        val message = malFailureMessage(
            "MAL authorization was refused (${(outcome as MalRedirect.Refused).error})"
        )
        assertTrue(message.contains("access_denied"))
        assertFalse(message.contains("THE_CODE_VALUE"))
    }

    // ── S6 boundary: a link that is neither form says nothing ─────────────

    @Test
    fun `a link that is neither callback nor start shows and records nothing`() {
        val router = functionBody(activity, "fun routeMalLink(link: String?) {")
        val startGuard = router.indexOf("if (!MalLinks.isStart(link)) return")
        assertTrue("the router rejects other links", startGuard >= 0)
        assertTrue(
            "before anything can be shown or recorded - no message and no paste " +
                "entry is the signature of a link that never reached the router",
            router.indexOf("showMalFailure(") in 0..startGuard
        )
        assertTrue(
            "and the callback arm is only entered for a callback",
            router.indexOf("if (MalLinks.isCallback(link))") in 0 until startGuard
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                }
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
        const val ACTIVITY = "java/com/kennyb1201/kbstream/MainActivity.kt"
    }
}
