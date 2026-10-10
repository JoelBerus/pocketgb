# N8 Kotlin · Game Boy Advance en la app Android: evidencia

Rama `n8-android-gba-kotlin` (desde `siguiente-nivel` @ `64e123d`: A9, N1, N2, N3, N4 Android y N8 nativo cerrados), worktree propio. Es el lote **N8 Kotlin** de [hitos/N-README.md](../hitos/N-README.md) §4 N8. La capa nativa (N8 nativo) ya estaba hecha: este lote conecta la app. `git diff 64e123d -- core gba` está vacío.

Commits:
- `a0535af` integración (biblioteca, sesión, partidas, pantalla, controles, ajustes) y 36 tests JVM;
- `f868f0e` firma `PGBA` en `StateStore`, kill-test GBA, catálogo `n8-*` e instrumentados;
- `c2a2136` L/R bajo el HUD en GBA horizontal, fila de BIOS legible, captura de la información técnica;
- `a86209c` medida de rendimiento en la app (`debug gba-bench`), guía, pruebas de Joel y 05-android-spec;
- `60252a5` prueba de la EEPROM que se confirma de 8 KiB solo leyendo y del traslado de un `.gba`;
- `11f4ec7` cadenas sustituidas fuera (lint). **Verificación desde limpio sobre `11f4ec7`.**

## Qué se hizo

### 1. Biblioteca
- `RomEntry.console: RomConsole` (GB/GBC/GBA; `core` = núcleo `Console`) **sustituye a `isColor`** en todo el código: chips, placeholder, descripción para TalkBack, detalle, ajustes del juego, filtros, catálogo y tests (60 usos).
- Escáner: `.gb`/`.gbc`/`.gba`. Límite por consola (`romLimit`: 8 MiB GB, 32 MiB GBA, = iOS `romLimit(for:)`); problemas propios `TOO_LARGE_GBA` e `INVALID_HEADER_GBA` con los textos de iOS. Cabecera GBA = iOS `RomHeader.parseGBA` (≥ 0xC0 bytes, byte fijo 0xB2 = 0x96, checksum de 0xA0..0xBC − 0x19, título 0xA0..0xAB con no imprimibles → `?`); se leen solo 0xC0 bytes.
- **N1 sigue funcionando con `.gba`:** la identidad de cabecera (sello de documento, detección de movimientos y caché de cabeceras) es 0xA0..0xBF (64 hex, frente a los 56 de GB: nunca se confunden); `HeaderCache` acepta las dos y descarta una cabecera de la otra consola (archivo renombrado de extensión). Test de traslado de un `.gba` entre carpetas con huella, favorito y alias.
- Filtro **GBA** (entre GBC y Favoritos, como iOS) en vertical y en el panel de filtros de N3/N4 (los dos recorren `LibraryFilter.entries`). La búsqueda encuentra también la consola (nombre corto exacto «gba» o el nombre completo desde 4 letras: «advance»). Categorías: un `.gba` cuenta como cualquier otro.
- Detalle (`GameDetails.gba`, = iOS `GameTechnicalInfo`): «Tipo de partida: Flash 128 KiB (detectado)», «Código del juego: BPEE · 01 · v0», partida («EEPROM (512 B u 8 KiB)», «Sin partida»…), «Reloj (RTC): Sí/No», checksum de cabecera y sin checksum global. `RomInspector.inspect(rom, console)` usa `CoreBridge(console)`.
- Textos de la biblioteca y de Acerca de con «.gb, .gbc y .gba» / «Game Boy, Game Boy Color y Game Boy Advance».

