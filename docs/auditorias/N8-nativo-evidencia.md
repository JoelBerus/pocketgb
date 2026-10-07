# N8 nativo · Game Boy Advance en Android (capa nativa y JNI): evidencia

Rama `n8-android-gba-nativo` (desde `siguiente-nivel` @ `c3d8405`), worktree propio. Commits: `25756cc` (implementación, tests y 05-android-spec) y `9458375` (prueba de superficie GBA). Es el lote **N8 nativo** de [hitos/N-README.md](../hitos/N-README.md) §4 N8. Solo se tocó:
- la capa nativa (`android/app/src/main/cpp/`);
- el envoltorio Kotlin mínimo (`emulator/`);
- los tests;
- la tarea Gradle `copyGbaTestRoms`;
- [05-android-spec.md](../05-android-spec.md) §Arquitectura nativa multiconsola.

No se tocaron `core/`, `gba/`, biblioteca, escáner, UI, `saves/`, `game/`, `input/` ni `settings/`. `git diff c3d8405 -- core gba` está vacío.

## Arquitectura (detalle en 05-android-spec.md)
- **Una `libpocketgb.so` con los dos núcleos.**
  - `CMakeLists.txt` compila `gba/src/*.c` como biblioteca de objetos `pocketgba`. Usa sus propias rutas de cabeceras y los flags de `gba/Makefile`: `-std=c11 -Wall -Wextra -Werror -pedantic -fno-common`.
  - La enlaza con `core/src`. `core/src/sha256.c` entra **una sola vez**.
  - Son objetos y no un `.a`: un símbolo repetido entre los dos núcleos sería un error de enlace.
- **Sesión multiconsola.**
  - `native_session_create_console(GB|GBA)` crea un único núcleo. Fija la pantalla (160×144 o 240×160) y la máscara de botones (0xFF o 0x3FF).
  - Todo acceso al núcleo pasa por funciones `core_*`, con un `if` por consola.
  - Son comunes y no cambian en GB: hilo, pacing, ring SPSC + AAudio, `Surface`, escalado (en «llenar», 10:9 o 3:2), aparcado e instantáneas.
  - `native_session_create()` sigue siendo GB.
- **Partida GBA con el formato de iOS: medio crudo + 16 B de RTC al final.**
  - Se acepta el medio solo, o el medio + RTC. Con un medio de 0 bytes, solo el RTC.
  - Ante un tamaño o un RTC inválidos no cambia nada. Si el medio falla después de aplicar el RTC, se restaura el RTC anterior.
  - **EEPROM sin ajuste:**
    - pasa de 512 B a 8 KiB cuando el `.sav` o la primera DMA lo confirman;
    - la instantánea se reserva para el tamaño máximo;
    - el hilo nativo actualiza `sram_size` en cada frame;
    - `copySram` devuelve el tamaño exacto de ese instante (holder JNI).
- **BIOS opcional.**
  - Solo se carga si mide 16 KiB y su SHA-256 es el de la BIOS oficial, el mismo valor que iOS (`ConsoleTest` compara las tres copias: Kotlin, C e iOS).
  - Si no lo es, se ignora y se usa HLE (`RomInfo.biosLoaded = false`).
  - El RTC usa hora local (`localtime_r` + `tm_gmtoff`).
- **Códigos de resultado.** Un único espacio para Kotlin:
  - los `gb_result`;
  - 17 = argumento inválido;
  - los `gba_result`, traducidos al código GB equivalente o a 18/19/20.
- **Kotlin.**
  - Tipos nuevos: `Console`, `ScreenSize`, `GbaButtonBits` (R en el bit 8, L en el bit 9), `GbaOptions`/`GbaSaveType`/`GbaRtc` y `GbaBios`.
  - Sesión y núcleo: `EmulatorSession(console)` con `loadGba`, y `CoreBridge(console)` con `loadGbaRom`.
  - `RomInfo` añade los campos GBA y `gbaValidSaveSizes` (= iOS `validSaveSizes`).
  - `CoreBridge.SCREEN_*` y `FRAME_PIXELS` siguen siendo los de GB, para los llamadores que aún no distinguen consola.

## Comandos y salidas

### Núcleos (Mac): sin cambios
```
$ make -C core test HITO=M9
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
```

