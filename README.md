<p align="center">
  <img src="docs/brand/kbstream-logo.png" width="420"
       alt="KBStream — Movies · Series · Live TV">
</p>

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
  end-of-episode panels behave exactly as they do in-app. Both engines can
  match the panel's refresh rate to the content: the app tells the platform the
  rate of what it is playing, which is how 24 fps film stops juddering on a
  60 Hz panel, and falls back to asking for a specific display mode when the
  panel does not take the request (Settings → Playback → Match Content Frame
  Rate, off by default, with a read-out of the panel's own modes and of the last
  request under it). Scrub previews are read from the player's own cache instead
  of opening a second connection to the stream.
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
- **Diagnostics** — Settings → About → Copy diagnostics writes a report of the
  build, device, account and sync health, and of where this session's time
  went: the slowest operations, ranked by name. Network time is filed under the
  service that owns the endpoint (TMDB, Simkl, MDBList, Supabase, IPTV) or
  under the addon that owns it, and an endpoint that is nothing but a literal
  address keeps that address beside its label, so a slow call stays fixable.

## Building

Requirements: JDK 17, Android SDK platform API 37 (with SDK Build Tools 36),
and Gradle via the wrapper (Gradle 9.8.0, Android Gradle Plugin 9.4.1, Kotlin
2.4.20, KSP 2.3.12). AGP fetches the platform and build-tools itself once the
SDK licenses are accepted, so only JDK 17 and an Android SDK install are
strictly needed.

