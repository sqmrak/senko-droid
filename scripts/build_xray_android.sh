#!/usr/bin/env bash
# the senko-core build of xray as a shared library for one android abi: libsenkocore.so. it is
# loaded at run time by the engine, so a device that cannot run it falls back to the c engine
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="${1:?usage: build_xray_android.sh <armeabi-v7a|arm64-v8a|x86|x86_64>}"
GO_BIN="${SENKODROID_GO:?set SENKODROID_GO to the go 1.27.1 executable}"
SRC="${SENKODROID_CORE_SRC:?set SENKODROID_CORE_SRC to the senko-core (xray) source, the one senko builds ios with}"
LLVM="${SENKODROID_LLVM:?set SENKODROID_LLVM to the ndk llvm prebuilt directory}"
SYSROOT="${SENKODROID_SYSROOT:?set SENKODROID_SYSROOT to the ndk r23 sysroot}"
# the cgo part is built against api 16 bionic: at 21 it imports sigfillset, which only api 21 exports.
# the go runtime itself was run on android 4.4 (x86) and nowhere older, see README
API="${SENKODROID_CORE_API:-16}"
# full links every xray proxy and transport. trim is the small distro senko built for ios
DISTRO="${SENKODROID_CORE_DISTRO:-full}"
OUT_DIR="${SENKODROID_BUILD:-${ROOT}/build}/xray-api${API}/${ABI}"

[[ "$("${GO_BIN}" version)" == "go version go1.27.1 "* ]] || { echo "the core needs the pinned go 1.27.1" >&2; exit 1; }
[[ -f "${SRC}/main/distro/senko/senko.go" ]] || { echo "${SRC} is not a senko-core source with the senko distro" >&2; exit 1; }
[[ -f "${SRC}/LICENSE" ]] || { echo "the senko-core license is missing" >&2; exit 1; }

case "${ABI}" in
  armeabi-v7a) GOARCH=arm; TRIPLE="armv7a-linux-androideabi${API}"; EXTRA="GOARM=7"; LD_EXTRA="" ;;
  # there is no 64 bit android below api 21, so no sysroot either
  arm64-v8a)   GOARCH=arm64; TRIPLE="aarch64-linux-android$(( API < 21 ? 21 : API ))"; EXTRA="GOARM="; LD_EXTRA="-Wl,-z,max-page-size=16384" ;;
  x86_64)      GOARCH=amd64; TRIPLE="x86_64-linux-android$(( API < 21 ? 21 : API ))"; EXTRA="GOARM="; LD_EXTRA="-Wl,-z,max-page-size=16384" ;;
  x86)         GOARCH=386; TRIPLE="i686-linux-android${API}"; EXTRA="GOARM="; LD_EXTRA="" ;;
  *) echo "unknown abi: ${ABI}" >&2; exit 1 ;;
esac

case "${DISTRO}" in
  full)
    TAGS=""
    # the full distro links wireguard and pion, whose anet package reads net.zoneCache by linkname,
    # which go 1.23 and later refuse unless the check is off. it is the one such reference
    LDFLAGS="-s -w -buildid= -checklinkname=0" ;;
  trim) TAGS="-tags senko_trim"; LDFLAGS="-s -w -buildid=" ;;
  *) echo "SENKODROID_CORE_DISTRO is full or trim" >&2; exit 1 ;;
esac

WORK="$(mktemp -d "${TMPDIR:-/tmp}/senkodroid-core.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${OUT_DIR}"

# the tun udp patch bounds the udp flows of the tun inbound, which a phone needs as much as ios does
mkdir -p "${WORK}/core/proxy/tun"
for file in udp_fullcone.go stack_gvisor.go; do cp "${SRC}/proxy/tun/${file}" "${WORK}/core/proxy/tun/${file}"; done
patch -s -p1 -d "${WORK}/core" < "${ROOT}/xraycore/patches/senko-core-tun-udp.patch"
python3 - "${SRC}" "${WORK}" <<'PY'
import json, sys
from pathlib import Path
source, stage = map(Path, sys.argv[1:])
overlay = {"Replace": {}}
for name in ("udp_fullcone.go", "stack_gvisor.go"):
    relative = Path("proxy/tun") / name
    overlay["Replace"][str(source / relative)] = str(stage / "core" / relative)
(stage / "overlay.json").write_text(json.dumps(overlay) + "\n")
PY

cp "${ROOT}/xraycore/go.mod" "${WORK}/go.mod"
cp "${ROOT}/xraycore/go.sum" "${WORK}/go.sum"
export GOFLAGS="${GOFLAGS:--mod=mod}"
export GOCACHE="${GOCACHE:-${SENKODROID_BUILD:-${ROOT}/build}/gocache}"
(cd "${ROOT}/xraycore" && "${GO_BIN}" mod edit -modfile "${WORK}/go.mod" -replace "github.com/xtls/xray-core=${SRC}")

CC_CMD="${LLVM}/bin/clang --target=${TRIPLE} --sysroot=${SYSROOT}"
(
  cd "${ROOT}/xraycore"
  env GOOS=android GOARCH="${GOARCH}" ${EXTRA} CGO_ENABLED=1 \
    CC="${CC_CMD}" CGO_LDFLAGS="${LD_EXTRA}" \
    "${GO_BIN}" build -modfile "${WORK}/go.mod" -overlay "${WORK}/overlay.json" ${TAGS} \
      -buildvcs=false -trimpath -buildmode=c-shared -ldflags="${LDFLAGS}" \
      -o "${OUT_DIR}/libsenkocore.so" .
)
# the full distro needs modules the module files did not list. this writes what the build resolved back
if [[ "${SENKODROID_CORE_SYNC_MOD:-0}" == 1 ]]; then
  # the replace line names this machine's source directory, so it stays out of the repository file
  (cd "${ROOT}/xraycore" && cp "${WORK}/go.mod" go.mod && cp "${WORK}/go.sum" go.sum \
    && "${GO_BIN}" mod edit -dropreplace github.com/xtls/xray-core)
fi
rm -f "${OUT_DIR}/libsenkocore.h"
file "${OUT_DIR}/libsenkocore.so"
