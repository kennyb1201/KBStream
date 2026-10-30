# Phone port — status and deferred work

The app is written for Android TV. The phone port keeps every TV screen's data
and rules exactly where they are and replaces only its presentation, one screen
at a time. **This file is the tracker for that work**: what is ported, what each
ported screen still does not do, and what is left. It is committed so the list
survives beyond a chat session — nothing here happens on its own.

## The rule

1. **One branch at the TV screen's entry**, never a rewrite of the TV page:

   ```kotlin
   if (!FormFactor.isTv) {
       SomeScreenMobile(/* same params, same ViewModel */)
       return
   }
   ```

2. **The phone layout lives in a new file** under `ui/mobile/` and imports no
   `androidx.tv.material3` and no focus plumbing (`FocusRequester`, `onPreviewKeyEvent`).
   It uses the touch twins in `ui/components/touch/` and `MobileTheme`.
3. **The same ViewModel and callbacks** — no second data path, no copied rules.
   Pure rules that both screens need go in a shared file with no Compose import
   (for example `ui/mobile/MobileGuideGroups.kt`, `ui/iptv/GuideRules.kt`).
4. **Pin it with a contract test** under `app/src/test/java/.../ui/mobile/`:
   the dispatch, the touch widget layer, the ViewModel reads, and whatever
   play/navigation path the screen owns. Pure helpers are tested by behaviour.

Portrait is the phone's first shape: see `docs/mobile_portrait.md` for the
orientation policy, the width-derived grid rule and the overlay width cap.

## Shell: the phone safe area

The window is edge-to-edge (`setDecorFitsSystemWindows(window, false)`) because
the TV shell wants the whole panel and a television has no status or navigation
bar for content to slide under. A phone does, so the root Box in `MainActivity`
reserves `systemBars ∪ displayCutout` **on the phone only** - one place instead
of twenty screen files, gated on `FormFactor.isTv` so the TV layout is unchanged.
`imePadding()` still follows it, measured against the space the bars left.
`MobileSafeAreaContractTest` pins the gate, the inset pair and that ordering.

## Shell: the phone's bottom navigation

Home used to reach its other screens through four header icons. They were the
wrong shape for a hand: they scrolled away with Home's content and they vanished
the moment you used one, because they belonged to Home rather than to the app.
The phone now has one persistent bottom bar, owned by the shell:

| Item | Destination |
|---|---|
| Home | `Screen.Home`; re-tapping it while on Home scrolls Home back to the top |
| Search | `Screen.Search` |
| Live TV | `Screen.Guide` |
| Library | `Screen.Library` |
| Settings | `Screen.Settings` |

- **One owner.** `MobileBottomNav` is drawn by `MainActivity` next to the screen
  switch, not by any screen, so switching to Search does not take the way back
  to Home with it. The four actions are the same lambdas Home's header icons
  called (`navToSearch`/`navToGuide`/`navToLibrary`/`navToSettings`), handed to
  both the bar and `HomeScreen`, so the TV and the phone cannot land somewhere
  different for the same destination.
- **One rule.** `mobileNavSelection(screen)` answers for the five top-level
  destinations and returns null for everything else - Detail, browse
  drill-downs, the poster grids, the streams picker, the player, the connect
  pages, the profile picker/editor, Stats, Sports. Null is the bar's absence, so
  a pushed screen can never wear it. The answer is *derived from the screen
  being drawn* rather than stored, which is what keeps the right item lit after
  Back.
- **Layout, not padding.** The bar is the shell `Column`'s own last child,
  after the screen switch's Box, so the switch is measured with the space that
  is left and no top-level screen's last rail can sit under the bar. That is the
  same rule the spec states as "pad every screen", implemented once instead of
  five times - and it cannot drift when a sixth top-level screen is added. On a
  television the bar is null and the column holds the single child it always
  had.
  Being a sibling *of the Box* is not the same thing: it has to be a child of
  that same `Column`. The shell is a stacking host (`SharedTransitionLayout`),
  and a sibling of the column is laid out at the top of the stack - which is
  exactly where the five items drew the first time the bar shipped, because a
  `}` closed the screen Box one line early and took the column's close with it.
  `MobileBottomNavContractTest` measures brace depth for this: token order
  cannot see nesting, and every substring check it had passed while the bar was
  across the top of the phone.
- **Styling.** The brand plate (`KBSurface`) under a hairline, `MaterialTheme`
  (the phone theme), labels under the icons, the selected item's icon, label and
  indicator in `KBAccent`, the rest in `KBTextLo`. The bottom system inset is
  already reserved by the shell's safe area; `navigationBarsPadding()` on the
  bar is a guard for the day it is composed anywhere else.
- **Home's header** is the wordmark alone now (ellipsised, full width).
  `HomeScreenMobile` no longer takes the four destination callbacks or the
  profile one at all, so a header door cannot come back by accident.
- **The profile door** is the active profile's avatar at the bar's right end:
  a circle drawn by the picker's own `ProfileAvatarCircleMobile` (custom avatar,
  then the palette and initial), observing `ProfileManager.activeProfile` the
  way Home's chip did. It is not a sixth destination - no selected state, no
  label, a fixed 56dp slot beside the five equal shares - and tapping it opens
  the same picker Home's chip opened. Back returns to the screen it left;
  choosing a profile always starts on Home (see below). The picker's own gates
  (PIN/exit, kids) are untouched by the move.

**A profile switch always starts on Home (2026-10-20).** The picker's `onSelect`
used to hand back `returnTo`, the screen it was opened from, so a switch made on
Search, Library, Live TV or Settings dropped the viewer onto the PREVIOUS
profile's screen with the new profile's rails still loading underneath it.
Choosing a profile now sets `Screen.Home` unconditionally and re-arms the launch
splash for the rails that are about to load; `returnTo` is still what Back
leaves for, and the cold-launch picker already landed on Home. One rule in one
place, so the bar's avatar, Home's chip and Settings' Profiles row cannot
disagree. (`MobileBottomNavContractTest`, `LaunchSplashWiringContractTest`.)

The Continue Watching card's episode line prints the episode pair only
(`S02 · E08`, from the same rule the TV rail uses). It used to lead with the
tracker's subtitle — "Resume - S1 E2", "Up Next - ..." — which repeated the
state the card already draws as its top-left chip and pushed the numbers off
the line on a phone; a card the sources named no pair for falls back to the
episode title, then the tracker's own text ("Paused 34%").

`MobileBottomNavContractTest` pins the host, its nesting inside the shell column
(by brace depth), the visibility rule, the shared wiring, the re-tap-to-top
path, the header change, the profile avatar as a door (not a destination, with
the picker's resolution, Back returning to the opener and a switch starting on
Home) and the bar's styling.

## Shell: coming back from another app

The launch gate used to be keyed on the process: the first activity create of a
process was a launch, and with profiles on the device a launch discards the
saved screen and opens on Who's Watching. That is the designed TV rule (each
session starts under a chosen identity) and the wrong phone one - Android kills
a backgrounded process routinely here, so switching to another app and coming
back was a NEW process, the flag was false again, and the viewer landed on the
picker although they had never left.

`isColdLaunch` (in `NavStateSerialization.kt`) now decides, and the saved state
is the evidence: on a phone, a create handed `savedInstanceState` is a return
and restores what was saved under the profile `ProfileManager.init` has already
re-established. A rebuild in the same process was already not a launch; a fresh
task with no saved state still is, so swiping the app away and reopening it
still asks who is watching. A television ignores the saved state entirely, so
its gate is unchanged.

**"Who's Watching on Launch" (Settings → Profiles & Accounts, TV and phone).**
A device-local switch, on by default, that decides whether a launch opens on the
picker at all: off sends the launch straight into the last used profile, which
`ProfileManager.init` has already re-established before the first composition.
It is `show_profile_picker` in `AppPreferences`, listed in
`PrefsPayloadBuilder.EXCLUDED_PREF_KEYS` and out of the synced set, because how a
tv in a living room is shared says nothing about a phone that only ever signs in
as one person. A PIN-protected active profile still gets the picker either way —
the code is entered there, so skipping it would silently unlock the profile.
`LaunchScreenGateTest` pins the gate both as a value and as MainActivity wiring;
`SyncScopeTest` and `DeviceLocalDisplayPrefsTest` pin that it never travels.

The time lock is not a casualty of this: the veil is composed by the shell for
every screen and answers to the guard's evaluation of the ACTIVE profile, which
`KidsTimeGuard.start` has before the first composition and re-evaluates on
foregrounding - so a restored Detail page under a spent budget is veiled
exactly like one reached through the picker. `LaunchScreenGateTest` pins all
four launch decisions, the wiring, and that the launch flag is not a key to the
veil.

## Player touch UX (Phase 3)

The chrome needed no widget port: both engines drive the same View/XML overlay
(`player_chrome.xml`) and every control in it is a tap target already. What a
phone was missing is the pointer vocabulary a remote does not have, so Phase 3
is one gesture layer shared by both engines, attached **only** when
`FormFactor.isTv` is false:

| Gesture | Effect | Rule |
|---|---|---|
| Tap the picture | show/hide the chrome | read as a **confirmed** tap, not on the lift: the double-tap below owns the second finger-down, so a single tap lands after the system's double-tap timeout and a double-tap never also toggles the chrome |
| Double-tap left / right half | seek back / forward 10s | one step per double-tap through the same ±seek button path (`stepSeekBy` / `seekStepBy`), so its live no-op and clamp apply; repeats on one side accumulate inside a 1s window and the ripple + label names the running total (`-30s`); a live/unseekable stream does nothing at all |
| Drag sideways | seek | full width = 30–120 s (`PlayerTouchRules.seekSpanMs`), clamped to the film, nothing on a live stream |
| Drag up/down, left half | brightness | drags the window's own level, floor `MIN_BRIGHTNESS` |
| Drag up/down, right half | volume | the music stream, 0–100 % |
| Pinch | next aspect mode | one step per pinch, through each engine's own aspect cycle |
| Hold | rotate portrait ↔ landscape | via `PlayerTouchDevice.toggleFullscreen`, never on TV |

Two invariants, both pinned by tests:

1. **A swipe never decides whether the film is playing.** The seek commits
   without a play, a pause, or a `playWhenReady` read — the state the viewer
   chose before the drag is the one it ends in (`PlayerTouchRules.playAfterSeek`,
   and `PlayerTouchWiringContractTest` asserts the gesture file contains no
   transport call at all).
2. **TV is untouched.** The main player's D-pad touch arm is the same two lines
   it was, behind the `FormFactor.isTv` branch; the MPV surface gained a listener
   only on phones. Key handling (DPAD scrub, digit jump, MEDIA_*, long-press
   centre, Bluetooth remotes) is not on this path.

