# 04 · Especificación iOS

iOS 26.0+ (decisión 2026-09-29: Liquid Glass nativo), Swift 6 (strict concurrency), SwiftUI, sin dependencias externas. Bundle ID: `com.joelbermudez.pocketgb` (cámbialo si ya existe en tu Personal Team).

## Proyecto Xcode (creado en M4)
`ios/PocketGB.xcodeproj` ya está en el repo (lo generó Claude en M4, formato Xcode 16+ `objectVersion 77`). Lo único manual es la firma: conectar el iPhone y pulsar Run una vez ([07](07-instalacion-iphone.md)). La receta de abajo documenta cómo está configurado, por si hubiera que recrearlo:
1. Xcode → *File › New › Project › iOS App*. Nombre `PocketGB`, interfaz SwiftUI, lenguaje Swift, sin Core Data ni tests por ahora. Guardar en `pocketgb/ios/`.
2. En el navegador del proyecto, borrar el grupo `PocketGB` generado y arrastrar la carpeta `ios/PocketGB/` como **carpeta sincronizada** (Xcode 16+, icono de carpeta azul). Así, cualquier `.swift` que añada el agente entra al target sin editar `project.pbxproj`.
3. Añadir `core/src` al target como carpeta sincronizada también. En *Build Settings*:
   - `HEADER_SEARCH_PATHS = $(SRCROOT)/../core/include`
   - `SWIFT_INCLUDE_PATHS = $(SRCROOT)/../core/include` (para el `module.modulemap`)
   - `GCC_C_LANGUAGE_STANDARD = c11`
   - `OTHER_CFLAGS = -Wall -Wextra`
4. *Signing & Capabilities*: Team = tu Personal Team (Apple ID). **No añadir la capability iCloud** (no está disponible con una cuenta gratuita y no hace falta).
5. *Deployment target* 26.0. *Supported orientations*: Portrait, Landscape Left, Landscape Right. *Requires full screen* = YES.

## Estructura de fuentes (`ios/PocketGB/`)
```
App/        PocketGBApp.swift, AppState.swift
Library/    LibraryView.swift, LibraryStore.swift (bookmark + escaneo), RomEntry.swift
Emulator/   EmulatorSession.swift (hilo), CoreBridge.swift (wrapper seguro de pocketgb.h),
            FrameBuffers.swift (triple buffer), RingBuffer.swift (SPSC int16)
Video/      GameMetalView.swift (UIViewRepresentable), Renderer.swift, Shaders.swift (fuente MSL compilada en runtime:
            así no hace falta el Metal Toolchain, que en Xcode 26 es una descarga aparte)
Audio/      AudioOutput.swift (AVAudioEngine + AVAudioSourceNode)
Input/      ControlsOverlayView.swift (UIView multitouch), ControlsLayout.swift,
            GamepadInput.swift (GameController), Haptics.swift
Saves/      SaveStore.swift, StateStore.swift, AtomicFile.swift
Settings/   SettingsView.swift, Settings.swift
Resources/  Assets.xcassets, Info.plist
```

## Info.plist (claves exigidas)
- `UIFileSharingEnabled = YES` y `LSSupportsOpeningDocumentsInPlace = YES`: la carpeta de la app se ve en Archivos (útil para sacar saves a mano).
- `UIStatusBarHidden = YES`. (`UIRequiresFullScreen` está obsoleta desde iOS 26 y se quitó; la app es solo iPhone.)
- `UIBackgroundModes`: **ninguno**. El audio se detiene en background.
- **Prohibido**: `NSAppTransportSecurity`, `NSLocalNetworkUsageDescription` o cualquier clave de red. La auditoría hace grep de `URLSession|Network|NWConnection|http` en `ios/`.

## Pantalla de juego
- **Horizontal:**
  - La imagen del juego va centrada, escalada al entero máximo que quepa en el alto o, si en Ajustes se elige "llenar", con ajuste de aspecto 10:9 no entero. Fondo negro.
  - Los controles van **superpuestos** en los laterales, encima de la imagen si se solapan.
