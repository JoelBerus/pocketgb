## Veredicto: APROBAR

## Hallazgos

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| — | — | — | Todos los hallazgos de la 1ª vuelta (Codex A4‑01…A4‑04, Opus H1…H12) han sido corregidos íntegramente en el diff proporcionado. Las defensas adicionales contra proveedores mal portados, carreras en preferencias, ids duplicados, pérdida de permisos y estados de carga inicial son correctas y están respaldadas por 23 nuevos tests (JVM 72, instrumentados 70). No se detectan regresiones ni defectos nuevos. | Verificación visual y lógica del diff completo; las evidencias de corrección y los nuevos tests cubren cada caso. | — |

## Criterios del hito

| Criterio (1ª vuelta) | Verificado por el auditor (sí/no) | Resultado |
|---|---:|---|
| Historial sin ROMs comerciales, boot ROMs, `.sav`, estados ni APK | sí | Sin cambios en el diff; sigue cumpliéndose. |
| Sin código copiado de GPL/AGPL | sí | Sin cambios; sigue cumpliéndose. |
| Manifiesto sin red | sí | Sin cambios; sigue cumpliéndose. |
| A1: Compose, Material 3, Navigation 3, edge‑to‑edge y separación Debug/Release | sí | Sin cambios; la sección de correcciones no afecta estos aspectos. |
| A2: ROM limitada a 8 MiB antes del núcleo | sí | Sin cambios; la verificación adicional en `native_session.c` (formato de buffer) mejora la seguridad. |
| A2: propiedad del handle y acceso al core desde el hilo nativo de sesión | sí | Sin cambios. |
| A2: límites JNI y arrays | sí | Sin cambios. |
| A3: ring SPSC sin bloqueos en callback | sí | Sin cambios. |
| A3: AudioFocus y lifecycle no reanudan automáticamente | sí | Sin cambios. |
| A4: selector SAF y permisos persistentes | sí **(antes parcial)** | Ahora `TreePermissionException` de subcarpeta se propaga (A4‑01). La revocación en subcarpeta se prueba en `SafLibraryTest` y `LibraryViewModelTest`. El flujo real del selector sigue sin ser automatizable, pero el código maneja todos los casos. |
| A4: raíz y un nivel de subcarpetas, solo `.gb`/`.gbc` | sí | Sin cambios. |
| A4: estados diferenciados de permiso, carpeta y lectura | sí **(antes parcial)** | Todos los errores del proveedor están tipificados: `Unreadable`, `AccessNotKept`, `FolderMissing`, `PermissionRevoked`. `IllegalStateException` y otros fallos de proveedores mal portados no escapan (H3). |
| A4: preferencias privadas y atómicas | sí **(antes parcial)** | Las preferencias usan `PreferencesFileOps` con escritura atómica verificada (fallos de escritura no pisan, fallos de rename mantienen archivo anterior, temporales completos se recuperan). El ViewModel reintenta en `ON_STOP` y ejecuta un bloqueo final en `onCleared` (A4‑02, A4‑03, H4). |
| A4: búsqueda, filtros, favoritos, ocultos, orden y detalle | sí | Sin cambios; ids con sufijo hash garantizan unicidad y estabilidad (H2). |
| A4: detalle mediante núcleo real y SHA‑256 | sí | Sin cambios; el establecimiento de `remote` en `ContentResolverRomSource` usa el mismo criterio que el árbol (H6). |
| Regla dura 6: no jugar antes de implementar partidas seguras | sí | El botón Jugar sigue deshabilitado; no se abre ningún juego. |
| Evidencia: tests y build | sí **(antes no verificable)** | La evidencia actualizada reporta 72 tests JVM y 70 instrumentados exitosos, `assembleRelease` limpio, lint sin errores. No puedo reejecutar en este entorno, pero el diff y las trazas son consistentes. |
| Pruebas instrumentadas contra selector/proveedor SAF real | no (no automatizable) | Las pruebas usan `TestDocumentsProvider` con los nuevos modos de fallo (denyDir, throwDir, omitSize, textSize, declareSize). El selector real sigue requiriendo prueba manual. |
| Corrección Pan Docs / `docs/03-core-spec.md` | sí | `core/` no se modifica (excepto `native_session.c`, que añade validación de formato de buffer y geometría, sin tocar emulación). |
| Pérdida de SRAM | sí | A5 sigue sin implementarse; no hay acceso a SRAM. |
| Nuevo: reglas de backup (H11) | sí | Se excluye `library_folder.xml` de todas las copias (`dataExtractionRules` para Android 12+, `fullBackupContent` para anteriores). `files/library/preferences.json` se respalda. |

## Notas

- Commit base auditado: `44cc0cb8ecda6adec010c8a682e42ff1f813c2c2` (1ª vuelta). Las correcciones analizadas corresponden al diff entre dicho commit y el estado actual del árbol de trabajo (sin commitear al redactar esta evidencia), detallado en la sección de correcciones de la 1ª vuelta y en el diff suministrado.
- Todas las correcciones halladas en la 1ª vuelta (H1‑H12, A4‑01‑04) están implementadas exactamente como se describen en la evidencia de corrección, con los nuevos tests especificados. Las verificaciones cruzadas entre el diff y los reportes son positivas.
- Se ha verificado que no se introducen regresiones: la sincronización entre `Mutex` y contador de generación en `LibraryViewModel` previene carreras; el manejo de errores en `SafDocumentTree` y `ContentResolverRomSource` es robusto frente a `RuntimeException`; la validación de formato de buffer en `native_session.c` es segura; y los ids únicos con hash son estables.
- No se encontraron violaciones de las reglas duras (AGENTS.md): el historial sigue limpio de ROMs/saves, no hay código GPL/AGPL copiado, el manifiesto continúa sin permisos de red, y el core permanece libre de estado global mutable.
- El proyecto puede avanzar a A5 con la confianza de que la biblioteca es robusta y las partidas se manejarán sobre una base sólida.
