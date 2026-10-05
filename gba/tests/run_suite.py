#!/usr/bin/env python3
"""Ejecuta gba/tests/suite.txt (docs/06-testing.md §GBA). Solo biblioteca estándar.

Formato: hito|ruta|modo|max_frames|tipo[|referencia]
  ruta: relativa a gba/tests/roms/, o "hb:NOMBRE" para las ROMs homebrew de gba/build/hb/
  modo: unit | sst (archivo .json.bin de SingleStepTests) | jsmolka | ref (PNG en gba/tests/ref/)
  tipo: requerido (bloquea) | known-fail | info
Sale con 1 si falla un caso requerido de un hito <= --hito.
"""
import argparse, glob, os, subprocess, sys, tempfile, time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools"))
import png2rgba  # noqa: E402


def hito_num(h):
    if not (h.startswith("G") and h[1:].isdigit()):
        raise ValueError(f"hito no válido: {h!r}")
    return int(h[1:])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--roms", required=True)
    ap.add_argument("--suite", required=True)
    ap.add_argument("--hito", default="G1")
    ap.add_argument("--sst-limit", type=int, default=0)
    ap.add_argument("--hb", default="build/hb")
    ap.add_argument("--refs", default="tests/ref")
    a = ap.parse_args()
    max_h = hito_num(a.hito)
    cases = []
    with open(a.suite, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("|")
            if len(p) not in (5, 6):
                sys.exit(f"{a.suite}:{n}: se esperaban 5 o 6 campos")
            hito, ruta, modo, frames, tipo = p[:5]
            ref = os.path.join(a.refs, p[5]) if len(p) == 6 else None
            if modo == "ref" and (not ref or not os.path.isfile(ref)):
                sys.exit(f"{a.suite}:{n}: el modo ref necesita una referencia existente")
            if tipo not in ("requerido", "known-fail", "info"):
                sys.exit(f"{a.suite}:{n}: tipo no válido {tipo!r}")
            if hito_num(hito) > max_h:
                continue
            if modo == "unit":
                files = ["unit"]
            elif ruta.startswith("hb:"):
                files = sorted(glob.glob(os.path.join(a.hb, ruta[3:])))
            else:
                files = sorted(glob.glob(os.path.join(a.roms, ruta)))
            if not files:
                sys.exit(f"{a.suite}:{n}: nada coincide con {ruta!r}")
            for fn in files:
                cases.append((hito, fn, modo, int(frames), tipo, ref))
    bad = 0
    t0 = time.time()
    tmp = tempfile.mkdtemp()
    for hito, fn, modo, frames, tipo, ref in cases:
        if modo == "unit":
            cmd = [a.bin, "--unit"]
        elif modo == "sst":
            cmd = [a.bin, "--sst", fn, "--show", "3"]
            if a.sst_limit:
                cmd += ["--limit", str(a.sst_limit)]
        elif modo == "ref":
            raw = os.path.join(tmp, os.path.basename(ref) + ".rgba")
            if not os.path.isfile(raw):
                w, h, rgba = png2rgba.read_png(ref)
                if (w, h) != (240, 160):
                    sys.exit(f"{ref}: {w}x{h}, se esperaba 240x160")
                open(raw, "wb").write(rgba)
            cmd = [a.bin, fn, "--mode", "ref", "--ref", raw, "--max-frames", str(frames)]
        else:
            cmd = [a.bin, fn, "--mode", modo, "--max-frames", str(frames)]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        ok = r.returncode == 0
        out = (r.stdout + r.stderr).strip().splitlines()
        status = "PASS" if ok else ("KNOWN" if tipo != "requerido" else "FAIL")
        name = "unit" if fn == "unit" else (os.path.basename(fn) if fn.startswith(a.hb) else os.path.relpath(fn, a.roms))
        print(f"{status:5} {hito} {name}: {out[-1] if out else ''}")
        if not ok:
            for l in out[:-1][:4]:
                print("       " + l)
            if tipo == "requerido":
                bad += 1
    print(f"\n{len(cases) - bad}/{len(cases)} sin fallos requeridos ({time.time() - t0:.1f} s)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
