package com.kennyb1201.kbstream.data.simkl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simkl AUTH V1 -> V2 (spec 2026-11-16).
 *
 * A migration with a date: Simkl retires the PIN flow this app shipped with
 * around April 2027, so what can break later is [sic] the pairing, every write,
 * and the household's other devices. The rules that have each been a shipped bug
 * in this tree are pure and are exercised directly; the wiring is pinned as
 * source, because there is no Android, TV or Simkl account on this host.
 *
 * - F0: both registrations live, the V2 id compiled in and fed by CI.
 * - F1: the device grant, its scope, its code formatting and its polling rules.
 * - F2: the 7-day token's lifecycle - store, refresh once, read a 429.
 * - F3: V2 tokens are per device and never enter the sync row.
 * - F4: no forced sign-out; legacy sessions keep working and get a reconnect row.
 * - F5: disconnect really revokes.
 */
class SimklAuthV2ContractTest {

    private val repository by lazy { source("com/kennyb1201/kbstream/data/simkl/SimklRepository.kt") }
    private val api by lazy { source("com/kennyb1201/kbstream/data/simkl/SimklApiService.kt") }
    private val models by lazy { source("com/kennyb1201/kbstream/data/simkl/SimklModels.kt") }
    private val interceptor by lazy { source("com/kennyb1201/kbstream/data/simkl/SimklAuthInterceptors.kt") }
    private val queryInterceptor by lazy { source("com/kennyb1201/kbstream/data/simkl/SimklQueryInterceptor.kt") }
    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklViewModel.kt") }
    private val uiState by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklUiState.kt") }
    private val tvScreen by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklConnectScreen.kt") }
    private val phoneScreen by lazy { source("com/kennyb1201/kbstream/ui/mobile/SimklConnectScreenMobile.kt") }
    private val syncPayload by lazy { source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt") }
    private val tvSettings by lazy { source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt") }
    private val phoneSettings by lazy { source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt") }
    private val gradle by lazy { repoFile("app/build.gradle.kts") }
    private val workflow by lazy { repoFile(".github/workflows/build.yml") }

    // ── F0: the second registration ──────────────────────────────────────

    @Test
    fun `both client ids ship, and CI feeds the V2 one`() {
        assertTrue(
            "the V2 id is a BuildConfig field beside the V1 one",
            gradle.contains("buildConfigField(\"String\", \"SIMKL_CLIENT_ID_V2\"")
        )
        assertTrue(
            "and it arrives from its own secret",
            workflow.contains("SIMKL_CLIENT_ID_V2: \${{ secrets.SIMKL_CLIENT_ID_V2 }}")
        )
        assertTrue(
            "the V1 registration keeps working (a V1 id cannot be upgraded)",
            gradle.contains("buildConfigField(\"String\", \"SIMKL_CLIENT_ID\"") &&
                workflow.contains("SIMKL_CLIENT_ID: \${{ secrets.SIMKL_CLIENT_ID }}")
        )
        assertFalse(
            "no secret comes with it: the device flow is a public-client flow",
            gradle.contains("buildConfigField(\"String\", \"SIMKL_CLIENT_SECRET\"") ||
                workflow.contains("SIMKL_CLIENT_SECRET:")
        )
        assertTrue(
            "the required query params and User-Agent are still on every request",
            queryInterceptor.contains("addQueryParameter(\"client_id\"") &&
                queryInterceptor.contains("addQueryParameter(\"app-name\"") &&
                queryInterceptor.contains("addQueryParameter(\"app-version\"") &&
                queryInterceptor.contains("header(\"User-Agent\"")
        )
    }

    // ── F1: the device grant ─────────────────────────────────────────────

    @Test
    fun `the flow asks for the write scope, because a grant can never grow`() {
        assertEquals("media:read media:write", SimklAuthV2Rules.SCOPE)
        assertTrue(SimklAuthV2Rules.scopeGrants("media:read media:write", "media:write"))
        assertFalse(
            "a read-only grant is exactly the downgrade this scope prevents",
            SimklAuthV2Rules.scopeGrants("media:read", "media:write")
        )
        assertTrue(
            "a session with no recorded scope is not treated as a downgrade",
            SimklAuthV2Rules.scopeGrants(null, "media:write")
        )
        assertTrue(
            "the device request sends the whole scope string",
            repository.contains("SimklDeviceAuthRequest(clientId = id, scope = SimklAuthV2Rules.SCOPE)")
        )
    }

    @Test
    fun `the code is shown XXXX-YYYY and entry is forgiving`() {
        assertEquals("ABCD-1234", SimklAuthV2Rules.formatUserCode("ABCD1234"))
        assertEquals("ABCD-1234", SimklAuthV2Rules.formatUserCode("abcd-1234"))
        assertEquals("ABCD-1234", SimklAuthV2Rules.formatUserCode("abcd 1234"))
        assertEquals("ABCD-1234", SimklAuthV2Rules.formatUserCode("ABCD-1234"))
        assertEquals(
            "a code shorter than the split is never padded or clipped",
            "ABCD",
            SimklAuthV2Rules.formatUserCode("abcd")
        )
        assertEquals("ABCD1234", SimklAuthV2Rules.normalizeUserCode(" ab-cd_12 34 "))
    }

    @Test
    fun `token_type is compared case-insensitively`() {
        // V1 said `bearer`, V2 says `Bearer`; the guide warns about exactly this.
        assertTrue(SimklAuthV2Rules.isBearerTokenType("Bearer"))
        assertTrue(SimklAuthV2Rules.isBearerTokenType("bearer"))
        assertTrue(SimklAuthV2Rules.isBearerTokenType("  BEARER "))
        assertFalse(SimklAuthV2Rules.isBearerTokenType("basic"))
        assertFalse(SimklAuthV2Rules.isBearerTokenType(null))
        assertTrue(
            "the poll path checks it before storing the token",
            repository.contains("!SimklAuthV2Rules.isBearerTokenType(granted.tokenType)")
        )
    }

    @Test
    fun `polling follows the interval, slow_down widens it, expiry restarts`() {
        val pending = SimklAuthV2Rules.poll(
            statusCode = 400,
            hasToken = false,
            elapsedSeconds = 5,
            expiresInSeconds = 900,
            error = "authorization_pending"
        )
        assertEquals(SimklAuthV2Rules.Poll.PENDING, pending)
        assertEquals(
            "a decline writes nothing, so it is indistinguishable from pending - the " +
                "code's own lifetime is the only signal",
            SimklAuthV2Rules.Poll.PENDING,
            SimklAuthV2Rules.poll(400, hasToken = false, elapsedSeconds = 5, expiresInSeconds = 900, error = null)
        )
        assertEquals(
            SimklAuthV2Rules.Poll.SLOW_DOWN,
            SimklAuthV2Rules.poll(429, false, 5, 900, "slow_down")
        )
        assertEquals(
            SimklAuthV2Rules.Poll.EXPIRED,
            SimklAuthV2Rules.poll(400, false, 900, 900, "expired_token")
        )
        assertEquals(
            "expiry wins over a pending body: the code is dead whatever the poll says",
            SimklAuthV2Rules.Poll.EXPIRED,
            SimklAuthV2Rules.poll(400, false, 901, 900, "authorization_pending")
        )
        assertEquals(
            "invalid_client cannot be fixed by retrying",
            SimklAuthV2Rules.Poll.MISCONFIGURED,
            SimklAuthV2Rules.poll(401, false, 5, 900, "invalid_client")
        )
        assertEquals(
            "a 5s interval becomes 10s after slow_down",
            10,
            SimklAuthV2Rules.slowedIntervalSeconds(5)
        )
        assertEquals(
            "the countdown never goes negative",
            0,
            SimklAuthV2Rules.secondsRemaining(elapsedSeconds = 950, expiresInSeconds = 900)
        )
    }

    @Test
    fun `the code is terminal on approval and the UI never shows the polling credential`() {
        assertEquals(
            SimklAuthV2Rules.Poll.APPROVED,
            SimklAuthV2Rules.poll(200, hasToken = true, elapsedSeconds = 5, expiresInSeconds = 900)
        )
        assertTrue(
            "the device request is one call, and the pin endpoints are gone",
            api.contains("@POST(\"oauth2/device\")") &&
                !api.contains("@GET(\"oauth/pin\")")
        )
        assertTrue(
            "the QR encodes verification_uri_complete, which pre-fills the code",
            repository.contains("verificationUriComplete") &&
                repository.contains("verificationUri = verification")
        )
        assertFalse(
            "the TV screen never draws the device code",
            tvScreen.contains("uiState.deviceCode")
        )
        assertFalse(
            "nor does the phone screen",
            phoneScreen.contains("uiState.deviceCode")
        )
        assertTrue(
            "the countdown is on the state and both screens show it",
            uiState.contains("val secondsRemaining: Int?") &&
                tvScreen.contains("uiState.secondsRemaining") &&
                phoneScreen.contains("uiState.secondsRemaining")
        )
        assertTrue(
            "an expired code restarts itself instead of dying on screen",
            viewModel.contains("private fun restartExpiredCode()") &&
                viewModel.contains("restartExpiredCode()")
        )
    }

    // ── F2: the 7-day lifecycle ──────────────────────────────────────────

    @Test
    fun `the session stores the refresh token and an expiry`() {
        assertTrue(
            "the three V2 keys live beside the access token",
            repository.contains("KEY_REFRESH_TOKEN") &&
                repository.contains("KEY_EXPIRES_AT_MS") &&
                repository.contains("KEY_SCOPE")
        )
        assertTrue(
            "an expiry is computed from the grant's own expires_in",
            repository.contains("SimklAuthV2Rules.expiresAtEpochMillis(")
        )
        assertTrue(
            "a grant with no expires_in falls back to V2's seven days",
            SimklAuthV2Rules.expiresAtEpochMillis(nowMs = 0L, expiresInSeconds = null) ==
                SimklAuthV2Rules.ACCESS_TOKEN_TTL_SECONDS * 1_000L
        )
        assertTrue(
            "and it is due for refresh a minute early",
            SimklAuthV2Rules.isExpired(expiresAtMs = 100_000L, nowMs = 40_000L) &&
                !SimklAuthV2Rules.isExpired(expiresAtMs = 100_000L, nowMs = 10_000L)
        )
        assertFalse(
            "a V1 row has no expiry and must not look expired",
            SimklAuthV2Rules.isExpired(expiresAtMs = 0L, nowMs = Long.MAX_VALUE / 2)
        )
    }

    @Test
    fun `a 401 refreshes once, single-flight, and never on the token endpoint`() {
        assertTrue(
            "the refresh rides an interceptor, so no call site can forget it",
            repository.contains("SimklAuthInterceptor(")
        )
        assertTrue(
            "one retry with the new token, never a loop",
            interceptor.contains("if (response.code != 401) return response") &&
                interceptor.contains("request.newBuilder()") &&
                interceptor.contains(".header(\"Authorization\", \"Bearer \$fresh\")")
        )
        assertTrue(
            "the token endpoint is never re-entered",
            interceptor.contains("startsWith(\"oauth2/\", ignoreCase = true)")
        )
        assertTrue(
            "refreshes are serialised, and a queued caller adopts the newer token",
            repository.contains("synchronized(refreshLock)") &&
                repository.contains("current.accessToken != failedAccessToken")
        )
        assertTrue(
            "the access token is refreshed, never the refresh token dropped",
            repository.contains("refreshToken = granted.refreshToken?.takeIf { it.isNotBlank() } ?: refresh")
        )
    }

    @Test
    fun `a 429 is read before it is retried`() {
        assertEquals(
            SimklAuthV2Rules.RateLimit.BURST,
            SimklAuthV2Rules.rateLimit(429, "rate_limit")
        )
        assertEquals(
            SimklAuthV2Rules.RateLimit.USER_DAILY,
            SimklAuthV2Rules.rateLimit(429, "user_limit_exceeded")
        )
        assertEquals(
            SimklAuthV2Rules.RateLimit.APP_DAILY,
            SimklAuthV2Rules.rateLimit(429, "app_limit_exceeded")
        )
        assertEquals(
            "a 200 that happens to mention a limit is not a 429",
            SimklAuthV2Rules.RateLimit.NONE,
            SimklAuthV2Rules.rateLimit(200, "rate_limit")
        )
        assertEquals(
            "the burst waits about a second, ignoring Retry-After",
            1_000L,
            SimklAuthV2Rules.retryDelayMs(SimklAuthV2Rules.RateLimit.BURST, retryAfterSeconds = 600)
        )
        assertEquals(
            "a spent daily quota waits what the server says",
            42_000L,
            SimklAuthV2Rules.retryDelayMs(SimklAuthV2Rules.RateLimit.USER_DAILY, 42)
        )
        assertTrue(SimklAuthV2Rules.isDailyQuota(SimklAuthV2Rules.RateLimit.APP_DAILY))
        assertFalse(SimklAuthV2Rules.isDailyQuota(SimklAuthV2Rules.RateLimit.BURST))
        assertTrue(
            "the interceptor peeks the body and never retries a daily quota",
            interceptor.contains("peekBody(") &&
                interceptor.contains("if (SimklAuthV2Rules.isDailyQuota(limit)) return response")
        )
        assertEquals(
            "a V1 token on a V2-only endpoint is named, not retried",
            true,
            SimklAuthV2Rules.isV2GrantRequired(403, "oauth2_token_required")
        )
        assertEquals(
            "and a missing session is not a rate limit",
            true,
            SimklAuthV2Rules.isMissingSession(401, "user_token_required")
        )
    }

    // ── F3: per-device tokens are not synced ─────────────────────────────

    @Test
    fun `a V2 session is never published, and a sibling's legacy tombstone never ends it`() {
        assertTrue(
            "the refresh token is the discriminator",
            SimklAuthV2Rules.isV2Session("simkl_rt_x")
        )
        assertFalse(SimklAuthV2Rules.isV2Session(null))
        assertFalse(SimklAuthV2Rules.isV2Session("   "))

        assertFalse(
            "no token, no marker, no tombstone: the row says nothing about a V2 session",
            com.kennyb1201.kbstream.data.sync.SimklAuthRules
                .shouldPublish("simkl_at_x", signedOut = false, refreshToken = "simkl_rt_x")
        )
        assertTrue(
            "a legacy session keeps travelling exactly as before",
            com.kennyb1201.kbstream.data.sync.SimklAuthRules
                .shouldPublish("tok", signedOut = false, refreshToken = null)
        )
        assertTrue(
            "the applier refuses a legacy row on a device holding a V2 grant",
            syncPayload.contains("if (SimklAuthV2Rules.isV2Session(prefs.getString(SIMKL_REFRESH_TOKEN_KEY, null))) return")
        )
        assertTrue(
            "and a V2 session's own row is the link state only, decided before the " +
                "row is built",
            syncPayload.contains("SimklLinkRules.shouldPublish(name, isV2)")
        )
        assertTrue(
            "a V2 sign-out writes no tombstone, so no sibling is signed out",
            repository.contains("val legacy = signedOutSession?.isV2 != true") &&
                repository.contains("editor?.remove(SimklAuthRules.SIGNED_OUT_FIELD)")
        )
    }

    // ── F4: side by side, no forced sign-out ─────────────────────────────

    @Test
    fun `a legacy session keeps working and gets the reconnect row`() {
        assertTrue(SimklAuthV2Rules.reconnectVisible(isConnected = true, isV2Session = false))
        assertFalse(
            "a V2 session has nothing to reconnect for",
            SimklAuthV2Rules.reconnectVisible(isConnected = true, isV2Session = true)
        )
        assertFalse(
            "and a disconnected profile's row opens the connect flow",
            SimklAuthV2Rules.reconnectVisible(isConnected = false, isV2Session = false)
        )
        assertEquals(
            "Simkl: connected (legacy)",
            SimklAuthV2Rules.settingsRowLabel(isConnected = true, isV2Session = false)
        )
        assertTrue(
            "TV and phone say the same thing",
            tvSettings.contains("\"Simkl: connected (legacy)\"") &&
                phoneSettings.contains("\"Simkl: connected (legacy)\"")
        )
        assertTrue(
            "the row leads to the device flow, not to a page with no way forward",
            tvScreen.contains("KBButton(label = \"RECONNECT\", onClick = vm::generateCode)") &&
                phoneScreen.contains("label = \"RECONNECT\"")
        )
        assertTrue(
            "existing V1 tokens keep working: nothing signs them out",
            repository.contains("fun isLegacySession(): Boolean") &&
                !repository.contains("if (isLegacySession()) clearAuth()")
        )
        assertFalse(
            "and no V1 token is ever minted again",
            api.contains("@GET(\"oauth/pin\")") || repository.contains("fun createPinCode")
        )
    }

    @Test
    fun `each request names the registration that issued its session`() {
        assertEquals(
            "the oauth2 endpoints always speak as the V2 app",
            "v2",
            SimklAuthV2Rules.clientIdFor("oauth2/device", legacyId = "v1", v2Id = "v2", isV2Session = false)
        )
        assertEquals(
            "a data call speaks as the app that issued its token",
            "v2",
            SimklAuthV2Rules.clientIdFor("sync/activities", "v1", "v2", isV2Session = true)
        )
        assertEquals(
            "a legacy token keeps its own registration",
            "v1",
            SimklAuthV2Rules.clientIdFor("sync/activities", "v1", "v2", isV2Session = false)
        )
        assertEquals(
            "a build handed only the V1 id still works for its own data calls",
            "v1",
            SimklAuthV2Rules.clientIdFor("oauth2/device", "v1", "", isV2Session = true)
        )
        assertEquals(
            "and a V2-only build still reaches the data endpoints",
            "v2",
            SimklAuthV2Rules.clientIdFor("sync/activities", "", "v2", isV2Session = false)
        )
        assertTrue(
            "the wire half resolves it per request",
            queryInterceptor.contains("clientIdFor(originalUrl.encodedPath.trimStart('/'))")
        )
    }

    // ── F5: a real revoke ────────────────────────────────────────────────

    @Test
    fun `disconnect revokes the grant and still clears the local session`() {
        assertTrue(
            "the revoke endpoint is wired",
            api.contains("@POST(\"oauth2/revoke\")") &&
                repository.contains("oauth2/revoke")
        )
        assertTrue(
            "both halves of the pair go with one call (RFC 7009)",
            repository.contains("revokeBlocking(session.refreshToken ?: session.accessToken)")
        )
        assertTrue(
            "it is best-effort and reported, never a reason to stay signed in",
            repository.contains("Simkl grant revoked") &&
                repository.contains("Simkl revoke failed (session dropped locally)")
        )
        assertTrue(
            "the local row is dropped either way",
            repository.contains(".remove(\n                KEY_ACCESS_TOKEN")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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

    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw AssertionError("repo file not found: $relative")
    }
}
