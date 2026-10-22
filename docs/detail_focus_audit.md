# Detail screen focus audit — the television D-pad surface

Date: 2026-10-22. Scope: the TV detail page — `ui/detail/DetailScreen.kt` and the
rules files it delegates to (`DetailFocusChain`, `DetailChipColumns`,
`DetailMediaRow`, `DetailReturnFocus`). Not `DetailScreenMobile`: phones reach the
page through that file, which has no D-pad surface at all.

Trigger: repeated field reports of focus trouble on this page — presses that do
nothing, presses that skip a row, and focus lost after a menu closes.

## Method, and what the marks mean

No television, emulator or renderer exists in this workspace, so nothing here is
a screenshot. Every cell is one of:

| Mark | Meaning |
|---|---|
| `PASS` | Sound by reading every focus modifier and handler, and pinned by a contract test that fails if it regresses. |
| `FIXED` | A defect found in this pass and repaired (with the pin updated/added). |
| `LEFT` | Deliberately left as-is, for a reason stated in the row. |

The scans used, over `ui/detail/**`:

- **Every focus modifier**: `focusRequester`, `requestFocus()`, `focusGroup`,
  `focusRestorer`, `focusProperties`, `focusable()`, `onFocusChanged`,
  `onPreviewKeyEvent`, `onKeyEvent`. Every hit was read against the row it
  belongs to.
- **Every landing**: each `requestFocus()` was classified as *a surface taking*
  focus (a menu anchor, an overlay's own node, the default-focus effect — a
  single ask is correct there) or *a hand-back* (a request made while the tree is
  being recomposed around it — which needs a verified landing).
- **The overlay layer**: every `PosterContextMenu`, `ReviewOverlay`,
  `BackdropOverlay` and their dismiss paths, read together with the surface that
  raises them.
- **The scroll layer**: `revealRail`, `scrollToTopOnFocus`, the TV
  `BringIntoViewSpec`, `bringSeasonChipIntoView`, and every `scrollToItem`.

The regression evidence is the normal gate: `:app:testDebugUnitTest` (5709
tests), `:app:ktlintMainSourceSetCheck` and `:app:ktlintTestSourceSetCheck`.

## The surface, row by row

Painted order is the chain's order (`DetailRow`); the "press" column says who
decides a vertical press there.

| Row | Press | State |
|---|---|---|
| Header: PLAY / random / trailer | default search | `LEFT` — see F5 |
| GENRE chips (fixed header) | the chip, via `DetailRowVerticalFocus(GENRE, …)` | `PASS` |
| Overview "View more", ratings, fact cards | default search | `LEFT` — deliberate; the fact cards are not focusable, and only the overview toggle is (see F6) |
| KEYWORDS (tag rail) | the chip, via the chain | `PASS` |
| EPISODES: season chips | default search | `LEFT` — see F7 |
| EPISODES: episode cards | the card (UP → season chip, LEFT/RIGHT past an edge → adjacent season) and the row (DOWN → chain) | `FIXED` (F4) |
| PEOPLE | the card, via the chain | `PASS` |
| NETWORK / PRODUCTION | the chip, via the chain | `PASS` |
| COLLECTION | the poster, via the chain | `PASS` |
| MORE LIKE THIS | the poster, via the chain | `PASS` |
| REVIEWS | the tile, via the chain | `PASS` |
| MEDIA: tab strip → cards | the strip (LEFT/RIGHT/DOWN/UP), each card (UP → strip, DOWN → chain) | `PASS` |
| Bottom of the page | the shared handler consumes it; focus stays put | `PASS` |

Overlays: every menu and overlay is its own focus group over the page, so each
one is a hand-back on close.

## Findings

