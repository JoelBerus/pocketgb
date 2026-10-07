# N1 🍎 · Evidencia de identidad y carpetas (iOS: N1a + N1b)

Rama `n1-ios-identidad` (desde `siguiente-nivel` en `c3d8405`), 2026-10-06. Plan: [hitos/N-README.md](../hitos/N-README.md) §3.1, §3.2 y §4 N1; decisión ND11. Guía para Joel: [guia/carpetas.md](../guia/carpetas.md).

## 1. Qué cambia

### N1a · Identidad y metadatos
| Pieza | Archivo | Qué hace |
|---|---|---|
| Huella sin abrir el juego | `Library/RomFingerprint.swift` (`RomFingerprint`) | SHA-256 con CryptoKit, primeros 16 bytes en hex, **igual que el núcleo**: en Game Boy solo los bytes que declara la cabecera (`32 KiB << rom[0x148]`, el excedente se ignora como en `cart_load`), en GBA el archivo entero (`gba_load_rom`). Sin huella si el núcleo lo rechazaría antes de hashear (tamaño, código de ROM, ROM truncado, sin 0x96 en GBA). Lectura coordinada por bloques de 1 MiB, cancelable. **Nunca** lee un placeholder ni un `.notDownloaded` de iCloud (la lectura coordinada forzaría la descarga). |
| Caché (ruta, tamaño, fecha) → huella | `FingerprintCacheData` (`Application Support/Library/fingerprint-cache.json`) | `verified` si coinciden tamaño y fecha; `stale` (pista provisional) si cambiaron o el archivo no se puede leer ahora; se vacía al cambiar de carpeta y se poda a las rutas presentes (salvo si se llegó al tope). Es solo una caché: si se daña, se recalcula. |
| Cola en segundo plano | `LibraryStore.startHashing` | `Task.detached(priority: .utility)`, cancelada por cada escaneo nuevo; resultados por lotes (16 archivos o 400 ms) al actor principal; la caché se guarda cada 256 huellas y al terminar. `learnFingerprint` registra la huella que da el núcleo al abrir. |
| Metadatos por huella | `Library/LibraryPreferences.swift` (formato 2) | `games[huella]` = favorito, último juego (+ ruta), oculto, alias y **ajustes por juego**. Mientras no hay huella, `pendingByPath[ruta]`; al conocerla (caché verificada, cálculo o apertura) se une a la huella (`adopt`, `merging`: lo marcado en cualquiera se conserva; en conflicto gana la huella). `fingerprints[ruta]` guarda la última huella conocida al abrir (pista para juegos que no se pueden hashear, p. ej. iCloud sin descargar). |
| Ajustes por juego | `Settings/EmulationSettingsView.swift`, `Settings/Settings.swift`, `App/AppState.swift` | Salen de `UserDefaults` (`gameplaySettings.perGame`, por ruta) y pasan a las preferencias por huella. `emulation(with:)` recibe los ajustes; al abrir, `AppState` calcula la huella de los bytes leídos y busca los ajustes por ella (valen aunque el archivo se acabe de mover). En `Input/ControlsSettings.swift` solo se documenta `perGame` como copia antigua y se retira `setOverrides` (nada de geometría ni de controles). |
| Duplicados | `LibraryIdentity.markDuplicates`, `RomEntry.duplicatePaths` | Misma huella en varias rutas: insignia «Duplicado» en tarjeta y fila (texto, con VoiceOver) y en el detalle «También en: B › C › archivo.gb». Comparten metadatos, partida local, estados y portada; «Continuar» muestra una sola tarjeta (la copia abierta la última vez). |
| Robustez | `LibraryPreferences.load`/`persist` | Ver §2. |

### N1b · Carpetas
| Pieza | Archivo | Qué hace |
|---|---|---|
| Escaneo recursivo | `Library/LibraryScanner.swift` | Hasta `maxFolderDepth = 5` niveles bajo la raíz, tope `maxEntries = 5 000` (orden estable; aviso «Se muestran los primeros 5 000 juegos»). Reservados (ND11): `.` se ignora, `PocketGB/` en la raíz (sin distinguir mayúsculas) no se escanea, las carpetas `_…` quedan apartadas en cualquier nivel. No sigue enlaces simbólicos a carpetas. Placeholders de iCloud y errores por archivo como antes. |
| `folderPath` | `RomEntry.folderPath`, `locationText`, `displayPath` | Componentes desde la raíz sin el archivo (`[]` = sin categoría). El detalle muestra «Homebrew › Pruebas de vídeo › … · dmg-acid2.gb»: en una línea junto a las insignias si cabe; si no, debajo y entera (`ViewThatFits`, nada recortado, también en AX5). |
| Consejo en la app | `Settings/LibrarySettingsView.swift` | Pie de «Carpeta de juegos»: subcarpetas hasta 5 niveles, `_`/`.` y `PocketGB` no se leen. La lista de ocultos muestra la ubicación. |
| Guía | `docs/guia/carpetas.md` | Español llano: categorías y subcategorías, 5 niveles, `_` para apartar, `PocketGB/` reservada, `.zip` no se lee, límites de tamaño, `.sav` junto al ROM, mover y renombrar, duplicados, preferencias apartadas, ejemplo de §3.2. |

