#!/usr/bin/env python3
"""Turns the preview's raw 466x466 RGB dumps into PNGs with the round panel
drawn: pixels outside the visible circle are dimmed and tinted red (so
anything drawn there stands out), and the safe radius is marked.
Standard library only (zlib + struct)."""

import os
import struct
import sys
import zlib

W = H = 466
VISIBLE_R = 233
SAFE_R = 225


def png_bytes(rows):
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def convert(src, dst):
    data = open(src, "rb").read()
    if len(data) != W * H * 3:
        raise SystemExit(f"{src}: unexpected size {len(data)}")
    cx = cy = (W - 1) / 2.0
    rows = []
    for y in range(H):
        row = bytearray(data[y * W * 3:(y + 1) * W * 3])
        dy2 = (y - cy) ** 2
        for x in range(W):
            d2 = (x - cx) ** 2 + dy2
            i = x * 3
            if d2 > VISIBLE_R ** 2:
                r, g, b = row[i], row[i + 1], row[i + 2]
                row[i] = min(255, 60 + r // 2)
                row[i + 1] = g // 3
                row[i + 2] = b // 3
            elif (SAFE_R - 0.5) ** 2 <= d2 <= (SAFE_R + 0.5) ** 2 and (x + y) % 6 < 3:
                row[i], row[i + 1], row[i + 2] = 200, 0, 200
        rows.append(row)
    with open(dst, "wb") as f:
        f.write(png_bytes(rows))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "out"
    for name in sorted(os.listdir(out)):
        if name.endswith(".rgb"):
            src = os.path.join(out, name)
            convert(src, src[:-4] + ".png")
            os.remove(src)
            print("png", name[:-4])


if __name__ == "__main__":
    main()
