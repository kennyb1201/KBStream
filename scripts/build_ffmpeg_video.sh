#!/usr/bin/env bash
#
# Build a VIDEO-ENABLED media3 FFmpeg decoder extension and drop it at
# libs/media3-ffmpeg-decoder.aar, where app/build.gradle.kts picks it up
# automatically (see the "FFmpeg decoder extension" block there).
#
# Why this exists
# ---------------
# media3's decoder_ffmpeg extension is what lets ExoPlayer decode in software.
# The published org.jellyfin.media3:media3-ffmpeg-decoder artifact is exactly
# that extension, but built with AUDIO decoders only, so its video renderer
# answers UNSUPPORTED for every video mime and never claims a track (the app
# relies on that: SplitModeRenderersFactory already runs the video extension
# renderer in EXTENSION_RENDERER_MODE_ON, so the software video decoder joins
# behind MediaCodec the moment a build actually carries video decoders).
# Turning software video decode on therefore means building this AAR ourselves,
# with the video decoders appended to media3's FFmpeg configure line.
#
# The result ships libffmpegJNI.so, which is what lets it coexist with libmpv
# (dev.jdtech.mpv:libmpv). libmpv already brings libavcodec.so / libavutil.so /
# libswresample.so / libswscale.so of its own, so any extension that ships
# THOSE sonames (NextLib, the ExoPlayer-video-extension projects) cannot be
# linked into the same APK: two dependencies providing one native library name
# either fail the build or silently keep a single copy.
#
# Requirements
# ------------
#   * Linux or macOS (media3 does not support building this module on Windows)
#   * JDK 17 on PATH
#   * Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT) with platform-tools
#   * Android NDK r26b (26.1.10909125) -- see ANDROID_NDK below
#   * several CPUs and ~10 GB free disk: the four ABIs of FFmpeg are the slow
#     part (tens of minutes cold; the static libs are cacheable)
#
# Usage
# -----
#   scripts/build_ffmpeg_video.sh
#
# Overridable environment:
#   MEDIA3_TAG=1.9.0        androidx/media git tag to build. MUST match the
#                           media3 version in app/build.gradle.kts.
#   FFMPEG_TAG=release/6.0  FFmpeg branch media3's build_ffmpeg.sh expects
#   ANDROID_NDK=/path       NDK root (default: $ANDROID_HOME/ndk/26.1.10909125)
#   ANDROID_API=23          native API level (must be <= the app's minSdk 23)
#   WORK_DIR=<dir>          scratch dir (default: build/ffmpeg-ext, gitignored)
#   OUTPUT=<file>           AAR destination
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MEDIA3_TAG="${MEDIA3_TAG:-1.9.0}"
FFMPEG_TAG="${FFMPEG_TAG:-release/6.0}"
ANDROID_API="${ANDROID_API:-23}"
NDK_VERSION="${NDK_VERSION:-26.1.10909125}"
WORK_DIR="${WORK_DIR:-${REPO_ROOT}/build/ffmpeg-ext}"
OUTPUT="${OUTPUT:-${REPO_ROOT}/libs/media3-ffmpeg-decoder.aar}"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_NDK="${ANDROID_NDK:-${ANDROID_SDK:+${ANDROID_SDK}/ndk/${NDK_VERSION}}}"

# Audio decoders as the published Jellyfin build carries them, so the software
# AUDIO path is unchanged by this swap, PLUS the audio decoders Android's
# MediaCodec does NOT guarantee and the original list therefore left silent:
#   mp2     MPEG-1 Layer II, the default audio in DVB/IPTV transport streams
#   wmav1/2 WMA (the audio side of WMV files whose video side already decodes
#           as wmv3/vc1 - without these the video plays with no sound)
#   wmapro  WMA Pro
#   opus    WebM/YouTube audio, in case a box omits its MediaCodec decoder
#   vorbis  OGG audio, same reasoning
#   ac4     ATSC 3.0 audio, which nothing on Android decodes
# When no renderer supports an audio track, ExoPlayer DISABLES it and keeps
# playing SILENTLY - it throws nothing, so the ExoPlayer->MPV fallback never
# fires and these decoders are the only thing that makes such tracks audible.
#
# Plus the video decoders the hardware MediaCodec path cannot cover:
# AVI/DivX (MPEG-4 ASP, MS-MPEG4 v3), WMV/VC-1, MPEG-1/2, 10-bit AVC and
# HEVC, VP8/VP9, AV1, Theora, H.263.
#
# "flv1" is deliberately absent: it names the FLV1 container/format tag, not an
# FFmpeg decoder, so --enable-decoder=flv1 matched nothing and only made
# configure print "Option --enable-decoder=flv1 did not match anything". FLV1
# video is H.263, which the h263 entry below already covers.
#
# Keep this list in sync with the comment on the FFmpeg block in
# app/build.gradle.kts. media3's build_ffmpeg.sh builds with --disable-everything
# and then one --enable-decoder per entry, so this list IS the decoder set.
ENABLED_DECODERS=(
  flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd
  mp2 wmav1 wmav2 wmapro opus vorbis ac4
  h264 hevc mpeg2video mpeg1video mpeg4 msmpeg4v3 wmv3 vc1
  vp8 vp9 av1 theora h263
)

