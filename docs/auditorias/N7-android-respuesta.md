# N7 Android · Respuesta a la auditoría Opus

Informe: [N7-android-opus](N7-android-opus.md) (APROBAR CON CAMBIOS). Las correcciones siguen **ND20** ([N-README](../hitos/N-README.md) §2, comportamiento común iOS/Android), que se trajo con `git merge siguiente-nivel`. Evidencia anterior: [N7-android-evidencia](N7-android-evidencia.md).

## Commits
| Commit | Qué |
|---|---|
| `2a436d2` | Vector cruzado **X8** (clave de META repetida → rechazo) en `core/tests/unit_pgbm_cross.c` y docs/12. X1–X7 sin cambios |
| `45bb367` | Linaje al abrir: ND20 a, b, c, m; H1–H4, H12 |
| `2734492` | Importador, META, config y metadatos: ND20 d–l; H5–H11 |
| guía | `docs/guia/viajar-android.md` al día |

## Hallazgos
| # | Respuesta | Dónde | Test |
|---|---|---|---|
| H1 (alta) | **Corregido.** `lastOwnMirrorHashes` = última escritura intentada, última confirmada **y última recibida** (ND20 b): si la local coincide con cualquiera, «no cambió». | `SaveStore` | `SaveLineageTest.receivedSaveCountsAsUnchangedSoTheNextExternalChangeIsNotADivergence` (iPhone→Android, abrir y cerrar sin jugar, el iPhone vuelve a escribir → cambio externo, el espejo nunca se reescribe con la vieja) |
| H2 (alta) | **Corregido.** `OWN_OLDER`: gana la local, pero el espejo se aparta antes (`setAsideMirrorLoser`, no rota) y se avisa sin bloquear (`MirrorOlderSetAside`). | `SaveOpening` | `ownOlderMirrorIsSetAsideAndWarnedWithoutBlocking`, `restoredHistoricalMirrorWithNewDateLosesToLocalAndIsRewritten` y `anOwnedOlderMirrorIsSetAsideOnceAndAnEqualOneNever` (ajustados) |
| H3 (alta) | **Corregido.** Divergencia al abrir → `Refusal.Divergence` **sin escribir nada** → `OpenError.SaveDivergence` → diálogo «Seguir con la de este teléfono / Usar la del otro equipo / Cancelar» → se vuelve a abrir con la elección. La no elegida va a momento «Conflicto» (con estado y miniatura si es la local, ND20 k), backup y apartada. Android no tiene cable link. | `SaveOpening`, `GameLauncher`, `GameplayViewModel`, `GameDialogs` | `divergenceAsksWithoutWritingAnythingUntilChosen` (árbol de archivos y escrituras del espejo idénticos), `divergenceKeepLocal…`, `divergenceUseMirror…`, `SaveOpeningTest.unknownMirrorWhenLocalAlsoChanged…` |
| H4 (media) | **Corregido.** El historial guarda la ubicación del espejo (`location` = carpeta + nombre; `SaveMirror.location`); un espejo en otra ubicación no hereda el linaje y usa la regla de N1 (fecha + apartado). | `SaveStore`, `MirrorChannel`, `SaveTarget`, `SafSaveMirror` | `aMirrorAtAnotherLocationUsesTheN1DateRule`; `MoveRomEndToEndTest` (instrumentado) |
| H5 (media) | **Corregido.** Huellas conocidas = historial + recibidas + copias de seguridad + apartadas (ND20 d); `STALE` pregunta («Ya tuviste esta partida»). | `SaveStore.knownHashes`, `SaveImporter` | `aKnownSaveAsksAndKeepingLocalLeavesItInTheCopies` |
| H6 (baja) | **Corregido.** La bandeja solo da por visto tras `Done` o «Ahora no». | `PocketGBApp`, `TravelHost` | revisión de código |
| H7 (baja) | **Corregido.** `inspectLocal`: ilegible → `LOCAL_UNREADABLE` sin tocar nada; demasiado grande → cuarentena antes de instalar. | `SaveImporter` | `anUnreadableLocalIsNeverTreatedAsAbsent`, `anOversizeLocalIsQuarantinedBeforeInstalling` |
| H8 (baja) | **Corregido.** Clave repetida en cualquier objeto (comparando claves ya decodificadas) → META inválida; vector X8. | `PgbmMeta.hasDuplicateKeys` | `x8DuplicateKeyIsRejectedWithoutTouchingAnything`, `duplicateKeysAreDetectedAfterDecodingAndAtAnyDepth`, `PgbmNativeTest` |
| H9 (baja) | **Corregido.** `config` tipada (`PgbmConfig`): tipo o valor fuera del esquema → inválida; se exporta con los valores de iOS (`model` `dmg`/`cgb`, paleta como cadena, nombres GBA de `gba/include`); estado de otra configuración → aviso antes de descartarlo. Longitudes en code points de Unicode (ND20 h fija code points; iOS se iguala). | `PgbmConfig`, `PgbmMeta`, `TravelService.config` | `configIsTypedAndExported`, `aStateFromAnotherConfigurationIsAnnouncedBeforeBeingDiscarded` |
| H10 (baja) | **Corregido** (= iOS): `X n.sav` con n ≥ 2, `X (n).sav` con n ≥ 1, excluye bases de otros ROMs de la carpeta, reconoce «copia en conflicto»; `stripConflictSuffix` para «Abrir con». | `SaveLineage`, `SafSaveMirror` | `providerConflictCopiesAreRecognised` |
| H11 (baja) | **Corregido.** `peek` en `Dispatchers.IO` (`produceState`). | `TravelHost` | revisión de código |
| H12 (baja) | **Corregido.** `ExternalChange`, `MirrorOlderSetAside` y `Divergence` llevan `readOnly` y el texto añade el aviso de solo lectura. | `SaveResolution`, `GameDialogs` | `ownOlderMirrorIsSetAsideAndWarnedWithoutBlocking` |

