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
App/          PocketGBApp.swift, AppState.swift (+Travel: N7), DebugScreenRouter.swift y DebugArguments.swift (solo DEBUG)
Library/      LibraryView.swift, LibraryStore.swift (bookmark + escaneo), LibraryScanner.swift, RomEntry.swift,
              RomFingerprint.swift y LibraryPreferences.swift (N1a), AdaptiveLayout.swift y LibraryLandscapeTools.swift (N3),
              LibraryOrganization.swift, LibraryHomeViews.swift, CategoryView.swift y GameCenterView.swift (N4),
              Covers.swift, CoverStore.swift y CoverCenterView.swift (N5), GameSaveTravelSection.swift (N7)
Emulator/     EmulatorSession.swift (hilo), ConsoleCore.swift, CoreBridge.swift y GBACoreBridge.swift (wrappers seguros),
              FrameBuffers.swift (triple buffer), RingBuffer.swift (SPSC int16), LinkCable/LinkSession/LinkedPair (M9)
Video/        GameMetalView.swift (UIViewRepresentable), Renderer.swift, Shaders.swift (fuente MSL compilada en runtime:
              así no hace falta el Metal Toolchain, que en Xcode 26 es una descarga aparte)
Audio/        AudioOutput.swift (AVAudioEngine + AVAudioSourceNode)
Input/        ControlsOverlayView.swift (UIView multitouch), ControlsLayout.swift, ControlPalette.swift,
              GamepadInput.swift (GameController), Haptics.swift
Saves/        SaveStore.swift, SRAMPersistence.swift, SaveMirror.swift, AtomicFile.swift, StateStore.swift (estado automático),
              MomentStore.swift, MomentActions.swift y MomentsView.swift (N6), FingerprintOwnership.swift (N6),
              SaveLineage.swift, PGBMPackage.swift, SaveImport.swift y SaveExport.swift (N7)
Progress/     ProgressStore.swift, ProgressLibrary.swift, ProgressViews.swift (N6)
Guide/        Guide.swift, GuideView.swift, PocketTips.swift (N9)
Settings/     SettingsView.swift, Settings.swift y una vista por pantalla de Ajustes
Resources/    Assets.xcassets, Info.plist, Guide/guia-*.md (N9, copiados de docs/guia)
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

- **Opacidad:** en horizontal, 30, 50, 70 o 100 % (Ajustes › Controles; en vertical siempre 100 %), con velo oscuro y sombra para leerse sobre escenas blancas; al pulsar se marca el control (animación de 70–90 ms). Se aplica al **dibujo**, no a la vista, así el hit-testing sigue igual. Con «Reducir transparencia», superficie sólida con borde blanco.
- **Estilo:** relleno blanco con la opacidad indicada, borde de 1,5 pt al doble de opacidad y letras (A, B, START, SELECT) con la misma opacidad que el borde. Se dibuja con `CAShapeLayer` y no con imágenes, para que no se pixele.
- **D-pad por ángulo:** vector del toque al centro. Si la distancia es menor que el 30 % del radio (zona muerta), no se pulsa nada. Si no, se toma el ángulo: con diagonales «Reducidas» (por defecto, N2) la diagonal solo cuenta a ±15° de 45° y las rectas ocupan 60°; «Normales» son 8 sectores de 45° y «Desactivadas», 4 rectas. Histéresis: un dedo que ya pulsa mantiene su dirección hasta 8° más allá del borde de su sector y se suelta por debajo del 24 % del radio. Con flechas separadas, todo el disco de cada flecha pulsa su dirección (sin diagonal) y la zona muerta acaba 2 pt antes de su borde interior. **Nunca** llegan direcciones opuestas al núcleo: se quitan arriba+abajo e izquierda+derecha en la máscara táctil (dos dedos) y otra vez tras el OR con el mando (`EmulatorSession.combinedButtons`). El dibujo recibe la máscara: solo se marca el brazo o la flecha pulsada, con contraste ≥ 3:1 frente al resto.
- **Seguimiento de toques:** `[UITouch: Control]`. En `touchesMoved` se recalcula el control bajo cada toque, lo que permite deslizar de B a A o rodar el pulgar en el D-pad. El D-pad "captura" el toque que empezó en él hasta que se levanta, aunque salga de su radio (ampliado ×1,5).
- **Salida:** máscara `UInt8`, publicada con `OSAllocatedUnfairLock` (M4, iOS 17; `Synchronization.Atomic<UInt8>` exige iOS 18) que el hilo de emulación lee cada frame.
- **Háptica:** `UIImpactFeedbackGenerator(style: .light)` al pasar de no pulsado a pulsado. Desactivable.
- **Editor de disposición (M7, N2):** desde Pausa › Personalizar controles se arrastran los controles y se cambia su tamaño (60–160 %). Hay **cuatro disposiciones** independientes: Game Boy y GBA, cada una en vertical y horizontal (restablecibles en Ajustes › Controles › Disposición).
- **Estilo de cruceta (N2):** «Game Boy» (cruz con flecha en cada brazo; se hunde solo el brazo pulsado) o «Flechas separadas» (cuatro discos en rombo; la pulsada se invierte). Con flechas separadas el editor ajusta la **separación** del grupo (70–150 %; el grupo se mueve entero y siempre alineado, ND10) y la zona táctil crece o encoge con ella. Diagonales «Reducidas» (por defecto), «Normales» o «Desactivadas». Háptica solo al activarse una dirección nueva. Guía: [guia/controles.md](guia/controles.md).

