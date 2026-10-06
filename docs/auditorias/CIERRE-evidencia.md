# Evidencia de cierre de la integración (2026-10-06)

Rama `cierre-integracion` (worktree `pocketgb-integracion`): `main` + D8.1 + GBA G0–G9 + M9 UI + Android A1–A8. Base de la auditoría conjunta: `70592b2`. Informe del auditor: [CIERRE-opus](CIERRE-opus.md) (APROBAR CON CAMBIOS, 7 hallazgos documentales, ninguno bloqueante ni alto).

## 1. Resumen de la noche
| Bloque | Resultado | Fusión en la rama |
|---|---|---|
| D8.1 (continuación exacta, alias, identidad, ajustes visuales) | Dos vueltas Opus; 103 tests | `4d085c9` |
| GBA G0–G9 | Auditoría final Opus: APROBAR CON CAMBIOS (3 de docs, corregidos); 117 tests iOS y 94 capturas | `94b53fa` |
| Integración iOS (D8.1 + GBA) | Revisión Opus: INT-H1 alta (prefijo EEPROM GBA) y H2–H4 bajas, corregidos (`ef7a1b2`, `cdb8b2f`); 130 tests | `4d085c9`, `94b53fa`, `2386573` |
| M9 UI del cable | Auditorías Opus L2 y final, respondidas; 172 tests, 106 PNG | `ad446d9` |
| Android A1–A8 | A6 y A7 con Opus + DeepSeek, respondidos; A8 cerrado; JVM 375, instrumentados 337 | `70592b2` |

## 2. Verificación por bloque
- **D8.1:** [D8.1-implementacion](D8.1-implementacion.md), [D8.1-opus](D8.1-opus.md), [D8.1-opus-v2](D8.1-opus-v2.md), [D8.1-respuesta](D8.1-respuesta.md).
- **GBA:** [G9-evidencia](G9-evidencia.md), [G9-opus](G9-opus.md). En el Mac: `gbtest --unit` sin fallos. `make -C gba test` completo solo corre en Linux/CI.
- **Integración iOS:** [CIERRE-integracion](CIERRE-integracion.md), [revisión](CIERRE-integracion-revision.md). Auditoría de cierre sobre `70592b2`: `make -C core test` 65/68 PASS (requeridos 65/65), `check-globals` OK, `gbatest --unit` 0 fallos, iOS `PocketGBTests` 172 tests en 17 suites, `** TEST SUCCEEDED **` (ver [CIERRE-opus](CIERRE-opus.md) §Notas).
- **M9:** [M9-ios-evidencia](M9-ios-evidencia.md), [M9-ios-opus](M9-ios-opus.md), [M9-L2-opus](M9-L2-opus.md), [M9-ios-respuesta](M9-ios-respuesta.md).
- **Android:** [A5](A5-android-evidencia.md), [A6](A6-android-evidencia.md), [A7](A7-android-evidencia.md), [A8](A8-android-evidencia.md) y las respuestas [A6](A6-android-respuesta.md) y [A7](A7-android-respuesta.md).

## 3. Regresión final de Android (Gradle)
Comando: `./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug --continue`, después `installDebug`, `tools/android-save-kill-test.sh 50` y `aapt2 dump permissions` del APK Release. Se ejecutó sobre `android/` del worktree principal (`codex/android-port` en `edbb071`) con el emulador bajo el mutex `emu`. El directorio `android/` de `70592b2` es idéntico (`git diff edbb071 70592b2 -- android` vacío), de modo que el resultado vale para la integración; después de esa corrida solo cambiaron H6/H7 (ver §4).

Líneas del log (`scratchpad/logs/android-final-regresion.log`), tal cual:
```
BUILD SUCCESSFUL in 7m 40s
152 actionable tasks: 96 executed, 56 up-to-date
gradle exit: 0

Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
package: com.joelbermudez.pocketgb
permission: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
exit: 0
JVM (375, 0, 0, 0)
INSTR (337, 0, 0, 0)
```
- JVM 375 tests, 0 fallos/errores/omitidos. Instrumentados 337, 0 fallos/errores/omitidos. Debug, Release y lint verdes. Kill-test 50/50.
- El único permiso del APK Release es el propio `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` de AndroidX (firma de la app); no hay `INTERNET`. En esta corrida no hubo fallos; `ProcessKillTest` (sensible a la carga, visto una vez en la noche con la máquina saturada) pasó.
- Tras H7 se ejecutó `:app:testDebugUnitTest --tests '*ViewportTest'`: BUILD SUCCESSFUL. La suite completa no se repitió tras ese cambio (solo renombre de un test y borrado de un alias sin otros usos).

## 4. Respuesta a CIERRE-H1…H7
| ID | Estado | Respuesta |
|---|---|---|
| H1 | Corregido | `docs/ESTADO.md` reescrito: cabecera con el estado final de la unión (A5V6/V7 cerrados con tests, A7 auditado y respondido, auditoría conjunta respondida). |
| H2 | Corregido | Un solo «Siguiente paso exacto» (pruebas, decisiones, PR); las entradas antiguas pasan a la sección «Histórico», sin borrarlas. |
| H3 | Corregido | `docs/hitos/README.md`: A7 auditado, A8 cerrado (literales extraídos: 51 claves, `9fa39a9`/`f875e9b`), M9, D8.1 y tablas G coherentes. |
| H4 | Corregido | Las decisiones K1–K20, R1–R16 (el auditor las llamó «teclas K/R»: son esas decisiones, no teclas), M9, G7-1, G7-3 y A8 constan en la tabla «Decisiones provisionales pendientes de ratificar por Joel» de ESTADO y en PRUEBAS-JOEL. |
| H5 | Corregido | G7-1 y G7-3 son de GBA y se vinculan a la prueba de Kirby, no al cable. |
| H6 | Corregido | `git diff --check main...HEAD` limpio (commit `2228c21`). |
| H7 | Corregido | `typealias IntegerViewport` borrado (sin otros usos en código; solo quedaban menciones históricas en planes); los dos tests de `IntegerViewportTest.kt` pasaron a `ViewportTest.kt` (ya existía), y el archivo antiguo se eliminó. |

## 5. Pendiente de Joel (lista consolidada del auditor)
Nada de esto se marca como hecho.
- **Decisiones:** G7-1 (aprobar el descarte); G7-3 (RTC dentro del `.sav`); M9 (alternar frente a pantalla dividida, provisional: alternar); Android K1–K20 y R1–R16 (provisionales); aceptar o aplazar `LibraryModels.kt:75` (A8); aprobar la PR a `main`.
- **🍎 iPhone:** D8.1 (continuación exacta, I1); G8/G9 con Kirby (60 fps con el HUD, ≥ 30 min, guardar, cierre forzado y recuperación, audio, L/R); M9 (Rojo ↔ Amarillo, intercambio de Kadabra, audio al alternar, segundo plano con el cable: I7–I10).
- **🍎 Android en el teléfono:** A5 (carpeta real, cierre forzado real, recuperación desde el espejo, restaurar backup); A7 (mando físico, TalkBack, rotación, memoria baja); A8 (instalación según [07-instalacion-android](../07-instalacion-android.md), Release sin firma).

## 6. No verificado
- Nada en dispositivo real (iPhone ni teléfono Android); el emulador no cubre audio, mando, TalkBack con gestos ni rendimiento.
- `make -C gba test` completo en este Mac (falta `ld.lld`); corre en Linux/CI.
- La regresión Gradle completa no se repitió después de H6/H7.
