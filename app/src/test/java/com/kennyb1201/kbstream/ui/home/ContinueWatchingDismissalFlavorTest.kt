package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Remove from Continue Watching" must hold across id flavors.
 *
 * Reported: a dismissed next-up card came back a day later. The removal keeps
 * the completed-episode history (so watched badges survive), and the rail
 * REBUILDS the card from those kept rows on every Home build - so the
 * dismissal is the only thing standing between them, and it was recorded and
 * looked up under the one key the dismissed card happened to carry. The
 * rebuilt card carries the flavor in the local rows, which can be the other
 * one. A resume card never showed this: its rows are deleted, so nothing
 * rebuilds it.
 */
class ContinueWatchingDismissalFlavorTest {

    private fun card(
        parentId: String?,
        tmdbId: Int? = null,
        title: String = "South Park",
        parentType: String = "series",
        season: Int? = 29,
        episode: Int? = 2,
        recencyTimestamp: Long = 0L
    ): UpNextItem = UpNextItem(
        id = "history:${parentId.orEmpty()}:$season:$episode",
        title = title,
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        subtitle = "Up Next - S${season}E$episode",
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        tmdbId = tmdbId,
        recencyTimestamp = recencyTimestamp
    )

    /** What the two write sites record: every strong key, one timestamp. */
    private fun dismiss(
        item: UpNextItem,
        at: Long,
        into: MutableMap<String, Long>
    ) {
        upNextDismissalKeys(item).forEach { key -> into[key] = at }
    }

    /** What the publish-time filter asks. */
    private fun hidden(
        item: UpNextItem,
        dismissals: MutableMap<String, Long>
    ): Boolean = continueWatchingDismissalOutcome(
        dismissalKeys = upNextDismissalKeys(item),
        dismissals = dismissals,
        recencyTimestamp = item.recencyTimestamp
    ) == ContinueWatchingDismissalOutcome.HIDDEN

    // ── T1/T2: the two flavors of one show ───────────────────────────

    @Test
    fun `an imdb-flavored dismissal holds for a rebuilt tmdb-flavored card`() {
        val dismissals = mutableMapOf<String, Long>()

        dismiss(
            card(parentId = "tt0121955", tmdbId = 2190),
            at = 1_000L,
            into = dismissals
        )

        val rebuilt = card(parentId = "tmdb:2190", tmdbId = 2190)

        assertTrue(
            "the rebuilt card is the same show and must stay gone",
            hidden(rebuilt, dismissals)
        )
    }

    @Test
    fun `a tmdb-flavored dismissal holds for a rebuilt imdb-flavored card`() {
        val dismissals = mutableMapOf<String, Long>()

        dismiss(
            card(parentId = "tmdb:2190", tmdbId = 2190),
            at = 1_000L,
            into = dismissals
        )

        assertTrue(
            hidden(card(parentId = "tt0121955", tmdbId = 2190), dismissals)
        )
    }

    @Test
    fun `the tmdb-number key is the bridge between the flavors`() {
        val imdbCard = upNextDismissalKeys(card("tt0121955", tmdbId = 2190))
        val tmdbCard = upNextDismissalKeys(card("tmdb:2190", tmdbId = 2190))

        assertEquals(
            setOf("parent:series:tt0121955", "parent:series:2190"),
            imdbCard
        )
        assertEquals(setOf("parent:series:2190"), tmdbCard)
        assertTrue(imdbCard intersect tmdbCard == tmdbCard)
    }

    // ── T3: a dismissal recorded before this fix ─────────────────────

    @Test
    fun `a single-key dismissal stored before the fix still holds`() {
        // What the old code wrote, verbatim: one key, no bridge.
        val dismissals = mutableMapOf("parent:series:tt0121955" to 1_000L)

        assertTrue(hidden(card(parentId = "tt0121955"), dismissals))
    }

    // ── T4: re-arm ──────────────────────────────────────────────────

