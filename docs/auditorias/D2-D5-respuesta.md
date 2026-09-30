# Respuesta a la auditoría Codex D2–D5 ([D2-D5-codex.md](D2-D5-codex.md), commit auditado `15c3b49`)

Veredicto: RECHAZAR. Tres hallazgos, todos corregidos.

| ID | Estado | Qué se hizo |
|---|---|---|
| H1 (bloqueante, regla 6) | **corregido** | El registro del espejo guarda, por cada escritura confirmada, la huella **y la fecha de modificación observada justo después de escribir** (`SaveStore.recordSuccessfulMirror(_:observedDate:)`; `MirrorChannel.Request.mirrorURL`). `recognizesOwnedMirror(_:date:)` solo acepta un espejo como escritura propia tardía si coinciden contenido y fecha, o si es la última escritura write-ahead sin confirmar (el proceso murió a mitad). Un contenido histórico restaurado a mano o llegado desde otro dispositivo tiene fecha nueva: se resuelve por fecha y el perdedor se respalda. El formato anterior del registro (solo huellas) se lee sin fecha, así que nunca prueba una escritura propia. Test nuevo: `restoredHistoricalMirrorWithNewDateWinsAndBacksUpLocal`; `staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens` sigue cubriendo el caso tardío. |
| H2 (alta) | **corregido** | `flushSRAM(sync:)` devuelve si la copia local quedó en disco. `EmulatorSession.loadState` guarda antes el estado actual del núcleo; si la partida del estado cargado no se puede guardar, vuelve a cargar el anterior y lanza `StateError.saveFailed`. `AppState` muestra "No se pudo guardar la partida del estado en este iPhone, así que no se ha cargado." Test: `loadStateFailsVisiblyAndRollsBackWhenSavingFails` (carpeta de partidas de solo lectura). |
| H3 (media) | **corregido** | `D2-evidencia.md` documenta la excepción y sus dos tests. |

Verificación: CI del runner propio sobre el commit de estas correcciones (ver `D5-evidencia.md` / `D6-evidencia.md`).
