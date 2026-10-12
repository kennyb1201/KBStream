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
# The app does not require the result to RUN. When the .so is absent,
# AssNative.available is false, the ASS sidecar keeps going through
# SubtitleFileParser (styling flattened), and everything else is unaffected.
# That is NOT acceptable for a release -- v0.6.24 shipped without the .so, so
# `System.loadLibrary("assjni")` threw and ASS typesetting was silently lost on
# the ExoPlayer engine on every device -- so the CI step that runs this is a
# hard gate now: a miss fails the release instead of publishing without native
# ASS. See the libass block in .github/workflows/build.yml.
#
# The failure that kept it soft for so long lived in the fontconfig stage:
# fontconfig's autogen.sh needs gperf and autopoint, tests for both, and reports
# the refusal on STDOUT -- which this script redirected to /dev/null, so the
# step went red with an empty log and every release shipped without native ASS.
# The workflow installs both now, the preconditions below check for them, and
# every fontconfig step runs through run_logged so the next failure names
# itself instead of vanishing.
#
# This script's own gates are fatal regardless: a run that does produce the .so
# cannot leave a library that is missing, unlinked from libass, or 4 KB aligned
# behind (see the verify section at the end).
#
# Requirements
# ------------
#   * Linux or macOS host (the NDK has no Windows toolchain here)
#   * Android NDK r27+ (r28 recommended: 16 KB page alignment is the default)
#   * autoconf, automake, libtool, pkg-config, meson, ninja, cmake, gettext
#     (the CI runner installs these; see .github/workflows/build.yml)
#   * gperf and autopoint, which fontconfig's autogen.sh requires (separate
#     packages from gettext on Debian/Ubuntu: apt install gperf autopoint)
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

# Runs one build command with its output held back: on success just a one-line
# "ok" reaches the log, on failure the tail is echoed before the script stops.
#
# Every stage here produces megabytes of configure/make noise, which is why the
# script used to send all of it to /dev/null -- and that is exactly how a red CI
# step ended up with nothing after it: fontconfig's autogen.sh reports its
# missing prerequisites on STDOUT, so the reason went into /dev/null with the
# noise and the log said only "Process completed with exit code 1".
run_logged() {
  local name="$1"; shift
  local logfile="${WORK_DIR}/logs/${name// /-}.log"
  mkdir -p "$(dirname "$logfile")"
  # The status is captured in the else branch: after a false `if` with no
  # else, $? is 0 again, so reading it after the fi would report every
  # failure as a success.
  local status=0
  if "$@" >"$logfile" 2>&1; then
    printf '  ok   %s\n' "$name"
    return 0
  else
    status=$?
  fi
  printf 'error: %s failed (exit %s); last 40 lines of %s:\n' \
    "$name" "$status" "$logfile" >&2
  tail -n 40 "$logfile" >&2
  return "$status"
}

# --- Preconditions ---------------------------------------------------------

command -v git >/dev/null || die "git is not on PATH"
command -v cmake >/dev/null || die "cmake is not on PATH"
command -v pkg-config >/dev/null || die "pkg-config is not on PATH"
command -v meson >/dev/null || die "meson is not on PATH (pip install meson, or apt install meson)"
command -v autoconf >/dev/null || die "autoconf is not on PATH"
command -v autoreconf >/dev/null || die "autoreconf is not on PATH (apt install autoconf automake)"
command -v libtoolize >/dev/null || die "libtoolize is not on PATH (apt install libtool)"
command -v gettextize >/dev/null || die "gettextize is not on PATH (apt install gettext)"
# gperf generates fontconfig's tables, and autopoint (a separate package from
# gettext) is what its autogen.sh shells out to: both are tested for there, and
# a miss exits 1 with everything on stdout. Naming them here turns the next
# such miss into a one-line reason instead of an unexplained red step.
command -v gperf >/dev/null || die "gperf is not on PATH (apt install gperf)"
command -v autopoint >/dev/null || die "autopoint is not on PATH (apt install autopoint)"
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

