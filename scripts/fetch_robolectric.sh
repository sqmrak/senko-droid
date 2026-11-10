#!/usr/bin/env bash
# the android framework jars robolectric runs the screen tests on, one for
# api 19 and one for api 28. fetched once into a cache directory beside the
# workspace and checked against the sha512 file maven central publishes next
# to each jar, so the tests never write into the home directory
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${SENKODROID_ROBOLECTRIC:-${ROOT}/../cache/robolectric}"
BASE="${SENKODROID_ROBOLECTRIC_URL:-https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented}"
VERSIONS=(4.4_r1-robolectric-r2-i4 9-robolectric-4913185-2-i4 14-robolectric-10818077-i4)
mkdir -p "${OUT}"

for version in "${VERSIONS[@]}"; do
  name="android-all-instrumented-${version}.jar"
  if [[ -s "${OUT}/${name}" ]]; then
    echo "cached ${OUT}/${name}"
    continue
  fi
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp}"' EXIT
  curl -fsSL --connect-timeout 15 --max-time 600 "${BASE}/${version}/${name}" -o "${tmp}/${name}"
  curl -fsSL --connect-timeout 15 --max-time 60 "${BASE}/${version}/${name}.sha512" -o "${tmp}/${name}.sha512"
  want="$(awk '{print $1; exit}' "${tmp}/${name}.sha512")"
  got="$(sha512sum "${tmp}/${name}" | awk '{print $1}')"
  if [[ -z "${want}" || "${want}" != "${got}" ]]; then
    echo "${name}: checksum mismatch, want ${want:-none}, got ${got}" >&2
    exit 1
  fi
  mv "${tmp}/${name}" "${OUT}/${name}"
  echo "fetched ${OUT}/${name}"
done
