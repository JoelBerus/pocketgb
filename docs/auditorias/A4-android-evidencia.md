# Evidencia · Android A4 — Biblioteca

**Fecha:** 2026-10-04

**Rama:** `codex/android-port`. Implementación original: `44cc0cb8ecda6adec010c8a682e42ff1f813c2c2`. Correcciones de la 1ª vuelta: `5f62e059489feddceeadc3d52ac88d4aa99b34ce` (72 JVM, 70 instrumentados, Debug/Release/lint y núcleo 65/65 verificados sobre ese árbol). Correcciones de la 2ª vuelta: commit siguiente a `5f62e05` (73 JVM; ver "2ª vuelta").

**Alcance:** biblioteca SAF (`SafDocumentTree`, `LibraryFolderStore`), `LibraryViewModel` con escaneo fuera del hilo principal y preferencias en orden, Biblioteca (búsqueda, filtros, cuadrícula/lista, orden, "Continuar jugando", estados vacío/cargando/error), Favoritos reales, detalle del juego, Ajustes › Biblioteca y catálogo debug. Jugar sigue deshabilitado hasta A5 (regla dura 6: no se abre un juego sin la ruta de guardado atómica).

## Implementación verificada

- `SafDocumentTree` solo consulta (`COLUMN_DOCUMENT_ID`, `DISPLAY_NAME`, `MIME_TYPE`, `SIZE`, `FLAGS`) y lee como mucho `limit` bytes; nunca escribe. Excepciones tipadas: `SecurityException` → `TreePermissionException`, carpeta raíz inexistente → `TreeMissingException`, lectura fallida → `DocumentReadException(remote)` (remoto salvo en los tres proveedores locales del sistema).
- `LibraryFolderStore` guarda el tree URI en `SharedPreferences` privadas, toma `READ|WRITE` persistentes (si `WRITE` se rechaza, toma solo `READ` y lo registra), detecta revocación con `persistedUriPermissions` y `forget()` libera el permiso.
- `LibraryViewModel` (AndroidX `ViewModel`, creado con `by viewModels` + factory) expone `state` (`NoFolder`, `Scanning(previous)`, `Ready`, `Failed(PermissionRevoked | FolderMissing | Unreadable)`), `prefs`, `query`, `filter` y `folderName`. El escaneo corre en `Dispatchers.IO` y cancela el anterior; las preferencias se cargan en IO, los cambios previos a la carga se aplican después y se escriben por un canal serie. `MainActivity` reescanea en `ON_START` (solo fuera del catálogo debug).
- Detalle: `loadDetails` lee el ROM completo con tope 8 MiB + 1 byte, obtiene metadatos con `CoreBridge.loadRom` (tipo de cartucho, tamaños, checksums, SHA-256), registra la huella y devuelve `DetailsLoad` con errores tipados (`DetailsError`).
- UI Material 3 con APIs estables, solo colores de `MaterialTheme`. `LibraryContent`, `FavoritesContent`, `GameDetailsContent` y `LibrarySettingsContent` no dependen del ViewModel y las reutiliza el catálogo. Los insets se consumen una vez en `PocketGBApp` (`consumeWindowInsets`) y la búsqueda usa `imePadding`.
- Navegación: se añadieron `FavoritesRoute.Details` y `SettingsRoute.Library`; Favoritos abre el detalle dentro de su propio stack.
- El botón de jugar aparece deshabilitado con "Jugar se activa en A5 (partidas seguras)". La UI de biblioteca no usa `EmulatorSession`.

## Verificación fresca

Núcleo host (sin tocar `core/`):

```bash
make -C core test
```

Resultado: `65/68 PASS · requeridos: 65/65 PASS` (los tres `known-fail` documentados de M1).

Android:

```bash
cd android
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew clean :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado: `BUILD SUCCESSFUL in 2m 58s`.

- 49/49 tests JVM: biblioteca-ViewModel 14, preferencias 8, escáner 8, modelos 3, navegación 4, apariencia 3, geometría 3, manifiesto 2, viewport 2, motor táctil 2.
- 61/61 tests instrumentados (`Small_Phone_API_35`): shell 2, catálogo 7, lifecycle 2, audio 3, puente 4, sesión 7, carga nativa 1, controles 2, superficie 1, **SAF 9** y **Compose de biblioteca 23**.
- Debug, Release y lint: 0 errores, 12 advertencias (7 son sugerencias `UseKtx` en archivos nuevos de `library/`; las otras 5 son anteriores a A4).
- Tras corregir un test JVM con una carrera propia (esperaba el estado inicial `NoFolder` como resultado), se repitió 5 veces `:app:testDebugUnitTest --rerun-tasks` sin fallos y después la verificación completa de arriba.

### Pruebas instrumentadas de SAF

`TestDocumentsProvider` (en el APK de test) sirve un directorio temporal con ROMs sintéticas válidas, una subcarpeta, una subcarpeta anidada (ignorada), un archivo de 9 MiB disperso, un archivo de 16 bytes de cabecera inválida y un documento virtual. Las pruebas ejecutan `SafDocumentTree` + `LibraryScanner` sobre su tree URI y comprueban: ids esperados, `TOO_LARGE`, `INVALID_HEADER`, `REMOTE_UNAVAILABLE`, límite de `readHead`, permiso revocado (`TreePermissionException`), carpeta raíz ausente (`TreeMissingException`), error de subcarpeta inexistente, que el escaneo no modifica ningún archivo, y el flujo completo `LibraryViewModel` → núcleo real (tipo de cartucho, huella de 64 hex registrada en preferencias, estados `PermissionRevoked` y `FolderMissing`).

## Revisión visual

`tools/android-screenshots.sh` generó 32/32 capturas (16 pantallas × claro/oscuro; se añadieron `library-list`, `library-search`, `library-error`, `library-detail`, `library-detail-problem`, `favorites` y `settings-library`). Se inspeccionaron las nuevas en claro y/o oscuro. La primera pasada detectó y se corrigió: subtítulos de tarjeta truncados ("512…", "1 M…") → etiqueta corta GB/GBC; detalle demasiado largo (portada alta, filas de 72 dp) → portada 2,2:1 y filas compactas; chip de sistema deshabilitado con poco contraste → etiqueta de texto; fila "Cambiar carpeta" sin icono. Además se capturó la app real (sin catálogo) para comprobar que los insets no se duplican.

Observación abierta: el botón Jugar deshabilitado usa el estilo de deshabilitado de Material 3 (contraste bajo por diseño); el texto explicativo está dentro del botón.

## Privacidad y separación Release

`aapt dump permissions` sobre Release solo muestra `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; no existen `INTERNET` ni `ACCESS_NETWORK_STATE`. La búsqueda de cadenas en los DEX Release no encontró `DebugCatalog`, `GameplayDebugScreen`, `NativeVideoScreen` ni `TestDocumentsProvider`; en Debug sí aparece `DebugCatalog`. No se versionaron ROMs, boot ROMs, partidas, estados, APK ni capturas.

## Desviaciones y límites

- El proveedor de prueba es un `ContentProvider` en Java que implementa el protocolo de `DocumentsContract`, **no** un `DocumentsProvider`, y es `exported=true` sin permiso (solo en el APK de test). Motivos medidos: `DocumentsProvider` exige `exported=true` y `MANAGE_DOCUMENTS`; la instrumentación corre con el UID de la app mientras el proveedor corre en el proceso del APK de test (otro UID, sin la librería estándar de Kotlin), por lo que no se podía conceder el URI ni compartir archivos. Se provisiona con `call()`, y la revocación se simula con `deny`.
- No se ejercitó el selector real del sistema ni `takePersistableUriPermission`/`persistedUriPermissions` contra un proveedor real: no hay forma de concederlos desde una prueba sin UI del sistema. `LibraryFolderStore` está cubierto solo por revisión de código y por el fake en las pruebas del ViewModel. Pendiente para la prueba manual en teléfono (elegir carpeta real, revocar el permiso, mover/borrar la carpeta).
- El emulador se reinició con `-gpu swiftshader_indirect` tras caídas de adb por presión de memoria del Mac (`device offline` en Gradle); la ejecución final no tuvo caídas.
- Las preferencias y los ids son relativos a la carpeta: cambiar de carpeta conserva favoritos de juegos con la misma ruta relativa.
- Jugar, última partida real (`recordPlayed`) y la fila "Continuar jugando" quedan vacías hasta A5.

## Correcciones de la 1ª vuelta

Auditorías: `A4-android-codex.md` (A4-01..04) y `A4-android-opus.md` (H1..H12). Las cifras de la sección "Verificación fresca" anterior son las del commit `44cc0cb`; las de esta sección son del árbol de trabajo corregido.

