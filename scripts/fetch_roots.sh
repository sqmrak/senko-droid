#!/usr/bin/env bash
# the trust roots the engine verifies servers against, fetched at build time so
# the app stays offline at run time. mozilla's bundle through curl.se, checked
# against the sha256 file published beside it, and kept when the host is down
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${SENKODROID_ASSETS:-${ROOT}/build/assets}"
URL="${SENKODROID_ROOTS_URL:-https://curl.se/ca/cacert.pem}"
mkdir -p "${OUT}"
DEST="${OUT}/cacert.pem"

if [[ -s "${DEST}" && "${SENKODROID_REFRESH_ROOTS:-0}" != "1" ]]; then
  echo "cached ${DEST}"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
if curl -fsSL --connect-timeout 15 --max-time 60 "${URL}" -o "${TMP}/cacert.pem" &&
   curl -fsSL --connect-timeout 15 --max-time 60 "${URL}.sha256" -o "${TMP}/cacert.pem.sha256"; then
  want="$(awk '{print $1; exit}' "${TMP}/cacert.pem.sha256")"
  got="$(sha256sum "${TMP}/cacert.pem" | awk '{print $1}')"
  if [[ -z "${want}" || "${want}" != "${got}" ]]; then
    echo "roots checksum mismatch: want ${want:-none}, got ${got}" >&2
    exit 1
  fi
  count="$(grep -c 'BEGIN CERTIFICATE' "${TMP}/cacert.pem")"
  if (( count < 50 )); then
    echo "only ${count} certificates in the roots bundle" >&2
    exit 1
  fi
  mv "${TMP}/cacert.pem" "${DEST}"
  echo "roots ready: ${count} certificate(s)"
  exit 0
fi

if [[ -s "${DEST}" ]]; then
  echo "cached ${DEST} (fetch failed)"
  exit 0
fi
echo "missing ${DEST} (fetch failed, no cache)" >&2
exit 1
