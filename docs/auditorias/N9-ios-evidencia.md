# N9 iOS · Guía consolidada, consejos y documentación: evidencia

Rama `n9-ios-guia` (desde `siguiente-nivel` en `8761341`). Solo la parte iOS de N9 ([N-README](../hitos/N-README.md) §N9); Android va en paralelo en `n9-android-guia`. No toca `ESTADO.md`, `PRUEBAS-JOEL.md`, `docs/11` ni `docs/05`. Las carpetas de Joel (inventario, movimientos, portadas, `LEEME`) no son de este lote.

## Qué se hizo
| Pieza | Dónde |
|---|---|
| **Ajustes › Guía** (sección «Ayuda»): índice de 9 secciones, búsqueda local sin mayúsculas ni acentos, sección dibujada con vistas nativas; los resultados abren la sección en su bloque, resaltado | `ios/PocketGB/Guide/Guide.swift` (modelo, `GuideParser`, búsqueda), `Guide/GuideView.swift`, `Settings/SettingsView.swift`, `SettingsRoute.guide/.guideSection` |
| Contenido empaquetado sin red: `Resources/Guide/guia-<id>.md`, copiado de `docs/guia` (enlaces a Android y al repo fuera; enlaces entre secciones → `guia:<id>`, que abre la sección dentro de la app; `OpenURLAction` descarta cualquier otro esquema) | `tools/ios-guide-sync.py`; `tools/ios-screenshots.sh` ejecuta `--check` al empezar |
| Guías del iPhone al día: portadas y momentos ya no dicen «Próximamente» ni «llegará»; nuevas `continuar.md` (continuar, pausa, renombrar, cable link) y `gba.md` (BIOS, L/R, partidas) | `docs/guia/{carpetas,biblioteca,categorias,momentos,controles,continuar,gba}.md` |
| Accesibilidad: títulos con rasgo de encabezado (rotor de VoiceOver), listas y fichas de tabla combinadas en un elemento salvo si llevan enlace, el árbol de carpetas en un bloque desplazable con etiqueta «Ejemplo», índice con el icono encima en tamaños de accesibilidad | `GuideView.swift` |
| **TipKit** en 4 puntos: «Guarda este instante» (pausa, sobre Momentos), «Cámbialo de categoría» (ajustes del juego), «Sigue en otro equipo» (detalle › Partida), «Prueba las flechas separadas» (Ajustes › Controles, solo con la cruz). Almacén local, `displayFrequency(.daily)`, sin CloudKit. Cada uno se invalida al usar lo que explica | `Guide/PocketTips.swift`, `PocketGBApp.init`, `PauseView`, `GameCenterView`, `GameSaveTravelSection`, `ControlsSettingsView`, `AppState.createMoment`, `AppState.exportPackage` |
| Corte por sílabas de «Momentos»/«Hitos» con AX5 (observación de N7): los dos botones van en fila si caben y, si no, uno debajo del otro, sin partir la palabra | `Progress/ProgressViews.swift` (`ViewThatFits`) |
| Acerca de: «Consolas» incluye Game Boy Advance; la guía y los consejos van dentro de la app | `Settings/AboutView.swift` |
| Docs | `docs/04-ios-spec.md` (estructura de fuentes, controles N2, estado automático/momentos/progreso N6, portadas N5, viajar N7, guía y consejos N9, GBA 32 MiB, mando L/R), `docs/02-arquitectura.md` (API C de progreso y `.pgbm`, trabajo de fondo, datos persistentes N1–N9, por dispositivo), `docs/06-testing.md` (suites por hito, catálogo, `--check`, revisión de red, checklist) |
| Tests y catálogo | `ios/PocketGBTests/GuideTests.swift` (13); 13 casos `n9*` al final de `DebugScreen` + `applyN9` al final de `DebugScreenRouter.swift` (una línea que lo llama tras `applyN7`); 20 capturas (más comentarios) al final de `screens.txt` |

## Decisiones
- **Una sola fuente:** la guía vive en `docs/guia`; el bundle lleva una copia derivada. Si alguien cambia una guía del iPhone y no ejecuta `tools/ios-guide-sync.py`, `tools/ios-screenshots.sh` (y el CI) fallan.
- **Markdown propio y acotado** en lugar de una dependencia (regla 5, sin paquetes): títulos, párrafos, listas con sangría, tablas, código y citas; lo desconocido es párrafo, nunca falla.
- **Tablas como fichas** (primera celda de título, las demás debajo con su cabecera si hay más de dos columnas): se leen igual con AX5 y VoiceOver que en tamaño normal.
- **Consejos en línea** (`TipView`) y no en globo: no tapan controles, crecen con el texto y no dependen de la geometría de la pausa.
- Títulos de sección cortos («Carpetas», «Controles»…) para que no se partan con AX5.

