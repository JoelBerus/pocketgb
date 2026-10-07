# N4 Android: evidencia (categorías, etiquetas, inicio y centro de ajustes del juego)

Rama `n4-android-categorias` desde `siguiente-nivel` (`dbe177c`: A9, N1 y N3 Android cerrados y N8 nativo). Plan: [N-README §4 N4](../hitos/N-README.md) (peticiones 8, 17 y 18; ND3, ND11 y ND12; §3.2). Reglas para el usuario: [11-biblioteca-carpetas](../11-biblioteca-carpetas.md) y [LEEME-PocketGB.txt](../LEEME-PocketGB.txt). Guía: [categorias-android](../guia/categorias-android.md). No se tocaron `android/app/src/main/cpp/`, `emulator/` ni `input/`. iOS es otro lote (N4 iOS).

Commits: `c977923` modelo y formato 4 · `229d3ad` UI (inicio, categoría, centro de ajustes, Ajustes › Biblioteca › Inicio), catálogo e instrumentadas · `411f43b` documentación y fuente grande · `adb48e3` lint · `ef043cd` `DebugCatalogTest` · `df14840` captura `n4-home-ax5-scrolled`. La verificación vigente es **desde limpio sobre `df14840`**.

## Qué cambia

### Modelo (`library/`)
- **`LibraryCategory.Folder(path)`** a cualquier profundidad (con el constructor de primer nivel de N3); `key` estable (`Pokémon/2ª generación`, `.` para «Sin categoría»); `contains` compara **niveles**, no texto. La categoría que cuenta es **`RomEntry.categoryPath`**: la virtual si el juego se movió en la app o la de su carpeta.
- **`LibraryTree`**: subcategorías directas con los juegos de su subárbol, migas y rutas conocidas (carpetas con juegos, sus padres y las virtuales; sin ocultos) para «Mostrar en categoría…».
- **`LibraryHome`**: fila de Favoritos (cada juego una vez aunque tenga copias) y una estantería por categoría de primer nivel (hasta 10 juegos y su total), en el orden de `HomeSettings`; **sin carpetas no hay estanterías** (repetirían «Todos los juegos»).
- **`HomeSettings`** (por dispositivo, ND12): orden elegido (una categoría que falta un rato conserva su sitio; una nueva va detrás), fijadas primero (fijar no cambia el orden: al soltar vuelve a su sitio), ocultas y fila de Favoritos. Subir/bajar se mueve dentro de su grupo (fijadas o no).
- **Etiquetas** (`Tags`): normalizadas (sin `#`, espacios colapsados, ≤ 30 caracteres), iguales sin mayúsculas ni acentos, ≤ 20 por juego, orden natural; `LibraryQuery.tagOptions` para el filtro.
- **Categoría virtual (ND3)**: `moveToCategory`/`returnToFolder`; elegir su propia carpeta = volver; ruta vacía = «Sin categoría». `CategoryPaths.parse` valida lo que se escribe (`/` o `›`, ningún nivel con `.` o `_` delante, ≤ 5 niveles).
- **Etiquetas y categoría virtual van por huella y solo se escriben con la huella confirmada** (N1-H1): sin huella o con una heredada de un movimiento no se escribe nada (`confirmedFingerprint`); el ViewModel devuelve `false` y la UI la confirma antes leyendo el ROM (`confirmFingerprint`). Se **ven** también con una huella heredada (el juego movido sigue en su categoría y con sus etiquetas).
- **Búsqueda**: además de alias, título y archivo, cada nivel de la categoría en la que se ve y las etiquetas. **Filtro por etiqueta** combinable con GB/GBC/Favoritos.
- `LibraryViewModel`: `tag`/`setTag` (sustituye a la categoría de N3, que ya no filtra), `addTag`, `removeTag`, `moveToCategory`, `returnToFolder`, `setCategoryLayout`, `updateHome`, `confirmFingerprint`.

