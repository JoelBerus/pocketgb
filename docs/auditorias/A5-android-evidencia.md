# Evidencia · Android A5 — Partidas y estados (etapas 6–8: coordinador, apertura real y prueba de cierre forzado)

**Fecha:** 2026-10-05

**Rama:** `codex/android-port`. **Commits de A5:** `b8b91bb` (etapas 1–8, el árbol que auditaron Codex, Opus y DeepSeek en la 1ª vuelta), `acfe806` (correcciones de la 1ª vuelta), `ffd6d97` (correcciones de la 2ª vuelta, auditadas en la 3ª) y el commit que sigue a `ffd6d97` (correcciones de la 3ª vuelta, sección «Correcciones de la 3ª vuelta»). El texto de las etapas 6–8 describe `b8b91bb`; donde una afirmación original quedó matizada por las correcciones posteriores se marca con «(corregido: …)».

**Alcance (etapas 6–8):** `SaveCoordinator` y `GameSession` (6); `GameLauncher`, `GameplayViewModel`, `GameplayHost` con menú de pausa, estados, diálogo de fallo local y avisos de espejo, Ajustes › Partidas con restauración de backups, **Jugar/Continuar habilitado** y catálogo debug (7); `tools/android-save-kill-test.sh`, medición de `flushSync` y esta evidencia (8). Las etapas 1–5 ya tienen su evidencia en commits previos; aquí se reejecutan sus pruebas dentro de la batería completa.

## Implementación

- **`SaveCoordinator`** (`saves/`): un `ScheduledThreadPoolExecutor` de 1 hilo `pocketgb-saves` por sesión. Ciclo cada 100 ms: política (`SramFlushPolicy`: debounce 1 s, red de 60 s) → `copySram()` → si ≠ `lastQueued` → `persistLocal`. Mantiene `confirmed` (lo último confirmado en disco) y `lastQueued` (lo último intentado). Una escritura fallida deja `lastQueued = null`, avisa por `onStatus` y la reintenta el siguiente ciclo (un debounce después). `flushSync(): FlushResult` (`Saved | Unchanged | Failed(e) | TimedOut`) se encola en el **mismo hilo** (I4: copiar y escribir siempre juntos y en orden), compara con `confirmed` y espera `get(3 s)`; el plazo agotado no cancela nada y deja el guardado pendiente. `close()` para el ejecutor **y une el hilo** antes de que nadie libere el handle nativo (I3). *(Corregido en la 1ª vuelta: en `b8b91bb` el `join` era acotado y su resultado se ignoraba, así que un hilo atascado en un archivo que no responde podía sobrevivir al cierre y luego tocar un handle destruido. Ahora `shutdown()` marca el cierre bajo la compuerta de cada copia nativa, confirma `isAlive` y devuelve `CloseResult.SaveThreadStuck` sin que nadie libere el handle; ver abajo.)* También ejecuta, en ese hilo, la E/S de los estados (`runOnSaveThread`).
- **`GameSession`** (`game/`): dueña de `EmulatorSession` + `SaveCoordinator` + `SaveTarget` (local y espejo) + `StateStore`. `saveState(slot)` (sesión aparcada; PNG y escritura atómica en el hilo de guardado); `loadState(slot)` (guarda el actual en AUTO si procede → `previous = saveState()` → `loadStateRaw` → `flushSync`; si el flush no es `Saved/Unchanged` ⇒ `loadStateRaw(previous)`, `requestFlush()` y `StateError.SaveFailed` «La partida actual no ha cambiado…»; *corregido: ahora encola además una barrera en el mismo hilo y solo dice «no ha cambiado» si la confirma*); `pause()` = pausa nativa + `flushSync` acotado, idempotente (sirve para `ON_PAUSE`, `ON_STOP` y foco de audio; repetirlo reintenta un guardado pendiente); `exit(force=false): ExitResult` = `Clean | LocalSaveFailed(error)`: **nunca cierra limpio ignorando un fallo local** (la sesión sigue abierta y en pausa); con `force` intenta antes un estado AUTO de rescate (J6). AUTO al salir tras flush correcto (su fallo no impide salir). Sesión sin destino (tamaño de `.sav` incorrecto, J10, o cartucho sin batería): los estados funcionan, la SRAM no se persiste y se avisa.
- **`GameLauncher`** (SPEC §2.2): lee la ROM con `ContentResolverRomSource` (≤ 8 MiB + 1), `EmulatorSession.load(rom, now/1000)`, `recoverOrphans(validSizes)`, localiza el espejo (`SafMirrorLocator`: `ReadWrite`/`ReadOnly`/`Shared`), `SaveOpening.prepare`, `loadSram`, `SavesIndex.record`; devuelve `OpenResult.Opened(GameSession)` o `Failed(OpenError)` (`Unplayable`, `FolderMissing`, `PermissionRevoked`, `RemotePending`, `RomTooLarge`, `RomRejected`, `MirrorNotDownloaded`, `SaveIncompatible`, `LocalSaveFailed`, `Unreadable`, `Core`). La apertura corre en `NonCancellable` (no deja un handle nativo sin dueño) y cierra la sesión ante cualquier fallo.
- **`GameplayViewModel`** (AndroidX `ViewModel`, `by viewModels` en `MainActivity`): dueño de la `GameSession` (jamás `remember`); sobrevive a rotación/`recreate()`. `open` con guarda de doble toque, `showPauseMenu`/`continueGame`/`revealPauseMenu`, estados (`saveState`/`loadState`/`deleteState` bajo `Mutex`), `exit(force)`, doble confirmación de riesgo, `onBackground()`, `onCleared()` *(en `b8b91bb` forzaba `exit(force = true)` en silencio; corregido: ver abajo)*. Registra «jugado» con `LibraryViewModel.recordPlayed`.
- **UI** (Material 3, tema por `MaterialTheme`, ≥ 48 dp): `PocketGBApp` pinta `GameplayHost` a pantalla completa cuando hay sesión (sin ruta en el back stack; el estado de navegación se hoistea para volver al mismo sitio). `BackHandler`: corriendo ⇒ pausa y abre el menú; el menú se cierra con atrás (reanuda); atrás nunca sale del juego. `SessionLifecycleObserver(GameSession)`: `ON_PAUSE`/`ON_STOP` ⇒ `pause()` (flush ≤ 3 s), `ON_RESUME` ⇒ reintenta un guardado pendiente sin reanudar; al volver el juego **queda en pausa** y el menú se muestra. `PauseSheet` (hoja inferior en vertical, diálogo en horizontal: Continuar, Estados, Salir), `StatesSheet` (AUTO + 4 manuales con captura, fecha, Guardar/Cargar/Eliminar; reemplazar, cargar y eliminar piden confirmación), `ExitSaveFailedDialog` («No se pudo guardar la partida en este teléfono» + causa; Reintentar / Seguir jugando / «Salir sin guardar…» con 2.ª confirmación en estilo de error), diálogo de `SaveLoadWarning` al abrir (textos en `strings.xml`), `OpenErrorDialog`, snackbar para fallos de espejo (`MirrorTrouble`, `MirrorDisabled`), cortina «Abriendo el juego…».
- **Detalle/Biblioteca:** el botón es «Jugar» o «Continuar» (si hay `lastPlayed`; no carga el AUTO, J8), habilitado si `entry.isPlayable && load !is Failed`; «Continuar jugando» de la biblioteca abre con el mismo lanzador. Se eliminó `PLAY_DISABLED_LABEL` y su test.
- **Ajustes › Partidas** (`SettingsRoute.Saves`): lista de partidas por título (`SavesIndex`), backups con fecha, «Restaurar» con confirmación (`SaveStore.restore`, que respalda antes la actual); deshabilitado para la huella con sesión abierta (y rechazado también en `SavesBrowser.restore`).
- **Solo Debug:** catálogo con 10 pantallas nuevas, modos `--es debug save-stress|save-verify` (`SaveStress.kt`) y `GameplayTestActivity` (anfitrión de las pruebas de ciclo de vida; un APK de test no puede declarar actividades que resuelvan al proceso de la app). Verificado abajo que nada de esto está en Release.

## Verificación fresca

Núcleo (sin tocar `core/`):

```bash
make -C core test && make -C core asan
```

Salida de ambos: `65/68 PASS · requeridos: 65/65 PASS · HITO=M1` y `OK: todos los casos requeridos en PASS` (los tres `known-fail` documentados de M1).

Android (emulador `Small_Phone_API_35`, con `-gpu swiftshader_indirect -memory 3072`):

