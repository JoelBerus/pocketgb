# M5 (parte iOS) · Respuesta a la auditoría

Implementación: Codex (workspace-write) por encargo de Claude. Auditoría: subagente Opus independiente (Codex no audita lo que implementó, AGENTS.md). Informe: [M5-ios-opus.md](M5-ios-opus.md). Veredicto: **APROBAR CON CAMBIOS**.

| ID | Respuesta |
|---|---|
| H0 (bloqueante, encontrado por Claude en la prueba de 10 min, no por Codex ni por la auditoría) | **Corregido.** La app se cerraba en el primer callback de audio: el render block se creaba dentro de un método `@MainActor`, heredaba el aislamiento y Swift 6 abortaba en `AURemoteIO::IOThread` (`dispatch_assert_queue`, crash reports del simulador 10:52 y 10:54). Ahora el bloque se crea en `nonisolated static func makeSourceNode`. Las capturas del CI no lo detectaban (la app caía y se capturaba la pantalla de inicio): `ScreenshotTests` ahora exige `app.state == .runningForeground` en cada pantalla. |
| H1 (media) | **Corregido.** `AVAudioEngineConfigurationChange` (del motor propio) y `routeChangeNotification` con `oldDeviceUnavailable` pausan el juego como una interrupción; "Continuar" rearranca el motor con cebado. |
| H2 (baja) | **Corregido.** El HUD muestra el modo de pacing (`audio` / `cebado` / `reloj`) y el número de caídas a reloj; la evidencia del criterio 3 exige modo `audio` durante toda la prueba. |
| Nota (tiempo real) | **Corregido.** `AudioRingBuffer.read` usa locales en el bucle y escribe `lastLeft/lastRight` una vez al final. |
| Aviso de iOS en el iPhone (`setActive` en el hilo principal) | **Corregido.** `AudioOutput` confinado a una cola serie; `EmulatorSession` aplica el resultado del arranque con un contador de generación (un arranque tardío no pisa una pausa o parada posterior); el ring se vacía en esa cola justo después de parar el motor. |
