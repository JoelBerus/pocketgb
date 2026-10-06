<!-- Borrador redactado por Codex (solo lectura) a partir de propuesta.html; revisado por Claude el 2026-09-29. APIs contrastadas con el SDK: ver API-iOS26.md. -->
# PocketGB — Especificación de diseño e implementación iOS

> Destino: `docs/diseno/SPEC.md`  
> Plataforma: iPhone, iOS 26.0+, Swift 6, SwiftUI con islas UIKit/Metal  
> Alcance: Game Boy y Game Boy Color  
> Fuente visual: `docs/diseno/propuesta.html`  
> Fuente funcional: `docs/04-ios-spec.md` y `docs/02-arquitectura.md`

## 1. Alcance

PocketGB es un emulador local para ROMs `.gb` y `.gbc` volcados por Joel desde sus propios cartuchos. La aplicación:

- Lee una carpeta elegida por el usuario en iCloud Drive o Archivos mediante security-scoped bookmark.
- No copia ni modifica los ROMs.
- No usa red, servicios de portadas, telemetría ni SDKs externos.
- Genera cada portada a partir de una captura local del juego o de un placeholder determinista.
- Conserva SRAM, backups y save states según `docs/04-ios-spec.md`.
- Usa Liquid Glass nativo de iOS 26 para navegación, controles flotantes y presentaciones.
- Mantiene el viewport, portadas, capturas y contenido principal sin vidrio.

Fuera de alcance:

- Cheats.
- Descarga o búsqueda de portadas.
- Importación de ROMs como copias administradas por la app.
- Sincronización propia por red.
- Shaders que alteren la exactitud del núcleo; los filtros de presentación son opcionales y locales.
- Eliminación del ROM original desde PocketGB.

## 2. Principios de diseño

1. **El juego es el contenido.** El framebuffer, las capturas y las portadas pertenecen a la capa visual principal.
2. **El vidrio comunica función.** Solo navegación, acciones y modales pueden usar Liquid Glass.
3. **Nunca vidrio sobre vidrio.** Los elementos próximos comparten un único `GlassEffectContainer`; no se anidan efectos.
4. **Sistema antes que decoración.** Se usan `TabView`, `NavigationStack`, `toolbar`, `searchable`, `sheet`, `alert`, `Menu`, `Form`, `Toggle`, `Slider` y `Picker`.
5. **La persistencia prima sobre la apariencia.** Ninguna transición, captura o cambio visual puede retrasar o debilitar el flush de SRAM.
6. **La opacidad visual no cambia el área táctil.** Gameplay mantiene una superficie UIKit única para multitouch.
7. **La UI no depende del color o del vidrio.** Forma, texto, símbolos y estados accesibles conservan la jerarquía con Reduce Transparency.
8. **Las capturas del CI son deterministas.** Los datos de demostración, fechas, progreso y frames quedan fijados por argumentos DEBUG.

## 3. Capas visuales

### L1 · Content

Contenido opaco que puede extenderse por debajo de navegación y controles:

- `GameViewport`
- Capturas y portadas
- Placeholders de juegos
- Grids y listas
- Fondos
- Previews de save states
- Preview de filtros de vídeo

Reglas:

- No aplicar `glassEffect`, `Material`, blur ni bordes especulares.
- Las portadas no tienen una card de vidrio detrás.
- El framebuffer conserva `nearest` sampling salvo que el usuario seleccione expresamente otro filtro.
- El contenido puede invadir safe areas cuando sea decorativo; sus controles nunca.

### L2 · Functional Glass

Acciones persistentes o temporales que flotan sobre L1:

- Tab bar del sistema
- Toolbar y botones de navegación
- Search
- HUD de gameplay
- Botones A/B, D-pad, Start, Select y menú
- Toolbar de selección de save states
- Toasts

Implementación:

- Preferir el Liquid Glass automático de componentes nativos.
- Para vistas SwiftUI personalizadas usar `glassEffect(_:in:)`.
- Agrupar formas cercanas con `GlassEffectContainer`.
- Usar `.regular` para navegación y `.clear` solo sobre gameplay con dimming localizado.
- Usar `.interactive()` únicamente en controles accionables.
- Usar `.buttonStyle(.glass)` o `.buttonStyle(.glassProminent)` para botones.
- Los controles UIKit de gameplay usan `UIVisualEffectView` con `UIGlassEffect`; no un blur manual.

### L3 · Modal Glass

Interrupciones que requieren atención:

- Pause sheet
- Confirmaciones de cargar o reemplazar estado
- Errores
- Menús contextuales
- Confirmación para ocultar un juego
- Inspector del editor de controles

Reglas:

- Usar presentaciones del sistema; no dibujar un “modal glass” manual.
- Atenuar el contenido posterior mediante la presentación del sistema.
- Usar `presentationDetents` cuando existan estados medium/large.
- No aplicar otro `glassEffect` al fondo de una sheet que ya recibe el material del sistema.
- La acción destructiva lleva `role: .destructive` y texto inequívoco.

