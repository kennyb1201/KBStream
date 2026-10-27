package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.addon.Stream
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-session addon demotion: which source the advance lands on once an
 * addon is dead, and which source the pre-playback probe starts from.
 *
 * The reported bug both mechanisms exist for: "try next source" walked the
 * ranked list in order, so a viewer watched three or four failures from the
 * same dead addon before reaching a working source from another - and the first
 * failure was always user-visible, because the player had already tried and
 * timed out on a link a one-byte probe would have rejected.
 *
 * The advance is pinned on its own because it is the rule both the manual "try
 * next source" button and the automatic open-failure advance share: an
 * Activity that builds a player cannot be driven in a JVM test, but this rule
 * can. The probe is pinned against a fake HTTP answer, including the timeout
 * that must count as a failure.
 */
class SourceAddonSessionTest {

    private fun stream(url: String) = Stream(name = url, url = url)

    /** A dead set built through the public writer, so the tests pin normalization. */
    private fun deadOf(vararg addons: String): MutableSet<String> =
        mutableSetOf<String>().also { set ->
            addons.forEach { SourceAddonSession.markDead(set, it) }
        }

    // ── the advance skip ────────────────────────────────────────────

    @Test
    fun `a dead addon is skipped and the next source from another addon wins`() {
        // The reported shape: the head of the list is several more links from
        // the addon that just failed.
        val addons = listOf("A", "A", "B", "A", "C")
        // Index 0 is the source playing now, so the scan starts at 1.
        assertEquals(2, SourceAddonSession.nextIndex(addons, 1, deadOf("A")))
    }

    @Test
    fun `when every remaining addon is dead the advance falls back to plain order`() {
        // A dead link is still better than "no sources".
        val addons = listOf("A", "A", "B", "A", "C")
        assertEquals(1, SourceAddonSession.nextIndex(addons, 1, deadOf("A", "B", "C")))
    }

    @Test
    fun `the surviving set keeps the ranked order`() {
        // The demotion filters, it does not re-rank: with A dead the first
        // survivor is B at index 1, and with B dead too it is C at index 2 -
        // never a promotion of a later source over an earlier one.
        val addons = listOf("A", "B", "C", "B", "C")
        assertEquals(1, SourceAddonSession.nextIndex(addons, 0, deadOf("A")))
        assertEquals(2, SourceAddonSession.nextIndex(addons, 0, deadOf("A", "B")))
    }

    @Test
    fun `an unknown addon is never skipped`() {
        // A null entry, an empty entry, and an addon the app has no name for.
        val addons = listOf<String?>(null, "", "A", "B")
        assertEquals(0, SourceAddonSession.nextIndex(addons, 0, deadOf("A", "B")))
        assertEquals(1, SourceAddonSession.nextIndex(addons, 1, deadOf("A", "B")))
        // ...and an addon that IS dead is skipped even with unknowns around it.
        assertEquals(3, SourceAddonSession.nextIndex(addons, 2, deadOf("A")))
    }

    @Test
    fun `dead addons compare through the shared normalization`() {
        // The player and the source fetch name the same addon differently, so
        // every comparison goes through SourceAddonPreference.normalize.
        val addons = listOf<String?>("AIOStreams", "Other")
        assertEquals(1, SourceAddonSession.nextIndex(addons, 0, deadOf("aiostreams")))
    }

    @Test
    fun `an exhausted list has no next source`() {
        assertNull(SourceAddonSession.nextIndex(listOf("A", "B"), 2, emptySet()))
        assertNull(SourceAddonSession.nextIndex(emptyList(), 0, emptySet()))
    }

    @Test
    fun `nothing dead leaves the order exactly as it was`() {
        val addons = listOf<String?>("A", "B", "C")
        assertEquals(0, SourceAddonSession.nextIndex(addons, 0, emptySet()))
        assertEquals(2, SourceAddonSession.nextIndex(addons, 2, emptySet()))
    }

    // ── the tab-scoped advance ──────────────────────────────────────
    //
    // The manual/automatic split: a press that names the tab the viewer is
    // looking at walks ONLY that tab's rows - it must not jump into another
    // add-on behind their back - while every automatic recovery walks the
    // merged list pinned above. The engines open their SOURCES picker when a
    // tab runs out, so an exhausted tab answers null instead of falling back to
    // a link already known dead.

    /** The walk with every row usable: these cases are membership and death. */
    private fun tabWalk(
        addons: List<String?>,
        fromIndex: Int,
        tab: String?,
        dead: Set<String> = emptySet(),
        usable: (Int) -> Boolean = { true }
    ) = SourceAddonSession.tabbedNextIndex(addons, fromIndex, dead, tab, usable)

