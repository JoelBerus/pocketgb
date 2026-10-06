He completado la revisión de solo lectura. Reejecuté la suite JVM de forma limpia (`--rerun-tasks`, 26 tareas ejecutadas): **372 tests, 0 fallos, 0 errores, 0 omitidos**, idéntico a la evidencia. Los instrumentados (333 casos) y la prueba en hardware no son verificables en esta sandbox (sin emulador/dispositivo físico), no es un hallazgo del proyecto.

## Veredicto: APROBAR CON CAMBIOS

Sin hallazgos bloqueantes ni altos. Las invariantes duras se cumplen y la evidencia es plausible y verificable. Pendientes menores (mando físico, teléfono real) más tres hallazgos de severidad baja, todos acotados y sin impacto en partidas ni en el núcleo.

## Hallazgos

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | baja | `input/GamepadRouter.kt:40` | `dispatchMotion` solo acepta `SOURCE_JOYSTICK`. Algunos mandos físicos emiten los ejes de hat/cruceta con `SOURCE_DPAD` o `SOURCE_GAMEPAD`, no `SOURCE_JOYSTICK`, con lo que el hat/stick podría no llegar en esos dispositivos. | No verificable en sandbox (sin mando físico); la propia evidencia lo admite ("mando real no verificado"). | Aceptar también `SOURCE_GAMEPAD` (y `SOURCE_DPAD` para el hat) en `dispatchMotion`, o documentar la limitación y cubrirlo al probar el mando real de Joel. |
| H2 | baja | `input/GameControlsView.kt:32` | `density` se captura una sola vez como `val` de `resources.displayMetrics.density`. Con `density` en `configChanges` (R12), un cambio de tamaño de pantalla no recrea la actividad y el campo queda obsoleto (dibujo y `touchFrame` a escala vieja) hasta la siguiente recreación. | Verificado en código: `rebuild()` (`:190-214`) reusa el `val density`. Caso de borde (el dispositivo está en pausa al cambiar el ajuste), impacto solo visual/de puntería, sin efecto en emulación ni guardado. | Releer la densidad en `onSizeChanged`/`rebuild()` (o desde `resources.displayMetrics` en vez de cachearla). |
| H3 | baja | `input/GamepadMappingTest.kt:130-132` | La prueba `customMappingReplacesDefaults` afirma que "BUTTON_B ya no es A" pero el `assertEquals(a, onKey(BUTTON_B).mask)` pasa solo porque `BUTTON_X` sigue pulsado; no aísla que `BUTTON_B` no esté asignado. | Verificado en código (`resolved()` sí elimina BUTTON_B→A cuando A se reasigna). No es bug de producto: la aserción es débil/engañosa. | Soltar `BUTTON_X` antes de pulsar `BUTTON_B`, y/o `assertFalse(pad.handles(BUTTON_B))`. |

Observación menor (no bloqueante): `resolved()` deja una acción sin botón si el usuario asigna su tecla por defecto a otra acción (p. ej. «A»→`BUTTON_A` deja a «B» sin mando). Es coherente con "una tecla, una acción" y recuperable desde la UI; solo se señala como nota de UX, no como defecto.

## Criterios del hito

