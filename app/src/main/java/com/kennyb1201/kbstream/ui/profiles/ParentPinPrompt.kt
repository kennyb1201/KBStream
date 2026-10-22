package com.kennyb1201.kbstream.ui.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kennyb1201.kbstream.data.sync.KidsGate
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager

/**
 * The parent-PIN prompt's rules, in one place for both profile editors.
 *
 * The field report this comes from: making ONE change on the kids profile asked
 * for a PIN three separate times, and the PIN "never saved". The credential was
 * never the problem (it is written and verified in
 * [KidsTimeGuard.setParentPin] / [KidsTimeGuard.verifyParentPin]); the prompt
 * was, in three ways:
 *
 *  - it had no VERIFY path at all. Every gate demanded a New PIN + Confirm and
 *    wrote it, so a parent who typed the PIN they already had and left "new"
 *    blank got "The PIN must be 4 digits" and nothing proceeded - the dialog's
 *    own copy ("Enter the profile's PIN, or the parent PIN, to continue")
 *    promised a path the handler did not implement. The stored PIN could never
 *    simply be *used*, only forcibly rotated.
 *  - nothing remembered a successful verification, so three gated actions in one
 *    edit session were three prompts (and three forced rotations).
 *  - the write's `false` return was discarded: a failed write closed the dialog
 *    and ran the action as if it had saved.
 *
 * Both editors used to hand-roll that state machine. The mode decision, the
 * submit decision, and the session flag live here so the contract exists once
 * and cannot drift between the phone and the TV; the dialogs keep their own
 * visuals.
 *
 * What did NOT change: [KidsGate]'s gates (which acts need a PIN, the
 * create-instead-of-fail-open fallback, [KidsGate.firstPinNeedsParentPin]'s
 * refusal), the gates OUTSIDE the editor (leaving a kids profile, deleting one,
 * the time-lock veil) and the per-act behaviour of
 * [KidsTimeGuard.overrideForSession].
 */

/**
 * What the prompt is for, which is what decides its fields.
 *
 * - [VERIFY] - a gated action on a device that already holds a credential: one
 *   field, checked against the stored PIN, nothing written.
 * - [CREATE] - a gated action with no credential at all: the audit's
 *   "create instead of failing open" half. New + confirm, written, then the
 *   action runs.
 * - [ROTATE] - the Parent PIN row itself (SET/CHANGE). The only place a new PIN
 *   is ever demanded.
 */
internal enum class ParentPinPromptMode { VERIFY, CREATE, ROTATE }

/**
 * The fields one prompt shows. [askCurrentPin] is the credential being entered,
 * [askNewPin] the one being created - never both on a gated action.
 */
internal data class ParentPinPromptSpec(
    val mode: ParentPinPromptMode,
    val askCurrentPin: Boolean,
    val askNewPin: Boolean
)

/**
 * The prompt [gated] actions get when [credentialExists] is whether the target
 * (or the device) can answer one.
 */
internal fun parentPinPromptSpec(
    gated: Boolean,
    credentialExists: Boolean
): ParentPinPromptSpec = when {
    // The Parent PIN row: rotation lives here and only here. `askCurrentPin`
    // follows the credential, so a first SET does not ask for a PIN that does
    // not exist yet.
    !gated -> ParentPinPromptSpec(
        mode = ParentPinPromptMode.ROTATE,
        askCurrentPin = credentialExists,
        askNewPin = true
    )
    // A gate with something to verify against: use it. No rotation, no write.
    credentialExists -> ParentPinPromptSpec(
        mode = ParentPinPromptMode.VERIFY,
        askCurrentPin = true,
        askNewPin = false
    )
    else -> ParentPinPromptSpec(
        mode = ParentPinPromptMode.CREATE,
        askCurrentPin = false,
        askNewPin = true
    )
}

/**
 * What pressing the prompt's SAVE means.
 *
 * [Accepted] is the only outcome that may close the dialog and run the gated
 * action; [Rejected] is shown inline and the dialog stays - including for a
 * WRITE that failed, which used to close as if it had saved.
 */
internal sealed interface ParentPinSubmit {
    /** [wrotePin] tells the caller whether the Parent PIN row should re-read. */
    data class Accepted(val wrotePin: Boolean) : ParentPinSubmit
    data class Rejected(val message: String) : ParentPinSubmit
}

internal const val PARENT_PIN_INCORRECT = "PIN is incorrect"
internal const val PARENT_PIN_CURRENT_INCORRECT = "Current PIN is incorrect"
internal const val PARENT_PIN_INVALID = "The PIN must be 4 digits"
internal const val PARENT_PIN_MISMATCH = "PINs don't match"
internal const val PARENT_PIN_SAVE_FAILED = "Couldn't save the PIN — try again"

/**
 * Decides a submission; the one place the three modes are implemented.
 *
 * [writePin] is a seam for tests and defaults to the real store. Its `false`
 * return is honoured rather than discarded: nothing was stored, so [Rejected]
 * keeps the dialog open and the gated action does not run.
 *
 * A successful VERIFY writes nothing - it only asks whether [pin] answers for
 * [gateTarget] (the profile's own PIN first, then the parent PIN, the same order
 * the veil uses).
 */
internal fun submitParentPin(
    spec: ParentPinPromptSpec,
    gateTarget: ProfileManager.Profile?,
    currentPin: String,
    newPin: String,
    confirmPin: String,
    writePin: (String?) -> Boolean = { KidsTimeGuard.setParentPin(it) }
): ParentPinSubmit {
    if (spec.mode == ParentPinPromptMode.VERIFY) {
        return if (KidsGate.verifies(gateTarget, currentPin)) {
            ParentPinSubmit.Accepted(wrotePin = false)
        } else {
            ParentPinSubmit.Rejected(PARENT_PIN_INCORRECT)
        }
    }

    return when {
        // The order the dialog always used, so the message a parent sees for
        // the same mistake does not change.
        !ProfileManager.isValidPin(newPin) -> ParentPinSubmit.Rejected(PARENT_PIN_INVALID)
        newPin != confirmPin -> ParentPinSubmit.Rejected(PARENT_PIN_MISMATCH)
        spec.askCurrentPin && !KidsGate.verifies(gateTarget, currentPin) ->
            ParentPinSubmit.Rejected(PARENT_PIN_CURRENT_INCORRECT)
        !writePin(newPin) -> ParentPinSubmit.Rejected(PARENT_PIN_SAVE_FAILED)
        else -> ParentPinSubmit.Accepted(wrotePin = true)
    }
}

/**
 * One editor's parent-PIN state: whether this sitting has already been
 * verified, and the one gate every gated action goes through.
 *
 * Editor-scoped UI state by construction: it is remembered by the editor, dies
 * with it, and is never persisted or shared - leaving a kids profile, deleting
 * one, and the time-lock veil keep their own per-act prompts
 * ([KidsTimeGuard.overrideForSession] is unchanged).
 */
@Stable
internal class ParentPinSession {

    /** True once a PIN has been accepted in this editor session. */
    var verified by mutableStateOf(false)
        private set

    /**
     * Runs [action] now if this session is already verified, and otherwise
     * raises the prompt for it (and only for it).
     */
    fun gate(action: () -> Unit, prompt: (action: () -> Unit) -> Unit) {
        if (verified) action() else prompt(action)
    }

    /** A PIN was accepted - verify mode, create mode, or a rotation. */
    fun markVerified() {
        verified = true
    }
}

/** The session for one editor target; a different target is a different scene. */
@Composable
internal fun rememberParentPinSession(editId: String?): ParentPinSession =
    remember(editId) { ParentPinSession() }
