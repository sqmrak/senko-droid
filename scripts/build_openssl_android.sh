#!/usr/bin/env bash
# static openssl for one android abi, built against the bionic sysroot of SENKODROID_API
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="${1:?usage: build_openssl_android.sh <armeabi|armeabi-v7a|arm64-v8a|x86|x86_64>}"
SRC="${SENKODROID_OPENSSL_SRC:?set SENKODROID_OPENSSL_SRC to the openssl source directory}"
OUT="${SENKODROID_BUILD:-${ROOT}/build}"
# shellcheck source=android-env.sh
source "${ROOT}/scripts/android-env.sh"
senkodroid_abi "${ABI}"

PREFIX="${OUT}/openssl-${ABI}"
BLD="${OUT}/openssl-${ABI}-obj"
[[ -f "${SRC}/Configure" ]] || { echo "missing ${SRC}/Configure" >&2; exit 1; }

if [[ -f "${PREFIX}/lib/libssl.a" && -f "${PREFIX}/lib/libcrypto.a" ]]; then
  echo "openssl ${ABI} already at ${PREFIX}"
  exit 0
fi

rm -rf "${BLD}"
mkdir -p "${BLD}" "${PREFIX}/lib" "${PREFIX}/include"
CC_WRAP="${BLD}/cc"
cat > "${CC_WRAP}" <<WRAP
#!/bin/sh
exec ${SENKODROID_LLVM}/bin/clang --target=${ABI_TRIPLE} --sysroot=${ABI_SYSROOT} ${ABI_CFLAGS} "\$@"
WRAP
chmod +x "${CC_WRAP}"

cd "${BLD}"
# no-async: bionic has no makecontext. no-asm keeps the armv7 slice free of
# instructions a cortex-a8 class core does not run
"${SRC}/Configure" "${ABI_SSL}" \
  "CC=${CC_WRAP}" "AR=${SENKODROID_LLVM}/bin/llvm-ar" "RANLIB=${SENKODROID_LLVM}/bin/llvm-ranlib" \
  no-asm no-shared no-dso no-tests no-engine no-ui-console no-async no-apps no-docs \
  -O2 -fno-strict-aliasing -fPIC --prefix="${PREFIX}"
make -j"$(nproc)" build_libs

cp -a libssl.a libcrypto.a "${PREFIX}/lib/"
cp -a "${SRC}/include/openssl" "${PREFIX}/include/"
cp -a include/openssl/. "${PREFIX}/include/openssl/"
echo "openssl ${ABI} installed: ${PREFIX}"
