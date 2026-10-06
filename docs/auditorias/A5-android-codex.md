## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A5-H1 | bloqueante | `android/app/src/main/java/com/joelbermudez/pocketgb/saves/SaveCoordinator.kt:251` | `close()` puede devolver con el hilo de guardado todavía vivo y después liberar el handle nativo. Esto rompe I3 y permite uso tras liberar. | Tras esperar 10 s, ejecuta `shutdownNow()`, espera 5 s y hace `join(5_000)`, pero nunca comprueba `saveThread.isAlive`. `GameSession.close()` llama inmediatamente a `session.close()` en líneas 281–285. Una operación de archivo/SAF no interrumpible puede seguir y luego ejecutar `source.copy()` contra el handle destruido. `closeJoinsTheSaveThread` solo prueba el caso sin una tarea bloqueada. La evidencia afirma que el hilo queda unido, pero el código no lo garantiza. | No destruir la sesión mientras el hilo siga vivo. Diseñar un cierre que impida nuevas copias nativas, cancele o drene la cola y confirme terminación real. Añadir un test con una operación que ignore interrupciones y comprobar que el handle nunca se libera antes de terminar. |
| A5-H2 | bloqueante | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:221` | El rollback de carga de estado no es transaccional cuando `flushSync()` expira: una escritura rezagada puede instalar después la SRAM del estado rechazado. | Un timeout no cancela el `Future` (`SaveCoordinator.kt:132–156`). `loadState()` restaura el core y solo llama `requestFlush()` en líneas 224–231. La tarea expirada puede copiar/escribir la SRAM cargada antes del rollback y dejarla en disco; la corrección queda diferida al próximo tick. Si el proceso muere en esa ventana, el disco contiene la SRAM del estado que la UI declaró rechazado. `loadStateFailsVisiblyAndRollsBack` únicamente inyecta un fallo inmediato; no cubre timeout/escritura rezagada. | Tras el rollback, encolar obligatoriamente una barrera/corrección de la SRAM anterior en el mismo executor y no comunicar “la partida no ha cambiado” hasta confirmar esa corrección. Añadir test con latch: timeout durante la escritura de B, rollback a A, liberar la tarea y matar/cerrar antes del siguiente tick; el disco debe quedar en A. |
| A5-H3 | alta | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:288` | `onCleared()` fuerza silenciosamente una salida con riesgo, sin la doble confirmación exigida para ignorar un fallo local. | `GameplayViewModel.onCleared()` llama `closeBestEffort()` (`GameplayViewModel.kt:305–307`), que ejecuta `exit(force = true)` y finalmente `close()` (`GameSession.kt:289–298`). Si el flush local falla, se cierra igualmente. Esto contradice SPEC §6 y la afirmación de evidencia “nunca cierra limpio ignorando un fallo local”. No hay test para `onCleared()` con disco fallando. | No usar la ruta de salida confirmada por el usuario desde `onCleared`. Separar “el proceso está muriendo” de una destrucción recuperable del ViewModel y conservar la sesión/guardado pendiente cuando sea posible. Añadir prueba de `onCleared` con fallo local. |
| A5-H4 | media | `docs/auditorias/A5-android-evidencia.md:5` | La evidencia no identifica correctamente el árbol auditado y sobreafirma I3. | Dice que todo está “sin commitear” sobre `ae6b4bc`, pero el árbol auditado es `b8b91bb`, que ya contiene esa evidencia. En la línea 11 y la tabla de la línea 62 afirma que `close()` une siempre el hilo, algo que H1 desmiente. | Actualizar la evidencia con el SHA auditado, distinguir evidencia previa de comprobación posterior al commit y corregir la afirmación de I3. |
| A5-H5 | baja | `android/app/src/main/java/com/joelbermudez/pocketgb/saves/SaveFileOps.kt:105` | La sincronización del directorio oculta cualquier `IOException`, no solo errores de sistema de archivos donde `fsync(dir)` no está soportado. | `syncDirectory()` captura toda `IOException` sin registrar ni distinguir causa. Un error real de E/S queda presentado como guardado confirmado, debilitando la garantía de durabilidad ante corte de energía. | Tolerar únicamente los errores documentados como no soportados, o registrar/propagar los demás. Añadir prueba de fallo real de sincronización del directorio. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| Sin ROMs, `.sav`, estados o boot ROMs en el historial | sí | No aparecieron rutas prohibidas al recorrer los árboles de todos los commits. Las menciones encontradas son documentación o ROMs sintéticas generadas por tests. |
| Sin copia GPL/AGPL ni código de referencias prohibidas | sí | No observé atribuciones o fragmentos sospechosos en el diff A5. `core/` no fue modificado. |
| Sin red en la aplicación | sí | El diff de producción no añade permisos o APIs de red. La evidencia declara inspección del APK Release, aunque no pude reejecutarla. |
| Reglas Android de backup, nunca `<include>` | sí | Ambos XML usan exclusivamente `<exclude>`; nube incluye la partida actual, excluye backups/estados y transferencia conserva estos últimos. |
| Escritura local atómica y cinco backups | sí, con reserva | La partida actual no se retira antes de instalar la nueva y hay temporales con `fsync`. Persiste la debilidad H5 sobre sincronización del directorio. |
| Recuperación de temporales y cuarentena | sí | `recoverOrphans()` valida tamaños y la cuarentena usa nombres únicos fuera de la rotación. |
| Resolución local frente a espejo | sí | La tabla de casos implementa fechas, empate/local, tamaño incorrecto, copia perdedora y espejo no disponible. |
| SAF: `"wt"`, verificación, ReadOnly/Shared y cambios externos | sí | Se usa `"wt"`, fsync best-effort, relectura con longitud y SHA-256 y desactivación ante cambios externos. |
| I4: copiar y escribir en el mismo hilo | sí | Las rutas periódica y síncrona pasan por el executor único. |
| `flushSync` acotado a 3 s y reintentos | sí, con fallo | El plazo existe, pero conservar la tarea tras timeout causa H2. |
| I3: unir hilo antes de destruir la sesión nativa | no | H1: las esperas y el `join` son acotados y su resultado se ignora. |
| JNI: topes para `.sav`/estado y sesión aparcada | sí | Hay topes antes de copiar, buffers temporales y exclusión mediante mutex; el core realiza la validación completa en dos pasadas. |
| Sin `malloc` dentro de `gb_run_frame` | sí | El snapshot persistente se reserva durante `load`; las reservas JNI ocurren fuera del bucle nativo. |
| Carga de estado con rollback seguro | no | H2: el timeout permite que una escritura rezagada contradiga el rollback. |
| Fallo local impide salida limpia sin confirmación | no | La UI normal lo respeta, pero `onCleared()` fuerza la salida sin confirmación, H3. |
| Restauración de backups | sí | `restore()` pasa por la escritura atómica, que respalda previamente la partida actual; se bloquea con sesión abierta. |
| Tests JVM, instrumentados y kill-test declarados | no | Las pruebas y sus conteos son plausibles por inspección, pero no pude reejecutarlas: el sandbox negó incluso crear la copia temporal en `/tmp`. Esto no es un hallazgo del proyecto. |
| Prueba manual en teléfono y proveedores SAF reales | no | La propia evidencia la declara pendiente, incluida J11 en teléfono. |
| Evidencia ligada inequívocamente al commit auditado | no | H4: conserva el texto de estado “sin commitear” sobre el commit anterior. |

## Notas

- No modifiqué archivos ni ejecuté comandos que escribieran en el repositorio.
- Revisé el diff `5c94c03..b8b91bb`, los tres commits indicados, el historial completo para extensiones prohibidas, la especificación y la evidencia.
- No hay cambios de CPU/core que requieran señalar discrepancias nuevas de opcode, registro o ciclos contra Pan Docs.
- La imposibilidad de crear un directorio temporal impidió reejecutar núcleo, Gradle y pruebas instrumentadas; no se considera defecto del proyecto.
- Los archivos no rastreados `.codex/` y los informes de auditoría existentes no forman parte de los tres commits auditados.
