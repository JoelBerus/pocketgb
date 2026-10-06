# 02 · Arquitectura

## Capas
```
┌──────────── iOS (Swift) ────────────┐   ┌──────── Android (Kotlin) ────────────────┐
│ SwiftUI: Biblioteca, Ajustes, Menú  │   │ Compose: Biblioteca, Ajustes, Menú        │
│ GameView: MTKView + ControlsOverlay │   │ SurfaceView + GameControlsView            │
│ EmulatorSession (hilo emulación)    │   │ EmulatorSession + GameSession (nativo)    │
│ AudioOutput (AVAudioSourceNode)     │   │ AudioOutput (AAudio)                      │
│ SaveStore / Library (bookmarks)     │   │ SaveCoordinator / Library (SAF tree URI)  │
└──────────────┬──────────────────────┘   └──────────────┬────────────────────────────┘
               │ C ABI (pocketgb.h)                       │ JNI → C ABI
        ┌──────┴──────────────────────────────────────────┴──────┐
        │ core/ (C11): cpu · mmu · ppu · apu · timer · cart_mbc   │
        │ serial · joypad · state      — sin I/O, sin globals      │
        └─────────────────────────────────────────────────────────┘
```
Regla: **toda** la lógica de emulación está en `core/`. Los frontends solo convierten entre el mundo del SO (toques, archivos, audio, GPU) y la API C.

## API C (contrato)
La fuente de verdad es [`core/include/pocketgb.h`](../core/include/pocketgb.h). Resumen:

| Función | Hilo | Notas |
|---|---|---|
| `gb_create` / `gb_destroy` | cualquiera | Una instancia = un Game Boy. Sin estado global. |
| `gb_load_rom(gb, data, len, opts)` | emulación | Copia el ROM, valida la cabecera, elige el MBC y el modo (DMG/CGB) y aplica el estado post-boot. Devuelve `gb_result`. |
| `gb_sram_load(gb, data, len)` | emulación | Tras cargar el ROM. Rechaza un tamaño distinto al de la cabecera. |
| `gb_set_buttons(gb, mask)` | emulación | Máscara `GB_BTN_*`. El frontend publica la máscara de forma atómica y el hilo de emulación la lee antes de cada frame. |
| `gb_run_frame(gb)` | emulación | Ejecuta hasta el próximo VBlank (≈70 224 T-ciclos). |
| `gb_run_cycles(gb, n)` | emulación | Avance acotado (≥ n T-ciclos, termina en frontera de instrucción; devuelve los ejecutados). Para el lockstep del cable virtual (M9). |
| `gb_cycle_count(gb)` | emulación | T-ciclos acumulados; ordena los eventos entre dos instancias (M9). |
| `gb_serial_clock_external(gb, bit)` | emulación | Pulso de reloj externo para una instancia esclava (M9). |
| `gb_framebuffer(gb)` | emulación | `uint32_t[160*144]` RGBA8888 (byte R primero en memoria). Válido hasta el siguiente `gb_run_frame`; el frontend lo copia. |
| `gb_audio_read(gb, out, max_frames)` | emulación | Estéreo intercalado `int16`, a la frecuencia pedida en `opts.sample_rate`. |
| `gb_sram` / `gb_sram_dirty` / `gb_sram_clear_dirty` | emulación | Para la política de guardado ([04](04-ios-spec.md) §Saves). |
| `gb_state_size` / `gb_state_save` / `gb_state_load` | emulación | Formato versionado con CRC32 y huella del ROM. |

**Una instancia no es thread-safe.** Todo se llama desde un único hilo de emulación. El resto de hilos se comunica con colas o atómicos.

## Hilos y sincronía (iOS)
- **Hilo de emulación** (`Thread` dedicado, QoS `.userInteractive`). Bucle:
  1. Leer la máscara de botones (atómico).
  2. Si el ring buffer de audio tiene menos de `target` (≈ 2 frames = 1 600 muestras a 48 kHz), `gb_run_frame` + `gb_audio_read` → ring buffer.
  3. Copiar el framebuffer a uno de 3 buffers (triple buffering con índice atómico).
  4. Si no, esperar en un semáforo que el callback de audio señala al consumir.
