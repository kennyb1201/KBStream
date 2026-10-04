#!/usr/bin/env bash
#
# Build libass for Android and drop the resulting libassjni.so into
# app/src/main/jniLibs/<abi>/, where Gradle packages it into the APK with no
# further wiring.
#
# Why this exists
# ---------------
# media3 does not typeset ASS/SSA and never will (google/ExoPlayer#8435 is
# open since 2021, label "enhancement / low priority", styling removed from
# the original PR): its SSA parser flattens the file into unstyled cues. The
# MPV engine already typesets ASS because libmpv statically links libass, but
# the ExoPlayer engine -- the default, and the only one that carries the
# FFmpeg software decoders -- has nothing. This script is what gives it one:
# scripts/libass-jni/libassjni.cpp bridges libass to Kotlin, and this script
# produces the shared library that bridge lives in.
#
# The app does not require the result. When the .so is absent,
# AssNative.available is false, the ASS sidecar keeps going through
# SubtitleFileParser (styling flattened, exactly as today), and everything
# else is unaffected. That is why the CI step that runs this is
# continue-on-error and its verify step warns by default.
#
# Requirements
# ------------
#   * Linux or macOS host (the NDK has no Windows toolchain here)
#   * Android NDK r27+ (r28 recommended: 16 KB page alignment is the default)
#   * autoconf, automake, libtool, pkg-config, meson, ninja, cmake, gettext
#     (the CI runner installs these; see .github/workflows/build.yml)
#   * ~4 GB free disk and a few CPUs: six libraries x two ABIs is tens of
#     minutes cold, and the per-ABI prefixes are cacheable
#
# Usage
# -----
#   scripts/build_libass.sh
#
# Overridable environment:
#   ANDROID_NDK=/path      NDK root (default: $ANDROID_HOME/ndk/$NDK_VERSION)
#   NDK_VERSION=28.2.13676358
#   ANDROID_API=26         native API level. Must be <= the app's minSdk (26),
#                          matching scripts/build_ffmpeg_video.sh's reasoning:
#                          a lower floor is the permissive direction.
#   ABIS="arm64-v8a armeabi-v7a"
#                          ABIs to build. The default is what
#                          app/build.gradle.kts's ndk { abiFilters } actually
#                          packages, so building the other two would only
#                          cost CI time.
#   WORK_DIR=<dir>         scratch dir (default: build/libass-ext, gitignored)
#   JOBS=<n>               per-library make/ninja parallelism
#
# Pinned upstream versions: every one of these is part of the CI cache key, so
# a bump is a deliberate cold rebuild rather than a silent change of what
# ships.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
ANDROID_API="${ANDROID_API:-26}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
WORK_DIR="${WORK_DIR:-${REPO_ROOT}/build/libass-ext}"
OUTPUT_DIR="${OUTPUT_DIR:-${REPO_ROOT}/app/src/main/jniLibs}"
JOBS="${JOBS:-$( (command -v nproc >/dev/null && nproc) || echo 4 )}"

LIBASS_TAG="${LIBASS_TAG:-0.17.4}"
FREETYPE_VERSION="${FREETYPE_VERSION:-2.13.3}"
HARFBUZZ_TAG="${HARFBUZZ_TAG:-10.0.1}"
FRIBIDI_TAG="${FRIBIDI_TAG:-v1.0.16}"
FONTCONFIG_TAG="${FONTCONFIG_TAG:-2.15.0}"
EXPAT_TAG="${EXPAT_TAG:-R_2_6_2}"

ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_NDK="${ANDROID_NDK:-${ANDROID_SDK:+${ANDROID_SDK}/ndk/${NDK_VERSION}}}"

SRC_DIR="${WORK_DIR}/src"
DEP_PREFIX="${WORK_DIR}/prefix"

log() { printf '\n=== %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- Preconditions ---------------------------------------------------------

command -v git >/dev/null || die "git is not on PATH"
command -v cmake >/dev/null || die "cmake is not on PATH"
command -v pkg-config >/dev/null || die "pkg-config is not on PATH"
command -v meson >/dev/null || die "meson is not on PATH (pip install meson, or apt install meson)"
command -v autoconf >/dev/null || die "autoconf is not on PATH"
[[ -n "$ANDROID_NDK" ]] || die "set ANDROID_NDK (or ANDROID_HOME) to an NDK r27+ root"
[[ -d "$ANDROID_NDK" ]] || die "NDK not found at $ANDROID_NDK"

case "$(uname -s)" in
  Linux)  HOST_PLATFORM="linux-x86_64" ;;
  Darwin) HOST_PLATFORM="darwin-x86_64" ;;
  *)      die "unsupported host $(uname -s)" ;;
esac

