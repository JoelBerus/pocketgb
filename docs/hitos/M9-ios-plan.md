# Plan M9 🍎: UI del cable link virtual (para ejecutar por lotes)

Diseño elaborado por un subagente Opus (Plan) el 2026-10-06 sobre g8 (`/Users/joelbermudez/Documents/workspace/pocketgb-g8`) y D8.1. **Base de ejecución:** la rama de integración `cierre-integracion` (g8 + D8.1 corregidos + Android) con CI verde; M9 se hace en la rama `m9-link-ui` desde ahí.

**Números de línea:** los que se citan son de g8; las funciones de D8.1 llevan la línea del worktree D8.1. Tras la fusión, `AppState.swift` y `EmulatorSession.swift` se desplazan unas 20–35 líneas: buscar cada punto por el nombre de la función.

---

## 0. Decisiones en una línea

| Tema | Decisión |
|---|---|
| UI | **Alternar** entre los dos juegos con un botón del HUD, con una miniatura en vivo del otro juego. Se descarta la pantalla dividida (§2.1). |
| Motor | **Se reutiliza `EmulatorSession` tal cual**: su hilo, el pacing por audio, el control de pausa y el ciclo de vida. El cable le llega como un `ConsoleCore` compuesto, `LinkedPair`. |
| Partidas | La lógica de SRAM sale de `EmulatorSession` a una clase nueva, `SRAMPersistence`, sin cambiar su comportamiento. Un juego suelto usa una instancia; el cable usa dos. |
| Dueño del cable | `LinkedPair` es el único dueño de `gb_link*` y de los dos `gb*`. `LinkSession` es una fachada en el hilo principal (títulos, lado activo, miniatura) y **no tiene hilo propio**. |
| Save states | **Desactivados en modo cable**, también el estado automático al salir. Los estados que ya tiene cada juego no se tocan. |
| Abrir el cable | Se rechaza si un lado con batería no puede guardar, si los dos lados son el mismo ROM con batería, o si alguno es GBA. |

---

## 1. Arquitectura

### 1.1 Opciones evaluadas

| Opción | Problema |
|---|---|
| **A. `EmulatorSession` como motor, con un núcleo compuesto y N persistencias (elegida)** | Cambia dos cosas internas de `EmulatorSession`: saca la SRAM a `SRAMPersistence` y parte el `init`. El hilo, el pacing, el audio y la pausa (auditados en M4, M5, D5 y D8.1) siguen siendo una sola implementación. `AppState` no cambia en background, memoria baja, pausa, mando ni avance rápido. |
| B. `LinkSession` en paralelo con su propio hilo | Duplica unas 150 líneas del bucle, el pacing y el control, y además la máquina de flush de la SRAM. Dos implementaciones de la ruta del peor bug posible (regla dura 6). |
| C. `EmulatorSession` con arrays de núcleos y de frames | Reparte el concepto de cable por toda la clase y cambia su API pública, que usan `GameScreen`, `DebugHUD`, `AppState` y los tests. |

### 1.2 Propiedad e hilos

```
AppState (main)
 ├─ session: EmulatorSession ── hilo "PocketGB.emulation" (sin cambios)
 │     ├─ core: LinkedPair : ConsoleCore      ← ÚNICO dueño del cable
 │     │     ├─ LinkCable (gb_link*)          ← retiene los CoreBridge mientras están conectados
 │     │     └─ cores: [CoreBridge ×2] (gb*)  ← lado 0 (el del detalle) y lado 1
 │     └─ persisters: [SRAMPersistence A, B]  ← SaveTarget A/B (local atómica + espejo)
 └─ link: LinkSession (@MainActor @Observable): títulos, activeSide, peerFrames
           └─ LinkSideSelector (Atomic<Int>) ─ LinkedPair lo lee una vez por frame
```

Cómo se cumplen las reglas del cable (`core/include/pocketgb.h:155-197`, `docs/hitos/M9-link-virtual.md`):

- **Crear y conectar.** En `LinkSession.init`, en el hilo principal y antes de arrancar el hilo de emulación, igual que `EmulatorSession.init` carga hoy el ROM y la SRAM (`EmulatorSession.swift:74-132`). Orden: `CoreBridge` ×2 → `loadROM` ×2 → comprobar si son el mismo juego → `SRAMPersistence.open` ×2 (hace `sramLoad`) → `gb_link_create` → `gb_link_attach` con los dos ROMs cargados. `Thread.start` garantiza que el hilo ve todo lo anterior.
- **Avanzar.** `gb_link_run_frame` sustituye a `gb_run_frame`, dentro de `LinkedPair.runFrame()`.
- **Desconectar.** `EmulatorSession.run()`, después del último flush (`EmulatorSession.swift:446`), llama a `core.shutdown()`. Para `LinkedPair` eso es `gb_link_detach`, en el hilo de emulación. `gb_link_detach` no hace nada mientras el cable está corriendo (`link.c:175`), y aquí ya no corre.
- **Destruir.** `LinkCable.deinit` llama a `gb_link_destroy`. Como `LinkCable` **retiene** sus `CoreBridge` hasta desconectar, ningún `gb_destroy` puede ejecutarse con la instancia conectada, aunque falle `shutdown`. Importa porque `gb_link_detach` lee `p->g->opts` (`link.c:179`): destruir antes una instancia sería un use-after-free. Swift libera las propiedades después del cuerpo de `deinit`, así que el orden está garantizado por construcción.
- **Una instancia en un solo cable.** Si `gb_link_attach` devuelve `false` (`link.c:197-226`), `LinkCable` desconecta y no retiene nada. `LinkedPair` solo acepta `CoreBridge`, así que GBA queda imposible por tipo.