| Hallazgo | Corrección | Test |
|---|---|---|
| H1 | El escritor persiste siempre el último `_prefs.value` (no el snapshot de la petición); los cambios previos a la carga se aplican sobre lo cargado. Ya no hay orden de encolado que pueda dejar el disco por detrás. | `LibraryViewModelTest`: `changesMadeWhileTheLoadIsReallyInFlightAreAppliedOnTopOfIt` (carga bloqueada con latch), `aChangeInsideTheLoadWindowCannotLeaveTheDiskBehindMemory`, `concurrentChangesDuringLoadAlwaysEndUpInTheFile` (150 rondas × 4 hilos; archivo == memoria). No se comprobó que fallen contra el código anterior. |
| A4-02 | Si guardar falla, la versión queda pendiente (`lastSaved` no avanza); `flushPreferences()` devuelve `PersistResult` (`Saved`/`Failed`); reintento en `ON_STOP` (`retryPendingWrites`) y escritura síncrona final en `onCleared`. | `preferenceWriteFailureStaysPendingAndFlushReportsIt`, `pendingWriteIsRetriedByTheBlockingFinalWrite`, `loadIoFailureNeverOverwritesTheFileWithDefaults` (almacén con I/O inyectado `FlakyStore`). |
| A4-03 / H7 | I/O de archivo detrás de `PreferencesFileOps`; tests que prueban de verdad atomicidad y recuperación; cancelación real del primer escaneo (latch de entrada al proveedor); carga previa forzada con latch; en SAF, ROM con SIZE anunciado 32 KiB y contenido de 9 MiB. | `LibraryPreferencesTest`: `failureWhileWritingTheTemporaryKeepsThePreviousFile`, `failedRenameKeepsThePreviousFileAndReportsTheError`, `completeTemporaryIsRecoveredWhenTheMainFileNeverExisted`, `partialTemporaryIsIgnoredWhenTheMainFileExists`. `LibraryViewModelTest`: `theFirstScanIsReallyCancelledAndNeverPublishesItsResult`. `SafLibraryTest`: `declaredSmallSizeButHugeContentIsStopppedByTheReadCapAtMaxPlusOne`. |
| A4-01 | `TreePermissionException` de una subcarpeta se propaga (estado `PermissionRevoked`); solo el resto de errores de subcarpeta se ignora. | `LibraryScannerTest.permissionRevokedOnASubfolderPropagatesInsteadOfReturningAPartialLibrary`, `missingSubfolderIsRecoverableAndIgnored`; `LibraryViewModelTest.revokedPermissionOnASubfolderIsNotAPartialLibrary`; `SafLibraryTest.revocationOnASubfolderPropagatesAndTheViewModelReportsPermissionRevoked` (proveedor real, raíz OK y subcarpeta revocada). |
| H2 | Ids únicos: las entradas con la misma ruta relativa reciben todas el sufijo `#<hash del id de documento>` (determinista, independiente del orden del proveedor); `subfolder` no cambia. | `LibraryScannerTest.repeatedNamesGetDeterministicStableAndUniqueIds` (archivos y subcarpetas homónimas, orden invertido); `LibraryUiTest.repeatedFileNamesDoNotCrashGridListOrFavorites`. |
| H3 | `SafDocumentTree`/`ContentResolverRomSource` convierten `RuntimeException` (sin tragar `CancellationException`) en `IOException`/`DocumentReadException`; columnas con `getColumnIndex` (id y nombre obligatorios; mime/size/flags opcionales); SIZE no numérico = 0 (desconocido). Red de seguridad en `scan()` y `loadDetails` (estado `Unreadable`). | `SafLibraryTest.providerThrowingIllegalStateIsAnIoErrorNotACrash`, `cursorWithoutOptionalColumnsOrWithTextSizeIsTolerated`; `LibraryViewModelTest.providerFailuresMapToTypedErrors` (IllegalState/UnsupportedOperation), `providerRuntimeFailuresInDetailsAreTypedNotCrashes`. |
| H4 | `load()` propaga el `IOException` (sin apartar ni sobrescribir); solo el contenido ininterpretable se aparta como `.corrupt-<timestamp>` sin pisar uno previo; `coerceInputValues` para enums desconocidos. El ViewModel no guarda hasta haber cargado. | `LibraryPreferencesTest`: `corruptFileIsKeptAsideWithTimestampAndDefaultsAreUsed`, `secondCorruptionNeverOverwritesAnEarlierQuarantine`, `ioErrorWhileReadingIsPropagatedAndTheFileIsNotMovedAside`, `unknownEnumValueDoesNotDiscardTheRest`; `LibraryViewModelTest.loadIoFailureNeverOverwritesTheFileWithDefaults`. |
| H5 | Operaciones de carpeta serializadas con `Mutex`; cada operación lleva un número de generación y solo la vigente publica estado. | `LibraryViewModelTest`: `forgetFolderWhileAScanIsRunningNeverResurrectsTheFolderState`, `forgetFolderDuringChooseFolderLeavesNoFolderRemembered`. |
| H6 | `ContentResolverRomSource` usa `ProviderLocality.isRemote` (mismo criterio que `SafDocumentTree`); `DetailsError.Remote` es alcanzable. | `SafLibraryTest.romSourceMarksProviderFailuresAsRemoteWithTheSameCriterionAsTheTree`. |
| H8 | `native_session.c` valida `buffer.format` (RGBA_8888/RGBX_8888), `bits` y geometría antes de dibujar y descarta la ventana si `ANativeWindow_setBuffersGeometry` falla. | Sin test nuevo dedicado (el caso de formato inválido no es reproducible en el emulador); regresión cubierta por `GameSurfaceTest`/`NativeVideoScreen` y los tests de sesión instrumentados (todos en verde). `make -C core test` 65/65 y `make -C core asan` 65/65. |
| H9 | Error propio `LibraryError.AccessNotKept` ("No se pudo conservar el acceso a la carpeta") cuando falla `select`; la carpeta anterior sigue recordada. | `LibraryViewModelTest.chooseFolderScansAndRejectedGrantFails`; `LibraryUiTest.accessNotKeptHasItsOwnMessageAndRecovery`. Capturas `library-access-error-{light,dark}`. |
| H10 | Estado inicial `LibraryState.Loading` (no `NoFolder`); el detalle y Favoritos muestran carga hasta el primer escaneo. | `LibraryViewModelTest.initialStateIsLoadingNotNoFolder`, `withoutFolderEndsInNoFolderAfterTheFirstScan`; `LibraryUiTest.loadingStateShowsProgressAndNeverTheNoFolderInvitation`, `detailsShowLoadingUntilTheFirstScanInsteadOfUnavailable`. Capturas `library-loading-{light,dark}`. |
| H11 | `dataExtractionRules` + `fullBackupContent` excluyen `library_folder.xml` (tree URI). `files/library/preferences.json` sigue respaldándose (solo rutas relativas y huellas). Comentario `TODO(A5)` sobre `saves/`. | `ManifestPolicyTest.backupRulesExcludeTheFolderTreeUri`. |
| H12 / A4-04 | Esta evidencia fija el commit `44cc0cb` y registra las correcciones. | n/a |
| Observación: `Details` apilado dos veces | `AppNavigationState.push` ignora una ruta idéntica a la cima de la pila (equivalente a `singleTop`); `dropUnlessResumed` no sirve aquí porque Navigation 3 no cambia el ciclo de vida durante la transición. | `AppNavigationStateTest.pushingTheSameRouteTwiceStacksItOnce`. |

