package com.kennyb1201.kbstream.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.kennyb1201.kbstream.Screen
import kotlin.reflect.KProperty

// ---------------------------------------------------------------------------
// The seam between the shell's `Screen` vocabulary and the one host that owns
// the destination (spec: navcompose, F5 - the teardown).
//
// The graph is the OWNER now. A screen is not a value the shell keeps; it is a
// destination the controller is showing, and this class is the last place that
// translation is written down:
//
//  - a WRITE (`screen = …`) navigates: it translates the Screen to its route
//    and replaces the stack with that one destination (the host holds one
//    entry - see the note on `navigateTo`). It never stores the Screen;
//  - a READ (`screen`) is a MIRROR of the graph, kept so the shell-level
//    readers (the bottom bar, the onboarding stand-in, the exit gate, the
//    splash) do not each collect the back stack. [follow] is what keeps it
//    honest, called once per entry change;
//  - the PLAYER is not a destination and never becomes one (F0): it owns its
//    own Activity launch, so it rides in [playerLaunch] as an overlay above
//    the host, and every write of a real screen dismisses it.
//
// Two things deliberately survive from before the teardown, because the spec
// makes them data rather than navigation:
//
//  - the DETAIL ARRIVAL slot. A Continue Watching tap or a billboard Play sets
//    a one-shot on the Detail it opens; the route must never carry it (a
//    persisted argument would re-fire the play on a process-death restore), so
//    the slot holds it between the navigation and the page's first frame;
//  - the launch screen. The graph opens on it, and it is what a read answers
//    before the controller has attached its graph.
// ---------------------------------------------------------------------------

internal class NavSeam(
    private val navController: NavHostController,
    /** The destination the graph opens on; a read before the graph exists. */
    private val launch: Screen
) {

    /** The shell's mirror of the destination the graph is showing. */
    private val mirror = mutableStateOf(launch)

    /**
     * The player overlay, or null. The player is a launch spec, not a
     * destination: it is composed above the host by `MainActivity`, and the
     * screen that opened it keeps its place underneath.
     */
    var playerLaunch by mutableStateOf<Screen.Player?>(null)
        private set

    /**
     * A navigation requested before the host attached its graph.
     *
     * The shell's own effects (deep links, the remote's "play on TV" bus) are
     * registered above the host and run before it on the first frame, so a write
     * from one of them has no graph to navigate yet. It waits here, and
     * [follow] flushes it as soon as the graph exists.
     */
    private var queued: Screen? = null

    /**
     * The play-on-arrival instruction for a Detail page that has not opened yet.
     *
     * Deliberately a plain field: NOT `rememberSaveable`, not part of the route,
     * not part of the restore codec. `Screen.Detail.autoPlay` and `pendingTarget`
     * are one-shots, and every persistence path they could take re-fires them - a
     * restore that auto-plays the episode the viewer was watching reads as the
     * binge restarting from there.
     *
     * It is held here rather than in MainActivity because the seam is the one
     * place every navigation passes through: a navigation that carries an intent
     * sets it, any other navigation clears it, and the page takes it once.
     */
    private var detailArrival: Screen.Detail? = null

    /** The screen the shell is showing: the player overlay, else the graph. */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Screen =
        playerLaunch ?: mirror.value

    /** The one navigation entry point: every `screen = …` in the shell lands here. */
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Screen) =
        navigateTo(value)

    fun navigateTo(value: Screen) {
        // Only the navigation being made right now can carry an intent: anything
        // else that happens between this call and the page's first frame means
        // the viewer has moved on, and a stale instruction must not survive to
        // play something they did not ask for.
        detailArrival = (value as? Screen.Detail)
            ?.takeIf { it.autoPlay || it.pendingTarget != null }

        // The player is an overlay, so it is not translated: it is published and
        // the destination underneath stays exactly where it was.
        if (value is Screen.Player) {
            playerLaunch = value
            return
        }
        // Any real destination dismisses the overlay - the player's own exit
        // writes the place it returns to, so that write is also the dismissal.
        playerLaunch = null

        val target = NavRoutes.of(value) ?: return
        val current = navController.currentBackStackEntry
        if (current == null) {
            // No graph yet: the host attaches it when it composes, and `follow`
            // sends this the moment it does.
            queued = value
            return
        }
        // A navigation to the destination already showing is not a new entry.
        // The compare is on the whole Screen, so a Detail re-target (a new id, a
        // new returnTo, a fresh one-shot) still navigates - only a press that
        // asks for what is already on screen is dropped, which is also what
        // keeps a screen that reflects its own state from navigating in a loop.
        if (NavRoutes.screenOfEntry(current) == value) return
        navigateNow(target)
    }

    /**
     * The stack is replaced, never deepened: one entry is all the shell holds,
     * which is what keeps the controller's own pop callback out of Back's way
     * (see NavShell's note) and what makes every landing the shell's.
     */
    private fun navigateNow(target: Any) {
        navController.navigate(target) {
            launchSingleTop = true
            popUpTo(navController.graph.id) { inclusive = true }
        }
    }

    /**
     * Follow the graph. Called whenever the current entry changes - including
     * the first one, which is also when a write that arrived before the graph
     * gets sent.
     *
     * A missing entry is not a destination: the host draws nothing without one,
     * so it is ignored here rather than mirrored as a screen.
     */
    fun follow(entry: NavBackStackEntry?) {
        val waiting = queued
        if (waiting != null && entry != null) {
            queued = null
            navigateTo(waiting)
            return
        }
        if (entry == null) return
        val shown = NavRoutes.screenOfEntry(entry)
        if (mirror.value != shown) mirror.value = shown
    }

    /**
     * Vestigial, and deliberately false: every kind renders through the host, so
     * no arm may be blanked - the host renders FROM the arm table.
     *
     * The per-arm `if (isMigrated(current)) Unit else { … }` guards stay exactly
     * where they are as the record of where a second renderer would have to sit;
     * nothing may make this true while the host owns every destination, because
     * a true answer blanks the arm the host is about to draw.
     */
    fun isMigrated(value: Screen): Boolean = false

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
}
