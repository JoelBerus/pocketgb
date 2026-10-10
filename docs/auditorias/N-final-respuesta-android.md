# Auditoría conjunta final del nivel N · respuesta Android

Rama `fin-android` desde `siguiente-nivel`. Informe: [N-final-opus](N-final-opus.md) (APROBAR CON CAMBIOS). Este documento cubre la parte Android (H2, H3, H4, H5, H6, H8, H9, H12, H13 y la paridad menor con iOS). La parte iOS va en `N-final-respuesta-ios.md` (rama `fin-ios`). No se tocó `ios/` ni los documentos compartidos, salvo [05-android-spec](../05-android-spec.md) y las guías `docs/guia/*-android.md`.

Regla 6 (perder una partida es el peor bug): cada cambio de la ruta de partidas tiene un test JVM con E/S real, y cada test se comprobó **con mutación**: se deshizo el arreglo, se vio fallar el test y se volvió a poner (tabla de abajo).

## Hallazgo → qué cambió → test

| Hallazgo | Qué cambió | Test (JVM) |
|---|---|---|
| **H2** (alta) `siblingRomsSharingBase` no contaba `.gba` | La lógica de hermanos pasa a `SafSiblings` (pura, en `saves/saf/SafSaveMirror.kt`) con `gb`, `gbc` y `gba`. `Juego.gb` + `Juego.gba` ponen el espejo en `MirrorMode.Shared` (ni se lee ni se escribe `Juego.sav`). | `NFinalSavesTest.aGbaWithTheSameBaseNameSharesTheMirror` |
| **H3** `recordReceivedHash` perdía `location` | `SaveStore.recordReceivedHash` conserva `h.location` (ND20 m). | `NFinalSavesTest.recordReceivedKeepsTheMirrorLocation` |
| **H4** paridad del linaje (decisión: regla de iOS, ND20 b′) | `SaveStore.ownMirrorHashes()` incluye las recibidas: un espejo igual a una recibida anterior es OWN_OLDER (gana la local) y un historial con solo recibidas decide por linaje, no por fecha (`SaveLineage.classifyMirror`, documentado). | `NFinalSavesTest.receivedCountsAsOwnHistory` (mismo caso que iOS: solo recibida → (1) espejo = recibida con fecha más nueva: gana la local; (2) local sin cambios y espejo desconocido más viejo: cambio externo) |
| **H5** «Abrir con» quitaba el sufijo antes del nombre exacto | `SaveLineage.gamesForRawSave`: primero el nombre exacto; si no hay ninguno, sin el sufijo de copia. `TravelHost` lo usa. `stripConflictSuffix` = iOS `originalStem` (`X 1` y `X (0)` ya no se recortan). | `NFinalSavesTest.rawSaveLooksForTheExactNameFirst` |
| **H6** «Conflicto» sin estado ni miniatura; estado entrante descartado | `SaveImporter`: en KEEP_LOCAL el momento «Conflicto» lleva el estado y la miniatura del paquete (acotada a `MAX_THUMBNAIL_BYTES`). Rechazar «sustituir el punto de Continuar» con la misma partida (`Choice.KEEP_LOCAL` sobre `Ask.REPLACE_STATE`, también sin batería) aparta la actual y guarda el estado entrante como momento «Conflicto» (si falla, en el anillo; si también falla, la importación falla sin tocar nada). En la UI, el botón «Mantener el mío» (y cerrar el diálogo) hace eso; texto de resultado propio. El AUTO instalado lleva ahora la miniatura del paquete, como iOS. | `NFinalTravelTest.keepLocalConflictMomentCarriesThePackageStateAndThumbnail`, `NFinalTravelTest.rejectingTheStateReplacementKeepsTheIncomingState` |
| **H8** el anillo expulsaba del índice antes de confirmar; fallo tragado | `MomentStore.pushBeforeLoadDeferred` añade la entrada nueva sin sacar las expulsadas del índice; `PendingPush.commit()` reescribe el índice sin ellas y después borra sus archivos. Sin commit, `recoverOrphans` no las toma por huérfanas y el siguiente push recorta el anillo: nunca pasa de `RING_SIZE + 1` entradas aunque se repitan los cierres forzados (el verificador del kill-test, `debug/SaveStress.kt`, pasa a aceptar esa entrada de más; la primera pasada del kill-test lo detectó: 3/50 «anillo con 4 entradas»). `PendingPush.rollback()` retira la entrada nueva solo cuando el núcleo rechazó el estado antes de tocar nada (`GameSession.loadMoment`, `StateError.Core`). `SaveImporter` ya no se traga un fallo del anillo: si falla, no instala. | `NFinalSavesTest.anUncommittedPushNeverDropsTheEvictedEntryFromTheIndex`, `NFinalSavesTest.rollbackRemovesOnlyTheNewEntry`, `NFinalTravelTest.aRingFailureAbortsTheInstall` |
| **H9** espejo `"wt"` no atómico | `SaveLineage.isTruncatedOwnWrite`: al abrir, si el espejo es prefijo estricto de la local y la local es `pending.first`, es OWN_OLDER (gana la local, se reescribe el espejo); nunca se instala el prefijo como cambio externo. | `NFinalSavesTest.aTruncatedOwnMirrorWriteIsOwnOlderNotAnExternalChange` |
| **H12** cadenas sin uso y condiciones siempre verdaderas | Fuera `storage_clear_footer` y `storage_clear_body`. En `GameSettingsHost` la huella solo existe si está confirmada (`fingerprint?.takeIf { hasConfirmedFingerprint }`), así desaparecen los `confirmed && fingerprint != null`. | lint (sin `UnusedResources` nuevos) y la suite instrumentada de ajustes del juego |
| **H13** tags medidos en UTF-16 | `PgbmMeta.parse`: cada etiqueta ≤ 64 code points (`codePointCount`), como el resto de textos. El exportador recorta en code points (`takeCodePoints`, sin partir pares sustitutos) etiquetas, nombre del equipo, título y alias. | `NFinalTravelTest.tagsAreMeasuredInCodePoints` |