Partidas, estados y portadas **no cambian de clave** (ya iban por huella del núcleo). La ruta de guardado (regla dura 6) no se toca.

## 2. Formato nuevo y migración

**`preferences.json` formato 2** (claves ordenadas):
```json
{"fingerprints":{"Rojo/rojo.gb":"fp-rojo"},
 "games":{"fp-rojo":{"alias":"Mi Rojo","favorite":true,"hidden":false,"lastPlayed":781000000,
                     "lastPlayedPath":"Rojo/rojo.gb","overrides":{"colorForGameBoy":true,"compatPalette":5}}},
 "importedLegacyGameSettings":true,"layout":"list",
 "pendingByPath":{"nunca.gb":{"alias":"Pendiente","favorite":true,"hidden":false,"overrides":{"gbaSaveType":3}}},
 "sort":"recent","version":2}
```

**Migración formato 1 → 2** (sin clave `version`; `LibraryPreferencesData.migrating`): lo que iba por una ruta con huella conocida (`fingerprints[ruta]`) pasa a `games[huella]`; el resto queda en `pendingByPath`. `hiddenFingerprints` y `aliasesByFingerprint` van directos a la huella; como en la versión 1, el alias de la huella gana al de la ruta. Antes de escribir el formato 2 se copia el original a `preferences.v1.json` (nunca se pisa una copia previa).

**Ajustes por juego de `UserDefaults`** (`importLegacyGameSettings`, una vez, marcado con `importedLegacyGameSettings`): cada `perGame[ruta]` va a la huella de esa ruta si se conoce o a `pendingByPath[ruta]`; lo que ya tenía la huella gana. **La copia de `UserDefaults` no se borra** (queda como respaldo y vuelve a importarse si las preferencias se apartan por daño). En DEBUG con ajustes solo en memoria no se importa ni se marca.

**Robustez (como Android `LibraryPreferencesFile`):**
- JSON que no se entiende (también una colección con tipo incorrecto) → se aparta como `preferences.corrupt-<yyyyMMdd-HHmmss>.json` (con `-2`, `-3`… si existe), se empieza de cero y se avisa con alerta. Un campo dañado dentro de un juego solo vuelve a su valor por defecto.
- Error de E/S al leer → aviso y **escrituras bloqueadas** en la sesión: nunca se pisa un archivo que no se pudo leer. Igual con un archivo de una versión más nueva (`version > 2`).
- Escritura atómica (temporal + `fsync` + `rename` + `fsync` del directorio) en cola serie; un fallo se avisa una vez por racha (`onIssue` → alerta de la app); un temporal completo sin archivo principal se recupera al arrancar.

## 3. Tests (TDD)
Suite completa con el script del CI (incluye unitarios, UI, catálogo y build Release del simulador), sobre el commit `845232b`:
```
$ with-lock.sh sim tools/ios-screenshots.sh <scratchpad>/n1-full
✔ Test run with 200 tests in 20 suites passed after 10.882 seconds.
** TEST SUCCEEDED **
xcodebuild Release: exit 0
xcodebuild test: exit 0
```
Antes había 172 (`@Test` en `c3d8405`). Nuevos: 28 (y 6 existentes adaptados al API por huella: `GBATests`, `SettingsTests`, `LibraryPreferencesTests`, `LinkPartnersTests`, y `LibraryScannerTests.findsOnlyGameBoyFilesInSubfolders`, que antes exigía profundidad 1).

