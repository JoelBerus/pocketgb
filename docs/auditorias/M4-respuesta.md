# M4 · Respuesta a la auditoría de Codex (vuelta 1: RECHAZAR)

| ID | Respuesta |
|---|---|
| H1 (bloqueante) | **Corregido.** `flushSRAM` ya no depende del flanco "el juego guardó": toma la SRAM (con el RTC) y la compara con lo último escrito o cargado (`lastSaved`); si difiere, guarda. En pausa/background/salida es síncrono. Un fallo de escritura invalida `lastSaved` para forzar el reintento. Verificado en el simulador: evidencia §4. |
| H2 (bloqueante) | **Pendiente de Joel** (no se puede descartar: es el criterio 3). Requiere el iPhone: dmg-acid2, Pokémon Rojo hasta el menú y botones. El hito no se cierra hasta tenerlo. |
| H3 (bloqueante) | **Pendiente de Joel.** El build firmado necesita que el iPhone esté registrado en el Personal Team (conectarlo y pulsar Run una vez). Después se ejecuta el comando literal del criterio 1 y se pega la salida. Sin firma ya compila sin warnings (evidencia §1). |
| H4 (media) | **Corregido.** `CoreError.description` ya no interpola `self`: casos explícitos y `.unknown(code)` muestra el número. |
| H5 (baja) | **Corregido.** En vertical los controles son opacos (gris sólido 0.38, 0.55 pulsado, borde y letras blancos). |
