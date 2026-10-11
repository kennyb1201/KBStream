#!/usr/bin/env bash
#
# Fetches the MPV engine's native library into app/libs/.
#
# The backup playback engine no longer comes from Maven: it is the
# Riteshp2001/mpvlibAndroid (Mpv∞) release AAR, consumed by
# app/build.gradle.kts as a file dependency. A ~76 MB native binary is
# deliberately not vendored in git (app/libs/ is gitignored, exactly like
# libs/ and the FFmpeg extension), so this script is how a fresh checkout gets
# one - locally, and in every CI run before a Gradle task configures the app.
#
# Verified twice, on purpose:
#
#  - against the .sha256 published beside the asset, which is what the release
#    actually attests to; and
#  - against the digest PINNED below, so a re-uploaded asset (or a swapped
#    GitHub release) cannot silently change the binary this build ships.
#
# Idempotent: an already-present, correct AAR is left alone without a network
# call, so a CI cache restore or a previous local run costs nothing.
#
# FALLBACK BUILD: if the standard (Vulkan-capable) AAR still shows black video
# over playing audio on the field test, the release also ships
# `mpvlib-no-vulkan-${VERSION}.aar` - the OpenGL-only build for unstable GPU
# drivers. Swap it in BEFORE debugging anything in app code: set ASSET to
# `mpvlib-no-vulkan-${VERSION}.aar`, paste its own .sha256 into PINNED_SHA256
# (verified the same way), and update the artifact name in app/build.gradle.kts
# and the cache path in .github/workflows/build.yml to match. The library is
# the variable under test, not the config.
#
# Usage: bash scripts/fetch_mpv_aar.sh

set -euo pipefail

VERSION="1.1.2"
ASSET="mpvlib-${VERSION}.aar"
# mpvlib v1.1.2, downloaded 2026-10-11 and checked against the published
# mpvlib-1.1.2.aar.sha256. Its 64-bit .so files are 16 KB page aligned.
PINNED_SHA256="2bee300d6ab79a0b7317049bc62d3271b3c9e1b5d065f54907726f0da7b976da"
RELEASE_BASE="https://github.com/Riteshp2001/mpvlibAndroid/releases/download/v${VERSION}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${REPO_ROOT}/app/libs"
DEST="${DEST_DIR}/${ASSET}"

# sha256sum is coreutils (Linux/CI); shasum is what a stock macOS has.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

mkdir -p "${DEST_DIR}"

if [ -f "${DEST}" ] && [ "$(sha256_of "${DEST}")" = "${PINNED_SHA256}" ]; then
  echo "${ASSET} already present and verified at ${DEST}"
  exit 0
fi

echo "Fetching ${ASSET} from the mpvlibAndroid v${VERSION} release..."
tmp="${DEST}.tmp.$$"
trap 'rm -f "${tmp}" "${tmp}.sha256"' EXIT

curl -fsSL --retry 3 --retry-delay 2 -o "${tmp}" "${RELEASE_BASE}/${ASSET}"
curl -fsSL --retry 3 --retry-delay 2 -o "${tmp}.sha256" "${RELEASE_BASE}/${ASSET}.sha256"

published="$(awk '{print $1}' "${tmp}.sha256")"
computed="$(sha256_of "${tmp}")"

if [ "${published}" != "${computed}" ]; then
  echo "ERROR: ${ASSET} does not match the .sha256 published beside it." >&2
  echo "  published: ${published}" >&2
  echo "  downloaded: ${computed}" >&2
  exit 1
fi

if [ "${computed}" != "${PINNED_SHA256}" ]; then
  echo "ERROR: ${ASSET}'s digest differs from the one this build pins." >&2
  echo "  pinned: ${PINNED_SHA256}" >&2
  echo "  published: ${computed}" >&2
  echo "Update PINNED_SHA256 in this script only after reviewing the new AAR." >&2
  exit 1
fi

mv -f "${tmp}" "${DEST}"
echo "${ASSET} verified (${computed}) and placed at ${DEST}"
