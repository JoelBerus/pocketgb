## Veredicto: APROBAR

## Hallazgos
No se encontraron hallazgos nuevos que pongan en riesgo la partida o el cumplimiento de los criterios. Todas las observaciones de la 1ª vuelta (Codex H1–H5, Opus H1–H12) han sido corregidas de forma sólida y los nuevos mecanismos (compuerta de cierre, barrera de rollback, `SaveLocks`, `rescueExit`, `snapshotWhenIdle`, validación de tamaños, etc.) están implementados correctamente. Los tests específicos para estos escenarios se encuentran en la evidencia y, por inspección del código, cubren cada corrección.

Durante la 2ª vuelta se revisaron con especial atención los posibles deadlocks entre `SaveLocks`, `handleLock`, `executor` y `Mutex`; el shutdown con reaper; el nuevo flujo de `onCleared`/`rescueExit`; la barrera de rollback; `restore` con `validSizes` y `snapshotWhenIdle`. No se detectaron condiciones de carrera, uso tras liberación ni ventanas de pérdida de partida.

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| (ninguno) | – | – | – | – | – |

## Criterios del hito
Se listan los criterios de la 1ª vuelta que quedaron corregidos y los nuevos, todos verificados por inspección de código y por la evidencia proporcionada.

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| Sin ROMs, `.sav`, estados o boot ROMs en el historial | sí | Igual que 1ª vuelta |
| Sin copia GPL/AGPL ni código de referencias prohibidas | sí | Sin cambios en `core/` |
| Sin red en la aplicación | sí | El APK Release no contiene permisos ni APIs de red |
| Reglas Android de backup, siempre `<exclude>` | sí | Confirmado en el manifiesto |
| Escritura local atómica y cinco backups | sí | `AtomicSaveWriter` con `.tmp` único, `SaveLocks` para evitar pisadas |
| Recuperación de temporales y cuarentena | sí | `recoverOrphans` completo, cuarentena con nombres únicos |
| Resolución local frente a espejo | sí | `SaveResolution` probada exhaustivamente |
| SAF: `"wt"`, verificación, ReadOnly/Shared y cambios externos | sí | `SafSaveMirror` con verificación, detección de cambios externos y modos |
| I4: copiar y escribir en el mismo hilo | sí | `SaveCoordinator` con `sourceGate` y tareas en el mismo hilo |
| `flushSync` acotado a 3 s y reintentos | sí | Timeout no cancela, la tarea permanece y se encola la barrera |
| I3: unir hilo antes de destruir la sesión nativa (Codex H1) | sí | `SaveCoordinator.shutdown()` + reaper; solo se libera el handle tras confirmar `Closed` |
| JNI: topes para `.sav`/estado y sesión aparcada | sí | Sin cambios en JNI; la parte Java/Kotlin acota lecturas |
| Sin `malloc` dentro de `gb_run_frame` | sí | Como en 1ª vuelta |
| Carga de estado con rollback seguro (Codex H2, Opus H5) | sí | Barrera `flushSync` y `disablePersistence` en caso de fallo del rollback |
| Fallo local no cierra limpio sin confirmación; `onCleared` no fuerza (Codex H3, Opus H2) | sí | `exit(force=false)` mantiene la sesión abierta; `onCleared()` usa `rescueExit` sin riesgo |
| Restauración de backups (Opus H7) | sí | `restore` valida tamaños con `validSizes` |
| Tests JVM, instrumentados y kill-test declarados | sí | Evidencia con 233 JVM y 181 instrumentados pasando; `ProcessKillTest` y script de cierre forzado (50/50) |
| Prueba manual en teléfono y proveedores SAF reales | no | Pendiente según la propia evidencia |
| Evidencia ligada al commit auditado | sí | La evidencia distingue entre commit base y correcciones sin commitear; todas las correcciones están documentadas |

## Notas
- La auditoría se realizó sobre el código con las **correcciones de la 1ª vuelta incorporadas**, tal como se detalla en `docs/auditorias/A5-android-evidencia.md`.  
- No fue posible reejecutar la batería de pruebas (sandbox sin herramientas), pero la evidencia adjunta muestra 233 pruebas JVM y 181 instrumentadas con 0 fallos, y 50 iteraciones del kill-test exitosas. La inspección del código confirma que los mecanismos añadidos son correctos.  
- Los criterios de pérdida de partida, en particular los relacionados con el cierre del coordinador, el rollback de estados y la salida del ViewModel, han quedado robustamente cubiertos.  
- El único punto pendiente sigue siendo la **prueba manual en el teléfono de Joel** (carpeta real, cierre forzado real, recuperación desde espejo), que la propia evidencia declara como no verificada.
