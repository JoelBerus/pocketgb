# Verificación visual de Android (A6-L5)

Catálogo: `tools/android-screens.txt` (A6: 61 ids, 106 capturas; con A7: 75 ids, 125 capturas) generado con `tools/android-screenshots.sh` en el emulador `Small_Phone_API_35` (720x1280, sin ventana, animaciones a 0). Salida: `android/build/screenshots/<id>-<orientación>-<tema>.png` (no se versiona). Corrida final sobre `d72d9ad` más los arreglos de esta revisión. Claude miró cada PNG (hojas de contacto con la herramienta Read) contra lo que debe verse.

## Ciclo
1. `cd android && ./gradlew --no-daemon --max-workers=1 :app:installDebug` y `tools/android-screenshots.sh` (variables `SCREENS`, `THEMES`, `RESUME=1`).
2. El script gira el emulador para las líneas `landscape`, espera a que la app tenga el primer plano (reintenta hasta 3 veces si hay ANR, lanzador o pantalla de arranque) y fija semillas de color dinámico (verde `FF2E7D32`, violeta `FF6A1B9A`) en Android 12+.
3. `CatalogCoverageTest` cruza el manifiesto con el registro `catalogScreens` del catálogo.

## Revisión por captura
Leyenda de variantes: C claro, O oscuro, D1/D2 color dinámico verde/violeta. Todas las capturas listadas se revisaron en todas sus variantes.

