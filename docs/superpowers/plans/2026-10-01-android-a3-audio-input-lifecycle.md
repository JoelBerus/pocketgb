# Android A3 Audio, Input and Lifecycle Implementation Plan

> **For agentic workers:** ejecutar una tarea por vez con TDD. La auditoría independiente no se inicia hasta A8.

**Goal:** añadir audio AAudio de baja latencia, controles multitáctiles nativos, háptica, avance rápido ×2/×4 y pausas seguras por lifecycle/foco de audio sobre la sesión A2, sin introducir todavía biblioteca ni persistencia.

**Architecture:** `NativeSession` continúa siendo el único dueño de `gb*`. Su hilo produce PCM estéreo a 48 kHz hacia un ring SPSC preasignado; un callback AAudio solo consume el ring y publica progreso mediante contadores atómicos. Kotlin controla comandos tipados y ciclo de vida. Una única `View` táctil mantiene `pointerId → control`, calcula una máscara pura y la envía al hilo nativo; Compose solo dibuja el overlay y presenta estados.

**Tech Stack:** Kotlin/JVM 17, Compose/Material 3, Android Lifecycle, Android `View`/`MotionEvent`, `AudioManager`, NDK r27.3, C11, AAudio API 26, pthread/atomics, JUnit 4 y AndroidX instrumentation.

**Spec:** `docs/diseno-android/SPEC.md` §§2.2–2.3, 3.3–3.4, 4.2–4.3, 6 y 8; iOS de referencia: `ios/PocketGB/Emulator/EmulatorSession.swift`, `ios/PocketGB/Audio/AudioOutput.swift` e `ios/PocketGB/Input/ControlsLayout.swift` (leer, no copiar código).

## Global Constraints

- Un solo módulo `:app`; sin dependencias runtime nuevas ni permiso `INTERNET`.
- El callback AAudio no reserva, bloquea, registra logs, toca JNI/JVM ni llama al core.
- Solo el hilo de sesión llama `gb_run_frame`, `gb_audio_read` y `gb_set_buttons`.
- Ring PCM preasignado, índices SPSC atómicos y silencio explícito ante underrun.
- Audio activo guía el pacing mediante ocupación y contador de consumo; el hilo de emulación duerme en intervalos monotónicos breves sin que el callback tome locks. Si no hay consumo durante cuatro ventanas de 25 ms, la sesión cae a reloj monotónico sin detener la partida.
- ×2/×4 silencia y usa reloj; volver a ×1 vacía/ceba el ring antes de reabrir audio.
- Interrupción, pérdida de foco u `ON_PAUSE` pausa; volver a foreground nunca reanuda automáticamente.
- Las máscaras táctil y física quedan separadas y se combinan con OR. A3 implementa táctil; mando físico completo corresponde a A7.
- Nunca emitir direcciones opuestas. D-pad captura su puntero; A/B/Start/Select permiten deslizar entre controles.
- Háptica solo al flanco de pulsación, nunca por cada `MOVE`; se respeta la configuración del sistema.
- A3 no guarda SRAM todavía. La barrera de pausa queda preparada para que A5 inserte el flush antes de confirmar el estado `Paused`.

## Task 1: Contrato de botones y velocidad en la sesión

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/input/GameBoyButton.kt`
- Modify: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/EmulatorSession.kt`
- Modify: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/NativeLibrary.kt`
- Modify: `android/app/src/main/cpp/native_session.h`
- Modify: `android/app/src/main/cpp/native_session.c`
- Modify: `android/app/src/main/cpp/pocketgb_jni.c`
- Modify: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/emulator/EmulatorSessionTest.kt`

- [ ] Escribir pruebas instrumentadas: máscara válida llega al core en el siguiente frame; una máscara con bits ajenos se normaliza; velocidad solo acepta 1/2/4; ×2 produce aproximadamente el doble de frames que ×1 bajo reloj y pause sigue siendo barrera.
- [ ] Ejecutar RED contra métodos ausentes `setTouchButtons`, `setPhysicalButtons`, `setSpeed` y snapshots Debug.
- [ ] Añadir campos protegidos por mutex para ambas máscaras y `speed`; el hilo aplica `gb_set_buttons(touch | physical)` antes de cada frame. JNI solo actualiza comandos, nunca toca `gb*`.
- [ ] Ajustar deadline por factor y reiniciarlo al cambiar velocidad para no acumular retraso.
- [ ] Ejecutar GREEN y regresión instrumental completa.
- [ ] Commit: `Android A3: añadir comandos de input y velocidad`.