```bash
cd android
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew clean :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado: `BUILD SUCCESSFUL in 3m 47s`. Lint: 0 errores, 15 avisos (A4 cerró con 12; el único aviso nuevo de A5 es una sugerencia `UseKtx` en `FramePng.kt`; el resto son sugerencias `UseKtx`/versión y avisos de vistas personalizadas anteriores). Recuentos reales (de los XML de resultados):

**JVM: 210 tests, 0 fallos** (A4 cerró con 73; +137 en las etapas 1–8 de A5): `AtomicFileTest` 12, `AtomicSaveFaultInjectionTest` 5, `FrameThumbnailTest` 4, `MirrorChannelTest` 13, `ProcessKillTest` 1, **`SaveCoordinatorTest` 15**, `SaveOpeningTest` 19, `SaveResolutionTest` 21, `SaveStoreTest` 19, **`SavesBrowserTest` 4**, `SavesIndexTest` 3, `SramFlushPolicyTest` 7, `StateStoreTest` 9, `LibraryViewModelTest` 25, `LibraryPreferencesTest` 15, `LibraryScannerTest` 14, `LibraryModelsTest` 3, `ManifestPolicyTest` 6, `AppNavigationStateTest` 5, `ControlGeometryTest` 3, `TouchInputEngineTest` 2, `AppearancePreferencesTest` 3, `IntegerViewportTest` 2.

**Instrumentados: 164 tests, 0 fallos**: `AppShellTest` 2, `DebugCatalogTest` 8, `SessionLifecycleObserverTest` 2, `NativeAudioTest` 3, `CoreBridgeTest` 4, `EmulatorSessionTest` 7, `NativeLibraryTest` 1, `NativeSaveBridgeTest` 19, **`GameSessionTest` 13**, **`GameplayLifecycleTest` 3**, **`GameplayUiTest` 6**, **`OpenFromLibraryTest` 8**, **`SaveCyclesTest` 1**, `GameControlsViewTest` 2, `SafLibraryTest` 14, `SafMirrorTest` 34, `SaveFilesOnAndroidTest` 2, `LibraryUiTest` 32, **`SavesSettingsUiTest` 2**, `GameSurfaceTest` 1. (En negrita, lo añadido en las etapas 6–8; además `LibraryUiTest` se amplió con las pruebas de Jugar/Continuar y `DebugCatalogTest` con las pantallas nuevas.)

`./gradlew :app:testDebugUnitTest --rerun-tasks` ×3: `BUILD SUCCESSFUL` las tres veces (39 s, 37 s, 37 s), 210 tests y 0 fallos tras la última.

### Cómo se comprobó que los instrumentados se ejecutan de verdad

Con poca RAM Gradle puede informar `BUILD SUCCESSFUL` con 0 tests si el sistema mata el proceso de la instrumentación. En cada ejecución se contaron los `<testcase>` de `app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` (164 casos en 20 suites). Los 164 de la corrida final están listados arriba por clase.

### Criterios de SPEC → prueba que los cubre

| Criterio | Prueba |
|---|---|
| §5.2.1 contenido idéntico no escribe ni rota | `AtomicFileTest`, `SaveStoreTest`, `SaveCoordinatorTest.identicalContentIsNotWrittenTwice` |
| §5.2.2–5 `.tmp` + fsync, rotar solo backups, copiar (no mover) el actual, renombrar | `AtomicSaveFaultInjectionTest` (fallo inyectado en cada operación), `SaveFilesOnAndroidTest`, `SaveCoordinatorTest.ioFailureDuringBackupRotationLeavesPreviousSaveIntact`, `SaveCyclesTest` (≤ 5 backups, sin `.tmp`), kill-test |
| §5.2.6 recuperar temporales al arrancar | `SaveStoreTest` (con RTC), `OpenFromLibraryTest`, `save-verify` tras `recoverOrphans` (50/50) |
| §5.2 tamaños (RTC +48/+44) y cuarentena | `SaveStoreTest`, `SaveResolutionTest`, `SaveOpeningTest`, `NativeSaveBridgeTest`, `GameSessionTest.rtcCartridgeSavesTheClockBlockToo` |
| §5.2 debounce 1 s y red de 60 s | `SramFlushPolicyTest`, `SaveCoordinatorTest.debounceWaitsForQuietBeforeWriting`, `safetyNetSavesAfter60SecondsWithoutAnEdge` |
| §5.2 síncrono al pausar, salir, restaurar estado y background | `GameSessionTest` (pausa, salida, `loadingAStateSavesItsSRAM…`), `GameplayLifecycleTest.movingToCreatedSavesTheSramAndComingBackLeavesTheGamePaused` (disco == núcleo), `SaveCyclesTest` |
| I4: copiar y escribir en el mismo hilo y en orden | `SaveCoordinatorTest.copyAndWriteHappenOnTheSaveThread`, `stateLoadedWhileAnAsyncWriteIsInFlightEndsWithDiskEqualToCore` (latch: A en vuelo, estado B cargado ⇒ disco == núcleo y orden A→B) |
| Flush síncrono reintenta una escritura asíncrona fallida; timeout | `SaveCoordinatorTest.syncFlushRetriesAnAsyncWriteThatFailed`, `aFailedPeriodicWriteIsRetriedByTheNextDueTick`, `requestFlushRetriesAFailedSyncFlushWithoutANewGameSave`, `syncFlushTimesOutWhenTheSaveThreadIsBlockedAndKeepsItPending` |
| I3: el hilo de guardado se une antes de liberar el handle | `SaveCoordinatorTest.closeJoinsTheSaveThread`, `GameSessionTest.closingJoinsTheSaveThreadAndIsIdempotent`, `SaveCyclesTest` (hilos estables en 100 vueltas) **solo cubrían el caso sin tarea bloqueada**; el caso con un hilo que no responde lo cubren `SaveCoordinatorCloseTest` y `GameSessionHardeningTest.theNativeHandleIsNeverReleasedWhileTheSaveThreadIsStillAlive` (1ª vuelta) |
| §5.3 espejo SAF (in situ `wt`, verificación, ReadOnly, Shared, cambios externos, nombre alterado) | `SafMirrorTest` (34), `SaveResolutionTest`, `SaveOpeningTest`, `MirrorChannelTest` |
| §5.3 el espejo nunca bloquea ni invalida la local | `GameSessionTest.synchronousFlushDoesNotWaitForBlockedMirror`, `aFailingMirrorRaisesANonBlockingEventAndNeverFailsTheLocalSave`, `SaveCoordinatorTest.mirrorTroubleIsReportedOnceAndNeverFailsTheLocalSave` |
| §5.3 / §2.2 apertura de extremo a extremo (ROM → SRAM en `filesDir/saves` y en el espejo) | `OpenFromLibraryTest` (`theSramWrittenByTheGameLandsInFilesDirSavesAndInTheMirror`, `anExistingSavNextToTheRomIsImported…`, `aReadOnlyFolderOpensWithAWarning…`, `aSavWithTheWrongSizeOpensWithoutPersisting…`, `siblingRomsSharingTheSavNameDisableTheMirror`, `anUnreadableMirrorWithNoLocalSaveRefusesToOpen`, `theViewModelOpensRecordsPlayed…`) |
| §5.4 estados: sesión aparcada; guardar incluye captura | `GameSessionTest.statesNeedAPausedSession`, `StateStoreTest`, `GameplayUiTest.statesSaveLoadAndDeleteWithConfirmation` |
| §5.4 cargar persiste la SRAM contenida, con rollback si falla | `GameSessionTest.loadingAStateSavesItsSRAMWithBackupOfThePrevious`, `loadStateFailsVisiblyAndRollsBack` (`FileOps` inyectado que falla) |
| §6 errores tipados con recuperación concreta | `OpenError` + `OpenFromLibraryTest.missingRomAndUnplayableEntries…`, `GameplayUiTest.aSaveLoadWarningIsShownAtOpenAndDismissed` |
| §6 un fallo local impide cerrar limpio hasta reintentar o confirmar con riesgo | `GameSessionTest.exitWithLocalFailureKeepsTheSessionOpenAndPausedThenRetrySucceeds`, `forcedExitAfterLocalFailureWritesARescueStateInItsOwnSlot`, `GameplayUiTest.localSaveFailureDialogOffersRetryAndDoubleConfirmedRiskyExit`, `retryAfterTheDiskRecoversExitsCleanlyAndKeepPlayingResumes` |
| §6 `ON_PAUSE` espera el flush; volver a primer plano deja el juego en pausa; la rotación no cierra la sesión | `GameplayLifecycleTest` (los tres), `SessionLifecycleObserverTest` |
| Atrás no sale del juego; menú de pausa | `GameplayUiTest.backOpensThePauseMenuAndContinueResumes`, `backWhilePausedClosesTheMenuAndResumesNeverLeavingTheGame` |
| Jugar habilitado solo si jugable; Continuar si hay `lastPlayed` | `LibraryUiTest` (`playIsEnabledOnlyForPlayableGames…`, `playedGamesOfferContinue`, `playIsDisabledWhen…` ×2, `continuePlayingCardOpensTheGame…`), `DebugCatalogTest` |
| J4 restaurar backups con confirmación, deshabilitado con sesión abierta | `SavesBrowserTest`, `SavesSettingsUiTest` |
| Regla dura 6 / §8: cierre forzado sin pérdida de partida | `ProcessKillTest` (JVM hijo, `destroyForcibly()` ×200), `tools/android-save-kill-test.sh` (abajo), `SaveCyclesTest` |

### 100 ciclos abrir → jugar → pausar → guardar ranura 1 → salir

`SaveCyclesTest.hundredOpenPlayPauseSaveStateExitCycles` (lanzador real, núcleo real, ROM contador sintética, misma partida en las 100 vueltas): en cada vuelta comprueba que la partida continúa donde se dejó, disco == núcleo tras pausar y tras salir, ningún `.tmp`, ≤ 5 backups y el mismo número de hilos `pocketgb-saves`; 0 crashes. Salida de logcat (`A5Metrics`):

```
ciclos=100 guardados=100 plazos-agotados=0 reintentos-de-salida=0 flushSync n=200 p50=0.93625 ms p99=19.058417 ms max=22.559375 ms | game.pause() p50=15.963 ms p99=25.144291 ms max=28.455875 ms
```

### `flushSync` en `ON_PAUSE` (p50/p99)

Mismo test; `flushSync` n = 200 (el de `game.pause()` y el de `exit()` de cada vuelta): **p50 0,94 ms, p99 19,1 ms, máx 22,6 ms**. `game.pause()` entera (pausa nativa + flush) **p50 16,0 ms, p99 25,1 ms, máx 28,5 ms**, muy por debajo del plazo de 3 s. Con el anfitrión bajo fuerte presión de memoria (carga media 10–20; ver «Incidencias de entorno») el mismo test dio `flushSync` p50 8,2 ms / p99 527 ms / máx 670 ms y `game.pause()` p50 67 ms / p99 742 ms / máx 854 ms. Es emulador x86/arm64 de escritorio, no un teléfono: **no es una medida del teléfono de Joel**.

### Prueba de cierre forzado: `tools/android-save-kill-test.sh 50`

Cada iteración: arranca `save-stress` (ROM contador, debounce de 20 ms, pausa/reanuda y estados al azar; escribe `SAVE-STRESS READY` cuando ya escribe), duerme 50–1500 ms al azar, mata (`am force-stop` y `run-as <pkg> kill -9 <pid>` alternando) y lanza `save-verify`, que tras `recoverOrphans` exige: `.sav` presente y de tamaño válido, contenido coherente, ningún `.tmp`, ≤ 5 backups con tamaño válido y que el núcleo acepta la partida y el estado `slot1`. Resultado (50 iteraciones, alternando `am force-stop` y `kill -9`):

```
Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
```

(Primeras y últimas líneas: `iter 1 kill=force-stop tras 1342 ms -> OK bytes=8192 backups=5 stateTmpOrphans=0` … `iter 50 kill=kill9 tras 1343 ms -> OK bytes=8192 backups=5 stateTmpOrphans=0`. Algunas iteraciones terminan con 4 backups: la muerte cayó en mitad de la rotación, estado intermedio permitido por el invariante.) El script falla con un solo `FAIL`, con alguna verificación ausente o con ninguna. Una primera versión del script mataba antes de que el juego escribiera (en el emulador cargado el arranque tardaba más que el retardo aleatorio): se corrigió para que el retardo cuente desde `SAVE-STRESS READY`.

### Release, privacidad y árbol limpio

- `aapt dump permissions` sobre `app-release-unsigned.apk`: solo `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; **0 coincidencias de `INTERNET`**.
- Cadenas en los DEX de Release: `DebugCatalog`, `TestDocumentsProvider`, `save-stress`, `save-verify`, `SaveStress`, `GameplayTestActivity`, `GameplayDebugScreen`, `NativeVideoScreen` → **0 coincidencias cada una**. En Debug: `DebugCatalog` 131, `save-stress` 2, `SaveStress` 46.
- `git status`: solo código, scripts y docs; ningún `.sav`, `.state`, ROM, APK ni captura. El hook `pre-commit` sigue activo.