log() { printf '\n=== %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- Preconditions ---------------------------------------------------------

command -v git >/dev/null || die "git is not on PATH"
command -v java >/dev/null || die "java is not on PATH (JDK 17 required)"
[[ -n "$ANDROID_NDK" ]] || die "set ANDROID_NDK (or ANDROID_HOME) to an NDK r26b root"
[[ -d "$ANDROID_NDK" ]] || die "NDK not found at $ANDROID_NDK"
if [[ -z "$ANDROID_SDK" || ! -d "$ANDROID_SDK" ]]; then
  die "set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK root"
fi

case "$(uname -s)" in
  Linux)  HOST_PLATFORM="linux-x86_64" ;;
  Darwin) HOST_PLATFORM="darwin-x86_64" ;;
  *)      die "unsupported host $(uname -s): media3 builds this module on Linux/macOS only" ;;
esac

log "media3 $MEDIA3_TAG / ffmpeg $FFMPEG_TAG / API $ANDROID_API on $HOST_PLATFORM"
log "NDK $ANDROID_NDK"

# --- Checkout --------------------------------------------------------------

MEDIA3="${WORK_DIR}/media"
FFMPEG_MODULE_PATH="${MEDIA3}/libraries/decoder_ffmpeg/src/main"
FFMPEG_DIR="${FFMPEG_MODULE_PATH}/jni/ffmpeg"
FFMPEG_LIBS="${FFMPEG_DIR}/android-libs"

mkdir -p "$WORK_DIR"

if [[ ! -d "${MEDIA3}/libraries/decoder_ffmpeg" ]]; then
  log "cloning androidx/media at ${MEDIA3_TAG}"
  git clone --depth 1 --branch "$MEDIA3_TAG" \
    https://github.com/androidx/media.git "$MEDIA3"
else
  log "reusing media3 checkout at ${MEDIA3}"
fi

if [[ ! -d "${FFMPEG_DIR}/.git" ]]; then
  log "cloning FFmpeg at ${FFMPEG_TAG} into ${FFMPEG_DIR}"
  mkdir -p "$(dirname "$FFMPEG_DIR")"
  git clone --depth 1 --branch "$FFMPEG_TAG" \
    https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_DIR"
else
  log "reusing FFmpeg checkout at ${FFMPEG_DIR}"
fi

# --- Build FFmpeg (static libs, one set per ABI) ---------------------------

if [[ -f "${FFMPEG_LIBS}/arm64-v8a/libavcodec.a" ]]; then
  log "FFmpeg static libs already built (delete ${FFMPEG_LIBS} to rebuild)"
else
  log "cross-compiling FFmpeg for armeabi-v7a arm64-v8a x86 x86_64"
  (
    cd "${FFMPEG_MODULE_PATH}/jni"
    ./build_ffmpeg.sh \
      "$FFMPEG_MODULE_PATH" \
      "$ANDROID_NDK" \
      "$HOST_PLATFORM" \
      "$ANDROID_API" \
      "${ENABLED_DECODERS[@]}"
  )
fi

# Sanity-check that the decoders really landed in the static lib, so a silent
# configure failure cannot ship an audio-only "video" build.
#
# Deliberately informational: it reads the archive with llvm-nm, and a tool or
# archive-format mismatch there must not fail an otherwise-good AAR. It prints
# how many ff_*_decoder symbols it can see, so an archive genuinely built
# without our decoder list is distinguishable from an llvm-nm invocation that
# could not read the archive at all.
NM="${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin/llvm-nm"
FFMPEG_LIBS_ARM64="${FFMPEG_LIBS}/arm64-v8a/libavcodec.a"
# media3's build_ffmpeg.sh turns ENABLED_DECODERS into --enable-decoder flags,
# so this only reports what configure ended up with. It greps a file rather
# than a pipe on purpose: under `set -o pipefail`, `printf ... | grep -q`
# reports failure every time grep -q exits early on a match (printf takes
# SIGPIPE), which made FOUND decoders print as MISS and then aborted the whole
# script -- before the AAR was ever built, which is why the FFmpeg artifact was
# missing from releases. Never fatal: a wrong llvm-nm invocation must not
# block an otherwise-good AAR.
if [[ -x "$NM" && -f "$FFMPEG_LIBS_ARM64" ]]; then
  log "verifying decoder symbols in ${FFMPEG_LIBS_ARM64}"
  NM_FILE="$(mktemp)"
  "$NM" --defined-only "$FFMPEG_LIBS_ARM64" >"$NM_FILE" 2>&1 || true
  printf '  %s ff_*_decoder symbols visible to llvm-nm\n' \
    "$(grep -c 'ff_.*_decoder' "$NM_FILE" || true)"
  for symbol in ff_h264_decoder ff_hevc_decoder ff_mpeg4_decoder ff_vc1_decoder \
                ff_wmv3_decoder ff_vp9_decoder ff_aac_decoder \
                ff_mp2_decoder ff_wmav2_decoder ff_opus_decoder; do
    if grep -qw "$symbol" "$NM_FILE"; then
      printf '  ok   %s\n' "$symbol"
    else
      printf '  MISS %s\n' "$symbol"
    fi
  done
  if ! grep -q 'ff_.*_decoder' "$NM_FILE"; then
    printf '  note: llvm-nm reported no decoder symbols; first lines of its output:\n'
    head -5 "$NM_FILE" | sed 's/^/    /'
  fi
  rm -f "$NM_FILE"
