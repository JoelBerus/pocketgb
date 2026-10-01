# PocketGB para Android

Aplicación nativa en Kotlin y Jetpack Compose. A1 establece el proyecto, Material 3/Material You, Navigation 3, edge-to-edge y un catálogo visual determinista. La integración del núcleo C11 comienza en A2.

La especificación aprobada está en [`docs/diseno-android/SPEC.md`](../docs/diseno-android/SPEC.md) y el plan de A1 en [`docs/superpowers/plans/2026-09-30-android-a1-foundations.md`](../docs/superpowers/plans/2026-09-30-android-a1-foundations.md).

## Requisitos

- Android Studio con JDK 17.
- Android SDK Platform 37 y Build Tools 37.0.0.
- Un emulador o teléfono con Android 8.0 (API 26) o posterior.
- Para A2: NDK r27.3 y CMake.

En macOS puede usarse el JDK incluido en Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

## Compilar y probar

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
```

El APK Debug se genera en `app/build/outputs/apk/debug/app-debug.apk`. Release no está firmado y se genera en `app/build/outputs/apk/release/app-release-unsigned.apk`.

Para instalar Debug:

```bash
./gradlew :app:installDebug
```

## Catálogo visual

El router y sus datos sintéticos existen solo en Debug. Con un dispositivo conectado:

```bash
cd ..
tools/android-screenshots.sh
```

Genera 12 PNG locales —seis pantallas en claro y oscuro— bajo `android/build/screenshots/`. Las capturas no se versionan.

## Privacidad

La aplicación no declara permisos de red, no incluye telemetría y no contiene ROMs, partidas ni estados. El APK Release puede verificarse con:

```bash
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump permissions \
  app/build/outputs/apk/release/app-release-unsigned.apk
```
