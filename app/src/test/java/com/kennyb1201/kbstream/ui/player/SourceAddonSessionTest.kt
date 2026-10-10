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
        val probe = SourcePlaybackProbe(dead) { url, _ -> answered.getValue(url) }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("the third source is the first servable one", "u2", pick.url)
        assertEquals("both failures belonged to A", setOf("a"), dead)
    }

    @Test
    fun `when every probe fails the top source is returned anyway`() {
        val candidates = listOf(stream("u0"), stream("u1"), stream("u2"))
        val addons = listOf("A", "A", "B")
        val dead = mutableSetOf<String>()
        val probe = SourcePlaybackProbe(dead) { _, _ -> false }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("the probe is a hint, not a verdict", "u0", pick.url)
        assertEquals(setOf("a", "b"), dead)
    }

    @Test
    fun `a timeout counts as a probe failure`() {
        val candidates = listOf(stream("u0"), stream("u1"))
        val addons = listOf("A", "B")
        val dead = mutableSetOf<String>()
        var calls = 0
        val probe = SourcePlaybackProbe(dead) { _, _ ->
            calls += 1
            throw SocketTimeoutException("probe timed out")
        }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("u0", pick.url)
        assertEquals("every candidate was still attempted", 2, calls)
        assertEquals(setOf("a", "b"), dead)
    }

    @Test
    fun `the probe stops at its cap`() {
        val candidates = (0 until 6).map { stream("u$it") }
        val addons = candidates.indices.map { "addon$it" }
        val dead = mutableSetOf<String>()
        val probed = mutableListOf<String>()
        val probe = SourcePlaybackProbe(dead) { url, _ ->
            probed += url
            false
        }

        val pick = runBlocking { probe.pickLiveSource(candidates, addons) { emptyMap() } }

        assertEquals("the top-ranked source loads when nothing answers", "u0", pick.url)
        assertEquals(
            "three probes at most",
            listOf("u0", "u1", "u2"),
            probed
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
        val probe = SourcePlaybackProbe(dead) { _, _ ->
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
        val probe = SourcePlaybackProbe(mutableSetOf()) { _, _ ->
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
        val seen = mutableListOf<Map<String, String>>()
        val probe = SourcePlaybackProbe(mutableSetOf()) { _, headers ->
            seen += headers
            true
        }

        runBlocking {
            probe.pickLiveSource(candidates, listOf("A", "B")) { stream ->
                mapOf("Referer" to stream.url.orEmpty())
            }
        }

        assertEquals(listOf(mapOf("Referer" to "u0")), seen)
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
        val probe = SourcePlaybackProbe(dead) { _, _ -> true }

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
        val probe = SourcePlaybackProbe(dead) { url, _ -> url == "A" }

        val pick = runBlocking { probe.pickLiveSource(candidates, ordered) { emptyMap() } }

        assertEquals("the pick was dead, so rank order wins", "A", pick.url)
        assertEquals("its addon is dead for the session", setOf("b"), dead)
    }
}
