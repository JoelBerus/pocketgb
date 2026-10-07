# N5 · Portadas (iOS): evidencia

Rama `n5-ios-portadas`, creada desde `siguiente-nivel` (que ya tiene N4 iOS y N5 Android). Esta es solo la parte iOS de N5 ([N-README](../hitos/N-README.md) §N5). Hay paridad con Android ([N5-android-evidencia](N5-android-evidencia.md), [respuesta](N5-android-respuesta.md)). El código está en `5c56e9b`.

## Qué se hizo

| Pieza | Dónde |
|---|---|
| Fuentes, prioridad, imagen junto al ROM, reglas de imagen no confiable, decodificador ImageIO y archivo de ajustes | `Library/Covers.swift` |
| Almacén: elección y preferencia, paso a la siguiente fuente, caché de la carpeta por ruta + sello (purga tras escaneo completo, N5A-2), importar, quitar, fijar, soltar y borrar | `Library/CoverStore.swift` |
| `GameArtworkStore` admite varias carpetas (`ArtworkPinned/`, `Covers/Imported/` y `Covers/Folder/`). Con `untrusted`, lee con `CoverDecoder` | `Library/GameArtworkStore.swift` |
| El escáner adjunta `coverURL` y `coverStamp` mirando solo el listado: no lee ni descarga la imagen | `LibraryScanner.swift`, `RomEntry.swift` |
| Encaje: la tarjeta se rellena; el detalle muestra la imagen entera con bandas; las capturas, sin suavizar | `GamePlaceholderView.swift` (`GameArtworkView`) |
| Centro de ajustes › Portada: elección con resumen, `PhotosPicker` (sin permisos), `fileImporter`, quitar importada y soltar fijada | `CoverCenterView.swift`, `GameCenterView.swift` |
| Pausa › «Usar como portada» | `PauseView.swift`, `AppState.pinCurrentFrameAsCover` |
| Ajustes › Biblioteca › Portadas | `LibrarySettingsView.swift` |
| Almacenamiento: mide las 4 carpetas, sin `settings.json` (N5A-3); «Borrar portadas» no toca la carpeta del usuario | `StorageSettingsView.swift` |
| «Continuar jugando» sin filtro de portada | `LibraryQuery.continuePlaying`, `LibraryView.swift` |
| Catálogo: `applyN5` (imágenes pintadas en código) y 23 líneas añadidas al final de `screens.txt` | `DebugScreenRouter.swift` |
| Guía | [docs/guia/portadas.md](../guia/portadas.md) |

## Decisiones (paridad con Android 1–5 y 7–9)
1. Importar elige «Imagen»; «Usar como portada» elige «Captura». Al quitar la importada, se vuelve a Automática si no hay imagen en la carpeta.
2. Si eliges una fuente que el juego no tiene, se ve la generada. Automática recorre todas las fuentes.
3. Dentro de «Imagen», la importada gana a la de la carpeta. Entre extensiones: png > jpg > jpeg > webp.
4. La preferencia por defecto es «Preferir imágenes».
5. Los ajustes se guardan en un archivo propio por dispositivo (`Application Support/Covers/settings.json`, v1). La escritura es atómica. Si el archivo está dañado, se aparta como `.corrupt-*`. Si viene de una versión futura, no se sobrescribe.
7. La copia de la imagen de la carpeta se guarda con clave SHA-256(ruta + sello). Una imagen que falla no se reintenta en la misma ejecución. Las copias huérfanas se purgan tras cada escaneo completo. Leerla la primera vez que se ve descarga de iCloud solo esa imagen.
8. «Borrar portadas» borra también las importadas y las fijadas. Nunca toca la carpeta del usuario ni la elección de cada juego.
9. Las imágenes se guardan reducidas a 1024 px como máximo, en PNG.
- **6 sustituida (decisión de Joel 2026-10-07, en lugar de K10):** «Continuar jugando» muestra todo juego reanudable, aunque no tenga portada. Antes, iOS exigía tener una captura; ese filtro se quitó. Lo cubre el test `continueRailShowsResumableGamesWithoutAnyCover`.

