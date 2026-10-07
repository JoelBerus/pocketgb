## Veredicto: RECHAZAR

La corrección de A5V5-H1 y su prueba son válidas, pero el barrido completo encontró rutas excepcionales que todavía pueden liberar la huella prematuramente o retenerla indefinidamente.

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A5V6-H1 | bloqueante | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:402-416` | El hilo de reparación todavía libera el lease sin demostrar que la reparación terminó si ocurre un `Error` dentro de su propio manejador de reintentos. | El `catch (Throwable)` protege el intento principal, pero `Log.w`, el cálculo/backoff y `submitOnSaveThread` se ejecutan dentro del propio `catch`. Un `Error` escapado desde esa sección alcanza el `finally`, que llama incondicionalmente a `dropLease()`. En ese caso B puede seguir escribiendo o A no haberse restaurado, pero una nueva sesión/restauración ya podría adquirir la huella. El comentario “solo se suelta… con éxito” no está garantizado por el código. | Registrar explícitamente la confirmación de reparación y no liberar jamás por terminación excepcional. Tras un fallo fatal, transferir la reparación/lease a un supervisor estable o conservar la exclusión hasta confirmar que el hilo de guardado terminó y A quedó restaurada. Añadir prueba que inyecte un `Error` durante el reintento. |
| A5V6-H2 | bloqueante | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:516-523` | `tryClose()` libera el lease si `SaveCoordinator.shutdown()` lanza, sin comprobar que el hilo de guardado terminó. | El `catch (Throwable)` ejecuta directamente `dropLease()` y relanza. `shutdown()` puede haber marcado el coordinador cerrado pero fallar antes de completar `awaitTermination`/`join`; una escritura antigua podría seguir viva. Esto rompe la misma invariante corregida para `SaveThreadStuck`. | Ante una excepción de `shutdown`, comprobar `isSaveThreadAlive`. Si sigue vivo, transferir el cierre a un reaper que espere sin abandonar ante interrupciones; cerrar sesión y soltar el lease únicamente después de confirmar su salida. |
| A5V6-H3 | alta | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:368-373,421` | La creación/puesta en marcha del hilo de reparación no está protegida y puede dejar permanentemente un hold adicional. | `holdLease()` ocurre antes de `submitOnSaveThread`, construir el `Thread` y `start()`. Solo se captura `IOException` del submit. Un `SecurityException`, `OutOfMemoryError` u otro fallo de construcción/arranque ocurre antes de entrar en el `try/finally` del hilo: `done` nunca se completa y el hold adicional nunca se decrementa. | Encapsular todo el arranque con transferencia explícita de propiedad. Si el hilo no empieza, ejecutar/supervisar la reparación por otra ruta que drene B y restaure A; nunca abandonar el hold sin resolver la escritura pendiente. Añadir una factoría de hilos inyectable y una prueba de fallo en `start()`. |
| A5V6-H4 | alta | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameplayViewModel.kt:353-370` | `onCleared()` entrega la sesión al registro antes de iniciar el rescate, pero no maneja el fallo al encolarlo. | Tras `orphans.claim(game)`, `rescue { … }` puede lanzar `RejectedExecutionException` o un `Error`. Entonces no se llama a `orphans.settle`, el registro conserva la sesión y nadie programa el rescate: la sesión y su lease pueden quedar retenidos para siempre. `GameRescue.run()` llama directamente a `executor.execute`. | Capturar fallos de envío y transferir inmediatamente el trabajo al propio `OrphanSessionRegistry`, o proporcionar una operación atómica `claimAndSchedule`. Probar un ejecutor de rescate que rechace la tarea. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| A5V5-H1: una interrupción no libera ni cancela la reparación | sí | Corregido. `InterruptedException` marca `interrupted`, continúa esperando/reintentando y restaura la marca únicamente al final. |
| El test nuevo cubre la regresión | sí | Bloquea B, cierra la sesión, interrumpe `pocketgb-save-repair`, comprueba hilo vivo, lease retenido y adquisición rechazada; después libera B y verifica A como principal. |
| El test fallaría con el código anterior | sí | El código anterior salía ante la interrupción y ejecutaba `dropLease()`; fallarían las comprobaciones tras los 500 ms. La evidencia además registra la ejecución revertida: 1 fallo de 14. |
| Toda liberación del lease espera a que terminen las escrituras antiguas | no | A5V6-H1 y A5V6-H2. |
| Ninguna ruta deja el lease retenido permanentemente | no | A5V6-H3 y A5V6-H4. |
| Reaper interrumpido conserva la propiedad | sí | Continúa `awaitThreadExit()` hasta la salida real y restaura después la interrupción. |
| Registro de huérfanas reintenta excepciones durante el rescate | sí | `attempt()` convierte `Throwable` en `KeptOpen`; queda el fallo previo a encolar/transferir descrito en A5V6-H4. |
| Escritura local atómica y backups | sí | No fueron modificados por `8b30cfd..HEAD`; no observé regresión en el diff. |
| Evidencia documental de las vueltas 4 y 5 | sí | Identifica explícitamente `8b30cfd730de4796f6148809efb04f378be5734e` y `0fe6c8045273ccc521909a83ad7829d81b6d970d`. |
| Estado del proyecto coherente | sí | `docs/ESTADO.md` indica correctamente que falta la 6.ª vuelta y la prueba en teléfono. |
| JVM 241/241 | sí | Los XML existentes suman 241 pruebas, 0 fallos, 0 errores y 0 omitidas. |
| Instrumentados 189/189 | sí | El XML existente declara 189 pruebas, 0 fallos/errores/omitidas e incluye el test nuevo. |
| Kill-test 50/50 | no | Declarado en la evidencia; no pude reejecutarlo ni encontré un resultado independiente equivalente. |
| Prueba manual J11 en teléfono | no | Continúa explícitamente pendiente. |
| ROM/state como entrada no confiable | sí | El diff auditado no modifica parsers, JNI, tamaños ni accesos derivados del ROM. |
| Corrección contra Pan Docs / `docs/03-core-spec.md` | sí | `core/` no cambió; no hay nuevos opcodes, registros ni ciclos que contrastar. |
| Sin ROMs comerciales, saves, estados ni APK versionados | sí | `git log --all --stat` y los nombres históricos no mostraron esos artefactos. |
| Sin código GPL/AGPL prohibido | sí | El diff no incorpora código de las fuentes prohibidas. |
| Sin red ni nuevos globals en `core/` | sí | El diff no toca iOS ni `core/`; no añade permisos o APIs de red. |

## Notas

- No ejecuté Gradle, pruebas instrumentadas ni el kill-test porque producirían archivos dentro del repositorio y el rol es estrictamente de solo lectura. Esto no constituye un hallazgo.
- Los artefactos existentes son plausibles y posteriores al cambio: JVM 241/241 e instrumentados 189/189; el XML instrumentado contiene exactamente `interruptingTheRepairThreadNeverReleasesTheLeaseNorCancelsTheRepair`.
- `git diff --check 8b30cfd..HEAD -- android docs` no mostró errores.
- El árbol contiene `.codex/` y `docs/auditorias/A5-android-codex-v6.md` sin seguimiento; no los consideré parte del commit auditado ni los modifiqué.
