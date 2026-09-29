#!/usr/bin/env bash
# Prepara un entorno Linux (Claude Code en la nube) para los hitos ☁️.
# Idempotente. Lo lanza el hook SessionStart de .claude/settings.json solo si CLAUDE_CODE_REMOTE=true.
# Sale con código ≠ 0 si falta una herramienta obligatoria; las ROMs de prueba solo generan aviso.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REQUIRED=(clang make ar nm python3 git curl unzip xxd)
missing() { local m=(); for c in "${REQUIRED[@]}"; do command -v "$c" >/dev/null || m+=("$c"); done; echo "${m[*]:-}"; }

SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
if [ -n "$(missing)" ] && command -v apt-get >/dev/null; then
  $SUDO apt-get update -qq && $SUDO apt-get install -y -qq clang llvm make binutils python3 git curl unzip xxd >/dev/null
fi
M="$(missing)"
if [ -n "$M" ]; then
  echo "cloud-setup: ERROR, faltan herramientas obligatorias: $M" >&2
  exit 1
fi
# Runtime de ASan/UBSan/libFuzzer (make asan, make fuzz). En Ubuntu va aparte: libclang-rt-N-dev.
probe="$(mktemp -d)"
if ! echo 'int main(void){return 0;}' | clang -x c -fsanitize=address,undefined - -o "$probe/p" 2>/dev/null \
   && command -v apt-get >/dev/null; then
  v="$(clang -dumpversion | cut -d. -f1)"
  $SUDO apt-get install -y -qq "libclang-rt-$v-dev" >/dev/null 2>&1 \
    || { $SUDO apt-get update -qq && $SUDO apt-get install -y -qq "libclang-rt-$v-dev" >/dev/null; } \
    || echo "cloud-setup: AVISO, no se pudo instalar libclang-rt-$v-dev: 'make asan' no enlazará" >&2
fi
rm -rf "$probe"
git -C "$ROOT" config core.hooksPath .githooks
if ! "$ROOT/tools/fetch-test-roms.sh"; then
  echo "cloud-setup: AVISO, sin ROMs de prueba (¿red bloqueada a github.com?). 'make test' fallará hasta resolverlo. Ver docs/06-testing.md" >&2
fi
echo "cloud-setup: listo. Lee docs/ESTADO.md."
