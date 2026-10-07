# N6 Android · respuesta a la auditoría Opus

Informe: [N6-android-opus.md](N6-android-opus.md) (APROBAR CON CAMBIOS). Cambios en `c74e83b`.

| Id | Respuesta |
|---|---|
| **N6A-H1** (media) | Corregido. `pushBeforeLoad` mete la entrada nueva delante del anillo y recorta por **orden de inserción** (`listOf(entry) + index.beforeLoad`, `take(3)`); `snapshot()` devuelve el anillo en ese orden, sin ordenar por fecha. La entrada nueva nunca puede salir expulsada. Prueba `aClockGoingBackwardsNeverEvictsTheNewEntry` (3 entradas, el reloj vuelve a 10, la nueva queda primera y sale la más antigua). **Mutación** (volver a ordenar por `createdMs`): `12 tests completed, 1 failed` → `aClockGoingBackwardsNeverEvictsTheNewEntry`. |
| **N6A-H2** (baja) | Corregido. Al reconstruir un índice dañado se recalcula el SHA-256 del estado de cada momento como `originSha`, así una ranura que reaparezca no se migra dos veces; el anillo reconstruido se queda en los 3 más recientes por fecha de archivo (la más nueva nunca sale; el resto pasa a huérfano). `origin` (qué ranura era) no se puede recuperar: documentado. Prueba `aRebuiltIndexKeepsTheMigrationShaAndAtMostThreeRingEntries`. |
| **N6A-H3** (baja) | Corregido. `MomentKillWorker` alterna (a) lo que hace `loadMoment` en disco: posición actual al anillo y después escritura atómica de la partida nueva, y (b) crear + `installSram`. Mismo invariante (la partida de antes sigue en `.sav`, anillo o backups). Desde limpio: `MomentKillTest: semilla=672108425383583, kills en bucle=118, instalaciones completadas=64`. |
| **N6A-H4** (baja) | Corregido. Fuera `StatesSheet.kt`, `LoadStateConfirmDialog.kt` (y su prueba), `StatesUi` y las operaciones de ranuras del ViewModel, los avisos `StateSaved/Loaded/Deleted` y **32 cadenas** que quedaron sin uso (incluidas `gameplay_pause_states*` y `menu_states_soon`). Los ids antiguos del catálogo (`states-sheet`, `states-dialog`, `load-state-confirm`, `replace-state-confirm`, `states-rescue`, `n8-states`) muestran ahora la hoja «Momentos» (sin tocar el manifiesto). El menú contextual de la tarjeta ofrece **«Momentos»** (abre la pantalla de momentos; sin ruta, el detalle). Se **mantiene** `GameSession.loadState(slot, saveCurrentToAuto)`: ninguna pantalla la usa, pero `GameSessionTest`, `GameSessionHardeningTest`, `GbaGameTest` y `ExactContinuationTest` la usan para ejercitar la ruta transaccional (`applyLoadedState`) que comparte con `loadMoment`. |

## Verificación desde limpio (`git archive` de `c74e83b`)
```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon --max-workers=1
BUILD SUCCESSFUL in 5m 20s
JVM 751 pruebas, 0 fallos
0 errors, 22 warnings          (antes 27: fuera las cadenas sin uso; quedan las previas a N6)
aapt2 dump permissions (Release) | grep -c INTERNET  →  0
```
Instrumentadas afectadas (`with-lock.sh emu`, una clase por corrida), todas 0 fallos: `DebugCatalogTest` 11, `CatalogCoverageTest` 9, `LibraryUiTest` 61 (el menú tiene «Momentos» activo), `GameplayUiTest` 12, `MomentsSessionTest` 4, `MomentsScreenTest` 2, `GameSessionTest` 13, `GameSessionHardeningTest` 18, `ExactContinuationTest` 15.

Kill-test con el APK Debug del árbol limpio:
```
tools/android-save-kill-test.sh 50
Resultado (gb): OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
```

## N6A-H2 · Recuperar una entrada del anillo lleno podía expulsarla (hallazgo trasladado desde iOS)

Con el anillo «Antes de cargar» lleno (3), «Recuperar» su entrada más antigua llamaba a `pushBeforeLoad`, que la
expulsaba y borraba sus archivos **antes** de que `SaveStore.save` (`MomentLibrary.installSram`) o el núcleo
(`GameSession.loadMoment` → `applyLoadedState`) confirmaran; si fallaban, esa partida se perdía.

Corrección: `MomentStore.pushBeforeLoadDeferred(..., protect)` mantiene la entrada recuperada en el anillo (plaza fija
tras la nueva) y devuelve un `PendingPush` cuyo `commit()` borra las expulsadas solo tras confirmar la carga o la
instalación; si no se confirma, quedan huérfanas y `recoverOrphans` las retira al abrir. `pushBeforeLoad` conserva su
contrato (push + commit inmediato).

Prueba: `MomentLibraryTest.recoveringTheOldestRingEntryWithAFullRingSurvivesAFailedSave` (fallo inyectado en la escritura
de la partida). Mutación: sin la protección, la prueba falla (`6 tests completed, 1 failed`). Desde árbol limpio
(`git archive`):
```
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL in 4m 1s
JVM 752 pruebas, 0 fallos
0 errors, 22 warnings
```
