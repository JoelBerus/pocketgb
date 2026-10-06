# Auditoría conjunta de cierre (Opus): `cierre-integracion` @ `70592b2`

## Veredicto: APROBAR CON CAMBIOS

El código de la unión cumple las reglas duras y las suites ejecutables están en verde. Los cambios pedidos son **documentales**: la cabecera y el «Siguiente paso exacto» de `docs/ESTADO.md` y la tabla A de `docs/hitos/README.md` describen el estado de antes de fusionar Android. Ninguno abre un camino de pérdida de partida.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| CIERRE-H1 | media | docs/ESTADO.md:5 | La cabecera fusionada está desfasada respecto a la unión. | Dice «Android se fusiona después» y «A7 … pendiente de auditoría». También dice «Riesgos residuales … a cerrar antes de A8: A5V6-H1, A5V6-H3 y A5V7-H1» y «la auditoría conjunta queda reservada para A8». Sin embargo, `A5-android-evidencia.md:411-413` los da por corregidos con tests, y A7 tiene `A7-android-opus/deepseek/respuesta.md`. | Reescribir la cabecera con este estado: `cierre-integracion` = main + D8.1 + G0–G9 + M9 UI + A1–A8; A5V6/V7 cerrados; A7 auditado y respondido; auditoría conjunta en curso. |
| CIERRE-H2 | media | docs/ESTADO.md:33 | El «Siguiente paso exacto» de integración dice «después se fusiona el port Android», pero Android ya está fusionado (`70592b2`). Las entradas 35, 37 y 65 mantienen pasos ya superados («luego auditoría de G8 y G9»). | `sed -n 32,37p docs/ESTADO.md` | Dejar un solo paso: pruebas 🍎 de `PRUEBAS-JOEL.md`, decisiones pendientes y aprobación de la PR a `main`. Marcar como históricas las entradas antiguas. |
| CIERRE-H3 | media | docs/hitos/README.md:45-46 | La fila A7 dice «🟡 implementado, pendiente de auditoría». La fila A8 lista «auditoría conjunta» y «literales `Text(…)` a `strings.xml`» sin decir si este último punto se hizo o se aplazó. ESTADO «Hecho» (A8) repite «pendiente … extraer los 47 literales». | grep de la tabla | Fila A7: auditado (Opus + DeepSeek, respondido); 🍎 pendiente. A8: decidir si `strings.xml` bloquea el cierre o queda registrado como deuda. |
| CIERRE-H4 | media | docs/PRUEBAS-JOEL.md:45-47; docs/ESTADO.md | La decisión de hoy «teclas K/R por defecto en Android» no aparece registrada como pendiente de ratificar por Joel. Busqué «K/R», «K y R» y «por defecto … K» en ESTADO, PRUEBAS-JOEL, 05-android-spec y A7-respuesta, sin resultados. En cambio, «alternar vs. pantalla dividida» sí está en PRUEBAS-JOEL:47, pero no en la tabla de decisiones de ESTADO. | grep sin coincidencias | Añadir ambas decisiones a «Decisiones pendientes de Joel» (PRUEBAS-JOEL) y a ESTADO, marcadas como provisionales. |
| CIERRE-H5 | baja | docs/PRUEBAS-JOEL.md:46 | «G7-1 y G7-3: decidir cuando se pruebe el cable» confunde el ámbito. G7-1 (descarte de un hallazgo de CI) y G7-3 (RTC al final del `.sav` frente a un `.rtc` separado) son de GBA, no del cable M9. | `G7-respuesta.md:7,9` | Redactarlo así: «G7-1: aprobar el descarte; G7-3: confirmar RTC dentro del `.sav`». Vincularlo a la prueba de Kirby. |
| CIERRE-H6 | baja | varios | `git diff --check main...HEAD` da avisos. | `Color.kt:165` y `A5-android-codex-v6.md:44`, `D8.1-implementacion.md:102`, `D8.1-respuesta.md:29`: línea en blanco al final. `A5-android-deepseek-v2.md:38-40`: espacios finales. | Limpiarlo (cosmético). |
| CIERRE-H7 | baja | android/app/src/main/java/com/joelbermudez/pocketgb/video/Viewport.kt:47 | El alias de compatibilidad `typealias IntegerViewport = Viewport` sigue vivo; su único uso aparente es el nombre del test `IntegerViewportTest.kt`. | `git grep typealias` | Renombrar el test y borrar el alias, o documentarlo. Sin impacto funcional. |

No encontré hallazgos bloqueantes ni altos.

## Reglas duras
| Regla | Cumple | Evidencia |
|---|---|---|
| 1. Sin ROMs/BIOS/saves/estados | Sí | `git log main..HEAD --stat \| grep -iE '\.(gb\|gbc\|gba\|sav\|state\|bin)$'` está vacío. Busqué el logo de Nintendo en 0x104 (GB) y en 0x04 (GBA) en todos los archivos añadidos de más de 300 B: ninguna coincidencia. |
| 2. Licencias | Sí (por muestreo) | Las líneas añadidas en `gba/ios/android` no mencionan Gambatte, mGBA, Hades, NBA, Delta, GPL, AGPL ni MPL. mGBA solo aparece como oráculo externo en `tools/` y docs. La única coincidencia de LGPL es `resources.excludes "/META-INF/{AL2.0,LGPL2.1}"`, que es empaquetado y no código copiado. |
| 3. ROM no confiable | Sin cambios en `core/`. GBA auditado en G0–G9 | `git diff main...HEAD --stat -- core/` está vacío. |
| 4. Núcleo puro | Sí | `make -C core check-globals`: «Sin estado global mutable: OK». `core/` sin cambios. |
| 5. Sin red | Sí | En iOS y Android, `git grep URLSession\|NWConnection\|NSAppTransport\|INTERNET\|HttpURLConnection\|okhttp` solo encuentra la aserción negativa de `ManifestPolicyTest.kt:33`. Dependencias: solo AndroidX/Compose/Navigation3/DataStore y kotlinx-serialization (JetBrains). |
| 6. Partidas | Sí (revisado en los puntos de cruce) | Ver las Notas. |
| 7. Criterios con salida | Sí en esta auditoría (salidas abajo). Android no se ejecutó, por indicación del encargo. | — |

