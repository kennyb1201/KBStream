# Trakt integration — phases A–E1 and the Library tab landed

Trakt is the app's third tracker, beside Simkl and MDBList, not a replacement.
Auth, the session's cross-device sync, scrobbling, the watched-state union, KB
list sources, the read side of reviews and playback-progress import as one of
the resume-authority choices (D1) are in. Comment posting (D2/E2) is
deliberately not, and says so below.

## What exists

**Data** (`app/src/main/java/com/kennyb1201/kbstream/data/trakt/`)

| File | What it owns |
|---|---|
| `TraktConfig.kt` | base URL, API version, activation URL, client id |
| `TraktModels.kt` | device-code/token, scrobble, history, watched, list and comment wire shapes |
| `TraktApiService.kt` | OAuth, `scrobble/*`, `sync/history[/remove]`, `sync/watched/*`, `sync/watchlist[/remove]`, user-list reads/removals, comments; header interceptor |
| `TraktDeviceFlowRules.kt` | the poll state machine, expiry, interval widening, refresh window (pure) |
| `TraktWatchState.kt` | id canonicalisation, the watched snapshot/index and scrobble progress rules (pure) |
| `TraktListRef.kt` | KB list reference parsing and the typed list row (pure) |
| `TraktTokenStore.kt` | per-profile `SecureTokenStore` under `<profile>.trakt_auth` |
| `TraktRateGuard.kt` | request budget, `Retry-After` park, synthetic-429 refusal |
| `TraktRepository.kt` | the flow, proactive refresh, account read, scrobble, history writes, watched snapshot, watchlist and user-list reads/writes, comments, disconnect |

**UI** (`ui/trakt/`, `ui/mobile/TraktConnectScreenMobile.kt`)

One state (`TraktUiState`), one view model (`TraktViewModel`), two layouts: the
TV page and the touch twin, both routed through `Screen.Trakt` and reached from a
**Trakt** row beside **Simkl** in Settings on both form factors.

## The rules the integration is built around

1. **400 is normal.** Trakt's device-token endpoint answers `400` while the
   viewer is still approving; `429` means poll slower (`+5 s`), `418` is a
   denial, `404/409/410` retire the code. `TraktDeviceFlowRules.poll` is the
   whole table and `TraktDeviceFlowRulesTest` pins it.
2. **A rejected client is not a pending poll.** `400 invalid_client` stops the
   flow with its own message; only a plain/`authorization_pending` 400 keeps
   waiting (`TraktDeviceFlowRules.MISCONFIGURED`).
3. **Refresh is proactive.** Access tokens last 7 days; the token is refreshed
   with a day in hand, never on a 401 in front of the first scrobble. Trakt's
   refresh tokens are single-use, so the stored pair is always replaced.
4. **The store is its own file, and encrypted.** `<profile>.trakt_auth` cannot
   collide with `<profile>.simkl_auth` (Diagnostics and the cloud readers
   address stores by name), and there is no plaintext migration path because
   nothing predates it.
5. **Disconnect clears Trakt only.** `revokeAndClear()` revokes the grant and
   drops the session keys. Local history, resume rows, watched markers and
   badges are untouched - Simkl's unlink wiping them was audit m10, and the
   contract test asserts this repository cannot reach that data layer at all.
6. **A kids profile needs the parent PIN.** `startConnect()` refuses without
   one, and the flow only begins after `verifyParentPin`; a parentless
   (PIN-less) kids profile is told to set one.
7. **The budget exists before the traffic does.** `TraktRateGuard` is the
   MDBList lesson stated up front: bounded calls per window, `Retry-After`
   honoured, and exhaustion reports "no answer" rather than an empty result.
8. **Watched state is a union, and a union only adds.** Home reads Trakt's
   watched episodes beside Simkl's and MDBList's; the badges test membership in
   all three snapshots. Watched anywhere is watched everywhere, with no
   conflict resolution.
9. **A tracker failure is not "nothing watched".** Every read is guarded on
   `isConfigured()` + `hasToken()` and sets `trackerReadFailed` when it throws;
   budget exhaustion keeps the previous snapshot. A blank rail is the one
   outcome the integration must never cause.

## Phase B — scrobbling