### Revisión visual

`tools/android-screenshots.sh` (con rotación horizontal automática para los `*-dialog`) generó **56/56 capturas** (28 pantallas × claro/oscuro; nuevas: `library-detail-played`, `pause-sheet`, `pause-dialog`, `states-sheet`, `states-dialog`, `exit-save-failed`, `exit-risk`, `save-warning`, `open-error`, `saves-settings`; `library-detail` ahora con «Jugar» habilitado). Se inspeccionaron con `Read` las nuevas en claro y oscuro. La primera pasada detectó y se corrigió: en `states-sheet` los botones se apilaban uno por línea (texto de la columna estrecho) → ahora la fila de botones ocupa todo el ancho de la tarjeta con relleno compacto; en el diálogo horizontal el título tocaba el borde superior y el diálogo era demasiado estrecho → relleno superior y ancho mínimo. Sin solapes ni recortes en las capturas finales; contraste correcto en ambos temas (acciones destructivas en `error`, «Salir sin guardar» en estilo de error con segunda confirmación).

Observaciones abiertas (A6): con tema claro la barra de estado del juego (fondo negro) usa iconos oscuros y apenas se ve; la lista de Partidas no deja margen inferior sobre la barra de gestos en el catálogo (en la app real lo da el `Scaffold`).

## Correcciones de la 1ª vuelta de auditoría

Informes: `A5-android-codex.md` (RECHAZAR: H1, H2 bloqueantes; H3 alta; H4 media; H5 baja), `A5-android-opus.md` (APROBAR CON CAMBIOS: H1 alta; H2–H5 medias; H6–H12 bajas) y `A5-android-deepseek.md` (RECHAZAR: H1 bloqueante, H2 baja; informe poco fiable, pero H1 es real). Árbol auditado: `b8b91bb`; las correcciones quedaron en el commit `acfe806` (auditado en la 2ª vuelta). Implementadas con TDD: cada prueba nueva se escribió para el comportamiento corregido; las marcadas «falla sin la corrección» se comprobaron **revirtiendo temporalmente la corrección** (resultado abajo).

### Hallazgo → corrección → prueba