### 1.3 Un frame en el hilo de emulación

Es el bucle existente (`EmulatorSession.swift:421-428`), sin cambios:

1. `core.setButtons(buttons | padButtons)`: `LinkedPair` solo guarda la máscara.
2. `core.runFrame()`: `LinkedPair` fija `side = selector.side` para todo el frame; pone la máscara en el lado activo y `0` en el otro (al cambiar de juego se sueltan los botones del anterior); llama a `gb_link_run_frame`; **drena y descarta** el audio del lado inactivo en un búfer propio reservado una sola vez; publica en `peerFrames` el `gb_link_framebuffer` del otro lado.
3. `drainAudio` (`:455-461`): `readAudio` solo lee el lado activo y lo manda al ring. El pacing por audio no cambia.
4. `frames.publish { copyFramebuffer }`: copia `gb_link_framebuffer(activo)` (`link.c:287-292`), nunca `gb_framebuffer`.
5. `checkSRAM` pasa a ser `persisters.forEach { $0.check() }`.

**Por qué se drena el audio inactivo en cada frame.** Cuando el anillo del APU (8192 frames, ~170 ms) está lleno, el núcleo descarta las muestras **nuevas** (`apu.c:379-381`). Si no se vacía, al cambiar de juego sonarían 170 ms de audio viejo y el ring de la sesión se llenaría de golpe.

### 1.4 Vídeo y botones

- **Dos texturas.** El viewport principal sigue siendo `GameMetalView(frames: session.frames)` (`GameScreen.swift:84`), que siempre muestra el lado activo. La miniatura es otro `GameMetalView(frames: link.peerFrames, integerScale: false)`. `Renderer` y `FrameBuffers` no cambian.
- **Cambio de juego.** Basta con cambiar el selector atómico; el siguiente frame ya pinta el otro lado.
- **Botones.** Una sola `ButtonMask` táctil más una del mando (`EmulatorSession.swift:11-13`). `ControlsOverlay` y `GamepadInput.target` no cambian; el reparto lo hace `LinkedPair`.

### 1.5 Partidas (regla dura 6)

| Disparador | Dónde está hoy | En modo cable |
|---|---|---|
| Debounce de 1 s y red de 60 s | `EmulatorSession.swift:482-494` | `SRAMPersistence.check()`, uno por lado |
| Pausa / sheet / editor | `:370-372` (flush síncrono antes de aparcar) | `flushAll(sync: true)` en A y B |
| Background | `AppState.swift:395-402` y `whenMirrorIdle` | `whenMirrorIdle` espera a los dos espejos con un `DispatchGroup` |
| Memoria baja | `AppState.swift:405-407`, `:205-210`, `:388-396` | `flushAll(sync: true)` |
| Salida | `closeGame` (`:363-383`): pausa, `stop` y flush final (`:446`) | Igual, más `shutdown` (detach) |
| Reintento tras un fallo | `:390-394` | `persisters.forEach { $0.retryIfFailed() }` |

Puntos a respetar:
- **`flushAll` no puede cortocircuitar.** Si falla A, B se guarda igual: `var ok = true; for p in persisters { ok = p.flush(sync: s) && ok }` (primero el flush, luego `&&`).
- **Mismo comportamiento con un solo persister.** `whenMirrorIdle` con 0 persisters llama a `callback()`, con 1 a `persisters[0].whenMirrorIdle(callback)`, y solo con más de uno usa el grupo.
- **Avisos al abrir.** Por cada lado, `SaveOpening.prepare` (`SaveMirror.swift:232-275`) devuelve su aviso. `.mirrorUnavailable`, `.mirrorShared`, `.mirrorIgnored`, `.localQuarantined` y «Cabecera dañada» abren el cable y se juntan en una alerta con el título de cada juego. Se **rechaza** abrir si un lado con batería se queda sin `SaveTarget` (`.localWrongSize`, `.mirrorWrongSizeOnly`, `.unreadable`): un intercambio guardado en un solo lado pierde un Pokémon. También se rechaza con `SaveOpening.Refusal.mirrorNotDownloaded` en cualquier lado. Si A ya instaló su espejo cuando B se rechaza, no pasa nada: es lo que haría abrir A solo, con backup.
- **Espejos.** Cada `SaveTarget` tiene su espejo y su canal, por huella (`SaveMirror.swift:281-294`). La detección de `.sav` compartido de `AppState.open` (`:225-232`) se aplica a cada lado.
- **Mismo ROM dos veces.** Mismo fingerprint = mismo `Saves/<huella>.sav`, backups y canal: con batería se **rechaza** antes de tocar ningún `.sav`. Sin batería (p. ej. Tetris) se permite.
- **Guardado no atómico entre los dos lados.** Si iOS mata la app entre el guardado de A y el de B, puede quedar un lado con el intercambio y el otro sin él, igual que al tirar del cable. Lo mitigan los cinco backups por juego; documentado como riesgo.

