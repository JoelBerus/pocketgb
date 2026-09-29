# M0 · Paquete de instrucciones

**Objetivo:** que cualquier agente (Claude local, Claude en la nube, Codex como auditor) pueda continuar sin contexto externo.

**Entregables:** `AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/00–08`, `docs/ESTADO.md`, `docs/hitos/*`, `docs/auditorias/*`, `core/include/pocketgb.h` (contrato), `core/Makefile`, `tools/fetch-test-roms.sh`, `.gitignore`, `.githooks/pre-commit`, `.claude/settings.json` + `tools/cloud-setup.sh` (entorno nube).

**Criterios de aceptación**
- [ ] `git ls-files | grep -Ei '\.(gb|gbc|sav)$'` → vacío.
- [ ] `printf 'x%.0s' $(seq 400) > /tmp/t.bin; printf '\xCE\xED\x66\x66' | dd of=/tmp/t.bin bs=1 seek=260 conv=notrunc; cp /tmp/t.bin fake.txt; git add fake.txt; git commit -m t` → **rechazado** por el hook (luego `git rm --cached fake.txt; rm fake.txt`).
- [ ] `make -C core check-header` compila `pocketgb.h` sola con `-std=c11 -Wall -Wextra -Werror -pedantic`.
- [ ] Informe de auditoría en `docs/auditorias/M0-*.md` sin hallazgos bloqueantes abiertos.
