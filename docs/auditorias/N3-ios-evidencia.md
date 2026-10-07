# N3 🍎 · Evidencia de biblioteca y detalle adaptables (iOS: N3a + N3b)

> **Auditoría Opus: APROBAR CON CAMBIOS** ([N3-ios-opus.md](N3-ios-opus.md)); respuesta en [N3-ios-respuesta.md](N3-ios-respuesta.md). Tras ella cambió la colocación de las herramientas en horizontal (decisión del orquestador): en reposo van en la barra de navegación (paneles hacia abajo) y el grupo flotante solo aparece al desplazar, en la fila de la burbuja. Las secciones 1–6 describen la primera versión; la **§8** recoge la verificación final.

Rama `n3-ios-adaptable` (desde `n1-ios-identidad` en `b216c2a`; después se fusionó `siguiente-nivel` en `da51a57`, con N1 iOS cerrado y N2 iOS), 2026-10-07. Plan: [hitos/N-README.md](../hitos/N-README.md) §1 (peticiones 1, 6, 7 y 15) y §4 N3. Reglas de diseño: [diseno/SPEC.md](../diseno/SPEC.md) §15. Guía para Joel: [guia/biblioteca.md](../guia/biblioteca.md).

## 1. Qué cambia

### N3a · Título, detalle, proporciones e información técnica
| Pieza | Archivo | Qué hace |
|---|---|---|
| Título legible | `Library/LibraryView.swift`, `Favorites/FavoritesView.swift` | En altura compacta (horizontal): título en línea (`InlineTitleInCompactHeight`) y `scrollEdgeEffectStyle(.hard, for: .top)`. En vertical no se toca el modo del título (ver §6: con `.automatic` explícito desaparecía el buscador). Velo inferior (`ControlScrim` al 45 %) bajo «Continuar» en el carril. En horizontal, título de sección fijado con el fondo opaco del contenido (sin vidrio), que también tapa la franja bajo la barra. |
| Detalle adaptable | `Library/AdaptiveLayout.swift` (`DetailLayout`), `Library/GameDetailsView.swift` | Por espacio (`GeometryReader`), nunca por modelo: dos columnas si ancho > alto o ancho ≥ 600 pt. Izquierda: imagen entera con la proporción de la consola, como mucho la mitad del ancho útil y el alto disponible. Derecha, con su propio scroll: cabecera, **Jugar/Continuar justo bajo el título**, estadísticas, cable, acciones, información técnica y ocultar. En vertical, imagen ≤ 45 % del alto, centrada. Favorito/Estados/Ajustes pasan a columna si no caben (`ViewThatFits`) y las estadísticas, a columna con AX. |
| Proporción por consola | `Library/AdaptiveLayout.swift` (`ArtworkStyle`), `Library/GamePlaceholderView.swift`, `Saves/SaveStateCard.swift`, `Saves/SaveStatesView.swift` | `.console` (detalle, vista previa del menú contextual): marco 10:9 o 3:2 y captura entera. `.card` (cuadrícula, lista, carriles): marco 10:9 común y la captura lo rellena centrada **sin deformarse** (una GBA pierde algo de los lados; es el «Encaje» que N5 ya prevé). `SaveStateCard`: proporción de su miniatura (240×160 en GBA) o, vacía, la de la consola del juego abierto. |
| Información técnica | `Library/GameTechnicalInfo.swift`, `Emulator/CoreBridge.swift`, `Emulator/GBACoreBridge.swift` | `DisclosureGroup` plegado: título de cabecera; cartucho/MBC (GB, nombres como Android `CartridgeNames`) o tipo de partida detectado y código del juego · fabricante · versión (GBA); ROM; partida guardada (RAM · batería · reloj, o «Sin RAM»/«Sin partida»; EEPROM «512 B u 8 KiB»); checksum de cabecera y global (solo GB, «No coincide (la consola real lo ignora)»); SHA-256 completo seleccionable con «Copiar huella». Se lee **solo al desplegar**, fuera del hilo principal, con una instancia propia del núcleo sin sesión ni audio (`GameTechnicalInfoLoader`: lectura coordinada y acotada con `LibraryScanner.readROM` y `CoreBridge`/`GBACoreBridge.loadROM`), y **nunca** de un archivo sin descargar (`RomFingerprint.isLocallyAvailable`). `RomInfo` gana campos de la API pública existente (`rom_bytes`, `global_checksum_ok`, los 32 bytes del SHA-256, `save_type`, `game_code`, `maker_code`, `version`). `core/` y `gba/` no se tocan. |

