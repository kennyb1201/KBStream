#!/usr/bin/env bash
#
# Build a VIDEO-ENABLED media3 FFmpeg decoder extension and drop it at
# libs/media3-ffmpeg-decoder.aar, where app/build.gradle.kts picks it up
# automatically (see the "FFmpeg decoder extension" block there).
#
# Why this exists
# ---------------
# media3's decoder_ffmpeg extension is what lets ExoPlayer decode in software.
# There is no published build of it we can consume: the extension subclasses
# media3's decoder internals, so it only works when compiled against the exact
# media3 release on the classpath, and the one prebuilt available
# (org.jellyfin.media3:media3-ffmpeg-decoder) stopped at 1.9.0+1. It also
# carried AUDIO decoders only, so its video renderer answered UNSUPPORTED for
# every video mime and never claimed a track. This script is therefore the only
# source of that extension: it builds it from the matching androidx/media tag,
# with the video decoders appended to media3's FFmpeg configure line, and drops
# the AAR where the app picks it up on the next Gradle build.
#
# The app relies on it being there: SplitModeRenderersFactory runs the video
# extension renderer in EXTENSION_RENDERER_MODE_ON, so the software video
# decoder joins behind MediaCodec the moment a build actually carries video
# decoders.
#
# The result ships libffmpegJNI.so, which is what lets it coexist with libmpv
# (the mpvlibAndroid AAR). libmpv already brings libavcodec.so / libavutil.so /
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
#   * Android NDK r28c (28.2.13676358) -- see ANDROID_NDK below, and the
#     16 KB page-size note that is why r28 and not the r26b media3's own
#     README still names
#   * several CPUs and ~10 GB free disk: the four ABIs of FFmpeg are the slow
#     part (tens of minutes cold; the static libs are cacheable)
#
# Usage
# -----
#   scripts/build_ffmpeg_video.sh
#
# Overridable environment:
#   MEDIA3_TAG=1.11.1       androidx/media git tag to build. MUST match the
#                           media3 version in app/build.gradle.kts.
#
# 16 KB page sizes
# ----------------
# Android 15 devices can run with 16 KB pages, and a shared library linked for
# 4 KB pages crashes on them. media3 used to force the alignment itself with
# "-Wl,-z,max-page-size=16384"; 1.10 replaced that flag with the NDK's
# ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES property, which NDK r27 and newer act on
# and older ones silently ignore. On r26 that leaves libffmpegJNI.so 4 KB
# aligned, so this script requires r28 (where 16 KB alignment is the default)
# rather than the r26b the extension's README was written against.
#   FFMPEG_TAG=release/6.0  FFmpeg branch media3's build_ffmpeg.sh expects
#   ANDROID_NDK=/path       NDK root (default: $ANDROID_HOME/ndk/28.2.13676358)
#   python3                 applies the one patch this script makes to
#                           media3's own source (see "Patch media3's resampler
#                           hand-off" below); the libdovi script needs it too
#   ANDROID_API=23          native API level (must be <= the app's minSdk).
#                           Held at 23 on purpose while the app sits at 26:
#                           a LOWER floor is the permissive direction (the .so
#                           only claims symbols that exist on 23), so this is
#                           not a thing to bump with minSdk - raising it would
#                           only make the library refuse to load on devices it
#                           currently works on. It is also part of the CI cache
#                           signature, so moving it forces a cold 4-ABI
#                           cross-compile for no gain.
#   WORK_DIR=<dir>          scratch dir (default: build/ffmpeg-ext, gitignored)
#   OUTPUT=<file>           AAR destination
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MEDIA3_TAG="${MEDIA3_TAG:-1.11.1}"
FFMPEG_TAG="${FFMPEG_TAG:-release/6.0}"
ANDROID_API="${ANDROID_API:-23}"
NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
WORK_DIR="${WORK_DIR:-${REPO_ROOT}/build/ffmpeg-ext}"
OUTPUT="${OUTPUT:-${REPO_ROOT}/libs/media3-ffmpeg-decoder.aar}"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_NDK="${ANDROID_NDK:-${ANDROID_SDK:+${ANDROID_SDK}/ndk/${NDK_VERSION}}}"

# The audio decoders media3's own published extension carries, which keep the
# software AUDIO path exactly as a stock media3 build has it, PLUS the audio
# decoders Android's MediaCodec does NOT guarantee:
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
[[ -n "$ANDROID_NDK" ]] || die "set ANDROID_NDK (or ANDROID_HOME) to an NDK r28 root"
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

