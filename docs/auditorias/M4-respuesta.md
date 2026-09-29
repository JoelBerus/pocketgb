# M4 · Respuesta a la auditoría de Codex (vuelta 1: RECHAZAR)

| ID | Respuesta |
|---|---|
| H1 (bloqueante) | **Corregido.** `flushSRAM` ya no depende del flanco "el juego guardó": toma la SRAM (con el RTC) y la compara con lo último escrito o cargado (`lastSaved`); si difiere, guarda. En pausa/background/salida es síncrono. Un fallo de escritura invalida `lastSaved` para forzar el reintento. Verificado en el simulador: evidencia §4. |
| H2 (bloqueante) | **Pendiente de Joel** (no se puede descartar: es el criterio 3). Requiere el iPhone: dmg-acid2, Pokémon Rojo hasta el menú y botones. El hito no se cierra hasta tenerlo. |
| H3 (bloqueante) | **Pendiente de Joel.** El build firmado necesita que el iPhone esté registrado en el Personal Team (conectarlo y pulsar Run una vez). Después se ejecuta el comando literal del criterio 1 y se pega la salida. Sin firma ya compila sin warnings (evidencia §1). |
| H4 (media) | **Corregido.** `CoreError.description` ya no interpola `self`: casos explícitos y `.unknown(code)` muestra el número. |
| H5 (baja) | **Corregido.** En vertical los controles son opacos (gris sólido 0.38, 0.55 pulsado, borde y letras blancos). |

# Vuelta 2 (RECHAZAR: H1 reabierto, H6 nuevo; H4 y H5 confirmados)

| ID | Respuesta |
|---|---|
| H1 (bloqueante) | **Corregido.** La red de seguridad de 60 s ya no depende de `gb_sram_dirty()`: cada 60 s `flushSRAM` toma la SRAM y la escribe si difiere de lo último encolado. El debounce de 1 s sigue para el flanco "el juego guardó". Verificado: evidencia §5, T1. |
| H6 (bloqueante) | **Corregido.** Se separa `lastQueued` (hilo de emulación, evita encolar lo mismo) de `confirmed` (bajo `control`, lo actualiza la cola de guardado solo si `save` tuvo éxito). El flush síncrono vacía primero la cola y compara con `confirmed`; si la última escritura asíncrona falló, reescribe antes de aparcar o terminar. Verificado con fallo inyectado (`-failAsyncSaves`, solo DEBUG): evidencia §5, T2. |
| H2, H3 (bloqueantes) | **Pendientes de Joel** (iPhone), sin cambios. |
| Nota `git diff --check` | Corregido: espacios finales de `Shaders.swift`. |

# Vuelta 3 (RECHAZAR: solo H2, H3 y H7; H1, H4, H5 y H6 confirmados como corregidos)

| ID | Respuesta |
|---|---|
| H3 (bloqueante) | **Corregido.** Joel registró su iPhone; el comando literal del criterio 1 da BUILD SUCCEEDED con firma (evidencia §1). |
| H7 (alta) | **Corregido.** `AppState.memoryWarning()` escucha `didReceiveMemoryWarningNotification` y pide `requestFlush()`: el hilo de emulación hace un flush síncrono en el siguiente frame y sigue jugando. Verificado: evidencia §6. |
| H2 (bloqueante) | **Parcial.** dmg-acid2 correcto en el iPhone (Joel, evidencia §7). Pokémon Rojo pendiente hasta que Joel vuelque su cartucho; se propone a Joel cerrar M4 con Rojo diferido a cuando tenga el volcado. |

# Vuelta 4 (RECHAZAR solo por H2; H3 y H7 confirmados como corregidos; "aparte de Rojo, listo para cerrarse")

| ID | Respuesta |
|---|---|
| H2 (bloqueante) | **Cumplido.** Joel probó Pokémon Rojo en su iPhone: llega al menú, los botones responden y el horizontal va bien. Volcado verificado (checksums OK) y la intro corre en el simulador (evidencia §7). |

**Cierre:** los 7 hallazgos de las 4 vueltas están corregidos (H1–H7) y los 3 criterios se cumplen. Joel aprobó cerrar M4 el 2026-09-29.