### Formato 4 de `preferences.json`
Claves nuevas (con valor vacío por defecto): `tagsByFingerprint`, `virtualFoldersByFingerprint`, `home` y `categoryLayouts`. Ejemplo tras añadir una etiqueta y mover Rojo (de la fixture v3):
```json
{ …lo de v3 igual…,
  "tagsByFingerprint":{"9d4c…4b58":["rpg"]},
  "virtualFoldersByFingerprint":{"9d4c…4b58":["Favoritas"]},
  "home":{"order":[],"pinned":[],"hidden":["Kirby"],"showFavorites":false},
  "categoryLayouts":{"Pokémon":"GRID"},
  "formatVersion":4}
```
- **v2 y v3 se leen tal cual** (lo nuevo vacío) y se escriben como v4; v1/sin versión se sigue migrando (A8/A9 → 4).
- **Versión futura (5+)**: se usa lo legible clave a clave, **nunca se sobrescribe ni se aparta** (N1-H2/H3). Una v4 ilegible sí se aparta como `.corrupt-<fecha>` (como cualquier versión conocida dañada).
- Hacia atrás: una app con N3 (v3) ve un v4 como futuro: lo usa y no lo sobrescribe (aviso fijo en la biblioteca).
- Fixture nueva `test/resources/library/preferences-n3.json` (v3 realista: huellas por ruta, alias, ocultos, documentos con cabecera, una lápida y una huella sin confirmar).

### UI
- **Inicio** (pestaña Biblioteca, sin búsqueda, filtro ni etiqueta): «Continuar jugando» (N3), **Favoritos** («Ver todo» → pestaña Favoritos) y **una estantería por categoría** (`HomeShelves.kt`: título con número de juegos, «Ver todo», tarjetas del ancho de una columna como el carril, ajuste al soltar, menú contextual por sitio para no abrir a la vez el de la cuadrícula). Debajo, «Todos los juegos». En horizontal sigue N3: iconos arriba en reposo y barra flotante cuando el título queda fijado, que ahora llega tras **todas** las filas del inicio (`isTitlePinned(leadingItems, …)`).
- **Pantalla de categoría** (`CategoryScreen.kt`, `LibraryRoute.Category(path)` serializable en la pila de Navigation 3): migas tocables («Biblioteca › Pokémon › 2ª generación», `popTo` o apilar), subcategorías como chips con su número de juegos, «Juegos · N» (de la carpeta y sus subcarpetas, o «En la carpeta principal») y **vista por categoría** (botón arriba a la derecha; `categoryLayouts`). Vacía: estado propio con «Volver a la biblioteca».
- **Categorías** (panel en horizontal, «⋮» › Categorías en vertical y «Ver todo») **abren la categoría** en vez de filtrar (cambio respecto a N3).
- **Etiquetas en los filtros**: panel Filtros › Etiquetas (horizontal) y chip «Etiqueta ▾» al final de los filtros (vertical; la fila se desplaza sola para que se vea elegido). Título «Etiqueta «rpg»».
- **Insignia «Movido en la app»** (contorno fino, icono de mover; en tarjetas estrechas solo el icono y el texto en la etiqueta de TalkBack); en el detalle, «Se ve en «Favoritas»» además de la ruta real, y las etiquetas.
- **Centro de ajustes del juego** (la hoja de ajustes del juego, desde el detalle y el menú contextual; **la pausa no tenía acceso a los ajustes del juego, así que no se añade**): Nombre (A9), Categoría (Cambiar → «Mostrar en categoría…» con la lista de categorías existentes y «Nueva categoría»; «Volver a su carpeta»), Etiquetas (editor con quitar, añadir y «Etiquetas que ya usas»), Portada y Progreso y momentos (deshabilitadas, «Próximamente», con estado para TalkBack), Partida (`LibraryRoute`/`FavoritesRoute.GameSaves`: Ajustes › Partidas solo de esa huella), Color y paleta (lo de siempre) y Ocultar (con confirmación; desde el detalle vuelve atrás). Mientras se confirma la huella: «Leyendo el juego…» y Categoría/Etiquetas deshabilitadas.
- **Ajustes › Biblioteca › Inicio** (`HomeSettingsScreen`): fila de Favoritos; por categoría, chincheta (fijar), subir/bajar y «En el inicio»; «Restablecer el inicio». Con fuente grande los controles van bajo el nombre.
- `app/LibraryRoutes.kt` reúne las pantallas de la pestaña Biblioteca (las usa la app y las pruebas de navegación).
- Cadenas nuevas solo en `strings_n4.xml`; se quitaron de `strings_n3.xml` seis cadenas de la categoría «en el sitio» que ya no se usan (lint).

