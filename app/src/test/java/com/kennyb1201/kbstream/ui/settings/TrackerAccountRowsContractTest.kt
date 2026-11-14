package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every integration row names the connected account (spec 2026-11-14, F1-F4).
 *
 * The field report was one sentence - "Kitsu — connected" with no hint of WHICH
 * Kitsu account - and the expanded rule is that every account-connected row says
 * `Service — connected as <name>`, on the TV and on the phone, from a cached
 * read so an offline entry still answers.
 *
 * The per-service state at landing:
 *  - MAL was already done (its own `malRowLabel`), and stays as it is;
 *  - AniList resolved a name but kept it in the row's DESCRIPTION; the spec moves
 *    it into the label;
 *  - Kitsu had a viewer id at connect and no name anywhere - the store even had
 *    the `viewer_name` key with nothing writing it;
 *  - Simkl and Trakt read the account only for their connect screens;
 *  - BingeCat's mapping already cached the mapped profile's name.
 *
 * Pinned by reading the sources: the rows need a player-less Compose host, the
 * auth objects need Android, and the identity reads need their networks.
 */
class TrackerAccountRowsContractTest {

    private val animeRows by lazy { read("ui/settings/AnimeTrackerRows.kt") }
    private val kitsuAuth by lazy { read("data/kitsu/KitsuAuth.kt") }
    private val kitsuStore by lazy { read("data/kitsu/KitsuTokenStore.kt") }
    private val simklRepo by lazy { read("data/simkl/SimklRepository.kt") }
    private val simklReads by lazy { read("data/simkl/SimklReads.kt") }
    private val traktRepo by lazy { read("data/trakt/TraktRepository.kt") }
    private val labelHelpers by lazy { read("ui/settings/TrackerRowLabels.kt") }
    private val labels by lazy { read("ui/settings/TrackerAccountLabels.kt") }
    private val tvPane by lazy { read("ui/settings/SettingsScreen.kt") }
    private val phonePane by lazy { read("ui/mobile/SettingsScreenMobile.kt") }

    // ── F1: the shared rule ───────────────────────────────────────────────

    @Test
    fun `one helper builds the connected-as label, with a plain fallback`() {
        assertTrue(
            "the helper is pure and lives outside the Compose dialogs, so the fallback is testable",
            labelHelpers.contains("internal fun connectedAsLabel(service: String, name: String?): String") &&
                !labelHelpers.contains("import androidx")
        )
        assertTrue(
            "and it is the only place the phrase is spelled",
            labelHelpers.contains("\"\$service — connected as \$account\"") &&
                labelHelpers.contains("\"\$service — connected\"")
        )
        assertTrue(
            "MAL's own helper already did exactly this, and is not duplicated away",
            read("ui/settings/MalConnectDialog.kt").contains("\"MyAnimeList — connected as \$name\"")
        )
    }

    // ── AniList: the name moves from the description into the label ──────

    @Test
    fun `the connected AniList row names the account in its label`() {
        assertTrue(
            "the label is the connected-as form",
            animeRows.contains("AniListConnectionState.Connected -> connectedAsLabel(\"AniList\", aniListViewer)")
        )
        assertTrue(
            "and the name is no longer a parenthetical in the description",
            !animeRows.contains("(aniListViewer?.let { \" (\$it)\" } ?: \"\")")
        )
        assertTrue(
            "the name still comes from the OAuth session, not the manual username",
            animeRows.contains("AniListAuth.viewerName(context)")
        )
    }

    // ── Kitsu: the reported gap ───────────────────────────────────────────

    @Test
    fun `the connected Kitsu row names the account, and the dialog does too`() {
        assertTrue(
            "the label is the connected-as form, from a resolved name",
            animeRows.contains("connectedAsLabel(\"Kitsu\", kitsuViewer)")
        )
        assertTrue(
            "the unresolved case keeps the public-username label",
            animeRows.contains("animeTrackerRowLabel(\"Kitsu\", kitsuUsername)")
        )
        assertTrue(
            "resolved once per state change, not per frame",
            animeRows.contains("KitsuAuth.viewerName(context)")
        )
        assertTrue(
            "the dialog says which account as well",
            animeRows.contains("viewerName = kitsuViewer,") &&
                read("ui/settings/KitsuConnectDialog.kt").contains("\" as \$connectedAs\"")
        )
    }