## Mandos físicos
`GameController`: `GCController.controllers()` más las notificaciones de conexión. Mapeo: cruceta y stick izquierdo → D-pad; A/B siguiendo la **posición** del GB (botón derecho = A, inferior = B); Menu → Start; Options → Select; hombros izquierdo/derecho → L/R en GBA. Con un mando conectado, la superposición se oculta. La máscara del mando se combina (OR) con la táctil.

## Biblioteca (iCloud Drive sin capability iCloud)
1. En el primer arranque, "Elegir carpeta de juegos" abre `UIDocumentPickerViewController(forOpeningContentTypes: [.folder])`.
2. Con la URL elegida: `startAccessingSecurityScopedResource()`, se crea `bookmarkData(options: .minimalBookmark)` y se guarda en `UserDefaults`.
3. En cada arranque o regreso a foreground, se resuelve el bookmark (si `isStale`, se regenera) y se enumeran los `.gb`/`.gbc`/`.gba` de la carpeta y sus subcarpetas hasta `LibraryScanner.maxFolderDepth` = 5 niveles (N1b), con un tope de `maxEntries` = 5 000 juegos y `maxVisitedItems` = 50 000 elementos recorridos (aviso persistente en Ajustes › Biblioteca). Nombres reservados (ND11): lo que empieza por `.` se ignora, `PocketGB/` en la raíz es de la app y las carpetas que empiezan por `_` quedan apartadas; los enlaces simbólicos a carpetas no se siguen. Cada juego tiene `folderPath` (carpetas desde la raíz; el primer nivel es la categoría). Guía para Joel: [guia/carpetas.md](guia/carpetas.md).
4. Los archivos de iCloud aún no descargados (`.icloud` placeholder, `ubiquitousItemDownloadingStatus != .current`) se muestran con icono de nube. Al tocarlos se llama `FileManager.startDownloadingUbiquitousItem(at:)` y se espera con `NSFileCoordinator`.
5. Cada ROM se lee completo en memoria con `NSFileCoordinator(readingItemAt:)` y se pasa a `gb_load_rom` (o `gba_load_rom`). Se rechazan archivos de más de 8 MiB (GB/GBC) o 32 MiB (GBA).
6. Cada entrada muestra el título de la cabecera, CGB sí/no, el tipo de MBC, un aviso si el checksum no coincide (A15) y la fecha del último save.
7. **Identidad (N1a).** Favoritos, último juego, ocultos, alias y ajustes por juego van por **huella** (`LibraryPreferences`, formato 3 desde N4, `Application Support/Library/preferences.json`); la ruta solo es clave provisional mientras no se conoce la huella. La huella se calcula sin abrir el juego con `RomFingerprint` (idéntica a la del núcleo: en Game Boy solo los bytes que declara la cabecera; en GBA el archivo entero), en segundo plano, con lectura coordinada y solo de archivos locales o ya descargados (nunca fuerza una descarga), y se recuerda en la caché (ruta, tamaño, fecha de modificación, fecha de cambio) → huella (`fingerprint-cache.json`); la fecha de cambio (ctime) no la puede fijar `utimes`. El cálculo se pausa con un juego abierto. Misma huella en varias rutas = duplicado («Duplicado», «También en: …»). El formato 1 y los ajustes por juego de `UserDefaults` (`gameplaySettings.perGame`, por ruta) se migran sin perder nada; el JSON que no se entiende se aparta como `preferences.corrupt-<fecha>.json` y se avisa, y nunca se sobrescribe un archivo que no se pudo leer.

