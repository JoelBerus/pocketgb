# N7 iOS · Evidencia (partidas que viajan)

Rama `n7-ios-viajan` (desde `siguiente-nivel`, con `538e625` de N7 Android fusionado para traer los vectores cruzados). Tres commits en orden: N7a (linaje), N7b (exportar/importar), N7c (enviar, continuar, estado). Prueba de Joel pendiente: Rojo iPhone → Android → iPhone con continuación exacta.

## Criterios
| Criterio (N-README §N7) | Dónde | Resultado |
|---|---|---|
| Tabla de linaje completa en tests Swift, con latencia, copias en conflicto y reloj desfasado | `ios/PocketGBTests/SaveLineageTests.swift` (12 tests) y `SaveMirrorTests.swift` (2 adaptados) | ✔ |
| Ida y vuelta exportar → importar e interplataforma (vectores dorados generados en el test) | `PGBMTests.swift`: G1/G2 reproducidos con `pgbm_encode` desde Swift (tamaño + SHA-256), G3 leído, G4 = `PGBM_ERR_CRITICAL`; X1…X7 reproducidos byte a byte y pasados al importador real; `exportThenImportRoundTrip` | ✔ |
| Importar respalda lo actual y respeta la exclusión por huella | `x2AdvancesFromS1…` (backup `.1`, «Antes de importar»), `importRespectsFingerprintOwnership` | ✔ |
| `SAVE` vacía o de otro tamaño no toca el `.sav` y deja backup | `x3AndX4NeverTouchTheSaveAndLeaveABackup` (contenido y fecha intactos, backup `.1`) | ✔ |
| `META` inválida o `PGBM_ERR_CRITICAL` se rechaza sin tocar nada | `x5X6X7AndG4AreRejectedWithoutTouchingAnything` (instantánea de todo el disco del juego antes/después), `metaSchemaIsStrict`, `otherGameAndHostileBytesAreRejected` (CRC, truncados) | ✔ |
| iOS sin APIs de red | Release `generic/platform=iOS`: `nm -u` sin `URLSession`/`nw_connection`/CFNetwork; Info.plist sin ATS | ✔ |
| Sin binarios en el repo | todos los `.pgbm` se generan en memoria en los tests | ✔ |

## Comandos y salida
```
xcodebuild test … -only-testing:PocketGBTests  (iPhone 17 Pro, iOS 26.5)
✔ Test run with 339 tests in 30 suites passed after 14.529 seconds.
xcodebuild test … -only-testing:PocketGBTests  (iPhone SE 3.ª gen., iOS 26.5)
✔ Test run with 339 tests in 30 suites passed after 13.659 seconds.
tools/ios-screenshots.sh  (iPhone 17 Pro)
xcodebuild test: exit 0 — 339 tests + catálogo completo, 7 capturas N7 nuevas
xcodebuild -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
** BUILD SUCCEEDED **
```

## Vectores cruzados (iOS reproduce exactamente los de `core/tests/unit_pgbm_cross.c`)
| Vector | Bytes | SHA-256 | Resultado del importador iOS |
|---|---|---|---|
| X1 | 37480 | `b7d163cf…bf796` | sin local: `installed(continuation: true)`, origen «Pixel de prueba»/android; con local = S1: `alreadyCurrent(continuation: true)` |
| X2 | 37410 | `e79d7993…5f2f` | local S1: `installed(continuation: false)` (avance, backup S1, sin STAT); local B: `needsChoice` sin tocar nada → `keptLocal` con momento «Conflicto» |
| X3 | 390 | `c1f03389…0e17` | `saveNotTouched`, backup `.1` |
| X4 | 8387 | `33640162…3771` | `saveNotTouched`, backup `.1` |
| X5 | 33158 | `8b1fb567…718a` | rechazo `meta(.newerFormat)` («Actualiza PocketGB») |
| X6 | 32832 | `c7f56509…2a6e` | rechazo `missingMeta` |
| X7 | 33158 | `6d8b3af3…9880` | rechazo `saveMismatch` |
| G4 | 93 | `b6b3f3e9…4a53` | rechazo `container(.critical)` |

Sin discrepancias con la especificación.

## Decisiones
- **Espejo = escritura nuestra anterior → gana la local** (tabla §3.4), aunque su fecha sea más nueva. Cambia la decisión de la auditoría D2-D5 H1 (antes ganaba por fecha); el contenido del espejo queda en una copia apartada que no rota (regla 6). Test `restoredHistoricalMirrorWithNewDateLosesToLocalAndIsKept`.
- **Divergencia al abrir:** `SaveOpening` lanza `Refusal.divergence` sin escribir nada; la app pregunta («Seguir con la de este iPhone» / «Usar la de junto al juego») y reabre con la elección. Con el **cable link** no se pregunta: sigue la local y la otra queda como «Conflicto».
- **Linaje de paquetes:** `sav == local` → nada; `base == local` → avance sin pregunta; `sav` ya conocido (historial del espejo + backups + apartadas) → «más antiguo», se pregunta; resto (incluida una base que aún no ha llegado, latencia) → divergencia, se pregunta. Las fechas solo se usan sin historial.
- **`.sav` crudo** siempre se confirma (si hay partida distinta) y exige el tamaño exacto del cartucho.
- **Continuación:** solo si `state_of_sav_sha256 == sav_sha256`; el estado se escribe como automático después del `.sav`. `gb_state_load` lo valida al continuar. Guardar un estado automático propio termina la «continuación de otro equipo».
- **`base_sav_sha256`** al exportar = la última partida recibida de otro equipo (`<huella>.origin.json`), o `null`.
- META: no se detectan claves repetidas (limitación de `JSONSerialization`); el resto del esquema se valida a mano.
- **Copias en conflicto** solo se listan (Ajustes › Partidas); se excluye `Juego 2.sav` si existe `Juego 2.gb`.

## Capturas (catálogo, al final de `screens.txt`)
`n7-detail-save` (claro y oscuro: «Continuar donde lo dejaste en Pixel 8», «Partida: Pixel 8 · hace 2 horas», botones), `n7-detail-save-ax5` (AX5: los botones parten línea, sin recortes), `n7-divergence`, `n7-import-prompt`, `n7-import-done`, `n7-settings-conflicts`.

Observación ajena a N7: en AX5 los botones «Momentos»/«Hitos» de la sección de progreso (N6) parten las palabras por sílabas.
