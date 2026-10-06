# Inventario de huecos de paridad Android ↔ iOS para A6, A7 y A8

Rama `codex/android-port`, commit `3d8e640`. Exploración de solo lectura (subagente Opus, 2026-10-06). Rutas abreviadas:
- `A/` = `android/app/src/main/java/com/joelbermudez/pocketgb/`
- `I/` = `ios/PocketGB/`

Descontado lo ya hecho en A1–A5 (`docs/ESTADO.md`, `docs/auditorias/A5-android-evidencia.md`).

**Resumen.**
- **Ajustes:** Android tiene 4 de las 9 pantallas (Apariencia, Biblioteca, Partidas y Acerca de). Las otras 5 solo muestran el aviso «Disponible en próximos hitos» (`A/ui/settings/SettingsScreen.kt:26-36, 62-64`).
- **Ajustes de juego:** no existe ningún modelo, ni global ni por juego.
- **Portadas y controles:** no hay portadas, y los controles usan siempre la disposición por defecto.
- **Mando:** no hay soporte de mando físico.
- **Accesibilidad:** es mínima.
- **Catálogo:** Android tiene 31 IDs. iOS tiene 51 IDs únicos en 83 entradas, y unos 32 de ellos no tienen equivalente.

---

## 1. Ajustes

### 1.1 Estructura de la pantalla principal
| Hueco | iOS | Android | Hito |
|---|---|---|---|
| Lista agrupada en tres secciones: «Juego» (Emulación, Controles, Audio, Pantalla), «Biblioteca y partidas» (Biblioteca, Partidas, Almacenamiento) y una final (Apariencia, Acerca de), con iconos | `I/Settings/SettingsView.swift:10-43` | Lista plana sin secciones ni iconos (`SettingsScreen.kt:26-36`) | A6 |
| Rutas Emulación, Controles, Audio, Pantalla, Almacenamiento y Licencias | `SettingsView.swift:48-61` | No existen en `A/app/AppDestination.kt:41-60` (solo Root, Appearance, Library, Saves y About) | A6 |

### 1.2 Modelo de ajustes globales
- **iOS:**
  - `GameplaySettingsData` (`I/Input/ControlsSettings.swift:32-82`) se guarda en UserDefaults con la clave `gameplaySettings`. Valida los valores al decodificar: opacidad del conjunto {30, 50, 70, 100}, escala entre 0,85 y 1,15, paleta ≤ 12 y volumen entre 0 y 1.
  - `GameplaySettings` (`:86-149`) aporta `update`, `move`, `resize`, `setOverrides` y `resetLayout`.
- **Android:** solo existen:
  - `A/settings/AppearancePreferences.kt`, con `themeMode` y `dynamicColor`.
  - `A/library/LibraryPreferences.kt`, con favoritos, `lastPlayed`, huellas, ocultos, vista y orden.
- **Hueco:** no hay almacén de ajustes de juego, aunque el SPEC Android §2.1 pide `settings/` con «preferencias globales y por juego». **Hito A6.**