### N3b · Biblioteca en horizontal
| Pieza | Archivo | Qué hace |
|---|---|---|
| Sin buscador ni filtros arriba | `Library/LibraryView.swift` | En altura compacta no hay segmentado; `.searchable` (bajo el título, `.searchToolbarBehavior(.minimize)`) solo existe mientras se busca (`LibrarySearchModifier`): la lupa lo añade y lo enfoca; al cancelar desaparece. |
| Grupo flotante | `Library/LibraryLandscapeTools.swift` (`LibraryToolsGroup`, `LibraryToolsState`), `Library/LibraryRootView.swift` (`LibraryToolsOverlay`) | Cuatro botones redondos de vidrio (48 pt, en un `GlassEffectContainer`): Buscar, Filtros, Categorías y Vista/Orden. Vive **encima del `TabView`**, en coordenadas de la ventana, a la derecha: con la barra de pestañas encogida, en la fila de su burbuja; desplegada, 8 pt por encima de ella. La barra no publica si está encogida: `LibraryToolsPosition` lo deduce como ella (`onScrollDown`: se encoge al bajar; al subir el grupo sube «por si acaso», nunca la pisa) con las distancias medidas en iOS 26.5 (centro de la burbuja a 43 pt del borde inferior y barra desplegada desde 65 pt, iguales en SE y 17 Pro). |
| Paneles hacia arriba | `LibraryLandscapeTools.swift` | Popover anclado al botón (`arrowEdge: .bottom`, `presentationCompactAdaptation(.popover)`), 400 pt de ancho, opciones en cápsulas que fluyen en filas (`ChipFlow`); la elegida, con relleno de acento y marca. Alto máximo (`LibraryToolsPosition.panelMaxHeight`): hasta la barra de navegación, o hasta el título de sección si el panel le pasaría por encima; con más opciones, se desplaza dentro. |
| Categorías | `Library/AdaptiveLayout.swift` (`LibraryCategory`) | Carpetas de primer nivel de `folderPath` con su número de juegos, «Sin categoría» (raíz) y «Todas». Se combinan con filtro y búsqueda. Extensiones propias de `LibraryQuery`/`LibraryPreferences` en el archivo de N3: **no se toca `LibraryPreferences.swift`**. |
| Vertical | `LibraryView.swift` | Igual que antes más la lupa en la barra («como una opción más») y «Categoría» en el menú «…» (así una categoría elegida en horizontal se ve y se quita en vertical; el título de sección la nombra). |
| Favoritos | `FavoritesView.swift` | Título en línea y borde duro en horizontal. Sin grupo: Favoritos no tiene búsqueda ni filtros. |

### Hallazgo corregido fuera del alcance previsto
La captura `save-states-gba` mostró la ranura recién guardada de un juego GBA como **«Dañado»**. `StateStore.entry` solo aceptaba la firma `PGBS` del núcleo GB; los estados GBA empiezan por `PGBA` (`gba/src/gba_state.c`). Consecuencias: estados GBA marcados como dañados y `automaticEntry` nunca válido tras reiniciar la app, así que **«Continuar» no salía para GBA** (justo después de cerrar el juego, en la misma sesión, sí salía porque se marcaba en memoria, y al abrirlo fallaba con «No se pudo continuar»). Corrección de una línea (`StateStore.signatures`) con test `gameBoyAdvanceStatesAreNotReportedAsDamaged`. El núcleo sigue validando firma, versión y CRC al cargar; no toca la ruta de la partida (SRAM).

