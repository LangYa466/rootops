#!/usr/bin/env bash
#
# Builds librootops_pty.so for every Android ABI the app ships.
# The output goes straight into app/src/main/jniLibs/<abi>/ so Gradle packages
# the prebuilt library without needing an externalNativeBuild step.
#
# Usage: scripts/build-native.sh [--clean]
#
set -euo pipefail

NDK="${NDK:-/opt/android-sdk/ndk/29.0.14206865}"
API="${API:-26}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SRC="${ROOT}/app/src/main/cpp/pty.c"
OUT_ROOT="${ROOT}/app/src/main/jniLibs"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"

# abi:clang-target-prefix
ABIS=(
  "arm64-v8a:aarch64-linux-android"
  "armeabi-v7a:armv7a-linux-androideabi"
  "x86_64:x86_64-linux-android"
)

if [[ ! -f "${SRC}" ]]; then
  echo "error: missing source ${SRC}" >&2
  exit 1
fi
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "error: NDK toolchain not found at ${TOOLCHAIN}" >&2
  echo "       set NDK=/path/to/ndk" >&2
  exit 1
fi

if [[ "${1:-}" == "--clean" ]]; then
  for entry in "${ABIS[@]}"; do
    abi="${entry%%:*}"
    rm -f "${OUT_ROOT}/${abi}/librootops_pty.so"
  done
fi

CFLAGS=(
  -shared
  -fPIC
  -O2
  -Wall
  -Wextra
  -Werror
  -fno-common
  -DANDROID
  # 16 KB page size compatible (required for Android 15+ arm64 devices).
  -Wl,-z,max-page-size=16384
  -Wl,-z,noexecstack
  -Wl,--build-id=sha1
)

echo "NDK:  ${NDK}"
echo "API:  ${API}"
echo "Src:  ${SRC}"
echo

for entry in "${ABIS[@]}"; do
  abi="${entry%%:*}"
  triple="${entry##*:}"
  cc="${TOOLCHAIN}/${triple}${API}-clang"
  out_dir="${OUT_ROOT}/${abi}"
  out="${out_dir}/librootops_pty.so"

  if [[ ! -x "${cc}" ]]; then
    echo "error: compiler not found: ${cc}" >&2
    exit 1
  fi

  mkdir -p "${out_dir}"
  echo "==> ${abi} (${triple}${API})"
  "${cc}" "${CFLAGS[@]}" -o "${out}" "${SRC}"

  size="$(stat -c %s "${out}")"
  echo "    ${out}  (${size} bytes)"
done

echo
echo "Done. Verify with:"
echo "  file app/src/main/jniLibs/*/librootops_pty.so"
echo "  nm -D app/src/main/jniLibs/arm64-v8a/librootops_pty.so | grep -E 'openPty|JNI_OnLoad'"