| # | Finding | Class | Status |
|---|---|---|---|
| F1 | **Hand-backs were one-shot requests.** All 15 places that give focus back — the studio-chip, review, poster, season, episode and image menus, the backdrop and review overlays, the hero Play menu, and the rows *inside* those menus — asked once with `requestFocus()` and read nothing. The ask is made in the frame the menu's scrim is leaving the tree, and a node that cannot take focus yet answers `false` rather than throwing, so a miss was silent: the D-pad restarted at the top of the page. This is the failure every one of those hand-backs was added to fix. | Trap, silent | `FIXED` — one `handFocusBack` helper, built on the app's verified landing (`landFocusOn`: retried across frames, answer read). The season-swap edge restore had the same defect and now also verifies its landing. |
| F2 | **Two vertical moves could be in flight at once.** A chain move is asynchronous by necessity (a rail the scroll disposed is scrolled back in, then asked again a frame at a time, up to 15 attempts). A press arriving mid-move started a second move while the first kept retrying, so the older one could land its focus *after* the newer one had already moved — the focus snapping back the way the viewer had just come. | Race, intermittent | `FIXED` — `startFocusMove` cancels the move in flight; every press that starts one goes through it (chain handler, Media strip DOWN, card UP to the strip). |
| F3 | **A press out of the episode section or the header could not reveal a rail.** The reveal arm — the one that scrolls a rail back into composition — was gated on the *pressed* row's own list key, and the episode section and the header are the two rows that have none (`detailRowKey` → `null`; the episode section is a season chip row and an episode row at once, the header is above the list). So DOWN off an episode card, whose target (PEOPLE) is the rail most often still under the fold, fell straight through to "next row that way" — jumping over the cast rail to the network or production chips, or, at the bottom of the page, doing nothing at all. Whether it worked depended on the lazy list's prefetch window, which is exactly the "sometimes DOWN skips the cast" shape of the report. | Trap, silent | `FIXED` — the reveal is no longer gated, and takes a nullable anchor: with no neighbour index it starts its search at the top of the list, the same fallback the Media strip already used. |
| F4 | **UP out of an episode card could be a dead press.** The card consumes UP and hands it to `focusSeasonChip`, which retried ten times and then returned nothing at all — so a chip that never took focus (a lazy row that was not laid out) left the press consumed with the focus unmoved. | Trap, silent | `FIXED` — `focusSeasonChip` reports whether it landed, and a miss falls back to the chain's own UP (the tag rail above), rather than being swallowed. |
| F5 | **DOWN out of the header row is the default search**, not the chain. The header's controls carry no handler; `DetailRow.HEADER` is a *destination* in the chain (UP out of the genre chips lands on PLAY), and `nextRow(HEADER, DOWN)` is never asked. | `LEFT` | Deliberate. The genre chips sit directly under the buttons, and the header row is three side-by-side controls whose "column" is not the chip-column the rest of the page keeps — wiring the chain there would replace a good geometric answer with a worse one. Recorded here so the next reader knows the chain's `HEADER → GENRE` rule is only ever used as a landing. |
| F6 | The overview toggle, the ratings strip and the fact cards are **not chain rows**; they are reached by the default search (in practice, DOWN from PLAY). | `LEFT` | Deliberate: they are leaf surfaces with no rail to keep a column in, and naming them in the chain would change presses the chain is not about. The fact cards are not focusable at all, so the facts rail's `focusRestorer` has nothing to restore. |
| F7 | **Season chips answer UP/DOWN with the default search.** DOWN lands on the episode rail directly below (what a viewer expects), UP on the tag rail above. | `LEFT` | Deliberate, with a hazard worth knowing: any focus that lands on a season chip *selects* that season (`onSeasonFocused`), because the chip row is also the section's selector. That is the intended behaviour for LEFT/RIGHT along the row, and the reason the episode card's UP is owned by the card rather than the default search. |
| F8 | **DOWN off the Media tab strip** runs `focusFirstMediaCard()`; if no card of the active page is composed, the focus stays on the strip. | `LEFT` | Acceptable: the strip is a legitimate place to be, the press is consumed, and the alternative (jumping to the next row) would skip the content. A stuck-strip report would be a new finding. |
| F9 | Stale entries in `chipColumnRequesters` / `railFocusRequesters` survive a title change (the page reuses its composition for a drill-down), so a vertical press can be aimed at a disposed card's requester. | Risk, low | `LEFT` — a stale requester answers `false`, which is exactly the "rail exists but cannot answer" case the retry and the (now ungated) reveal handle. Clearing the maps would instead make rails that do not recompose unaddressable, which is worse. |