| Hallazgo | Corrección | Prueba que lo cubre |
|---|---|---|
| **Opus H1** (alta): espejo y guardado mutan `backups/` a la vez con el mismo `<huella>.1.tmp` | `SaveLocks` (un `ReentrantLock` por directorio+huella, compartido entre instancias) y toda mutación de `SaveStore` (`save`, `addBackup`, `quarantineCurrent`, `restore`, `recoverOrphans`, historial del espejo) pasa por él; temporales únicos `<huella>.1.<rand8>.tmp` (`SaveStore.uniqueBackupTmpName`); `recoverOrphans` borra `.1.tmp`, `.1.<rand>.tmp` y `wrong-size-*.sav.tmp` de esa huella. `SafSaveMirror.addBackup` usa el mismo `SaveStore` ⇒ el mismo lock | JVM `SaveConcurrencyTest.mirrorBackupInterleavedWithALocalSaveLosesNothing` (latch: el espejo se queda a mitad de `addBackup`, entra el guardado, nada se pierde: contenido externo, partida 1, 2 y nueva presentes), `manyInterleavedBackupsAndSavesNeverFailNorLeaveTemporaries` (2 hilos × 150), `recoverOrphansRemovesEveryTemporaryOfThatSaveButNotOthers`; `AtomicSaveFaultInjectionTest` (adaptado a los nombres únicos). **Falla sin la corrección: sí** (lock por instancia + temporal fijo ⇒ fallan las dos de concurrencia) |
| **Opus H1** contra `restore()` y la apertura | `restore` y `SaveOpening.prepare` mutan solo vía `SaveStore` (mismo lock) | Cubiertos por el mismo lock; no hay interfoliación distinta que probar (`restore` = `save`; ver «No verificado» sobre la apertura) |
| **Codex H1** (bloqueante): `close()` puede liberar el handle con el hilo vivo | `SaveCoordinator.shutdown()` marca el cierre bajo una compuerta (`sourceGate`) que protege cada `source.copy()`/`dirtySeq()`, deja terminar lo encolado, interrumpe pasado el plazo y comprueba `isAlive`; devuelve `CloseResult.Closed` o `SaveThreadStuck`. `GameSession.tryClose()` solo libera el handle si `Closed`; si no, un "reaper" (`pocketgb-save-reaper`) lo libera cuando el hilo sale (`awaitThreadExit`) | JVM `SaveCoordinatorCloseTest` (`shutdownReportsAStuckThreadAndNeverStartsAnotherNativeCopy`, `tasksQueuedBeforeCloseSeeTheClosedGateAndNeverCopy`, `anInterruptIgnoringWriteIsOnlyReleasedAfterTheThreadReallyExits`; operación de archivo que ignora interrupciones, `HandleSram` cuenta usos tras liberar = 0); instrumentada `GameSessionHardeningTest.theNativeHandleIsNeverReleasedWhileTheSaveThreadIsStillAlive` (handle vivo con `SaveThreadStuck`, `Closed` solo tras soltar). **Falla sin la corrección: sí** (`tasksQueuedBeforeClose…` sin la comprobación de cierre) |
| **Codex H2** (bloqueante) + **Opus H5**: rollback de `loadState` no transaccional | Tras `loadStateRaw(previous)` se encola una **barrera** (`flushSync`) en el mismo hilo, detrás de cualquier escritura rezagada; solo si se confirma se lanza `StateError.SaveFailed` («no ha cambiado»); con `TimedOut`/fallo se lanza `StateError.SavePending` («guardado pendiente», sin afirmar que no cambió) y se pide `requestFlush`. Si `loadStateRaw(previous)` falla: `coordinator.disablePersistence()` (nunca se escribe esa SRAM, se drena lo que estuviera en vuelo), el problema queda visible y se lanza `StateError.RollbackFailed` con mensaje propio; NO se llama a `requestFlush` | `GameSessionHardeningTest.aTimedOutStateLoadRollsBackAndTheDiskEndsAtThePreviousSaveBeforeAnyNextTick` (latch: timeout durante la escritura de B, rollback a A, se libera la tarea y solo se drena la cola — sin siguiente tick — ⇒ disco en A), `aConfirmedRollbackStillSaysTheGameDidNotChange`, `ifTheRollbackItselfFailsTheSessionStopsPersistingAndNeverWritesTheDoubtfulSram`; JVM `SaveCoordinatorCloseTest.persistenceDisabledNeverWritesAgain`. **Falla sin la corrección: sí** (volviendo a `requestFlush` sin barrera fallan `aTimedOut…` y `aConfirmedRollback…`) |
| **Opus H2** + **DeepSeek H1** + **Codex H3**: uso tras liberar y `onCleared` forzado | `EmulatorSession`: TODA llamada nativa con handle (`load`, `loadSram`, `saveState`, `loadStateRaw`, transiciones, `attach/detachSurface`, `setTouchButtons`, `setPhysicalButtons`, `setSpeed`, propiedades) bajo `handleLock.read`; `close()` toma el de escritura. `onCleared` ya no llama a `exit(force = true)`: pausa nativa y delega en `GameSession.rescueExit` (reintenta el vaciado; si lo confirma guarda AUTO y cierra; si no, escribe el estado de **rescate** y deja la sesión abierta y reintentando) en el hilo `pocketgb-rescue`, bajo el `Mutex` de operaciones (espera a la que esté en curso). `GameplayViewModel.onBackground` captura excepciones (`InvalidTransition`/`Closed`); `GameSession` serializa "mirar estado y pausar" (`transitions`) para que `ON_PAUSE` y una salida no se pisen. Anti-ANR: tras un `TimedOut` reciente (10 s) los vaciados del hilo principal esperan 500 ms en vez de 3 s | `EmulatorSessionHandleLockTest` (determinista: cada método espera al lock de escritura que sostiene el test; `close()` espera a un lector; entradas tardías inofensivas; martilleo 30 rondas), `GameplayViewModelDestroyTest` (`onCleared` con disco fallando: no bloquea <1 s, sesión abierta, estado de rescate, no usa AUTO y el guardado pendiente se confirma solo al volver el disco; con disco sano cierra fuera del hilo principal con AUTO; espera a una salida en curso), `GameSessionHardeningTest` (`rescueExitWithAFailingDisk…`, `backgroundingWhenTheNativePauseRacesNeverCrashes`, `backgroundingWhileExitingNeverCrashesAndEndsClosed`, `afterATimeoutTheMainThreadFlushBudgetShrinks`) |
| **Opus H3**: la apertura puede leer un espejo con escritura en vuelo | `SaveOpening.snapshotWhenIdle`: espera (5 s, fuera del hilo principal) a `MirrorChannel.awaitIdle` de esa huella antes de `snapshot()`; si no se vacía, `Unavailable` (nunca parcial). `GameLauncher` lo usa | JVM `MirrorChannelRobustnessTest.openingNeverReadsAMirrorWithAWriteInFlight` y `awaitIdleTimesOutWhileAWriteIsInFlightAndCleansItsCallback` |
| **Opus H4**: `saveProblem` nunca se muestra | `SaveProblemBanner` persistente en `GameplayHost` mientras `saveProblem != null`; `SessionLifecycleObserver` entrega el `FlushResult` y el ViewModel emite `SavePending` ante `Failed`/`TimedOut` en `ON_PAUSE`/`ON_STOP` | `GameplayUiTest.theSaveProblemIndicatorStaysVisibleWhileTheProblemExistsAndGoesAwayWhenConfirmed`, `aFailedFlushOnBackgroundingRaisesTheSavePendingNotice`; catálogo `save-problem` (captura) |
| **Opus H8**: `.sav` local > 1 MiB o ilegible bloquea la apertura | `SaveStore.inspectLocal()` (Ausente/Presente/Demasiado grande/Ilegible); `SaveOpening` lo trata como tamaño incorrecto (candidata inválida); `quarantineCurrent` copia en streaming (`SaveFileOps.copySynced`) sin cargar el archivo; un ilegible sin espejo avisa con `Unreadable`, nunca falla la apertura de forma permanente | JVM `SaveHardeningTest.anOversizeLocalSaveGoesToQuarantineInsteadOfAPermanentOpenError`, `anOversizeLocalSaveWithoutMirrorIsLeftUntouchedAndOpensWithoutSaving` |
| **Opus H7**: `restore(n)` no valida | `restore(n, validSizes)` rechaza con `InvalidBackupException` sin tocar nada; `SavesIndex` guarda `validSizes` por huella al abrir y `SavesBrowser` los usa; el escritor no rota a `.1` una actual de tamaño inválido (ya apartada) | `SaveHardeningTest.restoreRejectsABackupWithAnInvalidSizeAndTouchesNothing`, `restoreOverAnInvalidCurrentDoesNotRotateItIntoBackupOne`, `savesBrowserRestoreUsesTheSizesRecordedAtOpening` |
| **Opus H6**: el AUTO de rescate (J6) se sobrescribe en silencio | Nueva ranura `StateSlot.RESCUE`: `StateStore.saveRescue` aparta la anterior como `rescue-<fecha>-<rand>.state` en vez de pisarla; `exit(force)` y `rescueExit` escriben ahí (no en AUTO); aviso al abrir (`GameNotice.RescueStateExists`, texto en `strings.xml`) y fila «Rescate» en Estados (solo si existe; sin «Guardar») | JVM `SaveHardeningTest.aSecondRescueNeverOverwritesTheFirstOne`; instrumentadas `GameSessionTest.forcedExitAfterLocalFailureWritesARescueStateInItsOwnSlot`, `GameSessionHardeningTest.forcedExitsNeverOverwriteAnEarlierRescueState`, `GameplayUiTest.localSaveFailureDialog…`; catálogo `states-rescue` (captura) |
| **Opus H11** + **DeepSeek H2**: temporales huérfanos | `StateStore.recoverOrphans` (`*.state.tmp`, `*.png.tmp`), `SavesIndex.recoverOrphans` (`index.json.tmp`) y `SaveStore.recoverOrphans` ampliado (`wrong-size-*.sav.tmp`); el lanzador los llama al abrir | JVM `SaveHardeningTest.stateRecoverOrphansRemovesTmpStatesAndThumbnails`, `indexRecoverOrphansRemovesItsTmp`, `SaveConcurrencyTest.recoverOrphans…`; `save-verify` exige `stateTmpOrphans=0` tras recuperar |
| **Opus H12**: `drain` solo captura `Exception`; `idleCallbacks` sin límite | `MirrorChannel.drain` captura `Throwable` (libera el canal y los que esperan; relanza el `Error`); `SaveCoordinator.watchMirror` solo mantiene una espera a la vez | JVM `MirrorChannelRobustnessTest.anErrorInTheWriterDoesNotLeaveTheChannelBusyForever`, `SaveCoordinatorCloseTest.aBlockedMirrorNeverPilesUpIdleCallbacks` |
| **Codex H5**: `syncDirectory` oculta cualquier `IOException` | `DirectorySync`: tolera solo no soportado (`EINVAL`, `EROFS`, `ENOTSUP`, `ENOSYS`, `AccessDenied`…) con `java.util.logging` informativo; cualquier otro fallo se registra (`warning`) y se propaga como `IOException` (fallo de durabilidad: el guardado no se da por confirmado y el reintento converge) | JVM `SaveHardeningTest.syncDirectoryToleratesOnlyDocumentedUnsupportedErrors`, `aRealIoErrorOnDirectorySyncIsPropagatedAsADurabilityFailure` (incluye un fallo real sin inyección: directorio inexistente), `aDirectorySyncFailureMakesTheSaveFailButTheNextTryConverges` |
| **Opus H9**: `SaveCyclesTest` esconde fallos con reintentos | Sin reintentos: un `Failed` falla el ciclo; `pause()` no se repite. Única excepción acotada y medida: un `TimedOut` (plazo de 3 s agotado por presión del emulador) se cuenta, exige problema visible y que el guardado en segundo plano se confirme **solo**, con presupuesto de 5 en 200 operaciones | `SaveCyclesTest` (corrida final: `plazos-agotados=0/5`, 100/100 guardados, sin reintentos) |
| **Opus H10**: `save-verify` no detecta regresiones | ROM contador de **16 bits** (`DebugSyntheticRom.sramCounter16`, da la vuelta cada ~18 min); `save-stress` anota el último contador confirmado por un vaciado correcto (`SAVE-STRESS CONFIRMED n` en logcat y `stress-confirmed.txt`); `save-verify` exige `sav >= confirmado` (módulo 65 536 con ventana de media vuelta); el script falla si `ready < iteraciones` o si `stateTmpOrphans != 0` (se cuenta tras `StateStore.recoverOrphans`, como al abrir) | `tools/android-save-kill-test.sh 50` (abajo) |
| **Codex H4** (media): evidencia sin SHA ni I3 matizado | Commit base `b8b91bb` en la cabecera, afirmaciones de I3 y de `onCleared`/J6 marcadas «corregido», esta sección | este documento |

### Comprobación «falla sin la corrección» (reversión temporal)