TOOLCHAIN="${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_PLATFORM}"
[[ -d "$TOOLCHAIN" ]] || die "NDK toolchain not found at $TOOLCHAIN"

log "libass ${LIBASS_TAG} / freetype ${FREETYPE_VERSION} / harfbuzz ${HARFBUZZ_TAG}"
log "fribidi ${FRIBIDI_TAG} / fontconfig ${FONTCONFIG_TAG} / expat ${EXPAT_TAG}"
log "ABIs: ${ABIS} / API ${ANDROID_API} / NDK ${ANDROID_NDK}"

mkdir -p "$SRC_DIR" "$OUTPUT_DIR"

# --- Source fetch ----------------------------------------------------------
#
# Clones/untars once for all ABIs: these are architecture-independent sources,
# and the per-ABI split happens entirely in the prefix directories.

fetch_git() {
  local url="$1" ref="$2" dir="$3"
  if [[ -d "${dir}/.git" ]]; then
    log "reusing $(basename "$dir") at ${dir}"
    return
  fi
  log "cloning ${url} at ${ref}"
  git clone --depth 1 --branch "$ref" "$url" "$dir"
}

fetch_tar() {
  local url="$1" dir="$2" strip="$3"
  if [[ -d "$dir" ]]; then
    log "reusing $(basename "$dir")"
    return
  fi
  log "downloading ${url}"
  local tarball="${WORK_DIR}/$(basename "$url")"
  curl -fsSL "$url" -o "$tarball"
  mkdir -p "$dir"
  tar -xf "$tarball" -C "$dir" --strip-components="$strip"
}

fetch_git https://github.com/libass/libass.git "$LIBASS_TAG" "${SRC_DIR}/libass"
fetch_git https://github.com/harfbuzz/harfbuzz.git "$HARFBUZZ_TAG" "${SRC_DIR}/harfbuzz"
fetch_git https://github.com/fribidi/fribidi.git "$FRIBIDI_TAG" "${SRC_DIR}/fribidi"
fetch_git https://gitlab.freedesktop.org/fontconfig/fontconfig.git "$FONTCONFIG_TAG" "${SRC_DIR}/fontconfig"
# FreeType and expat ship signed release tarballs rather than a tag we want to
# track, so they come from their release URLs.
fetch_tar "https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VERSION}.tar.gz" \
  "${SRC_DIR}/freetype" 1
fetch_tar "https://github.com/libexpat/libexpat/releases/download/${EXPAT_TAG}/expat-2.6.2.tar.gz" \
  "${SRC_DIR}/expat" 1

# --- Per-ABI cross-compile -------------------------------------------------

# The NDK's clang wrapper is named <triple><api>-clang for the *android*
# triples; autotools wants the plain GNU triple for --host.
meson_cpu_family() {
  case "$1" in
    arm64-v8a)   echo aarch64 ;;
    armeabi-v7a) echo arm ;;
    x86)         echo x86 ;;
    x86_64)      echo x86_64 ;;
    *)           die "unsupported ABI $1" ;;
  esac
}

clang_triple() {
  case "$1" in
    arm64-v8a)   echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7a-linux-androideabi ;;
    x86)         echo i686-linux-android ;;
    x86_64)      echo x86_64-linux-android ;;
  esac
}

autotools_host() {
  case "$1" in
    arm64-v8a)   echo aarch64-linux-android ;;
    armeabi-v7a) echo arm-linux-androideabi ;;
    x86)         echo i686-linux-android ;;
    x86_64)      echo x86_64-linux-android ;;
  esac
}

write_meson_cross_file() {
  local abi="$1" prefix="$2" file="$3"
  local triple; triple="$(clang_triple "$abi")"
  cat > "$file" <<EOF
[binaries]
c = '${TOOLCHAIN}/bin/${triple}${ANDROID_API}-clang'
cpp = '${TOOLCHAIN}/bin/${triple}${ANDROID_API}-clang++'
ar = '${TOOLCHAIN}/bin/llvm-ar'
strip = '${TOOLCHAIN}/bin/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '$(meson_cpu_family "$abi")'
cpu = '$(meson_cpu_family "$abi")'
endian = 'little'

[properties]
needs_exe_wrapper = true

[built-in options]
c_args = ['-fPIC', '-O2']
cpp_args = ['-fPIC', '-O2']
c_link_args = ['-L${prefix}/lib']
cpp_link_args = ['-L${prefix}/lib']
EOF
}

build_expat() {
  local abi="$1" prefix="$2"
  (
    cd "${SRC_DIR}/expat"
    ./configure --host="$(autotools_host "$abi")" --prefix="$prefix" \
      --enable-static --disable-shared --without-xmlwf --without-docbook \
      --disable-nls >/dev/null
    make -j"$JOBS" >/dev/null
    make install >/dev/null
  )
}

