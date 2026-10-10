# N7 iOS · Respuesta a la auditoría Opus y aplicación de ND20

Informe en [N7-ios-opus.md](N7-ios-opus.md). La decisión común ND20 de iOS y Android está en [N-README](../hitos/N-README.md).

## Hallazgos de la auditoría
| Id | Respuesta | Test |
|---|---|---|
| H1 (alta) | Importar nunca pisa el AUTO propio sin copia: va a «Antes de importar» con la partida actual, también sin partida local. Con la misma partida (`.same`) o en un juego sin batería y un AUTO distinto, se pregunta antes (`needsStateChoice`); si se rechaza, el estado del paquete queda como «Conflicto». | `sameSaveWithDifferentOwnAutoAsksAndKeepsIt`, `batterylessGameKeepsOwnAutoBeforeReplacing`, `noLocalSaveStillKeepsOwnAuto`. Mutaciones: M1 (no guardar el AUTO) y M2 (no preguntar) **las detectan los tests**. M3 (quitar la copia en `keepCurrent` sin partida) sobrevive porque es redundante con la de `installContinuation`: es una defensa en profundidad |
| H2 | `PendingDivergence` va ligada al `entry.id`, se consume siempre y se descarta si la apertura falla o ni empieza | `pendingDivergenceOnlyAppliesToTheSameGame` |
| H3 | La pregunta nombra el juego. Un `.sav` crudo se confirma también con `.noLocal`. Si «Abrir con» encaja con varios juegos, se elige | `rawSaveIsConfirmedEvenWithoutLocalAndNamesTheGame` |
| H4 | ND20 (h): recorrido de tokens (`StrictJSON`) que rechaza claves repetidas (comparadas ya decodificadas) y números con fracción o exponente; hex solo ASCII; longitudes en code points; `config` tipada | `metaIsStrictPerND20h`, `x8DuplicateKeyIsRejectedWithoutTouchingAnything` |
| H5 | ND20 (g): `base_sav_sha256` = última partida recibida de fuera (`received` en el historial: paquete, cambio externo o primera instalación desde el espejo) | `exportCarriesConfigAndLastReceivedBase`, `exportThenImportRoundTrip`, `firstInstallFromMirrorIsReceived` |
| H6 | ND20 (h, i): se exporta `config`. Al importar con continuación y otra configuración, se avisa de qué cambiar; el estado no se borra. Los metadatos se fusionan | `exportCarriesConfigAndLastReceivedBase`, `metadataIsMergedPerND20i` |
| H7 | Sin linaje si algún tamaño no vale: ni «Conflicto» ni avisos de linaje | `wrongSizesNeverProduceConflictMomentsOrLineageWarnings` |
| H8 | «Abrir con» prueba primero el nombre exacto y después el nombre original de la copia en conflicto (quita « 2», « (1)», `.sync-conflict-…` y «conflicted copy») | `originalStemOfConflictCopies` |
| N1 | Importar recorta el anillo a 3 al final, conservando las entradas de esta importación | `importTrimsTheRingKeepingTheNewEntry` |
| Nota | Un espejo `ownEarlier` (no estrictamente propio) da un aviso no bloqueante (toast, `.mirrorOlderKept`) y queda apartado. El test se renombró a `divergentExternalMirrorAsksThenUsesTheChoice` | `ownEarlierMirrorLosesEvenWithNewerDate`, `restoredHistorical…` |

## ND20 en iOS
| | Estado |
|---|---|
| (a) | Ya estaba: divergencia → `Refusal.divergence` sin escribir; el cable link sigue la local |
| (b) | `History.received`; `isUnchanged` = última propia **o** última recibida. Test del flujo iPhone→Android→iPhone sin jugar→Android: `receivedSaveIsUnchangedLocalAfterOpenCloseWithoutPlaying` (mutación M4, quitar la recibida: el test falla) |
| (c) | Gana la local, el espejo va a una copia apartada que no rota y se avisa con un toast |
| (d) | Huellas conocidas = escritas + pendientes + recibidas + backups + apartadas |
| (e) | H3 |
| (f) | H1 y N1 |
| (g) | H5 |
| (h) | H4 y H6; X8 reproducido (33185 B, `08c1ad1b…c95b`) → rechazo sin tocar nada |
| (i) | `MetadataMerge`: etiquetas = unión, alias local si existe, tiempo = máximo, hitos = unión (el marcado gana). iOS no exporta momentos sueltos, así que no hay colección ni nota que fusionar |
| (j) | Ya estaba: tamaños desde el ROM cargado en el núcleo |
| (k) | El «Conflicto» lleva el AUTO y la miniatura cuando pierde la local, tanto al abrir como al importar; una `SAVE` no vacía de tamaño incorrecto se aparta |
| (l) | Detector con n ≥ 2, excluye nombres de otro juego y reconoce las variantes; «Abrir con» quita el sufijo (H8) |
| (m) | El historial guarda la ubicación del espejo; otro espejo usa la regla de N1. Test: `historyOfAnotherMirrorLocationIsNotUsed` (mutación M5: el test falla) |