`make -C gba test` falla en el Mac porque falta `ld.lld`. Con el `ld.lld` y el `llvm-objcopy` del NDK al **final** del `PATH` (el `clang` sigue siendo el del sistema) se compilan las homebrew. La suite pide `ARM7TDMI/v1/*.json.bin`, que no está en el directorio de ROMs compartido (`gba/tests/roms` → `pocketgb-integracion`; no lo descargué para no escribir fuera del worktree). Se corre la misma suite sin esa línea:
```
$ PATH="$PATH:$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin" make -C gba test HITO=G9
tests/suite.txt:6: nada coincide con 'ARM7TDMI/v1/*.json.bin'
$ python3 tests/run_suite.py --bin build/gbatest --roms tests/roms --suite <suite sin SST> --hito G9 --hb build/hb
PASS  G1 unit: PASS unit: 0 fallos
PASS  G1 gba-tests/arm/arm.gba: PASS tests/roms/gba-tests/arm/arm.gba (2 frames)
… (G2–G6: memory, bios, nes, 3 PPU jsmolka, 14 escenas, sram/flash64/flash128/none, eeprom/eeprom8k/rtc, 6 de audio, 7 state, 3 det)
46/46 sin fallos requeridos (23.0 s)
```

### Símbolos y estado global
- **`make -C gba check-symbols` en macOS.** Falla con `Símbolos repetidos entre núcleos: _sha256`. El filtro `grep -v ' sha256$'` del Makefile no contempla el `_` de Mach-O. Es un fallo previo de la herramienta; en el CI Linux (`gba.yml`) pasa.
  - El mismo cálculo, aceptando el `_`: `GB: 91 GBA: 79 comunes: 0 sin prefijo: 0`.
- **`make -C gba check-globals` en macOS.** Da falsos positivos con los símbolos `s` de sección de Mach-O (tablas `lJTI`, `ltmp`). Hice la misma comprobación sobre los objetos **ELF de Android** (pocketgba, native_session, pocketgb_jni, audio): ningún símbolo `b/B/d/D/c/C/s/S` en ninguno de los 4 ABI.
- **`libpocketgb.so` Debug, 4 ABI** (`llvm-nm -D --defined-only`):
```
arm64-v8a:   total=278 gba/arm=79 sha256=1 duplicados=0
armeabi-v7a: total=278 gba/arm=79 sha256=1 duplicados=0
x86:         total=278 gba/arm=79 sha256=1 duplicados=0
x86_64:      total=278 gba/arm=79 sha256=1 duplicados=0
```

### Desde limpio (aviso del orquestador)
`git archive HEAD` (`9458375`) en `scratchpad/n8-clean2` + `android/local.properties`. Las ROMs libres se **enlazan** (no se copian al árbol): `gba/tests/roms` y `gba/build/hb`.
```
$ ./gradlew --no-daemon --max-workers=1 clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
> Task :app:buildCMakeDebug[arm64-v8a] [armeabi-v7a] [x86] [x86_64]
> Task :app:buildCMakeRelWithDebInfo[arm64-v8a] [armeabi-v7a] [x86] [x86_64]
> Task :app:assembleDebug / :app:assembleRelease / :app:lintDebug / :app:testDebugUnitTest
BUILD SUCCESSFUL in 4m 58s
123 actionable tasks: 122 executed, 1 up-to-date
JVM: 384 tests, 0 skipped, 0 failures, 0 errors (ConsoleTest: 9)
lint: 20 avisos, 0 errores (en archivos de este lote, solo versiones de dependencias ya existentes en build.gradle.kts)
aapt2 dump permissions (Debug y Release): solo com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION → sin INTERNET
```

Una primera corrida desde limpio sobre `25756cc` (`scratchpad/n8-clean`, sin `clean` porque era un árbol recién extraído) compiló los 4 ABI, Debug y Release, y pasó lint. Sin embargo, `ProcessKillTest` (`saves/`, no es de este lote) falló dos veces con la carga del Mac en 315–948 de load average:
- `guardados completados=0` y `20` (exige > 40);
- `kills en bucle=174/200` (exige ≥ 180).

Repetido con la carga en ~12: 384/384, `guardados completados=126`. Es un test sensible a la carga del anfitrión, como ya documenta su comentario.