### 1.3 Ajuste por ajuste
| Pantalla | Ajuste (iOS) | Archivo iOS | Android | Hito |
|---|---|---|---|---|
| Apariencia | Sistema / Claro / Oscuro | `AppearanceSettingsView.swift:29-53` | Sí: `AppearanceScreen.kt` + `ThemeMode` | — |
| Apariencia | Pie «El juego siempre se muestra sobre fondo oscuro», y que se cumpla | `:44-46` | Falta el texto. Además, el gameplay, sus hojas y diálogos usan el tema de la app, y los iconos de la barra de estado siguen el tema (`A/ui/theme/Theme.kt:35-43`). Es la observación abierta de A5 sobre la barra de estado en claro. | A6 |
| Apariencia | (solo Android) Color dinámico | — | `AppearanceScreen.kt:61-72` | Android de más |
| Biblioteca | Carpeta, «Cambiar carpeta», «Volver a escanear» | `LibrarySettingsView.swift:13-28` | Sí (`LibrarySettingsScreen.kt:111-137`), más «Olvidar carpeta» | — |
| Biblioteca | **Presentación: Vista (Cuadrícula/Lista) y Ordenar por (Nombre/Jugados recientemente)** | `:29-37` | No existe en Ajustes; solo en el menú de la biblioteca (`LibraryContent.kt:318-357`) | A6 |
| Biblioteca | Juegos ocultos con nombre de archivo y botón «Mostrar»; pies explicativos | `:38-64` | Lista ocultos, pero muestra `entry.id` en vez del nombre de archivo (`:171`) y no tiene pies | A6 |
| Controles | Opacidad en horizontal: 30/50/70/100 %, por defecto 70 | `ControlsSettingsView.swift:12-21`, `ControlsSettings.swift:34,52` | No existe. Alfa fija de 0,58 (`A/input/GameControlsOverlay.kt:20`) | A6 |
| Controles | Cruceta: Game Boy / Flechas separadas (`DpadStyle`) | `:22-31`; `ControlsSettings.swift:25-29`; dibujo en `ControlsOverlayView.swift:392-415` | No existe. Óvalo con «✦» (`GameControlsView.kt:97-115, 146`) | A6 |
| Controles | Tamaño: Pequeño 0,85 / Normal 1 / Grande 1,15 | `:33-35` | No existe. Hay escala por control en `ControlLayout.scales` (0,6–1,6, `ControlGeometry.kt:38-43`), pero no se usa: `GameControlsView.kt:58-63` llama siempre a `ControlLayout.defaults` | A6 |
| Controles | Mostrar: Siempre / Al tocar (se desvanecen a los 3 s) / Ocultos (pista de 3 s) | `:36-38`; `ControlsSettings.swift:6-21`; `ControlsOverlayView.swift:182-201` | No existe | A6 |
| Controles | Háptica (interruptor) | `:39` | El modelo tiene `hapticsEnabled` (`GameControlsView.kt:25`, `GameControlsOverlay.kt:17`), pero no hay ajuste y `GameplayScreen.kt:52,59` no lo pasa: siempre está activa | A6 |
| Controles | Restablecer disposición vertical y horizontal (deshabilitado si ya es la de fábrica) | `:41-53` | No existe | A6 |
| Pantalla | Vista previa 10:9 con arte generado | `DisplaySettingsView.swift:12-16, 34-61` | No existe | A6 |
| Pantalla | «Escala entera en horizontal» (por defecto activa; en vertical ocupa todo el ancho) | `:17-24`; `GameScreen.swift:29,37` | No existe. El nativo siempre usa escala entera, con respaldo, en ambas orientaciones (`cpp/native_session.c:174-199`, `A/video/IntegerViewport.kt`). No hay modo **Llenar** (SPEC Android §4.1), así que en vertical quedan bandas laterales | A6 |
| Pantalla | «Filtro: Píxeles nítidos» (informativo) | `:21` | No existe | A6 |
| Emulación | «Color en juegos de Game Boy» (`GB_MODEL_CGB`) | `EmulationSettingsView.swift:12-14`; `I/Emulator/CoreBridge.swift:84-91` | No existe. `nativeSessionLoad` usa `gb_options_default` y solo `unix_time` (`cpp/pocketgb_jni.c:200-225`). `NativeLibrary.kt:48` y `EmulatorSession.load` (`EmulatorSession.kt:77-85`) no reciben modelo ni paleta | A6 |
| Emulación | Paleta «Automática» + 1..12 (deshabilitada sin color) | `:15-19`; `Settings.swift:6-13` | No existe | A6 |
| Emulación | «Juegos de Game Boy Color: Siempre en color» y pie sobre estados entre modos | `:22-27` | No existe | A6 |
| Audio | Volumen de 0 a 1 | `AudioSettingsView.swift:12-23`; `I/Audio/AudioOutput.swift:113,138` | No existe. No hay ganancia en `cpp/audio_output.c` | A6 |
| Audio | «Sonar con el modo silencio» (`.playback` frente a `.ambient`) | `:24-31` | No existe. Es propio de iOS; `AudioFocusController.kt` pide `AUDIOFOCUS_GAIN`/`USAGE_GAME`. Falta decidir el equivalente Android | A6 (decisión) |
| Audio | «En segundo plano: Pausa» (informativo) | `:32-36` | No existe la pantalla; el comportamiento sí está desde A3 | A6 |
| Almacenamiento | Tamaños de «Partidas y copias», «Estados guardados» y «Portadas», medidos en segundo plano | `StorageSettingsView.swift:5-25, 36-45, 80-85` | No existe | A6 |
| Almacenamiento | «Borrar portadas» con confirmación; pie de que los ROM no cuentan | `:46-65` | No existe (tampoco hay portadas) | A6 |
| Partidas | Lista por juego que abre las copias, «Partida actual · Guardada <fecha>», «Copia más reciente/Copia n», pies y alerta de resultado | `I/Saves/SavesSettingsView.swift:4-129` | Funcional (J4): tarjetas con copias y Restaurar en línea (`SavesScreen.kt:125-185`). Faltan la fecha de la partida actual, los pies y el pulido visual (A5-plan J4: «pulido visual en A6»), y el margen inferior señalado en A5 | A6 |
| Acerca de | Versión (build), Núcleo, Consolas, Privacidad, «Juegos» (solo ROM propios), **Licencias de terceros** (Expat de SameBoy por las tablas de `cgb.c`) | `AboutView.swift:4-95` | `A/ui/about/AboutScreen.kt:39-42` solo tiene 4 textos: sin versión (aunque existe `buildConfig = true`) y sin licencias | A6 (licencias también en A8) |

---

## 2. Opciones por juego («Global» / «Personalizado»)

| Pieza iOS | Archivo | Android | Hito |
|---|---|---|---|
| `GameOverrides { colorForGameBoy?, compatPalette? }`, donde `nil` significa global | `I/Settings/Settings.swift:17-22` | No existe | A6 |
| Resolución global + juego con `emulation(for:)`, aplicada al abrir | `Settings.swift:31-37`; `I/App/AppState.swift:259-266` | No existe; además falta la vía JNI (sección 1.3, Emulación) | A6 |
| Hoja `GameSettingsView`: Color («Global (en color/sin color)» / En color / Sin color) y Paleta («Global (x)» / Automática / 1..12, deshabilitada sin color) | `I/Settings/EmulationSettingsView.swift:37-97` | No existe | A6 |
| Juego de GBC: cabecera «siempre en color» y sección deshabilitada; pie «Se aplica la próxima vez…» | `:75-80` | No existe | A6 |
| «Usar los ajustes globales», y borrar la entrada cuando todo vuelve a global | `:81-87`; `ControlsSettings.swift:135-139` | No existe | A6 |
| `SettingRowLabel`: «Global»/«Personalizado» como texto + símbolo, con accesibilidad combinada | `EmulationSettingsView.swift:100-119` | No existe | A6 |
| Accesos: botón «Ajustes» en el detalle y «Ajustes del juego» en el menú contextual | `I/Library/GameDetailsView.swift:156-162, 189` | `A/ui/details/GameDetailsScreen.kt` solo tiene Jugar, Favorito y Ocultar (`:195-226`); el menú contextual no tiene la opción (`LibraryContent.kt:385-404`) | A6 |

