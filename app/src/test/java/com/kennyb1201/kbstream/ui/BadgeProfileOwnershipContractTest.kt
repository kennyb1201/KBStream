package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eye badge is a claim about ONE profile, and the state behind it is not
 * per profile all the way down.
 *
 * Reported: "paw patrol has an eye marker on profil 1 but ive never watched it
 * on this profile 1 only on the kids profile" - and no tracker held the title,
 * so the marker came from this device's own stores: the tracker feeds hold one
 * library per ACCOUNT, and the FIRST profile adopts the pre-profile history
 * store when the profile system arrives (ProfileManager.createAndMigrateLegacy),
 * so viewing that predates the profiles lands in profile 1 and paints its eye.
 *
 * The device already knows the missing half - which profile a title belongs to
 * (TitleProfileOwnership, recorded from every local card and from the account's
 * own history rows). The rules that consume it are pinned here: a title
 * positively owned by ANOTHER profile gets no eye, while an unattributed title
 * keeps its badge, because hiding that would break the cross-device badge a
 * profile earned elsewhere.
 */
class BadgeProfileOwnershipContractTest {

    @Test
    fun `home drops the eye for a title another profile owns`() {
        val body = functionBody(HOME_VM, "private fun refreshWatchedStatus(")

        assertTrue(
            "the marker pass must resolve the rail items another profile owns",
            body.contains("siblingOwnedRailItemIds(rails)")
        )
        assertTrue(
            "and the eye set - never the checkmark set - is what it filters",
            body.contains(
                "_partialWatchedKeys.value =\n" +
                    "                    (resolvedPartialWatchedKeys - resolvedWatchedKeys)\n" +
                    "                        .filterNot { key -> keyOwnedByAnotherProfile(key) }\n" +
                    "                        .toSet()"
            )
        )
        assertTrue(
            "the set it filters against is the one the refresh just resolved",
            body.contains("siblingOwnedMarkerIds = siblingOwnedIds")
        )

        // A live write from a sibling's session must not put the eye back
        // between refreshes - the collector applies the same filter.
        val collector = functionBody(HOME_VM, "WatchStateBus.updates.collect {")
        assertTrue(
            "the watch-state collector must apply the ownership filter too",
            collector.contains(".filterNot { key -> keyOwnedByAnotherProfile(key) }")
        )
        assertTrue(
            "a finished title is not this bug: the checkmark set is untouched",
            body.contains("_watchedKeys.value =\n                    resolvedWatchedKeys")
        )

        val helper = functionBody(HOME_VM, "private fun siblingOwnedRailItemIds(")
        assertTrue(
            "the helper must ask the one ownership rule the tracker cards use",
            helper.contains("titleOwnedByAnotherProfile(")
        )
        assertTrue(
            "and key what it returns by the id the marker sets carry",
            helper.contains(".map { meta -> meta.id.trim() }")
        )
    }

    @Test
    fun `the library enrichment drops the eye for a title another profile owns`() {
        val body = functionBody(LIBRARY_VM, "private fun enrich(")

        assertTrue(
            "the eye the enrichment resolves must be checked against the owner map",
            body.contains("titleOwnedByAnotherProfile(")
        )
        assertTrue(
            "and cleared, so the grid draws nothing for it",
            body.contains("partiallyWatched = null")
        )
    }

    @Test
    fun `the drop is positive-evidence only`() {
        // The rule itself is TitleProfileOwnership's: no owner recorded, no
        // live profile or no title key all answer false. Pinned at the source
        // so a future "hide anything not provably mine" edit cannot slip in
        // and strip the cross-device badge.
        val rule = source(OWNERSHIP)
        assertTrue(
            "an unnamed title owns nothing",
            rule.contains("titleProfileKey(type, title) ?: return false")
        )
        assertTrue(
            "an unattributed title is not somebody else's",
            rule.contains("val owner = ownerByTitleKey[key] ?: return false")
        )
        assertTrue(
            "and a missing live profile means no rule applies",
            rule.contains("activeProfileId?.takeIf { it.isNotBlank() } ?: return false")
        )
    }

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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val LIBRARY_VM = "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val OWNERSHIP = "com/kennyb1201/kbstream/data/history/TitleProfileOwnership.kt"
    }
}
