# 05 · Implementación Android nativa

> La especificación vigente y aprobada está en [diseno-android/SPEC.md](diseno-android/SPEC.md). Este documento describe la implementación real (estado A1–A7 implementados, A8 en curso) y las equivalencias técnicas con iOS.

Objetivo: la **misma app** que [04](04-ios-spec.md), con UI 100 % nativa en Kotlin/Compose y el **mismo `core/`** compilado con el NDK. No se usan Flutter, React Native, KMP UI ni WebView.

## Requisitos
- Android Studio, JDK 17, SDK Platform 37, Build Tools 37.0.0, NDK 27.3.13750724 y CMake 3.22.1.
- minSdk 26 (AAudio), targetSdk 37, Kotlin 2.x, Jetpack Compose (BOM 2026.09.00), Navigation 3. Solo AndroidX y kotlinx-serialization en runtime.
- Paquete `com.joelbermudez.pocketgb`. Sin permiso `INTERNET`: se verifica con `aapt2 dump permissions` sobre el APK Release.
- Versión actual: `versionName 1.0.0`, `versionCode 2`.

## Estructura real
```
android/
  app/build.gradle.kts
  app/src/main/cpp/
    CMakeLists.txt        # biblioteca `pocketgb` = core/src + objetos de gba/src (N8) + los cuatro de abajo
    pocketgb_jni.c        # puente JNI → API C, con validación de argumentos hostiles
    native_session.c      # sesión nativa: estados, hilo de emulación, pacing, SRAM y estados
    audio_output.c        # salida AAudio (callback de tiempo real)
    audio_ring.c          # ring buffer SPSC lock-free
  app/src/main/java/com/joelbermudez/pocketgb/
    app/ emulator/ video/ audio/ input/ game/ saves/ library/ settings/ ui/
  app/src/{debug,release,test,androidTest}/
```
Mapa de paquetes en [android/README.md](../android/README.md).

## Arquitectura
- **`EmulatorSession`** (`emulator/`): envoltorio Kotlin del handle nativo. Un candado de handle evita usar la sesión durante el cierre; el handle nativo solo se destruye una vez.
- **`GameSession`** (`game/`): une sesión, partida y estados de un juego abierto. Gestiona el cierre (sincrónico si puede; con un reaper si el hilo de guardado no responde) y la reparación tras un rollback fallido.
- **`SaveCoordinator`** (`saves/`): un único hilo `pocketgb-saves` serializa todo el I/O de partidas y estados; vaciado síncrono de la SRAM, políticas de `SramFlushPolicy`, `AtomicSaveWriter` (temporal, `fsync`, rename) con 5 backups rotativos y espejo `<rom>.sav` junto a la ROM vía SAF (`SafSaveMirror`, `MirrorChannel`).
- **`FingerprintOwnership`**: un solo dueño por huella de ROM (SHA-256 truncado a 128 bits) en el proceso; impide dos sesiones escribiendo la misma partida.
- **`OrphanSessionRegistry`**: retiene las sesiones que no pudieron cerrarse (`KeptOpen`) hasta que el cierre termina, para no liberar la huella antes de tiempo.
- **`GameplaySettings*`** (`settings/`): ajustes globales y por juego (color, paleta, controles, mando) con persistencia atómica en `filesDir/gameplay-settings.json`.
- **`ArtworkStore`** (`library/artwork/`): portadas capturadas del último frame al cerrar, en caché con recorte de memoria (`onTrimMemory`).
- **`GamepadInput`**, `GamepadMonitor`, `GamepadRouter` (`input/`): mapeo por posición, hat y stick; acciones configurables (menú, velocidad).
- **`ControlsAccessibilityHelper`** (`input/`): `ExploreByTouchHelper` con un nodo virtual por control para TalkBack.

## Arquitectura nativa multiconsola (N8)
Una sola `libpocketgb.so` con los dos núcleos y una sesión nativa que no sabe de consolas salvo en un punto.