### Instrumentados (emulador `Small_Phone_API_35`, arm64-v8a, con `with-lock.sh emu`)
**GBA nuevos** (`GbaNativeTest`, 16 casos, en el worktree):
```
('com.joelbermudez.pocketgb.emulator.GbaNativeTest', tests=16, failures=0, errors=0, skipped=0)
```
- `armGbaPassesAndEveryFrameMatchesTheNativeRunner`: `CoreBridge(Console.GBA)` ejecuta arm.gba 120 frames.
  - El SHA-256 de los 120 frames concatenados es igual al del runner nativo del Mac: `fc7d116b…`.
  - El último frame es la pantalla «All tests passed» de jsmolka (`afc52560…`). Es lo que da `gbatest --mode jsmolka` (PASS en 2 frames; después la pantalla es estática).
- `thumbGbaPasses`: thumb.gba llega a la misma pantalla de PASS.
- `flash128AndPpuScenesMatchTheNativeRunnerFrameByFrame`: igual al runner frame a frame en flash128 (200 frames, `c493bfdd…`), stripes (60) y shades (60).
- `homebrewSceneWithIrqAndDmaMatchesTheNativeRunnerFor300Frames`: escena 11 (IRQ de HBlank/VCount y DMA), 300 frames idénticos al runner.
- `sessionRunsArmGbaToThePassScreenWithAudio`: `EmulatorSession(Console.GBA)` con arm.gba.
  - Huella `77ee8866…`.
  - Pantalla 240×160, también según el nativo.
  - Tras pausar, el frame es la pantalla de PASS.
  - Hay audio en el ring.
- `gbaStateRoundTripRestoresFrameAndSram`:
  - el estado (≈666 KiB) restaura la SRAM y la pantalla de su momento;
  - guardar justo después de cargar da los mismos bytes.
- `hostileForeignOrOtherConfigurationStatesNeverTouchTheSession`: ninguno de estos estados toca la sesión (partida y estado intactos):

  | Estado cargado | Error |
  |---|---|
  | Dañado | `StateCorrupt` |
  | 64 bytes | `StateMagic` |
  | 2 MiB | `StateCorrupt` |
  | De GB | `StateMagic` |
  | De otro ROM GBA | `StateRomMismatch` |
  | Del mismo ROM con RTC forzado | `StateConfig` |

- `sramWithRtcRoundTripsInTheIosFormat`: SRAM 32 KiB + RTC forzado.
  - `.sav` = 32768 + 16, con ida y vuelta exacta. El medio solo conserva el RTC.
  - Tamaños 0, 16, ±1, +15, +17 y +48 rechazados. Un RTC de 2^62 s también, sin tocar nada.
  - Corriendo, la instantánea del hilo nativo lleva el RTC.
- `rtcOnlyCartridgeSavesJustTheClockBlock`:
  - medio NONE + RTC: el `.sav` mide 16; 0 y 32 se rechazan;
  - sin medio ni RTC: `hasBattery = false`.
- `eepromWithoutSettingTakesItsSizeFromTheSaveOnce`:
  - EEPROM automática: 512 B, capacidad 8192. Un `.sav` de 8 KiB la confirma y después 512 se rechaza.
  - Forzada a 512: capacidad 512 y 8 KiB se rechaza.
- `eepromSizeConfirmedByTheFirstDmaWhileRunningReachesTheSnapshot`:
  - eeprom8k.gba (homebrew propia): tras la primera DMA, `sramSaveSize` pasa a 8192. La copia **en marcha** mide 8192 y trae los bloques 5, 6 y 1000 que escribe la ROM.
  - eeprom.gba: 512 con el bloque 5.
- `runningCounterIsCopiedFreshAndCountsSaves`: la copia en marcha cambia; `dirtySeq` crece y se para en pausa.
- `shoulderButtonsReachTheGbaCoreAndAreMaskedPerConsole`:
  - L y R llegan a `appliedButtons` del hilo nativo;
  - -1 o 0xFFFF se recortan a 0x3FF;
  - en GB, L se recorta (0x01).
- `nonOfficialBiosIsRejectedAndTheCoreUsesHle`: BIOS de 16 KiB falsa, de 1 MiB o vacía.
  - `biosLoaded = false` y `nativeGbaBiosIsOfficial = false`.
  - arm.gba sigue pasando con HLE.
