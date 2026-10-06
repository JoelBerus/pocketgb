# A7 Android: respuesta a la auditoría

Rama `a7-respuesta` (desde `codex/android-port`). Informes: `A7-android-opus.md` y `A7-android-deepseek.md`. Salidas en la sección «Correcciones de la auditoría Opus» de `A7-android-evidencia.md`.

| ID | Sev. | Decisión | Qué se hizo |
|---|---|---|---|
| A7-H1 | media | Corregido | `GameplayHost.onKey` devuelve `state.handles(keyCode)`: lo no asignado va al sistema. `padSeen` solo se activa con una tecla asignada que el router ya filtró a mando real. El router acepta solo `SOURCE_GAMEPAD`/`SOURCE_JOYSTICK` (`isGamepadSources`); `SOURCE_DPAD` solo (teclados con flechas, mandos a distancia) ya no cuenta, y un mando real con cruceta trae también `SOURCE_GAMEPAD`. «Mando conectado» sigue viniendo de `GamepadMonitor`. Pruebas JVM (`GamepadMappingTest`) e instrumentadas (`GamepadUiTest`, 3 casos), con rojo previo. |
| A7-H2 | baja | Corregido | `GestureExclusion` recorta también `top`/`bottom` al área segura; prueba con rojo previo en ventana muy baja. |
| A7-H3 | baja | Decisión: conservar | `ENABLE_LIST_DETAIL = false` deja la estructura y las dependencias `adaptive-layout`/`adaptive-navigation` (AndroidX; el catálogo `library-list-detail` las monta y A8 puede activarla). No se toca `build.gradle.kts`. |
| A7-H4 | baja | Corregido | Con los controles ocultos los nodos de TalkBack llevan `stateDescription` «Oculto» (`controls_a11y_hidden`) y la vista invalida el árbol al cambiar la visibilidad. Se mantienen accesibles (útil con mando) y anuncian su estado. Prueba con rojo previo. |
| A7-H5 | baja | Sin acción | `@SuppressLint("RestrictedApi")` en `dispatchKeyEvent`: riesgo nulo, se conserva el comentario. |
| DS-H1 | baja | Corregido | `dispatchMotion` acepta ejes si la fuente del evento o las fuentes de su dispositivo son de mando (`isPadMotion`); un teclado con `SOURCE_DPAD` no. Prueba JVM. Sin mando físico: no verificado en hardware. |
| DS-H2 | baja | Corregido | `density` se lee de `resources.displayMetrics` en cada uso y `onConfigurationChanged` reconstruye la geometría. Sin prueba: simular un cambio de densidad en caliente no es fiable en el emulador; solo comprobado por revisión de código. |
| DS-H3 | baja | Corregido | `customMappingReplacesDefaults` suelta X antes de comprobar que `BUTTON_B` ya no se maneja ni pulsa A. |

## No verificado
Mando Bluetooth real, TalkBack real y cambio de densidad real (ver la evidencia de A7).