1. Copy your API keys into `local.properties` at the repo root (gitignored):

   ```properties
   TMDB_API_KEY=...
   SIMKL_CLIENT_ID=...
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

### Optional: software video decoding

ExoPlayer's software video path needs a VIDEO-enabled build of media3's FFmpeg
decoder extension, and there is no published artifact left to fall back on: the
extension subclasses media3's decoder internals, so it only works compiled
against the exact media3 release on the classpath, and the one prebuilt
available (`org.jellyfin.media3:media3-ffmpeg-decoder`) stopped at `1.9.0+1`
and carried audio decoders only. So the AAR this repo builds itself is the only
source:

```sh
# needs JDK 17, an Android SDK, and NDK r28c (28.2.13676358)
scripts/build_ffmpeg_video.sh
# -> libs/media3-ffmpeg-decoder.aar
./gradlew assembleDebug
```

You do **not** need an NDK on your own machine. The `Build KBStream APK`
workflow runs the script in a cached step before the APK builds and uploads the
result as the `media3-ffmpeg-decoder-video` artifact, so every published release
ships software video decoding. The step after it fails the job if the AAR is
missing, so a release without one cannot go out unnoticed — **download that
artifact from any green run and drop it in `libs/`** instead of compiling it
yourself. The AAR is built from the tag in `MEDIA3_TAG`, which must match the
media3 version in `app/build.gradle.kts`.

That step also needs NDK r28c, not the r26b upstream's own README still names:
media3 1.10 moved 16 KB ELF alignment off the `-Wl,-z,max-page-size=16384`
linker flag and onto the NDK's `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` property,
which r27 and newer honour and older NDKs silently ignore. Under r26 the
library is linked for 4 KB pages and crashes on Android 15's 16 KB page-size
devices, so the script refuses to hand over an AAR whose 64-bit libraries are
not 16 KB aligned.

Without the AAR `app/build.gradle.kts` logs a warning and builds anyway:
ExoPlayer has no software video decoder and none of the audio codecs MediaCodec
does not guarantee (AC-3/E-AC-3, DTS, TrueHD, MP2, WMA, AC-4), and the MPV
engine — which bundles its own FFmpeg — is what covers those titles.

`libs/` is gitignored: the AAR is a build product, not a source artifact.

The extension ships its FFmpeg as `libffmpegJNI.so`, which is why it can coexist
with libmpv — see the FFmpeg block in `app/build.gradle.kts` for why the other
prebuilt video extensions cannot.

## Running tests

JVM unit tests cover the Kids Mode rating matrix, legacy value migration, the
kids catalog invariants, the player's refresh-rate matching, and the background
workers' retry policies:

```sh
./gradlew testDebugUnitTest
```

The suite also covers the Room migration path (see `data/history`): a real
SQLite database is built at an older version, migrated, and validated against
the committed schema. That runs under Robolectric, so it is still part of
`testDebugUnitTest` and still needs no device.

CI (`.github/workflows/build.yml`) runs `ktlintCheck`, the tests and Android
Lint, then builds the debug and signed release APKs on every push to `main`.
Nothing is published by a push to `main` — see Releases below.

## Releases

The self-update feed (Settings → Check for updates) is the GitHub release
tagged `latest`, which carries the signed APK plus a `metadata.json` the app
reads for its `versionCode` and the APK's SHA-256. Replacing it replaces what
every install is offered, so it is only replaced deliberately:

- **push to `main`** — gates and APK artifacts only. Nothing is published.
- **push a version tag** (`git push origin v0.2.123`) or run the `Build
  KBStream APK` workflow by hand — this publishes.

`VERSION_CODE` is the workflow run number, which is strictly increasing but is
*not* a commit order: re-running an older commit produces a higher number. The
publish steps therefore refuse to run unless the versionCode is above the
published one **and** the commit that is currently published is already
contained in the commit being published, so an old build can never be offered
as an upgrade. The commit check stays inert until a release published by this
workflow is in place (the guard step says so in the log); the versionCode check
applies from the first publish.

A publish also writes the notes the app shows. The release body *is* the
update text: the workflow composes it from the commit subjects between the
published build and this one — capped, and taken from the same `gitSha` the
guard reads — and Settings → Check for updates renders that body under the
version line.

## Linting

Two gates with different jobs. Both run in CI, both fail the build on an error:

```sh
./gradlew ktlintCheck    # fails on an import nothing references
./gradlew ktlintFormat   # removes them
./gradlew lintDebug      # Android Lint (CI only, see below)
```

Android Lint is configured in the `lint` block of `app/build.gradle.kts`:
errors fail the run, warnings are reported and uploaded as an artifact. CI runs
it on every push. Locally it is slow and memory-hungry on a tree this size (a
few minutes, and it has OOM-killed the Gradle daemon on a small container — it
wants the build caches present and disk headroom for its report), which is why
it is not part of the edit/build loop even though it does run here.

`app/lint.xml` carries the one project-wide exception. Most of what the first
run reported was `UnsafeOptInUsageError` on media3: `@UnstableApi` is an
`androidx.annotation` opt-in marker whose granularity is the library, and this
app uses that library throughout (ExoPlayer, the audio processors, the
extractors factory, the custom data sources), so the opt-in is declared once
for the project — the mechanism the marker documents — instead of on each of
those call sites. The check itself stays enabled, so an opt-in marker
introduced later still fails the build until somebody opts in where it
belongs. The handful of remaining errors are either a `@SuppressLint` with the
reason written above it (see `MainActivity.dispatchKeyEvent`,
`NotificationCenter`) or a real fix (`IptvRepository`'s byte-order mark,
`HomeViewModel`'s suspicious indentation).

Exactly one ktlint rule is enabled — unused imports (see `.editorconfig`).
Everything else is off on purpose: this tree predates the formatter, so turning
the rest on would either fail on day one or invite a whole-tree reformat nobody
could review. The single rule is the one piece of junk the compiler accepts in
silence, which is how several hundred of them accumulated before they were
swept out by hand. CI runs `ktlintCheck` before the tests.

## Baseline profile

The release build consumes a baseline profile: the classes and methods Android
touches during startup and first-frame work, which ART compiles ahead of time
instead of interpreting. On a TV box the difference is visible — all of the
cold-start cost (Application.setup's WorkManager enqueues and Coil loader,
then the Home rail fan-out) lands in the first seconds.

```sh
# 1. the device: adb devices must list it (TV: enable developer options and
#    either USB debugging, or `adb connect <tv-ip>:5555`)
# 2. signing: the generator INSTALLS the app, and the variant it installs
#    inherits the release signing config, which is empty unless the KBSTREAM_*
#    variables are set - point them at the debug keystore (no secrets needed):
export KBSTREAM_STORE_FILE="$HOME/.android/debug.keystore"
export KBSTREAM_STORE_PASSWORD=android
export KBSTREAM_KEY_ALIAS=androiddebugkey
export KBSTREAM_KEY_PASSWORD=android
# 3.
./gradlew :app:generateBaselineProfile
```

(Only do this in the shell you generate from: with those variables set, a local
`assembleRelease` is debug-signed too. CI supplies its own keystore.)

- `baselineprofile/` is a test-only module that generates it: it drives the app
  on the device and writes `app/src/release/generated/baselineProfiles/baseline-prof.txt`,
  which is committed (`baselineProfile { saveInSrc = true }` in
  `app/build.gradle.kts`).
- To confirm the profile reached an APK:
  `unzip -l app/build/outputs/apk/release/app-release.apk | grep baseline` —
  it ships as `assets/dexopt/baseline.prof`.
- `androidx.profileinstaller` in the app is what *installs* the profile on the
  versions that need it: Android compiles a profile at install time when Play
  installs the app, and this app installs itself from a GitHub release.

Nothing fails when the file is absent — the app just gets the slower,
interpreted path — so the generator needs no CI step and no managed device.
Re-run it when startup changes, or when a screen on the startup path does.

## Project layout

```
app/src/main/java/com/kennyb1201/kbstream/
  data/            # TMDB, addons, Simkl, IPTV, Supabase sync,
                   # profile management, watch history (Room)
  domain/          # stream engine (ranking, auto-select)
  ui/              # Compose for Android TV screens (home, detail, player,
                   # search, profiles, settings, addons, IPTV, streams)
  work/            # background workers (EPG refresh, Simkl sync, addons), plus
                   # WorkPolicies: the retry and reminder-arming decisions they
                   # make, which is the part of this package a JVM test can reach