### 1.6 Save states y continuación de D8.1

**En modo cable no hay estados.** Cargar un estado en un lado rompe el protocolo con el otro; cargar un estado **reescribe la SRAM** de ese lado (D5, `EmulatorSession.swift:300-314`), así que puede deshacer un intercambio y duplicar o perder un Pokémon; el estado automático al salir restauraría a solas un juego que está esperando a su pareja. Descartada la alternativa «estados solo del lado activo».

Implementación: `LinkedPair.stateSave/stateLoad` lanzan `CoreError.linkUnsupported`; `AppState.stateStore` queda `nil` en modo cable (el bloque de estado automático de `closeGame`, D8.1 `AppState.swift:396-403`, se salta solo); `PauseView` oculta «Estados guardados».

Interacción con D8.1 (se llama a sus funciones, no se cambian): **al cerrar el cable**, `didRestoreSave(fingerprint:)` (D8.1 `:442`) para los dos lados (su regla de vigencia invalida un estado automático más viejo que la nueva SRAM); **al abrir**, si algún lado tiene continuación válida (`canResume`, D8.1 `:419`), alerta «Conectar igualmente» / «Cancelar».

### 1.7 Swift 6

- `SRAMPersistence`, `LinkCable`, `LinkedPair` y `EmulatorSession.whenMirrorIdle` deben ser **nonisolated**. El closure de escritura y el `group.leave()` corren en otras colas (H0 de `M5-ios-respuesta`).
- `LinkSession` (`@MainActor`) no puede crear closures que corran en esas colas: los crean `SaveTarget` y `SRAMPersistence.open`, que son nonisolated.
- `shutdown()` tiene que estar **declarado en el protocolo** además del default en la extensión; si solo está en la extensión, `any ConsoleCore` lo despacha estáticamente al no-op.

---

## 2. UI

### 2.1 Alternar en vez de pantalla dividida
- En vertical no caben dos juegos y los controles (iPhone 13/14: dos viewports 10:9 a todo el ancho suman 702 pt y quedan ~95 pt para controles que necesitan > 260 pt); habría escala no entera y los layouts relativos (`ControlsLayout.swift:20-58`) se verían afectados.
- Alternar reutiliza todo `GameScreen` (vertical/horizontal, editor, Reduce Transparency, mando); solo añade un botón del HUD y la miniatura. El audio sigue al juego visible. El intercambio de Pokémon es por turnos: la miniatura enseña cuándo el otro lado espera. Una vista doble en horizontal se podría añadir después sin tocar la arquitectura.

### 2.2 Cómo se eligen los dos juegos
1. **Entrada.** En `GameDetailsView` (debajo de la acción principal, `:39`) un botón «Conectar con otro juego…» (`cable.connector`, `.pocketGlassButton()`, id `game-details-link`), solo si `entry.isPlayable && entry.console == .gameBoy`. En `GameContextMenu` (`:172-195`) la opción «Conectar con…». Ese juego es el **lado 0** y arranca activo.
2. **Selector.** Sheet `LinkPartnerPicker` (`.presentationDetents([.medium, .large])`) con filas `GameListItem(entry:, zoom: nil)`. Candidatos por una función pura `LinkPartners.candidates(for:in:prefs:)`: fuera el mismo `id`, GBA, ocultos, con problema o fuera de `.current`. **No se filtra por «con partida», solo se ordena**: jugados (`lastPlayed` desc.), luego con `.sav` junto al ROM, luego por título. Pie explicativo.
3. **Abrir.** Tocar una fila guarda `state.pendingLinkRequest` y cierra la sheet; la apertura se lanza en `onDismiss` de `.sheet(item:onDismiss:)` para no chocar presentaciones.