    @Test
    fun `a tab walk lands on its own next row and on nothing outside it`() {
        val addons = listOf<String?>("A", "A", "B", "A", "C")
        // [fromIndex] is INCLUSIVE, as it is for nextIndex above: the engines
        // scan from the row AFTER the one playing, so they pass index + 1.
        assertEquals("the scan starts on the first candidate and takes it", 1, tabWalk(addons, 1, tab = "A"))
        assertEquals("the one after B is 3", 3, tabWalk(addons, 3, tab = "A"))
        assertNull("past the last A there is no A left", tabWalk(addons, 4, tab = "A"))
        assertEquals(
            "B's whole tab is index 2, and C's is 4 - never another add-on's row",
            2,
            tabWalk(addons, 1, tab = "B")
        )
    }

    @Test
    fun `a dead tab row is skipped to the next live row of the same tab, then stops`() {
        // Inside one add-on every row IS that add-on, so a dead add-on empties
        // its tab: the walk must stop rather than hand back a link already
        // known dead (the engine opens the SOURCES picker instead of switching).
        val addons = listOf<String?>("A", "A", "A", "B")
        assertNull(tabWalk(addons, 1, tab = "A", dead = deadOf("A")))
        assertEquals(
            "while a tab whose own add-on is alive still walks",
            1,
            tabWalk(addons, 1, tab = "A", dead = deadOf("B"))
        )
    }

    @Test
    fun `a row the engine cannot open is stepped past inside the tab`() {
        // The engines' blank-URL / hash guard rides the scope, so a hash row in
        // the tab is not read as the tab's end while a url-bearing row follows.
        val addons = listOf<String?>("A", "A", "B")
        assertEquals(
            "index 0 is a hash row, so the walk steps to index 1",
            1,
            tabWalk(addons, 0, tab = "A") { it != 0 }
        )
        assertNull(
            "and when only unopenable rows are left the tab is exhausted",
            tabWalk(addons, 0, tab = "A") { false }
        )
    }

    @Test
    fun `a tab that is not in this session's list is empty`() {
        // A stale name: the add-on dropped out of the payload between the picker
        // and the player. There is nothing to advance to, and the walk must not
        // fall back to the merged list - the ADVANCE is scoped, the choice is
        // not, and the in-player SOURCES picker is where that choice is made.
        val addons = listOf<String?>("A", "B")
        assertNull("a name matching nothing", tabWalk(addons, 0, tab = "Gone"))
        assertNull("a blank name scopes nothing", tabWalk(addons, 0, tab = "  "))
        assertNull("and neither does a null one", tabWalk(addons, 0, tab = null))
    }

    @Test
    fun `unknown rows are never members of any tab`() {
        // A null/blank entry is an add-on the payload did not describe. The
        // merged walk never skips one (pinned above); the tab walk must never
        // claim one either.
        val addons = listOf<String?>(null, "", "A")
        assertNull(
            "a blank name matches no entry, not even the blank ones",
            tabWalk(addons, 0, tab = " ")
        )
        assertEquals("while the real A at index 2 still does", 2, tabWalk(addons, 0, tab = "A"))
    }

    @Test
    fun `tab membership compares through the shared normalization`() {
        val addons = listOf<String?>("AIOStreams", "Other")
        assertEquals(
            "the picker's spelling and the player's are the same add-on",
            0,
            tabWalk(addons, 0, tab = "aiostreams")
        )
    }

    // ── marking dead ────────────────────────────────────────────────

    @Test
    fun `a blank addon name is never marked dead`() {
        val dead = mutableSetOf<String>()
        assertFalse(SourceAddonSession.markDead(dead, null))
        assertFalse(SourceAddonSession.markDead(dead, "   "))
        assertTrue("an unattributable failure must not demote anything", dead.isEmpty())
    }

    @Test
    fun `marking the same addon twice reports the second as already dead`() {
        val dead = mutableSetOf<String>()
        assertTrue(SourceAddonSession.markDead(dead, "AIOStreams"))
        assertFalse(SourceAddonSession.markDead(dead, "aio streams"))
        assertEquals(1, dead.size)
    }

    // ── the pre-playback probe ──────────────────────────────────────

    @Test
    fun `the probe picks the first source that answers and marks the dead addon`() {
        val candidates = listOf(stream("u0"), stream("u1"), stream("u2"))
        val addons = listOf("A", "A", "B")
        val dead = mutableSetOf<String>()
        val answered = mapOf("u0" to false, "u1" to false, "u2" to true)
        val probe = SourcePlaybackProbe(dead) { url, _, _ -> answered.getValue(url) }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("the third source is the first servable one", "u2", pick.url)
        assertEquals("both failures belonged to A", setOf("a"), dead)
    }

