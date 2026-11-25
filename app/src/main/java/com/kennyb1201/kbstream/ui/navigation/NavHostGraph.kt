package com.kennyb1201.kbstream.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.ui.remote.RemoteScreen

// ---------------------------------------------------------------------------
// The NavHost a migrated destination renders through (spec: navcompose, F0).
//
// It is hosted by MainActivity beside the AnimatedContent it is replacing, and
// the shell's `screen` value stays the single source of truth: the shell
// navigates this host to follow `screen` (see the effect beside
// `rememberNavController` there), not the other way round. That keeps Back, the
// restoration codec, the cold-launch gate and the exit-confirm dialog working
// exactly as they do today, phase by phase.
//
// The graph starts with the first F1 destination and grows one at a time. Until
// a kind is listed in `NavSeam.migratedKinds` nothing here composes, so F0 ships
// a host with zero destinations migrated.
//
// Transitions: a stack push is always "deeper" and a pop always "back", which is
// the shell's own two rules for everything that is not a lateral tab move - the
// entering screen drifts in from the trailing edge and fades, and on the way
// back only the outgoing one moves, so the two never look like they fight for
// the same pixels. `launchSingleTop` keeps a re-target from stacking the same
// destination twice; the no-replay-on-retarget rule of F4 is checked per
// destination in that phase's walk, not assumed here.
// ---------------------------------------------------------------------------

@Composable
internal fun NavHostGraph(
    navController: NavHostController,
    /** The shell's animation length; 0 is reduced motion and stays instant. */
    transitionMs: Int,
    /** Every destination navigates through the seam, exactly like the arms did. */
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = RemoteRoute,
        modifier = modifier,
        enterTransition = {
            slideInHorizontally(tween(transitionMs)) { width -> width / 6 } +
                fadeIn(tween(transitionMs))
        },
        exitTransition = { fadeOut(tween(transitionMs)) },
        popEnterTransition = { fadeIn(tween(transitionMs)) },
        popExitTransition = {
            slideOutHorizontally(tween(transitionMs)) { width -> width / 6 } +
                fadeOut(tween(transitionMs))
        }
    ) {
        // F1: the remote control is the cheapest proof of the seam - one entry
        // point (Settings), one argument, and Back is the screen it came from.
        composable<RemoteRoute> {
            RemoteScreen(onBack = { onNavigate(Screen.Settings) })
        }
    }
}
