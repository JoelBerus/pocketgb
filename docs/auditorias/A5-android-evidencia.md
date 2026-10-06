# Evidencia · Android A5 — Partidas y estados (etapas 6–8: coordinador, apertura real y prueba de cierre forzado)

**Fecha:** 2026-10-05

**Rama:** `codex/android-port`. Commit base: `ae6b4bc` (etapas 1–5 de A5: guardado local atómico, estados, espejo SAF, nativo/JNI). Todo lo de este documento está **sin commitear** sobre ese árbol (el commit lo hace quien integre el hito).

**Alcance (etapas 6–8):** `SaveCoordinator` y `GameSession` (6); `GameLauncher`, `GameplayViewModel`, `GameplayHost` con menú de pausa, estados, diálogo de fallo local y avisos de espejo, Ajustes › Partidas con restauración de backups, **Jugar/Continuar habilitado** y catálogo debug (7); `tools/android-save-kill-test.sh`, medición de `flushSync` y esta evidencia (8). Las etapas 1–5 ya tienen su evidencia en commits previos; aquí se reejecutan sus pruebas dentro de la batería completa.

## Implementación

- **`SaveCoordinator`** (`saves/`): un `ScheduledThreadPoolExecutor` de 1 hilo `pocketgb-saves` por sesión. Ciclo cada 100 ms: política (`SramFlushPolicy`: debounce 1 s, red de 60 s) → `copySram()` → si ≠ `lastQueued` → `persistLocal`. Mantiene `confirmed` (lo último confirmado en disco) y `lastQueued` (lo último intentado). Una escritura fallida deja `lastQueued = null`, avisa por `onStatus` y la reintenta el siguiente ciclo (un debounce después). `flushSync(): FlushResult` (`Saved | Unchanged | Failed(e) | TimedOut`) se encola en el **mismo hilo** (I4: copiar y escribir siempre juntos y en orden), compara con `confirmed` y espera `get(3 s)`; el plazo agotado no cancela nada y deja el guardado pendiente. `close()` para el ejecutor **y une el hilo** antes de que nadie libere el handle nativo (I3). También ejecuta, en ese hilo, la E/S de los estados (`runOnSaveThread`).
- **`GameSession`** (`game/`): dueña de `EmulatorSession` + `SaveCoordinator` + `SaveTarget` (local y espejo) + `StateStore`. `saveState(slot)` (sesión aparcada; PNG y escritura atómica en el hilo de guardado); `loadState(slot)` (guarda el actual en AUTO si procede → `previous = saveState()` → `loadStateRaw` → `flushSync`; si el flush no es `Saved/Unchanged` ⇒ `loadStateRaw(previous)`, `requestFlush()` y `StateError.SaveFailed` «La partida actual no ha cambiado…»); `pause()` = pausa nativa + `flushSync` acotado, idempotente (sirve para `ON_PAUSE`, `ON_STOP` y foco de audio; repetirlo reintenta un guardado pendiente); `exit(force=false): ExitResult` = `Clean | LocalSaveFailed(error)`: **nunca cierra limpio ignorando un fallo local** (la sesión sigue abierta y en pausa); con `force` intenta antes un estado AUTO de rescate (J6). AUTO al salir tras flush correcto (su fallo no impide salir). Sesión sin destino (tamaño de `.sav` incorrecto, J10, o cartucho sin batería): los estados funcionan, la SRAM no se persiste y se avisa.
- **`GameLauncher`** (SPEC §2.2): lee la ROM con `ContentResolverRomSource` (≤ 8 MiB + 1), `EmulatorSession.load(rom, now/1000)`, `recoverOrphans(validSizes)`, localiza el espejo (`SafMirrorLocator`: `ReadWrite`/`ReadOnly`/`Shared`), `SaveOpening.prepare`, `loadSram`, `SavesIndex.record`; devuelve `OpenResult.Opened(GameSession)` o `Failed(OpenError)` (`Unplayable`, `FolderMissing`, `PermissionRevoked`, `RemotePending`, `RomTooLarge`, `RomRejected`, `MirrorNotDownloaded`, `SaveIncompatible`, `LocalSaveFailed`, `Unreadable`, `Core`). La apertura corre en `NonCancellable` (no deja un handle nativo sin dueño) y cierra la sesión ante cualquier fallo.
- **`GameplayViewModel`** (AndroidX `ViewModel`, `by viewModels` en `MainActivity`): dueño de la `GameSession` (jamás `remember`); sobrevive a rotación/`recreate()`. `open` con guarda de doble toque, `showPauseMenu`/`continueGame`/`revealPauseMenu`, estados (`saveState`/`loadState`/`deleteState` bajo `Mutex`), `exit(force)`, doble confirmación de riesgo, `onBackground()`, `onCleared()` best-effort (`closeBestEffort`). Registra «jugado» con `LibraryViewModel.recordPlayed`.
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
| I3: el hilo de guardado se une antes de liberar el handle | `SaveCoordinatorTest.closeJoinsTheSaveThread`, `GameSessionTest.closingJoinsTheSaveThreadAndIsIdempotent`, `SaveCyclesTest` (hilos estables en 100 vueltas) |
| §5.3 espejo SAF (in situ `wt`, verificación, ReadOnly, Shared, cambios externos, nombre alterado) | `SafMirrorTest` (34), `SaveResolutionTest`, `SaveOpeningTest`, `MirrorChannelTest` |
| §5.3 el espejo nunca bloquea ni invalida la local | `GameSessionTest.synchronousFlushDoesNotWaitForBlockedMirror`, `aFailingMirrorRaisesANonBlockingEventAndNeverFailsTheLocalSave`, `SaveCoordinatorTest.mirrorTroubleIsReportedOnceAndNeverFailsTheLocalSave` |
| §5.3 / §2.2 apertura de extremo a extremo (ROM → SRAM en `filesDir/saves` y en el espejo) | `OpenFromLibraryTest` (`theSramWrittenByTheGameLandsInFilesDirSavesAndInTheMirror`, `anExistingSavNextToTheRomIsImported…`, `aReadOnlyFolderOpensWithAWarning…`, `aSavWithTheWrongSizeOpensWithoutPersisting…`, `siblingRomsSharingTheSavNameDisableTheMirror`, `anUnreadableMirrorWithNoLocalSaveRefusesToOpen`, `theViewModelOpensRecordsPlayed…`) |
| §5.4 estados: sesión aparcada; guardar incluye captura | `GameSessionTest.statesNeedAPausedSession`, `StateStoreTest`, `GameplayUiTest.statesSaveLoadAndDeleteWithConfirmation` |
| §5.4 cargar persiste la SRAM contenida, con rollback si falla | `GameSessionTest.loadingAStateSavesItsSRAMWithBackupOfThePrevious`, `loadStateFailsVisiblyAndRollsBack` (`FileOps` inyectado que falla) |
| §6 errores tipados con recuperación concreta | `OpenError` + `OpenFromLibraryTest.missingRomAndUnplayableEntries…`, `GameplayUiTest.aSaveLoadWarningIsShownAtOpenAndDismissed` |
| §6 un fallo local impide cerrar limpio hasta reintentar o confirmar con riesgo | `GameSessionTest.exitWithLocalFailureKeepsTheSessionOpenAndPausedThenRetrySucceeds`, `forcedExitAfterLocalFailureTriesAnAutoStateAsRescue`, `GameplayUiTest.localSaveFailureDialogOffersRetryAndDoubleConfirmedRiskyExit`, `retryAfterTheDiskRecoversExitsCleanlyAndKeepPlayingResumes` |
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
10. `SaveCyclesTest` reintenta un `TimedOut` de `pause()` y un `LocalSaveFailed` de `exit()` (los cuenta y los imprime: 0 y 0 en la corrida final). Antes fallaba por un congelado del anfitrión de ~50 s; la propiedad probada (nada se pierde, no se cierra limpio con guardado pendiente) es la misma.

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
- `StateStore` deja un `.state.tmp` huérfano si el proceso muere a mitad de escribir un estado y `recoverOrphans` no lo limpia (solo trata `saves/`): inofensivo (`stateTmpOrphans=0` en las 50 muertes) pero sin limpiar; candidato a A6/A7.