    @Test
    fun `Kitsu resolves the display name from the stored viewer, cached`() {
        assertTrue(
            "the store exposes the cached name the row reads",
            kitsuStore.contains("fun viewerName(): String? = read()?.viewerName") &&
                kitsuStore.contains("fun saveViewerName(name: String?)")
        )
        assertTrue(
            "the resolver is cached-first, then ONE read, then null",
            kitsuAuth.contains("suspend fun viewerName(context: Context): String?") &&
                kitsuAuth.contains("store.viewerName()?.let { return it }")
        )
        assertTrue(
            "the read is the same user read the id came from, and its name field",
            kitsuAuth.contains("fetchViewerIdentity(") &&
                kitsuAuth.contains("optJSONObject(\"attributes\")") &&
                kitsuAuth.contains("optString(\"name\")")
        )
        assertTrue(
            "a connect files the name beside the id",
            kitsuAuth.contains("store.saveViewerId(identity.id)") &&
                kitsuAuth.contains("store.saveViewerName(identity.name)")
        )
        assertTrue(
            "and a disconnect drops it with the session, so a reconnect cannot show the old name",
            kitsuStore.contains("?.remove(KEY_VIEWER_NAME)")
        )
    }

    // ── Simkl ─────────────────────────────────────────────────────────────

    @Test
    fun `Simkl caches the account name beside its token`() {
        assertTrue(simklRepo.contains("fun accountName(): String?"))
        assertTrue(simklRepo.contains("fun saveAccountName(name: String?)"))
        assertTrue(
            "the name is cleared with the session",
            simklRepo.contains("?.remove(\n                KEY_ACCOUNT_NAME\n            )")
        )
        assertTrue(
            "cached-first, then the existing users-settings read",
            simklReads.contains("suspend fun SimklRepository.accountDisplayNameImpl(): String?") &&
                simklReads.contains("accountName()?.let { return it }") &&
                simklReads.contains("getAccountInfoImpl()") &&
                simklReads.contains("saveAccountName(name)")
        )
    }

    @Test
    fun `the Trakt name is its cached account slug, read once when absent`() {
        assertTrue(
            "the cached accessor already existed",
            traktRepo.contains("fun accountSlug(): String? = store.accountSlug()")
        )
        assertTrue(
            "and the one read is the connect screen's own, cached too",
            labels.contains("repository.accountSlug()") &&
                labels.contains("repository.loadAccountSlug()")
        )
    }

    // ── F4: BingeCat's mapped profile ─────────────────────────────────────

    @Test
    fun `BingeCat names the mapped profile, from the mapping itself`() {
        assertTrue(
            "the helper reads the mapping's cached name",
            labels.contains("rememberBingeCatProfileName") &&
                labels.contains("repository.mappedProfileName()")
        )
        assertFalse(
            "with no request of its own - the mapping is already on disk",
            labels.contains("repository.profiles(") || labels.contains("BingeCatApi")
        )
        assertTrue(
            "and a mapped-but-no-session row still falls back to the plain name",
            labels.contains("repository.hasToken() && repository.mappedProfileId() != null")
        )
    }

    // ── F3: both surfaces, same wording ───────────────────────────────────

    @Test
    fun `TV and phone rows say the same thing`() {
        listOf("Simkl" to tvPane, "Simkl" to phonePane, "Trakt" to tvPane, "Trakt" to phonePane).forEach { pair ->
            assertTrue(
                "${pair.first} names its account on this surface",
                pair.second.contains("connectedAsLabel(\"${pair.first}\", ")
            )
        }
        assertTrue(tvPane.contains("connectedAsLabel(\"BingeCat\", bingecatProfileName)"))
        assertTrue(phonePane.contains("connectedAsLabel(\"BingeCat\", bingecatProfileName)"))
        assertTrue(
            "the phone has the shared anime rows, Kitsu included",
            phonePane.contains("AnimeTrackerRows(phone = true) {")
        )
        assertTrue(
            "and both panes use the same helpers",
            tvPane.contains("rememberSimklAccountName(") &&
                tvPane.contains("rememberTraktAccountName(") &&
                tvPane.contains("rememberBingeCatProfileName(")
        )
    }

    // ── The identity reads stay off the hot path ──────────────────────────

    @Test
    fun `no row resolves a name inline while composing`() {
        assertFalse(
            "the Simkl/Trakt reads are suspending and belong in an effect, never in a row body",
            tvPane.contains("runBlocking") || phonePane.contains("runBlocking")
        )
        assertTrue(
            "the effects are keyed on the connection state and the caller's seed",
            labels.contains("LaunchedEffect(connected, seed)") &&
                labels.contains("mutableStateOf(repository.accountName())")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