8. **Biblioteca y detalle adaptables (N3).** Todo se decide por el espacio disponible, nunca por el modelo. En altura compacta (horizontal) la biblioteca usa título en línea con `scrollEdgeEffectStyle(.hard)`, no muestra el segmentado ni el buscador arriba y fija el título de sección. Buscar, Filtros, Categorías y Vista/Orden van en la barra de navegación en reposo (paneles hacia abajo) y, al desplazar con la barra de pestañas encogida, en un grupo de vidrio propio a la derecha alineado con la burbuja (`LibraryToolsGroup`, paneles hacia arriba que no tapan la cabecera de sección); la búsqueda (`searchable` + `.minimize`) solo existe mientras se usa. En vertical solo se añade la lupa en la barra. Las categorías son las carpetas de primer nivel (`LibraryCategory`). El detalle pasa a dos columnas si el ancho supera al alto o mide ≥ 600 pt (`DetailLayout`); en vertical la imagen ocupa ≤ 45 % del alto. Capturas y miniaturas de estados con la proporción de la consola (10:9 o 3:2); las tarjetas comparten marco 10:9 y la captura lo rellena sin deformarse. «Información técnica» plegable: el núcleo lee la cabecera en una instancia propia sin sesión (`GameTechnicalInfoLoader`), solo con la sección abierta y nunca de un archivo sin descargar. Guía: [guia/biblioteca.md](guia/biblioteca.md).

9. **Categorías, etiquetas, inicio y centro de ajustes del juego (N4).** La categoría de un juego es la virtual si se movió en la app (ND3, `GameMetadata.virtualFolder`, por huella) o su carpeta (`folderPath`); el primer nivel es la categoría y los siguientes, subcategorías (`LibraryTree`, `LibraryCategory`, `Library/LibraryOrganization.swift`). La pestaña Biblioteca empieza con un **inicio**: «Continuar jugando» (los 5 más recientes entre los reanudables: primero se filtra y después se toman 5, como Android), fila de Favoritos (hasta 10, con el total) y una estantería por categoría de primer nivel (hasta 10 juegos y su total, «Ver todo»; sin carpetas no hay estanterías), y debajo «Todos los juegos». Las categorías abren su pantalla (`LibraryRoute.category`, `CategoryView`: migas, subcategorías con conteo, juegos del subárbol, cuadrícula o lista recordada por categoría); la pila se restaura con `@SceneStorage`. **Etiquetas** libres por huella (≤ 20 por juego, ≤ 30 caracteres, sin distinguir mayúsculas ni acentos), filtro por etiqueta y búsqueda por categoría y etiquetas. Ajustes › Biblioteca › Inicio ordena, fija y oculta categorías y la fila de Favoritos (por dispositivo, ND12). El **centro de ajustes del juego** (`GameCenterView`) sustituye a la hoja de ajustes por juego: nombre, categoría («Mostrar en categoría…», insignia «Movido en la app», «Volver a su carpeta»), etiquetas, portada y progreso («Próximamente»), copias de la partida, color/paleta o partida/RTC/BIOS de GBA y ocultar. Etiquetas y categoría virtual **solo se escriben con la huella confirmada** (`RomEntry.fingerprintVerified`: caché verificada, calculada o del núcleo; nunca la pista de la ruta ni una de caché obsoleta); si falta, el centro lee el juego (`LibraryStore.confirmFingerprint`, nunca descarga de iCloud). `preferences.json` **formato 3**: etiquetas y `virtualFolder` dentro de cada juego, `home` y `categoryLayouts`; el formato 2 se lee tal cual y se reescribe como 3 tras una copia exacta verificada (`preferences.v2.json`); una versión futura se usa sin escribirla ni apartarla. Reglas para Joel: [11-biblioteca-carpetas](11-biblioteca-carpetas.md); guía: [guia/categorias.md](guia/categorias.md).

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
3. Si existen los dos y difieren, se usa el más reciente por `modificationDate`, pero **antes** se guarda el otro como backup. Si el espejo no es una escritura reconocida de PocketGB (`recognizesOwnedMirror`), el que pierde va además a una copia apartada que no rota, `backups/<huella>.mirror-<unix>-<id>.sav` (nunca se pisa; restaurable en Ajustes › Partidas). Cubre duplicados con su propio `.sav` y `.sav` ajenos con el mismo nombre (N1, auditoría H1).
4. Un tamaño incorrecto no se carga: se muestra un error y el archivo no se toca.

