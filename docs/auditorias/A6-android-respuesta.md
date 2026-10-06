# A6 Android: respuesta a la auditoría (lote A6-R)

Rama `a6-respuesta` sobre `codex/android-port` @ `a9b0dd8`. Auditoría: [A6-android-opus.md](A6-android-opus.md); evidencia actualizada: [A6-android-evidencia.md](A6-android-evidencia.md). Código final: `013910d`.

## Auditoría Opus
| ID | Estado | Commit | Qué se hizo |
|---|---|---|---|
| A6-H1 (alta) | corregido | `921155e` | `GameLauncher` resuelve las opciones al abrir con la huella SHA-256 del ROM y su `0x143` mediante un proveedor inyectado (`GameplaySettingsRepository.emulationProvider()`, cableado en `GameplayViewModelFactory`); `EmulationSelection.toOptions()` mapea `SelectedModel` a `GbModel` (GBC siempre AUTO, paleta por juego sobre la global). `EmulationSettingsAppliedTest` (ViewModel + repositorio): rojo sin la corrección (2 de 4), verde con ella. |
| A6-H2 (media) | corregido | `2689068` | `settings.state.compatPaletteChanges(huella)` (`map` + `distinctUntilChanged` + `drop(1)`) en lugar de `snapshotFlow`; `CompatPaletteChangesTest` (JVM). |
| A6-H3 (media) | corregido | commit de documentación | Evidencia corregida: K6 a K8, recuento de capturas (37 + 61 + 8 = 106) y recuentos regenerados sobre `013910d`. |
| A6-H4 (baja) | corregido | `7da430b` | `EmulatorSession.copyFrame()` (`requirePaused` + `withHandle`) usado por `captureParkedFrame`; prueba en `EmulatorSessionTest`. |
| A6-H5 (baja) | corregido | `85b9d2c` | «Borrar portadas» usa `ArtworkStore.shared(ctx).removeAll()` en IO (invalida caché y versión); se eliminó `StorageUsage.clearArtwork` y su prueba (la cubre `ArtworkStoreTest`). |
| A6-H6 (baja) | corregido | `0c88002` | 14 claves sin uso borradas (se conserva `compat_palette_names`, que se usa por `R.array`); `LoadStateConfirmDialog` muestra `state_load_body_rescue` para `StateSlot.RESCUE`; `LoadStateConfirmDialogTest`. |
| A6-H7 (baja) | corregido | `ee901f4` | `_Static_assert(GB_ERR_BUFFER_TOO_SMALL == 16, ...)` junto a `JNI_ERR_INVALID_ARGUMENT`. |
| A6-H8 (baja) | corregido | `013910d` | Cada escaneo completo poda `knownIds` a los ids presentes (no poda con listado vacío, por si un proveedor falla de forma pasajera); prueba en `LibraryViewModelTest` (roja sin la poda). |

## Segunda opinión DeepSeek
| ID | Estado | Detalle |
|---|---|---|
| DS-H1 (media) | corregido | `tools/android-save-kill-test.sh 50` ejecutado con el APK Debug de A6-R: `OK=50 FAIL=0` (salida en la evidencia). |
| DS-H2 (baja) | = A6-H4 | Corregido en `7da430b`. |
| DS-H3 (nota) | nota de alcance | `GameSession.kt` cambió más que `saveCurrentToAuto` y el callback de portada: el refactor del hilo de reparación es L0 (auditado aparte, A6-L0-Opus: APROBAR) y los delegados `setVolume`/`setScaleMode`/`setCompatPalette` son de L2 (sin impacto en la ruta de guardado). A6-R solo añadió `captureParkedFrame` → `copyFrame()` (solo lectura). |
| DS-H4 (nota) | nota de alcance | El rango auditado incluye commits de A7 (mando físico); no se juzgan como A6. |

## Limitaciones
Tests instrumentados reejecutados: paquetes `game` (90) y `ui` (68); el resto de paquetes no se reejecutó. El proveedor de ajustes lee el estado en memoria del repositorio: si se abre un juego antes de que termine su carga asíncrona inicial se usarían los valores por defecto (ventana de milisegundos al arrancar la app). El cableado de `GameplayViewModelFactory` (una línea) no tiene prueba propia; la prueba usa el mismo proveedor con el lanzador.
