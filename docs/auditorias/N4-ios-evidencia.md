# N4 iOS: evidencia (categorías, etiquetas, inicio y centro de ajustes del juego)

Rama `n4-ios-categorias` desde `siguiente-nivel` (`58cd81b`: N1 y N3 iOS cerrados). Plan: [N-README §4 N4](../hitos/N-README.md) (ND3, ND11, ND12; §3.2). Referencia funcional: N4 Android (`n4-android-categorias`, evidencia, auditoría Opus y respuesta H1–H15). Reglas para Joel: [11-biblioteca-carpetas](../11-biblioteca-carpetas.md) (llega con la fusión de Android; aquí solo se enlaza). Guía iOS: [guia/categorias.md](../guia/categorias.md). Diseño: [SPEC §16](../diseno/SPEC.md). No se tocan `Input/`, `Emulator/` ni `Saves/`.

## Qué cambia
| Pieza | Archivo | Qué hace |
|---|---|---|
| Modelo | `Library/LibraryOrganization.swift` (nuevo) | `CategoryPaths` (parse con `/` o `›`, sin `.`/`_`, ≤ 5 niveles, ≤ 60 car.; `matchExisting`; clave estable, «.» = raíz; prefijo por niveles), `Tags` (normalizadas, sin `#`, ≤ 30 car., ≤ 20 por juego, sin mayúsculas ni acentos, orden natural), `LibraryTree` (juegos del subárbol, subcategorías con conteo, migas, `knownPaths`), `LibraryHome`/`HomeSections` (Favoritos ≤ 10 con `favoritesTotal`, una estantería por categoría de primer nivel ≤ 10 con su total; sin carpetas no hay estanterías), `HomeSettings` (orden relativo, fijadas, ocultas, fila de Favoritos), búsqueda por categoría y etiquetas, filtro y opciones de etiqueta. |
| Categoría que cuenta | `LibraryPreferencesData.categoryPath` | La virtual si se movió en la app, si no la carpeta. `isMovedInApp` compara con la carpeta real (H10 Android). |
| Huella confirmada | `RomEntry.fingerprintVerified`, `RomFingerprint.swift`, `LibraryStore` | Verdadera con caché verificada, huella calculada o la del núcleo al abrir; falsa con caché obsoleta (pista). `LibraryPreferences.confirmedFingerprint`; `LibraryStore.confirmFingerprint` lee el ROM (solo local o descargado) y une lo provisional. Etiquetas y categoría virtual **solo** se escriben así. |
| Formato 3 | `LibraryPreferences.swift` | `GameMetadata.tags` y `virtualFolder` (en `games[huella]`, nunca en `pendingByPath`), `home`, `categoryLayouts`. El 2 se lee tal cual, copia exacta verificada a `preferences.v2.json` (sin pisar una previa: `preferences.v2-<fecha>.json`) y se reescribe como 3; si la copia falla, se bloquean las escrituras y se avisa. Versión futura (4+): se usa, no se escribe ni se aparta. Un 3 dañado se aparta. Campos nuevos dañados vuelven a su valor por defecto uno a uno. Escritura atómica de N1 sin cambios. |
| Inicio | `LibraryView.swift`, `Library/LibraryHomeViews.swift` | Continuar · Favoritos («Ver todo» → pestaña Favoritos) · estanterías («Ver todo» → categoría) · «Todos los juegos». Con filtro/etiqueta/búsqueda, solo resultados. Horizontal: N3 intacto (filas antes del título fijado). |
| Pantalla de categoría | `Library/CategoryView.swift`, `LibraryRoute.category` | Migas (VoiceOver no lee separadores), subcategorías con conteo, juegos del subárbol, cuadrícula/lista por categoría. Pila restaurable (`@SceneStorage`). |
| Categorías navegan | `LibraryLandscapeTools.swift`, «…» › Categorías | N4A-1: abren su pantalla; ya no filtran en el sitio (`state.libraryCategory` desaparece; `libraryTag` nuevo). Filtros › Etiquetas (horizontal), «…» › Etiqueta y fila «Etiqueta «x» · Quitar» (vertical). |
| Centro de ajustes | `Library/GameCenterView.swift`, `Settings/EmulationSettingsView.swift` (`GameEmulationSections`) | Sustituye a `GameSettingsView`. Nombre, Categoría (+ insignia, «Su carpeta: …», «Cambiar» y «Volver a su carpeta» juntos), Etiquetas (texto), Portada y Progreso deshabilitadas, Copias de la partida (`SaveBackupsView` de esa huella, dentro de la hoja), color/paleta o GBA, Ocultar con confirmación. «Leyendo el juego…» mientras confirma. Pausa sin centro. |
| Mostrar en categoría / Etiquetas | `CategoryPickerView`, `TagEditorView` | Existentes (sangradas, «Su carpeta», «Ahora») o nueva con aviso en vivo «Se usará la que ya existe: …» (H13). Editor: añadir, quitar, «Etiquetas que ya usas». |
| Insignias | `GameCard`, `GameDetailsView` | «Movido en la app»: solo símbolo en tarjetas (texto en VoiceOver), con texto en detalle/centro; detalle con «Se ve en «…»» y etiquetas (N4A-8). |
| Ajustes › Biblioteca › Inicio | `Settings/HomeSettingsView.swift`, `SettingsRoute.libraryHome` | Fila de Favoritos; chincheta, subir/bajar (dentro del grupo), «En el inicio»; Restablecer. |

