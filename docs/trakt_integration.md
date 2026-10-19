# Trakt integration — Phase A landed

Trakt is the app's third tracker, beside Simkl and MDBList, not a replacement.
Phase A is authentication only: connect, keep the token fresh, disconnect. The
scrobble, watched-union, list and review phases land at their own call sites and
are not in this change.

## What exists

**Data** (`app/src/main/java/com/kennyb1201/kbstream/data/trakt/`)

| File | What it owns |
|---|---|
| `TraktConfig.kt` | base URL, API version, activation URL, client id, optional secret |
| `TraktModels.kt` | device-code/token wire shapes and the OAuth request bodies |
| `TraktApiService.kt` | `oauth/device/code`, `oauth/device/token`, `oauth/token`, `oauth/revoke`, `users/settings`; header interceptor |
| `TraktDeviceFlowRules.kt` | the poll state machine, expiry, interval widening, refresh window (pure) |
| `TraktTokenStore.kt` | per-profile `SecureTokenStore` under `<profile>.trakt_auth` |
| `TraktRateGuard.kt` | request budget, `Retry-After` park, synthetic-429 refusal |
| `TraktRepository.kt` | the flow, proactive refresh, account read, disconnect |

**UI** (`ui/trakt/`, `ui/mobile/TraktConnectScreenMobile.kt`)

One state (`TraktUiState`), one view model (`TraktViewModel`), two layouts:
the TV page and the touch twin, both routed through `Screen.Trakt` and reached
from a **Trakt** row beside **Simkl** in Settings on both form factors.

## The rules this phase is built around

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

## What the build needs

`TRAKT_CLIENT_ID` only, read from `local.properties` or the environment and
exposed as a `BuildConfig` field (`app/build.gradle.kts`); CI passes it from a
repository secret.

**No client secret ships, and none may.** Trakt's current authentication guide
marks the secret deprecated for user sign-in territory ("should only be used
server to server"), the device flow is a public-client flow, and anything
compiled in is recoverable from every published APK. `SimklClientConfigTest`
already fails the build on any `*SECRET*` `BuildConfig` field, and
`TraktWiringContractTest` additionally asserts that no Trakt request carries a
`client_secret`.

This is the phase's one open risk. Older Trakt documentation describes
`client_secret` in the device-token body. If Trakt rejects the secret-less
request, the poll is classified as `MISCONFIGURED` (not "waiting") and the page
says the build's client id was rejected - the remedy is a small server-side
exchange, because a shipped secret is not one.

Without a client id the row still opens and the page says the build is not
configured - it does not pretend to connect.

## Not done in this phase (deliberately)

- **Token sync across devices (D3).** The plan's default is "same as Simkl",
  which means a per-profile cloud blob. Simkl's blob is a bespoke key in
  `PrefsPayloadBuilder`/`SyncPrefsPayload` with its own apply and tombstone
  path; adding a second one is a cross-cutting change to shared sync code, so
  Phase A ships local-only and the Trakt session does not leave the device. This
  is the one departure from the plan's default and it is visible here rather
  than implied.
- **Phase B (scrobbling), C (watched union), D (lists), E (reviews).** Each is
  its own call site and its own gate, in the plan's relay order.

## Verifying it

```bash
./gradlew :app:compileDebugKotlin
./gradlew ktlintCheck
./gradlew :app:testDebugUnitTest --tests "com.kennyb1201.kbstream.data.trakt.*"
```

On a device: Settings -> Trakt -> GENERATE CODE, open the verification address,
type the code, and the page should flip to "Signed in as <slug>". Killing the app
and returning should show the same account without a new code (refresh path),
and DISCONNECT should leave every Continue Watching row and badge in place.