## 2. Decisión: `tabViewBottomAccessory` frente a grupo propio
Se probó primero `tabViewBottomAccessory(isEnabled:)` (iOS 26.1, `#available`) activo solo en Biblioteca y en horizontal, con los cuatro botones a la derecha del accesorio (código de prueba en DEBUG, retirado después). Capturas en el scratchpad de la sesión (`…/scratchpad/n3/evidencia/`):

| Captura | Qué se vio |
|---|---|
| `accesorio-desplegado.png` (17 Pro, arriba) | Con la barra desplegada, el accesorio es una barra de vidrio a todo el ancho **encima** de la tab bar: ≈ 50 pt más de los ≈ 400 del horizontal, tapando el carril. Justo lo que Joel no quiere («se ve muy mal»). |
| `accesorio-encogido.png` y `accesorio-encogido-se.png` (con scroll) | Encogida, la burbuja queda a la izquierda y el accesorio es una cápsula larga **casi vacía** sobre las tarjetas, con los botones al fondo. |
| `accesorio-filtros-sin-panel.png` | Tocar «Filtros» dentro del accesorio **no abrió el popover** en la prueba. |

**Decisión: grupo de vidrio propio** (`GlassEffectContainer`), solo los cuatro botones, a la derecha, alineado con la burbuja y por encima de la barra desplegada. Primero vivió dentro de la pestaña y falló de forma sutil: en la fila de la burbuja el área de la pestaña ya terminó y los toques pasaban a la tarjeta de debajo (la captura de un panel acabó abriendo el detalle de jsmolka ARM); por eso vive encima del `TabView`. Lo cubre `AdaptiveLibraryUITests.testToolsAlignWithTheTabBarWithoutOverlapping` (tocar Filtros con la barra encogida abre su panel y no el detalle).

Antes/después: `antes-biblioteca-horizontal.png` (título grande, buscador, segmentado y carril de 240 pt: casi no se ven juegos) y `antes-detalle-horizontal.png` (imagen a todo el ancho, la información debajo).

## 3. Tests (TDD)
`AdaptiveLibraryTests` (Swift Testing, 15) y `AdaptiveLibraryUITests` (XCTest, 4):

