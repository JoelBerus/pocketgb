# Auditoría A6-L0 (subagente Opus, independiente): `git diff 8953d65...HEAD` (9ce3ce2, bbca15c)

## Veredicto: APROBAR

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A6L0-H1 | Baja (residual documentado) | `game/GameSession.kt` `settleDeferredRepair()` | Si escribir A falla al cerrar, el hold extra se conserva sin reintento ni reaper: la huella queda bloqueada hasta que muera el proceso. Mientras tanto el principal puede seguir siendo B (A sigue disponible en el backup `.1`). | En el `catch`, `deferredRepair.set(deferred)` y nadie más lo vuelve a llamar después del cierre. | Aceptable: falla hacia el lado seguro, es decir, no se escribe nada ni se suelta el lease con una escritura viva. Si se quiere cerrar del todo, añadir reintentos con backoff en un hilo, como hace `repairBody`, o un aviso en la UI. No bloquea. |

No encontré ninguna ruta nueva que suelte el lease sin una reparación confirmada o con una escritura viva en `pocketgb-saves`:
- **`repairBody`:** `dropLease()` solo se llama `if (confirmed)`. `confirmed` solo pasa a true después de `pending.get()` sin excepción, o después de `awaitThreadExit()` seguido de `persistLocal`. Cualquier `Throwable`, incluido uno lanzado dentro del manejador (log, sleep o reencolado), produce otra vuelta. Si el hilo muere por algo externo, el hold se conserva.
- **`repairWithoutThread`:** solo suelta el lease si el `get` ha tenido éxito. En cualquier otro caso difiere la reparación.
- **`settleDeferredRepair`:** solo se invoca en `Closed`, que según `shutdown` significa que el hilo de guardado ya no está vivo, o en el reaper después de `awaitThreadExit()`. Por tanto, B no puede escribir en paralelo. Tampoco hay carrera con otra sesión: el hold extra mantiene la huella con dueño hasta que se escribe A. `deferredRepair` y el hilo de reparación son excluyentes, así que A nunca se escribe dos veces de forma concurrente.
- **`forceStop()` en el `catch` de `tryClose` y en el reaper:** `shutdownNow()` puede interrumpir un guardado a medias. `AtomicSaveWriter` escribe en `.tmp` con `writeSynced` y solo después hace `atomicReplace`. Si la interrupción llega antes del reemplazo, el `.sav` principal queda intacto. El lease se suelta solo después de `awaitThreadExit()`.

## Estado de A5V6-H1, A5V6-H3, A5V7-H1
- **A5V6-H1: cerrado.** El hold solo se suelta con una reparación confirmada. El test con OOM dentro del manejador (intento fallido, luego OOM y luego un futuro que no termina) comprueba que la huella sigue con dueño y que se libera al confirmar.
- **A5V6-H3: cerrado, con el residual A6L0-H1.** Si `start()` o `repairSubmit` fallan, todo lo captura un `catch (Throwable)`. Quien llama sigue viendo `RollbackFailed`, y el hold tiene dueño: o la confirmación en línea o `settleDeferredRepair`. El único caso en que se retiene es un fallo de escritura de A al cerrar. Ahí no se reabre el defecto original, porque no queda un hold huérfano por fallar el arranque del hilo: es una retención deliberada que no deja ninguna escritura viva.
- **A5V7-H1: cerrado.** `shutdown` llama a `forceStop()` si `executor.shutdown()` lanza. `tryClose` y el reaper también llaman a `forceStop()`, que no lanza y es idempotente. Así el hilo termina aunque el cierre falle antes de llegar al ejecutor. El test con `SecurityException` lo verifica.

## Notas
- **Tests:** los tres tests nuevos de `GameSessionHardeningTest` (instrumentados) ejercitan el código de producción de `GameSession` a través de los hooks. `forceStopClosesTheExecutorWithoutThrowingAndIsIdempotent` (JVM) prueba `SaveCoordinator` real. Que fallarían sin la corrección es plausible:
  - Sin la guarda `confirmed`, el `finally` original soltaba el lease ante un OOM en el manejador.
  - Sin el `catch` alrededor de `start()`, el OOM se propagaba y el hold quedaba huérfano.
  - Sin `forceStop` en el reaper, este esperaba para siempre.

  No he repetido las reversiones: me apoyo en lo que dice la evidencia.
- **Hooks:** cuando no se inyectan, los valores por defecto de `repairSubmit` y `repairThreadFactory` (y los de `GameFixtures`) equivalen exactamente al código anterior: `submitOnSaveThread` y un `Thread` daemon con el mismo nombre. No cambian el comportamiento de producción.
- **I1–I4 y alcance:** `handleLock` y el orden de guardado I4 no se tocan. El diff solo modifica `GameSession.kt`, `SaveCoordinator.kt`, tests y docs. `core/`, JNI e iOS quedan intactos. No hay ROMs ni `.sav` en el diff.
- **Evidencia:** los XML de `android/app/build/test-results/testDebugUnitTest` suman 242 tests JVM y 0 fallos, lo que coincide. En `androidTest-results` solo hay fallos de `GameplayUiTest`, con `RootViewWithoutFocusException` (`has-window-focus=false`). Es el patrón típico de un emulador sin foco de ventana, ajeno al diff (que no toca la UI), así que la explicación de que es preexistente resulta plausible.
- **Observación menor, sin hallazgo:** en `repairWithoutThread`, si el primer `get` agota el plazo, la tarea encolada puede ejecutarse más tarde y además `settleDeferredRepair` vuelve a escribir A al cerrar. Escribe los mismos bytes en ambos casos, así que es idempotente y no hay riesgo.
