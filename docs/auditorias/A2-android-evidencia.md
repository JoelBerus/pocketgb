# Evidencia · Android A2 — Núcleo y vídeo

**Fecha:** 2026-10-01

**Rama:** `codex/android-port`

**Alcance:** núcleo C11 con NDK/JNI, validación tipada, sesión en hilo nativo y framebuffer RGBA8888 en `SurfaceView`. Sin audio, controles, lifecycle de background, biblioteca, partidas ni UI del cable virtual.

## Entorno y binarios

- NDK `27.3.13750724`, CMake `3.22.1` y JDK 17 de Android Studio.
- `libpocketgb.so` compila con C11 estricto (`-Wall -Wextra -Werror -pedantic`) para `arm64-v8a`, `armeabi-v7a`, `x86` y `x86_64`.
- Se verificó el ELF Release ARM64 como `ELF64 / AArch64`.
- La verificación instrumental completa se ejecutó en `Small_Phone_API_35`, Android 15/API 35. Las pruebas focales JNI y de sesión también pasaron en un Samsung físico API 33 durante el desarrollo.

## Núcleo host

Comandos frescos:

```bash
make -C core test
make -C core asan
```

Ambos terminaron correctamente: `65/65` casos requeridos y `1349` comprobaciones unitarias, sin fallos de AddressSanitizer ni UndefinedBehaviorSanitizer. Permanecen únicamente los tres `known-fail` documentados de M1.

## Android limpio

Comando:

```bash
cd android
./gradlew clean :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado: `BUILD SUCCESSFUL` en 1 min 18 s, 153 tareas ejecutadas.

- 11/11 tests JVM: manifiesto 2, apariencia 3, navegación 4 y viewport 2.
- 14/14 tests instrumentados: shell 2, catálogo 3, puente del core 4, sesión 3, carga nativa 1 y superficie 1.
- Debug, Release y lint correctos; las cuatro ABI se recompilaron desde limpio.

Las pruebas cubren el rechazo previo a JNI de ROM menor que `0x150` y mayor que 8 MiB, metadatos y framebuffer copiados, error tipado tras cierre, transiciones de sesión, contador detenido en pausa, cierre inmediato tras pausa y 25 recreaciones de superficie mientras el núcleo sigue ejecutándose. El desmontaje posterior al cierre es idempotente para tolerar el orden asíncrono de Compose/`SurfaceView`.

## Presentación y revisión visual

El hilo nativo conserva la única propiedad de `gb*`, ejecuta el pacing con `CLOCK_MONOTONIC` y presenta directamente a `ANativeWindow`. Cada frame limpia a negro, centra el mayor escalado entero posible y usa vecino más cercano; superficies menores emplean un ajuste acotado sin coordenadas negativas.

`tools/android-screenshots.sh` produjo 14/14 capturas reproducibles: las seis pantallas de A1 y `native-video`, en claro y oscuro. La pantalla nueva genera en memoria una ROM-only sintética de 32 KiB cuyo pequeño programa llena VRAM con franjas; no escribe ni empaqueta un `.gb`. Se inspeccionaron ambas variantes y el patrón 160×144 aparece centrado, sin distorsión, con barras negras y edge-to-edge correcto. Los PNG permanecen ignorados bajo `android/build/`.

## Privacidad y separación Release

`aapt2 dump permissions` sobre el APK Release solo mostró el permiso interno `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; no existen `INTERNET` ni `ACCESS_NETWORK_STATE`. `dexdump` no encontró `DebugCatalog`, `DebugIntent`, `DebugSyntheticRom`, `NativeVideoScreen`, `native-video` ni `A2 VIDEO` en Release.

No se versionaron ROMs, boot ROMs, partidas, estados, APK ni capturas. El diagnóstico y sus datos sintéticos están limitados al source set Debug.

## Resultado y límite de A2

A2 cumple la integración, propiedad del hilo y presentación de vídeo previstas. Audio, controles, háptica, background y velocidad comienzan en A3; persistencia de partidas continúa fuera de este hito. La auditoría independiente conjunta con Claude permanece reservada para A8 por decisión de Joel.

## Prueba manual

Joel probó A2 en Android el 2026-10-01 y aprobó continuar con A3.