build_fribidi() {
  local abi="$1" prefix="$2" cross="$3"
  (
    cd "${SRC_DIR}/fribidi"
    meson setup _build --cross-file "$cross" --prefix="$prefix" \
      --default-library=static --buildtype=release \
      -Ddocs=false -Dtests=false -Dbin=false >/dev/null
    ninja -C _build -j"$JOBS" >/dev/null
    ninja -C _build install >/dev/null
  )
}

build_freetype() {
  local abi="$1" prefix="$2"
  (
    cd "${SRC_DIR}/freetype"
    # HarfBuzz is linked in by libass itself, and the PNG/zlib/bzip2 paths are
    # unused by subtitle rendering: keeping them out means the static archive
    # has no dependency the NDK does not already provide.
    ./configure --host="$(autotools_host "$abi")" --prefix="$prefix" \
      --enable-static --disable-shared --with-pic \
      --without-zlib --without-bzip2 --without-png --without-harfbuzz \
      --without-brotli >/dev/null
    make -j"$JOBS" >/dev/null
    make install >/dev/null
  )
}

build_fontconfig() {
  local abi="$1" prefix="$2"
  (
    cd "${SRC_DIR}/fontconfig"
    # fontconfig's autotools build is the plainest way in: its meson port
    # wants a generated config that assumes a host libc.
    if [[ ! -x ./configure ]]; then
      NOCONFIGURE=1 ./autogen.sh >/dev/null
    fi
    # --disable-docs keeps the docbook toolchain out of the picture, and
    # NLS is skipped because an XML config is supplied at runtime instead of
    # a translated one.
    ./configure --host="$(autotools_host "$abi")" --prefix="$prefix" \
      --enable-static --disable-shared --disable-docs --disable-nls \
      --with-expat="$prefix" --with-freetype-config="$prefix/bin/freetype-config" \
      FREETYPE_CFLAGS="-I${prefix}/include/freetype2" \
      FREETYPE_LIBS="-L${prefix}/lib -lfreetype" \
      EXPAT_CFLAGS="-I${prefix}/include" \
      EXPAT_LIBS="-L${prefix}/lib -lexpat" >/dev/null
    make -j"$JOBS" >/dev/null
    make install >/dev/null
  )
}

build_harfbuzz() {
  local abi="$1" prefix="$2"
  # CMake rather than meson: harfbuzz's CMake path takes the NDK's own
  # toolchain file directly, so there is no cross file to keep in step.
  cmake -S "${SRC_DIR}/harfbuzz" -B "${SRC_DIR}/harfbuzz/_build-${abi}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DHB_HAVE_FREETYPE=OFF -DHB_HAVE_GLIB=OFF -DHB_HAVE_ICU=OFF \
    -DHB_HAVE_GRAPHITE2=OFF -DHB_HAVE_CORETEXT=OFF -DHB_HAVE_DIRECTWRITE=OFF \
    -DHB_BUILD_UTILS=OFF -DHB_BUILD_TESTS=OFF -DHB_BUILD_SUBSET=OFF \
    -DHB_BUILD_DOCS=OFF >/dev/null
  cmake --build "${SRC_DIR}/harfbuzz/_build-${abi}" -j"$JOBS" >/dev/null
  cmake --install "${SRC_DIR}/harfbuzz/_build-${abi}" >/dev/null
}

build_libass() {
  local abi="$1" prefix="$2" cross="$3"
  (
    cd "${SRC_DIR}/libass"
    if [[ ! -x ./configure ]]; then
      ./autogen.sh >/dev/null 2>&1 || true
    fi
    # libass must find the prefixes above, not the host's own copies; naming
    # PKG_CONFIG explicitly stops --host from making autotools look for a
    # ${host}-pkg-config that does not exist.
    PKG_CONFIG=pkg-config \
    PKG_CONFIG_PATH="${prefix}/lib/pkgconfig" \
    PKG_CONFIG_LIBDIR="${prefix}/lib/pkgconfig" \
    ./configure --host="$(autotools_host "$abi")" --prefix="$prefix" \
      --enable-static --disable-shared --with-pic \
      --disable-test --enable-fontconfig \
      --with-fribidi --with-harfbuzz \
      FREETYPE_CFLAGS="-I${prefix}/include/freetype2" \
      FREETYPE_LIBS="-L${prefix}/lib -lfreetype" \
      FONTCONFIG_CFLAGS="-I${prefix}/include" \
      FONTCONFIG_LIBS="-L${prefix}/lib -lfontconfig" \
      FRIBIDI_CFLAGS="-I${prefix}/include/fribidi" \
      FRIBIDI_LIBS="-L${prefix}/lib -lfribidi" \
      HARFBUZZ_CFLAGS="-I${prefix}/include/harfbuzz" \
      HARFBUZZ_LIBS="-L${prefix}/lib -lharfbuzz" >/dev/null
    make -j"$JOBS" >/dev/null
    make install >/dev/null
  )
}

