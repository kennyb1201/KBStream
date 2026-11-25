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
import androidx.navigation.toRoute
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.ui.addons.CatalogBuilderScreen
import com.kennyb1201.kbstream.ui.remote.RemoteScreen
import com.kennyb1201.kbstream.ui.settings.StatsScreen
import com.kennyb1201.kbstream.ui.simkl.SimklConnectScreen
import com.kennyb1201.kbstream.ui.trakt.TraktConnectScreen

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
// The graph opens ON the destination being entered: the shell hands it the route
// of the screen it is showing, so the host never has to navigate itself onto the
// right screen on its first frame (which would push it over a start destination
// for a frame, then replay an enter transition). A destination is added here
// when its kind joins `NavSeam.migratedKinds`; nothing here composes until then.
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
    /**
     * The route of the screen being entered: the shell captures it once, when
     * this host is composed for that screen, so a re-entry opens on the new
     * destination rather than resuming the previous stack.
     */
    startDestination: Any,
    /** The shell's animation length; 0 is reduced motion and stays instant. */
    transitionMs: Int,
    /** Every destination navigates through the seam, exactly like the arms did. */
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
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

        // Viewing stats, reached only from Settings and hardwired back to it,
        // exactly like the arm it stands in for.
        composable<StatsRoute> {
            StatsScreen(onBack = { onNavigate(Screen.Settings) })
        }

        // The catalog builder carries the pane that opened it, so its Back is
        // the route's own return argument - decoded and sanitized, never the
        // screen that happens to be underneath.
        composable<CatalogBuilderRoute> { entry ->
            val route = entry.toRoute<CatalogBuilderRoute>()
            CatalogBuilderScreen(
                onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) }
            )
        }

        // The two connect pages both carry their return destination, because the
        // same page is opened from Settings' tracker row and from onboarding's
        // setup card. Landing Back on the route's own argument is what makes the
        // header arrow and hardware Back agree - they did not before the
        // migration, when the arrow read returnTo and the press fell through to
        // the shell's else-arm and went Home. Each page now registers that same
        // landing from inside its own composition (see SimklConnectScreen and
        // TraktConnectScreen), which is what a migrated kind needs: the shell's
        // global handler stands down for it.
        composable<SimklRoute> { entry ->
            val route = entry.toRoute<SimklRoute>()
            SimklConnectScreen(
                onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) }
            )
        }

        composable<TraktRoute> { entry ->
            val route = entry.toRoute<TraktRoute>()
            TraktConnectScreen(
                onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) }
            )
        }
    }
}
