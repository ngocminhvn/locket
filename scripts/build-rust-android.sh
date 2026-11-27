#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v cargo >/dev/null || { echo "cargo is required" >&2; exit 1; }
command -v rustup >/dev/null || { echo "rustup is required" >&2; exit 1; }
cargo ndk --help >/dev/null 2>&1 || { echo "cargo-ndk is required" >&2; exit 1; }

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
mkdir -p "$REPO_ROOT/app/src/main/jniLibs"
cd "$REPO_ROOT/rust-core"
cargo ndk --platform 26 -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
  -o "$REPO_ROOT/app/src/main/jniLibs" build --release --locked
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  library="$REPO_ROOT/app/src/main/jniLibs/$abi/liblocket_core.so"
  test -s "$library" || { echo "Missing Rust library for $abi" >&2; exit 1; }
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    readelf="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    test -x "$readelf" || { echo "llvm-readelf not found in Android NDK" >&2; exit 1; }
    "$readelf" -lW "$library" | awk '$1 == "LOAD" && $NF != "0x4000" { exit 1 }' || {
      echo "ELF load segments are not 16 KiB aligned for $abi" >&2
      exit 1
    }
  fi
done
echo "OK: Rust libraries compiled for all four Android ABIs"
