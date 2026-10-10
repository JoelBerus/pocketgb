# N5 · Portadas (Android) — evidencia

Rama `n5-android-portadas` (desde `siguiente-nivel`, con N8 Kotlin y N4 Android integrados). Solo la parte Android de N5 ([N-README](../hitos/N-README.md) §N5). Verificación desde limpio sobre `6bb43f0` (código; este documento va en el commit siguiente).

## Qué se hizo

| Pieza | Dónde |
|---|---|
| Fuentes, prioridad, imagen junto al ROM y reglas de imágenes no confiables (puro, JVM) | `library/artwork/Covers.kt` |
| Elección por huella + preferencia global (`filesDir/covers/settings.json`, formato propio v1, escritura atómica, `.corrupt-*`, protegido ante versión futura) | `library/artwork/CoverSettingsStore.kt` |
| Decodificación: tope 15 MiB, firma PNG/JPEG/WebP, `inJustDecodeBounds`, lado ≤ 16 384 y ≤ 100 MP, `BitmapFactory` con `inSampleSize`, reducción a ≤ 1024 px, PNG | `library/artwork/CoverDecoder.kt` |
| Repositorio: capturas (`artwork/`), captura fijada (`artwork-pinned/`), importada (`covers/imported/`), copia de la imagen de la carpeta por sello (`covers/folder/`); orden de fuentes con caída a la siguiente ante cualquier fallo y, al final, la generada | `library/artwork/CoverRepository.kt` |
| `ArtworkStore`: `saveEncoded`, `remove`, tope de lectura y decodificador inyectables (las capturas siguen igual) | `library/artwork/ArtworkStore.kt` |
| Escáner: `RomEntry.coverUri/coverStamp` de `<ROM>.png|jpg|jpeg|webp` o `portada.*`/`cover.*` (carpeta con un solo juego); no lee la imagen | `library/LibraryScanner.kt`, `library/RomEntry.kt` |
| Encaje: tarjeta, lista y carril rellenan el marco (Crop); detalle `fit` (la imagen entera con bandas `surfaceVariant`); capturas sin suavizado | `ui/components/GameArtwork.kt`, `ui/details/GameDetailsScreen.kt` |
| Centro de ajustes › Portada (sustituye a «próximamente»): elección Automática/Imagen/Captura/Generada, Photo Picker (`PickVisualMedia`, sin permisos), `OpenDocument` (`image/png|jpeg|webp`), quitar importada, soltar captura fijada, consejo | `ui/details/CoverCenter.kt`, `ui/details/CoverHost.kt`, `GameCenter.kt`, `GameSettingsSheet.kt` |
| Pausa › «Usar como portada» (fotograma con la sesión en pausa; no toca la partida) | `PauseSheet.kt`, `GameplayHost.kt`, `GameplayViewModel.kt`, `GameSession.pausedFrame()` |
| Ajustes › Biblioteca › Portadas: Preferir imágenes / Preferir capturas | `ui/settings/LibrarySettingsScreen.kt` |
| Ajustes › Almacenamiento: cuenta y borra todas las portadas (nunca las imágenes de la carpeta) | `StorageSettingsScreen.kt`, `settings/StorageUsage.kt` |
| Cadenas | `res/values/strings_n5.xml` (solo ahí) |
| Guía | [docs/guia/portadas-android.md](../guia/portadas-android.md) |
| Catálogo | `debug/catalog/N5Catalog.kt` (imágenes pintadas en código: nada con copyright) y 17 líneas añadidas al final de `tools/android-screens.txt` |

Sin red: la app sigue sin `INTERNET` y no descarga portadas (ND4: las de libretro-thumbnails irán a las carpetas de Joel en N9, fuera de la app). No se añadió ninguna imagen ni binario al repo.

## Desde limpio (`git archive 6bb43f0`)

```
$ git archive HEAD | tar -x -C $S/n5-clean
$ ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL in 4m 49s
JVM: 724 tests, 0 fallos
lint: 0 errors, 26 warnings      (ninguno en archivos de N5; los dos UseKtx de N5 se corrigieron en 6bb43f0)
22681746 outputs/apk/debug/app-debug.apk
15545119 outputs/apk/release/app-release-unsigned.apk
$ aapt dump permissions <apk>
outputs/apk/debug/app-debug.apk:            sin android.permission.INTERNET
outputs/apk/release/app-release-unsigned.apk: sin android.permission.INTERNET
  (única: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, la de siempre)
$ git diff --name-only siguiente-nivel..HEAD | grep -iE "\.(png|jpe?g|webp|gif|sav|gb|gbc|gba)$"
sin binarios
```

JVM nuevos (`CoversTest`, 16): prioridad (Automática según preferencia, importada > carpeta, elección explícita sin fuente → generada, la generada siempre última), imagen junto al ROM (mayúsculas, prioridad de extensión, `portada`/`cover` solo con un juego, ignora ocultos/virtuales/vacíos/carpetas/otras extensiones), el escáner adjunta URI y sello sin leer la imagen, firma (extensión falsa, GIF, RIFF no WebP, vacío, truncado), dimensiones enormes, `inSampleSize`/reducción, persistencia, archivo corrupto apartado, versión futura sin sobrescribir, importar/quitar, fijar captura, imagen de la carpeta que falla no se reintenta y cae a la generada.

```
<testsuite name="com.joelbermudez.pocketgb.library.artwork.CoversTest" tests="16" skipped="0" failures="0" errors="0">
```