## 4. Arquitectura de información

```text
Tab: Biblioteca
├─ Elegir / cambiar carpeta de juegos
├─ Buscar
│  └─ Filtros: Todos · GB · GBC · Favoritos
├─ Continuar jugando
├─ Todos los juegos
│  ├─ Grid
│  └─ Lista
└─ Detalle del juego
   ├─ Continuar / Jugar desde el inicio
   ├─ Favorito
   ├─ Save states
   ├─ Ajustes del juego
   └─ Ocultar de PocketGB

Tab: Favoritos
├─ Jugados recientemente
└─ Favoritos

Tab: Ajustes
├─ Emulación
├─ Controles
│  ├─ Apariencia
│  ├─ Háptica
│  ├─ Gestos
│  └─ Editar layout portrait / landscape
├─ Audio
├─ Pantalla
├─ Biblioteca
│  ├─ Carpeta seleccionada
│  ├─ Volver a escanear
│  └─ Juegos ocultos
├─ Partidas
│  ├─ SRAM local
│  └─ Backups restaurables
├─ Almacenamiento
├─ Apariencia
└─ Acerca de

Gameplay, full-screen y sin tab bar
├─ HUD
├─ Pause sheet
├─ Save states
└─ Ajustes del juego
```

La selección de carpeta reemplaza el flujo “Import Game” de la propuesta original. (En D1 hubo un “Abrir un archivo…” transitorio; D2 lo retiró al llegar la carpeta con bookmark.) Un `.sav` junto al ROM con tamaño incorrecto nunca se sobrescribe: se avisa y se usa la copia local, o la sesión no guarda si no hay otra. Al seleccionar o volver a foreground:

1. Se resuelve el bookmark.
2. Se coordina el acceso.
3. Se escanean `.gb` y `.gbc` hasta un nivel de profundidad.
4. Se valida tamaño y cabecera.
5. Se muestran inmediatamente entradas y placeholders.
6. Los archivos pendientes de iCloud presentan estado de nube.
7. Al jugar por primera vez se guarda una captura local como portada.

## 5. Arquitectura de implementación

### 5.1 Límites existentes

Se conservan:

- `EmulatorSession`: dueño del núcleo y del hilo de emulación.
- `GameMetalView`: puente SwiftUI → `MTKView`.
- `Renderer`: presentación Metal del framebuffer.
- `ControlsOverlayView`: única `UIView` multitáctil.
- `AtomicFile` y `SaveStore`: base de persistencia atómica.
- `AppState`: coordinación global, ampliada sin contener lógica de archivos pesada.

La migración visual no debe mover lógica de emulación a SwiftUI ni realizar I/O en `body`.

### 5.2 Módulos previstos

```text
App/
  PocketGBApp.swift
  AppState.swift
  DebugArguments.swift
  DebugScreenRouter.swift

DesignSystem/
  DesignTokens.swift
  PocketGlass.swift
  EmptyStateView.swift
  ToastView.swift

Library/
  LibraryRootView.swift
  LibraryView.swift
  LibraryStore.swift
  LibraryScanner.swift
  RomEntry.swift
  GameCard.swift
  GameListItem.swift
  GameDetailsView.swift
  GameArtworkStore.swift
  FolderPicker.swift

Favorites/
  FavoritesView.swift

Gameplay/
  GameScreen.swift
  GameplayHUD.swift
  PauseView.swift

Input/
  ControlsOverlayView.swift
  ControlsLayout.swift
  ControlsSettings.swift
  ControlsEditorView.swift
  GamepadInput.swift
  Haptics.swift

Saves/
  AtomicFile.swift
  SaveStore.swift
  StateStore.swift
  SaveStatesView.swift
  SavesSettingsView.swift

Settings/
  Settings.swift
  SettingsView.swift
  ControlsSettingsView.swift
  DisplaySettingsView.swift
  AudioSettingsView.swift
  LibrarySettingsView.swift
  StorageSettingsView.swift
  AppearanceSettingsView.swift
  AboutView.swift
```

### 5.3 Concurrencia

- Los stores observables usados por SwiftUI son `@MainActor`.
- Escaneo, coordinación de archivos, hashing y generación de PNG se ejecutan fuera del actor principal.
- El resultado vuelve al `MainActor` como valores `Sendable`.
- El núcleo continúa confinado al hilo de `EmulatorSession`.
- Guardar una portada nunca bloquea el hilo de emulación: se copia el último framebuffer y se codifica en una cola aparte.
- El escaneo no abre ni ejecuta ROMs para generar artwork.
- La captura de portada se crea después de haber jugado, usando un frame ya producido.
- Las fechas de filesystem y metadatos se normalizan antes de llegar a la vista.

