# A6 Android: evidencia (paridad visual con iOS)

Rama `a6-l5-catalogo` sobre `codex/android-port` @ `8362274` (L0 a L4) más L5. Commits desde `63fcd2f`: `git log 63fcd2f..HEAD --oneline` (28 commits). Emulador `Small_Phone_API_35`. Plan: [A6-plan.md](../diseno-android/A6-plan.md); revisión visual: [VERIFICACION.md](../diseno-android/VERIFICACION.md).

## Qué se implementó
- **L0** (`9ce3ce2`, `2d66edb`, `bbca15c`, `7a6bb67`): cierre de los riesgos residuales de A5 (A5V6-H1, A5V6-H3, A5V7-H1) en `GameSession`/`SaveCoordinator` (`forceStop`, reparación diferida); auditoría Opus A6-L0: APROBAR (residual A6L0-H1, documentado).
- **L1** (`8d1be55`, `1e16c55`, `7508666`, `f577692`): modelo `GameplaySettingsData`, almacén atómico `gameplay-settings.json` (K7), repositorio, `StorageUsage`, pantallas de Ajustes (Controles, Pantalla, Emulación, Audio, Almacenamiento, Licencias), Acerca de, Biblioteca y Partidas ampliadas.
- **L2** (`7ee4936`, `378c6b7`, `40e7e61`, `f33d4d2`): JNI de modelo CGB y paleta, volumen en caliente, modo Llenar (`Viewport`), aviso «Cabecera dañada» (K15). Sin cambios en `core/`.
- **L3** (`a010827`, `d7fe33f`, `3ad6c3c`, `6f78051`): portadas al cerrar (`ArtworkStore`, K9), placeholder determinista (K11), carril «Continuar jugando» (K10), menú contextual, Favoritos, detalle con Ajustes del juego por huella (K6, K8), «Nuevo» (K19), fecha del `.sav` (K20).
- **L4** (`cd48aea`, `0505e93`, `074213d`, `3eaba4d`, `1f2180b`, `8362274`): juego siempre oscuro y a pantalla completa (K4, K5), HUD (K13), controles configurables (opacidad, visibilidad, flechas, escala, háptica, exclusión de gestos; K12), editor de disposición, confirmaciones de estados (K14), `loadState(saveCurrentToAuto)`.
- **L5** (`2de3ece`, `78d8f5e`, `d72d9ad`, `6d4ef71`): catálogo de 61 ids y 106 capturas, manifiesto único `tools/android-screens.txt`, script con rotación, color dinámico con semilla, reintentos y `swipe`, `CatalogCoverageTest` (7 pruebas), `VERIFICACION.md` y esta evidencia. Los lotes anteriores no dejaron archivos en `debug/catalog/`; L5 creó `CatalogData`, `LibraryCatalog`, `SettingsCatalog` y `GameplayCatalog`, convirtió `DebugCatalog` en un registro (`catalogScreens`) y retiró `GameplayDebugScreen` (sustituida por `GameplayCatalogScreen`).

## Decisiones K1 a K20
Aplicadas según el plan; no se cambió ninguna. K1 (sin «modo silencio»; pie «El sonido sigue el volumen multimedia del teléfono»), K2 volumen nativo, K3 escalado, K4 y K5 tema y pantalla completa, K6 a K8 ajustes por huella, K9 a K11 portadas y carril, K12 y K13 controles y HUD, K14 estados, K15 cabecera, K16 y K17 almacenamiento y licencias, K18 a K20 biblioteca.

## Desviaciones
- El plan habla de 31 ids existentes, pero la lista enumera 30; el catálogo conserva los 30 y añade 31 (61 en total, con `library-grid-scrolled` extra).
- Ganchos mínimos en código de producción solo para el catálogo, con valor por defecto que no cambia la app: `LocalInitialMenuFor`, `LocalAutoFocusSearch`, `StatesDialogPreview` (parámetro `previewDialog`), `initialSelected` en `GameplayScreen` (commit `2de3ece`).
- Arreglos visuales de L5 en archivos de L1 y L4 (`StorageSettingsScreen`, `ControlsEditor`), commit aparte `78d8f5e`.
- Criterio 7: `git diff 8953d65 -- .../saves/` no está vacío: `SaveCoordinator.kt` tiene 16 inserciones y 1 borrado (L0, `forceStop`); L5 no toca `saves/`.
- Pruebas de `DebugCatalogTest` de L1 a L4 estaban desactualizadas (HUD sin «×1», descripción de controles sin «Menú», listas largas); se corrigieron en L5.
- `swipe=up` y `wait=` son argumentos solo del script; el manifiesto lleva la orientación y el script gira el emulador (la app solo la anota).
- Color dinámico: ajustado con `settings put secure theme_customization_overlay_packages` y semillas `FF2E7D32` y `FF6A1B9A`.

## Regresión final (HEAD de L5)
Comando, bajo el mutex `emu`: `./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug --continue`.

```
BUILD SUCCESSFUL in 5m 22s
152 actionable tasks: 25 executed, 127 up-to-date
```
- JVM (suma de `build/test-results/testDebugUnitTest/*.xml`): 312 tests, 0 fallos, 0 errores, 0 omitidos (A5: 233, A6-L0: 242).
- Instrumentados (`connected/debug/*.xml`): 279 casos, 0 fallos, 0 omitidos; `CatalogCoverageTest` 7.
- Lint: `0 errors, 35 warnings` (`app/build/reports/lint-results-debug.txt`); los avisos no se compararon uno a uno con A5.
- Dos corridas previas del mismo comando fallaron por pruebas de `DebugCatalogTest` (4 y luego 1) y por una dependencia de Gradle del manifiesto con lint; se corrigieron y la tercera corrida pasó. No hubo fallos de `ProcessKillTest` ni de foco de ventana.

```
$ aapt2 dump permissions app/build/outputs/apk/release/app-release-unsigned.apk
package: com.joelbermudez.pocketgb
permission: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```
Sin `INTERNET`. DEX de Release (`unzip 'classes*.dex' | strings | grep -c`): `DebugCatalog` 0, `GameplayTestActivity` 0, `GameplayCatalogScreens` 0, `DebugSyntheticRom` 0, `CatalogData` 0, `GameplayCatalogScreen` 0, `pocketgb/debug/` 0.

`git diff --stat main -- core/` vacío (no se tocó `core/`).

## Capturas
106 PNG (36 claro, 60 oscuro, 4 + 4 dinámicas) de 61 ids; lista y revisión por captura en [VERIFICACION.md](../diseno-android/VERIFICACION.md). Defectos corregidos: D1 (formato de tamaños), D2 (pie del editor), D3 y D4 (script). Pendientes: D5 (tooltip «Drag handle»), D6 («Salir» fuera de vista en horizontal), D7, D8.

## Lo que NO se ha verificado
- Teléfono real: tacto, háptica, audio, 60 fps, rendimiento.
- Carpeta SAF real y Rojo/Amarillo con cartuchos reales; el catálogo usa ROM y portadas sintéticas.
- Cutouts, rotación sin pausa (A7), mando físico (A7), TalkBack y contraste formal (A7).
- `tools/android-save-kill-test.sh` (50 iteraciones), `make -C core test` y `make -C core asan` no se ejecutaron en esta pasada.
- Comparación de los avisos de lint con A5 y auditoría independiente de L1 a L5.
- Si D5 y D8 son artefactos del emulador (teclado) o del código: sin comprobar.