| Qué se revirtió | Pruebas que fallaron | Resultado |
|---|---|---|
| Opus H1: lock por instancia (sin compartir) y temporal fijo `<huella>.1.tmp` | `SaveConcurrencyTest.mirrorBackupInterleavedWithALocalSaveLosesNothing` (se pierde un contenido) y `manyInterleavedBackupsAndSavesNeverFailNorLeaveTemporaries` | 2 de 3 fallan |
| Codex H1: `withSource` sin comprobar el cierre | `SaveCoordinatorCloseTest.tasksQueuedBeforeCloseSeeTheClosedGateAndNeverCopy` (una copia nativa tras cerrar) | 1 de 6 falla |
| Codex H2/Opus H5: sin barrera (comportamiento de `b8b91bb`: `requestFlush` + «no ha cambiado») | `GameSessionHardeningTest.aTimedOutStateLoadRollsBackAndTheDiskEndsAtThePreviousSaveBeforeAnyNextTick` (el disco queda en la SRAM del estado rechazado) y `aConfirmedRollbackStillSaysTheGameDidNotChange` (el problema de guardado no se resuelve) | 2 de 9 fallan |

Tras cada reversión se restauró el código (copia exacta) y se volvió a pasar la batería. El resto de las correcciones se verificó con pruebas escritas para el comportamiento nuevo (no se revirtió cada una).

### Verificación fresca de la corrida de correcciones

Núcleo (sin tocar `core/`): `make -C core test && make -C core asan` → `65/68 PASS · requeridos: 65/65 PASS · HITO=M1` y `OK: todos los casos requeridos en PASS` (los dos).

```bash
cd android
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew clean :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado: `BUILD SUCCESSFUL in 3m 45s`. Lint: **0 errores, 15 avisos** (los mismos de `b8b91bb`). Recuentos reales (de los XML de resultados):

**JVM: 233 tests, 0 fallos** (`b8b91bb`: 210): `ManifestPolicyTest` 6, `AppNavigationStateTest` 5, `ControlGeometryTest` 3, `TouchInputEngineTest` 2, `LibraryModelsTest` 3, `LibraryPreferencesTest` 15, `LibraryScannerTest` 14, `LibraryViewModelTest` 25, `AtomicFileTest` 12, `AtomicSaveFaultInjectionTest` 5, `FrameThumbnailTest` 4, **`MirrorChannelRobustnessTest` 3**, `MirrorChannelTest` 13, `ProcessKillTest` 1, **`SaveConcurrencyTest` 3**, **`SaveCoordinatorCloseTest` 6**, `SaveCoordinatorTest` 15, **`SaveHardeningTest` 11**, `SaveOpeningTest` 19, `SaveResolutionTest` 21, `SaveStoreTest` 19, `SavesBrowserTest` 4, `SavesIndexTest` 3, `SramFlushPolicyTest` 7, `StateStoreTest` 9, `AppearancePreferencesTest` 3, `IntegerViewportTest` 2.

**Instrumentados: 181 tests, 0 fallos** (`b8b91bb`: 164): `AppShellTest` 2, `DebugCatalogTest` 8, `SessionLifecycleObserverTest` 2, `NativeAudioTest` 3, `CoreBridgeTest` 4, **`EmulatorSessionHandleLockTest` 3**, `EmulatorSessionTest` 7, `NativeLibraryTest` 1, `NativeSaveBridgeTest` 19, **`GameSessionHardeningTest` 9**, `GameSessionTest` 13, `GameplayLifecycleTest` 3, `GameplayUiTest` 8 (+2), **`GameplayViewModelDestroyTest` 3**, `OpenFromLibraryTest` 8, `SaveCyclesTest` 1, `GameControlsViewTest` 2, `SafLibraryTest` 14, `SafMirrorTest` 34, `SaveFilesOnAndroidTest` 2, `LibraryUiTest` 32, `SavesSettingsUiTest` 2, `GameSurfaceTest` 1.

`./gradlew :app:testDebugUnitTest --rerun-tasks` ×3: `BUILD SUCCESSFUL` las tres veces (≈1 min cada una), 233 tests y 0 fallos en cada una.

`SaveCyclesTest` (100 ciclos, **sin reintentos**), logcat `A5Metrics`: `ciclos=100 guardados=100 plazos-agotados=0/5 flushSync n=200 p50=2.4 ms p99=18.2 ms max=31.8 ms | game.pause() p50=18.7 ms p99=23.4 ms max=35.4 ms`.

`tools/android-save-kill-test.sh 50` (contador de 16 bits, `sav >= confirmado`, `stateTmpOrphans=0`, `ready == iteraciones`):

```
iter  50  kill=kill9      tras 1491 ms  -> OK bytes=8192 counter=3511 confirmed=3511 backups=5 stateTmpFound=0 stateTmpOrphans=0
Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
```

Capturas nuevas revisadas con `Read` (claro y oscuro): `save-problem` (indicador persistente «Guardado pendiente…» arriba y aviso breve abajo; contraste correcto en ambos temas) y `states-rescue` (fila «Rescate» con Cargar/Eliminar y sin Guardar, y el aviso de rescate); `exit-risk` (texto nuevo de la doble confirmación) sin solapes. Release: `aapt dump permissions` solo `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (0 `INTERNET`); 0 coincidencias en los DEX de Release de `DebugCatalog`, `TestDocumentsProvider`, `save-stress`, `save-verify`, `SaveStress`, `GameplayTestActivity`, `GameplayDebugScreen`, `NativeVideoScreen`, `sramCounter16` y `DebugSyntheticRom`. `git status`: solo código, scripts y docs (ningún `.sav`, `.state`, ROM ni APK).

### Cómo se obtuvo la corrida limpia (honestidad sobre el entorno)

El Mac estuvo con la memoria saturada (swap 14–20 GB de 21 GB, compresor > 6 GB; apps de Joel abiertas). Con el emulador con ventana una corrida parcial tardó 32 min para 44 tests (tests de UI de 80–260 s) y dio 5 fallos ajenos al código (`ComposeNotIdleException`, `TimeoutException` de `runOnSaveThread`, un `TimedOut` real de `pause()`), y la batería completa avanzaba a ~1 test cada 5 min. Se paró Gradle y el Kotlin daemon, se reinició el emulador **sin ventana** (`-no-window`, mismas banderas `-gpu swiftshader_indirect -memory 3072`), con las animaciones del emulador a 0 (`settings put global *_scale 0`, solo en el emulador) y se lanzó Gradle con `--no-daemon -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process`. Así la batería completa pasó en 3m45s. Cambios en pruebas que dependían del anfitrión: `ProcessKillTest` pasó de exigir > 100 guardados completados entre las muertes a > 40 (la propiedad real, el invariante tras 200 SIGKILL, no cambió; con la memoria saturada el JVM hijo completó 92); `GameplayUiTest` espera hasta 30 s a que desaparezca un indicador transitorio antes de provocar su fallo; las pruebas del rollback con plazo usan 1,5 s de plazo para los pasos previos.

### Lo que NO está verificado tras las correcciones

- **2ª vuelta de auditoría** (Codex/Opus) sobre el commit que contenga estas correcciones; **prueba manual de Joel** en el teléfono (carpeta real, Rojo/Amarillo, cierre forzado real J11, recuperación desde el espejo, restaurar) siguen pendientes.
- **Opus H1 contra la apertura y `restore()`**: no hay una prueba de interfoliación específica de `SaveOpening.prepare` ni de `restore`; ambos pasan por los mismos métodos de `SaveStore` bajo el mismo lock, que es lo que prueban las dos pruebas de concurrencia. Una interfoliación entre los pasos de `prepare` (cada paso es atómico, el conjunto no) no se pierde datos pero no está probada con latches.
- **`rescueExit` con `KeptOpen`** (corregido en la 2ª vuelta, A5V2-H1): `OrphanSessionRegistry` hereda la sesión, reintenta y la cierra al confirmarse el guardado; ver «Correcciones de la 2ª vuelta».
- **Rollback fallido y reapertura** (corregido en la 2ª vuelta, A5V2-H3): la partida anterior se repone como principal y la reapertura se bloquea hasta entonces; ver «Correcciones de la 2ª vuelta».
- **Anti-ANR**: el plazo corto (500 ms) tras un `TimedOut` está medido solo en prueba con un disco atascado inyectado; no hay medida de ANR en un teléfono. `ON_PAUSE` sigue esperando hasta 3 s en el hilo principal la primera vez (decisión J5).
- **`DirectorySync` en Android real**: la tolerancia por mensaje de errno (`Invalid argument`, etc.) se probó en JVM y con el sistema de archivos del emulador (`SaveFilesOnAndroidTest`); no se sabe qué mensajes dan otros sistemas de archivos de teléfonos reales.
- Cierres forzados en el teléfono (J11), proveedores SAF reales y nube, TalkBack y fuente al 200 % (como antes).

## Correcciones de la 2ª vuelta de auditoría

Informes: `A5-android-codex-v2.md` (RECHAZAR: A5V2-H1 y H3 bloqueantes, H2 alta, H4 baja) y `A5-android-deepseek-v2.md`. Árbol auditado: **`acfe806`**; las correcciones quedaron en el commit **`ffd6d97`** (corrida del 2026-10-05/06; auditadas en la 3ª vuelta). Implementadas con TDD; cada prueba se comprobó **revirtiendo temporalmente la corrección** (tabla siguiente).

### Hallazgo → corrección → prueba