**Portrait stage.** In portrait the picture keeps its 16:9 shape as a band at the
top and `chrome_layer` is moved below it (`PlayerPortraitStage`), instead of an
entire landscape picture letterboxed into a sliver behind the controls. It is
runtime layout, not a second XML file, and it restores the declared params on
rotation because `configChanges` keeps the activity alive (both players now
re-apply it from `onConfigurationChanged`).

**The clearlogo slots fit the phone (2026-10-21).** The overlay's title graphic
was clipped on a phone - its edges cut off - and `fitCenter` was not the cause:
both slots were fixed TV-sized boxes (`chrome_clear_logo` 400dp, the two
splash logos 420dp), a phone header cannot give 400dp, and the parent clipped
the box with the art's edges inside it. The overlay's slot is now measured by
its header and capped at 400dp (`ConstraintLayout` +
`layout_constraintWidth_max`, bias 0, so it is left-aligned exactly where the
old box was); both splashes - the Exo activity's `splash_clear_logo` and the
MPV activity's `mpv_loading_logo` - are `wrap_content` with a 420dp cap, which
is the same art at the same centre on a TV and the screen's width on a phone.
At TV widths the caps never bind, so the TV draws what it always did; heights,
`fitCenter`, `adjustViewBounds`, margins and the text-title fallback are
untouched. (`PlayerClearLogoSlotContractTest`.) The shell's own pre-player
splash is Compose and still draws a fixed 420dp slot
(`AutoPlayLoadSplash.LOGO_WIDTH`), so it is the one remaining place a phone can
overhang - it is a Kotlin change and was left out of this layout-only fix.

**The television's overlay does not scroll (2026-10-21).** Reported the day
after the phone fix: "the clearlogo on the player overlay is getting cut off on
tv unless I scroll up into it", and the rule that followed - nothing on the
overlay scrolls except the cast row, and the clearlogo, synopsis and badges are
fixed and all fit. The overlay's title block used to be a `ScrollView` taking
whatever the clock, badges, cast band and control bar left. A television gives
the overlay 540dp; the block (104dp clearlogo + episode line + five-line
synopsis) is taller than the remainder with the cast band up, so it was clipped
and scrolling was the only way to see it whole.

The block is now a plain weighted `LinearLayout` (`chrome_info`, no scrollbar),
the wall clock and "Ends at" share one row (two stacked lines cost a line of the
synopsis), the synopsis is capped at three lines with its old ellipsis, and the
badge chips are a fixed row instead of a horizontal scroller. Measured at
960x540dp with clock, badges, eight cast tiles, a two-line episode title and a
long synopsis all up, every block lands inside the screen with the controls on
it, and the clearlogo keeps its full 104dp
(`PlayerOverlayFitContractTest`).

A phone's player is landscape on a window ~390dp tall, where this same chrome
cannot hold the block plus the cast band plus the controls; there the block is
wrapped in a `ScrollView` at construction
(`PlayerChrome.makeInfoBlockScrollable`, `FormFactor.isTv` false), so the title
and synopsis stay reachable instead of being clipped. The television path is
the XML, untouched by that branch.

**PiP** is unchanged and not form-gated: `enterPipMode` already refuses when the
activity is finishing, on Amazon devices, during an engine handoff, and when the
setting is off. Unverified on hardware like everything else below.

## Ship readiness (Phase 4)

What Phase 4 can settle without a device, and what it cannot.

**One artifact, one update feed.** There are no product flavors (the single
runtime `FormFactor` decision from Phase 0 is why), and `AppUpdater` never reads
the form factor: it takes the release's `metadata.json` plus its one `.apk` and
installs it. `SingleArtifactUpdateContractTest` pins both halves, so a future
flavor split or a form-factor branch in the updater turns red.

**Notification permission is asked, once.** `GET_NEW_EPISODES` and the guide's
live reminders are both on by default, and on Android 13+ a fresh install used
to run them with no grant: `NotificationCenter.canPost()` refuses silently, so
every alert was dropped with nothing on screen to explain it. MainActivity now
asks once at launch, only when an alert feature is on, spends the prompt before
the dialog opens (no re-ask after a denial), and records that locally rather
than in the synced prefs blob - a sibling device still has to answer for itself.
The Settings toggles remain the place to grant it later.
`NotificationPromptRulesTest` and `NotificationPromptWiringContractTest` pin the
four conditions, the launch path and the device-local flag.

**Deep links land on the phone page.** `kbstream://title/...` is advertised on
the invisible handoff activity, parsed by `TitleDeepLink`, forwarded into
MainActivity, and opened on Detail - whose entry is the `FormFactor` dispatch,
so a phone gets `DetailScreenMobile`.
`DeepLinkMobileRouteContractTest` pins the chain end to end.

**Low-RAM.** Nothing new was needed: the video buffer budget is a third of the
process's own `maxMemory()` (clamped 48-160 MB, so it follows `largeHeap` and the
device), and the image memory cache is 15 % of the system memory class
(24-64 MB). On a 3-4 GB phone both land in the same range they already do on a
TV box. The risk left is behavioural, not numerical, and it needs the device.

**Play listing copy** lives in `docs/play_listing.md`, with the screenshot shot
list. The graphics cannot be produced here - no device, no renderer - so they
are a capture task.

**The update popup was a dead end on a phone.** It is the one dialog the two
form factors share, and it had never been branched: its buttons came from the TV
`KBCard` (a focus surface - a finger cannot press it), `onDismissRequest = { }`
swallowed Back and the scrim in every phase, and the panel was a fixed 560dp,
wider than a portrait phone. The phone now draws the same six buttons
(INSTALL NOW / LATER / HIDE / DONE / TRY AGAIN / CLOSE) from `TouchKBCard`,
dismisses with the phase's own action on Back or a scrim tap (Offer -> exactly
LATER, so the dismissal sticks for that version; Installed / Failed ->
`acknowledge()`; Downloading / Installing -> nothing, so a download is never
orphaned), and sizes the panel as the screen minus a 16dp gutter capped at
560dp. The television renders and behaves exactly as before, barrier included.
`UpdatePopupMobileInputContractTest` pins both halves.

### The device pass this phase ends on

- [ ] Screenshots from the shot list, feature graphic at 1024 x 500, listing copy
      pasted (one icon, one banner, no TV UI in any phone shot)
- [ ] Self-update on a phone: install an older build, check Settings -> Check for
      updates, confirm the same APK arrives (no form-factor fork in the feed)
- [ ] Update popup on a phone: INSTALL NOW and LATER both respond to taps, Back
      on the offer dismisses it as Later and it stays down for that version, and
      the portrait panel fits with both buttons on-screen
- [ ] Notification prompt appears once on a fresh Android 13+ install with an
      alert feature on; denying it does not re-prompt on the next launch
- [ ] Both alert kinds actually arrive (new episode, guide reminder)
- [ ] `kbstream://title/<id>` from a browser or adb opens the touch Detail page
- [ ] Low-RAM pass on a 3-4 GB device: rails scroll, 4K/high-bitrate playback,
      no `OutOfMemoryError`, no image-cache thrash (watch `PLAYER_PERF`'s
      "Buffer budget" line)
- [ ] **Final TV regression gate**: the full field-test checklist on both boxes
      (next-build list + AFR/DV/splash items) before the release is tagged

## Screens

| Screen | TV file | Phone file | Status |
|---|---|---|---|
| Home | `ui/home/HomeScreen.kt` | `ui/mobile/HomeScreenMobile.kt` | ported |
| Detail | `ui/detail/DetailScreen.kt` | `ui/mobile/DetailScreenMobile.kt` | ported |
| Streams (source picker) | `ui/streams/StreamsScreen.kt` | `ui/mobile/StreamsScreenMobile.kt` | ported |
| Live TV guide | `ui/iptv/GuideScreen.kt` | `ui/mobile/GuideScreenMobile.kt` + `MobileGuideGroups.kt` | ported |
| Search | `ui/search/SearchScreen.kt` (+ `SearchScreenPart2`) | `ui/mobile/SearchScreenMobile.kt` | ported |
| Library | `ui/library/LibraryScreen.kt` | `ui/mobile/LibraryScreenMobile.kt` | ported |
| Settings | `ui/settings/SettingsScreen.kt` | `ui/mobile/SettingsScreenMobile.kt` | ported |
| Viewing stats | `ui/settings/StatsScreen.kt` | `ui/mobile/StatsScreenMobile.kt` | ported |
| Sports hub | `ui/sports/SportsHubScreen.kt` | `ui/mobile/SportsHubScreenMobile.kt` | ported |
| Add-ons | `ui/addons/AddonsScreen.kt` | `ui/mobile/AddonsScreenMobile.kt` | ported |
| Catalog builder | `ui/addons/CatalogBuilderScreen.kt` | `ui/mobile/CatalogBuilderScreenMobile.kt` | ported |
| Profile picker | `ui/profiles/ProfilePickerScreen.kt` | `ui/mobile/ProfilePickerScreenMobile.kt` | ported |
| Profile editor | `ui/profiles/ProfileEditScreen.kt` | `ui/mobile/ProfileEditScreenMobile.kt` | ported |
| Tag / Studio / Decade (browse pages) | `ui/{tag,studio,decade}/` | `ui/mobile/BrowseSectionsMobile.kt` + `TagScreenMobile.kt`, `StudioScreenMobile.kt`, `DecadeScreenMobile.kt` | ported |
| Collection | `ui/collection/CollectionScreen.kt` | `ui/mobile/CollectionScreenMobile.kt` | ported |
| Actor | `ui/actor/ActorScreen.kt` | `ui/mobile/ActorScreenMobile.kt` | ported |
| KB folder | `ui/kb/KBFolderScreen.kt` | `ui/mobile/KBFolderScreenMobile.kt` | ported |
| Simkl connect | `ui/simkl/SimklConnectScreen.kt` | `ui/mobile/SimklConnectScreenMobile.kt` | ported |
| Onboarding | `ui/onboarding/OnboardingScreen.kt` | `ui/mobile/OnboardingScreenMobile.kt` | ported |
| Catalog grid ("Open in Grid") | `ui/home/CatalogGridScreen.kt` | `ui/mobile/CatalogGridScreenMobile.kt` | ported |

Unported screens still compile and render on a phone, but they are D-pad
Compose (`androidx.tv.material3`) — reachable by tap, awkward to use, and they
are what the remaining work covers.

**Every poster surface follows the landscape switch.** The phone pages were
built on `components.touch.PosterCard` before the app-wide "Landscape posters"
toggle existed, and each kept calling it directly — so turning that toggle on
reshaped Home's rails and nothing else on a phone. They now draw
`components.touch.TouchGlobalPosterCard` (for a row of fixed-width tiles) or
`TouchGlobalPosterTile` (for a cell whose width the layout owns), the touch
twins of `GlobalPosterCard`: one answer to the switch, and the same backdrop +
clearlogo resolution the rails use (`ui/components/GlobalLandscapeArt.kt`).
Library, Search, the browse sections, actor credits, collection parts and its
header poster, KB folders, Detail's rails and the catalog grid all went through
that swap. A Home surface keeps its own two-switch rule
(`AppPreferences.homeLandscapeActive`), so the Home-rails switch still stops at
Home.