| Suite | Tests | Qué comprueba |
|---|---|---|
| `LibraryFoldersTests` (N1b) | `syntheticTreeHonoursDepthAndReservedNames`, `reservedFolderRules`, `entryCapStopsTheScanDeterministically`, `symlinkedFoldersAreNotFollowed`, `searchFiltersFavoritesAndRecentWorkInDeepFolders` | Árbol real en una carpeta temporal: `raiz.gb` → `[]`; `Pokémon/1ª generación/rojo.gb` → `["Pokémon","1ª generación"]` y su `.sav` en la misma carpeta; `A/B/C/D/E/cinco.gb` (5 niveles) sí, `A/B/C/D/E/F/seis.gb` no; `.oculta/`, `A/.oculta/`, `_apartada/`, `A/_Revisar/`, `PocketGB/…` fuera; `A/PocketGB/dentro.gb` dentro (solo es reservada en la raíz); placeholder `Kirby/.kirby.gba.icloud` → `Kirby/kirby.gba` sin descargar; `.txt` y `.zip` fuera; un `.gbc` corto sigue como error por archivo. Tope con orden estable; enlaces simbólicos a carpetas no se siguen; búsqueda, filtro GB, Favoritos y recientes con un juego a 5 niveles. |
| `RomFingerprintTests` (N1a) | `gameBoyFingerprintMatchesTheCore`, `paddedGameBoyDumpHashesOnlyTheDeclaredSize`, `romsTheCoreRejectsHaveNoFingerprint`, `gameBoyAdvanceFingerprintMatchesTheCore`, `testROMFixturesMatchTheCore`, `iCloudPlaceholderIsNeverRead`, `cacheLookupIsInvalidatedBySizeOrDate`, `resolveQueuesOnlyPlayableFilesWithoutAVerifiedFingerprint`, `storePersistsAndInvalidatesTheCache`, `otherLibraryFolderResetsTheCache` | Igualdad con `CoreBridge`/`GBACoreBridge` (ROMs sintéticos y las fixtures); volcado con relleno = huella de los 32 KiB declarados, ≠ SHA del archivo entero; ROM truncado → el núcleo lanza y no hay huella; un placeholder de iCloud nunca se lee; caché `verified`/`stale`/`missing` por tamaño y fecha; nunca se encola un archivo sin descargar; con `LibraryStore` real: la caché se escribe, el segundo arranque la usa sin hashear y otro contenido en la misma ruta (tamaño y fecha nuevos) se recalcula. |
| `LibraryIdentityTests` (N1a) | `version1FileMigratesWithoutLoss`, `legacyPerGameSettingsMoveToTheFingerprint`, `provisionalPathMetadataMergesIntoTheFingerprint`, `corruptFileIsQuarantinedAndReported`, `structurallyInvalidVersion2IsQuarantinedNotEmptied`, `unreadableFileIsNeverOverwritten`, `newerVersionIsReadButNotOverwritten`, `writeErrorsAreReportedOncePerStreak`, `interruptedFirstWriteIsRecoveredFromTheTemporary`, `movingARomBetweenFoldersKeepsEverything`, `duplicatesShareMetadataAndShowOnce`, `staleMirrorOfADuplicateIsBackedUpAndRefreshed`, `learnedFingerprintFromTheCoreUpdatesTheEntry` | Fixture literal del `preferences.json` v1 y del blob `gameplaySettings` de `UserDefaults` tal como los escribía N0: cada juego muestra lo mismo que antes, ahora por huella; backup `preferences.v1.json` byte a byte; el blob de `UserDefaults` queda intacto y no se reimporta. Cuarentena con los bytes originales y nombre sin pisar (`-2`); colección dañada → cuarentena (no vacía); campo dañado → solo ese campo; archivo ilegible (directorio) → no se escribe nada; versión 3 → no se sobrescribe; error de escritura → un aviso por racha. **Mover** (carpeta temporal real + `LibraryStore` + preferencias en disco): favorito provisional por ruta antes de la huella → se une a ella al hashear; tras mover ROM y `.sav` a `Pokémon/2ª generación/`, el juego conserva favorito, alias, oculto, ajustes y último juego (también releyendo de disco), y su partida, estado `.auto` y portada por huella. **Duplicados:** dos copias con `duplicatePaths` cruzados, favorito compartido, una sola tarjeta en recientes, misma partida local y espejos distintos. |

Igualdad de huella con el núcleo (fixtures en `FIXTURE_DIR`, fuera de `~/Documents`):
```
N1a huella dmg-acid2.gb: app=464e14b7d42e7feea0b7ede42be7071d núcleo=464e14b7d42e7feea0b7ede42be7071d
N1a huella cgb-acid2.gbc: app=197fb0bcec544f0400527fc707e0a94f núcleo=197fb0bcec544f0400527fc707e0a94f
N1a huella arm.gba: app=77ee88662552bdc885c1080c0172ff11 núcleo=77ee88662552bdc885c1080c0172ff11
$ shasum -a 256 dmg-acid2.gb cgb-acid2.gbc arm.gba     # los 32 primeros hex coinciden (archivos sin relleno)
464e14b7d42e7feea0b7ede42be7071d…  dmg-acid2.gb
197fb0bcec544f0400527fc707e0a94f…  cgb-acid2.gbc
77ee88662552bdc885c1080c0172ff11…  arm.gba
```
Sin `FIXTURE_DIR` ni ROMs de prueba en el repo, `testROMFixturesMatchTheCore` se desactiva con su motivo (`.enabled(if:)`); los ROMs sintéticos cubren lo mismo siempre.

