## Veredicto: APROBAR CON CAMBIOS

Las correcciones de A5V6-H2 y A5V6-H4 evitan la liberación prematura observada y el abandono de la sesión huérfana, respectivamente. No encontré una ruta nueva ordinaria que libere el lease con una escritura viva ni que lo retenga para siempre.

Sin embargo, H2 no está completamente cubierta: si `SaveCoordinator.shutdown()` lanza antes de detener el executor, el reaper puede esperar indefinidamente. Aceptando este caso excepcional y los riesgos residuales H1/H3 declarados por Joel, A5 sí puede pasar a prueba manual en teléfono.

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A5V7-H1 | alta | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:524-552`; `android/app/src/main/java/com/joelbermudez/pocketgb/saves/SaveCoordinator.kt:316-329`; `android/app/src/androidTest/java/com/joelbermudez/pocketgb/game/GameSessionHardeningTest.kt:380-382` | La corrección de H2 presupone que una excepción de `shutdown()` ocurre después de haber cerrado el executor. Si ocurre antes, el reaper puede retener el lease y el handle indefinidamente. | `shutdown()` marca `closed=true` y después llama a `executor.shutdown()`. Esta llamada puede lanzar, por ejemplo, `SecurityException`. En tal caso el ciclo periódico continúa programado: `tick()` retorna por `closed`, pero el executor no termina, y `awaitThreadExit()` espera para siempre. El test inyectado llama primero a `c.shutdown(...)` y lanza después; por tanto solo cubre la variante favorable donde el cierre ya fue solicitado. Sí fallaría con el código anterior, pero no prueba todos los comportamientos de producción que la evidencia atribuye a la corrección. | Hacer que la recuperación vuelva a solicitar de forma segura el cierre/cancelación del executor, o exponer una operación no lanzable que garantice al menos `shutdownNow()` antes de iniciar el reaper. Añadir una prueba donde la excepción se produzca antes de que el executor reciba `shutdown` y verificar que el hilo y el lease finalmente se liberan. |
| A5V7-H2 | baja | `docs/ESTADO.md:5,29` | El resumen de estado es internamente contradictorio. | La cabecera todavía dice “pendientes de una 5ª vuelta”, mientras el cuerpo identifica correctamente como siguiente paso la 7ª vuelta. La línea 29 también enumera la 5ª vuelta dos veces. | Actualizar la cabecera a “pendiente de la 7ª vuelta” y eliminar la enumeración duplicada. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| A5V6-H2: una excepción de cierre no libera el lease mientras el hilo siga vivo | sí | Corregido para el caso probado: la excepción se convierte en `SaveThreadStuck` y el reaper conserva el lease hasta la salida real. Queda la cobertura incompleta descrita en A5V7-H1. |
| El test de H2 prueba código de producción | sí | Usa `GameSession.tryClose()` y el `SaveCoordinator` real; solo el punto de fallo es inyectado. El fallo se inyecta después del cierre efectivo, no antes ni durante sus primeros pasos. |
| El test de H2 fallaría sin la corrección | sí | El código anterior ejecutaría `dropLease()` y propagaría la excepción; fallaría ya en la expectativa “sin lanzar”. |
| A5V6-H4: rescate rechazado pasa al registro de huérfanas | sí | `onCleared()` captura el fallo de envío y llama inmediatamente a `orphans.settle(game, KeptOpen)`. |
| El test de H4 prueba el comportamiento de producción | sí | Ejecuta el `onCleared()` real mediante `ViewModelStore.clear()`, hace rechazar el executor y verifica cierre, guardado y liberación del lease. |
| El test de H4 fallaría sin la corrección | sí | La excepción del executor escaparía de `onCleared()` y el registro no programaría el reintento. |
| Nuevas rutas ordinarias que liberen el lease con una escritura viva | sí | No encontradas en `73de293..HEAD`. |
| Nuevas rutas ordinarias que retengan permanentemente el lease | sí | No encontradas. A5V7-H1 requiere una excepción durante el cierre. |
| Riesgos residuales A5V6-H1/H3 documentados con exactitud | sí | La evidencia los describe como no corregidos, identifica sus condiciones excepcionales y los deja pendientes antes de A8. No se repiten como hallazgos. |
| Escritura local atómica y backups | sí | El rango no modifica `AtomicSaveWriter`, `SaveStore`, espejo ni rotación de backups. |
| ROM/state como entrada no confiable | sí | El rango no modifica parsers, JNI, tamaños ni accesos derivados del ROM o registros. |
| Corrección contra Pan Docs y `docs/03-core-spec.md` | sí | `core/` no cambia; no hay opcodes, registros ni ciclos nuevos que contrastar. |
| Sin ROMs comerciales, saves, estados, boot ROMs o APK versionados | sí | Revisados `git log --all --stat`, nombres del historial y objetos de Git; no aparecieron artefactos prohibidos. Las menciones a Pokémon son únicamente documentación. |
| Sin código GPL/AGPL prohibido | sí | El diff no incorpora código ni fragmentos de Gambatte, Delta, mGBA o `iOS/` de SameBoy. |
| Sin red en iOS | sí | El rango no modifica la app iOS, manifiestos ni permisos de red. |
| Sin globals nuevos en `core/` | sí | `core/` no fue modificado. |
| Evidencia JVM 241/241 | sí | Los XML existentes registran 241 pruebas, 0 fallos, 0 errores y 0 omitidas. |
| Evidencia instrumentada 191/191 | sí | El XML existente declara 191 pruebas, 0 fallos/errores/omitidas e incluye ambos tests nuevos. |
| Kill-test 50/50 | no | Declarado en la evidencia, pero no pude reejecutarlo ni obtuve un artefacto independiente equivalente. |
| Prueba manual J11 en teléfono | no | Continúa expresamente pendiente. |

## Notas

- No pude reejecutar las pruebas instrumentadas: el sandbox impidió iniciar el daemon de ADB porque no podía crear su log temporal. Esto es una limitación del entorno, no un hallazgo del proyecto.
- Los XML existentes son coherentes con la evidencia: JVM 241/241 e instrumentados 191/191. Los dos tests nuevos aparecen y pasaron.
- `git diff --check 73de293..HEAD` solo informa una línea en blanco final en el informe anterior; no afecta al código.
- No consideré los archivos sin seguimiento `.codex/` y `docs/auditorias/A5-android-codex-v7.md` como parte de los commits auditados.
- Aceptando A5V6-H1/H3 y el caso excepcional A5V7-H1 como riesgos residuales, **A5 puede pasar a prueba manual en teléfono**. Antes de A8 conviene cerrar A5V7-H1 y corregir la incoherencia documental.
