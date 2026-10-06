# Auditoría Opus (respaldo) · Android A1–A4 · 1ª vuelta

Commit auditado: `44cc0cb`. Veredicto: **APROBAR CON CAMBIOS** (sin violaciones de reglas duras: sin ROMs/saves en el historial, sin red, `core/` intacto, Jugar deshabilitado). Reejecutado en copia scratch: 49/49 JVM, `assembleRelease` verde, Release sin `INTERNET`. Instrumentados no ejecutados (sin emulador).

| ID | Sev. | Ubicación | Problema |
|---|---|---|---|
| H1 | media | `LibraryViewModel.kt:93-100,203-215` | `trySend` de la carga inicial fuera del lock: un `mutate` en esa ventana encola antes y el disco queda con el estado antiguo (reproducido con test de estrés: `MISMATCHES=1`). |
| H2 | media | `LibraryScanner.kt:45-63`; keys en `LibraryContent.kt`, `LibrarySettingsScreen.kt` | Ids duplicados (nombres repetidos en proveedores remotos) → `IllegalArgumentException` en LazyGrid/LazyColumn, crash en cada arranque; además se mezclan favoritos/ocultos. |
| H3 | media | `SafDocumentTree.kt`, `LibraryEnvironment.kt`, `LibraryViewModel.kt` | Proveedor SAF mal portado: `IllegalStateException`/`UnsupportedOperationException`, `getColumnIndexOrThrow`, `getLong` no numérico → crash no capturado repetido en `ON_START`. |
| H4 | baja | `LibraryPreferences.kt:139-147` | `load()` aparta como `.corrupt` ante cualquier excepción (incluso I/O transitorio), `renameTo` pisa un `.corrupt` previo; enum desconocido descarta todo. |
| H5 | baja | `LibraryViewModel.kt:107-133,169-170` | Estados publicados desde varios jobs sin serializar (escaneo viejo publica `Ready` tras `forgetFolder`; carpeta se repersiste). |
| H6 | baja | `LibraryEnvironment.kt:19-23` | `DetailsError.Remote` inalcanzable en producción (`remote=false` siempre). |
| H7 | baja | tests ViewModel/Preferences/SAF | Tests que no prueban lo que dicen (cancelación, carga previa, atomicidad, tope 8 MiB real). |
| H8 | baja | `native_session.c:141-150,488` | `render_frame` no valida `buffer.format` de 32 bpp ni el retorno de `setBuffersGeometry`. |
| H9 | baja | `LibraryViewModel.kt:112-114` | Fallo de `takePersistableUriPermission` se muestra como "permiso revocado". |
| H10 | baja | `GameDetailsScreen.kt:79-85` | Tras muerte de proceso, el detalle muestra "no disponible" antes del primer escaneo. |
| H11 | baja | `AndroidManifest.xml:4` | `allowBackup=true` incluye el tree URI; decidir reglas de backup antes de A5. |
| H12 | baja | `A4-android-evidencia.md:7` | Dice "sin commit" estando en `44cc0cb`. |

Observaciones sin contar como hallazgo: `onOpenDetails` sin `dropUnlessResumed`; en el gameplay debug la sesión arranca aunque se deniegue el foco (no copiar en A5); `gradle-wrapper.properties` sin `distributionSha256Sum`.