- **Pacing por audio.** El reloj maestro es la tarjeta de sonido. Los 59,7275 fps del GB contra los 60/120 Hz de la pantalla: se dibuja el último frame completo y se aceptan frames repetidos u omitidos ocasionales. El audio no se corta.
- **Avance rápido ×N.** Se ejecutan N frames por ciclo y se descarta el audio sobrante (o se remuestrea), para que no se desincronice.
- **Callback de audio** (tiempo real): solo lee del ring buffer SPSC lock-free. Sin locks, sin alloc, sin Swift runtime pesado. Si hay underrun, rellena con la última muestra (sin clic) y cuenta el underrun para diagnóstico.
- **Render** (`MTKView`, `preferredFramesPerSecond = 60`): sube el último buffer listo a una `MTLTexture` de 160×144 y la dibuja con sampler `nearest`.

## Hilos de Android
| Hilo | Función |
|---|---|
| Principal | Compose, `SurfaceView`, entrada táctil y de mando, lifecycle |
| Nativo (`native_session.c`) | Bucle de emulación con pacing por audio (fallback a reloj); es el único que toca `gb_*` |
| AAudio (callback) | Solo lee del ring SPSC; sin locks ni alloc |
| `pocketgb-saves` | Un hilo por `SaveCoordinator`: todo el I/O de partidas y estados, serializado |
| `pocketgb-save-reaper` / `pocketgb-save-repair` | Cierre de sesiones con el hilo de guardado bloqueado y reparación de la partida tras un rollback fallido |
| `pocketgb-orphans` / `pocketgb-rescue` | Registro de sesiones huérfanas y recuperación de partidas |
| `pocketgb-artwork` | Captura y decodificación de portadas |

**Propiedad del handle nativo.** `EmulatorSession` posee el handle; un candado impide usarlo durante `close` y solo se destruye una vez. `GameSession` posee la sesión y su `SaveCoordinator`; `FingerprintOwnership` garantiza un único dueño por huella de ROM, y `OrphanSessionRegistry` mantiene vivas las sesiones que no pudieron cerrarse hasta que terminan, para no liberar la huella (ni el handle) con un hilo de guardado aún vivo.

## Datos persistentes
| Qué | Dónde (iOS; en Android `filesDir/saves`, `states` y `artwork`, más el espejo SAF) | Formato |
|---|---|---|
| ROMs | Carpeta de iCloud Drive elegida por Joel (bookmark) | `.gb` / `.gbc` sin tocar |
| Partida (SRAM) | `Application Support/Saves/<huella>.sav` + espejo `<rom>.sav` junto al ROM | Bytes crudos de la RAM del cartucho (compatible con otros emuladores) |
| Backups | `Application Support/Saves/backups/<huella>.<n>.sav` (n = 1…5) | Igual |
| Save states | `Application Support/States/<huella>.<slot>.state` | Formato propio versionado |
| Ajustes | `UserDefaults` | Opacidad de controles, escala, etc. |

`<huella>` = primeros 32 caracteres hex (128 bits) del SHA-256 de **todo** el ROM (`gb_rom_info.fingerprint`). Así, renombrar el archivo del ROM no pierde la partida, y dos ROMs distintos con la misma cabecera no comparten partida.

## Compilación
- `core/Makefile`: clang, `-std=c11 -Wall -Wextra -Werror -pedantic`. Targets: `lib`, `test`, `asan`, `fuzz`, `oracle`.
- iOS: los fuentes de `core/src` se añaden a un target de Xcode como **referencia a carpeta** (no se copian). El header se expone vía `module.modulemap`, en `core/include/module.modulemap`, con `module PocketGBCore { header "pocketgb.h" export * }`.
- Android: `android/app/src/main/cpp/CMakeLists.txt` añade `../../../../../core/src/*.c` ([05](05-android-spec.md)).
