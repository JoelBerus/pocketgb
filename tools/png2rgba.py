#!/usr/bin/env python3
"""Convierte PNG <-> RGBA crudo (docs/06-testing.md). Solo biblioteca estándar.

  png2rgba.py entrada.png salida.rgba          PNG -> RGBA8888 (R,G,B,A por píxel)
  png2rgba.py --reverse entrada.rgba salida.png [--size 160x144]

Soporta PNG no entrelazados de 8 bits (gris, RGB, paleta, gris+alfa, RGBA)
y paleta/gris de 1, 2 o 4 bits: suficiente para las referencias de las
pruebas (dmg-acid2, cgb-acid2, Mealybug).
"""
import argparse
import struct
import sys
import zlib

SIG = b"\x89PNG\r\n\x1a\n"


def paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def read_png(path):
    data = open(path, "rb").read()
    if not data.startswith(SIG):
        sys.exit(f"{path}: no es un PNG")
    pos, idat, plte, trns = 8, b"", None, None
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"PLTE":
            plte = chunk
        elif kind == b"tRNS":
            trns = chunk
        elif kind == b"IDAT":
            idat += chunk
        pos += 12 + n
    if interlace:
        sys.exit(f"{path}: PNG entrelazado no soportado")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    if depth != 8 and ctype not in (0, 3):
        sys.exit(f"{path}: profundidad {depth} no soportada para tipo {ctype}")
    bits_pp = channels * depth
    stride = (w * bits_pp + 7) // 8
    bpp = max(1, bits_pp // 8)
    raw = zlib.decompress(idat)
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 0xFF
            elif f == 2:
                line[x] = (line[x] + b) & 0xFF
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 0xFF
            elif f == 4:
                line[x] = (line[x] + paeth(a, b, c)) & 0xFF
        rows.append(line)
        prev = line
    out = bytearray()
    for line in rows:
        for x in range(w):
            if depth == 8:
                px = line[x * channels:(x + 1) * channels]
                v = px[0]
            else:
                bitpos = x * depth
                v = (line[bitpos // 8] >> (8 - depth - bitpos % 8)) & ((1 << depth) - 1)
            if ctype == 3:
                r, g, b = plte[v * 3:v * 3 + 3]
                a = trns[v] if trns and v < len(trns) else 255
                out += bytes((r, g, b, a))
            elif ctype == 0:
                g = v * 255 // ((1 << depth) - 1)
                out += bytes((g, g, g, 255))
            elif ctype == 4:
                out += bytes((px[0], px[0], px[0], px[1]))
            elif ctype == 2:
                out += bytes((px[0], px[1], px[2], 255))
            else:
                out += bytes(px)
    return w, h, bytes(out)


def write_png(path, w, h, rgba):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    raw = b"".join(b"\x00" + rgba[y * w * 4:(y + 1) * w * 4] for y in range(h))
    png = SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    open(path, "wb").write(png)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--reverse", action="store_true", help="RGBA crudo -> PNG")
    ap.add_argument("--size", default="160x144")
    a = ap.parse_args()
    if a.reverse:
        w, h = (int(v) for v in a.size.split("x"))
        rgba = open(a.src, "rb").read()
        if len(rgba) != w * h * 4:
            sys.exit(f"{a.src}: {len(rgba)} bytes, se esperaban {w * h * 4}")
        write_png(a.dst, w, h, rgba)
    else:
        w, h, rgba = read_png(a.src)
        open(a.dst, "wb").write(rgba)
        print(f"{a.src}: {w}x{h} -> {a.dst}")


if __name__ == "__main__":
    main()