Pendiente (no se hace en esta vuelta): `distributionSha256Sum` en `gradle-wrapper.properties` (observación de Opus).

### Verificación de las correcciones

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew clean :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Resultado: `BUILD SUCCESSFUL`. JVM: 72 tests, 0 fallos (antes 49). Instrumentados (emulador `Small_Phone_API_35`): 70 tests, 0 fallos (antes 61). Release: `aapt dump permissions` solo lista `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (sin `INTERNET`); los DEX de Release no contienen `DebugCatalog` ni `TestDocumentsProvider`. `make -C core test`: 65/65 requeridos. `make -C core asan`: 65/65 requeridos. `tools/android-screenshots.sh`: 36 capturas (18 pantallas × claro/oscuro; nuevas `library-loading` y `library-access-error`, inspeccionadas visualmente en claro y oscuro respectivamente).

## 2ª vuelta (final)

Informes: `A4-android-codex-v2.md` (APROBAR CON CAMBIOS, 3 hallazgos) y `A4-android-deepseek-v2.md` (APROBAR sin hallazgos; su 1ª auditoría había pasado por alto H1–H3, por lo que no se le da peso). Auditado: `5f62e05`.

| ID | Corrección | Test |
|---|---|---|
| A4-V2-01 | El sufijo de id duplicado ya no usa `String.hashCode()`: son 6 bytes de SHA-256 del id de documento y, ante colisión residual, se desempata ordenando por id de documento (no por orden del proveedor). | `LibraryScannerTest.idsStayStableEvenWhenDocumentIdsShareAJavaHashCode` (`"Aa"`/`"BB"`, orden invertido). JVM: 73/73 tras el cambio. |
| A4-V2-02 | **Aceptado, sin cambio.** La ventana exacta de H1 no se fuerza con un seam; la corrección elimina el snapshot obsoleto (el escritor persiste siempre `_prefs.value`) y el test de 150 rondas × 4 hilos la cubre de forma probabilística. No se hizo mutation testing. | — |
| A4-V2-03 | Esta evidencia fija ahora el commit `5f62e05`. | — |

Tras V2-01 solo se reejecutaron los tests JVM (73/73); los instrumentados no se repitieron (el cambio no toca SAF ni UI). `distributionSha256Sum` sigue pendiente.
