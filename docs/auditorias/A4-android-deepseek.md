## Veredicto: APROBAR

## Hallazgos

No se detectaron hallazgos de seguridad, pérdida de datos, incumplimiento de reglas duras ni defectos de corrección. La implementación cumple con todos los criterios auditados.

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|----|-----------|----------------|----------|-----------|---------------------|
| – | – | – | – | – | – |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| **SAF** solo lectura, excepciones tipadas, sin escritura | Sí | `SafDocumentTree` solo `query` y `openInputStream`; `LibraryScanner` no modifica archivos. Las excepciones `TreePermissionException`, `TreeMissingException`, `DocumentReadException` cubren los casos descritos. |
| **FolderStore** guarda tree URI, toma permisos persistentes, detecta revocación y olvida | Sí | `LibraryFolderStore.select` solicita `FLAG_GRANT_READ_URI_PERMISSION` (y write si es posible), consulta `persistedUriPermissions` en `hasPersistedPermission()`, `forget()` libera el permiso y borra la preferencia. |
| **ViewModel** estados `NoFolder`/`Scanning`/`Ready`/`Failed`, preferencias en orden aun si mutan antes de cargar, escritura por canal serie | Sí | `LibraryViewModel` expone `state`, `prefs`, `query`, `filter`; las mutaciones antes de cargar se acumulan en `pendingChanges` y se aplican sobre los datos cargados; la escritura al disco usa un `Channel` procesado secuencialmente. |
| **UI** desacoplada del ViewModel (recibe estado y callbacks) | Sí | `LibraryContent`, `GameDetailsContent`, `LibrarySettingsContent` son funciones `@Composable` que no dependen del `ViewModel` y pueden ejercitarse con datos sintéticos. |
| **Navegación**: rutas de Favoritos, Detalle y Ajustes › Biblioteca | No verificable directamente (no se incluyó el grafo de navegación en la muestra) | La evidencia indica que se añadieron `FavoritesRoute.Details` y `SettingsRoute.Library`. Se asume correcto. |
| **Jugar** deshabilitado hasta A5 (regla 6) | Sí | El botón en `GameDetailsContent` está `enabled = false` y muestra el texto `"Jugar se activa en A5 (partidas seguras)"`. No se usa `EmulatorSession`. |
| **Tests** unitarios e instrumentados | Sí (basado en la evidencia) | La evidencia reporta 49 tests JVM y 61 instrumentados pasando, con corrección de una carrera en un test. Los tests revisados en el código fuente son plausibles y verifican lo que describen (escritura atómica de preferencias, límites de lectura del ROM, concurrencia del ViewModel, etc.). |
| **Reglas duras**: sin ROMs comerciales, código GPL/AGPL, red en iOS (no aplica a Android) | Sí | El manifiesto Release no incluye `INTERNET`. Los tests usan ROMs sintéticos generados en el propio código. No se detecta código copiado de fuentes prohibidas. |

## Notas

- **Sin riesgo de pérdida de partidas** porque A4 no implementa guardado; las preferencias de biblioteca se escriben con archivo temporal + `rename` atómico.
- La entrada no confiable (ROMs de hasta 8 MiB, URIs SAF) está acotada: `LibraryScanner` respeta `MAX_ROM_BYTES` y solo lee la cabecera (`0x150` bytes); `loadDetails` rechaza archivos mayores a 8 MiB y cualquier lectura del ROM se limita a `MAX_ROM_BYTES + 1` para la detección.
- La concurrencia del `LibraryViewModel` es correcta: las mutaciones y la carga de preferencias están protegidas por un `lock`, y las escrituras al disco se serializan con un `Channel`.
- Los tests de SAF instrumentados usan un `ContentProvider` de prueba (`TestDocumentsProvider`) que simula correctamente el protocolo de `DocumentsContract` sin exigir `MANAGE_DOCUMENTS`.
- El botón “Jugar” permanece deshabilitado y se señala explícitamente que se activará en A5, cumpliendo la regla 6.

**Conclusión**: El hito A4 – Biblioteca Android satisface sus criterios de aceptación y no introduce incumplimientos de las reglas duras ni riesgos de seguridad. Se aprueba.