| Hallazgo | Corrección | Prueba que lo cubre |
|---|---|---|
| **A5V2-H1** (bloqueante): una sesión que acaba `KeptOpen` queda sin dueño, nunca se cierra y puede pisar una restauración o una sesión nueva | `OrphanSessionRegistry` (a nivel de app, `OrphanSessionRegistry.shared`) hereda la sesión. `GameplayViewModel.onCleared` hace `claim` SÍNCRONO (la huella entraba en `BlockedFingerprints` antes de lanzar el rescate; **sustituido en la 3ª vuelta por `FingerprintOwnership`**, ver más abajo) y tras el rescate `settle`: `Closed` ⇒ libera la huella (esperando la salida real del hilo de guardado si quedó `SaveThreadStuck`); `KeptOpen` ⇒ reintenta `rescueExit(1 intento, sin reescribir el estado de rescate)` con backoff exponencial 1 s…30 s en un `ScheduledThreadPoolExecutor` de un hilo que muere solo tras 2 s inactivo (sin fugas). Al confirmarse guarda AUTO, cierra y libera. `GameLauncher.openBlocking` rechaza con `OpenError.SavePending` (antes de tocar ningún archivo) y `SavesBrowser.restore` con `SavePendingException` («Guardado pendiente de esa partida…») mientras la huella esté bloqueada | `GameplayViewModelDestroyTest.onClearedWithAFailingDiskHandsTheSessionToTheRegistryWhichBlocksThenClosesItWhenTheDiskRecovers` (producción pura, sin segunda llamada: `onCleared` → KeptOpen → reabrir da `OpenFailed(SavePending)` y restaurar `SavePendingException` → vuelve el disco → se cierra sola con AUTO y SRAM confirmada → huella libre, 0 hilos del registro → ya se reabre y restaura); `GameSessionHardeningTest.theOrphanRegistryClosesAKeptOpenSessionByItselfWhenTheDiskRecoversAndReleasesTheFingerprint` (nivel sesión+registro) y `rescueExitWithAFailingDiskKeepsTheSessionOpen…RetriesDoNotPileUpRescues`; JVM `SavesBrowserTest.restoreIsRefusedWhileAnOrphanSessionStillHasItsSavePending`, `blockedFingerprintsAreCountedPerOwner`. **Falla sin la corrección: sí** |
| **A5V2-H2** (alta): carrera TOCTOU entre «canal en reposo» y `snapshot()` | `MirrorChannel.withIdleLease(timeoutMs, block)`: espera el reposo y toma el lease en el MISMO instante bajo el lock del canal; mientras dura, `enqueue`/`retryIfNeeded` conservan la petición (coalescida) pero NO arrancan el trabajador, que arranca al soltar el lease (también si `block` lanza). `SaveOpening.snapshotWhenIdle` lee dentro del lease; se mantiene el plazo de 5 s y el fallback a `Unavailable` | JVM `MirrorChannelRobustnessTest.aWriteEnqueuedRightAfterIdleCannotStartWhileTheSnapshotIsBeingRead` (la escritura se encola DENTRO de `snapshot()`, justo tras el reposo; 300 ms de margen; el snapshot es el contenido previo completo, ningún escritor empezó durante la lectura y corre después), `theLeaseIsReleasedEvenIfTheReadThrowsAndHeldWritesStillRun`; siguen `openingNeverReadsAMirrorWithAWriteInFlight` y `awaitIdleTimesOut…`. **Falla sin la corrección: sí** (con `awaitIdle` + `snapshot()` falla la primera) |
| **A5V2-H3** (bloqueante): rollback fallido con escritura B rezagada que acaba como `.sav` principal | `loadState` toma una copia INDEPENDIENTE en memoria de la SRAM (`sramBefore`) antes de cargar el estado. Si `loadStateRaw(previous)` falla (o el flush no se confirmó): `disablePersistence` y `repairPrevious`: bloquea la huella y encola `persistLocal(sramBefore)` en el MISMO hilo de guardado (FIFO, detrás de B) con el escritor atómico ⇒ A principal y B backup (y A al espejo). Espera `repairWaitMs` (3 s); si no, la reparación continúa en el hilo `pocketgb-save-repair` (backoff 0,2…5 s; si el hilo de guardado se cierra sin ejecutarla, espera su salida real y escribe directamente) y la huella sigue bloqueada hasta lograrlo. `StateError.RollbackFailed.restored` solo afirma «la partida guardada sigue intacta» si quedó confirmado; si no, dice que se está restaurando y que no se podrá abrir aún | `GameSessionHardeningTest.aFailedRollbackWithALateWriteEndsWithThePreviousSaveAsPrimaryAndBlocksReopeningUntilThen` (latch `StallingOps`: B en vuelo, falla `loadStateRaw(previous)`, plazo vencido ⇒ `restored=false`, mensaje sin «intacta», huella bloqueada y `openBlocking` ⇒ `SavePending`; se libera B ⇒ la reparación libera la huella, el principal es A, B queda en backups, se reabre); `ifTheRollbackItselfFailsTheSessionStopsPersisting…` (adaptado, sigue verde). **Falla sin la corrección: sí** (dos reversiones, ver abajo) |
| **A5V2-H4** (baja): evidencia y estado con «sin commitear» sobre `b8b91bb` | Cabecera y esta sección con el SHA `acfe806`; las correcciones de esta vuelta son el commit `ffd6d97` (la 3ª vuelta de Codex, A5V3-H2, detectó que seguían diciendo «sin commitear» y se corrigió en la 3ª vuelta) | este documento y `docs/ESTADO.md` |

### Comprobación «falla sin la corrección» (reversión temporal)

| Qué se revirtió | Prueba que falló | Resultado |
|---|---|---|
| H2: `snapshotWhenIdle` = `awaitIdle` + `snapshot()` (código de `acfe806`) | `MirrorChannelRobustnessTest.aWriteEnqueuedRightAfterIdleCannotStartWhileTheSnapshotIsBeingRead` | 1 de 5 falla; restaurado ⇒ 5/5 |
| H1: `settle(KeptOpen)` sin reintentos (sesión huérfana sin dueño) | `GameSessionHardeningTest.theOrphanRegistryCloses…` («el registro la cierra solo») y el build de `GameplayViewModelDestroyTest` falla (misma causa) | falla |
| H3 (a): `repairPrevious` omitido (`confirmed = true`, comportamiento de `acfe806`) | `aFailedRollbackWithALateWriteEndsWith…` («no estaba confirmado») | falla |
| H3 (b): reparación sin escribir A (`persistLocal` vaciado, pero con bloqueo y mensaje) | misma prueba: «el principal es A, no B: arrays first differed» (B queda como principal) | falla |

Tras cada reversión se restauró el código (copia exacta de `/private/tmp/…/bk`) y se volvió a pasar la batería.

### Verificación fresca de la corrida de la 2ª vuelta

Núcleo (sin tocar `core/`): `make -C core test` y `make -C core asan` → `65/68 PASS · requeridos: 65/65 PASS · HITO=M1` y `OK: todos los casos requeridos en PASS` (los dos).

Emulador `Small_Phone_API_35` SIN ventana (`-no-window -gpu swiftshader_indirect -memory 3072`, animaciones a 0), Gradle `--no-daemon -Xmx1536m`:

```bash
cd android
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --no-daemon clean :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado: `BUILD SUCCESSFUL in 3m 36s`. Lint: **0 errores, 15 avisos** (los mismos). Recuentos reales (de los XML): **JVM 237 tests, 0 fallos/errores/omitidos** (`acfe806`: 233; +2 `MirrorChannelRobustnessTest`, +2 `SavesBrowserTest`); **instrumentados 183 tests, 0 fallos/errores/omitidos** (`acfe806`: 181; `GameSessionHardeningTest` 11 (+2), `GameplayViewModelDestroyTest` 3, resto igual). Los instrumentados se ejecutaron de verdad: el XML de `connected/debug` lista los 183 `testcase` (p. ej. `aFailedRollbackWithALateWrite…`, `theOrphanRegistryCloses…`).

`./gradlew :app:testDebugUnitTest --rerun-tasks` ×3: `BUILD SUCCESSFUL` las tres, 237 tests y 0 fallos en cada una (≈51–57 s).

`tools/android-save-kill-test.sh 50`: `Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)` / `OK: 50/50 iteraciones con el invariante intacto`.

Release: `aapt dump permissions` solo `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (0 `INTERNET`); 0 coincidencias en los DEX de Release de `DebugCatalog`, `TestDocumentsProvider`, `save-stress`, `save-verify`, `SaveStress`, `GameplayTestActivity`, `GameplayDebugScreen`, `NativeVideoScreen`, `sramCounter16` y `DebugSyntheticRom`. `git status`: solo código y docs (ningún `.sav`, `.state`, ROM ni APK).

### Lo que NO está verificado tras las correcciones de la 2ª vuelta

- **3ª vuelta de auditoría** y **prueba manual de Joel en el teléfono** (J11) siguen pendientes.
- **Muerte del proceso con una sesión huérfana**: el registro vive en el proceso; si éste muere antes de que vuelva el disco solo queda el estado de rescate (probado que se escribe) y el `.sav` anterior; al relanzar, el bloqueo ya no existe (correcto: no hay sesión que escriba).
- **`SaveThreadStuck` en el registro y reparación tras cierre forzado del hilo**: la espera a la salida real del hilo (`awaitSaveThreadExit`) y la escritura directa de la reparación cuando la cola se cerró no tienen una prueba propia con hilo atascado; la rama principal (cola FIFO detrás de B) sí.
- **Reparación que falla en bucle** (disco que nunca vuelve): reintenta con backoff acotado indefinidamente mientras viva el proceso y la huella sigue bloqueada; solo se probó el caso en que el disco responde.
- **Interfoliación de dos `withIdleLease` simultáneos** (dos aperturas de la misma huella): se serializan por el mismo lock/callbacks pero no hay una prueba con dos hilos.
- **Mensaje de `SavePending` en la UI** (`open_error_save_pending`, texto de Ajustes › Partidas): cubierto a nivel de modelo; sin captura visual nueva.