- `hostileArgumentsAreRejectedBeforeTouchingTheCore`: consola 2/-1 → 0; ROM de 32 MiB + 1 → 4 sin copiarla; `saveType` 7/-1 y `rtc` 3 → 17; ROM < 0xC0 → 3; cabecera sin 0x96 → 18; ROM GB en sesión GBA y al revés → 17; GB > 8 MiB → 4; cabecera GBA en búfer corto → 16; framebuffer de 160×144 en GBA → 16; `.sav` de 4 MiB → 11 sin copiarlo; `WrongConsole` en Kotlin.
- `millisecondsPerFrameOnThisDevice`: ver la sección Rendimiento.

**Regresión GB: suite instrumentada completa en el worktree** (`connectedDebugAndroidTest`, 353 casos):
- 351 pasan.
- Fallan 2 de `ControlsTalkBackTest` (`anAccessibilityPressIsCombinedWithAHeldTouch`: «el dedo sigue en B expected:<2> but was:<10>»; y `clickOnAPressesTheABitForAbout100Ms`). Son tiempos de 100–250 ms en `input/` (no tocado, sin sesión nativa), con load average de 443 en el Mac.
- La clase sola, repetida: `('com.joelbermudez.pocketgb.input.ControlsTalkBackTest', tests=14, failures=0, errors=0, skipped=0)`.
- Todo lo que usa la sesión nativa pasa:

  | Clase | Casos |
  |---|---|
  | `CoreBridgeTest` | 6 |
  | `EmulatorSessionHandleLockTest` | 3 |
  | `EmulatorSessionTest` | 14 |
  | `NativeLibraryTest` | 1 |
  | `NativeSaveBridgeTest` | 19 |
  | `NativeAudioTest` | 4 |
  | `GameSurfaceTest` | 3 |
  | `GameSessionTest` | 13 |
  | `GameSessionHardeningTest` | 18 |
  | `SaveCyclesTest` (100 ciclos) | 1 |
  | `SaveFilesOnAndroidTest` | 2 |

**Desde limpio** (`connectedDebugAndroidTest` en `scratchpad/n8-clean2`, sobre `9458375`, con el emulador arrancado dentro de `with-lock.sh emu`; `boot_completed=1`, `abi=arm64-v8a`):
```
-P…package=com.joelbermudez.pocketgb.emulator   → BUILD SUCCESSFUL; tests=59 failures=0 errors=0 skipped=0
  CoreBridgeTest 6 · EmulatorSessionHandleLockTest 3 · EmulatorSessionTest 14 · GbaNativeTest 16 · NativeLibraryTest 1 · NativeSaveBridgeTest 19
-P…class=com.joelbermudez.pocketgb.video.GameSurfaceTest → BUILD SUCCESSFUL; tests=4 failures=0 (incluye gbaSessionDrawsAndSurvivesSurfaceRecreationInBothScaleModes)
BENCH arm64-v8a arm.gba 1.877 ms/frame | ppu_scene_11.gba 16.133 ms/frame | shades.gba 5.456 ms/frame   (con el Mac a load ≈ 680)
```
El argumento con varios paquetes o clases separados por coma solo ejecutó el primero, así que `NativeAudioTest` (4) no corrió desde limpio; sí corrió en la suite completa del worktree, con el mismo código. Los 16 GBA no se saltaron: las ROMs estaban en los assets (`copyGbaTestRoms`).

### Kill-test de partidas GB (`tools/android-save-kill-test.sh 50`)
```
iter  50  kill=kill9      tras  897 ms  -> OK bytes=8192 counter=3023 confirmed=3023 backups=5 stateTmpFound=0 stateTmpOrphans=0
Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
```

### Fusión con A9
`git merge-tree --write-tree HEAD a9-android-paridad` (`a615fe9`) termina sin conflictos (rc=0). Los fuentes nativos fusionados compilan con el clang del NDK (`-std=c11 -Wall -Wextra -Werror -pedantic`, aarch64).

**Hay que adaptarlo al fusionar.** `native_session_set_rtc_time` de A9 llama a `gb_rtc_set_time(session->core, …)`. En una sesión GBA `session->core` es NULL, así que es un no-op seguro pero incorrecto. Debe llamar a `core_rtc_set_time(session, unix_time)`, que en GBA usa la hora local. Lo hace quien fusione (lote N8 Kotlin o integración).

