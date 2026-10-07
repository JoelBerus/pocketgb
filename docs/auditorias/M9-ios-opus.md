# Auditoría final M9 🍎 · UI del cable link virtual (Opus, solo lectura)

Rama `m9-link-ui`, HEAD `c311186`, rango `cdb8b2f...c311186`. Me centré en los lotes 3–5 (`3c272fb`, `6c37f31`, `c311186`) y en la respuesta `bc89def`. De los lotes 1–2 solo revisé lo que toca la integración con `LinkSession` y `AppState`. Para los tests usé una copia `git archive c311186` en el scratchpad, con el mutex `sim` y `-derivedDataPath …/dd-m9-final`.

## Veredicto: APROBAR CON CAMBIOS

La ruta de guardado con dos partidas cumple la regla 6. El orden de apertura, los rechazos, `flushAll` sin cortocircuito, `whenMirrorIdle` agrupado y el cierre del cable siguen el plan. La propiedad del cable impide un `gb_destroy` con la instancia conectada, también cuando algo falla a medias. No hay hallazgos bloqueantes, ni altos ni medios. Pido tres cambios de severidad baja:
- el test de M9L2-H3 no detecta que se quite la guarda (lo comprobé con una mutación);
- un título incoherente en `SavesIndex`;
- una frase contradictoria en `M9-link-virtual.md`.

## Hallazgos

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| M9-H1 | baja (tests, respuesta a M9L2-H3) | `ios/PocketGBTests/EmulatorSessionContractTests.swift:91-103` (`statesAreRefusedWithSeveralPersisters`) · `PairCore` `:136-150` | El test **no prueba la guarda** `persisters.count <= 1` (`EmulatorSession.swift:151` y `:366`). `PairCore.stateSave()` y `sramSave()` ya lanzan `.linkUnsupported`. Sin la guarda, `start(restoring:)` y `loadState` fallan igual con el error esperado: en `core.stateSave()` y en `withParkedCore → core.stateSave()`. Es el mismo punto débil que M9L2-H3 pedía cerrar: la protección no puede depender de que el núcleo compuesto lance. | **Mutación** en la copia de `c311186`: cambié las dos guardas por `_ = 0` y corrí `-only-testing:PocketGBTests`. Resultado: `✔ Test statesAreRefusedWithSeveralPersisters() passed after 0.456 seconds.` Con la guarda revertida, la suite completa pasa: `✔ Test run with 169 tests in 17 suites passed after 10.554 seconds.` | Que el núcleo compuesto del test **sí** guarde y cargue estados y SRAM. Por ejemplo, una variante de `PairCore` con `stateSave` → `Data([0])`, `stateLoad` sin efecto y `sramSave` → la SRAM de `a`. Luego exigir `.linkUnsupported` y comprobar que ningún `.sav` cambió. Con la mutación tiene que salir en rojo. |
| M9-H2 | baja (coherencia de datos) | `ios/PocketGB/App/AppState.swift:414-415` (`startLink`) | `SavesIndex.record` guarda `link.titles[i]`, que es `libraryPrefs.displayTitle` (el **alias** del usuario si lo tiene). El juego suelto guarda `session.info.title`, el título de la cabecera (`AppState.swift:514-515`). Así, Ajustes › Partidas (`SavesSettingsView.swift:19`) muestra el alias después de una sesión de cable y vuelve al título de cabecera después de abrir el juego solo. | Lectura de las dos llamadas y de `SavesIndex.record`, que reescribe `title` si cambia. | Pasar `link.infos[i].title` (o `title.isEmpty ? fileName : title`, como en el juego suelto) a `SavesIndex.record`, y dejar `titles` solo para la interfaz. |
| M9-H3 | baja (docs) | `docs/hitos/M9-link-virtual.md:17` | La línea «**Alternativa descartada:** pantalla dividida (…), o bien alternar con un botón. El audio se toma solo del juego activo.» presenta como descartada la opción que se implementó, que es alternar. | `git diff cdb8b2f...c311186 -- docs/hitos/M9-link-virtual.md`. | Dejar solo «pantalla dividida (dos juegos, uno arriba y otro abajo, controles con selector)» y quitar «o bien alternar con un botón». |

