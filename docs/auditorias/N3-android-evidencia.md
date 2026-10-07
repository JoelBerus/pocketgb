# N3 Android: evidencia (biblioteca y detalle adaptables: N3a + N3b)

Rama `n3-android-adaptable`, creada desde `n1-android-identidad` (`4ef80c1`) y con `siguiente-nivel` fusionado (`2770fd5`, sobre `e631cf5`: N1 Android cerrado y N8 nativo; sin conflictos). Commits del lote: `576e561` (carril, horizontal, categorías, detalle, tests y catálogo), `c49f7e7` (revisión tras mirar las capturas, guía, SPEC y script de capturas) y el de esta evidencia. **La verificación desde limpio se hizo sobre `c49f7e7`** (después solo cambian documentos).

Plan: [N-README §4 N3](../hitos/N-README.md) (peticiones 5, 6, 7 y 15; ND15). Guía: [biblioteca-android](../guia/biblioteca-android.md). SPEC: [diseno-android/SPEC.md](../diseno-android/SPEC.md) §3.3. Capturas: [VERIFICACION.md](../diseno-android/VERIFICACION.md) §N3. No se tocaron `android/app/src/main/cpp/`, `emulator/` ni la distribución de los controles en juego.

## Qué cambia

### N3a · Carril «Continuar jugando»
- **Solo reanudables (ND15)**: `LibraryQuery.continueRail` (`library/LibraryCategory.kt`) = jugados recientemente, jugables, con portada capturada (K10) **y** con estado automático vigente (`GameActions.canResume`, el mismo que decide «Continuar» en A9), hasta 5. El filtro va antes del límite: si los tres últimos no se pueden continuar, el carril se llena con los siguientes. Igual que iOS (`continueCandidates`), que también exige la portada.
- **Ancho = una columna de la cuadrícula**: `railMetricsFor` (`ui/library/LibraryAdaptive.kt`) usa `columnsFor` y `gridCellWidth` (margen 16 dp y separación 12 dp, ahora constantes compartidas con `GameCollection`). Se ven tantas tarjetas como columnas, alineadas con las celdas; `rememberSnapFlingBehavior(SnapPosition.Start)` las deja en sus columnas al soltar. La fila sangra hasta el borde izquierdo y acaba en el margen derecho: en reposo no asoma ningún trozo de tarjeta (antes, 240/170 dp fijos y la segunda «Con…» cortada en el margen). Ancho de tarjeta en px enteros hacia abajo (con densidades fraccionarias la suma no pasa del ancho).
- **Alto**: la portada se limita para que portada + texto (o portada + botón con fuente grande) quepan en el alto visible con la barra superior desplegada; si el alto no da, la portada es más estrecha que su columna y se alinea a la izquierda (mínimo 96 dp).
- **Fuente grande (A7)**: portada a la izquierda, texto a la derecha y «Continuar» debajo. En vertical con una columna siguen apiladas (hasta 3, A7 R9) con el botón a todo el ancho; en horizontal van en fila (una por columna) y el botón a su medida, a la izquierda, lejos de la barra flotante.

### N3a · Detalle adaptable
- `detailLayoutFor` (`ui/details/DetailLayout.kt`, mismo criterio que `DetailLayout` de iOS): **dos columnas** si ancho > alto o ancho ≥ 600 dp, con la imagen a la izquierda (como mucho la mitad del ancho útil y el alto disponible) y la información a la derecha con su propio scroll; **una columna** con la imagen como mucho al 45 % del alto. Se decide con `BoxWithConstraints` (espacio que deja la barra superior), nunca por dispositivo.
- **Proporción de la consola como parámetro**: `RomEntry.screenAspectRatio` = `Console.fromFileName(fileName).screen.aspectRatio` (la `Console` que trajo N8 nativo): 10:9 en GB/GBC y 3:2 en `.gba` (la biblioteca aún no lista `.gba`; llegará con N8 Kotlin). `GameArtwork` recibe la proporción (las tarjetas siguen en 10:9).
- **Jugar/Continuar visible sin desplazar**; con fuente grande va justo bajo el título (antes de la ruta y el chip, que con 200 % lo empujaban fuera, también en vertical).
- **«Información técnica»** (los datos del núcleo: cartucho, ROM, partida con RAM/batería/reloj, checksums y SHA-256 seleccionable) pasa a ser plegable con cabecera accesible (botón, estado «Desplegada/Plegada», título), **desplegada de entrada** para no esconder lo que Android ya mostraba (iOS la adopta plegada).
- **Favorito/Estados/Ajustes**: se miden sus textos con la fuente real (`rememberTextMeasurer`): en fila con el icono al lado si caben, en fila con el icono encima si no, y apilados con fuente grande. Arregla un corte que ya existía en 360 dp («Favori», «Estad», «Ajust», visible en `library-detail`).
- Se mantienen todas las acciones de A9 (Continuar, Jugar desde el inicio, Renombrar en «⋮», favorito, ajustes, ocultar) y los datos de N1 (ruta, «Duplicado», «También en»).