# The NDK ships no ${host}-gcc, and autoconf's --host search does not stop when
# it finds nothing: it falls back to the HOST compiler. Every autotools stage
# here built x86_64 objects into an arm64 prefix and reported "ok" -- until
# libass's aarch64 assembly met the host assembler ("no such instruction:
# adrp", "too many memory references for `add'"), which is the only reason the
# mistake was visible at all. Reproduce it by configuring any autotools project
# with --host=aarch64-linux-android and nothing else: the Makefile's CC is gcc,
# and readelf calls the archive it produces x86-64.
#
# So the cross drivers are named explicitly, the way the meson cross file below
# names them for fribidi. CC carries the target, and with it the assembler and
# the linker driver; the archive tools are named too so the .a files are not
# produced by the host's ar either.
CROSS_ENV=()
set_cross_env() {
  local abi="$1"
  local triple; triple="$(clang_triple "$abi")"
  CROSS_ENV=(
    "CC=${TOOLCHAIN}/bin/${triple}${ANDROID_API}-clang"
    "CXX=${TOOLCHAIN}/bin/${triple}${ANDROID_API}-clang++"
  )
  # Only the tools this NDK actually ships: naming a missing one would turn
  # into a "command not found" inside make instead of autoconf's own fallback.
  local pair file var
  for pair in llvm-ar:AR llvm-ranlib:RANLIB llvm-strip:STRIP; do
    file="${pair%%:*}"; var="${pair##*:}"
    [[ -x "${TOOLCHAIN}/bin/${file}" ]] || continue
    CROSS_ENV+=("${var}=${TOOLCHAIN}/bin/${file}")
  done
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

# Every project below is configured OUT of its source tree, in a build
# directory of its own per ABI.
#
# The source trees are shared between ABIs -- they are architecture-independent
# and fetched once -- and an in-tree build leaves the first ABI's object files
# in them. The second ABI's configure rewrites the Makefile, but make then sees
# objects newer than the sources, builds nothing, and its `make install` copies
# the FIRST ABI's archives into the second ABI's prefix. That is not
# theoretical: the 2026-10-12 run built arm64-v8a from end to end and then had
# armeabi-v7a's expat install an aarch64 libexpat.a, which fontconfig's own
# probe rejected -- "checking for expat.h... yes / checking for
# XML_SetDoctypeDeclHandler... no / configure: error: *** expat is required" --
# with nothing in the log saying why the headers it could see did not link.
# One build directory per ABI removes the whole class: object files a previous
# ABI produced are never in front of this one.
build_dir() {
  local abi="$1" project="$2"
  local dir="${WORK_DIR}/build-${abi}/${project}"
  mkdir -p "$dir"
  printf '%s\n' "$dir"
}

# The machine every archive in a finished prefix has to have been compiled for.
# This is the gate that names the failure at the prefix instead of leaving it to
# be inferred from fontconfig's configure: a wrong-architecture archive in a
# prefix looks exactly like a correct one.
expected_machine() {
  case "$1" in
    arm64-v8a)   echo AArch64 ;;
    armeabi-v7a) echo ARM ;;
    x86)         echo 386 ;;
    x86_64)      echo X86-64 ;;
    *)           die "unsupported ABI $1" ;;
  esac
}

# llvm-readelf prints one header per archive member; the first member's machine
# is the archive's. No llvm-readelf (a host-only checkout) means no answer, and
# no answer is not a failure.
prefix_arch_ok() {
  local abi="$1" prefix="$2"
  local readelf="${TOOLCHAIN}/bin/llvm-readelf"
  [[ -x "$readelf" ]] || return 0
  local want; want="$(expected_machine "$abi")"
  local lib found
  for lib in libass libharfbuzz libfreetype libfribidi libfontconfig libexpat; do
    # Everything after "Machine:", spaces removed: readelf pads the column, and
    # the x86 machine names are multi-word ("Advanced Micro Devices X86-64").
    found="$("$readelf" -h "${prefix}/lib/${lib}.a" 2>/dev/null |
      awk -F'Machine:' '/Machine:/ { print $2; exit }' | tr -d ' \t' || true)"
    case "$found" in
      *"$want"*) ;;
      *)
        printf 'error: %s is not a %s archive (llvm-readelf says "%s")\n' \
          "${prefix}/lib/${lib}.a" "$want" "${found:-nothing}" >&2
        return 1
        ;;
    esac
  done
  return 0
}