## Task 2: Geometría pura y motor multitáctil

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/input/ControlGeometry.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/input/TouchInputEngine.kt`
- Create: `android/app/src/test/java/com/joelbermudez/pocketgb/input/ControlGeometryTest.kt`
- Create: `android/app/src/test/java/com/joelbermudez/pocketgb/input/TouchInputEngineTest.kt`

- [ ] Escribir pruebas JVM para defaults vertical/horizontal, clamp dentro del área segura, touch target mínimo 48 dp, ocho sectores/diagonales, zona muerta, ausencia de opuestos, A+B, dos punteros simultáneos, captura del D-pad, slide B→A y `cancelAll`.
- [ ] Ejecutar RED por tipos ausentes.
- [ ] Implementar tipos inmutables en coordenadas normalizadas y un motor sin Android UI; un puntero capturado por D-pad conserva ese rol hasta `UP/CANCEL`.
- [ ] Ejecutar GREEN y refactorizar nombres/duplicación sin ampliar alcance.
- [ ] Commit: `Android A3: modelar controles multitáctiles`.

## Task 3: Overlay Android único, dibujo Material y háptica

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/input/GameControlsView.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/input/GameControlsOverlay.kt`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/input/GameControlsViewTest.kt`

- [ ] Escribir prueba instrumental que inyecta dos punteros, desliza entre A/B, verifica diagonales, `ACTION_CANCEL`, máscara cero al desmontar y una sola háptica por flanco.
- [ ] Ejecutar RED por vista ausente.
- [ ] Implementar una única `View`: procesa todos los índices de `MotionEvent`, actualiza el motor puro y envía la máscara solo cuando cambia. `performHapticFeedback(KEYBOARD_TAP)` ocurre para nuevos controles si está habilitado.
- [ ] Dibujar cruceta, A/B, Start/Select y Menú con contraste suficiente, pressed state y etiquetas accesibles; tamaño mínimo 48 dp y exclusión de zonas de gesto mediante insets entregados por Compose.
- [ ] `AndroidView.onRelease` cancela punteros y publica máscara cero antes de soltar referencias.
- [ ] Ejecutar GREEN, TalkBack semantics básicas y regresión.
- [ ] Commit: `Android A3: añadir overlay táctil y háptica`.

## Task 4: Ring SPSC y salida AAudio

**Files:**
- Create: `android/app/src/main/cpp/audio_ring.h`
- Create: `android/app/src/main/cpp/audio_ring.c`
- Create: `android/app/src/main/cpp/audio_output.h`
- Create: `android/app/src/main/cpp/audio_output.c`
- Modify: `android/app/src/main/cpp/CMakeLists.txt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/audio/AudioState.kt`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/audio/NativeAudioTest.kt`

- [ ] Escribir pruebas nativas expuestas solo a Debug/instrumentation: wraparound, overflow sin sobrescribir, underrun rellena silencio, clear con callback detenido y contadores monótonos. Añadir smoke start/stop tolerante a dispositivo sin salida.
- [ ] Ejecutar RED por símbolos ausentes.
- [ ] Implementar ring estéreo `int16_t` preasignado con índices `_Atomic`; productor único=session, consumidor único=callback. Capacidad objetivo ≥4096 frames y cebado de 2048.
- [ ] Implementar AAudio low-latency/shared, PCM I16 estéreo 48 kHz, callback sin locks ni allocations y callback de error que solo publica un flag atómico.
- [ ] En ausencia/error de AAudio devolver estado `ClockFallback`, sin convertirlo en crash.
- [ ] Ejecutar GREEN en emulador y Samsung físico; ASan/UBSan del código portable del ring mediante test host si CMake lo permite.
- [ ] Commit: `Android A3: añadir salida AAudio de baja latencia`.

## Task 5: Pacing por audio, velocidad y recuperación

