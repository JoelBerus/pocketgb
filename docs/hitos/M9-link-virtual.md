# M9 · Cable link virtual (intercambios Rojo ↔ Amarillo en el mismo iPhone)

☁️ Núcleo · 🍎 UI. Resuelve A10.

**Diseño:** dos instancias `gb` en el mismo hilo, ejecutadas en *lockstep* por bloques de ≤ 1 scanline (456 T-ciclos). La transferencia serie de reloj interno de una instancia desplaza un bit hacia la otra (`opts.serial_cb` + `gb_serial_receive`). Hay que manejar el caso de reloj externo (la otra instancia es el esclavo).

**UI:** pantalla dividida (dos juegos, uno arriba y otro abajo, controles con selector de a qué juego se envían), o bien alternar con un botón. El audio se toma solo del juego activo.

**Criterios de aceptación**
- [ ] Test headless: dos instancias con un ROM de prueba serie (homebrew) intercambian bytes correctamente.
- [ ] iPhone: intercambio completo de un Pokémon entre una partida Roja y una Amarilla; Kadabra evoluciona.
- [ ] Las dos SRAM se guardan con la ruta normal de M6 tras el intercambio.
