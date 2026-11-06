package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trailer's FIRST mount is a preferred client's source, not the highest
 * format score.
 *
 * Reported (field, 2026-11-05, Moto G, 0.9.167): `trailer.death: video=
 * aHrMFtt40_Q client=android_vr kind=progressive playerToken=true pot=true
 * at=15058/138458 retry=1/first resume=client android ok`. The first mount was
 * an android_vr progressive, fully tokened, and it died at 15.1 s; the android
 * replacement resumed from the stop point and played to the end. android_vr is
 * LAST in the client chain (its LOGIN_REQUIRED history), but both the
 * progressive and the HLS picks ranked the whole pool by format score first and
 * consulted client priority only as the last tiebreak - so a higher-scored
 * android_vr source won the mount that android's own source would have played
 * through.
 *
 * The rule: a preferred client's source first whenever it has one, the rest of
 * the pool when it does not. android_vr is demoted to the fallback its chain
 * position already says it is - not banned; when android produced no usable
 * progressive or manifest it still mounts, and a death still excludes the dead
 * client and re-picks from what is left.
 *
 * The picks are driven directly here (they are pure); what a resolve wires to
 * them is read out of the source, like the other `*ContractTest`s.
 */
class TrailerPreferredClientFirstContractTest {

    private val extractor = source(INNER_TUBE)
    private val picks = source(PICKS)

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    // ── the progressive pick: preferred client first (F1) ──────────────────

    @Test
    fun `a preferred client's progressive wins whatever another client's score`() {
        val pick = pickBest(
            listOf(
                progressive(client = "android", score = 100.0, url = "android-url"),
                progressive(client = "android_vr", score = 1.0e13, url = "vr-url")
            ),
            PREFERRED
        )

        assertEquals(
            "the 0.9.167 capture's first mount: android_vr outscored android and " +
                "died at 15.1 s while android's progressive played the trailer whole",
            "android-url",
            pick?.url
        )
    }

    @Test
    fun `with no preferred progressive the pool is every eligible client`() {
        val pick = pickBest(
            listOf(
                progressive(client = "tv", score = 50.0, url = "tv-url"),
                progressive(client = "android_vr", score = 1.0e13, url = "vr-url")
            ),
            PREFERRED
        )

        assertEquals(
            "demoted, not banned: android has no progressive here, so android_vr " +
                "still mounts as the best source available",
            "vr-url",
            pick?.url
        )
    }

    @Test
    fun `an excluded client never wins by being the preferred one`() {
        val pick = pickBest(
            listOf(
                progressive(client = "android", score = 1.0e13, url = "android-url"),
                progressive(client = "tv", score = 50.0, url = "tv-url")
            ),
            PREFERRED,
            excludeClients = setOf("android")
        )

        assertEquals(
            "the death plan's exclusion is applied before the preference, or a " +
                "resume would re-pick the client whose URLs just refused every offset",
            "tv-url",
            pick?.url
        )
    }

    @Test
    fun `the progressive pick is the shared preferred-client pick`() {
        assertTrue(
            "one ranking function decides what plays: the progressive pool goes " +
                "through it, not through a sort of its own",
            extractor.contains(
                "val bestProgressive = pickBest(" +
                    "allowedProgressive, PREFERRED_SEPARATE_CLIENT, excludeClients)"
            )
        )
        assertTrue(
            "and score ranks only WITHIN the chosen pool",
            picks.contains("val preferred = eligible.filter { it.client == preferredClient }") &&
                picks.contains(
                    "val pool = if (preferred.isNotEmpty()) preferred else eligible"
                )
        )
        assertTrue(
            "the adaptive pick is the same shape it always was - untouched",
            extractor.contains(
                "pickBest(allowedVideo, PREFERRED_SEPARATE_CLIENT, excludeClients)"
            )
        )
    }

    // ── the HLS pick: the same check (F2) ─────────────────────────────────

    @Test
    fun `the preferred client's manifest wins over a taller variant from another client`() {
        val pick = pickHlsManifest(
            listOf(
                manifest(
                    client = "android_vr",
                    url = "vr-manifest",
                    ManifestVariant("vr-1080", height = 1080, bandwidth = 5_000_000)
                ),
                manifest(
                    client = "android",
                    url = "android-manifest",
                    ManifestVariant("android-720", height = 720, bandwidth = 2_000_000)
                )
            ),
            PREFERRED
        )

        assertEquals(
            "height ranked across clients is how an android_vr first mount beat " +
                "an android one that played through",
            "android-manifest",
            pick?.third
        )
    }

    @Test
    fun `with no preferred manifest the highest variant across the pool wins again`() {
        val pick = pickHlsManifest(
            listOf(
                manifest(
                    client = "tv",
                    url = "tv-manifest",
                    ManifestVariant("tv-720", height = 720, bandwidth = 9_000_000)
                ),
                manifest(
                    client = "android_vr",
                    url = "vr-manifest",
                    ManifestVariant("vr-1080", height = 1080, bandwidth = 1_000_000)
                )
            ),
            PREFERRED
        )

        assertEquals(
            "android has no manifest here, so height ranks the whole pool as before",
            "vr-manifest",
            pick?.third
        )
    }

    @Test
    fun `the TV ceiling still rules across clients`() {
        val pick = pickHlsManifest(
            listOf(
                manifest(
                    client = "android",
                    url = "android-manifest",
                    ManifestVariant("android-2160", height = 2160, bandwidth = 9_000_000)
                ),
                manifest(
                    client = "android_vr",
                    url = "vr-manifest",
                    ManifestVariant("vr-720", height = 720, bandwidth = 1_000_000)
                )
            ),
            PREFERRED
        )

        assertEquals(
            "an above-ceiling rendition can be the reason a TV mount fails, so " +
                "the ceiling outranks the client preference instead of following it",
            "vr-manifest",
            pick?.third
        )
    }

