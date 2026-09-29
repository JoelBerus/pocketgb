<!-- Borrador redactado por Codex (solo lectura) a partir de propuesta.html; revisado por Claude el 2026-09-29. APIs contrastadas con el SDK: ver API-iOS26.md. -->
# PocketGB — Plan de hitos de diseño para nube + CI

> Destino: `docs/hitos/D-README.md`  
> Ejecutor: Claude en Linux, sin Xcode  
> Verificación: GitHub Actions macOS 26 + capturas en `ci-shots/<rama>`

## 1. Relación con los hitos existentes

Este plan reemplaza la ejecución separada de los hitos visuales/frontend M6 y M7:

- **D2 + D3 sustituyen M6**: biblioteca por carpeta, bookmark, iCloud, SRAM, espejo y UI de backups.
- **D4 + D5 + D6 sustituyen M7**: controles, layout, mando, avance rápido y save states.
- `docs/hitos/M6-biblioteca-saves.md` y `M7-controles.md` pueden conservarse como antecedentes, pero al comenzar la implementación deben enlazar a este plan como fuente de verdad.
- Los criterios de seguridad de `docs/04-ios-spec.md` continúan siendo obligatorios.
- Ningún hito D se cierra sin auditoría.

Orden respecto de otros hitos:

1. Integrar primero la parte iOS de M5: audio, `RingBuffer`, `AudioOutput` y pacing.
2. Crear D1 sobre esa base para evitar conflictos en `PocketGBApp.swift`, `AppState.swift` y `EmulatorSession.swift`.
3. M8 puede continuar en paralelo porque trabaja principalmente en `core/`.
4. Si M8 modifica la ABI, adaptar `CoreBridge.swift` en un commit separado antes de mezclarlo con cambios visuales.
5. Los hitos D no reescriben audio ni lógica C salvo el puente mínimo requerido por save states/fast-forward.

## 2. Flujo obligatorio nube + CI

### 2.1 Ahorro de minutos macOS

- Trabajar un hito completo o un lote coherente antes de hacer push.
- No usar GitHub Actions como compilador interactivo para cada línea.
- Ejecutar en Linux todos los chequeos posibles antes del push.
- Hacer un solo push por lote.
- Esperar el CI y revisar `SUMMARY.md`, warnings y todas las capturas antes de continuar.
- Corregir en un segundo push solo si hay error de compilación, test, accesibilidad o defecto visible.
- No comenzar el siguiente hito con capturas pendientes de revisión.
- Evitar cambios triviales en `ios/` que disparen macOS CI por separado.

### 2.2 Comandos previos al push en Linux

```bash
git diff --check
rg -n 'GBA|\\.gba|Cheat|cheat|URLSession|NWConnection|NSAppTransportSecurity' ios docs/diseno docs/hitos
rg -n '\\.blur\\(|UIBlurEffect|Material\\.' ios/PocketGB
make -C core test HITO=M5
```

Los matches legítimos en documentación de exclusiones deben revisarse manualmente. En código de producto no se aceptan APIs de red ni vidrio simulado.

### 2.3 Ejecución del CI

El workflow `.github/workflows/ios.yml`:

1. Selecciona Xcode 26.
2. Descarga ROMs de prueba libres con hashes verificados.
3. Ejecuta el núcleo.
4. Ejecuta tests Swift y UI.
5. Recorre `ios/PocketGBUITests/screens.txt`.
6. Publica PNG, resumen y logs en `ci-shots/<rama>`.

Revisión desde la nube:

```bash
git fetch origin ci-shots/<rama>
git show origin/ci-shots/<rama>:SUMMARY.md
git ls-tree -r --name-only origin/ci-shots/<rama>
```

Para revisar una captura, extraerla a un directorio temporal o abrirla mediante la herramienta visual disponible. No mezclar la rama `ci-shots/*` con la rama de desarrollo.

### 2.4 Formato de `screens.txt`

```text
<id> <portrait|landscape> <light|dark> [argumentos DEBUG...]
```

El archivo final es el manifiesto de cobertura visual. Los IDs provienen de `docs/diseno/SPEC.md`.

### 2.5 Argumentos DEBUG

Se conservan:

- `-rom <path>`
- `-paused`
- `-uiStyle light|dark`
- `-memoryWarningAfter <seconds>`
- `-failAsyncSaves`

Se añaden:

| Argumento | Propósito |
|---|---|
| `-screen <id>` | Navegar directamente a un estado del catálogo |
| `-demoLibrary <empty|standard|cloud|errors>` | Instalar modelo de biblioteca determinista en memoria |
| `-demoFolderState <none|available|stale|denied>` | Simular bookmark sin abrir Files |
| `-demoCloudState <pending|downloading|current>` | Simular estado de iCloud |
| `-demoSaveState <none|slots|corrupt>` | Sembrar save states temporales |
| `-demoSaveError wrong-size` | Presentar `.sav` inválido sin tocar datos reales |
| `-controlOpacity <30|50|70|100>` | Fijar variante visual |
| `-controlsVisibility <always|touch|hidden>` | Fijar visibilidad |
| `-reduceTransparency` | Forzar el fallback visual interno para capturas |
| `-reduceMotion` | Forzar la política interna de motion |
| `-contentSizeCategory accessibility5` | Fijar Dynamic Type para catálogo |
| `-demoController connected` | Simular el estado visual de mando conectado |

Los argumentos:

- Se declaran bajo `#if DEBUG`.
- No escriben `UserDefaults` productivos.
- Usan directorios temporales.
- No necesitan iCloud real.
- No eluden validaciones del núcleo.
- No incluyen ROMs comerciales.
- No intentan falsificar `UIAccessibility.isVoiceOverRunning`; VoiceOver se valida manualmente.

### 2.6 Qué puede comprobar el CI

- Compilación Swift 6 con Xcode 26.
- Tests unitarios y UI.
- Ausencia de crashes en rutas del catálogo.
- Jerarquía visible, textos y clipping.
- Light/dark.
- Portrait/landscape.
- Estados deterministas de biblioteca, nube, saves y controles.
- Existencia y dimensiones de cada PNG.
- Identificadores y botones accesibles mediante XCTest.
- Que los argumentos DEBUG no estén disponibles en Release mediante tests/configuración.
- Reglas estáticas de red y archivos prohibidos.

### 2.7 Qué no puede certificar el CI

Requiere iPhone de Joel:

- Refracción y legibilidad real de Liquid Glass sobre movimiento.
- Háptica.
- Multitouch físico A+B.
- Deslizamiento B→A y rodar el pulgar por el D-pad.
- Dynamic Island en ambos lados.
- Home Indicator y gestos diferidos.
- Mando Bluetooth real.
- Descarga real desde iCloud Drive.
- Persistencia del security-scoped bookmark tras reinicio.
- Reinstalación desde Xcode conservando el `.sav` espejo.
- Audio, modo silencio, interrupciones y underruns.
- Rendimiento térmico y latencia.
- Apariencia del launch screen antes de que arranque SwiftUI.
- VoiceOver real y Switch Control.

## 3. D1 · Fundamentos visuales y catálogo determinista

### Objetivo

Crear el design system, la shell de tres tabs y el router DEBUG sin cambiar todavía la biblioteca real, audio, núcleo o guardado.

### Archivos

```text
ios/PocketGB/App/PocketGBApp.swift
ios/PocketGB/App/AppState.swift
ios/PocketGB/App/DebugArguments.swift
ios/PocketGB/App/DebugScreenRouter.swift
ios/PocketGB/DesignSystem/*
ios/PocketGB/Settings/SettingsView.swift
ios/PocketGB/Settings/AppearanceSettingsView.swift
ios/PocketGB/Settings/AboutView.swift
ios/PocketGB/Resources/Assets.xcassets/*
ios/PocketGBUITests/ScreenshotTests.swift
ios/PocketGBUITests/screens.txt
```

### Capturas añadidas