**Files:**
- Modify: `android/app/src/main/cpp/native_session.c`
- Modify: `android/app/src/main/cpp/native_session.h`
- Modify: `android/app/src/main/cpp/pocketgb_jni.c`
- Modify: `android/app/src/main/java/com/joelbermudez/pocketgb/emulator/EmulatorSession.kt`
- Modify: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/emulator/EmulatorSessionTest.kt`

- [ ] Escribir pruebas instrumentadas: cebado previo a audio live, frames/PCM avanzan, cuatro timeouts fuerzan fallback, ×2/×4 mantienen audio parado, volver a ×1 limpia y vuelve a cebar, pause/stop cierran AAudio antes de confirmar.
- [ ] Ejecutar RED contra snapshots/controles ausentes.
- [ ] Tras cada `gb_run_frame`, vaciar PCM del core al ring. En modo priming llenar objetivo; en live observar ocupación/contador de consumo con espera monotónica acotada; en fallback/fast-forward usar deadline por frame.
- [ ] Las transiciones de audio se serializan bajo control nativo: ningún callback sobrevive a `pause`/`stop`; al reanudar se crea un stream nuevo y no se reutiliza estado de error.
- [ ] Solicitar `AudioManager` focus antes de activar; pérdida transitoria o permanente invoca pausa, y la ganancia no reanuda.
- [ ] Ejecutar GREEN, bucle de 100 ciclos start/pause/resume/stop y regresión completa.
- [ ] Commit: `Android A3: integrar pacing de audio y avance rápido`.

## Task 6: Lifecycle, gameplay Debug y cierre verificable

**Files:**
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/app/SessionLifecycleObserver.kt`
- Create: `android/app/src/main/java/com/joelbermudez/pocketgb/ui/gameplay/GameplayScreen.kt`
- Modify: `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/DebugCatalog.kt`
- Create: `android/app/src/debug/java/com/joelbermudez/pocketgb/debug/GameplayDebugScreen.kt`
- Modify: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/DebugCatalogTest.kt`
- Create: `android/app/src/androidTest/java/com/joelbermudez/pocketgb/app/SessionLifecycleObserverTest.kt`
- Modify: `tools/android-screenshots.sh`
- Modify: `android/README.md`
- Modify: `docs/ESTADO.md`
- Create: `docs/auditorias/A3-android-evidencia.md`

- [ ] Escribir pruebas: `ON_PAUSE` pausa una sesión running una sola vez; `ON_STOP` libera audio; `ON_RESUME` conserva `Paused`; una sesión ya pausada no produce transición inválida. La pantalla Debug expone surface, controles, menú y velocidad con semantics estables.
- [ ] Ejecutar RED.
- [ ] Implementar observer idempotente y `GameplayScreen` teléfono primero: viewport arriba en vertical, overlay seguro abajo; horizontal superpone controles sin invadir cutout/gestos. Menú pausa y botones ×1/×2/×4 muestran el estado actual.
- [ ] Añadir catálogo `gameplay-controls` y `gameplay-fast-forward` claro/oscuro con ROM sintética en memoria; capturas nunca contienen juegos comerciales.
- [ ] Ejecutar una prueba prolongada de al menos 10 minutos: cero crash/ANR, registrar underruns, fallback y frame pacing; validar interrupción por otra app/background en el Samsung.
- [ ] Ejecutar verificación fresca: `make -C core test`, `make -C core asan`, Gradle clean con JVM/instrumented/Debug/Release/lint, 100 ciclos de lifecycle, capturas, permisos y ausencia del catálogo Debug en Release.
- [ ] Documentar resultados y limitaciones. Actualizar bóveda de contexto.
- [ ] Commit: `Android A3: verificar audio input y ciclo de vida`.

## Acceptance Gate

A3 queda listo cuando audio real funciona en el Samsung sin bloquear el callback ni el hilo principal; el emulador cae limpiamente a reloj si AAudio falla; ×2/×4 queda silenciado y vuelve a ×1 con cebado; dos o más dedos, diagonales y slides producen máscaras correctas sin direcciones opuestas; háptica solo ocurre por flanco; background/foco pausan sin auto-resume; no hay regresiones del core, permisos de red, ROMs/partidas/estados ni clases Debug en Release. La prueba manual de Joel y la auditoría conjunta permanecen separadas: la primera cierra la aceptación real del hito y la segunda se reserva para A8.