## Paridad con Android
| Decisión | iOS |
|---|---|
| N4A-1 categorías abren su pantalla | Sí (panel, «…», estanterías). |
| N4A-2 sin carpetas no hay estanterías; «Sin categoría» al final | Sí (`withoutFoldersThereAreNoShelvesButFavoritesStay`). |
| N4A-3 ≤ 10 por fila, total visible (H1) | Sí (`theFavoritesRowShowsTenAndCountsEveryFavoriteOnce`). |
| N4A-4 Favoritos «Ver todo» → pestaña | Sí. |
| N4A-5 virtual por huella compartida por copias, `isMovedInApp` vs carpeta real (H10) | Sí (`copiesShareTheVirtualCategory`). |
| N4A-6 búsqueda por la categoría en que se ve | Sí. |
| N4A-7 reglas de nombres | Sí. |
| N4A-8 etiquetas en detalle y centro, no en tarjetas | Sí. |
| N4A-9 / H11 fijar no reordena; orden relativo; «Sin categoría» al final no se guarda | Sí (`homeSettingsKeepTheRelativeOrderAndPinningDoesNotReorder`). |
| N4A-10 Partida dentro del mismo contexto | Sí: se empuja dentro de la hoja del centro. |
| N4A-11 pruebas de N3 sin estanterías | Sí: `-demoHome off` (oculta Favoritos y todas las categorías con `HomeSettings` real); inicio probado aparte, también en horizontal (H3). |
| H2 centro sin redundancia, H6 «Cambiar categoría», H13 categoría existente, H15 huella perdida | Sí; si al aplicar no hay huella confirmada se avisa en el editor/selector (no se cierra en silencio). |
| Pausa sin centro | Sí. |
| Formato | Android pasa a 4 (venía de 3); iOS pasa de 2 a 3. |

## Comandos y salidas (sobre el árbol final)
```
xcodebuild test -only-testing:PocketGBTests  (17 Pro)  ✔ Test run with 275 tests in 25 suites passed after 10.831 seconds.
                                              (SE 3.ª) ✔ Test run with 275 tests in 25 suites passed after 11.002 seconds.
UI sin catálogo (17 Pro): Executed 20 tests, with 0 failures
UI sin catálogo (SE 3.ª): Executed 20 tests, with 2 failures — ShellLinkTests.testSwitchAndExitTheCable (pause-link-exit);
   falla igual en la base 58cd81b en SE (comprobado con un árbol limpio de 58cd81b): ajeno a N4. CategoriesUITests 4/4 y el resto pasan.
SIM_DEVICE="iPhone 17 Pro" tools/ios-screenshots.sh → testScreenCatalog passed (1684.633 s), 197 PNG,
   xcodebuild Release: exit 0; en esa corrida un test unitario ajeno (GBATests.noSaveWithClockAcceptsAndLoadsTheClockOnlySave,
   Saves/GBA, no tocado) falló una vez; pasó en las otras 4 corridas completas (17 Pro y SE).
Bloque n4- en SE: testScreenCatalog passed (413.801 s); en 17 Pro Max: passed (367.978 s); 32 PNG cada uno.
xcodebuild -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build → ** BUILD SUCCEEDED **
```
254 → 275 tests Swift: `LibraryOrganizationTests` (14) y `LibraryFormatV3Tests` (7) nuevos, ajustes en `LibraryIdentityTests` (versión futura = 4; v1 se escribe como 3). UI: `CategoriesUITests` (4) nuevo; `AdaptiveLibraryUITests` (categoría → pantalla), `ShellLibraryTests` y `ShellAccessibilityTests` con `-demoHome off`.