---

## 3. Biblioteca y detalle

| Hueco | iOS | Android | Hito |
|---|---|---|---|
| **Portadas locales**: último fotograma al cerrar, en `Artwork/<huella>.png` con escritura atómica en cola aparte; descarta fotogramas de un solo color; `removeAll` | `I/Library/GameArtworkStore.swift`; se guarda en `AppState.swift:337-366` (`closeGame` / `saveArtwork`) | No existe. `GameCard.kt:64-84` y `GameDetailsScreen.kt:247-265` («hasta A6 es un marcador») usan un icono. Hay `saves/FramePng.kt` y `FrameThumbnail.kt` para los estados | A6 |
| **Portada generada determinista** (FNV-1a, 4 colores, glifo 5×5, iniciales, chip GB/GBC, etiqueta «Sin captura, portada generada para…») y `GameArtworkView` 10:9 sin suavizado | `I/Library/GamePlaceholderView.swift:1-119` | No existe | A6 |
| **«Continuar jugando» con captura**: hasta 5 recientes, **solo con captura**; la primera de 240 pt y el resto de 170; botón «Continuar» encima; tocar la portada abre el detalle; «Jugado <relativo>»; en columna con texto grande | `LibraryView.swift:145-151, 319-378` | `RecentRow` (`LibraryContent.kt:470-493`): tarjetas de relleno de 156 dp, tocar juega directamente, sin captura ni fecha; límite de 10 (`LibraryPreferences.kt:122`) | A6 (la columna con texto grande, A7) |
| **Aviso «Cabecera dañada»** al abrir | `AppState.swift:287-288` | No existe en `GameDialog`/`GameNotice` (`A/game/GameplayViewModel.kt:41-61`). Solo aparece «Checksum de cabecera: Incorrecto» en el detalle (`GameDetailsScreen.kt:324`). `RomInfo.headerChecksumOk` ya existe (`RomInfo.kt:11`). Desviación 9 de A5 | A6 |
| **Alias** | No existe en iOS (grep vacío) | No existe | No es hueco |
| Estados vacío, cargando y error | `LibraryView.swift:44-82` | Paridad o más: `LibraryContent.kt:115-203` (NoFolder, Loading, Scanning, Ready vacío y 4 errores) | — |
| Progreso del escaneo «Buscando juegos… X de Y» | `LibraryView.swift:381-394`; `LibraryStore.swift:109-116` | `LibraryScanner.scan` ya tiene la función de progreso (`LibraryScanner.kt:44-47`), pero `LibraryViewModel.kt:199` la ignora; solo se ve una barra sin recuento | A6 |
| Resumen de escaneo con juegos nuevos (aviso + marca «Nuevo») | `LibraryStore.swift:151-165`; `RomEntry.isNew`; `GameCard.swift:71-75` | No existe: `RomEntry.kt:12-26` no tiene `isNew` | A6 |
| Carpeta no disponible con «Reintentar» además de «Elegir de nuevo» | `LibraryView.swift:55-65` | `PermissionRevoked` solo ofrece «Volver a elegir» (`LibraryContent.kt:167-173`) | A6 (menor) |
| Cabecera «Todos los juegos» + nombre de la carpeta | `LibraryView.swift:168-179` | No existe | A6 |
| Deslizar para reescanear (`.refreshable`) | `LibraryView.swift:137` | No existe (grep `PullToRefresh` vacío) | A6 |
| Menú «Más opciones»: Vista, Orden, **Volver a escanear, Cambiar carpeta** | `LibraryView.swift:94-112` | El `ViewMenu` solo tiene Vista y Orden (`LibraryContent.kt:318-357`) | A6 |
| **Menú contextual**: Jugar (si se puede), Ver detalle, Favorito, Estados (deshabilitado), **Ajustes del juego**, Ocultar (destructivo) y vista previa con portada | `GameDetailsView.swift:171-195`; `LibraryView.swift:229-235` | `LibraryContent.kt:385-404`: Ver detalle, Favorito y Ocultar. Faltan Jugar, Ajustes del juego, la vista previa y el estilo destructivo | A6 |
| Confirmar ocultar | `GameDetailsView.swift:198-213` | Existe (`A/ui/components/HideGameDialog.kt`), pero «Ocultar» no tiene estilo destructivo | A6 (menor) |
| **Búsqueda**: resultados en lista con «N resultados»; sin resultados con «Buscar en todos» si el filtro no es «Todos» | `LibraryView.swift:260-288` | `NoResults` sin acciones (`LibraryContent.kt:292-316`); los resultados conservan la vista y no hay recuento | A6 |
| Filtro vacío con «Ver todos» | `LibraryView.swift:183-191` | Sin acción | A6 |
| Filtros Todos/GB/GBC/Favoritos y orden | `LibraryPreferences.swift:5-30` | Sí (`FilterRow`, `LibrarySort`) | — |
| Cuadrícula/lista: más columnas o menos según el tamaño de texto | `LibraryView.swift:216-219` | Fijo en `GridCells.Adaptive(156.dp)` (`LibraryContent.kt:409`) | A7 |
| Contenido de la tarjeta: portada 10:9 atenuada si hay problema, insignia de estado, título en 2 líneas (sin límite con texto grande), línea con chip GB/GBC, estrella, «Nuevo» y «Sin jugar» / fecha relativa / «Partida <fecha>» | `GameCard.swift:5-106` | `GameCard.kt:43-109`: título en 1 línea, subtítulo «GBC · tamaño», sin última partida, sin «Nuevo» y sin portada | A6 |
| Fila de lista con miniatura | `GameListItem.swift` | Icono de 48 dp (`GameCard.kt:142-155`) | A6 |
| **Favoritos**: carril «Jugados recientemente» + cuadrícula + pies | `I/Favorites/FavoritesView.swift:48-104` | Sin carril (`FavoritesScreen.kt:78-84` pasa `recent = emptyList()`) | A6 |
| Transición con zoom de portada a detalle (fundido con Reducir movimiento) | `LibraryView.swift:302-315` | No existe | A6 (opcional) / A7 |
| Detalle: tres datos (Jugado / **Partida**, la fecha del `.sav` junto al ROM / Tamaño) | `GameDetailsView.swift:73-100` | Faltan «Partida» y `mirrorSaveDate` en `RomEntry` | A6 |
| Detalle: «Continuar» si hay `lastPlayed` **o** un `.sav` junto al ROM | `GameDetailsView.swift:109` | Solo mira `lastPlayedAt` (`GameDetailsScreen.kt:201`) | A6 |
| Detalle: fila Favorito / Estados / **Ajustes** y estrella en la barra superior | `GameDetailsView.swift:60-69, 139-168` | Solo botones de contorno, sin Ajustes ni estrella arriba | A6 |
| Detalle: filas que se apilan con texto grande | — | El comentario dice que se apilan, pero el código es una `Row` con pesos (`GameDetailsScreen.kt:340-362`) | A7 |
| (solo Android) Cartucho, checksums y SHA-256 en el detalle | — | `GameDetailsScreen.kt:313-338` | Android de más |