| Id (variantes) | Debe verse | Se ve | Resultado |
|---|---|---|---|
| launch (C,O) | Biblioteca lista con carril y portadas | Carril con 3 portadas, cuadrícula debajo | ✅ (el splash del sistema no es capturable; icono real es A8) |
| library-empty, library-folder-empty (C,O) | Sin carpeta / carpeta sin juegos con acciones | Icono, título, texto y botones | ✅ |
| library-cloud-pending (C,O) | Cuadrícula con una entrada remota con aviso | Tarjeta atenuada con insignia de aviso | ✅ |
| library-cloud-downloading (C,O) | Cortina «Abriendo el juego…» sobre la biblioteca | Cortina con progreso | ✅ |
| library-scan-progress (C,O) | Barra 3 de 8 sobre el contenido | Barra y «Buscando juegos… 3 de 8» | ✅ |
| library-scan-summary (C,O) | Aviso «2 juegos nuevos» | Snackbar visible | ✅ |
| library-continue, library-grid (C,O,D1,D2) | Carril «Continuar jugando» con portadas; cuadrícula | Carril con 3 portadas generadas, botón Continuar sobre la portada | ✅; la segunda tarjeta asoma cortada («Con»), es el carrusel |
| library-grid-scrolled (C,O) | Tarjetas, favoritos, «hace 3 días», juego con problema | Tarjetas con portadas y placeholders, estrellas | ✅ |
| library-list (C,O) | Filas con miniatura, chips y problema en rojo | Correcto, ROTO atenuado con aviso | ✅ |
| search-active (C,O) | Campo enfocado con teclado | Campo con foco y teclado | ✅ (el teclado del sistema sigue claro en O) |
| search-results (C,O) | «3 resultados» con `query=te` | 3 filas | ✅ |
| library-search (C,O) | Sin resultados + «Buscar en todos» | Texto del filtro GBC y botón | ✅ |
| library-error, library-access-error, library-loading (C,O) | Estados de error y carga | Sin cortes | ✅ |
| game-context-menu (C,O) | Menú sobre la primera tarjeta | Menú completo (Jugar, Detalle, Favoritos, Estados deshabilitado, Ajustes, Ocultar) | ✅ (se abre sobre el carril porque la tarjeta 1 está bajo el pliegue) |
| remove-game-confirm (C,O) | Diálogo de ocultar | Texto y botones | ✅ |
| game-settings (C,O) | Hoja con valores «Personalizado» | Color y Paleta personalizados, «Usar los ajustes globales» | ✅ con el artefacto D5 |
| library-detail (C,O,D1,D2), library-detail-played, library-detail-problem (C,O) | Portada, acciones, datos | Portada generada o capturada, error en contenedor rojo | ✅ |
| favorites-empty, favorites (C,O) | Vacío / recientes con portadas y favoritos | Correcto | ✅ (D8) |
| settings-main (C,O) | Tres grupos con 9 filas | Visibles hasta Apariencia; el resto por desplazamiento | ✅ (D8) |
| settings-controls (C,O,D1,D2) | Opacidad, cruceta, tamaño | Segmentados legibles, pies | ✅ |
| settings-display, settings-emulation, settings-audio (C,O) | Vista previa, color y paleta 5, volumen 60 % | Correcto | ✅ |
| settings-storage (C,O) | Tamaños y Borrar portadas | `96 KiB`, `3,2 MiB`, `400,4 KiB` | ✅ tras D1 |
| settings-library, settings-licenses, saves-settings, appearance, about, native-video (C,O) | Pantallas existentes | Sin cambios de aspecto salvo los pies nuevos | ✅ |
| gameplay-controls, gameplay-fast-forward (O) | Imagen 10:9 a todo el ancho, controles, HUD | Barras ocultas, HUD con ×4 en naranja | ✅ |
| gameplay-landscape, -clear, -hidden (O) | Controles; sin controles (clear); sin controles con HUD (hidden) | Los controles solapan algo la imagen (16:9); clear y hidden sin controles | ✅ (D7) |
| gameplay-portrait-arrows, gameplay-landscape-arrows (O) | Cruceta como cuatro flechas | Cuatro flechas separadas | ✅ |
| gameplay-fill (O) | Controles legibles sobre imagen clara | Capa oscura bajo cada control, etiquetas legibles | ✅ (D7) |
| customize-controls-portrait, -landscape, -size (O) | Barra del editor; A seleccionado a 130 % | Barra con Restablecer/Listo, −/+; selección punteada | ✅ tras D2 |
| pause-sheet, pause-dialog, states-sheet, states-dialog (O) | Hoja o diálogo con título del juego, Personalizar controles | Correcto | ✅ (D5, D6) |
| load-state-confirm, replace-state-confirm (O) | Diálogos sobre Estados | Tres botones / fecha de la ranura | ✅ (D5) |
| exit-save-failed, exit-risk, save-warning, open-error, save-problem, states-rescue (O) | Diálogos y avisos de A5 en oscuro | Sin cambios de contenido | ✅ |
| gameplay-header-damaged (O) | Aviso «Cabecera dañada» sobre el juego | Snackbar inferior | ✅ |

## Defectos y desviaciones del catálogo
| Id | Hallazgo | Estado |
|---|---|---|
| D1 | `settings-storage` mostraba `98.30 kB` con el formateador del sistema (inglés), distinto de `KiB` con coma del resto | Corregido (`ByteFormat`), commit `78d8f5e` |
| D2 | `customize-controls-landscape`: el pie de ayuda cruzaba la cruceta y B | Corregido (ancho máximo 320 dp), commit `78d8f5e` |
| D3 | Con el Mac saturado salían capturas del lanzador, del splash o de un ANR | Corregido en el script (primer plano + reintentos) |
| D4 | La primera captura horizontal salía vertical | Corregido en el script (espera la rotación) |
| D5 | Las hojas modales muestran el tooltip «Drag handle» | Pendiente: aparece en todas las hojas; sospecha de foco de teclado del emulador, no comprobado en teléfono (A7) |
| D6 | `pause-dialog` horizontal: «Salir del juego» no cabe en 360 dp de alto; se llega desplazando | Pendiente (A7, horizontal) |
| D7 | En 1280x720, Llenar y Entero coinciden (720 = 5 × 144); `gameplay-fill` no distingue ambos | Documentado; haría falta otra altura |
| D8 | Resaltado gris del primer elemento (Emulación, «hace 1 h», «Jugar») | Probable foco de teclado del emulador; no comprobado |
| D9 | Color dinámico: el valor se cambia con `settings put secure theme_customization_overlay_packages` (comillas remotas) | Verde y violeta se ven distintos del azul por defecto; no se comparó el matiz exacto |

