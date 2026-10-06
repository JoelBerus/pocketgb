# A7 Android: evidencia (robustez: mando, accesibilidad, rotación y adaptabilidad)

Rama `a7-l4-catalogo` sobre `codex/android-port` @ `cadf641` (A6 cerrado + A7 L1 a L3); L4 añade 3 commits (`b734e03`, `f00beec`, `546d220`). Emulador `Small_Phone_API_35` (Android 15, 720x1280 a 320 dpi). Plan: [A7-plan.md](../diseno-android/A7-plan.md); revisión visual: [VERIFICACION.md](../diseno-android/VERIFICACION.md) (sección A7).

## Qué hizo cada lote (según los commits)
- **L1 · Mando** (`9cb131c`, fusión `9c29ec1`): `GamepadInput` (mapeo por posición, hat y stick con anulación de opuestos, acciones `MENU` y `FAST_FORWARD`), `GamepadMonitor`/`GamepadRouter` (entrega desde `MainActivity` solo con sink registrado), `ControllerMappingScreen` (asignar y restablecer), `ControllerMappingData` con `sanitized()`, ajuste `showTouchControlsWithController`, `GameplayHost` con máscara 0 al perder foco o desconectar. Pruebas JVM `GamepadMappingTest` y `GameplaySettingsTest`; instrumentada `GamepadUiTest`.
- **L2 · Accesibilidad** (`c66547a`, `72cad07`): `ControlsAccessibilityHelper` (`ExploreByTouchHelper`, un nodo por control, límites >= 48 dp, «Abrir menú»), se retira la `contentDescription` única, contraste alto (controles sólidos, esquemas medio/alto fijos en `Color.kt`, sin color dinámico), `AccessibilitySignals` (`LocalReduceMotion`, `LocalHighContrast`, `LocalLargeFont`), `columnsFor(fontScale)`, tarjetas y carril con fuente grande, detalle con filas apiladas, HUD con `stateDescription`. Pruebas JVM (columnas, contraste, fundido, nodos, esquemas) e instrumentadas (`ControlsTalkBackTest`, `TouchTargetTest`, `HighContrastTest`, `LibraryUiTest`).
- **L3 · Rotación y adaptabilidad** (`363434a`, `cadf641`): `configChanges` en el manifiesto, `onTrimMemory`/`onLowMemory` → `GameplayViewModel.onTrimMemory`, `ArtworkStore.trimMemory`, `AppScaffold` con `NavigationSuiteScaffold` (barra o rail), `LibraryListDetail` tras `ENABLE_LIST_DETAIL`, área segura con `displayCutout`, superficie simétrica. Pruebas: `GameplayLifecycleTest`, `AppShellTest`, `GameSurfaceTest`, `ControlGeometryTest`, `ManifestPolicyTest`, `AppNavigationStateTest`, `ArtworkStoreTest`.
- **L4 · Catálogo y evidencia** (este lote): 14 ids nuevos y sus argumentos, script de capturas con `window=wide` y `cutout=tall`, `CatalogCoverageTest` y `DebugCatalogTest` al día, revisión visual, regresión, esta evidencia.

## Decisiones R1 a R16
Aplicadas en L1 a L3 sin cambiar ninguna: R1 a R6 (L1), R7 a R11 y R16 (L2), R12 a R15 (L3). Matices: R5 «pista de pausa»: no hay una pista en pantalla con los controles ocultos (solo el HUD de pausa y velocidad; la explicación está en el pie de Ajustes › Controles). R14: el lista-detalle existe pero sigue desactivado (`ENABLE_LIST_DETAIL = false`), decisión del plan; el rail sí está activo.