**Captions go through one block too.** Each phone tile used to draw its own
title and `year · ★rating` lines, so the Settings switches for poster titles,
years and star ratings stopped at the television: the TV showed bare posters and
the phone kept its captions. Those tiles now draw
`components.touch.TouchPosterCaptions`, the touch twin of `PosterCaptions` -
the same three synced prefs, the same "emit nothing when every enabled part is
blank" rule - in the browse sections, the catalog grid, Library, Search, actor
credits, collection parts, and Detail's collection and More Like This rails.
Lines that are not the caption (Library's source label) are not gated by it.

The 2026-10-20 audit for "does every screen obey the three switches" found one
last hand-rolled title: the Catalog Builder's PREVIEW strip, on **both** form
factors, which drew `meta.name` under its poster and so kept showing titles
with the switch off (and never showed the year or rating it had). It draws the
shared block now - `TouchPosterCaptions` on the phone, `PosterCaptions` on the
TV - and `PosterCaptionsMobileContractTest` pins the pair. The catalog grid
("Open in Grid") was already converted on both platforms; the audit's full list
is Home's own rails (no captions by design - see the prefs note above), the
catalog grid, browse sections, Library, Search, actor credits, collection
parts, Detail's rails, KB folders and the builder preview.

**A Settings change now reaches the screen it changes.** The synced-pref
revision (`PrefsPayloadApplier.revision`) used to tick only when a PULLED blob
differed, so a local edit moved the switch in Settings and nothing else: every
`remember(syncedRevision) { read the pref }` kept the value it read at first
composition. That is what "changing it does nothing" was for poster size and
the three poster-caption switches. Every display-pref setter funnels through
`AppPreferences.syncDisplayPrefsBlob`, which ticks the same counter
(`notifyLocalPrefChange`), so a local edit and a sibling device's edit arrive by
one path. (Poster Size's setter now ticks it directly - see the device-local
note below - but the counter and the readers are unchanged.)

**Poster Size has a phone scale.** `ui/components/touch/TouchPosterSize.kt`
reads the same pref the TV's `PosterSize` does and maps it to a thumb-sized tile
(96 / 112 / 128dp wide, medium being what the phone pages already used), plus
`rememberTouchTileWidth` for the landscape width. Every phone poster surface
sizes its cell, its card and its caption from that one answer - the rails, the
browse sections, Library, Search, actor credits, collection parts, the KB
folder, the catalog grid, Detail's rails and Home's own rows. Before it, each
file pinned its own literal (112dp, 104dp, 120dp), which is why the switch
changed nothing on a phone.

**Poster Size and the two landscape switches are device-local (2026-10-20).**
`poster_size`, `home_landscape_cards` and `landscape_posters` are screen
geometry, not identity: the right tile size and card shape depend on the screen,
so a 65" TV and a 6.7" phone no longer overwrite each other. They are out of
`PrefsPayloadBuilder.SYNCED_PREF_KEYS`, which gates both directions - the
builder publishes only listed keys and `applyDisplayPrefs` skips any key not in
it - so a blob from a build that still carries them is ignored on receipt and
there is no migration code. Each device keeps the value already stored, and
changing one affects only that device. The setters still write the same keys
with the same defaults and still tick the local revision, so a Settings change
reshapes the tiles on that device exactly as before; what they no longer do is
enqueue a display blob. The rest of the display blob keeps syncing. Pinned by
`data/sync/DeviceLocalDisplayPrefsTest` and the absence assertion in
`SyncScopeTest`.

**The auto-play quality ceiling joined them (2026-10-20).**
`max_auto_play_quality` is what the box itself can decode and stream, not a
viewing preference for the account: a Fire TV Stick capped at 1080p must not
cap the phone on the same profile. It is out of
`PrefsPayloadBuilder.SYNCED_PREF_KEYS` and named in `EXCLUDED_PREF_KEYS`; its
setter still writes the key and ticks the local revision but no longer enqueues
a display blob, so picking a ceiling on one device leaves every other device
where it was.

**The ceiling is enforced on every auto-play path (2026-10-20).** "Set to
1080p and it is still autoplaying 4K" had three causes, all of which let a copy
the viewer excluded start by itself:

- the resolution reader knew `2160p` and `4k` only, so a `UHD` or `2160i` or
  `3840x2160` copy was read as *unlabeled* — and an unlabeled source was
  admitted by the ceiling. `StreamRanker.resolutionRankOf` now owns the tokens
  and the ranker's resolution bonus reads the same function;
- an unlabeled head was taken ahead of a source that plainly declared 1080p.
  A declared copy at or under the ceiling now wins, and an unlabeled one is
  only the fallback;
- the played-link replay (`MainActivity`'s cache hit) returned before the
  capped pick ever ran, so a link remembered from before the setting was
  lowered was replayed at its old height. It now replays under the ceiling and
  falls through to a fresh resolve when the cached list has nothing to offer.

When every source for the episode is plainly taller than the ceiling, nothing
is auto-started and the picker opens with all of them listed — which is what
the setting's own copy says it does — and the picker prints why
(`AutoPlayQuality.ceilingNotice`, the same sentence on both form factors).

## The last two gaps: onboarding and the catalog grid

Both were found by audit after Phase 2 was called done, and both are ports like
the rest - same ViewModel, same callbacks, a new file under `ui/mobile/`.

**Onboarding** was the shipped blocker: a fresh phone install landed on the TV
page, whose fixed 392dp account pane takes a phone's whole width (the three
source rows were laid out past the right edge with nothing to scroll to them)
and whose fields are the D-pad `KBTextField`, so a tap focused a field but no
soft keyboard ever opened - the user could not get past sign-in. The phone page
is one vertical scroll stacking every section in order (welcome copy, account,
Add Add-ons, Live TV, Connect Simkl, START BROWSING, the skip note) and uses the
touch text field, which is the keyboard fix. Completion still goes through
`OnboardingPrefs`, so finishing on either form factor satisfies the other.

**The catalog grid** ("Open in Grid" on a Home rail) rendered the TV D-pad grid
on every form factor: no finger scrolling, and the case it is opened from is a
rail, which on a phone is body scrolling. The phone page is a
`LazyVerticalGrid` of the touch poster/landscape cards - the shape follows the
rail's own landscape switch - over the same Home pager, hidden-title filter,
append footer and poster menu.

Both branches live at the **call site** in `MainActivity`, not inside the TV
file, which is what keeps those two TV files byte-identical; the contract test
asserts the branch order and that neither TV file gained a `FormFactor`
reference. `OnboardingAndCatalogGridMobileContractTest` pins all of it.

## Deferred inside ported screens

Each item is a real gap on a phone, not a stylistic choice.

### Home

**Finished on the phone.** The screen is arranged by the same slot builder the
TV reads (`KBHomeSlots.buildMergedEntries`), so imported KB collections, the
mirrored Browse-chip rows and the catalog rails appear in the home manager's
order (pin / reorder / hide included), and a rail's *name* comes from the same
arrangement: the two app rows (Continue Watching, Upcoming) and the
add-on-keyed rails (a Top Today row, a built catalog) resolve their section
title through `arrangement.renames` exactly as the TV screen does, so a rename
made on either device reaches this Home and clearing it restores the default.
That last part was a real gap - the phone drew its two rows from string
literals and dropped `titleOverride`, so renames showed in the builder and on
the TV and nowhere on a phone. Collections open their folder screen and
size their tiles by the folder's own `tileShape`; Browse chips open their
discover screen and can be removed from Home with a long press; the synced
landscape pref draws backdrop+clearlogo cards with their rank; spoiler-free mode
hides episode identities; a Continue Watching tap re-verifies the stored resume
root against TMDB before opening, and a long press raises the TV card's menu
(details, play manually, play from beginning, watched/unwatched, remove from the
row, hide).

**A long-press row does what its label says (2026-10-21).** The phone's Continue
Watching menu had the TV card's wording but not its wiring: its "Go to Details"
row opened the episode's *play* target, so Detail auto-played the file instead of
showing the page - and because the shared sheet derives "Play Manually" / "Play
from Beginning" from the details row, both of those resumed it as well. The menu
now carries all three rows explicitly, through one `UpNextOpen` destination
(`DETAILS` opens the page with no target, `STREAMS` goes to the picker, and
`FROM_BEGINNING` plays from 0), which is the TV card's own three-row shape. The
rest of the phone's long-press menus were checked against the same rule at the
same time - every "Go to Details" on Detail, Library, Search, the browse pages,
collections, actor pages, KB folders and the catalog grid navigates to the page
rather than playing, and the tab/channel/follow menus do what their rows say.
(`HomeScreenMobileContractTest`.)

**Long-press rows are label-only, as they are on TV.** The phone sheet used to
print each action's description under its label "because there is vertical
room"; that doubled the height of every menu for text nobody reads before
tapping, and the label already says what the row does ("Mark Entire Series as
Unwatched" needs no gloss). `PosterContextAction.description` is still carried
for the surfaces that spell a consequence out elsewhere; the sheet stops drawing
it. (`MobileTouchTwinContractTest`.)

**The Browse chips are cards, not pills.** The row of search/browse shortcuts
used to be a `LazyRow` of text pills - the category's name on an accent wash -
which said what the door was and nothing about what was behind it, on a screen
where every other row is artwork. Each chip is now the touch twin of the TV's
`BrowseShortcutTile`: the resolved backdrop (`KBHomeViewModel.browseShortcutArt`,
keyed by `BrowseHomeShortcut.chipKey()`) as a dimmed base, the legibility scrim
over it, and the brand's clearlogo walked across its candidate list - with the
chip's own name uppercased and centred when the category owns no mark (a genre,
a tag, a decade) or every candidate turned out undrawable.

**The ranked "Top ... Today" row keeps the TV's landscape treatment.** Two
pieces of it were missing on the phone: the numeral now comes from the shared
`landscapeRank(rail.ranked, index)` rule (so the two screens cannot disagree
about which rows are rankings), and a landscape card's artwork honours the
viewer's chosen still through `imageOverrideCardBackdropUrl` - including over a
pinned row's blank art marker, which is the one case that marker exists for.
The tile's size follows Poster Size like every other phone row.

The Continue Watching and Upcoming rows are drawn in the television's own
design, because the phone had a different one: the cards used to be a bare
backdrop with the show, episode and air date captioned UNDER the artwork, which
read as a different row from the one on the TV beside it. They now carry what
`CompactUpNextCard` and `UpcomingEpisodeCard` carry — the scrim, the state chip
(RESUME / NEXT UP / NEW EPISODE / NEW SEASON, or a finale flag) or the air-date
chip, the show and episode lines inside the bottom corner, the "N ep. left" and
time-left chips, and the shared resume bar along the bottom edge.

Two Home bugs of the same shape were fixed with it, and both were a pref that
no phone surface ever read:

- **The landscape rails had no clearlogo.** The rows looked the rail art up by
  the item's raw id, but that map is filed under `landscapeArtKey()` —
  `"type:id"` — so every lookup missed and the card fell back to a plain
  backdrop. The key is the same one `HomeScreen` uses.
- **"Hide the Upcoming rail" did nothing.** The switch was a Settings toggle and
  a synced pref with no reader on this screen: the row was built from the
  schedule alone. The phone now asks `getHomeRailHideUpcoming` and gates the
  row's presence in the order builder with it (`upcomingVisible`), so a hidden
  row is not drawn, is not counted as content, and does not make the
  all-hidden card lie.

When every rail is hidden the phone draws the same "All rails are hidden" card
as TV, with MANAGE RAILS opening the rail manager through the one-shot request
(see Add-ons below) - the one state where the manager is the only way forward.

**The hero slot is a billboard now (2026-10-30).** It used to render nothing at
all, and not by accident: the one hero composable the phone had was fed by the
TV's `heroMeta` flows, which are published by the focus-following resolver -
and a phone has no focus, so nothing ever drove one. The slot is now a pager of
up to ten titles (`MobileBillboard` in `ui/mobile/HomeScreenMobile.kt`), each
page the SAME `MobileHero` presentation (backdrop, TMDB clearlogo or the plain
title, meta line, two-line synopsis, tap for Details) plus Play and Details
buttons, chevrons and tappable page dots.

Its feed is the viewer's choice, made in the Home manager: any built-in row, any
add-on catalog or any built catalog, merged in the order the chips were picked,
or - by default - TMDB's trending of the day, movie and TV interleaved by rank.
Those are two new endpoints (`trending/movie/day`, `trending/tv/day`); nothing
else new is fetched. A chosen catalog that is empty contributes nothing, an
all-empty selection falls back to the day feed, and an empty day feed hides the
slot so the rails start at the top exactly as before. Items come from the rail
Home already shows (hidden titles and hide-watched applied), and each page is
resolved in `HomeViewModel` two or three at a time through the hero's own
backdrop/clearlogo ladder and published as ONE value - so page N can never show
page N-1's words over page N's art, and the pager appears on the first page that
settles while the rest fill in.

Motion: it advances every 6 s by default (3/6/10/15 s, or **Manual only**, in
the manager), pauses under a finger, in the background, and while a trailer owns
the page; a swipe, a chevron or a dot re-arms it from the page it landed on.
Trailers are the billboard's own choice (`Off` by default, `Auto-play`,
`Manual`) and reuse the existing stack and its settings - the pooled
`HeroInlineTrailerPlayer`, **Hero Trailer Delay** and **Mute Hero Trailers** -
with only the current page allowed to play, stopped by any navigation, and a
failed resolve leaving the backdrop with no error UI. Play does not resolve
anything itself: it raises `BillboardPlaySelection` and opens Detail, which
calls its own `playCurrent()` - so the autoplay/picker branch and a series'
resume or next-up choice are Detail's, once.

