package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.touch.MobileDivider
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
 * [KBAccent] while the rest stay [KBTextLo]. Every item gets an equal share of
 * the width the profile avatar leaves, so each is well past the 48dp minimum
 * touch target.
 *
 * The right end carries the active profile's avatar: a door to the picker, not
 * a sixth destination (see [MobileProfileButton]).
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
    onSwitchProfile: () -> Unit,
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
            MobileDivider()
            // The item row: the five destinations, then the profile door in a
            // fixed slot of its own. The NavigationBar keeps `weight(1f)` so
            // the five still split what the avatar does not take - adding the
            // sixth element must not squeeze them or give one of them the
            // avatar's width.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationBar(
                    modifier = Modifier.weight(1f),
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
                MobileProfileButton(onClick = onSwitchProfile)
            }
        }
    }
}

/**
 * The profile door at the bar's right end.
 *
 * Deliberately NOT a sixth destination: it has no selected state and no label,
 * it sits in a fixed slot rather than a share of the row, and `selected` never
 * reaches it - switching identity is not somewhere the app goes. What it shows
 * is the active profile's avatar, read from the same [ProfileManager] flow the
 * Home top bar used to read, so a switch re-renders it on every top-level
 * screen at once.
 *
 * Tapping it opens the picker, whose rules (the PIN and exit gates, the kids
 * restrictions) are untouched by this move: the button only reaches the door
 * Home's chip already opened.
 */
@Composable
private fun MobileProfileButton(onClick: () -> Unit) {
    val activeProfile by ProfileManager.activeProfile.collectAsStateWithLifecycle()
    val name = activeProfile?.name?.takeIf { it.isNotBlank() } ?: "PROFILES"

    Box(
        modifier = Modifier
            // A fixed 56dp slot with the bar's own height, so the touch target
            // is the full height rather than the 30dp circle drawn inside it.
            .width(56.dp)
            .height(80.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ProfileAvatarCircleMobile(
            name = name,
            // -1 is the picker's "no avatar" value: the manage glyph, which is
            // also what this door means when no profile is active yet.
            avatarIndex = activeProfile?.avatarIndex ?: -1,
            customAvatarUrl = activeProfile?.customAvatarUrl ?: activeProfile?.avatarData,
            size = 30.dp
        )
    }
}
