## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A5V4-H1 | bloqueante | [GameSession.kt:519](/Users/joelbermudez/Documents/workspace/pocketgb/android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:519) | Si `pocketgb-save-reaper` es interrumpido, libera el lease aunque el hilo de guardado siga vivo. Esto permite reabrir o restaurar la misma huella mientras una escritura antigua continúa bloqueada y puede completarse después, sobrescribiendo la partida nueva/restaurada. También queda sin cerrar el handle nativo. Viola la regla dura 6 y el invariante declarado por `FingerprintOwnership`. | `awaitThreadExit()` lanza `InterruptedException`; el `catch` no espera de nuevo ni cierra la sesión, pero el `finally` ejecuta `dropLease()` en líneas 524–526. El test [GameSessionHardeningTest.kt:334](/Users/joelbermudez/Documents/workspace/pocketgb/android/app/src/androidTest/java/com/joelbermudez/pocketgb/game/GameSessionHardeningTest.kt:334) valida precisamente el comportamiento inseguro: interrumpe el reaper, espera que la huella quede libre mientras `StallingOps` sigue bloqueado y solo después libera la escritura. No intenta abrir/restaurar durante esa ventana ni verifica qué save queda finalmente. La propia evidencia reconoce expresamente que no se probó la escritura tardía posterior a la liberación ([A5-android-evidencia.md:305](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/A5-android-evidencia.md:305)). | El reaper no debe liberar el lease hasta confirmar que `pocketgb-saves` terminó y cerrar el handle. Ante interrupción, continuar esperando —recordando/restaurando el estado de interrupción al final— o transferir la espera a otro guardián que conserve el mismo lease. Cambiar el test para: interrumpir, comprobar que abrir y restaurar siguen bloqueados, liberar la operación antigua, comprobar su finalización/cierre y solo entonces permitir una nueva sesión. Además debe verificar que la escritura antigua nunca pisa una restauración o save nuevo. |
| A5V4-H2 | baja | [A5-android-evidencia.md:245](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/A5-android-evidencia.md:245) | La evidencia todavía no identifica por SHA el commit de corrección auditado, pese a que A5V3-H2 pedía eliminar la dependencia del estado circunstancial de la rama. | Tanto la cabecera como esta sección dicen “el commit que sigue a `ffd6d97`”. El árbol auditado es concretamente `9b4e5b12b9fc43131bb206965aa08ad75a8a7604`. Hoy la referencia es deducible porque es hijo directo, pero dejará de serlo al crecer la rama. | Registrar explícitamente `9b4e5b1` como commit de las correcciones y como árbol de la cuarta auditoría. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `withExclusive` cubre comprobación y restauración sin TOCTOU | sí | Correcto: el lease envuelve toda la mutación en [SavesBrowser.kt:42](/Users/joelbermudez/Documents/workspace/pocketgb/android/app/src/main/java/com/joelbermudez/pocketgb/saves/SavesBrowser.kt:42). El test intercala otro adquirente dentro de la escritura. |
| La apertura adquiere propiedad antes del primer acceso a saves/states | sí | Correcto: se obtiene en [GameLauncher.kt:176](/Users/joelbermudez/Documents/workspace/pocketgb/android/app/src/main/java/com/joelbermudez/pocketgb/game/GameLauncher.kt:176), antes de `StateStore.recoverOrphans`, `SavesIndex` y `SaveStore`. Antes solo se lee/carga el ROM y se obtiene su huella. |
| Dos aperturas simultáneas de la misma huella | sí | Correcto en código y cubierto por un test instrumentado con latch. |
| Apertura fallida o excepción libera su lease | sí | Correcto: cierre de sesión y lease en `finally`, [GameLauncher.kt:255](/Users/joelbermudez/Documents/workspace/pocketgb/android/app/src/main/java/com/joelbermudez/pocketgb/game/GameLauncher.kt:255). |
| Transferencia ViewModel → registro sin ventana | sí | Correcto: se transfiere la misma `GameSession`; `claim()` no libera ni readquiere. |
| Un lease viejo no libera a un dueño nuevo / doble `close` | sí | Correcto: `AtomicBoolean` más comparación por identidad en [FingerprintOwnership.kt:28](/Users/joelbermudez/Documents/workspace/pocketgb/android/app/src/main/java/com/joelbermudez/pocketgb/saves/FingerprintOwnership.kt:28). |
| El lease sobrevive al cierre mientras exista una reparación pendiente | sí | Correcto en el camino normal mediante `leaseHolds`; prueba con escritura rezagada incluida. |
| `SaveThreadStuck`: no liberar propiedad hasta terminar toda escritura antigua | no | Incumplido si el reaper es interrumpido; A5V4-H1. |
| Corrección de DeepSeek H1 | no | La fuga permanente se sustituyó por una liberación prematura peligrosa. El test nuevo prueba el comportamiento equivocado. |
| Reapertura legítima después de cierre normal o recuperación del disco | sí | Los caminos normales liberan el lease tras cerrar y las pruebas lo cubren. |
| Ausencia de deadlock evidente entre `handleLock`, `SaveLocks`, mutex de operaciones y executors | sí | No encontré inversión concreta: `SaveLocks` actúa como lock hoja; el lease no se mantiene como monitor durante I/O; el handle se cierra después de unir el executor. |
| Escritura local atómica, backups y restauración con backup previo | sí | Sin regresiones observadas en el diff de esta vuelta. |
| ROM/state como entrada no confiable | sí | Esta vuelta no modifica parser, JNI ni `core/`; no encontré nuevos índices o tamaños derivados sin acotar. |
| Corrección contra Pan Docs / `docs/03-core-spec.md` | sí | `core/` no cambió en A5 ni en `ffd6d97..9b4e5b1`; no hay opcodes, registros o ciclos nuevos que contrastar. |
| Sin ROMs, `.sav`, estados o APK en Git, incluido historial | sí | `git log --all --stat` y listado histórico no mostraron ROMs comerciales, saves, estados ni APK versionados. Los ROMs de pruebas se generan sintéticamente. |
| Sin copia GPL/AGPL prohibida | sí | No se introdujo código de Gambatte, Delta, mGBA ni `iOS/` de SameBoy en el diff auditado. |
| Sin red en la app | sí | El manifiesto no declara `INTERNET` ni `ACCESS_NETWORK_STATE`; el diff no toca iOS. |
| Sin globals nuevos en `core/` | sí | `core/` no fue modificado. |
| Evidencia corresponde al commit | parcialmente | Los XML existentes tienen 241 JVM y 188 instrumentados, sin fallos; incluyen todas las pruebas nuevas. Sus marcas de tiempo son inmediatamente anteriores al commit `9b4e5b1`, por lo que la evidencia es plausible. Falta identificar ese SHA en el documento. |
| Prueba de cierre forzado en emulador | no | Reportada como 50/50, pero no reejecutada por el auditor. |
| Prueba manual J11 en teléfono | no | Pendiente y reconocido explícitamente en la evidencia. |

## Notas

- No ejecuté Gradle, tests instrumentados ni el kill-test: el sandbox de esta auditoría es de solo lectura y esas tareas escriben en `build/` y en el emulador. Esto no constituye un hallazgo del proyecto.
- Sí inspeccioné los resultados existentes: 241 tests JVM y 188 instrumentados, todos sin fallos ni omitidos. Las pruebas nuevas aparecen en los XML.
- `git diff --check ffd6d97..HEAD -- android tools docs` no informó errores.
- No consideré los informes `A5-android-*-v4.md` actualmente sin seguimiento; no forman parte de `9b4e5b1`.
- El resto del diseño de ownership corrige adecuadamente A5V3-H1. El rechazo se debe al único punto donde vuelve a abrirse exactamente la ventana que el lease debía impedir: liberar propiedad antes de que la escritura antigua haya dejado de existir.
