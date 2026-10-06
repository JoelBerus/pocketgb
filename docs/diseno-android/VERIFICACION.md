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
