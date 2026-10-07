## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A5V5-H1 | bloqueante | `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameSession.kt:397-409` | El hilo de reparación de un rollback todavía libera el lease al ser interrumpido, aunque una escritura anterior o la propia reparación puedan seguir pendientes. Esto permite abrir/restaurar la misma huella mientras una escritura antigua todavía puede instalarse después, con riesgo de pérdida de partida. | `repairPrevious()` retiene el lease mediante `holdLease()`, pero tanto `pending.get()` como `Thread.sleep()` capturan `InterruptedException` y terminan el hilo. Su `finally` ejecuta incondicionalmente `dropLease()`. Interrumpir `pocketgb-save-repair` mientras la tarea FIFO sigue bloqueada libera ese hold; si la sesión ya cerró, el lease llega a cero aunque `pocketgb-saves` aún pueda escribir. Es la misma clase de fallo que A5V4-H1, en otra ruta. No existe una prueba que interrumpa este hilo. | La interrupción no debe considerarse finalización de la reparación. Continuar esperando/reintentando hasta confirmar la reparación, conservando la marca de interrupción para restaurarla al final, igual que el reaper corregido. Añadir una prueba con escritura B bloqueada, reparación A pendiente, cierre de sesión e interrupción de `pocketgb-save-repair`: el lease debe seguir retenido; tras liberar B, A debe quedar como principal y solo entonces permitir reapertura/restauración. |
| A5V5-H2 | baja | `docs/auditorias/A5-android-evidencia.md:360-365` | A5V4-H2 no quedó completamente corregido: la evidencia afirma usar SHAs explícitos, pero no identifica `8b30cfd` como commit de la corrección. | La sección dice “commit que sigue a `9b4e5b1`” y remite a `git log`. La fila A5V4-H2 afirma “Evidencia con SHAs explícitos”, aunque el SHA auditado solo puede deducirse del estado actual de la rama. | Registrar explícitamente `8b30cfd730de4796f6148809efb04f378be5734e` como commit de las correcciones de la cuarta vuelta. |
| A5V5-H3 | baja | `docs/ESTADO.md:8,29` | El estado interno es contradictorio y está atrasado. | La cabecera indica correctamente que falta la quinta vuelta, pero la sección “Hecho” dice “sin 4ª vuelta” y “Siguiente paso” vuelve a pedir la cuarta auditoría. | Actualizar ambas secciones para reflejar esta quinta vuelta y su resultado. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| A5V4-H1: el reaper conserva el lease después de una interrupción | sí | Corregido. `GameSession.kt:523-535` continúa esperando hasta la salida real de `pocketgb-saves`, cierra el handle y solo entonces libera el lease. |
| El test nuevo fallaría con el código anterior | sí | Sí. El código anterior ejecutaba `dropLease()` en el `finally` inmediatamente después de `interrupt()`. La aserción de que la huella sigue poseída tras 500 ms fallaría. |
| El test comprueba el comportamiento seguro del reaper | sí | Comprueba que el lease sigue retenido, que otro adquirente es rechazado, que el reaper permanece vivo y que se libera tras salir el hilo de guardado. No comprueba de extremo a extremo el contenido final frente a una restauración, pero distingue correctamente la regresión. |
| Toda ruta de `dropLease()` espera a que desaparezcan las escrituras antiguas | no | La reparación de rollback libera su hold si se interrumpe: A5V5-H1. |
| `GameSession` cierre normal / `SaveThreadStuck` | sí | El cierre normal libera después de terminar el coordinador; el reaper corregido espera la salida real. |
| `OrphanSessionRegistry` conserva la propiedad durante el rescate | sí | Transfiere la misma sesión sin soltar/readquirir el token. Retirar la sesión de `owned` después de `tryClose()` no libera por sí mismo el lease. |
| `GameLauncher` libera el lease correctamente ante apertura fallida | sí | El `finally` cierra la sesión y después el lease si no hubo transferencia. Durante una sesión abierta, el token pasa a `GameSession`. |
| `SavesBrowser.restore()` mantiene exclusión durante toda la restauración | sí | `withExclusive` abarca validación y mutación y libera en `finally`, una vez terminada la operación síncrona. |
| A5V4-H2: evidencia identifica el commit corregido | no | Sigue usando una referencia relativa; A5V5-H2. |
| Escritura local atómica y backups | sí | No fueron modificados por `8b30cfd`; no observé regresiones en esta vuelta. |
| ROM/state como entrada no confiable | sí | El diff `9b4e5b1..8b30cfd` no modifica parsers, JNI, tamaños ni índices derivados del ROM. |
| Corrección contra Pan Docs / `docs/03-core-spec.md` | sí | `core/` no cambió en esta vuelta; no hay opcodes, registros o ciclos nuevos que contrastar. |
| Sin ROMs comerciales, `.sav`, estados ni APK versionados | sí | La inspección de `git log --all --stat` y de nombres históricos no mostró estos artefactos versionados. Los ROMs Android observados son sintéticos y se generan en código. |
| Sin código GPL/AGPL prohibido | sí | No aparece código nuevo de Gambatte, Delta, mGBA ni del frontend `iOS/` de SameBoy en el diff auditado. |
| Sin red en la app | sí | No se añadieron permisos de red ni APIs de red. El diff no toca iOS. |
| Sin globals nuevos en `core/` | sí | `core/` no fue modificado. |
| Evidencia de pruebas JVM | sí | Los XML existentes suman 241 pruebas, 0 fallos, 0 errores y 0 omitidas. |
| Evidencia de pruebas instrumentadas | sí | El XML existente registra 188 pruebas, 0 fallos, incluido `theSaveReaperKeepsTheLeaseWhenInterruptedUntilTheSaveThreadReallyExits`. |
| Kill-test 50/50 | no | Está declarado en la evidencia, pero no pude reejecutarlo ni encontré un resultado independiente equivalente al XML. |
| Prueba manual J11 en teléfono | no | Sigue explícitamente pendiente. |

## Notas

- No ejecuté Gradle, pruebas instrumentadas ni el kill-test porque generan archivos en `build/` o modifican el emulador, incompatible con el entorno estrictamente de solo lectura. Esto no es un hallazgo del proyecto.
- Los XML de pruebas son plausibles: fueron generados minutos antes del commit `8b30cfd` y contienen el nombre exacto del test nuevo.
- `git diff --check 9b4e5b1..HEAD -- android docs` no reportó errores.
- No consideré el archivo sin seguimiento `docs/auditorias/A5-android-codex-v5.md` como parte de `8b30cfd`.
- La corrección específica de A5V4-H1 es válida. El rechazo se debe a que la ruta paralela `pocketgb-save-repair` conserva el mismo patrón inseguro de liberar propiedad ante una interrupción antes de confirmar que ya no quedan escrituras vivas.
