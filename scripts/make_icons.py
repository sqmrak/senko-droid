#!/usr/bin/env python3
"""draws the launcher and status icons with nothing but the standard library.
a fox head from a few polygons, supersampled so the edges stay smooth. rerun it
after changing the shapes, the pngs are checked in"""
import os
import struct
import sys
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

# shapes in a unit square, y grows down
HEAD = [(0.12, 0.10), (0.36, 0.30), (0.64, 0.30), (0.88, 0.10), (0.94, 0.52),
        (0.50, 0.92), (0.06, 0.52)]
CHEEKS = [(0.16, 0.52), (0.50, 0.58), (0.84, 0.52), (0.50, 0.88)]
NOSE = [(0.43, 0.76), (0.57, 0.76), (0.50, 0.86)]
EYES = [[(0.28, 0.46), (0.40, 0.50), (0.28, 0.54)], [(0.72, 0.46), (0.60, 0.50), (0.72, 0.54)]]


def inside(poly, x, y):
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def render(size, layers, samples=4):
    """layers: (polygon, rgba) painted in order. returns rows of rgba bytes"""
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sy in range(samples):
                for sx in range(samples):
                    x = (px + (sx + 0.5) / samples) / size
                    y = (py + (sy + 0.5) / samples) / size
                    color = (0, 0, 0, 0)
                    for poly, rgba in layers:
                        if poly == "disc":
                            if (x - 0.5) ** 2 + (y - 0.5) ** 2 <= 0.48 ** 2:
                                color = rgba
                        elif inside(poly, x, y):
                            color = rgba
                    a = color[3] / 255.0
                    acc[0] += color[0] * a
                    acc[1] += color[1] * a
                    acc[2] += color[2] * a
                    acc[3] += a
            n = samples * samples
            alpha = acc[3] / n
            if alpha > 0:
                row += bytes((int(acc[0] / acc[3]), int(acc[1] / acc[3]), int(acc[2] / acc[3]),
                              int(alpha * 255)))
            else:
                row += bytes((0, 0, 0, 0))
        rows.append(bytes(row))
    return rows


def png(path, size, rows):
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


ORANGE = (240, 132, 40, 255)
CREAM = (255, 240, 220, 255)
DARK = (50, 30, 20, 255)
SKY = (36, 54, 84, 255)

LAUNCHER = [("disc", SKY), (HEAD, ORANGE), (CHEEKS, CREAM), (NOSE, DARK), (EYES[0], DARK), (EYES[1], DARK)]
STATUS = [(HEAD, (255, 255, 255, 255))]

for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    png(os.path.join(ROOT, "mipmap-" + density, "ic_launcher.png"), size, render(size, LAUNCHER))
for density, size in (("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72)):
    png(os.path.join(ROOT, "drawable-" + density, "ic_stat.png"), size, render(size, STATUS))
print("icons written", file=sys.stderr)
