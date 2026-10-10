# Nivel N · auditoría conjunta final · respuesta de iOS

Veredicto de la auditoría: **APROBAR CON CAMBIOS**. Aquí, los hallazgos de iOS (regla dura 6 crítica). Android responde
aparte (rama `fin-android`). Cada cambio tiene su test y una mutación que lo rompe: el test la detecta (tabla final).

## Hallazgos
| Id | Qué cambió | Test |
|---|---|---|
| **H1 (alta)** | «Continuar» ya no borra el AUTO. `CoreError.stateConfig` (otro modelo, tipo de partida, reloj o BIOS, también GBA con `gba_bios.bin` ilegible) da el error propio `StateError.otherConfiguration` y el AUTO **se queda en su ranura** (ND21): al volver a ese ajuste, carga. «Jugar desde el inicio» tras ese rechazo lo aparta antes de abrir, para que la salida de esa sesión no lo pise. Con la RAM distinta (`notCurrent`) se **aparta** en vez de borrarse: `StateStore.setAsideAuto()` (rename atómico + fsync a `States/<huella>/auto.obsolete-<unix>-<id>.state`, como Android `setAsideAuto`; ninguna ranura lo lee). La decisión vive en `AppState.retireAutomaticState(after:in:)` | `GBATests.importedStateOfAnotherConfigurationSurvivesContinue` (importar un `.pgbm` jugado con BIOS → «Continuar» sin BIOS → `otherConfiguration` y el AUTO sigue en disco, en su ranura), `GBATests.automaticStateOfAnotherConfigurationIsRejectedAsOtherConfiguration`, `SaveRestorationTests.refusedContinuationSetsAsideOrKeepsTheAutoNeverDeletes` |
| **H7** | El aviso de otra configuración se añade en **todas** las ramas, también a «Listo para continuar» (`.alreadyCurrent(continuation: true)`). El texto sale de `AppState.importNotice(_:origin:configNote:)`, con el ternario entre paréntesis | `PGBMTests.configNoteReachesEveryNoticeWithContinuation` |
| **H10** | Cambiar la partida sin sesión aparta antes el AUTO, que deja de ser vigente y `closeGame` pisaría: instalar la SRAM de un momento o del anillo (`MomentActions.installSRAM`, con `states`), restaurar un backup o una copia apartada (`SaveRestoration`). Si la escritura falla, el AUTO vuelve a su ranura; si la partida nueva es idéntica, no se toca. Cable link: al cerrarlo, el AUTO de cada juego se aparta si su partida quedó más nueva (`setAsideAutoIfStale`). Además, red de seguridad general: al abrir un juego **sin** «Continuar», un AUTO anterior a la partida (ya abierta, con el espejo resuelto) se aparta antes de que la sesión escriba el suyo (cubre restaurar fuera de la app o importar sin estado) | `SaveRestorationTests`: `installingAMomentSaveSetsAsideTheAuto`, `restoringABackupSetsAsideTheAuto`, `restoringAKeptCopySetsAsideTheAuto`, `installingTheSameSaveKeepsTheAuto`, `failedInstallPutsTheAutoBack`, `staleAutoIsSetAsideOnlyWhenOlderThanTheSave` |
| **H11** | Ajustes › Partidas restaura con `SaveRestoration.restore(backup:)` y `restore(kept:)`, dentro de `ownership.withExclusive` (y rechazada mientras se abre un juego, como el detalle): con el juego abierto o aún guardando su espejo se rechaza sin tocar la partida ni el AUTO. La vista solo llama a `AppState.restoreSave` | `SaveRestorationTests.restoreIsRefusedWhileTheGameIsOwned` |
| **H13** | META (docs/12): `null` solo vale en `base_sav_sha256`; en cualquier otra clave conocida (`state_of_sav_sha256`, `config` y sus claves, `play_time_ms`, `title`, `alias`, `tags` y sus elementos, `milestones`, `moment` y sus claves, `core.version`) la META es inválida. `StrictJSON.scan` ya no rechaza todo número con fracción: devuelve las rutas que la llevan y solo invalidan las claves enteras (`format`, `created_ms`, `play_time_ms`, `moment.created_ms`); una clave desconocida se ignora aunque lleve `null`, `1.5` o `2e3`, también dentro de `config` o de una lista. Las claves repetidas siguen rechazándose | `PGBMTests.metaNullAndFractionsOnlyMatterInKnownKeys` (más `metaIsStrictPerND20h` y `metaSchemaIsStrict`, que siguen pasando) |
| **H4** | Decisión: se adopta la regla de iOS; el código no cambia. Test explícito para que Android lo replique con el mismo nombre y caso | `SaveLineageTests.receivedCountsAsOwnHistory`: (a) historial solo con la recibida `r`, local `l`, espejo `r` → `ownEarlier`; con E/S: `d1` llega por el espejo (recibida, sin escrituras propias), la local avanza a `d2`, el espejo sigue en `d1` con el reloj adelantado → gana la local (`d2`, aviso `.mirrorOlderKept`). (b) Solo recibidas: local = `r`, espejo `z` → `external`; local `l`, espejo `z` → `divergent`; con E/S, `d3` en el espejo con el reloj atrasado se instala como cambio externo (por fecha ganaría la local) |
| Paridad (ND17) | El carril «Continuar jugando» filtra primero los reanudables (y jugables) y después toma 5 (`LibraryQuery.continuePlaying`) | `CoversTests.continueRailFiltersResumableBeforeTakingFive` (7 juegos, solo el más reciente y el más antiguo reanudables → salen los dos) |