## Correcciones de la 3ª vuelta de auditoría

Informes: `A5-android-codex-v3.md` (RECHAZAR: A5V3-H1 bloqueante, A5V3-H2 baja) y `A5-android-deepseek-v3.md` (APROBAR CON CAMBIOS: H1 media, el reaper). Árbol auditado: **`ffd6d97`**; las correcciones están en el commit que sigue a `ffd6d97` (corrida del 2026-10-06). Cada prueba clave se comprobó **revirtiendo temporalmente la corrección**.

### Diseño: propiedad exclusiva atómica por huella

`saves/FingerprintOwnership.kt` sustituye a `BlockedFingerprints` (contador + `isBlocked`, que permitía «comprobar y luego actuar»). `tryAcquire(fingerprint, owner): Lease?` comprueba y adquiere bajo el mismo monitor (como máximo un `Lease` vivo por huella); `Lease.close()` es idempotente y un `close` viejo nunca suelta a un dueño nuevo.

- `GameLauncher.openBlocking` adquiere el lease justo después de leer la cabecera (huella conocida) y ANTES de tocar ningún archivo de `saves/`/`states/`; si ya hay dueño ⇒ `OpenError.SavePending`; si la apertura falla o lanza, lo libera en `finally`; si tiene éxito se lo entrega a `GameSession`.
- `GameSession` posee el lease TODA su vida (contador de usos: 1 la sesión + 1 por reparación pendiente). Lo suelta cuando `tryClose` terminó de verdad (hilo de guardado terminado) y no queda reparación; con el hilo atascado lo suelta el hilo `pocketgb-save-reaper` en `finally`, también ante `InterruptedException` o error. `repairPrevious` ya no adquiere nada: retiene el mismo token hasta confirmar.
- `OrphanSessionRegistry`: `onCleared`/orfandad TRANSFIEREN la sesión (con su mismo lease) al registro; no hay liberar-y-readquirir. Desaparece el hilo `pocketgb-orphan-reaper` (la liberación es de la sesión).
- `SavesBrowser.restore` usa `withExclusive`: el permiso envuelve comprobación y mutación y se libera en `finally`; con dueño ⇒ `SavePendingException`.
- `GameplayViewModel.open`: si `recordPlayed` lanza tras abrir, cierra la sesión (antes la dejaba abierta con su huella).

### Hallazgo → corrección → prueba

| Hallazgo | Corrección | Prueba que lo cubre |
|---|---|---|
| **A5V3-H1** (bloqueante), restore vs `onCleared`/`repairPrevious` entre la comprobación y la mutación | `SavesBrowser.restore` bajo `withExclusive` | JVM `SavesBrowserTest.aRestoreInFlightCannotBeOvertakenByAnotherOwnerBetweenCheckAndMutation` (latch DENTRO de la mutación: `tryAcquire` ha de dar `null`; al terminar, libre), `anOwnerThatAcquiredFirstMakesRestoreFailWithoutTouchingFiles`, `restoreReleasesItsPermitEvenWhenItFails`, `restoreIsRefusedWhileTheFingerprintHasAnOwner` |
| **A5V3-H1**, dos aperturas simultáneas de la misma huella | Lease adquirido por el lanzador antes de tocar archivos y conservado toda la sesión | Instrumentado `FingerprintOwnershipLauncherTest.twoSimultaneousOpensOfTheSameFingerprintOnlyOneWins` (la 1ª se detiene en el localizador del espejo con el lease ya tomado; la 2ª ⇒ `SavePending`; la ganadora conserva el lease; tras cerrar se puede abrir) |
| **A5V3-H1**, transferencia `onCleared` → registro | La sesión conserva el mismo token | `GameplayViewModelDestroyTest.onClearedWithAFailingDisk…`: un hilo intruso llama a `tryAcquire` sin parar desde antes de `onCleared` hasta el cierre (>100 intentos); 0 adquisiciones con la sesión abierta |
| **A5V3-H1**, lease liberado al cerrar / fallar / lanzar | `finally` en lanzador, sesión y ViewModel | `FingerprintOwnershipLauncherTest.theLeaseIsReleasedWhenTheOpenFailsOrThrows` (excepción inesperada y `Refusal`), `aViewModelThatFailsAfterOpeningStillReleasesTheLease`, `GameSessionHardeningTest.theOrphanRegistryCloses…` (se libera tras el cierre por el registro), `aFailedRollbackWithALateWrite…` (se conserva hasta `close()`) |
| **A5V3-H1**, reparación pendiente tras cerrar la sesión | Contador de usos del lease en `GameSession` | `GameSessionHardeningTest.theLeaseOutlivesTheSessionCloseUntilThePendingRepairFinishes` |
| **DeepSeek H1** (reaper sin liberar al ser interrumpido) | El reaper suelta el lease en `finally` | `GameSessionHardeningTest.theSaveReaperReleasesTheLeaseEvenIfItIsInterrupted` (interrumpe `pocketgb-save-reaper` con el hilo de guardado atascado) |
| **A5V3-H2** (baja): documentación dependiente del estado de commit | Redacción sin «sin commitear»; commits `acfe806`/`ffd6d97` correctos | este documento y `docs/ESTADO.md` |

### Comprobación «falla sin la corrección» (reversión temporal)

| Qué se revirtió | Prueba que falló | Resultado |
|---|---|---|
| `restore`: `isOwned` + mutación sin lease (check+acción) | `SavesBrowserTest.aRestoreInFlightCannotBeOvertakenByAnotherOwnerBetweenCheckAndMutation` | 1 de 10 falla (`tryAcquire` tenía éxito en plena restauración); restaurado ⇒ 10/10 |
| Lanzador: `isOwned` al principio y `tryAcquire` solo al construir la sesión | `FingerprintOwnershipLauncherTest.twoSimultaneousOpens…` («esperaba SavePending y abrió») y `theLeaseIsReleasedWhenTheOpenFailsOrThrows` («durante la apertura tenía dueño») | 2 de 3 fallan; restaurado ⇒ 3/3 |

Las demás pruebas de esta vuelta (transferencia, reaper, lease tras cierre con reparación, ViewModel) se escribieron para el comportamiento nuevo y no se revirtió cada una. Tras cada reversión se restauró el código (copia exacta).

### Verificación fresca de la corrida de la 3ª vuelta

Núcleo (sin tocar `core/`): `make -C core test` y `make -C core asan` → `65/68 PASS · requeridos: 65/65 PASS · HITO=M1` y `OK: todos los casos requeridos en PASS` (los dos).

Emulador `Small_Phone_API_35` SIN ventana (`-no-window -gpu swiftshader_indirect -memory 3072`, animaciones a 0), Gradle `--no-daemon -Xmx1536m --max-workers=1`:

```bash
cd android
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --no-daemon --max-workers=1 clean :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado: `BUILD SUCCESSFUL in 4m 55s`. Lint: **0 errores, 15 avisos** (los mismos). Recuentos reales (de los XML): **JVM 241 tests, 0 fallos/errores** (`ffd6d97`: 237; `SavesBrowserTest` 6 → 10); **instrumentados 188 tests, 0 fallos/errores/omitidos** (`ffd6d97`: 183; +3 `FingerprintOwnershipLauncherTest`, +2 `GameSessionHardeningTest`). El XML de `connected/debug` lista los 188 `testcase`, incluidos los nuevos.

`./gradlew :app:testDebugUnitTest --rerun-tasks` ×3 (emulador apagado): `BUILD SUCCESSFUL` las tres, 241 tests y 0 fallos (≈48 s).

`tools/android-save-kill-test.sh 50`: `Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)` / `OK: 50/50 iteraciones con el invariante intacto` (tras `installDebug`).

Release: `aapt2 dump permissions` solo `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (0 `INTERNET`); 0 coincidencias en los DEX de Release de `DebugCatalog`, `TestDocumentsProvider`, `save-stress`, `save-verify`, `SaveStress`, `GameplayTestActivity`, `GameplayDebugScreen`, `NativeVideoScreen`. `git status`: solo código y docs (ningún `.sav`, `.state`, ROM ni APK).

### Incidencia de entorno (honestidad)

Con `--max-workers` por defecto, en dos corridas completas seguidas (con el emulador y la compilación/lint en paralelo y el Mac con carga ~27–38) falló `ProcessKillTest.killedWriterNeverLeavesAPartialOrMissingSave` por su umbral de significatividad («debía completarse algún guardado en total (16)», umbral > 40): depende de la carga del anfitrión (ya documentado arriba), no del invariante, y no toca código de esta vuelta. Pasa sola (`--rerun-tasks` ×5) y la corrida completa con `--max-workers=1` pasó entera.

### Lo que NO está verificado tras las correcciones de la 3ª vuelta

