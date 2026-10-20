# Mobile hardening audit — every phone screen and shared surface

Date: 2026-10-20. Scope: the whole mobile surface reachable on a phone, plus the
shared code that renders over it (the `MainActivity` dialogs, menus and sheets).
Trigger: Phase 2 shipped with the caveat that nothing had been rendered on
hardware, and the first field use produced an app-bricking trap from that gap —
the update popup (TV-only buttons plus a swallowed Back).

## Method, and what "PASS" means here

No phone, tablet or emulator exists in this workspace, so nothing in this pass is
a screenshot. Every cell is one of:

| Mark | Meaning |
|---|---|
| `PASS` | Verified by reading the source against the check AND covered by an existing unit/contract test that fails if it regresses. |
| `PASS*` | Verified by reading the source against the check (and, where named, by a contract test), but the actual rendering only a device can show — font overflow, keyboard behaviour, gesture-bar overlap — is **unverified**. Every screen has at least this mark for the layout row. |
| `FIXED` | A trap was found and repaired in this pass (mobile-branched / touch twin; TV unchanged). |
| `POLICY` | Deliberately not dismissible, for a reason stated in the row. |

The scans used (over `ui/mobile/**` and the shared surfaces):

- **TV-only widgets**: every `ui/components` import whose symbol is a composable,
  and every `ui.components.KBCard` / `PosterContextMenu` / `KBTextField` /
  `KBDialogPanel` reference. Each mobile file imports the `ui/components/touch/`
  twin under the same name, so a screen that fell back to the TV one would show
  up in this scan.
- **Focus-only activation**: `focusRequester`, `requestFocus()`,
  `onPreviewKeyEvent`, `onKeyEvent`, `focusable()` in mobile files. The only hit
  is `SearchScreenMobile` moving focus into its own field — an initial focus, not
  an activation path.
- **Dismiss traps**: every `Dialog(` / `ModalBottomSheet(` / `AlertDialog(` in
  `MainActivity` and `ui/mobile/**`, and every `onDismissRequest`.
- **Width**: every `.width(N.dp)` / `.widthIn(...)` in `ui/mobile/**` and the
  touch components, for anything at or above 360dp (a phone's narrowest common
  width). The largest fixed width anywhere in the mobile tree is a 250dp
  landscape tile; every panel uses the touch `KBDialogPanel`, which is
  `fillMaxWidth()` inside `widthIn(max = width)`.
- **Insets**: `systemBarsPadding` / `safeDrawing` / `WindowInsets` — none in a
  screen file, by design: the shell reserves `systemBars ∪ displayCutout` once,
  for phones only (`MobileSafeAreaContractTest`).
- **Back**: `MainActivity`'s single `BackHandler` plus each screen's own, read
  for whether it delegates once its sub-state closes.

The regression evidence for all of it is the normal gate:
`:app:testDebugUnitTest` (5367 tests after this pass), `:app:compileDebugKotlin`,
and both ktlint source sets — plus the 22 existing `ui/mobile` contract tests.

## Findings

| # | Finding | Class | Status |
|---|---|---|---|
| F1 | **Update popup**: INSTALL NOW / LATER built from the TV `KBCard` (focus surface → untappable), `onDismissRequest = { }` (Back/scrim dead in every phase), fixed 560dp panel (wider than a portrait phone). | Trap, app-bricking | `FIXED` in the previous pass — touch plates, phase-aware dismiss (Offer → exactly LATER, Installed/Failed → acknowledge, Downloading/Installing → no-op), capped width. Pinned by `UpdatePopupMobileInputContractTest`. |
| F2 | **Exit prompt** (Back on Home → "EXIT KBSTREAM?"): STAY and EXIT built from the same TV `KBCard`, so both were dead to a finger; the 620dp panel is wider than a portrait phone. Back worked (`onDismissRequest` was wired), which is why it never reached a field report — but "every interactive element operable by touch" is the check. | Trap, escapable | `FIXED` this pass — same `DialogPlate` two-way button, 620dp is now a cap with a 16dp gutter on a phone, fixed width on TV. Pinned by the same test file. |
| F3 | **Kids time-lock veil**: its parent-PIN field is the TV `KBTextField` (shared overlay, TV behaviour pinned by `KidsLockOverlayInputContractTest`). The field is editable on a phone − `openKeyboardOnFocus` defaults true, so `readOnly` is false and tapping raises the IME − and the four-digit check fires without any Done action. | Risk, not a trap | `PASS*` — keep the TV field so the veil's focus/key handling stays byte-identical; **verify on hardware**: tap the field, type four digits, unlock. |
| F4 | **Kids time-lock dismissal**: Back is swallowed and the scrim is not dismissible. | Deliberate | `POLICY` — the only exits are the PIN or the limit expiring; any other exit is a bypass a child can use (Kenny, 2026-10-18, one app-level parent PIN). |
| F5 | Everything else in this sweep. | — | No other trap found: no mobile file imports a TV interactive widget, no mobile dialog swallows its dismiss, no fixed panel exceeds a phone's width, no focus-only activation path exists in mobile code. |

