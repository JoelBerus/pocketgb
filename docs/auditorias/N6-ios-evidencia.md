# N6 iOS · momentos y progreso: evidencia

Rama `n6-ios-momentos` (desde `siguiente-nivel`, con el lector C de N6-C). Solo iOS. Guía: [docs/guia/momentos.md](../guia/momentos.md).

## Qué se hizo
| Pieza | Archivos |
|---|---|
| `header "pocketgb_progress.h"` en el modulemap (el `.c` ya entraba por la carpeta sincronizada) | `core/include/module.modulemap` |
| Momentos + anillo «Antes de cargar» + migración de ranuras 1–4 | `ios/PocketGB/Saves/MomentStore.swift` |
| Cargar / instalar partida (regla 6) | `ios/PocketGB/Saves/MomentActions.swift`, `EmulatorSession.cartridgeRAM()` |
| Exclusión por huella (equivalente de `FingerprintOwnership`) | `ios/PocketGB/Saves/FingerprintOwnership.swift`, `AppState` (lease de la sesión hasta `whenMirrorIdle`) |
| Pantalla Momentos (pausa y detalle), editor | `ios/PocketGB/Saves/MomentsView.swift` (sustituye a `SaveStatesView`/`SaveStateCard`, borrados) |
| Progreso, tiempo de juego, lector Pokémon | `ios/PocketGB/Progress/{ProgressStore,ProgressLibrary,ProgressViews}.swift` |
| Detalle («Momentos» en lugar de «Estados», sección Progreso), menú contextual («Momentos»), centro de ajustes (Momentos y Progreso en lugar de «Próximamente»), % en la tarjeta | `GameDetailsView`, `GameCenterView`, `GameCard`, `LibraryRootView`, `PauseView` |
| Tests | `ios/PocketGBTests/MomentsTests.swift` (MomentsTests 15, ProgressTests 4) |
| Catálogo | `DebugScreenRouter` (casos N6 al final) y 18 líneas al final de `screens.txt` |

## Formato y regla dura 6
`Application Support/Moments/<huella>/`: `m-<id>.{state,sav,png}`, `b-<id>.{state,sav,png}`, `index.json` (punto de confirmación: último al crear, primero al borrar). Cada archivo con `AtomicFile` (tmp + fsync + rename + fsync de carpeta). Índice ilegible: se aparta a `index.damaged-…json` y se reconstruye desde los archivos, nunca se borra nada; si hay más de 3 entradas de anillo, las 3 más nuevas quedan en el anillo y las demás pasan a momentos. Anillo de 3 **por orden de inserción** (la recién escrita nunca se expulsa aunque el reloj retroceda). `Application Support/Progress/<huella>.json` (ilegible → se aparta).

- Cargar (pausa): se leen los bytes del momento, la posición actual (estado + RAM + miniatura + configuración) entra al anillo y solo después `session.loadState` (que guarda la partida con backup `.1` y revierte si falla). El AUTO no se escribe; desaparece «Cargar sin guardar»/«Guardar actual y cargar».
- Instalar partida (detalle, sin sesión): `ownership.withExclusive`; la actual al anillo, después `SaveStore.save` (backup `.1`). Tamaño distinto → rechazo sin tocar nada.
- Detalle «Cargar»: abre el juego y carga en pausa (la sesión ya es dueña de la huella).

## Criterios (con salida)
`xcodebuild test` iPhone 17 Pro (tools/ios-screenshots.sh) y iPhone SE (3.ª gen):
```
✔ Test run with 314 tests in 28 suites passed after 13.785 seconds.   (17 Pro)
xcodebuild Release: exit 0 / xcodebuild test: exit 0                 (catálogo: 238 capturas)
✔ Test run with 314 tests in 28 suites passed after 13.088 seconds.   (SE)
xcodebuild … -configuration Release -destination 'generic/platform=iOS' → ** BUILD SUCCEEDED **
make -C core test → 65/68 PASS · requeridos: 65/65 PASS
```
- Migración ranuras 1–4 sin pérdida: `slotsMigrateWithoutLossAndSurviveACrashMidway`, `migrationAfterARebuiltIndexDoesNotDuplicate`.
- Cargar momento antiguo → Recuperar en un toque, `.sav` previo en backup, AUTO intacto: `loadOldMomentThenRecoverInOneTap`, `recoveringTheOldestWithAFullRingWorks`.
- Estado que ya no carga → recuperar RAM: `stateThatNoLongerLoadsStillRecoversItsSave`.
- Exclusión con sesión aparcada y kill a mitad de instalar/cargar: `installIsRefusedWhileTheSessionOwnsTheGameAndSurvivesACrash`, `crashWhileLoadingAMomentNeverLosesTheSave`, `crashBeforeIndexLeavesNoHalfMoment`, `crashBeforeEvictionIsCleanedOnOpen`. El kill se simula con puntos de fallo inyectados (`crashPoint`: files/index/evict/migrate), no matando el proceso.
- Reloj que retrocede: `ringEvictsByInsertionEvenIfTheClockGoesBack`; índice dañado: `damagedIndexIsSetAside…`, `rebuiltIndexKeepsTheNewestThree…`.
- Tiempo (pausa, segundo plano, cierre forzado con checkpoint de 30 s): `playTimeCountsOnlyWhileRunning`.
- Lector: `.sav` sintético byte a byte, checksum malo = sin datos, japonés no soportado: `pokemonReaderWithSyntheticSaveAndBadChecksum`. Fuzz/ASan del lector: N6-C.

## Capturas revisadas (una a una)
n6-moments-pause (claro/oscuro), -load (aviso de configuración distinta), -recover, -new, n6-moments-detail (claro/oscuro), -filter, -install, -load, n6-moments-ax5, n6-moment-edit, n6-progress-details (claro/oscuro; botón «Momentos» ya sin truncar y rejilla 2×2), n6-progress-editor (claro/oscuro; «Marcar 2 medallas» propuesto), n6-progress-ax5, n6-library-percent («50 %» en la tarjeta). Las antiguas `save-states`, `load-state-confirm`, `replace-state-confirm` y `save-states-gba` ahora muestran la pantalla de Momentos (ranuras de demostración migradas); `replace-state-confirm` ya no tiene confirmación propia.

## Diferencias con Android
- iOS no tenía RESCUE ni `rescue-*.state`: solo se migran ranuras 1–4 y no hay aviso de rescate.
- Momentos en el detalle se abren dentro del centro de ajustes del juego (sheet) en lugar de una pantalla propia de la pila de navegación.
- Cable link: sin momentos (como antes sin estados) y sin contar tiempo de juego.
- Kill-test por inyección de fallos en vez de proceso trabajador matado.
- Índice reconstruido: el anillo sobrante pasa a momentos «Recuperado» (no se borra).
