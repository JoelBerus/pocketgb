#!/usr/bin/env python3
"""Capturas de la landing (site/) y comprobaciones de la página.

Convierte las capturas reales del catálogo de iOS (tools/ios-screenshots.sh) y de Android
(tools/android-screenshots.sh) en WebP con nombres estables para site/assets/shots/:
rota las horizontales de iOS (el simulador las guarda giradas), redimensiona y controla el peso.

Uso:
  tools/landing-shots.py --ios build/landing-shots/ios --android build/landing-shots/android [--og]
  tools/landing-shots.py --check     # sin Pillow: referencias, claves i18n ES/EN y pesos (lo usa el CI)

Las capturas solo salen de ROMs de prueba libres (dmg-acid2, cgb-acid2, jsmolka) y de los
catálogos de demostración; nunca de juegos comerciales (docs/08-roms-legal.md).
"""
from __future__ import annotations

import argparse
import html.parser
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
SHOTS = SITE / "assets" / "shots"

# (slug de destino, plataforma, id del catálogo, orientación, temas)
MANIFEST = [
    ("home", "ios", "n4-home", "portrait", ("light", "dark")),
    ("library", "ios", "n5-library", "portrait", ("light", "dark")),
    ("details", "ios", "game-details", "portrait", ("light", "dark")),
    ("gameplay", "ios", "gameplay-dpad-up", "portrait", ("dark",)),
    ("gameplay-landscape", "ios", "gameplay-landscape", "landscape", ("dark",)),
    ("gba-details", "ios", "game-details-gba", "portrait", ("dark",)),
    ("controls-editor", "ios", "customize-controls-portrait", "portrait", ("dark",)),
    ("link", "ios", "gameplay-link-portrait", "portrait", ("dark",)),
    ("moments", "ios", "n6-moments-pause", "portrait", ("light", "dark")),
    ("save-travel", "ios", "n7-detail-save", "portrait", ("light", "dark")),
    ("guide", "ios", "n9-guide", "portrait", ("light", "dark")),
    ("library", "android", "n5-library", "portrait", ("light", "dark")),
    ("details", "android", "n5-details-imported", "portrait", ("light", "dark")),
    ("gameplay", "android", "n8-gameplay", "portrait", ("dark",)),
    ("gameplay-landscape", "android", "n8-gameplay-landscape", "landscape", ("dark",)),
    ("moments", "android", "n6-moments", "portrait", ("light", "dark")),
    ("controls", "android", "settings-controls", "portrait", ("light", "dark")),
    ("controller", "android", "settings-controller-mapping", "portrait", ("light", "dark")),
    ("guide", "android", "n9-guide", "portrait", ("light", "dark")),
]

PORTRAIT_WIDTH = 600
LANDSCAPE_WIDTH = 1200
QUALITY = 80
MAX_FILE_KB = 300


def build(args: argparse.Namespace) -> int:
    from PIL import Image  # solo hace falta para convertir

    rotation = Image.Transpose.ROTATE_270 if args.ios_landscape_rotation == "cw" else Image.Transpose.ROTATE_90
    sources = {"ios": Path(args.ios), "android": Path(args.android)}
    SHOTS.mkdir(parents=True, exist_ok=True)
    missing, total = [], 0
    rows = []
    for slug, platform, shot_id, orient, themes in MANIFEST:
        for theme in themes:
            src = sources[platform] / f"{shot_id}-{orient}-{theme}.png"
            if not src.is_file():
                missing.append(str(src))
                continue
            img = Image.open(src).convert("RGB")
            if orient == "landscape" and img.height > img.width:
                img = img.transpose(rotation)
            width = LANDSCAPE_WIDTH if orient == "landscape" else PORTRAIT_WIDTH
            height = round(img.height * width / img.width)
            img = img.resize((width, height), Image.Resampling.LANCZOS)
            suffix = f"-{theme}" if len(themes) > 1 else f"-{themes[0]}"
            out = SHOTS / f"{platform}-{slug}{suffix}.webp"
            img.save(out, "WEBP", quality=QUALITY, method=6)
            kb = out.stat().st_size / 1024
            total += kb
            rows.append((out.name, f"{width}x{height}", kb))
    for name, dims, kb in rows:
        print(f"{name:42s} {dims:>10s} {kb:7.1f} KB")
    print(f"{'Total':42s} {'':>10s} {total:7.1f} KB ({len(rows)} archivos)")
    if args.og:
        make_og()
    if missing:
        print("Faltan capturas:\n  " + "\n  ".join(missing), file=sys.stderr)
        return 1
    if total > args.budget_kb:
        print(f"Se pasa del presupuesto: {total:.0f} KB > {args.budget_kb} KB", file=sys.stderr)
        return 1
    return 0


