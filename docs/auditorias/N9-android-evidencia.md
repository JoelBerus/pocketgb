# N9 Android · Evidencia (guía en la app, consejos descartables y documentación)

Generada en el Mac de Joel (Darwin 25.5.0) el 2026-10-09, rama `n9-android-guia` desde `siguiente-nivel` (`8761341`). Plan: [N-README](../hitos/N-README.md) §N9 (parte Android: guía y consejos, documentación 05, 06 Android, 11 y PRUEBAS-JOEL). No se tocaron `docs/ESTADO.md`, `02`, `04`, `ios/` ni `travel/SaveImporter.kt` (cambia en paralelo en `n7-android-config-como-ios`). La parte «Carpetas de Joel» de N9 (Drive e iCloud reales) no es de este lote.

## Commits
| Commit | Qué |
|---|---|
| `b0c8a34` | Ajustes › Guía sin red, cuatro consejos descartables, guías Android al día, tests y catálogo `n9-*` |
| `7054101` | Docs: 05, 11 y PRUEBAS-JOEL consolidado |
| `ab12d59` | Lint: sin avisos nuevos |
| (este) | 06 (parte Android), esta evidencia y `n9-settings` con `swipe=up` (para que «Guía» salga en la captura) |

## Guía (Ajustes › Guía)
- **Una sola fuente.** La tarea Gradle `copyGuideAssets` (`android/app/build.gradle.kts`, API de variantes `addGeneratedSourceDirectory`) copia `docs/guia/*-android.md` y `docs/guia/partidas-continuar-y-renombrar.md` a `assets/guide/` en **todas** las variantes. Nada se duplica en el repo y nada es remoto. Comprobado en los dos APK del árbol limpio: 9 archivos `assets/guide/*.md` en Debug y en Release.
- **Secciones (orden de `GuideLibrary.SECTION_IDS`):** biblioteca, carpetas, categorías, portadas, continuar y renombrar, momentos, viajar, controles y GBA. Son las guías de Android de los temas comunes que pide el plan (categorías, portadas, momentos, viajar, GBA, controles, carpetas) más biblioteca y continuar/renombrar. Las guías del iPhone (`*.md` sin `-android`) y `11-biblioteca-carpetas.md` (documento técnico con referencias a `preferences.json` y a decisiones) no se incluyen; sus enlaces se leen como texto.
- **Render nativo** (`guide/GuideMarkdown.kt`, puro, y `ui/guide/GuideScreens.kt`): el subconjunto de Markdown que usan las guías (títulos, párrafos, listas con viñetas y numeradas y anidadas, tablas, bloques de código, citas, negrita, cursiva, código y enlaces). Sin WebView. Los títulos `##`/`###` son encabezados de TalkBack; los enlaces a otra sección son `LinkAnnotation.Clickable` (TalkBack los anuncia como enlaces) y muestran el título de la sección en vez del nombre del archivo; las **tablas se pintan como fichas** (primera celda como título y cada celda con su cabecera), así no hay desplazamiento horizontal y nada se corta con la fuente al 200 %. El código va en monoespaciada con desplazamiento horizontal propio.
- **Búsqueda simple:** todas las palabras, sin acentos ni mayúsculas («bíos OFICIAL» = «bios oficial»), por apartado; un resultado por apartado con la sección, el apartado y un fragmento; al tocarlo se abre la sección desplazada a ese apartado. Tope de 40 resultados. Mensaje claro si no hay nada.
- **Rutas:** `SettingsRoute.Guide` y `SettingsRoute.GuideSection(sectionId, anchor)` (serializables: la pila se restaura).
- **Contenido al día para la app:** se quitaron de las guías Android las referencias a hitos («(N4)», «Desde N4», «igual que en N3»), se añadió GBA a los filtros y a las reglas de carpetas, `PocketGB/` ya explica `Intercambio/` y los momentos aclaran que «Enviar a otro dispositivo» no los lleva. `GuideMarkdownTest` falla si vuelve a aparecer un «N<número>», Markdown sin interpretar o un título con «Android».

