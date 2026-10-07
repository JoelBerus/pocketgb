# Auditoría N8 nativo · GBA en Android, capa nativa y JNI (Opus, subagente sin historial)

Fecha: 2026-10-07. Rama `n8-android-gba-nativo` en `725fb83` (diff `siguiente-nivel...HEAD`, 20 archivos, con el merge de integración `71e5564`). Rol: solo lectura; ejecución en `/private/tmp/claude-501/audit-n8` y arnés TSan/ASan del auditor en `/private/tmp/claude-501/audit-n8-harness/`. Informe transcrito (formato condensado, contenido sin cambios).

## Veredicto: APROBAR CON CAMBIOS
Sin hallazgos bloqueantes ni altos. H1 (media): con `installDebug` el núcleo GBA va sin optimizar, lo que pone en riesgo la prueba de 60 fps y la medida de ms/frame. H2–H5 (bajas): robustez y calidad de tests y evidencia.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | `cpp/CMakeLists.txt:29-31`; `app/build.gradle.kts:33-37`; `docs/07-instalacion-android.md:15`, `docs/PRUEBAS-JOEL.md:7` | En CMake Debug el núcleo GBA compila sin `-O` (-O0); la guía instala con `installDebug`, así que Kirby a 60 fps y ms/frame se medirían con un núcleo 3–5× más lento que Release (`-O2`). | `compile_commands.json` Debug arm64 sin `-O`; RelWithDebInfo `-O2`. Host `gbatest --bench 600`: scene_11 506 fps (-O2) frente a 180 (-O0); shades 1264 frente a 427. Evidencia: 8,90 ms (Debug) frente a 1,80 ms (runner -O2). | `target_compile_options(pocketgba PRIVATE $<$<CONFIG:Debug>:-O2>)` (valorarlo para `core/`), o documentar que la prueba de rendimiento se hace con Release. |
| H2 | baja | `native_session.c:657-666, 682-700`; `pocketgb_jni.c:976-1010` | Recargas sobre la misma instancia: `native_session_load_gba` solo comprueba `!thread_started`; una segunda carga fallida tras una buena deja READY con la instantánea y los flags de la anterior y un núcleo vacío; si `gba_create` falla en `reset_gba_core` el núcleo conserva la BIOS; en el núcleo suelto una BIOS cargada antes sigue activa al recargar sin BIOS. No explotable (Kotlin exige `SessionState.New`). | Lectura; `gba.c:71-100,181`. | Rechazar si `state != NEW` o volver a NEW liberando la instantánea; `CoreBridge(GBA)` con núcleo nuevo por carga, o documentarlo. |
| H3 | baja | `androidTest/.../video/GameSurfaceTest.kt:152-183` | La prueba de superficie GBA no verifica el 3:2 ni el contenido del blit; ninguna prueba lee píxeles de la superficie en GBA. | Lectura. | `PixelCopy` de la `SurfaceView` (bandas y 3:2 en INTEGER/FILL), o pendiente explícito para N8 Kotlin. |
| H4 | baja | `N8-nativo-evidencia.md` §Símbolos | `total=278` es anterior a la integración; HEAD da 280 (A9 añadió el RTC); `gba/arm=79`, `sha256=1`, `dup=0`. | `llvm-nm -D --defined-only` sobre las 8 `.so`. | Actualizar la cifra o indicar el commit. |
| H5 | baja | `native_session.c:137-142` | En armeabi-v7a y x86 `time_t` es de 32 bits: más allá de 2038 `native_local_time` devuelve UTC sin convertir. Mismo límite que `time(NULL)` en GB. | Lectura. | Riesgo residual documentado. |

## Criterios (resumen)
CMake (4 ABI, `-Werror`, `sha256=1`, `dup=0`): sí. Concurrencia (`core_*` solo en el hilo nativo o aparcado; TSan con EEPROM 8 KiB + RTC y Flash 128 + RTC: 0 avisos; ASan+UBSan: 0): sí. Entrada hostil por JNI (barrido de longitudes de `.sav` 0–8272: solo 512/528 aceptados; rechazos sin cambiar la partida): sí. BIOS: parcial (rechazo y SHA iguales en Kotlin, C e iOS; ruta positiva no verificable sin BIOS oficial). Regla 6 (formato idéntico a iOS; EEPROM creciendo en marcha: copias de 528 u 8208 B): sí. Integración A9/N2 (opuestos 16 bits; RTC a hora local, emulador en UTC−5): sí. Hashes de `arm.gba`, flash128, stripes, shades y scene11 = runner nativo: sí. JVM 483/0; instrumentados `emulator` 63/0, `GameSurfaceTest` 4/0, `input` 29/0 (más `NativeAudioTest` 4, `GameSessionTest` 13, `GameSessionHardeningTest` 18, `SaveCyclesTest` 1); kill-test GB 50/50: sí. Reglas 1, 2, 4 y 5: sí. ms/frame en el teléfono, partida GBA con espejo, kill-test GBA, capturas y Kirby: pendientes (N8 Kotlin y Joel).

## Notas
- `arm.gba` pasa en 2 frames (su hash de 120 frames = el de thumb.gba); las comparaciones con contenido real son scene11, stripes y shades.
- Para N8 Kotlin: un estado con la EEPROM confirmada en 8 KiB solo carga tras un `.sav` de 8 KiB (orden `.sav` → estado automático); si la DMA confirma 8 KiB sin escribir, no hay `.sav` y la continuación da `StateConfig`.
- Fuera de alcance (iOS): `GBACoreBridge.sramLoad` aplica el RTC antes de validar el medio y no lo restaura si `gba_save_load` falla; Android es más estricto.