## Estado de M9L2-H1..H3

| ID | Estado | Comprobación |
|---|---|---|
| M9L2-H1 (media) | **Corregido** | `retryAfterAFailedAsyncWriteRewritesWithoutASyncFlush` (`SRAMPersistenceTests.swift`) fuerza una escritura asíncrona fallida y después `retryIfFailed` → `check` → +1,1 s → `check`, sin flush síncrono. Sin el cuerpo de `retryIfFailed`, `lastQueued == data` y `dirtyLast == nil`, así que el segundo `check` no escribe y `store.load() == sramSave()` falla. La evidencia (§5) muestra la mutación en rojo. No la repetí: el razonamiento sobre el código lo confirma. |
| M9L2-H2 (baja) | **Corregido** | `FakeCore` cuenta las lecturas de SRAM posteriores a `shutdown()`. `shutdownRunsAfterTheFinalFlush` cambia la SRAM después de `start()`, cuando `prime` ya corrió porque `start` espera al cebado del hilo. Si `core.shutdown()` se moviera antes de `flushAll(sync: true)` (`EmulatorSession.swift:495-496`), el flush leería después del shutdown y el test fallaría. Pasa en la suite limpia. |
| M9L2-H3 (baja) | **Corregido en producción; el test no lo prueba (M9-H1)** | La guarda y el comentario de la invariante están en `start(restoring:)` (`:151`) y en `loadState` (`:366`). El test sigue en verde sin ellas (mutación arriba). |

## Criterios del hito

**Verificables en ☁️/CI (los ejecuté o los comprobé leyendo):**
- [x] **Suite unitaria:** `✔ Test run with 169 tests in 17 suites passed after 10.554 seconds.` (copia de `c311186`, iPhone 17 Pro). Cuadra con `git grep -c "@Test"`, que suma 169: 152 tras L2, +10 `LinkSessionTests`, +4 `LinkPartnersTests` y +3 de la respuesta.
- [x] **Las dos SRAM se guardan por la ruta normal** (con ROMs sintéticos): `exchangeIsSavedInBothSavFilesThroughTheNormalPath` comprueba los 16 bytes `i ^ key[1−s]` y `$A100 == 1` en los dos `SaveStore`, por pausa → `flushAll`.
- [x] **Rechazos**: `.sameGame` con la carpeta vacía; `.cannotSave(.localWrongSize)` sin cambiar ni un byte ni el listado; `.saveNotDownloaded`; `.notGameBoy`; mismo ROM sin batería se abre; `saveState` → `.linkUnsupported`.
- [x] **Catálogo**: `screens.txt` tiene 106 líneas de pantalla (95 + 11). Son **9 IDs nuevos** en 11 líneas (claro/oscuro). Los 9 están en `AccessibilityTests.specIDs` y en SPEC §9 (filas 44–52). **No** ejecuté `tools/ios-screenshots.sh` ni `ShellLinkTests`. Las 106 capturas y el `passed` de `ShellLinkTests` me parecen plausibles por la evidencia.
- [x] **Regla 1**: el diff no tiene `.gb/.gbc/.gba/.sav/.bin`. Los ROMs de los tests son sintéticos (`LinkTestROMs`); las capturas usan `dmg-acid2`/`cgb-acid2`, libres y fuera del repo (`tools/ios-screenshots.sh` los copia a un temporal).
- [x] **Regla 4**: `git diff --stat cdb8b2f...c311186 -- core gba` vacío.
- [x] **Regla 5**: en el diff no hay `URLSession`, `NWConnection`, `NSAppTransportSecurity` ni `import Network`. `git diff --check` limpio.
- [x] **DEBUG**: `DebugArguments` y `DebugScreenRouter` están enteros bajo `#if DEBUG`. Lo mismo `openLink(romURL:linkURL:)`, `openFromLaunchArguments` y `DebugDynamicType`, que ahora es interno pero sigue dentro de `#if DEBUG` en `PocketGBApp.swift:114-126`; su uso en `LibraryRootView.swift:27-29` también está bajo `#if DEBUG`. No sale en Release. La ruta `-linkROM` no escribe `UserDefaults`: `recordPlayed` solo se llama con `entryID` y aquí es `nil`. Las capturas `-screen` usan preferencias y portadas en memoria.

