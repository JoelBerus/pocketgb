#!/usr/bin/env python3
"""Copia las secciones de la guía del iPhone (docs/guia/*.md) al bundle de la app (N9).

La fuente es docs/guia; ios/PocketGB/Resources/Guide/guia-<id>.md es una copia derivada que la app
renderiza sin red (Ajustes › Guía). Cambios al copiar:
- se quita el título `# …` (la app usa el de GuideLibrary.sections);
- los enlaces a otra sección del iPhone pasan a `guia:<id>` (la app los abre dentro de la guía);
- los enlaces a guías de Android o a documentos del repo se quitan: entre paréntesis desaparece el
  paréntesis entero; una frase con un enlace a `../` desaparece; el resto queda como texto.
Uso: tools/ios-guide-sync.py          escribe las copias
     tools/ios-guide-sync.py --check  falla si alguna copia no está al día (lo usa tools/ios-screenshots.sh)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "guia"
DST = ROOT / "ios" / "PocketGB" / "Resources" / "Guide"

# id de la sección en la app (GuideLibrary.sections) -> archivo de docs/guia. Mismo orden que la app.
SECTIONS = [
    ("carpetas", "carpetas.md"),
    ("biblioteca", "biblioteca.md"),
    ("categorias", "categorias.md"),
    ("portadas", "portadas.md"),
    ("continuar", "continuar.md"),
    ("momentos", "momentos.md"),
    ("viajar", "viajar.md"),
    ("controles", "controles.md"),
    ("gba", "gba.md"),
]
BY_FILE = {f: i for i, f in SECTIONS}
LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]*)\)")
DROP = "\x00"


def convert(text: str, source: str) -> str:
    lines = text.split("\n")
    if lines and lines[0].startswith("# "):
        lines = lines[1:]
        while lines and not lines[0].strip():
            lines = lines[1:]
    out = [f"<!-- Generado por tools/ios-guide-sync.py desde docs/guia/{source}: no editar aquí. -->"]
    in_code = False
    for line in lines:
        if line.startswith("```"):
            in_code = not in_code
            out.append(line)
            continue
        if in_code:
            out.append(line)
            continue
        # Frases con un enlace a un documento del repo (`../`): fuera.
        line = re.sub(r"(?:(?<=\. )|^)[^.]*?\[[^\]]*\]\(\.\./[^)]*\)[^.]*\.\s?", "", line)

        def link(m: re.Match) -> str:
            label, target = m.group(1), m.group(2)
            if target in BY_FILE:
                return f"[{label}](guia:{BY_FILE[target]})"
            return DROP + label + DROP

        line = LINK.sub(link, line)
        # Un paréntesis que contiene un enlace que no es del iPhone desaparece entero.
        line = re.sub(r"\s?\([^()]*" + DROP + r"[^()]*\)", "", line)
        line = line.replace(DROP, "")
        line = line.replace(" .", ".").replace(" ,", ",").rstrip()
        out.append(line)
    return "\n".join(out).rstrip("\n") + "\n"


def main() -> int:
    check = "--check" in sys.argv[1:]
    stale = []
    DST.mkdir(parents=True, exist_ok=True)
    wanted = set()
    for sid, source in SECTIONS:
        result = convert((SRC / source).read_text(encoding="utf-8"), source)
        target = DST / f"guia-{sid}.md"
        wanted.add(target.name)
        current = target.read_text(encoding="utf-8") if target.exists() else None
        if current != result:
            stale.append(target.name)
            if not check:
                target.write_text(result, encoding="utf-8")
    extra = sorted(p.name for p in DST.glob("guia-*.md") if p.name not in wanted)
    if check:
        if stale or extra:
            print("La guía del bundle no está al día con docs/guia:", ", ".join(stale + extra))
            print("Ejecuta tools/ios-guide-sync.py y vuelve a compilar.")
            return 1
        print(f"Guía del bundle al día ({len(SECTIONS)} secciones).")
        return 0
    for name in extra:
        (DST / name).unlink()
    print(f"Guía copiada: {len(SECTIONS)} secciones ({len(stale)} cambiadas, {len(extra)} borradas).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