---

## 4. Gameplay

| Hueco | iOS | Android | Hito |
|---|---|---|---|
| Barra superior del juego (HUD): un botón de pausa (44 pt, «Pausa y opciones») y un botón de avance rápido que cicla ×1→×2→×4, con texto, valor accesible y háptica rígida; el control «menú» del overlay va oculto | `I/Gameplay/GameplayHUD.swift:6-48`; `AppState.swift:409-416`; `ControlsOverlayView.swift:141` | Tres chips ×1/×2/×4 siempre visibles, sin etiqueta «Avance rápido» y sin háptica (`GameplayScreen.kt:66-85`). La pausa es un control «MENÚ» dibujado dentro de los controles (`GameControlsView.kt:145-152`; `ControlGeometry.kt:53,63`) | A6 |
| Ocultación a los 3 s. En iOS se aplica a los **controles** en modo «Al tocar»; el HUD queda fijo por decisión del 30-09, y el criterio D5 «HUD se oculta a los 3 s» quedó sustituido (`GameplayHUD.swift:3-5`) | `ControlsOverlayView.swift:190-201`; `DesignTokens.swift:59` | No existe | A6 |
| Menú de pausa: título = nombre del juego, Continuar, Estados guardados, **Personalizar controles**, Salir (destructivo), pies y fotograma atenuado | `PauseView.swift:6-55`; `GameScreen.swift:44-47` | `PauseSheet.kt:76-140`: título genérico «Juego en pausa», sin «Editar controles» (comentario en `:76`) y sin pies | A6 |
| Confirmar carga «Guardar actual y cargar» / «Cargar sin guardar»; reemplazo con fecha; pie que distingue estados de partida | `I/Saves/SaveStatesView.swift:26, 37-62` | Un solo «Cargar» que siempre guarda antes en Automático; textos genéricos (`strings.xml:26-31`) | A6 (menor) |
| Etiqueta accesible por estado (ranura, tipo, fecha, seleccionado) | `SaveStateCard.swift:42-44` | No combinada | A7 |
| **Editor de disposición por orientación**: arrastrar para mover (ajustado a los bordes), tocar para elegir, − / + al 10 % (0,6–1,6), Restablecer y Listo; el juego sigue en pausa | `GameScreen.swift:105-180`; `ControlsSettings.swift:112-149`; `ControlsOverlayView.swift:276-300` | No existe | A6 |
| Apariencia de los controles: capa oscura localizada, opacidad que solo cambia lo visual, etiqueta ≥ 70 %, anillos de A/B cálido y frío, sólido con Reducir transparencia | `ControlsOverlayView.swift:305-566`; SPEC iOS §10.3 | Relleno `primaryContainer` al 58 % según el tema (también el color dinámico), sin capa oscura ni anillos A/B; el contraste sobre fotogramas claros no está garantizado (`GameControlsOverlay.kt:19-21`) | A6 (contraste, A7) |
| Háptica: impacto al pulsar A/B/Start/Select y **selección al cambiar de sector de la cruceta** | `I/Input/Haptics.swift:17-27`; `ControlsOverlayView.swift:258-266` | Solo un toque cuando entra un control nuevo (`GameControlsView.kt:121-124`); los cambios de sector de la cruceta no dan háptica | A6 |
| Pantalla entera / llenar | Sección 1.3, Pantalla | — | A6 |
| **Pantalla completa y zonas seguras**: barras ocultas, gestos del sistema diferidos; en vertical la imagen va bajo la Dynamic Island, no dentro; en horizontal los controles dentro del área segura | `GameScreen.swift:35-41, 67-68`; `PocketGBApp.swift:71` | No hay `WindowInsetsController`, ni exclusión de gestos, ni modo cutout propio (grep vacío). En vertical, `GameSurface` va arriba sin padding (`GameplayScreen.kt:54-58`); en horizontal los controles ocupan todo (`:50-52`), y `ControlGeometry` usa toda la vista como área (`GameControlsView.kt:58-63`) | A6 (el estado `gameplay-landscape`); verificación de cutouts en A7 |
| Gameplay siempre oscuro: hojas, diálogos y barra de estado | Captura `game-acid` en claro; `AppearanceSettingsView.swift:45` | Siguen el tema de la app (`Theme.kt:35-43`) | A6 |
| Avisos breves | Aviso arriba, 2,5 s (`AppState.swift:467-472`) | Snackbar (`GameplayHost.kt:77-79, 89-91`) | — (equivalente) |
| Guardado ante memoria baja | `AppState.swift:379-381` (`memoryWarning` → `requestFlush`) | No existe `onTrimMemory` (grep vacío) | A7 |
| **Rotación** (desviación 6 de A5): iOS no pausa, y emulación y audio continúan | SPEC iOS §10.5; `GameScreen.swift:22-23` | El manifiesto no tiene `configChanges` (`AndroidManifest.xml:11-14`), así que la actividad se recrea. `SessionLifecycleObserver.kt:24-29` pausa en `ON_PAUSE`/`ON_STOP` y `GameplayHost.kt:74-76` abre el menú. Sí publica máscara cero (`GameControlsView.kt:54-65`) | A7 |
| Avance rápido nativo ×2/×4 | `AppState.swift:409-416` | Hecho (`native_session.c:504-512`) | — |

