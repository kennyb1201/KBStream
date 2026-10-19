package com.kennyb1201.kbstream.ui.profiles

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parent PIN's wiring (sweep-4 M11), read from the sources.
 *
 * [com.kennyb1201.kbstream.data.sync.ParentPinTest] pins the credential itself
 * (hash, verify, the override accepting it). What is left is the part no unit
 * test can press: the veil deciding to draw its field at all, the editor
 * REFUSING a lock it cannot unlock, and the prompt that closes that gap by
 * writing the PIN and then running the save it interrupted.
 *
 * Both halves are silent failures in opposite directions: a veil that only asks
 * for the profile's PIN leaves a lock on a PIN-less profile with no exit at all
 * (the brick), and a gate that refuses the save without ever offering the prompt
 * makes a bedtime impossible to set.
 */
class ParentPinWiringContractTest {

    private val veil: String by lazy { flat(MAIN_ACTIVITY) }
    private val guard: String by lazy { flat(GUARD) }
    private val editor: String by lazy { flat(EDITOR) }

    // ── the veil ───────────────────────────────────────────────────────

    @Test
    fun `the veil offers a field whenever either credential can answer it`() {
        assertTrue(
            "the field's presence is the OR of the profile's PIN and the parent PIN",
            veil.contains("val hasPinField = profile != null && (") &&
                veil.contains(
                    "ProfileManager.hasPin(profile) || guard.hasParentPin()"
                )
        )
        assertTrue(
            "the field itself renders on that flag rather than on the profile's PIN alone",
            veil.contains("if (profile != null && hasPinField) {") &&
                veil.contains("text = \"Parent PIN to unlock\"") &&
                veil.contains("value = pin, ")
        )
        assertTrue(
            "and the copy a viewer reads is the same either way: which credential " +
                "answers is the parent's business, not something the veil announces",
            veil.contains("text = \"Parent PIN to unlock\"") &&
                veil.contains("text = \"A parent can remove the limit in profile settings.\"")
        )
        assertTrue(
            "the entry is verified through the guard's override, which is the " +
                "half that knows both PINs",
            veil.contains("if (guard.overrideForSession(profile, pin)) {")
        )
    }

    @Test
    fun `the override checks the profile's own PIN first and the parent PIN after`() {
        assertTrue(
            "both checks exist, in that order",
            guard.contains(
                "val byProfilePin = ProfileManager.hasPin(profile) && " +
                    "ProfileManager.verifyPin(profile, pin)"
            ) &&
                guard.contains("val byParentPin = verifyParentPin(pin)") &&
                guard.indexOf("val byProfilePin") < guard.indexOf("val byParentPin")
        )
        assertTrue(
            "and neither one answering is still a refusal: a profile with no " +
                "credential of any kind keeps the veil it always had",
            guard.contains("if (!byProfilePin && !byParentPin) return false")
        )
        assertTrue(
            "with the same session override the profile-PIN path already set",
            guard.contains("overriddenProfileId = profile.id") &&
                guard.contains("evaluate()")
        )
        assertTrue(
            "the parent PIN is stored hashed, with the function a profile PIN uses",
            guard.contains("clean.takeIf { it.isNotEmpty() }?.let { ProfileManager.hashPin(it) }")
        )
        assertFalse(
            "and never synced with the profile blob: it is this TV's credential",
            guard.contains("pushProfilesBlob")
        )
    }

    // ── the editor: the gate that kills the brick ───────────────────────

    @Test
    fun `a lock with no credential at all is refused, and the prompt is offered`() {
        assertTrue(
            "the gate is about the locks that need a credential",
            editor.contains(
                "val lockBeingSet = kidsModeOn && (kidsDailyLimitHours > 0 || kidsBedtimeOn)"
            )
        )
        assertTrue(
            "a PIN typed in this same save counts as the credential that will exist",
            editor.contains(
                "val anyPinField = newPinInput.isNotBlank() || " +
                    "confirmPinInput.isNotBlank() || currentPinInput.isNotBlank()"
            )
        )
        assertTrue(
            "the decision is the one shared rule, asked with all four facts",
            editor.contains("KidsGate.saveNeedsPin(") &&
                editor.contains("kidsChanged = kidsChanged, creatingProfile = editing == null, ") &&
                editor.contains("pinChanging = anyPinField, ") &&
                editor.contains("anyKidsProfileExists = profiles.any { it.kidsMaxAge != null }")
        )
        assertTrue(
            "and the prompt is raised INSTEAD of the save, with the lock path named",
            editor.contains(
                "gateWithParentPin( if (lockBeingSet) \"Required to set this limit\" " +
                    "else \"Required to change this profile\", editing ) { saveProfile() }"
            )
        )
        assertTrue(
            "the save cannot happen on that path: the gated branch returns before " +
                "the shared save lambda runs",
            editor.contains("return@KBButton } saveProfile() }")
        )
    }

