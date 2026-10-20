package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The five top-level destinations a phone reaches from its bottom bar.
 *
 * This is the phone's navigation, in the order the bar draws it. The four
 * screens Home's header used to hide behind icons (Search, Live TV, Library,
 * Settings) are all here, so the header no longer has to carry them - and
 * because the bar is hosted by the shell rather than by a screen, it is still
 * there after you use it.
 */
enum class MobileNavDestination(
    val label: String,
    val icon: ImageVector
) {
    Home("Home", Icons.Filled.Home),
    Search("Search", Icons.Filled.Search),
    LiveTv("Live TV", Icons.Filled.LiveTv),
    Library("Library", Icons.Filled.VideoLibrary),
    Settings("Settings", Icons.Filled.Settings)
}

/**
 * Which bar item is lit for [screen], or null when the bar must not be drawn.
 *
 * The rule is the whole visibility policy in one pure place: only the five
 * top-level destinations carry the bar, and everything else - a Detail page, a
 * browse drill-down, the streams picker, the player, onboarding (which is not a
 * Screen), the connect pages, the profile picker/editor, Stats - is a pushed
 * screen and gets none of it. A bar over a poster grid or under a film is the
 * thing this rule exists to prevent.
 *
 * Because the answer is derived from the screen the shell is drawing, the lit
 * item always matches what is on screen - including after Back, which is the
 * one path a "remember the last selected tab" implementation gets wrong.
 */
internal fun mobileNavSelection(screen: Screen): MobileNavDestination? = when (screen) {
    is Screen.Home -> MobileNavDestination.Home
    is Screen.Search -> MobileNavDestination.Search
    is Screen.Guide -> MobileNavDestination.LiveTv
    is Screen.Library -> MobileNavDestination.Library
    is Screen.Settings -> MobileNavDestination.Settings
    else -> null
}

/**
 * "Take Home back to the top": a one-shot request from the bar's Home item to
 * the Home screen.
 *
 * The bar belongs to the shell and Home's scroll position belongs to
 * `HomeScreenMobile`'s own `LazyListState`, and there are two reasons not to
 * lift that state up instead. Hoisting it to the shell would make Home keep a
 * scroll offset across a trip to Search (today it reopens at the top, which is
 * what the phone screen was built to do), and reaching it would mean growing
 * `HomeScreen` - the TV entry - a parameter only a phone uses. A one-shot
 * request crosses the same gap `HomeRailManagerRequest` already does, so the
 * pattern is the project's.
 */
object MobileHomeTopRequest {

    private val requested = MutableStateFlow(false)

    /** Raised by a re-tap of Home; Home's own effect is what consumes it. */
    val requests: StateFlow<Boolean> = requested.asStateFlow()

    fun request() {
        requested.value = true
    }

    /**
     * True exactly once per request, so a request raised in one Home session
     * cannot scroll a later one - and a re-entry into Home, which already opens
     * at the top, is not a scroll at all.
     */
    fun consume(): Boolean {
        if (!requested.value) return false
        requested.value = false
        return true
    }
}

/**
 * The phone's persistent bottom navigation: labeled, thumb-reachable, and the
 * app's own surface rather than a stock Material bar.
 *
 * It is drawn by the shell (`MainActivity`) outside the screen switch, so it
 * survives moving between the five destinations instead of being rebuilt by
 * each of them - the one property the concept design called for and a per-screen
 * bar cannot have. It is also a *layout sibling* of the screens there rather
 * than an overlay, which is why no top-level screen needed a bottom inset added:
 * the switch is given the space above the bar, so the last row of every rail
 * ends where the bar begins.
 *
 * Styling follows the phone theme: [KBSurface] plate under a hairline, labels
 * under the icons, and the selected item's icon, label and indicator pill in
 * [KBAccent] while the rest stay [KBTextLo]. Every item is a full-width fifth of
 * the bar, so each is well past the 48dp minimum touch target.
 *
 * The bottom system inset is reserved by the shell already (the root Box pads
 * `systemBars` on a phone), which means [NavigationBar]'s own inset is zero
 * here; the explicit [navigationBarsPadding] is a guard for the day this bar is
 * ever composed outside that box, not a second padding.
 */
@Composable
fun MobileBottomNav(
    selected: MobileNavDestination,
    onSelect: (MobileNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    MobileTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                // The fill comes before the inset padding on purpose: the plate
                // runs to the window edge, only the items are lifted off it.
                .background(KBSurface)
                .navigationBarsPadding()
        ) {
            // The edge the bar needs against a poster rail scrolling behind it:
            // without it the plate and the screen are the same colour and the
            // bar reads as a gap in the content.
            HorizontalDivider(
                thickness = 1.dp,
                color = KBTextLo.copy(alpha = 0.14f)
            )
            NavigationBar(
                containerColor = KBSurface,
                contentColor = KBTextLo,
                tonalElevation = 0.dp,
                // The Column above already reserved the navigation bar; letting
                // the component reserve it again would double the gap.
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                MobileNavDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == selected,
                        onClick = { onSelect(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                // The label below is the item's accessible name;
                                // a description here would read it twice.
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = destination.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = KBAccent,
                            selectedTextColor = KBAccent,
                            indicatorColor = KBAccent.copy(alpha = 0.16f),
                            unselectedIconColor = KBTextLo,
                            unselectedTextColor = KBTextLo
                        )
                    )
                }
            }
        }
    }
}