baselineprofile/   # baseline profile generator (test-only module, ships nothing)
app/schemas/       # exported Room schema JSON, one per released watch-history
                   # version: the record a Migration is reviewed against.
                   # Watch history is user data (resume points, watched state),
                   # so its schema is exported and committed and its versions
                   # have real migrations; the guide database is a cache and is
                   # deliberately rebuilt instead.
scripts/           # TMDB id-verification probes used while curating the
                   # search catalog (read TMDB_API_KEY from the environment),
                   # plus the dev tools behind the rating chips' vector marks
                   # (font_glyphs.py draws a wordmark from a bundled TTF,
                   # svg_preview.py renders a drawable's path as ASCII).
                   # render_brand_assets.py is the one source of truth for the
                   # branding -- the play-button mark, the TV banner, the
                   # icons, the lockup and the two store listing plates (a
                   # 1280x720 banner and a 1024x500 feature graphic, both laid
                   # out from the same plate as the TV banner) -- rasterizing
                   # them to res/ and to docs/brand/ (run it after any design
                   # change; nothing is hand-edited). The launcher banner ships
                   # in two forms -- translucent (what android:banner points
                   # at, inset so a launcher's rounded, scaled-up card cannot
                   # clip it) and @drawable/tv_banner_plate, the same lockup on
                   # the opaque plate. `--preview
                   # banner|plate|promo|icon|logo|mark|round` prints one as
                   # ASCII. patch_source.py applies a verified edit spec (a
                   # JSON list of anchored find/replace pairs, hard-erroring
                   # unless every anchor matches exactly) and split_kotlin.py
                   # moves whole top-level declarations into a sibling file:
                   # both exist because this tree has declarations past the
                   # size one editor tool call can reach. Their docstrings
                   # carry the byte offsets that measured it.
                   # migrate_runcatching.py moves `runCatching` over suspending
                   # work to runCatchingCancellable: it rewrites the sites where
                   # it can prove nothing runs after the expression, and reports
                   # the rest - a reviewer names the ones that are safe to
                   # convert with --approve, and the few that must keep plain
                   # runCatching say so in a comment above them. See
                   # data/RunCatchingCancellable.kt for what the difference is.
supabase_profiles.sql  # optional dashboard table for inspecting profiles
docs/              # Supabase RLS + Realtime SQL reference
                   #   supabase_sync_rls.sql  -- schema + per-user RLS policies
                   #   supabase_realtime.sql -- enable live sync (publication)
```

## Notes

- Content filtering assumes the US region (TMDB certifications, watch
  providers, and the kids rating scale are US-specific).
- The `scripts/tmdb_*.py` probes expect `TMDB_API_KEY` exported in the
  environment; they are development tools, not part of the app.
- `scripts/build_ffmpeg_video.sh` builds the optional video-enabled FFmpeg
  decoder extension (see Building → Optional: software video decoding). It
  needs an NDK, which the normal Gradle build does not.

## License

KBStream is licensed under the **GNU General Public License v3.0 or later**
(GPL-3.0-or-later); the full text is in [`LICENSE`](LICENSE).

That is not a free choice here: the app bundles NewPipeExtractor, which is
GPLv3, so any distributed build has to carry GPL-compatible terms and ship
corresponding source. [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) lists
the other bundled components (mpv, FFmpeg, libass, and the permissive
libraries) with their licenses.