## Pendiente de Joel
Nada de esto se marca como hecho.
- **Decisiones:**
  - G7-1: aprobar el descarte.
  - G7-3: RTC dentro del `.sav` o `.rtc` aparte.
  - M9: alternar entre juegos o pantalla dividida (provisional: alternar).
  - Android: teclas K/R por defecto (provisional, sin registrar; ver H4).
  - Aceptar o aplazar la extracción a `strings.xml` (A8).
  - Aprobar la PR a `main`.
- **🍎 iPhone:**
  - D8.1: continuación exacta.
  - G8/G9 con Kirby: 60 fps con el HUD, ≥ 30 min, guardar, cierre forzado y recuperación, audio, L/R.
  - M9: Rojo ↔ Amarillo, intercambio de Kadabra, audio al alternar, segundo plano durante el cable (`PRUEBAS-JOEL` I7–I10).
- **🍎 Android en el teléfono:**
  - A5: carpeta real, cierre forzado real, recuperación desde el espejo, restaurar backup.
  - A7: mando físico, TalkBack, rotación, memoria baja.
  - A8: instalación según `07-instalacion-android.md`; Release sin firma.
- Evidencia final de la regresión Android (Gradle) de la integración: la añade el desarrollador.

## Notas
**Verificación ejecutada en el worktree:**
- `make -C core test`: `65/68 PASS · requeridos: 65/65 PASS` y «OK: todos los casos requeridos en PASS». Los 3 que fallan son known-fail documentados, como `boot_sclk_align`.
- `make -C core check-globals`: «Sin estado global mutable: OK».
- `gba/build/gbatest --unit`: «PASS unit: 0 fallos».
- iOS `-only-testing:PocketGBTests` (bajo el mutex `sim`): «Test run with 172 tests in 17 suites passed» y `** TEST SUCCEEDED **` (172, como se esperaba).
- `git diff --check`: solo lo de H6.
- Catálogos:
  - iOS `ios/PocketGBUITests/screens.txt`: 106 IDs, sin contar comentarios.
  - Android `tools/android-screens.txt`: 125 líneas útiles (137 en total, con comentarios).

**Regla 6 en los cruces (código final):**
- **D8.1 × GBA:**
  - `EmulatorSession.start(restoring:)` compara la RAM sin el pie del RTC (`ramBytes()` = `sramSave().dropLast(sramFooterBytes)`, l. 183-186) y revierte el núcleo si la RAM difiere (`notCurrent`).
  - La partida confirmada se compara también sin pie (l. 166-167).
  - Si el flush falla, revierte y lanza `saveFailed`.
  - `sramFooterBytes`: GB `sramSaveSize - ramBytesAtLoad`, GBA 16 con RTC, `LinkedPair` 0.
- **Continuación × cable:**
  - `start(restoring:)` y `saveState()` lanzan `linkUnsupported` con más de una persistencia.
  - `AppState` pone `stateStore = nil` en modo cable (l. 424). Por eso `saveAutomaticState` y `enterBackground` no escriben `.auto`.
  - `closeGame` del cable: pausa (flush de las dos SRAM), `stop`, portadas y `didRestoreSave` por cada juego (invalida continuaciones), sin estado automático.
- **Android:**
  - `configChanges` cubre `orientation|screenSize|screenLayout|smallestScreenSize|…|density`, así que rotar no recrea la Activity ni dispara `ON_PAUSE`. No hace falta flush porque el proceso y la sesión siguen vivos.
  - `ON_PAUSE`/`ON_STOP` → pausa y flush acotado a 3 s (`SessionLifecycleObserver`).
  - `onTrimMemory` va al mismo camino.
  - `forceStop` y el reaper de `tryClose` están cubiertos por los tests de A5V7-H1.
  - No encontré ninguna interacción nueva entre bloques que reabra la pérdida de partida.

**Calidad de la unión:**
- No hay `.g7-codex.err` ni archivos `.err` versionados. El worktree está limpio.
- No hay `TODO`/`FIXME` nuevos en el código; solo una mención a `TODO(A5)` dentro de una tabla de evidencia.
- No hay tests desactivados: los `XCTSkipIf` sin `FIXTURE_DIR` ya estaban y están documentados.
- Scripts nuevos en `tools/` documentados (`android-save-kill-test.sh`, `android-screenshots.sh`, `fetch-gba-test-roms.sh`, `gba-compare.py`, `oracle-gba/shot.c`).

**Límites:** no revisé el diff completo, solo muestras e informes por hito. Tampoco ejecuté Gradle ni `make -C gba test` completo, que no corre en este Mac por falta de `ld.lld`.