### 2.3 Gameplay en modo cable
- **HUD** (`GameplayHUD.swift:10-46`): tercer botón en el mismo `GlassEffectContainer`, icono `arrow.left.arrow.right`, 44×44, `accessibilityLabel("Cambiar a <otro título>")`, id `hud-link-switch` → `AppState.switchLinkSide()` + toast «Ahora juegas con <título>» (`showGameToast`, `AppState.swift:493-499`) + háptica de selección. El avance rápido se hereda.
- **Miniatura `LinkPeerPreview`**: contenido L1, sin vidrio, borde fino, `allowsHitTesting(false)`, etiqueta «Otro juego del cable: <título>». En los huecos de L/R (no usados en GB): vertical `.overlay(alignment: .topTrailing)` sobre `controls(.portrait)` (`GameScreen.swift:39-41`), 72 pt; horizontal como hija del `ZStack` raíz (respeta área segura, como el toast `:48-53`), 96 pt, `topLeading`. Se oculta con `.opacity(0)` (no `if`) al editar controles para no recrear la `MTKView`.
- **Pausa** (`PauseView.swift`) con `state.link != nil`: título del juego activo; «Continuar»; «Cambiar a <otro> y continuar» (id `pause-link-switch`); «Personalizar controles»; **sin** «Estados guardados» (pie explicativo); «Salir del cable» destructivo (id `pause-link-exit`, pie «Al salir se guardan las dos partidas.»).
- **Salida.** `closeGame()` en modo cable: pausa (flush de las dos SRAM) → `stop()` (último flush + detach) → portadas de los dos juegos (`session.frames` y `link.peerFrames`) → `didRestoreSave` ×2 → `link = nil`.

---

## 3. Archivos

### 3.1 Nuevos (bajo `ios/`)
| Ruta | Contenido |
|---|---|
| `PocketGB/Emulator/LinkCable.swift` | `LinkCable` (envoltorio de `gb_link*` que retiene sus núcleos) y `LinkSideSelector` (`Sendable`, `Atomic<Int>`) |
| `PocketGB/Emulator/LinkedPair.swift` | `LinkedPair: ConsoleCore` |
| `PocketGB/Saves/SRAMPersistence.swift` | Lógica movida de `EmulatorSession` y fábrica `open(...)` |
| `PocketGB/Emulator/LinkSession.swift` | Fachada `@MainActor @Observable`: `Side`, `Refusal` (`title`, `message`), `init`, `switchSide`, frames por lado |
| `PocketGB/Library/LinkPartnerPicker.swift` | Sheet, `LinkPartners.candidates`, `LinkRequest`, modificador de la alerta de continuación |
| `PocketGB/Gameplay/LinkPeerPreview.swift` | Miniatura |
| `PocketGBTests/LinkTestROMs.swift` | ROMs sintéticos (§5.1) |
| `PocketGBTests/{LinkCableTests,SRAMPersistenceTests,EmulatorSessionContractTests,LinkSessionTests,LinkPartnersTests}.swift` | Tests unitarios (Swift Testing) |
| `PocketGBUITests/ShellLinkTests.swift` | Test funcional de UI (prefijo `Shell` para que `ScreenshotTests` siga siendo la primera clase) |
| `docs/auditorias/M9-ios-evidencia.md` | Evidencia |

Firmas a respetar:
```swift
final class LinkCable {                       // no Sendable; un solo hilo a la vez
    init() throws(CoreError)                  // gb_link_create; .outOfMemory si NULL
    deinit                                    // gb_link_destroy (desconecta); después suelta los núcleos
    func attach(_ a: CoreBridge, _ b: CoreBridge) -> Bool   // false → gb_link_detach y no retiene nada
    func detach(); var isAttached: Bool { get }
    func runFrame()                           // gb_link_run_frame
    func copyFramebuffer(side: Int, to dst: UnsafeMutablePointer<UInt32>)  // gb_link_framebuffer(l, UInt32(side))
}
final class LinkedPair: ConsoleCore {         // console = .gameBoy
    let cores: [CoreBridge]                   // exactamente 2
    init(cores: [CoreBridge], selector: LinkSideSelector, peerFrames: FrameBuffers) throws // attach; error si false
    // sram*: inertes (0/false/.linkUnsupported): cada lado guarda con su SRAMPersistence
    // stateSave/stateLoad: throw .linkUnsupported · setRTCTime: los dos lados · shutdown(): cable.detach()
}
final class SRAMPersistence: @unchecked Sendable {
    static func open(core: any ConsoleCore, info: RomInfo, savesDirectory: URL, mirror: SaveMirror?,
                     snapshot: SaveMirror.Snapshot, mirrorWriter: (@Sendable (Data) throws -> Void)?)
        throws -> (persister: SRAMPersistence?, warning: SaveLoadWarning?)   // reenvía SaveOpening.Refusal
    init(core: any ConsoleCore, target: SaveTarget, clock: @escaping @Sendable () -> UInt64 = { mach_absolute_time() })
    func prime(); func retryIfFailed(); func check()
    @discardableResult func flush(sync: Bool) -> Bool
    func whenMirrorIdle(_ cb: @escaping @Sendable () -> Void)
}
// EmulatorSession:
@MainActor init(core: any ConsoleCore, info: RomInfo, persisters: [SRAMPersistence],
                loadWarning: SaveLoadWarning?, onAudioInterrupted: @escaping @MainActor @Sendable () -> Void)
@MainActor convenience init(romData:savesDirectory:mirror:mirrorSnapshot:mirrorWriter:emulation:console:bios:onAudioInterrupted:) throws  // misma firma que hoy
```

