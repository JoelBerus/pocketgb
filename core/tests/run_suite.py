#!/usr/bin/env python3
"""Ejecuta core/tests/suite.txt e imprime una tabla PASS/FAIL (docs/06-testing.md).

Sale con código 1 si falla algún caso `requerido` de un hito <= --hito.
Solo usa la biblioteca estándar (funciona igual en macOS y en Linux).
"""
import argparse
import glob
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools"))
import png2rgba  # noqa: E402  (tools/png2rgba.py, solo stdlib)


def hito_num(h):
    if not (h.startswith("M") and h[1:].isdigit()):
        raise ValueError(f"hito no válido: {h!r}")
    return int(h[1:])


def load_cases(path, roms, max_hito):
    cases, seen = [], set()
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("|")
            if len(parts) not in (6, 7):
                sys.exit(f"{path}:{n}: se esperaban 6 o 7 campos, hay {len(parts)}")
            hito, ruta, modo, modelo, frames, tipo = parts[:6]
            ref = os.path.join(roms, parts[6]) if len(parts) == 7 else None
            if modo == "acid" and not ref:
                sys.exit(f"{path}:{n}: el modo acid necesita la referencia (7.º campo)")
            if ref and not os.path.isfile(ref):
                sys.exit(f"{path}:{n}: no existe la referencia {parts[6]!r}")
            if tipo not in ("requerido", "known-fail", "info"):
                sys.exit(f"{path}:{n}: tipo no válido {tipo!r}")
            if hito_num(hito) > max_hito:
                continue
            if modo == "unit":
                files = ["unit"]
            else:
                files = sorted(glob.glob(os.path.join(roms, ruta)))
                if not files:
                    sys.exit(f"{path}:{n}: ninguna ROM coincide con {ruta!r}")
            for f in files:
                key = (f, modo, modelo)
                if key in seen:       # un comodín no repite un caso ya listado
                    continue
                seen.add(key)
                cases.append((hito, f, modo, modelo, int(frames), tipo, ref))
    return cases


def reference_rgba(ref, workdir):
    """Convierte la referencia PNG a RGBA crudo en workdir (una vez por archivo)."""
    out = os.path.join(workdir, os.path.basename(ref) + ".rgba")
    if not os.path.isfile(out) or os.path.getmtime(out) < os.path.getmtime(ref):
        w, h, rgba = png2rgba.read_png(ref)
        if (w, h) != (160, 144):
            sys.exit(f"{ref}: {w}x{h}, se esperaba 160x144")
        with open(out, "wb") as f:
            f.write(rgba)
    return out


def run_case(binary, case, timeout, workdir):
    hito, rom, modo, modelo, frames, tipo, ref = case
    if modo == "unit":
        cmd = [binary, "--unit"]
    else:
        cmd = [binary, rom, "--mode", modo, "--max-frames", str(frames)]
        if modelo != "-":
            cmd += ["--model", modelo]
        if ref:
            cmd += ["--expect", reference_rgba(ref, workdir)]
    t0 = time.monotonic()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        ok = p.returncode == 0
        out = (p.stdout + p.stderr).strip().splitlines()
        detail = out[-1] if modo == "unit" and out else (out[0] if out else "")
        if p.returncode not in (0, 1):
            detail = f"error {p.returncode}: {detail}"
    except subprocess.TimeoutExpired:
        ok, detail = False, f"timeout ({timeout} s)"
    return ok, detail, time.monotonic() - t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--roms", required=True)
    ap.add_argument("--suite", required=True)
    ap.add_argument("--hito", default="M1")
    ap.add_argument("--timeout", type=int, default=300)
    a = ap.parse_args()

    cases = load_cases(a.suite, a.roms, hito_num(a.hito))
    workdir = os.path.join(os.path.dirname(os.path.abspath(a.bin)), "refs")
    os.makedirs(workdir, exist_ok=True)
    blocking, rows = 0, []
    for case in cases:
        ok, detail, dt = run_case(a.bin, case, a.timeout, workdir)
        hito, rom, modo, _, _, tipo, _ = case
        name = "unit tests" if modo == "unit" else os.path.relpath(rom, a.roms)
        status = "PASS" if ok else "FAIL"
        if not ok and tipo == "requerido":
            blocking += 1
        rows.append((hito, status, tipo, name, f"{dt:5.1f}s", detail))

    w = max(len(r[3]) for r in rows) if rows else 10
    print(f"{'Hito':4}  {'Res.':4}  {'Tipo':10}  {'Caso':{w}}  {'Tiempo':>6}  Detalle")
    for hito, status, tipo, name, dt, detail in rows:
        print(f"{hito:4}  {status:4}  {tipo:10}  {name:{w}}  {dt:>6}  {detail}")
    n_pass = sum(r[1] == "PASS" for r in rows)
    n_req = sum(r[2] == "requerido" for r in rows)
    print(f"\n{n_pass}/{len(rows)} PASS · requeridos: {n_req - blocking}/{n_req} PASS · "
          f"HITO={a.hito}")
    if blocking:
        print(f"FALLO: {blocking} caso(s) requerido(s) en FAIL")
        return 1
    print("OK: todos los casos requeridos en PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
