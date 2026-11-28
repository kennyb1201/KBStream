package com.kennyb1201.kbstream.data.bingecat

import com.kennyb1201.kbstream.data.sync.BingeCatAuthRules
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BingeCat rotation-race cascade (spec 2026-11-27).
 *
 * The field symptom: three devices (TCL, phone, Fire Stick) all reading
 * `connected=no, needs_reconnect=yes, mapped=unmapped, last_error=none`, twice in
 * a week, with the mapping intact on disk. The race itself is benign - a loser
 * always exists - so what is pinned here is the CASCADE, all three steps of it:
 *
 *  1. the loser keeps its consumed pair ([BingeCatTokenStore.markNeedsReconnect]
 *     deliberately does not delete it) and used to publish it anyway, with a
 *     fresh `updatedAt` that made the dead pair look newest;
 *  2. the winner then adopted that pair in its expiry window, because
 *     `incomingLive || !localLive` says "both expired, so take theirs" - and took
 *     the consumed refresh token with it;
 *  3. the winner's next use refreshed the dead token, found nothing adoptable,
 *     and refused. Nobody held a live pair.
 *
 * The three rules that stop it are pure ([BingeCatAuthRules]) and are pinned
 * here end to end; the wiring that could bypass them is pinned by source.
 */
class BingeCatRotationRaceContractTest {

    private val hour = 3_600_000L
    private val now = 1_000_000_000L

    private val payload by lazy {
        source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    }
    private val repository by lazy {
        source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    }
    private val sync by lazy {
        source("com/kennyb1201/kbstream/data/sync/SupabaseSync.kt")
    }
    private val store by lazy {
        source("com/kennyb1201/kbstream/data/bingecat/BingeCatTokenStore.kt")
    }
    private val accountStore by lazy {
        source("com/kennyb1201/kbstream/data/bingecat/BingeCatAccountStore.kt")
    }

    // ── the cascade, step by step ─────────────────────────────────────

    @Test
    fun `the loser of a rotation never publishes its consumed pair`() {
        // Step 1+2. The loser's marker is set and its pair is what the server
        // consumed; the pair is still in the store by design (deleting it is how
        // a recoverable expiry became a permanent `no-token`, builds 242-253).
        val loserMayPublish = BingeCatAuthRules.shouldPublish(
            accessToken = "consumed-access",
            signedOut = false,
            needsReconnect = true,
            expiresAtEpochMillis = now + hour,
            nowMs = now
        )
        assertFalse(
            "a refused pair published with a fresh updatedAt overwrites the " +
                "winner's live row - this single write is what took all three " +
                "devices down together",
            loserMayPublish
        )

        // And the winner's own row stays untouched: nothing else in the pass can
        // write BINGECAT_AUTH, because both publish sites ask this question.
        assertTrue(
            "the bulk push gates the row",
            squash(sync).contains("!PrefsPayloadBuilder.hasBingeCatAuthToPublish(context)")
        )
        assertTrue(
            "and the immediate publish (connect/refresh/open) gates it too",
            squash(repository).contains(
                "if (!PrefsPayloadBuilder.hasBingeCatAuthToPublish(appContext)) return"
            )
        )
    }

