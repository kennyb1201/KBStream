package com.kennyb1201.kbstream.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import com.kennyb1201.kbstream.ui.addons.AddonsScreen
import com.kennyb1201.kbstream.ui.addons.AddonsViewModel
import com.kennyb1201.kbstream.ui.addons.CatalogBuilderScreen
import com.kennyb1201.kbstream.ui.bingecat.BingeCatConnectScreen
import com.kennyb1201.kbstream.ui.bingecat.BingeCatPublicListsScreen
import com.kennyb1201.kbstream.ui.bingecat.BingeCatPublicListsViewModel
import com.kennyb1201.kbstream.ui.bingecat.BingeCatViewModel
import com.kennyb1201.kbstream.ui.publiclists.PublicListsScreen
import com.kennyb1201.kbstream.ui.publiclists.PublicListsViewModel
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
    /**
     * Activity-scoped ViewModels for the leaves that own state across the seam,
     * resolved and handed in by the shell.
     *
     * Inside a NavHost the ViewModel store owner is the back-stack entry, so a
     * screen's default `hiltViewModel()` is scoped to the visit. Three of these
     * pages are not: the two public-list browsers must not lose the level the
     * viewer left (or restart an import) when Detail opens over them, and the
     * add-on page must not refetch what it already has on every entry. The arms
     * they replace resolved them in the activity's composition, which is what
     * these parameters preserve - only the resolution point moved, not the life.
     */
    addonsViewModel: AddonsViewModel? = null,
    bingeCatViewModel: BingeCatViewModel? = null,
    bingeCatPublicListsViewModel: BingeCatPublicListsViewModel? = null,
    publicListsViewModel: PublicListsViewModel? = null,
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

        // The add-on page has three real entry points - Settings, onboarding's
        // setup card and Home's rail manager - so its Back is the route's own
        // argument, decoded and sanitized exactly as the arm's was. The page now
        // registers that landing itself for BOTH form factors: the touch page
        // had no handler of its own and leaned on the shell's, which stands down
        // for a migrated kind (see MigratedBackCoverageContractTest).
        composable<AddonsRoute> { entry ->
            val route = entry.toRoute<AddonsRoute>()
            AddonsScreen(
                onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) },
                viewModel = checkNotNull(addonsViewModel)
            )
        }

        // Settings' two public-list rows land here, [vendor] naming which one.
        // The page's own handler walks its surface's levels first and leaves for
        // the opener only at the root, so it is given the route's landing rather
        // than a pop - the same split the arm had.
        composable<PublicListsRoute> { entry ->
            val route = entry.toRoute<PublicListsRoute>()
            // A title opened from here returns to THIS screen, exactly as the arm
            // handed `current` to Detail - rebuilt from the route, whose vendor
            // and return argument are the whole of the screen's state.
            val self = Screen.PublicLists(
                vendor = route.vendor,
                returnTo = NavRoutes.decoded(route.returnRoute) ?: Screen.Settings
            )
            PublicListsScreen(
                vendor = PublicListVendor.fromWire(route.vendor) ?: PublicListVendor.TRAKT,
                onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) },
                onOpenTitle = { mediaType, id ->
                    onNavigate(Screen.Detail(type = mediaType, id = id, returnTo = self))
                },
                vm = checkNotNull(publicListsViewModel)
            )
        }

        // BingeCat is two pages behind one destination: the connect page, and the
        // public-lists browser one press inside it. The arm branched on the
        // browser's own state and so does this, so the row keeps landing wherever
        // the viewer left it.
        composable<BingeCatRoute> { entry ->
            val route = entry.toRoute<BingeCatRoute>()
            val self = Screen.BingeCat(
                returnTo = NavRoutes.decoded(route.returnRoute) ?: Screen.Home
            )
            val lists = checkNotNull(bingeCatPublicListsViewModel)
            val listsState by lists.uiState.collectAsStateWithLifecycle()
            if (listsState.browsing) {
                BingeCatPublicListsScreen(
                    // Leaving the browser at its root closes it, so the next visit
                    // to the BingeCat row lands on the connect page.
                    onBack = {
                        lists.close()
                        onNavigate(NavRoutes.landingOf(route.returnRoute))
                    },
                    onOpenTitle = { mediaType, id ->
                        onNavigate(Screen.Detail(type = mediaType, id = id, returnTo = self))
                    },
                    vm = lists
                )
            } else {
                BingeCatConnectScreen(
                    onBack = { onNavigate(NavRoutes.landingOf(route.returnRoute)) },
                    onBrowsePublicLists = { lists.open() },
                    vm = checkNotNull(bingeCatViewModel)
                )
            }
        }
    }
}