## Rendimiento (orientativo; el dato real es el del teléfono de Joel)
| ROM | App Debug en el emulador (CMake Debug, sin `-O`) | Runner `-O2` (NDK) en el emulador | Runner en el Mac (host) |
|---|---|---|---|
| arm.gba (bucle `b .` + texto) | 2,04 ms/frame | 0,57 ms (1763 fps) | 0,55 ms (1809 fps) |
| ppu_scene_11 (IRQ por línea + DMA) | 8,90 ms/frame | 1,80 ms (556 fps) | 1,81 ms (553 fps) |
| shades.gba | 3,51 ms/frame | 0,81 ms (1227 fps) | 0,75 ms (1325 fps) |

En la corrida desde limpio, con el Mac a load ≈ 680, la app Debug dio 1,88, 16,13 y 5,46 ms/frame: la cifra depende mucho de la carga del anfitrión. El emulador arm64 corre en el M del Mac casi a velocidad nativa, así que **no representa un teléfono**. La build Release de la app compila con `RelWithDebInfo` (`-O2`) y no la medí dentro de la app. Un frame dura 16,74 ms.

## No verificado
- **BIOS real.** La ruta positiva (BIOS oficial cargada, `biosLoaded = true`) no se probó: no hay BIOS en el repo (regla 1) y no usé la de Joel. Solo se verificó el rechazo y que el SHA-256 es el mismo en Kotlin, C e iOS.
- **RTC del GBA al reanudar.** No se observa por JNI: el bloque del `.sav` solo guarda desplazamiento y estado, no la hora. Sí se ejecuta en cada `resume` y en cada carga.
- **Audio GBA.** Solo se comprobó que el hilo produce muestras, no que sean iguales a las del runner.
- **ms/frame en el teléfono de Joel**, Kirby 30 min, capturas GBA y kill-test GBA 50/50. Son criterios de N8 que necesitan la ruta de partidas y la UI (lote N8 Kotlin) o el dispositivo (🤖 Joel).
- **Interacción del crecimiento de la EEPROM (512 → 8192) con `SaveCoordinator`.** No se verificó cómo reacciona la línea base o la política de vaciado al cambio de tamaño a mitad de sesión.

## Lo que queda para «N8 Kotlin»
- `RomEntry.console` y `Console.fromFileName`. El escáner acepta `.gba` ≤ 32 MiB (`LibraryScanner.MAX_ROM_BYTES` hoy es 8 MiB) y hay que añadir un filtro GBA.
- `GameLauncher`:
  - `EmulatorSession(Console.GBA)` + `loadGba(rom, ahora, GbaOptions, bios)`;
  - la BIOS leída de `gba_bios.bin` de la carpeta, con `GbaBios.status` para la UI;
  - el aviso de ajustes que no casan con el `.sav` (iOS `GameSettingsSaveWarning`).
- `SaveSizes`/`SaveStore` con `RomInfo.gbaValidSaveSizes`; `recoverOrphans` con esos tamaños; y la línea base cuando la EEPROM crece. Además, kill-test GBA 50/50.
- Vídeo y portadas. Hoy usan `CoreBridge.SCREEN_*` y `FRAME_PIXELS` fijos de GB y deben pasar a `session.screen` o `Console.screen`:
  - `Viewport`, viewport 3:2;
  - `FramePng`, `FrameThumbnail` y `ArtworkStore`, para 240×160;
  - `SavedState.pixels`.
- Controles:
  - L/R táctiles y de mando con disposición GBA propia (`GbaButtonBits`);
  - en GBA, L1/R1 pasan a ser L/R y la pausa y la velocidad se reasignan, como en iOS;
  - `GameBoyButton` sigue con 8 bits.
- Ajustes por juego GBA: `GbaSaveType`, `GbaRtc`, usar BIOS. Datos GBA en el detalle: código de juego, medio (`gbaSaveType`, `eeprom`) y BIOS; ocultar «checksum global» (en GBA siempre `true`).
- Adaptar `native_session_set_rtc_time` de A9 (ver Fusión con A9).

## Hallazgos fuera del alcance
- `gba/Makefile`: `check-symbols` y `check-globals` dan falsos positivos en macOS (`_sha256` y símbolos `s` de Mach-O). En el CI Linux pasan.
- `ProcessKillTest` (JVM) y `ControlsTalkBackTest` (instrumentado) fallan de forma intermitente con el Mac muy cargado.