## Matrix

`1` layout at ~360dp / font 1.3 · `2` touch-operable · `3` dismissible (touch **and**
Back, or POLICY) · `4` Back returns, never a dead end · `5` safe areas · `6`
long-press / menus / fields.

### Screens with a phone twin

| Screen (phone file) | 1 | 2 | 3 | 4 | 5 | 6 | Notes |
|---|---|---|---|---|---|---|---|
| Home (`HomeScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | No dialogs; long press raises the touch context sheet (swipe + Back). Hero 180dp, rows scroll. |
| Browse sections — Tag / Studio / Decade / Actor / Collection (`*ScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | Poster/landscape touch cards with captions; long press → touch menu; each returns through `stableBackDestination`. |
| Catalog grid (`CatalogGridScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | LazyVerticalGrid, tile width from `rememberTouchPosterSize`. |
| Detail (`DetailScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 2 dialogs (episode/season menus), both with `onDismissRequest`; 1 bottom sheet for the play menu, swipe + Back. |
| Search (`SearchScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | Touch field auto-focuses on entry; suggestions are touch rows; long press → touch menu. |
| Library (`LibraryScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 1 confirm dialog (`onDismissRequest = { listPendingDelete = null }`), touch field for the list name. |
| KB folder (`KBFolderScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | Uses the **touch** `PosterContextMenu` (bottom sheet). |
| Guide / Live TV (`GuideScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 3 dialogs (setup/import), all dismissible; Back closes setup before leaving the screen. |
| Sports hub (`SportsHubScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 4 dialogs, all `onDismissRequest = onClose`; Back clears the search before leaving; standings use the two-line layout (`SportsHubLayoutContractTest`). |
| Add-ons (`AddonsScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 4 dialogs + 1 `ModalBottomSheet` (install/remove), every one with a dismiss handler; paste/import fields are the touch field. |
| Settings (`SettingsScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 3 dialogs (clear-history confirm, numeric pickers), all dismissible; Back pops the open pane first, then leaves. |
| Onboarding (`OnboardingScreenMobile`) | PASS* | PASS | PASS | POLICY | PASS | PASS | No dialogs. Back exits **by design** — it is the entry screen the app finishes from (same rule as the profile picker). |
| Simkl connect (`SimklConnectScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 1 dialog ("not configured"/disconnect), dismissible; QR is display-only. |
| Trakt connect (`TraktConnectScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 1 disconnect confirm (`ModalBottomSheet`, `onDismissRequest`), touch field for the code. |
| Profile picker (`ProfilePickerScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 2 dialogs (PIN, manage), both dismissible; entry picker Back is the exit prompt. |
| Profile edit (`ProfileEditScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 1 parent-PIN prompt dialog, dismissible; every field is the touch field. |
| Viewing stats (`StatsScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | No dialogs; Back delegates straight to `onBack`. |
| Streams / source picker (`StreamsScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | No dialogs; rows are touch cards; long press → touch menu. |
| Catalog builder (`CatalogBuilderScreenMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 3 dialogs, all dismissible; Back closes the sheet before leaving. |
| Home-manager sheet (`HomeManagerDialogMobile`) | PASS* | PASS | PASS | PASS | PASS | PASS | 3 dialogs (rename/hide/delete), every one with a dismiss handler; `ModalBottomSheet` for the sheet itself. |

### Shared surfaces rendered over every phone screen

| Surface | 1 | 2 | 3 | 4 | 5 | 6 | Notes |
|---|---|---|---|---|---|---|---|
| Update popup (`UpdateAvailablePopup`) | `FIXED` (F1) | `FIXED` | `FIXED` | PASS | PASS | n/a | Phase-aware Back: Offer → LATER (per-version dismissal), Installed/Failed → acknowledge, Downloading/Installing → no-op with HIDE still on screen. |
| Exit prompt (`ExitConfirmDialog`) | `FIXED` (F2) | `FIXED` | PASS | PASS | PASS | n/a | Back dismisses (unchanged); STAY/EXIT now tap targets on a phone. |
| Kids time lock (`KidsTimeLockOverlay`) | PASS* | PASS* (F3) | `POLICY` (F4) | POLICY | PASS | PASS* | PIN field is the TV field on purpose; editable, keyboard opens on focus. |
| Touch context menu / sheet (`TouchContextMenu`) | PASS | PASS | PASS | PASS | PASS | PASS | `ModalBottomSheet`: scrim, swipe-down and Back all dismiss. |
| Touch text field (`TouchTextField`) | PASS | PASS | PASS | n/a | PASS | PASS | Real `OutlinedTextField`; IME is the platform's, `imePadding()` on the shell keeps it visible. |
| Dialog plate (`KBDialogPanel` touch twin) | PASS | PASS | PASS | n/a | PASS | n/a | `fillMaxWidth()` inside `widthIn(max = width)`. |
| Splash / auto-play splash | PASS | n/a | n/a | n/a | PASS | n/a | Display only, no interactive element. |

### Player (separate activity, phone gestures)

Reachable from every screen above; excluded from the orientation column because
it owns its own stage. Its controls are the XML chrome, which is all tap targets,
plus the Phase 3 gesture layer attached only when `FormFactor.isTv` is false
(confirmed tap for the chrome, double-tap ±10s with accumulation, drag seek,
brightness / volume, pinch / rotate). `PlayerTouchWiringContractTest` and
`PlayerTouchRulesTest` cover the invariants; hardware-verify alongside F3.

## What this pass changed

- `MainActivity.kt`: `UpdateDialogPlate` → `DialogPlate` (now shared by both
  dialogs), and `ExitConfirmDialog` / `ExitDialogButton` gained the phone branch:
  touch plates for STAY and EXIT, `widthIn(max = 620.dp)` + `fillMaxWidth()` +
  16dp gutter on a phone, `width(620.dp)` on a television. TV composition and
  behaviour are unchanged — the same `KBCard`, the same 620dp panel, the same
  `onDismissRequest = onDismiss`.
- `UpdatePopupMobileInputContractTest`: renamed to follow the shared plate, and
  two tests added — the exit prompt's phone branch, and "no dialog in the shell
  swallows its own dismiss".

## TV regression gate (owed from Phase 2)

| Gate item | Result |
|---|---|
| TV source files: nothing changed **by this pass** | PASS — the sweep edited `MainActivity.kt` only, and only inside the two shared dialogs' rendering; no file under `ui/mobile` was touched at all, and `ui/components`, `ui/home`, `ui/detail` are untouched. (Separately, the earlier Trackers-section request deliberately edits the TV `SettingsScreen.kt` layout; that is its own spec and its own contract test, not a hardening change.) |
| TV behaviour of the two edited dialogs | PASS — the TV path still composes the same `KBCard`, the same fixed 560dp/620dp panel, and the same `{}`/`onDismiss` behaviour; the phone branch is the only new code. |
| TV identity/dialog contracts | PASS — `KidsLockOverlayInputContractTest`, `SyncedPrefComposeReadsContractTest`, `SettingsScreenMobileContractTest`'s TV half, `Immersive`/`ExitConfirm`-adjacent tests and the rest of the suite: 5367 tests, 0 failures. |
| TV visual/behavioural flows on a TV build (Home rails + rail-manager dialog, Detail, player, Settings) | **NOT RUN** — no television, no emulator and no renderer in this workspace. This is the hardware half of the gate and it is still owed; the source-identity row above is what can be asserted here. |

## Still owed on hardware

1. The whole matrix's `PASS*` cells: render each screen in portrait and landscape
   at 360dp and font scale 1.3; confirm nothing clips and every last item scrolls
   into reach under the gesture bar.
2. F3: kids lock → tap the PIN field, keyboard opens, four digits unlock.
3. The update popup's own field checks (INSTALL/LATER taps, Back = Later and
   stays down, portrait fit) and the exit prompt's STAY/EXIT taps.
4. The TV regression gate's visual half on both boxes.