---

## 5. Mando físico

- **iOS (`I/Input/GamepadInput.swift`):**
  - Mapeo por posición: botón derecho = A, inferior = B, Menu = Start, Options = Select.
  - La cruceta y el stick izquierdo dan la misma máscara: umbral 0,5, 8 sectores y nunca direcciones opuestas.
  - Detecta conexión y desconexión, y combina la máscara con la táctil mediante OR.
  - Con mando conectado se ocultan los controles táctiles (`ControlsOverlayView.swift:141`).
  - Tiene modo de demostración `-demoController` y 6 tests en `GamepadMappingTests` (incluido el OR y el ciclo de velocidad).
  - iOS **no** asigna el avance rápido al mando. El SPEC Android §4.3 pide más: cruceta/stick, A/B, Start/Select y avance rápido configurables.
- **Android:** solo existe la tubería:
  - `EmulatorSession.setPhysicalButtons` (`A/emulator/EmulatorSession.kt:179`).
  - `NativeLibrary.nativeSessionSetPhysicalButtons` (`NativeLibrary.kt:95`).
  - La combinación OR en nativo (`cpp/native_session.c:27-28, 238`).
- **grep** de `onKeyDown|onKeyUp|onGenericMotionEvent|InputDevice|KeyEvent|dispatchKeyEvent|onKeyEvent` en `android/app/src/main`: **0 resultados**.
- **Falta todo lo demás:** captura de teclas y ejes; detección de dispositivo y estado «mando conectado»; mapeo configurable con su ajuste; ocultar los controles táctiles; navegación de la interfaz con teclado o mando; tests de mapeo (solo `EmulatorSessionTest` y `GameSessionTest` llaman a `setPhysicalButtons`).
- **Hito:** A7, como indica `docs/superpowers/plans/2026-10-01-android-a3-audio-input-lifecycle.md:22`.

---

## 6. Accesibilidad y adaptabilidad (A7)

Resultados de búsqueda en `android/app/src/main`:
- Sin resultados: `fontScale` (solo existe en el catálogo debug, `DebugIntent.kt:26`), `LocalReduceMotion`, `WindowSizeClass`, `NavigationRail`, `currentWindowAdaptiveInfo`, `heading()`, `stateDescription`, `customActions`, `focusRequester`, `onKeyEvent`.
- Ninguna dependencia `material3-adaptive` (`app/build.gradle.kts`).
- Solo hay `semantics`/`contentDescription` puntuales: iconos, barra de progreso, cortina de apertura y tarjetas con `mergeDescendants`.

