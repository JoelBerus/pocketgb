He completado la revisión de solo lectura. Informe a continuación.

## Veredicto: APROBAR CON CAMBIOS

No hay hallazgos bloqueantes ni altos. El código es sólido y los cambios sensibles están acotados y bien defendidos. El "cambio" pendiente es de proceso, no de código: el criterio 9 (regresión de pérdida de partidas) no se ejecutó en esta pasada, y el criterio 7 se cumple "de hecho" pero no al pie de la letra.

## Hallazgos

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | docs/auditorias/A6-android-evidencia.md:54 | Criterio de aceptación 9 no ejecutado: `tools/android-save-kill-test.sh` (50 iteraciones sin pérdida) quedó pendiente. Es la prueba de regresión del fallo de mayor impacto (regla dura 6, pérdida de partidas). | La propia evidencia lo lista en "Lo que NO se ha verificado". No se encontró salida de esa corrida en el repo. | Ejecutarla antes de dar el hito por cerrado, o justificar explícitamente por qué no aplica (la ruta de guardado I1–I4 no fue tocada por L1–L5; solo SaveCoordinator ganó `forceStop`, que es aditivo). |
| H2 | baja | game/GameSession.kt:651 | `captureParkedFrame` usa `session.saveState().pixels` para capturar la portada, lo que serializa el estado completo del núcleo (SRAM+WRAM+VRAM+registros) en el hilo principal solo para descartar los bytes y quedarse con el framebuffer. K9 pedía `copyFramebuffer`/`CoreBridge.copyFrame` (92 KiB). | Diff de `GameSession.kt`; `saveState()` invoca `nativeSessionStateSave` + `nativeSessionCopyFrame` (EmulatorSession.kt:119-128). | Usar una copia directa del framebuffer (`copyFrame`) en lugar de `saveState()`; es read-only, pero evita latencia innecesaria en el cierre. Riesgo bajo: va envuelto en `runCatching` y solo con sesión aparcada. |
| H3 | baja/nota | GameSession.kt (rango L0–L2) | El criterio 7 dice "cambios en GameSession.kt limitados a saveCurrentToAuto y al callback de portada", pero el diff también incluye: (a) refactor del hilo de reparación (A6-L0, cierre de riesgos A5V6-H1/H3/A5V7-H1) y (b) delegados en caliente `setVolume`/`setScaleMode`/`setCompatPalette` (L2). | git diff 63fcd2f...9583cef -- GameSession.kt (201 líneas cambiadas). | No es defecto de código: (a) fue auditado por separado en A6-L0-Opus (APROBAR) y (b) son delegados triviales sin impacto en la ruta de guardado. Documentar que el criterio se cumple por alcance auditado, no por literalidad. |
| H4 | nota de alcance | — | El rango `63fcd2f...9583cef` contiene además 3 commits de A7 (mando físico: `9cb131c`, merge `9c29ec1`, y plan `a84ee3a`) con archivos propios (Gamepad*.kt, ControllerMappingScreen.kt, strings_a7_controller.xml). La instrucción solo cita excluir `9c29ec1`. | `git log --graph`; `git show --stat 9cb131c/a84ee3a`. | No afecta al juicio de A6; lo señalo para que no se confunda contenido A7 con A6. Quedó fuera de mi evaluación. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| 1. `testDebugUnitTest` verde, ≥ 241 | no (sandbox sin SDK Android) | Evidencia: 312 tests, 0 fallos. Plausible; no reejecutable aquí. |
| 2. `connectedDebugAndroidTest` verde, ≥ 191 | no (sin emulador) | Evidencia: 279 casos, 0 fallos. No reejecutable. |
| 3. `assembleDebug assembleRelease` sin avisos nuevos de lint | no (sin SDK) | Evidencia: 0 errors / 35 warnings. No reejecutable. |
| 4. `aapt2` sin `INTERNET` | sí (estático) | Sin `INTERNET` ni `http/https/URLSession/OkHttp` en manifest ni en main. Release solo lleva `BuildVariantContent.kt` (stub `false`). |
| 5. DEX de Release sin clases Debug | no (sin APK) / sí (estático) | Código de catálogo reside en `src/debug/`; `src/release/` solo el stub. Separación por buildVariant correcta. |
| 6. `core/` intacto + `make -C core test/asan` | parcial | `git diff main...9583cef -- core/` vacío (verificado). `make` no reejecutable (escribiría objetos en el repo). |
| 7. `saves/` intacto salvo SaveCoordinator; GameSession limitado | sí / con matiz (H3) | `saves/` solo `SaveCoordinator.kt` (16+/1−, `forceStop` de L0). GameSession.kt lleva más que los dos puntos nombrados (H3). |
| 8. Catálogo + `CatalogCoverageTest` + `VERIFICACION.md` | sí (estático) | `tools/android-screens.txt` tiene 61 ids; `VERIFICACION.md` revisa todas las capturas. Test de cobertura no reejecutable, pero el cruce está automatizado. |
| 9. `android-save-kill-test.sh` 50 iteraciones sin pérdida | no | No ejecutado (H1). |
| 10. Auditoría con informe | sí | Este informe (y el de A6-L0-Opus ya presente). |

## Notas

Reglas duras, verificadas una a una:
- Regla 1 (ROMs/saves): `git ls-files` no devuelve ningún `.gb/.gbc/.sav/.rom`; `git log --all --stat` solo arroja un falso positivo (la cadena ".sav" en un mensaje de commit, no un archivo). El ROM sintético de catálogo es generado (`DebugSyntheticRom`), no comercial.
- Regla 2 (copyleft): `licenses_sameboy.txt` y la cabecera de `core/src/cgb.c` citan correctamente SameBoy bajo Expat/MIT (Lior Halphon). No hay Gambatte/Delta/GPL/AGPL ni el `iOS/` de SameBoy.
- Regla 5 (sin red): verificado estático, sin permiso ni SDK de red en runtime.
- Regla 4 (core determinista y sin globals): `core/` no se tocó en absoluto.
- Seguridad ROM-no-confiable: `options_valid` (JNI) valida `model ∈ {0..2}` y `paleta ∈ 0..12` antes de tocar el núcleo, en fase con la validación Kotlin (`EmulationOptions.init` + `CoreBridge`). El puente `JNI_ERR_INVALID_ARGUMENT = 17` es el primer código libre de `gb_result` (que llega a 16) y `CoreError.fromResult(17) → InvalidArgument`; `NS_INVALID`/`NS_NOT_COMPAT` (-3/-4) mapean correctamente. `compute_layout` usa casts `int64` para multiplicaciones y acota `source_x/source_y` a [0,159]/[0,143]; sin `malloc` en el bucle. La ganancia de audio es atómica, con `isfinite` y recorte a [0,1].
- Guardado (regla 6): `saves/` solo ganó `forceStop` (endurecimiento idempotente); `loadState(saveCurrentToAuto)` es semánticamente idéntico al A5 (`saveCurrentToAuto=true` + guarda `slot != AUTO` ≡ el antiguo `saveCurrentFirst = slot != AUTO`). El gancho de portada (`captureParkedFrame`) es read-only, va tras el flush y antes de `nativeSessionDestroy`, envuelto en `runCatching`. `artwork/` queda excluido de `backup_rules.xml` y `data_extraction_rules.xml` (no consume cuota ni pone en riesgo partidas).

No inventé hallazgos. Lo que no pude reejecutar (Gradle, emulador, APK, `make`, test de pérdida de partidas) lo marco explícitamente como "no reejecutable en esta sandbox", que no es un hallazgo del proyecto.
