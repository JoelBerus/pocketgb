# N1 Android: evidencia (identidad por huella y carpetas)

Rama `n1-android-identidad` desde `a9-android-paridad` (`5036468`), con `a9-android-paridad` fusionada de nuevo en `620f447` (corrección del catálogo A9-H1 y respuesta a su auditoría) y en `809f419` (`b4c453b`, A9 cerrado). Plan: [N-README §3.1, §3.2 y §4 N1](../hitos/N-README.md), decisiones ND2 y ND11. Estado: **auditado (Opus: APROBAR CON CAMBIOS, regla 6 limpia) y respondido** ([informe](N1-android-opus.md), [respuesta](N1-android-respuesta.md)); `siguiente-nivel` fusionada en `a77a8d9`. La verificación vigente es la de [Respuesta a la auditoría](#respuesta-a-la-auditoría-h1h8); las secciones anteriores describen la primera entrega. iOS (N1 iOS) es otro lote.

## Qué se hizo

### N1b · carpetas
- `LibraryScanner` recorre la carpeta **por niveles** hasta `MAX_FOLDER_DEPTH = 5` (la raíz es el nivel 0; `a/b/c/d/e/x.gb` es lo más hondo que se ve; la carpeta del sexto nivel **ni se lista**). Tope `MAX_SCAN_ENTRIES = 5000` documentos (archivos y carpetas) por escaneo; al llegar, para y el escaneo queda incompleto.
- Nombres reservados (ND11): lo que empieza por `.` se ignora; `PocketGB/` **en la raíz** no se escanea (una `PocketGB/` anidada sí); las carpetas que empiezan por `_` quedan apartadas en cualquier nivel (un archivo `_x.gb` sí se lee). `.zip` sigue sin leerse.
- Fuera del hilo principal (como antes, en `Dispatchers.IO`) y **cancelable**: `checkCancelled` antes de listar cada carpeta y de leer cada cabecera. Una subcarpeta que falla no aborta el resto (se cuenta en `folderErrors`); perder el permiso sí (como antes). Una carpeta con dos padres (Drive) se lista una vez. Orden estable por nombre para que el tope no dependa del proveedor.
- `ScanStats` (`scanDetailed`): carpetas listadas, cabeceras leídas, documentos vistos, ocultos, `PocketGB/`, apartadas, demasiado hondas, errores, tope y `complete`. La app escribe una línea por escaneo en logcat (`adb logcat -s PocketGB/Library`, sin rutas ni nombres) y el ViewModel la expone (`lastScan`).
- `RomEntry.folderPath: List<String>` (de `subfolder` sale la ruta con `/`), `lastModified` del ROM. Los ids siguen siendo la ruta relativa con sufijo `#<hash del id de documento>` si dos documentos comparten ruta; vale igual con rutas profundas (test con dos «Rojo» homónimas en el tercer nivel).
- Espejo `<rom>.sav`: `folderDocumentId` es la carpeta **inmediata** del ROM a cualquier profundidad, así que `SafSaveMirror` (sin cambios) lista y escribe el `.sav` hermano en la subcarpeta (probado a 5 niveles en `MoveRomEndToEndTest`).
- Detalle: «Pokémon › 2ª generación · archivo» (hasta 3 líneas; con fuente grande, entera) con descripción para TalkBack («Ubicación: …»). La UI de categorías es de N4.

### N1a · identidad
- **Formato 2 de `preferences.json`** (`formatVersion`, `LibraryPreferencesFormat`): favoritos y «jugado» por huella (`favoriteFingerprints`, `lastPlayedByFingerprint`), como ya iban ocultos y alias. La ruta queda como clave provisional (`favorites`, `lastPlayed`, `hiddenPaths`, `aliasesByPath`) y `recordFingerprint` (abrir, ver el detalle o reconocer un movimiento) la migra a la huella. Quitar un favorito lo quita de los dos sitios; la fecha migrada se queda con la más reciente.
- **Migración**: un archivo sin `formatVersion` (A5–A9) se migra al leerlo (`migrated()`: cada ruta con huella conocida lleva lo suyo a la huella) y se escribe como versión 2 en la siguiente escritura. Lo que ve el usuario (favorito, fecha, oculto y nombre) es igual antes y después (comparado con las fixtures `preferences-a8.json` y `preferences-a9.json`). Un archivo de versión 2 no se vuelve a migrar; uno de una versión futura conserva lo que esta versión conoce. Leer sigue tolerando claves desconocidas y enums nuevos; un JSON ilegible se sigue apartando como `.corrupt-<fecha>`.
- **Caché por documento** (`documents`: ruta → `DocumentStamp(nombre, tamaño, fecha)`), en el mismo `preferences.json` (una sola escritura atómica con las huellas). Tras cada escaneo, `reconciled()`:
  - con el escaneo **completo**, `MoveDetection` reconoce una ruta desaparecida y **una sola** ruta nueva con el mismo (nombre, tamaño, fecha), o, si cambió de nombre, con el mismo (tamaño, fecha) cuando nadie más los comparte. Se trasladan la huella y los registros por ruta (favorito, fecha, oculto y alias provisionales, y «ya visto», para que no salga como «Nuevo»);
  - ambigüedad (dos candidatos, o dos desaparecidas con el mismo sello) → no se traslada nada; tamaño o fecha nulos (`COLUMN_SIZE`/`COLUMN_LAST_MODIFIED` pueden faltar en SAF) → sello incompleto, no se traslada nada. Lo guardado por huella sigue ahí y vuelve en cuanto se calcula la huella (abrir o ver el detalle);
  - una ruta que sigue en su sitio con otro tamaño u otra **cabecera** olvida su huella (tras H4: la fecha y el id de documento ya no bastan, Drive puede cambiarlos sin tocar el archivo; ver la respuesta a la auditoría);
  - con el escaneo completo se olvida la huella de las rutas que ya no están (lo guardado por huella no se toca); con uno **incompleto** (carpeta con error o tope) no se traslada ni se olvida nada, y tampoco se podan los «ya vistos». Un listado vacío no cambia nada.
- **La huella no se calcula en segundo plano** (Drive descargaría cada ROM): se sigue calculando al abrir o ver el detalle. Reconocer un movimiento no lee el ROM (probado contando lecturas en JVM y en el instrumentado).
- **Duplicados** (misma huella **conocida** en varias rutas; nunca se adivina por título o tamaño): insignia «Duplicado» discreta (contorno fino, sin color de alerta) en tarjeta y fila, incluida en la etiqueta de TalkBack; en el detalle, «También en» con las otras ubicaciones («Carpeta principal · …» para la raíz) y qué comparten. El carril «Continuar jugando» muestra cada juego una vez.

### Qué pasa con el `.sav` de cada copia (duplicados) y con un `.sav` ajeno
- La partida **local** es una sola por huella (`saves/<huella>.sav`, con sus backups), igual que estados, ajustes y portada. `FingerprintOwnership` impide abrir dos copias a la vez.
- Cada copia tiene su espejo `<rom>.sav` en su carpeta. Al abrir una copia se aplica la regla de siempre (`SaveResolution`) entre la local y **el espejo de esa copia**: si es igual, nada; si el espejo es una escritura nuestra anterior, gana la local y se reescribe; si no es nuestro, gana el más nuevo por fecha (la local en un empate o sin fecha). El espejo de la otra copia se pone al día cuando se abra esa copia.
- **Regla 6, perdedor apartado (aviso del orquestador, hallazgo de la auditoría de N1 iOS):** con el escaneo recursivo, un duplicado con su propio `.sav` distinto, o un `.sav` de **otro juego** con el mismo nombre que el ROM, se resuelve por fecha contra la partida local compartida, y el perdedor solo iba a la rotación de 5 backups (desaparecía tras 5 guardados). Ahora, si el espejo **no es una escritura propia reconocida** y difiere de la local (las dos de tamaño válido), el perdedor —sea la local o el espejo— se copia **además** a `saves/backups/<huella>.mirror-<unix>-<rand8>.sav` **antes de tocar nada** (temporal + `fsync` + rename; nombre único, nunca se pisa ni se borra; el mismo contenido no se repite en cada apertura). `recoverOrphans` limpia los temporales que deje un cierre forzado. Se ve y se restaura en Ajustes › Partidas › «Apartadas (no se borran solas)»; restaurar guarda antes la actual en `.1` y la apartada sigue ahí. Una local de tamaño incorrecto sigue yendo a su cuarentena `wrong-size-*` de siempre; un espejo de tamaño incorrecto no se toca.
- El modo «Compartido» de `SafSaveMirror` **no aplica** a copias en carpetas distintas: solo se activa cuando dos ROMs de la **misma** carpeta comparten el nombre base (`Juego.gb` y `Juego.gbc`), y entonces no se lee ni escribe ese `.sav`.
- Lo que no cambia: el que gana por fecha. Un espejo viejo con fecha posterior (tocado por el proveedor) sigue ganando al abrir esa copia, pero la partida local queda apartada para siempre y restaurable en un toque. La guía recomienda dejar una sola copia y apartar las demás en `_Revisar/`.

### Otros
- Cadenas nuevas solo en `res/values/strings_n1.xml`. Catálogo Debug `debug/catalog/N1Catalog.kt` y 10 líneas al final de `tools/android-screens.txt`.
- Guía: [docs/guia/carpetas-android.md](../guia/carpetas-android.md); una frase de la guía A9 sobre mover el ROM. Docs: `05-android-spec.md`, `diseno-android/SPEC.md` §5.1 y filas N1-1…N1-5 en `PRUEBAS-JOEL.md`.

## Formato de `preferences.json`

Antes (A8/A9, sin versión):
```json
{"favorites":["Pokemon Red.gb"],"lastPlayed":{"Pokemon Red.gb":1759700000000},
 "fingerprints":{"Pokemon Red.gb":"9d4c…4b58"},"hiddenFingerprints":[],"hiddenPaths":["Roto.gb"],
 "layout":"LIST","sort":"RECENT","knownIds":[…],"aliasesByFingerprint":{},"aliasesByPath":{}}
```
Después (N1, versión 2; la fixture A8 migrada):
```json
{"favorites":[],"lastPlayed":{},"fingerprints":{"Pokemon Red.gb":"9d4c…4b58", …},
 "hiddenFingerprints":[],"hiddenPaths":["Roto.gb"],"layout":"LIST","sort":"RECENT","knownIds":[…],
 "aliasesByFingerprint":{},"aliasesByPath":{},
 "favoriteFingerprints":["9d4c…4b58"],"lastPlayedByFingerprint":{"9d4c…4b58":1759700000000, …},
 "documents":{"Pokemon Red.gb":{"name":"Pokemon Red.gb","size":1048576,"lastModified":1759690000000,"documentId":"…"}},
 "formatVersion":2}
```
(`documents` se llena en el primer escaneo tras actualizar; hasta entonces no hay nada que comparar y no se traslada nada.)

Hacia atrás: una versión anterior de la app lee el archivo nuevo (ignora las claves que no conoce), pero no vería los favoritos y fechas que ya están por huella. No hay vuelta atrás prevista (Joel instala siempre la última); las partidas no dependen de este archivo.

## Commits
`1c7174c` escaneo recursivo (N1b) · `552721a` identidad por huella, caché, movimientos y duplicados (N1a) · `15c9ce1` UI y catálogo · `79dfe29` instrumentados · `bd1af49` guía y docs · `620f447`, `809f419` fusiones de `a9-android-paridad` · `2cc0d59` guía A9 · `5043b59` corrección de un test · `870b539` perdedor apartado fuera de la rotación (regla 6) · `563d9ba` id de documento en el sello · `7e5a771` catálogo `saves-set-aside` · `0ce5088` docs · `cdd4f9e` fuente grande, pie de «También en» y catálogo sin carril.

## Comandos y salidas

Mac compartido con otros lotes (load average entre 40 y 290 durante la sesión). Emulador `Small_Phone_API_35` (API 35) sin ventana, siempre dentro de `with-lock.sh emu`. **Todo desde limpio**: `git archive cdd4f9e` a una carpeta temporal (sin `build/` ni `.gradle/` del proyecto).

### JVM, APK y lint (desde limpio, `cdd4f9e`)
`./gradlew --no-daemon --max-workers=1 --continue :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest`
```
> Task :app:assembleDebug
> Task :app:assembleRelease
> Task :app:lintDebug
> Task :app:assembleDebugAndroidTest
BUILD SUCCESSFUL in 5m 45s
```
Suma de los XML de `build/test-results/testDebugUnitTest`: **478 tests, 0 fallos, 0 errores, 0 omitidos** (la evidencia de A9 daba 413 antes de su auditoría). De N1: `LibraryScannerTest` 25 (antes 16: 9 nuevos de N1b y el de «un nivel» reescrito), `LibraryIdentityTest` 13, `LibraryMoveDetectionTest` 21, `LibraryDuplicatesTest` 6, `LibraryMoveViewModelTest` 3, `SaveOpeningTest` 27 (+8 de apartado), `SavesBrowserTest` 12 (+2); `LibraryAliasTest` 17 (la aserción A8 pasa a comprobar el significado, no el almacenamiento), `LibraryViewModelTest` 32 y `ManifestPolicyTest` 9 en verde.

`ProcessKillTest` pasó en esa corrida: `kills en bucle=196, con .tmp huérfano=144, guardados completados=49`. En dos corridas anteriores (con otra compilación en paralelo y load ~280) falló por su umbral de significatividad, no por un invariante: «el hijo debía llegar al bucle casi siempre (175/200)» y «debía completarse algún guardado en total (3)»; repetido aislado pasó (`kills=194, .tmp huérfano=147, completados=64`). Es el comportamiento ya documentado en A5–A9.

Lint: `0 errors, 20 warnings`; ninguno en archivos de N1 (búsqueda de `strings_n1`, `N1Catalog`, `DocumentCache`, `LibraryScanner`, `LibraryPreferences`, `SaveStore`, `SaveOpening`, `SavesScreen`, `GameDetailsScreen`, `GameCard` en `lint-results-debug.txt` = 0).

Manifiesto sin `INTERNET` (`aapt2 dump permissions` del APK Release):
```
package: com.joelbermudez.pocketgb
permission: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

### Mutaciones (los tests detectan la falta de cada pieza)
Sobre una copia, `:app:testDebugUnitTest` de las clases afectadas:
- Escaneo sin límite de profundidad, `_carpetas` escaneadas, migración desactivada, ambigüedad aceptada en la terna, sello siempre «completo» y traslados con escaneo incompleto (6 mutaciones a la vez): **11 fallos de 150**, entre ellos `scansFiveFolderLevelsAndNeverListsTheSixth`, `reservedNamesFollowNd11`, `anA8FileIsMigrated…`, `anA9FileWithAliases…`, `twoCandidatesForOneDisappearedPathTransferNothing`, `twoDisappearedPathsWithTheSameStampTransferNothing`, `withoutSizeOrDateFromTheProviderNothingMoves`, `anIncompleteScanNeitherMovesNorForgets`.
- Sin el apartado del perdedor y sin el id de documento en el sello: **5 fallos de 48**: `aDuplicateWithANewerMirrorSetsTheLocalAsideOutsideTheRotation`, `aForeignSaveWithTheSameNameIsSetAsideOutsideTheRotation`, `theSameLoserIsSetAsideOnce…`, `theSetAsideCopyIsWrittenBeforeAnythingIsReplaced`, `anotherDocumentAtTheSamePathWithTheSameNameSizeAndDateDoesNotInheritTheFingerprint`.

### Instrumentados (desde limpio, suite completa)
`with-lock.sh emu ./gradlew --no-daemon --max-workers=1 --continue :app:connectedDebugAndroidTest`
- Sobre `7e5a771` (con el apartado y el id de documento): `BUILD SUCCESSFUL in 7m 11s`; XML **371 tests, 0 fallos, 0 errores, 0 omitidos**.
- Sobre `cdd4f9e` (solo cambian la disposición del detalle con fuente grande, una cadena y el catálogo): 371 tests, **1 fallo ajeno a N1**: `NativeSaveBridgeTest.closeIsIdempotentAndASavingThreadNeverUsesAFreedHandle` («el hilo llegó a copiar antes del cierre»: el hilo de prueba no llegó a copiar en 150 ms con el Mac a load ~130; N1 no toca `emulator/` ni `cpp/`). Repetida la clase aislada en el mismo build: `NativeSaveBridgeTest tests="19" failures="0"`, `BUILD SUCCESSFUL in 26s`.

De N1: `game/MoveRomEndToEndTest` 3, `saf/SafFoldersTest` 1, `ui/FoldersUiTest` 2, `ui/SavesSettingsUiTest` 3 (+1); `saf/SafLibraryTest` 15 (escaneo a dos niveles), `CatalogCoverageTest` 9 (manifiesto ↔ catálogo), `DebugCatalogTest` 11, `LibraryUiTest` 61 y los de continuación exacta de A9 (`ExactContinuationTest` 15, `ExactContinuationMirrorTest` 1) en verde.

`MoveRomEndToEndTest` (proveedor de pruebas, núcleo real, ROM contador sintética): mover ROM y `.sav` de `Pokémon/Gen 1/` a `Clásicos/Nintendo/Pokémon/Rojo/Kanto/` (5 niveles) conserva favorito, alias, oculto, «jugado», ajustes del juego (CGB + paleta 3), portada, estado manual, partida local y espejo (el espejo viaja con el ROM y se sigue escribiendo en la carpeta nueva), sin leer el ROM para reconocerlo (contador de lecturas igual) y sin marcarlo «Nuevo»; «Continuar» retoma el AUTO (que solo vale con el modelo CGB del ajuste por huella) sin avisos ni backups nuevos. Mover solo el ROM: la local manda, se crea el `.sav` en la carpeta nueva y el viejo no se toca. Duplicado con su propio `.sav` más nuevo: gana ese, la local queda apartada y sobrevive a cinco guardados; el `.sav` de la otra copia no se toca.

### Kill-test de partidas (desde limpio, `cdd4f9e`, tras el cambio del apartado)
`with-lock.sh emu tools/android-save-kill-test.sh 50` con el APK Debug del build limpio:
```
iter   1  kill=force-stop tras  … ms  -> OK bytes=8192 …
…
Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
```
(También 50/50 sobre `7e5a771`.) El estrés no usa espejo, así que no crea apartados; la seguridad del apartado ante un cierre forzado es la de las demás escrituras (temporal + `fsync` + rename; `recoverOrphans` borra el temporal: `aSetAsideTemporaryLeftByAKillIsCleanedAndARealOneIsKept`).

### Consultas SAF por escaneo
`SafFoldersTest` (SAF real con el proveedor de pruebas, que cuenta los listados que sirve): árbol con `Tetris.gb`, `Pokémon/1ª generación/`, `Pokémon/2ª generación/`, `Pokémon/PocketGB/`, `Pokémon/_Revisar/`, `N1/…/N5/` (+ `N6/`), `.oculta/`, `_apartada/`, `PocketGB/Intercambio/` y un `.zip`:
- **10 carpetas listadas** (raíz, Pokémon, 1ª, 2ª, Pokémon/PocketGB, N1…N5) = 10 listados servidos por el proveedor; **5 cabeceras leídas** (una por ROM); **15 consultas** en total. No se consultan `.oculta/`, `_apartada/`, `Pokémon/_Revisar/`, `PocketGB/` de la raíz ni `N6/`.
- En general: consultas = carpetas a su alcance (cada una, una llamada de red en Drive) + una apertura por ROM para leer su cabecera (como antes de N1; en Drive puede descargar el archivo). Reconocer movimientos no añade ninguna. La app anota cada escaneo en `adb logcat -s PocketGB/Library` («escaneo: N carpetas listadas + M cabeceras = T consultas SAF; …»).

### Capturas revisadas
`SCREENS="details-deep-path details-deep-path-ax5 details-duplicate library-duplicates library-duplicates-list saves-set-aside library-detail-ax5" THEMES="light dark" tools/android-screenshots.sh` (11 PNG del build limpio, vistos uno a uno):
- `details-deep-path` (claro/oscuro): «Clásicos › Nintendo › Pokémon › 2ª generación › Johto · Pokemon Gold.gbc» en dos líneas junto al chip.
- `details-deep-path-ax5`: con fuente al 200 % la ruta va bajo el chip, entera. El chip «GBC» parte su texto en dos líneas («GB / C»): es el ancho fijo de 38 dp de `ConsoleChip`, previo a N1 y compartido por otras pantallas; no se toca aquí.
- `details-duplicate` (claro/oscuro): insignia «Duplicado», «También en» con «Copias · Pokemon Red (1).gb» y «Carpeta principal · Pokemon Red.gb», y el pie (en la captura clara el final del pie queda bajo el borde y se ve al desplazar).
- `library-duplicates-list`: tres «POKÉMON RED» con la insignia «Duplicado» discreta junto al chip y la estrella. En `library-duplicates` (cuadrícula) las tarjetas de Rojo quedan en la segunda fila y su línea de metadatos bajo el borde; la insignia en cuadrícula la comprueba `FoldersUiTest`.
- `saves-set-aside` (claro/oscuro): «Apartadas (no se borran solas)» con su fecha, «Restaurar» y el pie. Las fechas salen en inglés porque el emulador está en inglés (formato del sistema, como el resto de la pantalla).
- `library-detail-ax5` (existente): sin cambios aparentes para un juego en la raíz.
- Primera vuelta sobre `7e5a771`: en `library-duplicates*` el carril «Continuar jugando» (que muestra Rojo una vez, sin repetir) tapaba la cuadrícula; el catálogo de duplicados se quedó sin carril para ver las insignias.

## Decisiones tomadas (para ratificar por Joel)
| # | Decisión | Motivo |
|---|---|---|
| N1A-1 | La huella no se calcula en segundo plano en Android; los movimientos se reconocen por el sello (nombre, tamaño, fecha) sin leer el ROM. | Drive descargaría cada ROM. §3.1 lo prevé solo para archivos locales; en Android la carpeta real es Drive. |
| N1A-2 | Con un escaneo incompleto (carpeta con error o tope) no se traslada ni se olvida nada, tampoco los «ya vistos». | Un fallo pasajero del proveedor no puede parecer un movimiento ni una desaparición. |
| N1A-3 | En la misma ruta, otro tamaño u otra cabecera olvidan la huella (lo guardado por huella no se toca); la fecha y el id de documento no (H4). | Otro ROM con el mismo nombre tiene otra cabecera; Drive cambia fecha e id sin tocar el archivo. |
| N1A-4 | Un oculto por ruta pasa a la huella al conocerla (también en la migración). | Coherencia con favoritos, fecha y alias; con duplicados, ocultar oculta todas las copias (como ya pasaba al ocultar con huella conocida). |
| N1A-5 | El carril «Continuar jugando» muestra cada juego una vez aunque tenga varias copias (la primera por ruta). | Las copias comparten partida y estado. |
| N1A-6 | El perdedor frente a un espejo ajeno se aparta fuera de la rotación (`<huella>.mirror-<unix>-<rand8>.sav`), además de lo de siempre; restaurar no lo borra. | Regla 6 (aviso del orquestador, hallazgo de N1 iOS). |
| N1B-1 | `PocketGB/` solo se reserva en la raíz y sin distinguir mayúsculas; `_` aparta carpetas en cualquier nivel, no archivos. | ND11 habla de la carpeta de la app en la raíz y de «carpetas» que empiezan por `_`. |
| N1B-2 | Tope de 5 000 documentos (archivos y carpetas) por escaneo, recorrido por niveles y por nombre. | Con el tope, se quedan fuera los más hondos y siempre los mismos. Sin aviso en la UI (la biblioteca de Joel está muy lejos). |
| N1B-3 | El caché de sellos vive en `preferences.json` (una sola escritura atómica con las huellas). | Huella y sello no pueden quedar desincronizados. |

## Riesgos para la regla 6 (partidas)
- Ninguna ruta nueva escribe la partida local: identidad y carpetas solo cambian metadatos de la biblioteca. La apertura sigue calculando la huella del ROM leído, así que una asociación ruta → huella equivocada nunca abre la partida de otro juego (solo mostraría mal favorito, nombre, oculto o ajustes hasta abrirlo).
- El apartado va **antes** de cualquier escritura destructiva en `SaveOpening` (probado por el orden de operaciones) y con nombre único; si falla, la apertura falla sin tocar nada (`addBackupFailureStopsBeforeAnythingElseAndKeepsLocal` sigue en verde, ahora con el fallo en el apartado).
- Residual: con dos copias, un espejo viejo con fecha posterior gana al abrir esa copia (regla de siempre); la local queda apartada y restaurable.
- Residual: proveedor cuyo id es la ruta y otro ROM copiado encima con el mismo tamaño y la misma fecha (ver arriba).
- Las apartadas no se borran nunca solas: si se acumulan, ocupan espacio (cada una ≤ 128 KiB + 48). Borrarlas a mano queda para N7 (linaje) o N9.

## No verificado
- **La carpeta real de Google Drive de Joel** (`Roms`): ni el escaneo recursivo, ni el número de llamadas reales, ni cuánto tarda el primer escaneo, ni si Drive da `COLUMN_SIZE`/`COLUMN_LAST_MODIFIED` y conserva la fecha y el id al mover (de eso depende reconocer movimientos sin abrir). Filas N1-1…N1-6 de [PRUEBAS-JOEL](../PRUEBAS-JOEL.md).
- El tope de 5 000 con un proveedor real (solo el doble JVM).
- TalkBack real sobre la insignia y la ruta (solo las semánticas de Compose en `FoldersUiTest`).
- iOS no cambia en este lote.
- `make -C core test`: no se ejecutó; `core/` no cambia.

## Respuesta a la auditoría (H1–H8)

Detalle por hallazgo en [N1-android-respuesta.md](N1-android-respuesta.md). Commits: `f2ddb5e` (informe), `a77a8d9` (fusión de `siguiente-nivel`: N2 Android, N1 iOS y `ProcessKillTest` robusto; conflictos solo en `DebugCatalog.kt` y `android-screens.txt`, con los bloques N1 y N2), `aba6771` (H1–H8), `939f648` (catálogo `library-prefs-read-only` y `save-warning-set-aside`).

### Qué cambia
- **Sello con cabecera (H1):** `DocumentStamp.header` = bytes 0x134–0x14F en hexadecimal. Las reglas 1 (movido) y 2 (renombrado) y la comparación en la misma ruta la exigen; sin cabecera (documento remoto sin descargar) no hay traslado. La huella heredada queda en `inferredFingerprints` hasta leer el ROM; los ajustes del juego la confirman antes de escribir.
- **Preferencias de otra versión (H2/H3):** versión leída con tolerancia; futura o ilegible = sin migrar, sin sobrescribir y aviso fijo en la biblioteca. Formato nuevo del archivo: `tombstones` e `inferredFingerprints` (con valor por defecto; un archivo v2 anterior se lee igual).
- **Listados a medias y lápidas (H4):** `EXTRA_LOADING`/`EXTRA_ERROR` = escaneo incompleto. Lo que desaparece en un escaneo completo deja una lápida (≤ 200, ≤ 30 días) que devuelve huella, «ya visto» y registros por ruta al volver (misma ruta con mismo tamaño y cabecera, u otra ruta con el sello entero y sin ambigüedad). Consecuencia: el mismo juego que vuelve ya no sale «Nuevo» (el test A6-H8 se adapta: otro juego en su ruta sí lo es).
- **Aviso (H5):** `SaveLoadWarning.LocalSetAside` cuando el `.sav` junto al juego sustituye a la local apartada.
- **Caché de cabeceras (H6):** por (id de documento, tamaño, fecha).
- **Subcarpeta sin acceso (H7):** con el árbol aún concedido, `folderErrors`; **H8:** KDoc.

### Comandos y salidas (desde limpio, `git archive 939f648`)
`./gradlew --no-daemon --max-workers=1 --continue :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest`
```
> Task :app:assembleDebug
> Task :app:assembleRelease
> Task :app:lintDebug
> Task :app:assembleDebugAndroidTest
BUILD SUCCESSFUL in 4m 53s
```
- JVM: **551 tests, 0 fallos, 0 errores, 0 omitidos** (con N2 Android y el resto de `siguiente-nivel`). `ProcessKillTest`: `kills en bucle=199, con .tmp huérfano=131, guardados completados=314`.
- Lint: `0 errors, 20 warnings`; ninguno nuevo de N1 (la única línea que nombra un archivo tocado es el `UseKtx` previo de `Uri.parse` en `LibraryEnvironment.kt`).
- `aapt2 dump permissions` del Release: solo `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (sin `INTERNET`).
- Instrumentados completos dentro de `with-lock.sh emu` (`:app:connectedDebugAndroidTest`): `BUILD SUCCESSFUL in 6m`; XML **386 tests, 0 fallos, 0 errores, 0 omitidos**. De N1: `SafFoldersTest` 4, `MoveRomEndToEndTest` 3, `GameSettingsConfirmTest` 1, `FoldersUiTest` 2, `SavesSettingsUiTest` 3, `SafLibraryTest` 15, `SafMirrorTest` 34, `CatalogCoverageTest` 9, `DebugCatalogTest` 11.
- Kill-test con el APK Debug del build limpio: `OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)`; `OK: 50/50 iteraciones con el invariante intacto`.

### Mutaciones (copia aparte, clases de biblioteca y `SaveOpeningTest`)
Seis a la vez (cabecera fuera de las reglas, versión futura escribible, sin lápidas, listado a medias como completo, sin aviso H5, sin caché de cabeceras): **18 fallos de 196**, entre ellos `aDeletedRomAndADifferentNewRomWithTheSameSizeAndDateShareNothing`, `detectionIsPureOnStamps`, `aFileFromAFutureVersionIsReadButNeverOverwritten`, `oddVersionValuesNeverSendTheFileToQuarantine`, `preferencesFromAFutureVersionAreUsedButNeverWritten`, `aGameThatDisappearsInACompleteScanAndComesBackKeepsItsFavoriteAndIsNotNew`, `aFolderTheProviderIsStillLoadingIsUsedButTheScanIsIncomplete`, `aPartialRootIsAlsoIncompleteInsteadOfFailing`, `aDuplicateWithANewerMirrorSetsTheLocalAsideOutsideTheRotation`, `aReadOnlyFolderWhoseNewerSaveReplacesTheLocalStillWarnsAboutTheSetAside`, `eachRomCarriesItsHeaderIdentityAndACachedHeaderIsNotReadAgain`, `anUnchangedRomIsNotReopenedOnTheNextScan`.

### Consultas y aperturas antes y después de la caché (H6)
`SafFoldersTest.aSecondScanReopensNoUnchangedRom` (proveedor de pruebas, que cuenta las aperturas de lectura que sirve), mismo árbol de 5 ROMs y 10 carpetas a su alcance:

| Escaneo | Carpetas listadas | Cabeceras leídas (aperturas) | De la caché |
|---|---|---|---|
| Primero (sin caché) | 10 | **5** | 0 |
| Siguiente, sin cambios | 10 | **0** | 5 |

En la app, la caché sale de los sellos guardados, así que desde el segundo escaneo solo se abren los ROMs nuevos o con otro tamaño, fecha o id; un ROM movido en un proveedor cuyo id es la ruta (ExternalStorage) se abre una vez. La línea de logcat dice ahora también cuántas cabeceras vinieron de la caché.

### Capturas revisadas (11, del build limpio)
- `library-prefs-read-only` (claro/oscuro): aviso fijo en un recuadro de error sobre el buscador, legible en ambos temas.
- `save-warning-set-aside` (oscuro): «Aviso sobre tu partida» con el texto de H5 y «Entendido».
- `details-duplicate` (claro/oscuro): el pie de «También en» con la mención de «Apartadas» (se corta al final de la pantalla; se ve al desplazar).
- `details-deep-path`, `details-deep-path-ax5`, `library-duplicates-list` y `saves-set-aside`: sin cambios respecto a la revisión anterior.

### Riesgos y no verificado (añadido)
- Residual de H1: otro ROM con el mismo tamaño y la misma cabecera (un parche aplicado encima sin recalcular los checksums) conserva la huella anterior hasta abrirlo o ver su detalle; abrir calcula siempre la huella real y los ajustes solo se escriben con la huella confirmada.
- Las lápidas devuelven también el «ya visto»: un juego que vuelve en 30 días no sale «Nuevo» (cambio de comportamiento respecto a A6-H8, que la auditoría pedía).
- Sin verificar en Drive real: si su proveedor pone `EXTRA_LOADING` o `EXTRA_ERROR` en los listados, y si conserva id, tamaño y fecha (de eso dependen la caché de cabeceras y reconocer movimientos).
