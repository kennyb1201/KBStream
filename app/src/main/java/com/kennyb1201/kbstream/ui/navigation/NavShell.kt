package com.kennyb1201.kbstream.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.navDepth
import com.kennyb1201.kbstream.ui.mobile.mobileNavSelection

// ---------------------------------------------------------------------------
// The host that owns the shell (spec: navcompose, F3+F4+F5).
//
// Every destination renders through `renderScreen`, which is the shell's own arm
// table - one lambda, keyed by the Screen a back-stack entry stands for. That is
// deliberate: the arms are the app's single description of what a screen is
// composed with (sixty captured values from MainActivity's composition), and
// rewriting them into twenty-six destination lambdas would be a god-class split
// by another name. What the host owns is NAVIGATION: the stack, the transitions,
// the restoration, and every Back landing.
//
// Two rules are load-bearing here:
//
//  - BACK LIVES IN THE DESTINATION. The shell's global table cannot answer any
//    more: the controller registers its pop callback when the host composes,
//    which is AFTER the shell's handler and BEFORE the destinations', so the pop
//    would win over the table and lose to the destinations. Registering the
//    landing inside each destination reproduces today's order exactly - the
//    screen's own handler (if it has one) composes after this one and wins, and
//    the Kids Time Lock veil composes after the whole host and wins over both.
//
//  - THE VIEWMODEL STORE IS THE ACTIVITY'S. Inside a destination the ambient
//    store owner is the back-stack entry, and the shell pops that entry the
//    moment it leaves the kind, so a screen's default `hiltViewModel()` would be
//    scoped to the visit: a browse page would lose its loaded pages when Detail
//    opened over it, the public-list browsers would lose the level the viewer
//    left and restart an import, and the catalog grid would stop sharing Home's
//    ViewModel. Re-providing the activity's store per destination keeps the
//    instance and its life exactly as the arms had them.
// ---------------------------------------------------------------------------