## Verificación
```
PocketGBTests iPhone 17 Pro: ✔ Test run with 355 tests in 30 suites passed after 13.949 seconds.
PocketGBTests iPhone SE:     ✔ Test run with 355 tests in 30 suites passed after 13.325 seconds.
Release generic/platform=iOS: ** BUILD SUCCEEDED **; nm -u sin URLSession/nw_connection/CFNetwork (0)
ScreenshotTests (filtro n7-, settings-saves, game-details): ** TEST SUCCEEDED **
```

## Igualación con N7 Android (tras integrar `siguiente-nivel`)
Merge sin conflictos. El texto de Android en docs/12 («Valores de `config`») es el que vale.

| Decisión | En iOS | Test |
|---|---|---|
| (1) Divergencia al abrir con tres botones | «Seguir con la de este iPhone», «Usar la del otro equipo» y «Cancelar». Cancelar no abre el juego (no escribe nada y la elección no queda pendiente) | captura `n7-divergence` |
| (2) Confirmar antes de sustituir el AUTO solo si la partida no cambia | Ya era así: `needsStateChoice` solo con la misma partida o sin batería | `sameSaveWithDifferentOwnAutoAsksAndKeepsIt`, `batterylessGameKeepsOwnAutoBeforeReplacing` |
| (3) Conflicto de configuración | Solo cuentan `model`, `gba_save_type`, `gba_rtc` y `gba_bios`; la paleta no. `model` = `"cgb"` para un ROM CGB (bit 7 de 0x143) o un GB con color activado, `"dmg"` si no. Una clave de `config` con `null` es inválida, como en Android | `configConflictIgnoresPaletteAndUsesCGBFlag`, `exportCarriesConfigAndLastReceivedBase` |
| (4) Al importar siempre se aparta la partida actual | También con la misma partida, con `SAVE` vacía o de otro tamaño, y al elegir «seguir con la mía» | `importAlwaysKeepsCurrentAside` |
| (5) Partida ya conocida: la no elegida va a copias y se aparta, sin «Conflicto» | `relation == .older` no crea momento | `knownOlderPackageGoesToCopiesWithoutConflictMoment` |

Diferencia que queda: con otra configuración, iOS no descarta el estado del paquete. Lo instala y avisa de qué ajuste cambiar; si no carga al continuar, la partida sigue intacta y se puede jugar desde el inicio.

```
PocketGBTests iPhone 17 Pro: ✔ Test run with 358 tests in 30 suites passed after 13.868 seconds.
PocketGBTests iPhone SE:     ✔ Test run with 358 tests in 30 suites passed after 13.768 seconds.
Release generic/platform=iOS: ** BUILD SUCCEEDED **; nm -u sin APIs de red (0)
```
(El primer intento en el 17 Pro terminó con «TEST FAILED» sin ningún test fallido, un fallo del arranque del simulador; al repetirlo, todo en verde.)

`tools/ios-screenshots.sh` completo (iPhone 17 Pro): 358 tests unitarios en verde y `ScreenshotTests testScreenCatalog` en verde (245 capturas). El script terminó con exit 65 por 6 tests de interfaz de `AdaptiveLibraryUITests` y `CategoriesUITests`: el runner se reinició («Restarting after unexpected exit, crash, or test timeout») con la máquina cargada. Al repetir esas dos suites aparte, pasan las 11. No tienen que ver con N7.

La divergencia al abrir pasó de hoja de acciones a alerta: en iOS 26 la hoja oculta «Cancelar» (se cancela tocando fuera) y Android lo muestra como botón. Captura `n7-divergence` regenerada: tres botones.
