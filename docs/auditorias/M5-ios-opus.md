# M5 (parte iOS) · Auditoría del subagente Opus (commit 382e625)

## Veredicto: APROBAR CON CAMBIOS

No encontré nada bloqueante. El ring SPSC, el render block, la ruta de SRAM y las reglas duras están bien. Hay un hallazgo medio: el audio puede perderse sin avisar, y el contador de underruns no lo detecta, lo que afecta a la evidencia del criterio 3. Hay otro bajo relacionado.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | `ios/PocketGB/Emulator/EmulatorSession.swift:222-230`; `ios/PocketGB/Audio/AudioOutput.swift:49-62` | Si los callbacks se detienen más de ≈100 ms, la sesión pasa a `audioClock` y para el motor. El audio no vuelve hasta que el usuario pausa y toca "Continuar". No se observa `AVAudioEngineConfigurationChange`. | Caso concreto: se conecta una salida con otra frecuencia de muestreo o con otro número de canales (DAC USB, Bluetooth de coche a 44,1 kHz, AirPlay). Según Apple, el motor se detiene y se desinicializa solo. Después, `audioConsumed.wait` agota 4 × 25 ms, la línea 224 pasa a reloj y el `Task` llama a `audioOutput.stop()`. El juego sigue sin sonido. `grep -rn "ConfigurationChange\|routeChange" ios/PocketGB` no da resultados. | Observar `.AVAudioEngineConfigurationChange` (y, si se quiere, `routeChangeNotification`) y rearrancar el motor en main con cebado, o pausar la sesión con `onPause` como en una interrupción. Otra opción: al volver a reloj, reintentar `audioOutput.start()` cada cierto tiempo. |
| H2 | baja | `ios/PocketGB/App/DebugHUD.swift:10-13` | El HUD muestra underruns, nivel del ring y tiempo de frame, pero no el modo de pacing (reloj/cebado/audio). Con el motor caído o sin arrancar, el contador se queda en 0, porque sin callbacks no hay underruns. | Si `audioOutput.start()` devuelve `false` o se da H1, el modo queda en `audioClock`: `ring.read` no se ejecuta y `underrunCount` no sube. En ese estado, "10 min con 0 underruns" puede cumplirse sin sonido. El ring se queda lleno en 8 192 porque nadie lo consume, y es la única pista indirecta. | Mostrar `audioMode` y un contador de caídas a reloj en el HUD, y pedir en la evidencia del criterio 3 que el modo sea `live` durante toda la prueba. |

## Criterios del hito
| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| 1. `make -C core test HITO=M5` dmg_sound 01–06 | no (fuera de alcance: núcleo, ya auditado en M5-opus) | — |
| 2. `gbtest --wav` 48 kHz sin clipping | no (fuera de alcance, núcleo) | — |
| 3. 10 min en el iPhone con 0 underruns | no | Sin evidencia todavía. El contador existe en el HUD de DEBUG (3 dedos o `-debugHUD`); ver H2 sobre su poder probatorio. |
| 4. A9: grito de Pikachu de oído vs oráculo | no | Hace falta que Joel lo escuche en el dispositivo. |
| Compila (extra) | sí | `xcodebuild ... 'generic/platform=iOS Simulator' -derivedDataPath /tmp/audit-dd-m5 build` → `** BUILD SUCCEEDED **`, 0 errores y 0 warnings del proyecto (Swift 6, strict concurrency complete). No ejecuté los tests. |

## Notas
- **Tiempo real:** el render block solo hace `UnsafeMutableAudioBufferListPointer`, `ring.read` (atómicos + memoria preasignada) y `consumed.signal()` (CAS + `DispatchSemaphore.signal`). Sin reservas, locks, logging ni Obj-C. Detalle menor: escribir `lastLeft/lastRight` (propiedades de clase) pasa por la comprobación dinámica de exclusividad; se evitaría con locales.
- **Ordering del ring:** correcto (productor: relaxed propio / acquire ajeno / release al publicar; consumidor en espejo). `clear()` desde main es seguro: solo lo llama `resume()` con el productor aparcado y el motor parado. `availableFrames` desde el HUD puede dar un valor transitorio incoherente (cosmético).
- **Deadlocks:** no encontré ninguno. `start()/resume()` esperan `audioPrimed` sin timeout, pero el núcleo siempre produce muestras (el APU emite a `sample_rate` aunque esté apagado y la CPU avanza ciclos en locked/stopped/halted): 2 048 frames en ~3 frames emulados. Stop o pausa durante el cebado es imposible (main está bloqueado dentro). `pause()`/`stop()` señalan el semáforo antes de esperar. El `Task` de fallback solo para el motor si el modo sigue en reloj.
- **Partidas (regla 6):** ruta intacta (`flushSRAM`, `confirmed/lastQueued`, `saveFailed`, `requestFlush` sin cambios). La espera de audio vuelve al principio del bucle, que procesa pausa/stop/flush en cada vuelta; `checkSRAM` tras cada frame. Interrupciones y `mediaServicesWereReset` pasan por `enterBackground` → `pause()` con flush síncrono.
- **Swift 6:** los `nonisolated(unsafe)` están justificados (storage y last* por contrato SPSC; `observers` en el deinit). `ButtonMask` con `Atomic<UInt8>` válido en iOS 26.
- **Reglas duras:** sin ROMs en el historial, sin red, sin dependencias.
- **Tests:** `AudioRingBufferTests` cubre wraparound, lleno, underrun con retención y conversión; todo de un hilo.
- **Entorno:** en el worktree, `core/tests/roms` es un symlink sin seguimiento; el patrón del `.gitignore` no lo cubre. No incluirlo en commits.