## Instrumentadas (emulador `Small_Phone_API_35`, worktree en `1394809` + `6bb43f0`)

Suite completa (con las ROMs de prueba de GBA presentes):

```
$ ./gradlew --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
BUILD SUCCESSFUL in 8m 13s
tests="476" failures="0" errors="0" skipped="0"
```

Paquete `covers` tras el último cambio del decodificador:

```
$ ./gradlew ... :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.joelbermudez.pocketgb.covers
BUILD SUCCESSFUL in 38s
tests="15" failures="0"
aFakeExtensionOrAnUnsupportedFormatIsRejected          CoverDecoderTest
truncatedImagesNeverCrashAndNeverExceedTheTarget       CoverDecoderTest
hugeDeclaredDimensionsAreRejectedBeforeDecoding        CoverDecoderTest   (PNG con IHDR 60 000×60 000 y CRC válido)
validImagesOfEveryFormatAreReducedToTheTargetSide      CoverDecoderTest   (PNG, JPEG, WebP 3000×1500 → 1024×512)
imagesOverTheByteLimitAreNotReadToTheEnd               CoverDecoderTest
theReducedCopyIsAPngOfAtMostTheTargetSide              CoverDecoderTest
pinningThePausedFrameChoosesCaptureAndSurvivesTheCloseCapture  CoverPinTest (sesión real; la SRAM no cambia; escena lisa no se fija)
anExplicitChoiceWithoutThatSourceSaysSo                CoverUiTest
choosingInTheGameCenterChangesTheVisibleCover          CoverUiTest
aFolderImageWithAFakeExtensionShowsTheGeneratedCover   CoverUiTest
anUnreadableCaptureFallsBackToTheGenerated             CoverUiTest
aFolderImageIsShownAndAHostileOneFallsBackToTheGenerated CoverUiTest
detailsShowEachSource                                  N5CatalogTest
libraryShowsTheContinueRailWithOwnCovers               N5CatalogTest
centerPauseAndSettingsShowTheNewControls               N5CatalogTest
```

Prueba existente adaptada: `GameCenterUiTest.upcomingRowsAreDisabledForTalkBackAndHidingAsksFirst` (la fila «Portada» ya no está deshabilitada; ahora comprueba «Cambiar» habilitado y «Progreso» sigue próximamente).

## Capturas (revisadas una a una)

`SCREENS="n5-…" tools/android-screenshots.sh android/build/screenshots-n5` → 17 capturas (no se versionan):

| Captura | Qué se comprobó |
|---|---|
| `n5-library-portrait-light/dark` | Carril «Continuar jugando» con imagen importada (vertical 3:4 recortada centrada) y captura; estanterías con imagen de la carpeta (2:1 recortada). |
| `n5-library-list-portrait-light` | Lista (filtro Favoritos): miniaturas de captura, imagen, generada (Rally eligió «Generada» aunque tiene `cover.webp`) y GBA. |
| `n5-library-prefer-captures-portrait-light` | Con «Preferir capturas», Isla Pixel (imagen + captura, Automática) muestra su captura. |
| `n5-details-imported-portrait-light/dark`, `n5-details-landscape` | Detalle: la imagen vertical entera con bandas laterales, en claro, oscuro y horizontal. |
| `n5-details-folder` | Imagen apaisada entera con bandas arriba y abajo. |
| `n5-details-capture`, `n5-details-generated` | Captura llenando el marco 10:9; generada con iniciales y chip. |
| `n5-game-center` | Fila «Portada · Automática · se ve: Imagen de la carpeta» con «Cambiar». |
| `n5-cover-dialog` (claro/oscuro), `-actions`, `-failed` | Opciones con resumen; importar; quitar importada; soltar fijada; consejo; error de importación arriba. |
| `n5-pause` | «Usar como portada» entre «Personalizar controles» y el pie. |
| `n5-settings-library` | «Portadas: Preferir imágenes / Preferir capturas» con su pie. |

## Decisiones propias (a ratificar)

1. **Importar elige «Imagen»** para ese juego; «Usar como portada» elige «Captura». Quitar la importada vuelve a Automática si no hay imagen en la carpeta.
2. **Elección explícita sin esa fuente → generada** (no salta a otra fuente). Automática sí recorre todas.
3. **Imagen importada gana a la de la carpeta** dentro de «Imagen».
4. **Preferencia global por defecto: «Preferir imágenes».**
5. **Elección y preferencia en un archivo propio** (`covers/settings.json`), no en `library-prefs` (no sube su formato v4 ni choca con otros lotes). Por dispositivo (ND12); N7 podrá incluirlas en el paquete.
6. **K10 (carril solo con portada):** ahora cuenta cualquier portada propia por huella (captura, fijada o importada); la de la carpeta no (no va por huella). Sigue filtrando por reanudables (ND15).
7. **Copia de la imagen de la carpeta** cacheada por SHA-256(URI + sello); una imagen que falla no se reintenta en esa ejecución. Leerla en Drive la descarga (solo esa imagen, la primera vez que se ve).
8. **«Borrar portadas»** (Almacenamiento) borra también importadas y fijadas; el diálogo lo dice.
9. **Copia reducida en PNG** (sin pérdida, conserva transparencia), lado ≤ 1024.

## Pendiente fuera de este lote

- iOS de N5 (otro lote). Ejemplo con los juegos del Drive: N9 (ND4).
- La fila «Portada» del centro de ajustes en los catálogos antiguos de N4/N8 sigue mostrando «próximamente» (esas pantallas no pasan `cover`); son capturas históricas.