```text
launch              portrait light -screen launch -uiStyle light
launch              portrait dark  -screen launch -uiStyle dark
library-no-folder   portrait light -screen library-no-folder -demoFolderState none -uiStyle light
library-no-folder   portrait dark  -screen library-no-folder -demoFolderState none -uiStyle dark
library-empty       portrait light -screen library-empty -demoLibrary empty -uiStyle light
library-empty       portrait dark  -screen library-empty -demoLibrary empty -uiStyle dark
settings-main       portrait light -screen settings-main -uiStyle light
settings-main       portrait dark  -screen settings-main -uiStyle dark
settings-appearance portrait light -screen settings-appearance -uiStyle light
settings-appearance portrait dark  -screen settings-appearance -uiStyle dark
settings-about      portrait light -screen settings-about -uiStyle light
settings-about      portrait dark  -screen settings-about -uiStyle dark
```

El launch se captura mediante preview DEBUG. El launch screen real sigue definido por los recursos del target.

### Datos de prueba

- Biblioteca vacía en memoria.
- Versión y licencia estáticas derivadas del bundle.
- Ningún ROM requerido.

### Criterios CI

- Compila con Swift 6/iOS 26.
- `TabView` contiene `Tab` para Biblioteca, Favoritos y Ajustes.
- Usa `.tabBarMinimizeBehavior(.onScrollDown)`.
- Settings usa `Form`.
- Assets light/dark existen con los nombres de SPEC.
- No hay `.blur`, `UIBlurEffect` ni materiales que imiten vidrio.
- No aparecen GBA, L/R, cheats ni artwork online.
- Las doce capturas existen y no presentan clipping.
- `ScreenshotTests` falla si un ID es desconocido.
- El router DEBUG queda excluido de Release.

### Validación en iPhone

- Tab bar Liquid Glass real.
- Tamaño de controles nativos.
- Launch screen real.
- Contraste en brillo alto/bajo.
- Apariencia Clear/Tinted del app icon cuando se implemente.

## 4. D2 · Biblioteca persistente y saves robustos

### Objetivo

Implementar el backend de M6: selección de carpeta, bookmark, escaneo coordinado, estados iCloud, SRAM local autoritativa, espejo junto al ROM y restauración de backups.

### Archivos

```text
ios/PocketGB/Library/LibraryStore.swift
ios/PocketGB/Library/LibraryScanner.swift
ios/PocketGB/Library/RomEntry.swift
ios/PocketGB/Library/FolderPicker.swift
ios/PocketGB/Saves/AtomicFile.swift
ios/PocketGB/Saves/SaveStore.swift
ios/PocketGB/Saves/SaveMirror.swift
ios/PocketGB/Saves/SavesSettingsView.swift
ios/PocketGB/App/AppState.swift
ios/PocketGBTests/AtomicFileTests.swift
ios/PocketGBTests/LibraryScannerTests.swift
ios/PocketGBTests/SaveMirrorTests.swift
ios/PocketGBUITests/screens.txt
```

No tocar `AudioOutput`, `RingBuffer` ni el pacing de `EmulatorSession`, salvo inyectar la URL coordinada y el store mediante una interfaz mínima.

### Capturas añadidas

```text
library-folder-unavailable portrait light -screen library-folder-unavailable -demoFolderState stale -uiStyle light
library-folder-unavailable portrait dark  -screen library-folder-unavailable -demoFolderState stale -uiStyle dark
library-cloud-pending       portrait light -screen library-cloud-pending -demoLibrary cloud -demoCloudState pending -uiStyle light
library-cloud-pending       portrait dark  -screen library-cloud-pending -demoLibrary cloud -demoCloudState pending -uiStyle dark
library-cloud-downloading   portrait light -screen library-cloud-downloading -demoLibrary cloud -demoCloudState downloading -uiStyle light
library-cloud-downloading   portrait dark  -screen library-cloud-downloading -demoLibrary cloud -demoCloudState downloading -uiStyle dark
library-scan-progress       portrait light -screen library-scan-progress -demoLibrary standard -uiStyle light
library-scan-progress       portrait dark  -screen library-scan-progress -demoLibrary standard -uiStyle dark
library-scan-summary        portrait light -screen library-scan-summary -demoLibrary standard -uiStyle light
library-scan-summary        portrait dark  -screen library-scan-summary -demoLibrary standard -uiStyle dark
library-rom-error           portrait light -screen library-rom-error -demoLibrary errors -uiStyle light
library-rom-error           portrait dark  -screen library-rom-error -demoLibrary errors -uiStyle dark
save-data-error             portrait light -screen save-data-error -demoLibrary standard -demoSaveError wrong-size -uiStyle light
save-data-error             portrait dark  -screen save-data-error -demoLibrary standard -demoSaveError wrong-size -uiStyle dark
```