## Paridad menor con iOS

| Punto | Qué cambió | Test (JVM) |
|---|---|---|
| Detalle: «Continuar donde lo dejaste en <equipo>» | `SaveStore.Origin` anota la huella del estado automático instalado del paquete; `SaveStatus.continueFrom` es el equipo mientras el AUTO de aquí sea ese mismo estado (si se juega aquí, deja de valer). El botón principal del detalle lo muestra cuando hay «Continuar». | `NFinalTravelTest.detailContinuesFromTheOtherDeviceWhileItsStateIsTheAuto` |
| Copias en conflicto: Dropbox en inglés y al escanear | `stripConflictSuffix` reconoce `X (Joel's conflicted copy …)`. El escáner anota en cada `RomEntry` las copias en conflicto de su `.sav` (`conflictCopies`, ND20 l para `X 2.gb`); Ajustes › Partidas las usa por huella (`scannedConflictsByFingerprint`, `SavesBrowser.list(scanned)`), así salen aunque el juego no se haya abierto desde que aparecieron. | `LibraryScannerTest.scanListsProviderConflictCopiesNextToEachRom`, `SavesBrowserTest.scannedConflictCopiesWinOverTheOnesRecordedAtOpening`, `NFinalSavesTest.rawSaveLooksForTheExactNameFirst` |
| ALREADY_CURRENT | La actual queda apartada, entra como recibida en el linaje y se anota el origen (si hay META), como iOS. | `NFinalTravelTest.alreadyCurrentSetsTheCurrentAsideAndRecordsItAsReceived` |
| ND20 (j) tamaños | `ImportTarget.sizing`: la cabecera del ROM manda (vacía = sin batería); el índice solo si no se puede leer. `TravelService.target` lo usa. | `NFinalTravelTest.headerSizesWinOverTheIndex` |

El carril ND17 (orden filtrar/tomar) que cita el informe no estaba en el encargo de Android.

## Mutación

MUTACION_PLACEHOLDER

## Verificación desde limpio

VERIFICACION_PLACEHOLDER

## Documentación
- [05-android-spec](../05-android-spec.md): resumen de estos cambios en «Partidas que viajan».
- [viajar-android](../guia/viajar-android.md): «Continuar donde lo dejaste en…» en el detalle, «Mantener el mío», búsqueda por nombre exacto en «Abrir con», cabecera siempre, copias en conflicto al escanear (con el formato inglés de Dropbox).
- [carpetas-android](../guia/carpetas-android.md): `Juego.gb` + `Juego.gba` también comparten `.sav`.