| Test | Comprueba |
|---|---|
| `landscapeUsesTwoColumnsWithTheWholeImageOnScreen` | En horizontal (tamaños de SE, 17 Pro y Pro Max), dos columnas, imagen entera en alto, ≤ mitad del ancho útil, columna de información ≥ 300 pt y proporción exacta (10:9 y 3:2). |
| `portraitImageTakesAtMost45PercentOfTheHeight` | Una columna, imagen ≤ 45 % del alto y ≤ ancho útil; en el SE el 45 % estrecha la imagen GB. |
| `wideAreasUseTwoColumnsEvenInPortrait` | ≥ 600 pt → dos columnas aunque sea más alto que ancho; 599 → una; sin medir → una columna sin tamaños negativos. |
| `artworkKeepsTheConsoleProportion` | `.console` 10:9/3:2; `.card` común; miniatura por píxeles (240×160 → 3:2) o por consola. |
| `gameBoyInfoComesFromTheCore` | ROM sintético MBC3+RTC+RAM+batería por el núcleo real: filas y valores («MBC3 + reloj + RAM + batería», «64 KiB», «32 KiB · batería · reloj», checksums correctos) y SHA-256 de 64 hex cuyo prefijo es la huella de `RomFingerprint`. |
| `gameBoyGlobalChecksumMismatchIsAWarningWithText` | Checksum global que no cuadra → texto explicativo y aviso; «Solo ROM», «Sin RAM». |
| `gameBoyAdvanceInfoShowsSaveTypeCodeAndClock` | GBA Flash 128 + RTC por código (`BPEE`): «Flash 128 KiB (detectado)», «BPEE · 01 · v2», «128 KiB · reloj», sin checksum global; EEPROM → «512 B u 8 KiB»; sin cadena → «Sin partida». |
| `rejectedAndMissingROMsExplainWhy` | MBC desconocido → el texto del núcleo; archivo inexistente → no se lee (como uno sin descargar); archivo local → datos. |
| `byteFormatUsesBinaryUnitsAndDecimalComma` | «512 B», «1 KiB», «1,5 KiB», «8 MiB», «32,1 KiB»; cartucho desconocido «Desconocido (0x77)». |
| `categoriesAreTheFirstLevelFolders` | Árbol sintético (raíz, `Pokémon/1ª…`, `Pokémon/2ª…`, `Kirby/`, `Acción/A/B/C/D/`): categorías ordenadas con «Sin categoría» al final; filtro por categoría con subcarpetas, combinado con consola y búsqueda; mismo nombre en NFD = misma categoría. |
| `hiddenGamesDoNotCreateCategories` | Un juego oculto no crea categoría. |
| `gameBoyAdvanceStatesAreNotReportedAsDamaged` | Estado real del núcleo GBA → no «Dañado» y «Continuar» válido; firma ajena → «Dañado». |
| `toolsFollowTheTabBarMinimizeRule` | Regla de encogido (bajar/subir/arriba/sin cambio) y distancias: grupo centrado con la burbuja (centro 359 en 17 Pro) y 8 pt por encima de la barra desplegada. |
| `continueRowUsesTheGridColumnWidthInLandscape` | Columnas y ancho de columna (4 de 170,5 pt en 17 Pro; 3 en SE; 2 con AX5). |
| `panelsNeverCoverTheSectionTitle` | Alto del panel hasta la barra si no se solapa con el título; hasta el título si le pasaría por encima; mínimo dos filas. |
| UI `testLandscapeToolsFilterByConsoleAndCategory` | En horizontal no hay segmentado ni campo de búsqueda; los cuatro botones son tocables y ≥ 44 pt; Filtros › GBA deja solo GBA y el panel queda **por encima** del botón; Categorías › Blargg deja solo Blargg y el título de sección dice «Blargg». |
| UI `testLandscapeSearchOpensFromTheGroupAndGoesAwayOnCancel` | La lupa del grupo abre la búsqueda, «cpu» filtra, cerrar la quita de arriba y vuelve el grupo. |
| UI `testPortraitKeepsTheSegmentedFilterAndAddsTheSearchButton` | En vertical siguen el segmentado y el buscador bajo el título; la lupa de la barra abre la búsqueda con teclado. |
| UI `testToolsAlignWithTheTabBarWithoutOverlapping` | Marcos reales: arriba el grupo va encima de las pestañas; con scroll baja a la fila de la burbuja (centro ±6 pt) y a su derecha, y Filtros abre su panel (no el detalle de debajo); al subir, el grupo sube. |

Salidas reales: §5.

## 4. Catálogo de capturas
Bloque N3 al final de `screens.txt` (23 líneas; `-demoLibrary adaptive`: 13 juegos libres en carpetas, CGB-ACID2 con captura casi blanca y arm.gba con captura de 240×160; `-demoROMDir $FIXTURES` hace que la información técnica lea los ROMs de prueba reales). `ScreenshotTests` gana `-uiDrag x0,y0,x1,y1` (en horizontal `-uiSwipeUp` no mueve la lista) y `-uiTap <id>` (comprueba que es tocable y lo toca). Se adoptó idéntico el cambio de N2 en `ScreenshotTests` (filtro por comas), que ya está en `siguiente-nivel`.

Catálogo completo en iPhone 17 Pro (`tools/ios-screenshots.sh`): **160 PNG** (137 anteriores de `siguiente-nivel` + 23 de N3), `testScreenCatalog passed (1246.044 seconds)`. El bloque N3 también en **iPhone SE (3.ª gen)** (simulador creado con el runtime iOS 26.5: el que venía instalado era iOS 17.5 y la app exige 26) y **iPhone 17 Pro Max**: 23 + 23 PNG. Carpetas: `/private/tmp/claude-501/-Users-joelbermudez-Documents-workspace-pocketgb/6cc26fde-813f-4136-be84-5cf079cf4e60/scratchpad/n3-full`, `…/n3-se`, `…/n3-promax` (y `view/` con las horizontales giradas a su orientación real). Revisadas a ojo (herramienta Read) en hojas de contacto:

| Captura | SE | 17 Pro | Pro Max | Lo que se ve |
|---|---|---|---|---|
| `library-landscape` (claro/oscuro) | ✅ | ✅ | ✅ | Título «Biblioteca» en línea; sin buscador ni segmentado; carril con tarjetas del ancho de una columna (velo bajo «Continuar» sobre la captura blanca); grupo de 4 botones a la derecha **por encima** de la barra desplegada, sin tocarla. |
| `library-landscape-scrolled` | ✅ | ✅ | ✅ | Burbuja a la izquierda y el grupo a la derecha **en su misma fila**; «Todos los juegos» fijado bajo la barra. |
| `library-landscape-search` | ✅ | ✅ | ✅ | Campo de búsqueda arriba con la «X»; el grupo se oculta; en el SE sale el teclado. |
| `library-landscape-filters` / `-categories` / `-view` | ✅ | ✅ | ✅ | Popover hacia arriba con su flecha sobre el botón; cápsulas, la elegida con marca; Categorías con las 6 opciones y sus números (Todas 13, Blargg 4, Game Boy Advance 2, Mooneye 2, Pruebas 3, Sin categoría 2); el título de sección queda visible encima. |
| `library-landscape-category` | ✅ | ✅ | ✅ | Título de sección «Blargg» y solo sus juegos. |
| `library-landscape-white` (claro/oscuro) | ✅ | ✅ | ✅ | Con la portada casi blanca del carril bajo la barra, «Biblioteca» se lee sobre el borde duro (oscuro en modo oscuro). |
| `library-landscape-ax5` | ✅ | ✅ | ✅ | Texto AX5: tarjeta del carril del ancho de una columna, grupo con símbolos de tamaño fijo dentro de sus círculos. |
| `library-white` (vertical, oscuro) | ✅ | ✅ | ✅ | Título en línea sobre tarjetas al desplazar (borde suave de siempre). |
| `favorites-landscape` | ✅ | ✅ | ✅ | «Favoritos» en línea; tarjetas 10:9. |
| `game-details-gb` / `-gba` (vertical) | ✅ | ✅ | ✅ | Imagen ≤ 45 % del alto (en el SE la GB se estrecha y centra; la GBA, 3:2 entera); Jugar/Continuar a la vista. |
| `game-details-gb` / `-gba` (horizontal) | ✅ | ✅ | ✅ | Dos columnas: imagen entera (10:9; 3:2 con el marco del arte de prueba visible en los cuatro lados: no se recorta ni estira) y Jugar/Continuar bajo el título sin desplazar. |
| `game-details-technical` / `-gba-technical` | ✅ | ✅ | ✅ | Datos reales de los ROMs de prueba: dmg-acid2 «Solo ROM · 32 KiB · Sin RAM · checksums correctos · 464e14b7…»; arm.gba «Sin partida (detectado) · 1337 · JS · v0 · 8,6 KiB · 77ee8866…». |
| `game-details-ax5` (vertical y horizontal) | ✅ | ✅ | ✅ | Estadísticas y Favorito/Estados/Ajustes en columna; nada recortado. |
| `save-states-gba` | ✅ | ✅ | ✅ | Ranura 1 con la miniatura real de arm.gba en 3:2 y su fecha (antes salía «Dañado», §1); ranuras vacías en 3:2. |

También revisadas porque cambian con N3 (17 Pro): `library-grid` (vertical: buscador bajo el título, segmentado y la lupa nueva en la barra), `library-ax5`, `search-results`, `game-details` (imagen al 45 %), `favorites`, `game-settings-gba` y `library-reduce-transparency`: ✅. Antes/después en `/private/tmp/claude-501/-Users-joelbermudez-Documents-workspace-pocketgb/6cc26fde-813f-4136-be84-5cf079cf4e60/scratchpad/n3/evidencia/`.