The third fire-and-forget beside Simkl's and MDBList's, at the same three call
sites (`NativePlayerActivity`, `MpvPlayerActivity`, `ExternalPlayerActivity`):
`start` / `pause` / `stop`, plus an explicit `sync/history` write when a title
completes. Progress is a whole percent (`TraktScrobbleRules.progressPercent`) -
MDBList's fractional percentage was a 400, and decimals buy nothing here.
Failures are logged and swallowed; a tracker can never block playback.

Mark/unmark actions mirror too: `WatchedStatusRepository` and Detail's
season/episode paths push `sync/history` and remove through
`sync/history/remove`, so a manual mark and a playlist scrobble leave the same
record.

## Phase C — watched state

- `HomeViewModel` unions `simkl + mdbList + trakt` episodes; everything
  downstream (suppression, the same-episode rule, Up Next candidates) is
  unchanged.
- `WatchedStatusRepository` refreshes Trakt's `/sync/watched` snapshot on the
  same 15-minute cadence as the other two, with the same profile-switch guard,
  and resolves movie/show badges against it. Trakt's show rows prove "started"
  rather than "completed" (the response has no all-episodes-watched flag), so
  they land in the partial set exactly as MDBList's do.
- One snapshot read serves both: `/sync/watched/shows` and
  `/sync/watched/movies` are cached for 15 minutes and keyed by the access
  token, so a profile switch cannot be served the previous profile's history.
  A half-failed refresh keeps the half it did not hear about.

## Phase D — Trakt lists for KB folders

`KBContentLoader`'s `"trakt"` branch is alive again. It reads the list reference
from the source's own fields (URL, `users/<user>/lists/<id>` path, or a bare
list id - see `TraktListRefParser`), reads movies and shows (the API has no
all-types GET), maps TMDB/IMDb ids onto the same `KBContentItem` rows every
other rail uses, takes artwork from Trakt's images, and returns the whole list.
Without a connected account the rail is empty, which is the loader's existing
"this source cannot answer" behavior. The kids ceiling is applied through the
shared meta filter, so a Trakt rail hides the same titles a Home rail would.

**No KB catalog is capped.** The old `MAX_ITEMS_PER_SOURCE = 40` cutoff on the
two branches that map raw provider rows — **add-on catalogs and Trakt lists** —
is gone. Both branches now page to the end:

- Trakt sends `X-Pagination-Page-Count`; the repository fetches every page of
  the movies read and the shows read, one request at a time through
  `TraktRateGuard`. If the header is absent, a full 100-row page means "keep
  going" and a short page ends the read. Budget exhaustion stops with the
  pages already in hand rather than blanking the rail.
- An add-on catalog pages by `skip`; the loader requests one page at a time
  (the existing catalog cache absorbs repeats) until the add-on returns an
  empty page.
- A page that contributes no new id ends either walk, so a provider that
  ignores paging parameters cannot loop the read. Duplicate detection, not a
  page-number cap, is the terminator.

## Library tab — the third source beside Local, Simkl and MDBList

The Library tab reads Trakt the same way it reads the other trackers:

- **WATCHLIST** merges Trakt's `sync/watchlist` beside Simkl's plan-to-watch
  and MDBList's watchlist. Rows carry `extended=full,images` artwork, so they
  draw without a TMDB lookup; title/year and IMDb/TMDB ids come from the same
  payload. The read walks every page, not a fixed first chunk.
- **PERSONAL LISTS** includes the account's `users/me/lists`, with the item
  read through `users/me/lists/{id}/items/{movies,shows}`. Trakt list ids are
  strings, so the rail's Int id is a reserved-range shelf id
  (`traktListShelfId`, `Int.MIN_VALUE + traktId`) and the real slug/id lives on
  `LibraryList.remoteId`. `LibrarySource.isLocalList` — not the id's sign —
  decides what may be renamed or deleted.
- **Removal** mirrors remotely: a Trakt watchlist row leaves through
  `sync/watchlist/remove`, a Trakt list row through
  `users/me/lists/{id}/items/remove`, and removing a local row also clears its
  Trakt twin along with MDBList's.
- **Adds** mirror to Trakt through `LibraryMirror`, beside Simkl and MDBList,
  so "Add to Library" on any poster writes the same title to all three when
  they are connected. The add row's description names only the connected
  trackers.
- **Add to list…** offers Trakt's watchlist and every personal list as
  destinations beside My List, local lists and MDBList's. A row carries the
  list's real slug/id (or the watchlist flag), so the press reports the actual
  POST's outcome; Trakt list shelf ids are never mistaken for local ids.
