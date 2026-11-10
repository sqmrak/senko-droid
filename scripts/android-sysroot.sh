#!/usr/bin/env bash
# prints the sysroot for one abi at SENKODROID_API. from api 16 up it is the ndk r23 sysroot
# (SENKODROID_SYSROOT). below that no current ndk ships the bionic stubs, so the
# headers and libraries of ndk r15c are staged in the r23 layout, per abi
set -euo pipefail

ABI="${1:?usage: android-sysroot.sh <armeabi|armeabi-v7a|arm64-v8a|x86|x86_64>}"
API="${SENKODROID_API:-16}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TRIPLE_DIR="$("${HERE}/android-abi.sh" "${ABI}" libdir)"

if (( API >= 16 )) || [[ "${ABI}" == arm64-v8a || "${ABI}" == x86_64 ]]; then
  echo "${SENKODROID_SYSROOT:?set SENKODROID_SYSROOT to the ndk sysroot}"
  exit 0
fi

NDK15="${SENKODROID_NDK15:?set SENKODROID_NDK15 to an ndk r15c directory (platforms/android-9 and sysroot)}"
OUT="${SENKODROID_BUILD:-${HERE}/../build}/sysroot-api${API}-${ABI}"
PLAT="${NDK15}/platforms/android-${API}/arch-$("${HERE}/android-abi.sh" "${ABI}" arch)/usr/lib"
[[ -f "${PLAT}/libc.so" ]] || { echo "no api ${API} libc.so at ${PLAT}" >&2; exit 1; }
[[ -f "${NDK15}/sysroot/usr/include/stdio.h" ]] || { echo "no headers at ${NDK15}/sysroot" >&2; exit 1; }

if [[ ! -f "${OUT}/usr/lib/${TRIPLE_DIR}/${API}/libc.so" ]]; then
  rm -rf "${OUT}"
  mkdir -p "${OUT}/usr/lib/${TRIPLE_DIR}/${API}"
  cp -a "${NDK15}/sysroot/usr/include" "${OUT}/usr/include"
  cp -a "${PLAT}/." "${OUT}/usr/lib/${TRIPLE_DIR}/${API}/"
  # clang looks for the static runtime pieces beside the triple, not under the api directory
  cp -a "${PLAT}"/libc.a "${PLAT}"/libm.a "${OUT}/usr/lib/${TRIPLE_DIR}/"
fi
echo "${OUT}"
