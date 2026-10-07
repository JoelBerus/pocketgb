# Auditoría A7 Android (Opus, auditor de respaldo) — HEAD ae8be16

## Veredicto: APROBAR CON CAMBIOS

Ningún bloqueante. Los cambios piden poco: uno de mando (consumo de teclas no asignadas) y limpiezas menores.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| A7-H1 | media | android/app/src/main/java/com/joelbermudez/pocketgb/ui/gameplay/GameplayHost.kt:118-128 (+ input/GamepadInput.kt:32) | Durante la partida el sink consume **cualquier** tecla con origen de mando o DPAD, aunque no esté asignada (`return true` incondicional). `GamepadState.handles()` existe pero nadie lo llama en producción. Efectos: las teclas sin asignar del mando (p. ej. `BUTTON_MODE`, `BUTTON_THUMBL`) no llegan al sistema. Además, `isPadSource` acepta `SOURCE_DPAD`, que también traen algunos teclados y mandos a distancia: una flecha de un teclado físico pone `padSeen = true` y oculta los controles táctiles. | Lectura del código; `grep "\.handles("` no encuentra ningún uso fuera de la definición. | `onKey` debe devolver `state.handles(keyCode)` y poner `padSeen` solo en ese caso. Valorar quitar `SOURCE_DPAD` de `isPadSource` o exigir `isGamepadSources(event.device.sources)`. |
| A7-H2 | baja | input/GestureExclusion.kt:20-25 | El recorte al área segura solo se aplica en horizontal (left/right). En vertical, el rectángulo puede salirse de `area.top`/`area.bottom`. El efecto es pequeño, porque el control ya está dentro del área. | Código. | Recortar también `top`/`bottom` con `area`. |
| A7-H3 | baja | ui/library/LibraryListDetail.kt:21; app/build.gradle.kts | `ENABLE_LIST_DETAIL = false` deja la lista-detalle como código muerto en Release. Arrastra `adaptive-layout`/`adaptive-navigation` para un camino que no se ejecuta. La decisión está documentada en el plan, pero añade dependencias a cambio de nada. | Código y diff de gradle. | Aceptable si A8 lo activa. Si no, retirar el archivo y las dos dependencias. |
| A7-H4 | baja | input/ControlsAccessibilityHelper.kt:84-87 | Con los controles ocultos por mando (`visibility = HIDDEN`), los nodos virtuales siguen expuestos a TalkBack y una pulsación accesible sigue pulsando. Para un usuario ciego con mando es útil, pero ningún anuncio indica que los controles están ocultos. Es confuso, no incorrecto. | Código; `touchSettingsFor` solo cambia `visibility`. | Documentarlo como decisión, o marcar los nodos con `stateDescription` «oculto». |
| A7-H5 | baja | MainActivity.kt:76 | `@SuppressLint("RestrictedApi")` en `dispatchKeyEvent`: el riesgo real es nulo, porque es un override público de `Activity` que androidx anota por `KeyEventDispatcher`. Se llama a `super`, así que se conserva el despacho de androidx. | Código. | Nada. Solo dejar el comentario. |

## Comprobaciones de reglas duras
- **ROMs, saves y estados:** no hay `.gb/.gbc/.sav/.state/.bin` en `git log a84ee3a..HEAD --stat`.
- **`core/` y `cpp/`:** la única diferencia es `pocketgb_jni.c` (+2), que viene de `ee901f4` (A6-R H7, `_Static_assert`), no de A7. Desde los commits de A7 están intactos.
- **`saves/**` y `GameSession.kt`:** sin cambios en `git diff dd12f99 HEAD`.
- **Red:** ni `INTERNET` ni `HttpURLConnection` en `src/main`.
- **Dependencias nuevas:** todas son AndroidX (`customview:1.1.0`, `material3-adaptive-navigation-suite`, `adaptive`, `adaptive-layout`, `adaptive-navigation`, con versiones del BOM). No hay `materialyoucolor`, y la evidencia lo describe bien.
  - Imprecisión menor de la evidencia (§15): dice que solo se añadió `customview`, pero L3 añadió además las cuatro adaptive. La propia evidencia lo recoge en otro punto.
- **`src/debug/AndroidManifest.xml`:** los `configChanges` añadidos solo afectan a `GameplayTestActivity`, que es Debug. No llegan a Release.

## Revisión por áreas
- **Mando (R1–R6):**
  - `GamepadState` es puro y correcto: OR de cruceta, hat y stick, anulación de opuestos, umbral 0,5 y acciones de la app solo en el flanco.
  - `KEYCODE_BACK` del mando se deja al sistema.
  - La máscara vuelve a 0 en `onFocusLost`, al desconectar (`LaunchedEffect`) y en `onDispose`.
  - Con un menú o un diálogo abierto, `padEnabled = false` desregistra el sink.
  - `sanitized()` filtra con `isAssignableKey` y quita los duplicados.
  - El ajuste `showTouchControlsWithController` está respetado.
- **Rotación y ciclo de vida (R12–R15):**
  - Con `configChanges` no hay recreación al rotar, así que `SessionLifecycleObserver` no recibe `ON_PAUSE`.
  - Multiventana con pérdida de foco, llamadas y segundo plano siguen produciendo `ON_PAUSE`/`ON_STOP`, por lo que el vaciado (I1–I4) no se ve afectado. No se encontró riesgo.
  - `onTrimMemory` solo pausa con nivel ≥ `UI_HIDDEN`, por el mismo camino idempotente `game.pause()`.
  - `onLowMemory` pasa `RUNNING_CRITICAL` (15 < `UI_HIDDEN` 20), así que solo libera la caché.
  - No se modificó la ruta de guardado.
- **Prueba de rotación:** usa `requestedOrientation`, que con `configChanges` dispara un cambio de configuración real del mismo tipo. Es un sustituto razonable, aunque no idéntico a girar el dispositivo físico.

## Criterios del hito
| Criterio | Resultado |
|---|---|
| JVM 372 | **Verificado.** Lo ejecuté: `:app:testDebugUnitTest` BUILD SUCCESSFUL; 43 XML, 372 tests y 0 fallos. Hay 372 `@Test` en `src/test`. |
| Instrumentados 333 | El número de `@Test` en `src/androidTest` cuadra: 333. Los resultados no los pude verificar: en `build/outputs/androidTest-results` del worktree solo hay 1 XML con 8 tests, que es de otra corrida. No los lancé, como pedía el encargo. |
| Lint 0 errores, DEX sin Debug, kill-test 50/50 | No verificado; no lo ejecuté. Lo acepto según la evidencia. |
| Release sin `INTERNET` | **Verificado** en el manifiesto `main`. |
| 75 IDs / 125 PNG | `tools/android-screens.txt` tiene 125 líneas no vacías, lo que coincide con 125 capturas. Los 75 IDs no se recontaron por separado. |

## Notas
- Los ganchos de catálogo son neutros por defecto:
  - `touchSettingsFor` es identidad si `hideTouch = false`.
  - `initialAssigning` vale `null` por defecto.
  - `initialDetailId` también vale `null` y está detrás de un flag desactivado.
- `onTrimMemory(UI_HIDDEN)` repite la pausa de `ON_STOP`. Es inocuo porque `pause()` es idempotente.
- Este auditor no participó en la implementación.