- **Vertical:** imagen arriba (ancho completo, 10:9), controles debajo sobre fondo sólido y con opacidad 1.0.
- `prefersHomeIndicatorAutoHidden = true` y `preferredScreenEdgesDeferringSystemGestures = .all` mientras se juega, para que un toque en el borde no abra el Centro de Control.
- El renderizado usa `MTLSamplerState` con `minFilter = magFilter = .nearest` y una textura `.rgba8Unorm` **del tamaño del frame de la consola** (160×144 en GB/GBC, 240×160 en GBA; el shader no depende del tamaño), actualizada con `replace(region:)` desde el último buffer listo.

### Game Boy Advance (G7–G8)
- La imagen es 3:2 (240×160). Escalado entero, «Llenar» y la vista vertical siguen las mismas reglas con relación 3:2 en vez de 10:9.
- **L y R**: píldoras táctiles (92×40 pt, área táctil ≥ 44 pt) con posiciones por defecto propias por orientación (arriba a los lados; en vertical sin pisar el menú ni A/B/cruceta); arrastrables y redimensionables en el editor. El mando físico los mapea a los hombros izquierdo/derecho. La máscara de botones es de 16 bits; el núcleo GB descarta los bits de L/R.
- **Ajustes por juego (solo GBA):** tipo de partida (detectado / sin partida / SRAM 32 KiB / Flash 64 / Flash 128 / EEPROM 512 B / 8 KiB), reloj (detectado / con / sin) y BIOS (Global —la tuya si existe— / Emulada). Se validan contra la partida guardada: si el tipo forzado no coincide con el `.sav`, no se sobrescribe (regla dura 6) y se avisa. Un save state de otra configuración se rechaza como «estado de otra configuración», no como dañado.
- **BIOS opcional:** `gba_bios.bin` (16 KiB, volcado propio de Joel) en la raíz de la carpeta de la biblioteca; se valida por SHA-256 y se informa en Ajustes › Emulación. Sin ella se usa la HLE del núcleo. Nunca se incluye una BIOS en el repo ni en la app ([08](08-roms-legal.md)).
- Audio: `sample_rate` 48 kHz, mismo anillo SPSC y pacing guiado por audio que GB.

## Controles translúcidos (requisito explícito de Joel)
Una sola `UIView` (`ControlsOverlayView`) con `isMultipleTouchEnabled = true` gestiona **todos** los toques. No se usan `UIButton` ni gestos de SwiftUI, porque no permiten deslizar entre botones ni pulsar A y B a la vez de forma fiable.

| Elemento | Posición en horizontal (relativa al safe area) | Forma |
|---|---|---|
| D-pad | Centro en (18 % ancho, 62 % alto), radio 70 pt | Círculo; dirección por ángulo |
| A | (88 %, 55 %), radio 34 pt | Círculo |
| B | (78 %, 68 %), radio 34 pt | Círculo |
| A+B | Zona entre A y B, radio 18 pt | Invisible; pulsa ambos |
| Start / Select | (54 %, 92 %) / (46 %, 92 %), 60×24 pt | Píldora |
| Menú | Esquina superior central, 36 pt | Icono ☰; abre pausa |
| Avance rápido | Junto a Menú | Mantener = ×N; doble toque = fijo |

