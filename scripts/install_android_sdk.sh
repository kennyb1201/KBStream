#!/usr/bin/env bash
#
# Installs the Android SDK packages the cross-compile steps need, retrying a
# corrupted download instead of failing a release on it.
#
# Why this exists: the v0.9.19 release died in "Build FFmpeg video extension"
# after 18 seconds with
#
#   Warning: An error occurred while preparing SDK package NDK (Side by side)
#   28.2.13676358: Error reading Zip content from a SeekableByteChannel.
#
# and every downstream gate then failed with "was not produced" - which reads
# like a code regression, but was a truncated NDK download: the very next run
# of the same commit installed the same NDK without complaint. sdkmanager has
# no retry of its own, and `sdkmanager ... || sdkmanager ...` is not a retry
# either: a failed install leaves the half-unpacked package behind, so the
# second attempt reports "already installed" and exits 0 over a broken
# toolchain. Per package, this script therefore
#
#   1. runs sdkmanager,
#   2. accepts it only if the package's own directory now exists - the exit
#      code alone is not proof, which is the whole point,
#   3. on failure deletes the partial package and sdkmanager's scratch
#      directory, then tries again, up to SDK_INSTALL_ATTEMPTS (3) times,
#   4. exits 1 naming the package and the missing directory when every
#      attempt failed.
#
# Usage: install_android_sdk.sh "ndk;28.2.13676358" "cmake;3.22.1"
#
# Every package must name a version: step 2 is a path check, and a bare "ndk"
# names no path to check.
set -euo pipefail

ATTEMPTS="${SDK_INSTALL_ATTEMPTS:-3}"

if [[ $# -eq 0 ]]; then
  echo "::error::install_android_sdk.sh needs at least one 'name;version' package" >&2
  exit 2
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "::error::neither ANDROID_HOME nor ANDROID_SDK_ROOT is set; cannot install or verify a package" >&2
  exit 2
fi

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
  SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
fi
if [[ ! -x "$SDKMANAGER" ]]; then
  echo "::error::no sdkmanager found on PATH or at ${SDKMANAGER}" >&2
  exit 2
fi

# Where sdkmanager unpacks a package, for the families this workflow installs.
# The unlisted fallback covers the rest of the SDK (build-tools;x, platform;x,
# ...), whose layout is <root>/<name>/<version> as well.
package_dir() {
  local spec="$1" name version
  name="${spec%%;*}"
  version="${spec#*;}"
  if [[ -z "$version" || "$name" == "$spec" ]]; then
    echo "::error::'${spec}' has no version, so there is no directory to verify" >&2
    return 1
  fi
  case "$name" in
    ndk) printf '%s/ndk/%s\n' "$SDK_ROOT" "$version" ;;
    cmake) printf '%s/cmake/%s\n' "$SDK_ROOT" "$version" ;;
    *) printf '%s/%s/%s\n' "$SDK_ROOT" "$name" "$version" ;;
  esac
}

# Licences first: an unaccepted licence makes sdkmanager exit non-zero for a
# reason a retry cannot fix, so keep it out of the attempt loop.
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

for spec in "$@"; do
  target="$(package_dir "$spec")"
  attempt=1
  while :; do
    # sdkmanager's own output is kept on stdout: on a real failure it names the
    # package and the reason, and that log is the only record of why a release
    # stopped here.
    status=0
    "$SDKMANAGER" "$spec" 2>&1 || status=$?
    if [[ "$status" -eq 0 && -d "$target" ]]; then
      echo "Installed ${spec} at ${target}"
      break
    fi
    if (( attempt >= ATTEMPTS )); then
      echo "::error::${spec} could not be installed after ${ATTEMPTS} attempts (${target} is absent). A truncated download is the usual cause; re-running the job is the fix." >&2
      exit 1
    fi
    echo "::warning::${spec} attempt ${attempt} failed (exit ${status}); clearing ${target} and sdkmanager's scratch directory, then retrying" >&2
    rm -rf "$target"
    rm -rf "${SDK_ROOT}/.temp"
    attempt=$((attempt + 1))
  done
done