## 5. Comandos y salidas
Sobre `75931c5` (rama fusionada con `siguiente-nivel`):
```
$ with-lock.sh sim env SIM_DEVICE="iPhone 17 Pro" tools/ios-screenshots.sh <scratchpad>/n3-full
✔ Test run with 250 tests in 23 suites passed after 10.737 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (1246.044 seconds).
Executed 14 tests, with 0 failures (0 unexpected) in 1407.189 (1407.217) seconds     (UI: catálogo, Shell*, AdaptiveLibraryUITests)
xcodebuild Release: exit 0                                                               (simulador genérico, sin DEBUG)
xcodebuild test: exit 0
$ ls n3-full/*.png | wc -l
     160
$ with-lock.sh sim run.sh "iPhone SE (3rd generation)" <bloque N3>   → testScreenCatalog passed (253.836 s), 23 PNG
$ with-lock.sh sim run.sh "iPhone 17 Pro Max" <bloque N3>            → testScreenCatalog passed (249.443 s), 23 PNG
$ with-lock.sh sim run.sh "iPhone SE (3rd generation)" -only-testing:PocketGBUITests/AdaptiveLibraryUITests
Test Case '…testLandscapeSearchOpensFromTheGroupAndGoesAwayOnCancel]' passed (19.099 seconds).
Test Case '…testLandscapeToolsFilterByConsoleAndCategory]' passed (20.512 seconds).
Test Case '…testPortraitKeepsTheSegmentedFilterAndAddsTheSearchButton]' passed (9.573 seconds).
Test Case '…testToolsAlignWithTheTabBarWithoutOverlapping]' passed (22.628 seconds).
N3 arriba: grupo=(435.0, 254.0, 216.0, 48.0) … pestañas=[(185.0, 315.0, …), (290.5, 315.0, …), (394.0, 315.0, …)]
N3 desplazada: grupo=(435.0, 308.0, 216.0, 48.0) … pestañas=[(38.0, 311.0, 44.0, 44.0)]     (burbuja: centro 333; grupo: 332)
N3 al subir: grupo=(435.0, 254.0, 216.0, 48.0) …
```
En el 17 Pro la misma prueba da grupo 281…329 sobre pestañas en 342 (arriba) y 335…383 con la burbuja en 338…382 (desplazada).
```
$ with-lock.sh build xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -configuration Release \
    -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData-release CODE_SIGNING_ALLOWED=NO build
** BUILD SUCCEEDED **
$ rg -n 'URLSession|NWConnection' ios/PocketGB ; echo $?
1                                        (sin resultados: sin red)
```
N3 añade 15 tests Swift (`AdaptiveLibraryTests`) y 4 de UI (`AdaptiveLibraryUITests`). Compilación sin avisos del proyecto (`grep warning:` del log solo da el aviso ajeno de `appintentsmetadataprocessor`).

Archivos de otros lotes: N3 no toca `Input/` ni el editor de controles de `GameScreen.swift`, ni `LibraryPreferences.swift`, `LibraryStore.swift`, `LibraryScanner.swift`, `RomFingerprint.swift`, ni la ruta de guardado (`SaveStore`, `SaveMirror`, `AtomicFile`, `SRAMPersistence`): `git diff --stat b216c2a 892918c` sobre esos archivos sale vacío. En `AppState.swift` añade dos propiedades (`libraryCategory` y `libraryTools`). En `DebugScreenRouter.swift` y `screens.txt` solo bloques nuevos al final (más una llamada a `applyAdaptive` tras el `switch` de `apply` y otra a `afterGameOpenedAdaptive`). La fusión con `siguiente-nivel` solo chocó en esos dos archivos (se conservaron los dos bloques).

Commits: `892918c` (código N3a/N3b), `473e761` (guía, SPEC, 04), `0a65baa` (fusión), `4967daa`, `c36ff3f` y `75931c5` (correcciones vistas en las capturas: estadísticas en AX5, buscador en vertical, grupo tras girar).