### 3.2 Archivos que se modifican
| Archivo:línea (g8) | Cambio |
|---|---|
| `Emulator/CoreBridge.swift:5-12, 38-57` | Caso `CoreError.linkUnsupported` («No disponible con el cable link.») |
| `Emulator/CoreBridge.swift:81` | `var linkHandle: OpaquePointer { g }` (solo para `LinkCable`); `g` sigue privado |
| `Emulator/ConsoleCore.swift:48-72` | `func shutdown()` en el protocolo y `extension ConsoleCore { func shutdown() {} }` |
| `Emulator/EmulatorSession.swift` | Mover el bloque de SRAM del `init` (`:102-129`), las variables de SRAM (`:29-30, 39, 42, 46-51`), `checkSRAM` (`:482-494`) y `flushSRAM` (`:505-558`) a `SRAMPersistence`. El `init` actual (`:74-132`) pasa a `convenience` y llama al designado; desaparece la liberación manual de `audioScratch` en el rechazo (`:118-122`). En `run()`: `prime` (`:353-366`), `retryIfFailed` (`:390-394`), `flushAll` (`:372, 396, 446`), `check` (`:427`) y `core.shutdown()` después de `:446`. `whenMirrorIdle` (`:214-220`) agrupado. `loadState` (`:307`) y `start(restoring:)` de D8.1 (D8.1 `:134`) pasan a `flushAll`. **No se toca** nada más. |
| `App/AppState.swift` | `private(set) var link: LinkSession?`, `linkPartnerSource`, `pendingLinkRequest`, `linkContinueRequest`, `startPendingLink()`, `openLink(_:)` (patrón de `open(entry:)` `:211-243`, lectura de dos ROMs en `Task.detached`, `LibraryScanner.readROM(_:limit:)` `:133`, espejo compartido por lado), `finishOpeningLink`, `startLink` (patrón de `start` `:282-323`: `closeGame()`, `SavesIndex.record` y `recordPlayed` ×2, `applyAudioPreferences`, `start()`, `session = link.session`, `gamepad.target`, `stateStore = nil`, avisos juntos), `switchLinkSide()`, rama de cable en `closeGame` (`:363-383`) y `saveArtwork(link)` (`:388-392`); en DEBUG `openLink(romURL:linkURL:)` más `-linkROM` en `openFromLaunchArguments` (`:339-359`). **No cambian** `enterBackground`, `memoryWarning`, `resume`, `pauseGame`, `cycleSpeed` ni `editingControls`. |
| `Gameplay/GameScreen.swift:39-41, 48-53` | Miniatura (vertical y horizontal) cuando `state.link != nil` |
| `Gameplay/GameplayHUD.swift:10-46` | Botón de cambio |
| `Gameplay/PauseView.swift:26-45` | Rama de cable |
| `Library/GameDetailsView.swift:39, 172-195` | Botón «Conectar con otro juego…» y opción en el menú contextual |
| `Library/LibraryRootView.swift:22-26` | `.sheet(item: $state.linkPartnerSource, onDismiss: state.startPendingLink)` y el modificador de alerta (patrón `HideGameAlert`, `GameDetailsView.swift:198-214`) |
| `App/DebugScreenRouter.swift:6-63, 75-165, 176-199` | IDs nuevos (§4, lote 4) |
| `App/DebugArguments.swift:28-36` | `static var linkROM: String? { value("-linkROM") }` |
| `PocketGBUITests/screens.txt` (tras `:123`) | Sección «M9 · Cable link virtual» |
| `PocketGBUITests/ScreenshotTests.swift:10-22` | Filtro opcional `SCREEN_FILTER` (`name.contains`) |
| `PocketGBUITests/AccessibilityTests.swift:6` | IDs nuevos en `specIDs` |
| `tools/ios-screenshots.sh:27` | `cp "$ROOT/core/tests/roms/cgb-acid2/cgb-acid2.gbc" "$FIX/"` |
| `docs/diseno/SPEC.md` §9 y nuevo §10.6, `docs/04-ios-spec.md`, `docs/02-arquitectura.md` (Hilos), `docs/hitos/M9-link-virtual.md`, `docs/ESTADO.md`, `docs/hitos/README.md` | Documentación (lote 5) |

### 3.3 Código que se reutiliza sin cambios
- **Partidas:** `SaveOpening.prepare` (`SaveMirror.swift:232-275`), `SaveTarget.persistLocal / retryMirrorIfNeeded / whenMirrorIdle` (`:453, 460, 467`), `SaveStore.recoverOrphans` (`SaveStore.swift:179-201`), `SaveMirror.snapshot()` (`:38-55`), `SaveLoadWarning.title / message` (`:185-212`), `EmulatorSession.validSaveSizes` (`:138-141`), `SavesIndex.record` (`SavesIndex.swift:21-29`).
- **Núcleo, vídeo y audio:** `CoreBridge.loadROM` (`:95-119`), `FrameBuffers(size:) / publish / latest` (`:26, 37, 48`), `GameMetalView` (`:5-33`), `AudioOutput`, `AudioRingBuffer` y el pacing de `EmulatorSession` (`:143-272, 368-444`).
- **Biblioteca:** `LibraryQuery` y `LibraryPreferences.isHidden / lastPlayed` (`LibraryPreferences.swift:101-104, 195-219`).
- **D8.1:** `canResume`, `refreshContinuations`, `didRestoreSave` (D8.1 `AppState.swift:419, 428, 442`) y `displayTitle` (D8.1 `LibraryPreferences.swift:117`).