## Decisiones tomadas (para Joel)
| # | Decisión | Motivo |
|---|---|---|
| N4A-1 | Las categorías (panel, «⋮», estanterías) **navegan** a su pantalla; ya no filtran la biblioteca en el sitio (N3). | Lo pide el plan («Categorías abre la lista de categorías y navega»); una sola forma de ver una categoría. |
| N4A-2 | Sin carpetas en la raíz no hay estanterías; «Sin categoría» es una estantería más (al final) cuando hay carpetas. | Con solo la raíz, la estantería repetiría «Todos los juegos». |
| N4A-3 | Estanterías y Favoritos con **hasta 10 juegos**; Favoritos muestra cada juego una vez (copias), las estanterías muestran archivos (como la cuadrícula). | Fila corta y rápida; «Ver todo» para el resto. |
| N4A-4 | «Ver todo» de Favoritos abre la **pestaña Favoritos**. | Ya existe y tiene su carril de recientes. |
| N4A-5 | La categoría virtual se aplica **por huella**: las copias del mismo juego se ven juntas en ella; «Volver a su carpeta» devuelve cada copia a la suya. | Las copias son el mismo juego para PocketGB (N1a). |
| N4A-6 | La búsqueda mira la categoría **en la que se ve** (no la carpeta real si está movido). | Coherente con lo que muestra la biblioteca. |
| N4A-7 | Nombres de categoría escritos: sin niveles que empiecen por `.` o `_`, ≤ 5 niveles, ≤ 60 caracteres por nivel. | Mismas reglas que las carpetas (ND11). |
| N4A-8 | Etiquetas: ≤ 20 por juego, ≤ 30 caracteres, sin distinguir mayúsculas ni acentos; se ven en el detalle y en el centro, no en las tarjetas. | Tarjetas limpias; el filtro y la búsqueda las usan. |
| N4A-9 | Fijar no cambia el orden guardado (al soltar vuelve a su sitio); subir/bajar no cruza de fijadas a no fijadas. | Predecible y reversible. |
| N4A-10 | «Partida» del centro abre Ajustes › Partidas **de ese juego dentro de la misma pestaña** (Atrás vuelve al centro de origen). | No salta de pestaña ni pierde el contexto. |
| N4A-11 | Las pruebas de N3 (`AdaptiveLibraryUiTest`) y de la cuadrícula (`LibraryUiTest`) ocultan las estanterías en su arnés; el inicio se prueba aparte (`HomeUiTest`). `TouchTargetTest` sí las incluye. | Esas pruebas miden carril, paneles y cuadrícula; con el inicio delante la cuadrícula queda fuera de la pantalla. |