## Desviaciones
- **Dependencia `materialyoucolor`:** la tarea decía que L2 la había añadido; no es así. `Color.kt` contiene constantes `Color(0x...)` de los esquemas de contraste medio y alto (generadas con el algoritmo de Material Color Utilities, comentario en el archivo) y `build.gradle.kts` añadió `androidx.customview:customview:1.1.0` (L2) y, en L3, las cuatro dependencias adaptativas de AndroidX (`adaptive`, `adaptive-layout`, `adaptive-navigation` y `material3-adaptive-navigation-suite`, todas AndroidX). Origen y licencia: no hay artefacto externo que auditar.
- **L1 y L2 dejaron los ids del catálogo y los argumentos de `DebugIntent` a L4**; L4 los creó (`debug/catalog/A7Catalog.kt`) con tres ganchos mínimos en producción, de valor por defecto neutro: `touchSettingsFor` (la misma función que usa `GameplayHost` para ocultar los controles con mando), `initialAssigning` en `ControllerMappingContent` y `initialDetailId` en `LibraryListDetail`.
- **Argumentos solo del script** `window` y `cutout`; `fontScale` pasa a ser un Float en el intent (`--ef`). `DebugCatalog` ya no aplica la escala de fuente: la aplica `buildVariantContent` fuera de `PocketGBTheme` (antes `LocalLargeFont` no la veía).
- Varios ids son alias de pantallas de A6 con otros argumentos (`library-ax5` = `library-grid` con `fontScale=2.0`, etc.).
- `library-list-detail` muestra la estructura montada en el catálogo aunque `ENABLE_LIST_DETAIL` esté en `false`.
- **Criterio 9 del plan (rotaciones dentro del kill-test):** `tools/android-save-kill-test.sh` no se modificó; el kill-test se ejecutó como en A6. La rotación sin pausa tiene su prueba en `GameplayLifecycleTest` (L3), pero no hay una prueba que combine rotar y matar el proceso.
- Defecto visual propio: L4-D1, `library-ax5` cortaba «Continuar» (corregido en `f00beec`, que partía la palabra, y `546d220`).
- Prueba de `DebugCatalogTest` desactualizada (buscaba la descripción única que L2 sustituyó): sustituida por una comprobación de los 5 nodos virtuales y de `contentDescription == null`; añadidas tres pruebas de A7.

## Regresión (HEAD `546d220`)
Comando, bajo el mutex `emu`: `./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug --continue`.
```
BUILD SUCCESSFUL in 7m 32s
152 actionable tasks: 81 executed, 71 up-to-date
```
- JVM (suma de `build/test-results/testDebugUnitTest/*.xml`): **372 tests, 0 fallos, 0 errores, 0 omitidos** (`@Test` en `src/test`: 372; A6 cerró con 331).
- Instrumentados (`connected/debug/*.xml`): **333 casos, 0 fallos, 0 omitidos** (A6: 279). No hubo `ProcessKillTest` ni `RootViewWithoutFocusException`. Antes de esta pasada, una corrida parcial de `DebugCatalogTest` falló una prueba nueva mía (`useUnmergedTree` en `pad-key-A`); corregida en `f00beec`.
- Lint: `0 errors, 20 warnings` (`app/build/reports/lint-results-debug.txt`; A6: 35 avisos).
- `CatalogCoverageTest` (9 pruebas, incluye 14 ids A7 con sus argumentos y el renderizado de cada id del manifiesto): verde.

```
$ aapt2 dump permissions app/build/outputs/apk/release/app-release-unsigned.apk
package: com.joelbermudez.pocketgb
permission: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```
Sin `INTERNET`, igual que en A6. DEX de Release (`unzip classes*.dex | strings | grep -c`): `pocketgb/debug/` 0, `DebugCatalog` 0, `A7Catalog` 0, `DebugIntent` 0, `GameplayTestActivity` 0, `GameplayTestConfig` 0.

Dependencias (`:app:dependencies --configuration releaseRuntimeClasspath`, filtrando androidx, kotlin y org.jetbrains): solo aparecen `com.squareup.okio:okio(-jvm):3.9.1` (Square, Apache 2.0, transitiva de dependencias de AndroidX), `org.jspecify:jspecify:1.0.0` (Apache 2.0, anotaciones) y `com.google.guava:listenablefuture:1.0`; todas transitivas y sin red propia. No hay `materialyoucolor`.

`git diff --stat dd12f99 -- core/ android/app/src/main/cpp/ .../saves/ .../game/GameSession.kt`: vacío. (No se ejecutaron `make -C core test` ni `asan`: `core/` sin cambios.)