@Composable
internal fun NavShell(
    navController: NavHostController,
    startDestination: Any,
    transitionMs: Int,
    /**
     * The shell's arm table: `Screen` in, screen composed. The receiver is the
     * destination's own `AnimatedContentScope`, which is also its
     * `AnimatedVisibilityScope` - the hero flight needs it per screen.
     */
    renderScreen: @Composable AnimatedContentScope.(Screen) -> Unit,
    /** Every navigation goes through the seam, so the shell's mirror follows. */
    onNavigate: (Screen) -> Unit,
    /** The activity's store, re-provided inside each destination. */
    viewModelOwner: ViewModelStoreOwner,
    /** Home: cancel an in-flight autoplay, else the exit prompt. */
    onHomeBack: () -> Unit,
    /** Search: commit the query and reset the list, then Home. */
    onSearchBack: () -> Unit,
    /** The entry picker: the exit prompt; a pushed one: its returnTo. */
    onPickerBack: (returnTo: Screen?) -> Unit,
    /** ProfileEdit with no returnTo belongs to the picker. */
    onProfileEditBack: (returnTo: Screen?) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // The shell's three transition rules, per destination. A phone lateral
        // tab move is a crossfade - two screens that sit next to each other in
        // the bar, not a push; television keeps the depth slide, because it has
        // no bar and those moves arrive from the rail the viewer was on.
        // Forward is the incoming screen drifting in from the trailing edge
        // while the old one fades; Back moves only the outgoing one, so the two
        // never look like they are fighting for the same pixels.
        enterTransition = {
            if (isLateralTabMove()) {
                fadeIn(tween(transitionMs))
            } else if (depthOf(targetState) >= depthOf(initialState)) {
                slideInHorizontally(tween(transitionMs)) { width -> width / 6 } +
                    fadeIn(tween(transitionMs))
            } else {
                fadeIn(tween(transitionMs))
            }
        },
        exitTransition = { fadeOut(tween(transitionMs)) },
        popEnterTransition = { fadeIn(tween(transitionMs)) },
        popExitTransition = {
            slideOutHorizontally(tween(transitionMs)) { width -> width / 6 } +
                fadeOut(tween(transitionMs))
        }
    ) {
        // ── Top-level destinations ───────────────────────────────────────
        composable<HomeRoute> {
            BackHandler { onHomeBack() }
            Destination(Screen.Home, renderScreen, viewModelOwner)
        }

        composable<SearchRoute> {
            // Search keeps its exit side effects in its own destination: the
            // query is committed and the session cleared HERE, so a drill-down
            // into Detail (which is a different entry) never clears the results
            // the viewer is coming back to.
            BackHandler { onSearchBack() }
            Destination(Screen.Search, renderScreen, viewModelOwner)
        }

        composable<GuideRoute> {
            BackHandler { onNavigate(Screen.Home) }
            Destination(Screen.Guide, renderScreen, viewModelOwner)
        }

        composable<LibraryRoute> {
            BackHandler { onNavigate(Screen.Home) }
            Destination(Screen.Library, renderScreen, viewModelOwner)
        }

        composable<SettingsRoute> {
            BackHandler { onNavigate(Screen.Home) }
            Destination(Screen.Settings, renderScreen, viewModelOwner)
        }

        // ── Settings leaves with a hardwired landing ─────────────────────
        composable<RemoteRoute> {
            BackHandler { onNavigate(Screen.Settings) }
            Destination(Screen.Remote, renderScreen, viewModelOwner)
        }

        composable<StatsRoute> {
            BackHandler { onNavigate(Screen.Settings) }
            Destination(Screen.Stats, renderScreen, viewModelOwner)
        }

        // The hub is reached from the guide's header, so its Back is Guide and
        // is not data: a game launched from its sheet carries its own returnTo.
        composable<SportsRoute> { entry ->
            BackHandler { onNavigate(Screen.Guide) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        // ── Leaves that carry their own returnTo ─────────────────────────
        composable<AddonsRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<AddonsRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<CatalogBuilderRoute> { entry ->
            BackHandler {
                onNavigate(NavRoutes.landingOf(entry.toRoute<CatalogBuilderRoute>().returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<SimklRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<SimklRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<TraktRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<TraktRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<BingeCatRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<BingeCatRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<PublicListsRoute> { entry ->
            BackHandler {
                onNavigate(NavRoutes.landingOf(entry.toRoute<PublicListsRoute>().returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<YearReviewRoute> { entry ->
            BackHandler {
                onNavigate(NavRoutes.landingOf(entry.toRoute<YearReviewRoute>().returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        // ── Browse drill-downs ───────────────────────────────────────────
        composable<ActorRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<ActorRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<StudioRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<StudioRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<DecadeRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<DecadeRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<TagRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<TagRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<CollectionRoute> { entry ->
            BackHandler {
                onNavigate(NavRoutes.landingOf(entry.toRoute<CollectionRoute>().returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<CatalogGridRoute> { entry ->
            BackHandler {
                onNavigate(NavRoutes.landingOf(entry.toRoute<CatalogGridRoute>().returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<KBFolderRoute> { entry ->
            BackHandler {
                onNavigate(NavRoutes.landingOf(entry.toRoute<KBFolderRoute>().returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        // ── Detail and the picker ────────────────────────────────────────
        // Detail's Back is the sanitized returnTo: the one that strips a
        // one-shot autoplay, so returning to this page can never press Play.
        composable<DetailRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<DetailRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<StreamsRoute> { entry ->
            BackHandler { onNavigate(NavRoutes.landingOf(entry.toRoute<StreamsRoute>().returnRoute)) }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        // ── Profiles ─────────────────────────────────────────────────────
        composable<ProfilePickerRoute> { entry ->
            val route = entry.toRoute<ProfilePickerRoute>()
            // The entry picker's Back is the app's exit prompt, never Home; a
            // picker opened from Settings or the top bar belongs to that screen.
            BackHandler {
                onPickerBack(NavRoutes.decoded(route.returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }

        composable<ProfileEditRoute> { entry ->
            val route = entry.toRoute<ProfileEditRoute>()
            // The Manage tile opens this page with no returnTo; its Back is the
            // picker.
            BackHandler {
                onProfileEditBack(NavRoutes.decoded(route.returnRoute))
            }
            Destination(NavRoutes.screenOfEntry(entry), renderScreen, viewModelOwner)
        }
    }
}

/**
 * One destination's content: the activity's ViewModel store, then the arm table.
 *
 * The store is re-provided here rather than around the NavHost because NavHost
 * installs the back-stack entry as the owner INSIDE each destination, so a
 * provider outside it would be shadowed.
 */
@Composable
private fun AnimatedContentScope.Destination(
    screen: Screen,
    renderScreen: @Composable AnimatedContentScope.(Screen) -> Unit,
    viewModelOwner: ViewModelStoreOwner
) {
    CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelOwner) {
        renderScreen(screen)
    }
}

/** Depth of a destination, from the table the shell has always used. */
private fun depthOf(entry: NavBackStackEntry): Int = NavRoutes.screenOfEntry(entry).navDepth

/**
 * A move between two of the phone's top-level tabs.
 *
 * Television has no bar, so its moves between those screens really are pushes
 * and keep the depth slide - which is `mobileNavSelection` answering null there,
 * the same predicate the bar itself uses.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isLateralTabMove(): Boolean {
    if (FormFactor.isTv) return false
    val from = NavRoutes.screenOfEntry(initialState).let { mobileNavSelection(it) } ?: return false
    val to = NavRoutes.screenOfEntry(targetState).let { mobileNavSelection(it) } ?: return false
    return from != to
}