| Criterio (plan A7) | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| 1. `testDebugUnitTest` en verde, recuento ≥ A6+ | sí | Reejecutado: 372/372 (A6 cerró en 331). Coincide con la evidencia. |
| 2. `connectedDebugAndroidTest` verde (rotación, TalkBack, rail, mando) | no (sandbox) | Requiere emulador; no ejecutable aquí. Evidencia reporta 333 casos, 0 fallos; plausible y consistente. |
| 3. `assembleDebug assembleRelease lintDebug` sin errores | no (sandbox) | No reejecutado; evidencia: lint 0 errores/20 avisos. |
| 4. `aapt2 dump` sin INTERNET + `configChanges` presente | sí | Manifest sin `<uses-permission>`; `configChanges` incluye orientation/screenSize/…/density, excluye fontScale. `ManifestPolicyTest` lo valida. |
| 5. DEX Release sin clases Debug | no (sandbox) | Evidencia reporta 0 clases `pocketgb/debug/`. |
| 6. `core/`+`cpp/`+`saves/`+`GameSession.kt` sin cambios | sí | `git diff --stat dd12f99 ae8be16 -- <esos>` vacío. |
| 7. Dependencias nuevas solo AndroidX | sí | `build.gradle.kts` añade solo `material3-adaptive-navigation-suite`, `adaptive`, `adaptive-layout`, `adaptive-navigation`, `customview`. Sin `materialyoucolor` (consistente con la desviación). |
| 8. Catálogo A6+A7 generado + `CatalogCoverageTest` | sí (parcial) | 14 ids A7 verificados en `A7Catalog.kt` (4+8 alias+2 compuestas). Capturas/generación no reejecutadas aquí. |
| 9. Regresión kill-test 50/50 con rotaciones | no | Evidencia: kill-test 50/50 OK, **pero** sin rotaciones (el script no se modificó; desviación reconocida). |
| 10. Prueba manual en el teléfono de Joel | no | La evidencia lo declara abiertamente "no verificado" (mando físico, TalkBack real, rotar 20×). |
| 11. Informe de auditoría | sí | `docs/auditorias/A7-android-evidencia.md` presente; esta es la segunda auditoría independiente. |

Regla dura 6 (prioridad del encargo): `saves/**` y `GameSession.kt` intactos desde `dd12f99` (diff vacío). `MainActivity.onTrimMemory`→`GameplayViewModel.onTrimMemory` (`:239-241`) solo llama a `onBackground()` (pausa con flush acotado de 3 s ya existente) si `level >= TRIM_MEMORY_UI_HIDDEN`, y en primer plano solo `ArtworkStore.trimMemory()` (limpia caché en memoria, sin tocar disco). No añade ruta de guardado.

## Notas

- Reglas duras 1–5, 7: sin ROMs/saves en el historial (el `git log --all --stat` solo halló un mensaje de commit que menciona ".sav", no un archivo); `.gitignore` y el hook `pre-commit` (detecta la cabecera por la firma Nintendo en offset 260, `ceed6666`) presentes y activos. Sin código GPL/AGPL ni importaciones de red en los archivos nuevos; esquema Material con colores fijos hardcodeados (sin dependencia `materialyoucolor`). Núcleo y C sin cambios, así que no hay código nuevo de parseo del ROM que auditar en este hito.
- El mando no consume eventos ajenos: `GamepadRouter.dispatchKey` solo consume con `isPadSource` y descarta `KEYCODE_BACK`; el sink solo está registrado durante la partida sin hoja/diálogo (al abrir hoja se desregistra y la cruceta vuelve a Compose). Las teclas de mando no asignadas se consumen intencionadamente (evitan que la cruceta mueva el foco); efecto nulo, sin bug.
- `SessionLifecycleObserver` intacto (solo un comentario): con `configChanges` la rotación ya no llega a `ON_PAUSE`/`ON_STOP`, como pretende R12; multiventana/llamadas/segundo plano siguen pausando.
- `GameSurface` es simétrico: la bandera `attached` impide el doble `attachSurface` y `surfaceChanged` re-aplica el escalado; cumple la invariante 4 de A7.
- Desviaciones reconocidas y coherentes con el código: `ENABLE_LIST_DETAIL` sin conectar a navegación, kill-test sin rotaciones, `fontScale` como `Float` en el intent. Ninguna oculta un defecto de producto.
- Los criterios 2, 3, 5, 9 (con rotaciones) y 10 quedan como verificación en hardware/emulador a cargo de Joel; no los doy por cumplidos, pero tampoco son hallazgos del proyecto — son pasos de cierre pendientes, ya declarados así en la evidencia.
