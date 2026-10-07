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
    CMakeLists.txt        # biblioteca `pocketgb` = fuentes de core/src + los cuatro de abajo
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
- **`ExactContinuation`** (`saves/`, A9): «Continuar» exacto como iOS D8.1. Al abrir con `LaunchMode.RESUME`, ya resuelta la partida y con la huella adquirida, el estado AUTO solo se aplica si no es anterior al `.sav` local y si su RAM del cartucho (sin el pie del RTC) es la de la partida; si no, se revierte el núcleo y se cierra sin escribir nada (`OpenError.ResumeFailed` → «Jugar desde el inicio»). El AUTO se guarda al salir y en `ON_STOP`, siempre después del vaciado de la SRAM. Un AUTO obsoleto se aparta (`auto.obsolete.state`), no se borra.
- **Alias** (`LibraryPreferencesData.aliasesByFingerprint`/`aliasesByPath`, A9): renombrar solo cambia el nombre visible (≤ 80 caracteres; vacío = cabecera), por huella si se conoce y por ruta si no, con migración al conocer la huella.
- **Identidad por huella** (N1a): `preferences.json` formato 2 (`formatVersion`). Favoritos, «jugado», ocultos y alias van por huella en cuanto se conoce (al abrir o ver el detalle); la ruta es clave provisional y se migra. Un archivo de A5–A9 (sin versión) se migra al leerlo. Caché por documento `documents` (ruta → nombre, tamaño, fecha, id de documento e identidad de cabecera 0x134–0x14F): tras un escaneo completo, una ruta desaparecida y una sola nueva con el mismo sello (cabecera incluida) se llevan su huella (sin confirmar hasta leer el ROM) y lo provisional (`DocumentCache.kt`, `MoveDetection`); con ambigüedad, columnas nulas, cabecera desconocida o un escaneo incompleto (`EXTRA_LOADING`/`EXTRA_ERROR`) no se traslada nada. Lo que desaparece deja una lápida (200 entradas, 30 días) para recuperarlo si vuelve. La cabecera se cachea por (id, tamaño, fecha): un ROM sin cambios no se vuelve a abrir. Un `preferences.json` de una versión futura o ilegible no se migra ni se sobrescribe (aviso en la biblioteca). La huella no se calcula en segundo plano (en Drive leer el ROM lo descarga). Duplicados (misma huella conocida en varias rutas): insignia «Duplicado» y «También en» en el detalle; las copias comparten partida local, estados, ajustes y portada, y cada una tiene su `.sav` junto al ROM. Si al abrir el espejo no es una escritura propia y difiere de la local, el perdedor (sea cual sea) se aparta además fuera de la rotación en `saves/backups/<huella>.mirror-<unix>-<rand8>.sav` (nunca se pisa ni se borra; Ajustes › Partidas › «Apartadas» lo restaura). En la misma ruta, otro tamaño u otra cabecera olvidan la huella (no la fecha ni el id, que Drive puede cambiar). Si el perdedor apartado es la local, se avisa al abrir.
- **Carpetas** (N1b): `LibraryScanner` recorre hasta `MAX_FOLDER_DEPTH = 5` niveles por niveles, con tope `MAX_SCAN_ENTRIES = 5000`; ignora lo que empieza por `.`, no escanea `PocketGB/` de la raíz ni las carpetas que empiezan por `_` (ND11). `RomEntry.folderPath`; `ScanStats` cuenta las consultas SAF (`adb logcat -s PocketGB/Library`).
- **`GameplaySettings*`** (`settings/`): ajustes globales y por juego (color, paleta, controles, mando) con persistencia atómica en `filesDir/gameplay-settings.json`.
- **`ArtworkStore`** (`library/artwork/`): portadas capturadas del último frame al cerrar, en caché con recorte de memoria (`onTrimMemory`).
- **`GamepadInput`**, `GamepadMonitor`, `GamepadRouter` (`input/`): mapeo por posición, hat y stick; acciones configurables (menú, velocidad).
- **`ControlsAccessibilityHelper`** (`input/`): `ExploreByTouchHelper` con un nodo virtual por control para TalkBack.

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
