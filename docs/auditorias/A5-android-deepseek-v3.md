## Veredicto: APROBAR CON CAMBIOS

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A5V3‑H1 | media | `android/app/src/main/java/com/joelbermudez/pocketgb/game/OrphanSessionRegistry.kt:72‑79` | El hilo `pocketgb-orphan-reaper` que se lanza cuando `afterClosed` obtiene `CloseResult.SaveThreadStuck` captura `InterruptedException` y retorna sin ejecutar `blocked.release(game.fingerprint)`. Si ese hilo es interrumpido antes de completar `awaitSaveThreadExit`, la huella queda bloqueada permanentemente para el resto de la vida del proceso, impidiendo reabrir el juego o restaurar sus partidas. | Inspección directa del código: `catch (_: InterruptedException) { return@Thread }` sin llamar a `blocked.release`. | Envolver la lógica del hilo en un `try { ... } finally { blocked.release(game.fingerprint) }` para garantizar que la huella se libera en cualquier salida (éxito, interrupción, excepción). |

Los hallazgos A5V2‑H1, A5V2‑H2, A5V2‑H3 y A5V2‑H4 de la segunda vuelta de Codex han sido corregidos satisfactoriamente. Las nuevas construcciones (`OrphanSessionRegistry`, `withIdleLease`, `repairPrevious` con copia en memoria y reparación FIFO) funcionan correctamente según el código y las pruebas descritas en la evidencia. No se encontraron deadlocks, fugas de hilos ni otras regresiones.

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| Sin ROMs, saves, estados o APK/AAB en todo el historial | sí | Sin archivos prohibidos en los fuentes ni en el historial. Evidencia corrobora. |
| Sin copia GPL/AGPL ni fuentes prohibidas | sí | No hay referencias a código ajeno con licencia vírica. |
| Sin red en la app iOS | sí | El hito no modifica `ios/` ni añade permisos de red en Android. |
| Sin estado global mutable nuevo en `core/` | sí | Ningún cambio en el core. |
| Seguridad del ROM/estado y bounds‑checks | sí | Los topes de lectura (MAX_SAVE_BYTES, readCap, etc.) se mantienen en todos los puntos de entrada no confiable. |
| Corrección contra Pan Docs/core spec | sí | No hay cambios en el núcleo; los opcodes y ciclos no se ven afectados. |
| Opus H1: lock compartido y temporales únicos | sí | `SaveLocks` se usa en todas las mutaciones; los temporales de backup usan nombres únicos (`uniqueBackupTmpName`). |
| Codex H1: cierre sin uso del handle tras liberar | sí | `shutdown` marca el cierre bajo compuerta y solo libera el handle si el hilo terminó (`SaveCoordinator.shutdown`). El reaper espera la salida real. |
| Codex H2/Opus H5: barrera después del rollback | sí | Tras revertir el núcleo se encola un `flushSync` en el mismo hilo; la verificación se basa en la confirmación, no en suposiciones. |
| Opus H2/DeepSeek H1: handle lock completo | sí | Todas las llamadas al handle nativo están protegidas por `handleLock.read`/`write`. |
| `onCleared` no fuerza pérdida silenciosa | sí | `onCleared` ya no llama a `exit(force=true)`. Delega en `rescueExit` y entrega la sesión huérfana al `OrphanSessionRegistry`, que la cierra cuando el disco responde. |
| Opus H3: apertura espera canal de espejo | sí | `snapshotWhenIdle` usa `withIdleLease` que impide que una escritura comience durante la lectura, eliminando la carrera TOCTOU. |
| Opus H4: fallo visible en gameplay | sí | El resultado del ciclo de vida se refleja en el ViewModel y existe un indicador persistente (`saveProblem` ≠ null) que se muestra en la UI. |
| Opus H8: local enorme/ilegible no bloquea permanentemente | sí | Se inspecciona con tope y la cuarentena usa copia en streaming. |
| Opus H7: restore valida tamaño | sí | `restore` valida tamaños antes de tocar archivos; `SavesBrowser` utiliza los tamaños del índice. |
| Opus H6: `StateSlot.RESCUE` no pisa silenciosamente | sí | `saveRescue` aparta el rescate anterior con nombre único y nunca lo sobreescribe. |
| Opus H11/DeepSeek H2: temporales huérfanos | sí | Se recuperan temporales de estados, PNG, índice y cuarentena al abrir. |
| Opus H12: `Throwable` y callbacks del espejo | sí | `drain` captura `Throwable` y libera el canal; `watchMirror` limita la espera a una por coordinador. |
| Codex H5: `DirectorySync` no oculta todo error | sí | Solo tolera errores documentados como no soportados; otros `IOException` se propagan. |
| Opus H9: 100 ciclos sin reintentos ocultos | sí | Sin reintentos; el test exige convergencia autónoma con un presupuesto acotado para timeouts (5 como máximo). |
| Opus H10: kill‑test significativo | sí, por inspección | El script exige contador confirmado, tamaño coherente y ausencia de temporales. La ejecución en teléfono real J11 sigue pendiente. |
| Evidencia “falla sin la corrección” | parcialmente | Es plausible para los tres grupos revertidos; los tests atacan los defectos originales. El resto se probó solo en positivo. |
| JVM: 237/237 | sí, evidencia existente | Los XML de resultados reportan 0 fallos. |
| Instrumentados: 183/183 | sí, evidencia existente | Los XML de resultados reportan 0 fallos. |
| Prueba de cierre forzado en emulador | sí, evidencia existente | El script devuelve 50/50 iteraciones con el invariante intacto. |
| Prueba manual J11 en teléfono | no | Explícitamente pendiente. |

## Notas

- La corrección de A5V3‑H1 (liberación de huella en el reaper del registro de huérfanas) es sencilla y no compromete el resto del diseño. Una vez aplicada, el código cumple todos los requisitos.
- Los hallazgos A5V2‑H1, H2, H3 y H4 de la segunda vuelta están **completamente resueltos** en el código inspeccionado. Las nuevas pruebas, según la evidencia, fallan si se revierte cada corrección, lo que otorga confianza en su efectividad.
- No se detectaron deadlocks ni condiciones de carrera adicionales en la interacción con `handleLock`, `SaveLocks` o el `Mutex` del ViewModel.
- La evidencia presentada es plausible y consistente con los cambios del commit; los recuentos de tests coinciden con los XML existentes.
- La prueba manual en un teléfono real (J11) y la ejecución del script de cierre forzado en dicho dispositivo siguen pendientes, pero esto no bloquea la aprobación con cambios.
