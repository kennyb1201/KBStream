package com.kennyb1201.kbstream.data.kitsu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kitsu connect + write contract (spec 2026-11-13 phase 2).
 *
 * These are source pins, not behaviour tests. The behaviour is tested where it
 * lives ([KitsuWriteRulesTest] for the bodies and the scale, the CI unit run for
 * the queue); what a test can still enforce cheaply is that the things which
 * must never exist do not - a stored password, a bundled client secret, a write
 * that runs without a connection - and that the three engines all reach the write
 * through the one path.
 *
 * The file set is read from the repository root, so this is inert in a release
 * AAR but exact in a checkout - which is where it runs.
 */
class KitsuConnectContractTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("missing $path", file.exists())
        return file.readText()
    }

    private val root = "src/main/java/com/kennyb1201/kbstream"
    private val kitsu = "$root/data/kitsu"

    // ── The password ──────────────────────────────────────────────────────

    @Test
    fun `the password is never a stored key`() {
        val store = source("$kitsu/KitsuTokenStore.kt")

        // The store's whole vocabulary. A `password` key here would mean the
        // password survives the connect, which is the one thing the flow's
        // privacy claim rests on not happening.
        assertFalse(store.contains("\"password\""))
        assertFalse(store.contains("KEY_PASSWORD"))
        assertFalse(store.contains("savePassword"))
    }

    @Test
    fun `the password is never written to a log`() {
        val auth = source("$kitsu/KitsuAuth.kt")
        val rules = source("$kitsu/KitsuWriteRules.kt")

        for (text in listOf(auth, rules)) {
            for (line in text.lineSequence()) {
                val logs = line.contains("Log.") || line.contains("println") ||
                    line.trimStart().startsWith("* ") || line.trimStart().startsWith("//")
                if (!logs) continue
                assertFalse("a log or comment names the password field: $line", line.contains("password="))
            }
        }
    }

    @Test
    fun `the connect screen keeps the password in its own state only`() {
        val dialog = source("$root/ui/settings/KitsuConnectDialog.kt")

        // Masked while typed, emptied once the exchange answers, and never
        // handed anywhere but the exchange itself.
        assertTrue(dialog.contains("PasswordVisualTransformation"))
        assertTrue(dialog.contains("password = \"\""))
        assertFalse("a password saved to preferences", dialog.contains("setKitsuPassword"))
    }

    // ── The client credentials ────────────────────────────────────────────

    @Test
    fun `kitsu bundles a client id and no secret`() {
        val config = source("$kitsu/KitsuConfig.kt")
        // A unit test's working directory is the module (app/), the same root the
        // source helper above resolves against.
        val gradle = File("build.gradle.kts").let {
            if (it.exists()) it.readText() else File("../app/build.gradle.kts").readText()
        }

        assertTrue(gradle.contains("KITSU_CLIENT_ID"))
        // The same rule SimklClientConfigTest enforces for every tracker: a
        // secret compiled into an APK is recoverable from every published build.
        assertFalse("Kitsu must not add a secret BuildConfig field", gradle.contains("KITSU_CLIENT_SECRET"))
        assertFalse(config.contains("CLIENT_SECRET"))
        assertFalse(config.contains("clientSecret"))
    }

    @Test
    fun `the token endpoint is Doorkeeper's, on the API host`() {
        val config = source("$kitsu/KitsuConfig.kt")

        assertTrue(config.contains("https://kitsu.app/api/oauth/token"))
        // The JSON:API writes are a different mount than the token endpoint.
        assertTrue(config.contains("https://kitsu.app/api/edge"))
    }

    // ── The session ───────────────────────────────────────────────────────

    @Test
    fun `the refresh is single-flight`() {
        val auth = source("$kitsu/KitsuAuth.kt")

        assertTrue(auth.contains("Mutex()"))
        assertTrue(auth.contains("withLock"))
        // Kitsu ROTATES the refresh token, so a second concurrent exchange
        // spends one that is already gone.
        assertTrue(auth.contains("forceRefresh"))
    }

    @Test
    fun `a dead grant is marked, not retried forever`() {
        val auth = source("$kitsu/KitsuAuth.kt")

        assertTrue(auth.contains("markNeedsReconnect"))
        assertTrue(auth.contains("NeedsReconnect"))
    }

    @Test
    fun `a 404 drops the cached entry id`() {
        val library = source("$kitsu/KitsuLibrary.kt")

        // The ONLY way a cached id goes stale, and the only correct answer to it.
        assertTrue(library.contains("EntryGone"))
        assertTrue(library.contains("entryIds.remove"))
    }

    @Test
    fun `a 429 parks the leg instead of retrying inside the window`() {
        val library = source("$kitsu/KitsuLibrary.kt")

        assertTrue(library.contains("parkedUntilMs"))
        assertTrue(library.contains("RateLimited"))
    }

    // ── No connection, no write ───────────────────────────────────────────

    @Test
    fun `every player write is gated on the connection`() {
        val writes = source("$kitsu/KitsuWrites.kt")

        assertTrue(writes.contains("KitsuConnectionState.Connected"))
        // No id, no write - and no error either.
        assertTrue(writes.contains("kitsuId"))
        assertTrue(writes.contains("KitsuWriteOutcome"))
    }

    @Test
    fun `all three engines reach Kitsu through the one write path`() {
        for (player in listOf("NativePlayerActivity", "MpvPlayerActivity", "ExternalPlayerActivity")) {
            val text = source("$root/ui/player/$player.kt")
            assertTrue("$player does not write Kitsu", text.contains("scrobbleKitsu()"))
            assertTrue(
                "$player bypasses KitsuWrites",
                text.contains("KitsuWrites.markEpisodeWatched")
            )
        }
    }

    @Test
    fun `disconnect clears the session and stops the writes`() {
        val auth = source("$kitsu/KitsuAuth.kt")
        // The Kitsu row is shared by both Settings surfaces now, so its
        // disconnect lives in the shared block rather than the TV file.
        val settings = source("$root/ui/settings/AnimeTrackerRows.kt")

        assertTrue(auth.contains("fun disconnect"))
        assertTrue(settings.contains("KitsuAuth.disconnect"))
        // The spec's Trakt invariant: disconnect never wipes local data.
        assertFalse(settings.contains("deleteAllKitsuHistory"))
    }

    // ── The queue ─────────────────────────────────────────────────────────

    @Test
    fun `kitsu joined the rating queue by appending`() {
        val queue = source("$root/data/library/TasteRatingQueue.kt")
        val kitsuEnum = queue.indexOf("KITSU(\"Kitsu\")")

        assertTrue(kitsuEnum > 0)
        // A stored entry names its tracker by NAME, so an older build must keep
        // reading every older value; Kitsu is last.
        assertTrue(kitsuEnum > queue.indexOf("MDBLIST(\"MDBList\")"))
    }

    @Test
    fun `the kitsu rating leg is isolated and enabled`() {
        val submission = source("$root/data/library/TasteRatingSubmission.kt")

        assertTrue(submission.contains("TasteRatingTracker.KITSU -> drainKitsu"))
        assertTrue(submission.contains("KITSU_RATINGS_ENABLED"))
        // Isolation: a failed Kitsu exchange closes Kitsu's leg only.
        assertTrue(submission.contains("closeLeg(app, TasteRatingTracker.KITSU)"))
    }

    // ── AniList: the gate, and what cleared it ────────────────────────────

    @Test
    fun `the AniList writes ship only with a recorded terms reading and no secret`() {
        // The spec's gate, decided 2026-11-13: AniList's terms prohibit use
        // "within competing, non-complementary services of the same nature ...
        // including ... anime and manga list or tracker services". KBStream is a
        // player that MIRRORS the viewer's progress INTO AniList - complementary,
        // not competing, and the use AniList's own auth docs invite - so the
        // writes ship. This test keeps the two things that must stay true of
        // that decision visible in the tree: the reading is recorded in the write
        // path, and the connect still carries no client secret.
        val writes = source("$root/data/anime/AniListWrites.kt")
        assertTrue(
            "the write path records the terms reading it ships under",
            writes.contains("complementary") || writes.contains("competing")
        )

        val buildGradle = File("build.gradle.kts").let {
            if (it.exists()) it.readText() else File("../app/build.gradle.kts").readText()
        }
        // A secret in a published APK is recoverable from it; AniList's docs send
        // a client that cannot store one to the implicit grant, which is the flow
        // this app uses (see AniListWriteRules.PIN_REDIRECT_URI).
        assertFalse(buildGradle.contains("ANILIST_CLIENT_SECRET"))
        assertTrue(buildGradle.contains("\"ANILIST_CLIENT_ID\""))
    }

    @Test
    fun `the spec's own invariants still hold for the read legs`() {
        // Phase 1 is untouched: Kitsu's reads still go through one client and the
        // same host the writes use, so a host move cannot split them.
        val client = source("$root/data/anime/KitsuClient.kt")
        assertTrue(client.contains("KitsuConfig.API_BASE"))
        // One literal for the base, shared by the reads and the writes.
        assertFalse(client.contains("\"https://kitsu.app/api/edge\""))
    }
}
