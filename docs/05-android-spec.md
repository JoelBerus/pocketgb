# 05 · Implementación Android nativa

> La especificación vigente y aprobada está en [diseno-android/SPEC.md](diseno-android/SPEC.md). Este documento conserva las equivalencias técnicas entre plataformas.

Objetivo: la **misma app** que [04](04-ios-spec.md), con UI 100 % nativa en Kotlin y el **mismo `core/`** compilado con el NDK. No se usan Flutter, React Native, KMP UI ni WebView.

## Requisitos
- Android Studio (última estable), NDK r27+ y CMake 3.22+ desde el SDK Manager.
- minSdk 26 (AAudio), targetSdk el último, Kotlin 2.x, Jetpack Compose (BOM).
- Paquete `com.joelbermudez.pocketgb`. Sin permiso `INTERNET` en el manifiesto: la auditoría lo verifica.

## Estructura
```
android/
  settings.gradle.kts, build.gradle.kts
  app/build.gradle.kts            # externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }
  app/src/main/cpp/CMakeLists.txt # add_library(pocketgb SHARED jni_bridge.c ${CORE_SRCS})
  app/src/main/cpp/jni_bridge.c   # JNI → pocketgb.h
  app/src/main/java/com/joelbermudez/pocketgb/
    MainActivity.kt, library/, emulator/, video/, audio/, input/, saves/, settings/
```
`CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22)
project(pocketgb C)
set(CMAKE_C_STANDARD 11)
file(GLOB CORE_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core/src/*.c)
add_library(pocketgb SHARED jni_bridge.c ${CORE_SRCS})
target_include_directories(pocketgb PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core/include)
target_compile_options(pocketgb PRIVATE -Wall -Wextra -O2)
target_link_libraries(pocketgb android aaudio log)
```

## Equivalencias iOS → Android
| Pieza | iOS | Android |
|---|---|---|
| UI | SwiftUI | Jetpack Compose |
| Imagen del juego | `MTKView` + textura 160×144 nearest | `SurfaceView` + `ANativeWindow_setBuffersGeometry(160·k, 144·k, RGBA_8888)` + `ANativeWindow_lock`. El escalado nearest ×k (entero) se hace en C, así el compositor no aplica filtro bilineal perceptible. Alternativa: `GLSurfaceView` con `GL_NEAREST`. |
| Audio | `AVAudioSourceNode` | AAudio en modo `PERFORMANCE_MODE_LOW_LATENCY` con data callback nativo que lee el mismo ring buffer SPSC (en C) |
| Hilo de emulación | `Thread` Swift | `pthread` nativo creado desde JNI (evita la GC en el bucle) |
| Controles translúcidos | `UIView` multitouch | `View` personalizada con `onTouchEvent` multipuntero (`ACTION_POINTER_DOWN/UP`, `getPointerId`), con la misma geometría y opacidades de [04](04-ios-spec.md) |
| Háptica | `UIImpactFeedbackGenerator` | `view.performHapticFeedback(KEYBOARD_TAP)` |
| Mandos | `GameController` | `onKeyDown`/`onGenericMotionEvent` (`KEYCODE_BUTTON_A`, `AXIS_HAT_X/Y`) |
| Biblioteca | Document picker de carpeta + security-scoped bookmark | `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission(READ|WRITE)` + `DocumentFile.fromTreeUri` (funciona con Google Drive, carpeta local o SD) |
| Escritura atómica | `replaceItemAt` | Local: `File.createTempFile` + `FileOutputStream.fd.sync()` + `renameTo` en `filesDir`. Espejo SAF: `contentResolver.openOutputStream(uri, "wt")` |
| Ciclo de vida | `scenePhase` | `ON_PAUSE` → flush síncrono de la SRAM; `ON_STOP` → detener el audio |

## Instalación (gratis, sin caducidad)
```bash
cd android && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
Firmar con un keystore propio (`keytool -genkeypair`), guardado **fuera** del repo. Hay que habilitar en el teléfono *Opciones de desarrollador › Depuración USB*.

## Orden de implementación

Se siguen A1–A8 de la especificación Android. A1 (fundamentos Compose) está implementado; A2 integra el núcleo mediante NDK/CMake y JNI. La UI del cable virtual permanece fuera de esta primera entrega.