| Hueco | iOS | Android | Hito |
|---|---|---|---|
| TalkBack en los controles: un elemento por control («Cruceta» con su pista, A, B, Start, Select, «Abrir menú») y un gesto rápido que abre el menú | `ControlsOverlayView.swift:150-179` | Una sola `contentDescription` para toda la vista (`GameControlsView.kt:48-52`; `GameControlsOverlay.kt:30-32`). Sin nodos virtuales y sin acción accesible para el menú | A7 |
| Etiqueta única por tarjeta: título, sistema completo, favorito, nuevo, estado, última partida, más una pista | `GameCard.swift:131-159` | Se leen los textos sueltos («GBC · 1 MiB», «Favorito»); hay `onClickLabel`/`onLongClickLabel` (`GameCard.kt:53-63`) | A7 |
| Chips de velocidad sin nombre accesible | `GameplayHUD.swift:42-44` | `GameplayScreen.kt:75-84` | A7 |
| Fuente al 200 %: menos columnas, títulos sin truncar, filtro como menú, carril en columna | `LibraryView.swift:160-166, 216-219, 329-333`; `GameCard.swift:20, 59-63`; test `AccessibilityTests.testGridReflowsToOneColumnAtAX5` | Cuadrícula fija de 156 dp; títulos en 1 línea (`GameCard.kt:89, 133`); filas del detalle sin apilar; el script de capturas fuerza `fontScale 1.0` (`tools/android-screenshots.sh:41`) | A7 |
| Contraste | Reducir transparencia, controles sólidos ≥ 90 % (`ControlsOverlayView.swift:487-514`) | Sin esquemas de contraste medio o alto (`Color.kt:7,33`; `Theme.kt:24-33`); controles sin capa oscura | A7 |
| Reducir movimiento | `DesignTokens.swift:63`; `LibraryView.swift:302-315` | No hay transiciones propias; nada consulta la escala de animación ni las transiciones de `NavDisplay` | A7 (verificar) |
| Objetivos ≥ 48 dp | Test `AccessibilityTests.testLibraryLabelsAndTouchTargets` | Botones con `heightIn(min = 48.dp)` y `ControlGeometry.MIN_TOUCH_SIZE = 48` (`ControlGeometry.kt:140`), pero ningún test lo comprueba | A7 |
| Navegación con teclado o mando, orden de foco | D7 (validación manual) | Nada | A7 |
| Cutouts y zonas de gesto en el gameplay | SPEC iOS §10.2 | Sección 4 | A6 / A7 |
| `NavigationRail` y lista-detalle (SPEC Android §3.2) | — | `AppNavigationState.kt` no depende de la ventana, pero `PocketGBApp.kt:87-105` solo usa `NavigationBar`; sin clases de tamaño de ventana | A7 |
| Rotación sin pausa | Sección 4 | — | A7 |
| Atrás predictivo | — | `enableOnBackInvokedCallback="true"`; `BackHandler` en el gameplay | — (hecho) |

---

## 7. Catálogo de capturas

**IDs Android (31, `debug/DebugCatalog.kt:81-158` = `tools/android-screenshots.sh:21`):**
`library-empty`, `library-grid`, `library-list`, `library-search`, `library-error`, `library-loading`, `library-access-error`, `library-detail`, `library-detail-problem`, `favorites-empty`, `favorites`, `settings-main`, `settings-library`, `appearance`, `about`, `native-video`, `gameplay-controls`, `gameplay-fast-forward`, `library-detail-played`, `pause-sheet`, `pause-dialog`, `states-sheet`, `states-dialog`, `exit-save-failed`, `exit-risk`, `save-warning`, `open-error`, `saves-settings`, `save-problem`, `states-rescue`.

**Equivalencias aproximadas:**

| iOS | Android |
|---|---|
| `library-no-folder` | `library-empty` |
| `settings-appearance` | `appearance` |
| `settings-about` | `about` |
| `library-folder-unavailable` | `library-error` / `library-access-error` |
| `save-data-error` | `save-warning` |
| `search-no-results` | `library-search` |
| `game-details` | `library-detail` |
| `gameplay-portrait` | `gameplay-controls` |
| `gameplay-pause` | `pause-sheet` |
| `save-states` | `states-sheet` |
| `settings-saves` | `saves-settings` |
| `library-grid`, `library-list`, `favorites`, `settings-main`, `settings-library`, `gameplay-fast-forward` | mismo ID |

Parciales (falta contenido, no el ID): `library-grid` (sin carril ni portadas), `favorites` (sin recientes), `game-details` (sin portada ni ajustes), `library-rom-error` (solo dentro de `library-list` y `library-detail-problem`), `game-acid` en claro, y `game-paused`.

**IDs iOS sin equivalente Android:**

| ID iOS | Hito |
|---|---|
| `launch` (Android tampoco tiene icono de app: no hay `mipmap` ni `android:icon`) | A6 (icono, A8) |
| `library-empty` (carpeta válida sin ROM; el `library-empty` de Android es «sin carpeta») | A6 |
| `game-acid` horizontal / `gameplay-landscape` | A6 |
| `library-cloud-pending`, `library-cloud-downloading` (de iCloud; el análogo Android sería el documento remoto pendiente, `RomProblem.REMOTE_UNAVAILABLE`) | A6 |
| `library-scan-progress`, `library-scan-summary` | A6 |
| `library-continue` | A6 |
| `search-active`, `search-results` | A6 |
| `game-context-menu`, `remove-game-confirm` | A6 |
| `gameplay-landscape-clear`, `gameplay-landscape-hidden` | A6 |
| `customize-controls-portrait`, `customize-controls-landscape`, `customize-controls-size` | A6 |
| `settings-controls`, `settings-display` | A6 |
| `load-state-confirm`, `replace-state-confirm` | A6 |
| `gameplay-portrait-arrows`, `gameplay-landscape-arrows` | A6 |
| `settings-audio`, `settings-emulation`, `settings-storage`, `game-settings` | A6 |
| `gameplay-controller` | A7 |
| `gameplay-reduce-transparency`, `library-reduce-transparency` (el análogo Android sería contraste alto) | A7 |
| `library-ax5` (fuente al 200 %) | A7 |