- **Compilación.** `CMakeLists.txt` compila `gba/src/*.c` como biblioteca de objetos (`pocketgba`, con sus rutas de cabeceras y los flags de `gba/Makefile`: `-std=c11 -Wall -Wextra -Werror -pedantic -fno-common`) y la enlaza en `libpocketgb.so` junto a `core/src`. `core/src/sha256.c` entra **una sola vez** (lo comparten la huella GB, la huella GBA y la validación de la BIOS). Como los objetos GBA entran todos (no un `.a`), un símbolo repetido entre núcleos es un error de enlace; además todos los del núcleo GBA llevan prefijo `gba_`/`arm_` (`make -C gba check-symbols`). Los cuatro ABI (arm64-v8a, armeabi-v7a, x86, x86_64) compilan con `-Werror`.
- **Consola de la sesión.** `native_session_create_console(console)` (`NATIVE_CONSOLE_GB = 0`, `NATIVE_CONSOLE_GBA = 1`) crea un único núcleo (`gb *core` o `gba *gba_core`) y fija el tamaño del framebuffer (160×144 o 240×160) y la máscara de botones (0xFF o 0x3FF). `native_session_create()` sigue siendo Game Boy. Todo el uso del núcleo pasa por funciones `core_*` de `native_session.c` con un `if` por consola (ejecutar frame, framebuffer, audio, partida, estados, RTC, botones). El resto no cambia y es común: hilo nativo, pacing por audio o reloj (59,7275 Hz en las dos consolas), ring SPSC + AAudio a 48 kHz, recreación de la `Surface`, escalado entero o «llenar» (que conserva la proporción de la consola: 10:9 o 3:2), aparcado e instantáneas de la partida.
- **Carga GBA.** `native_session_load_gba` (solo antes de arrancar). La BIOS del usuario (`gba_bios.bin`, opcional) solo se pasa al núcleo si mide 16 KiB y su SHA-256 es el de la oficial (`NATIVE_GBA_BIOS_SHA256`, el mismo que iOS); si no, se ignora y el núcleo emula la BIOS (HLE), y `RomInfo.biosLoaded` es `false`. Kotlin tiene la misma comprobación (`GbaBios.status`) para mostrar el estado sin abrir un juego. Si la carga falla, el núcleo GBA se recrea limpio. La hora que llega de Kotlin es UTC; el puente la pasa a hora local (`native_local_time`) porque el RTC del GBA cuenta hora local (como iOS); al reanudar, el RTC vuelve a la hora local actual.
- **Partida GBA.** Un único `.sav` con el formato de iOS (G7): el medio crudo (SRAM, Flash o EEPROM, compatible con mGBA/VBA) y, si el cartucho tiene RTC, sus 16 bytes al final. `sram_load` acepta el medio solo o el medio + RTC (con un medio de 0 bytes, solo el RTC); cualquier otro tamaño, o un bloque RTC fuera de ±200 años, se rechaza sin cambiar nada (si el medio falla tras aplicar el RTC, el RTC anterior se restaura). Una EEPROM sin ajuste mide 512 B hasta que el `.sav` o la primera DMA confirman 8 KiB: la instantánea se reserva para el mayor tamaño posible (`native_session_sram_capacity`), el hilo nativo actualiza `sram_size` tras cada frame y `nativeSessionSramCopy` entrega un `ByteArray` del tamaño exacto de ese instante. `sram_dirty_seq` crece cuando una escritura cambia algún byte del medio.
- **Estados GBA.** `gba_state_*` con las mismas barreras que en GB (sesión aparcada). Un estado de otra configuración del mismo ROM (medio, RTC o BIOS distintos) da `CoreError.StateConfig`, no «dañado».
- **Códigos de resultado.** Un único espacio para Kotlin: los `gb_result` (0..16), 17 = argumento inválido y los `gba_result` traducidos por `native_result_from_gba` al código GB equivalente (p. ej. `GBA_ERR_SAVE_SIZE` → `SramSize`) o a tres nuevos (18 cabecera GBA inválida, 19 tamaño de BIOS, 20 estado de otra configuración). Los negativos (`NS_BUSY`, `NS_TIMEOUT`…) no cambian.
- **JNI.** `nativeSessionCreateConsole`, `nativeSessionLoadGba`, `nativeSessionGbaRomInfo`, `nativeSessionScreenSize`, `nativeSessionSramCapacity`, `nativeConsoleScreenSize`, `nativeGbaBiosIsOfficial` y el núcleo suelto `nativeGba*` (para `CoreBridge(Console.GBA)`). Entrada hostil: ROM de GBA > 32 MiB y de GB > 8 MiB se rechazan antes de copiarla; la BIOS se copia solo si mide exactamente 16 KiB; `save_type` (0..6) y `rtc` (0..2) fuera de rango dan 17; cargar un ROM en la sesión de la otra consola da 17.
- **Kotlin (`emulator/`).** `Console` (GB/GBA: pantalla, máscara, límites de ROM, `fromFileName` por extensión como iOS), `ScreenSize`, `GbaButtonBits` (R = bit 8, L = bit 9, como iOS), `GbaOptions`/`GbaSaveType`/`GbaRtc`, `GbaBios`. `EmulatorSession(console)` con `load` (GB) y `loadGba`, `screen`, máscaras recortadas a la consola y `copySram` de tamaño exacto. `CoreBridge(console)` con `loadGbaRom`. `RomInfo` añade `console`, códigos, medio GBA, EEPROM y BIOS, y `gbaValidSaveSizes` (= iOS `validSaveSizes`). Las constantes `CoreBridge.SCREEN_*`/`FRAME_PIXELS` siguen siendo las de GB para los llamadores que aún no distinguen consola (vídeo, portadas, miniaturas); los conecta el lote «N8 Kotlin».