## Consejos descartables
| Consejo | Dónde | Cuándo |
|---|---|---|
| «Prueba sin miedo» | Pausa, bajo **Momentos** | siempre |
| «Ordena sin tocar archivos» | Ajustes del juego, bajo **Categoría › Cambiar** | siempre |
| «Sigue en otro equipo» | Detalle del juego, bajo la fila Jugado/Partida/Tamaño | con el menú de viajar (N7) |
| «Ajusta las flechas» | Ajustes › Controles, bajo **Cruceta** | solo con «Flechas separadas» |

- `tips/Tips.kt`: `Tip` (id estable), `TipsState` observable, `SharedPreferencesTipsStorage` (`shared_prefs/tips.xml`, conjunto de ids descartados). Un fallo al guardar no rompe nada (el consejo se oculta en la sesión). `LocalTips` es `null` por defecto: solo la app (`PocketGBApp`) los muestra, así las pantallas aisladas y las pruebas de otros lotes no cambian.
- `ui/tips/TipCard.kt`: tarjeta `secondaryContainer` con icono, título (encabezado de TalkBack), texto que crece con la fuente y **Entendido** (≥ 48 dp, descripción «Descartar el consejo «…»»).
- Ajustes › Guía › **Volver a mostrar los consejos**.
- Cadenas solo en `res/values/strings_n9.xml`; catálogo `debug/catalog/N9Catalog.kt` (+1 línea en `DebugCatalog.kt`) y 19 líneas añadidas **al final** de `tools/android-screens.txt`.

## Tests nuevos
- **JVM `GuideMarkdownTest` (9):** bloques y en línea (incluida la barra dentro de código en una tabla y marcadores sin cerrar), anclas repetidas, `resolve` solo a secciones empaquetadas, **todas las guías reales** se analizan sin Markdown residual ni referencias a hitos y coinciden con lo que empaqueta Gradle, los enlaces internos muestran el título de su sección, búsqueda (acentos, mayúsculas, apartado, ancla, fragmento, vacía, tope), consejos que siguen descartados tras «reiniciar» y almacenamiento que falla.
- **Instrumentado `GuideUiTest` (9):** entrada «Guía» en Ajustes; las 9 secciones se listan y abren con sus encabezados para TalkBack; buscar «bíos OFICIAL» abre GBA en «La BIOS (opcional)»; sin resultados y borrar la búsqueda; un enlace (`performFirstLinkClick`) abre la otra sección; con fuente al 200 % ninguna ficha de tabla sale del ancho de la pantalla; consejo accesible (descripción y 48 dp) que sigue descartado tras recrear el estado desde disco y vuelve con «Volver a mostrar»; sin `LocalTips` no se muestra; el de flechas no sale con la cruz.

## Verificación desde limpio
`git archive HEAD` (`ab12d59`) en `scratchpad/n9a-clean`, ROMs libres enlazadas (`core/tests/roms`; `gba/tests/roms/gba-tests` y `gba/build/hb` de `pocketgb-n7-android`), `ANDROID_HOME=~/Library/Android/sdk`, Gradle `--no-daemon --max-workers=1` dentro del candado `build`; los consejos están en toda la app (`PocketGBApp`) y la suite completa pasa con ellos; emulador `Small_Phone_API_35` dentro del candado `emu`.

```
$ ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
BUILD SUCCESSFUL in 6m 47s
JVM: 815 tests, 0 fallos (incluye GuideMarkdownTest, 9)
lint: 0 errors, 22 warnings — ninguno en archivos de N9 (los 3 de la primera pasada se corrigieron en ab12d59)
aapt2 dump permissions app-debug.apk / app-release-unsigned.apk → 0 permisos de red (solo DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, la de siempre)
unzip -l <apk> | grep -c assets/guide/ → 9 en Debug, 9 en Release

$ adb shell am instrument -w com.joelbermudez.pocketgb.test/androidx.test.runner.AndroidJUnitRunner   (suite completa, sin filtro)
OK (494 tests)   — Time: 656 s; incluye GuideUiTest (9) y CatalogCoverageTest con los 19 ids n9-*
```

