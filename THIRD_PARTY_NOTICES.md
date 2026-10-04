# Third-party notices

KBStream itself is licensed under the GNU General Public License v3.0 or later
(see [`LICENSE`](LICENSE)). This file is a factual inventory of the third-party
code shipped in the APK and the licenses its authors publish it under.

## Copyleft / native components

| Component | Where it comes from | License |
| --- | --- | --- |
| NewPipeExtractor | `com.github.TeamNewPipe:NewPipeExtractor`, pinned to commit `43f8e6eb` (tag `v0.26.4`) | GPL-3.0-or-later |
| mpv (libmpv) | `dev.jdtech.mpv:libmpv:0.5.1` | LGPL-2.1-or-later (mpv is LGPL-2.1+ with some optional GPL parts) |
| FFmpeg | the video-enabled `media3-ffmpeg-decoder.aar` built by `scripts/build_ffmpeg_video.sh`, and the FFmpeg linked into libmpv | LGPL-2.1-or-later for the libraries as built here (FFmpeg can be built as GPL; the build script enables only LGPL-compatible components) |
| libass | linked into libmpv (subtitle typesetting) | ISC |
| libass | also linked into `libassjni.so`, built by `scripts/build_libass.sh`, which is what typesets an `.ass` SIDECAR on the ExoPlayer engine | ISC |
| FreeType | linked into `libassjni.so` by `scripts/build_libass.sh` | FTL or GPL-2.0-or-later (FreeType's own dual license; this build uses the FTL option) |
| HarfBuzz | linked into `libassjni.so` by `scripts/build_libass.sh` | MIT |
| fontconfig | linked into `libassjni.so` by `scripts/build_libass.sh` | MIT-style (fontconfig's own permissive license) |
| FriBidi | linked into `libassjni.so` by `scripts/build_libass.sh` | LGPL-2.1-or-later |
| expat | linked into `libassjni.so` by `scripts/build_libass.sh` (fontconfig's XML parser) | MIT |

Because KBStream includes NewPipeExtractor, which is GPLv3, any distributed build
that contains it must be licensed under terms compatible with the GPLv3 and made
available with corresponding source. The project's top-level `LICENSE` is
GPL-3.0-or-later for exactly that reason. The same goes the other way for the
copyleft libraries linked into `libassjni.so` and into libmpv: LGPL-2.1-or-later
(FriBidi, libmpv, FFmpeg) is compatible with a GPL-3.0-or-later application, and
FreeType is used under its permissive FTL option rather than its GPL-2.0-only
alternative.

## Permissive components

The remaining dependencies are permissively licensed (Apache-2.0 or MIT) and,
apart from attribution, impose no distribution obligations:

- AndroidX (Media3, Room, WorkManager, RecyclerView, Compose, and friends) — Apache-2.0
- Kotlin, kotlinx.coroutines — Apache-2.0
- OkHttp, Moshi — Apache-2.0
- Coil 3 — Apache-2.0
- Ktor client — Apache-2.0
- Sentry Android SDK — MIT
- Supabase-kt — MIT
- KSP — Apache-2.0
- desugar_jdk_libs (`com.android.tools:desugar_jdk_libs_nio`) — Apache-2.0

## How to regenerate this inventory

The authoritative list is the `dependencies` block of `app/build.gradle.kts`,
plus the native components that are not dependencies at all: the FFmpeg AAR
produced by `scripts/build_ffmpeg_video.sh` and the libass/FreeType/HarfBuzz/
fontconfig/FriBidi/expat set linked into `libassjni.so` by
`scripts/build_libass.sh`. When a dependency is added or upgraded, update the
tables above. Dependabot
(`.github/dependabot.yml`) opens the upgrade PRs.
