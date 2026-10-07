# Auditoría N1 iOS, segunda vuelta (Opus, solo lectura)

Fecha: 2026-10-07. Rama `n1-ios-identidad` en `eda598d` (diff `b216c2a..HEAD`; entre `963ba44` y `eda598d` solo cambian documentos). Ejecución en `/private/tmp/claude-501/audit-n1-ios-v2` con 12 sondas (P1–P12) que solo existieron en esa copia.

## Veredicto: APROBAR
Los doce hallazgos de la primera vuelta están resueltos. En la ruta de guardado (H1, regla 6) no hay ningún camino nuevo que sobrescriba la partida local sin copia (tests de la respuesta y sondas P1–P8). Tres hallazgos nuevos de severidad baja, no bloqueantes.

## Hallazgos nuevos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| V2-H1 | baja (improbable) | `Library/LibraryPreferences.swift:410-417` | La recuperación de `preferences.json.tmp` (si falta el principal) no pasa por las comprobaciones de formato de H2/H4: un `.tmp` de versión más nueva se instala sin bloquear escrituras y la siguiente escritura borra campos desconocidos; uno indecodificable se descarta y se pisa. Requiere que una versión futura se corte en su primera escritura y luego se instale una anterior. | P10: `.tmp` `{"version":3,"games":{},"futuro":{"x":1}}` → `issue=nil` y archivo reescrito sin `futuro`. | Aplicar `fileFormat` al temporal (más nuevo o ilegible → bloquear y no tocarlo) o documentarlo. |
| V2-H2 | baja | `Saves/SaveStore.swift:95,182-193`, `Saves/SavesSettingsView.swift` | Las «Copias apartadas» crecen sin tope ni borrado; con duplicados y > 8 guardados (historial `prefix(8)`) cada alternancia aparta una partida vieja no única. Nada se pierde. `keepMirrorLoser` lee todas las copias en cada apertura con espejo distinto. | P8: 6 aperturas alternadas → 5 copias, 0 perdidas. | En N7a: «Borrar» con confirmación o tope por antigüedad que conserve la más reciente; mientras, explicarlo en la guía. |
| V2-H3 | baja | Tests | H5 sin test (declarado); de H7 solo se prueba la pausa; H8 no es reproducible en simulador; `legacyMigrationIsBlockedWhenTheBackupFails` no distingue «bloqueado» de «la escritura falló» (carpeta en solo lectura). El comportamiento es correcto (P9). | P9. | Hacer fallar solo la copia en el test de H10 o comprobar que `issue` sigue en `.migrationBackupFailed`; opcional test de `refreshContinuations(adding:)`. |

## Verificación de H1–H12
H1 resuelto (escritura temporal + fsync + `rename` + fsync de carpeta; nombre con UUID; se aparta antes de `addBackup`/cuarentena/`save`; si apartar falla, `prepare` lanza sin tocar nada; fuera de la rotación y del índice; `restore(kept:)` respalda la actual en `.1`; casos: duplicado más nuevo, `.sav` ajeno, iCloud sin descargar, tamaño incorrecto, escritura propia). H2, H3 (ctime con tolerancia de 1 µs), H4, H6, H9, H10, H11, H12 resueltos con test real. H5 resuelto sin test (declarado). H7 resuelto (solo la pausa con test). H8 resuelto sin test posible.

## Criterios
`xcodebuild test` iPhone 17 Pro: 212 tests en 20 suites, TEST SUCCEEDED. Catálogo: 113 PNG sin fallos (`settings-save-backups` revisada). Release `generic/platform=iOS`: BUILD SUCCEEDED sin avisos. Sondas 12/12. Reglas duras: sí. Documentación coherente con el código.

## Notas
No verificado: iCloud real (si el proveedor cambia el ctime en cada sincronización, la caché se recalcularía), desalojo real (H8), pausa con juego abierto en el iPhone. Menor: un `…mirror-….sav.tmp` huérfano si el proceso muere entre escribir y renombrar (sin pérdida).