## Capturas (revisadas una a una; scratchpad `n9a-shots/`)
`tools/android-screenshots.sh` con los 19 ids `n9-*` → 19 capturas. Revisado:
- `n9-settings` (desplazada): «Guía» en el último grupo, entre Apariencia y Acerca de, con icono de libro.
- `n9-guide` (claro, oscuro, ×2): introducción «sin conexión», buscador y las secciones; con fuente al 200 % los títulos ocupan varias líneas sin cortarse.
- `n9-guide-search`: «1 resultado» → «Game Boy Advance / La BIOS (opcional)» con un fragmento del texto (en la primera pasada el fragmento repetía el título del apartado; corregido antes del commit).
- `n9-guide-search-empty`: mensaje «Nada en la guía con «zzz»».
- `n9-guide-section` (claro, oscuro, ×2): GBA con código en línea resaltado, bloque de código monoespaciado y listas; al 200 % el texto fluye sin cortes.
- `n9-guide-section-anchor`: abre momentos en «Cargar un momento (importante)».
- `n9-guide-section-table` y `-landscape`: la tabla de iconos en horizontal como fichas («Lupa · Buscar», «Embudo · Filtros»…), también a lo ancho.
- `n9-tip-pause` (oscuro): la tarjeta bajo «Momentos», sin tapar Continuar ni Personalizar.
- `n9-tip-category`: la tarjeta bajo «Categoría › Cambiar».
- `n9-tip-send` (claro, oscuro): bajo «Jugado · Partida (iPhone de Joel · hace 2 h) · Tamaño».
- `n9-tip-arrows` (y ×2): bajo la cruceta con «Flechas separadas».

## Documentación
- [05-android-spec](../05-android-spec.md): estado real (A1–A9, N1–N9), portadas, momentos y progreso, partidas que viajan, guía y consejos, `FileProvider` privado, equivalencias nuevas con iOS y estado por hito.
- [06-testing](../06-testing.md) (solo «Pruebas de Android»): cifras de N9, receta de verificación desde limpio, qué cubre cada suite por hito, kill-test GB/GBA, catálogo con ids al final y directorio propio, candado del emulador y límites (Drive real).
- [11-biblioteca-carpetas](../11-biblioteca-carpetas.md): estado N9 (todo vale en las dos apps), GBA y portadas sin «cuando llegue», `PocketGB/Intercambio/`, y qué metadatos viajan y se fusionan (ND20 i).
- [PRUEBAS-JOEL](../PRUEBAS-JOEL.md): consolidado de A9 y N1–N8. Primero **N7-1** (Rojo iPhone → Android → iPhone con continuación exacta, con SHA-256 del `.sav` antes y después) y **N7-2** (bandeja `PocketGB/Intercambio/` con Google Drive real), el resto de N7 en las dos plataformas, después Android A9, N1–N6, N8 y N9 e iPhone N1–N6. Decisiones pendientes al día (ND20, N5, N9, PR a `main`; la PR #18 ya se fusionó; K10 sustituida por ND17).

## Fuera de alcance o pendiente
- **Carpetas de Joel** (Drive `Roms` e iCloud `GMRoms`): inventario, movimientos, `_Revisar/`, portadas (ND4) y `LEEME-PocketGB.txt` exigen su confirmación explícita; no se tocaron.
- `docs/LEEME-PocketGB.txt` sigue diciendo que GBA llega a Android «cuando llegue» y que las portadas «llegarán»: es del bloque de carpetas de Joel (el texto se copia a su carpeta); conviene ponerlo al día allí.
- `android/README.md` aún describe «A1 a A8»; no estaba en el encargo.
- Pruebas reales de la guía y los consejos: PRUEBAS-JOEL N9-1…N9-3.