## 6. Comportamientos documentados y riesgos
- **Modo del título en vertical.** `navigationBarTitleDisplayMode(.automatic)` explícito en la raíz hacía desaparecer el buscador bajo el título grande (detectado en la captura `library-grid` del catálogo completo y aislado con una prueba); ahora el modo solo se fija en altura compacta. `testPortraitKeepsTheSegmentedFilterAndAddsTheSearchButton` lo vigila.
- **Estado de la barra de pestañas.** No hay API pública para saber si está encogida; se deduce del desplazamiento. Si Joel toca la burbuja para desplegarla sin desplazar, la barra (que se dibuja por debajo del grupo) puede quedar parcialmente bajo él hasta el siguiente desplazamiento. Al subir, el grupo sube aunque la barra siga encogida (en la prueba, 100 pt hacia arriba no la despliegan): se prefiere no pisarla nunca.
- **Distancias medidas** en iOS 26.5 (simuladores SE 3.ª gen y 17 Pro, iguales). Si una versión de iOS mueve la barra, se ajustan dos constantes (`LibraryToolsPosition`).
- **Tarjetas GBA** recortadas por los lados para compartir marco 10:9 (decisión de encaje; el detalle la muestra entera). N5 podrá cambiarlo con la elección de portada.
- **Categorías** solo de primer nivel; N4 añade subcategorías, categoría virtual y pantalla de categoría.
- **Información técnica GBA:** el tipo de partida es el detectado (la carga usa `GBA_SAVE_AUTO`); un ajuste forzado se ve en «Ajustes del juego».

## 7. No verificado
- En el iPhone de Joel: sensación del grupo y los paneles con el dedo, la burbuja real de su versión de iOS, VoiceOver en horizontal, y la información técnica de sus juegos reales (Pokémon con MBC3+RTC, GBA con Flash y RTC) y de juegos en iCloud sin descargar.
- iPad (fuera de alcance, ND8); el layout por espacio ya da dos columnas con ≥ 600 pt.

## 8. Verificación tras la auditoría (commit `32b9437` + ajuste del test)
```
PocketGBTests, iPhone 17 Pro:   ✔ Test run with 254 tests in 23 suites passed after 12.879 seconds.
PocketGBTests, iPhone SE (3.ª): ✔ Test run with 254 tests in 23 suites passed after 10.802 seconds.
AdaptiveLibraryUITests (8 tests), 17 Pro y SE: todos passed (test exit 0)
tools/ios-screenshots.sh (17 Pro): ✔ 254 tests Swift; Executed 17 tests, with 0 failures (0 unexpected) (UI)
  testScreenCatalog passed (1356.551 seconds); 165 PNG; xcodebuild Release: exit 0; xcodebuild test: exit 0
Bloque N3 (28 capturas): SE passed (331.710 s), Pro Max passed (340.766 s)
Release generic/platform=iOS (CODE_SIGNING_ALLOWED=NO): ** BUILD SUCCEEDED **
rg -n 'URLSession|NWConnection' ios/PocketGB → sin resultados
```
Capturas revisadas (herramienta Read, hojas de contacto) en SE, 17 Pro y Pro Max: ✅
- `library-landscape` (reposo): Buscar, Filtros, Categorías, Vista y «…» en una cápsula de la barra; sin grupo; tarjetas del carril del ancho de una columna (en el SE, 3 columnas de ≈ 203 pt, como la cuadrícula: H1).
- `library-landscape-scrolled`: burbuja a la izquierda, grupo a la derecha en su fila; en la barra solo «…»; cabecera fijada.
- `library-landscape-bar-filters` / `-bar-categories` / `-bar-view`: el panel nace del botón de la barra y se despliega hacia abajo, cubriendo la barra mientras está abierto (nativo de iOS 26).
- `library-landscape-filters` / `-categories` / `-view` (grupo): panel hacia arriba, cabecera de sección visible encima.
- `library-landscape-panel-ax5`: cápsulas en varias filas, el panel se desplaza por dentro; `library-landscape-panel-reduce-transparency`: botones del grupo sólidos con borde.
- `library-landscape-white` (oscuro): «Biblioteca» legible; `game-details-technical` horizontal: borde suave en la columna derecha (H10).

Pendiente del iPhone de Joel: tacto real de los paneles desde la barra y desde el grupo, VoiceOver en horizontal y la burbuja de su versión de iOS.