## Lo que el catálogo no ve
Tacto real, háptica, audio, fps, carpeta SAF real, cutouts reales, rotación sin pausa, mando real y TalkBack (A7: ver la sección A7 y `docs/auditorias/A7-android-evidencia.md`).

## A7 (L4): mando, accesibilidad, ventana ancha y recorte
Catálogo ampliado a 75 ids y 125 capturas (los 14 ids nuevos suman 19 capturas) en `Small_Phone_API_35` (720x1280, 320 dpi), código `b734e03`. Argumentos nuevos de `DebugIntent`: `controller=1`, `showTouch=1`, `fontScale=2.0` (el script lo manda como `--ef`), `contrast=high|medium`, `reduceMotion=1`. `buildVariantContent` los aplica **fuera** de `PocketGBTheme` (`LocalDensity` + `LocalAccessibilityOverrides`). Argumentos solo del script: `window=wide` (`wm size 1280x800` + `wm density 240` = 853 dp, rotación 0; se restaura con `wm size reset`/`wm density reset`, también en el `trap`) y `cutout=tall` (`cmd overlay enable --user 0 com.android.internal.display.cutout.emulation.tall`, disponible en este AVD; si falta el overlay, el id se omite con el aviso «requiere AVD con recorte»).

| Id (variantes) | Debe verse | Se ve | Resultado |
|---|---|---|---|
| gameplay-controller (O, horizontal) | Sin controles táctiles; HUD de pausa y velocidad | Imagen centrada, solo pausa y avance rápido | ✅ (no hay «pista» de pausa en pantalla: la pista del plan se limita al pie de Ajustes) |
| gameplay-controller-touch (O) | Con `showTouch=1` los controles siguen | Cruceta, A, B, Select, Start y HUD | ✅ |
| settings-controller-mapping (C,O) | Seis acciones con el botón asignado | Botón A = Derecho (B), Botón B = Inferior (A), Start, Select, Menú = Mode / L1, Avance = R1; pie con la regla | ✅ («Restablecer» queda bajo el pliegue) |
| controller-assign-dialog (C,O) | «Pulsa un botón del mando» para A | Diálogo con título, «Para «Botón A». Atrás cancela.» y Cancelar | ✅ |
| library-ax5 (C,O) | Una columna, carril en columna, sin texto cortado | Una columna, títulos en 2 líneas, carril apilado; el botón «Continuar» salía como «Conti» | **Defecto L4-D1 corregido** (`546d220`: el botón ocupa todo el ancho bajo portada y texto); tras el arreglo se ve «Continuar» completo. Los chips de filtro se desplazan en horizontal («Fa…» asoma): comportamiento esperado |
| library-detail-ax5 (C) | Filas apiladas | Portada, título, botón Continuar a todo el ancho; filas apiladas bajo el pliegue | ✅ |
| settings-ax5 (C) | Filas legibles | Títulos a 200 % sin cortes, 7 filas visibles | ✅ |
| library-high-contrast (C,O) | Esquema de contraste alto, sin color dinámico | Texto y bordes más fuertes que el estándar; diferencia sutil en la biblioteca | ✅ (el contraste se aprecia más en los controles) |
| gameplay-high-contrast (O) | Controles sólidos pese a `opacity=30` | Cruceta, A y B con relleno opaco y anillo claro | ✅ |
| gameplay-landscape-high-contrast (O, horizontal) | Igual en horizontal | Controles sólidos, Select/Start con anillo | ✅ |
| pause-sheet-ax5 (O) | Hoja de pausa con fuente grande | Hoja completa, botones y pies sin cortes | ✅ |
| library-wide-rail (C,O, 1280x800) | NavigationRail con tres destinos | Rail a la izquierda (Biblioteca seleccionada, Favoritos, Ajustes) y contenido ancho | ✅ |
| library-list-detail (C, 1280x800) | Lista y detalle a la vez | Biblioteca a la izquierda y detalle de POKÉMON RED a la derecha | ✅ (estructura; `ENABLE_LIST_DETAIL` sigue en `false`: no está conectada a la navegación) |
| gameplay-cutout-landscape (O, horizontal) | Controles fuera del recorte | Cruceta desplazada a la derecha respecto a `gameplay-landscape-high-contrast` | ✅ con reserva: `screencap` no dibuja el recorte, solo se ve el desplazamiento de los controles |