# --- Patch media3's resampler hand-off ------------------------------------
#
# ONE edit to media3's own source, applied here because this repo owns the
# libffmpegJNI.so it ships and the defect below is a field crash on it
# (Sentry ANDROID-Z, kbstream@0.6.89: SIGSEGV in
# swri_oldapi_conv_fltp_to_s16_nch_neon, inside this AAR's libffmpegJNI.so).
#
# What media3 1.11.1 does (libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc,
# decodePacket):
#
#   swr_convert(resampleContext, &outputBuffer, bufferOutSize,
#               (const uint8_t**)frame->data, frame->nb_samples);
#
# `frame->data` is an array of AV_NUM_DATA_POINTERS (8) pointers. For planar
# audio with MORE than eight channels FFmpeg puts the per-channel planes in
# `frame->extended_data` instead (av_frame_get_buffer() only aliases the two
# when the channel count fits), so this call hands libswresample an array whose
# entries past the eighth are the frame's own `linesize[]` values read as
# pointers. libswresample's planar converters walk one pointer per input
# channel, so on a 9+ channel frame the ARM NEON FLTP->S16 converter
# (swri_oldapi_conv_fltp_to_s16_nch_neon, selected for FLTP->S16 above two
# channels) dereferences a garbage pointer on the frame's first block: exactly
# the crash above, from the wide TrueHD/Atmos tracks this decoder exists for.
#
# `frame->extended_data` is the array with one valid pointer per channel always
# (FFmpeg keeps it pointing at `data` for the 8-or-fewer case, where the two
# are the same array), so the swap is a no-op for every track that works today
# and the fix for the ones that crash. It is also what the FFmpeg API documents
# planar audio access to go through.
#
# Deliberately NOT patched here, in the same call: `bufferOutSize` is a BYTE
# count being passed as swr_convert's `out_count`, which is documented in
# samples per channel. With output layout, format and rate pinned to the input
# (see swr_alloc_set_opts2 above) the two agree for the ordinary case, so this
# is latent rather than live, and it is entangled with the caller's own
# `outputBuffer += bufferOutSize` block accounting and the Java side's buffer
# growth: changing it is a separate, device-tested change, not something to
# fold into a crash fix. The same applies to the resampler context being built
# once from the codec context and never rebuilt if a frame's layout changes.
# Neither is what crashed.
#
# The patch is anchored on the exact upstream call text, hard-fails if that
# anchor is missing (a media3 bump that rewrites the call must be looked at,
# not silently skipped) and is idempotent, because the media3 checkout is
# cached between runs.
FFMPEG_JNI="${FFMPEG_MODULE_PATH}/jni/ffmpeg_jni.cc"
[[ -f "$FFMPEG_JNI" ]] || die "no ${FFMPEG_JNI} in the media3 ${MEDIA3_TAG} checkout"
command -v python3 >/dev/null || die "python3 is required to patch media3's ffmpeg_jni.cc"
log "patching media3's resampler hand-off (frame->data -> frame->extended_data)"
python3 - "$FFMPEG_JNI" <<'PY'
import sys

path = sys.argv[1]
with open(path) as handle:
    text = handle.read()

UNPATCHED = "(const uint8_t**)frame->data, frame->nb_samples);"
PATCHED = "(const uint8_t**)frame->extended_data, frame->nb_samples);"

if text.count(UNPATCHED) == 0 and text.count(PATCHED) == 1:
    print("  already patched; nothing to do")
    sys.exit(0)
if text.count(UNPATCHED) != 1 or text.count(PATCHED) != 0:
    sys.exit(
        "error: expected exactly one %r in %s (found %d unpatched, %d patched); "
        "a media3 rewrite of this call must be reviewed by hand, not skipped"
        % (UNPATCHED, path, text.count(UNPATCHED), text.count(PATCHED))
    )

with open(path, "w") as handle:
    handle.write(text.replace(UNPATCHED, PATCHED))
print("  patched %s" % path)
PY
# Independent read-back: the build must not proceed on a patch that did not
# land, because the AAR it produces would be the one that segfaults.
grep -q 'frame->extended_data' "$FFMPEG_JNI" ||
  die "${FFMPEG_JNI} still hands swr_convert frame->data"

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

# --- Verify the AAR actually carries the native extension -----------------

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

# --- Verify the 64-bit libraries are 16 KB page aligned -------------------

# Android 15 devices can run with 16 KB pages, and a 4 KB-aligned shared
# library crashes on them. media3 1.10 stopped passing
# "-Wl,-z,max-page-size=16384" itself and relies on the NDK's
# ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES property instead, which only NDK r27+
# honours -- so a 4 KB-aligned library here means the NDK is too old, not that
# something in this repo is wrong. 32-bit ABIs cannot request 16 KB pages, so
# only the 64-bit ones are checked.
READELF="${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin/llvm-readelf"
if [[ -x "$READELF" ]]; then
  for abi in arm64-v8a x86_64; do
    entry=""
    for candidate in "jni/${abi}/libffmpegJNI.so" "lib/${abi}/libffmpegJNI.so"; do
      if unzip -l "$OUTPUT" | grep -q "$candidate"; then
        entry="$candidate"
        break
      fi
    done
    [[ -n "$entry" ]] || continue
    SO_FILE="$(mktemp)"
    unzip -p "$OUTPUT" "$entry" >"$SO_FILE"
    if "$READELF" -l "$SO_FILE" | awk '$1 == "LOAD" { if ($NF == "0x4000") found = 1 } END { exit(found ? 0 : 1) }'; then
      printf '  ok   %s is 16 KB aligned\n' "$entry"
    else
      rm -f "$SO_FILE"
      die "$entry is not 16 KB aligned, so it crashes on 16 KB page-size devices; rebuild with NDK r28 (this run used ${ANDROID_NDK})"
    fi
    rm -f "$SO_FILE"
  done
else
  log "llvm-readelf not found at ${READELF}; skipping the 16 KB alignment check"
fi

log "done. Rebuild the app (./gradlew assembleDebug) and it uses this AAR;"
log "the FFmpeg video renderer then joins behind MediaCodec with no code change."
