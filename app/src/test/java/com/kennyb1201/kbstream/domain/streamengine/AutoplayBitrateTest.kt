package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The learned bitrate ceiling and the pick rule that uses it.
 *
 * Reported (field, 2026-11-03): the top Penguplay link is a ~40 Mbps remux and
 * stalls on both engines, while another source of the same title plays fine -
 * the head was simply heavier than the line, and auto-play takes the head
 * without asking. [AutoplayBitrate] is the model that closes the SECOND stall:
 * a qualified stall at a row worth B teaches a device-local ceiling of B × 0.8,
 * a clean minute raises an existing ceiling, and the pick skips rows above it.
 *
 * What this file pins, in the spec's order:
 *
 *  - the estimate, from size and runtime only, unknown meaning UNGATED;
 *  - the gate: debrid picks untouched (by link, tag, or debrid-backed add-on),
 *    hash picks untouched, ceiling absent or estimate unknown untouched,
 *    diversion to a lighter row, diversion TO debrid, and the fallback to the
 *    original pick when every candidate is over;
 *  - the writes: a stall creates or lowers and never raises, a clean play raises
 *    an existing ceiling and never creates one, and a session with no played row
 *    (a resolved hash link, a debrid row) teaches nothing;
 *  - that the gate is a pick rule: it never mutates the caller's list, and with
 *    no ceiling it answers exactly what the caller's own pick answered.
 */
class AutoplayBitrateTest {

    private companion object {
        /** 40 GB over two hours: the heavy Penguplay head. */
        const val HEAVY_GB = 40.0

        /** 2 GB over the same two hours: an ordinary 1080p web release. */
        const val LIGHT_GB = 2.0

        const val RUNTIME_MIN = 120

        /** 40 GB / 2 h = 44.4 Mbps; 2 GB / 2 h = 2.2 Mbps. */
        const val HEAVY_MBPS = 44.4
        const val LIGHT_MBPS = 2.2

        /** A ceiling below both, so only the light row fits. */
        const val CEILING = 10.0f
    }

    // ── The estimate ────────────────────────────────────────────────────

    @Test
    fun `the estimate is the size over the runtime, in Mbps`() {
        assertEquals(HEAVY_MBPS, AutoplayBitrate.estimateMbps(HEAVY_GB, RUNTIME_MIN)!!, 0.1)
        assertEquals(LIGHT_MBPS, AutoplayBitrate.estimateMbps(LIGHT_GB, RUNTIME_MIN)!!, 0.1)
        // A 45-minute episode: 1 GB is 2.96 Mbps, the same file over two hours
        // is 1.1 - the runtime is half the reading, not a detail.
        assertEquals(2.96, AutoplayBitrate.estimateMbps(1.0, 45)!!, 0.01)
        assertEquals(1.11, AutoplayBitrate.estimateMbps(1.0, 120)!!, 0.01)
    }

    @Test
    fun `an unknown size or runtime is no estimate at all, not a small one`() {
        assertNull(AutoplayBitrate.estimateMbps(null, RUNTIME_MIN))
        assertNull(AutoplayBitrate.estimateMbps(HEAVY_GB, null))
        assertNull(AutoplayBitrate.estimateMbps(HEAVY_GB, 0))
        assertNull(AutoplayBitrate.estimateMbps(0.0, RUNTIME_MIN))
    }

    @Test
    fun `the estimate reads the row through the ranker's one size parser`() {
        // A size written in the text, no behaviorHints hint - the reading
        // StreamRanker.sizeGb already makes everywhere else.
        assertEquals(
            HEAVY_MBPS,
            AutoplayBitrate.estimateFor(row("heavy", sizeGb = HEAVY_GB), RUNTIME_MIN)!!,
            0.1
        )
        assertNull(AutoplayBitrate.estimateFor(row("unlabeled"), RUNTIME_MIN))
    }

    // ── The gate: what is never touched ────────────────────────────────

    @Test
    fun `a debrid-served pick is returned untouched, however heavy`() {
        val heavy = row(
            "heavy",
            url = "https://store-012.torbox.app/file.mkv",
            sizeGb = HEAVY_GB
        )
        val light = row("light", sizeGb = LIGHT_GB)
        assertSame(
            heavy,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, light),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
    }

    @Test
    fun `a debrid TAGGED pick is returned untouched too`() {
        val heavy = row("heavy", sizeGb = HEAVY_GB, extra = "[TB+] 4K")
        val light = row("light", sizeGb = LIGHT_GB)
        assertSame(
            heavy,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, light),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
    }