**🍎 pendientes de Joel** (no se marcan; la evidencia §7 y `M9-link-virtual.md` los dejan abiertos):
- [ ] Rojo ↔ Amarillo: intercambio, Kadabra evoluciona, los dos `.sav` locales y sus espejos con fecha nueva.
- [ ] HUD DEBUG 10 min con los dos núcleos (`audio ·0`, `U 0`, `emu` < 10 ms).
- [ ] Al alternar, el audio y los controles (táctiles y mando) siguen al juego visible, sin audio viejo.
- [ ] Background y cierre forzado durante el cable sin perder partida.
- [ ] Miniatura y HUD en las dos rotaciones horizontales con Dynamic Island.
- [ ] Rechazo con dos copias del mismo ROM y aviso de continuación en el dispositivo.

## Notas

**Regla 6 con dos partidas (revisado sin hallazgos).**
- **`LinkSession.init`** (`LinkSession.swift:89-151`). El orden es el del plan §1.2:
  1. `.notGameBoy` antes de crear nada.
  2. `CoreBridge` ×2 + `loadROM` ×2.
  3. `.sameGame` (misma huella y batería) **antes** de cualquier `SRAMPersistence.open`.
  4. `open` ×2, con un `.cannotSave` por lado justo después de abrirlo (`hasBattery && sramSaveSize > 0 && persister == nil`). Esa condición cubre exactamente `.localWrongSize`, `.mirrorWrongSizeOnly` y `.unreadable` (`SaveOpening.prepare` `.wrongSize` y el `catch` genérico de `open`). `mirrorNotDownloaded` → `.saveNotDownloaded`.
  5. `LinkedPair`, que hace `LinkCable` + `attach`.
- **Si B se rechaza después de abrir A**, A solo ha hecho lo que haría abrirlo solo: `recoverOrphans`, y `addBackup`/`quarantine`/`installLocal` con backup. Su persister se descarta sin `prime`, así que el espejo desfasado se resuelve en la siguiente apertura. No hay test de este caso, pero el código cumple el plan §1.5.
- **El test de `.sameGame`** (`sameGameWithBatteryIsRefusedAndTouchesNothing`) parte de una carpeta vacía. Fallaría si se quitara la comprobación, pero no si se moviera después de `open`: con la carpeta vacía `prepare` no escribe nada. Un caso con `.sav` local y un espejo más nuevo lo blindaría. Es una observación; el código está bien ordenado.
- **`AppState`.** `flushAll(sync: true)` corre en pausa, en background (`pause()`) y en memoria baja (`requestFlush` → flush en el hilo), y al salir (`stop()` → flush final → `core.shutdown()`). `enterBackground` usa `session.whenMirrorIdle`, que con dos persisters agrupa con `DispatchGroup` (`EmulatorSession.swift:259-273`). `closeGame` del cable (`AppState.swift:616-623`) sigue el orden pausa → `stop` → `saveArtwork(link)` (dos portadas) → `didRestoreSave` ×2 → `link = nil`, y no hay estado automático. `startLink` deja `stateStore = nil` y `stateEntries = [:]`; `saveAutomaticState` sale en su `guard let stateStore`. `gamepad.target = link.session.padButtons`.

