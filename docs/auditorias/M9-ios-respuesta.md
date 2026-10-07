# M9 · Respuesta a la auditoría final de los lotes 3–5 ([M9-ios-opus](M9-ios-opus.md): APROBAR CON CAMBIOS)

Sin hallazgos bloqueantes, altos ni medios. Las salidas están en [M9-ios-evidencia](M9-ios-evidencia.md) §8.

## Estado de M9L2-H1..H3 (auditoría del lote 2)
| ID | Estado | Comprobación del auditor |
|---|---|---|
| M9L2-H1 (media) | corregido (lote 2) | Test `retryAfterAFailedAsyncWriteRewritesWithoutASyncFlush`, con mutación en rojo. |
| M9L2-H2 (baja) | corregido (lote 2) | `shutdownRunsAfterTheFinalFlush`. |
| M9L2-H3 (baja) | corregido en producción (lote 2) y el test, en `0b9f570` (ver M9-H1) | Guardas en `start(restoring:)` y `loadState`. |

## Hallazgos de la auditoría final
| ID | Estado | Qué se hizo |
|---|---|---|
| M9-H1 (baja) | corregido (`0b9f570`) | `PairCore` admite `savesEverything: true` (`stateSave` → `Data([0])`, `stateLoad` sin efecto, `sramSave` → la SRAM de `a`). El test `statesAreRefusedByTheSessionGuardEvenIfTheCoreCouldSaveThem` exige `.linkUnsupported` en `start(restoring:)` y en `loadState` y que los dos `.sav` no cambien. **Mutación:** con las dos guardas sustituidas por `_ = 0` sale en rojo (3 expectativas); restauradas, en verde. |
| M9-H2 (baja) | corregido (`46d0f64`) | `LinkSession.indexTitles` (título de cabecera o `fileName` si está vacío, como el juego suelto); `startLink` lo pasa a `SavesIndex.record`. `titles` queda solo para la interfaz. Test `indexTitlesIgnoreTheAliasAndFallBackToTheFileName`. |
| M9-H3 (baja, doc) | corregido (`b19d0cb`) | «Alternativa descartada» de `M9-link-virtual.md` deja solo la pantalla dividida. |
| Observación (`.sameGame` antes de `open`) | atendida (`46d0f64`) | Test `sameGameIsRefusedBeforeOpeningEvenWithANewerMirror`: `.sav` local y espejo más nuevo; el rechazo no cambia ni un byte ni el listado. |

Los 🍎 de Joel (prueba Rojo ↔ Amarillo y el resto de [M9-ios-evidencia](M9-ios-evidencia.md) §7) siguen abiertos.