## Diferencias con Android
- Al importar se acepta también **HEIC**, reconocido por la firma `ftyp`, porque Fotos del iPhone guarda en ese formato. La imagen de la carpeta sigue limitada a png/jpg/jpeg/webp.
- Para decodificar se usa ImageIO: dimensiones de la cabecera con `CGImageSourceCopyPropertiesAtIndex` (sin decodificar) y miniatura con `kCGImageSourceThumbnailMaxPixelSize` = 1024. En Android, `BitmapFactory` + `inSampleSize`.
- La foto de Fotos llega como archivo temporal (`FileRepresentation`) y se lee con el tope de 15 MiB. Nunca se carga entera antes de comprobar el tamaño.
- La fila «Portada» del centro abre una página propia (no un diálogo). Error de importación: «No se pudo usar esa imagen…».
- La pausa confirma en la propia fila («Portada fijada»). Si la escena es lisa, lo explica en el pie.

## Verificación
Tests unitarios, iPhone 17 Pro (`tools/ios-screenshots.sh`, completo) e iPhone SE (3.ª gen.):
```
✔ Test run with 292 tests in 26 suites passed after 10.899 seconds.
** TEST SUCCEEDED **
xcodebuild Release: exit 0
xcodebuild test: exit 0
✔ Test run with 292 tests in 26 suites passed after 11.020 seconds.
** TEST SUCCEEDED **
```
CoversTests (17, sin fallos): prioridad; imagen junto al ROM (mayúsculas, extensiones, portada/cover solo con un juego, ocultas y vacías); el escáner adjunta una imagen con permisos 000 sin leerla; firma (extensión falsa, GIF, RIFF que no es WebP, vacía, corta); reducción PNG/JPEG de 3000×1500 a 1024×512; truncadas; IHDR de 60 000² y 16 000² con CRC válido rechazadas; más de 15 MiB rechazada y sin leer hasta el final; ajustes (persistencia, archivo dañado apartado, versión futura no sobrescrita); importar y quitar; fijar captura y que sobreviva al cierre; escena lisa rechazada; imagen de la carpeta hostil, que pasa a la captura, se lee una sola vez y con «Imagen» da la generada; caché reducida y purgada; borrar todo (0 B, se conservan ajustes y la imagen del usuario); reinicio; carril «Continuar».

Release para dispositivo:
```
$ xcodebuild build -scheme PocketGB -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
** BUILD SUCCEEDED **
```
Sin binarios ni red:
```
$ git diff --name-only siguiente-nivel..HEAD | grep -iE "\.(png|jpe?g|webp|heic|gif|sav|gb|gbc|gba)$"
sin binarios
$ git diff siguiente-nivel..HEAD -- ios | grep -E "^\+.*(URLSession|NSAppTransport|http)"
sin red
```
Regla 6: no se toca ninguna ruta de guardado (SaveStore, SRAMPersistence, SaveMirror y StateStore no cambian). Fijar la portada solo copia el fotograma.

## Capturas (220 en total; las 23 `n5-*` revisadas una a una, incluida AX5)
| Captura | Qué se comprobó |
|---|---|
| `n5-library` (claro/oscuro) | «Continuar jugando»: imagen importada vertical recortada, captura y CPU_INSTRS **sin portada, con la generada**; estanterías con imagen de la carpeta |
| `n5-library-list`, `-prefer-captures`, `-ax5`, `-landscape` | Miniaturas de cada fuente; con «Preferir capturas», arm.gba muestra la captura; AX5 legible |
| `n5-details-imported` (claro, oscuro, horizontal), `-folder`, `-gba` | La imagen entera con bandas laterales o arriba y abajo |
| `n5-details-capture`, `-generated` | La captura llena el marco 10:9; la generada lleva iniciales y chip |
| `n5-game-center` | «Portada · Imagen · se ve: Imagen importada» |
| `n5-cover` (claro/oscuro), `-capture`, `-failed`, `-ax5` | Elección con resumen, Fotos/Archivo, quitar, soltar fijada, error arriba; AX5 sin recortes |
| `n5-pause` (claro; oscuro tras tocar) | «Usar como portada» → «Portada fijada» |
| `n5-settings-library`, `n5-settings-storage` | Picker «Portadas» con su pie; textos nuevos de Almacenamiento |

## Pendiente
- Joel debe ratificar las decisiones y aceptar HEIC como diferencia con Android.
- El ejemplo con los juegos de Joel queda para N9 (ND4).
