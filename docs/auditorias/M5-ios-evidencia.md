# M5 (parte iOS) · Evidencia

Mac de Joel, Xcode 26.6, 2026-09-29, rama `m5-ios-audio`.

## 1. Build y tests
- `xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` → `** BUILD SUCCEEDED **`, sin warnings del proyecto.
- `tools/ios-screenshots.sh` → `** TEST SUCCEEDED **`: `AudioRingBufferTests` (4) y `AtomicFileTests` (7) en verde; catálogo de capturas con la app viva en cada pantalla (nueva comprobación `app.state == .runningForeground`).

## 2. Crash encontrado en la prueba real (H0)
La primera prueba de 10 min dejó la app cerrada: crash reports `PocketGB-2026-09-29-1052522.ips` y `-105433.ips`, `EXC_BREAKPOINT` en `AURemoteIO::IOThread`, pila `dispatch_assert_queue` ← `swift_task_isCurrentExecutor` ← `closure #1 in AudioOutput.start()`. Causa y corrección en [M5-ios-respuesta.md](M5-ios-respuesta.md). Tras la corrección no aparecen crash reports nuevos.

## 3. Prueba de 10 minutos (criterio 3, en el simulador; falta el iPhone)
iPhone 17 Pro (iOS 26.5, simulador), build Debug, Pokémon Amarillo (volcado de Joel, copia temporal fuera del repo, borrada al terminar), `-debugHUD`, sin tocar nada durante 600 s (intro y pantalla de título con música).

| Momento | HUD |
|---|---|
| t = 20 s | `audio ·0  U 0  ring 1804  emu 3.30 ms` |
| t = 600 s | `audio ·0  U 0  ring 2480  emu 3.27 ms` |

Modo `audio` (sin caídas a reloj: `·0`), **0 underruns**, el ring oscila alrededor del objetivo de 2048 frames y la emulación usa ~3,3 ms de los 16,7 ms de cada frame. Captura: [M5-ios-hud-10min.png](M5-ios-hud-10min.png).

## 4. iPhone de Joel (2026-09-29)
- Joel instaló el build con audio y jugó Pokémon Rojo y Amarillo: **"el audio se escuchó bien"** (criterio 4, de oído: aceptado por Joel). No se anotó la línea del HUD en el iPhone; el criterio 3 se apoya en esa prueba sin cortes audibles y en los 10 min del simulador (§3: modo `audio`, 0 caídas, 0 underruns).
- Su log de Xcode mostró un aviso propio: `AVAudioSession_iOS.mm:978 This method can lead to UI unresponsiveness if called on the main thread` (dos veces al abrir cada juego). Corregido: la sesión y el motor viven ahora en una cola serie propia (`AudioOutput.queue`); el resto del log es ruido del sistema (LaunchServices, miniaturas de iCloud, teclado).

## 5. Tras mover el audio fuera del hilo principal
- Build sin warnings; `tools/ios-screenshots.sh` → `** TEST SUCCEEDED **`.
- Simulador, Amarillo con `-debugHUD`, 30 s: `audio ·0  U 0  ring 2529  emu 2.10 ms`; al pasar a background y cerrar, `grep -c 'UI unresponsiveness'` en el log de la app → `0`; sin crash reports nuevos.