## Verificación DESDE LIMPIO (`df14840`)
```
git archive df14840 | tar -x -C <scratchpad>/n4-android/clean3
cp android/local.properties clean3/android/
ln -sfn …/pocketgb-integracion/core/tests/roms clean3/core/tests/roms
ln -sfn …/pocketgb-integracion/gba/tests/roms clean3/gba/tests/roms
PATH="$PATH:$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin" make -C clean3/gba homebrew   # rc=0, 22 .gba
cd clean3/android && ./gradlew --no-daemon --max-workers=1 --continue :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```
```
BUILD SUCCESSFUL in 4m 2s
120 actionable tasks: 120 executed
```
- **JVM: 664 pruebas, 0 fallos, 0 errores, 0 omitidas** (N3 cerró con 620). Nuevas o ampliadas: `LibraryOrganizationTest` 31, `LibraryFormatV4Test` 7, `LibraryOrganizationViewModelTest` 2, `AppNavigationStateTest` 10 (+2), `SectionTitleTest` 6 (+2). Ajustadas por la versión 4: `LibraryIdentityTest` (v2 se escribe como la actual) y `LibraryMoveViewModelTest` (la versión futura pasa a ser la 5). `ProcessKillTest`: `kills en bucle=197, con .tmp huérfano=136, guardados completados=301`. `ManifestPolicyTest` 9/9.
- **Lint: `0 errors, 20 warnings`**, los mismos 20 de antes (ninguno en archivos de N4; una primera corrida dio 28: plural en una descripción y cadenas de N3 sin uso, corregido en `adb48e3`).
- APK: `app-debug.apk` 22 480 694 B, `app-release-unsigned.apk` 15 442 371 B. `aapt2 dump permissions` del Release: solo `com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (**sin `INTERNET`**).

### Emulador (`Small_Phone_API_35`, dentro de `with-lock.sh emu`, árbol limpio)
Preparación: `hide_error_dialogs 1`, `anr_show_background 0`, animaciones a 0, `show_ime_with_hard_keyboard 0`. El emulador compartido tiene `font_scale 1.3` (se dejó como estaba; las pruebas que lo necesitan fijan la fuente).
- **Instrumentadas completas, sin filtro** (`:app:connectedDebugAndroidTest`): **446 pruebas, 0 fallos, 0 omitidas, 52 clases** (N3: 433). Entre ellas `HomeUiTest` 9, `CategoryNavigationTest` 1, `GameCenterUiTest` 3 (nuevas), `AdaptiveLibraryUiTest` 22, `LibraryUiTest` 61, `TouchTargetTest` 8, `DebugCatalogTest` 11, `CatalogCoverageTest` 9 (manifiesto ↔ catálogo con los ids N4), `GameSettingsSheetTest` 6, `GameSettingsConfirmTest` 1, `RenameUiTest` 5, `FoldersUiTest` 2, `FavoritesUiTest` 4, `MoveRomEndToEndTest` 3, `SafLibraryTest` 15 y `GbaNativeTest` 21 (con las ROMs libres enlazadas y las homebrew compiladas).
- **Kill-test** con el APK Debug del árbol limpio: `tools/android-save-kill-test.sh 50` →
  ```
  Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
  OK: 50/50 iteraciones con el invariante intacto
  ```

### Instrumentadas nuevas
- **`CategoryNavigationTest`** (ViewModel real con árbol de carpetas, núcleo real y las pantallas de la app vía `LibraryRouteContent` + `NavDisplay` + `AppNavigationState.Saver`): inicio → «Ver todo» Pokémon → subcategoría «2ª generación» → **recreación** (`StateRestorationTester.emulateSavedInstanceStateRestore`) → sigue en «2ª generación» con su miga → juego → detalle → **recreación** → pila `[Root, Category(Pokémon), Category(Pokémon, 2ª generación), Details(…Gold.gb)]` → Atrás → «2ª generación» → Atrás → «Pokémon» → miga «Biblioteca» → solo `Root`.
- **`GameCenterUiTest`** (ViewModel real y `preferences.json` en disco): desde el menú contextual, renombrar, añadir la etiqueta «plataformas» (chip «Quitar la etiqueta plataformas»), mostrar en una categoría **nueva** («Favoritas · movido en la app»), comprobar en disco formato 4 con alias, etiqueta y categoría virtual **por huella confirmada**, «Volver a su carpeta» («Kirby · su carpeta» y la entrada desaparece), y filtrar por la etiqueta en vertical (1 juego, título «Etiqueta «plataformas»»). Desde el **detalle**: mover a una categoría existente («Pokémon»): «Se ve en «Pokémon»» y la ruta real sigue siendo `Tetra.gb`. Portada y Progreso deshabilitadas para TalkBack con «No disponible todavía»; Ocultar pide confirmación y oculta.
- **`HomeUiTest`**: estanterías y «Ver todo» (abre la categoría; Favoritos abre su pestaña); orden, fijadas, ocultas y fila de Favoritos según `HomeSettings`; Ajustes › Biblioteca › Inicio (subir, fijar, ocultar con su estado, fila de Favoritos, restablecer); pantalla de categoría con migas (**TalkBack: «Estás en: Biblioteca › Pokémon › 2ª generación»**, «Ir a Pokémon»), subcategoría «Subcategoría Johto, 1 juego» y vista por categoría; juego movido con la insignia en su categoría virtual; filtro por etiqueta en vertical; **encabezados de estantería como títulos para TalkBack** y «Ver todo Kirby, 1 juego»; **fuente al 200 %**: «Ver todo» entero y ≥ 48 dp, migas y subcategorías visibles.

### Mutaciones (copia aparte; `library.*`: 229 pruebas, 9 fallos)
| Mutación | Test que falla |
|---|---|
| Escribir etiquetas/categoría sin huella confirmada | `withoutAConfirmedFingerprintNothingIsWritten` |
| Categoría por prefijo de texto en vez de por niveles | `aCategoryScreenShowsTheGamesOfTheFolderAndItsSubfolders` |
| El inicio ignora las fijadas | `pinnedCategoriesGoFirstAndCannotBeMovedBelowTheUnpinned` |
| La v3 deja de ser conocida (se trataría como futura) | `aVersionThreeFileIsReadAsIsAndKeepsEverything`, `aVersionThreeFileIsWrittenAsVersionFour…` |
| La búsqueda no mira categoría ni etiquetas | `searchFindsTags`, `searchFindsTheCategoryAndAMovedGameOnlyInItsNewCategory` |
| El inicio no quita las ocultas | `hiddenCategoriesLeaveTheHomeButStayInTheSettingsList` |
| Elegir su propia carpeta no revierte | `choosingItsOwnFolderIsTheSameAsReturning` |

## Capturas
Bloque añadido al final de `tools/android-screens.txt`: **30 ids y 38 capturas** (el manifiesto queda en 171 ids y 251 capturas). Catálogo `debug/catalog/N4Catalog.kt` (+1 línea en `DebugCatalog.kt`) con un árbol demo: `Pokémon/1ª generación`, `Pokémon/2ª generación/Johto`, `Aventuras`, `Puzles` (Tetra Blocks movido a «Favoritas», virtual), `Pruebas` y un juego en la raíz; etiquetas rpg/pendiente/dos jugadores, tres favoritos y tres reanudables. Los ids N3 que abrían una categoría en el sitio (`n3-library-landscape-category`, `-landscape-list`, `-portrait-category`) muestran ahora su pantalla de categoría.

**Fuente 200 % en hojas y diálogos:** el catálogo fija la fuente con `LocalDensity`, que no llega a las ventanas propias de las hojas y diálogos; `n4-game-center-ax5` y `n4-move-category-ax5` se capturaron además con `settings put system font_scale 2.0` (restaurado después a 1.3).

### Capturas revisadas
48 PNG del APK limpio (`df14840`), vistos en hojas de contacto con la herramienta Read; tabla por id en [VERIFICACION.md §N4](../diseno-android/VERIFICACION.md). Rutas (scratchpad `n4-android/final/`): `n4-home-portrait-light.png`, `n4-home-scrolled-portrait-light.png`, `n4-home-landscape-scrolled-landscape-light.png`, `n4-category-nested-list-portrait-light.png`, `n4-game-center-moved-portrait-light.png`, `n4-tag-editor-portrait-dark.png`, `n4-move-category-portrait-light.png`, `n4-settings-home-portrait-light.png`, `n4-details-moved-portrait-light.png`.

Defectos que se vieron en las capturas y se corrigieron antes de la verificación final: botones del selector de categoría que aplastaban la lista; campo «Nueva categoría» con foco y teclado tapando la lista; «Nueva etiqueta» partida; la captura horizontal desplazada que no llegaba al título fijado; título del centro y categorías del selector cortados con fuente grande.

## Lo que no se verificó
- **Teléfono real de Joel** con la carpeta `Roms` de Drive (estanterías con sus carpetas reales, tacto, TalkBack real, rendimiento con muchas categorías): filas N4-1…N4-6 de [PRUEBAS-JOEL](../PRUEBAS-JOEL.md).
- **TalkBack a mano**: solo semántica en tests (títulos, descripciones de «Ver todo», migas, subcategorías, estados de las filas deshabilitadas y de «En el inicio»).
- **Recreación real de la actividad** (girar con «No conservar actividades»): se probó con `StateRestorationTester` (estado guardado de `rememberSaveable`) y el test JVM de la pila serializada; no con `ActivityScenario.recreate()` sobre la app completa.
- **Plegable y tablet**: no se repitieron las capturas en `Pixel_Fold_API_35` para N4.
- El aviso emergente «Drag handle» que asoma en las capturas de las hojas (`game-settings`, `n4-game-center*`) es previo a N4: sale porque el emulador tiene teclado físico y el asa recibe el foco al abrir la hoja.
- No se actualizaron `docs/ESTADO.md` ni la tabla de `docs/hitos/README.md` (los lleva el orquestador al fusionar).

## Respuesta a la auditoría (verificación vigente, sobre `aa87b28`)
Hallazgos H1–H15 y cambios: [N4-android-respuesta](N4-android-respuesta.md). Decisiones del orquestador: se mantienen N4A-1, N4A-2, N4A-3 (con el total visible, H1), N4A-5 (con H10) y N4A-8; la pausa sigue sin centro de ajustes. El ejemplo de categoría virtual pasa de «Favoritas» a «Para jugar» (catálogo y documentación; H13).

### Desde limpio
```
git archive aa87b28 | tar -x -C <scratchpad>/n4-android/clean4      # + local.properties, ROMs libres enlazadas
PATH="$PATH:$NDK/…/bin" make -C clean4/gba homebrew                   # rc=0, 22 .gba
./gradlew --no-daemon --max-workers=1 --continue :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
BUILD SUCCESSFUL in 4m 19s · 120 actionable tasks: 120 executed
```
- **JVM: 668 pruebas, 0 fallos** (+4: `theFavoritesRowCountsEveryFavoriteEvenBeyondTheLimit`, `aCopyShownInItsOwnFolderIsNotMarkedAsMoved`, `movingAPinnedCategoryKeepsTheRelativeOrder…`, `aTypedCategoryReusesAnExistingOne…`).
- **Lint: `0 errors, 20 warnings`** (los de siempre). Release **sin `INTERNET`** (`aapt2 dump permissions`).
- **Instrumentadas completas** (`with-lock.sh emu`, sin filtro): **447 pruebas, 0 fallos, 0 omitidas** (+1: `HomeUiTest.inLandscapeWithTheWholeHomeScrollingDownPinsTheTitleAndPanelsDoNotCoverIt`; `GameCenterUiTest` escribe «pokemon» y comprueba que se reutiliza «Pokémon»; la prueba al 200 % mide el texto dibujado).
- **Kill-test** con el APK limpio: `Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)` · `OK: 50/50 iteraciones con el invariante intacto`.
- **Mutaciones de las correcciones** (`library.*`, 233 pruebas): 4 fallos, uno por mutación (ver la respuesta).

### Capturas regeneradas y revisadas (APK de `aa87b28`; scratchpad `n4-android/resp/`)
`n4-game-center` (C,O), `-moved`, `-landscape`, `-ax5` (fuente del sistema 2,0), `n4-details-moved`, `n4-category-virtual`, `n4-home` (C,O), `n4-home-ax5-scrolled`, `n4-move-category` (C,O), `n4-home-landscape-scrolled`, vistas con Read:
- Centro: «Para jugar [Movido en la app]» y «Su carpeta: Puzles» sin repetir; «Cambiar» y «Volver a su carpeta» bajo la fila (con la fuente del sistema del emulador al 1,3 las dos acciones se reparten en dos líneas); etiquetas como texto «pendiente · rpg».
- Detalle: «Movido en la app · Se ve en «Para jugar»» y «dos jugadores» como texto.
- `n4-home-ax5-scrolled` muestra ya la estantería «Aventuras 2 juegos · Ver todo» con la portada a la izquierda (H14).
- `n4-home-landscape-scrolled`: «Todos los juegos · Roms» fijado y la barra flotante.
- Sigue asomando el aviso «Drag handle» sobre el asa de las hojas (foco de teclado físico del emulador; previo a N4).
