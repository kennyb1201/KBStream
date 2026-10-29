package com.kennyb1201.kbstream.data.addon

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manifest refresh writes TWO stores: the addon list (whose catalogs carry the
 * global order) and the Home arrangement (which is keyed by catalog id, so a
 * churned rail needs a remap there). They now derive from one pairing, but two
 * prefs files still mean two writes - so the only thing keeping them together is
 * ORDER plus ABORT: the arrangement is rewritten first, and the addon list is
 * saved only if that succeeded.
 *
 * The old shape got both halves wrong. The remap sat behind a bare `runCatching`
 * (which swallows the refusal) and ran AFTER the addon list had already been
 * assigned, so an unreadable arrangement blob left the order saved and the
 * arrangement stale, with nothing anywhere saying so.
 *
 * Why a source-level pin instead of a harness test: [AddonManager] IS
 * constructible in a Robolectric test (see [AddonConfigReadSafetyTest]), but the
 * refusal cannot be reached from one. `remapAddonKeys` only refuses a write when
 * [KBHomeOrderPrefs.isBlobUnreadable] is true, and an unreadable blob also reads
 * as the EMPTY default arrangement - which contains no addon key for the remap
 * to rewrite, so `updated == current` and the call reports success without ever
 * reaching `writeBlob`. Inducing the refusal would take a corrupt blob that
 * failed to parse AND a default arrangement holding the removed key, which no
 * state can produce. The visible half - that a real rewrite reports success and
 * still does not claim a viewer edit - is pinned in
 * [com.kennyb1201.kbstream.data.kb.HomeBlobReadSafetyTest].
 */
class AddonMergeRemapContractTest {

    private val packageRoot get() = "com/kennyb1201/kbstream/"

    private val addonManager by lazy { readSource("${packageRoot}data/addon/AddonManager.kt") }
    private val homeOrder by lazy { readSource("${packageRoot}data/kb/KBHomeOrderPrefs.kt") }

    /**
     * `updateAddonFromManifest` only. The other mutators in this file also save,
     * and a whole-file search would happily match one of them.
     */
    private fun mergeBody(): String {
        val start = addonManager.indexOf("fun updateAddonFromManifest(")
        assertTrue("updateAddonFromManifest moved or was renamed", start >= 0)
        val end = addonManager.indexOf("fun removeCatalog(", start)
        assertTrue("the merge's end anchor (removeCatalog) moved", end > start)
        return addonManager.substring(start, end)
    }

    // ── the remap reports whether its write landed ─────────────────────────

    @Test
    fun `a refused arrangement write is reported, not swallowed`() {
        assertTrue(
            "the merge needs a result to check",
            homeOrder.contains("fun remapAddonKeys(context: Context, remap: Map<String, String>): Boolean")
        )
        assertTrue(
            "a refused writeBlob is the refusal",
            homeOrder.contains("if (!writeBlob(context, updated, stampAsEdit = false)) return false")
        )
        assertTrue(
            "and a rewrite that landed reports success",
            homeOrder.contains("publish(context)\n        return true")
        )
    }

    // ── order + abort in the merge ─────────────────────────────────────────

    @Test
    fun `the arrangement is rewritten before the addon list is assigned or saved`() {
        val body = mergeBody()
        val remapAt = body.indexOf(".remapAddonKeys(")
        val assignAt = body.indexOf("current[index] =")
        val saveAt = body.indexOf("saveInstalledAddons(current")

        assertTrue("the merge no longer remaps the arrangement", remapAt >= 0)
        assertTrue("the addon row is never assigned", assignAt >= 0)
        assertTrue("the addon list is never saved", saveAt >= 0)
        assertTrue(
            "the arrangement must be written before the order is stored",
            remapAt < assignAt
        )
        assertTrue(
            "and before the addon list is saved, or the two stores disagree",
            remapAt < saveAt
        )
    }

    @Test
    fun `a failed remap aborts the apply and says why`() {
        val body = mergeBody()
        val remapAt = body.indexOf(".remapAddonKeys(")
        val assignAt = body.indexOf("current[index] =")

        // Cancellation is not a failure: the file's own convention.
        assertTrue(
            "the remap must run under runCatchingCancellable",
            body.contains("runCatchingCancellable {")
        )
        assertFalse(
            "a bare runCatching would swallow the refusal again",
            body.contains("runCatching {")
        )

        // The result is inspected between the call and the assignment, and the
        // failure branch returns instead of falling through to the save.
        val gateAt = body.indexOf("getOrElse { false } != true")
        assertTrue(
            "the remap result must be checked before the addon list is touched",
            gateAt in remapAt until assignAt
        )
        val failureBranch = body.substring(gateAt, assignAt)
        assertTrue("a refused remap must abort the apply", failureBranch.contains("return"))

        // And it is loud: the addon, the remap size, the reason.
        assertTrue(
            "a silent abort is the bug this replaced",
            body.contains("Log.e(") &&
                body.contains("remap failed for ") &&
                body.contains("remap refused for ")
        )
        assertTrue(
            "the log line names the addon it refused",
            body.contains("remap failed for \${updatedAddon.id}") &&
                body.contains("remap refused for \${updatedAddon.id}")
        )
        assertTrue("and how many keys it would have moved", body.contains("remap.size"))
    }

    // ── the merge and the arrangement consume ONE pairing ─────────────────

    @Test
    fun `the order and the remap both come from the merge plan`() {
        val source = readSource("${packageRoot}data/addon/CatalogReplacementRules.kt")
        assertTrue(
            "the plan must still expose the pairing for the arrangement remap",
            source.contains("val replaced: List<Pair<ManifestCatalog, ManifestCatalog>>")
        )
        assertTrue(
            "and the order must be inherited through it",
            source.contains("replacementByKey[key]?.let { predecessor ->")
        )
        assertTrue(
            "a slot a pair inherits is withdrawn from the freed pool",
            source.contains("val inheritedSlots =")
        )
        assertTrue(
            "so an unpaired newcomer can never take an inherited slot",
            source.contains(".filter { slot -> slot !in inheritedSlots }")
        )
    }

    // ── source reading ────────────────────────────────────────────────────

    private fun readSource(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
