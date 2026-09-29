# M9 · Cable link virtual (intercambios Rojo ↔ Amarillo en el mismo iPhone)

☁️ Núcleo · 🍎 UI. Resuelve A10.

**Diseño:** dos instancias `gb` en el mismo hilo, en *lockstep* con `gb_run_cycles(g, 456)` alternado. La instancia con reloj interno llama por cada bit a su `serial_bit_cb`, que invoca `gb_serial_clock_external(peer, bit_out)` y devuelve el bit del esclavo. Si ninguna tiene reloj interno, no se transfiere nada (igual que el hardware). Ver [03](../03-core-spec.md) §Serial.

**UI:** pantalla dividida (dos juegos, uno arriba y otro abajo, controles con selector de a qué juego se envían), o bien alternar con un botón. El audio se toma solo del juego activo.

**Criterios de aceptación**
- [ ] Test headless: dos instancias con un ROM de prueba serie (homebrew) intercambian bytes correctamente.
- [ ] iPhone: intercambio completo de un Pokémon entre una partida Roja y una Amarilla; Kadabra evoluciona.
- [ ] Las dos SRAM se guardan con la ruta normal de M6 tras el intercambio.
