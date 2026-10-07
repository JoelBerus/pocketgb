# PocketGB para Android

Aplicación nativa en Kotlin y Jetpack Compose que ejecuta el mismo núcleo C11 de `core/` mediante NDK/JNI. Sin red, sin telemetría y sin ROMs en el repositorio.

Estado (hitos A1 a A8, ver [`docs/hitos/README.md`](../docs/hitos/README.md)):

- **A1** fundamentos: Material 3 con color dinámico, Navigation 3, edge-to-edge.
- **A2** núcleo y vídeo: `libpocketgb.so` (4 ABI), sesión nativa, `SurfaceView` con escalado entero.
- **A3** audio, entrada y ciclo de vida: AAudio con pacing por audio y fallback a reloj, controles multitáctiles, háptica, velocidad x2/x4.
- **A4** biblioteca: carpeta SAF (solo lectura de ROMs), búsqueda, filtros, favoritos, detalle.
- **A5** partidas y estados: escritura atómica con 5 backups, espejo SAF junto a la ROM, estados, recuperación tras cierre forzado.
- **A6** paridad visual con iOS: portadas capturadas al salir, ajustes de juego (color y paleta), Ajustes completos.
- **A7** robustez: mando físico, TalkBack, fuente al 200 %, contraste alto, rotación sin pausa, rail en pantallas anchas, área segura con recorte.
- **A8** cierre (en curso): documentación, icono adaptativo y versión 1.0.0 (`versionCode 2`).

Pendiente de verificación real por Joel: ver [`docs/PRUEBAS-JOEL.md`](../docs/PRUEBAS-JOEL.md). Especificación: [`docs/diseno-android/SPEC.md`](../docs/diseno-android/SPEC.md) y [`docs/05-android-spec.md`](../docs/05-android-spec.md). Instalación en el teléfono: [`docs/07-instalacion-android.md`](../docs/07-instalacion-android.md).

## Requisitos

- Android Studio con JDK 17.
- Android SDK Platform 37 y Build Tools 37.0.0, NDK r27.3 (`27.3.13750724`) y CMake 3.22.1.
- Un emulador o teléfono con Android 8.0 (API 26) o posterior.

