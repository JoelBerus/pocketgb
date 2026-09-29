#!/usr/bin/env bash
# Prepara un entorno Linux (Claude Code en la nube) para los hitos ☁️.
# Idempotente. Lo lanza el hook SessionStart de .claude/settings.json solo si CLAUDE_CODE_REMOTE=true.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
need=()
for c in clang make python3 unzip curl; do command -v "$c" >/dev/null || need+=("$c"); done
if [ ${#need[@]} -gt 0 ] && command -v apt-get >/dev/null; then
  SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
  $SUDO apt-get update -qq && $SUDO apt-get install -y -qq clang make python3 unzip curl >/dev/null \
    || echo "cloud-setup: no se pudieron instalar paquetes (${need[*]}). Revisa el acceso a red del entorno."
fi
git -C "$ROOT" config core.hooksPath .githooks
"$ROOT/tools/fetch-test-roms.sh" || echo "cloud-setup: sin ROMs de prueba (¿red bloqueada a github.com?). Ver docs/06-testing.md"
echo "cloud-setup: listo. Lee docs/ESTADO.md."
exit 0