**Infraestructura del catálogo:**
- El SPEC Android §8 pide claro, oscuro y **varios colores dinámicos**, pero el script fija `dynamicColor false` y `fontScale 1.0` (`android-screenshots.sh:40-41`).
- Solo rota las pantallas `*-dialog` (`:35`).
- Faltan parámetros debug equivalentes a los de `D-README §2.5`: opacidad, visibilidad, estilo de cruceta, mando, reducir movimiento y contraste.
- No hay un test que cruce el catálogo con el script, como hace `AccessibilityTests.testCatalogCoversEverySpecScreenWithoutContradictions` en iOS.

Hitos: A6, y A7 para las variantes de accesibilidad.

---

## 8. Tests

**JVM (`src/test`, 241):**
`ManifestPolicyTest` 6, `AppNavigationStateTest` 5, `ControlGeometryTest` 3, `TouchInputEngineTest` 2, `LibraryModelsTest` 3, `LibraryPreferencesTest` 15, `LibraryScannerTest` 14, `LibraryViewModelTest` 25, `AtomicFileTest` 12, `AtomicSaveFaultInjectionTest` 5, `FrameThumbnailTest` 4, `MirrorChannelTest.kt` 18 (13 + `MirrorChannelRobustnessTest` 5), `ProcessKillTest` 1, `SaveConcurrencyTest` 3, `SaveCoordinatorCloseTest` 6, `SaveCoordinatorTest` 15, `SaveHardeningTest` 11, `SaveOpeningTest` 19, `SaveResolutionTest` 21, `SaveStoreTest` 19, `SavesBrowserTest` 10, `SavesIndexTest` 3, `SramFlushPolicyTest` 7, `StateStoreTest` 9, `AppearancePreferencesTest` 3, `IntegerViewportTest` 2.

**Instrumentados (`src/androidTest`, 191):**
`AppShellTest` 2, `DebugCatalogTest` 8, `SessionLifecycleObserverTest` 2, `NativeAudioTest` 3, `CoreBridgeTest` 4, `EmulatorSessionHandleLockTest` 3, `EmulatorSessionTest` 7, `NativeLibraryTest` 1, `NativeSaveBridgeTest` 19, `FingerprintOwnershipLauncherTest` 3, `GameSessionHardeningTest` 15, `GameSessionTest` 13, `GameplayLifecycleTest` 3, `GameplayUiTest` 8, `GameplayViewModelDestroyTest` 4, `OpenFromLibraryTest` 8, `SaveCyclesTest` 1, `GameControlsViewTest` 2, `SafLibraryTest` 14, `SafMirrorTest` 34, `SaveFilesOnAndroidTest` 2, `LibraryUiTest` 32, `SavesSettingsUiTest` 2, `GameSurfaceTest` 1.

**Áreas de A6 sin test.** La referencia iOS es `SettingsTests` 6 y `ControlsLayoutTests` 16:
- Modelo y persistencia de los ajustes de juego: validar opacidad, recortar la escala, disposición separada por orientación, tamaño por control. Android solo tiene 3 + 2 tests de geometría y de motor.
- Ajustes por juego: volver a global y borrar la entrada vacía.
- Que modelo y paleta lleguen al núcleo (JNI).
- Almacén de portadas (fotograma en blanco, escritura atómica, `removeAll`) y estabilidad de la semilla de la portada generada.
- Recuento de almacenamiento.
- Aviso de cabecera dañada.
- Editor: mover, redimensionar y restablecer.
- Modos de visibilidad y desvanecimiento a los 3 s; estilo de cruceta; interruptor de háptica.
- Modo Llenar (`IntegerViewportTest` solo cubre el entero).
- Ciclo del HUD de velocidad.
- Navegación a las 5 pantallas nuevas de Ajustes (`AppShellTest` solo cubre Apariencia).
- Progreso y resumen del escaneo; carril de recientes en Favoritos; acciones nuevas del menú contextual.
- Test de cobertura del catálogo.

**Áreas de A7 sin test:**
- Mapeo del mando (iOS `GamepadMappingTests` 6; Android ninguno).
- Rotación sin pausa: `GameplayLifecycleTest.recreateDoesNotCloseTheSession` solo comprueba que la sesión sobrevive.
- Semántica de TalkBack en los controles: `GameControlsViewTest` solo cubre máscaras y háptica.
- Objetivos ≥ 48 dp.
- Reflujo con fuente al 200 %.
- Contraste y reducir movimiento.
- Foco con teclado o mando.
- Área segura y cutouts en la geometría (el test de `ControlGeometryTest` usa toda el área).
- Tamaño de ventana y `NavigationRail`.

---

## 9. Documentación y cierre (A8)