    @Test
    fun `the winner does not adopt a dead pair once its own token has expired`() {
        // Step 3. This is the window the cascade used: the winner's access token
        // has expired, it has not refreshed yet, and the row carries the loser's
        // consumed pair. Both pairs are expired, and the old rule
        // (`incomingLive || !localLive`) read that as "nothing to protect".
        assertFalse(
            "an expired incoming pair carries nothing this device cannot get by " +
                "refreshing its own, and adopting it replaces a LIVE refresh " +
                "token with the consumed one",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "consumed-access",
                incomingExpiresAt = now - hour,
                incomingGeneration = 4L,
                localAccess = "winner-access",
                localGeneration = 4L,
                nowMs = now
            )
        )
        assertFalse(
            "and with no generation to compare (rows from older builds) the " +
                "liveness rule alone refuses it",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "consumed-access",
                incomingExpiresAt = now - hour,
                incomingGeneration = 0L,
                localAccess = "winner-access",
                localGeneration = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun `a stale echo cannot outrank a live pair, whatever its expiry says`() {
        // F3. Expiry cannot order two rotated pairs - the loser's pair can look
        // newer than the winner's - so the generation decides, and an echo with a
        // bigger expiresAt is still an echo.
        assertFalse(
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "echo",
                incomingExpiresAt = now + 30 * hour,
                incomingGeneration = 6L,
                localAccess = "live",
                localGeneration = 7L,
                nowMs = now
            )
        )
        assertTrue(
            "and the sibling's rotation IS adopted, expired or not",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "rotated",
                incomingExpiresAt = now - hour,
                incomingGeneration = 8L,
                localAccess = "behind",
                localGeneration = 7L,
                nowMs = now
            )
        )
    }

    @Test
    fun `after the race the winner stays connected and the loser heals on the pull`() {
        // The contract's END state, as one walk through the three devices' rules.
        val winnerGeneration = 7L
        val loserGeneration = 6L

        // The loser cannot publish its pair (F1) - the row therefore keeps the
        // winner's live pair, which is what the loser's pull carries.
        assertFalse(
            BingeCatAuthRules.shouldPublish(
                accessToken = "loser-consumed",
                signedOut = false,
                needsReconnect = true,
                expiresAtEpochMillis = now + hour,
                nowMs = now
            )
        )

        // The loser's marker is set, but the account row is a strictly newer
        // pair: adoption does not ask the marker, it asks whether the row moved
        // forward - so the loser comes back with NO QR (the recovery the field
        // report could never reach).
        assertTrue(
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "winner-access",
                incomingExpiresAt = now + hour,
                incomingGeneration = winnerGeneration,
                localAccess = "loser-consumed",
                localGeneration = loserGeneration,
                nowMs = now
            )
        )

        // And the winner ignores the same row it already holds (same pair, same
        // generation): an unchanged pull retires the marker instead of rewriting.
        assertTrue(
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "winner-access",
                incomingExpiresAt = now + hour,
                incomingGeneration = winnerGeneration,
                localAccess = "winner-access",
                localGeneration = winnerGeneration,
                nowMs = now
            )
        )
    }

    @Test
    fun `a deliberate disconnect still propagates as a tombstone`() {
        // F1 must not cost the disconnect: a blank token plus the marker is the
        // one shape that has to reach every device.
        assertTrue(
            BingeCatAuthRules.shouldPublish(
                accessToken = null,
                signedOut = true,
                needsReconnect = false,
                expiresAtEpochMillis = 0L,
                nowMs = now
            )
        )
        assertTrue(BingeCatAuthRules.clearsSession(null, signedOut = true))
        assertTrue(
            "the repository still publishes the write that took",
            repository.contains("if (cleared) publishAuthToSync()")
        )
    }

    // ── the wiring behind the rules ───────────────────────────────────

    @Test
    fun `the generation travels from the store to the row and back`() {
        assertTrue(
            "the pair carries it",
            source("com/kennyb1201/kbstream/data/bingecat/BingeCatModels.kt")
                .contains("val generation: Long = 0L")
        )
        assertTrue(
            "the profile store persists it",
            store.contains("const val KEY_GENERATION = \"generation\"") &&
                store.contains(".putLong(KEY_GENERATION, token.generation)") &&
                store.contains("generation = prefs?.getLong(KEY_GENERATION, 0L) ?: 0L")
        )
        assertTrue(
            "the account store keeps it too, or a sibling's refresh reads as a tie",
            accountStore.contains(".putLong(BingeCatTokenStore.KEY_GENERATION, token.generation)") &&
                accountStore.contains("generation = prefs?.getLong(BingeCatTokenStore.KEY_GENERATION, 0L) ?: 0L")
        )
        assertTrue(
            "the blob publishes it",
            payload.contains("put(BingeCatTokenStore.KEY_GENERATION, session?.generation ?: 0L)")
        )
        assertTrue(
            "and the applier adopts it with the pair",
            payload.contains("val generation = text(BingeCatTokenStore.KEY_GENERATION).toLongOrNull() ?: 0L") &&
                payload.contains("generation = nextGeneration")
        )
    }

    @Test
    fun `a minted pair is persisted even when the store refuses it`() {
        // F4. The server has already consumed the old refresh token by the time
        // the save is attempted, so a failed save is NOT a failed refresh.
        assertTrue(
            repository.contains("private suspend fun saveMintedPair(token: BingeCatToken): Boolean")
        )
        assertTrue(
            "retried in place...",
            repository.contains("MINTED_PAIR_SAVE_ATTEMPTS") &&
                repository.contains("delay(MINTED_PAIR_RETRY_MS)")
        )
        assertTrue(
            "...and then kept for the next call, which is the only way to save a " +
                "pair whose only copy is the one in flight",
            repository.contains("private var unsavedMintedPair: BingeCatToken? = null") &&
                repository.contains("refreshMutex.withLock { retryUnsavedMintedPairLocked() }")
        )
        assertFalse(
            "the old shape returned null from a refresh that actually succeeded",
            squash(repository).contains(
                "runCatching { store.save(token) }.getOrNull() ?: return@withLock null"
            )
        )
        assertTrue(
            "and a lost publish is no longer silent",
            repository.contains("BingeCatTrace.recordError(\"publish-failed\")")
        )
    }

    @Test
    fun `the pull names the recovery it just performed`() {
        // The contract's field test reads the paste, so the healing has to be
        // visible there: a device that came back must not look like one that
        // never had a fault.
        val apply = functionBody(payload, "private fun applyBingeCatAuth(")
        assertTrue(
            "the refusal is read before the save retires it: $apply",
            apply.contains("val wasRefused = store.needsReconnect()")
        )
        assertTrue(
            "and a successful adoption over a refused pair is recorded: $apply",
            apply.contains("if (saved && wasRefused)") &&
                apply.contains("BingeCatTrace.recordError(BingeCatRefreshRecovery.RECOVERED)")
        )
    }

    @Test
    fun `diagnostics read the mapping even when the session is refused`() {
        // F5: the incident paste read `mapped=unmapped` because the mapping was
        // only recorded behind the `usable` short-circuit.
        val hasToken = functionBody(repository, "fun hasToken(): Boolean")
        assertTrue(
            "the trace is populated from the mapping store unconditionally: $hasToken",
            hasToken.contains(
                "BingeCatTrace.recordMapping(mapping.mappedProfileId(), mapping.mappedProfileName())"
            )
        )
        assertTrue(
            "and it does not sit behind the usability test",
            hasToken.indexOf("BingeCatTrace.recordMapping(") <
                hasToken.indexOf("recordNeedsReapproval(")
        )
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("missing body for $signature", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
            i++
        }
        error("unterminated body for $signature")
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        error("source tree not found from ${System.getProperty("user.dir")}")
    }
}