build_expat() {
  local abi="$1" prefix="$2"
  local build; build="$(build_dir "$abi" expat)"
  (
    cd "$build"
    run_logged "expat configure ${abi}" \
      env "${CROSS_ENV[@]}" \
      "${SRC_DIR}/expat/configure" --host="$(autotools_host "$abi")" --prefix="$prefix" \
      --enable-static --disable-shared --without-xmlwf --without-docbook \
      --disable-nls
    run_logged "expat make ${abi}" make -j"$JOBS"
    run_logged "expat install ${abi}" make install
  )
}

build_fribidi() {
  local abi="$1" prefix="$2" cross="$3"
  # Per-ABI build directory here too: meson reconfigures a build directory it
  # is handed a second time, and the objects already in it are the first ABI's.
  # The source directory is named explicitly -- a single positional argument is
  # whichever of the two meson can find a meson.build in, and the old form only
  # worked because it ran from inside the fribidi tree.
  local build; build="$(build_dir "$abi" fribidi)"
  run_logged "fribidi setup ${abi}" \
    meson setup "$build" "${SRC_DIR}/fribidi" --cross-file "$cross" --prefix="$prefix" \
    --default-library=static --buildtype=release \
    -Ddocs=false -Dtests=false -Dbin=false
  run_logged "fribidi build ${abi}" ninja -C "$build" -j"$JOBS"
  run_logged "fribidi install ${abi}" ninja -C "$build" install
}

build_freetype() {
  local abi="$1" prefix="$2"
  local build; build="$(build_dir "$abi" freetype)"
  (
    cd "$build"
    # HarfBuzz is linked in by libass itself, and the PNG/zlib/bzip2 paths are
    # unused by subtitle rendering: keeping them out means the static archive
    # has no dependency the NDK does not already provide.
    run_logged "freetype configure ${abi}" \
      env "${CROSS_ENV[@]}" \
      "${SRC_DIR}/freetype/configure" --host="$(autotools_host "$abi")" --prefix="$prefix" \
      --enable-static --disable-shared --with-pic \
      --without-zlib --without-bzip2 --without-png --without-harfbuzz \
      --without-brotli
    run_logged "freetype make ${abi}" make -j"$JOBS"
    run_logged "freetype install ${abi}" make install
  )
}

build_fontconfig() {
  local abi="$1" prefix="$2"
  local build; build="$(build_dir "$abi" fontconfig)"
  # fontconfig's autotools build is the plainest way in: its meson port
  # wants a generated config that assumes a host libc.
  #
  # None of these steps redirect to /dev/null any more: run_logged holds the
  # output back until something fails, and then prints the whole reason. This
  # stage is where the release lost native ASS for months, and the reason --
  # "You must have gperf installed to compile Fontconfig." -- was on the
  # stdout that used to be thrown away.
  #
  # autogen.sh writes ./configure into the SOURCE tree and cannot run from a
  # build directory: it is done once, here, and every configure/make below runs
  # from this ABI's own build directory.
  if [[ ! -x "${SRC_DIR}/fontconfig/configure" ]]; then
    ( cd "${SRC_DIR}/fontconfig" &&
      run_logged "fontconfig autogen" env NOCONFIGURE=1 ./autogen.sh )
  fi
  (
    cd "$build"
    # --disable-docs keeps the docbook toolchain out of the picture, and
    # NLS is skipped because an XML config is supplied at runtime instead of
    # a translated one.
    run_logged "fontconfig configure ${abi}" \
      env "${CROSS_ENV[@]}" \
      "${SRC_DIR}/fontconfig/configure" --host="$(autotools_host "$abi")" --prefix="$prefix" \
      --enable-static --disable-shared --disable-docs --disable-nls \
      --with-expat="$prefix" --with-freetype-config="$prefix/bin/freetype-config" \
      FREETYPE_CFLAGS="-I${prefix}/include/freetype2" \
      FREETYPE_LIBS="-L${prefix}/lib -lfreetype" \
      EXPAT_CFLAGS="-I${prefix}/include" \
      EXPAT_LIBS="-L${prefix}/lib -lexpat"
    run_logged "fontconfig make ${abi}" make -j"$JOBS"
    run_logged "fontconfig install ${abi}" make install
  )
}