Kill-test, tras `installDebug`, bajo el mutex: `tools/android-save-kill-test.sh 50`:
```
Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
```
(última iteración: `bytes=8192 counter=3452 confirmed=3452 backups=5 stateTmpFound=0`.)

## Capturas
`tools/android-screenshots.sh` completo: `Capturas generadas: 125` (75 ids; A6 106 + A7 19) en el emulador. Cuatro avisos «la pantalla no giró a ROTATION_90» durante la corrida (el script continuó; las capturas horizontales salieron en horizontal, comprobado en las 4 de A7 revisadas). Revisión captura a captura de las 19 nuevas y recaptura de `library-grid`, `library-detail`, `gameplay-controls`, `pause-sheet` y `settings-main` sobre `f00beec`: ver [VERIFICACION.md](../diseno-android/VERIFICACION.md). Hallazgo: L4-D1 (corregido). Las otras 106 capturas de A6 no se volvieron a mirar una a una en esta pasada.

## Lo que NO se ha verificado
- **Mando real** (Bluetooth o USB): la lógica se prueba con eventos inyectados y `FakeGamepadConnection`; no hay prueba con un mando físico, ni de sticks reales, ni del `KEYCODE_BACK` de mandos concretos.
- **TalkBack real**: se prueban los nodos virtuales con `AccessibilityNodeInfo`, no el recorrido por gestos con el servicio activo ni el doble toque mantenido.
- **Contraste del sistema** (Ajustes › Accesibilidad › Contraste) y reducir movimiento reales: se forzaron con `LocalAccessibilityOverrides`; el contraste medio no tiene captura.
- **Cutouts reales**: el overlay `tall` del emulador solo se aprecia por el desplazamiento de los controles (`screencap` no pinta el recorte); sin prueba en punch hole ni en esquina, ni en rotación a 270°.
- **Tablet y plegable reales**: el rail y el lista-detalle se vieron a 1280x800 a 240 dpi, no en un dispositivo; el lista-detalle no está conectado a la navegación.
- **Teléfono real** (rotar 20 veces jugando sin pausa ni perder audio, memoria baja real, háptica, 60 fps).
- Combinación de rotación y cierre forzado del proceso (ver desviaciones); `make -C core test` y `asan` en esta pasada.
- Que el tooltip «Drag handle» y el resaltado gris del primer elemento (D5, D8 de A6) sean artefactos del emulador.
- Auditoría independiente de A7.

## Correcciones de la auditoría Opus

Auditoría: `A7-android-opus.md`; respuesta completa en `A7-android-respuesta.md`. Rama `a7-respuesta`.

- **A7-H1 (rojo sin la corrección):** instrumentados `com.joelbermudez.pocketgb.game`, 95 pruebas, `GamepadUiTest`:
  `unassignedPadKeyIsNotConsumedAndKeepsTouchControls` falla con «BUTTON_THUMBL no está asignado: no se consume» y
  `keyboardArrowWithOnlyDpadSourceDoesNotHideTouchControls` con «una flecha de teclado no se consume». Con la corrección: 95 pruebas, 0 fallos.
- **A7-H2 (rojo):** `ControlGeometryTest.gestureExclusionNeverLeavesTheSafeAreaVertically` falla (AssertionError, línea 233) con el `GestureExclusion` anterior; verde con la corrección.
- **A7-H4 (rojo):** `ControlsTalkBackTest.hiddenControlsAnnounceTheirHiddenStateToTalkBack` falla («DPAD oculto expected:<Oculto> but was:<null>», 22 pruebas, 1 fallo); verde con la corrección (22/22).
- **DS-H1…H3:** ver la respuesta.
- **Suite final:** JVM 375 pruebas, 0 fallos, 0 errores; `assembleDebug` y `lintDebug` BUILD SUCCESSFUL; instrumentados `game` 95/95 y `input` 22/22 (bajo el mutex del emulador, Small_Phone_API_35).
- Nota: en una pasada JVM completa `ProcessKillTest.killedWriterNeverLeavesAPartialOrMissingSave` falló una vez con la máquina cargada; solo, y en la pasada completa siguiente, pasa. No se ha tocado esa ruta.