### Datos de prueba

- Directorios temporales con ROMs libres de `core/tests/roms`.
- Archivo inválido generado durante el test, no versionado.
- Bookmark simulado mediante una abstracción inyectable.
- Estados de nube simulados; no se intenta usar iCloud real en CI.
- `.sav` temporal del tamaño correcto e incorrecto.

### Criterios CI

- Solo `.gb` y `.gbc`; profundidad máxima uno.
- Rechazo de archivos >8 MiB.
- Coordinación de lectura mediante `NSFileCoordinator`.
- La UI no modifica el ROM.
- Tests obligatorios de `AtomicFile`:

  - Primer guardado.
  - Primer guardado interrumpido y recuperado.
  - Fallos después de los pasos 2–4 conservan el `.sav` anterior.
  - Reemplazo completo deja el anterior en `.1`.
  - Contenido idéntico no rota.
  - Siete guardados mantienen únicamente `.1`–`.5`.

- `.sav` incorrecto no se sobrescribe.
- Conflicto local/espejo selecciona el más reciente y respalda el otro.
- Fallo del espejo conserva el local y programa reintento.
- Restaurar un backup respalda primero la partida actual.
- Todas las capturas muestran acciones recuperables y no destructivas.
- Los tests temporales no escriben dentro del repo.

### Validación en iPhone

- Elegir una carpeta real de iCloud Drive.
- Cerrar y abrir sin volver a elegirla.
- Regresar de background y reescanear.
- Descargar un ROM que esté solo en iCloud.
- Guardar y forzar cierre en menos de dos segundos.
- Confirmar el `.sav` junto al ROM desde Finder.
- Reinstalar desde Xcode y recuperar el espejo.
- Revocar acceso a la carpeta y confirmar recuperación visible.

## 5. D3 · Biblioteca visual, búsqueda y detalle

### Objetivo

Completar la experiencia visual de biblioteca sobre el backend D2, incluida portada local, búsqueda, favoritos, detalle y exclusión de juegos.

### Archivos

```text
ios/PocketGB/Library/LibraryRootView.swift
ios/PocketGB/Library/LibraryView.swift
ios/PocketGB/Library/GameCard.swift
ios/PocketGB/Library/GameListItem.swift
ios/PocketGB/Library/GameDetailsView.swift
ios/PocketGB/Library/GameArtworkStore.swift
ios/PocketGB/Library/GamePlaceholderView.swift
ios/PocketGB/Favorites/FavoritesView.swift
ios/PocketGB/Settings/LibrarySettingsView.swift
ios/PocketGBUITests/screens.txt
```

### Capturas añadidas

```text
library-grid       portrait light -screen library-grid -demoLibrary standard -uiStyle light
library-grid       portrait dark  -screen library-grid -demoLibrary standard -uiStyle dark
library-list       portrait light -screen library-list -demoLibrary standard -uiStyle light
library-list       portrait dark  -screen library-list -demoLibrary standard -uiStyle dark
library-continue   portrait light -screen library-continue -demoLibrary standard -uiStyle light
library-continue   portrait dark  -screen library-continue -demoLibrary standard -uiStyle dark
favorites          portrait light -screen favorites -demoLibrary standard -uiStyle light
favorites          portrait dark  -screen favorites -demoLibrary standard -uiStyle dark
search-active      portrait light -screen search-active -demoLibrary standard -uiStyle light
search-active      portrait dark  -screen search-active -demoLibrary standard -uiStyle dark
search-results     portrait light -screen search-results -demoLibrary standard -uiStyle light
search-results     portrait dark  -screen search-results -demoLibrary standard -uiStyle dark
search-no-results  portrait light -screen search-no-results -demoLibrary standard -uiStyle light
search-no-results  portrait dark  -screen search-no-results -demoLibrary standard -uiStyle dark
game-details       portrait light -screen game-details -demoLibrary standard -uiStyle light
game-details       portrait dark  -screen game-details -demoLibrary standard -uiStyle dark
game-context-menu  portrait light -screen game-context-menu -demoLibrary standard -uiStyle light
game-context-menu  portrait dark  -screen game-context-menu -demoLibrary standard -uiStyle dark
remove-game-confirm portrait light -screen remove-game-confirm -demoLibrary standard -uiStyle light
remove-game-confirm portrait dark  -screen remove-game-confirm -demoLibrary standard -uiStyle dark
```

