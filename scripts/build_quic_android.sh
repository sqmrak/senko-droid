#!/usr/bin/env bash
# static ngtcp2 and nghttp3 for the hysteria2 client, one android abi
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="${1:?usage: build_quic_android.sh <armeabi|armeabi-v7a|arm64-v8a|x86|x86_64>}"
NGTCP2="${SENKODROID_NGTCP2_SRC:?set SENKODROID_NGTCP2_SRC to the ngtcp2 source directory}"
NGHTTP3="${SENKODROID_NGHTTP3_SRC:?set SENKODROID_NGHTTP3_SRC to the nghttp3 source directory}"
OUT="${SENKODROID_BUILD:-${ROOT}/build}"
# shellcheck source=android-env.sh
source "${ROOT}/scripts/android-env.sh"
senkodroid_abi "${ABI}"

OSSL="${OUT}/openssl-${ABI}"
DIR="${OUT}/quic-${ABI}"
if [[ -f "${DIR}/lib/libngtcp2.a" && -f "${DIR}/lib/libnghttp3.a" ]]; then
  echo "quic ${ABI} already at ${DIR}"
  exit 0
fi
for dir in "${NGTCP2}/lib" "${NGHTTP3}/lib"; do
  [[ -d "${dir}" ]] || { echo "missing ${dir}" >&2; exit 1; }
done
# ngtcp2_crypto_ossl needs SSL_set_quic_tls_cbs, which only openssl 3.5 has
if ! "${SENKODROID_LLVM}/bin/llvm-nm" "${OSSL}/lib/libssl.a" 2>/dev/null |
     grep ' T SSL_set_quic_tls_cbs' >/dev/null; then
  echo "${OSSL} has no SSL_set_quic_tls_cbs, run build_openssl_android.sh ${ABI} first" >&2
  exit 1
fi

OBJ="${DIR}/obj"
rm -rf "${DIR}"
mkdir -p "${OBJ}/ngtcp2" "${OBJ}/nghttp3" "${DIR}/lib"
CC=("${SENKODROID_LLVM}/bin/clang" --target="${ABI_TRIPLE}" --sysroot="${ABI_SYSROOT}")
# the sources read config.h only under HAVE_CONFIG_H, so the few feature
# macros go on the command line. bionic has no explicit_bzero before api 28
# and no memset_s, ngtcp2 falls back to a volatile memset pointer
FLAGS=(${ABI_CFLAGS} -std=gnu11 -O2 -fPIC -fno-strict-aliasing -D_GNU_SOURCE
  -DNGTCP2_STATICLIB -DNGHTTP3_STATICLIB
  -DHAVE_ARPA_INET_H -DHAVE_NETINET_IN_H -DHAVE_UNISTD_H
  -I"${NGTCP2}/lib" -I"${NGTCP2}/lib/includes" -I"${NGTCP2}/crypto"
  -I"${NGTCP2}/crypto/includes" -I"${NGHTTP3}/lib" -I"${NGHTTP3}/lib/includes"
  -I"${OSSL}/include")

compile() {
  local src="$1" out="$2"
  "${CC[@]}" "${FLAGS[@]}" -c "${src}" -o "${out}"
}
export -f compile
for src in "${NGTCP2}"/lib/*.c "${NGTCP2}/crypto/shared.c" "${NGTCP2}/crypto/ossl/ossl.c"; do
  compile "${src}" "${OBJ}/ngtcp2/$(basename "${src}" .c).o" &
  while (( $(jobs -r | wc -l) >= $(nproc) )); do wait -n; done
done
# nghttp3 vendors sfparse for the priority header
for src in "${NGHTTP3}"/lib/*.c "${NGHTTP3}/lib/sfparse/sfparse.c"; do
  compile "${src}" "${OBJ}/nghttp3/$(basename "${src}" .c).o" &
  while (( $(jobs -r | wc -l) >= $(nproc) )); do wait -n; done
done
wait

"${SENKODROID_LLVM}/bin/llvm-ar" rcs "${DIR}/lib/libngtcp2.a" "${OBJ}"/ngtcp2/*.o
"${SENKODROID_LLVM}/bin/llvm-ar" rcs "${DIR}/lib/libnghttp3.a" "${OBJ}"/nghttp3/*.o
rm -rf "${OBJ}"
mkdir -p "${DIR}/include/ngtcp2" "${DIR}/include/nghttp3"
cp "${NGTCP2}"/lib/includes/ngtcp2/*.h "${DIR}/include/ngtcp2/"
cp "${NGTCP2}/crypto/includes/ngtcp2/ngtcp2_crypto.h" \
   "${NGTCP2}/crypto/includes/ngtcp2/ngtcp2_crypto_ossl.h" "${DIR}/include/ngtcp2/"
cp "${NGHTTP3}"/lib/includes/nghttp3/*.h "${DIR}/include/nghttp3/"
echo "quic ${ABI} installed: ${DIR}"
