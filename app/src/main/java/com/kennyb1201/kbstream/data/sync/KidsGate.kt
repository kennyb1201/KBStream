package com.kennyb1201.kbstream.data.sync

/**
 * Who may change the kids sandbox (audit 2026-10-18, C2–C7, M2/M6/M7).
 *
 * The content plane was already filtered everywhere; the escapes were all in the
 * PROFILE-MANAGEMENT and lock planes, and every one of them came down to the
 * same missing rule: a kids profile's own shape (its ceiling, its options, its
 * time locks), the act of leaving it, and the act of deleting or creating a
 * profile were all unauthenticated. A kid holding the remote could switch Kids
 * Mode off, walk into an unrestricted profile, mint a fresh one, or delete the
 * locked profile - all without a PIN, because no rule ever asked who was allowed.
 *
 * This is that one rule, kept pure so it can be pinned without a device:
 *
 *  - **Leaving** a kids profile is an authenticated act when that profile asks
 *    for one ("PIN to leave profile") or is currently time-locked. Reading the
 *    lock state means a lock cannot be escaped by simply switching profile while
 *    the veil is up (M2), which is exactly what the veil's own trap was for.
 *  - **Editing** a kids configuration, creating a profile once the device holds
 *    a kids profile, and deleting one are authenticated acts too (C2, C5, C3).
 *  - The credential is the profile's own PIN first, then the app-level PARENT
 *    PIN (see [KidsTimeGuard.setParentPin]) - so daily profile switching stays
 *    PIN-free while every protective action has a door a kid cannot open.
 *
 * A device where NEITHER credential exists cannot verify anything. That state is
 * what the editor's save gate refuses to create for new configurations (see
 * ProfileEditScreen), and where it survives from an older build the prompts
 * offer the parent PIN's CREATION instead of failing open - the audit's
 * "block leaving or force PIN creation", resolved toward the half that cannot
 * brick a TV.
 */
internal object KidsGate {

    /** Whether [profile] is a kids profile at all. */
    fun isKids(profile: ProfileManager.Profile?): Boolean = profile?.kidsMaxAge != null

    /**
     * Whether [profile] is locked by a time limit RIGHT NOW.
     *
     * Read from the guard's published state rather than recomputed: that state is
     * what the veil itself is drawn from, so the two can never disagree.
     */
    fun timeLocked(profile: ProfileManager.Profile?): Boolean {
        if (profile == null) return false
        val state = KidsTimeGuard.state.value
        return state.locked && state.profileId == profile.id
    }

    /** Whether [profile] has a PIN of its own. */
    fun hasOwnPin(profile: ProfileManager.Profile?): Boolean =
        profile != null && ProfileManager.hasPin(profile)

    /**
     * Whether ANY credential exists that could answer a prompt about [profile]:
     * the profile's own PIN, or the device's parent PIN.
     */
    fun hasCredential(profile: ProfileManager.Profile?): Boolean =
        hasOwnPin(profile) || KidsTimeGuard.hasParentPin()

    /**
     * Leaving [active] - switching to another profile, opening Manage, creating
     * one - has to be verified first.
     *
     * Two triggers, and deliberately no third: a kids profile that ASKS for a PIN
     * to leave, and one that is locked right now. The default (no flag, not
     * locked) is unchanged, because the parent-PIN spec froze this gate and
     * because daily switching between a kid's own profiles is not an escape.
     */
    fun leavingNeedsPin(active: ProfileManager.Profile?): Boolean =
        isKids(active) && (active!!.kidsRequirePinToExit || timeLocked(active))

    /**
     * Whether an editor SAVE has to be verified.
     *
     * [kidsChanged] is any delta to a kids profile's ceiling, options or time
     * locks (turning Kids Mode on or off included); [creatingProfile] is the
     * create form, which is only gated once the device already holds a kids
     * profile - a first-run profile must not demand a credential that does not
     * exist yet; [pinChanging] is setting or clearing a profile's PIN.
     */
    fun saveNeedsPin(
        kidsChanged: Boolean,
        creatingProfile: Boolean,
        pinChanging: Boolean,
        anyKidsProfileExists: Boolean
    ): Boolean =
        kidsChanged || pinChanging || (creatingProfile && anyKidsProfileExists)

    /**
     * Whether DELETE has to be verified: the target carries a PIN of its own, the
     * target is a kids profile, or a kids profile is what the kid is holding.
     */
    fun deleteNeedsPin(
        target: ProfileManager.Profile?,
        active: ProfileManager.Profile?
    ): Boolean =
        target != null && (ProfileManager.hasPin(target) || isKids(target) || isKids(active))

    /**
     * Whether a save that writes a FIRST PIN to a PIN-less profile is one the
     * parent PIN has to authorise first (M6).
     *
     * Not the same as the other gates: a first PIN is how a kid locks the parent
     * OUT, so there is no create-the-parent-PIN fallback here - with no parent
     * PIN the save is refused and the parent sets one deliberately (the Parent
     * PIN row in the same editor), which is a two-step the kid cannot shortcut.
     */
    fun firstPinNeedsParentPin(target: ProfileManager.Profile?): Boolean =
        target != null && !hasOwnPin(target) && !KidsTimeGuard.hasParentPin()

    /** Whether a device-wide change (leaving, deleting) may offer to CREATE the parent PIN. */
    fun parentPinMayBeCreated(): Boolean = !KidsTimeGuard.hasParentPin()

    /**
     * Whether [pin] answers for [profile]: its own PIN first, then the parent PIN.
     * The same order the veil uses (see KidsTimeGuard.overrideForSession).
     */
    fun verifies(profile: ProfileManager.Profile?, pin: String): Boolean {
        val byProfilePin = hasOwnPin(profile) && ProfileManager.verifyPin(profile!!, pin)
        return byProfilePin || KidsTimeGuard.verifyParentPin(pin)
    }
}
