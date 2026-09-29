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