### Datos de prueba

`-demoLibrary standard` crea modelos en memoria con:

- Dos juegos GB.
- Dos juegos GBC.
- Un juego con captura.
- Un juego con placeholder.
- Un favorito.
- Fechas fijas.
- Un último juego activo.
- Un juego ocultable.
- Títulos largos para truncado.

Las capturas de demostración deben proceder de ROMs libres del fixture o de arte abstracto generado; nunca de juegos comerciales.

### Criterios CI

- Grid/list comparten el mismo modelo.
- Filtros disponibles: Todos, GB, GBC y Favoritos.
- No existe filtro GBA.
- `searchable` filtra en vivo.
- `.searchToolbarBehavior(.minimize)` se usa donde corresponda.
- Portada → detalle usa zoom con `matchedTransitionSource`.
- Reduce Motion evita el zoom mediante la política central.
- Las cards no usan vidrio.
- Continue muestra una captura real/local.
- El placeholder es determinista por fingerprint.
- No hay llamadas de red.
- Ocultar un juego persiste el fingerprint excluido.
- “Ocultar” no borra ROM, SRAM ni backups.
- Context menu y confirmación describen qué se conserva.
- Los títulos largos no solapan badges o acciones.

### Validación en iPhone

- Calidad del zoom.
- Context menu con press real.
- Scroll y minimización de tab bar.
- Búsqueda con teclado.
- Comportamiento al cambiar orientación fuera de gameplay.
- Portada creada al terminar una sesión real.

## 6. D4 · Gameplay y controles Liquid Glass

### Objetivo

Aplicar el diseño portrait/landscape, resolver la legibilidad sobre frames claros y añadir editor de layout. Sustituye la parte visual y multitáctil de M7.

### Archivos

```text
ios/PocketGB/Gameplay/GameScreen.swift
ios/PocketGB/Gameplay/GameplayHUD.swift
ios/PocketGB/Input/ControlsOverlayView.swift
ios/PocketGB/Input/ControlsLayout.swift
ios/PocketGB/Input/ControlsSettings.swift
ios/PocketGB/Input/ControlsEditorView.swift
ios/PocketGB/Input/Haptics.swift
ios/PocketGB/Settings/ControlsSettingsView.swift
ios/PocketGB/Settings/DisplaySettingsView.swift
ios/PocketGBTests/ControlsLayoutTests.swift
ios/PocketGBUITests/screens.txt
```

No modificar audio ni la temporización de emulación.

### Capturas añadidas

```text
gameplay-portrait            portrait  dark -screen gameplay-portrait -rom $FIXTURES/dmg-acid2.gb -uiStyle dark
gameplay-landscape           landscape dark -screen gameplay-landscape -rom $FIXTURES/dmg-acid2.gb -uiStyle dark
gameplay-landscape-clear     landscape dark -screen gameplay-landscape-clear -rom $FIXTURES/dmg-acid2.gb -controlOpacity 30 -uiStyle dark
gameplay-landscape-hidden    landscape dark -screen gameplay-landscape-hidden -rom $FIXTURES/dmg-acid2.gb -controlsVisibility hidden -uiStyle dark
customize-controls-portrait  portrait  dark -screen customize-controls-portrait -rom $FIXTURES/dmg-acid2.gb -uiStyle dark
customize-controls-landscape landscape dark -screen customize-controls-landscape -rom $FIXTURES/dmg-acid2.gb -uiStyle dark
settings-controls            portrait  light -screen settings-controls -uiStyle light
settings-controls            portrait  dark  -screen settings-controls -uiStyle dark
settings-display             portrait  light -screen settings-display -rom $FIXTURES/dmg-acid2.gb -uiStyle light
settings-display             portrait  dark  -screen settings-display -rom $FIXTURES/dmg-acid2.gb -uiStyle dark
gameplay-reduce-transparency landscape dark -screen gameplay-reduce-transparency -rom $FIXTURES/dmg-acid2.gb -controlOpacity 30 -reduceTransparency -uiStyle dark
```