Regresión visual de L2/L3 en A6 (recaptura de `library-grid` C, `library-detail` C, `gameplay-controls` O, `pause-sheet` O y `settings-main` O sobre `f00beec`): sin cambios respecto a A6 (carril con portadas, detalle con datos, controles y HUD, hoja de pausa y Ajustes completos).

Defectos A7: L4-D1 corregido (arriba, dos commits: `f00beec` partía «Continuar» en «Conti/nuar» y `546d220` lo resuelve). Pendientes: ninguno grande. Observación (no es defecto de L4): con `controls=hidden` los nodos virtuales de TalkBack siguen existiendo (los controles ocultos siguen siendo pulsables).

## N3: biblioteca y detalle adaptables
Bloque añadido al final de `tools/android-screens.txt`: **22 ids y 27 capturas** (el manifiesto queda en 134 ids y 204 capturas). Catálogo `debug/catalog/N3Catalog.kt`: los juegos sintéticos repartidos en carpetas (Aventuras, Pokémon con una subcarpeta, Pruebas, Puzles y uno en la raíz), cinco con portada y fecha de juego y **cuatro reanudables**: COLOR DEMO tiene portada y se jugó pero no tiene estado automático, así que no sale en el carril (ND15). Las pantallas van dentro de `AppScaffold`: barra inferior en vertical y NavigationRail en horizontal (640 dp) y en la ventana ancha. Capturas de `c49f7e7` en `Small_Phone_API_35` (720x1280, 320 dpi), claro y oscuro, revisadas en hojas de contacto con la herramienta Read; y un subconjunto en `Pixel_Fold_API_35` desplegado (2208x1840, 420 dpi).

`tools/android-screenshots.sh` gira ahora también con `wm user-rotation lock` y repite la orden a mitad de la espera: en este emulador el giro por `settings` se perdía a veces al relanzar la app y salían capturas «horizontales» en vertical (12 de 20 en una corrida); con el cambio, 0 de 20.

