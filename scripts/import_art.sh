#!/usr/bin/env bash
# copies the raster art of the ios app (glyphs, flags, backgrounds) into the
# android resources, under names android accepts. glyphs keep their three
# scales: the plain file is mdpi, @2x xhdpi, @3x xxhdpi.
# usage: import_art.sh <ios app/icons dir>
set -euo pipefail

SRC="${1:?usage: import_art.sh <ios app/icons dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES="${ROOT}/app/src/main/res"

[[ -d "${SRC}/glyphs" && -d "${SRC}/flags" ]] || { echo "no glyphs or flags in ${SRC}" >&2; exit 1; }

mkdir -p "${RES}/drawable-mdpi" "${RES}/drawable-xhdpi" "${RES}/drawable-xxhdpi" "${RES}/drawable-nodpi"

for file in "${SRC}"/glyphs/glyph-*.png; do
  name="$(basename "${file}" .png)"
  case "${name}" in
    *-src) continue ;;
    *@2x) dir=xhdpi; base="${name%@2x}" ;;
    *@3x) dir=xxhdpi; base="${name%@3x}" ;;
    *) dir=mdpi; base="${name}" ;;
  esac
  cp "${file}" "${RES}/drawable-${dir}/$(echo "${base}" | tr '-' '_').png"
done

# flags are one size, scaled by the view
for file in "${SRC}"/flags/flag-*.png; do
  name="$(basename "${file}" .png)"
  cp "${file}" "${RES}/drawable-nodpi/$(echo "${name}" | tr '-' '_').png"
done

for name in server-placeholder icon-list icon-ping icon-refresh icon-settings icon-terminal icon-trash \
            boykisser senko-fall about-bg; do
  [[ -f "${SRC}/${name}.png" ]] && cp "${SRC}/${name}.png" "${RES}/drawable-nodpi/$(echo "${name}" | tr '-' '_').png"
done
for name in miside-bg frutiger-bg frutiger-bg-dark ios26-bg-light ios26-bg-dark; do
  [[ -f "${SRC}/${name}.jpg" ]] && cp "${SRC}/${name}.jpg" "${RES}/drawable-nodpi/$(echo "${name}" | tr '-' '_').jpg"
done
echo "art imported into ${RES}"