build_harfbuzz() {
  local abi="$1" prefix="$2"
  # CMake rather than meson: harfbuzz's CMake path takes the NDK's own
  # toolchain file directly, so there is no cross file to keep in step.
  run_logged "harfbuzz configure ${abi}" \
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
    -DHB_BUILD_DOCS=OFF
  run_logged "harfbuzz build ${abi}" \
    cmake --build "${SRC_DIR}/harfbuzz/_build-${abi}" -j"$JOBS"
  run_logged "harfbuzz install ${abi}" \
    cmake --install "${SRC_DIR}/harfbuzz/_build-${abi}"
}

build_libass() {
  local abi="$1" prefix="$2" cross="$3"
  local build; build="$(build_dir "$abi" libass)"
  # ./configure is generated into the source tree (autogen.sh cannot run from a
  # build directory) and every build below happens in this ABI's own.
  if [[ ! -x "${SRC_DIR}/libass/configure" ]]; then
    ( cd "${SRC_DIR}/libass" && ./autogen.sh >/dev/null 2>&1 || true )
  fi
  (
    cd "$build"
    # libass must find the prefixes above, not the host's own copies; naming
    # PKG_CONFIG explicitly stops --host from making autotools look for a
    # ${host}-pkg-config that does not exist.
    run_logged "libass configure ${abi}" \
      env "${CROSS_ENV[@]}" PKG_CONFIG=pkg-config \
        PKG_CONFIG_PATH="${prefix}/lib/pkgconfig" \
        PKG_CONFIG_LIBDIR="${prefix}/lib/pkgconfig" \
      "${SRC_DIR}/libass/configure" --host="$(autotools_host "$abi")" --prefix="$prefix" \
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
      HARFBUZZ_LIBS="-L${prefix}/lib -lharfbuzz"
    run_logged "libass make ${abi}" make -j"$JOBS"
    run_logged "libass install ${abi}" make install
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
  set_cross_env "$abi"

  # A cached prefix is only reused when every archive is present AND every one
  # of them was compiled for this ABI: a wrong-architecture archive in a
  # complete-looking prefix is exactly what the 2026-10-12 run restored and
  # then died on inside fontconfig's configure.
  if build_prefix_complete "$prefix" && prefix_arch_ok "$abi" "$prefix"; then
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
  prefix_arch_ok "$abi" "$prefix" ||
    die "${abi}: a dependency archive in ${prefix} was built for another architecture"
done

# --- Build libassjni.so per ABI --------------------------------------------

for abi in $ABIS; do
  prefix="${DEP_PREFIX}/${abi}"
  log "${abi}: linking libassjni.so"
  # ANDROID_STL=c++_static is set in the CMakeLists: nothing crosses a std::
  # type boundary through JNI, so the library must not add a second
  # libc++_shared.so next to the one libmpv already brings.
  cmake_args=(
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$abi"
    -DANDROID_PLATFORM="android-${ANDROID_API}"
    -DANDROID_STL=c++_static
    -DASS_DEPS_PREFIX="$DEP_PREFIX"
    -DCMAKE_BUILD_TYPE=Release
  )
  run_logged "libassjni configure ${abi}" \
    cmake -S "${REPO_ROOT}/scripts/libass-jni" -B "${WORK_DIR}/jni-${abi}" "${cmake_args[@]}"
  run_logged "libassjni link ${abi}" \
    cmake --build "${WORK_DIR}/jni-${abi}" -j"$JOBS"

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
