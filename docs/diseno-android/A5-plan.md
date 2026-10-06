# Android A5 — Plan de partidas y estados

Plan elaborado por un subagente Opus el 2026-10-05 sobre `codex/android-port` @ `5c94c03`; este documento lo condensa y fija las decisiones de Joel. Complementa [SPEC.md](SPEC.md) §2.2, §5.2–5.4, §6 y §7. Referencia iOS: `ios/PocketGB/Saves/` (`AtomicFile`, `SaveStore`, `SaveMirror` con `SaveResolution`/`SaveOpening`/`MirrorChannel`/`SaveTarget`, `StateStore`, `SavesIndex`), `EmulatorSession.swift:458-537` (`checkSRAM`/`flushSRAM`), `AppState.swift:209-486` y sus tests (`AtomicFileTests`, `SaveMirrorTests`, `StateSRAMTests`, `StateStoreTests`).

## Decisiones de Joel (2026-10-05)
| # | Decisión |
|---|---|
| J1 | Espejo SAF: escritura **in situ** con `openFileDescriptor(uri, "wt")` (nunca `"w"`), respaldo local previo, fsync y verificación releyendo longitud y SHA-256. No se declara atómico. |
| J3 | Backup de Android: se **incluye `saves/`** (nube y transferencia); se excluyen de la nube `saves/backups/` y `states/`; transferencia entre teléfonos incluye todo. Solo con `<exclude>`, jamás `<include>` (volvería lista blanca). Riesgo documentado: tras restaurar, la mtime nueva puede hacer ganar a una local antigua; el espejo queda en `backups/.1`. |
| J4 | Restaurar backups (Ajustes › Partidas) entra **en A5** en versión funcional (pulido visual en A6). Deshabilitado si esa huella tiene sesión abierta. |
| J11 | Prueba de cierre forzado en **emulador (script, 50 iteraciones) y en el teléfono** con Rojo/Amarillo. |
| J2 | (por defecto, recomendado) Si el `.sav` junto a la ROM cambia por fuera durante la partida: copia local, dejar de espejar en la sesión y avisar. |
| J5 | Flush en `ON_PAUSE` acotado a 3 s; si se agota: juego en pausa, guardado pendiente, reintento en `ON_STOP`/al volver, y fallo si se intenta salir. |
| J6 | Al "salir sin guardar" tras fallo local: intentar antes el estado AUTO como rescate; doble confirmación de riesgo. |
| J7 | Carpeta solo con lectura: importar `.sav` si existe, nunca escribirlo, avisar (`MirrorReadOnly`). |
| J8 | "Continuar" no carga el estado AUTO (como iOS). |
| J9 | Espejo también en proveedores en la nube; sin fecha gana la local; lectura fallida sin local impide abrir. |
| J10 | `.sav` de tamaño incorrecto como única copia: se abre, no se guarda nada, se avisa. |

## Invariantes de hilo (reglas duras 3, 4, 6)
- I1: las transiciones de `EmulatorSession` solo en el hilo principal. I2: el único otro hilo es el de guardado (`pocketgb-saves`), solo `sramDirtySequence`/`copySram` (protegidas por el mutex nativo). I3: el cierre marca el guardado cerrado bajo la compuerta de cada copia nativa, confirma que el hilo de guardado terminó (`isAlive`) y solo entonces llama a `nativeSessionDestroy`; si el hilo no termina, no se libera (un "reaper" lo hace al salir el hilo) y se devuelve `CloseResult.SaveThreadStuck`; `handle` es `@Volatile` y TODA llamada nativa con handle va bajo `handleLock.read` (corrección de la 1ª vuelta de auditoría). **I4: copiar la SRAM y escribirla ocurren en el mismo hilo de guardado**; el flush síncrono es `executor.submit{…}.get(3 s)`.
- Nativo (`native_session.c`): `sram_snapshot` (malloc en load, nunca en el bucle), `sram_dirty_seq`, petición de snapshot atendida por el hilo nativo tras `gb_run_frame`; `gb_rtc_set_time(core, time(NULL))` al salir de pausa; `unix_time` pasado en `load`; funciones `rom_info`, `sram_size/load/dirty_seq/copy`, `state_size/save/load`, `copy_framebuffer` (estas últimas solo con sesión PAUSED/READY/STOPPED, si no `NS_BUSY=-1`; `NS_TIMEOUT=-2`). No llamar a JNI con el mutex tomado; validar longitudes en JNI (tope `sram_size` y `gb_state_size`). `core/` no se toca.