### 2. Sesión
- `GameLauncher`: `EmulatorSession(entry.core)`; lectura del ROM con el límite de su consola (`OpenError.RomTooLargeGba`); en GBA `loadGba(rom, ahora, gbaOptionsFor(huella), BIOS)`.
- BIOS: `GbaBiosSource` busca `gba_bios.bin` en la **raíz** de la carpeta por SAF (sin distinguir mayúsculas; nunca un documento virtual), lee como mucho 16 KiB + 1 y el lanzador solo la pasa si `GbaBios.isOfficial` (el puente nativo lo vuelve a comprobar). Con «Emulada» ni se lee. Sin BIOS válida, HLE.
- Orden de apertura idéntico a GB: espejo resuelto → `.sav` → estado automático (A9). Con EEPROM de 8 KiB el `.sav` de 8 KiB se carga antes del AUTO (nota de la auditoría N8 nativo).
- **DMA que confirma 8 KiB sin escribir** (nota de la auditoría): el cambio de tamaño del `.sav` cuenta como un guardado más (`GameSession`: `dirtySeq` + desplazamiento cuando `sramSaveSize` cambia), así el guardado periódico escribe el `.sav` de 8 KiB aunque el juego no haya escrito y un AUTO de 8 KiB nunca queda sin su `.sav`. Pausar o salir ya lo escribían (comparan contenido). Prueba instrumentada con una ROM sintética que solo lee (mutación: sin el arreglo falla «el .sav de 8 KiB se escribe mientras se juega»).
- `CoreError.StateConfig` (estado de otra configuración: tipo de partida, reloj, BIOS) → `ResumeFailure.INCOMPATIBLE`: se conserva y se ofrece «Jugar desde el inicio».
- **Hallazgo y arreglo:** `StateStore` solo aceptaba la firma `PGBS`; los estados de GBA empiezan por `PGBA`, así que todos se veían «Dañado» y «Continuar» nunca se ofrecía (el mismo bug que N3 iOS corrigió). Ahora acepta las dos (= iOS `StateStore.signatures`).
- `SessionResumableCore.cartridgeRam` en GBA = el medio real del instante (tras `loadSram`) sin los 16 B del RTC (iOS `ramBytes()`).

### 3. Partidas (regla 6)
- `SaveSizes.forInfo(info)`: GB igual que antes; GBA = medio (EEPROM sin ajuste: 512 u 8192) y, con RTC, + 16; solo RTC → {16}. Test: igual a `RomInfo.gbaValidSaveSizes` (= iOS) en 6 casos.
- Todo lo de N1/A5 vale igual: espejo `<rom>.sav` junto al ROM, copias apartadas, backups, historial, `FingerprintOwnership`, `SavesIndex` (ahora con `gbaMedia`/`gbaHasRtc` detectados, = iOS).
- Ajustes forzados que no casan con el `.sav` (local o espejo): `SaveLoadWarning.GameSettingsMismatch` con los títulos y textos de iOS `GameSettingsSaveWarning`; el `.sav` no se toca y la sesión no guarda.
- Kill-test: `tools/android-save-kill-test.sh N gba` (modos `save-stress-gba`/`save-verify-gba`): ROM sintética ARM propia (contador de 16 bits por VBlank en la SRAM) con RTC forzado → `.sav` de 32 784 B.

### 4. Pantalla
- `Viewport.calculate(w, h, modo, screen)` = `compute_layout` del blit nativo para cualquier consola (test exhaustivo contra la transcripción del C en 110 tamaños × 2 modos × 2 consolas).
- Vertical: la superficie toma `session.console.screen.aspectRatio` (3:2 en GBA). Horizontal: superficie a pantalla completa con el blit 3:2 (entero o llenar).
- `FramePng` por número de píxeles (240×160 en GBA): portadas (`ArtworkStore`) y miniaturas de estados; las tarjetas de 10:9 recortan centrado (`ContentScale.Crop`) y el detalle usa el marco 3:2; las miniaturas de estados toman la proporción de la captura.

### 5. Controles
- `ControlId.L/R` (al final del enum: los ids virtuales de TalkBack de los demás no cambian), cápsulas de 92×40 dp como iOS, con prioridad en el toque (como iOS) y bits 9/8 (`GbaButtonBits`).
- Disposición GBA de fábrica = iOS `ControlsLayout.defaults(_, shoulders: true)`: vertical, la de GB + L (0,17; 0,14) y R (0,83; 0,14); horizontal, cruceta al 0,6 y A/B/Start/Select en los márgenes. **Diferencia con iOS:** en horizontal L/R a y = 0,26 (iOS 0,1) porque en Android el HUD de pausa y velocidad ocupa la esquina superior derecha y tapaba R (visto en la captura; test `inLandscapeRStaysClearOfTheHudAndOfA`).
- Disposición guardada por **consola × orientación** (`gbaPortraitLayout`/`gbaLandscapeLayout`, también en la lectura campo a campo del archivo); el editor dice «Controles GBA · Vertical/Horizontal» y «Restablecer» vuelve a la de fábrica de GBA.
- TalkBack: nodos «Botón L»/«Botón R» con `ACTION_CLICK` (pulsación de 100 ms) solo en GBA.
- **Mando** (= iOS: hombros = L/R): en GBA, L1/R1 son siempre L y R; lo que el mapeo tuviera en L1/R1 pasa a L2/R2 si están libres (`GamepadMapping.forConsole`). De fábrica:

