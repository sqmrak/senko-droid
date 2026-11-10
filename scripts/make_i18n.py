#!/usr/bin/env python3
"""turns the translation tables of the ios app (app/localization.m) into the
assets/i18n.tsv the android app reads. the english text is the key, as on ios.
usage: make_i18n.py <localization.m> <i18n.tsv> <errors.tsv>"""
import re
import sys

STRING = r'@"((?:[^"\\]|\\.)*)"'
RU = re.compile(r'SenkoAddTranslation\(\s*' + STRING + r'\s*,\s*' + STRING + r'\s*\)\s*;')
ROW = re.compile(r'\{\s*' + STRING + r'\s*,\s*' + STRING + r'\s*,\s*' + STRING + r'\s*\}')
ZH = re.compile(r'SenkoAddChineseTranslation\(\s*' + STRING + r'\s*,\s*' + STRING + r'\s*\)\s*;')


def unescape(text):
    out, i = [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            n = text[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "'": "'"}.get(n, n))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def java_format(text):
    """objective c specifiers to the ones java's String.format takes"""
    text = re.sub(r"%(\d+\$)?@", lambda m: "%" + (m.group(1) or "") + "s", text)
    text = re.sub(r"%(\d+\$)?l{1,2}[du]", lambda m: "%" + (m.group(1) or "") + "d", text)
    text = re.sub(r"%(\d+\$)?[ul]", lambda m: m.group(0), text)
    return text


def escape(text):
    return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def main():
    source = open(sys.argv[1], encoding="utf-8").read()
    table = {}
    for english, russian in RU.findall(source):
        table.setdefault(java_format(unescape(english)), ["", ""])[0] = java_format(unescape(russian))
    for english, chinese in ZH.findall(source):
        table.setdefault(java_format(unescape(english)), ["", ""])[1] = java_format(unescape(chinese))
    with open(sys.argv[2], "w", encoding="utf-8") as out:
        for english in sorted(table):
            ru, zh = table[english]
            out.write("%s\t%s\t%s\n" % (escape(english), escape(ru), escape(zh)))
    # the three column table of the engine's own error sentences: english, russian, chinese
    rows = {}
    for english, russian, chinese in ROW.findall(source):
        rows[java_format(unescape(english))] = (java_format(unescape(russian)), java_format(unescape(chinese)))
    with open(sys.argv[3], "w", encoding="utf-8") as out:
        for english in sorted(rows):
            ru, zh = rows[english]
            out.write("%s\t%s\t%s\n" % (escape(english), escape(ru), escape(zh)))
    print("%d entries, %d error sentences" % (len(table), len(rows)), file=sys.stderr)


main()