### N3b · Biblioteca en horizontal
- **Cuándo**: `isLandscapeLibrary` = el espacio de la biblioteca es más ancho que alto (teléfono girado con rail, ventana ancha, plegable desplegado en su postura apaisada). En vertical, la estructura es la de siempre (buscador y chips).
- **Arriba**: sin buscador ni chips; `TopAppBar` con `TopAppBarDefaults.enterAlwaysScrollBehavior()` (se esconde al bajar y vuelve al subir). El «⋮» conserva «Volver a escanear» y «Cambiar carpeta».
- **Título de sección fijado**: `GameCollection` gana `pinnedHeader` (`stickyHeader` de `LazyGridScope`/`LazyListScope`, API estable de foundation 1.12) con fondo opaco a todo el ancho. Dice «Todos los juegos», el filtro, la categoría o «Categoría · filtro».
- **Barra flotante propia** (`LibraryToolbar`, `ui/library/LibraryLandscapeTools.kt`): `Surface` en pastilla con `IconButton`/`FilledTonalIconButton` de 48 dp en la ranura FAB del `Scaffold` (abajo a la derecha, funciona igual con barra inferior o NavigationRail). Sin `HorizontalFloatingToolbar` (solo existe en material3 1.5.0-alpha; el BOM trae 1.4.0). Botones: **Buscar**, **Filtros** (Todos/GB/GBC/Favoritos), **Categorías** y **Vista/Orden**; el botón con un filtro o categoría activos se ve relleno y lo anuncia (`stateDescription`).
- **Paneles hacia arriba**: un `Popup` encima de la barra, alineado a su borde derecho, de hasta 480 dp, con `FilterChip`. Su alto máximo llega hasta **debajo del título de sección** si ese título se ve por encima de la barra y el panel lo taparía (`LibraryToolsState.panelLimitTop`); si el título aún no se ve (arriba del todo, con el carril) llega hasta la barra superior. Con más opciones el panel se desplaza dentro. Tocar fuera o Atrás lo cierran; elegir una opción lo cierra y aplica.
- **Buscar**: la barra superior pasa a ser un campo (`TextField`, con foco y teclado); la búsqueda sigue el filtro y la categoría; la flecha o Atrás (`BackHandler`) la cierran, vacían la búsqueda y la cuadrícula vuelve a su posición (`LazyGridState`/`LazyListState` elevados en `GameBrowser`). Sin barra flotante mientras se busca.
- **Categorías** (`LibraryCategory`): carpetas de primer nivel de `folderPath` (con todo su subárbol), orden natural, con cuántos juegos visibles tiene cada una, y «Sin categoría» para la raíz; «Todas» quita el filtro. El filtro va después de `visible`, así una copia conserva «También en» aunque la otra esté en otra categoría. La categoría vive en `LibraryViewModel` (como el filtro). En vertical se elige en «⋮» › Categoría (solo si hay carpetas). Estados vacíos propios («No hay juegos en «X»», «Ver todos los juegos», búsqueda sin resultados en una categoría).
- Al pasar de horizontal a vertical se cierra un panel abierto y la búsqueda horizontal si no tiene texto.

### Cadenas, catálogo, guía y herramientas
- Cadenas nuevas solo en `res/values/strings_n3.xml`.
- `tools/android-screens.txt`: bloque añadido al final, 22 ids y 27 capturas. `debug/catalog/N3Catalog.kt` (+1 línea en `DebugCatalog.kt`); `LibraryCatalog.kt` marca como reanudables los tres juegos recientes del catálogo (sin eso, ND15 quitaba el carril de `launch`, `library-grid`, etc.).
- `tools/android-screenshots.sh`: el giro también con `wm user-rotation lock` y se repite a mitad de la espera (con el emulador cargado, 12 de 20 capturas horizontales salían en vertical; después, 0 de 20).
- Guía nueva `docs/guia/biblioteca-android.md`; `carpetas-android.md` ya no dice que las categorías son «más adelante». SPEC §3.3 y `05-android-spec.md`.

