# N5 · Portadas (iOS): respuesta a la auditoría Opus

Antes de corregir se hizo merge de `siguiente-nivel` en la rama (trae ND17 y el N-README; sin conflictos).

| # | Respuesta |
|---|---|
| N5iA-1 | Corregido. `CoverDecoder.decode`/`reduce` reciben `formats`, y por defecto valen `CoverFormat.folder` (png, jpeg y webp, como Android). Solo `CoverStore.importImage` pasa `CoverFormat.imported`, que incluye HEIC. Las copias guardadas (`GameArtworkStore` con `untrusted`) se leen solo como PNG (`CoverFormat.stored`). Test `heicIsOnlyAcceptedWhenImporting`: un `Juego.png` HEIC en la carpeta acaba en la generada; el mismo HEIC al importarlo se acepta; un JPEG renombrado a `.png` en la caché no se usa. **Mutación:** si se acepta cualquier firma en `decode`, el test falla con 6 incidencias (salida abajo). |
| N5iA-2 | Corregido. Las lecturas de la carpeta van en una `OperationQueue` con un máximo de 3 concurrentes (`CoverStore.maxConcurrentFolderReads`): una descarga lenta de iCloud ya no frena las demás portadas. La guía avisa de que **ver la biblioteca puede descargar portadas de iCloud** y explica cómo evitarlo. |
| N5iA-3 | Corregido. `saveEncoded` es `async`: escribe en la cola del almacén y vuelve al hilo principal solo para actualizar el estado. Las imágenes del catálogo DEBUG usan `applyDemo(key:image:)`, que no toca el disco. |
| N5iA-4 | Corregido. `LibraryScanner.ScanResult.complete` solo es verdadero si la raíz se pudo listar, ninguna subcarpeta falló, no se llegó al tope y ningún ROM quedó ilegible. `LibraryStore` solo purga con `complete`. Test `onlyACompleteScanAllowsPruning`: una carpeta inexistente da un escaneo vacío y no completo; una carpeta vacía legible sí cuenta como completa; una subcarpeta con permisos 000 hace que no lo sea. |

## Mutación de N5iA-1 (`decode` acepta cualquier firma)
```
✘ Test heicIsOnlyAcceptedWhenImporting() recorded an issue at CoversTests.swift:228: Expectation failed: (CoverDecoder.decode(heic) → <CGImage …>) == nil
✘ … CoversTests.swift:229: (CoverDecoder.reduce(heic) → 350 bytes) == nil
✘ … CoversTests.swift:231: (CoverDecoder.decode(jpeg, formats: CoverFormat.stored) → <CGImage …>) == nil
✘ … CoversTests.swift:237: (covers.shown(…) → ShownCover(kind: sidecar, …)) == .generated
✘ Test heicIsOnlyAcceptedWhenImporting() failed after 4.776 seconds with 6 issues.
```
Después se restauró el código.

## Verificación tras las correcciones (con with-lock.sh sim)
```
tools/ios-screenshots.sh (iPhone 17 Pro, completo):
✔ Test run with 294 tests in 26 suites passed after 11.213 seconds.
xcodebuild Release: exit 0
xcodebuild test: exit 0        (catálogo: 220 capturas; las n5-* regeneradas y revisadas)
iPhone SE (3.ª gen.):
✔ Test run with 294 tests in 26 suites passed after 11.332 seconds.
Release generic/platform=iOS:
** BUILD SUCCEEDED **
```
