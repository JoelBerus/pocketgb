# Auditoría G0 (andamiaje del núcleo GBA) · Opus de respaldo
Commit auditado: `0eeaf06`. Ejecutado en una copia propia.

## Veredicto: APROBAR CON CAMBIOS

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| G0-1 | media | .githooks/pre-commit (bloque 16384) | La BIOS se detecta solo por nombre (`*bios*`/`.bin`); una BIOS de 16 KiB llamada `x.rom` entra. | Repo temporal: bloquea `gba_bios.bin`, `bios.dat`, `r.txt`; no bloquea `x.rom`. | Detectar por hash/contenido sin importar el nombre. |
| G0-2 | baja | gba/Makefile `check-roms` | El mensaje apunta a `fetch-test-roms.sh`; `check-roms` no está en `.PHONY`. | Lectura. | Corregir. |
| G0-3 | baja | gba/Makefile `check-globals` | El patrón no ve símbolos `C`/`S`/`s` (common, Mach-O). En Linux con clang funciona. | Inyección de `static int` detectada en Linux. | Ampliar a `[bBdDcCsS]` o `-fno-common`. |
| G0-4 | baja | tools/fetch-gba-test-roms.sh | El comentario de `fetch()` dice `repo sha dir`; el orden real es `repo dir sha`. | Lectura. | Corregir el comentario. |

## Criterios del hito
| Criterio | Verificado por el auditor | Resultado |
|---|---|---|
| Contrato, Makefile, runner, suite y fetch fijado a commit | sí | check-header OK; check-globals detecta `b`/`d` inyectados; check-symbols detecta duplicados y símbolos sin prefijo; fetch con rev-parse y fsck. |
| Hook bloquea ROM GBA renombrado y `gba_bios.bin` | sí | Se cumple; hueco G0-1. |
| `gba.yml` y `10-gba-spec.md` | sí (lectura) | CI correcto; no ejecutado en GitHub. |
| Regla dura 1: historial limpio | sí | `git log --all --stat` sin binarios sospechosos. |