## Equivalencias iOS → Android
| Pieza | iOS | Android |
|---|---|---|
| UI | SwiftUI | Jetpack Compose (Material 3, color dinámico) |
| Imagen del juego | `MTKView` + textura 160×144 nearest | `SurfaceView` + `ANativeWindow`; escalado entero nearest ×k en C |
| Audio | `AVAudioSourceNode` | AAudio `LOW_LATENCY`, callback nativo que lee el ring SPSC; fallback a reloj si no hay audio |
| Hilo de emulación | `Thread` Swift | hilo nativo creado en `native_session.c` (sin GC en el bucle) |
| Controles | `UIView` multitouch | `View` personalizada con multitoque (`GameControlsView`, `TouchInputEngine`) |
| Háptica | `UIImpactFeedbackGenerator` | `performHapticFeedback` |
| Mandos | `GameController` | `GamepadInput` sobre `KeyEvent`/`MotionEvent` (botones, hat `AXIS_HAT_X/Y`, stick); mapeo por posición y reasignable |
| Biblioteca | Document picker + bookmark | `ACTION_OPEN_DOCUMENT_TREE` + permisos persistentes (lectura y escritura); detección de revocación |
| Escritura atómica | `replaceItemAt` | temporal + `fsync` + rename en `filesDir/saves`; espejo SAF junto a la ROM |
| Ciclo de vida | `scenePhase` | observador de lifecycle: pausa y vaciado de SRAM; sin reanudación automática; rotación sin pausa (`configChanges`) |

## Estado por hito
A1 a A5 cerrados y probados (A5 con prueba manual pendiente); A6 cerrado y auditado; A7 implementado y en auditoría; A8 en curso. Detalle y evidencia: [hitos/README.md](hitos/README.md) y `auditorias/A*-android-evidencia.md`. Las pruebas reales pendientes están en [PRUEBAS-JOEL.md](PRUEBAS-JOEL.md). La UI del cable virtual no forma parte de Android v1.

## Instalación
Debug por USB con `./gradlew :app:installDebug`, o Release firmado con un almacén de claves propio fuera del repo. Pasos en [07-instalacion-android.md](07-instalacion-android.md). El repositorio no define `signingConfigs`: el APK Release sale sin firmar.