Everything else on the page is sound and pinned: one chain decides every vertical
handoff (`DetailFocusChainTest`, `DetailFocusChainWiringContractTest`); a chip
press keeps its column (`DetailChipColumns`); a rail the scroll disposed is
revealed before the press walks on; a drill-down return puts the pressed card
back under the focus (`DetailReturnFocus`, `DetailChipReturnFocusContractTest`);
a menu or an overlay is a focus group that cannot be escaped sideways; a season
swap parks focus on a zero-size sink *before* the focused card is disposed; and
the Media row is one chain row whose strip walks its own pages
(`DetailMediaRowWiringContractTest`).

## What this pass changed

- `ui/detail/DetailScreen.kt`: `handFocusBack` (all 15 hand-backs, plus the
  season-swap edge restore); `startFocusMove` (single-flight moves);
  `revealAndFocusChipColumn` takes a nullable `fromRowKey` and `focusRow` no
  longer gates the reveal on it; `focusSeasonChip` answers whether it landed and
  the episode card's UP falls back to the chain.
- `DetailFocusHandBackContractTest` (new): the hand-backs, the single-flight
  moves and the UP fallback.
- `DetailFocusChainWiringContractTest`: the reveal is now asserted to be *not*
  gated on the anchor, and the ordering assertion hangs off the reveal itself;
  the delegated press is asserted through `startFocusMove`.
- `DetailNetworkChipUpTrapContractTest`, `DetailMediaRowWiringContractTest`,
  `UiUniformityBatchAContractTest`: the same spellings as above. The play-button
  and studio-chip hand-back assertions there were loose enough to pass on
  unrelated strings; they now name the hand-back itself.

## The same rule everywhere else (same pass)

Finding F1 — a hand-back asked once and read by nobody — was not unique to this
page, so the rule now lives in one place and the other rail screens use it.

| Surface | Before | Now |
|---|---|---|
| `ui/browse/BrowseReturnFocus.kt` | `landFocusOn` (verified landing) only | + `rememberFocusHandBack` / `FocusHandBack`: verified **and** single-flight — a newer hand-back cancels the landing still waiting on a frame |
| Genre/Keyword, Studio, Decade, Actor, Collection pages | the dismiss and the menu's watched row each asked once | both go through `handFocusBack` |
| Search | the title menu's dismiss and watched row, and the browse-chip menu's action and Back, asked once | all four go through `handFocusBack`; the chip's own return-by-name fallback (`browseChipMenuReturn`) stays |
| Home | `restoreFocus` was already verified, but every dismiss started a new retry loop without cancelling the last one | `focusRestoreJob` cancels the superseded landing; the frame cadence is kept because the bar lives in an `AnimatedVisibility`. The rail menu's watched row uses it too |
| Catalog grid (`CatalogGridScreen`) | already verified (`landFocusOn` when the menu closes) | unchanged |
| This page | its own local helper | the same shared helper |

Deliberately still a direct request: `PosterContextMenu`'s `onBeforeHide`
successor hand-off (Search, the catalog grid). The successor's node is attached
*and* the ask has to happen in that same frame, before the hidden tile leaves —
a retried landing would only run after the framework had already refilled the
focus owner.

Pinned by `FocusHandBackContractTest`: the helper is verified and cancels a
superseded landing, every rail screen's dismiss uses it, and no
`…?.requestFocus()` hand-back survives in those files.

## Still owed on hardware

1. Every FIXED row, pressed for real: close each menu and overlay with Back and
   with an action row, and confirm focus is back on the surface that raised it.
2. A series with many seasons: hold DOWN through the episode rail into the cast
   rail and confirm the page scrolls rather than skipping to the network chips.
3. Rapid UP/DOWN through the chip rails, to confirm the cancelling move settles
   where the last press asked and not where an earlier one did.
4. The `LEFT` rows above, to confirm none of them is reported as a miss.
5. Home's rail menus and each browse page's poster menu: close with Back and
   with a row press, and confirm focus is back on the pressed card (and that a
   quick double-close settles on the last one, not the first).
