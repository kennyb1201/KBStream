package com.kennyb1201.kbstream.data.mal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two QR items in the anime tracker dialogs (spec 2026-11-14).
 *
 * F1: AniList's dialog had a long authorize URL as plain text and nothing else,
 * so a viewer on a couch typed a URL with a remote. The same shared code panel
 * MAL, BingeCat, Simkl and Trakt already use now sits beside that text - on the
 * TV only, because on the phone a code would be scanned by the device holding
 * it.
 *
 * F2: MAL's QR scans, offers KBStream, opens the app, and then nothing. Three
 * separate silences made that unreadable: a build with no app id returned `false`
 * from `openAuthorize` and said nothing; the two arrival shapes each carried
 * their own copy of the routing `when`, so one could go quiet alone; and the warm
 * callback's exchange ran inside the effect keyed on `incomingIntent`, which the
 * warm deep-link consumer clears - cancelling the exchange mid-flight.
 *
 * Pinned by reading the source: the module has no Compose or Robolectric
 * harness, and each failure here is silent in the field - a dialog that shows no
 * code, an app that opens onto the same screen, a connect that never lands.
 */
class MalQrArrivalsContractTest {

    private val aniList by lazy { source(ANILIST) }
    private val malDialog by lazy { source(MAL_DIALOG) }
    private val activity by lazy { source(ACTIVITY) }
    private val links by lazy { source(LINKS) }

    // ── F1: AniList's authorize link as a code, TV only ───────────────────

    @Test
    fun `the TV dialog draws the authorize URL as a code, beside the same URL as text`() {
        val flat = flatten(aniList)
        assertTrue(
            "the code is the dialog's own authorize URL, not a second link builder",
            flat.contains("KBQrCode(url = authorizeUrl)")
        )
        assertTrue(
            "the URL stays as text for a viewer who would rather type it",
            flat.contains("1. Open this link on your phone") &&
                flat.contains("\$authorizeUrl")
        )
        assertEquals(
            "one code: a second would be a second URL to keep in step",
            1,
            flat.count("KBQrCode(")
        )
        val gate = flat.substringAfter("if (!phone) {").substringBefore("}")
        assertTrue(
            "the code is gated on the surface - a phone cannot scan its own screen",
            gate.contains("KBQrCode(url = authorizeUrl)")
        )
    }

    @Test
    fun `a build with no AniList app id shows the text and no code`() {
        val flat = flatten(aniList)
        val nullArm = flat.substringAfter("authorizeUrl == null ->").substringBefore("else ->")
        assertTrue(
            "the existing build-problem sentence stays",
            nullArm.contains("no AniList app id")
        )
        assertFalse(
            "there is no URL to encode, so a code here would encode nothing",
            nullArm.contains("KBQrCode")
        )
    }

    // ── F2a: the silence is what made the QR look broken ──────────────────

    @Test
    fun `the MAL QR's caption says the phone's build needs the app id`() {
        val flat = flatten(malDialog)
        assertTrue(
            "the one thing the TV cannot see is which build the scanning phone runs",
            flat.contains("The phone needs KBStream built with the MAL app id")
        )
        assertTrue(
            "and the code still opens the app - the scheme was never the suspect",
            links.contains("""fun startLink(): String = MAL_REDIRECT_URI + "?connect=1"""")
        )
    }

    @Test
    fun `a start link that cannot open MAL says so instead of returning into silence`() {
        val router = flatten(routerBody())
        assertTrue(
            "the outcome is turned into a sentence at the one place that opens the page",
            router.contains("malStartFailureMessage( MalLinks.openAuthorize(context) ) ?: return")
        )
        assertTrue(
            "and an opened page shows nothing at all",
            router.indexOf("malStartFailureMessage") < router.indexOf("host.show")
        )
        assertTrue(
            "the sentence goes through the app's one feedback channel",
            router.contains("host.show(message, isError = true)")
        )
        assertTrue(
            "with a toast only where no shell exists above the node",
            router.contains("Toast.makeText(context, message, Toast.LENGTH_LONG).show()")
        )
    }

    // ── F2b: one router, two arrivals, and an exchange nothing cancels ────

    @Test
    fun `both arrival shapes reach the one router`() {
        val flat = flatten(activity)
        val warm = flat.substringAfter("// Warm arrival:").substringBefore("// Warm deep links:")
        assertTrue(
            "the warm arrival is the running singleTop activity's new intent",
            warm.contains("LaunchedEffect(incomingIntent)") &&
                warm.contains("routeMalLink(incomingIntent?.data?.toString()")
        )
        val cold = flat.substringAfter("// Cold launch:").substringBefore("val handledByExtras")
        assertTrue(
            "the cold arrival is the activity's own launch intent",
            cold.contains("routeMalLink(hostActivity?.intent?.data?.toString())")
        )
        assertEquals(
            "one place decides what a MAL link means, not one per arrival",
            1,
            flat.count("MalLinks.openAuthorize(context)")
        )
        assertEquals(
            "and one place starts the connect",
            1,
            flat.count("MalAuth.completeConnect(context, link)")
        )
    }

    @Test
    fun `the callback exchange is launched off the effect that saw the link`() {
        val router = flatten(routerBody())
        assertFalse(
            "the router is not an effect: an effect keyed on the intent is exactly " +
                "what the warm consumer below restarts",
            router.contains("LaunchedEffect(")
        )
        val launchedAt = router.indexOf("malRouteScope.launch {")
        val exchangeAt = router.indexOf("MalAuth.completeConnect(context, link)")
        assertTrue("the exchange runs somewhere: it is the point of the arrival", exchangeAt >= 0)
        assertTrue(
            "in a scope the intent being cleared cannot cancel",
            launchedAt in 0 until exchangeAt
        )
    }

    @Test
    fun `one callback link is exchanged once`() {
        val router = flatten(routerBody())
        val guardAt = router.indexOf("if (handledMalCallback == link) return")
        val launchAt = router.indexOf("malRouteScope.launch {")
        assertTrue("the guard exists", guardAt >= 0)
        assertTrue(
            "and it is checked before the exchange starts, so a replayed intent " +
                "cannot fail a connect that already worked",
            guardAt in 0 until launchAt
        )
        assertTrue(
            "the code is single use, so the guard keys on the whole link",
            router.contains("handledMalCallback = link")
        )
        assertTrue(
            "a start link is a constant - scanning the QR again must open the browser " +
                "again, so it is never filtered by that guard",
            router.contains("if (!MalLinks.isStart(link)) return")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** The router plus the scope it launches into, up to the first arrival effect. */
    private fun routerBody(): String =
        activity.slice("val malRouteScope = rememberCoroutineScope()", "// Warm arrival:")

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

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun String.count(token: String): Int {
        var found = 0
        var from = indexOf(token)
        while (from >= 0) {
            found++
            from = indexOf(token, from + token.length)
        }
        return found
    }

    private companion object {
        const val SRC = "java/com/kennyb1201/kbstream/"
        const val MAL = "${SRC}data/mal/"
        const val ANILIST = "${SRC}ui/settings/AniListConnectDialog.kt"
        const val MAL_DIALOG = "${SRC}ui/settings/MalConnectDialog.kt"
        const val ACTIVITY = "${SRC}MainActivity.kt"
        const val LINKS = "${MAL}MalLinks.kt"
    }
}