    @Test
    fun `backing out of the prompt refuses the lock rather than applying it quietly`() {
        val close = editor
            .substringAfter("val closeParentPinPrompt: (Boolean) -> Unit")
            .substringBefore("val saveProfile:")
        assertTrue(
            "the refusal is a feedback message on the app's own channel",
            close.contains("text = \"A parent PIN is needed for that\"") &&
                close.contains("isError = true")
        )
        assertTrue(
            "and it only speaks for a GATED action: closing the Parent PIN row's " +
                "own dialog changes nothing",
            close.contains("if (refused && wasGated) {")
        )
        assertTrue(
            "the prompt's fields are cleared with it, so a cancelled prompt " +
                "cannot leave digits behind for the next one",
            close.contains("parentPinNew = \"\"") &&
                close.contains("parentPinConfirm = \"\"") &&
                close.contains("parentPinCurrent = \"\"")
        )
        assertTrue(
            "and the interrupted action is dropped rather than kept for later",
            close.contains("parentPinAfterVerify = null")
        )
    }

    @Test
    fun `the prompt writes the PIN and then runs the save it interrupted`() {
        val dialog = editor
            .substringAfter("if (parentPinPrompt) {")
            .substringBefore("private fun")
        assertTrue(
            "the same 4-digit rule as a profile PIN, stated once",
            dialog.contains("!ProfileManager.isValidPin(parentPinNew) ->") &&
                dialog.contains("\"The PIN must be 4 digits\"")
        )
        assertTrue(
            "a confirm step",
            dialog.contains("parentPinNew != parentPinConfirm -> \"PINs don't match\"")
        )
        assertTrue(
            "and the CURRENT credential is verified before a change, the contract " +
                "the profile-PIN fields follow",
            dialog.contains(
                "credentialExists && !KidsGate.verifies(gateTarget, parentPinCurrent) -> " +
                    "\"Current PIN is incorrect\""
            )
        )
        assertTrue(
            "nothing is written on a failure",
            dialog.contains("if (failure != null) { parentPinError = failure } else {")
        )
        assertTrue(
            "on success the PIN is written, then the interrupted action runs - which " +
                "is what makes the lock land on the same press",
            dialog.indexOf("KidsTimeGuard.setParentPin(parentPinNew)") <
                dialog.indexOf("after?.invoke()")
        )
        assertTrue(
            "and the row above re-reads it, because the guard's prefs file is " +
                "not observable state",
            editor.contains(
                "val parentPinSet = remember(parentPinRevision) { " +
                    "KidsTimeGuard.hasParentPin() }"
            )
        )
    }

    // ── the editor: the recovery path ──────────────────────────────────

    @Test
    fun `the parent PIN is managed beside the locks it unlocks`() {
        // Bounded by the row after it (the avatar tiles), because the source is
        // read with its comments stripped: a comment marker would slice nothing.
        val kidsSection = editor
            .substringAfter("if (kidsModeOn) {")
            .substringBefore("Row( horizontalArrangement = Arrangement.spacedBy(14.dp)")
        assertTrue(
            "the row lives in the kids options, next to the Bedtime lock it exists to lift",
            kidsSection.contains("text = \"Parent PIN\"") &&
                kidsSection.contains("title = \"Bedtime lock\"")
        )
        assertTrue(
            "it is one control whose label says whether there is one to change",
            kidsSection.contains("label = if (parentPinSet) \"CHANGE\" else \"SET\"")
        )
        assertTrue(
            "and it opens the same dialog the save gate raises, with the " +
                "gate flag cleared so a cancel here refuses nothing",
            kidsSection.contains("parentPinPromptGated = false") &&
                kidsSection.contains("parentPinPrompt = true")
        )
    }

    // ── source access ──────────────────────────────────────────────────

    private fun flat(relative: String): String =
        File(findSourceRoot(), relative).readText()
            .lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }
            .replace(Regex("\\s+"), " ")
            .trim()

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
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val GUARD = "com/kennyb1201/kbstream/data/sync/KidsTimeGuard.kt"
        const val EDITOR = "com/kennyb1201/kbstream/ui/profiles/ProfileEditScreen.kt"
    }
}