## Archivos principales

Nuevos: `game/{GameSession,GameLauncher,GameplayViewModel}.kt`, `saves/{SaveCoordinator,SavesBrowser,FramePng}.kt`, `ui/gameplay/{GameplayHost,PauseSheet,StatesSheet,GameDialogs}.kt`, `ui/settings/SavesScreen.kt`, `debug/{SaveStress,GameplayCatalogScreens,GameplayTestActivity}.kt`, `src/debug/AndroidManifest.xml`, `tools/android-save-kill-test.sh`; pruebas `SaveCoordinatorTest`, `SavesBrowserTest` (JVM) y `game/{GameSessionTest,OpenFromLibraryTest,GameplayLifecycleTest,GameplayUiTest,SaveCyclesTest,GameplayTestHost}.kt`, `testing/GameFixtures.kt`, `ui/SavesSettingsUiTest.kt`. Modificados: `MainActivity`, `PocketGBApp`, `AppDestination`, `SessionLifecycleObserver`, `EmulatorSession` (entrada tras cerrar), `GameplayScreen`, `GameDetailsScreen`, `LibraryContent/Screen`, `LibraryViewModel.recordPlayed`, `SettingsScreen`, `strings.xml`, catálogo debug, `tools/android-screenshots.sh`, `docs/ESTADO.md`.