En macOS:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"   # o `sdk.dir` en android/local.properties (ignorado por git)
```

## Compilar y probar

```bash
cd android
./gradlew :app:testDebugUnitTest                       # pruebas JVM
./gradlew :app:connectedDebugAndroidTest               # instrumentadas (emulador o teléfono)
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
./gradlew :app:installDebug                            # instala el APK Debug
```

El APK Debug queda en `app/build/outputs/apk/debug/app-debug.apk`. Release se genera **sin firmar** (`app/build/outputs/apk/release/app-release-unsigned.apk`); para firmarlo ver [`docs/07-instalacion-android.md`](../docs/07-instalacion-android.md). Recomendado en máquinas con poca memoria: `--no-daemon --max-workers=1`.

Detalle de las suites y sus límites: [`docs/06-testing.md`](../docs/06-testing.md).

### Emulador sin ventana y trucos de entorno

Las verificaciones se hicieron con el AVD `Small_Phone_API_35` (Android 15, 720x1280 a 320 dpi):

```bash
emulator -avd Small_Phone_API_35 -no-window -no-audio -gpu swiftshader_indirect
adb shell settings put global hide_error_dialogs 1              # sin diálogos «la app no responde» que tapen la UI
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0          # animaciones a 0: pruebas y capturas deterministas
```

Son ajustes del emulador, no del repositorio; restáurelos a `1` si el dispositivo es de uso diario.

## Catálogo visual (solo Debug)

`tools/android-screens.txt` es el manifiesto único (también lo lee `CatalogCoverageTest`): una línea por captura `<id> <portrait|landscape> <light|dark|dyn-green|dyn-violet> [clave=valor ...]`. A fecha de A7 tiene 75 ids y 125 capturas. El router y sus datos sintéticos existen solo en Debug; Release no los contiene.

```bash
tools/android-screenshots.sh [directorio]     # por defecto android/build/screenshots
SCREENS="library-grid launch" THEMES="dark" tools/android-screenshots.sh
RESUME=1 tools/android-screenshots.sh         # retoma una corrida interrumpida
```

Parámetros del script: `SCREENS` y `THEMES` limitan ids y temas; `RESUME=1` salta lo ya capturado; `MANIFEST` cambia el manifiesto. Argumentos por línea que son solo del script: `wait=<s>`, `swipe=up` (desplazar antes), `window=wide` (1280x800 a 240 dpi para rail y lista-detalle; se restaura con `wm size reset` y `wm density reset`) y `cutout=tall` (recorte simulado; se omite con aviso si el AVD no tiene el overlay). El resto de claves llegan a la app como extras (`query`, `fontScale` como Float, `contrast`, `reduceMotion`, `controller`, `showTouch`...). Las capturas no se versionan.

## Prueba de cierre forzado (kill-test)

```bash
tools/android-save-kill-test.sh 50
```

Cada iteración lanza el modo debug `save-stress` (ROM contador sintética, muchas escrituras atómicas con rotación de backups, pausas y estados al azar), mata el proceso tras 50 a 1500 ms (alternando `am force-stop` y `kill -9`) y lanza `save-verify`, que tras `recoverOrphans` comprueba: `.sav` presente y completo, sin `.tmp`, como máximo 5 backups, contador que no retrocede y sin temporales de estados. Los modos se invocan con `am start -n com.joelbermudez.pocketgb/.MainActivity --es debug save-stress|save-verify`; el resultado sale en logcat (etiqueta `PocketGBStress`, `SAVE-VERIFY OK|FAIL`). La prueba instrumentada equivalente es `ProcessKillTest`.

## Privacidad

No hay permisos de red, telemetría ni ROMs, partidas o estados en el repositorio. Verificación:

```bash
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump permissions app/build/outputs/apk/release/app-release-unsigned.apk
```

Debe mostrar solo el permiso interno `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` de AndroidX y ningún `INTERNET`.

## Estructura

`app/src/main/cpp/`: `CMakeLists.txt`, `pocketgb_jni.c` (puente JNI), `native_session.c` (sesión, hilo de emulación y pacing), `audio_output.c` (AAudio), `audio_ring.c` (ring SPSC).

`app/src/main/java/com/joelbermudez/pocketgb/`:

| Paquete | Contenido |
|---|---|
| `app/` | `PocketGBApp`, `AppScaffold` (barra o rail), navegación, observador de ciclo de vida |
| `emulator/` | `CoreBridge`, `NativeLibrary`, `EmulatorSession`, errores y opciones |
| `video/` | `GameSurface`, viewport con escalado entero |
| `audio/` | foco de audio y estado |
| `input/` | `TouchInputEngine`, controles, `GamepadInput` (mapeo por posición, hat, stick), `ControlsAccessibilityHelper` (TalkBack) |
| `game/` | `GameSession`, `GameplayViewModel`, `OrphanSessionRegistry`, `GameLauncher` |
| `saves/` | `SaveCoordinator`, `SaveStore`, `StateStore`, `SaveMirror`, `FingerprintOwnership`, `SavesIndex`, escritura atómica |
| `library/` | escáner SAF, preferencias, `ArtworkStore` (`library/artwork/`) |
| `settings/` | `GameplaySettings*`, apariencia, uso de almacenamiento |
| `ui/` | pantallas Compose: biblioteca, detalle, gameplay, ajustes, acerca de, tema y accesibilidad |

Fuentes `debug/` (solo Debug): catálogo, ROM sintética, `SaveStress`. Pruebas en `app/src/test` (JVM) y `app/src/androidTest` (instrumentadas).