### Datos de prueba

- `dmg-acid2.gb` libre.
- Frame claro detenido en un punto determinista para validar contraste.
- Layout temporal independiente para portrait/landscape.
- Preferencias DEBUG no persistentes.

### Criterios CI

- `ControlsOverlayView` sigue siendo una única vista multitáctil.
- Visuales UIKit usan `UIGlassEffect`, no `UIBlurEffect`.
- Clear glass tiene scrim localizado.
- Al 30 %, labels y siluetas siguen visibles sobre blanco.
- El área táctil no depende del alpha.
- Reduce Transparency usa superficie sólida ≥90 %.
- No hay L/R.
- Tests de los ocho sectores y ángulos límite.
- Tests de zona muerta.
- Tests que impiden direcciones opuestas.
- Test de zona A+B.
- Posiciones relativas se claman al safe area.
- Layout portrait y landscape se persisten por separado.
- Rotación publica máscara cero durante el cambio.
- Viewport conserva 10:9.
- Screenshots no muestran controles bajo Island/Home Indicator.

### Validación en iPhone

- A+B con dos dedos.
- B→A sin levantar.
- Ocho direcciones y diagonales.
- D-pad capturado fuera de su radio.
- Háptica sin repetición excesiva.
- Scrim sobre escenas blancas y flashes.
- Dynamic Island a ambos lados.
- Centro de Control/Notificaciones diferidos.
- Home Indicator oculto.
- Editor con dedos y persistencia tras reinicio.

## 7. D5 · HUD, pausa y save states

### Objetivo

Completar las superficies modales de gameplay y la persistencia de estados. Sustituye la parte save-state de M7.

### Archivos

```text
ios/PocketGB/Gameplay/GameplayHUD.swift
ios/PocketGB/Gameplay/PauseView.swift
ios/PocketGB/Saves/StateStore.swift
ios/PocketGB/Saves/SaveStatesView.swift
ios/PocketGB/Saves/SaveStateCard.swift
ios/PocketGB/Emulator/CoreBridge.swift
ios/PocketGB/Emulator/EmulatorSession.swift
ios/PocketGBTests/StateStoreTests.swift
ios/PocketGBTests/StateSRAMTests.swift
ios/PocketGBUITests/screens.txt
```

Los cambios en `EmulatorSession` se limitan a comandos serializados de pause/save/load y al flush obligatorio de SRAM.

### Capturas añadidas

```text
gameplay-portrait-hud portrait dark -screen gameplay-portrait-hud -rom $FIXTURES/dmg-acid2.gb -uiStyle dark
gameplay-pause        portrait dark -screen gameplay-pause -rom $FIXTURES/dmg-acid2.gb -paused -uiStyle dark
save-states           portrait dark -screen save-states -rom $FIXTURES/dmg-acid2.gb -demoSaveState slots -uiStyle dark
load-state-confirm    portrait dark -screen load-state-confirm -rom $FIXTURES/dmg-acid2.gb -demoSaveState slots -uiStyle dark
replace-state-confirm portrait dark -screen replace-state-confirm -rom $FIXTURES/dmg-acid2.gb -demoSaveState slots -uiStyle dark
```

### Datos de prueba

- ROM libre `dmg-acid2.gb`.
- Cuatro estados temporales con timestamps fijos.
- Slot auto.
- Capturas derivadas del fixture.
- Estado corrupto cubierto por unit test aunque no necesite captura permanente.

### Criterios CI

- HUD usa un único `GlassEffectContainer`.
- Botón menú y HUD usan `glassEffectID`.
- Reduce Motion cambia morph por fade.
- HUD se oculta a los tres segundos.
- Pause sheet usa `presentationDetents`.
- Cuatro slots manuales y uno auto.
- Estado escrito con `AtomicFile`.
- Estado corrupto se rechaza sin afectar SRAM.
- Estado de ROM distinto se rechaza.
- Tras `gb_state_load`, la SRAM del estado se persiste por la ruta normal.
- Antes de sustituir SRAM se crea backup.
- Load confirmation ofrece “Guardar actual y cargar”.
- Exit hace flush síncrono antes de volver a biblioteca.
- Ninguna acción muestra cheats.

