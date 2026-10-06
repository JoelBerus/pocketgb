# Auditoría Opus (respaldo) · Android A5 · 1ª vuelta

Commit auditado: `b8b91bb`. Veredicto: **APROBAR CON CAMBIOS**. Reejecutado en copia scratch: 210 JVM sin fallos (`ProcessKillTest` con SIGKILL real), Release sin `INTERNET` ni código debug, `core/` intacto, sin ROM/.sav/.state en el historial. Instrumentados y kill-test no reejecutados (desinstalan la app y borrarían datos del emulador).

| ID | Sev. | Ubicación | Problema |
|---|---|---|---|
| H1 | alta | `SafSaveMirror.kt:205`, `SaveStore.kt:65-80`, `AtomicSaveWriter.kt:56-58`, `MirrorChannel.kt:109-111`, `GameLauncher.kt:178-185`, `SavesBrowser.kt:35` | El hilo del espejo (`addBackup`) y el de guardado/apertura/restaurar mutan `saves/backups/` a la vez, sin lock y con el mismo temporal `<fp>.1.tmp`. Entrelazado concreto que pierde de forma definitiva el contenido externo del `.sav`. |
| H2 | media | `GameplayViewModel.kt:239,271,305-307`, `EmulatorSession.kt:111-129,189-198`, `GameSession.kt:288-299`, `GameplayViewModel.kt:181-185` | `saveState`/`loadStateRaw`/`loadSram` leen `handle` sin `handleLock`; `onCleared` (main) puede cerrar mientras IO opera → uso tras liberar; `onBackground()` sin try puede crashear con `InvalidTransition`; `closeBestEffort` puede bloquear main >20 s (ANR). |
| H3 | media | `GameLauncher.kt:178-183`, `SafSaveMirror.kt:124-143` | La apertura no espera a que el `MirrorChannel` de esa huella quede inactivo: puede leer un `.sav` vacío/parcial (con RTC, un tamaño `S` parcial es válido y se importaría sin RTC). |
| H4 | media | `GameSession.kt:103`, `SessionLifecycleObserver.kt:21-27` | `saveProblem` nunca se muestra; el observer ignora el `FlushResult`. Se puede jugar horas sin guardar sin aviso (SPEC §6 pide que se vea). |
| H5 | media | `GameSession.kt:221-232` | Rollback de `loadState`: si `loadStateRaw(previous)` falla se ignora y `requestFlush` persiste la SRAM del estado rechazado; con `TimedOut` el mensaje "no ha cambiado" puede ser falso. |
| H6 | baja | `GameSession.kt:206,264-268` | El AUTO de rescate (J6) se sobrescribe en silencio. |
| H7 | baja | `SaveStore.kt:105-108`, `SaveOpening.kt:79-80` | `restore(n)` no valida tamaño: se puede restaurar una partida inválida. |
| H8 | baja | `SaveOpening.kt:53`, `GameLauncher.kt:195-196` | `.sav` local >1 MiB o ilegible → la apertura falla siempre en vez de ir a cuarentena. |
| H9 | baja | `SaveCyclesTest.kt:64-68,76-80,102` | Reintentos de `TimedOut`/`LocalSaveFailed` contradictorios con la aserción `<3 s`; ocultan fallos reales. |
| H10 | baja | `SaveStress.kt:103,132`, `android-save-kill-test.sh:87,109` | `save-verify` no detecta regresiones (solo bytes ≠ 0; `ready` no obligatorio; `stateTmpOrphans` no exigido). |
| H11 | baja | `StateStore.kt:78-83`, `SavesIndex.kt:38`, `SaveStore.kt:95` | Temporales huérfanos sin limpiar: `*.state.tmp`, `*.png.tmp`, `index.json.tmp`, `wrong-size-….sav.tmp`. |
| H12 | baja | `MirrorChannel.kt:113`, `SaveCoordinator.kt:196-205` | `drain` solo captura `Exception` (un `Error` deja `workerRunning=true`); `idleCallbacks` crece sin límite con el proveedor bloqueado. |

Nota (conforme a SPEC §5.4): cada carga de estado empuja la SRAM real una posición en `backups/`; tras 5 cargas la partida original deja de estar entre los backups.
