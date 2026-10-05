#!/usr/bin/env python3
"""Ejecuta gba/tests/suite.txt (docs/06-testing.md §GBA). Solo biblioteca estándar.

Formato: hito|ruta|modo|max_frames|tipo
  modo: unit | sst (archivo .json.bin de SingleStepTests) | jsmolka
  tipo: requerido (bloquea) | known-fail | info
Sale con 1 si falla un caso requerido de un hito <= --hito.
"""
import argparse, glob, os, subprocess, sys, time


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
    a = ap.parse_args()
    max_h = hito_num(a.hito)
    cases = []
    with open(a.suite, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("|")
            if len(p) != 5:
                sys.exit(f"{a.suite}:{n}: se esperaban 5 campos")
            hito, ruta, modo, frames, tipo = p
            if tipo not in ("requerido", "known-fail", "info"):
                sys.exit(f"{a.suite}:{n}: tipo no válido {tipo!r}")
            if hito_num(hito) > max_h:
                continue
            files = ["unit"] if modo == "unit" else sorted(glob.glob(os.path.join(a.roms, ruta)))
            if not files:
                sys.exit(f"{a.suite}:{n}: nada coincide con {ruta!r}")
            for fn in files:
                cases.append((hito, fn, modo, int(frames), tipo))
    bad = 0
    t0 = time.time()
    for hito, fn, modo, frames, tipo in cases:
        if modo == "unit":
            cmd = [a.bin, "--unit"]
        elif modo == "sst":
            cmd = [a.bin, "--sst", fn, "--show", "3"]
            if a.sst_limit:
                cmd += ["--limit", str(a.sst_limit)]
        else:
            cmd = [a.bin, fn, "--mode", modo, "--max-frames", str(frames)]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        ok = r.returncode == 0
        out = (r.stdout + r.stderr).strip().splitlines()
        status = "PASS" if ok else ("KNOWN" if tipo != "requerido" else "FAIL")
        name = os.path.relpath(fn, a.roms) if fn != "unit" else "unit"
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