### 3.4 Qué no se toca
- `core/` y `gba/` enteros: la API M9 está auditada y cerrada.
- `GBACoreBridge.swift`, `AudioOutput.swift`, `RingBuffer.swift`, `FrameBuffers.swift`, `Video/*`, `ControlsOverlayView.swift`, `ControlsLayout.swift`, `GamepadInput.swift`.
- `SaveStore.swift`, `SaveMirror.swift`, `AtomicFile.swift`, `StateStore.swift`, `LibraryScanner`, `LibraryStore`, `Settings/*`.
- En `EmulatorSession`: `start`, `pause`, `resume`, `stop`, `setSpeed`, `startAudio`, el pacing y `withParkedCore`.
- La lógica de continuación de D8.1: solo se llama.

---

## 4. Lotes (TDD: primero los tests en rojo, luego verde)

Comando base. Después de cada lote se corre además la suite unitaria completa (`-only-testing:PocketGBTests`).
```bash
X='xcodebuild test -project ios/PocketGB.xcodeproj -scheme PocketGB -destination platform=iOS\ Simulator,name=iPhone\ 17\ Pro -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO'
FIX="$(mktemp -d)"; cp core/tests/roms/dmg-acid2/dmg-acid2.gb core/tests/roms/cgb-acid2/cgb-acid2.gbc "$FIX/"   # fuera de ~/Documents (TCC)
```

### Lote 1. El cable en Swift (solo añade código)
1. **Tests en rojo.** `LinkTestROMs` y `LinkCableTests`: intercambio DMG con el maestro en el lado 0 y en el lado 1; compatibilidad ↔ CGB (como Rojo ↔ Amarillo); sin cable, el maestro recibe `0xFF`; el cable retiene sus núcleos hasta desconectar (`weak`); un núcleo no puede estar en dos cables, `attach(a, a)` devuelve `false` sin retener; los botones solo llegan al lado activo; los frames siguen al lado activo y `peerFrames` tiene el otro; tras cambiar de lado, `readAudio` devuelve ≤ ~1000 frames (no los 8192 viejos); `stateSave` lanza `.linkUnsupported`; `shutdown` deja `isAttached == false`. Los tests llaman a `pair.setButtons(…)`, `pair.runFrame()` y vacían el audio, sin hilos.
2. **Código.** `CoreError.linkUnsupported`, `linkHandle`, `ConsoleCore.shutdown()`, `LinkCable.swift`, `LinkedPair.swift`.
3. **Verificación.** `$X -only-testing:PocketGBTests/LinkCableTests`, la suite completa y `make -C core test`.

### Lote 2. `SRAMPersistence` e `init` designado (refactor sin cambio de comportamiento; el de más riesgo)
1. **Tests en rojo.** `SRAMPersistenceTests` (sin hilo, reloj inyectado): abrir sin `.sav` → `prime` → flush síncrono no escribe nada; una SRAM que cambia se escribe y deja `.1`; si no cambia, no rota; con carpeta de solo lectura el flush devuelve `false` y el siguiente reintenta y escribe; debounce (hook de test `waitForPendingWrites()` = `queue.sync {}`); red de 60 s sin flanco; la fábrica con local de tamaño incorrecto devuelve `persister == nil` y `.localWrongSize`, y reenvía `mirrorNotDownloaded`. `EmulatorSessionContractTests` con un núcleo falso (~804 frames de silencio por `runFrame`): `stop()` llama a `shutdown()` exactamente una vez; `pauseFlushesEveryPersisterEvenIfOneFails` (dos persisters en carpetas distintas, una pasa a 0o555; la otra queda escrita).
2. **Código.** Mover la lógica tal cual (`saves` → `target`, `control` → `lock` propio, `mach_absolute_time()` → `clock()`, `-failAsyncSaves` en `prime`). `init` designado y de conveniencia, `flushAll`, `whenMirrorIdle`.
3. **Verificación.** `$X -only-testing:PocketGBTests/SRAMPersistenceTests -only-testing:PocketGBTests/EmulatorSessionContractTests -only-testing:PocketGBTests/StateSRAMTests -only-testing:PocketGBTests/SaveMirrorTests -only-testing:PocketGBTests/GBATests` (las de D8.1 `startRestoring*` y `synchronousSessionFlushDoesNotWaitForBlockedRealMirror`, `SaveMirrorTests.swift:294-331`, protegen contra regresiones). Después la suite completa y `tools/ios-screenshots.sh build/shots-l2` (regresión de `game-*` y `gameplay-*`).