- **Opacidad:** en reposo 0.30 y 0.60 al pulsar (animación de 80 ms). Configurable en Ajustes entre 0.10 y 0.80. La opacidad se aplica al **dibujo**, no a la vista, así el hit-testing sigue igual.
- **Estilo:** relleno blanco con la opacidad indicada, borde de 1,5 pt al doble de opacidad y letras (A, B, START, SELECT) con la misma opacidad que el borde. Se dibuja con `CAShapeLayer` y no con imágenes, para que no se pixele.
- **D-pad por ángulo:** vector del toque al centro. Si la distancia es menor que el 25 % del radio (zona muerta), no se pulsa nada. Si no, se toma el ángulo en 8 sectores de 45°, de modo que las diagonales pulsan 2 direcciones. **Nunca** se generan direcciones opuestas.
- **Seguimiento de toques:** `[UITouch: Control]`. En `touchesMoved` se recalcula el control bajo cada toque, lo que permite deslizar de B a A o rodar el pulgar en el D-pad. El D-pad "captura" el toque que empezó en él hasta que se levanta, aunque salga de su radio (ampliado ×1,5).
- **Salida:** máscara `UInt8`, publicada con `OSAllocatedUnfairLock` (M4, iOS 17; `Synchronization.Atomic<UInt8>` exige iOS 18) que el hilo de emulación lee cada frame.
- **Háptica:** `UIImpactFeedbackGenerator(style: .light)` al pasar de no pulsado a pulsado. Desactivable.
- **Editor de disposición (M7):** en Ajustes se pueden arrastrar los controles y guardar sus posiciones relativas por orientación.

## Mandos físicos
`GameController`: `GCController.controllers()` más las notificaciones de conexión. Mapeo: cruceta y stick izquierdo → D-pad; A/B siguiendo la **posición** del GB (botón derecho = A, inferior = B, configurable); Menu → Start; Options → Select. Con un mando conectado, la superposición se oculta. La máscara del mando se combina (OR) con la táctil.

## Biblioteca (iCloud Drive sin capability iCloud)
1. En el primer arranque, "Elegir carpeta de juegos" abre `UIDocumentPickerViewController(forOpeningContentTypes: [.folder])`.
2. Con la URL elegida: `startAccessingSecurityScopedResource()`, se crea `bookmarkData(options: .minimalBookmark)` y se guarda en `UserDefaults`.
3. En cada arranque o regreso a foreground, se resuelve el bookmark (si `isStale`, se regenera) y se enumeran los `.gb`/`.gbc`/`.gba` de la carpeta y sus subcarpetas hasta `LibraryScanner.maxFolderDepth` = 5 niveles (N1b), con un tope de `maxEntries` = 5 000 juegos. Nombres reservados (ND11): lo que empieza por `.` se ignora, `PocketGB/` en la raíz es de la app y las carpetas que empiezan por `_` quedan apartadas; los enlaces simbólicos a carpetas no se siguen. Cada juego tiene `folderPath` (carpetas desde la raíz; el primer nivel es la categoría). Guía para Joel: [guia/carpetas.md](guia/carpetas.md).
4. Los archivos de iCloud aún no descargados (`.icloud` placeholder, `ubiquitousItemDownloadingStatus != .current`) se muestran con icono de nube. Al tocarlos se llama `FileManager.startDownloadingUbiquitousItem(at:)` y se espera con `NSFileCoordinator`.
5. Cada ROM se lee completo en memoria con `NSFileCoordinator(readingItemAt:)` y se pasa a `gb_load_rom`. Se rechazan archivos de más de 8 MiB.
6. Cada entrada muestra el título de la cabecera, CGB sí/no, el tipo de MBC, un aviso si el checksum no coincide (A15) y la fecha del último save.
7. **Identidad (N1a).** Favoritos, último juego, ocultos, alias y ajustes por juego van por **huella** (`LibraryPreferences`, formato 2, `Application Support/Library/preferences.json`); la ruta solo es clave provisional mientras no se conoce la huella. La huella se calcula sin abrir el juego con `RomFingerprint` (idéntica a la del núcleo: en Game Boy solo los bytes que declara la cabecera; en GBA el archivo entero), en segundo plano, con lectura coordinada y solo de archivos locales o ya descargados (nunca fuerza una descarga), y se recuerda en la caché (ruta, tamaño, fecha) → huella (`fingerprint-cache.json`). Misma huella en varias rutas = duplicado («Duplicado», «También en: …»). El formato 1 y los ajustes por juego de `UserDefaults` (`gameplaySettings.perGame`, por ruta) se migran sin perder nada; el JSON que no se entiende se aparta como `preferences.corrupt-<fecha>.json` y se avisa, y nunca se sobrescribe un archivo que no se pudo leer.