### Validación en iPhone

- Animación/morph real del HUD.
- Detents y gesto de sheet.
- Guardar/cargar durante una sesión real.
- Salir inmediatamente después de cargar y recuperar SRAM.
- Interrupción/background mientras una confirmación está abierta.
- Hápticas success/warning/error.

## 8. D6 · Mando, avance rápido y ajustes completos

### Objetivo

Terminar M7 y las pantallas funcionales restantes sin reestructurar el audio de M5.

### Archivos

```text
ios/PocketGB/Input/GamepadInput.swift
ios/PocketGB/Emulator/EmulatorSession.swift
ios/PocketGB/Settings/AudioSettingsView.swift
ios/PocketGB/Settings/LibrarySettingsView.swift
ios/PocketGB/Settings/SavesSettingsView.swift
ios/PocketGB/Settings/StorageSettingsView.swift
ios/PocketGB/Settings/Settings.swift
ios/PocketGBTests/GamepadMappingTests.swift
ios/PocketGBTests/SettingsTests.swift
ios/PocketGBUITests/screens.txt
```

Fast-forward consume la interfaz de audio creada en M5; no crea un segundo motor ni un segundo reloj.

### Capturas añadidas

```text
settings-audio   portrait light -screen settings-audio -uiStyle light
settings-audio   portrait dark  -screen settings-audio -uiStyle dark
settings-library portrait light -screen settings-library -demoLibrary standard -uiStyle light
settings-library portrait dark  -screen settings-library -demoLibrary standard -uiStyle dark
settings-saves   portrait light -screen settings-saves -demoLibrary standard -demoSaveState slots -uiStyle light
settings-saves   portrait dark  -screen settings-saves -demoLibrary standard -demoSaveState slots -uiStyle dark
settings-storage portrait light -screen settings-storage -demoLibrary standard -demoSaveState slots -uiStyle light
settings-storage portrait dark  -screen settings-storage -demoLibrary standard -demoSaveState slots -uiStyle dark
```

### Datos de prueba

- Mando simulado solo para estado visual y tests de mapping.
- Estadísticas de almacenamiento calculadas en un directorio temporal.
- Ajustes globales y overrides por juego.
- Fixtures de ROM libres.

### Criterios CI

- Mapping por posición: botón derecho = A, inferior = B.
- D-pad y stick izquierdo producen la misma máscara lógica.
- Menu → Start; Options → Select.
- Máscara del mando se combina por OR con la táctil.
- Estado “mando conectado” oculta únicamente la superposición visual.
- Fast-forward ×2/×4 no altera archivos ni save states.
- Al salir de fast-forward se limpia audio sobrante según la interfaz M5.
- Audio Settings refleja `.ambient` y opción `.playback`.
- No existe audio en background.
- Storage no ofrece borrar ROMs.
- Biblioteca no ofrece artwork automático por red.
- Settings por juego muestra “Global” o “Personalizado” como texto.
- Tests no requieren un mando físico.

### Validación en iPhone

- Mando Bluetooth real.
- Reconexión y desconexión durante gameplay.
- Mapeo y ocultación de overlay.
- Fast-forward ×2/×4 sin audio desincronizado.
- Interrupción por Siri/llamada.
- Modo silencio.
- Diez minutos sin underruns.
- Uso de almacenamiento con datos reales.

## 9. D7 · Accesibilidad, adaptabilidad y cierre visual

### Objetivo

Verificar Reduce Transparency, Reduce Motion, Dynamic Type, VoiceOver y consistencia completa antes de la auditoría.

### Archivos

```text
ios/PocketGB/DesignSystem/*
ios/PocketGB/Library/*
ios/PocketGB/Gameplay/*
ios/PocketGB/Input/*
ios/PocketGB/Settings/*
ios/PocketGBUITests/ScreenshotTests.swift
ios/PocketGBUITests/AccessibilityTests.swift
ios/PocketGBUITests/screens.txt
docs/diseno/VERIFICACION.md
```

### Capturas añadidas