| Documento | Problema | Hito |
|---|---|---|
| `android/README.md` | Describe solo A1–A3 (línea 3). Dice «18 PNG — nueve pantallas» (`:47`), cuando ahora son 31 × 2. No menciona A4/A5, `tools/android-save-kill-test.sh` ni los modos debug `save-stress` y `save-verify` | A8 |
| `docs/05-android-spec.md` | Cita `jni_bridge.c` (`:17-18, 28`); los fuentes reales son `pocketgb_jni.c`, `native_session.c`, `audio_output.c` y `audio_ring.c`. Dice «A1 está implementado; A2 integra…» (`:57`). Instala `app-release.apk` firmado (`:50-53`), pero no hay `signingConfigs`. La fila «Mandos» (`:43`) aún no se cumple | A8 |
| `docs/02-arquitectura.md` | «Android (Kotlin, futuro)» (`:5`); la sección de hilos es solo de iOS (`:40`); no aparecen `SaveCoordinator`, `GameSession`, `FingerprintOwnership` ni `OrphanSessionRegistry` | A8 |
| `docs/06-testing.md` | No menciona Android: ni JVM e instrumentados, ni prueba de cierre forzado, ni catálogo, ni emulador | A8 |
| `docs/hitos/README.md` | No tiene filas A1–A8 (`:5-21`) | A8 |
| `README.md` (raíz) | «Android queda documentado para después» (`:3, 7`) | A8 |
| `docs/00-vision.md` | «Android en v1: solo se documenta» (`:20`) y «Android (futuro)» (`:29`) | A8 |
| `docs/07-instalacion-iphone.md` | No hay equivalente para Android (instalación, almacén de claves fuera del repo) | A8 |
| Plan y verificación | `docs/superpowers/plans/` solo cubre A1–A3 (más `docs/diseno-android/A5-plan.md`). No hay plan de A6/A7/A8 ni un `VERIFICACION.md` Android para registrar la revisión del catálogo | A6 / A8 |
| Release | `app/build.gradle.kts`: sin firma, `versionCode = 1`, `versionName = "0.1.0"`; sin icono de app (el manifiesto no tiene `android:icon` y no hay `mipmap`); faltan las licencias de SameBoy en la app | A8 |
| Textos | 47 literales `Text("…")` en 9 archivos de interfaz, cuando A5 usa `strings.xml` | A8 (coherencia) |
| `docs/ESTADO.md` / `A5-android-evidencia.md` | Pendiente la prueba manual de A5 en el teléfono (J11, carpeta real, Rojo/Amarillo, recuperación desde el espejo, restaurar) | Antes de A6 / A8 |

---

## 10. Riesgos residuales de A5 a cerrar antes de A8

**A5V6-H1: un `Error` dentro del manejador de reintentos del hilo de reparación suelta el «hold».**
- `A/game/GameSession.kt:371-429` es `repairPrevious` y crea el hilo `pocketgb-save-repair`. Lo invoca `loadState` en `:343-351`, cuando el rollback falla.
- El manejador `catch (error: Throwable)` está en `:405-415`: `Log.w` en `:406`, bucle de espera en `:407-412` y `coordinator.submitOnSaveThread` en `:414`. Este último solo atrapa `IOException`.
- Cualquier `Throwable` lanzado *dentro* de ese manejador sale del `while (true)` (`:383`) y llega al `finally` de `:417-423`.
- Ese `finally` ejecuta `dropLease()` en `:419`. Si era el último hold, `lease?.close()` libera la huella (`:172-174`; `A/saves/FingerprintOwnership.kt:33-35, 44-46`) sin haber confirmado la reparación.

**A5V6-H3: un fallo al crear o arrancar el hilo de reparación deja el hold sin liberar.**
- `holdLease()` se llama en `GameSession.kt:373` (definido en `:168-170`).
- Pueden lanzar después:
  - `coordinator.submitOnSaveThread` en `:375`. Solo atrapa `IOException`; la API, en `SaveCoordinator.kt:301-305`, convierte solo `RejectedExecutionException`.
  - La construcción de `Thread(...)` en `:376`, o `.start()` en `:424` (OOM «pthread_create», `SecurityException`).
- Ninguna de esas rutas llama a `dropLease()`. `leaseHolds` no vuelve a 0 y la huella queda bloqueada hasta que muera el proceso. La excepción sale además de `loadState` sustituyendo a `RollbackFailed`.

**A5V7-H1: si `executor.shutdown()` lanza antes de cerrar el executor, el reaper espera para siempre.**
- En `A/saves/SaveCoordinator.kt:316-330` (`shutdown`), la línea `:317` marca `closed`. Si `executor.shutdown()` en `:318` lanza (por ejemplo `SecurityException`), no se ejecutan ni `shutdownNow` (`:320`) ni el `join` (`:325-328`).
- El hilo del executor (`:84-90`) sigue vivo por la tarea periódica programada en `:136`.
- `GameSession.tryClose` atrapa el error en `:523-528` y lo trata como `SaveThreadStuck`. El reaper `pocketgb-save-reaper` (`:534-552`) entra en bucle sobre `coordinator.awaitThreadExit()` (`:540-547`), que es `thread.join()` **sin plazo** (`SaveCoordinator.kt:340-343`).
- Por eso `session.close()` y `dropLease()` (`:548`) nunca se ejecutan: se retienen el handle nativo y la huella.
- Aunque el registro quite la sesión de `owned`, el lease sigue retenido. Llegan aquí:
  - `SaveCoordinator.close()` (`:346-348`).
  - `OrphanSessionRegistry.afterClosed` (`A/game/OrphanSessionRegistry.kt:77-83`).
- El test de A5V6-H2 (`GameSessionHardeningTest.aFailingCoordinatorShutdownNeverReleasesTheLeaseWhileTheSaveThreadIsAlive`, con el cierre inyectable de `GameSession.kt:153-155`) solo cubre el fallo *después* de pedir el cierre.

**Ruta cercana documentada, que no es uno de los tres riesgos.**
- `OrphanSessionRegistry.schedule` (`:58-64`) se traga `RejectedExecutionException`. La sesión quedaría retenida para siempre, aunque el registro no se cierra nunca.