    @Test
    fun `every rendition above the ceiling still plays the lowest`() {
        val pick = pickHlsManifest(
            listOf(
                manifest(
                    client = "android",
                    url = "android-manifest",
                    ManifestVariant("android-2160", height = 2160, bandwidth = 9_000_000)
                ),
                manifest(
                    client = "android_vr",
                    url = "vr-manifest",
                    ManifestVariant("vr-1440", height = 1440, bandwidth = 4_000_000)
                )
            ),
            PREFERRED
        )

        assertEquals(
            "refusing to play is never the answer: the last resort is the lowest " +
                "rendition there is, whichever client signed it",
            "vr-manifest",
            pick?.third
        )
    }

    @Test
    fun `a manifest that would not parse is not a usable source`() {
        assertNull(
            "nothing parsed is nothing to mount, not a guess",
            pickHlsManifest(emptyList(), PREFERRED)
        )
        assertTrue(
            "the parse is what decides usability, so its failures are dropped",
            extractor.contains("}.filter { it.second.isNotEmpty() }")
        )
    }

    @Test
    fun `the HLS pick is handed the preferred client and keeps the ceiling`() {
        assertTrue(
            "the manifest pick cannot grow a client term without being told one",
            extractor.contains("val bestHls = pickBestHls(allowedHls, PREFERRED_SEPARATE_CLIENT)")
        )
        assertTrue(
            "the pool order is the one the unit tests drive",
            extractor.contains("return pickHlsManifest(parsed, preferredClient)")
        )
        assertTrue(
            "the ceiling is unchanged",
            picks.contains("internal const val MAX_TRAILER_HEIGHT = 1080")
        )
    }

    // ── untouched: the chain, the death plan, the token gate ──────────────

    @Test
    fun `the client chain still ends with android_vr`() {
        assertTrue(
            "android_vr keeps its position for its LOGIN_REQUIRED history; this " +
                "spec demotes it in the picks, it does not move it in the chain",
            extractor.contains("YouTubeClients.all.map { it.key }")
        )
    }

    @Test
    fun `a death still excludes the dead client and re-picks from what is left`() {
        val player = source(NATIVE_PLAYER)

        assertTrue(
            "the recovery is unchanged: the dead client is excluded, android_vr " +
                "included, and the replacement continues from the stop point",
            player.contains(
                ".resolvePlayableUrl(videoId, excludeClients = setOfNotNull(deadClient))"
            )
        )
        assertTrue(
            "and a tokenless progressive still carries the gate's flag",
            flat(extractor).contains(
                "bestProgressive.client, KIND_PROGRESSIVE, tokenlessByColdMint"
            )
        )
    }

    // ── the AUDIO half of the same rule ───────────────────────────────────

    @Test
    fun `adaptive audio prefers the client whose UA plays it, then the preferred client`() {
        val order = audioProbeOrder(
            listOf(
                progressive(client = "ios", score = 1.0e13, url = "ios-audio"),
                progressive(client = "android", score = 10.0, url = "android-audio"),
                progressive(client = "tv", score = 50.0, url = "tv-audio")
            ),
            videoClient = "tv",
            preferredClient = PREFERRED
        )

        assertEquals(
            "the video's own client first, then the preferred one, then score - a " +
                "higher score from a client that would play under the wrong UA " +
                "cannot come first",
            listOf("tv-audio", "android-audio", "ios-audio"),
            order.map { it.url }
        )
    }

    @Test
    fun `with no video client the preferred client still leads`() {
        val order = audioProbeOrder(
            listOf(
                progressive(client = "ios", score = 1.0e13, url = "ios-audio"),
                progressive(client = "android", score = 10.0, url = "android-audio")
            ),
            videoClient = null,
            preferredClient = PREFERRED
        )

        assertEquals(listOf("android-audio", "ios-audio"), order.map { it.url })
    }

    @Test
    fun `the adaptive audio pool and its probe use the UA the source plays with`() {
        val body = flat(extractor)
        assertTrue(
            "the audio candidates come from the shared order",
            body.contains("val audioCandidates = audioProbeOrder(") &&
                body.contains("videoClient = bestVideo.client,") &&
                body.contains("preferredClient = PREFERRED_SEPARATE_CLIENT")
        )
        assertTrue(
            "and every audio probe presents the Adaptive source's own UA, not " +
                "the candidate's",
            body.contains("val probed = resolveReachableUrl(candidate.url, playUa)") &&
                !body.contains("userAgentFor(candidate.client)")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun progressive(
        client: String,
        score: Double,
        url: String
    ): StreamCandidate = StreamCandidate(
        client = client,
        priority = if (client == "android_vr") 4 else 0,
        url = url,
        score = score,
        hasN = false,
        height = 720,
        ext = "mp4"
    )

    private fun manifest(
        client: String,
        url: String,
        vararg variants: ManifestVariant
    ): Pair<Triple<String, Int, String>, List<ManifestVariant>> =
        Triple(client, 0, url) to variants.toList()

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private companion object {
        // The extractor's PREFERRED_SEPARATE_CLIENT, spelled out so these unit
        // tests compile against the pure picks alone (YouTubeClientsTest pins
        // that the constant is this value).
        const val PREFERRED = "android"
        const val YOUTUBE = "com/kennyb1201/kbstream/data/youtube/"
        const val INNER_TUBE = "${YOUTUBE}InnerTubeExtractor.kt"
        const val PICKS = "${YOUTUBE}TrailerSourcePicks.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