- The header lists `TRAKT` as a connected source, an empty watchlist says
  "Trakt not connected" when it is not, and every Trakt row still goes through
  the shared kids ceiling, ratings, poster/year backfill and watched badges.

## Phase E1 — reviews

Detail fetches the title's Trakt comments (sorted by likes) and appends them to
the same review rail as TMDB's, with the author, `created_at` and the spoiler
flag mapped onto `TmdbReview`. Trakt accepts an IMDb id in the comments path;
a TMDB-only title is resolved through `/search/tmdb/{id}` first. Read-only, and
silent on failure: reviews must never block the page.

## Cross-device session (D3)

The session travels as its own `trakt_auth` sync blob, on the same rules the
Simkl session uses (`SimklAuthRules`): it is a full replace, so a profile that
never connected Trakt publishes nothing, and only a deliberate disconnect
publishes a tombstone that may clear a session elsewhere. Trakt differs in two
ways. The blob carries the **refresh token and expiry** as well as the access
token - Trakt's refresh tokens are single-use and its access tokens expire, so an
access-token-only blob would strand the sibling after one window. And the
applier refuses a session that has already expired while the local one is still
live (`TraktAuthRules.shouldAdopt`), which is the stale-overwrite race between a
sibling that has not refreshed yet and one that just did; a reconnect to a
different account always wins regardless. Connect, every refresh and the
disconnect push the blob immediately; adopting one drops the old account's
cached watched snapshot and watched sets, and touches no local history.

## What the build needs

`TRAKT_CLIENT_ID` only, read from `local.properties` or the environment and
exposed as a `BuildConfig` field (`app/build.gradle.kts`); CI passes it from the
**repository** Actions secret `TRAKT_CLIENT_ID`. That is a repository secret,
not an environment secret: the workflow declares no `environment:`, so an
environment-scoped secret would not be visible to it.

**No client secret ships, and none may.** Trakt's current authentication guide
marks the secret deprecated for user sign-in territory ("should only be used
server to server"), the device flow is a public-client flow, and anything
compiled in is recoverable from every published APK. `SimklClientConfigTest`
already fails the build on any `*SECRET*` `BuildConfig` field, and
`TraktWiringContractTest` additionally asserts that no Trakt request carries a
`client_secret`.

Without a client id the row still opens and the page says the build is not
configured - it does not pretend to connect.

## Resume authority (D1)

`GET /sync/playback/{movies,episodes}` is read only when the viewer has chosen
Trakt in Settings → Continue Watching → Resume Source. The rail merges every
source it can reach (this device's history, Simkl, MDBList, Trakt) and the
choice decides whose **position/episode** wins a title they all know - never
which titles or order the rail shows. A source only speaks when it actually has
a resume point (a tracker's NEXT_UP card cannot replace an in-progress
position), and the other sources remain the fallback for titles the chosen one
has not seen, so the rail cannot empty. Trakt reports a percentage rather than a
position, so the millisecond start is derived from TMDB's runtime, exactly as it
is for MDBList's sessions. The choice is a synced display pref, so every device
in the account agrees; every linked tracker still scrobbles and receives watched
marks regardless.

## Not done (deliberately)

- **D2/E2 — posting comments.** There is no submission UI for any provider; the
  read side is E1. Posting needs its own compose surface and is a separate
  change.

D3 (token sync across devices) is done - see the section above.

## Verifying it

```bash
./gradlew :app:compileDebugKotlin
./gradlew ktlintCheck
./gradlew :app:testDebugUnitTest --tests "com.kennyb1201.kbstream.data.trakt.*"
```

The Trakt tests cover the pure rules (`TraktDeviceFlowRulesTest`,
`TraktRateGuardTest`, `TraktWatchStateTest`), the wire field names
(`TraktWireFormatTest`), the phase wiring (`TraktIntegrationContractTest`) and
the phase-A invariants (`TraktWiringContractTest`).

On a device: Settings -> Trakt -> GENERATE CODE, open the verification address,
type the code, and the page should flip to "Signed in as <slug>". Playing a
title should produce scrobble events on the Trakt dashboard, a finished episode
should get a play, and a title watched on Trakt should show its badge here after
the snapshot refresh. Killing the app and returning should show the same account
without a new code (refresh path), and DISCONNECT should leave every Continue
Watching row and badge in place.