### Lote 3. `LinkSession`
1. **Tests en rojo** (`LinkSessionTests`, `@MainActor`, carpeta temporal por test): el intercambio se guarda por la ruta normal en los dos `.sav` (arrancar; hasta 10 veces reanudar 0,3 s y pausar, patrón `playUntilSaveChanges`, `StateSRAMTests.swift:47-56`, hasta `$A100 == 1` en las dos `SaveStore`; comprobar los 16 bytes); la pausa guarda las dos SRAM; mismo juego con batería → `.sameGame` y carpeta vacía; mismo juego sin batería → se abre; un lado con `.sav` de tamaño incorrecto → `.cannotSave` y el archivo no cambia ni un byte; `.unavailable` sin local → `.saveNotDownloaded(título)`; `.unavailable` con local → el aviso incluye el título; un `.gba` → `.notGameBoy`; `session.saveState()` lanza `.linkUnsupported`; `switchSide` cambia `activeSide` y `activeInfo`.
2. **Código.** `LinkSession.swift`, secuencia de §1.2, textos de rechazo en español.
3. **Verificación.** `$X -only-testing:PocketGBTests/LinkSessionTests` y la suite completa.

### Lote 4. UI, DEBUG y catálogo
**4a. Conexión con la app.** `AppState`, `LinkPartnerPicker`, HUD, pausa, detalle y menú, alertas y `-linkROM`. Tests: `LinkPartnersTests` (función pura). `ShellLinkTests` (con `XCTSkipIf` si no hay `FIXTURE_DIR`), abriendo con `-rom $FIX/dmg-acid2.gb -linkROM $FIX/cgb-acid2.gbc`: `hud-link-switch` existe, ≥ 44×44, etiqueta «Cambiar a CGB-ACID2»; al tocarlo pasa a «Cambiar a DMG-ACID2»; en la pausa no hay «Estados guardados» y sí `pause-link-exit`; salir vuelve a las tabs.

**4b. Miniatura y catálogo.** Casos nuevos de `DebugScreen` y sus líneas en `screens.txt`:
| ID | Orientación / estilo | Argumentos y efecto en el router |
|---|---|---|
| `gameplay-link-portrait` | vertical, oscuro | `-screen … -rom $FIXTURES/dmg-acid2.gb -linkROM $FIXTURES/cgb-acid2.gbc` |
| `gameplay-link-landscape` | horizontal, oscuro | ídem; la miniatura no debe quedar bajo el recorte |
| `gameplay-link-switched` | vertical, oscuro | `afterGameOpened`: `switchLinkSide()` a los 0,5 s; se ve el toast |
| `gameplay-link-pause` | vertical, oscuro | ídem más `-paused` |
| `gameplay-link-reduce-transparency` | horizontal, oscuro | `-reduceTransparency -controlOpacity 30` |
| `link-partner-picker` | vertical, claro y oscuro | `-demoLibrary standard`; detalle de `dmg-acid2.gb` más `linkPartnerSource` (patrón `:115-117`) |
| `link-partner-picker-ax5` | vertical, claro | más `-contentSizeCategory accessibility5` |
| `link-open-refused` | vertical, claro y oscuro | `-demoLinkError same-game`: alerta con el texto de `Refusal` (patrón `saveDataError`, `:94-100`) |
| `link-continue-warning` | vertical, claro | el router pone `linkContinueRequest` |
Revisar también `game-details` y `game-context-menu`, que ahora llevan el botón y la opción nuevos.

**Verificación.** `$X -only-testing:PocketGBTests/LinkPartnersTests`; `TEST_RUNNER_FIXTURE_DIR="$FIX" $X -only-testing:PocketGBUITests/ShellLinkTests`; `TEST_RUNNER_FIXTURE_DIR="$FIX" TEST_RUNNER_SCREENSHOT_DIR=build/shots-link TEST_RUNNER_SCREEN_FILTER=link $X -only-testing:PocketGBUITests/ScreenshotTests`; al final `tools/ios-screenshots.sh` (incluye Release y `ShellAccessibilityTests`) y **mirar cada PNG** con la lista de `VERIFICACION.md`.

### Lote 5. Evidencia, documentación y auditoría
- `M9-ios-evidencia.md`: salida de los tests, lista de capturas revisadas, Release, `make -C core test` y `git diff --check`. `rg -n 'URLSession|NWConnection|NSAppTransportSecurity' ios` vacío.
- SPEC §9 y §10.6, `04-ios-spec`, `02-arquitectura`, M9, `ESTADO.md` y la tabla de hitos.
- Auditoría en solo lectura (Opus; Codex sin créditos). Prioridades: `SRAMPersistence` y `EmulatorSession` (regla 6); `LinkSession` (rechazos); `LinkCable` y `LinkedPair` (detach antes de destroy); Swift 6.
- Un commit en español por lote.

---

## 5. Tests

