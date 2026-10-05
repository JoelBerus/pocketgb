#!/usr/bin/env bash
# Descarga las pruebas libres del núcleo GBA en gba/tests/roms/ (ignorado por git).
# - jsmolka/gba-tests (MIT): ROMs homebrew de CPU, memoria, BIOS, PPU y saves.
# - SingleStepTests/ARM7TDMI (MIT): ~1 GB de casos de una instrucción (.json.bin).
# Cada repo se fija a un commit: git verifica cada objeto por su hash, así que
# un contenido distinto no se puede colar con el mismo commit.
# Nunca descarga ROMs comerciales ni la BIOS de Nintendo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/gba/tests/roms"
JSMOLKA_SHA="a7113b67e63f83a9b321696ddd7042ccfad6c881"
SST_SHA="e3097d88d428752736b949d94c094d5da50f0db6"
STAMP="$JSMOLKA_SHA $SST_SHA"
if [ -f "$DEST/.version" ] && [ "$(cat "$DEST/.version")" = "$STAMP" ]; then
  echo "Pruebas GBA ya presentes en $DEST"; exit 0
fi
fetch() { # repo dir sha
  rm -rf "$2"; mkdir -p "$2"
  git -C "$2" init -q
  git -C "$2" remote add origin "https://github.com/$1.git"
  for i in 1 2 3 4; do
    git -C "$2" fetch -q --depth 1 origin "$3" && break
    [ "$i" = 4 ] && { echo "ERROR: no se pudo descargar $1" >&2; exit 1; }
    sleep $((2 ** i))
  done
  git -C "$2" checkout -q --detach "$3"
  [ "$(git -C "$2" rev-parse HEAD)" = "$3" ] || { echo "ERROR: commit inesperado en $1" >&2; exit 1; }
  git -C "$2" fsck --no-dangling --no-progress >/dev/null 2>&1 || { echo "ERROR: objetos corruptos en $1" >&2; exit 1; }
  rm -rf "$2/.git"
}
mkdir -p "$DEST"
fetch jsmolka/gba-tests "$DEST/gba-tests" "$JSMOLKA_SHA"
fetch SingleStepTests/ARM7TDMI "$DEST/ARM7TDMI" "$SST_SHA"
echo "$STAMP" > "$DEST/.version"
echo "OK: pruebas GBA en $DEST"