Android equivalente: [05](05-android-spec.md) §Biblioteca.

## Saves (A5): la parte que no puede fallar
**Cuándo se guarda la SRAM:**
1. **Guardado del juego detectado:** `gb_sram_dirty()` es verdadero después de un frame. Se hace flush a los 1,0 s con debounce, en una cola serie de guardado (no en el hilo de emulación). Antes se copia la SRAM al buffer de guardado dentro del hilo de emulación.
2. **`scenePhase` pasa a `.inactive` o `.background`:** flush síncrono, con `beginBackgroundTask` si hace falta.
3. **Salir del juego al menú:** flush síncrono.
4. **Red de seguridad:** cada 60 s, si hay datos sucios.

**Cómo se guarda (`AtomicFile`).** Invariante **de reemplazo**: si ya existía un `<huella>.sav`, en todo instante sigue existiendo uno completo (el viejo o el nuevo). El actual nunca se mueve ni se borra antes de instalar el nuevo. En el **primer guardado** no hay viejo: hasta el paso 5 el destino no existe y el `.tmp` completo es recuperable.
1. Si el contenido nuevo es igual al actual (SHA-256), no se hace nada.
2. Escribir `<huella>.sav.tmp` y hacer `fsync` (`FileHandle.synchronize()`).
3. Rotar **solo backups**: `.4`→`.5`, …, `.1`→`.2` (cada paso es un `rename(2)`; si falla a medias, solo se pierde el backup más viejo).
4. Si existe el actual: **copiarlo** (no moverlo) a `.1.tmp`, `fsync` y `rename(2)` a `.1`. El actual sigue intacto.
5. Instalar: `rename(2)` de `.sav.tmp` sobre `<huella>.sav`. POSIX garantiza el reemplazo atómico si el destino existe y la creación si no existe: **una sola ruta** para el primer guardado y para los siguientes. Después, `fsync` del directorio.
6. Al arrancar, los `*.sav.tmp` huérfanos: si no existe `<huella>.sav` y el `.tmp` tiene el tamaño esperado, **se instala** (recupera un primer guardado interrumpido). Si no, se borra. Los `.1.tmp` se borran.
- Tests obligatorios (M6), por separado:
  - primer guardado exitoso;
  - primer guardado con fallo antes del paso 5 → destino ausente y el `.tmp` se recupera al arrancar;
  - reemplazo con fallo tras cada paso 2–4 → `.sav` viejo intacto;
  - reemplazo completo → `.sav` nuevo y el viejo en `.1`;
  - contenido igual → no rota.
4. Espejo en iCloud: con `NSFileCoordinator(writingItemAt: rom.deletingPathExtension().appendingPathExtension("sav"), options: .forReplacing)`. Si falla (sin acceso a la carpeta), se registra y se reintenta en el próximo guardado. **La copia local es la autoritativa.**

**Carga al abrir un ROM:**
1. Se usa el `.sav` local por huella.
2. Si no existe, el `.sav` junto al ROM en iCloud. Esto permite importar partidas de otro emulador o de un volcado del cartucho.
3. Si existen los dos y difieren, se usa el más reciente por `modificationDate`, pero **antes** se guarda el otro como backup.
4. Un tamaño incorrecto no se carga: se muestra un error y el archivo no se toca.

**Restaurar:** Ajustes › Partidas lista los backups con fecha y permite restaurar uno. Antes de restaurar, se crea un backup de la partida actual.

