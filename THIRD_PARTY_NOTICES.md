# Third-party notices

KBStream itself is licensed under the GNU General Public License v3.0 or later
(see [`LICENSE`](LICENSE)). This file is a factual inventory of the third-party
code shipped in the APK and the licenses its authors publish it under.

## Copyleft / native components

| Component | Where it comes from | License |
| --- | --- | --- |
| NewPipeExtractor | `com.github.TeamNewPipe:NewPipeExtractor`, pinned to commit `43f8e6eb` (tag `v0.26.4`) | GPL-3.0-or-later |
| mpv (libmpv) | the `mpv-android-lib-libdovi.aar` release AAR from [`fortunasxp/mpv-android-libdovi`](https://github.com/fortunasxp/mpv-android-libdovi) (release `mpv-libdovi-v1`, sha256 `b19d39a7…`), fetched into `app/libs/` by `scripts/fetch_mpv_aar.sh` — this replaced the `Riteshp2001/mpvlibAndroid` `mpvlib-1.1.2.aar`, which in turn replaced the old `dev.jdtech.mpv:libmpv` Maven artifact; neither is a dependency of anything now | The AAR's own Android wrapper is MIT (the mpv-android project, © 2016 Ilya Zhuravlev and sfan5); its `classes.jar`, `libplayer.so`, `libc++_shared.so` and `libxml2.so` are reused from the `io.github.abdallahmehiz:mpv-android-lib` shell (Apache-2.0). The `libmpv.so` and FFmpeg native libraries bundled inside it are **GPL-3.0-or-later**: the release builds its FFmpeg with GPL terms enabled rather than its default LGPL, as the build it replaced did. mpv's own `-Dgpl` is left at its default, so libmpv links a GPLv3 FFmpeg. This is why the AAR's shipped native code is described here as GPLv3, not LGPL. |
| libdovi | statically linked into the `libmpv.so` of the `fortunasxp/mpv-android-libdovi` AAR above (version 3.3.2) | MIT — the same license as the `libdovi.so` this repo builds itself for the ExoPlayer engine's RPU conversion; a statically linked copy inside a GPLv3 library adds no obligation beyond attribution |
| FFmpeg | the video-enabled `media3-ffmpeg-decoder.aar` built by `scripts/build_ffmpeg_video.sh` (this repo's own FFmpeg), and the FFmpeg linked into libmpv | LGPL-2.1-or-later for the media3 extension built here (that build script enables only LGPL-compatible components). The FFmpeg inside libmpv is a different, separately built FFmpeg and is GPL-3.0-or-later — see the mpv row above. |
| libass | linked into libmpv (subtitle typesetting) | ISC |
| libass | also linked into `libassjni.so`, built by `scripts/build_libass.sh`, which is what typesets an `.ass` SIDECAR on the ExoPlayer engine | ISC |
| FreeType | linked into `libassjni.so` by `scripts/build_libass.sh` | FTL or GPL-2.0-or-later (FreeType's own dual license; this build uses the FTL option) |
| HarfBuzz | linked into `libassjni.so` by `scripts/build_libass.sh` | MIT |
| fontconfig | linked into `libassjni.so` by `scripts/build_libass.sh` | MIT-style (fontconfig's own permissive license) |
| FriBidi | linked into `libassjni.so` by `scripts/build_libass.sh` | LGPL-2.1-or-later |
| expat | linked into `libassjni.so` by `scripts/build_libass.sh` (fontconfig's XML parser) | MIT |

There is no copyleft native component in the Dolby Vision path: libdovi is MIT
(see the permissive list below), so `libdovi.so` and `libdovi_bridge.so` add no
obligation beyond the attribution already recorded here.

Because KBStream includes NewPipeExtractor, which is GPLv3, any distributed build
that contains it must be licensed under terms compatible with the GPLv3 and made
available with corresponding source. The project's top-level `LICENSE` is
GPL-3.0-or-later for exactly that reason. The same goes the other way for the
copyleft libraries linked into `libassjni.so` and into libmpv: the LGPL-2.1-or-later
of FriBidi and of the FFmpeg this repo builds itself is compatible with a
GPL-3.0-or-later application, the GPLv3 of the libmpv/FFmpeg pair bundled in the
mpv-android-libdovi AAR is the same license as the app's own, and FreeType is
used under its permissive FTL option rather than its GPL-2.0-only alternative.

## Permissive components

The remaining dependencies are permissively licensed (Apache-2.0 or MIT) and,
apart from attribution, impose no distribution obligations:

- AndroidX (Media3, Room, WorkManager, RecyclerView, Compose, and friends) — Apache-2.0
- Kotlin, kotlinx.coroutines — Apache-2.0
- OkHttp, Moshi — Apache-2.0
- Coil 3 — Apache-2.0
- Ktor client — Apache-2.0
- libdovi (`dolby_vision`, the C API of [quietvoid/dovi_tool](https://github.com/quietvoid/dovi_tool), fetched from crates.io at `3.4.0` and built into `libdovi.so` by `scripts/build_dovi.sh`) — MIT. The `libdovi_bridge.so` next to it is this repo's own `app/src/main/cpp/dovi_bridge.c`, GPL-3.0-or-later like the rest of the app.
- Sentry Android SDK — MIT
- Supabase-kt — MIT
- KSP — Apache-2.0
- desugar_jdk_libs (`com.android.tools:desugar_jdk_libs_nio`) — Apache-2.0

## Trademarks and data sources

Two brand marks ship in the app's UI, both drawn unmodified and both used only
to identify the service they belong to:

- **TMDB** - `app/src/main/res/drawable/ic_tmdb_attribution.xml`, the official
  "blue short" wordmark with its own gradient, taken from the asset TMDB's API
  FAQ links to for an approved logo
  (`https://files.readme.io/29c6fee-blue_short.svg`; its path data is copied
  verbatim, with only the SVG's compressed arc flags separated so Android's path
  parser reads them as the SVG grammar means them). Settings > About draws it
  beside the notice that FAQ requires: "This product uses the TMDB API but is
  not endorsed or certified by TMDB." TMDB's terms forbid changing the mark's
  colour, aspect ratio or geometry and require it to be less prominent than the
  mark that describes the app itself.
- **Simkl** - `app/src/main/res/drawable-xhdpi/ic_simkl_mark.png`, the coloured
  PNG the Simkl API rules publish for badges
  (`https://us.simkl.in/img_favicon/v2/favicon-192x192.png`). About draws it
  beside the one-time "Movie, TV and anime data from Simkl" line those rules
  name, and - because they require attribution to be a link to the SPECIFIC
  item, not a homepage - the Library's Simkl rows and the detail screen's season
  menu send each title to its own page,
  `https://simkl.com/{movies|tv}/<simkl id>[/<slug>]`.

TMDB's mark is a trademark of TMDB, and Simkl's of Simkl; neither is covered by
this project's GPL-3.0-or-later licence.

## How to regenerate this inventory

The authoritative list is the `dependencies` block of `app/build.gradle.kts`,
plus the native components that are not dependencies at all: the mpvlibAndroid
AAR fetched by `scripts/fetch_mpv_aar.sh` (its license follows the build recipe
in that release, not the repo's own MIT wrapper), the FFmpeg AAR produced by
`scripts/build_ffmpeg_video.sh`, the libass/FreeType/HarfBuzz/fontconfig/
FriBidi/expat set linked into `libassjni.so` by `scripts/build_libass.sh`, and
the `dolby_vision` crate plus this repo's `dovi_bridge.c` built into
`libdovi.so`/`libdovi_bridge.so` by `scripts/build_dovi.sh`.
When a dependency is added or upgraded, update the tables above. Dependabot
(`.github/dependabot.yml`) opens the upgrade PRs.