def make_og() -> None:
    """Imagen para enlaces compartidos (1200x630): icono, nombre, lema y una captura real."""
    from PIL import Image, ImageDraw, ImageFont

    W, H = 1200, 630
    canvas = Image.new("RGB", (W, H), "#0B0D11")
    draw = ImageDraw.Draw(canvas)
    icon = Image.open(ROOT / "ios/PocketGB/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-Default.png").convert("RGBA")
    icon = icon.resize((132, 132), Image.Resampling.LANCZOS)
    mask = Image.new("L", icon.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 131, 131), radius=30, fill=255)
    canvas.paste(icon, (80, 96), mask)

    def font(size: int, bold: bool) -> ImageFont.FreeTypeFont:
        for path, index in (("/System/Library/Fonts/SFNS.ttf", 0), ("/System/Library/Fonts/Helvetica.ttc", 1 if bold else 0)):
            try:
                f = ImageFont.truetype(path, size, index=index)
                if path.endswith("SFNS.ttf"):
                    try:
                        f.set_variation_by_name("Bold" if bold else "Regular")
                    except Exception:
                        pass
                return f
            except OSError:
                continue
        return ImageFont.load_default()

    draw.text((80, 270), "PocketGB", font=font(92, True), fill="#E8EBF0")
    draw.text((80, 390), "Tus cartuchos de Game Boy,", font=font(40, False), fill="#9299A7")
    draw.text((80, 440), "en tu bolsillo.", font=font(40, False), fill="#9299A7")
    draw.text((80, 530), "iPhone · Android · sin red ni anuncios", font=font(26, False), fill="#7FA3FF")

    shot = SHOTS / "ios-gameplay-dark.webp"
    if shot.is_file():
        phone = Image.open(shot).convert("RGB")
        ph_w = 300
        ph_h = round(phone.height * ph_w / phone.width)
        phone = phone.resize((ph_w, ph_h), Image.Resampling.LANCZOS)
        bezel = 12
        frame = Image.new("RGBA", (ph_w + 2 * bezel, ph_h + 2 * bezel), (0, 0, 0, 0))
        ImageDraw.Draw(frame).rounded_rectangle((0, 0, frame.width - 1, frame.height - 1), radius=54, fill="#101217", outline="#2A2E37", width=2)
        smask = Image.new("L", phone.size, 0)
        ImageDraw.Draw(smask).rounded_rectangle((0, 0, ph_w - 1, ph_h - 1), radius=42, fill=255)
        frame.paste(phone, (bezel, bezel), smask)
        canvas.paste(frame, (W - frame.width - 110, 60), frame)
    out = SITE / "assets" / "og.jpg"
    canvas.save(out, "JPEG", quality=82, optimize=True, progressive=True)
    print(f"og.jpg: {out.stat().st_size / 1024:.1f} KB")


class _Refs(html.parser.HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.paths: list[str] = []
        self.keys: set[str] = set()

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        for name in ("src", "href"):
            if a.get(name):
                self.paths.append(a[name])
        if a.get("srcset"):
            self.paths += [part.strip().split(" ")[0] for part in a["srcset"].split(",")]
        if a.get("data-i18n"):
            self.keys.add(a["data-i18n"])
        if a.get("data-i18n-attr"):
            for pair in a["data-i18n-attr"].split(";"):
                if ":" in pair:
                    self.keys.add(pair.split(":", 1)[1].strip())


def check() -> int:
    problems: list[str] = []
    index = SITE / "index.html"
    parser = _Refs()
    parser.feed(index.read_text(encoding="utf-8"))
    for ref in parser.paths:
        if re.match(r"^(https?:|mailto:|#|\?|data:)", ref) or ref == "./":
            continue
        target = SITE / ref.split("#", 1)[0].split("?", 1)[0]
        if not target.exists():
            problems.append(f"Referencia rota en index.html: {ref}")

    en_js = (SITE / "assets" / "i18n" / "en.js").read_text(encoding="utf-8")
    en_keys = set(re.findall(r'^\s*"([^"]+)"\s*:', en_js, flags=re.M))
    js_keys = set(re.findall(r'ui\("([^"]+)"', (SITE / "assets" / "js" / "landing.js").read_text(encoding="utf-8")))
    for key in sorted(parser.keys - en_keys):
        problems.append(f"Falta la traducción inglesa de «{key}»")
    for key in sorted(js_keys - en_keys):
        problems.append(f"Falta la traducción inglesa de «{key}» (landing.js)")
    for key in sorted(en_keys - parser.keys - js_keys):
        problems.append(f"Clave inglesa sin uso: «{key}»")

    for f in sorted(SITE.rglob("*")):
        if not f.is_file():
            continue
        kb = f.stat().st_size / 1024
        if f.parent == SHOTS and f.suffix.lower() != ".webp":
            problems.append(f"En shots/ solo van WebP: {f.relative_to(SITE)}")
        if kb > MAX_FILE_KB:
            problems.append(f"Archivo de más de {MAX_FILE_KB} KB: {f.relative_to(SITE)} ({kb:.0f} KB)")
        if f.suffix.lower() in {".gb", ".gbc", ".gba", ".sav", ".pgbm", ".zip", ".bin"}:
            problems.append(f"Archivo prohibido en site/: {f.relative_to(SITE)}")
        if f.suffix.lower() in {".html", ".css", ".js", ".md"}:
            text = f.read_text(encoding="utf-8")
            if "—" in text or "–" in text:
                problems.append(f"Raya o semirraya en {f.relative_to(SITE)} (usar punto, coma o guion)")

    total = sum(f.stat().st_size for f in SITE.rglob("*") if f.is_file()) / 1024
    print(f"site/: {total:.0f} KB · {len(parser.keys)} claves i18n · {len(parser.paths)} referencias")
    for p in problems:
        print("ERROR:", p, file=sys.stderr)
    return 1 if problems else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ios", default="build/landing-shots/ios")
    ap.add_argument("--android", default="build/landing-shots/android")
    ap.add_argument("--ios-landscape-rotation", choices=("cw", "ccw"), default="ccw",
                    help="giro de las capturas horizontales de iOS (cw = 90° horario)")
    ap.add_argument("--budget-kb", type=int, default=2600)
    ap.add_argument("--og", action="store_true", help="genera también site/assets/og.jpg")
    ap.add_argument("--check", action="store_true", help="solo comprueba site/ (no necesita Pillow)")
    args = ap.parse_args()
    return check() if args.check else build(args)


if __name__ == "__main__":
    sys.exit(main())
