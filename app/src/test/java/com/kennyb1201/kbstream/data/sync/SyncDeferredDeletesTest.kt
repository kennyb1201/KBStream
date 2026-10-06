package com.kennyb1201.kbstream.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The durable, account-scoped staging of deletes made while SIGNED OUT.
 *
 * The bug this closes: signing out does not delete the account's cloud rows, so
 * a signed-out delete used to be dropped and then pulled straight back on the
 * next same-account sign-in. The delete is staged here instead, tagged with the
 * account it belongs to, and replayed only when that account signs back in.
 *
 * The two halves that must not be gotten wrong are pinned here:
 *  - the store round-trips and is scoped by account (a different account's
 *    staged deletes never leak into a replay), and
 *  - the account identity is normalized, so a case/whitespace difference
 *    between sign-in and the stored id cannot replay under the wrong account.
 */
@RunWith(AndroidJUnit4::class)
class SyncDeferredDeletesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStore() {
        // The store's own prefs file (name is a stable contract; it is separate
        // from the session store precisely so sign-out does not clear it).
        context.getSharedPreferences("kbstream_sync_deferred_deletes", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun staged(account: String, key: String, at: Long = 1L) =
        SyncDeferredDeletes.Staged(
            accountId = account,
            table = "sync_watch_history",
            keyColumn = "item_id",
            key = key,
            payloadJson = """{"id":"$key","deletedAt":$at,"updatedAt":$at}""",
            enqueuedAtMs = at
        )

    @Test
    fun `a staged delete round-trips through the store`() {
        SyncDeferredDeletes.stage(context, staged("user@example.com", "p:a:movie::tt1", at = 42L))

        val loaded = SyncDeferredDeletes.stagedFor(context, "user@example.com")

        assertEquals(1, loaded.size)
        assertEquals("p:a:movie::tt1", loaded.single().key)
        assertEquals(42L, loaded.single().enqueuedAtMs)
        assertTrue(loaded.single().payloadJson.contains("\"deletedAt\":42"))
    }

    @Test
    fun `staging the same row twice replaces rather than duplicates`() {
        SyncDeferredDeletes.stage(context, staged("user@example.com", "p:a:movie::tt1", at = 1L))
        SyncDeferredDeletes.stage(context, staged("user@example.com", "p:a:movie::tt1", at = 2L))

        val loaded = SyncDeferredDeletes.stagedFor(context, "user@example.com")
        assertEquals(1, loaded.size)
        assertEquals(2L, loaded.single().enqueuedAtMs)
    }

    @Test
    fun `staged deletes are scoped to their account`() {
        SyncDeferredDeletes.stage(context, staged("a@example.com", "p:a:movie::tt1"))
        SyncDeferredDeletes.stage(context, staged("b@example.com", "p:a:movie::tt2"))

        assertEquals(
            listOf("p:a:movie::tt1"),
            SyncDeferredDeletes.stagedFor(context, "a@example.com").map { it.key }
        )
        assertEquals(
            listOf("p:a:movie::tt2"),
            SyncDeferredDeletes.stagedFor(context, "b@example.com").map { it.key }
        )
    }

    @Test
    fun `remove drops only the replayed deletes and leaves other accounts`() {
        val mine = staged("a@example.com", "p:a:movie::tt1")
        val theirs = staged("b@example.com", "p:a:movie::tt2")
        SyncDeferredDeletes.stage(context, mine)
        SyncDeferredDeletes.stage(context, theirs)

        SyncDeferredDeletes.remove(context, listOf(mine.id))

        assertTrue(SyncDeferredDeletes.stagedFor(context, "a@example.com").isEmpty())
        assertEquals(
            listOf("p:a:movie::tt2"),
            SyncDeferredDeletes.stagedFor(context, "b@example.com").map { it.key }
        )
    }

    @Test
    fun `the remembered account is normalized so it matches at replay time`() {
        SyncDeferredDeletes.rememberAccount(context, "  User@Example.com ")

        assertEquals("user@example.com", SyncDeferredDeletes.lastAccountId(context))
    }

    @Test
    fun `a blank account is not remembered`() {
        SyncDeferredDeletes.rememberAccount(context, "   ")

        assertNull(SyncDeferredDeletes.lastAccountId(context))
    }

    @Test
    fun `the user id is the remembered identity when the session has one`() {
        SyncDeferredDeletes.rememberAccount(context, "uid-123", "User@Example.com")

        // The id is what survives an email change, so it is what a new stage
        // carries; the email is kept alongside it as the fallback form.
        assertEquals("uid-123", SyncDeferredDeletes.lastAccountId(context))
    }

    @Test
    fun `a stage tagged with the user id replays when the caller knows both`() {
        SyncDeferredDeletes.rememberAccount(context, "uid-123", "user@example.com")
        SyncDeferredDeletes.stage(context, staged("uid-123", "p:a:movie::tt1"))

        assertEquals(
            listOf("p:a:movie::tt1"),
            SyncDeferredDeletes.stagedFor(context, "uid-123", "user@example.com").map { it.key }
        )
    }

    @Test
    fun `a stage tagged with the OLD email still replays once the id is known`() {
        // The form an earlier build wrote: email only. The account's email has
        // since changed, so only the user id is stable - matching the email the
        // session still reports is what keeps the old stage reachable (SD-5),
        // and the id-only lookup below is the case that must NOT strand it
        // silently when both are supplied.
        SyncDeferredDeletes.stage(context, staged("old@example.com", "p:a:movie::tt9"))

        assertEquals(
            listOf("p:a:movie::tt9"),
            SyncDeferredDeletes.stagedFor(context, "uid-123", "old@example.com").map { it.key }
        )
    }

    @Test
    fun `another account's stage is still invisible`() {
        SyncDeferredDeletes.stage(context, staged("uid-999", "p:a:movie::tt7"))

        assertTrue(
            SyncDeferredDeletes.stagedFor(context, "uid-123", "user@example.com").isEmpty()
        )
    }
}

/** Pure rules: account normalization, identity, and same-account replay. */
class DeferredDeleteRulesTest {

