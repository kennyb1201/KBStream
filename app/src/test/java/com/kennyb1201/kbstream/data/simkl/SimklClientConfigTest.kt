package com.kennyb1201.kbstream.data.simkl

import com.kennyb1201.kbstream.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit finding this pins: `SIMKL_CLIENT_SECRET` was passed through
 * `buildConfigField`, so a live secret sat in a public GPL-3.0 repository's CI
 * configuration and inside every distributed APK, where a strings-style scan
 * recovers it in seconds.
 *
 * It bought nothing. Simkl is reached through its PIN (device) flow - ask
 * `/oauth/pin` for a user_code, show it, poll `/oauth/pin/{user_code}` until
 * the user approves on simkl.com - and that is a PUBLIC-client flow: only
 * `client_id`, which is public by construction, ever travels. Nothing in the
 * request path ever sent the secret; the app's only use of it was to gate
 * `isConfigured()` on a value nothing consumed.
 *
 * So the fix is deletion rather than PKCE: no secret to exchange, no code
 * verifier, no redirect URI to register. What this test exists to stop is the
 * secret quietly coming back the next time someone "tidies up" the Simkl
 * config - because the failure mode is silent (the app works either way) and
 * the damage is permanent (a shipped APK cannot be un-shipped).
 */
class SimklClientConfigTest {

    private val buildConfigFields =
        BuildConfig::class.java.declaredFields.map { it.name }

    /**
     * Guards the test below from passing vacuously: if the whole Simkl block
     * were ever dropped from BuildConfig, "no secret is present" would be true
     * for the wrong reason.
     */
    @Test
    fun `the client id is still compiled in, because the PIN flow needs it`() {
        assertTrue(
            "BuildConfig has no SIMKL_CLIENT_ID, so Simkl can never be " +
                "configured. Fields present: $buildConfigFields",
            buildConfigFields.contains("SIMKL_CLIENT_ID")
        )
    }

    /**
     * Not scoped to Simkl on purpose: the rule this enforces is about the
     * mechanism, and it is the same rule for any future provider added to the
     * `buildConfigField` list. Credentials that must ship (the TMDB key, the
     * Supabase URL and publishable key, the Sentry DSN) are public-by-design
     * identifiers, not secrets, and none of them is named `*SECRET*`.
     */
    @Test
    fun `no secret is compiled into BuildConfig`() {
        val bakedInSecrets = buildConfigFields.filter { it.contains("SECRET") }
        assertFalse(
            "A secret is being baked into every published APK again: " +
                "$bakedInSecrets. Anything compiled in via buildConfigField is " +
                "recoverable from the APK by anyone who downloads it; a " +
                "public-client flow (device/PIN, PKCE) must be used instead, " +
                "or the call must move behind a server.",
            bakedInSecrets.isNotEmpty()
        )
    }
}
