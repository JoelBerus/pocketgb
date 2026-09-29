# M9 · Cable link virtual (intercambios Rojo ↔ Amarillo en el mismo iPhone)

☁️ Núcleo · 🍎 UI. Resuelve A10.

**Diseño:** dos instancias `gb` en el mismo hilo.
- **Avance base por deadline absoluto** (sin deriva): `T += 456`; para cada instancia `i`, si `t_i < T` → `t_i += gb_run_cycles(g_i, T - t_i)`.
- **Causalidad en cada pulso:** el `serial_bit_cb` del maestro, **antes** de entregar el bit, avanza al par hasta el instante del pulso: `while (gb_cycle_count(peer) < gb_cycle_count(master)) gb_run_cycles(peer, gb_cycle_count(master) - gb_cycle_count(peer));`. Después llama a `gb_serial_clock_external(peer, bit_out)`. Así, un `SC=0x80` que el esclavo escriba entre el inicio del bloque y el flanco ya está aplicado.
- **Reentrada:** mientras se avanza al par dentro del callback, un `serial_bit_cb` del par (ambos con reloj interno, caso indefinido en hardware) devuelve `1` sin tocar al maestro. Un flag del host lo marca.
- **Tests:** (a) deriva: instrucciones de 4 y 24 T-ciclos, tras 10⁶ vueltas `|t_a - t_b| ≤ 44`; (b) causalidad: el esclavo activa `SC=0x80` entre el inicio del bloque y el primer flanco del maestro, y el byte se transfiere completo; (c) sin esclavo activo el maestro recibe `0xFF`.

**UI:** pantalla dividida (dos juegos, uno arriba y otro abajo, controles con selector de a qué juego se envían), o bien alternar con un botón. El audio se toma solo del juego activo.

**Criterios de aceptación**
- [ ] Test headless: dos instancias con un ROM de prueba serie (homebrew) intercambian bytes correctamente.
- [ ] iPhone: intercambio completo de un Pokémon entre una partida Roja y una Amarilla; Kadabra evoluciona.
- [ ] Las dos SRAM se guardan con la ruta normal de M6 tras el intercambio.
