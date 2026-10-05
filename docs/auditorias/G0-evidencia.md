# G0 · Evidencia (instrucciones y andamiaje del núcleo GBA)

Rama `g0-gba-instrucciones`, 2026-10-05, Linux (clang 18.1.3).

## Entregables
- Contrato `gba/include/pocketgba.h` (prefijo `gba_` en todo símbolo; tipos de guardado, botones L/R, API espejo de `pocketgb.h`).
- `gba/Makefile`: `lib`, `test`, `asan`, `check-header`, `check-globals` (falla también si `nm` no produce salida, nota de Codex en M4), `check-symbols` (sin colisiones con el núcleo GB y sin símbolos sin prefijo).
- Runner `gba/tests/runner.c` (`--sst`, `--mode jsmolka`, `--bench`, `--unit`), `run_suite.py`, `suite.txt`.
- `tools/fetch-gba-test-roms.sh`: jsmolka/gba-tests `a7113b67…` y SingleStepTests/ARM7TDMI `e3097d88…`, fijados a commit con `git fsck`. `codeload.github.com` devuelve 403 en la nube, así que no hay tarball con SHA-256: la integridad la da el hash del commit.
- Hook `.githooks/pre-commit` y `.gitignore` ampliados a GBA; CI `.github/workflows/gba.yml`; `docs/10-gba-spec.md`; secciones GBA en 06, 08 y 09.

## Comandos
```
$ make -C gba check-header check-globals check-symbols
pocketgba.h compila aislado: OK
Sin estado global mutable: OK
Símbolos de los dos núcleos sin colisiones: OK
```
Hook, en un repo temporal (ROM de jsmolka renombrado a `.txt` y un `gba_bios.bin` de 16 KiB de ceros):
```
pre-commit: bloqueado renombrado.txt (contiene cabecera de cartucho Game Boy Advance)
pre-commit: bloqueado gba_bios.bin (posible BIOS de GBA)
```
Ningún archivo de `gba/tests/roms/` entra al índice (`git add -n gba | grep -c roms` → `0`).
