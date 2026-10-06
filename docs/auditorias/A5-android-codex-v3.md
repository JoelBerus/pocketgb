## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A5V3-H1 | bloqueante | `android/app/src/main/java/com/joelbermudez/pocketgb/saves/SavesBrowser.kt:41`; `android/app/src/main/java/com/joelbermudez/pocketgb/game/GameLauncher.kt:175`; `android/app/src/main/java/com/joelbermudez/pocketgb/saves/BlockedFingerprints.kt:12` | `BlockedFingerprints` conserva una carrera TOCTOU: comprobar `isBlocked()` y abrir/restaurar no constituyen una operación atómica. Una sesión puede adquirir el bloqueo después de que otra operación supere la comprobación. | `SavesBrowser.restore()` consulta el bloqueo en la línea 43 y después restaura por separado en la 47. `GameLauncher` lo consulta en la línea 177 y luego recupera temporales, resuelve la partida y crea la nueva sesión. Mientras tanto, `onCleared()` o `repairPrevious()` pueden ejecutar `acquire()`. El lock de `SaveStore` serializa las escrituras físicas, pero no invalida una restauración ya autorizada: la restauración puede escribir C y la escritura rezagada A ejecutarse después, dejando otra vez A como principal. Asimismo, dos `GameplayViewModel`/actividades pueden abrir la misma huella porque el registro solo contiene guardados pendientes, no propiedad activa. Las pruebas bloquean primero y luego restauran/abren; ninguna intercala `acquire()` exactamente después de `isBlocked()`. Esto incumple la garantía declarada de que una sesión antigua no puede pisar una restauración o sesión nueva. | Sustituir check+acción por una concesión atómica por huella. La apertura debe adquirir una propiedad exclusiva antes de tocar saves y conservarla durante toda la vida de la sesión; `onCleared` debe transferir ese mismo token al registro, no adquirir otro después. Restaurar debe obtener un permiso exclusivo corto que incluya comprobación y mutación. Añadir tests deterministas con latch que intercalen el bloqueo justo después de la comprobación tanto en `restore()` como en `openBlocking()`, y verificar que nunca queda una escritura tardía como principal. |
| A5V3-H2 | baja | `docs/auditorias/A5-android-evidencia.md:192`; `docs/ESTADO.md:5` | A5V2-H4 no quedó corregido respecto del commit auditado. | HEAD es `ffd6d977ed19861b6ab331931c2cb69dbba6e23c`, pero la evidencia todavía dice que las correcciones están “sin commitear” sobre `acfe806`, y `ESTADO.md` repite “SIN commitear”. La fila de H4 afirma que ambos documentos reflejan el estado real, lo cual es falso. | Registrar `ffd6d97` como commit de las correcciones de la segunda vuelta y actualizar el estado del proyecto. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---:|---|
| Sin ROMs comerciales, boot ROMs, `.sav`, estados, APK o AAB en el historial | sí | Conforme. `git log --all --stat`, nombres del historial y objetos alcanzables no muestran archivos prohibidos. |
| Sin código GPL/AGPL copiado | sí | No encontré incorporaciones ni atribuciones sospechosas en el diff. `core/` e `ios/` no cambian. |
| Sin red en la app | sí | No se añadió `INTERNET` ni APIs de red. El resultado Release registrado también informa cero permisos de red. |
| Sin estado global mutable nuevo en `core/` | sí | `core/` no cambia en `acfe806..ffd6d97`. |
| Seguridad del ROM y save states | sí | La corrección no modifica parser, JNI ni `gb_state_load`; la ROM sigue limitada a 8 MiB antes del core. |
| Corrección Pan Docs / ciclos / registros | sí | No existen cambios de emulación en este commit. |
| A5V2-H1: sesión `KeptOpen` conserva dueño, reintenta y cierra | parcialmente | El flujo secuencial está implementado y las pruebas correspondientes pasan en los XML. La protección frente a reapertura/restauración no es atómica: A5V3-H1. |
| `OrphanSessionRegistry`: reintentos y cierre | sí | Backoff 1–30 s, un solo executor, AUTO antes del cierre y espera del hilo atascado. No encontré acumulación de reintentos ni fuga en el camino probado. |
| `BlockedFingerprints`: liberación y conteo | parcialmente | El contador funciona en el camino secuencial y permanece bloqueado mientras falla la reparación. No protege la transición check→operación: A5V3-H1. |
| A5V2-H2: snapshot sin TOCTOU con el escritor | sí | `withIdleLease()` toma reposo+lease bajo el mismo lock, retiene escrituras y las arranca en `finally`. La prueba encola durante `snapshot()` y fallaría con el código anterior. No encontré deadlock en dos leases: los contendientes vuelven a competir al despertarse. |
| Escrituras retenidas por `withIdleLease` arrancan al liberar | sí | `releaseLease()` marca `workerRunning` y ejecuta `drain`; también ocurre si la lectura lanza. Cubierto por prueba específica. |
| A5V2-H3: rollback fallido repone A detrás de B | sí | `sramBefore` es independiente; `repairPrevious()` encola FIFO detrás de B, persiste atómicamente y mantiene la huella bloqueada hasta completar. El test con latch comprueba A principal y B respaldada y sí fallaría al omitir la reparación. |
| Reparación tras cierre del executor | parcialmente | La lógica espera la salida real y escribe directamente, pero la propia evidencia reconoce que esta rama no tiene prueba específica. |
| Interacción `handleLock` / save thread / `operations` Mutex | sí | No encontré un ciclo de locks concreto en las rutas revisadas. |
| Restaurar backups sin riesgo de escritura tardía | no | Existe la ventana descrita en A5V3-H1. |
| Evidencia “falla sin la corrección” | parcialmente | H1, H2 y H3 tienen pruebas plausibles y resultados de reversión documentados. H1 no prueba la carrera concurrente check→`acquire`; H4 sigue desactualizado. |
| JVM | sí, por resultados existentes | 237 casos, 0 fallos y 0 errores. Los XML contienen las pruebas nuevas de lease y bloqueo. |
| Instrumentados | sí, por resultados existentes | 183 casos, 0 fallos y 0 errores. Los XML contienen las tres pruebas nuevas citadas. |
| Kill-test en emulador | no, ejecución propia | La evidencia registra 50/50; no pude reejecutarlo. |
| Prueba manual J11 en teléfono | no | Sigue explícitamente pendiente. |

## Notas

- El sandbox no permitió crear un directorio temporal (`mktemp: Operation not permitted`), por lo que no pude realizar una corrida fresca sin modificar el repositorio. Esto no es un hallazgo del proyecto.
- Sí inspeccioné los resultados existentes: 237 pruebas JVM y 183 instrumentadas, todas verdes, y confirmé que contienen los casos nuevos citados.
- `withIdleLease`, la reparación FIFO de A5V2-H3 y el dueño de sesiones huérfanas corrigen los defectos secuenciales de la segunda vuelta. El rechazo se debe a que el nuevo mecanismo de bloqueo no hace atómica la autorización con la apertura/restauración, precisamente en una ruta donde una escritura tardía puede cambiar la partida principal.
- No usé como evidencia los informes v3 no rastreados presentes en el árbol.
