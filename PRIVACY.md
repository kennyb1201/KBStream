# KBStream Privacy Policy

**Effective date:** 12 October 2026
**Applies to:** the KBStream Android application (`com.kennyb1201.kbstream`) and
its optional companion sync service.

KBStream is a media streaming client for Android TV, Fire TV and phones. It has
no advertising, no user tracking, and no analytics SDK. This policy says exactly
what the app does with your data, which is what the code actually does.

Contact for anything in this policy: **kennykenny1201@gmail.com**,
or open an issue at <https://github.com/kennyb1201/KBStream/issues>.

---

## 1. Summary

- **No ads, no ad ID, no tracking, no data selling.** The app contains no
  advertising SDK and no third-party analytics SDK.
- **Everything is optional.** Core playback and browsing work with no account
  and no sign-in.
- **Two things leave your device automatically:** crash reports (Sentry), and —
  only if you sign in — your library/history/settings (Supabase sync). Both are
  described in full below.
- **Your credentials never leave your device** except to the service they belong
  to (your IPTV provider, your Simkl account, your addons). They are stored in
  the app's private storage.

---

## 2. Data the app handles

### 2.1 Stored on your device only (never sent to us)

- **API keys and credentials** you enter: TMDB, Simkl, MDBList, OMDb, and the
  bundled Supabase anon key. Stored in the app's private storage.
- **IPTV playlist / EPG / Xtream credentials** you enter. Used only to fetch
  your own playlists and guide data from your own provider.
- **Stremio-style addon URLs** you install, and the collections and custom
  catalogs you build.
- **Watch history, resume positions, watched state, preferences, Kids-Mode
  settings, and profiles** — unless you sign in to sync (below).
- **The episode-numbering scheme** the player learns for a show.

Clearing the app's data or uninstalling the app deletes all of the above from
your device.

### 2.2 Crash and performance reports (Sentry) — automatic

If the build includes a Sentry DSN, the app reports **crashes, native crashes,
and non-fatal errors** to Sentry, and samples **10% of performance traces**.
Each report contains:

- the error/exception and its stack trace;
- the app version, build number, and short commit hash;
- the device model, Android/API level, and ABI;
- breadcrumbs of recent app activity.

**What is deliberately NOT sent:** no name, no email, no account identity — the
app never calls `setUser`, so reports are not tied to your account or device by
us. Free-text in messages and breadcrumbs is scrubbed on the way out: bearer
tokens, passwords/usernames in URLs, API keys and JWTs are masked by
`data/reporting/Redaction.kt` before the event leaves the device.

Sentry processes the data as our service provider; see
<https://sentry.io/privacy/>. Reports are retained per Sentry's retention
settings (30 days by default). A build with no DSN baked in sends nothing.

### 2.3 Account and cross-device sync (Supabase) — only if you sign in

Signing in is optional. When you create an account or sign in, the app uses
**Supabase** (your own Supabase project, or the one configured for this build)
to:

- authenticate you with an **email address and password** (Supabase Auth); and
- store, per account and protected by row-level security, the data it syncs:
  **watch history and resume positions, watched state, your profiles (names,
  avatars, and any Kids-Mode settings), installed addons, collections, your
  poster/backdrop artwork choices, and app settings**; plus the sessions of the
  trackers you link (Simkl, Trakt) and the service API keys you paste (MDBList,
  TorBox, OpenSubtitles), so a second device comes up already connected.

This data is associated with your account and is what makes "continue watching"
and setup follow you between devices. Password handling is done by Supabase Auth;
the app does not store your password. The sync schema and its row-level-security
policies are in `docs/supabase_sync_rls.sql` and `docs/supabase_realtime.sql`.

Supabase processes this data as our service provider; see
<https://supabase.com/privacy>.

### 2.4 Third-party services you choose to connect

When a feature needs content or a service you asked for, the app contacts:

| Service | What it is used for | What is sent |
| --- | --- | --- |
| TMDB | Titles, artwork and metadata | Lookup queries (the build's API key) |
| Simkl | Watch history / scrobbling, if you link it | Your Simkl token and what you watch |
| Trakt | Watch history / scrobbling and reviews, if you link it | Your Trakt session (access and refresh tokens) and what you watch |
| MDBList | Ratings, if you provide a key | Your key and the titles looked up |
| TVmaze / ESPN | Air dates and sports scores | Lookup queries |
| YouTube | Trailers (via NewPipeExtractor) | The trailer lookup |
| Your addons | Stream sources you installed | The request your addon makes |
| Your IPTV provider | Live TV and EPG from the playlist you entered | Requests to your provider, with your provider credentials |
| GitHub Releases | In-app update check and download | The app version |

These third parties handle the request under their own privacy policies. Only
the data each feature needs is sent.

---

## 3. What the app does NOT collect

- No precise or approximate **location**.
- No **contacts**, calendar, SMS, or call data.
- No **advertising identifier** and no ad profiling.
- No **account or device fingerprinting** beyond the crash metadata above.
- We do **not sell or rent** your data to anyone.

---

## 4. Children

KBStream's **Kids Mode** is a parental control inside the app (a rating ceiling,
an optional Live TV channel filter, an optional daily watch-time limit, and an
optional bedtime lock, with a parent PIN). It is not a separate "app for
children" directed at children, and the app does not knowingly collect personal
information from children. On Google Play, use the content-rating and target-
audience declarations that match how you actually ship the app.

---

## 5. Security and retention

- Credentials are kept in the app's private storage; on official builds the
  Android Keystore-backed encryption is used where available.
- Crash reports are retained by Sentry for its default period (usually 30 days).
- Synced account data is retained until you delete it (see below).

---

## 6. Your choices and deletion

- **No account, no sync:** just do not sign in; nothing syncs.
- **Stop crash reports:** use a build with no Sentry DSN, or clear the app data
  to stop the current install reporting.
- **Remove local data:** Android Settings → Apps → KBStream → Storage → Clear
  data, or uninstall the app.
- **Delete synced (cloud) data:** sign out stops further syncing. To delete the
  account's cloud rows entirely, contact us at the address above (or, where the
  app exposes it, use account/row deletion there) and we will remove them. Deleting
  the account removes your synced history, watched state, profiles, addons,
  collections, and settings from Supabase.
- **Request a copy or correction** of your synced data via the same contact.

---

## 7. Changes to this policy

If this policy changes, the effective date above is updated and the new version
is committed with the source. Material changes will also be noted in the
release notes.

---

## 8. Contact

**KB / kennykenny1201@gmail.com**
<https://github.com/kennyb1201/KBStream/issues>
