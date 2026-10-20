package com.kennyb1201.kbstream.data.trakt

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Trakt store an earlier build left unreadable must not brick Trakt forever.
 *
 * The file holds nothing the account cannot hand out again, so the one recovery
 * that works is deleting it and writing a fresh store. The open is a parameter
 * here because no device can be asked to corrupt its KeyStore on demand; the
 * assertions are that the rebuild happens EXACTLY once, that it happens only
 * after the bad file is gone, and that the session is written where the caller
 * can read it back.
 *
 * Simkl's store uses the same helper and is deliberately not in this path.
 */
@RunWith(AndroidJUnit4::class)
class TraktTokenStoreRebuildTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profileId = "trakt_rebuild_profile"
    private val storeName = "$profileId.${TraktTokenStore.PREFS_NAME}"

    private fun storeFile(): File =
        File(context.filesDir.parentFile, "shared_prefs/$storeName.xml")

    private fun token() = TraktToken(
        accessToken = "access",
        refreshToken = "refresh",
        expiresAtEpochMillis = 42L
    )

    @Test
    fun `a store that will not open is rebuilt once and the token still saves`() {
        // The shape of the fault: a file on disk whose KeyStore entry is gone.
        context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
            .edit()
            .putString(TraktTokenStore.KEY_ACCESS_TOKEN, "stale")
            .commit()
        assertTrue("the unreadable file exists to begin with", storeFile().exists())

        var opens = 0
        var rebuilt: SharedPreferences? = null
        var deletedBeforeRebuild: Boolean? = null
        val store = TraktTokenStore(
            context = context,
            profileId = profileId,
            openPrefs = { ctx, name ->
                opens++
                if (opens == 1) throw IllegalStateException("keyset entry missing")
                deletedBeforeRebuild = !storeFile().exists()
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .also { rebuilt = it }
            }
        )

        store.save(token())

        assertEquals("one failed open, one fresh one", 2, opens)
        assertEquals(
            "the bad file is deleted BEFORE the rebuild",
            true,
            deletedBeforeRebuild
        )
        assertEquals(
            "the granted access token is in the rebuilt store",
            "access",
            rebuilt?.getString(TraktTokenStore.KEY_ACCESS_TOKEN, null)
        )
        assertEquals(
            "and the refresh token with it",
            "refresh",
            rebuilt?.getString(TraktTokenStore.KEY_REFRESH_TOKEN, null)
        )
        assertFalse(
            "a fresh session retires the sign-out marker",
            rebuilt?.getBoolean(TraktTokenStore.KEY_SIGNED_OUT, true) ?: true
        )
    }

    @Test
    fun `a store that cannot be rebuilt reports the fault instead of pretending`() {
        val store = TraktTokenStore(
            context = context,
            profileId = profileId,
            openPrefs = { _, _ -> throw IllegalStateException("still broken") }
        )

        val thrown = runCatching { store.save(token()) }.exceptionOrNull()

        assertTrue(
            "the caller must see the storage fault, not a silent success: $thrown",
            thrown is IllegalStateException
        )
    }
}
