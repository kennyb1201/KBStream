package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which activity owns the trackers' OAuth callbacks (spec 2026-11-14, F1).
 *
 * Both `kbstream://oauth/...` filters were declared on VoiceSearchActivity,
 * whose `onCreate` treats a VIEW intent as a title deep link and calls
 * `finish()` on anything else. A redirect from MAL or AniList was therefore
 * accepted by the system, handed to the wrong activity, parsed as a title,
 * rejected and thrown away - the phone paste showed a bare
 * `oauth arrival cold: launcher intent, no data` while the routers in
 * MainActivity never saw a link. The filters moved whole; these pins make sure
 * they cannot drift back, and that the activity they left keeps its own links.
 *
 * Pinned by reading the manifest: the module's JVM tests have no
 * PackageManager to resolve intents with. A link matching two activities is
 * also the failure S4 describes, so the count matters as much as the owner.
 */
class OAuthCallbackActivityContractTest {

    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val activity by lazy { source("src/main/java/com/kennyb1201/kbstream/MainActivity.kt") }
    private val voice by lazy { source("src/main/java/com/kennyb1201/kbstream/ui/search/VoiceSearchActivity.kt") }

    private val mainBlock by lazy { activityBlock(".MainActivity") }
    private val voiceBlock by lazy { activityBlock(".ui.search.VoiceSearchActivity") }

    @Test
    fun `exactly one activity declares each OAuth callback, and it is MainActivity`() {
        val owner = ".MainActivity"
        listOf("/mal" to "MAL", "/anilist" to "AniList").forEach { (path, name) ->
            assertEquals(
                "$name: one declaration, or the system shows a chooser (S4)",
                1,
                countOf(manifest, "android:pathPrefix=\"$path\"")
            )
            assertTrue(
                "$name: the callback must be declared on $owner",
                mainBlock.contains("android:pathPrefix=\"$path\"")
            )
            assertFalse(
                "$name: and not on VoiceSearchActivity, which finishes non-title links",
                voiceBlock.contains("android:pathPrefix=\"$path\"")
            )
        }
        assertEquals(
            "the oauth host belongs to MainActivity's two filters and nobody else",
            2,
            countOf(manifest, "android:host=\"oauth\"")
        )
    }

    @Test
    fun `the filters moved whole - VIEW, DEFAULT and BROWSABLE`() {
        listOf("/mal" to "MAL", "/anilist" to "AniList").forEach { (path, name) ->
            val filter = filterContaining(mainBlock, "android:pathPrefix=\"$path\"")
            assertTrue("$name: the browser sends a VIEW", filter.contains("android.intent.action.VIEW"))
            assertTrue("$name: DEFAULT makes it resolvable at all", filter.contains("android.intent.category.DEFAULT"))
            assertTrue(
                "$name: BROWSABLE is what lets the browser hand the redirect back",
                filter.contains("android.intent.category.BROWSABLE")
            )
            assertTrue("$name: the app scheme, unchanged", filter.contains("android:scheme=\"kbstream\""))
            assertTrue("$name: the host, unchanged", filter.contains("android:host=\"oauth\""))
        }
    }

    @Test
    fun `VoiceSearchActivity keeps the links it does handle`() {
        assertTrue("the TV remote's mic", voiceBlock.contains("android.intent.action.SEARCH"))
        assertTrue("and \"play <title>\"", voiceBlock.contains("android.media.action.MEDIA_PLAY_FROM_SEARCH"))
        assertTrue(
            "the app's own play action, which the fulfillment names",
            voiceBlock.contains("com.kennyb1201.kbstream.action.PLAY_TITLE")
        )
        assertTrue(
            "a picked global-search suggestion still resolves here (S5)",
            voiceBlock.contains("android:host=\"title\"")
        )
        assertFalse(
            "nothing OAuth-shaped is left on the activity that finishes such links",
            voiceBlock.contains("android:host=\"oauth\"")
        )
    }

    @Test
    fun `MainActivity keeps the launcher, the shortcuts and the assistant link`() {
        assertTrue(mainBlock.contains("android.intent.action.MAIN"))
        assertTrue(mainBlock.contains("android.intent.category.LAUNCHER"))
        assertTrue(mainBlock.contains("android.intent.category.LEANBACK_LAUNCHER"))
        assertTrue(mainBlock.contains("android:host=\"assistant\""))
        assertTrue(
            "the shortcut intents resolve through this filter",
            mainBlock.contains("android.app.shortcuts")
        )
        assertTrue(
            "singleTop is what keeps a warm callback on the running instance",
            mainBlock.contains("android:launchMode=\"singleTop\"")
        )
    }

    @Test
    fun `the move was the whole fix - the routers already handle both arrivals`() {
        assertTrue(
            "cold: the activity's own intent",
            activity.contains("routeMalLink(hostActivity?.intent?.data?.toString())") &&
                activity.contains("routeAniListLink(hostActivity?.intent?.data?.toString())")
        )
        assertTrue(
            "warm: the intent delivered to onNewIntent",
            activity.contains("routeMalLink(incomingIntent?.data?.toString()") &&
                activity.contains("routeAniListLink(incomingIntent?.data?.toString())")
        )
        assertTrue(
            "the kill site is untouched and still the reason the filters moved: " +
                "a VIEW link that is not a title finishes this activity",
            voice.contains("TitleDeepLink.parse(") && voice.contains("finish()")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun activityBlock(name: String): String {
        val anchor = "android:name=\"$name\""
        val at = manifest.indexOf(anchor)
        require(at >= 0) { "activity not declared: $name" }
        val end = manifest.indexOf("</activity>", at)
        require(end > at) { "activity block unclosed: $name" }
        return manifest.substring(at, end)
    }

    private fun filterContaining(block: String, token: String): String {
        val at = block.indexOf(token)
        require(at >= 0) { "no filter carrying $token" }
        val start = block.lastIndexOf("<intent-filter>", at)
        val end = block.indexOf("</intent-filter>", at)
        require(start >= 0 && end > at) { "no filter around $token" }
        return block.substring(start, end)
    }

    private fun source(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "$prefix$relativePath")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relativePath")
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
}
