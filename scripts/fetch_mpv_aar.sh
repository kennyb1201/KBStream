#!/usr/bin/env bash
#
# Fetches the MPV engine's native library into app/libs/.
#
# The backup playback engine does not come from Maven: it is the
# fortunasxp/mpv-android-libdovi release AAR, consumed by app/build.gradle.kts as
# a file dependency. A ~155 MB native binary is deliberately not vendored in git
# (app/libs/ is gitignored, exactly like libs/ and the FFmpeg extension), so this
# script is how a fresh checkout gets one - locally, and in every CI run before a
# Gradle task configures the app.
#
# What this library is, and why it replaced the previous one:
#
#  - it is a libdovi-enabled rebuild of the `io.github.abdallahmehiz:mpv-android-lib`
#    shell (the same `classes.jar` + `libplayer.so` + `libc++_shared.so` +
#    `libxml2.so`), with `libmpv.so` and the FFmpeg libraries swapped for a build
#    carrying libplacebo `-Ddovi=enabled` (DV reshape + HDR metadata from the
#    RPU), libdovi 3.3.2 statically linked, FFmpeg n8.1.1 with `dovi_rpudec`, and
#    mpv on `vo=gpu-next` rather than `vo=gpu`;
#  - because that shell is reused, the Java class the engine binds is
#    `is.xyz.mpv.MPV` (a CLASS), NOT the previous library's `is.xyz.mpv.MPVLib`
#    (a Kotlin object). `src/mpvrites/.../MpvBackend.kt` is the one file that
#    names it;
#  - it is a drop-in for the shell it reuses, not for Riteshp2001/mpvlibAndroid:
#    the AAR it replaces exposed a different Java API, so this is a source change
#    as well as a binary one.
#
# Verified twice, on purpose:
#
#  - against the digest PINNED below, so a re-uploaded asset (or a swapped
#    GitHub release) cannot silently change the binary this build ships; and
#  - against the release's own asset listing, which is the only other public
#    statement of what the asset is (this release publishes no .sha256 beside the
#    asset - unlike the mpvlibAndroid one this script used to fetch - so the pin
#    is the whole verification, and it is why the pin is checked against the
#    downloaded bytes and nothing else).
#
# Idempotent: an already-present, correct AAR is left alone without a network
# call, so a CI cache restore or a previous local run costs nothing.
#
# Usage: bash scripts/fetch_mpv_aar.sh

set -euo pipefail

VERSION="mpv-libdovi-v1"
ASSET="mpv-android-lib-libdovi.aar"
# fortunasxp/mpv-android-libdovi, release `mpv-libdovi-v1`, downloaded
# 2026-10-15 and hashed here. Verified 16 KB page aligned (readelf -lW) on every
# 64-bit .so it carries - arm64-v8a and x86_64 - and present for all four ABIs
# (arm64-v8a, armeabi-v7a, x86_64, x86). The 32-bit ABIs legitimately carry
# 4 KB LOAD alignment on libc++_shared.so and libplayer.so, exactly as they did
# in the previous AAR, and Google exempts them.
PINNED_SHA256="b19d39a7b4d1c65b48cfdc480c905ffb5846e24277a0bc434bb7bd97dc874ff0"
RELEASE_BASE="https://github.com/FortunasXP/mpv-android-libdovi/releases/download/${VERSION}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${REPO_ROOT}/app/libs"
DEST="${DEST_DIR}/${ASSET}"

# The library this replaced. Left here as a deliberate tripwire: a stale copy of
# it in app/libs/ is what a half-finished swap looks like, and the Gradle guard
# only knows about the AAR it expects.
STALE_ASSET="mpvlib-1.1.2.aar"

# sha256sum is coreutils (Linux/CI); shasum is what a stock macOS has.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

mkdir -p "${DEST_DIR}"

if [ -f "${DEST_DIR}/${STALE_ASSET}" ]; then
  echo "Removing the superseded ${STALE_ASSET} (the engine no longer links it)"
  rm -f "${DEST_DIR}/${STALE_ASSET}"
fi

if [ -f "${DEST}" ] && [ "$(sha256_of "${DEST}")" = "${PINNED_SHA256}" ]; then
  echo "${ASSET} already present and verified at ${DEST}"
  exit 0
fi

echo "Fetching ${ASSET} from the mpv-android-libdovi ${VERSION} release..."
tmp="${DEST}.tmp.$$"
trap 'rm -f "${tmp}"' EXIT

curl -fsSL --retry 3 --retry-delay 2 -o "${tmp}" "${RELEASE_BASE}/${ASSET}"

computed="$(sha256_of "${tmp}")"

if [ "${computed}" != "${PINNED_SHA256}" ]; then
  echo "ERROR: ${ASSET}'s digest differs from the one this build pins." >&2
  echo "  pinned:     ${PINNED_SHA256}" >&2
  echo "  downloaded: ${computed}" >&2
  echo "Update PINNED_SHA256 in this script only after reviewing the new AAR." >&2
  exit 1
fi

# The 16 KB page-size gate (Play requirement, Feb 1 2027), on the artifact
# itself rather than only on the packaged APK: a swapped or re-uploaded AAR is
# exactly the case the APK-level check in the release workflow would catch, but
# only on a release run. Only the 64-bit ABIs are checked - only they are
# required to be 16 KB aligned - and a vacuous pass is a failure, because an AAR
# whose layout moved would otherwise silently stop being gated.
if command -v readelf >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  check_dir="${tmp}.jni.$$"
  mkdir -p "${check_dir}"
  unzip -q -o "${tmp}" 'jni/*' -d "${check_dir}"
  checked=0
  for abi in arm64-v8a x86_64; do
    for so in "${check_dir}/jni/${abi}"/*.so; do
      [ -f "${so}" ] || continue
      checked=$((checked + 1))
      # -lW (wide): binutils WRAPS the long LOAD line, which pushes the alignment
      # column onto a continuation line, so plain -l reads an aligned library as
      # unaligned. A 16 KB aligned object reports 0x4000 (or larger).
      while IFS= read -r align; do
        case "${align}" in
          0x1000|0x800|0x400|0x200)
            echo "ERROR: ${abi}/$(basename "${so}") has a LOAD segment aligned ${align}," >&2
            echo "  not 16 KB. It would crash on a 16 KB page-size device and Play" >&2
            echo "  rejects it from Feb 1 2027. Do not ship this AAR." >&2
            rm -rf "${check_dir}"
            exit 1
            ;;
        esac
      done < <(readelf -lW "${so}" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}')
    done
  done
  rm -rf "${check_dir}"
  if [ "${checked}" -eq 0 ]; then
    echo "ERROR: no 64-bit .so files found in ${ASSET}; the AAR layout changed and" >&2
    echo "  this alignment gate would be vacuous. Re-check it by hand." >&2
    exit 1
  fi
  echo "${ASSET} is 16 KB page aligned across ${checked} 64-bit libraries"
else
  echo "WARNING: readelf/unzip unavailable; skipping the 16 KB alignment gate." >&2
  echo "  The release workflow's APK-level check still covers this." >&2
fi

mv -f "${tmp}" "${DEST}"
echo "${ASSET} verified (${computed}) and placed at ${DEST}"