All four billboard prefs are DEVICE-LOCAL (`home_billboard_*`, excluded from
sync): a television has no billboard, so a phone's answers say nothing about the
account - and the TV Home hero, its trailers and its settings are untouched.
(`BillboardFeedRulesTest` covers the merge/interleave/cap and the pref storage;
`MobileBillboardContractTest` pins the slot gate, the motion keys, the handoff
and the trailer modes.)

Parity notes (not bugs to fix):
- The "verify then open" step is bounded at five seconds, exactly as on TV; a
  slow TMDB answer falls through to the stored id rather than blocking the tap.
- The TV top bar has no long-press variants to port: it is the content of the
  Up-gesture overlay, which a phone does not have (the mobile bar keeps the
  profile/avatar, search, Live TV, Library and Settings entries).

### Detail

**Finished on the phone.** The page loads its own ViewModel (`load` with the
route's `initialMeta`, resume season and `initialTarget`), so a Continue
Watching / Up Next open auto-plays once metadata (and a movie's resume row)
lands, exactly as the TV deep link does. The clearlogo ladder is the TV's
(TMDB's English mark, else the plain title - the add-on logo is deliberately
not a fallback), and the resolved logo and widescreen backdrop are handed up to
the pre-playback cover splash.

The Media row is the shared projection (`DetailMediaRow.tabs/trailers/backdrops/
posters`): video cards play through the shared launcher (`DetailTrailerLaunch`,
which the TV's own intent builder now delegates to), artwork opens a fullscreen
viewer, an unplayable video says UNAVAILABLE, and a long press offers the same
surface rows (`ImageOverrides.choose/reset`). Reviews (page 1 plus the
background pages the ViewModel fetched) open full with a spoiler gate, and the
collection rail opens each part by its resolved IMDb id.

**The Play button says what the press does, and the pairs are unpadded
(2026-10-21).** A film with a saved position offered "Play" for a press that
resumed it, and a paused episode offered "Play S2E3" for the same reason: the
label was a fixed string written by whoever last touched the page. It is now
derived in the ViewModel (`publishPlayButtonLabel` -> `detailPlayLabel`) from
the resume row, the loaded season and its target episode, so it reads
`Resume` / `Play` for a film and `Resume S2 E3` / `Play S2 E3` for a series, and
it moves as those do - a finished film goes back to `Play` without a reload.
Both Detail screens read the same state flow, so the phone and the TV cannot
promise different things about one press. The pair is also printed unpadded
everywhere it is a glance rather than a column: the Continue Watching hero and
cards (`upNextEpisodeLabel`) read `S2 E8`, not `S02 · E08`. The phone's episode
cards also tick from the season's watched set now (`watchedInSeason`), the same
set the menus mark into and the trackers fill - checking only completed stream
ids is why an episode marked watched (or synced from Simkl/Trakt/MDBList)
showed no checkmark until the page was reloaded.

**The reviews sit last before the Media row (2026-10-21)**, on the phone and on
TV alike: under the collection and More Like This, above Media. The rails that
lead to the next title come first, and the reviews - an addendum to a title the
viewer has already decided about - follow them. The TV page's focus chain
(`DetailRow`) is declared in that same painted order, so UP out of the review
cards is More Like This and DOWN is the Media row, and the phone is pinned to
the same order by `DetailScreenMobileContractTest`.

**The hero trailer plays in the hero (2026-10-20).** The page's TRAILER button
used to hand the resolved YouTube source to `NativePlayerActivity`, so a press
left the page for the fullscreen player. It now plays in the Detail hero
itself, through the TV Home hero's own renderer (`HeroInlineTrailerPlayer` in
`ui/home/HomeScreen.kt`, now `internal`): the same pooled ExoPlayer, the same
`YoutubeChunkedDataSourceFactory` (chunked googlevideo requests with the
signing client's UA), the same adaptive video+audio merge, the same start and
re-buffer watchdogs, and the same one-shot retry from where a mid-stream
failure died. A second implementation of that stack is how the two would drift,
which is why it is shared rather than copied.

It follows the trailer settings exactly as Home's hero does: **Hero Trailer
Autoplay** decides whether it starts on its own (after **Hero Trailer Delay**,
with `Now` = immediately), and **Mute Hero Trailers** decides whether it is
heard. All three are read through `PrefsPayloadApplier.revision`, so a toggle
made on another device lands here. With autoplay off the backdrop stays up and
the button still plays the trailer in the hero; a second press - or the hero's
close mark - puts the backdrop back and releases the pooled player, and leaving
the page releases it too. Media-row video cards are unchanged: they open the
fullscreen player, because they are the individual videos the viewer picked.
(`DetailScreenMobileContractTest` pins the wiring; `HeroTrailerResumeTest`
already pins the retry rule the hero reuses.)

**The phone's hero trailer never actually resolved (2026-10-21).** Reported as
"trailers aren't autoplaying or following the trailer autoplay setting in hero
on details on mobile". The settings were read correctly; the resolve was
cancelled. The page armed playback with a `trailerRequested` flag that the
resolve effect both was keyed on and cleared, and clearing a `LaunchedEffect`
key recomposes with a new one - which CANCELS the coroutine that just started,
a frame into its network resolve. So every autoplay (and every TRAILER press)
was thrown away and the hero stayed on the backdrop. The dwell and the resolve
are now one effect keyed on `trailerRequest`, a counter nothing inside the
effect writes, which is the shape Home's hero already used; a retry also
invalidates the cached signed URL before re-resolving, as Home does.
(`DetailScreenMobileContractTest` now asserts the effect's keys, that the dwell
lives in the same effect, and that nothing assigns the request counter.)

Long-press menus cover the episode rows (watched toggle, previous, season,
entire series, play from beginning, play manually), the season chips (season,
entire series, View on Simkl) and the collection / More Like This posters (go to
details, watched toggle, add to library, hide). Spoiler-free mode hides an
unstarted episode's name on the row and in the menu, and an unaired episode in a
trusted season is marked UNAVAILABLE.

**The title's own page is one press from the Library (2026-10-27).** Both Detail
pages carry a plus/minus toggle in the action row, right after the trailer: `+`
saves the title this page is about and becomes `−` once it is saved, and the same
press takes it back off (`LibraryAdds.toggle` -> `LibraryMirror
.removeFromLibrary`, the Library tab's own remove, so every connected tracker
drops its watchlist copy rather than re-merging the title back). A hold raises
the shared title menu, which is where `Add to list…` lives. The mark is painted
from the store on `LibraryAdds.revision`, not from the press that preceded it,
so an add made through the menu - which resolves an IMDb id first, long after
that menu is gone - still flips the button, and a failed add cannot paint a
save. The phone's old full-width `ADD TO LIBRARY` row is gone: it could only add,
and the toggle does both. (`DetailAuditContractTest`.)

Both field reports from 2026-10-20 are closed here. A cast tile draws the
character under the actor's name, in the TV card's own slot and type
(`KBTextLo`, `labelSmall`, one line, centred) - the tile was reading
`member.name` alone, so a phone showed a row of names and nothing about who
they played.

A season can also be browsed sideways. **Settings → Posters & Browsing →
Episode layout** offers **List** (the default, vertical rows) and **Rail**; the
choice is no longer a control on the Detail page. RAIL draws the television's
episode cards through the shared touch twin `ui/components/touch/
KBEpisodeTouch.kt` (`KBEpisodeRailTouch` / `KBEpisodeRailCardTouch`): the TV
card's 260x170 frame and gradient, the caption over the still, the episode
badge, the app's watched marker, resume bar, UNAVAILABLE mark, spoiler-free
mask, and the TV card's bottom line - runtime (`42m`, or `12m left` while part-watched),
the yellow IMDb chip with the episode's score, and the air date (formatted by
the TV page's own `formatEpisodeAirDate`, so one date cannot print two ways). The vertical row draws the
same runtime rule and the same `EpisodeRatingChip`, and suppresses the score on
an unaired episode for the TV card's reason (early votes read as a real score).
Both layouts read ONE `episodeVisual` derivation (watched, progress, unaired,
hidden title), so the two shapes cannot disagree about an episode, and both
call the same `openEpisode` and raise the same `EpisodeMenuTarget`. The choice
is the `detail_episode_rail` pref: device-local screen geometry like Poster
Size - nothing published to the sync blob, and the TV page does not read it
(`DeviceLocalDisplayPrefsTest`, `SyncScopeTest`,
`DetailEpisodeRailMobileContractTest`). The Detail page collects
`PrefsPayloadApplier.revision`, so a Settings change repaints it even when it
is still on the back stack.

**Watched episodes wear the app's marker (2026-10-20).** Both phone episode
layouts used to hand-roll a tick: a small gold check on a plain scrim, at one
size on the rail card and another on the row's thumbnail, matching no other
surface. Both now draw `WatchedCheckBadge` - the disc, gold ring and glyph every
poster tile, Home rail and TV episode card already use - so "watched" is one
marker in one language. The row's 132x74 thumbnail asks the same badge for an
18dp diameter (the parameter is new; 26dp remains the default everywhere else)
rather than for a different shape, and neither layout keeps a tick of its own
(`DetailEpisodeRailMobileContractTest`).

**Detail's poster captions and ratings (2026-10-20).** The collection and More
Like This rails were the two phone surfaces still drawing a bare poster: their
title/year/rating toggles looked broken because the shared
`TouchPosterCaptions` block was never added. Both now draw it under the card,
with the tile width from `rememberPosterTileWidth` so a landscape card's
caption follows its width. The page also collects
`DetailViewModel.mdbListRatings` and draws `MobileRatingsStrip` under the
overview - the same `mdbListRatingSources` order and brand colours as the TV
RATINGS row, with TMDB's own score as the fallback chip - so an MDBList
account sees its IMDb/RT/Metacritic/Trakt/Letterboxd/MAL scores on the phone
too, not only on the television.

That strip is **one line** (2026-10-20). Seven chips wrapped onto a second row
under the synopsis, where the stray chips read as more body text; the phone now
takes the head of the shared list through `mobileRatingSources`
(`MOBILE_RATING_SOURCE_LIMIT` = 4: IMDb, Rotten Tomatoes, TMDB, Metacritic,
which is the order the list already ranks them in), and the row scrolls
sideways instead of wrapping when a large font scale widens four chips past a
narrow screen. An absent source promotes the next one rather than leaving a
gap, and the TV page still draws all seven - it has the width.
(`MdbListRatingChipsTest`, `DetailScreenMobileContractTest`.)

**Networks, Production and the details block (2026-10-20).** The phone page had
neither of the TV's company rails and no facts block, so a show's channel, a
film's studios, its certification, origin and run were all unreachable here. It
now draws **Networks** (series) and **Production** with
`ui/components/touch/TouchStudioChip.kt`, the touch twin of `StudioChip`: the TV
chip's white 120x54 plate, the company's TMDB mark fitted inside it (the name
when TMDB sent no logo), and a tap opening that company's Studio page through
the same `onNavigateStudio(id, name, isNetwork)` the TV chips call. Below them,
**Movie Details** / **Show Details** is the rows block: Status (series only),
Release Info, Runtime, Certification, Origin Country, each a label/value row
with a hairline between. The rules live in `ui/mobile/MobileDetailFacts.kt` and
read the TV meta line's own helpers (`certification`, `releaseYear`,
`displayRuntimeMinutes`, and now-internal `formatSeriesYearRange`), so the two
layouts cannot disagree about a span or a rating; a row TMDB cannot fill is
dropped rather than printed as a dash, and Origin Country is country names,
capped at three. (`MobileDetailFactsTest`, `DetailScreenMobileContractTest`.)

Parity notes (not bugs to fix):
- The season mark/unmark arithmetic lives in `ui/mobile/MobileDetailRules.kt`
  (pinned by `MobileDetailRulesTest`), because the TV page keeps its inline
  copies: no unaired episode is included, a season whose row disagrees with the
  show-level pointer is left whole, and the loaded season uses its resolved
  listing rather than TMDB's count.
- The TV's long press on the Play button is a HOLD on the phone's Play button:
  it opens the streams picker for the episode a tap would start, marked as a
  manual pick (`ManualSourceSelection`) so Auto-select cannot jump past the
  list. "Play from Beginning" stays the explicit START OVER button beside the
  resume action - the half of the TV menu a phone should not hide behind a
  gesture.
- The TV page's genre and keyword chips are drawn on the Detail page, as
  `TouchLinkChip` rails under the calls to action: a genre chip and a
  `#`-prefixed keyword chip each open the Tag page for that id, through the
  route's own `onNavigateTag` and with the TV chip's arguments. They moved the
  genres out of the meta line - the same two genres as text that went nowhere,
  directly beside the chips that do, is worse than either alone. The network
  and production-company chips are drawn too (see the section above), and both
  open the ported Studio page.

### Search

**Finished on the phone.** The query path is the screen's own (per-keystroke
search, Done commits and records the recent search, the one-shot `SearchSeed`
is consumed on entry, and a fresh blank visit opens the keyboard while a return
to existing results does not). Suggestions and recents are chip rows, with
CLEAR RECENT where the TV has it.

The browse browser is the full one: six category tabs, the active category's
blurb and count, the filter field wherever a category is big enough
(`BROWSE_FILTER_MIN_ENTRIES`), "Surprise me", and a tappable chip grid. A chip
opens its discover screen; a long press offers Add to / Remove from Home and
Hide; a category tab's long press brings back the chips hidden under it. All of
it goes through the same `SearchViewModel` and the same pure browse rules
(`BrowseRules.kt`, `BrowseChipVisibility.kt`) the TV browser uses.

The result rails are the TV's - Titles, one per add-on source honouring the two
synced rail-label toggles, Actors, Studios, Collections - with the same watched
and partial badges (`watchedTile` / `partialWatchedTile`) and the same
title long-press menu (details, watched toggle, library add, hide). Hidden
titles drop out of both the TMDB and add-on rails as they do on TV, and the
empty state counts the raw matches so a fully-hidden result set does not read as
"no matches".

Parity notes (not bugs to fix):
- The TV's focus plumbing has no phone twin: the requester registries, the
  `successorIndexAfter` hand-offs and every return-focus loop exist to keep a
  D-pad off a dying node, and a touch screen has no focus to strand.
- The phone shares the hoisted `LazyListState` with the TV column (only one is
  ever composed), so Back from a result still lands where the viewer left it -
  **and keeps the tile highlighted**, which the hoisted scroll alone did not
  do: the screen swap disposes every tile's requester, so `SearchReturnFocus`
  (one process-scoped record: tile key, rail, index) restores the rail's
  offset and hands the focus back to the tile that was pressed.

### Library

**Finished on the phone.** One vertical grid with full-span header rows: the
title and connection line, the filter strip (ALL / MY LIST / WATCHLIST /
PERSONAL LISTS), the UNWATCHED toggle, the four sort fields with the selected
one carrying its ▲/▼ direction, then the posters. Every filter reads the same
activity-scoped `LibraryViewModel`; hidden titles drop out of all four lists as
they do on TV; each filter keeps its own empty sentence (the watchlist's comes
from the same `libraryWatchlistEmptyText` the TV pane calls - see below).

The personal-lists pane becomes a list of lists above the selected list's grid
(count and source per row), with the inline "+ NEW LIST" field and the
long-press delete behind a confirmation dialog - the press never deletes by
itself, and only a local list (negative id) offers it. A tap records the same
`BrowseReturnTarget` the TV grid records, so backing out of Detail reopens on
the pressed poster. A row with no ids reports that it has no detail page
instead of swallowing the tap, and the row menu is the TV menu per source
(details, add to list, hide, remove from this list / the library, or the Simkl
attribution link and "Managed on Simkl" row).

Parity notes (not bugs to fix):
- The TV page's side-by-side list rail and grid become one column: a phone has
  no room for a fixed 280dp rail beside a poster grid.
- The D-pad plumbing has no phone twin: per-card focus requesters, the
  successor hand-off and the return-focus loop exist to keep a remote's focus
  off a removed cell, and hiding a card just removes it here.

**The watchlist says what actually happened (2026-10-27).** Both panes now draw
one sentence from `libraryWatchlistEmptyText` (in `LibraryViewModel.kt`): the
local half clearing `loading` first no longer makes a remote fetch still in
flight read as "Watchlist is empty." (`LibraryUiState.remoteLoading` gates the
empty copy), and a tracker whose read FAILED no longer reads either as empty or
as "not connected" - it names the service as unreachable
(`LibraryUiState.failedRemotes`), with the never-connected services listed
after it. The phone had its own copy of this string; the two can no longer
drift. The TV grid's scroll state is also keyed on the open personal list beside
the filter, so opening list B starts at B's top rather than list A's offset, and
the TV long-press menu's "Go to Details" records the `BrowseReturnTarget` the
poster press records - which the phone's menu already did.

### Browse pages (Tag, Studio, Decade)

**Finished on the phone.** One shared touch page
(`ui/mobile/BrowseSectionsMobile.kt`) behind three thin wrappers, each on its
own TV ViewModel (`TagViewModel`, `StudioViewModel`, `DecadeViewModel`). Each
wrapper keeps its route's header: the genre/keyword title, the studio's brand
mark plus its streaming-service/network/company wording and location line, or
the decade name. Studio and Decade keep the genre chip row (the chips call the
view model's own `onGenreSelected`, so a chip re-runs the rails with the genre
ANDed onto the page's base), and Tag keeps its `isKeyword` / media-type route
arguments.

The shared page keeps the TV rules below the header: hidden titles drop out of
every rail (and a rail that empties drops out), watched/partial badges resolve
through the IMDb key with the TMDB `tmdb:<id>` fallback and the checkmark wins
over the eye, and the long-press sheet offers the shared library add, hide,
Go to Details and watched toggle. A card tap records the same
`BrowseReturnTarget` the TV rail records, and a return puts the page back on
that card in the two steps the shape needs: the outer list onto the recorded
rail (`revealBrowseReturnRail`, the shared TV walk), then that rail's own row
onto the card.

**The shape is the TV's: a vertical list of horizontal rails.** It was one
vertical grid, and that was the wrong shape for a page of rails - a grid lays a
rail's cards out as ROWS, so the next rail (the next genre, the next studio) sat
below a screenful of the current one's posters, and reading on meant scrolling
past cards already seen. Each rail is now its own `LazyRow` under its own
title, so the next rail is one swipe away. A rail's items land at a fixed tile
width (`landscapeTileWidth` when the global landscape switch is on), and the
caption under a tile follows that width.

Parity notes (not bugs to fix):
- Paging is explicit: a rail with another page ends its band with a LOAD MORE
tile, at the end of the row (LOADING… while the page is in flight, and the
rail's heading says so too). The TV page triggers the next page from a scroll
delta on the rail's own row.
- The TV focus restore (rail scrolled, focus put back on the card) becomes two
  scrolls - outer list, then row: a phone has no focus ring to land on the
  poster.
- The studio header reuses the TV `BrandLogo`, so the brand-mark treatment
  (whiten / as-is / unusable, with its candidate walk) is not duplicated.

### Collection

**Finished on the phone.** Same `CollectionViewModel`, release-ordered parts,
hidden-film filter, watched arithmetic (resolved IMDb key first, TMDB key
second), and the same two-row part menu (Go to Details, watched toggle). The
header keeps the collection's name, count line, overview, and the same artwork
chain as TV: the chosen still for this screen, then the all-screens still, then
TMDB's own; the chosen poster is drawn on the header card too. A tap records
`collectionBrowseScreenKey`, so backing out of a film's Detail page reopens the
collection scrolled to that film.

Parity notes (not bugs to fix):
- The TV page is a fixed header over one horizontal rail (a collection is two to
  five films). The phone keeps the header and lays the films out as a grid, and
  the header's poster is decoration in both (it took no press on TV).

### Actor

**Finished on the phone.** Same `ActorViewModel`: the same `load(actorId)`, the
same sorted filmography (`sortedCredits`, so the popularity order and the
talk-show filter are not re-derived), the same resolved-id map and watched
sets, and the same `resolveAndNavigate` / mark-as-watched calls. Hidden titles
leave both rails through the shared filter, with the resolved IMDb id in the
tuple so a title hidden from Home or the Library leaves the filmography too.

The header keeps the TV page's pieces: the still (through the same override
chain, ending at the top work's backdrop), the portrait or the person's
initials, the name, the `metaLine` facts and the "Known for" line. The
biography is four lines with a VIEW MORE / VIEW LESS toggle, exactly the TV
toggle's content.

Credits carry the TV badges (checkmark wins over the eye) resolved through the
IMDb key with the credit's own `tmdb:<id>` fallback, and the long-press sheet
has the TV rows: Go to Details (raising the same return record first), the
watched toggle, the library add and Hide everywhere. A tap records
`actorBrowseScreenKey` with the TV's own rail keys (`moviesrow` / `tvrow`) and
the credit id, so backing out of a title's Detail page reopens the filmography
scrolled to that card.

Parity notes (not bugs to fix):
- The two TV rails become titled bands in one vertical grid: a phone has no
  room for two horizontal credit rows plus a pinned header, and the grid puts
  the whole filmography behind one thumb scroll.
- The return lands by scrolling to the card; there is no focus ring to put back
  on a phone. The TV page's `BringIntoViewSpec`, per-card requesters and
  landing-budget loop have no touch twin.

### Add-ons

**Finished on the phone.** The same activity-scoped `AddonsViewModel` drives a
single list of add-ons with a detail bottom sheet, and every management action
the TV page offers is there: order up/down, rename, open the manifest in a
browser, soft enable/disable, refresh one manifest, refresh all, check health
(the ONLINE/OFFLINE badge, keyed by add-on id exactly as on TV), remove behind
a confirmation, and copy add-ons from another profile.

The list rule is the TV page's own shared helper (`distinctAddonRows`), so the
phone cannot grow a second answer to the duplicate-manifest case that used to
crash the list with a duplicate key. The filter matches display name, manifest
id or resource as it does on TV, and the empty state offers the same ADD.

The ADD dialog keeps the manifest URL field with PASTE and the same two
quick-add presets, and cancelling clears the draft. Copy-from-profile defaults
to the whole source list, toggles rows per manifest URL, skips anything already
installed (the importer's own rule) and re-reads the active profile so the
copied rows appear immediately. Banners are tappable to dismiss, as on TV.

The Home-rail catalog manager lives here too, in `ui/mobile/HomeManagerDialogMobile.kt`.
It is the TV dialog's own row builder (`homeRailManagerRows`) under a phone
frame, so a phone lists every rail Home draws - built-ins, imported collections,
the Browse-chip rows, hand-built catalogs and add-on catalogs - in the merged
order, with the same HIDE/SHOW, PIN, four reorder stops and RENAME actions, and
the same "would this press move it?" answers. Reorders write the same
`KBHomeOrderPrefs` arrangement Home renders, so a move is instant on both
screens, and the collections import block (URL, PASTE, FILE via the system
document picker, remove behind a confirmation) is ported with it. It opens from
Settings' Collections pane, and from Home's "every rail is hidden" card through
the same one-shot `HomeRailManagerRequest` the TV uses.

Deferred on purpose:
- **SEND FROM PHONE** (the LAN pairing server and its QR code). It exists so a
  TV can receive a URL pasted on a phone; on a phone the field is already beside
  the keyboard and has PASTE, so it would be a phone asking a phone for a URL.

Parity notes (not bugs to fix):
- The TV master-detail pair (list pane plus pinned action pane) becomes the list
  page plus a bottom sheet: a phone has no room for both panes, and the sheet is
  where the actions the D-pad pane pinned are now reached.

### Catalogs

**Finished on the phone**, on the same activity-scoped `CatalogBuilderViewModel`
and the same rule vocabulary: every control writes the one `KBFilters` object
the imported collection profiles use and `TmdbRepository.discoverKB` consumes.
The TV page's own helpers - the option lists, `catalogOptionsWithCustom`,
`voteCountOptionsWithCustom`, `parseCustomIds`, `parseVoteCountInput` and the
summary line - are imported rather than re-derived, so a phone catalog and a TV
catalog mean exactly the same thing.

The shape is the TV page's IA at phone scale. The **list** is the page: status
pill (ON HOME / HIDDEN), summary and genre line, tap-to-edit, and SHOW or HIDE,
UP, DOWN and DELETE per catalog (DELETE behind a confirmation; the catalog
ceiling is stated in place). **NEW CATALOG** opens the editor as a pushed page,
where BACK returns to the form's list rather than leaving the screen.

The **editor** carries every rule the TV editor carries - type, sort, runtime
min/max, genres and excluded genres, services and excluded services, watch
region, studios and excluded studios, networks and excluded networks, TV status
and show type (series only), keywords (match / exclude with TMDB search and
chips), cast (movies only, for the endpoint's own reason), decade, released
after/before, release type (movies only), original language, origin country,
age rating (country scale plus the exact value and the "or milder" ceiling),
minimum and maximum rating, and minimum votes - with the same hand-typed id and
vote-count dialogs behind "+ OTHER ID" and "+ CUSTOM". Picked values are drawn
back as removable chips, so a rule set by chip or by number can be seen and
taken back.

The **preview** is pinned above the form, exactly as on TV: a PREVIEW press has
to change something visible, and a phone's editor is long enough that a preview
at the bottom of the scroll would read as a dead button. Long chip rows wrap
(`FlowRow`) rather than scroll sideways, which is the phone-native form of the
TV page's chip rows.

Parity notes (not bugs to fix):
- Chips wrap instead of scrolling: a horizontal chip strip on a phone hides
  options behind a gesture nobody performs, and the wrap keeps the picked state
  of a long section visible while the form scrolls.

**What these screens stopped over-promising (2026-10-27).** The add-ons /
Catalog Builder / Home manager deep dive's fixes, all of them shared by both
form factors because every one lives below the screens:

- **A blob this build cannot read is no longer a write source.** Both stores had
the same hole in different degrees. `AddonManager` already refused to OVERWRITE
an unparseable add-on blob on the read path, but the fallback factory list stayed
in memory as the base every mutator computed from — one toggle, rename, reorder,
remove or manifest refresh in that session wrote the defaults over the preserved
blob, and a user-edit save could publish the wipe. The load now reports the
fallback and `saveInstalledAddons` refuses while it is armed
(`servingUnreadableBlob` — its own flag, because the profile id alongside it is
null in a session with no profile yet, where the refusal is just as necessary):
a sibling profile's unreadable blob does not arm it, and any load of the active
profile re-answers the question (so a cloud pull that adopts a readable set
repairs the profile). `CustomCatalogStore` had the weaker version — a silent
empty list — so a corrupt catalog blob plus ONE new catalog wiped every built
catalog; the parse failure is now logged once per bad payload, `isBlobUnreadable`
says which empty it is, and `save` refuses instead of replacing the blob.
- **A manifest refresh re-answers the health badge.** Health was re-checked on
add/remove/enable only, so a viewer could REFRESH ALL a dead add-on, fix it, and
watch the row keep OFFLINE — the screen contradicting the fix it had just made.
Both refresh paths now record what their own fetches answered, which is also why
they do not call `checkHealth()`: that would re-fetch every manifest a second
time for an answer already in hand.
- **The catalog cap is reported, not faked.** `save` caps and `upsert` appends,
so at 100 catalogs the one just built was exactly the one dropped while the
builder said “added to Home”. The message now comes from the stored list, says
“Not saved — 100 is the catalog limit”, and the draft stays open so the rules
just set are not lost.
- **The builder's move arrows say what they order.** They move the builder's list
only; Home draws rails from the home arrangement, which stops following that
list the moment the manager moves a rail. Both lists carry that sentence
(“The arrows set this list's order. Where a rail sits on Home is set in the home
manager.”), the TV arrows' descriptions say “in this list”, and the phone's bare
UP/DOWN became MOVE UP/MOVE DOWN.
- **Keyword and cast search wait for the typing to stop.** The editor called the
view model on every keystroke, so “time travel” was seven TMDB requests; a 300 ms
wait inside the cancelled job (the search screen's own rule) sends one per burst,
and a pick cancels a search still waiting so the suggestion row cannot re-open
over it.
- **SHOW ALL names its scope.** In the home manager it only ever toggled add-on
catalogs, under a header promising the rail list — so hiding one builtin and one
catalog and pressing SHOW ALL left the builtin hidden. The action is unchanged
(widening it would restore rails hidden on purpose); the label now reads SHOW ALL
CATALOGS / HIDE ALL CATALOGS on both dialogs.
- **Home's “Manage rails” card is gated like the other two doors.** It navigated
to the Add-ons screen (and the manager dialog) with no kids “Lock add-ons” check,
while `onOpenAddons` and `onOpenCatalogManager` both had one. Same check now, and
no manager request is left behind on a blocked press.

Deliberately not done: the ADD dialog still closes before the add resolves (a
failure surfaces in the screen's banner and the URL must be retyped), and the
builder still shows no rail POSITION — only ON HOME / HIDDEN. Both are noted in
the dive as improvements rather than bugs.

### Sports hub

**Finished on the phone**, on the same activity-scoped `SportsHubViewModel`:
the enabled-league set, the tab order, the follow set, the 30-second live poll,
the standings cache, the channel memory and the return-from-the-player record
are one set of facts for both form factors.

The screen is the TV hub's content in phone clothes. League tabs (FAVORITES
first) sit under the header with REFRESH and LEAGUES; the search field filters
the loaded sections across every enabled league through `SportsSearchRules`, so
"which tab was that game on?" is still answered from the field. The slate is one
scroll: LIVE NOW, TOURNAMENTS, UPCOMING, FINAL, with the TV's own partition and
favourites-first order (`SportsGameOrder`), the shared LIVE beat (one clock,
held while a sheet is open, still for a reduced-motion viewer), and the card's
full content - marks with letter fallbacks, rank/record, stars, possession ball,
score, situation, status, the playoff-series pill ("MIL LEADS SERIES 2-0") and
the note/broadcast chips, week/venue and the channel line with its three honest
"no channel" sentences. The series is the TV card's own line and the sheet's
"Playoff series" row, read from ESPN's `series.summary` in every team sport that
plays one.

A tap opens the card's sheet: matchup header, score, the stats block under the
game's own `hasStats` gate (win probability, comparison rows, last play), the
same venue/broadcast/round rows, leaders, backup feeds, and WATCH pinned below
the scrolling middle - with the disabled button carrying
`SportsDetailRules.watchLabel`'s reason. A long press opens the follow dialog
for the game's two teams. Standings are the league's table, one column (a phone
has no 900dp for the TV's two-conference body), still followable per row and
still showing the TV's own figures - W/L/T/PCT/GB/STRK, without the T column on
a league that cannot tie. A long table (college) is narrowed to one conference
by a chip row over it, ALL first, from the same `SportsStandingsLayout` rule the
TV uses. The leagues panel (toggle, ▲▼
order, game reminders, forget channel memory) and the follow dialog are ordinary
dialogs.

Playback is untouched: a card and a sheet hand their feeds to the same
`onPlayChannels` the TV passes, so the guide's player, the profile's kids filter
and the Back-to-sheet reopen all stay exactly as they were.

Parity notes (not bugs to fix):
- One column, not two: a phone cannot hold two game cards side by side, and a
  LIVE card was already full-width on TV.
- The D-pad's focus-restore and scroll fallback have no touch twin; the scroll
  position is still remembered across the player through the ViewModel, and a
  tab switch starts at the new league's top (`listState.scrollToItem(0)` on a
  path change - one list state is shared by every tab, so without it the new
  slat opened at the old league's offset). A fresh entry with live games in
  hand also starts at the top: the remembered offset is stale when the slate
  is in play.
- The standings columns collapse onto two lines per row (the counts, then
  PCT/GB/STRK) rather than being dropped - the table says no less than the TV's.

### Settings

**Finished on the phone**, on the TV page's own pane IA: the phone keeps
`SettingsPane` / `SettingsGroup` (labels, order and blurbs), shows the grouped
list as the page, and pushes one pane at a time. BACK (the system gesture or the
on-screen button) returns to the list first, then leaves Settings.

**Downloads is a section of its own** at the top of that list, not one of the
panes: it is a phone feature, and `SettingsPane` is the television's own pane
inventory, so it is not a member of that enum. Its page is the queue (one row
per download, play/pause/retry/delete) plus the two switches that govern it
(download over cellular, delete after finishing) - the switches used to be the
last block of the Playback pane.

Ported panes and what they write:

- **Integrations** — Profiles, Add-ons (hidden when the active profile locks
  them) and Viewing stats; a **Trackers** section holding Simkl, Trakt, the
  MDBList key card (with VERIFY) and Resume Source, in that order, between
  Profiles & Accounts and API Keys; then the OMDb, OpenSubtitles and TorBox key
  cards, auto-fetch subtitles and TorBox library sync. Key fields are not
  composed at all on a kids profile, as on TV - the MDBList card in its new
  section keeps that, while Resume Source is a behaviour pref and stays usable
  on a kids profile. The TV pane has the same Trackers section in the same
  order (`ui/settings/TrackersSectionContractTest`).
- **Collections** — Home / Collections and the catalog builder, both through the
  screen's own navigation callbacks.
- **Hidden Titles** — the store's entries with SHOW per row and "Show everything
  again", loaded on entry.
- **Playback** — engine (ExoPlayer / MPV), anime-in-MPV, the Dolby Vision mode,
  P5 → 8.1, HDR10+ strip, frame-rate match, auto-select and ranking, the quality
  ceiling, auto-play next, next-episode popup, Because You Watched, the three
  binge-group switches, still-there, auto-skip intro/credits, and the audio
  decoder / output / downmix choices (from `PlayerAudioTuning`'s own option
  lists).
- **Video & Audio** — network buffer, tunneled playback, picture-in-picture,
  default aspect ratio.
- **Language** — preferred audio and subtitle language from the player's own
  language list.
- **Subtitles** — mode, size, background, position, Clean SDH.
- **Interface** — notification toggles, AMOLED / pure-black theme, hero trailer
  autoplay and mute, 24-hour clock, Home and Search rail labels, the Upcoming
  rail, poster captions, spoiler-free, poster size / border / edge, landscape
  cards and posters, the partial-watch badge, English-only browse.
- **Data & Backup** — backup export and import (SAF, with the TV page's
  no-picker message; a picked file waits behind the same "Import this
  backup?" dialog the TV page shows — see below), Viewing stats, and Clear
  Continue Watching behind a confirmation that clears the cloud half first and
  then the local rows and watched markers, in the TV page's order.
- **Viewing stats** — the phone screen (`ui/mobile/StatsScreenMobile.kt`), on
  the same `StatsViewModel`: finished titles / episodes / movies and finished
  runtime, the streak, and the top-shows table with the TV's wording. The four
  headline plates stack two by two - one row of four leaves about 80dp per
  number on a phone - and nothing is pressable but BACK, matching the TV plates
  that deliberately take no focus.
- **About** — version, the in-app updater row (debug/Play gate included) and the
  last release's changelog, plus the TMDB and Simkl notices.
- **Sync** — the phone pane reports the same `SupabaseSync` health the TV pane
  does: account, active profile, last pull/push (through the TV page's own
  `syncRelativeTime`), pending uploads, realtime channel state, local row counts
  and the cleanup's last sweep, followed by Force full resync, the two-tap
  eye-badge clear and COPY DIAGNOSTICS. A phone has no rail for the TV pane's
  status dot, so the rows are the whole report.
- **Numeric choices** — the next-episode and Because-You-Watched end-panel
  points (quick jumps plus the 0.5 fine step), the still-there episode count and
  the hero-trailer delay are chip rows instead of D-pad steppers. The option
  lists, labels, stepping rule and units all come from `AppPreferences`, so a
  chip picked on a phone stores exactly what the TV stepper would.
- **Audio tuning** — dialogue boost as a level (the same
  `PlayerAudioTuning.dialogueLevelText` wording, so the number on screen is the
  value being heard) and volume boost over `VOLUME_OPTIONS`, both writing the
  same device-local prefs the TV rows write.
- **External player** — the engine pill (hidden on a kids profile, and a stored
  External choice falls back to ExoPlayer, the C8 rule), the detected-player
  chips, the hand-picked-app picker over every installed app, the remembered-
  player line, Ask every time and Treat coming back as the end. Same
  `ExternalPlayer.installed` / `allApps` probes and the same prefs as TV, so a
  phone and a TV agree on which app gets a title.
- **MPV & badges** — the MPV video-output choice (Auto / GPU / MediaCodec /
  Vulkan) behind the TV page's own `PlayerEngine.isMpvAvailable()` gate, and the
  stream-badge pack URL with PASTE, IMPORT, REMOVE and the live
  imported-state line (plus the Badges-above-the-file-name toggle). Both write
  the same prefs and use the same `StreamBadgeEngine` calls as TV.
- **Accent mixer** — the same `KBAccentPalette` grid and the same synced accent
  index, with the custom colour mixed on three touch sliders over the theme's
  own `hsvFromArgb` / `argbFromHsv` maths. A slider writes the pref on release
  (the TV writes per key step; a drag would be hundreds of writes), so the
  colour that lands is the colour the TV bars would produce from the same
  values.

Every synced pref read is re-seeded on `PrefsPayloadApplier.revision`, so a
change made on another device reaches a Settings page that is already open.
Every DEVICE-LOCAL row (engine, decoder, buffer, PiP, aspect ratio, the key
fields, the badge pack URL, Who's Watching on launch) is keyed on
`rememberProfileRowKey()` instead — the active profile id. Those two keys are
the whole rule: the revision is what the ACCOUNT changed, the profile id is
what THIS box's own store changed, and a device-local row must never be keyed
on the revision (a push would then select a decoder this phone may not have).

**Settings answers before it acts (2026-10-27).** The settings deep dive's
confirmed findings, fixed on both screens:

- **Import asks first.** Picking a backup file used to apply it on the spot;
  import clears this profile's history and watched marks before restoring the
  file's, replaces the prefs behind every row, and takes the file's add-ons when
  it carries them. It now waits behind a dialog that names all of that, says it
  cannot be undone, and offers EXPORT FIRST (the answer for a wrong pick). TV
  puts CANCEL in focus, as its clear-history dialog does; the phone's Import
  button is the only path to the apply. The file's own gates (newer version,
  missing history) still run after the answer, and the status line is unchanged.
- **Device-local rows follow the profile.** `rememberProfileRowKey()`
  (`ui/settings/ProfileRowKey.kt`) is the shared key; see the paragraph above.
  The phone's API-key card re-seeds on the stored value its caller passes, so a
  switch cannot leave the previous profile's key in the field, and the badge
  pack URL is keyed on the active profile id.
- **Force full resync reports its outcome.** `SupabaseSync.forceFullResync`
  takes a result callback and answers every press — `resynced — …`, `resync
  failed — …`, or `resync skipped — …` for the two early returns (signed out,
  already running). A pass in which no table answered is a FAILURE, not a
  success: the pull/push timestamps only move when something was actually
  attempted, so the outcome compares them either side of the run. Both screens
  draw the line under the button in COPY DIAGNOSTICS's shape.
- **The phone remembers its pane.** Settings reopens on the last pane read,
  through the same `SettingsPane.fromStored` fallback the TV rail uses, so a
  pane name written by a build that has since dropped one cannot take out the
  screen. Back from a pane still returns to the list (and Downloads is still a
  row there, not a pane); the stored name is not cleared on the way, because it
  records which pane was last open, not that the list is where the screen
  should start.
- **"Show everything again" says it is one-way.** No dialog — nothing is lost —
  but the row now states that hiding them again is one press per title.

Still open from that dive, deliberately: mobile has no settings SEARCH (TV's
index, results and scroll-to-row are a parity build of their own), and the
search contract is still one-directional (index → screen; a row added to the
screen but never declared in the index is silently unsearchable). Neither is a
bug claim, and `SettingsDeepDiveContractTest` pins the five fixes above.

Nothing else is deferred in Settings: the only remaining differences are the
TV-only surfaces listed below, whose prefs are left untouched.

TV-only, deliberately not drawn (a different list from the deferred one):
- **Match Content Frame Rate** - switches the HDMI panel's display mode and
  blanks the screen to do it. A phone has no panel mode to switch, so offering
  the row could only ever be a no-op. The stored preference is untouched, so a
  profile shared with a TV keeps the TV's own answer.
- **Frame rate diagnostics** - the readout beside it reports the HDMI panel's
  identity, the Android rate path, the last requested rate and the panel's
  answer. A phone has no panel mode to report on, so the whole card would be a
  list of "not available" rows - it stays on TV with the pref it explains.
- **Tunneled Playback** - keeps A/V in sync over the HDMI link into an AVR. A
  phone has no such link; the preference is again left exactly as it was.

Everything else that touches resolution or codecs is kept, because the phone
plays the same files: the Dolby Vision compatibility mode, P5 -> 8.1, the
HDR10+ strip, MPV / anime-in-MPV, the audio decoder / output / downmix choices,
the buffer mode, PiP and the aspect ratio.

Parity notes (not bugs to fix):
- The TV's focus plumbing (rail focus, dialog focus retries) has no touch twin;
  a pane is a pushed page and its controls are ordinary Material 3 controls.
- The Dolby Vision chips are the TV page's, word for word: Auto / None / Strip
  All, each with its own explanation underneath the selected mode. A phone does
  not get a shortened copy of that - the mode behaves the same there, and the
  only word adapted is the audience ("DV TVs" reads "DV displays").
  `DvCompatCopyMobileContractTest` holds the two screens to the same text.

### KB folder (imported collections)

**Finished on the phone.** Same `KBFolderViewModel` and route: the folder is
loaded by id, a failed load offers RETRY through the view model's own retry, and
cards open through `resolveAndNavigate` (so an un-resolved TMDB card is resolved
before its Detail page opens).

The page is the collection's merged, de-duplicated titles as a poster grid. The
source tabs appear under the TV page's gate (`showAllTab` and at least two rails)
and call the same `selectSource`, with All meaning every rail. Hidden titles
leave through the TV page's own `visibleFolderRails` (widened from private to
internal rather than copied), including its resolved-IMDb bridge, so a title
hidden on Home or in the Library leaves this grid too. Cards are keyed with the
TV page's `kbFolderCardKey`, watched/partial badges use the TV's candidate order
(the card's own `tt` id, or the resolved IMDb flavour then `tmdb:<n>`), and a
tap records the same return target, so backing out of Detail reopens the grid
scrolled to the poster that was pressed.

The long-press sheet carries Go to Details, the watched toggle, add to library
and Hide - the menu's own hide removes the poster from the grid at once.

Parity notes (not bugs to fix):
- The TV page's three layouts (follow the manifest's hero+rails, plain rows,
  grid) collapse into the grid on a phone: a phone cannot afford a hero plus
  rails, and the collection's whole list belongs behind one thumb scroll.
- "Open in Grid" is not offered, because the phone page is already the grid.
- The TV focus plumbing (per-card requesters, successor keys, the rail reveal
  loop) has no touch twin; a return is a scroll to the card.

### Simkl connect

**Finished on the phone.** Same activity-scoped `SimklViewModel`, so the
device-code request, the polling, the account/counts refresh and the
destructive disconnect are the same code. Connected shows the account name,
the two watched counts, the continue-watching fetch state and the view model's
status/error lines, with REFRESH / BACK / DISCONNECT; not connected shows the
verification URL and the code in the same large type, with GENERATE CODE /
BACK - plus OPEN VERIFICATION PAGE, which hands the address to the browser:

the QR beside it is drawn for the OTHER device, so on the phone showing it the
QR is unscannable and the address was something to type by hand. Trakt's phone
connect has the same button (and the same disabled-while-polling GENERATE CODE).

Both safety rules survive: a kids profile gets no DISCONNECT button at all,
and the press opens a confirmation that names the wipe (every resume position,
watched marker and Continue Watching row) rather than just the unlink.

### Profiles

**Finished on the phone (picker and editor).** The launch picker keeps every
gate:

- tapping the active profile enters without rebinding the singletons;
- a profile with its own PIN raises Enter PIN, auto-verifies on the fourth
  digit, and clears a wrong entry instead of kicking the viewer out;
- leaving a gated kids profile (switching or opening Manage) raises the exit
  gate, answered by the profile's PIN or the device parent PIN;
- with neither credential the gate creates the parent PIN before leaving, so
  it never fails open;
- MANAGE sits in the same grid, and avatars keep the palette, custom image and
  selected ring (and the initials fallback).

The editor is the TV editor's form and rules on touch controls: name, the 8
avatar colours, an uploaded photo (the system photo picker) or a typed/pasted
image URL, REMOVE PHOTO, the profile's own PIN (current PIN required to change
or remove), guest mode, and Kids Mode with its ceiling, lock add-ons,
kid-safe Live TV, PIN-to-leave, daily limit, bedtime hour and half-hour, and the
device parent PIN. The manage-mode chip row can switch target or start a new
profile, gated for a target with its own PIN or a kids sandbox.

The save path is the TV editor's, step for step: validate the PIN entries,
create-or-migrate, finalize the avatar, write guest, write Kids Mode and its
options, then write the PIN. Every parent-PIN gate is kept - a first PIN needs
an existing parent PIN (no create-the-PIN fallback), a kids change / PIN change /
profile creation beside a kids profile asks for the parent PIN first, DELETE is
guarded - and backing out of the prompt refuses the action instead of applying
it with the protection dropped.

Parity notes (not bugs to fix):
- The form is one vertical scroll of full-width fields and chip rows: a phone
  has no 0.6-width field row or D-pad focus order to reproduce. The verified
  focus retries on the parent-PIN prompt have no touch twin.
- The parent PIN is DEVICE-LOCAL while profiles sync. On a second device that
  has taken a sync of a PIN-less kids profile, the first person at the exit gate
  is the one who sets the parent PIN - so set it on a new device before handing
  the remote over. A deployment note, not a code path: a synced credential would
  have to travel through the same cloud account that curates the profile.

**An unreadable profiles blob is not an empty one (2026-10-27).** `profiles_json`
that this build could not parse used to be read as "no profiles", so the picker
showed only MANAGE and the first create/rename/PIN/kids write saved "the list I
can see plus my change" over every profile on the device - and pushed it. The
store now keeps the blob, publishes `ProfileManager.profilesUnreadable` (both
pickers draw "Profiles couldn't be read on this device. Nothing has been
deleted..."), refuses every profile write while that is true, and lets a pulled
cloud blob repair it: a payload is a COMPLETE list, not a local edit, so it may
replace an unreadable blob and reopens writing. `ProfileManager.create` returns
null in that state; both editors report it instead of entering a profile that
was never written. Pinned by `ProfileStoreReadSafetyTest` (real store, corrupt
blob) and `ProfileReadSafetyContractTest` (wiring, both form factors).

**A sync pull is not a pick.** When this device had no active profile, a pull
used to set the in-memory active to whichever profile sorted first by
`createdAt` - no picker, no PIN gate, no persisted `KEY_ACTIVE` and no cache
reset - so a device that had just cleared its profile was handed one back, and a
fresh sign-in could enter a PIN'd profile without its gate. Nothing is activated
by a pull now, matching the delete paths: the list is published, and choosing
stays an authenticated act (`KidsGate`).

Still open, both product calls rather than code gaps:
- **PINs do not travel** (by design: a device secret, not account state). A
  profile PIN'd on the TV arrives unPIN'd on a device that pulls the list, and
  the parent PIN is device-local too, so the first exit there falls to the
  gate's CREATE fallback. Whether a first-pull device should SAY so is a copy
  decision nobody has made.
- **No PIN attempt throttling** anywhere (pickers, exit gate, the editor's
  current-PIN check): a wrong code clears the entry and says "Wrong PIN". The  4-digit space costs hours of PBKDF2 to sweep, but it is unbounded work; an
  escalating delay after N failures is a calibration decision, not a bug fix.


### Streams
- TV-only by design: focus-entry latch and scroll-reset effects.
- The spinning refresh mark (the button label carries the state instead).

### Live TV guide

**Finished on the phone.** Group chips (All / Recent / Favorites / one per
playlist group, with hidden groups and channels excluded); long-press actions
(favourite, reminder for the next program, hide channel, hide group, catch-up);
hidden-channel and hidden-group undo in playlist setup; extra playlist and EPG
sources; EPG program search alongside channel search; the in-guide "starting
now" reminder banner and notification-tap tuning; the catch-up program list;
and a per-group zapping lineup with the on-screen group selected.

The TV's zapping **number pad is intentionally not ported**: a remote has no
text entry, and on a phone the search field matches a channel number exactly.

**A program search hit delivers its program (2026-10-27).** A hit's press used
to tune the channel whatever the time was, so a "what's on tonight" hit opened a
different program with nothing on screen saying so. Both screens now ask
`programHitPress` (in `ui/iptv/GuideRules.kt`): a hit still ahead arms a reminder
for THAT program through the same store/worker the channel sheet uses, a hit
airing now tunes live, and one that has ended tunes live too. Each row carries
the action's own line (`programHitActionLabel`), and the TV search dialog keeps a
30s clock while it is open so it never offers a reminder for something already
on air.

Parity notes (not bugs to fix):
- Restored `Recent` order is best-effort: TV persists it as an unordered
  `StringSet`, so a reloaded queue can come back in any order. Within a session
  it is ordered.
- A playlist group literally named `All` / `Recent` / `Favorites` cannot shadow
  the reserved chip (same on TV).

## Not verified anywhere yet

- **Nothing has been rendered.** No device or emulator is available in the dev
  sandbox, and there is no compose-ui-test harness in this module, so every
  phone screen, the player gesture layer and the portrait stage are verified by
  compiler, ktlint, contract tests and code review only. First real check is on
  hardware - for Phase 3 that means a finger on the picture, not a D-pad.
- The TV regression gates for the earlier phases have not been run on hardware.

## Next steps, in order

1. Hardware verification of the ported screens, starting with the two that own
   playback: the sports hub's sheets and the settings that feed the player.
2. Hardware verification of the Phase 3 gestures on the same pass: tap,
   horizontal seek (including while paused), both vertical halves, pinch aspect,
   hold-to-rotate, and that the portrait band and the chrome below it survive a
   rotation in both directions.
3. The Phase 4 device pass above (screenshots, listing, the device checklist).
4. Nothing else is queued: every screen in the table above is ported, Settings
   has no deferred controls left, and the player's touch vocabulary is written.

## Verifying a port

```bash
./gradlew :app:compileDebugKotlin
./gradlew ktlintCheck
./gradlew :app:testDebugUnitTest
```

The contract tests live in `app/src/test/java/com/kennyb1201/kbstream/ui/mobile/`.

CI runs the same set on every pull request as its own `phone-port` job in
`.github/workflows/pr-checks.yml` - the phone screens, the Settings contracts
that pin them and the KB/Home contracts that decide what the phone draws - so a
port regression turns red by name instead of being one line in the full suite.
