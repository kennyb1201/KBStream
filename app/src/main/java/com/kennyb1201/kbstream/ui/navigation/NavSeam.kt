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

    /** The one navigation entry point: every `screen = …` in the shell lands here. */
    fun navigateTo(value: Screen) {
        state.value = value
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Screen = state.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Screen) =
        navigateTo(value)
}