| Mando | Game Boy | Game Boy Advance |
|---|---|---|
| L1 | Menú | **L** |
| R1 | Avance rápido | **R** |
| L2 | — | Menú |
| R2 | — | Avance rápido |
| Mode (Guía) | Menú | Menú |
| B / A (derecho / inferior) | A / B | A / B |

  Ajustes › Controles › Botones del mando muestra un grupo «En juegos de Game Boy Advance» con L, R y lo que cambia. iOS no asigna pausa ni velocidad al mando: en Android se conservan en los gatillos.

### 6. Ajustes por juego GBA (centro de ajustes de N4)
- `GameOverrides.gbaSaveType` (valor nativo 1..6), `gbaRtc` (1/2) y `gbaUseBios` (`false` = Emulada), por huella; valores desconocidos del disco se descartan sin tumbar el archivo.
- Sección «Partida, reloj y BIOS» con «Detectado (Flash 128 KiB)» / «Detectado (con reloj)» (de `SavesIndex`), Sin partida, SRAM 32 KiB, Flash 64/128 KiB, EEPROM 512 B/8 KiB; Con/Sin reloj; Global/Emulada; estado de la BIOS; «Volver a lo detectado». Mismos textos que iOS.
- Ajustes › Emulación: grupo «Juegos de Game Boy Advance» con el estado de la BIOS.

### 7. Guía y documentación
[guia/gba-android.md](../guia/gba-android.md) (BIOS opcional, nunca incluida; L/R; mando; partidas; ajustes), [PRUEBAS-JOEL](../PRUEBAS-JOEL.md) §N8 (N8-1..N8-8) y [05-android-spec](../05-android-spec.md) §Arquitectura nativa multiconsola (párrafo «App (N8 Kotlin)»).

## Paridad con iOS
| Pieza | iOS | Android (N8 Kotlin) |
|---|---|---|
| Escáner `.gba` | `extensions` + `romLimit` + `parseGBA` | igual (misma fórmula de checksum y título) |
| Consola en la biblioteca | `ConsoleBadge` (GB/GBC/GBA), `LibraryFilter.gba` | `RomConsole`, `LibraryFilter.GBA`; además búsqueda por consola |
| Tamaños de `.sav` | `GBACoreBridge.validSaveSizes` | `SaveSizes.forInfo` (test de igualdad con `gbaValidSaveSizes`) |
| Aviso de ajustes | `GameSettingsSaveWarning` | `SaveLoadWarning.GameSettingsMismatch` (mismos textos) |
| BIOS | `BIOSFile` en la carpeta, SHA-256 | `GbaBiosSource` en la raíz por SAF, mismo SHA-256 |
| Firmas de estado | `PGBS`, `PGBA` | `PGBS`, `PGBA` (antes solo `PGBS`) |
| Estado de otra configuración | `notCurrent` (se retira) | `INCOMPATIBLE` (se conserva; más conservador) |
| L/R táctiles | cápsulas, defaults con `shoulders` | iguales salvo L/R en horizontal a y = 0,26 (HUD) |
| L/R con mando | hombros | L1/R1; pausa y velocidad pasan a L2/R2 |
| Ajustes por juego | tipo de partida, RTC, BIOS | iguales |
| Detalle | «Tipo de partida (detectado)», código | igual + fila «Reloj (RTC)» |