    @Test
    fun `account ids normalize whitespace and case`() {
        assertEquals("user@example.com", DeferredDeleteRules.normalizeAccount("  User@EXAMPLE.com "))
    }

    @Test
    fun `a blank account has no identity`() {
        assertNull(DeferredDeleteRules.normalizeAccount(null))
        assertNull(DeferredDeleteRules.normalizeAccount("   "))
    }

    @Test
    fun `only the same account may replay`() {
        assertTrue(DeferredDeleteRules.mayReplay("user@example.com", "User@Example.com"))
        assertFalse(DeferredDeleteRules.mayReplay("user@example.com", "other@example.com"))
        assertFalse(DeferredDeleteRules.mayReplay("user@example.com", "  "))
        assertFalse(DeferredDeleteRules.mayReplay("user@example.com", null))
    }

    @Test
    fun `identities carry both the user id and the email, normalized`() {
        assertEquals(
            setOf("uid-123", "user@example.com"),
            DeferredDeleteRules.identities("uid-123", "  User@Example.com ")
        )
    }

    @Test
    fun `identities drop blanks rather than adding empty entries`() {
        assertEquals(setOf("user@example.com"), DeferredDeleteRules.identities(null, "user@example.com"))
        assertEquals(setOf("uid-123"), DeferredDeleteRules.identities("uid-123", "   "))
        assertTrue(DeferredDeleteRules.identities(null, null).isEmpty())
    }

    @Test
    fun `id is stable and distinguishes table and key`() {
        val a = DeferredDeleteRules.id("u", "sync_watch_history", "item_id", "p:a:tt1")
        val b = DeferredDeleteRules.id("u", "sync_watch_history", "item_id", "p:a:tt1")
        val c = DeferredDeleteRules.id("u", "sync_watch_history", "item_id", "p:a:tt2")
        val d = DeferredDeleteRules.id("u", "sync_watched_status", "item_id", "p:a:tt1")
        assertEquals(a, b)
        assertFalse(a == c)
        assertFalse(a == d)
    }
}
