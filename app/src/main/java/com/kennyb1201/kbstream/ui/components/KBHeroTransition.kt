package com.kennyb1201.kbstream.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * The two scopes a shared-element transition needs, bundled so that the
 * experimental annotation stops here.
 *
 * A poster that flies into the Detail hero has to name the same key on both
 * ends, and each end has to be told which transition it belongs to: the
 * [SharedTransitionScope] that owns the shared element registry (the enclosing
 * `SharedTransitionLayout`) and the [AnimatedVisibilityScope] of the screen it
 * is drawn in (the `AnimatedContent` target). Both are receivers of nested
 * lambdas in the app shell, so they are captured once there and bundled into
 * this one value rather than threaded around as two.
 *
 * The app shell publishes it through [LocalKBHeroTransition], and call sites join
 * the flight with [heroSharedElement] — a plain `Modifier`-returning function.
 * That is deliberate: it keeps `ExperimentalSharedTransitionApi` out of every
 * call site, so a screen opts in without knowing this API is experimental, or
 * that shared elements exist at all.
 *
 * The key is the title's `type:id` — the same string Home already uses for
 * `lastFocusedItemKey` — so a rail poster and the Detail page it opens agree
 * on what is being shared without either side having to know about the other.
 */
@Immutable
@OptIn(ExperimentalSharedTransitionApi::class)
class KBHeroTransition(
    private val sharedScope: SharedTransitionScope,
    private val visibilityScope: AnimatedVisibilityScope,
    /** Flight duration; 0 under reduced motion (see KBMotion). */
    private val durationMs: Int = KB_SCREEN_TRANSITION_MS
) {

    companion object {
        /** Stable shared-element key for one title. */
        fun keyOf(type: String, id: String): String = "$type:$id"
    }

    /**
     * The shared-element modifier for [key].
     *
     * `placeholderSize = ContentSize` keeps both ends honest. It means "hold
     * the slot at the size this composable would normally be", so the rail
     * keeps the poster's own footprint for the duration of the flight instead
     * of letting the posters beside it shuffle across to fill the gap (and
     * shuffle back on return). `AnimatedSize` would instead drag the rail
     * layout out to the destination's size mid-flight.
     *
     * `boundsTransform` uses the same duration as the screen transition, so the
     * flight and the crossfade finish together instead of the poster arriving a
     * beat late — including the reduced-motion case, where it is 0.
     */
    @Composable
    fun modifierFor(key: String): Modifier = with(sharedScope) {
        Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = { _, _ -> tween(durationMs) },
            placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize
        )
    }
}

/**
 * [KBHeroTransition.modifierFor] for call sites that may not have a transition
 * (a screen rendered outside the app shell's `AnimatedContent`), so the caller
 * does not need a null check around a `@Composable` call.
 */
@Composable
fun KBHeroTransition?.modifierOrEmpty(key: String): Modifier =
    if (this == null) Modifier else modifierFor(key)

/**
 * The app shell's hero-transition scope, published as an ambient value from
 * inside the `AnimatedContent` content lambda.
 *
 * This exists so that opting a poster in costs one modifier and nothing else.
 * Threading the scope through screen signatures instead would mean adding a
 * parameter to `LibraryScreen`, then to `ItemGrid`, then to `ListsPane`, then to
 * `LibraryPosterCard` — four signatures to make one poster fly — and the same
 * again for every other grid. Providing it once at the screen boundary keeps the
 * call sites to a single line and means a new poster grid gets the flight for
 * free.
 *
 * Null outside the shell (previews, or a screen composed standalone), which
 * [heroSharedElement] treats as "no flight".
 */
val LocalKBHeroTransition = compositionLocalOf<KBHeroTransition?> { null }

/**
 * Marks this node as a participant in the poster -> Detail hero flight.
 *
 * Both ends must use the same `type`/`id` — that is what pairs them. A no-op
 * when there is no enclosing transition, so call sites never need a null check
 * and a poster grid keeps working unchanged outside the app shell.
 *
 * This is the **destination** end, and also the right choice for any endpoint
 * that is always in play — the Detail hero, which is not focusable and so has
 * no focus to be gated on. A card that can *start* a flight should use
 * [heroSourceElement] instead.
 *
 * Only worth adding to a node that can open a **Detail** page. Two Detail pages
 * share one `AnimatedContent` content key and therefore do not animate at all,
 * so the rails inside Detail (More Like This, collections) deliberately do not
 * opt in: there is no transition for them to join.
 */
@Composable
fun Modifier.heroSharedElement(type: String, id: String): Modifier =
    then(
        LocalKBHeroTransition.current
            .modifierOrEmpty(KBHeroTransition.keyOf(type, id))
    )

/**
 * The one title whose card is the current source of a hero flight.
 *
 * A single value for the whole app, because a single screen is on top at a
 * time and exactly one card in it holds D-pad focus, so there is never more
 * than one flight source to describe.
 *
 * Deliberately sticky: it records the last card FOCUSED, not the focused card.
 * Both directions of the flight depend on that.
 *
 *  - Forward, pressing a card moves focus off the rail before the transition
 *    paints its first frame, so an "is this card focused right now" test would
 *    detach the source at exactly the moment the flight needs it.
 *  - Back, the flight into the rail has to find the same card again once focus
 *    returns to it, and by then the card may have been disposed and recomposed
 *    while Detail was on top — a flag kept on the card itself would have gone
 *    with it.
 */
private val heroSourceKey = mutableStateOf<String?>(null)

/**
 * [heroSharedElement] for a card that can START a flight.
 *
 * Only the card the viewer is about to open can be that end, so only it joins
 * the registry. Putting the shared-element modifier on every poster in every
 * rail and grid instead registered a lookahead-tracked node on hundreds of
 * cards at once, and the cost of that read as jank across the app while
 * scrolling. The Detail hero keeps [heroSharedElement]: a destination is always
 * in play, not just when it is the last thing focused.
 *
 * The per-card [derivedStateOf] is what keeps this cheap. A card recomposes
 * only when it personally gains or loses the role, so moving focus from one
 * card to the next re-runs exactly two of them — not every card that happens to
 * be composed, which is what a plain read of [heroSourceKey] would do.
 */
@Composable
fun Modifier.heroSourceElement(type: String, id: String): Modifier {

    val transition =
        LocalKBHeroTransition.current
            ?: return Modifier

    val key =
        KBHeroTransition.keyOf(type, id)

    val isSource =
        remember(key) {
            derivedStateOf { heroSourceKey.value == key }
        }

    return this
        .onFocusChanged { focusState ->
            if (focusState.hasFocus) heroSourceKey.value = key
        }
        .then(
            if (isSource.value) transition.modifierFor(key) else Modifier
        )
}
