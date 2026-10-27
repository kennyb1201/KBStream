package com.kennyb1201.kbstream.ui.profiles

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profiles deep dive's fixes, pinned where a device would meet them.
 *
 * Two findings, both silent on a device and both about a list that is not what
 * it looks like:
 *
 *  - C1: a profiles blob this build could not read was reported as an empty
 *    list, and the first create/rename/PIN/kids write replaced every profile
 *    with that emptiness (and published it);
 *  - S1: a sync pull entered the first profile by `createdAt` when this device
 *    had none active - no pick, no PIN, no persisted id, no cache reset.
 *
 * This module carries no Compose harness, so the wiring is read from source -
 * the same approach the other deep-dive contract tests take. The behavioural
 * half lives in `ProfileStoreReadSafetyTest`, which drives the real store.
 */
class ProfileReadSafetyContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val MANAGER = "com/kennyb1201/kbstream/data/sync/ProfileManager.kt"
        const val PICKER_TV = "com/kennyb1201/kbstream/ui/profiles/ProfilePickerScreen.kt"
        const val PICKER_MOBILE =
            "com/kennyb1201/kbstream/ui/mobile/ProfilePickerScreenMobile.kt"
        const val EDITOR_TV = "com/kennyb1201/kbstream/ui/profiles/ProfileEditScreen.kt"
        const val EDITOR_MOBILE =
            "com/kennyb1201/kbstream/ui/mobile/ProfileEditScreenMobile.kt"
        const val ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
    }

    /** Everything between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    /** One function's body, from its header to the brace that closes it. */
    private fun functionBody(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("missing function: $header", start >= 0)
        var i = source.indexOf('(', start)
        assertTrue("missing parameter list: $header", i >= 0)
        var parens = 0
        while (i < source.length) {
            when (source[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) {
                        i++
                        break
                    }
                }
            }
            i++
        }
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    // ── C1: an unreadable blob is preserved, and says so ─────────────

    @Test
    fun `an unreadable profiles blob is preserved instead of reading as none`() {
        val manager = source(MANAGER)
        assertTrue(
            "the read distinguishes a parse failure from an empty store",
            manager.contains("private class ProfileLoad(") &&
                manager.contains("return ProfileLoad(emptyList(), unreadable = true)")
        )
        assertTrue(
            "and publishes it, because the picker has to draw something other " +
                "than an empty grid",
            manager.contains("val profilesUnreadable: StateFlow<Boolean>") &&
                manager.contains("_profilesUnreadable.value = true")
        )
        assertTrue(
            "a missing blob is NOT a failure: it is the ordinary first run",
            manager.contains("return ProfileLoad(emptyList())")
        )
        assertTrue(
            "and the launch still lands on the picker, which is where the message " +
                "is drawn - answering \"no profiles\" routed it to Home instead",
            manager.contains("return read.unreadable || read.profiles.isNotEmpty()")
        )
    }

    @Test
    fun `every profile write refuses while the blob cannot be read`() {
        val manager = source(MANAGER)
        assertTrue(
            "one gate, with the reason spelled out",
            manager.contains("private fun writableProfiles(context: Context): List<Profile>?") &&
                manager.contains("Refusing to change profiles")
        )
        listOf(
            "fun create(",
            "fun rename(",
            "fun setPin(",
            "fun setKidsMaxAge(",
            "fun setKidsOptions(",
            "fun setGuest(",
            "fun setCustomAvatar(",
            "fun setAvatarData("
        ).forEach { header ->
            assertTrue(
                "$header computes from the readable list, not from whatever the " +
                    "fallback returned",
                functionBody(manager, header).contains("writableProfiles(context)")
            )
        }

        // Delete is the one that also DESTROYS data: it has to refuse before it
        // closes handles or wipes a namespace, not after.
        val delete = functionBody(manager, "fun delete(")
        assertTrue(
            "delete refuses first",
            delete.indexOf("writableProfiles(context)") <
                delete.indexOf("closeScopedInstanceForDeletion")
        )

        // And the choke point holds even for a mutator that forgets: a save
        // computed from an unreadable blob cannot write.
        val save = functionBody(manager, "private fun saveProfiles(")
        assertTrue(
            "the save itself refuses, not only its callers",
            save.contains("if (servingUnreadableBlob && !cloudRepair)") &&
                save.contains("Refusing to save profiles")
        )
    }

    @Test
    fun `a pulled blob is the repair path, and a pull is not a pick`() {
        val manager = source(MANAGER)
        val apply = functionBody(manager, "fun applyProfilesPayload(")
        assertTrue(
            "a cloud payload is a complete list, so it may replace an unreadable " +
                "blob - that is how the state comes back",
            apply.contains("saveProfiles(context, merged, cloudRepair = true)")
        )
        assertFalse(
            "and it does not enter a profile the viewer did not pick: the old " +
                "branch activated whichever profile sorted first by createdAt, " +
                "with no PIN gate, no persisted id and no cache reset",
            apply.contains("_activeProfile.value = merged.firstOrNull()")
        )
    }

    @Test
    fun `both pickers say the profiles could not be read`() {
        listOf(PICKER_TV to "TV", PICKER_MOBILE to "phone").forEach { (path, label) ->
            val picker = source(path)
            assertTrue(
                "$label: the picker cannot present this as a fresh device",
                picker.contains("ProfileManager.profilesUnreadable") &&
                    picker.contains("Profiles couldn't be read on this device")
            )
            assertTrue(
                "$label: and says nothing was deleted, because nothing was - the " +
                    "one thing this state must not look like is a fresh device",
                picker.contains("deleted - sign in on another device")
            )
        }
    }

    @Test
    fun `the picker is what a launch opens on, whatever the device-local toggle says`() {
        val launch = between(
            source(ACTIVITY),
            "val showProfilePicker = remember(launchProfileHasPin) {",
            "val entrySaver ="
        )
        assertTrue(
            "an unreadable profile list forces the picker the way a PIN does: " +
                "it is the screen that can say what happened, and with the " +
                "switch off the viewer would land on Home instead",
            launch.contains("ProfileManager.profilesUnreadable.value") &&
                launch.indexOf("profilesUnreadable.value") <
                launch.indexOf("getShowProfilePicker(applicationContext)")
        )
    }

    @Test
    fun `both editors report a refused create instead of entering it`() {
        listOf(EDITOR_TV to "TV", EDITOR_MOBILE to "phone").forEach { (path, label) ->
            val editor = source(path)
            val create = between(
                editor,
                "ProfileManager.createAndMigrateLegacy(",
                "kidsTargetId = created.id"
            )
            assertTrue(
                "$label: a null create means \"the list could not be read\", not " +
                    "\"a profile exists\"",
                create.contains("if (created == null)") &&
                    create.contains("couldn't be read") &&
                    create.contains("return@save")
            )
        }
    }
}
