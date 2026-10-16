package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `image_overrides` is the FOURTH synced table, and every path that makes that
 * true is pinned here.
 *
 * The field report: a poster or backdrop chosen by long-press in the Media
 * section stayed on the device that set it. The local table was written and
 * read correctly - it was simply not part of the account: SupabaseSync synced
 * three tables, so the sibling device went on drawing TMDB's artwork.
 *
 * Between them these tests cover the whole round trip, because a table that is
 * half-wired is worse than none: a row that uploads but is never pulled, or one
 * that is pulled but whose realtime channel is never subscribed (so the change
 * only lands on the next full sync), or a reset that is a cloud DELETE the
 * sibling cannot see.
 *
 * The decisions themselves are pinned behaviourally by [ImageOverrideSyncTest];
 * this file pins that the app calls them, in the right places.
 */
class ImageOverrideSyncWiringContractTest {

    private val sync: String by lazy { read(SYNC) }
    private val dto: String by lazy { read(SYNC_DTO) }
    private val rls: String by lazy { read(RLS_SQL) }
    private val realtime: String by lazy { read(REALTIME_SQL) }

    /**
     * Reads a file by its path from the repository ROOT.
     *
     * The working directory differs between a Gradle test (the module, `app/`)
     * and an IDE run (the root), so the root is found by walking up until the
     * requested path exists rather than assumed.
     */
    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not find $relative walking up from " + System.getProperty("user.dir")
        )
    }

    private fun body(source: String, anchor: String, spanLength: Int = 4_000): String {
        val at = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", at >= 0)
        return source.substring(at, minOf(source.length, at + spanLength))
    }

    // ── the row the wire carries ───────────────────────────────────────────

    @Test
    fun `the sync DTO carries a key column of its own`() {
        assertTrue(
            "the realtime applier tells a table apart by the fields its record " +
                "carries, so this table needs its own key column - reusing " +
                "item_key would route its changes into the watched-marker merge",
            dto.contains("@SerialName(\"override_key\") val overrideKey: String? = null")
        )
    }

    // ── the table is declared, subscribed and routed ───────────────────────

    @Test
    fun `the table and its key column are declared once`() {
        assertTrue(sync.contains("private const val TABLE_OVERRIDES = \"sync_image_overrides\""))
        assertTrue(sync.contains("private const val KEY_OVERRIDES = \"override_key\""))
    }

    @Test
    fun `realtime subscribes to four tables and says so`() {
        assertTrue(
            "the new table must be in the subscription list, or changes only " +
                "arrive on the next full pull",
            sync.contains(
                "private val REALTIME_TABLES =\n" +
                    "        listOf(TABLE_HISTORY, TABLE_WATCHED, TABLE_OVERRIDES, TABLE_PREFS)"
            )
        )
        // 4_000, not the 2_000 used elsewhere: the join carries the dead-channel
        // drop, the tracked-set read and the budget note before it reaches the
        // log line, and a slice that stops short of it would assert on nothing.
        val start = body(sync, "private fun startRealtime(force: Boolean = false) {", 4_000)
        assertTrue(
            "and the join walks that list, so the four tables are the four " +
                "the log line counts",
            start.contains("REALTIME_TABLES.filterNot") &&
                start.contains("\"realtime subscribed to \${missing.size} new table(s); \"")
        )
    }

    @Test
    fun `a realtime row is routed to the override merge`() {
        val routing = body(sync, "val table = when {", 400)
        assertTrue(routing.contains("record[KEY_OVERRIDES] != null -> TABLE_OVERRIDES"))
        assertTrue(
            "and it is applied by the override merge, which is also the pull's",
            body(sync, "when (table) {", 400).contains("TABLE_OVERRIDES -> applyImageOverrideRow(row)")
        )
    }

    @Test
    fun `the outbox uploads the row under the override key column`() {
        val flush = body(sync, "private suspend fun flushOutbox()", 3_000)
        assertTrue(
            "without this the bulk upsert would write the key into pref_key",
            flush.contains("KEY_OVERRIDES -> KEY_OVERRIDES")
        )
    }

    // ── the write path, both directions ───────────────────────────────────

    @Test
    fun `a chosen override is published, and signed out it is a no-op like history`() {
        val enqueue = body(sync, "fun enqueueImageOverride(", 1_200)
        assertTrue(enqueue.contains("if (!isSignedIn()) return"))
        assertTrue(enqueue.contains("OutboxItem("))
        assertTrue(enqueue.contains("TABLE_OVERRIDES"))
        assertTrue(enqueue.contains("ImageOverrideRowRules.payload(entity, updatedAt)"))
    }

    @Test
    fun `a reset is published as a tombstone, and staged while signed out`() {
        val clear = body(sync, "fun clearImageOverrides(", 1_800)
        assertTrue(
            "a cloud DELETE is invisible to a sibling that still holds the row",
            clear.contains("ImageOverrideRowRules.tombstone(key, now)")
        )
        assertTrue(
            "and a signed-out reset must be replayed on that account's next " +
                "sign-in, or the cloud copy comes straight back",
            clear.contains("stageSignedOutOverrideClear(clean, profileId)")
        )
        assertTrue(
            "the staged delete is table-generic, so it needs no new machinery",
            body(sync, "private fun stageSignedOutOverrideClear(", 1_200)
                .contains("table = TABLE_OVERRIDES")
        )
    }

    // ── the pull and the seed push ────────────────────────────────────────

    @Test
    fun `the pull and the push both include the new table`() {
        assertTrue(body(sync, "private suspend fun pullAllNow(", 500).contains("pullOverrides(context)"))
        assertTrue(body(sync, "private suspend fun pushAllNow(", 500).contains("pushOverrides(context)"))
    }

    @Test
    fun `the pull merges through the same rule the realtime path uses`() {
        val pull = body(sync, "private suspend fun pullOverrides(", 2_400)
        assertTrue(
            "one merge rule for both paths, or a live change and a pull can " +
                "disagree about what a remote choice means",
            pull.contains("applyImageOverrideRow(row)")
        )
        assertTrue(
            "and it filters by profile before resolving the table",
            pull.contains("storedKeyMatchesProfile(storedKey, pid)")
        )
    }

    @Test
    fun `the applier supersedes a stale row, honours a reset and refreshes the mirror`() {
        val apply = body(sync, "private suspend fun applyImageOverrideRow(", 2_400)
        assertTrue(
            "a write this device has queued and not yet flushed is the newer one",
            apply.contains("outbox.pending(TABLE_OVERRIDES, KEY_OVERRIDES, storedKey)")
        )
        assertTrue(
            "a reset clears the local row (using the row's own columns, so the " +
                "composed key is never parsed)",
            apply.contains("dao.clear(local.tmdbId, local.type, local.screen)")
        )
        assertTrue(
            "an identical row is a no-op, which is what stops two devices " +
                "echoing one choice forever",
            apply.contains("if (local?.imagePath == entity.imagePath) return")
        )
        assertTrue(
            "and the in-memory mirror the surfaces read is republished, so the " +
                "change appears without an app restart",
            apply.contains("ImageOverrides.refresh(context)")
        )
    }

    @Test
    fun `the seed push only adds keys the account does not have`() {
        val push = body(sync, "private suspend fun pushOverrides(", 2_400)
        assertTrue(
            "the local table carries no per-row timestamp, so a full push would " +
                "have to stamp every row with now() - and an older local choice " +
                "would then overwrite a sibling's newer one",
            push.contains("all.filter { entity -> entity.key !in cloudKeys }")
        )
    }

    // ── the write the menu actually makes is the one that syncs ────────────

    @Test
    fun `the long-press write is what publishes the choice`() {
        val images = read(IMAGE_OVERRIDES)
        assertTrue(
            "the set path must enqueue after the local row lands",
            body(images, "private suspend fun persistSet(", 1_200)
                .contains("SupabaseSync.enqueueImageOverride(row)")
        )
        assertTrue(
            "and the reset path must tombstone the surfaces it just cleared",
            body(images, "private suspend fun persistClear(", 1_400)
                .contains("SupabaseSync.clearImageOverrides(")
        )
    }

    // ── the deployment side: the table has to exist, with RLS and realtime ──

    @Test
    fun `the schema file creates the table with owner-scoped policies`() {
        assertTrue(rls.contains("create table if not exists public.sync_image_overrides"))
        assertTrue(rls.contains("override_key text primary key"))
        assertTrue(
            "ownership is what the RLS policies key on",
            rls.contains("'sync_image_overrides','sync_prefs'")
        )
        assertTrue(rls.contains("alter table public.sync_image_overrides enable row level security;"))
        assertTrue(rls.contains("create policy \"kbstream_own_image_overrides\""))
        assertTrue(
            "a leftover permissive policy would re-open every other account's " +
                "rows, so the converge loop has to know about this table too",
            rls.contains("'kbstream_own_image_overrides',")
        )
        assertTrue(
            "an existing install syncs on the old schema, so the ownership and " +
                "primary-key steps must include the new table",
            rls.contains("array['item_id','item_key','override_key','pref_key']")
        )
    }

    @Test
    fun `the realtime publication includes the table`() {
        assertTrue(
            "a table outside the publication gets its join rejected, and live " +
                "propagation never happens",
            realtime.contains("'sync_image_overrides',")
        )
        assertFalse(
            "and the file's own prose must not still say three",
            realtime.contains("three sync tables")
        )
    }

    private companion object {
        const val SYNC = "app/src/main/java/com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val SYNC_DTO = "app/src/main/java/com/kennyb1201/kbstream/data/sync/SyncRowDto.kt"
        const val IMAGE_OVERRIDES =
            "app/src/main/java/com/kennyb1201/kbstream/data/images/ImageOverrides.kt"
        const val RLS_SQL = "docs/supabase_sync_rls.sql"
        const val REALTIME_SQL = "docs/supabase_realtime.sql"
    }
}
