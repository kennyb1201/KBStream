# KBStream

A media streaming app for Android TV / Fire TV. Discovers movies and series
via TMDB, resolves playback through Stremio-style addons and KB
collections, tracks watching across devices via Simkl and Supabase sync, and
plays it all in a native ExoPlayer-based player (including IPTV live TV with
EPG).

## Features

- **Discover / Home** — TMDB-powered rails by genre, network, studio,
  collection, decade, and streaming service, plus addon catalogs and KB
  collections; Continue Watching and an Upcoming-episodes rail.
- **Search** — unified TMDB + addon search with a browsable catalog
  (genres, keywords, services, networks, studios, collections, decades).
- **Player** — native player with stream ranking, auto-select, resume,
  next-episode autoplay, Dolby Vision compat layer, and custom subtitle
  styling. A second engine (MPV/libmpv, Settings → Playback engine) backs it
  up: it is used as the fallback when ExoPlayer cannot play a stream at all —
  the box has no decoder left to hand out, or the codec has no decoder — and
  can be selected outright for files only libmpv handles (including fansub
  ASS/SSA typesetting). Both engines write the same watch history, scrobbles
  and Continue Watching rows, and both raise the same two end-of-episode
  panels — the Up Next card, and the because-you-watched recommendations when
  there is no next episode — each with its own on/off switch and pop-up point
  (a percentage of the runtime) in Settings → Playback. A third engine, an
  installed external player (VLC, MX Player, Kodi), can be chosen for a title
  or from either in-player bar: the stream is handed over while KBStream keeps
  the session, so watch history, Continue Watching, scrobbling and both
  end-of-episode panels behave exactly as they do in-app.
- **Profiles** — multiple per-device profiles with avatars, optional PIN
  locks, and full cross-device sync (history, watched state, addons,
  settings) via Supabase with row-level security.
- **Kids Mode** — per-profile rating ceiling (PG-13 / PG / G "or lower")
  enforced on every content surface: search, discover rails, collections,
  actor filmographies, detail recommendations, and Continue Watching /
  Upcoming (including account-wide Simkl items). Optional per-profile
  toggles: hide addon management, kid-safe Live TV channel filter, PIN to
  leave the profile, daily watch-time limit, and a bedtime lock — each with
  parent-PIN override where relevant.
- **Integrations** — Simkl (watch history sync + live scrobbling),
  MDBList ratings, IPTV (M3U + XMLTV EPG), YouTube trailers.

## Building

Requirements: JDK 17, Android SDK (API 35), and `gradle` via the wrapper.

1. Copy your API keys into `local.properties` at the repo root (gitignored):

   ```properties
   TMDB_API_KEY=...
   SIMKL_CLIENT_ID=...
   SIMKL_CLIENT_SECRET=...
   MDBLIST_API_KEY=...
   SUPABASE_URL=...
   SUPABASE_ANON_KEY=...
   SENTRY_DSN=...
   ```

   Environment variables with the same names work too (that's what CI uses).

2. Build a debug APK:

   ```sh
   ./gradlew assembleDebug
   ```

3. Release builds additionally read `KBSTREAM_STORE_FILE`,
   `KBSTREAM_STORE_PASSWORD`, `KBSTREAM_KEY_ALIAS`, and
   `KBSTREAM_KEY_PASSWORD` (or a CI-provided keystore) for signing, and run
   R8 with `proguard-rules.pro`.

## Running tests

JVM unit tests cover the Kids Mode rating matrix, legacy value migration,
and the kids catalog invariants:

```sh
./gradlew testDebugUnitTest
```

CI (`.github/workflows/build.yml`) runs the tests, then builds the debug
and signed release APKs on every push to `main`.

## Project layout

```
app/src/main/java/com/kennyb1201/kbstream/
  data/            # TMDB, addons, Simkl, IPTV, Supabase sync,
                   # profile management, watch history (Room)
  domain/          # stream engine (ranking, auto-select)
  ui/              # Compose for Android TV screens (home, detail, player,
                   # search, profiles, settings, addons, IPTV, streams)
  work/            # background workers (EPG refresh, Simkl sync, addons)
scripts/           # TMDB id-verification probes used while curating the
                   # search catalog (read TMDB_API_KEY from the environment),
                   # plus the dev tools behind the rating chips' vector marks
                   # (font_glyphs.py draws a wordmark from a bundled TTF,
                   # svg_preview.py renders a drawable's path as ASCII)
supabase_profiles.sql  # optional dashboard table for inspecting profiles
docs/              # Supabase RLS policy reference
```

## Notes

- Content filtering assumes the US region (TMDB certifications, watch
  providers, and the kids rating scale are US-specific).
- The `scripts/tmdb_*.py` probes expect `TMDB_API_KEY` exported in the
  environment; they are development tools, not part of the app.