```text
library-reduce-transparency portrait dark  -screen library-reduce-transparency -demoLibrary standard -reduceTransparency -uiStyle dark
library-ax5                 portrait light -screen library-ax5 -demoLibrary standard -contentSizeCategory accessibility5 -uiStyle light
```

`gameplay-reduce-transparency` ya fue añadido en D4 para que el defecto crítico de controles blancos no quede aplazado.

### Datos de prueba

- Títulos cortos y largos.
- Fechas fijas.
- Juego con captura y juego con placeholder.
- Reduce Transparency y Reduce Motion forzados mediante entorno interno DEBUG.
- Dynamic Type AX5.

### Criterios CI

- Cada ID de SPEC aparece en `screens.txt`.
- No hay IDs duplicados con argumentos contradictorios.
- Cada PNG esperado fue publicado.
- Tests de accesibilidad encuentran labels y acciones principales.
- Grid hace reflow en AX5.
- No hay texto crítico truncado.
- Touch targets medibles ≥44 pt.
- Los símbolos decorativos no duplican lectura.
- Reduce Transparency conserva jerarquía.
- Reduce Motion desactiva zoom/morph/stagger.
- Todos los estados usan texto además de color.
- `rg` confirma ausencia de red, GBA, cheats y vidrio simulado.
- `make -C core test` y tests iOS pasan.
- Se revisan visualmente todos los PNG antes de solicitar auditoría.

### Validación en iPhone

- VoiceOver completo.
- Magic Tap o acción equivalente para menú.
- Orden de foco en cards, HUD, sheets y save states.
- Dynamic Type AX1–AX5.
- Bold Text e Increase Contrast.
- Reduce Transparency real del sistema.
- Reduce Motion real.
- Switch Control si está disponible.
- Legibilidad exterior y brillo mínimo.

## 10. D8 · Regresión, documentación y auditoría

### Objetivo

Cerrar el trabajo sin añadir funcionalidad, consolidar evidencia y obtener auditoría independiente.

### Archivos

```text
docs/diseno/SPEC.md
docs/diseno/VERIFICACION.md
docs/hitos/D-README.md
docs/ESTADO.md
docs/auditorias/D8-*.md
ios/PocketGBUITests/screens.txt
```

### Capturas

No añade IDs. Reejecuta el catálogo completo en un solo push final.

### Datos de prueba

- Todas las ROMs continúan siendo fixtures libres de `core/tests/roms`.
- Ningún `.sav`, `.state`, ROM comercial o captura comercial entra al repo.

### Criterios CI

- Catálogo completo verde.
- `SUMMARY.md` sin errores ni warnings propios.
- Núcleo sin regresiones.
- Tests AtomicFile, biblioteca, controles, save states y accesibilidad verdes.
- Revisión visual registrada en `docs/diseno/VERIFICACION.md`.
- Cada criterio indica el comando/run que lo verificó.
- Auditoría independiente aprobada.
- M6/M7 quedan marcados como sustituidos por D2–D6, sin duplicar estado.
- `docs/ESTADO.md` refleja el resultado real.

### Validación final de Joel

- Carpeta iCloud y bookmark.
- Pokémon Rojo/Amarillo volcados por Joel, nunca subidos.
- SRAM tras cierre forzado y reinstalación.
- Mando físico.
- Multitouch.
- Audio.
- Save states y restauración de backups.
- Liquid Glass sobre escenas claras y oscuras.
- Accesibilidad.
- Aprobación del merge.

## 11. Condición de cierre de cada hito

Un hito D solo se considera terminado cuando:

1. Los cambios forman un lote coherente.
2. Se ejecutaron los chequeos disponibles en Linux.
3. Se hizo push una vez para el lote.
4. El CI macOS terminó.
5. Se revisó `SUMMARY.md`.
6. Se inspeccionaron todas las capturas nuevas.
7. Se corrigieron errores visibles o se documentó su aceptación.
8. Se ejecutaron los tests exigidos por el hito.
9. Se registró qué falta validar en el iPhone.
10. Se obtuvo auditoría cuando el hito cierra funcionalidad de M6/M7.
11. Se actualizó `docs/ESTADO.md`.
12. No se añadieron ROMs comerciales, `.sav`, `.state`, boot ROMs ni material con licencia incompatible.
