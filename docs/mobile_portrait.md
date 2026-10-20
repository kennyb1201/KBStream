# Portrait browsing on mobile

Phones live in portrait, but the mobile UI was built as a touch twin of the
landscape television layouts. Fixed-width panels overflowed a hand (the 560dp
update panel was the proven case), poster grids opened two oversized columns
wide, and every browse screen was effectively landscape-shaped.

This pass makes the phone's browse family portrait-first, and makes playback the
one place the app asks for a side. The television is untouched: its manifest
locks, layouts and dialogs stay byte-identical.

Pinned by `PortraitBrowseContractTest` and `FormFactorScaffoldContractTest`.

## Orientation policy

| Where | Phone | Television |
|---|---|---|
| `MainActivity` (every browse destination) | `SCREEN_ORIENTATION_UNSPECIFIED` — follows the device | manifest's `landscape` lock, no code call |
| `NativePlayerActivity`, `MpvPlayerActivity`, `ExternalPlayerActivity` | `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` on entry, `UNSPECIFIED` on `onDestroy` | manifest's `landscape` lock, no code call |

`FormFactor` owns all three calls (`applyOrientation`, `applyPlaybackOrientation`,
`releasePlaybackOrientation`), and each returns on `isTv` before touching the
activity. The playback request is `SENSOR_LANDSCAPE`, not a hard lock, so the
player's own hold-to-rotate gesture can still reach the portrait stage.

Every browse destination is a Compose `Screen` inside `MainActivity`, so one
programmatic unlock covers all of them — Home, Search, Detail, CatalogGrid, Tag,
Studio, Decade, Collection, Actor, Library, Guide, Sports Hub, Settings,
Add-ons, connect screens and the profile pages.

## The width rule for grids

`ui/mobile/MobileGridColumns.kt` turns the window width into a column count:

- **posters** start at **3 columns** in portrait, whatever the Poster Size pref
  says (a 360dp phone cannot fit two 140dp tiles and honestly call it a grid);
- **landscape cards** start at 2, because three 16:9 cards in a portrait hand
  are thumbnails;
- a landscape phone or a tablet fits as many target-width tiles as the width
  allows, so Poster Size still changes the count on a wide window (5 vs 4 on a
  720dp window);
- the count is capped at 8.

The tile keeps its chosen shape — 2:3 poster or 16:9 landscape card — and fills
the narrower cell, so the setting still means what it meant and the grid just
fits. Applied to `CatalogGridScreenMobile`, `LibraryScreenMobile`,
`CollectionScreenMobile`, `ActorScreenMobile` and `KBFolderScreenMobile`.

The Tag, Studio and Decade browse pages are horizontal rails on the phone (see
`BrowseSectionsMobile`), not grids: each rail is already one swipe away and a
rail cannot overflow sideways, so portrait needs no column rule there. Their
per-rail offsets are saved across rotation (below).

## Per-screen portrait shape

| Screen | Portrait |
|---|---|
| Home | top bar carries the wordmark and the profile chip only (the four destination icons moved into the shell's bottom bar — see `docs/mobile_port.md`); rails stay horizontal carousels |
| Catalog grid / Library / Collection / Actor / KB folder | width-derived fixed columns (3 posters in portrait), captions under each card |
| Detail | full-width 16:9 backdrop header, meta and actions stacked below it, one vertical scroll |
| Search | field takes the row's remaining width; results are full-width rails; browse chips are 3 across in portrait, 5 on a wide window; the shell's `imePadding()` keeps results above the keyboard |
| Guide | vertical channel list with now/next; portrait-safe, never requests an orientation |
| Settings / Add-ons / Library / Sports / Trakt / Simkl | single-column stacks; choices use `FlowRow`, label rows give controls the leftover width |

## Overlay width safety

The rule for every phone dialog, sheet and panel: `widthIn(max = …)` plus the
screen's own padding, never a fixed width wider than ~340dp.

- `ui/components/touch/TouchDialog.kt` (`KBDialogPanel`) is `fillMaxWidth()` inside
  `widthIn(max = width)`;
- long-press menus are `ModalBottomSheet`s (`TouchContextMenu.kt`);
- the app shell's update and exit dialogs use `widthIn(max = 560/620.dp)` +
  `fillMaxWidth()` + a 16dp gutter on a phone, and keep their fixed TV width on
  a television;
- the shared "Add to list…" picker (`ui/components/LibraryPicker.kt`) is
  reachable from the phone's long-press sheet, so it caps at 560dp with the
  same gutter on a phone, its row list is capped instead of fixed, and its rows
  and actions become the tappable twins there — the TV dialog keeps its fixed
  width and focus rows;
- no file under `ui/mobile/` has a fixed `.width(N.dp)` above 340dp (tested).

## Rotation and state

`MainActivity` is recreated on a rotation, as before (the manifest is unchanged
for TV). What the viewer notices is that nothing is lost:

- the navigation destination is `rememberSaveable`;
- activity-scoped ViewModels keep the loaded rails, history, search results and
  detail data across the recreation;
- every phone grid uses `rememberLazyGridState()` (the Library grid is keyed by
  filter, the folder grid by folder, the actor grid by actor, so switching
  context still starts at the top);
- the Browse pages' per-rail sideways offsets are saved through `mapSaver`;
- Home's vertical list and every other `LazyColumn`/`rememberScrollState` is
  saveable by default;
- the bottom bar is drawn by the shell from the restored screen, so a rotation
  comes back with the same item lit.

## Still owed (needs hardware)

The layout rules are source-pinned and the column arithmetic is unit-tested, but
the visual pass has not been run on a phone in this workspace:

- browse upright through Home, a catalog grid, Detail, Search, Guide and
  Settings on a 360dp phone;
- start playback upright (player should go landscape), back out (browse should
  be upright again);
- rotate mid-browse and confirm scroll position plus loaded rails survive;
- the five-destination bottom bar: one tap between any two of them, present on
  all five, absent in Detail and during playback.
