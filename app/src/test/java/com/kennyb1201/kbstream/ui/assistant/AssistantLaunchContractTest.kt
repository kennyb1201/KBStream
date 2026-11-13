package com.kennyb1201.kbstream.ui.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the Assistant wiring is declared and where it lands (spec 2026-11-13,
 * "Google Assistant control spec").
 *
 * Three properties matter, and none of them is visible in a single file:
 *
 *  - the declarations are the ones Google's CURRENT docs accept (`shortcuts.xml`
 *    with `capability` elements, not the deprecated `actions.xml`; the app
 *    feature BII is `actions.intent.OPEN_APP_FEATURE`, and it requires inline
 *    inventory);
 *  - "play <title>" lands in the EXISTING resolver - the file the TV's
 *    `MEDIA_PLAY_FROM_SEARCH` filter already targets - so there is no second
 *    search and no second ranking;
 *  - every id in the XML is a destination [AssistantLaunch] knows, and every
 *    screen that the ids name actually consumes it.
 */
class AssistantLaunchContractTest {

    private val shortcuts by lazy { resFile("xml/shortcuts.xml") }
    private val arrays by lazy { resFile("values/arrays.xml") }
    private val strings by lazy { resFile("values/strings.xml") }
    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml") }
    private val mainActivity by lazy { source("com/kennyb1201/kbstream/MainActivity.kt") }
    private val tvHome by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val phoneHome by lazy { source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt") }
    private val voiceSearch by lazy { source("com/kennyb1201/kbstream/ui/search/VoiceSearchActivity.kt") }
    private val mpvSession by lazy { source("com/kennyb1201/kbstream/ui/player/MpvMediaSession.kt") }

    // ── The vocabulary ───────────────────────────────────────────────────

    @Test
    fun `a feature extra and a shortcut link name the same destinations`() {
        listOf(
            AssistantLaunch.CONTINUE_WATCHING,
            AssistantLaunch.SEARCH,
            AssistantLaunch.LIBRARY
        ).forEach { destination ->
            assertEquals(
                "the extra Assistant fills in for $destination",
                destination,
                AssistantLaunch.destination(destination, null)
            )
            assertEquals(
                "and the link a launcher shortcut for it carries",
                destination,
                AssistantLaunch.destination(null, AssistantLaunch.linkFor(destination))
            )
        }
    }

    @Test
    fun `nothing unrecognised is routed, and the feature wins when both are present`() {
        assertNull("no extra and no link is an ordinary launch", AssistantLaunch.destination(null, null))
        assertNull(
            "an unknown feature must open the app, never a guessed screen",
            AssistantLaunch.destination("settings", null)
        )
        assertNull(
            "an unknown link host is not ours",
            AssistantLaunch.destination(null, "kbstream://title/603")
        )
        assertNull(
            "a link on the right host with an unknown id routes nowhere",
            AssistantLaunch.destination(null, AssistantLaunch.LINK_PREFIX + "nowhere")
        )
        assertEquals(
            "the fulfillment's own choice outranks the link the same intent carries",
            AssistantLaunch.LIBRARY,
            AssistantLaunch.destination(AssistantLaunch.LIBRARY, AssistantLaunch.linkFor(AssistantLaunch.SEARCH))
        )
    }

    // ── F1: the declarations ─────────────────────────────────────────────

    @Test
    fun `nothing here aims at one installed package`() {
        // The debug build installs BESIDE the release one (applicationIdSuffix
        // ".debug"), and shortcuts.xml - a resource file - cannot carry the
        // `${packageName}` placeholder. A hardcoded package would send the debug
        // install's shortcuts to the release app.
        assertFalse(
            "no hardcoded package in the shortcut/capability intents",
            shortcuts.contains("android:targetPackage=")
        )
        assertTrue(
            "the app's own destinations resolve through this activity's filter",
            manifest.contains("android:host=\"assistant\"") &&
                manifest.contains("android:scheme=\"kbstream\"")
        )
        assertTrue(
            "and the play-title action through the voice-search activity's",
            manifest.contains("android:name=\"${AssistantLaunch.ACTION_PLAY_TITLE}\"")
        )
    }

    @Test
    fun `the declarations use shortcuts xml, the form Google still supports`() {
        assertTrue(
            "the app's shortcuts/capabilities file is referenced from the manifest",
            manifest.contains("android.app.shortcuts") && manifest.contains("@xml/shortcuts")
        )
        assertFalse(
            "actions.xml is deprecated and must not be reintroduced",
            File(resRoot(), "xml/actions.xml").isFile
        )
        assertTrue(
            "the app feature BII is the current one",
            shortcuts.contains("<capability android:name=\"actions.intent.OPEN_APP_FEATURE\">")
        )
        assertTrue(
            "OPEN_APP_FEATURE requires a parameter-less fallback intent",
            openAppFeatureBlock().let { block ->
                Regex("<intent[^>]*>").findAll(block).count() >= 2
            }
        )
    }

    @Test
    fun `open KBStream features are an inline inventory of the three destinations`() {
        val block = openAppFeatureBlock()
        assertTrue(
            "the capability passes the matched shortcut id under the feature extra",
            block.contains("android:key=\"${AssistantLaunch.EXTRA_FEATURE}\"")
        )
        listOf(
            AssistantLaunch.CONTINUE_WATCHING to "assistant_feature_continue_watching",
            AssistantLaunch.SEARCH to "assistant_feature_search",
            AssistantLaunch.LIBRARY to "assistant_feature_library"
        ).forEach { (id, synonyms) ->
            val shortcut = shortcutBlock(id)
            assertTrue(
                "$id is bound to the app feature BII",
                shortcut.contains("<capability-binding android:key=\"actions.intent.OPEN_APP_FEATURE\">")
            )
            assertTrue(
                "$id supplies its feature names as inline inventory",
                shortcut.contains("<parameter-binding") &&
                    shortcut.contains("android:key=\"${AssistantLaunch.EXTRA_FEATURE}\"") &&
                    shortcut.contains("android:value=\"@array/$synonyms\"")
            )
            assertTrue(
                "the synonyms array for $id exists",
                arrays.contains("<string-array name=\"$synonyms\">")
            )
        }
        assertTrue(
            "a shortcut with no label cannot be suggested, and labels must be resources",
            Regex("<shortcut\\s[^>]*android:shortcutShortLabel=\"@string/[^\"]+\"")
                .findAll(shortcuts).count() == 3
        )
    }

    @Test
    fun `play a title is a custom intent into the existing resolver`() {
        assertTrue(
            "no BII exists for playing an arbitrary title, so the route is a custom intent",
            shortcuts.contains("android:name=\"custom.actions.intent.PLAY_TITLE\"")
        )
        assertTrue(
            "and it carries query patterns, which custom intents require",
            shortcuts.contains("app:queryPatterns=\"@array/assistant_play_title_queries\"") &&
                arrays.contains("<string-array name=\"assistant_play_title_queries\">")
        )
        // The one hard requirement of the spec: the spoken title reaches the
        // handler the TV's media action already uses, under the extra that
        // handler already reads (SearchManager.QUERY == "query").
        val block = capabilityBlock("custom.actions.intent.PLAY_TITLE")
        assertTrue(
            "it is fulfilled with this app's own action, filtered by the voice-search activity",
            block.contains("android:action=\"${AssistantLaunch.ACTION_PLAY_TITLE}\"") &&
                manifest.contains("android:name=\"${AssistantLaunch.ACTION_PLAY_TITLE}\"")
        )
        assertTrue(
            "which routes it into the SAME resolver as the platform media action",
            voiceSearch.contains("intent?.action == ACTION_MEDIA_PLAY_FROM_SEARCH") &&
                voiceSearch.contains("AssistantLaunch.ACTION_PLAY_TITLE") &&
                Regex("openSpokenTitle\\(").findAll(voiceSearch).count() == 2
        )
        assertTrue(
            "and the title arrives in the extra the resolver reads",
            block.contains("android:key=\"query\"") &&
                voiceSearch.contains("getStringExtra(SearchManager.QUERY)")
        )
        assertTrue(
            "a custom intent parameter must declare a schema-org type",
            block.contains("android:mimeType=\"https://schema.org/Text\"")
        )
        assertTrue(
            "the resolution itself is still the one function",
            voiceSearch.contains("private suspend fun resolveSpokenTitle(") &&
                Regex("fun resolveSpokenTitle\\(").findAll(voiceSearch).count() == 1
        )
    }

    // ── F3: the launcher shortcuts ───────────────────────────────────────

    @Test
    fun `the launcher shortcuts deep link to the destinations they name`() {
        listOf(
            AssistantLaunch.CONTINUE_WATCHING,
            AssistantLaunch.SEARCH,
            AssistantLaunch.LIBRARY
        ).forEach { id ->
            val block = shortcutBlock(id)
            assertTrue(
                "$id launches with a VIEW the shell's filter answers",
                block.contains("android:action=\"android.intent.action.VIEW\"")
            )
            assertTrue(
                "$id carries the link AssistantLaunch reads",
                block.contains("android:data=\"${AssistantLaunch.linkFor(id)}\"")
            )
        }
    }

    // ── Where they land ──────────────────────────────────────────────────

    @Test
    fun `the shell routes the three ids onto existing screens`() {
        assertTrue(
            "the intent is read through the shared parser",
            mainActivity.contains("AssistantLaunch.destination(")
        )
        assertTrue(
            "Continue Watching opens Home and asks the rail to land",
            mainActivity.contains("AssistantLaunch.CONTINUE_WATCHING ->") &&
                mainActivity.contains("HomeContinueWatchingRequest.request()") &&
                mainActivity.contains("screen = Screen.Home")
        )
        assertTrue(
            "Search is the existing screen",
            mainActivity.contains("AssistantLaunch.SEARCH ->") &&
                mainActivity.contains("screen = Screen.Search")
        )
        assertTrue(
            "Library is the existing screen",
            mainActivity.contains("AssistantLaunch.LIBRARY ->") &&
                mainActivity.contains("screen = Screen.Library")
        )
    }

    @Test
    fun `both Home layouts answer the Continue Watching request`() {
        assertTrue(
            "the TV Home lands the rail through its own verified landing",
            tvHome.contains("HomeContinueWatchingRequest.signal") &&
                tvHome.contains("HomeContinueWatchingRequest.consume()") &&
                tvHome.contains("continueWatchingLandOnHeadSignal++")
        )
        assertTrue(
            "the phone Home, which has no focus landing, scrolls the column to the rail",
            phoneHome.contains("HomeContinueWatchingRequest.consume()") &&
                phoneHome.contains("listState.animateScrollToItem(0)")
        )
        assertFalse(
            "no new screens: the request is a landing, not a destination",
            phoneHome.contains("Screen.Home")
        )
    }

    // ── F2: the transport session the assistant drives ───────────────────

    @Test
    fun `the phone's mpv session advertises and answers fast forward and rewind`() {
        assertTrue(
            "the actions are advertised",
            mpvSession.contains("PlaybackState.ACTION_FAST_FORWARD") &&
                mpvSession.contains("PlaybackState.ACTION_REWIND")
        )
        assertTrue(
            "and answered by moving the playhead, which is what 'skip ahead 30 seconds' asks for",
            mpvSession.contains("override fun onFastForward() = seekBy(SKIP_STEP_MS)") &&
                mpvSession.contains("override fun onRewind() = seekBy(-SKIP_STEP_MS)")
        )
        assertTrue(
            "with the seek going through the player's existing seek path",
            mpvSession.contains("override fun onSeekTo(pos: Long) = this@MpvMediaSession.onSeek(pos)")
        )
        assertTrue(
            "the poster and title the spec requires are published",
            mpvSession.contains("MediaMetadata.METADATA_KEY_TITLE") &&
                mpvSession.contains("MediaMetadata.METADATA_KEY_ALBUM_ART_URI")
        )
    }

    // ── Plumbing ────────────────────────────────────────────────────────

    private fun capabilityBlock(name: String): String =
        shortcuts.substring(shortcuts.indexOf("<capability android:name=\"$name\"")).let { tail ->
            tail.substring(0, tail.indexOf("</capability>"))
        }

    private fun openAppFeatureBlock(): String = capabilityBlock("actions.intent.OPEN_APP_FEATURE")

    private fun shortcutBlock(id: String): String {
        val marker = "<shortcut"
        var searchFrom = 0
        while (true) {
            val start = shortcuts.indexOf(marker, searchFrom)
            assertTrue("shortcut $id missing", start >= 0)
            val end = shortcuts.indexOf("</shortcut>", start)
            val block = shortcuts.substring(start, end)
            if (block.contains("android:shortcutId=\"$id\"")) return block
            searchFrom = end
        }
    }

    /** `app/src/main/res`, found from whichever directory the test runs in. */
    private fun resRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/res")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main res root not found")
    }

    private fun resFile(relative: String): String = File(resRoot(), relative).readText()

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
}