**Restaurar:** Ajustes › Partidas lista los backups con fecha y permite restaurar uno. Antes de restaurar, se crea un backup de la partida actual. Restaurar (un backup o una copia apartada) va con la huella en exclusiva (`SaveRestoration`, `FingerprintOwnership.withExclusive`: con el juego abierto o aún guardando se rechaza sin tocar nada) y, si la partida cambia, aparta antes el estado automático (`auto.obsolete-*`, ver abajo).

## Cable link virtual (M9)
Dos juegos de Game Boy en el mismo iPhone, unidos por el cable de `core/src/link.c` (API `gb_link_*`, [03-core-spec](03-core-spec.md) §Cable link virtual). Plan y decisiones: [hitos/M9-ios-plan.md](hitos/M9-ios-plan.md); UI: [diseno/SPEC.md](diseno/SPEC.md) §10.6.
- **Motor.** Se reutiliza `EmulatorSession` (hilo, pacing por audio, pausa): el cable le llega como un `ConsoleCore` compuesto, `LinkedPair`, que es el único dueño de los dos `CoreBridge` y de `gb_link*`. `LinkCable` retiene los dos núcleos mientras están conectados, así que ningún `gb_destroy` puede ejecutarse con la instancia conectada; `shutdown()` (el último paso del hilo de emulación) desconecta antes de destruir. `LinkSession` (`@MainActor @Observable`) es la fachada de la interfaz: títulos, lado activo, `peerFrames`, rechazos y avisos; no tiene hilo propio.
- **Un solo juego activo.** El lado activo es el que se ve, se oye y recibe los botones (táctiles y del mando); `LinkedPair` suelta los botones del otro y drena su audio en cada frame (si no, el anillo del APU lleno descartaría lo nuevo y al cambiar sonaría audio viejo). La miniatura pinta el framebuffer del otro lado desde `peerFrames`.
- **Partidas (regla dura 6).** Cada juego guarda su `.sav` con **su propia `SRAMPersistence`** (la misma ruta de siempre: debounce de 1 s, red de 60 s, flush síncrono en pausa, background, memoria baja y salida, escritura atómica con cinco backups y espejo junto al ROM). `flushAll` no cortocircuita: si falla un lado, el otro se guarda igual. `whenMirrorIdle` espera a los dos espejos.
- **Rechazos antes de arrancar:** un `.gba`; el mismo ROM con batería dos veces (compartirían `.sav`, backups y espejo; sin batería se permite); un juego con batería que no puede guardar (`.localWrongSize`, `.mirrorWrongSizeOnly`, `.unreadable`) porque un intercambio guardado en un solo lado perdería un Pokémon; una partida de iCloud sin descargar y sin copia local. Si el primer juego ya instaló su espejo cuando el segundo se rechaza, no pasa nada: es lo que haría abrirlo solo, con backup. Los avisos no bloqueantes de los dos juegos se juntan en una alerta.
- **Sin save states** (tampoco el automático al salir): cargar un estado reescribe la SRAM de un lado y rompe el protocolo con el otro. `AppState.stateStore` queda en `nil`; los estados que ya tenía cada juego no se tocan. Al salir, el AUTO anterior de cada juego se aparta si su partida quedó más nueva (`setAsideAutoIfStale`) y `didRestoreSave` de los dos juegos (D8.1); al abrir, si alguno tiene continuación válida se avisa.
- **Riesgo conocido.** Si iOS mata la app entre el guardado de un lado y el del otro, puede quedar un lado con el intercambio y el otro sin él, igual que al tirar del cable de verdad. Lo mitigan los cinco backups por juego.

