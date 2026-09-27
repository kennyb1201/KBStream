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

Because KBStream includes NewPipeExtractor, which is GPLv3, any distributed build
that contains it must be licensed under terms compatible with the GPLv3 and made
available with corresponding source. The project's top-level `LICENSE` is
GPL-3.0-or-later for exactly that reason.

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

The authoritative list is the `dependencies` block of `app/build.gradle.kts`
(plus the FFmpeg AAR produced by `scripts/build_ffmpeg_video.sh`). When a
dependency is added or upgraded, update the tables above. Dependabot
(`.github/dependabot.yml`) opens the upgrade PRs.
