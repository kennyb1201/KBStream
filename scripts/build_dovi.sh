#!/usr/bin/env bash
#
# Build libdovi (quietvoid/dovi_tool's `dolby_vision` crate, C API) and the JNI
# bridge that talks to it, and drop both into app/src/main/jniLibs/<abi>/, where
# Gradle packages them into the APK with no further wiring.
#
# Why this exists
# ---------------
# The ExoPlayer engine rewrites a Dolby Vision Player 7 RPU into a Profile 8.1
# one by hand (DolbyVisionCompat.rewriteRpuTo81, plus the VPS/SPS rewriters
# beside it). On the field boxes the MediaTek decoder stalls on every variant of
# that rewrite -- relabel, no-relabel, VPS rewrite -- and presents as audio over
# a black screen; the reference player, which hands the same file to libdovi and
# lets IT produce the 8.1 RPU, renders. So the RPU bytes now come from libdovi:
# app/src/main/cpp/dovi_bridge.c is the bridge and
# app/src/main/java/com/kennyb1201/kbstream/ui/player/DoviBridge.kt is its
# Kotlin surface.
#
# The app does not require the result to RUN. When the .so files are absent,
# DoviBridge.available is false and the Kotlin RPU rewrite stays in charge --
# which is what every local build and any CI run whose native step failed gets.
# That is NOT acceptable for a release, so the CI step that runs this is a hard
# gate: a miss fails the release instead of publishing without the RPU path the
# DV settings are field-tested against. See the libdovi block in
# .github/workflows/build.yml.
#
# This script's own gates are fatal too: a run that does produce the libraries
# cannot leave one missing or unlinked from libdovi behind (see the verify
# section at the end), and the arm64-v8a library has to be 16 KB page aligned
# for the Play requirement that takes effect on 1 February 2027.
#
# Requirements
# ------------
#   cargo + rustc (stable) with the two Android targets:
#     rustup target add aarch64-linux-android armv7-linux-androideabi
#   cargo-ndk:
#     cargo install cargo-ndk
#   ANDROID_NDK (or ANDROID_NDK_HOME, or a single NDK under $ANDROID_HOME/ndk)
#   python3 (to patch the fetched crate's crate-type; see below)
#
# Environment
# -----------
#   DOVI_VERSION   dolby_vision crate version to build (default 3.4.0)
#   ABIS           space-separated Android ABIs (default "arm64-v8a armeabi-v7a")
#   OUTPUT_DIR     where the .so files land (default app/src/main/jniLibs)
#   WORK_DIR       scratch/build dir (default build/dovi)
#
# Layout of what it produces, per ABI under OUTPUT_DIR:
#   libdovi.so         the crate's C API
#   libdovi_bridge.so  dovi_bridge.c, linked against libdovi.so and liblog

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOVI_VERSION="${DOVI_VERSION:-3.4.0}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
OUTPUT_DIR="${OUTPUT_DIR:-${REPO_ROOT}/app/src/main/jniLibs}"
WORK_DIR="${WORK_DIR:-${REPO_ROOT}/build/dovi}"
BRIDGE_SRC="${REPO_ROOT}/app/src/main/cpp/dovi_bridge.c"
BRIDGE_INCLUDE="${REPO_ROOT}/app/src/main/cpp/libdovi-include"

log() { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- preconditions -----------------------------------------------------------
command -v cargo >/dev/null 2>&1 || fail "cargo not found: install Rust (rustup) first"
command -v cargo-ndk >/dev/null 2>&1 || fail "cargo-ndk not found: cargo install cargo-ndk"
command -v python3 >/dev/null 2>&1 || fail "python3 not found: it patches the fetched crate's crate-type"
[[ -f "$BRIDGE_SRC" ]] || fail "missing ${BRIDGE_SRC}"
[[ -d "$BRIDGE_INCLUDE/libdovi" ]] || fail "missing ${BRIDGE_INCLUDE}/libdovi/rpu_parser.h"

if [[ -z "${ANDROID_NDK:-}" ]]; then
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK="$ANDROID_NDK_HOME"
  elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
    # A single installed NDK is unambiguous; several means the caller has to say
    # which, because the static archives and the link that consumes them must be
    # one toolchain.
    ndks=("${ANDROID_HOME}/ndk"/*)
    [[ ${#ndks[@]} -eq 1 ]] || fail "several NDKs under ${ANDROID_HOME}/ndk: set ANDROID_NDK to one of them"
    ANDROID_NDK="${ndks[0]}"
  else
    fail "set ANDROID_NDK (or ANDROID_NDK_HOME) to an Android NDK r25+"
  fi
fi
[[ -d "$ANDROID_NDK" ]] || fail "ANDROID_NDK=${ANDROID_NDK} is not a directory"

TOOLCHAIN="${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64"
[[ -x "${TOOLCHAIN}/bin/clang" ]] || fail "no clang in ${TOOLCHAIN}/bin (unexpected NDK layout)"
READELF="${TOOLCHAIN}/bin/llvm-readelf"

# The app's floor is API 26 (see app/build.gradle.kts); media3's ndkVersion and
# the libass build both target it, and the bridge must not out-date them.
API_LEVEL="${API_LEVEL:-26}"

# --- helpers -----------------------------------------------------------------
# Android ABI -> Rust target triple and the NDK clang prefix.
rust_target() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7-linux-androideabi" ;;
    *) fail "unsupported ABI: $1" ;;
  esac
}

ndk_clang() {
  case "$1" in
    arm64-v8a) echo "${TOOLCHAIN}/bin/aarch64-linux-android${API_LEVEL}-clang" ;;
    armeabi-v7a) echo "${TOOLCHAIN}/bin/armv7a-linux-androideabi${API_LEVEL}-clang" ;;
    *) fail "unsupported ABI: $1" ;;
  esac
}

# 16 KB alignment, the Play requirement that takes effect on 1 February 2027. The
# test is the one the repo already uses for this (see build_libass.sh and the
# workflow): a 16 KB aligned object is one with a LOAD segment whose alignment is
# 0x4000. Only 64-bit ABIs can request 16 KB pages, so armeabi-v7a is reported
# but never gated.
verify_alignment() {
  local so="$1" abi="$2" label="$3"
  local page
  page="$("$READELF" -lW "$so" | awk '$1 == "LOAD" { if ($NF == "0x4000") found = 1 } END { exit(found ? 0 : 1) }' && echo aligned || echo unaligned)"
  if [[ "$abi" == "arm64-v8a" && "$page" != "aligned" ]]; then
    fail "$label (${abi}) is not 16 KB page aligned: it crashes on a 16 KB page-size device (Android 15+) and Play rejects it from 1 February 2027"
  fi
  log "  ${label} (${abi}): ${page}"
}

# --- 1. the crate ------------------------------------------------------------
# Fetched from crates.io at an EXACT version rather than cloned from a tag: the
# crate version is what the bridge's declarations and the mode mapping are pinned
# to, and a tag name is one more thing to get wrong on a runner.
log "== dovi_bridge: building dolby_vision ${DOVI_VERSION} =="
mkdir -p "${WORK_DIR}/fetch/src"
cat > "${WORK_DIR}/fetch/Cargo.toml" <<EOF
[package]
name = "kbstream-dovi-fetch"
version = "0.0.0"
edition = "2021"

[lib]
path = "src/lib.rs"

[dependencies]
dolby_vision = { version = "=${DOVI_VERSION}", features = ["capi"] }
EOF
: > "${WORK_DIR}/fetch/src/lib.rs"

# --manifest-path, not a cd: the scratch crate is not part of this repo's
# workspace and must not touch its Cargo.lock.
( cd "${WORK_DIR}/fetch" && cargo fetch --quiet )

CARGO_HOME_DIR="${CARGO_HOME:-$HOME/.cargo}"
CRATE_SRC="$(find "${CARGO_HOME_DIR}/registry/src" -maxdepth 2 -type d \
  -name "dolby_vision-${DOVI_VERSION}" 2>/dev/null | head -1)"
[[ -n "$CRATE_SRC" ]] || fail "the fetched dolby_vision-${DOVI_VERSION} source was not found under ${CARGO_HOME_DIR}/registry/src"

# A copy, so the registry cache stays as cargo left it.
CRATE_DIR="${WORK_DIR}/crate"
rm -rf "$CRATE_DIR"
cp -R "$CRATE_SRC" "$CRATE_DIR"

# The published crate declares no crate-type (cargo-c passes it on the command
# line), so a plain `cargo build` yields an rlib and no .so. `name = "dovi"` is
# forced for the same reason the file has to be called libdovi.so: it is what the
# bridge links with (-ldovi) and what the C API's own metadata asks for.
python3 - "$CRATE_DIR/Cargo.toml" <<'PY'
import re, sys

path = sys.argv[1]
with open(path) as handle:
    text = handle.read()

def set_key(section_text, key, value):
    if re.search(rf'(?m)^{key}\s*=', section_text):
        return re.sub(rf'(?m)^{key}\s*=.*$', f'{key} = {value}', section_text, count=1)
    return section_text.rstrip('\n') + f'\n{key} = {value}\n'

match = re.search(r'(?ms)^\[lib\]\s*\n(.*?)(?=^\[|\Z)', text)
if match:
    section = match.group(1)
    section = set_key(section, 'name', '"dovi"')
    section = set_key(section, 'crate-type', '["cdylib"]')
    text = text[:match.start()] + '[lib]\n' + section.lstrip('\n') + '\n' + text[match.end():]
else:
    text = text.rstrip('\n') + '\n\n[lib]\nname = "dovi"\ncrate-type = ["cdylib"]\n'

with open(path, 'w') as handle:
    handle.write(text)
PY
log "  patched $(basename "$CRATE_DIR")/Cargo.toml: name=\"dovi\", crate-type=[\"cdylib\"]"

# The linker flags, not a linker script, are what make the ELF 16 KB aligned.
# Both are documented by Google for NDK r27 and older; r28 defaults to them, and
# passing them is harmless there.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
export ANDROID_NDK_HOME="$ANDROID_NDK"
# Outside the disposable crate copy above: the target dir is what makes a
# second run fast (`cache the libdovi build` in CI restores build/dovi), and it
# is wiped with the copy if it lives inside it. It does not depend on
# dovi_bridge.c -- only the crate does -- so a bridge-only change reuses it.
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-${WORK_DIR}/target}"

RUST_TARGETS=()
for abi in $ABIS; do
  RUST_TARGETS+=(-t "$abi")
done
rustup target add aarch64-linux-android armv7-linux-androideabi >/dev/null 2>&1 || true

DOVI_OUT="${WORK_DIR}/jniLibs"
rm -rf "$DOVI_OUT"
log "  cargo ndk build (this takes a few minutes the first time)"
( cd "$CRATE_DIR" && cargo ndk "${RUST_TARGETS[@]}" -o "$DOVI_OUT" build --release --features capi )

# --- 2. the bridge -----------------------------------------------------------
log "== dovi_bridge: compiling the JNI bridge =="
for abi in $ABIS; do
  dovi_so="${DOVI_OUT}/${abi}/libdovi.so"
  [[ -s "$dovi_so" ]] || fail "libdovi.so was not produced for ${abi} (cargo ndk's log above says why)"

  mkdir -p "${OUTPUT_DIR}/${abi}"
  cp "$dovi_so" "${OUTPUT_DIR}/${abi}/libdovi.so"

  CLANG="$(ndk_clang "$abi")"
  [[ -x "$CLANG" ]] || fail "no NDK compiler at ${CLANG}"

  # -Wl,--no-undefined: an unresolved libdovi symbol has to fail HERE, not at
  # dlopen time on a TV.
  "$CLANG" -shared -O2 -fPIC -fvisibility=hidden \
    -I"$BRIDGE_INCLUDE" \
    -o "${OUTPUT_DIR}/${abi}/libdovi_bridge.so" \
    "$BRIDGE_SRC" \
    -L"${DOVI_OUT}/${abi}" -ldovi -llog \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -Wl,--no-undefined
  log "  libdovi_bridge.so (${abi}) linked"
done

# --- 3. verify ---------------------------------------------------------------
# Fatal: a run that got this far may not leave a library that is missing, not
# linked against libdovi, or misaligned behind.
log "== dovi_bridge: verifying =="
for abi in $ABIS; do
  for lib in libdovi.so libdovi_bridge.so; do
    so="${OUTPUT_DIR}/${abi}/${lib}"
    [[ -s "$so" ]] || fail "${lib} (${abi}) was not produced"
    log "  ${lib} (${abi}): $(wc -c < "$so") bytes"
  done

  bridge="${OUTPUT_DIR}/${abi}/libdovi_bridge.so"
  if [[ -x "$READELF" ]]; then
    "$READELF" -dW "$bridge" | grep -q 'NEEDED.*libdovi\.so' ||
      fail "libdovi_bridge.so (${abi}) does not link libdovi.so; the app would dlopen a library with unresolved RPU symbols"
    log "  libdovi_bridge.so (${abi}): linked against libdovi.so"
  else
    log "  llvm-readelf not found at ${READELF}; skipping the link check"
  fi
done

# Alignment last, once both files exist: arm64-v8a only (see verify_alignment).
for abi in $ABIS; do
  [[ "$abi" == "arm64-v8a" ]] || continue
  verify_alignment "${OUTPUT_DIR}/${abi}/libdovi.so" "$abi" "libdovi.so"
  verify_alignment "${OUTPUT_DIR}/${abi}/libdovi_bridge.so" "$abi" "libdovi_bridge.so"
done

log "== dovi_bridge: done — ${OUTPUT_DIR}/<abi>/libdovi.so + libdovi_bridge.so =="