build_prefix_complete() {
  local prefix="$1"
  for lib in libass libharfbuzz libfreetype libfribidi libfontconfig libexpat; do
    [[ -f "${prefix}/lib/${lib}.a" ]] || return 1
  done
  return 0
}

for abi in $ABIS; do
  prefix="${DEP_PREFIX}/${abi}"
  cross="${WORK_DIR}/meson-cross-${abi}.txt"
  write_meson_cross_file "$abi" "$prefix" "$cross"

  if build_prefix_complete "$prefix"; then
    log "${abi}: dependency prefix already built (delete ${prefix} to rebuild)"
    continue
  fi

  log "${abi}: building expat"
  build_expat "$abi" "$prefix"
  log "${abi}: building freetype ${FREETYPE_VERSION}"
  build_freetype "$abi" "$prefix"
  log "${abi}: building fribidi ${FRIBIDI_TAG}"
  build_fribidi "$abi" "$prefix" "$cross"
  log "${abi}: building fontconfig ${FONTCONFIG_TAG}"
  build_fontconfig "$abi" "$prefix"
  log "${abi}: building harfbuzz ${HARFBUZZ_TAG}"
  build_harfbuzz "$abi" "$prefix"
  log "${abi}: building libass ${LIBASS_TAG}"
  build_libass "$abi" "$prefix" "$cross"

  build_prefix_complete "$prefix" || die "${abi}: dependency prefix is missing a static library"
done

# --- Build libassjni.so per ABI --------------------------------------------

for abi in $ABIS; do
  prefix="${DEP_PREFIX}/${abi}"
  log "${abi}: linking libassjni.so"
  # ANDROID_STL=c++_static is set in the CMakeLists: nothing crosses a std::
  # type boundary through JNI, so the library must not add a second
  # libc++_shared.so next to the one libmpv already brings.
  cmake -S "${REPO_ROOT}/scripts/libass-jni" -B "${WORK_DIR}/jni-${abi}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    -DASS_DEPS_PREFIX="$DEP_PREFIX" \
    -DCMAKE_BUILD_TYPE=Release >/dev/null
  cmake --build "${WORK_DIR}/jni-${abi}" -j"$JOBS" >/dev/null

  so="$(find "${WORK_DIR}/jni-${abi}" -name 'libassjni.so' -print -quit)"
  [[ -n "$so" ]] || die "no libassjni.so produced for ${abi}"

  mkdir -p "${OUTPUT_DIR}/${abi}"
  cp "$so" "${OUTPUT_DIR}/${abi}/libassjni.so"
  log "wrote ${OUTPUT_DIR}/${abi}/libassjni.so ($(wc -c < "${OUTPUT_DIR}/${abi}/libassjni.so") bytes)"
done

# --- Verify the output is loadable -----------------------------------------

# An .so that exists but resolves none of libass (or that is not 16 KB
# aligned) is worse than a missing one: the app would report the renderer as
# available and then draw nothing. Both checks are fatal here -- unlike
# build_ffmpeg_video.sh's informational symbol pass, this script's whole
# output is the one file the next step packages.
READELF="${TOOLCHAIN}/bin/llvm-readelf"
NM="${TOOLCHAIN}/bin/llvm-nm"

for abi in $ABIS; do
  so="${OUTPUT_DIR}/${abi}/libassjni.so"

  if [[ -x "$NM" ]]; then
    if "$NM" --defined-only "$so" 2>/dev/null | grep -q 'ass_render_frame'; then
      printf '  ok   %s exports a linked libass\n' "$abi"
    else
      die "${abi}/libassjni.so does not contain ass_render_frame; libass was not linked in"
    fi
  else
    log "llvm-nm not found at ${NM}; skipping the libass symbol check"
  fi

  # 16 KB pages: only the 64-bit ABIs can request them, and a 4 KB-aligned
  # library crashes on an Android 15+ device that runs with 16 KB pages.
  case "$abi" in
    arm64-v8a|x86_64)
      if [[ -x "$READELF" ]]; then
        if "$READELF" -l "$so" | awk '$1 == "LOAD" { if ($NF == "0x4000") found = 1 } END { exit(found ? 0 : 1) }'; then
          printf '  ok   %s is 16 KB aligned\n' "$abi"
        else
          die "${abi}/libassjni.so is not 16 KB aligned; rebuild with NDK r28 (this run used ${ANDROID_NDK})"
        fi
      else
        log "llvm-readelf not found at ${READELF}; skipping the 16 KB alignment check"
      fi
      ;;
  esac
done

log "done: ${OUTPUT_DIR}"
