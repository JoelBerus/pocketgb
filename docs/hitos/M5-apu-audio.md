# M5 · APU + audio sincronizado

☁️ Núcleo (APU + tests) · 🍎 integración AVAudioEngine. Spec: [03](../03-core-spec.md) §APU, [02](../02-arquitectura.md) §Hilos, [04](../04-ios-spec.md) §Audio.

**Archivos:** `core/src/apu.c`, `ios/PocketGB/Audio/AudioOutput.swift`, `Emulator/RingBuffer.swift` (SPSC lock-free con `Synchronization.Atomic`), cambio de pacing a audio-driven. En el runner: `--wav out.wav` para escuchar la salida sin iPhone.

**Criterios de aceptación**
- [x] `make -C core test HITO=M5` → `dmg_sound` 01–06 PASS (07–12 pueden quedar `known-fail`, documentados).
- [x] `gbtest <rom> --wav` genera un WAV de 48 kHz sin clipping sostenido. El pico se reporta.
- [ ] Mac: 10 min de juego en el iPhone con 0 underruns (contador en el menú de depuración).
- [ ] A9: Joel escucha el grito de Pikachu en Amarillo (pantalla de título) y lo compara con `make oracle` + `diff --wav`. Diferencias audibles → issue abierto, no bloquea el cierre si se documenta.

Núcleo cerrado el 2026-09-29 (criterios 1 y 2). Evidencia: [M5-evidencia](../auditorias/M5-evidencia.md) · Auditoría: [M5-opus](../auditorias/M5-opus.md) · Respuesta: [M5-respuesta](../auditorias/M5-respuesta.md). Los criterios 3 y 4 (iPhone, oído) quedan para una sesión en el Mac tras M4.