## Estado automático, momentos y progreso (M7, N6)
- **Estado automático:** un slot «auto» por juego al salir o pasar a segundo plano, en `Application Support/States/` con `AtomicFile`, siempre **después** del flush de la SRAM. «Continuar» solo lo usa si corresponde a la partida actual (misma SRAM y configuración); si no, «No se pudo continuar» y «Jugar desde el inicio». Un estado **nunca** sustituye a la SRAM sin pasar por la ruta normal de guardado con backup.
- **El AUTO nunca se borra sin copia** (auditoría N-final H1/H10): si su RAM no es la de la partida (`StateError.notCurrent`) se **aparta** con `StateStore.setAsideAuto()` (rename atómico a `States/<huella>/auto.obsolete-<unix>-<id>.state`; ninguna ranura lo lee, como Android). Si es de otra configuración (`CoreError.stateConfig` → `StateError.otherConfiguration`: modelo, tipo de partida, reloj, BIOS o `gba_bios.bin` ilegible) se queda en su ranura (ND21) y «Jugar desde el inicio» lo aparta antes de abrir. También se aparta al cambiar la partida sin sesión (instalar la de un momento, restaurar un backup o una copia apartada), al cerrar el cable link si la partida quedó más nueva que el AUTO, y al abrir un juego sin «Continuar» si el AUTO es anterior a la partida (`setAsideAutoIfStale`): así `closeGame` nunca pisa un AUTO que ya no es vigente.
- **Momentos (N6):** sustituyen a las ranuras 1–4 (migradas sin pérdida la primera vez). Cada momento guarda estado + RAM del cartucho del instante + miniatura (proporción de la consola) + configuración (modelo/paleta; en GBA, tipo de partida, RTC y BIOS) + nombre, etiquetas, colección, nota, fecha y tiempo jugado. Disco: `Application Support/Moments/<huella>/` con `m-<id>.{state,sav,png}`, `b-<id>.*` del anillo e `index.json` (punto de confirmación; ilegible → se aparta y se reconstruye, nunca se borra nada).
- **Cargar un momento** cambia la partida: la posición actual entra antes en el anillo **«Antes de cargar»** (3 entradas por orden de inserción, fuera de la rotación de 5 backups), después `session.loadState` guarda la SRAM con backup `.1`; el AUTO no se toca. «Recuperar» deshace en un toque. Desde el detalle, «Recuperar partida» instala solo la SRAM con el juego cerrado.
- **Exclusión por huella** (`FingerprintOwnership`): instalar o cargar desde el detalle, importar o instalar una partida que llega de otro equipo solo ocurre sin sesión abierta o aparcada de esa huella (la sesión la retiene hasta `whenMirrorIdle`).
- **Cable link:** sin momentos ni estado automático (rompería el protocolo con el otro lado).
- **Progreso:** `Application Support/Progress/<huella>.json` (tiempo de juego solo con el juego corriendo, checkpoint cada 30 s; sesiones; primera y última vez; hitos con plantilla «Pokémon» o libre; porcentaje opcional en tarjeta y detalle). Panel «Leído de la partida» con `pgb_progress_read` del núcleo ([03](03-core-spec.md) §Lector de progreso Pokémon), solo lectura. Guía: [guia/momentos.md](guia/momentos.md).

## Portadas (N5)
Fuentes por juego: **imagen importada** (`PhotosPicker` sin permiso de fototeca o `fileImporter`; PNG/JPEG/WebP, **sin HEIC**, ND18), **imagen de la carpeta** (mismo nombre que el ROM con `.png/.jpg/.jpeg/.webp`, o `portada.*`/`cover.*` si la carpeta tiene un solo juego), **captura** (última escena no lisa al salir, o fijada con Pausa › «Usar como portada») y **generada**. Elección por juego (Automática / Imagen / Captura / Generada) y preferencia global (Preferir imágenes o capturas) en `Application Support/Covers/settings.json` (por dispositivo, escritura atómica, dañado → `.corrupt-*`). Toda imagen es **entrada no confiable**: firma real, ≤ 15 MiB, ≤ 16 384 px de lado y ≤ 100 MP leídos de la cabecera con ImageIO antes de decodificar; se guarda reducida a 1024 px (PNG). El escáner solo mira nombres; la imagen de la carpeta se lee la primera vez que se ve (descarga de iCloud solo esa imagen, como mucho tres a la vez) y se cachea por ruta + sello. Tarjetas rellenan su marco 10:9; el detalle muestra la imagen entera; capturas sin suavizar. Guía: [guia/portadas.md](guia/portadas.md).