### Mutaciones (todas detectadas)
| Mutación | Test que falla |
|---|---|
| Escribir con `data.fingerprint(of:)` (pista) | `withoutAConfirmedFingerprintNothingIsWritten` |
| Categoría por prefijo de texto | `syntheticTreeGivesShelvesSubcategoriesBreadcrumbsAndCounts` |
| El inicio ignora las fijadas | `homeSettingsKeep…`, `hiddenAndPinnedCategoriesShapeTheHome` |
| El formato 2 deja de leerse tal cual | `version2…` (LibraryFormatV3Tests) |
| Búsqueda sin categoría ni etiquetas | `aVirtualCategoryIsAppliedAndReverted`, `searchFindsTags…` |
| `isMovedInApp` = hay virtual | `copiesShareTheVirtualCategory` |
| Total de Favoritos recortado | `theFavoritesRowShowsTen…` |
| Sin reutilizar la categoría existente | `typedCategoriesFollow…` |
| Guardar «Sin categoría» al final | `homeSettingsKeep…` |
| Sin copia previa del formato 2 | `version2MigrationIsBlockedWhenTheCopyFails`, `version2CopyNeverOverwritesAPreviousOne` |

## Capturas revisadas (herramienta Read, hojas de contacto)
32 ids nuevos al final de `screens.txt` (`-demoLibrary n4`, juegos libres; `-centerRoute`; `ScreenshotTests` gana `-uiDragTimes`). Revisadas en 17 Pro, SE y Pro Max: inicio vertical claro/oscuro y desplazado, horizontal en reposo y desplazado («Todos los juegos» fijado y grupo), AX5 (títulos de estantería enteros), inicio personalizado, categoría, anidada (cuadrícula, horizontal y lista oscura), virtual «Para jugar», centro (claro, oscuro, horizontal, GBA, AX5), editor de etiquetas, «Mostrar en categoría» (y AX5), detalle movido, filtro por etiqueta, paneles con etiquetas y categorías, Ajustes › Biblioteca › Inicio (y AX5). Rutas: scratchpad `n4ios/full/`, `n4ios/se/`, `n4ios/promax/`.

Defectos vistos en las capturas y corregidos: chips de «Etiquetas que ya usas» sin texto; filas del selector en azul; «Favoritos» partido con AX5; botones «Cambiar»/«Volver» partidos con AX5 y luego solo icono (estilo de etiqueta forzado); `game-context-menu` (línea antigua) dejó de encontrar su tarjeta bajo las estanterías → el router oculta el inicio en esa captura.

Las capturas antiguas de la biblioteca (`library-grid`, `library-list`…) muestran ahora el inicio encima de «Todos los juegos» (es la app real); `library-landscape-category` abre la pantalla de categoría.

## Cambios fuera de bloques nuevos
- `DebugScreenRouter.swift`: casos N4 al final del enum, bloque `applyN4` al final, una llamada tras `applyAdaptive` y otra cuando no hay `-screen`; `libraryLandscapeCategory` abre la pantalla de categoría.
- `screens.txt`: solo líneas añadidas.

## No verificado
- **Restauración de la pila** con la app cerrada por el sistema: `@SceneStorage`; solo el códec tiene test. La prueba de UI (salir al inicio, `terminate`, relanzar) no restaura porque iOS descarta el estado de escena cuando XCTest mata la app; se retiró.
- iPhone de Joel con su carpeta de iCloud (estanterías reales, VoiceOver a mano, rendimiento con muchas categorías, juego sin descargar en el centro).
- No se actualizaron `docs/ESTADO.md` ni la tabla de hitos (lo hace el orquestador al fusionar).