    @Test
    fun `a debrid-BACKED addon's pick is returned untouched, though its link looks plain`() {
        // AIOStreams: a bare HTTP row from an add-on that is debrid-first. The
        // add-on half of the exemption is the one a screen that forgot to pass
        // the set would silently lose.
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val light = row("light", sizeGb = LIGHT_GB)
        assertSame(
            heavy,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, light),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = setOf("aiostreams"),
                addonOf = { addon -> if (addon === heavy) "AIOStreams" else null },
                fullPick = firstRow
            )
        )
    }

    @Test
    fun `a hash row is returned untouched - the walk resolves it`() {
        val hashOnly = row("pack", url = null, sizeGb = HEAVY_GB, infoHash = "abcd1234")
        val light = row("light", sizeGb = LIGHT_GB)
        assertSame(
            hashOnly,
            AutoplayBitrate.gatedPick(
                candidates = listOf(hashOnly, light),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
    }

    @Test
    fun `no ceiling and no estimate both leave the pick exactly as today`() {
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val light = row("light", sizeGb = LIGHT_GB)
        assertSame(
            heavy,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, light),
                ceilingMbps = null,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
        // No runtime: the same rows have no estimate, so nothing is gated.
        assertSame(
            heavy,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, light),
                ceilingMbps = CEILING,
                runtimeMinutes = null,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
        // The unlabeled head is ungated by design: an unlabeled file is no
        // evidence of a heavy one, and gating it would punish the rows this
        // cannot judge at all.
        val unlabeled = row("unlabeled", url = "https://host.example/a.mkv")
        assertSame(
            unlabeled,
            AutoplayBitrate.gatedPick(
                candidates = listOf(unlabeled, light),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
    }

    // ── The gate: what it does move ────────────────────────────────────

    @Test
    fun `a heavy HTTP head is skipped for a lighter row, and rank order stands`() {
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val firstLight = row("light-1", sizeGb = LIGHT_GB)
        val secondLight = row("light-2", sizeGb = 1.0)
        assertSame(
            firstLight,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, firstLight, secondLight),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
    }

    @Test
    fun `it may move the pick TO debrid, and never away from it`() {
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val debrid = row("debrid", url = "https://cdn.torbox.app/x.mkv", sizeGb = HEAVY_GB)
        val light = row("light", sizeGb = LIGHT_GB)
        // The gated-out HTTP head hands the pick to the debrid row that ranked
        // under it - not to the light HTTP row below that.
        assertSame(
            debrid,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, debrid, light),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
    }

    @Test
    fun `when everything is over the ceiling the ORIGINAL pick is what starts`() {
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val heavier = row("heavier", sizeGb = 80.0)
        assertSame(
            heavy,
            AutoplayBitrate.gatedPick(
                candidates = listOf(heavy, heavier),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = firstRow
            )
        )
    }

    @Test
    fun `a pass the gate emptied falls back to the original, never to null`() {
        // The head fits no other rule once it is excluded (here: the caller's
        // pick has nothing left), but the full list DID have an answer a moment
        // ago - so the answer is that head, not the picker.
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        var passes = 0
        val pick = AutoplayBitrate.gatedPick(
            candidates = listOf(heavy),
            ceilingMbps = CEILING,
            runtimeMinutes = RUNTIME_MIN,
            debridAddons = emptySet(),
            addonOf = { null },
            fullPick = { rows -> passes++; rows.firstOrNull() }
        )
        assertSame(heavy, pick)
        assertEquals("one pass to divert, one to find nothing left", 2, passes)
    }

    @Test
    fun `null from the caller's own pick stays null - the ceiling emptied the set`() {
        assertNull(
            AutoplayBitrate.gatedPick(
                candidates = listOf(row("heavy", sizeGb = HEAVY_GB)),
                ceilingMbps = CEILING,
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet(),
                addonOf = { null },
                fullPick = { null }
            )
        )
    }

    @Test
    fun `the gate is a pick rule - the caller's list is never mutated`() {
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val light = row("light", sizeGb = LIGHT_GB)
        val candidates = listOf(heavy, light)
        AutoplayBitrate.gatedPick(
            candidates = candidates,
            ceilingMbps = CEILING,
            runtimeMinutes = RUNTIME_MIN,
            debridAddons = emptySet(),
            addonOf = { null },
            fullPick = firstRow
        )
        assertEquals(listOf(heavy, light), candidates)
        assertSame(heavy, candidates[0])
    }

    @Test
    fun `with no ceiling the gate asks the caller's pick once and answers it`() {
        var calls = 0
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val pick = AutoplayBitrate.gatedPick(
            candidates = listOf(heavy),
            ceilingMbps = null,
            runtimeMinutes = RUNTIME_MIN,
            debridAddons = emptySet(),
            addonOf = { null },
            fullPick = { rows -> calls++; rows.firstOrNull() }
        )
        assertSame(heavy, pick)
        assertEquals("one pass, the same work as before", 1, calls)
    }

    // ── The writes ─────────────────────────────────────────────────────

    @Test
    fun `a qualified stall creates the ceiling at 80 percent of the row`() {
        assertEquals(
            35.52f,
            AutoplayBitrate.afterStall(null, HEAVY_MBPS)!!,
            0.01f
        )
    }

    @Test
    fun `a qualified stall lowers a higher ceiling and never raises a lower one`() {
        assertEquals(
            35.52f,
            AutoplayBitrate.afterStall(50f, HEAVY_MBPS)!!,
            0.01f
        )
        assertNull(
            "a row that already fits proves nothing new about a tighter ceiling",
            AutoplayBitrate.afterStall(10f, HEAVY_MBPS)
        )
    }

    @Test
    fun `a stall on an unestimated row writes nothing`() {
        assertNull(AutoplayBitrate.afterStall(null, null))
        assertNull(AutoplayBitrate.afterStall(10f, null))
    }

    @Test
    fun `a clean play raises an existing ceiling to the played bitrate`() {
        assertEquals(
            44.4f,
            AutoplayBitrate.afterCleanPlay(10f, HEAVY_MBPS)!!,
            0.01f
        )
    }

    @Test
    fun `a clean play never creates a ceiling and never lowers one`() {
        assertNull(
            "a clean 25 Mbps play must not start gating rows that never stalled",
            AutoplayBitrate.afterCleanPlay(null, HEAVY_MBPS)
        )
        assertNull(
            "playing a light row clean says nothing about a higher ceiling",
            AutoplayBitrate.afterCleanPlay(50f, LIGHT_MBPS)
        )
    }

    // ── What a session may teach ───────────────────────────────────────

    @Test
    fun `a session teaches from the row it played, and only when that row is a plain HTTP row`() {
        val heavy = row("heavy", sizeGb = HEAVY_GB)
        val debrid = row("debrid", url = "https://cdn.torbox.app/x.mkv", sizeGb = HEAVY_GB)
        val aiostreams = row("aio", sizeGb = HEAVY_GB)
        val sources = listOf(heavy, debrid, aiostreams)

        assertEquals(
            HEAVY_MBPS,
            AutoplayBitrate.sessionEstimateMbps(
                playedUrl = heavy.url.orEmpty(),
                sources = sources,
                sourceAddons = listOf("Penguplay", "TorBox", "AIOStreams"),
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = setOf("aiostreams")
            )!!,
            0.1
        )
        assertNull(
            "a debrid link says nothing about what a file host will sustain",
            AutoplayBitrate.sessionEstimateMbps(
                playedUrl = debrid.url.orEmpty(),
                sources = sources,
                sourceAddons = listOf("Penguplay", "TorBox", "AIOStreams"),
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = setOf("aiostreams")
            )
        )
        assertNull(
            "nor does a debrid-backed add-on's row",
            AutoplayBitrate.sessionEstimateMbps(
                playedUrl = aiostreams.url.orEmpty(),
                sources = sources,
                sourceAddons = listOf("Penguplay", "TorBox", "AIOStreams"),
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = setOf("aiostreams")
            )
        )
        assertNull(
            "a link that matches no row is a resolved hash / trailer - not this row's throughput",
            AutoplayBitrate.sessionEstimateMbps(
                playedUrl = "https://cdn.torbox.app/resolved.mkv",
                sources = sources,
                sourceAddons = listOf("Penguplay", "TorBox", "AIOStreams"),
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = setOf("aiostreams")
            )
        )
        assertNull(
            "and a row with no size teaches nothing",
            AutoplayBitrate.sessionEstimateMbps(
                playedUrl = row("unlabeled").url.orEmpty(),
                sources = listOf(row("unlabeled")),
                sourceAddons = listOf(null),
                runtimeMinutes = RUNTIME_MIN,
                debridAddons = emptySet()
            )
        )
    }

    @Test
    fun `the row is matched by the URL playing, not by position`() {
        val first = row("first", sizeGb = LIGHT_GB)
        val second = row("second", sizeGb = HEAVY_GB)
        val estimate = AutoplayBitrate.sessionEstimateMbps(
            playedUrl = second.url.orEmpty(),
            sources = listOf(first, second),
            sourceAddons = listOf(null, null),
            runtimeMinutes = RUNTIME_MIN,
            debridAddons = emptySet()
        )
        assertEquals(HEAVY_MBPS, estimate!!, 0.1)
    }

    /** The caller's pick, isolated: the first of whatever is left. */
    private val firstRow: (List<Stream>) -> Stream? = { rows -> rows.firstOrNull() }

    private fun row(
        name: String,
        url: String? = "https://host.example/$name.mkv",
        sizeGb: Double? = null,
        infoHash: String? = null,
        extra: String? = null
    ): Stream = Stream(
        name = name,
        description = listOfNotNull(
            sizeGb?.let { "${it} GB" },
            extra
        ).joinToString(" ").ifBlank { null },
        url = url,
        infoHash = infoHash
    )

    @Test
    fun `a positive estimate below the ceiling is admitted, above it is not`() {
        assertTrue(AutoplayBitrate.admits(null, HEAVY_MBPS))
        assertTrue(AutoplayBitrate.admits(CEILING, null))
        assertTrue(AutoplayBitrate.admits(CEILING, LIGHT_MBPS))
        assertTrue("at the ceiling fits", AutoplayBitrate.admits(10f, 10.0))
        assertFalse(AutoplayBitrate.admits(CEILING, HEAVY_MBPS))
    }
}
