package com.kennyb1201.kbstream.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import kotlinx.coroutines.delay

/** How long a message stays up before it dismisses itself. */
private const val FEEDBACK_VISIBLE_MS = 3_500L

/**
 * One transient message: what just happened, and optionally the one thing the
 * user can do about it.
 *
 * [id] exists so showing the same text twice in a row still restarts the
 * dismiss timer and re-announces — without it, "Added to list" followed by
 * "Added to list" would be silently treated as no change.
 */
data class KBFeedbackMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val isError: Boolean = false,
    val id: Long = System.nanoTime()
)

/**
 * The app's one transient-feedback channel.
 *
 * Before this the app had four ways of saying "something happened": an Android
 * `Toast` in the player and the profile editor, a dismissible `StatusBanner` in
 * add-ons, an `InlineErrorChip` in the guide and a plain `Text` line in the
 * Simkl screen. The system Toast is the worst of them on a TV — small, grey,
 * phone-shaped, and gone before a viewer sitting ten feet away has finished
 * reading it.
 */
class KBFeedbackState {

    var current by mutableStateOf<KBFeedbackMessage?>(null)
        private set

    fun show(
        text: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        isError: Boolean = false
    ) {
        current = KBFeedbackMessage(
            text = text,
            actionLabel = actionLabel,
            onAction = onAction,
            isError = isError
        )
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun rememberKBFeedbackState(): KBFeedbackState = remember { KBFeedbackState() }

/**
 * Null when there is no host above this node — the player Activities are
 * separate Activities with their own chrome and never provide one.
 */
val LocalKBFeedback = staticCompositionLocalOf<KBFeedbackState?> { null }

/**
 * Post a transient message from any screen. Returns the host's state, or a
 * state nothing is listening to when called outside the app shell — callers
 * that care should check [LocalKBFeedback] instead.
 */
@Composable
fun rememberKBFeedback(): KBFeedbackState =
    LocalKBFeedback.current ?: rememberKBFeedbackState()

/**
 * Renders the current message bottom-centre, above everything else in the app
 * shell. Auto-dismisses; a new message replaces the old one outright rather
 * than queueing, because on a TV these are acknowledgements, not a log.
 *
 * The container carries `liveRegion = Polite`, so TalkBack reads each message
 * as it appears. Nothing in the app announced state changes before this:
 * adding to a list, hiding a title or switching a profile all happened in
 * silence for a screen-reader user.
 */
@Composable
fun KBFeedbackHost(
    state: KBFeedbackState,
    modifier: Modifier = Modifier
) {
    val message = state.current

    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(FEEDBACK_VISIBLE_MS)
            state.dismiss()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(KB_SCREEN_TRANSITION_MS)) +
                slideInVertically(tween(KB_SCREEN_TRANSITION_MS)) { height -> height / 2 },
            exit = fadeOut(tween(KB_SCREEN_TRANSITION_MS)) +
                slideOutVertically(tween(KB_SCREEN_TRANSITION_MS)) { height -> height / 2 }
        ) {
            message?.let { current ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier
                        .padding(bottom = 40.dp)
                        .widthIn(max = 720.dp)
                        .background(KBSurfaceRaised, KBShapePanel)
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .background(if (current.isError) KBDanger else KBAccent)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = current.text,
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 560.dp)
                    )
                    if (current.actionLabel != null && current.onAction != null) {
                        Spacer(modifier = Modifier.width(18.dp))
                        KBCard(onClick = current.onAction) {
                            CompositionLocalProvider(LocalContentColor provides KBAccent) {
                                Text(
                                    text = current.actionLabel,
                                    color = KBAccent,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 8.dp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
