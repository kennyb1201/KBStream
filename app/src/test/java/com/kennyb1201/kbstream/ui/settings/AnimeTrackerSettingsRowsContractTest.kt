package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anime tracker rows exist on BOTH Settings surfaces (spec 2026-11-13:
 * "MAL on TV + phone, AniList + Kitsu on phone").
 *
 * The field report was two sentences: "still dont see mal anywhere" and "i dont
 * see anilist or kitsu on mobile". The backends had all landed - MAL's OAuth
 * client, token store and sync enrollment; AniList's and Kitsu's connects - and
 * the rows that would reach them were either never built (MAL, both surfaces) or
 * built on the TV only (AniList, Kitsu). A missing row is indistinguishable from
 * a missing backend in the field, which is why this is pinned at the source.
 *
 * Pinned here:
 *  - F1: a MyAnimeList row on both surfaces, drawn by one shared block, with its
 *    four states (not configured / disconnected / connected as <name> /
 *    reconnect needed) and MAL's landed flow - QR on TV, browser on the phone;
 *  - F2: AniList and Kitsu rows on the phone, through the SAME dialogs the TV
 *    raises (no second dialog, no per-surface copy), each dialog drawing the
 *    plate of the surface it was raised on;
 *  - F3: all three rows on both surfaces, no row-only-on-one-surface drift, and
 *    a disconnect that drops that profile's session and touches nothing else;
 *  - the public-username read still exists beside the connects, so a viewer with
 *    a public list needs no account.
 */
class AnimeTrackerSettingsRowsContractTest {

    private val shared by lazy { flat(source(SHARED_ROWS)) }
    private val malDialog by lazy { flat(source(MAL_DIALOG)) }
    private val tv by lazy { flat(source(TV_SETTINGS)) }
    private val phone by lazy { flat(source(PHONE_SETTINGS)) }

    // ── F1: the MAL row, both surfaces ─────────────────────────────────────

    @Test
    fun `the MAL row exists on both surfaces, through one shared block`() {
        assertTrue(
            "the shared block renders all three rows",
            shared.contains("internal fun AnimeTrackerRows(")
        )
        assertTrue(
            "the TV pane asks for it with its own row component",
            tv.contains("AnimeTrackerRows(phone = false) {") &&
                tv.contains("NavigationRow(")
        )
        assertTrue(
            "the phone pane asks for the same block with its own row",
            phone.contains("AnimeTrackerRows(phone = true) {") &&
                phone.contains("SettingsNavRowMobile(")
        )
        assertTrue(
            "the MAL row is the shared block's third row",
            shared.contains("malRowLabel(malState, malViewer)") &&
                shared.contains("malRowDescription(malState, malViewer)")
        )
        assertTrue(
            "and MAL's state is read from the landed auth, not a second source",
            shared.contains("MalAuth.connectionState(context)")
        )
        assertTrue(
            "the not-configured build says so instead of offering a dead connect",
            malDialog.contains("not configured") &&
                malDialog.contains("This is a build problem, not a setting.") &&
                malDialog.contains("Nothing in Settings can add one.")
        )
    }

    @Test
    fun `MAL connects the way it was built - QR on TV, browser on the phone`() {
        assertTrue(
            "the phone starts the landed flow on this device",
            malDialog.contains("MalLinks.openAuthorize(context)")
        )
        assertTrue(
            "the TV hands out the start link as a QR, because a TV has no browser for it",
            malDialog.contains("KBQrCode(url = MalLinks.startLink())") &&
                malDialog.contains("phone = phone")
        )
        assertTrue(
            "the state text distinguishes a dead grant from a healthy session",
            malDialog.contains("reconnect") &&
                malDialog.contains("Disconnecting stops the writes and keeps everything local.")
        )
        assertFalse(
            "no client id and no secret is ever typed into this dialog - it has no input at all",
            malDialog.contains("KBTextField(") ||
                malDialog.contains("KBSecretField") ||
                malDialog.contains("MalConfig.clientId")
        )
        assertTrue(
            "and 'connected as' comes from MAL's own /users/@me read, cached per profile",
            shared.contains("MalApiClient.viewerName(context)") &&
                malDialog.contains("MyAnimeList — connected as ") &&
                malDialog.contains("MyAnimeList — reconnect needed") &&
                malDialog.contains("MyAnimeList — not configured")
        )
    }

    // ── F2: AniList + Kitsu on the phone ───────────────────────────────────