**Dueño del cable y vida de los punteros.**
- Si `LinkedPair.init` falla en `attach`, `LinkCable.attach` desconecta y no retiene nada; después `LinkCable.deinit` → `gb_link_destroy`, que desconecta primero (`link.c:165-171`).
- `EmulatorSession.init` del cable no lanza, así que no hay un estado intermedio con el cable conectado y sin sesión.
- Si la sesión se liberara sin haber arrancado, `LinkCable` retiene los dos `CoreBridge`: `gb_link_destroy` siempre corre antes de que puedan llegar a `gb_destroy`.
- `shutdown()` está declarado en el protocolo (`ConsoleCore.swift:78`) y se llama en el hilo de emulación tras el último flush (`EmulatorSession.swift:495-496`).

**Botones, audio y vídeo.**
- `LinkedPair.runFrame` lee `side` una sola vez por frame. Pone la máscara en el lado activo y `0` en el otro, drena el audio inactivo en un búfer reservado una sola vez y publica `peerFrames` del otro lado.
- `copyFramebuffer`, `readAudio` y `cpuLocked` usan ese mismo `side`. El cambio es atómico por frame: `LinkSideSelector` usa `Atomic` con acquire/release.
- `switchLinkSide` muestra el toast «Ahora juegas con …» y la háptica de selección solo si `haptics` está activo.
- Carrera teórica: `saveArtwork(link)` usa `link.activeSide` del hilo principal. Si se cambiara de lado y se saliera antes de que corra un solo frame, las dos portadas quedarían cruzadas. En la práctica no ocurre, porque la pausa y la sheet tardan muchos frames, y además solo afectaría a las portadas.

**UI.**
- El HUD tiene el botón `hud-link-switch` de 44×44 con «Cambiar a <peerTitle>».
- `PauseView` en cable no tiene estados y muestra «Cambiar a … y continuar» y «Salir del cable».
- `LinkPeerPreview` usa `allowsHitTesting(false)`. En horizontal es hija del `ZStack` raíz dentro del `GeometryReader`, que respeta el área segura.
- `LinkPartners.candidates` es pura y está probada (4 tests). La apertura va en el `onDismiss` de la sheet. La alerta de continuación sale **antes** de leer ROMs, en `openLink`; es una desviación documentada y razonable.
- `GameDetailsView` y el menú contextual solo ofrecen el cable para juegos de GB/GBC jugables.
- En horizontal, el `DebugHUD` (solo DEBUG) se dibuja también arriba a la izquierda y tapará la miniatura durante la prueba 🍎 de 10 min. Es solo visual y no es un hallazgo.

**Swift 6.**
- `LinkSession` es `@MainActor` y no crea closures que corran en otras colas: pasa `mirrorWriter: nil`, igual que el juego suelto.
- `LinkedPair`, `LinkCable` y `SRAMPersistence` son nonisolated. `onAudioInterrupted` es `@MainActor @Sendable`.
- `Task.detached` en `openLink` solo captura valores `Sendable`.
- La evidencia registra el Release compilado.

**Flake observado (no es de M9).** En la ejecución con la mutación falló `SaveMirrorTests.mirrorFailureKeepsLocalAndRetries` por el timeout de 2 s del semáforo: `attemptFinished.wait(timeout: .now() + 2) → .timedOut`. Ese test no usa `EmulatorSession` y la mutación no lo afecta. En la ejecución limpia pasó. Es anterior a M9, pero los `LinkSessionTests`, con hilos reales y `Thread.sleep`, aumentan la carga en paralelo. Si se repite en CI, conviene ampliar ese timeout.

**Docs.**
- SPEC §9 (filas 44–52) y §10.6, `04-ios-spec` §Cable link virtual, `02-arquitectura` (Hilos), `ESTADO.md` y `hitos/README.md` son coherentes con el código y **no** marcan 🍎 como hecho.
- La única incoherencia es M9-H3.
- `ESTADO.md` y la tabla de hitos dicen «falta la auditoría de los lotes 3–5»: hay que actualizarlos con este informe.
