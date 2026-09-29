# D2 · Evidencia (biblioteca por carpeta y saves robustos)

Generada en Claude Code en la nube el 2026-09-29, en la rama `claude/amazing-babbage-lgip5y` reiniciada desde `main` (`ca6bec5`, merge de D1). Código: `614792d`. El CI de macOS corrió sobre ese commit: run https://github.com/JoelBerus/pocketgb/actions/runs/36617107438 (Xcode 26.6).

## 1. Chequeos en Linux (D-README §2.2)
- `git diff --check`: sin errores.
- `rg 'URLSession|NWConnection|NSAppTransportSecurity|\.blur\(|UIBlurEffect|Material\.|GBA|\.gba|cheat' ios/PocketGB ios/PocketGBTests`: sin coincidencias, quitando `RGBA`.

## 2. CI (`SUMMARY.md`)
```
resultado: success
## Errores y warnings del proyecto
(ninguno)
✔ Test interruptedFirstSaveIsRecoveredOnLaunch() passed
✔ Test failureAfterStepKeepsPreviousSave(step:) with 3 test cases passed
✔ Test sevenSavesKeepBackupsOneToFive() passed
✔ Test mirrorOnlyIsImportedIntoLocal() passed
✔ Test restoreBacksUpCurrentFirst() passed
✔ Test mirrorFailureKeepsLocalAndRetries() passed
✔ Test mirrorSaveDateIsReported() passed
✔ Test run with 36 tests in 5 suites passed after 2.468 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (355.505 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (24.177 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (23.229 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (22.414 seconds).
** TEST SUCCEEDED **
```
El build Release también compila (paso del script del CI).

## 3. Criterios de CI de D2 (D-README §4)
| Criterio | Verificación |
|---|---|
| Solo `.gb`/`.gbc`, profundidad máxima 1 | `LibraryScannerTests.findsOnlyGameBoyFilesUpToDepthOne` (ignora `.txt`, ocultos y profundidad 2) |
| Rechazo de archivos > 8 MiB | `rejectsTooLargeAndInvalidFiles` (archivo disperso de 8 MiB + 1). `readROM` también lo rechaza al abrir |
| Lectura coordinada con `NSFileCoordinator` | `LibraryScanner.readHeader/readROM`, `SaveMirror.read/write` |
| La UI no modifica el ROM | `scanningDoesNotModifyROMs`: mismo contenido y misma fecha tras escanear y leer |
| AtomicFile: primer guardado | `firstSaveCreatesFileWithoutBackups` |
| AtomicFile: primer guardado interrumpido y recuperado | `interruptedFirstSaveIsRecoveredOnLaunch` (fallo inyectado tras el paso 2) |
| AtomicFile: fallo tras los pasos 2–4 conserva el `.sav` | `failureAfterStepKeepsPreviousSave(step:)` con 2, 3 y 4 |
| AtomicFile: reemplazo completo deja el anterior en `.1` | `replaceKeepsPreviousInBackupOne` |
| AtomicFile: contenido idéntico no rota | `sameContentDoesNotRotate` |
| AtomicFile: siete guardados mantienen solo `.1`–`.5` | `sevenSavesKeepBackupsOneToFive` |
| Un `.sav` incorrecto no se sobrescribe | `SaveResolution`: `wrongSizeMirrorIsNeverTouched` y `wrongSizeLocalIsNotLoaded`. La sesión no guarda (sin `SaveTarget`) o no toca el espejo (`mirrorIgnored`) |
| Conflicto local/espejo: gana el más reciente y el otro se respalda | `newerMirrorWins…` (la instalación con AtomicFile deja la local en `.1`), `newerLocalWinsAndMirrorIsBackedUp` y `missingDatesPreferLocal` |
| Un fallo del espejo conserva la local y programa un reintento | `mirrorFailureKeepsLocalAndRetries`. `EmulatorSession` reintenta en cada flush y al abrir |
| Restaurar un backup respalda antes la partida actual | `restoreBacksUpCurrentFirst` |
| Capturas con acciones recuperables y no destructivas | Sección 4 |
| Los tests temporales no escriben dentro del repo | Todos usan `FileManager.default.temporaryDirectory/<UUID>` |

## 4. Revisión visual (14 capturas nuevas + las de D1, 1206×2622)
| Captura | Resultado |
|---|---|
| library-folder-unavailable · light / dark | ✅ Error no destructivo ("Tus partidas siguen guardadas en este iPhone") y "Elegir de nuevo" |
| library-cloud-pending · light / dark | ✅ Nube y "En iCloud · archivo" en los que no están descargados; el footer explica que se descargan al tocarlos |
| library-cloud-downloading · light / dark | ✅ Spinner por fila y "Descargando…"; el footer explica que se podrá jugar al terminar. Sin spinner global |
| library-scan-progress · light / dark | ✅ "Buscando juegos… 2 de 5" en una fila; la lista sigue utilizable, sin modal |
| library-scan-summary · light / dark | ✅ Toast de vidrio "2 juegos nuevos" y badge "Nuevo" en los dos juegos |
| library-rom-error · light / dark | ✅ Nombre, motivo (> 8 MiB, cabecera inválida) y triángulo; al tocar, alerta con la ubicación y "no lo abrirá ni lo modificará" |
| save-data-error · light / dark | ✅ Alerta: el archivo no se tocará y se usa la partida del iPhone |
| D1 (launch, library-no-folder, library-empty, settings-*) | ✅ Sin cambios de aspecto. La biblioteca vacía ahora es la de una carpeta real (`-demoLibrary empty`) |
| game-* | ✅ Sin cambios |

Observación: con un título largo, el badge "Nuevo" pasa a la derecha del texto truncado. Es legible; D3 rediseña la presentación (grid/lista con portadas).

## 5. Pendiente del iPhone (🍎, D-README §4)
- Elegir una carpeta real de iCloud Drive; cerrar y abrir la app sin volver a elegirla.
- Volver de background y que se reescanee.
- Descargar un ROM que esté solo en iCloud.
- Guardar en Pokémon y forzar el cierre en menos de 2 s; al reabrir, la partida está.
- Ver el `.sav` junto al ROM desde Finder o Archivos.
- Reinstalar desde Xcode y recuperar la partida desde el espejo.
- Revocar el acceso a la carpeta y ver la pantalla "No se puede abrir la carpeta".
- Ajustes › Partidas: ver las copias y restaurar una.

## Verificación en el Mac tras la auditoría Codex (2026-09-29)
El CI de `c314a17` no arrancó: GitHub bloqueó el job por facturación ("recent account payments have failed or your spending limit needs to be increased"). Verificación equivalente en el Mac de Joel (Xcode 26.6):
- `c314a17` (antes de la corrección): núcleo 157/157; `xcodebuild -configuration Release … build` → BUILD SUCCEEDED; `tools/ios-screenshots.sh` → TEST SUCCEEDED, 29 capturas. Claude revisó las 18 de D2 (claro y oscuro): sin errores visuales.
- Tras la corrección de H1 (Codex): Release → BUILD SUCCEEDED sin warnings del proyecto; `tools/ios-screenshots.sh` → TEST SUCCEEDED, 43 tests `✔` (incluido `blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest`), catálogo completo con la app viva.
