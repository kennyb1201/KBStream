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
| `TraktPollExchange.kt` | one poll: the terminal 200, the save-first ordering and the approved-but-unsaved outcome |
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
3. **A 200-with-token is terminal.** Trakt deletes the device code at the
   moment it answers with a token pair, so that code is never polled again: a
   second request reads `404`, and the flow would report "that code expired"
   over a grant that succeeded. A token that cannot be persisted is therefore
   its own result - `APPROVED` plus `TraktPollResult.saveError` - and the screen
   says "Trakt approved, but the token couldn't be saved on this device" instead
   of retrying a spent code. The poll is also written to the diagnostics before
   any storage work, so a 200 is never missing from the evidence. See
   `TraktPollExchange`.
4. **Nothing on the wire is read by reflection.** Every model in
   `TraktModels.kt` carries `@JsonClass(generateAdapter = true)` (request bodies
   included), so Moshi uses the KSP-generated adapter and never constructs a
   Kotlin class reflectively. That is not tidiness: in 0.9.4's minified release
   build, the reflective adapter for `TraktTokenResponse` threw "This callable
   does not support a default call" on the 200 that carried no `error` fields -
   after Trakt had consumed the code - and the next poll's `404` was reported as
   an expired code. `KotlinJsonAdapterFactory` stays registered last as a
   fallback, and `TraktWireFormatTest` resolves every model with a
   reflection-free `Moshi` so a model that loses its annotation fails the
   build's tests rather than a release.
5. **A poll that threw cannot be called an expiry.** `TraktFlowEvidence`
   records whether anything in this attempt threw and what the last status was;
   a `404` after a throw is reported as "Something went wrong reading Trakt's
   reply" (the throwing poll may have been the 200 that spent the code), while a
   `404` with no throw keeps the old "that code expired" meaning. `409/410` are
   Trakt saying the code is gone either way.
6. **Refresh is proactive.** Access tokens last 7 days; the token is refreshed
   with a day in hand, never on a 401 in front of the first scrobble. Trakt's
   refresh tokens are single-use, so the stored pair is always replaced.
7. **The store is its own file, and encrypted.** `<profile>.trakt_auth` cannot
   collide with `<profile>.simkl_auth` (Diagnostics and the cloud readers
   address stores by name), and there is no plaintext migration path because
   nothing predates it. A file that will not open at all (a keyset an earlier
   build left behind, a restored backup) is deleted and rebuilt ONCE before the
   save is allowed to fail; Simkl's store is untouched by that path.
8. **Disconnect clears Trakt only.** `revokeAndClear()` revokes the grant and
   drops the session keys. Local history, resume rows, watched markers and
   badges are untouched - Simkl's unlink wiping them was audit m10, and the
   contract test asserts this repository cannot reach that data layer at all.
9. **A kids profile needs the parent PIN.** `startConnect()` refuses without
   one, and the flow only begins after `verifyParentPin`; a parentless
   (PIN-less) kids profile is told to set one.
10. **The budget exists before the traffic does.** `TraktRateGuard` is the
   MDBList lesson stated up front: bounded calls per window, `Retry-After`
   honoured, and exhaustion reports "no answer" rather than an empty result.
11. **Watched state is a union, and a union only adds.** Home reads Trakt's
   watched episodes beside Simkl's and MDBList's; the badges test membership in
   all three snapshots. Watched anywhere is watched everywhere, with no
   conflict resolution.
12. **A tracker failure is not "nothing watched".** Every read is guarded on
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

## Connect screen — WATCHED tiles and QR (2026-10-19)

The connect page now shows the same two tiles Simkl's does, labelled
"MOVIES WATCHED" / "SHOWS WATCHED" (Trakt's own noun, not Simkl's "series").
They are the list sizes of `GET /sync/watched/movies` and
`GET /sync/watched/shows`, read once per entry into the connected state under a
single `TraktRateGuard` slot. Any failure - a non-2xx, a transport error, no
token, an exhausted budget, or only one of the two answering - stores null and
hides the row; "Connected to Trakt." is unaffected. This is display-only: Home
and the badges still read the watched snapshot (`watchedSnapshot`), not these
counts.

While linking, both connect screens draw a QR of the verification address with
`qrcodeBitmap()` (the add-on pairing helper, ZXing - no new dependency), on a
white field under the label "SCAN TO OPEN" with the URL printed below in small
text. On TV the QR is 140dp and sits in a Row beside the user code, not above
it: at 200dp+ stacked, the code was pushed below the fold and the D-pad could
not scroll to it. The linking Row is itself a focus target and the page is a
`focusGroup`, so Down from the nav rail lands on the pair (scrolling it into
view) and Down again reaches GENERATE CODE. The phone layouts keep the QR
larger (200dp) and stacked, because their page scrolls by finger.

The user code stays large and separate: neither Trakt's nor Simkl's activation
page pre-fills the code from a query parameter, so the QR only replaces typing
the URL. The QR is drawn only in the linking state (`verificationUrl != null`);
a connected account shows no code.

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
the phase-A invariants (`TraktWiringContractTest`), and the poll's monotonic
clock plus its diagnostics (`TraktPollClockContractTest`, `TraktPollTraceTest`),
the terminal-200 / approved-but-unsaved outcome
(`TraktPollExchangeTest`, `TraktApprovedButUnsavedContractTest`), the codegen
adapters and the live 200 fixture (`TraktWireFormatTest`), the flow-level rule
that stops a parse throw reading as an expiry (`TraktFlowEvidenceTest`), the
store's one-shot rebuild (`TraktTokenStoreRebuildTest`),
the WATCHED tile rule (`TraktWatchedCountsTest`), and the QR/counts placement on
both connect layouts (`TraktConnectSurfacesContractTest`). `QrCodeEncodingTest`
encodes both activation URLs through the shared helper under Robolectric.

If a connect fails, its last polls travel in the in-app Diagnostics report
(Settings -> diagnostics): one `trakt.poll: status=… elapsed=…s expiresIn=…s
state=…` line per poll, newest last, capped at eight and cleared when a new code
is requested. That is the release-safe channel - the signed APK has no debug log
and a TV has no logcat within reach. A debug build additionally prints the same
fields to logcat tag `TraktPoll`. Neither ever logs the device code, the user
code or a token.

On a device: Settings -> Trakt -> GENERATE CODE, scan the QR (or open the
address it prints), type the code, and the page should flip to "Signed in as
<slug>" with the MOVIES/SHOWS WATCHED tiles below it. The same QR appears on
Simkl's connect page while its PIN is pending. Playing a
title should produce scrobble events on the Trakt dashboard, a finished episode
should get a play, and a title watched on Trakt should show its badge here after
the snapshot refresh. Killing the app and returning should show the same account
without a new code (refresh path), and DISCONNECT should leave every Continue
Watching row and badge in place.
