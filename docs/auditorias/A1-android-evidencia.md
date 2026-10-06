# Evidencia · Android A1 — Fundamentos

**Fecha:** 2026-09-30

**Rama:** `codex/android-port`

**Alcance:** Gradle, Compose, Material 3/Material You, Navigation 3, edge-to-edge, apariencia y catálogo visual. Sin JNI, ROMs, partidas ni UI del cable virtual.

## Entorno verificado

- Android Gradle Plugin 9.4.1 y Gradle 9.8.0.
- Kotlin integrado 2.4.10; JVM 17 de Android Studio.
- `compileSdk`/`targetSdk` 37, Build Tools 37.0.0, `minSdk` 26.
- Emulador `Small_Phone_API_35`, Android 15/API 35, 720 × 1280.

## Núcleo sin regresiones

Comando:

```bash
make -C core test
```

Resultado relevante:

```text
65/68 PASS · requeridos: 65/65 PASS · HITO=M1
OK: todos los casos requeridos en PASS
```

Los tres `known-fail` son los ya documentados (`boot_div`, `boot_hwio`, `boot_sclk_align`). A1 no modificó `core/`.

## Android

Comando:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado:

- 9/9 tests JVM: manifiesto 2, apariencia 3, navegación 4.
- 4/4 tests instrumentados: shell 2, catálogo Debug 2.
- Build Debug y Release correctos.
- Lint Debug correcto. Durante la regresión detectó atributos de navegación declarados para API 26; se movieron a recursos `v27` y se repitió lint en verde.
- Flujo shell repetido con fuente del sistema al 200 %: 2/2 tests instrumentados correctos.

## Política sin red y separación Release

```text
package: com.joelbermudez.pocketgb
permission: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

`aapt2 dump permissions` no encontró `INTERNET` ni `ACCESS_NETWORK_STATE`. La inspección del APK Release no encontró `DebugCatalog`, `DebugIntent` ni `demoGames`.

## Revisión visual

`tools/android-screenshots.sh` generó 12/12 PNG reproducibles:

- `library-empty`, `library-grid`, `favorites-empty`;
- `settings-main`, `appearance`, `about`;
- cada una en claro y oscuro, con color dinámico desactivado para estabilidad.

Se inspeccionaron las 12 capturas. Verificado: barras del sistema con contraste correcto, edge-to-edge sin inset doble, títulos y contenidos sin recorte, cuatro tarjetas sintéticas completas, estados vacíos legibles y ausencia de ROMs o portadas reales. Los PNG permanecen ignorados bajo `android/build/screenshots/`.

## Resultado

A1 cumple sus criterios técnicos y queda listo para continuar con A2. La auditoría independiente conjunta con Claude se realizará al cierre de A8, según decisión de Joel.

## Prueba manual

Joel probó A1 en Android el 2026-10-01 y aprobó continuar con A2.