    @Test
    fun `watching again clears every key so the next rebuild does not re-hide it`() {
        val dismissals = mutableMapOf<String, Long>()

        dismiss(
            card(parentId = "tt0121955", tmdbId = 2190),
            at = 1_000L,
            into = dismissals
        )

        val watchedAgain = card(
            parentId = "tt0121955",
            tmdbId = 2190,
            recencyTimestamp = 2_000L
        )

        assertEquals(
            ContinueWatchingDismissalOutcome.REARMED,
            continueWatchingDismissalOutcome(
                dismissalKeys = upNextDismissalKeys(watchedAgain),
                dismissals = dismissals,
                recencyTimestamp = watchedAgain.recencyTimestamp
            )
        )
        assertTrue(dismissals.isEmpty())

        // The next Home build rebuilds the card under the other flavor: it
        // must not be filtered again.
        assertFalse(hidden(card(parentId = "tmdb:2190", tmdbId = 2190), dismissals))
    }

    // ── T5: one show's dismissal never hides another ─────────────────

    @Test
    fun `an id-carrying card never records the title key`() {
        val keys = upNextDismissalKeys(card("tt0121955", tmdbId = 2190))

        assertFalse(
            "two shows can share a normalized title",
            keys.any { it.startsWith("title:") }
        )
    }

    @Test
    fun `another show with the same title but its own id passes`() {
        val dismissals = mutableMapOf<String, Long>()

        dismiss(
            card(parentId = "tt0121955", tmdbId = 2190, title = "Breeders"),
            at = 1_000L,
            into = dismissals
        )

        assertFalse(
            hidden(
                card(parentId = "tt00000001", tmdbId = 11111, title = "Breeders"),
                dismissals
            )
        )
    }

    // ── T6: an id-less card, exactly as today ─────────────────────────

    @Test
    fun `an id-less card dismisses and filters under its title key`() {
        val idless = card(parentId = null, tmdbId = null, title = "Some Show")

        assertEquals(
            setOf("title:series:some show"),
            upNextDismissalKeys(idless)
        )

        val dismissals = mutableMapOf<String, Long>()

        dismiss(idless, at = 1_000L, into = dismissals)

        assertTrue(hidden(card(parentId = null, tmdbId = null, title = "Some Show"), dismissals))
        assertFalse(hidden(card(parentId = null, tmdbId = null, title = "Another Show"), dismissals))
    }

    // ── wiring ──────────────────────────────────────────────────────

    private val root: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val viewModel =
        source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    private val upNext =
        source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")

    @Test
    fun `both dismissals are recorded under the card's keys`() {
        val writes = Regex("upNextDismissalKeys\\(item\\)\\.forEach")
            .findAll(viewModel)
            .count()

        assertEquals(
            "Remove and Mark as Watched must both widen the write",
            2,
            writes
        )
        assertFalse(
            "the single-key write is the defect",
            viewModel.contains("showDedupeKey(item)")
        )
    }

    @Test
    fun `the publish-time filter asks the rule with the card's keys`() {
        assertTrue(
            viewModel.contains("continueWatchingDismissalOutcome(") &&
                viewModel.contains("dismissalKeys = upNextDismissalKeys(item),")
        )
    }

    @Test
    fun `the stored dismissal key keeps its shape`() {
        // upNextShowKey is the persisted key: its shape must not change, and
        // the key set is built BESIDE it, not out of a new one.
        assertTrue(
            upNext.contains(
                "return \"parent:\${upNextMediaType(item.parentType)}:\$normalizedParentId\""
            )
        )
        assertTrue(
            upNext.contains(
                "upNextStrongIdentityKeys(item).ifEmpty { setOf(upNextShowKey(item)) }"
            )
        )
        assertTrue(
            "the dismissal still persists as the same key -> timestamp JSON map",
            viewModel.contains("json.put(key, dismissedAt)")
        )
        assertTrue(
            viewModel.contains("PrefsPayloadBuilder.KEY_DISMISSALS")
        )
    }
}
