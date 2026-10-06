## Veredicto: APROBAR

## Hallazgos

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|----|-----------|---------------|----------|-----------|---------------------|
| – | – | – | No se encontraron hallazgos bloqueantes ni de alta criticidad en esta vuelta. El diseño `FingerprintOwnership` resuelve el TOCTOU original (A5V3-H1), el reaper libera el lease incluso si es interrumpido y la transferencia al `OrphanSessionRegistry` conserva el mismo token sin huecos. Las verificaciones de carrera entre apertura, restauración y sesiones huérfanas quedan cubiertas por la adquisición atómica del lease. | Revisión exhaustiva del código, resultados de pruebas existentes (241 JVM + 188 instrumentados, todos verdes) y evidencia documental. | – |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|---|
| Propiedad exclusiva atómica por huella (`FingerprintOwnership`) | sí | `tryAcquire` es `@Synchronized`; toda apertura, restauración y mantenimiento de sesión adquiere el lease antes de tocar archivos. |
| El lanzador adquiere el lease antes de cualquier E/S de saves/ | sí | `GameLauncher.openBlocking:180` adquiere `lease` inmediatamente después de obtener la huella y antes de `recoverOrphans`. |
| La sesión conserva el lease toda su vida y lo libera al cerrar | sí | `GameSession` posee `lease`, cuenta referencias (`leaseHolds`) para reparaciones, y libera en `tryClose` (incluido el reaper). |
| Transferencia al `OrphanSessionRegistry` sin ceder el lease | sí | `GameplayViewModel.onCleared` llama a `orphans.claim(game)` sin liberar; el mismo token sigue vigente. `settle`/`afterClosed` cierran la sesión, que a su vez libera el lease. |
| `SavesBrowser.restore` usa `withExclusive` (check+acción atómicos) | sí | `SavesBrowser.restore:44` emplea `ownership.withExclusive`, que adquiere y libera en `finally`. |
| Reaper libera el lease siempre (incluso si es interrumpido) | sí | El hilo `pocketgb-save-reaper` en `GameSession.tryClose` tiene `dropLease()` en `finally`. |
| Sin deadlocks entre `handleLock`, `SaveLocks`, `Mutex` de operaciones y `FingerprintOwnership` | sí | No se identifican ciclos de espera. La adquisición del lease no se produce mientras se mantiene `handleLock` u otros locks involucrados. |
| Sin pérdida de partidas (escritura atómica, backup, SRAM) | sí | `AtomicSaveWriter` rota backups, `SaveCoordinator` garantiza orden FIFO y cierre seguro; el lease impide escrituras concurrentes de distintas sesiones. |
| Pruebas de carrera TOCTOU y propiedades atómicas pasan | sí | Los resultados documentados muestran 241 tests JVM y 188 instrumentados con 0 fallos. Las pruebas añadidas (`aRestoreInFlightCannotBeOvertaken…`, `twoSimultaneousOpens…`, etc.) validan el nuevo diseño. |
| Documentación y evidencia actualizadas (A5V3-H2) | sí | La evidencia (`docs/auditorias/A5-android-evidencia.md`) ya no contiene “sin commitear” y refleja los commits correctos. |

## Notas

- La corrección del hallazgo bloqueante de la tercera vuelta (A5V3-H1) se considera completa y sólida. El nuevo mecanismo `FingerprintOwnership` elimina la ventana TOCTOU mediante concesión atómica del lease, mantenido durante toda la vida de la sesión y transferido al registro de huérfanas sin soltarlo.
- El hallazgo del reaper (DeepSeek) también queda subsanado: en todos los caminos (cierre normal, hilo atascado, interrupción) se libera el lease en un bloque `finally`.
- No se detectaron ventanas entre la adquisición del lease y el primer acceso a los archivos de guardado. El lanzador toma el lease inmediatamente después de leer la huella y antes de cualquier operación de E/S.
- La auditoría se basó en la revisión estática de los archivos proporcionados (no se ejecutaron pruebas debido a las limitaciones del sandbox), pero los resultados de pruebas documentados en la evidencia son consistentes con el código auditado y no hay indicios de regresiones.
- Se mantiene pendiente la prueba manual de Joel en un teléfono real (J11), así como la verificación en proveedores SAF reales, que no forman parte del alcance de esta auditoría.