### 5.1 ROMs sintéticos (`LinkTestROMs`)
Cabecera MBC1+RAM+batería (`0x147=0x03`, `0x149=0x02`, `JP $0150`, checksum), con la base de `LibraryScannerTests.rom` (`:16-24`) y `StateSRAMTests.rom` (`:18-43`). Los saltos `jr` se calculan con etiquetas (`off = label − (pc + 2)`).
- **`exchange(title:key:sc:)`.** Copia de `build_exchange` (`core/tests/unit_link.c:246-266`) cambiando `$C000` por `$A000` y `$C100` por `$A100`, y habilitando la RAM al empezar:
  ```
  3E 0A EA 00 00        ; habilitar RAM
  21 00 A0 06 00        ; LD HL,$A000 ; LD B,0
  loop: 0E (sc&1 ? 40 : 01)
  dly:  0D ; JR NZ,dly
        78 EE key E0 01 ; SB = B ^ key
        3E sc E0 02     ; SC = sc
  wait: F0 02 CB 7F ; JR NZ,wait
        F0 01 22 04 78 FE 10 ; JR NZ,loop
        3E 01 EA 00 A1  ; ($A100) = 1
  done: JR done
  ```
  Esperado: SRAM del lado *s* `[i] == i ^ key[1−s]` para i en 0…15, y `[0x100] == 1`, en ≤ 30 frames.
- **`joypadToSRAM`.** Bucle: `LD A,$10; LDH ($00),A; LDH A,($00)` ×2, y luego `LD ($A000),A`. Con A pulsado, el bit 0 vale 0 solo en el lado activo.
- **`palette(bgp:)`.** `LD A,bgp; LDH ($47),A; JR -2` en DMG. Con `0x00` y `0xFF` la pantalla sale entera de colores distintos.
- **`romOnly(title:)`.** Sin batería, para el caso del mismo juego sin batería.

### 5.2 Qué queda para el iPhone de Joel
- [ ] HUD DEBUG con los dos núcleos durante 10 min: `audio ·0`, `U 0`, `emu` < 10 ms (en M5 un núcleo medía 3,3 ms; el par cuesta ~2×).
- [ ] Al alternar, el audio sigue al juego visible, sin audio viejo ni clics; controles y mando solo actúan sobre el visible.
- [ ] Rojo ↔ Amarillo: los dos al Cable Club, guardar, intercambiar un Kadabra y comprobar que evoluciona. Al salir, los dos `.sav` locales y sus espejos tienen fecha nueva; al abrir cada juego por separado, el Pokémon está donde debe.
- [ ] Background y cierre forzado durante el cable: no se pierde ninguna partida.
- [ ] Miniatura y HUD en las dos rotaciones horizontales, con notch o Dynamic Island.
- [ ] Rechazo con dos copias del mismo ROM; aviso de continuación cuando hay progreso sin guardar.

---

## 6. Riesgos y decisiones abiertas
| Riesgo | Mitigación |
|---|---|
| Regresión en la ruta de guardado de **todos** los juegos (lote 2) | Movimiento literal del código, reloj inyectado, todas las suites actuales como red, catálogo completo y auditoría centrada en ese lote |
| Use-after-free si se destruye una instancia conectada | Retención en `LinkCable`, `shutdown` en el hilo y tests con referencias `weak` |
| Audio viejo al cambiar de juego | Drenaje por frame del lado inactivo, con test |
| Rendimiento en A15 (dos núcleos y dos `MTKView`) | ~7 ms estimados de 16,74; se mide en el iPhone. Plan B: miniatura a 30 fps o publicar `peerFrames` cada 2 frames |
| Guardado no atómico entre los dos lados | Igual que el hardware; cinco backups por juego; documentado |
| Estados y continuación de D8.1 | Desactivados en cable, `didRestoreSave` al cerrar y alerta al abrir |
| La miniatura choca con controles en pantallas pequeñas o si el usuario movió los controles | Va en los huecos de L/R y no recibe toques; si choca, se puede quitar sin afectar a lo demás |
| Choque entre la sheet y las alertas | La apertura se lanza en `onDismiss` |
| Prerrequisito: la fusión g8 + D8.1 | M9 no empieza sin el CI verde de esa integración |

Decisiones que quedan para Joel: alternar con miniatura (vista doble en horizontal como mejora futura); avance rápido en cable (se hereda: sí); mismo ROM sin batería permitido; el lado 0 es el juego desde cuyo detalle se conecta; los textos de la interfaz.

## 7. Tamaño y orden de riesgo
| Lote | Producción (aprox.) | Tests (aprox.) | Riesgo |
|---|---|---|---|
| 1. Cable | +175 | +290 | Medio |
| 2. `SRAMPersistence` | +190 / −110 en `EmulatorSession` | +260 | **Alto** (regla 6, todos los juegos) |
| 3. `LinkSession` | +200 | +230 | Alto/medio |
| 4. UI y catálogo | ~+450 | +150 y 11 capturas | Bajo en datos, medio en UX |
| 5. Docs y evidencia | ~+220 de docs | — | — |
Total ≈ 850–1000 líneas de producción netas y 800–950 de tests. Orden 1 → 2 → 3 → 4 → 5.