## Mutaciones (cada una se aplicó sola, con los tests indicados, y se revirtió)
| Mutación | Resultado |
|---|---|
| M1 H1: `stateConfig` vuelve a lanzar `notCurrent` | `importedStateOfAnotherConfigurationSurvivesContinue` falla (el AUTO ya no está) |
| M2 H1: apartar → `delete(.auto)` | `refusedContinuationSetsAsideOrKeepsTheAutoNeverDeletes` falla |
| M3 H1: retirar con cualquier rechazo | fallan los dos tests de H1 |
| M4 H7: ternario sin paréntesis | `configNoteReachesEveryNoticeWithContinuation` falla |
| M5 H10: el momento escribe sin `SaveRestoration` | `installingAMomentSaveSetsAsideTheAuto` falla |
| M6 H10: `SaveRestoration.install` no aparta | `restoringABackupSetsAsideTheAuto` y `restoringAKeptCopySetsAsideTheAuto` fallan |
| M7 H11: restaurar el backup con un registro de dueños propio (sin exclusión real) | `restoreIsRefusedWhileTheGameIsOwned` falla |
| M8 H13: `null` aceptado en cadenas opcionales | `metaNullAndFractionsOnlyMatterInKnownKeys` falla |
| M9 H13: una fracción en cualquier clave invalida | `metaNullAndFractionsOnlyMatterInKnownKeys` falla |
| M10 H4: `History.contains` sin `received` | `receivedCountsAsOwnHistory` falla (divergencia en vez de `ownEarlier`) |
| M11 ND17: tomar 5 y después filtrar | `continueRailFiltersResumableBeforeTakingFive` falla |

## Documentación
- [04-ios-spec](../04-ios-spec.md): Restaurar (exclusión y AUTO apartado), estado automático (nunca se borra sin copia: `auto.obsolete-*`, `otherConfiguration`, cuándo se aparta), cable link y carril de «Continuar jugando».
- [guia/continuar.md](../guia/continuar.md) (iPhone): qué pasa con el estado automático cuando «Continuar» no carga; copiada al bundle con `tools/ios-guide-sync.py`.
- **Pendiente fuera de iOS:** [guia/partidas-continuar-y-renombrar.md](../guia/partidas-continuar-y-renombrar.md) (compartida, la escribe Android) dice «En Android, si el estado ya no corresponde a tu partida, lo aparta»: ahora el iPhone hace lo mismo y puede decirse para los dos.

## Verificación
```
PocketGBTests iPhone 17 Pro (iOS 26.5): ✔ Test run with 384 tests in 32 suites passed after 14.875 seconds. ** TEST SUCCEEDED **
PocketGBTests iPhone SE (3.ª gen.):     ✔ Test run with 384 tests in 32 suites passed after 14.123 seconds. ** TEST SUCCEEDED **
xcodebuild build -scheme PocketGB -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
** BUILD SUCCEEDED **
nm -u PocketGB.app/PocketGB | grep -ciE "URLSession|nw_connection|CFNetwork|CloudKit|CKContainer"   → 0
python3 tools/ios-guide-sync.py --check   → Guía del bundle al día (9 secciones).
```
Los simuladores «iPhone 17 Pro» que ya existían no arrancaban (backboardd y SimRenderServer abortaban en bucle, ajeno a la app); la suite del 17 Pro corrió en un simulador 17 Pro nuevo con el mismo runtime.
