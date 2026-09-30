## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | bloqueante | `ios/PocketGB/Saves/SaveMirror.swift:278`; `ios/PocketGB/Saves/SaveMirror.swift:289`; `ios/PocketGB/Emulator/EmulatorSession.swift:217`; `ios/PocketGB/Emulator/EmulatorSession.swift:372` | Una operación lenta o bloqueada del espejo iCloud puede impedir que se escriba la copia local más reciente. Esto vulnera la regla dura 6. | `persist` guarda local y después ejecuta sincrónicamente `NSFileCoordinator` sobre el espejo en la misma cola serie. Además, al abrir se encola un reintento del espejo. Si esa coordinación queda esperando al proveedor de iCloud, un flush posterior queda detenido en `saveQueue.sync {}` antes de escribir la SRAM actual. Al pasar a background no se abre una background task. Si iOS suspende o termina la app, puede perderse el guardado más reciente aunque la copia local sea declarada “autoritativa”. El test `mirrorFailureKeepsLocalAndRetries` solo simula un error inmediato, no un escritor bloqueado. | Separar la cola local de la cola del espejo. El flush síncrono de background/salida debe esperar únicamente la escritura local atómica; el espejo debe ejecutarse aparte, conservando/coalesciendo el último contenido pendiente. Añadir una prueba con escritor de espejo inyectable que permanezca bloqueado y verificar que la copia local se actualiza y el flush local termina. Usar una background task para el tramo crítico local si puede ejecutarse después de entrar en background. |
| H2 | media | `docs/auditorias/D2-evidencia.md:3`; `docs/auditorias/D2-evidencia.md:21`; `docs/auditorias/D2-evidencia.md:28` | La evidencia no corresponde al código finalmente auditado. | El documento certifica el commit `614792d`, 36 tests y un build Release exitoso. Después se cambiaron diez archivos de producto/pruebas en `2f01e83` y `8d2e73c`. Existe un CI posterior sobre `cd5ab8b`, cuyo código iOS es idéntico a HEAD: los 41 tests unitarios y 4 UI tests terminaron correctamente, pero el run figura como `cancelled` y el build Release fue cancelado a mitad. Por tanto, no hay una ejecución Release completa sobre las correcciones ni sobre HEAD `c314a17`. | Tras corregir H1, ejecutar el CI completo sin cancelación y actualizar `D2-evidencia.md` con el commit exacto, salida de los tests, resultado Release y capturas correspondientes. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---:|---|
| Solo `.gb`/`.gbc`, profundidad máxima uno | sí | Implementación acotada y prueba `findsOnlyGameBoyFilesUpToDepthOne` aprobada. |
| Rechazo de archivos mayores de 8 MiB | sí | Se limita también la lectura real a 8 MiB + 1, evitando el TOCTOU anterior. |
| Lecturas coordinadas mediante `NSFileCoordinator` | sí | ROM, cabecera y espejo usan coordinación. |
| La UI no modifica el ROM | sí | No hay rutas de escritura al ROM; la prueba de contenido y fecha pasó. |
| Primer guardado atómico | sí | Cubierto por prueba. |
| Primer guardado interrumpido y recuperado | sí | El `.sav.tmp` válido se recupera cuando no existe el destino. |
| Fallos después de los pasos 2–4 conservan el `.sav` anterior | sí | Prueba parametrizada para 2, 3 y 4 aprobada. |
| Reemplazo completo deja el anterior en `.1` | sí | Cubierto por prueba. |
| Contenido idéntico no rota | sí | Cubierto por prueba. |
| Siete guardados mantienen únicamente `.1`–`.5` | sí | Cubierto por prueba. |
| `.sav` de tamaño incorrecto no se sobrescribe | sí | Las rutas de `SaveOpening` desactivan el destino o excluyen el espejo; hay pruebas de E/S byte a byte. |
| Conflicto local/espejo selecciona el más reciente y respalda el otro | sí | Ambas direcciones están implementadas y probadas. |
| Fallo del espejo conserva la local y programa reintento | no | Funciona para errores inmediatos, pero una coordinación bloqueada puede impedir posteriores escrituras locales; véase H1. |
| Restaurar un backup respalda primero la partida actual | sí | `AtomicFile.write` deja la actual en `.1`; prueba aprobada. |
| Capturas con acciones recuperables y no destructivas | no | El catálogo terminó y produjo las 14 capturas, pero no pude inspeccionar visualmente los PNG desde este sandbox. |
| Tests temporales no escriben dentro del repo | sí | Usan `FileManager.default.temporaryDirectory/<UUID>`. |
| Compilación Swift 6 y Release del código corregido | no | Tests Debug completos en `cd5ab8b`; el build Release posterior fue cancelado a mitad. |
| Validación real en iPhone/iCloud | no | La propia evidencia la declara pendiente: bookmark tras reinicio, descarga real, cierre forzado, espejo visible, reinstalación, revocación y restauración. |

## Notas

- `git diff origin/main...HEAD` no contiene cambios en `core/`; por ello D2 no introduce nuevos accesos derivados del ROM, cambios en `gb_state_load`, opcodes, ciclos ni estado global mutable.
- No encontré APIs de red, claves ATS, paquetes runtime ni SDKs de terceros nuevos.
- El barrido de refs e historial no encontró ROMs comerciales, saves o estados versionados. Los ROMs libres descargados en `core/tests/roms/` están ignorados y no pertenecen a Git.
- No encontré código D2 copiado de GPL/AGPL. La referencia MIT a tablas de SameBoy está en código previo fuera del diff.
- `git diff --check origin/main...HEAD` no reportó errores.
- Intenté ejecutar Xcode localmente, pero el sandbox impidió crear el caché temporal de `xcrun`. Esto no es un hallazgo del proyecto.
