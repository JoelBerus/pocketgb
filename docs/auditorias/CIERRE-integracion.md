# Cierre: integración D8.1 + GBA G0–G9

Rama `cierre-integracion` (desde `main` `76492f4`, sin push).

| Paso | Rama fusionada | HEAD | Commit de merge |
|---|---|---|---|
| 1 | `codex/d8-1-corrections` (D8.1, auditado en dos vueltas) | `adb5749` | `4d085c9` (limpio) |
| 2 | `g8-gba-controls` (GBA G0–G9, auditado) | `8328fcc` | `94b53fa` |

## Conflictos y resolución
Git solo marcó 6 archivos (11 bloques); el resto de los que predijo `merge-tree` (DebugScreenRouter, ControlsOverlayView, GameDetailsView, GameListItem, LibraryPreferences(+Tests), LibraryView, screens.txt, hitos/README.md) se fusionaron solos con el orden D8.1 → GBA; se comprobó que compilan y pasan los tests. `screens.txt` queda con 95 IDs, sin duplicados.

| Archivo | Bloques | Resolución |
|---|---|---|
| `App/AppState.swift` | 5 | La carga en segundo plano conserva `GameOpeningPayload` + `.resumeAutomatic` (D8.1) y usa `readROM(url, limit: romLimit(for: console))` (GBA). `finishOpening` recibe `mode` **y** `console`/`bios`; guarda `gbaBIOSStatus`. `start(...)` tiene `restoring:`/`resumeFallback:` (D8.1) y `console:`/`bios:` (GBA). En el cuerpo: `applyAudioPreferences` + `try session.start(restoring:)` (D8.1) seguido del cálculo `detected` de GBA para `SavesIndex`. |
| `Emulator/EmulatorSession.swift` | 1 | Se conservan `confirmed = initialSRAM`, `lastQueued = initialSRAM` (D8.1) y `gameSettingsWarning = forcedWarning` (GBA). El resto de la continuación (`start(restoring:)`, `StateError.notCurrent`, pie RTC solo GB) se fusionó automáticamente sobre `any ConsoleCore` y compila. |
| `Library/GameCard.swift` | 1 | `[prefs.displayTitle(entry), entry.badge.name]`: alias de D8.1 + nombre de consola de GBA. |
| `Library/GamePlaceholderView.swift` | 1 | `GamePlaceholderView(seed:, title: title, badge: entry.badge, ...)`: título con alias (D8.1) + insignia por consola (GBA). |
| `project.pbxproj` | 2 (Debug/Release) | `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` (D8.1) + `HEADER_SEARCH_PATHS` con `core/include` y `gba/include` (GBA). |
| `docs/ESTADO.md` | 2 | Cabecera nueva coherente (integración); «Siguiente paso» con la línea de integración, D8.1 cerrado y todas las líneas GBA. |

No se tocó `core/` ni `gba/`. Ninguna funcionalidad eliminada. La ruta de partidas (regla 6) es la de ambos lados sin cambios: `confirmed`/`lastQueued` iniciales de D8.1 siguen activos también para GBA.

## Verificación (Mac, 2026-10-06)
- Tests unitarios (`-only-testing:PocketGBTests`, iPhone 17 Pro, bajo mutex): `✔ Test run with 127 tests in 12 suites passed` · `** TEST SUCCEEDED **`.
- Release `generic/platform=iOS`: `** BUILD SUCCEEDED **`.
- `make -C core test`: `65/68 PASS · requeridos: 65/65 PASS · HITO=M1` · `OK: todos los casos requeridos en PASS` (ROMs copiadas del checkout principal; los 3 restantes son known-fail).
- `gba/build/gbatest --unit`: `PASS unit: 0 fallos`. `make -C gba test` completo no corre en este Mac (falta `ld.lld` y los ROMs de SingleStepTests); corre en CI Linux.
- Catálogo `tools/ios-screenshots.sh`: `xcodebuild test: exit 0`; los tests de UI incluidos: `✔ Test run with 127 tests in 12 suites passed`. **95/95 capturas** (una por cada ID de `screens.txt`, ninguna falta; ese archivo trae 95 entradas, no 96: los 2 tests AX5 no generan capturas del catálogo). Revisadas a ojo: `game-details` (alias, Continuar + «Jugar desde el inicio», chip GB), `gameplay-gba-landscape-clear` (arm.gba «All tests passed», L/R, pantalla 3:2), `customize-controls-portrait` (cruceta redondeada, disposición «Controles · GB · Vertical»), `library-continue` (carril Continuar + filtro GBA). Todo correcto.

## Pendiente
- Port Android (`codex/android-port`) se fusiona después.
- Prueba 🍎 de Joel en el iPhone (D8.1 + Kirby GBA) y aprobación del PR a `main`.

## Revisión independiente (Opus): APROBAR CON CAMBIOS → INT-H1…H4

Informe: [CIERRE-integracion-revision.md](CIERRE-integracion-revision.md).

- **INT-H1 (alta, regla 6) corregido.** `EmulatorSession.ramBytes()` y la comparación con lo confirmado en disco usaban `info.sramBytes` (en GBA con EEPROM autodetectada, el tamaño provisional de 512 B). Ahora comparan `sramSave().dropLast(core.sramFooterBytes)`, el medio real completo; `ConsoleCore.sramFooterBytes` es el pie RTC (GB: `sramSaveSize` menos la RAM de la cabecera; GBA: 16 B con reloj, 0 sin él). Tests nuevos en `GBATests`: `eepromAutoStateDifferingPastByte512IsRejectedWithoutWriting` (rojo antes: «an error was expected but none was thrown»; verde después, disco intacto) y `eepromAutoStateMatchingTheSaveResumesWithoutWriting`. Los tests GB de D8.1 (`StateSRAMTests`, incluidos `newerMirrorRejects…` y RTC MBC3) siguen en verde.
- **INT-H2 (baja) corregido.** `start(restoring:)` convierte `CoreError.stateConfig` en `StateError.notCurrent`, así `AppState` retira el `.auto` de otra configuración. Test `automaticStateOfAnotherConfigurationIsNotCurrent` (rojo antes: lanzaba `CoreError.stateConfig`).
- **INT-H3 (baja, doc) corregido:** «95 IDs».
- **INT-H4 (baja, doc) corregido:** `ESTADO.md` unifica: auditoría Opus final de GBA hecha (`G9-opus.md`); queda la prueba 🍎 de Joel; la auditoría Codex es opcional.

Salidas:
```
GBATests + StateSRAMTests: Test run with 31 tests in 2 suites passed — TEST SUCCEEDED
PocketGBTests completa:    Test run with 130 tests in 12 suites passed — TEST SUCCEEDED
Release generic/iOS:       ** BUILD SUCCEEDED **
```
