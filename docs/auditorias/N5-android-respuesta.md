# N5 · Portadas (Android) — respuesta a la auditoría Opus

| # | Respuesta |
|---|---|
| N5A-1 | Corregido: `N5Catalog.kt` usa `androidx.core.graphics.createBitmap`. Lint desde limpio sin avisos en archivos de N5 (salida abajo). |
| N5A-2 | Corregido: `ArtworkStore.retainOnly` y `CoverRepository.pruneFolderCache(entries)`; `LibraryScreen` la llama en segundo plano cada vez que hay una biblioteca lista. Solo borra copias de `covers/folder/`, nunca la carpeta del usuario. Test JVM `pruningKeepsOnlyFolderCopiesOfCurrentEntries`. |
| N5A-3 | Corregido: `StorageUsage` mide `artwork/`, `artwork-pinned/`, `covers/imported/` y `covers/folder/` (no `covers/settings.json`). Test JVM `storageUsageIgnoresTheSettingsFile`. |
| Carrera | Corregida: candado por clave en `CoverRepository.loadFolderImage`, así dos tarjetas del mismo juego no leen la imagen a la vez. El último recurso `decodePlain` ahora también va protegido (`runCatching`). |
| Decisión 6 | Pendiente de Joel. |

## Verificación (desde limpio, `git archive 4285ccc`)

```
$ ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL in 3m 59s
JVM 726 fallos 0                       (724 + los 2 tests nuevos)
0 errors, 25 warnings                  (0 en archivos de N5)
$ with-lock.sh emu ./gradlew ... :app:connectedDebugAndroidTest -P...package=com.joelbermudez.pocketgb.covers
BUILD SUCCESSFUL in 44s
15 testcases, 0 failures
```
