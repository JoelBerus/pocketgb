#!/usr/bin/env python3
"""Compara la PPU de PocketGB con el oráculo mGBA (dev-only), frame a frame.

  tools/gba-compare.py ROM FRAMES [--out DIR] [--gbatest BIN] [--oracle BIN]

FRAMES: lista creciente "1,60,120". Por cada frame imprime los píxeles que
difieren y, con --out, escribe PNG del nuestro, del oráculo y de la diferencia.
Sale con 1 si algún frame difiere. Solo biblioteca estándar.
"""
import argparse, os, struct, subprocess, sys, tempfile, zlib

W, H = 240, 160


def write_png(path, rgba, w=W, h=H):
    raw = b"".join(b"\x00" + rgba[y * w * 4:(y + 1) * w * 4] for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser()
    ap.add_argument("rom")
    ap.add_argument("frames")
    ap.add_argument("--out")
    ap.add_argument("--gbatest", default=os.path.join(root, "gba/build/gbatest"))
    ap.add_argument("--oracle", default=os.path.join(root, "gba/build/mgba-shot"))
    a = ap.parse_args()
    frames = [int(x) for x in a.frames.split(",")]
    with tempfile.TemporaryDirectory() as t:
        ours, ref = os.path.join(t, "ours.rgba"), os.path.join(t, "ref.rgba")
        subprocess.run([a.gbatest, a.rom, "--dump", ours, "--frames", a.frames], check=True)
        subprocess.run([a.oracle, a.rom, ref] + [str(f) for f in frames], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        o, r = open(ours, "rb").read(), open(ref, "rb").read()
    n = W * H * 4
    bad = 0
    name = os.path.splitext(os.path.basename(a.rom))[0]
    for i, f in enumerate(frames):
        fo, fr = o[i * n:(i + 1) * n], r[i * n:(i + 1) * n]
        diff = sum(1 for p in range(0, n, 4) if fo[p:p + 3] != fr[p:p + 3])
        uniform = len(set(fo[p:p + 3] for p in range(0, n, 4 * 97))) == 1
        print(f"{'OK  ' if diff == 0 else 'DIFF'} {name} frame {f}: {diff} píxeles distintos"
              + ("  (AVISO: frame uniforme, la prueba no dice nada)" if uniform else ""))
        bad += diff != 0 or uniform
        if a.out:
            os.makedirs(a.out, exist_ok=True)
            write_png(os.path.join(a.out, f"{name}-{f}-ours.png"), fo)
            write_png(os.path.join(a.out, f"{name}-{f}-mgba.png"), fr)
            d = bytearray(n)
            for p in range(0, n, 4):
                d[p:p + 4] = b"\xff\x00\x00\xff" if fo[p:p + 3] != fr[p:p + 3] else bytes((fo[p] // 4, fo[p + 1] // 4, fo[p + 2] // 4, 255))
            write_png(os.path.join(a.out, f"{name}-{f}-diff.png"), bytes(d))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
