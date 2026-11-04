package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden titles are part of the account, and this file pins every path that
 * makes that true.
 *
 * The field report: a title hidden on the television was still on the phone's
 * rails. The store was written and read correctly - it was simply not in the
 * sync payload, so nothing carried it. A half-wired blob is worse than none: a
 * blob that is built but never gated blank-checks every sibling on its first
 * sync, and one that is gated but never applied looks exactly like the original
 * bug.
 *
 * The merge itself is pinned behaviourally by `HiddenTitlesSyncTest` (in
 * `data/library`); this file pins that the app calls it, in the right places.
 */
class HiddenTitlesSyncWiringContractTest {

    private val payload: String by lazy { read(PAYLOAD) }
    private val sync: String by lazy { read(SUPABASE_SYNC) }
    private val store: String by lazy { read(HIDDEN_TITLES) }

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

    /** The text between two anchors, so a slice cannot run into a neighbour. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("anchor not found: $start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("$end must follow $start", to > from)
        return source.substring(from, to)
    }

    // ── the payload key ────────────────────────────────────────────────────

    @Test
    fun `the blob has its own key and the bulk push carries it`() {
        assertTrue(
            "its own key, not a row in the display-prefs blob: this is a per-title " +
                "merge, and a blob whose point is 'the newer event wins per title' " +
                "cannot be one string with one stamp",
            payload.contains("const val KEY_HIDDEN_TITLES = \"hidden_titles\"")
        )
        assertTrue(
            "buildAll is what the bulk push walks, so a key missing here is a " +
                "blob that never leaves the device",
            body(payload, "fun buildAll(", 3_000)
                .contains("KEY_HIDDEN_TITLES to buildHiddenTitles(context)")
        )
    }

    @Test
    fun `a pulled row reaches the store through its own arm`() {
        val arms = body(payload, "when (prefKey) {", 2_400)
        assertTrue(arms.contains("PrefsPayloadBuilder.KEY_HIDDEN_TITLES ->"))
        assertTrue(
            "and it merges rather than replaces",
            body(payload, "PrefsPayloadBuilder.KEY_HIDDEN_TITLES ->", 400)
                .contains("applyHiddenTitles(context, payload, profileId)")
        )
        val apply = between(
            payload,
            "private fun applyHiddenTitles(",
            "private fun applyDismissals("
        )
        assertTrue(apply.contains(".mergeFromSync(context, entries, removed, profileId)"))
        assertTrue(
            "both halves blank is 'the account has nothing', never 'clear yours'",
            apply.contains("if (entries.isBlank() && removed.isBlank()) return")
        )
        assertFalse(
            "precedence is each row's own stamp, so the blob stamp must not win anything",
            apply.contains("payloadUpdatedAt")
        )
    }

    // ── the push gate ──────────────────────────────────────────────────────

    @Test
    fun `the push consults the gate for this key, not just the builder`() {
        val push = body(sync, "private suspend fun pushPrefsBlobs(", 6_000)
        assertTrue(
            "without this a fresh device publishes an empty set over the account's " +
                "hidden titles before its first pull",
            push.contains("if (key == PrefsPayloadBuilder.KEY_HIDDEN_TITLES &&") &&
                push.contains("!PrefsPayloadBuilder.hasHiddenTitlesToPublish(context)")
        )
    }

    @Test
    fun `the gate is hides or removals, never hides alone`() {
        val gate = body(
            payload,
            "fun hasHiddenTitlesToPublish(context: Context): Boolean",
            500
        )
        assertTrue(
            "a device whose only edit was an unhide still has something to say",
            gate.contains("HiddenTitles.hasHiddenTitlesToPublish(context)")
        )
        assertTrue(
            "and that answer counts both halves",
            body(
                store,
                "fun hasHiddenTitlesToPublish(context: Context, profileId: String? = null)",
                800
            ).let { it.contains("parseEntriesOrNull") && it.contains("parseRemovedOrNull") }
        )
    }

    // ── every local edit publishes ────────────────────────────────────────

    @Test
    fun `hide, unhide and unhideAll all reach the enqueue path`() {
        assertTrue(between(store, "fun hide(", "fun unhide(").contains("publishToAccount(app)"))
        assertTrue(between(store, "fun unhide(", "fun unhideAll(").contains("publishToAccount(app)"))
        assertTrue(
            between(store, "fun unhideAll(", "// ── Cross-device sync")
                .contains("publishToAccount(app)")
        )
        val enqueue = between(store, "private fun publishToAccount(", "private fun sameTitle(")
        assertTrue(enqueue.contains("SupabaseSync.enqueuePrefs("))
        assertTrue(
            "under this blob's key, through the existing outbox: no new transport",
            enqueue.contains("PrefsPayloadBuilder.KEY_HIDDEN_TITLES")
        )
    }

    @Test
    fun `the no-op edits write nothing and publish nothing`() {
        val unhide = between(store, "fun unhide(", "fun unhideAll(")
        assertTrue(
            "an unhide that matched nothing is not an event",
            unhide.contains("if (gone.isEmpty()) return false")
        )
        assertTrue(
            "and the clear of an empty set is not an event either",
            between(store, "fun unhideAll(", "// ── Cross-device sync")
                .contains("if (current.isEmpty()) return")
        )
    }

    @Test
    fun `the merge itself never enqueues`() {
        val merge = between(store, "fun mergeFromSync(", "private fun recordRemoved(")
        assertFalse(
            "a blob that came FROM the account is already there; echoing it back " +
                "would keep two devices writing to each other",
            merge.contains("publishToAccount")
        )
    }

    // ── the blob travels alone ────────────────────────────────────────────

    @Test
    fun `the hidden titles are not folded into a full-replace blob`() {
        val display = between(payload, "fun buildDisplayPrefs(", "fun buildAddons(context: Context)")
        val iptv = between(payload, "fun buildIptv(", "fun buildWatchedOverrides(")
        listOf("display prefs" to display, "IPTV" to iptv).forEach { (name, blob) ->
            assertFalse(
                "a $name blob replaces wholesale, so carrying the merged set there " +
                    "would reintroduce the whole-set race the merge removes",
                blob.contains("hidden_titles") || blob.contains("HiddenTitles")
            )
        }
    }

    @Test
    fun `the blob key is not a display pref`() {
        val start = "internal val SYNCED_PREF_KEYS = setOf("
        val from = payload.indexOf(start)
        assertTrue("anchor not found: $start", from >= 0)
        val to = payload.indexOf("\n    )", from)
        assertTrue("the set's closing paren is missing", to > from)
        assertFalse(
            "that set gates scalar display prefs; a blob key there would be " +
                "pushed as a meaningless scalar",
            payload.substring(from, to).contains("hidden_titles")
        )
    }

    private companion object {
        const val PAYLOAD =
            "app/src/main/java/com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val SUPABASE_SYNC = "app/src/main/java/com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val HIDDEN_TITLES =
            "app/src/main/java/com/kennyb1201/kbstream/data/library/HiddenTitles.kt"
    }
}