## 6. Uso de APIs de iOS 26

| Necesidad | API |
|---|---|
| Navegación principal | `TabView` con `Tab` |
| Tab bar compacta al desplazarse | `.tabBarMinimizeBehavior(.onScrollDown)` |
| Navegación jerárquica | `NavigationStack` |
| Portada → detalle | `.matchedTransitionSource(id:in:)` y `.navigationTransition(.zoom(sourceID:in:))` |
| Reduce Motion en navegación | `.navigationTransition(.automatic)` sin source zoom (no existe `.crossFade` en el SDK de iOS 26) |
| Toolbars | `.toolbar` y `ToolbarSpacer(.fixed)` |
| Búsqueda | `.searchable`, `.searchToolbarBehavior(.minimize)` |
| Separación sobre scroll | `.scrollEdgeEffectStyle(.soft, for: .top/.bottom)` |
| Botón secundario | `.buttonStyle(.glass)` |
| CTA principal | `.buttonStyle(.glassProminent)` |
| Vidrio personalizado | `.glassEffect(.regular/.clear, in:)` |
| Vidrio interactivo | `.glassEffect(.regular.interactive(), in:)` |
| Grupo de vidrio | `GlassEffectContainer` |
| Morph entre formas de vidrio | `.glassEffectID(_:in:)` dentro del mismo container |
| Sheets | `.sheet`, `.presentationDetents`, `.presentationDragIndicator` |
| Formularios | `Form`, `Section`, `Toggle`, `Slider`, `Picker`, `Menu` |
| Menús contextuales | `.contextMenu` con preview cuando aplique |
| Safe areas | `safeAreaInset`, `GeometryProxy.safeAreaInsets` |
| Gestos de sistema | `.defersSystemGestures(on: .all)` durante gameplay |
| Ocultar overlays | `.persistentSystemOverlays(.hidden)` durante gameplay |
| Vidrio UIKit de controles | `UIVisualEffectView` + `UIGlassEffect` |
| Contenedor UIKit | `UIGlassContainerEffect` cuando varias superficies deban compartir muestreo |

### 6.1 Límites reales de las APIs

- No existe una API pública de Liquid Glass que permita indicar blur, saturación, brillo, borde o refracción numéricos. Los valores CSS de la propuesta no se trasladan a SwiftUI.
- `Glass.clear` sí existe, pero no ofrece contraste adaptativo suficiente en todos los fondos. Requiere un scrim oscuro localizado bajo cada control.
- No existe un parámetro público `glassOpacity`. El ajuste 30/50/70/100 modifica el alpha de la superficie UIKit de vidrio y del scrim, nunca el contenedor táctil.
- No existe `glass.modal`. Sheets, alerts, menus y context menus deben usar su material nativo.
- `glassEffectID` no transforma cualquier vista arbitraria: coordina formas Liquid Glass dentro de un `GlassEffectContainer`.
- No existe una API pública que entregue la geometría de Dynamic Island. Solo se usan safe-area insets.
- SwiftUI no reemplaza el seguimiento multitáctil `[UITouch: Control]` requerido por gameplay. Los controles siguen siendo UIKit.
- No existe un control nativo de D-pad ni un editor de layouts.
- No se imitará Liquid Glass con `.blur`, `UIBlurEffect`, gradientes especulares o sombras dibujadas a mano.
- El sistema gestiona Reduce Transparency sobre sus componentes, pero los controles UIKit personalizados deben aportar explícitamente una superficie sólida alternativa.

Referencias oficiales:

- [Liquid Glass para vistas SwiftUI](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views)
- [Glass y sus variantes](https://developer.apple.com/documentation/swiftui/glass)
- [GlassEffectContainer](https://developer.apple.com/documentation/swiftui/glasseffectcontainer)
- [Tab](https://developer.apple.com/documentation/swiftui/tab)
- [TabBarMinimizeBehavior](https://developer.apple.com/documentation/swiftui/tabbarminimizebehavior)
- [ScrollEdgeEffectStyle](https://developer.apple.com/documentation/swiftui/scrolledgeeffectstyle)
- [ToolbarSpacer](https://developer.apple.com/documentation/swiftui/toolbarspacer)
- [ZoomNavigationTransition](https://developer.apple.com/documentation/swiftui/zoomnavigationtransition)

## 7. Design tokens

### 7.1 Color assets

Todos los colores propios se crean en `Assets.xcassets` con variantes Any/Dark. No se codifican hex repetidos en vistas.

| Asset | Light | Dark | Uso |
|---|---:|---:|---|
| `BackgroundBase` | `#F2F3F7` | `#0E1014` | Biblioteca y ajustes |
| `BackgroundElevated` | `#FFFFFF` | `#1A1D23` | Grupos opacos y fallback accesible |
| `GameplayBackground` | `#101217` | `#101217` | Controles portrait y letterbox |
| `AccentPrimary` | `#1F68F5` | `#3F7FEF` | Selección y CTA (dark era `#5A96FF`: texto blanco a 2,9:1; `#3F7FEF` da ~3,9:1, auditoría D1 H2) |
| `Danger` | `#E3342F` | `#FF5B52` | Acciones destructivas |
| `ControlScrim` | `#000000` | `#000000` | Dimming localizado bajo `.clear` |
| `ControlAWarm` | `#E7A65B` | `#FFC478` | Anillo semántico A |
| `ControlBCool` | `#6699E8` | `#96BEFF` | Anillo semántico B |
| `PlaceholderBlue` | `#406BC7` | `#547FDB` | Placeholder determinista |
| `PlaceholderGreen` | `#4E8C6B` | `#65A17F` | Placeholder determinista |
| `PlaceholderAmber` | `#B77932` | `#D3974C` | Placeholder determinista |
| `PlaceholderViolet` | `#7456A3` | `#9271BE` | Placeholder determinista |

Texto usa `Color.primary` y `Color.secondary`; no se duplican como assets.

### 7.2 Espaciado

```swift
enum PocketSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 20
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
}
```

- Margen horizontal normal: 16 pt.
- Margen en pantallas anchas: 20 pt.
- Gap de grid: 12 pt.
- Separación mínima entre controles: 8 pt.
- Touch target mínimo: 44×44 pt.
- El contenido puede extenderse bajo barras; el texto y las acciones respetan safe areas.

### 7.3 Radios

- Portada grande: 14 pt.
- Thumbnail: 8 pt.
- Grupo opaco: 14 pt.
- Preview de save state: 12 pt.
- Cápsula: mitad de su altura.
- Sheets: radio gestionado por iOS.
- Cuando sea aplicable, usar forma concéntrica del sistema en lugar de copiar el radio físico del dispositivo.

### 7.4 Tipografía

Solo SF y estilos dinámicos:

| Uso | SwiftUI |
|---|---|
| Título principal | `.largeTitle.bold()` |
| Título de juego | `.title2.bold()` |
| Sección | `.headline` |
| Contenido | `.body` |
| Metadatos | `.subheadline` |
| Ayuda | `.footnote` |
| Badge GB/GBC | `.caption2.monospaced().weight(.semibold)` |
| Etiquetas A/B | Fuente UIKit dinámica equivalente a `.title3.bold()` |
| Start/Select | Fuente UIKit dinámica equivalente a `.caption.bold()` |

No existe una variante pixel de SF. No se incorpora Silkscreen ni otra fuente externa solo para imitar la maqueta.

### 7.5 Motion

| Token | Valor |
|---|---|
| `controlPress` | 70 ms, ease-out, scale 0.90 |
| `controlRelease` | 90 ms, ease-out |
| `hudMorph` | 220 ms, spring |
| `toast` | entrada 180 ms, visible 2.5 s, salida 180 ms |
| `rotationViewport` | 300 ms, spring; 200 ms crossfade con Reduce Motion |
| `newArtwork` | 180 ms, opacity + scale 0.98→1 |
| `controlsAutohide` | 3 s |

Ninguna animación bloquea input, flush o cambio de orientación.

### 7.6 Háptica

| Evento | Feedback |
|---|---|
| A/B/Start/Select | `UIImpactFeedbackGenerator(style: .light)`, una vez al entrar en pressed |
| Cambio de sector D-pad | `UISelectionFeedbackGenerator` |
| Guardar estado | `UINotificationFeedbackGenerator(.success)` |
| Error de carga/guardado | `.error` |
| Confirmación destructiva | `.warning` |
| Avance rápido | `.rigid` al activar/desactivar |

Se respeta el toggle global `Haptic Feedback`. No se dispara háptica por cada frame ni mientras un botón permanece pulsado.

## 8. Componentes

| Componente | Tipo | Capa | Variantes | Estados | API iOS 26 |
|---|---|---|---|---|---|
| `LibraryRootView` | SwiftUI | — | Library/Favorites/Settings | tab activa | `TabView`, `Tab`, `tabBarMinimizeBehavior` |
| `LibraryView` | SwiftUI | L1 | grid/list | loading, empty, ready, unavailable | `NavigationStack`, `searchable`, `scrollEdgeEffectStyle` |
| `GameCard` | SwiftUI | L1 | large/medium/small | default, pressed, favorite, new, cloud | `matchedTransitionSource` |
| `GameListItem` | SwiftUI | L1 | con/sin favorito | default, pressed, cloud, warning | `swipeActions`, `contextMenu` |
| `ConsoleChip` | SwiftUI | L1 | GB/GBC | normal/on-dark | SF monospaced; sin vidrio |
| `GameArtworkView` | SwiftUI | L1 | captura/placeholder | loading, ready, unavailable | `Image`, `Canvas` solo para placeholder |
| `ContinuePlayingView` | SwiftUI | L1+L2 | compact/expanded | disponible/no disponible | botón `.glassProminent` sobre captura |
| `FilterScope` | SwiftUI | L2 | Todos/GB/GBC/Favoritos | seleccionado/no seleccionado | `searchScopes` o `Picker` |
| `EmptyStateView` | SwiftUI | — | sin carpeta/sin juegos/sin resultados | normal/error | `ContentUnavailableView` si permite la composición requerida |
| `PocketToolbar` | SwiftUI | L2 | library/detail/selection | normal/scrolled | `toolbar`, `ToolbarSpacer` |
| `GameplayHUD` | SwiftUI | L2 | collapsed/expanded | visible, auto-hide, saving | `GlassEffectContainer`, `glassEffectID` |
| `ControlsOverlay` | SwiftUI wrapper | L2 | portrait/landscape/editor | active, hidden, controller, RT | `UIViewRepresentable` |
| `ControlsOverlayView` | UIKit | L2 | GB/GBC | idle, pressed, editing, hidden | `UIGlassEffect`, `UIGlassContainerEffect` |
| `DPadControl` | UIKit layer/view | L2 | cruz | zona muerta, 8 direcciones, editing | vidrio nativo; cálculo propio |
| `FaceButtonControl` | UIKit layer/view | L2 | A/B | idle, pressed, editing | vidrio clear + scrim |
| `StartSelectControl` | UIKit layer/view | L2 | Start/Select | idle, pressed, editing | vidrio clear + scrim |
| `GameViewport` | SwiftUI/UIKit | L1 | fit/integer/fill | loading, running, paused, error | `MTKView` |
| `PauseView` | SwiftUI | L3 | medium/large | paused, saving, error | `sheet`, `presentationDetents` |
| `SaveStateCard` | SwiftUI | L1 | manual/auto/empty | default, selected, corrupt | sin vidrio |
| `SaveStateActionBar` | SwiftUI | L2 | load/replace/delete | enabled/disabled | `GlassEffectContainer`, buttons glass |
| `SettingsView` | SwiftUI | — | global/per-game | inherited/customized | `Form`, `NavigationLink` |
| `SettingsValueBadge` | SwiftUI | — | Global/Personalizado | normal | texto + símbolo, nunca solo color |
| `ToastView` | SwiftUI | L2 | success/info/error | entering/visible/leaving | `glassEffect(.regular)` |
| `FolderPicker` | UIKit wrapper | L3 | carpeta | presented/cancelled | `UIDocumentPickerViewController` |
| `ConfirmationDialog` | SwiftUI | L3 | ocultar/cargar/reemplazar | normal/destructive | `confirmationDialog`/`alert` |

## 9. Pantallas y estados del catálogo

Cada ID es estable y se usa como primer campo de `ios/PocketGBUITests/screens.txt`. “Ambos” significa una captura light y otra dark. Gameplay usa fondo oscuro aunque el resto de la app esté en light.

| # | ID | Pantalla/estado | Orientación | Apariencia | Debe verse | Error visual |
|---:|---|---|---|---|---|---|
| 1 | `launch` | Launch preview determinista | portrait | dark | Glifo centrado, fondo oscuro, sin texto ni spinner | UI de biblioteca o fondo blanco |
| 2 | `library-no-folder` | Primer arranque | portrait | ambos | Título, explicación breve y CTA “Elegir carpeta” | “Abrir ROM”, selector de archivo o GBA |
| 3 | `library-empty` | Carpeta válida sin ROMs | portrait | ambos | Carpeta actual, estado vacío y acción de reescaneo/cambio | Grid vacío sin explicación |
| 4 | `library-folder-unavailable` | Bookmark inválido/stale no recuperable | portrait | ambos | Error no destructivo y “Elegir de nuevo” | Borrar preferencias o crash |
| 5 | `library-cloud-pending` | ROM pendiente en iCloud | portrait | ambos | Entrada con símbolo de nube y estado pendiente | Juego presentado como listo |
| 6 | `library-cloud-downloading` | Descarga coordinada | portrait | ambos | Progreso o indicador y controles bloqueados con explicación | Spinner global que oculta la biblioteca |
| 7 | `library-scan-progress` | Escaneo activo | portrait | ambos | Biblioteca utilizable y progreso discreto | Modal bloqueante |
| 8 | `library-scan-summary` | Escaneo terminado con novedades | portrait | ambos | Toast y nuevo juego con énfasis breve | “Import success” o artwork remoto |
| 9 | `library-rom-error` | ROM inválido/no compatible | portrait | ambos | Nombre, motivo y acción para localizar archivo | Ejecutar o modificar el ROM |
| 10 | `save-data-error` | `.sav` de tamaño incorrecto | portrait | ambos | Mensaje de que el archivo no se tocará | Ofrecer sobrescribirlo |
| 11 | `library-grid` | Biblioteca grid | portrait | ambos | Continue, portadas/capturas, GB/GBC, tab bar nativa | Cards de vidrio, GBA o portadas web |
| 12 | `library-list` | Biblioteca lista | portrait | ambos | Thumbnail, título, sistema y última partida | Filas sin jerarquía o truncado crítico |
| 13 | `library-continue` | Carril Continue Playing | portrait | ambos | Último frame real y CTA dominante | Cover ficticia en vez de frame |
| 14 | `favorites` | Recientes y favoritos | portrait | ambos | Carril reciente y grid favorito | Pantalla vacía sin ayuda |
| 15 | `search-active` | Búsqueda enfocada | portrait | ambos | Teclado, query, scopes Todos/GB/GBC/Favoritos | Scope GBA |
| 16 | `search-results` | Resultados en vivo | portrait | ambos | Coincidencias y sistema | Resultados fuera del scope |
| 17 | `search-no-results` | Sin resultados | portrait | ambos | Query, explicación y “Buscar en todos” | Solo texto “0 resultados” |
| 18 | `game-details` | Detalle del juego | portrait | ambos | Captura/placeholder, metadatos, Continue, estados y ajustes | Portada descargada, cheats o GBA |
| 19 | `game-context-menu` | Long press | portrait | ambos | Preview, Play, Favorito, Estados, Ajustes, Ocultar | Opción para borrar el ROM original |
| 20 | `remove-game-confirm` | Ocultar juego | portrait | ambos | Explica ROM intacto y destino de saves | Texto ambiguo “Eliminar juego” |
| 21 | `gameplay-portrait` | Juego vertical | portrait | dark | Viewport 10:9 arriba y controles sólidos/claros debajo | Controles sobre Dynamic Island o viewport estirado |
| 22 | ~~`gameplay-portrait-hud`~~ | Sustituida (decisión de Joel, 2026-09-30) | portrait | dark | El botón de pausa abre directamente la sheet de opciones: ver `gameplay-pause`. El avance rápido está junto a él (`gameplay-fast-forward`) | — |
| 23 | `gameplay-landscape` | Juego horizontal | landscape | dark | Viewport a máxima altura, controles dentro del safe area | Controles bajo Island o Home Indicator |
| 24 | `gameplay-landscape-clear` | Controles clear al 30 % | landscape | dark | Silueta y labels legibles sobre frame claro gracias a scrim | Controles blancos desaparecidos |
| 25 | `gameplay-landscape-hidden` | Show On Touch oculto | landscape | dark | Solo juego y pista temporal | Áreas invisibles bloqueando HUD o gestos |
| 26 | `gameplay-pause` | Pause sheet | portrait | dark | Frame atenuado, Resume dominante y Exit separado | Pausa como botón aislado sin contexto |
| 27 | `save-states` | 4 manuales + auto | portrait | dark | Capturas, timestamps, selección y slot vacío | Estados mezclados con SRAM |
| 28 | `load-state-confirm` | Confirmar carga | portrait | dark | Progreso que se perderá y “Guardar actual y cargar” | Carga inmediata sin advertencia |
| 29 | `replace-state-confirm` | Reemplazar slot | portrait | dark | Slot destino, fecha y cancelación | Acción destructiva sin contexto |
| 30 | `customize-controls-portrait` | Editor vertical | portrait | dark | Handles, guías, snap y inspector | L/R o controles fuera del safe area |
| 31 | `customize-controls-landscape` | Editor horizontal | landscape | dark | Layout independiente y áreas seguras visibles | Home Indicator cubierto |
| 32 | `settings-main` | Ajustes | portrait | ambos | Lista nativa agrupada | Cards de vidrio decorativas |
| 33 | `settings-controls` | Controles | portrait | ambos | Opacidad, tamaño, visibilidad, háptica y editor | Slider que reduce hit area |
| 34 | `settings-display` | Pantalla | portrait | ambos | Preview 10:9, integer scale y filtros disponibles | Preview GBA |
| 35 | `settings-audio` | Audio | portrait | ambos | Volumen, mute y respeto al modo silencio | Audio en background activado |
| 36 | `settings-library` | Biblioteca | portrait | ambos | Carpeta, vista, orden, escanear y ocultos | Auto-match artwork por red |
| 37 | `settings-saves` | Partidas y backups | portrait | ambos | Backups por juego, fechas y restauración | Confundir SRAM con save state |
| 38 | `settings-storage` | Almacenamiento | portrait | ambos | Saves, states, capturas y caché local separados | Botón para borrar ROMs de iCloud |
| 39 | `settings-appearance` | Apariencia | portrait | ambos | Sistema/Light/Dark y gameplay oscuro | Paleta propia que ignore accesibilidad |
| 40 | `settings-about` | Acerca de | portrait | ambos | Versión, núcleo, licencias y privacidad sin red | Créditos/licencias ausentes |
| 41 | `library-reduce-transparency` | Biblioteca sin transparencia | portrait | dark | Misma jerarquía con superficies sólidas | Vidrio manual persistente |
| 42 | `gameplay-reduce-transparency` | Gameplay sin transparencia | landscape | dark | Controles sólidos ≥90 %, borde y labels completos | Controles al 30 % ilegibles |
| 43 | `library-ax5` | Biblioteca Dynamic Type AX5 | portrait | light | Reflow, títulos completos y targets accesibles | Solapamientos o texto recortado |

La pantalla real del launch de iOS termina antes de que el UI test pueda capturarla de forma fiable. `launch` es una ruta DEBUG que renderiza la misma composición para revisión; el launch asset real se valida en dispositivo.

## 10. Reglas de gameplay

### 10.1 Portrait

- El viewport conserva proporción 10:9 y usa todo el ancho disponible sin entrar en la Dynamic Island.
- El framebuffer no se estira.
- Los controles ocupan el área inferior sobre `GameplayBackground`.
- D-pad, A, B, Start y Select tienen superficie visual opaca o regular; no necesitan clear glass sobre un fondo uniforme.
- Si falta altura, se reduce primero el viewport. Los touch targets nunca bajan de 44 pt.
- En Game Boy Advance (G8) la imagen es 3:2 en vez de 10:9 y aparecen L y R: píldoras de 92×40 pt (táctil ≥ 44 pt) arriba a los lados, sin pisar el menú ni A/B/cruceta, arrastrables en el editor. En GB/GBC no existen L/R.
- La hoja «Ajustes del juego» de GBA añade tipo de partida, reloj y BIOS (global / la tuya / emulada); el aviso de ajustes forzados que no coinciden con la partida usa el estilo de las demás alertas.
- El botón de menú queda entre viewport y controles, fuera del área del D-pad.
- El HUD se superpone sin detener la emulación; la sheet de pausa sí pausa antes de mostrarse.

### 10.2 Landscape

- El framebuffer puede ocupar todo el alto y extenderse bajo las safe areas.
- La geometría de controles usa `bounds.inset(by: safeAreaInsets)`.
- D-pad: lateral izquierdo.
- A/B: lateral derecho, con A arriba/derecha y B abajo/izquierda.
- Start/Select: separados del centro inferior y del Home Indicator.
- Menú/HUD: zona superior central, sin invadir Dynamic Island.
- El lado de la Dynamic Island puede cambiar según la orientación; nunca se codifica “izquierda”.
- `.defersSystemGestures(on: .all)` y `.persistentSystemOverlays(.hidden)` se activan solo durante gameplay.
- El controlador host solicita `prefersHomeIndicatorAutoHidden = true` si los modificadores SwiftUI no cubren el comportamiento requerido.

### 10.3 Vidrio claro y contraste

Cada control landscape tiene tres capas independientes:

```text
área táctil UIKit — siempre del mismo tamaño
└─ scrim localizado oscuro — forma del control, expandida 2–4 pt
   └─ UIVisualEffectView + UIGlassEffect clear
      └─ label/símbolo vibrante
```

Reglas:

- El scrim se mantiene incluso si el frame es blanco.
- Opacidad seleccionable: 30, 50, 70 o 100 %.
- Cambia el alpha del scrim y de la superficie visual, no el alpha de `ControlsOverlayView`.
- El label no baja de 70 %.
- En pressed, la superficie aumenta contraste y hace scale 0.90; el hit frame no cambia.
- A y B se distinguen por label, posición y anillo cálido/frío.
- Reduce Transparency reemplaza el vidrio por relleno sólido oscuro ≥90 %, borde de 1.5 pt y texto al 100 %.
- No se mezcla `.regular` y `.clear` dentro del mismo conjunto de controles.
- Al ocultar controles, las vistas visuales dejan de participar en hit testing; un toque libre muestra el overlay si el modo es Show On Touch.

### 10.4 Multitouch

`ControlsOverlayView` conserva:

- `isMultipleTouchEnabled = true`.
- Tabla `[UITouch: Control]`.
- A+B simultáneos.
- Zona A+B invisible.
- Deslizamiento B→A sin levantar.
- D-pad capturado hasta `touchesEnded`.
- Zona muerta del 25 %.
- Ocho sectores de 45°.
- Prohibición de direcciones opuestas.
- Máscara táctil combinada por OR con la del mando físico.

El efecto visual nunca decide qué control está pulsado; solo representa el estado calculado por el motor de input.

### 10.5 Rotación

- La sesión y el audio continúan.
- El último framebuffer queda visible.
- Los controles anteriores hacen fade/scale out.
- El viewport adopta su frame nuevo conservando 10:9.
- Entran las posiciones guardadas para la nueva orientación.
- No se interpolan posiciones táctiles activas; al iniciar la transición se publicará máscara cero.
- Con Reduce Motion se usa crossfade de 200 ms.

## 11. Portadas y capturas

Orden de resolución:

1. Captura local elegida para el juego.
2. Último frame persistido al cerrar una sesión válida.
3. Placeholder determinista.

El placeholder:

- Se deriva de `fingerprint`, título y sistema.
- Usa exclusivamente los assets `Placeholder*`.
- Incluye un glifo abstracto, título abreviado y chip GB/GBC.
- No intenta recrear el arte comercial.
- Es estable entre lanzamientos.
- Tiene descripción VoiceOver: “Sin captura, portada generada para …”.

Las capturas se guardan en `Application Support/Artwork/<fingerprint>.png` con reemplazo atómico. No se escriben junto al ROM.

## 12. Saves y estados en la UI

### SRAM

- La SRAM local continúa siendo autoritativa.
- El espejo junto al ROM se coordina mediante `NSFileCoordinator`.
- Un error de espejo no invalida el guardado local.
- Un `.sav` de tamaño incorrecto se muestra como error y no se toca.
- La restauración de backup crea primero un backup del estado actual.
- Quitar u ocultar un juego no borra SRAM ni backups por defecto.

### Save states

- Cuatro slots manuales y uno automático al salir.
- Cada estado muestra captura, fecha y tiempo de juego.
- Guardar y reemplazar usan `AtomicFile`.
- Cargar un estado actualiza la SRAM del núcleo y la persiste inmediatamente por la ruta normal, con backup.
- Un estado corrupto permanece visible como error hasta que el usuario decida borrarlo.
- Save states y SRAM nunca se presentan como equivalentes.

## 13. Accesibilidad

### Reduce Transparency

Leer `accessibilityReduceTransparency`.

- Navegación nativa adopta el fallback del sistema.
- Los controles UIKit cambian a superficie sólida.
- Sheets no reciben fondos glass personalizados.
- El contraste y la jerarquía no dependen de blur.
- Las capturas `library-reduce-transparency` y `gameplay-reduce-transparency` validan esta variante.

### Reduce Motion

Leer `accessibilityReduceMotion`.

- Zoom de portada → detalle se reemplaza por transición automática/crossfade.
- `glassEffectID` usa fade.
- La rotación usa crossfade.
- Se elimina stagger.
- Press/release conserva feedback inmediato sin rebote ornamental.

### VoiceOver

- Cada card expone título, sistema, favorito, estado iCloud y última partida en un solo elemento.
- D-pad: “Cruceta”; hint: “Desliza para cambiar de dirección”.
- A/B/Start/Select tienen labels independientes.
- Menú tiene label “Abrir menú”.
- Gameplay registra una acción accesible para abrir menú.
- Save state anuncia slot, tipo, fecha y selección.
- Los símbolos decorativos se ocultan.
- “Global” y “Personalizado” aparecen como texto, no solo color.
- Ningún gesto es la única ruta a una función.

### Dynamic Type

- Biblioteca y Settings soportan hasta AX5.
- Grid reduce columnas antes de truncar.
- Cards pueden crecer verticalmente.
- Toolbars mantienen labels accesibles aunque visualmente muestren símbolos.
- Gameplay no escala libremente las etiquetas dentro de controles; el usuario cambia el tamaño completo del control.
- `library-ax5` verifica reflow y targets.

### Contraste y diferenciación

- Favorito: estrella + label accesible.
- GB/GBC: texto.
- Error: símbolo + título + explicación.
- Personalizado: punto/símbolo + palabra.
- A/B: posición + letra + anillo, no solo color.

## 14. Verificación global

Antes de aceptar una pantalla:

- GBA, `.gba` y L/R solo aparecen donde este documento los describe (§GBA, G7–G8); no aparecen cheats.
- No aparece ninguna promesa de descargar artwork.
- La biblioteca se refiere a una carpeta, no a importar copias.
- Tab bar, toolbar, search, sheets y alerts se ven nativos de iOS 26.
- No hay `.blur`, `Material` o gradientes usados para falsificar Liquid Glass.
- No hay vidrio en portadas, cards, viewport o previews.
- Light y dark conservan contraste.
- La UI funciona con texto largo y AX5.
- Gameplay no invade safe areas con controles.
- Los controles permanecen legibles sobre un framebuffer blanco.
- Opacidad visual y hit frames son independientes.
- Todas las acciones destructivas indican exactamente qué se conserva.
- Los estados DEBUG no se compilan en Release.
- Ninguna captura o placeholder contiene material protegido.
- La UI no introduce red, ATS, `URLSession`, `Network` ni paquetes runtime.