    @Test
    fun `the phone raises the same AniList and Kitsu dialogs as the TV`() {
        assertTrue(
            "the shared block is the only place the three dialogs are raised",
            shared.contains("AniListConnectDialog(") &&
                shared.contains("KitsuConnectDialog(") &&
                shared.contains("MalConnectDialog(")
        )
        listOf(tv to "TV", phone to "phone").forEach { (surface, name) ->
            listOf("AniListConnectDialog(", "KitsuConnectDialog(", "MalConnectDialog(")
                .forEach { dialog ->
                    assertFalse(
                        "$name must not build its own $dialog - one dialog per service, raised by the block",
                        surface.contains(dialog)
                    )
                }
        }
        assertTrue(
            "the phone can reach the shared block without a package move (internal = module scope)",
            phone.contains("import com.kennyb1201.kbstream.ui.settings.AnimeTrackerRows")
        )
        listOf("AniListConnectDialog", "KitsuConnectDialog", "MalConnectDialog").forEach { file ->
            val src = flat(source("$file.kt"))
            assertTrue(
                "$file draws the plate of the surface it was raised on",
                src.contains("AnimeDialogPanel(phone = phone, title =") &&
                    !src.contains("KBDialogPanel(title =")
            )
        }
        assertTrue(
            "AniList's row follows the same not-configured rule as MAL's",
            shared.contains("AniListConnectionState.NotConfigured -> \"AniList — not configured\"") &&
                shared.contains("This build has no AniList app id")
        )
        assertTrue(
            "and both surfaces' rows keep the public-username read beside the connects",
            shared.contains("animeTrackerRowLabel(\"AniList\"") &&
                shared.contains("animeTrackerDescription(\"AniList\"") &&
                shared.contains("animeTrackerRowLabel(\"Kitsu\"") &&
                shared.contains("animeTrackerDescription(\"Kitsu\"") &&
                shared.contains("AppPreferences.setAniListUsername(") &&
                shared.contains("AppPreferences.setKitsuUsername(")
        )
    }

    // ── F3: one row pattern, local-safe disconnect ─────────────────────────

    @Test
    fun `all three rows sit with the other trackers, before the public lists`() {
        val tvHeader = tv.indexOf("AnimeTrackerRows(phone = false)")
        val tvPublicLists = tv.indexOf("SettingsSectionHeader(\"Public lists\")")
        assertTrue(
            "the TV block sits inside the trackers section, before PUBLIC LISTS",
            tvHeader > 0 && tvPublicLists > tvHeader
        )
        val phoneBlock = phone.indexOf("AnimeTrackerRows(phone = true)")
        val phonePublicLists = phone.indexOf("SettingsSectionMobile(\"Public lists\")")
        assertTrue(
            "and so does the phone's",
            phoneBlock > 0 && phonePublicLists > phoneBlock
        )
    }

    @Test
    fun `disconnect drops a session and nothing else`() {
        assertTrue(
            "each tracker's disconnect is its own",
            shared.contains("AniListAuth.disconnect(context)") &&
                shared.contains("KitsuAuth.disconnect(context)") &&
                malDialog.contains("MalAuth.disconnect(context)")
        )
        listOf(
            "markUnwatchedLocal",
            "markEpisodesUnwatchedLocal",
            "deleteAll",
            "clearHistory",
            "removeAllRows"
        ).forEach { destructive ->
            assertFalse(
                "a tracker disconnect must never touch local history ($destructive)",
                shared.contains(destructive) || malDialog.contains(destructive)
            )
        }
        assertTrue(
            "the unconnectable build reads as a state, per profile, not a disabled row",
            shared.contains("MalConnectionState.Connected") &&
                malDialog.contains("MyAnimeList — connected")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun source(fileName: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                listOf(
                    File(candidate, "com/kennyb1201/kbstream/ui/settings/$fileName"),
                    File(candidate, "com/kennyb1201/kbstream/ui/mobile/$fileName")
                ).firstOrNull { it.isFile }?.let { return it.readText() }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "source missing: $fileName (walking up from " + System.getProperty("user.dir") + ")"
        )
    }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val SHARED_ROWS = "AnimeTrackerRows.kt"
        const val MAL_DIALOG = "MalConnectDialog.kt"
        const val TV_SETTINGS = "SettingsScreen.kt"
        const val PHONE_SETTINGS = "SettingsScreenMobile.kt"
    }
}