else
  log "llvm-nm not found at ${NM}, or ${FFMPEG_LIBS_ARM64} is missing; skipping the decoder symbol check"
fi

# --- Build the extension AAR ----------------------------------------------

# The module's native build only runs when ffmpeg is present (media3 guards it
# on src/main/jni/ffmpeg existing), so this AAR is the video build. Pin the
# module's NDK to the one FFmpeg was just compiled with: the CMake link step
# uses AGP's NDK, and a mismatch between the NDK that produced the .a files and
# the one linking them is a confusing failure.
MODULE_GRADLE="${MEDIA3}/libraries/decoder_ffmpeg/build.gradle.kts"
if ! grep -q 'ndkVersion' "$MODULE_GRADLE"; then
  printf '\nandroid { ndkVersion = "%s" }\n' "$NDK_VERSION" >> "$MODULE_GRADLE"
fi
# sdk.dir only: AGP 8 rejects the old ndk.dir key, and ndkVersion above is the
# supported way to pin the toolchain.
printf 'sdk.dir=%s\n' "$ANDROID_SDK" > "${MEDIA3}/local.properties"

log "assembling :lib-decoder-ffmpeg:assembleRelease"
(
  cd "$MEDIA3"
  ./gradlew :lib-decoder-ffmpeg:assembleRelease --stacktrace
)

# Locate the AAR wherever Gradle actually wrote it. It is NOT under
# ${MEDIA3}/libraries/decoder_ffmpeg/build/outputs/aar: media3's
# gradle.properties sets "buildDir=buildout", which redirects each module's
# build directory to <projectDir>/buildout. Hardcoding build/outputs/aar
# therefore found nothing on every run -- even when Gradle had just printed
# BUILD SUCCESSFUL -- and the step died with "no AAR produced".
BUILT_AAR="$(find "$MEDIA3" -type f -name '*.aar' \
  \( -path '*decoder_ffmpeg*' -o -path '*lib-decoder-ffmpeg*' \) \
  -print 2>/dev/null | head -1 || true)"
if [[ -z "$BUILT_AAR" ]]; then
  printf 'error: no decoder_ffmpeg AAR found under %s; AARs present:\n' "$MEDIA3" >&2
  find "$MEDIA3" -type f -name '*.aar' -print 2>/dev/null >&2 || true
  exit 1
fi
log "found AAR ${BUILT_AAR}"

mkdir -p "$(dirname "$OUTPUT")"
cp "$BUILT_AAR" "$OUTPUT"
log "wrote $OUTPUT ($(wc -c < "$OUTPUT") bytes)"

# --- Verify the AAR is a drop-in for the audio-only artifact --------------

# An AAR stores native libraries under jni/<abi>/ -- that is the AAR on-disk
# layout; Gradle relocates them to lib/<abi>/ only when it packages the APK.
# Checking lib/ here therefore never matched anything and printed "MISS" for
# all four ABIs even on a perfectly good AAR. Either path is accepted so the
# check keeps working whichever layout Gradle emits.
ABIS_WITH_LIB=0
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  entry=""
  for candidate in "jni/${abi}/libffmpegJNI.so" "lib/${abi}/libffmpegJNI.so"; do
    if unzip -l "$OUTPUT" | grep -q "$candidate"; then
      entry="$candidate"
      break
    fi
  done
  if [[ -n "$entry" ]]; then
    printf '  ok   %s\n' "$entry"
    ABIS_WITH_LIB=$((ABIS_WITH_LIB + 1))
  else
    printf '  MISS jni/%s/libffmpegJNI.so\n' "$abi"
  fi
done

# An AAR that carries no JNI library for any ABI is not a decoder extension at
# all, so refuse to hand it to the app build (which would happily accept the
# file and then fail at runtime).
if [[ "$ABIS_WITH_LIB" -eq 0 ]]; then
  die "$OUTPUT contains no libffmpegJNI.so for any ABI; not usable as a decoder extension"
fi

log "done. Rebuild the app (./gradlew assembleDebug) and it uses this AAR;"
log "the FFmpeg video renderer then joins behind MediaCodec with no code change."