## Piezas (paquete `saves/` y relacionados)
- `SaveFileOps` (interfaz inyectable: writeSynced, readBytes(limit), atomicReplace con `Files.move(ATOMIC_MOVE)`, syncDirectory tolerante a `EINVAL`, etc.).
- `AtomicSaveWriter`: pasos 1–5 de SPEC §5.2 (igual→nada; `.sav.tmp`+fsync; rotar `.4→.5…`; copiar actual a `.1.tmp`+fsync+rename; rename `.tmp`→`.sav` + sync directorio). 5 backups.
- `SaveStore` (`filesDir/saves/`: `<fp>.sav`, `backups/<fp>.<n>.sav`, cuarentena `wrong-size-<unix>-<rand8>` nunca rotada ni borrada, `<fp>.mirror-history.json`; `addBackup` con dedupe, `restore(n)` respaldando antes el actual, `recoverOrphans(validSizes)` con **conjunto** de tamaños `{sram, sram+48, sram+44}` si hay RTC).
- `SramFlushPolicy` (puro, reloj inyectado): debounce 1000 ms, red de 60 000 ms, fallo reprograma. `SaveCoordinator`: hilo `pocketgb-saves`, `confirmed` vs `lastQueued`, `flushSync()` → `Saved|Unchanged|Failed|TimedOut`.
- `StateStore` (`filesDir/states/<fp>/{auto,slot1..slot4}.state` + miniatura PNG best-effort, cabecera `PGBS`, tope de lectura), `SavesIndex`, `FrameThumbnail` (intercambiar R y B).
- Espejo: `SaveResolution` (tabla completa de casos portada de iOS), `SaveOpening`/`Refusal`/`SaveLoadWarning`, `MirrorChannel` (executor por huella a nivel de app, coalescer), `SaveTarget`, `saves/saf/SafSaveMirror` (localizar `<base>.sav` sin distinguir mayúsculas; >1 coincidencia ⇒ `Unavailable`; colisión de bases de ROM ⇒ espejo deshabilitado; crear con `createDocument` verificando nombre exacto; escribir `"wt"`; releer y comparar; registrar historial con fecha exacta; todo `RuntimeException` ⇒ fallo de espejo que nunca afecta a la local). `RomEntry.folderDocumentId` + `DocumentTree.rootId`.
- Save states en `GameSession`: `saveState(slot)` (sesión aparcada), `loadState(slot)` (guardar actual en AUTO si procede; `previous`; `loadStateRaw`; `flushSync`; si falla, rollback a `previous`). Un estado nunca sustituye la SRAM por su cuenta. AUTO al salir tras flush correcto; su fallo no impide salir.
- Apertura: `GameLauncher` (SPEC §2.2), `GameplayViewModel` (dueño de `GameSession`, sobrevive a rotación; nunca `remember`), `PocketGBApp` pinta `GameplayHost` a pantalla completa si hay sesión (sin ruta en el back stack), `BackHandler` pausa/menú, `SessionLifecycleObserver` con flush acotado (el juego queda en pausa al volver), `PauseSheet`, `StatesSheet`, `ExitSaveFailedDialog` (Reintentar / Seguir jugando / Salir sin guardar… con 2ª confirmación; **a diferencia de iOS, nunca se cierra limpio ignorando un fallo local**), `recordPlayed` en `LibraryViewModel`, Ajustes › Partidas (restaurar). **Jugar se habilita en el último commit funcional**, con todo lo demás en verde.

## Etapas
1. Contratos (`SaveFileOps`, `SaveMirror`, `SramSource`), reglas XML de backup y `ManifestPolicyTest`.
2. Local atómico + `StateStore` + `SavesIndex` + `FrameThumbnail` + `ProcessKillTest` (JVM hijo matado con `destroyForcibly()` ×200).
3. `SaveResolution`, `SaveOpening`, `MirrorChannel`, `SaveTarget`, `SramFlushPolicy` (JVM).
4. Nativo/JNI/`EmulatorSession` (SRAM y estados) + `NativeSaveBridgeTest` + ASan.
5. Espejo SAF + `TestDocumentsProvider` ampliado (modos `w/wt/rw`, createDocument/rename/delete, fallos inyectables) + `SafMirrorTest`.
6. `SaveCoordinator` + `GameSession` + `GameSessionTest`.
7. `GameLauncher`, ViewModel, UI de pausa/estados/salida, `recordPlayed`, observer, Ajustes › Partidas; habilitar Jugar; 100 ciclos.
8. `tools/android-save-kill-test.sh` (50 iteraciones, `am force-stop`/`kill -9`, verificación por logcat), capturas, `docs/auditorias/A5-android-evidencia.md`, `docs/ESTADO.md`.

## Pruebas mínimas
JVM +60 (tabla de `SaveResolution`, atomicidad con fallos inyectados en cada operación, rotación, cuarentena, `recoverOrphans` con RTC, historial, política de flush, orden de I4 con latch, `ProcessKillTest`); instrumentadas (SRAM contador MBC1 y variante MBC3+RTC sintéticas, puente nativo, `GameSession`, espejo SAF con modos de fallo, lifecycle incl. `recreate()` sin cerrar sesión, UI de pausa/estados/fallo, 100 ciclos abrir-jugar-pausar-guardar-salir sin `.tmp`, ≤5 backups, hilos estables). Medir `flushSync` en `ON_PAUSE` (p50/p99). Ninguna ROM comercial; todo sintético.

## Trampas
Rotación (la sesión no vive en `remember`); ANR (flush acotado y medido); carrera I4; uso tras liberar; SAF sin semántica POSIX (`"w"` puede no truncar, create/rename cambian nombres, fsync en pipes falla, mtime remota poco fiable, revocación a mitad de sesión); `File.renameTo` oculta errno; un solo `<include>` vacía el backup; cuota de 25 MiB; `.sav`/`.state` como entrada no confiable (topes y validación en JNI); RTC con `unix_time=0` hoy.
