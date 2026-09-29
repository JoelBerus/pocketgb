#!/usr/bin/env bash
# Descarga las ROMs de prueba libres (c-sp/game-boy-test-roms) y verifica su hash.
# Nunca descarga ROMs comerciales. Destino: core/tests/roms/ (ignorado por git).
set -euo pipefail
VERSION="v7.0"
URL="https://github.com/c-sp/game-boy-test-roms/releases/download/${VERSION}/game-boy-test-roms-${VERSION}.zip"
SHA256="b9a9d7a1075aa35a3d07c07c34974048672d8520dca9e07a50178f5860c3832c"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/core/tests/roms"
if [ -f "$DEST/.version" ] && [ "$(cat "$DEST/.version")" = "$VERSION" ]; then
  echo "ROMs de prueba $VERSION ya presentes en $DEST"; exit 0
fi
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo "Descargando $URL"
curl -fsSL --retry 3 -o "$TMP/roms.zip" "$URL"
if command -v sha256sum >/dev/null; then GOT=$(sha256sum "$TMP/roms.zip" | cut -d' ' -f1)
else GOT=$(shasum -a 256 "$TMP/roms.zip" | cut -d' ' -f1); fi
if [ "$GOT" != "$SHA256" ]; then
  echo "ERROR: hash inesperado ($GOT). No se extrae nada." >&2; exit 1
fi
rm -rf "$DEST"; mkdir -p "$DEST"
unzip -q "$TMP/roms.zip" -d "$DEST"
echo "$VERSION" > "$DEST/.version"
echo "OK: ROMs de prueba en $DEST"