    @Test
    fun `when every probe fails the top source is returned anyway`() {
        val candidates = listOf(stream("u0"), stream("u1"), stream("u2"))
        val addons = listOf("A", "A", "B")
        val dead = mutableSetOf<String>()
        val probe = SourcePlaybackProbe(dead) { _, _, _ -> false }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("the probe is a hint, not a verdict", "u0", pick.url)
        assertEquals(setOf("a", "b"), dead)
    }

    @Test
    fun `a timeout counts as a probe failure`() {
        val candidates = listOf(stream("u0"), stream("u1"))
        val addons = listOf("A", "B")
        val dead = mutableSetOf<String>()
        // Concurrent probes: a plain Int counter is not safe to increment here.
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val probe = SourcePlaybackProbe(dead) { _, _, _ ->
            calls.incrementAndGet()
            throw SocketTimeoutException("probe timed out")
        }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("u0", pick.url)
        assertEquals("every candidate was still attempted", 2, calls.get())
        assertEquals(setOf("a", "b"), dead)
    }

    @Test
    fun `the probe stops at its cap`() {
        val candidates = (0 until 6).map { stream("u$it") }
        val addons = candidates.indices.map { "addon$it" }
        val dead = mutableSetOf<String>()
        // Probes run concurrently (audit P2), so this recorder is touched from
        // several threads - a plain ArrayList loses entries here.
        val probed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val probe = SourcePlaybackProbe(dead) { url, _, _ ->
            probed += url
            false
        }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("the top-ranked source loads when nothing answers", "u0", pick.url)
        // Probes run at once (audit P2), so completion order is not the rule;
        // WHICH candidates get probed is, and it is still the first three.
        assertEquals(
            "three probes at most",
            setOf("u0", "u1", "u2"),
            probed.toSet()
        )
        assertEquals(setOf("addon0", "addon1", "addon2"), dead)
    }

    @Test
    fun `a source with no url cannot be probed and is not blamed`() {
        // A torrent-only row has no URL to GET, so its addon must not be marked
        // dead on a request that could never succeed.
        val candidates = listOf(
            Stream(name = "torrent", infoHash = "abc"),
            stream("u1")
        )
        val addons = listOf("A", "B")
        val dead = mutableSetOf<String>()
        var calls = 0
        val probe = SourcePlaybackProbe(dead) { _, _, _ ->
            calls += 1
            true
        }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("u1", pick.url)
        assertEquals("only the real URL was fetched", 1, calls)
        assertTrue("nothing was demoted on the unprobeable row", dead.isEmpty())
    }

    @Test
    fun `a single source is never probed`() {
        val candidates = listOf(stream("u0"))
        var calls = 0
        val probe = SourcePlaybackProbe(mutableSetOf()) { _, _, _ ->
            calls += 1
            true
        }

        val pick = runBlocking {
            probe.pickLiveSource(candidates, listOf("A")) { emptyMap() }
        }

        assertEquals("u0", pick.url)
        assertEquals("a one-source list has nothing to choose between", 0, calls)
    }

    @Test
    fun `the probe sends the source's own playback headers`() {
        val candidates = listOf(stream("u0"), stream("u1"))
        val seen = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val probe = SourcePlaybackProbe(mutableSetOf()) { _, headers, _ ->
            seen += headers
            true
        }

        runBlocking {
            probe.pickLiveSource(candidates, listOf("A", "B")) { stream ->
                mapOf("Referer" to stream.url.orEmpty())
            }
        }

        // Both candidates are probed now (concurrently), and each request must
        // carry its OWN source's headers - never the head source's.
        assertEquals(
            setOf(mapOf("Referer" to "u0"), mapOf("Referer" to "u1")),
            seen.toSet()
        )
    }

    // ── pinning the explicitly chosen initial source ─────────────────
    //
    // The reported bug: "Play manually" tapped a source, but the player opened
    // a different one. The probe walked the full ranked list with no knowledge
    // of the explicit choice, so the first live candidate whose URL differed
    // from the session's current URL replaced it. The fix feeds the probe the
    // initial source first (probeCandidates); these two cases are the whole
    // point - a live choice is kept, a dead one still falls through.