## Verificación desde limpio (`11f4ec7`)
`git archive` en `scratchpad/n8-kotlin/clean2` + `android/local.properties`, ROMs libres **enlazadas** (`core/tests/roms`, `gba/tests/roms` → `pocketgb-integracion`), `make -C gba homebrew` con el `clang`/`ld.lld`/`llvm-objcopy` del NDK (rc=0, 23 ROMs):
```
$ ./gradlew --no-daemon --max-workers=1 clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
buildCMakeDebug y buildCMakeRelWithDebInfo × 4 ABI
BUILD SUCCESSFUL in 5m 1s — 123 actionable tasks: 122 executed, 1 up-to-date
JVM: 708 tests, 0 skipped, 0 failures, 0 errors   (668 en 64e123d → +40)
lint: 0 errors, 23 warnings   (ninguno nuevo de N8: UseKtx, Typos «momento», versiones, PluralsCandidate…)
aapt2 dump permissions (Debug y Release): solo DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION → sin INTERNET
```
Dentro de `with-lock.sh emu` (Small_Phone_API_35, arm64-v8a, `show_ime_with_hard_keyboard 0`, sin animaciones):
```
:app:connectedDebugAndroidTest   tests=460 failures=1 errors=0 skipped=0   (447 en 64e123d → +13)
  FALLO GbaNativeTest.homebrewSceneWithIrqAndDmaMatchesTheNativeRunnerFor300Frames (hash acumulado de ppu_scene_11)
```
El único fallo es del entorno: compilé las homebrew con el `clang` del NDK delante en el `PATH`; el hash de referencia (N8 nativo) es el de las ROMs compiladas con el `clang` del sistema y solo el `ld.lld`/`llvm-objcopy` del NDK al final del `PATH`. Con ese `PATH` (como documenta N8-nativo-evidencia) y el mismo árbol limpio:
```
$ PATH="$PATH:$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin" make -C gba homebrew   (clang = /usr/bin/clang) rc=0
GbaNativeTest tests=21 failures=0 errors=0 skipped=0
GbaGameTest   tests=8  failures=0 errors=0 skipped=0
```
Ni `gba/` ni la capa nativa cambian en este lote.

Kill-tests con el APK Debug del árbol limpio:
```
$ tools/android-save-kill-test.sh 50 gb
Resultado (gb): OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
$ tools/android-save-kill-test.sh 50 gba
Resultado (gba): OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
   (cada línea: OK bytes=32784 counter=… confirmed=… backups=5 stateTmpFound=0 stateTmpOrphans=0)
```

## Tests nuevos
- **JVM (+40):** `LibraryScannerGbaTest` 7 (límites 32/8 MiB, solo 0xC0 bytes, cabeceras hostiles —byte fijo, corta, vacía, no imprimibles, checksum—, identidad y caché sin confundir consolas, traslado con huella/favorito/alias), `LibraryConsoleTest` 5 (filtros, combinación con categoría y búsqueda, búsqueda por consola, categorías, consola por extensión), `SaveSizesGbaTest` 4 (cada medio con y sin RTC, igualdad con la sesión, pie del RTC, aviso de ajustes), `GamepadGbaMappingTest` 4, `ControlsGbaLayoutTest` 6 (defaults = iOS, L/R solo en GBA y dentro del área con ≥ 48 dp, R libre del HUD, prioridad del toque y máscara, TalkBack, disposición GB sin hombros), `GameplaySettingsGbaTest` 5 (persistencia por huella, valores hostiles, ida y vuelta del archivo, disposición por consola × orientación, escala de fábrica GBA), `ViewportGbaTest` 3, `ExactContinuationGbaTest` 1, `GbaDetailsTest` 3, `StateStoreGbaTest` 2.
- **Instrumentados (+13):** `GbaGameTest` 8 (`arm.gba` desde una carpeta SAF hasta la pantalla «All tests passed»; partida + RTC importada del espejo, guardada en local y en el espejo y de vuelta byte a byte; estados con captura de 240×160; continuación exacta con `eeprom8k.gba`; EEPROM confirmada de 8 KiB solo leyendo; ajustes forzados que no casan; BIOS no oficial nunca cargada y «Emulada» sin lectura; `gba_bios.bin` de la raíz por SAF), `GbaControlsTest` 3 (L/R táctiles, TalkBack, máscara en la sesión GBA y recortada en GB), `GbaGameplayScreenTest` 2 (3:2 en GBA, 10:9 en GB).