## Cable link virtual (M9)
Dos juegos de Game Boy en el mismo iPhone, unidos por el cable de `core/src/link.c` (API `gb_link_*`, [03-core-spec](03-core-spec.md) §Cable link virtual). Plan y decisiones: [hitos/M9-ios-plan.md](hitos/M9-ios-plan.md); UI: [diseno/SPEC.md](diseno/SPEC.md) §10.6.
- **Motor.** Se reutiliza `EmulatorSession` (hilo, pacing por audio, pausa): el cable le llega como un `ConsoleCore` compuesto, `LinkedPair`, que es el único dueño de los dos `CoreBridge` y de `gb_link*`. `LinkCable` retiene los dos núcleos mientras están conectados, así que ningún `gb_destroy` puede ejecutarse con la instancia conectada; `shutdown()` (el último paso del hilo de emulación) desconecta antes de destruir. `LinkSession` (`@MainActor @Observable`) es la fachada de la interfaz: títulos, lado activo, `peerFrames`, rechazos y avisos; no tiene hilo propio.
- **Un solo juego activo.** El lado activo es el que se ve, se oye y recibe los botones (táctiles y del mando); `LinkedPair` suelta los botones del otro y drena su audio en cada frame (si no, el anillo del APU lleno descartaría lo nuevo y al cambiar sonaría audio viejo). La miniatura pinta el framebuffer del otro lado desde `peerFrames`.
- **Partidas (regla dura 6).** Cada juego guarda su `.sav` con **su propia `SRAMPersistence`** (la misma ruta de siempre: debounce de 1 s, red de 60 s, flush síncrono en pausa, background, memoria baja y salida, escritura atómica con cinco backups y espejo junto al ROM). `flushAll` no cortocircuita: si falla un lado, el otro se guarda igual. `whenMirrorIdle` espera a los dos espejos.
- **Rechazos antes de arrancar:** un `.gba`; el mismo ROM con batería dos veces (compartirían `.sav`, backups y espejo; sin batería se permite); un juego con batería que no puede guardar (`.localWrongSize`, `.mirrorWrongSizeOnly`, `.unreadable`) porque un intercambio guardado en un solo lado perdería un Pokémon; una partida de iCloud sin descargar y sin copia local. Si el primer juego ya instaló su espejo cuando el segundo se rechaza, no pasa nada: es lo que haría abrirlo solo, con backup. Los avisos no bloqueantes de los dos juegos se juntan en una alerta.
- **Sin save states** (tampoco el automático al salir): cargar un estado reescribe la SRAM de un lado y rompe el protocolo con el otro. `AppState.stateStore` queda en `nil`; los estados que ya tenía cada juego no se tocan. Al salir, `didRestoreSave` de los dos juegos (D8.1); al abrir, si alguno tiene continuación válida se avisa.
- **Riesgo conocido.** Si iOS mata la app entre el guardado de un lado y el del otro, puede quedar un lado con el intercambio y el otro sin él, igual que al tirar del cable de verdad. Lo mitigan los cinco backups por juego.

## Save states (M7)
4 slots por juego más un slot "auto" al salir. Se guardan en `Application Support/States/` con `AtomicFile`. Un save state **nunca** sustituye a la SRAM: al cargar un estado, la SRAM del estado pasa a ser la actual y se guarda con la ruta normal, con su backup.

## Audio
`AVAudioSession` en categoría `.ambient`, para respetar el interruptor de silencio (configurable a `.playback`). `AVAudioEngine` + `AVAudioSourceNode` a 48 kHz en estéreo `Float32`. El callback convierte `int16` a float desde el `RingBuffer`. Las interrupciones (llamadas, Siri) pausan la emulación y al terminar se reanuda en pausa, no jugando.

## Ciclo de vida
- `background`: pausar la emulación, hacer flush de la SRAM y detener el motor de audio.
- `foreground`: volver a escanear la biblioteca. El juego queda en pausa hasta que Joel toque "Continuar".
- Si hay memoria baja (`didReceiveMemoryWarning`), se guarda y se sigue.