- **Auditoría de la 4ª vuelta** y **prueba manual de Joel en el teléfono** (J11).
- Un reaper interrumpido suelta el lease aunque el hilo de guardado siguiera atascado (así lo pide DeepSeek H1; en producción solo ocurre al morir el proceso, donde ya no hay sesión que escriba). No se probó una escritura tardía posterior a esa liberación.
- Reversión no aplicada a las pruebas de transferencia, reaper, lease con reparación y ViewModel (solo se escribieron en positivo).
- `SaveThreadStuck` con la sesión huérfana en el registro y la reparación tras cierre forzado del hilo siguen sin prueba propia de extremo a extremo (heredado de la 2ª vuelta).

## Decisiones de Joel (J1–J11) y dónde quedaron

| # | Estado en A5 |
|---|---|
| J1 espejo in situ `"wt"` + verificación | etapa 5 (`SafSaveMirror`, `SafMirrorTest`); usado por el lanzador real |
| J2 el `.sav` cambia por fuera durante la partida ⇒ copia, dejar de espejar, avisar | `SafSaveMirror` + `GameEvent.MirrorDisabled` → snackbar; sin prueba instrumentada del texto del snackbar |
| J3 reglas de backup (`saves/` sí; `saves/backups/` y `states/` fuera de la nube) | etapa 1 (`ManifestPolicyTest`) |
| J4 restaurar backups en A5 | Ajustes › Partidas (`SavesBrowser`, `SavesScreen`) |
| J5 flush de `ON_PAUSE` ≤ 3 s; si se agota, pendiente, reintento en `ON_STOP`/al volver, fallo si se intenta salir | `SaveCoordinator`, `SessionLifecycleObserver`, `GameSession.exit` |
| J6 «salir sin guardar»: AUTO de rescate + doble confirmación | `GameSession.exit(force)`, `ExitSaveFailedDialog` |
| J7 carpeta de solo lectura ⇒ importar, nunca escribir, avisar | `MirrorMode.ReadOnly` (`OpenFromLibraryTest`) |
| J8 «Continuar» no carga el AUTO | `GameDetailsContent` |
| J9 espejo también en proveedores en la nube; sin fecha gana la local; lectura fallida sin local impide abrir | etapa 3/5 + `OpenError.MirrorNotDownloaded` |
| J10 `.sav` de tamaño incorrecto como única copia ⇒ se abre sin guardar, con aviso | `OpenFromLibraryTest.aSavWithTheWrongSizeOpensWithoutPersistingAnything` |
| J11 cierre forzado en emulador (50 iteraciones) **y en el teléfono** | emulador hecho (50/50); **teléfono pendiente** |

## Desviaciones

1. Las pruebas «Jugar habilitado solo si jugable» están en `LibraryUiTest` (arnés Compose del detalle), no en `GameplayUiTest`; `GameplayUiTest` cubre atrás, menú, estados y fallo local.
2. `GameplayTestActivity` vive en `src/debug` (con `src/debug/AndroidManifest.xml`) y no en el APK de test: una actividad declarada en el APK de test resuelve al proceso del test («Intent … resolved to different process»). No existe en Release (verificado).
3. Las operaciones de estados y la salida (`saveState`/`loadState`/`deleteState`/`exit`) corren en `Dispatchers.IO` bajo un `Mutex`, no en el hilo principal, para no bloquear la UI; la sesión está aparcada en pausa todo ese tiempo, las transiciones (`start`/`pause`/`resume`) siguen en el principal y el mutex nativo protege el handle. Lo único que se ajusta de I1 es «solo en el principal» para esas llamadas, que ya exigían sesión aparcada.
4. `EmulatorSession.setTouchButtons/setPhysicalButtons` ignoran la llamada tras cerrar la sesión (antes lanzaban `CoreError.Closed`): la salida cierra antes de que Compose retire la vista y un toque tardío podía tumbar la app. Test: `GameSessionTest.touchInputAfterCloseIsIgnored`.
5. `SaveCoordinator.close()` une el hilo con `join` explícito: `awaitTermination` vuelve cuando el ejecutor termina, no cuando el hilo sale de `run()`, y `SaveCyclesTest` lo detectó bajo carga («hilos de guardado esperado 0 pero 1»).
6. Rotar (o cualquier recreación de la actividad) pausa el juego y abre el menú de pausa al volver, porque `ON_PAUSE`/`ON_STOP` lo pausan. La sesión no se cierra (`GameplayLifecycleTest.recreateDoesNotCloseTheSession`); evitar la pausa en rotación queda para A7.
7. `save-stress` usa un debounce de 20 ms (no el de 1 s de producción): un juego que escribe la SRAM en cada fotograma nunca llega a un segundo de calma, y la prueba quería muchas escrituras con rotación de backups. Las pausas/reanudaciones aleatorias añaden vaciados síncronos.
8. «Cargar» un estado pide confirmación también para AUTO (el progreso actual se pierde); no estaba pedido explícitamente.
9. No se añadió el aviso de «Cabecera dañada» (checksum) al abrir (existe en iOS); queda para A6.
10. ~~`SaveCyclesTest` reintenta un `TimedOut` de `pause()` y un `LocalSaveFailed` de `exit()`.~~ *(Corregido en la 1ª vuelta, Opus H9: ya no hay reintentos; ver abajo.)*

## Incidencias de entorno (honestidad sobre la evidencia)

Durante la jornada el Mac estuvo bajo fuerte presión de memoria (carga media 10–60, compresor de memoria > 6 GB, otras apps y simuladores iOS de Joel abiertos). En una corrida completa con ese estado fallaron 20 tests por tiempos de espera ajenos al código (`NativeAudioTest`, `NativeSaveBridgeTest`, `EmulatorSessionTest`, `AppShellTest`, varios de `GameSessionTest`/`GameplayUiTest`: «La sesión no avanzó antes del timeout», `ComposeNotIdleException`, hilo nativo sin CPU). Se reinició el emulador (y se paró el daemon de Gradle) y la misma batería pasó **164/164**; las otras dos corridas intermedias fallaron solo en `SaveCyclesTest` (un congelado de ~50 s del emulador y un `TimedOut` real de 3 s en `pause()`, ver desviación 10). Lo que se pega arriba es la corrida limpia final (`BUILD SUCCESSFUL in 3m 47s`). Los tests propios de A5 aguantan cargas moderadas (plazos de 20–45 s en esperas activas); el plazo de producción (3 s) es deliberadamente corto.

## Lo que NO se ha verificado

- **Prueba manual de Joel con una carpeta real** (SAF de un proveedor real, p. ej. almacenamiento interno, tarjeta, nube): permisos persistentes, espejo `.sav` junto a la ROM, `Shared`/`ReadOnly` reales.
- **Pokémon Rojo y Amarillo** (ROMs de Joel): ninguna prueba usa ROMs comerciales; todo es sintético (contador MBC1, MBC3+RTC).
- **Cierre forzado real en el teléfono** (J11): solo se hizo en emulador (50/50). Faltan: matar desde el selector de apps, batería baja, memoria baja.
- **Recuperación desde el espejo real** (borrar los datos de la app o reinstalar y reabrir con un `.sav` junto a la ROM) y la coexistencia con la copia de seguridad de Android.
- **Restaurar un backup** desde Ajustes › Partidas con partidas reales (se probó la lógica en JVM y la UI con datos sintéticos).
- Proveedores remotos reales (nube con descarga lenta o sin conexión): se simulan con el proveedor de pruebas y un espejo falso.
- Rotación real en el teléfono con el menú de pausa en horizontal (solo capturas del catálogo con rotación forzada y pruebas de recreación), TalkBack, fuente al 200 %, mando físico.
- Rendimiento de `flushSync` en el teléfono (las cifras son del emulador) y una sesión larga (soak) con guardados.
- El orden de `ON_PAUSE` frente a la muerte del proceso por el sistema con el plazo de 3 s agotado en un dispositivo lento (la ruta `TimedOut` se probó con un bloqueo inyectado y por un congelado real del anfitrión).

## Archivos principales

Nuevos: `game/{GameSession,GameLauncher,GameplayViewModel}.kt`, `saves/{SaveCoordinator,SavesBrowser,FramePng}.kt`, `ui/gameplay/{GameplayHost,PauseSheet,StatesSheet,GameDialogs}.kt`, `ui/settings/SavesScreen.kt`, `debug/{SaveStress,GameplayCatalogScreens,GameplayTestActivity}.kt`, `src/debug/AndroidManifest.xml`, `tools/android-save-kill-test.sh`; pruebas `SaveCoordinatorTest`, `SavesBrowserTest` (JVM) y `game/{GameSessionTest,OpenFromLibraryTest,GameplayLifecycleTest,GameplayUiTest,SaveCyclesTest,GameplayTestHost}.kt`, `testing/GameFixtures.kt`, `ui/SavesSettingsUiTest.kt`. Modificados: `MainActivity`, `PocketGBApp`, `AppDestination`, `SessionLifecycleObserver`, `EmulatorSession` (entrada tras cerrar), `GameplayScreen`, `GameDetailsScreen`, `LibraryContent/Screen`, `LibraryViewModel.recordPlayed`, `SettingsScreen`, `strings.xml`, catálogo debug, `tools/android-screenshots.sh`, `docs/ESTADO.md`.