    @Test
    fun `a live pinned initial source is returned first and nothing is demoted`() {
        // Tap order: A live, B live, current = B. probeCandidates pins B to the
        // head, so the probe answers with B and never leaves that pick.
        val sources = listOf(stream("A"), stream("B"))
        val addons = listOf("A", "B")
        val (candidates, ordered) = probeCandidates(sources, addons, "B", null, emptyMap())
        assertEquals("the pinned source is probed first", listOf("B", "A"), candidates.map { it.url })
        val dead = mutableSetOf<String>()
        val probe = SourcePlaybackProbe(dead) { _, _, _ -> true }

        val pick = runBlocking { probe.pickLiveSource(candidates, ordered) { emptyMap() } }

        assertEquals("the tapped source is pinned", "B", pick.url)
        assertTrue("a live pick demotes nothing", dead.isEmpty())
    }

    @Test
    fun `a dead pinned initial source falls through to rank order and is marked dead`() {
        // Current = B, but B is dead: the probe must fall through to A in rank
        // order and mark B's addon dead for the session.
        val sources = listOf(stream("A"), stream("B"))
        val addons = listOf("A", "B")
        val (candidates, ordered) = probeCandidates(sources, addons, "B", null, emptyMap())
        val dead = mutableSetOf<String>()
        val probe = SourcePlaybackProbe(dead) { url, _, _ -> url == "A" }

        val pick = runBlocking { probe.pickLiveSource(candidates, ordered) { emptyMap() } }

        assertEquals("the pick was dead, so rank order wins", "A", pick.url)
        assertEquals("its addon is dead for the session", setOf("b"), dead)
    }

    // ── the pinned candidate's probe budget ─────────────────────────
    //
    // The bug this closes: every candidate was probed under the same 2.5s
    // ceiling, so a tapped source that was merely slow to answer (cold TLS plus
    // a debrid redirect chain) was read as dead and the session opened a
    // different source - the very override the pinning above exists to
    // prevent, arriving through the timeout instead of through rank order. Only
    // the pinned head of the probe order now gets the larger ceiling.

    @Test
    fun `only the head of the probe order gets the larger ceiling`() {
        assertEquals(SourcePlaybackProbe.PINNED_TIMEOUT_MS, probeTimeoutMsFor(isPinned = true))
        assertEquals(SourcePlaybackProbe.TIMEOUT_MS, probeTimeoutMsFor(isPinned = false))
        assertTrue(
            "patience for the viewer's own pick must exceed a ranked guess",
            SourcePlaybackProbe.PINNED_TIMEOUT_MS > SourcePlaybackProbe.TIMEOUT_MS
        )
    }

    @Test
    fun `the probe spends the larger ceiling on the pinned head only`() {
        val candidates = listOf(stream("pinned"), stream("ranked1"), stream("ranked2"))
        // Probes run on their own threads, so this recorder has to be one that
        // can take concurrent appends - the same reason the cap and timeout
        // cases above use a synchronized list. A plain ArrayList lost entries
        // under the release gate and read as a candidate that was never probed.
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val probe = SourcePlaybackProbe(mutableSetOf()) { url, _, timeoutMs ->
            seen += url to timeoutMs
            false
        }

        runBlocking { probe.pickLiveSource(candidates, listOf("A", "B", "C")) { emptyMap() } }

        // Probes run concurrently (audit P2), so completion order is not the
        // rule; what each candidate is probed WITH still is.
        assertEquals(
            "the tapped source is probed with more patience than the ranked ones",
            mapOf(
                "pinned" to SourcePlaybackProbe.PINNED_TIMEOUT_MS,
                "ranked1" to SourcePlaybackProbe.TIMEOUT_MS,
                "ranked2" to SourcePlaybackProbe.TIMEOUT_MS
            ),
            seen.toMap()
        )
    }

    @Test
    fun `a slow but live tapped source stays chosen instead of falling through`() {
        // The end-to-end shape of the fix. The tapped source answers later than
        // the ranked ceiling but within its own, and the ranked candidate would
        // have answered instantly - so without the larger pinned ceiling the
        // probe walks past a live tap and demotes its addon.
        val sources = listOf(stream("tapped"), stream("ranked"))
        val (candidates, ordered) =
            probeCandidates(sources, listOf("A", "B"), "tapped", null, emptyMap())
        val dead = mutableSetOf<String>()
        val probe = SourcePlaybackProbe(dead) { url, _, timeoutMs ->
            if (url == "tapped") {
                timeoutMs >= SourcePlaybackProbe.PINNED_TIMEOUT_MS
            } else {
                true
            }
        }

        val pick = runBlocking { probe.pickLiveSource(candidates, ordered) { emptyMap() } }

        assertEquals("the tapped source is what opens", "tapped", pick.url)
        assertTrue("a source that did answer is never demoted", dead.isEmpty())
    }
}
