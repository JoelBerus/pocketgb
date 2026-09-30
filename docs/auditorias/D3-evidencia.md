# D3 · Evidencia (biblioteca visual, búsqueda y detalle)

Rama `d2-a1-wip` (encima de D2 A1–A3), CI del runner propio en el Mac de Joel.

## CI
| Commit | Resultado |
|---|---|
| `1bb1a8e` | success: 56 tests unitarios, 5 de UI (incluido `ShellLibraryTests.testDetailsAndHideGame`). 2 warnings nuevos en Release (`AppState.swift`, `inMemory`) y el campo de búsqueda minimizado no se expandía en las capturas `search-*`. |
| `7effb5a` | success, sin warnings. La búsqueda seguía sin expandirse (`.searchToolbarBehavior(.minimize)` + `isPresented`). |
| `29f3c78` | success, sin warnings: `** TEST SUCCEEDED **`, 56 tests. Búsqueda siempre visible (`.navigationBarDrawer(displayMode: .always)`): teclado, query y filtros visibles. |

## Criterios CI (D-README §5)
| Criterio | Verificación |
|---|---|
| Grid y lista con el mismo modelo | `LibraryQuery.visible` (tests `filtersAreAllGBGBCAndFavorites`, `recentSort…`) |
| Filtros Todos/GB/GBC/Favoritos, sin GBA | Test unitario y `ShellLibraryTests` (no existe el botón "GBA") |
| `searchable` filtra en vivo | `searchIsLiveCaseAndAccentInsensitiveWithinTheFilter`; capturas `search-results` / `search-no-results` |
| `.searchToolbarBehavior(.minimize)` "donde corresponda" | **No se usa**: minimizado no se podía expandir de forma determinista; en la biblioteca la búsqueda es la acción principal y queda siempre visible. |
| Zoom portada → detalle; Reduce Motion lo evita | `matchedTransitionSource` + `ZoomNavigation`, política `PocketMotion.reducesMotion` (`-reduceMotion`) |
| Cards sin vidrio | `GameCard`/`GameListItem`: solo `Image`/`Canvas` |
| Continue con captura real/local | Solo aparecen juegos con portada guardada (`GameArtworkStore`) |
| Placeholder determinista por huella | `placeholderIsDeterministicPerSeed` (FNV-1a) |
| Sin red | grep de `URLSession|NWConnection|NSAppTransportSecurity`: sin coincidencias |
| Ocultar persiste la huella y no borra nada | `hidingPersistsTheFingerprintAndNeverTouchesFiles` (ROM y `.sav` intactos byte a byte) |
| Menú y confirmación dicen qué se conserva | Capturas `game-context-menu`, `remove-game-confirm` |
| Títulos largos sin solapar badges | Chip/favorito/"Nuevo" en línea propia (`library-grid`, `library-list`) |

## Revisión visual (claro y oscuro)
| Captura | Resultado |
|---|---|
| library-grid, library-continue | ✅ Filtros, "Continuar jugando" con el botón de vidrio sobre la captura, cuadrícula 2 columnas |
| library-list | ✅ Miniatura, título, chip, favorito y fecha; el título largo pasa a 2 líneas |
| favorites | ✅ Carril de recientes y cuadrícula de favoritos |
| search-active / results / no-results | ✅ (`29f3c78`) Campo, teclado y filtros; "2 resultados"; "Sin resultados para “CGB”" con "Buscar en todos". En `search-active` claro sale el aviso del teclado bilingüe del simulador (primer arranque del sistema, no de la app). |
| game-details | ✅ Captura 10:9 nearest, metadatos, "Continuar" dominante, Favorito/Estados/Ajustes y "Ocultar de PocketGB" |
| game-context-menu | ✅ Preview, Jugar, Ver detalle, Favoritos, Estados y Ajustes (próximamente), Ocultar en rojo; nada de borrar el ROM |
| remove-game-confirm | ✅ Explica que el ROM y la partida se conservan |
| settings-library | ✅ Carpeta, vista, orden y juegos ocultos con "Mostrar" |
| D2 (cloud, errores, escaneo) | ✅ Siguen correctas con las nuevas cards/filas |

Observación: `library-continue` y `library-grid` muestran lo mismo (el carril está arriba de la cuadrícula).

## Pendiente del iPhone
Calidad del zoom, menú contextual con presión real, scroll y minimización de la tab bar, búsqueda con teclado, rotación fuera del juego y la portada creada al terminar una sesión real.