| Id (variantes) | Debe verse | Se ve | Resultado |
|---|---|---|---|
| n3-rail-portrait (C,O) | Carril con el ancho de las 2 columnas, alineado con la cuadrícula, sin trozo de la tercera tarjeta | 2 tarjetas de 158 dp alineadas con las celdas; la fila acaba en el margen; «Roms» junto a su icono | ✅ |
| n3-rail-portrait-ax5 (C) | Fuente 200 %: tarjetas apiladas, portada a la izquierda, «Continuar» entero | Una columna, «Continuar» a todo el ancho | ✅ |
| n3-library-landscape (C,O) | Horizontal: sin buscador ni chips, rail de navegación, carril de 3 columnas, barra flotante a la derecha | Como se pide; la barra flotante tapa el texto de la 3.ª tarjeta del carril arriba del todo (flota, como en iOS; al desplazar se ve) | ✅ |
| n3-library-landscape-ax5 (C) | Fuente 200 % en horizontal: una tarjeta por fila que cabe en alto, «Continuar» a la vista | Portada a la izquierda limitada en alto, «Continuar» a su medida a la izquierda, lejos de la barra | ✅ |
| n3-library-landscape-scrolled (C) | Barra superior plegada, título de sección | «Biblioteca» oculta, carril subido, «Todos los juegos · Roms» | ✅ |
| n3-library-landscape-filters (C,O) | Panel de filtros hacia arriba, pegado a la barra, los 4 en una fila | Panel sobre la barra, «Todos» marcado | ✅ |
| n3-library-landscape-categories (C) | Categorías con cuántos juegos y «Sin categoría» | Todas, Aventuras (2), Pokémon (2), Pruebas (1), Puzles (2), Sin categoría (1); arriba del todo el título de sección aún no se ve, así que el panel sube hasta la barra superior | ✅ |
| n3-library-landscape-view (C) | Vista y Orden en dos filas con etiqueta | «Vista: Cuadrícula/Lista», «Orden: Nombre/Jugados recientemente» | ✅ (en una corrida anterior el panel no salía: se cerraba al arrancar en vertical; corregido en `c49f7e7`) |
| n3-library-landscape-search (C) | Campo en la barra superior con teclado, sin barra flotante | «po», 2 resultados, teclado | ✅ (el teclado ocupa casi todo el alto, como en cualquier app en horizontal) |
| n3-library-landscape-search-results (C) | Búsqueda abierta sin teclado: lista de resultados | «o», 6 resultados en lista | ✅ |
| n3-library-landscape-category (C) | Título de sección «Pokémon» (con su subcarpeta) y sin carril | POKÉMON RED y POKÉMON YELLOW, botón de categorías relleno | ✅ |
| n3-library-landscape-list (O) | Lista con categoría | «Aventuras»: 2 filas | ✅ |
| n3-library-portrait-category (C) | Vertical con categoría: el título la nombra | «Puzles», 2 tarjetas | ✅ |
| n3-library-wide, -wide-filters (C / O, 853 dp) | 4 columnas, carril alineado, panel sobre la barra | Como se pide; el panel tapa «Roms» del título de sección pero no el título (queda a la izquierda) | ✅ |
| n3-details-portrait (C,O) | Imagen ≤ 45 % del alto, Jugar visible, Favorito/Estados/Ajustes sin cortar | Imagen de 224 dp; botones con el icono encima | ✅ |
| n3-details-portrait-ax5 (C) | Fuente 200 %: Jugar visible | Título y «Jugar» justo debajo, antes de la ruta | ✅ |
| n3-details-landscape (C,O), -resume (C) | Dos columnas, imagen limitada en alto, Jugar/Continuar visible | Imagen a la izquierda, información con su scroll; «Continuar» y «Jugar desde el inicio» a la vista | ✅ |
| n3-details-landscape-ax5 (C) | Dos columnas con fuente 200 % y Jugar visible | Título en 2 líneas y «Jugar» bajo él | ✅ |
| n3-details-landscape-scrolled (C) | La columna derecha se desplaza sola | Checksums, SHA-256 y Ocultar; la imagen sigue fija | ✅ |
| n3-details-wide (C) | Dos columnas en 853 dp | Columna de información ancha, botones con el icono encima | ✅ |
| Regresión: launch, library-continue, library-grid (C,O), library-ax5 (C,O), library-detail (C,O), library-detail-ax5 (C) | Lo de antes, con el carril nuevo | Carril con 2 columnas alineadas; el detalle de siempre ya no corta «Favori/Estad/Ajust» | ✅ |

**Pixel Fold desplegado** (`n3-rail-portrait`, `n3-library-landscape`, `-filters`, `-categories`, `-scrolled`, `n3-details-portrait`, `-landscape`, `-landscape-resume`, claro). Su orientación natural es apaisada, así que las líneas «portrait» salen a 2208x1840 (841 × 701 dp, más ancho que alto) y las «landscape» a 1840x2208: con 2208x1840 la biblioteca usa la barra flotante con 4 columnas y el carril alineado; con 1840x2208, buscador y chips con 3 columnas y el carril alineado; el detalle va a dos columnas en las dos posturas (≥ 600 dp) con «Jugar» visible. En las dos capturas de paneles a 1840x2208 no hay panel: en esa postura no hay barra flotante (correcto). `screencap` en el plegable antepone un aviso de «varias pantallas» al PNG; se quitó a mano (los 347 primeros bytes) antes de mirarlas.
