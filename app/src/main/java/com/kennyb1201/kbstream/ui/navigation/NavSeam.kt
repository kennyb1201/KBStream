package com.kennyb1201.kbstream.ui.navigation

import androidx.compose.runtime.MutableState
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.typeName
import kotlin.reflect.KProperty

// ---------------------------------------------------------------------------
// The navigation seam (spec: navcompose, F0).
//
// Today every navigation in MainActivity is one assignment to a `Screen` value:
// `screen = Screen.Detail(...)`. This class is the single point those
// assignments pass through, so the strangler has ONE place - not ninety-eight -
// where "this destination renders through the NavHost" is decided:
//
//  - [migratedKinds] names the destination kinds that a NavHost destination now
//    owns. It is EMPTY in F0: nothing renders through Nav yet, so [navigateTo]
//    still does exactly what the assignment always did, and every existing
//    screen behaves identically.
//  - a kind joins that set in a later phase, one destination at a time, and
//    leaves it again on revert. Nothing else in a migration phase has to
//    change for that: the call sites never learn about it.
//
// The seam is a DELEGATE rather than ninety-eight rewritten call sites on
// purpose. `var screen by navSeam` routes every write through [navigateTo] and
// every read to the same state object the app has always held, so the change is
// one line at the declaration and provably no change anywhere else - the
// property still reads as a plain `Screen`, smart-casts included. That is what
// makes F0's acceptance ("a byte-identical walk") something a reviewer can
// check by reading one diff hunk.
//
// The state object is still the one `rememberSaveable(stateSaver = entrySaver)`
// produces, so restoration, the cold-launch gate and the depth-capped codec are
// untouched: this class never encodes anything itself.
// ---------------------------------------------------------------------------

internal class NavSeam(
    private val state: MutableState<Screen>,
    /** Destination kinds a NavHost destination owns. Empty until F1. */
    private val migratedKinds: Set<String> = emptySet()
) {

    /** Whether [value]'s kind is rendered by the NavHost rather than the old renderer. */
    fun isMigrated(value: Screen): Boolean = value.typeName() in migratedKinds

    /**
     * The play-on-arrival instruction for a Detail page that has not opened yet.
     *
     * Deliberately a plain field: NOT `rememberSaveable`, not part of the route,
     * not part of the restore codec. `Screen.Detail.autoPlay` and
     * `pendingTarget` are one-shots, and every persistence path they could take
     * re-fires them - a restore that auto-plays the episode the viewer was
     * watching reads as the binge restarting from there. The codec already
     * refuses to encode `pendingTarget` for exactly that reason; this field is
     * that refusal made structural, because a route argument would be saved by
     * the NavController whether the codec agrees or not.
     *
     * It is held here rather than in MainActivity because the seam is the one
     * place every navigation passes through: a navigation that carries an intent
     * sets it, any other navigation clears it, and the page takes it once.
     */
    private var detailArrival: Screen.Detail? = null

    /** The one navigation entry point: every `screen = …` in the shell lands here. */
    fun navigateTo(value: Screen) {
        // Only the navigation being made right now can carry an intent: anything
        // else that happens between this call and the page's first frame means
        // the viewer has moved on, and a stale instruction must not survive to
        // play something they did not ask for.
        detailArrival = (value as? Screen.Detail)
            ?.takeIf { it.autoPlay || it.pendingTarget != null }
        state.value = value
    }

    /**
     * The arrival instruction this page owns, taken exactly once.
     *
     * Keyed by (type, id): More Like This re-targets Detail without leaving it,
     * and an instruction meant for the page being left must not press Play on
     * the page that replaced it.
     */
    fun takeDetailArrival(type: String, id: String): Screen.Detail? {
        val held = detailArrival ?: return null
        detailArrival = null
        return held.takeIf { it.type == type && it.id == id }
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Screen = state.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Screen) =
        navigateTo(value)
}