## ND20, resto
- **(e)** `.sav` crudo: siempre se confirma nombrando el juego (`RAW_CONFIRM`); «Abrir con» busca el juego por nombre sin el sufijo de copia; si coinciden varios, se elige. Test `rawSaveIsValidatedByExactSizeAndAlwaysConfirmed`.
- **(f)** Antes de instalar: la actual con su AUTO va a «Antes de importar» (anillo de 3; las expulsadas solo se borran tras confirmar la instalación) y a copia apartada; el AUTO propio nunca se pisa sin copia, y si la partida no cambia (o el juego no tiene batería) se confirma (`REPLACE_STATE`). Tests `installingPutsTheCurrentSaveWithItsAutoInTheBeforeImportRingAndSetsItAside`, `theSameSaveWithAStateAsksBeforeReplacingTheAuto`.
- **(g)** Sin cambios (ya era así).
- **(i)** Fusión de metadatos (`MetadataMerge`): etiquetas = unión; alias = el local si existe; tiempo = máximo; hitos = unión, marcado gana. «Colección» y «nota» son de momentos y Android no exporta momentos sueltos en N7. Test `metadataIsMergedNotOverwritten`.
- **(j)** Juego nunca abierto: tamaños desde la cabecera del ROM (núcleo suelto, sin sesión).
- **(k)** Momento «Conflicto» con estado y miniatura (si es la local); SAVE entrante de otro tamaño, no vacía, apartada. Test `aWrongSizedIncomingSaveIsSetAside`.

## Verificación desde limpio
Ver el final de este archivo (se completa con la corrida de `git archive`).

### Resultado (árbol `git archive` de `2734492` + guía, antes del último ajuste de texto del diálogo; con las ROMs libres de GB y GBA copiadas)
```
make -C core test   → unit tests PASS: 11349 comprobaciones, 0 fallos (suites pgbm y pgbmx con X8) · requeridos 65/65
make -C core asan   → unit tests PASS: 11349 comprobaciones, 0 fallos · requeridos 65/65
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest → BUILD SUCCESSFUL
JVM: 806 tests, 0 fallos (790 antes)
lint: 23 avisos, 0 errores, ninguno en código de N7
aapt2 dump permissions (Debug y Release): 0 permisos de red
Emulador Small_Phone_API_35 (with-lock.sh emu): am instrument → OK (485 tests)
tools/android-save-kill-test.sh 50 gb → OK: 50/50 iteraciones con el invariante intacto
tools/android-screenshots.sh (n7-*) → 18 capturas
```
Fuzz: sin cambios en `core/src` desde la evidencia anterior (solo el test `pgbmx`); vale la corrida de 600 s de `fuzz-pgbm-smoke` allí documentada (libFuzzer no disponible en este Mac).

### Capturas revisadas
Nuevas: `n7-divergence-ask` (claro y oscuro), `n7-warning-mirror-older`, `n7-known`, `n7-raw-confirm`, `n7-config-mismatch`; regeneradas `n7-warning-external` (ahora con el aviso de solo lectura combinado, H12) y las demás `n7-*`. La primera captura de `n7-divergence-ask` cortaba la última línea del texto: se acortó el texto y el cuerpo del diálogo se desplaza (último commit); recapturada y correcta.

## Decisiones para igualar en iOS
- Divergencia al abrir: tres botones (Seguir con la de este teléfono / Usar la del otro equipo / Cancelar); «Cancelar» no abre el juego.
- `REPLACE_STATE`: si la partida no cambia (misma huella o juego sin batería) y el paquete trae estado, se confirma antes de sustituir el AUTO; con instalación de partida no hace falta (el AUTO va con ella a «Antes de importar»).
- `CONFIG_MISMATCH` compara `model`, `gba_save_type`, `gba_rtc` y `gba_bios` cuando están en los dos lados; la paleta no cuenta.
- Al importar se aparta SIEMPRE la partida actual (además del anillo y de `.1`).
- `KNOWN`: la que no se elige va a backup y apartada (sin momento «Conflicto»; solo la divergencia crea momento).