## Rendimiento (app Debug, `-O2`, emulador)
Modo `--es debug gba-bench` (solo Debug): ms por fotograma del núcleo (`gba_run_frame` sin pausas, 600 fotogramas) y fotogramas por segundo de la **sesión real** a ×4 (hilo nativo, audio, sin superficie) durante 5 s. El emulador arm64 corre en el M del Mac, con el Mac cargado (load ≈ 50): **no representa un teléfono**.
```
carga del Mac: { 54.22 47.75 64.43 }
GBA-BENCH ppu_scene_11.gba sdk_gphone64_arm64 arm64-v8a núcleo=2.202 ms/frame sesión×4=239.0 fps (tope 239)
GBA-BENCH arm.gba          sdk_gphone64_arm64 arm64-v8a núcleo=2.808 ms/frame sesión×4=238.9 fps (tope 239)
GBA-BENCH ppu_scene_11.gba sdk_gphone64_arm64 arm64-v8a núcleo=5.154 ms/frame sesión×4=258.4 fps (tope 239)
GBA-BENCH arm.gba          sdk_gphone64_arm64 arm64-v8a núcleo=0.997 ms/frame sesión×4=238.9 fps (tope 239)
```
La sesión llega al tope de ×4 (≈ 239 fps; los 258 son la recuperación del ritmo tras un tirón del anfitrión): sobra margen para 60 fps. La medida en el teléfono de Joel con Kirby queda preparada en [PRUEBAS-JOEL](../PRUEBAS-JOEL.md) N8-2 (`--es library "Kirby - Nightmare in Dream Land.gba"`: el ROM se lee de su carpeta y nunca sale de ella).

## Capturas (bloque `n8-*` al final de `tools/android-screens.txt`, 20 líneas)
`SCREENS=<ids n8-*> THEMES="light dark" tools/android-screenshots.sh` con el APK limpio: 20 capturas (rc=0) en `scratchpad/n8-kotlin/final/shots/`; todas revisadas a ojo:
- `n8-library-portrait-light/dark`: chips GB/GBC/GBA, carril con Kirby (portada 3:2 recortada en la tarjeta 10:9).
- `n8-library-filter-gba-portrait-light`, `n8-library-landscape-filters-landscape-light`: filtro GBA en vertical y en el panel.
- `n8-details-portrait-light/dark`, `n8-details-landscape-landscape-light`, `n8-details-technical-portrait-light`: imagen 3:2 y datos GBA.
- `n8-game-center(-custom)-portrait-*`: Tipo de partida/Reloj/BIOS «Detectado (Flash 128 KiB)» y «Personalizado».
- `n8-settings-emulation`, `n8-controller-mapping`: BIOS y grupo GBA del mando.
- `n8-gameplay-portrait-dark`, `n8-gameplay-landscape(-fill)-landscape-dark`: ROM GBA sintética 3:2 con L/R (R bajo el HUD).
- `n8-editor-portrait-dark`, `n8-editor-landscape-landscape-dark`: «Controles GBA · Vertical/Horizontal» con L/R elegidos.
- `n8-states-portrait-dark` (capturas 240×160), `n8-settings-warning-portrait-dark` (aviso de ajustes).

## Lo no verificado
- **Kirby en el teléfono de Joel** (60 fps ≥ 30 min, guardar y cierre forzado, L/R reales, ms/frame): PRUEBAS-JOEL N8-1..N8-7.
- **BIOS real**: sin la BIOS oficial no se puede probar la ruta positiva (ni el logo de arranque); sí que una que no lo es nunca llega al núcleo y que con «Emulada» ni se lee (N8-8 para Joel).
- **Mando físico real** en GBA: probado con `KeyEvent` sintéticos (JVM) y en el teléfono queda N8-4.
- **Drive real** (proveedor remoto) con `.gba` grandes: probado con el proveedor de pruebas SAF.

## Observaciones fuera de alcance
- **RTC que cambia el juego sin escribir el medio.** `rtc_commit` (gba/) no marca la partida como sucia: el guardado *periódico* no lo ve hasta el siguiente cambio del medio. Pausar, ir a segundo plano o salir sí lo escriben (comparan contenido, el pie del RTC incluido). Solo un cierre forzado justo después de ajustar el reloj sin guardar perdería ese ajuste. Igual en iOS (`gba_save_dirty`). Propuesta para un lote nativo: marcar sucia la partida en `rtc_commit` cuando cambie el desplazamiento.
- iOS: un estado de otra configuración (`StateConfig`) se retira como `notCurrent`; Android lo conserva. Unificar en un lote iOS si se prefiere conservar.