## Partidas que viajan (N7)
- **Linaje del espejo** (`SaveLineage`, `mirror-history.json`): espejo = local → nada; = una escritura o recepción nuestra anterior → gana la local, se reescribe y el contenido del espejo se aparta; desconocido con la local sin cambios → cambio externo, se instala con backup y aviso; desconocido con la local cambiada → **divergencia**: se pregunta sin escribir nada (ND20 a); la otra queda como momento «Conflicto» y copia apartada. Sin historial: por fecha con backup. Espejo borrado: se recrea. Copias en conflicto del proveedor (`X 2.sav`, `X (1).sav`, `.sync-conflict-`, «conflicted copy») se listan en Ajustes › Partidas y nunca se borran.
- **Paquete `.pgbm`** ([12-formato-pgbm](12-formato-pgbm.md); parser y codificador en C, `pgbm_*`): `.sav`, estado automático si es de esa partida, META (sha, sha base, equipo, versión del núcleo, configuración y metadatos del juego). «Enviar a otro dispositivo» lo comparte con `ShareLink` (Drive, AirDrop, Archivos); «Exportar .sav» y «Guardar paquete en Archivos…» también. Se importa con «Abrir con PocketGB» (UTI propio declarado en Info.plist) o «Importar partida…» del detalle.
- **Al importar:** avance (`base == local`) se instala sin preguntar; más antiguo, divergente o con base desconocida → se pregunta; la actual va antes a «Antes de cargar», a copia apartada y a backup. `SAVE` vacía o de otro tamaño, META inválida o paquete de otro juego → rechazo sin tocar nada. Metadatos se fusionan (ND20 i). Si el estado coincide con la partida, el detalle ofrece «Continuar donde lo dejaste en <equipo>». `.sav` crudo: tamaño exacto y confirmación nombrando el juego. Guía: [guia/viajar.md](guia/viajar.md).

## Guía y consejos (N9)
- **Ajustes › Guía:** las secciones de `docs/guia` que aplican al iPhone (carpetas, biblioteca, categorías, portadas, jugar y continuar, momentos, viajar, controles y GBA), copiadas al bundle como `Resources/Guide/guia-<id>.md` por `tools/ios-guide-sync.py` (que quita los enlaces a Android y al repo y convierte los enlaces entre secciones en `guia:<id>`). `tools/ios-screenshots.sh` falla si la copia no está al día (`--check`). Se dibuja con vistas nativas (`GuideParser` → bloques; texto en línea con `AttributedString(markdown:)`): títulos con rasgo de encabezado para el rotor de VoiceOver, tablas como fichas apiladas que se leen bien con AX5, el ejemplo de carpetas en un bloque desplazable. Búsqueda local sin mayúsculas ni acentos; un resultado abre la sección en su bloque, resaltado. `OpenURLAction` solo atiende `guia:`; nada sale de la app.
- **Consejos (TipKit):** cuatro, en línea (`TipView`): «Momentos» en la pausa, «Cambiar de categoría» en los ajustes del juego, «Enviar a otro dispositivo» en el detalle y «Flechas separadas» en Ajustes › Controles. `Tips.configure` con `.datastoreLocation(.applicationDefault)` y `.displayFrequency(.daily)` (como mucho uno al día); sin contenedor de CloudKit, así que TipKit no usa red. Cada consejo se invalida al usar lo que explica. En DEBUG, las pruebas de UI (`-uiStyle`) los ocultan y `-showTips` los muestra para sus capturas.

## Audio
`AVAudioSession` en categoría `.ambient`, para respetar el interruptor de silencio (configurable a `.playback`). `AVAudioEngine` + `AVAudioSourceNode` a 48 kHz en estéreo `Float32`. El callback convierte `int16` a float desde el `RingBuffer`. Las interrupciones (llamadas, Siri) pausan la emulación y al terminar se reanuda en pausa, no jugando.

## Ciclo de vida
- `background`: pausar la emulación, hacer flush de la SRAM y detener el motor de audio.
- `foreground`: volver a escanear la biblioteca. El juego queda en pausa hasta que Joel toque "Continuar".
- Si hay memoria baja (`didReceiveMemoryWarning`), se guarda y se sigue.
