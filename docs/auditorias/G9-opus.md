# Auditoría Opus de G9 (final del bloque GBA) — `g8-gba-controls` @ `b02f914`

## Veredicto: APROBAR CON CAMBIOS

Las correcciones de G8 son correctas y respetan la regla dura 6; el núcleo v2 rechaza limpiamente estados v1 y de otra configuración. Los cambios pedidos son solo de documentación (dos afirmaciones no coinciden con el código) y de precisión en la evidencia.

## Hallazgos

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| G9-H1 | Media (doc, regla dura 6) | docs/10-gba-spec.md:81 | Dice que «un `.sav` solo con el bloque de reloj (16 bytes) es válido y deja el medio en blanco». Eso solo es cierto con un medio de 0 B («Sin partida» + reloj). Con un cartucho que tiene medio, un `.sav` de 16 B se rechaza (`.localWrongSize`) y no se escribe nada. Leído así, invita a pensar que un `.sav` de 16 B sirve para cualquier juego con RTC. | `GBACoreBridge.validSaveSizes`: `media.isEmpty ? [rtcBytes] : media ∪ media+16`. En `sramLoad`, con medio > 0, `isMediaSize(0)` es falso y se lanza `.sramSize`. El test `GBATests` (≈l.269-276) solo cubre `sramBytes == 0`. | Reescribir: «Si el cartucho no tiene medio (tipo forzado «Sin partida») pero sí reloj, el `.sav` son solo los 16 bytes del RTC; con medio, un `.sav` de 16 B tiene tamaño incorrecto y no se toca». |
| G9-H2 | Baja (doc) | docs/04-ios-spec.md (§Game Boy Advance, ajustes por juego); docs/diseno/SPEC.md:493 | Describen la BIOS por juego como «global / la tuya / emulada». Con G8-H7 se quitó «Usar la tuya»: el código solo ofrece «Global (la tuya si existe)» y «Emulada». | `EmulationSettingsView.swift:162-163` (solo los tags 0 y 2). | Cambiar a «Global (la tuya si existe) / Emulada» en los dos documentos. |
| G9-H3 | Baja (evidencia) | docs/auditorias/G9-evidencia.md:60 | Junta en una frase «117 tests, 94 capturas» y «CI verde de `c5d8286`». Ese run tenía 105 tests y 92 capturas (G8-evidencia:29). Los 117/94 son ejecuciones locales posteriores a `92ff6e2` y no hay run de CI sobre HEAD. | G8-evidencia.md:29 y :76. | Separar las dos cosas: CI verde de `c5d8286` con 105/92; ejecución local con 117/94; CI de `b02f914` pendiente (o enlazarlo cuando exista). |

No encontré hallazgos de código.

## Estado de los hallazgos G8-H1…H12

| ID | Estado | Comprobación |
|---|---|---|
| G8-H1 | Cerrado | `gbaPortraitLayout`/`gbaLandscapeLayout` se decodifican con `decodeIfPresent` y caen al valor por defecto, y las claves antiguas siguen siendo de GB. Tanto el editor (`GameScreen` `isAdvance`) como «Restablecer» (`ControlsSettingsView` por consola) y el overlay pasan `shoulders`. `hit(at:)` mira primero el menú y después L/R. En GB, L/R no tiene frames, así que solo se adelanta el menú; con los valores por defecto no se solapa con A/B/cruceta, por lo que no hay regresión práctica. |
| G8-H2 | Cerrado | `eepromSizeFixed` hace que `isMediaSize` y `validSaveSizes` coincidan con `gba_save_load`, que con `opts.save_type` forzado exige `len == save_bytes`. |
| G8-H3 | Cerrado | `forcedMismatchingMediumLeavesLocalMirrorAndBackupsUntouched` crea `EmulatorSession(…, emulation:)` real y compara el disco byte a byte antes y después. El caso EEPROM 8K con espejo es justo el rojo que se reprodujo. |
| G8-H4 | Cerrado | Hay dos capturas AX5 nuevas en `screens.txt`. Solo hay ejecución local (ver G9-H3). |
| G8-H5 | Cerrado (c, e diferidos a Joel) | `GameSettingsSaveWarning.check` cubre la partida local y el espejo. Medio de 0 B con RTC: `validSaveSizes == [16]`. Un `.sav` real de otro tamaño no se sobrescribe: `SaveResolution.resolve` devuelve `.wrongSize` (sin destino de guardado) o, si el espejo sí vale, aparta la partida local con `quarantineCurrent` (no la borra). |
| G8-H6 | Cerrado | `recoverOrphans` aparta el `.tmp` sin `.sav` con `quarantineURL`, con `sync` del directorio. Solo descarta el `.tmp` cuando existe el `.sav`. |
| G8-H7 | Cerrado (docs desalineados: G9-H2) | — |
| G8-H8 | Cerrado | `gba.c`: `save_type > EEPROM8K` pasa a AUTO y `rtc > OFF` pasa a AUTO. Lo cubre el test `test_options_out_of_range`. |
| G8-H9 | Cerrado | Estado v2 con `B(bios_loaded)`. La versión se comprueba antes del parseo (v1 da `GBA_ERR_STATE_VERSION`, con test en unit.c:543). `same_config` solo compara escalares de `tmp`, no indexa nada, y se evalúa después de `v.bad`/`pos`. `consistent()` se sigue aplicando. Los tests de G6 prueban CONFIG para RTC y para BIOS. |
| G8-H10 | Cerrado | — |
| G8-H11 | Cerrado con dos erratas | Ver G9-H1 y G9-H2. |
| G8-H12 | Pendiente de Joel | Falta su aprobación del descarte de G7-1 y su decisión sobre G7-3. Así figura en G-README y ESTADO, sin marcarlo como hecho. |

## Criterios de G8 y G9

- ☁️ Regresión: `gba/build/gbatest --unit` lo ejecuté yo, con resultado `PASS unit: 0 fallos`. `grep -c "@Test"` da 117 en 12 archivos, coherente con «117 tests in 12 suites». Lo que no se ejecutó en el Mac (SingleStepTests, escenas, audio, fuzzers, `make -C gba test` completo) está declarado en G9-evidencia:49.
- Reglas duras 1, 2, 4 y 5: el diff no tiene binarios, ROMs, BIOS ni `.sav`. mGBA, NBA y Hades solo se citan como «leer, no copiar». No aparece código de red ni globals o estáticas nuevas en `gba/`; los cambios son campos de instancia y una variable local.
- Criterios 🍎 (60 fps, Kirby ≥ 30 min, audio, L/R, aprobación de G7-1/G7-3 y de la PR): correctamente sin marcar en G-README, hitos/README y ESTADO.
- La auditoría final Codex sigue pendiente en los documentos. Este informe es la auditoría Opus.

## Notas

- No ejecuté los tests Swift ni `make -C core test`: la lectura del código bastaba para los puntos auditados.
- No comprobé que el formato «medio + 16 B de RTC» sea «compatible con mGBA/VBA», como afirma 10-gba-spec:81. Conviene que Joel lo confirme al importar un `.sav` real.
