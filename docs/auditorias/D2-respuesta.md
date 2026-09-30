# D2 · Respuesta a la auditoría Opus ([D2-opus.md](D2-opus.md))

## Ronda 1 → commit `2f01e83`
| ID | Estado | Qué se hizo |
|---|---|---|
| H1 | corregido | `SaveMirror.snapshot()` detecta el placeholder `.x.sav.icloud` y el estado `.notDownloaded`, pide la descarga y hace una lectura coordinada. `SaveOpening.prepare`: con `.unavailable` y sin partida local lanza `Refusal.mirrorNotDownloaded` y el juego no se abre; con local, el espejo queda en `nil` toda la sesión y se muestra el aviso `.mirrorUnavailable`. Test `iCloudOnlyMirrorIsUnavailableAndNeverWritten`, con un `persist` posterior que no crea `juego.sav`. |
| H2 | corregido | `AppState.open` lee el ROM y el espejo en el mismo `Task.detached`; `EmulatorSession` recibe la instantánea ya leída. |
| H3 | corregido | `SaveResolution` marca `quarantineLocal`: la local incorrecta se copia intacta a `backups/<huella>.wrong-size-<t>-<id>.sav`, fuera de la rotación, y se muestra el aviso `.localQuarantined`. Test byte a byte. |
| H4 | corregido | `addBackup` no añade un contenido que ya esté en `.1`–`.5`. Test de tres aperturas que deja un solo backup. |
| H5 | corregido | Si otro ROM de la carpeta resuelve al mismo `.sav`, el espejo va a `nil` y se muestra el aviso `.mirrorShared`. |
| H6 | corregido | `enterForeground` llama a `restore()` si la carpeta no está disponible; botón "Reintentar". |
| H7 | corregido | `finishedGeneration`. |
| H8 | corregido | `SaveLoadWarning` con título y texto; el router usa el mismo texto que la app. |
| H9 | corregido | `SaveOpening` aplica la decisión con E/S real; cuatro tests de integración nuevos. |
| H10 | corregido | Las comprobaciones de `failAfterStep` van dentro de `#if DEBUG`. |
| H11 | corregido | Solo `.notDownloaded` y el placeholder cuentan como "solo en iCloud". |
| H12 | aceptado | La copia local es la autoritativa; si se juega en dos dispositivos a la vez, el espejo lo gana el último en escribir. |
| H13 | corregido | `FileHandle.read(upToCount: 8 MiB + 1)`. |

## Ronda 2 → commit `8d2e73c`
| ID | Estado | Qué se hizo |
|---|---|---|
| N1 | corregido | `restore()` sin bookmark no cambia `.unavailable`; en DEBUG, con biblioteca de demostración, `enterForeground` no reescanea. |
| N2 | corregido | Espera de hasta 20 × 0,5 s a que llegue un `.sav` de iCloud, fuera del hilo principal (`snapshot(polls:)`; el test pasa 0). |
| N3 | corregido | La cuarentena lleva un sufijo UUID. |
| N4 | corregido | Comentario en el `catch` de `Refusal`. |
| N5 | corregido | La colisión se compara en minúsculas. |

## Auditoría Codex

| ID | Estado | Qué se hizo |
|---|---|---|
| H1 | **corregido** | La escritura local atómica permanece en `PocketGB.saves-local`; el espejo usa la cola serie independiente `PocketGB.save-mirror`. El flush síncrono de pausa, background, salida y memoria baja solo vacía/espera la cola local y nunca ejecuta ni espera `NSFileCoordinator`. `SaveTarget` coalesce el espejo: una operación ya iniciada termina, pero de todos los contenidos acumulados mientras está bloqueada solo conserva el último. El reintento al abrir también se agenda en la cola del espejo. `AppState.enterBackground()` abre y cierra en el hilo principal una `UIApplication` background task alrededor de `session.pause()`; su expiration handler se fabrica en un método `nonisolated static` para no heredar `@MainActor` al ejecutarse desde otro hilo. El test `blockedMirrorDoesNotBlockLocalFlushAndCoalescesLatest` inyecta un escritor detenido por semáforo, comprueba que los flushes locales terminan y actualizan la copia autoritativa, y que al liberarlo el espejo recibe el primer contenido ya iniciado y el último pendiente, omitiendo el intermedio. |
| H2 | **pendiente de evidencia** | Claude debe regenerar `D2-evidencia.md` y ejecutar el CI completo sobre el commit final: tests, build Release y capturas sin cancelación. |
- H2 (evidencia): regenerada por Claude en el Mac (ver D2-evidencia, "Verificación en el Mac tras la auditoría Codex"): el CI de GitHub está bloqueado por facturación.