## Decisiones tomadas (para la auditoría)
1. **Horizontal = más ancho que alto, en cualquier pantalla** (no solo teléfonos): en la ventana ancha (853 × 509 dp) y en el plegable desplegado también se usa la barra flotante. En un plegable en su postura vertical (701 × 841 dp) vuelve el buscador.
2. **El carril sigue exigiendo portada** además de ser reanudable, como iOS («nunca una portada inventada», K10).
3. **La barra flotante flota sobre el contenido**: arriba del todo tapa el texto de la tercera tarjeta del carril en un teléfono girado (como el grupo de iOS); la lista lleva 88 dp de aire al final para que la última fila se pueda ver entera. Reservar ese hueco encogía las tarjetas por debajo de su columna, que era la queja principal.
4. **Panel con alto mínimo de 112 dp**: si entre la barra y el título fijado hay menos sitio, el panel tapa el título (se desplaza dentro). En el teléfono de prueba girado hay ~120 dp con la barra superior desplegada y ~184 dp plegada.
5. **«Información técnica» desplegada de entrada** (Android ya la mostraba entera).
6. **Paneles propios en vez de `DropdownMenu`**: con 48 dp por opción, Vista y Orden no cabían en el alto del horizontal; los chips en filas sí.

## Verificación DESDE LIMPIO (comandos y salidas)
```
git archive c49f7e7 | tar -x -C <scratchpad>/n3a-android/clean
cp android/local.properties <scratchpad>/n3a-android/clean/android/
cd <scratchpad>/n3a-android/clean/android
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```
```
BUILD SUCCESSFUL in 3m 45s
119 actionable tasks: 119 executed
```
- **Pruebas JVM: 606, 0 fallos, 0 omitidas** (suma de los XML de `testDebugUnitTest`). Nuevas de este lote (35): `RailMetricsTest` 11, `DetailLayoutTest` 8, `LibraryCategoryTest` 11, `LibraryToolsStateTest` 5. `ManifestPolicyTest` (sin `INTERNET`) 9/9.
- `app-debug.apk` 22,2 MB y `app-release-unsigned.apk` 15,3 MB. `aapt2 dump permissions` del Release: solo `com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (**sin `INTERNET`**).
- **Lint: `0 errors, 20 warnings`**, ninguno en archivos de este lote (los mismos 20 de antes: dependencias, `UseKtx`, `ViewConstructor`, `Typos`, `PluralsCandidate`, `UnusedAttribute`).

### Emulador (`Small_Phone_API_35`, dentro de `with-lock.sh emu`, árbol limpio)
- `installDebug` y **kill-test**: `tools/android-save-kill-test.sh 50` →
  ```
  Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
  OK: 50/50 iteraciones con el invariante intacto
  ```
- **Instrumentadas completas** (`:app:connectedDebugAndroidTest`, sin filtro): **423 pruebas, 414 correctas y 9 fallos, todos de `GbaNativeTest`** (N8 nativo) por falta de ROMs de prueba GBA en el árbol exportado: 8 por `AssumptionViolatedException: Falta gba/arm.gba en los assets de prueba…` y `millisecondsPerFrameOnThisDevice` porque sin ROMs no mide nada (`assertTrue(results.isNotEmpty())`). `gba/tests/roms` es un enlace ignorado por git y las homebrew se compilan aparte. Con el enlace a las ROMs compartidas y `make -C gba homebrew` (con `ld.lld`/`llvm-objcopy` del NDK al final del `PATH`) en el mismo árbol limpio: `GbaNativeTest` **21/21** (`tests="21" failures="0"`). Todas las demás clases pasan, entre ellas `AdaptiveLibraryUiTest` 14/14, `LibraryUiTest` 61/61, `CatalogCoverageTest` 9/9, `DebugCatalogTest` 11/11, `TouchTargetTest` 6/6, `FavoritesUiTest` 4/4, `FoldersUiTest` 2/2, `ContinueChoiceUiTest` 3/3, `RenameUiTest` 5/5, `HighContrastTest` 2/2, `OpenFromLibraryTest` 8/8, `ExactContinuationTest` 15/15 y `SafLibraryTest` 15/15.

### Instrumentadas nuevas (`AdaptiveLibraryUiTest`, 14)
Carril: solo reanudables (1 de 3 jugados) · sin reanudables no hay carril · tarjetas del ancho de una celda y alineadas con la 1.ª y 2.ª columna · en 640 × 360 dp, 4 tarjetas enteras (4 columnas) y ninguna cortada. Horizontal (`DeviceConfigurationOverride.ForcedSize`): sin buscador ni chips y con barra flotante y título fijado · en vertical siguen buscador y chips · cada botón abre su panel **por encima** de la barra y **sin tapar el título de sección** (360 × 340 dp, sin escalar, para que el panel tenga la densidad real) y Atrás lo cierra · Filtros (GBC → 1 juego, título «GBC»), Categorías (Pokémon → 2, «Sin categoría» → 2) y Vista (Lista) se aplican · Buscar abre el campo arriba, filtra («yel» → 1) y al cerrar vuelven la barra y «Todos los juegos» · Atrás cierra la búsqueda · en vertical «⋮» › Kirby filtra. Detalle: en horizontal, dos columnas con la imagen a la izquierda, dentro del alto, y «Continuar»/«Jugar desde el inicio» enteros sin desplazar · en vertical, imagen ≤ 45 % y «Jugar» visible · «Información técnica» se pliega y despliega.

Ajustes de las pruebas existentes: `LibraryUiTest` (8 pruebas del carril) recibe `resumable` porque el carril ahora exige poder continuar (ND15); `CatalogCoverageTest.scriptOnlyArgumentsNeverReachTheApp` conoce los 3 ids N3 con `window=wide`. `AdaptiveLibraryUiTest` fija la fuente al 100 % porque el emulador compartido tenía `font_scale = 1.3` (se dejó como estaba).

### Mutaciones (copia aparte de `c49f7e7`; cada una rompe su test)
| Mutación | Test | Resultado |
|---|---|---|
| `continueRail` sin `.filter(isResumable)` | `LibraryCategoryTest` | 3 fallos |
| `gridCellWidth` sin las separaciones | `RailMetricsTest` | 4 fallos |
| `isWideDetail` solo por ≥ 600 dp | `DetailLayoutTest` | 2 fallos |
| `panelLimitTop` siempre hasta la barra superior | `LibraryToolsStateTest` | 1 fallo |
| Carril sin tope de alto | `RailMetricsTest` | 2 fallos |

## Capturas revisadas
Todas miradas con la herramienta Read (hojas de contacto); detalle por id en [VERIFICACION.md §N3](../diseno-android/VERIFICACION.md).
- **`Small_Phone_API_35`** (vertical 360 × 640 dp; horizontal 640 × 360 dp con NavigationRail), claro y oscuro: los 22 ids N3 (27 capturas) + regresión (`launch`, `library-continue`, `library-grid`, `library-ax5`, `library-detail`, `library-detail-ax5`) sobre `c49f7e7`.
- **Ventana ancha** (`window=wide`, 1280x800 a 240 dpi = 853 dp): `n3-library-wide`, `n3-library-wide-filters`, `n3-details-wide`.
- **Fuente 200 %**: `n3-rail-portrait-ax5`, `n3-library-landscape-ax5`, `n3-details-portrait-ax5`, `n3-details-landscape-ax5`.
- **`Pixel_Fold_API_35` desplegado** (2208x1840, 420 dpi): 8 capturas en sus dos posturas (biblioteca con barra flotante y 4 columnas en la apaisada; con buscador y 3 columnas en la vertical; detalle a dos columnas en ambas).

Defectos encontrados al mirar y corregidos en `c49f7e7`: asomaba un trozo de 4 dp de la tarjeta siguiente; la portada del carril se recortaba de más (contaba la separación final); los filtros y Vista/Orden no cabían en el panel; con fuente grande en horizontal la barra flotante tapaba «Continuar»; el detalle a 200 % dejaba «Jugar» fuera; «Favorito/Estados/Ajustes» cortados; el icono de carpeta del título de sección lejos de «Roms»; el panel preseleccionado del catálogo se cerraba si la app arrancaba en vertical.

## Lo que no se verificó
- **Teléfono real de Joel** (tacto, desplazamiento y plegado de la barra con el dedo, teclado real en horizontal, carpeta de Drive con categorías reales). Queda para PRUEBAS-JOEL.
- **TalkBack a mano**: solo semántica en tests (etiquetas, `stateDescription`, encabezados, objetivos ≥ 48 dp en `TouchTargetTest`).
- **Tablet** propiamente dicha: se probó la ventana ancha (853 dp) y el plegable; no un AVD de tablet de 10".
- **GBA 3:2 en el detalle**: la proporción sale de `Console` y tiene test JVM, pero ningún `.gba` llega aún a la biblioteca (N8 Kotlin).
- La barra flotante en **modo multiventana** y con **recortes de pantalla** reales.
- No se actualizaron `docs/ESTADO.md` ni la tabla de `docs/hitos/README.md` (los lleva el orquestador al fusionar).
