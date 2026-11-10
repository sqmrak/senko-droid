#!/usr/bin/env python3
"""reads the palettes of the ios themes (app/theme/theme_*.m) into the
assets/themes.tsv the android app reads. one row per theme:
id, name, blurb, flags, card radius, group, aliases, light colors, dark colors.
colors are 16 comma separated #aarrggbb values in the slot order of ui_theme.h.
usage: make_themes.py <ios app/theme dir> <out.tsv>"""
import os
import re
import sys

SLOTS = ["kBG", "kBGBot", "kFelt", "kConnOn", "kConnOnLo", "kIdleGrey", "kIdleGreyLo", "kInk",
         "kInkMuted", "kAccentBlue", "kAccentBlueLo", "kChromeHi", "kChromeLo", "kCellHi",
         "kCellLo", "kWell"]
CAPS = {"Flat": "flat", "Ios16": "ios16", "Boykisser": "boykisser", "Miside": "miside",
        "Aero": "aero", "LightOnly": "lightonly", "DarkOnly": "darkonly", "Ios26": "ios26",
        "Material": "material"}
SET = re.compile(r"SenkoThemeSetSlot\(&(k\w+),\s*(?:SenkoThemeC\(([^)]*)\)|SenkoThemeCA\(([^)]*)\)|Pick\(([^)]*)\))\s*\)")
FUNC = re.compile(r"static void (Fill\w*)\(void\)\s*\{(.*?)\n\}", re.S)
DEF = re.compile(r"const SenkoThemeDef (kSenkoTheme\w+)\s*=\s*\{(.*?)\};", re.S)


def hexcolor(r, g, b, a=1.0):
    return "#%02x%02x%02x%02x" % tuple(int(round(max(0.0, min(1.0, v)) * 255)) for v in (a, r, g, b))


def palette(body):
    colors = {}
    for match in SET.finditer(body):
        slot = match.group(1)
        if match.group(2):
            r, g, b = [float(x) for x in match.group(2).split(",")]
            colors[slot] = hexcolor(r, g, b)
        elif match.group(3):
            r, g, b, a = [float(x) for x in match.group(3).split(",")]
            colors[slot] = hexcolor(r, g, b, a)
        else:
            colors[slot] = None  # a pick from the ios 6 accent table, filled by the app
    return colors


def row_of(colors):
    return ",".join(colors.get(slot) or "#00000000" for slot in SLOTS)


def main():
    folder, out = sys.argv[1], sys.argv[2]
    rows = []
    for name in sorted(os.listdir(folder)):
        if not re.match(r"theme_(aero|boykisser|ios16|ios6|ios7|miside)\.m$", name):
            continue
        source = open(os.path.join(folder, name), encoding="utf-8").read()
        funcs = {m.group(1): palette(m.group(2)) for m in FUNC.finditer(source)}
        definition = DEF.search(source)
        strings = re.findall(r'"([^"]*)"', definition.group(2))
        tid, title, blurb = strings[0], strings[1], strings[2]
        aliases = strings[3] if len(strings) > 3 else ""
        group = re.search(r"SENKO_THEME_GROUP_(\w+)", definition.group(2)).group(1).lower()
        caps = [CAPS[c] for c in re.findall(r"SenkoThemeCap(\w+)", definition.group(2)) if c in CAPS]
        radius = re.search(r"([\d.]+)f", definition.group(2)).group(1)
        light = funcs.get("FillLight") or funcs.get("Fill")
        dark = funcs.get("FillDark") or funcs.get("Fill")
        rows.append([tid, title, blurb, ",".join(caps), radius, group, aliases, row_of(light), row_of(dark)])
    order = ["senko-ios6", "senko-ios7", "senko-ios16", "senko-boykisser",
             "senko-miside", "senko-frutigeraero"]
    rows.sort(key=lambda r: order.index(r[0]) if r[0] in order else 99)
    with open(out, "w", encoding="utf-8") as f:
        for row in rows:
            f.write("\t".join(row) + "\n")
    print("%d themes" % len(rows), file=sys.stderr)


main()