## Auditoría Opus de la corrección (`cf62b31`)

| ID | Estado | Qué se hizo |
|---|---|---|
| A1 | **corregido** | `SaveStore` mantiene junto a la copia local un registro JSON atómico (escrito con `AtomicFile`, máximo 8 huellas SHA-256) de contenidos propios del espejo. Usa una entrada write-ahead y la confirma al terminar la escritura, de modo que también cubre la muerte del proceso entre el reemplazo remoto y la confirmación local. `SaveOpening` reconoce esos contenidos: si difieren de la local, la local gana, no se crea un backup espurio y se reencola al espejo; un contenido no reconocido conserva la resolución por fecha y el backup del perdedor. `MirrorChannelRegistry` comparte una única cola/coalescer por huella de ROM, así dos sesiones del mismo juego no pueden escribir el espejo fuera de orden. Los tests `staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens` y `newerExternalMirrorWinsAndBacksUpLocal` cubren ambos caminos con E/S real. |
| A2 | **corregido** | `AppState.enterBackground()` conserva la `UIApplication` background task después de `session.pause()`: `EmulatorSession.whenMirrorIdle` la termina cuando el canal del espejo deja de tener una escritura en vuelo, o UIKit la termina mediante el expiration handler. `end()` sigue siendo idempotente y ambos callbacks se fabrican en `nonisolated static` para no heredar `@MainActor` en Swift 6. |
| A3 | **corregido** | El test `synchronousSessionFlushDoesNotWaitForBlockedRealMirror` crea una `EmulatorSession` real con un ROM sintético libre que escribe SRAM, un `SaveMirror` real y solo el escritor remoto inyectado/bloqueado. Verifica la barrera auténtica `flushSRAM(sync:)`/`localSaveQueue`: `pause()` termina y la copia local atómica contiene el byte escrito mientras el espejo continúa bloqueado. El test anterior de coalescing también usa ahora un espejo no nulo, como producción. |

### Revisión de Claude del WIP de Codex (`d5eeeae`) y correcciones
Codex dejó marcados A1–A3 como corregidos sin llegar a compilar el target de tests. Revisé el diff completo (`git diff cf62b31 d5eeeae`):
- **Qué está bien, y se mantiene:**
  - El registro write-ahead de huellas propias (`recordMirrorAttempt` → escritura → `recordSuccessfulMirror`, JSON con AtomicFile, máximo 8).
  - La regla "espejo propio ≠ local ⇒ gana la local sin backup espurio", comprobada después de las validaciones de tamaño.
  - El canal del espejo compartido por huella (`MirrorChannelRegistry`) y `whenMirrorIdle` para la background task.
  - La barrera local de `flushSRAM(sync:)`, que no espera al espejo.

| # | Problema encontrado | Corrección |
|---|---|---|
| W1 | El target de tests no compilaba: `try #require(store.load())` con `load()` que lanza. | `try #require(try store.load())`. |
| W2 | `staleOwnedMirrorCannotReplaceNewerLocalWhenGameReopens` se habría colgado. Al reabrir, `retryMirrorIfNeeded(d2)` volvía a encolar el mismo contenido que ya se estaba escribiendo; el canal lo habría escrito dos veces y la segunda escritura se habría quedado esperando un semáforo que nadie señala. En producción era una escritura redundante del espejo. | `MirrorChannel` guarda el contenido en vuelo (`inFlight`) y no reencola el mismo contenido si no hay otro pendiente. Si esa escritura falla, `drain` la reencola igual que antes. |
| W3 | El inicializador con escritor inyectado creaba el canal aunque `mirror` fuera `nil`. En tests, un espejo ignorado o sin descargar se habría escrito igualmente; en producción no afectaba (no se inyecta escritor). | Sin espejo no hay canal, igual que en el inicializador de producción. |
| W4 | El expiration handler de la background task terminaba la tarea con un `Task` asíncrono. UIKit lo llama en el hilo principal y exige terminarla antes de volver; si no, iOS puede matar la app. | `makeExpirationHandler`: en el hilo principal, `MainActor.assumeIsolated { task.end() }`; si no, salta al actor principal. `whenMirrorIdle` sigue usando `makeEndHandler`, porque llega desde la cola del espejo. |

La verificación (build, tests y capturas) corresponde al CI del runner propio sobre este lote: `ci-shots/d2-a1-wip`. Ver `D2-evidencia.md`.