## 4. Catálogo de capturas
`tools/ios-screenshots.sh`: **112 PNG** (106 anteriores + 6 nuevas), `ScreenshotTests` sin fallos (`testScreenCatalog passed (1094.965 seconds)`). Las nuevas, revisadas a ojo (herramienta Read):

| Captura | Lo que se ve |
|---|---|
| `library-folders-portrait-light` | Filtro GB (sin carril «Continuar»): las dos copias de DMG-ACID2 con «GB · Duplicado · 27 sept» en su propia línea, sin solaparse con el título; RTC3TEST «Sin jugar» sin insignia |
| `library-folders-list-portrait-dark` | Lista: CGB-ACID2 favorito; DMG-ACID2 con «Duplicado»; el carril «Continuar jugando» muestra **una sola** tarjeta de DMG-ACID2 (la copia abierta la última vez) |
| `game-details-folders-portrait-light` / `-dark` | Insignias «GB» y «Duplicado»; debajo, entera, «Homebrew › Pruebas de vídeo › Acid › Game Boy › Clásicos · dmg-acid2.gb» (5 niveles); título; «También en: Copias › dmg-acid2.gb» con icono; estadísticas y «Continuar» |
| `game-details-folders-ax5-portrait-light` | Texto accesible 5: insignias en su fila y la ruta completa en varias líneas, sin recortar (en la primera versión el `HStack` la dejaba en «Hom e…gb»; se corrigió con `ViewThatFits`) |
| `library-preferences-quarantined-portrait-light` | Alerta «Preferencias de la biblioteca dañadas» con el nombre del archivo apartado y «Tus partidas, estados y ROMs no se tocan.» |

También revisadas (cambian por este lote): `game-details-portrait-light` (juego en la raíz: misma disposición de siempre, «GB dmg-acid2.gb» en una línea), `settings-library-portrait-dark` (pie nuevo: subcarpetas hasta 5 niveles, «_», «.» y «PocketGB»; el oculto muestra «Pruebas · rtc3test.gb»), `game-settings-portrait-light` y `game-settings-gba-portrait-dark` (ajustes por juego ahora por huella o ruta provisional: «Personalizado» en Color/Paleta y en Tipo de partida, como antes).

## 5. Otras comprobaciones
```
$ with-lock.sh build xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -configuration Release \
    -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData-release build
** BUILD SUCCEEDED **                    (también con CODE_SIGNING_ALLOWED=NO)
$ rg -n 'URLSession|NWConnection' ios/PocketGB ; echo $?
1                                        (sin resultados: sin red)
$ git diff c3d8405 --stat -- ios/PocketGB/Input/ControlsSettings.swift
 ios/PocketGB/Input/ControlsSettings.swift | 11 +++--------    (solo el comentario de perGame y retirar setOverrides)
```
Compilación de la app sin avisos nuevos (`xcodebuild build … | grep warning:` vacío). No se tocan `ControlsOverlayView.swift`, `ControlsLayout.swift` ni `GameScreen.swift`, ni la ruta de guardado (`Saves/*`).

## 6. Comportamientos documentados y riesgos
- **Mover un juego** conserva todo en cuanto se conoce su huella. Un juego movido que está **solo en iCloud** (sin descargar) no se puede hashear: hasta que se descargue no muestra sus metadatos (no se pierden: siguen en su huella). Lo provisional por ruta de un juego nunca abierto ni hasheado que se mueve antes de hashearse se queda en la ruta vieja (no hay forma de saber que es el mismo archivo).
- **Pista de ruta provisional:** si un archivo se sustituye por otro ROM con el mismo nombre, durante el cálculo (≈ 1 s) se ven los metadatos del anterior; al terminar se corrige.
- **Duplicados:** ocultar o marcar como favorita una copia afecta a las dos (es el mismo juego). Cada copia tiene su `.sav` junto a ella: se actualiza el de la copia que se abre; el de la otra se pone al día al abrirla, con la regla de siempre de `SaveResolution` (gana la más reciente, normalmente la local; el perdedor va antes a backup salvo que sea una escritura reconocida de la app, `recognizesOwnedMirror`). Test `staleMirrorOfADuplicateIsBackedUpAndRefreshed`.
- **«Nuevo»** sigue yendo por ruta: un juego movido aparece como «Nuevo» una vez (solo presentación).
- **Placeholder de la portada generada:** su semilla pasa a ser la huella también para juegos nunca abiertos, así que su color puede cambiar una vez al calcularse la huella.

## 7. No verificado
- La biblioteca real de Joel en iCloud (`GMRoms/`): latencia de iCloud, archivos `.downloaded` frente a `.current`, carpetas grandes y la migración de sus preferencias y ajustes reales. Se verifica en el iPhone al instalar el lote.
- Rendimiento del cálculo de huellas con cientos de ROMs GBA de 32 MiB en el dispositivo (en el simulador, las fixtures tardan milisegundos).