## Comandos y salida
`tools/ios-screenshots.sh` completo (iPhone 17 Pro, iOS 26.5; con el candado del simulador):
```
Guía del bundle al día (9 secciones).
✔ Test run with 371 tests in 31 suites passed after 13.757 seconds.
Executed 21 tests, with 0 failures (0 unexpected)          (UI: ShellTests, AccessibilityTests, CategoriesUITests, AdaptiveLibraryUITests, ScreenshotTests)
xcodebuild Release: exit 0
xcodebuild test: exit 0
265 capturas
```
iPhone SE (3.ª gen.), solo unitarias:
```
xcodebuild test … -destination 'platform=iOS Simulator,name=iPhone SE (3rd generation)' -only-testing:PocketGBTests
✔ Test run with 371 tests in 31 suites passed after 14.060 seconds.
** TEST SUCCEEDED **
```
Release para dispositivo y revisión de red:
```
xcodebuild build -scheme PocketGB -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
** BUILD SUCCEEDED **
nm -u PocketGB.app/PocketGB | grep -iE "URLSession|nw_connection|CFNetwork|CloudKit|CKContainer"   → sin resultados
ls PocketGB.app | grep guia | wc -l   → 9
grep -c NSAppTransportSecurity ios/PocketGB/Resources/Info.plist   → 0
python3 tools/ios-guide-sync.py --check   → Guía del bundle al día (9 secciones).
```
`GuideTests` (13): cada sección está en el bundle, viene de `docs/guia` y tiene apartados; ningún `http`, `../` ni guía de Android, y todo enlace es `guia:<id>` a una sección que existe; lector (títulos, comentario, párrafo de varias líneas, cita, listas anidadas y numeradas con continuación, tabla sin la fila separadora, código literal y sin cerrar); texto plano; búsqueda sin mayúsculas ni acentos, con todas las palabras, por título de sección, sin duplicar títulos; temas clave de la guía real («antes de cargar», «pgbm», «separacion», «gba_bios», «_Revisar», «volver a su carpeta»); fragmento corto, centrado y sin cortar palabras.

## Capturas revisadas
Todas las `n9-*` una a una (claro, oscuro, horizontal y AX5):
- `n9-settings`: sección «Ayuda › Guía».
- `n9-guide` (claro, oscuro, horizontal) y `n9-guide-ax5`: índice; con AX5 el icono va encima y el título («Carpetas») no se parte.
- `n9-guide-search` («momento», «categoria» en oscuro): primero la sección cuyo título coincide, después los apartados; fragmentos sin palabras cortadas y sin títulos repetidos.
- `n9-guide-section` (Carpetas con el árbol desplazable, Controles en oscuro con las tablas como fichas, Viajar en horizontal con ancho máximo 720 pt) y `n9-guide-section-ax5` (claro y oscuro): sin recortes; el árbol de carpetas se desplaza en horizontal.
- `n9-guide-focus`: «recuperar» abre Momentos en «Me equivoqué: «Recuperar»», resaltado.
- `n9-tip-pause`, `n9-tip-game-center`, `n9-tip-send`, `n9-tip-controls` (claro, oscuro) y `n9-tip-controls-ax5`: cada consejo en línea, con su botón de cerrar, sin tapar nada.
- `n9-progress-details-ax5`: «Momentos» e «Hitos» uno debajo del otro, enteros. En tamaño normal (`n6-progress-details`) siguen en una fila.
- Regresión: el resto del catálogo (las capturas de N1–N7 y las de diseño) pasa sin cambios de comportamiento; los consejos no aparecen en ninguna captura sin `-showTips`.

## Límites y pendientes
- El árbol de carpetas con AX5 obliga a desplazar en horizontal (es texto alineado en columnas; partirlo lo haría ilegible).
- TipKit muestra un consejo al día como mucho; en el iPhone de Joel conviene comprobar que no molestan (checklist de [06-testing](../06-testing.md)).
- La revisión con VoiceOver real (rotor de encabezados, enlaces dentro de la guía) queda para la prueba manual de Joel; en el simulador se comprobó la estructura (encabezados, elementos combinados) por código y capturas.
