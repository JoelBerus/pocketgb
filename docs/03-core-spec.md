# 03 · Especificación del núcleo (`core/`)

Fuente canónica: **Pan Docs** (https://gbdev.io/pandocs/). Si este documento y Pan Docs discrepan, gana Pan Docs y se corrige este. Timing de instrucciones: tabla de opcodes de gbdev (https://gbdev.io/gb-opcodes/optables/) y *Game Boy: Complete Technical Reference* (Gekkio).

## Principios
- **Modelo de tiempo: la CPU manda.** Cada acceso a memoria de la CPU cuesta 1 M-ciclo (4 T-ciclos) y llama a `gb_tick(gb, 4)`. Esa función avanza timer, PPU, APU, DMA y serial. Así, las lecturas y escrituras ocurren en el ciclo correcto dentro de la instrucción, que es lo que piden `instr_timing` y `mem_timing`.
- **PPU por scanline** (no FIFO) en v1: la línea se renderiza al entrar en modo 0 (HBlank), con los registros vigentes en ese momento. Modo 3 = 172 dots + `SCX % 8` + 6 por objeto de la línea (penalización simplificada). Basta para dmg-acid2 y Pokémon; el tiempo fino de STAT/LCD de Mooneye (`ppu/*` marcados `known-fail` en `suite.txt`) queda fuera de v1.
- **Doble velocidad CGB:** la CPU y el timer van a 2×; PPU, APU y RTC siguen en tiempo real. `gb_tick` recibe T-ciclos de CPU y convierte: en doble velocidad cada M-ciclo son 2 dots. `gb_cycle_count` y el límite de `gb_run_frame` cuentan tiempo real (70 224 por frame en las dos velocidades). Verificado con Blargg `interrupt_time` (solo-CGB).

## Arranque (sin boot ROM)
Estado post-boot (Pan Docs → *Power Up Sequence*):

| Reg | DMG (rev. ABC) | CGB nativo (`0x143 & 0x80`) | CGB en compatibilidad (ROM DMG) |
|---|---|---|---|
| A | `0x01` | `0x11` | `0x11` |
| F | `0xB0` si el checksum de cabecera ≠ 0; si no, `0x80` (Z=1, H y C según el checksum) | `0x80` | `0x80` |
| B | `0x00` | `0x00` | `Σ título[0x134..0x143] & 0xFF` si old licensee (`0x14B`) = `0x01`, o si = `0x33` y new licensee (`0x144–0x145`) = `"01"`. Si no, `0x00` |
| C | `0x13` | `0x00` | `0x00` |
| D / E | `0x00` / `0xD8` | `0xFF` / `0x56` | `0x00` / `0x08` |
| H / L | `0x01` / `0x4D` | `0x00` / `0x0D` | `0x991A` si B ∈ {`0x43`, `0x58`}; si no, `0x007C` |
| SP / PC | `0xFFFE` / `0x0100` | `0xFFFE` / `0x0100` | `0xFFFE` / `0x0100` |

Verificación: Mooneye `acceptance/boot_regs-dmgABC.gb` (requerido en M1) y `misc/boot_regs-cgb.gb` (M8; es un ROM DMG en CGB y espera `A=11 F=80 B=00 C=00 D=00 E=08 H=00 L=7C`). La nota "F según `INC B`" de Pan Docs aplica **solo** al AGB, no al CGB. Pokémon Rojo tiene licencia Nintendo (`0x33` + `"01"`), así que en compatibilidad **B depende del título**: hay que implementar la regla, no un valor fijo.

Los registros de E/S se inicializan con la tabla *Hardware registers* de la misma página, por modelo (p. ej. `LCDC=0x91`, `STAT=0x85`, `BGP=0xFC`, `IF=0xE1`, `NR52=0xF1`, `DIV` interno según el modelo). Los juegos detectan GBC por `A == 0x11`; Amarillo lo usa para activar color.

Contador interno del timer tras el arranque: `0xABCC` en DMG; `0x2674` en CGB con un ROM DMG (el único valor con el que pasa Mooneye `misc/boot_div-cgbABCDE`; con licencia Nintendo el arranque real tarda algo más). En CGB nativo no hay prueba que lo fije: se deja el de DMG. Paletas tras el arranque en CGB nativo: todo blanco (`0x7FFF`), también las de objetos (en el hardware quedan con basura). `boot_hwio-C` (valores exactos de E/S) queda como `known-fail`.

**Selección de modo:** si `opts.model == GB_MODEL_AUTO` (o un valor desconocido), se usa CGB cuando `rom[0x143] & 0x80`, y DMG en otro caso. `GB_MODEL_DMG` con `0x143 == 0xC0` → `GB_ERR_CGB_ONLY`. En CGB con un ROM DMG se activa el **modo compatibilidad** (el arranque real escribe `KEY0=0x04` y `OPRI=1`):
- Los registros exclusivos de CGB (`KEY1`, `VBK`, `HDMA1–5`, `RP`, `BCPS/BCPD`, `OCPS/OCPD`, `OPRI`, `SVBK`, `FF74`) leen `0xFF` e ignoran escrituras. `FF72`, `FF73` y `FF75` siguen accesibles. No hay cambio de velocidad.
- El fondo usa la paleta de color BG 0 y los objetos OBJ 0/1 según su bit 4; `BGP/OBP0/OBP1` indexan esas paletas. Prioridad de objetos DMG (por X), sin atributos de fondo, tiles del banco 0.
- **Paleta de compatibilidad** (Pan Docs → *Compatibility palettes*): si la licencia es Nintendo (`0x14B == 0x01`, o `0x33` con `"01"` en `0x144–0x145`), se suma el título completo (`0x134–0x143`) y se busca en la tabla de checksums; los checksums repetidos se desempatan con la 4.ª letra del título. Sin licencia Nintendo o sin coincidencia, combinación 0 (la que usan las referencias de dmg-acid2 en CGB). Cada combinación da 3 paletas (OBJ0, OBJ1, BG) de una tabla de 30. Tablas transcritas de SameBoy v1.0.3 `BootROMs/cgb_boot.asm` (MIT, citado en `cgb.c`). No se emula la copia del mapa del logo que el arranque hace para los checksums `0x43`/`0x58` (dos juegos), aunque B, H y L sí siguen esa regla.
- **Selección manual:** `opts.compat_palette` (o `gb_set_compat_palette` en caliente; al cargar un estado en compatibilidad se reaplica la selección actual, no la del archivo): `0` = automática; `1..12` = las 12 combinaciones de botones del arranque real (→, ←, ↑, ↓, →+A, ←+A, ↑+A, ↓+A, →+B, ←+B, ↑+B, ↓+B). Fuera de rango = automática.

## Cabecera del cartucho y validación (entrada no confiable)
| Campo | Offset | Validación |
|---|---|---|
| Logo | `0x104–0x133` | Solo se informa, no se exige (homebrew). |
| Título | `0x134–0x143` | Longitud efectiva: 11 bytes si `0x143` es flag CGB (`0x80`/`0xC0`) y `0x13F–0x142` son 4 caracteres ASCII mayúsculas o dígitos (código de fabricante); 15 bytes si `0x143` es flag CGB; 16 en otro caso. Se corta en el primer `0x00`. Lo no imprimible se sustituye por `?`. **Nunca** se incluye `0x143` cuando es flag. |
| CGB flag | `0x143` | `0x80` compatible, `0xC0` solo CGB. |
| Tipo | `0x147` | Soportados: `0x00` (ROM), `0x01–0x03` (MBC1), `0x0F–0x13` (MBC3, RTC en `0x0F/0x10`), `0x19–0x1E` (MBC5). Otro valor → `GB_ERR_UNSUPPORTED_MBC`. |
| Tamaño ROM | `0x148` | `32 KiB << n` para n ≤ 8. `0x52/0x53/0x54` (72/80/96 bancos) están documentados, pero no se conoce ningún ROM que los use: **fuera de alcance**, devuelven `GB_ERR_BAD_ROM_SIZE_CODE` y tienen un test de rechazo. Debe ser ≤ tamaño real del archivo; si el archivo es más grande, se ignora el excedente. Si es más pequeño → `GB_ERR_ROM_TRUNCATED`. |
| Tamaño RAM | `0x149` | 0→0, 2→8 KiB, 3→32 KiB, 4→128 KiB, 5→64 KiB (1 = sin uso, se trata como 0). Se ignora en MBC2/sin RAM. |
| Checksum cabecera | `0x14D` | `x=0; for i in 0x134..0x14C: x = x - rom[i] - 1`. Si no coincide, **se carga igual**, pero se expone en `gb_rom_info` para que la UI avise. |
| Checksum global | `0x14E–0x14F` | Suma de todos los bytes excepto esos dos. Solo informativo (A15). |

Longitud mínima aceptada: `0x150` bytes. Máxima: 8 MiB.

**Huella del ROM:** SHA-256 de **todos** los bytes validados del ROM (implementación propia en `core/src/sha256.c`, sin dependencias, con los vectores de prueba de FIPS 180-4). Se expone completa (32 bytes). Los save states guardan los 32 bytes y los nombres de archivo usan los primeros 32 caracteres hex (128 bits).

## Mapa de memoria
| Rango | Qué | Notas |
|---|---|---|
| `0000–3FFF` | ROM banco 0 (MBC1 modo 1 puede remapear) | |
| `4000–7FFF` | ROM banco N | `N %= num_banks` **siempre** (bounds-check) |
| `8000–9FFF` | VRAM (CGB: 2 bancos, `VBK=FF4F`, lee `0xFE`\|banco) | Inaccesible a la CPU en modo 3 → lee `0xFF` |
| `A000–BFFF` | RAM externa / RTC | Si la RAM está deshabilitada o no existe → lee `0xFF`, ignora escrituras. `bank %= num_ram_banks` |
| `C000–DFFF` | WRAM (CGB: `D000` bancos 1–7 vía `SVBK=FF70`, 0→1; lee `0xF8`\|valor) | |
| `E000–FDFF` | Eco de `C000–DDFF` | |
| `FE00–FE9F` | OAM | Inaccesible en modos 2/3 y durante OAM DMA |
| `FEA0–FEFF` | No usable | Lee `0x00` (DMG) |
| `FF00–FF7F` | E/S | Bits no implementados leen 1 (máscaras de Pan Docs) |
| `FF80–FFFE` | HRAM | Durante la copia del OAM DMA la CPU no ve OAM (`FE00–FEFF`) ni el **bus del origen** del DMA. En DMG se distinguen VRAM y el bus externo compartido (ROM/SRAM/WRAM/eco); en CGB se distinguen tres buses: VRAM, cartucho (ROM/SRAM) y WRAM/eco. Los accesos bloqueados leen `0xFF` y las escrituras se ignoran; E/S (`FF00–FF7F`), HRAM e IE siguen accesibles. Pan Docs simplifica el DMG a "solo HRAM", pero las pruebas Mooneye verificadas en DMG real (`reti_timing`, `ret_timing`, `call_timing`, `oam_dma/reg_read`) ejecutan desde ROM durante un DMA con origen en VRAM y leen registros de E/S, por lo que se sigue el modelo por bus. |
| `FFFF` | IE | |

## CPU SM83
- Registros A F B C D E H L SP PC. Nibble bajo de F siempre 0 (`POP AF` lo enmascara).
- Los 256 opcodes base + 256 con prefijo `CB`, con los ciclos de la tabla de gbdev (incluidas las variantes con salto tomado o no).
- **Opcodes ilegales** `D3 DB DD E3 E4 EB EC ED F4 FC FD`: la CPU se bloquea (`gb->cpu.locked = true`). `gb_run_frame` sigue avanzando la PPU con pantalla fija y `gb_cpu_locked()` expone el estado para que la UI lo muestre.
- **Interrupciones:** `IE=FFFF`, `IF=FF0F`. Prioridad VBlank(0x40) > STAT(0x48) > Timer(0x50) > Serial(0x58) > Joypad(0x60). El despacho cuesta 5 M-ciclos: el primero es el fetch del opcode, que se descarta. **Muestreo:** en cada M-ciclo primero avanza el hardware y después la CPU accede al bus; `IE & IF` se comprueba al final del M-ciclo de fetch, así que una IRQ pedida en ese mismo M-ciclo ya se atiende. En HALT la CPU repite ese fetch sin avanzar PC: con IME=1 el despacho continúa con los 4 M-ciclos restantes; con IME=0 ejecuta el opcode ya leído, sin M-ciclo extra (verificado con Mooneye `rapid_toggle`, `di_timing-GS`, `halt_ime0_nointr_timing`, `halt_ime1_timing2-GS`). El vector se elige después de escribir el byte alto de PC (si esa escritura cambia IE, PC=0: `ie_push`). `EI` tiene efecto tras la instrucción siguiente; `DI` es inmediato; `RETI` = `RET` + `IME=1` inmediato.
- **HALT:** sale al haber `IE & IF & 0x1F` aunque `IME=0`. **Bug de HALT:** con `IME=0` y una interrupción pendiente, el siguiente byte se lee 2 veces.
- **STOP:** en CGB (nativo) con `KEY1` bit 0 → cambio de velocidad inmediato (`KEY1` bit 7 = velocidad actual, bit 0 se borra, DIV a 0). No se emula la pausa de ~2050 M-ciclos del hardware. Si no, se trata como HALT profundo hasta que se pulse un botón (suficiente para v1).

## Timer
- Contador interno de 16 bits que avanza 1 por T-ciclo; `DIV` = byte alto. Escribir en `DIV` pone el contador a 0.
- `TAC` bit 2 = habilitar; bits 1–0 → bit del contador observado: `00→9` (4096 Hz), `01→3` (262144 Hz), `10→5` (65536 Hz), `11→7` (16384 Hz).
- `TIMA` se incrementa en el **flanco de bajada** de `(bit_observado AND habilitar)`. Esto incluye los glitches al escribir `DIV` o `TAC`.
- Desbordamiento: `TIMA` queda en 0 durante 1 M-ciclo; después se carga `TMA` y se pide la interrupción Timer. Una escritura en `TIMA` en ese M-ciclo cancela la recarga. En el M-ciclo de la recarga, escribir `TIMA` se ignora y escribir `TMA` también llega a `TIMA`.
- Valor post-boot del contador en DMG ABC: `0xABCC` (DIV=`0xAB`).
- El reloj interno de la serie (8192 Hz) es el flanco de bajada del bit 8 del mismo contador.

## PPU (DMG, luego CGB)
- 456 dots por línea, 154 líneas (0–143 visibles, 144–153 VBlank). Modo 2 (80 dots) → 3 (≈172+) → 0 → … ; modo 1 en VBlank. En la línea 144, el modo 1 y la IRQ de VBlank llegan 4 dots después del cambio de LY. El latch de WY se evalúa al comienzo de cada línea visible (LY == WY) y se reinicia en la línea 0.
- Registros: `LCDC FF40`, `STAT FF41`, `SCY/SCX FF42/43`, `LY FF44` (solo lectura), `LYC FF45`, `DMA FF46`, `BGP FF47`, `OBP0/1 FF48/49`, `WY/WX FF4A/4B`.
- **Línea STAT:** OR de (modo0 & bit3) | (modo1 & bit4) | (modo2 & bit5) | (LY==LYC & bit6). La interrupción se pide solo en el **flanco de subida** de esa OR ("STAT blocking").
- **Render de línea:** fondo (SCX/SCY con wrap), ventana (contador de línea interno propio, que solo avanza si la ventana se dibujó en esa línea, `WX-7`), sprites (máx. 10 por línea en orden OAM; prioridad DMG = menor X y luego menor índice OAM; 8×16 ignora el bit 0 del tile; bit de prioridad BG sobre OBJ contra el color 0 del fondo).
- **LCD apagado** (`LCDC` bit 7 = 0): `LY=0`, modo 0, el framebuffer se pone blanco y el siguiente encendido empieza en la línea 0.
- **OAM DMA:** 160 M-ciclos (+1 de arranque), copia `XX00–XX9F` → OAM, 1 byte por M-ciclo. Origen `≥ 0xE0` se lee de WRAM (`XX - 0x20`). Accesos de la CPU según la fila HRAM del mapa de memoria.
- **Paleta DMG → RGBA:** 4 tonos configurables por el frontend (`opts.dmg_palette`). Por defecto, gris neutro `#FFFFFF #AAAAAA #555555 #000000`.
- **CGB:** atributos de tile en VRAM banco 1 (paleta, banco, flip X/Y, prioridad), `BCPS/BCPD FF68/69`, `OCPS/OCPD FF6A/6B` (autoincremento, que avanza aunque la escritura caiga en modo 3 y se ignore; leer BCPD/OCPD en modo 3 da `0xFF`), prioridad de sprites por índice OAM salvo con `OPRI` bit 0 = 1, HDMA general y de HBlank (`FF51–FF55`). Color RGB555 → RGBA8888 con `c8 = (c5 << 3) | (c5 >> 2)`; la corrección de color del LCD es opcional (la hará el frontend si se quiere). Las paletas se cachean en RGBA al escribirlas.
- **Prioridad BG/OBJ en CGB:** con `LCDC` bit 0 = 0 los objetos van siempre encima (el fondo se sigue dibujando); si no, el fondo con color ≠ 0 tapa al objeto cuando el objeto (atributo bit 7) **o** el tile de fondo (atributo bit 7) piden prioridad. Objetos: paleta en bits 2–0, banco del tile en el bit 3. Verificado con cgb-acid2.
- **HDMA:** origen `FF51/52` (4 bits bajos ignorados; `8000–9FFF` lee `0xFF`, `E000–FFFF` se lee como `A000–BFFF`), destino `FF53/54` dentro de VRAM (banco de `VBK`). `FF55` bit 7 = 0: general, todo de golpe con la CPU parada 8 M-ciclos por bloque de 16 bytes (16 en doble velocidad); bit 7 = 1: de HBlank, un bloque al entrar en modo 0 de cada línea visible (con la misma parada). Escribir bit 7 = 0 durante uno de HBlank lo detiene. Con la CPU en HALT o STOP el de HBlank no avanza (sigue al despertar). Si el destino pasa de `9FF0`, la transferencia termina ahí (no da la vuelta). Lectura: bit 7 = 0 si está activo, bits 6–0 = bloques restantes − 1; `0xFF` al terminar.
- **Serie en CGB:** `SC` bit 1 = reloj rápido (flanco del bit 3 del contador, 262 144 Hz). `SC` lee `0x7C`|bits.
- **Registros sin función:** `FF72`, `FF73` (lectura/escritura), `FF74` (solo CGB nativo), `FF75` (bits 6–4). `FF76/FF77` (PCM12/PCM34) leen 0 (no se emula la salida digital). Verificado con Mooneye `misc/bits/unused_hwio-C`.

## Joypad
`FF00`: bits 5/4 seleccionan botones o cruceta (activo en 0); bits 3–0 = estado (0 = pulsado). Se pide la interrupción Joypad al pasar cualquier bit seleccionado de 1 a 0. **El núcleo no filtra direcciones opuestas.** Lo hace el frontend (el D-pad por ángulo nunca las genera).

## Serial
`SB FF01`, `SC FF02`. El registro se desplaza **bit a bit** (MSB primero).
- **Reloj interno (maestro):** cada 512 T-ciclos (8192 Hz; CGB rápido: 16 T-ciclos) desplaza un bit. Llama a `opts.serial_bit_cb(user, bit_out)`, que devuelve el bit entrante. Sin callback, entra `1` (cable desconectado ⇒ recibe `0xFF`). Tras 8 bits: `SC.7 = 0` e interrupción Serial.
- **Reloj externo (esclavo):** no avanza solo; espera indefinidamente. El otro extremo llama a `gb_serial_clock_external(gb, bit_in)`, que desplaza un bit y devuelve el bit saliente; tras 8 bits, igual que arriba.
- `opts.serial_byte_cb(user, byte)`: notificación al completar un byte (lo usan las pruebas Blargg para leer la salida). No sustituye al callback por bit.
- Para M9, las dos instancias avanzan con `gb_run_cycles` en bloques ≤ 456 T-ciclos. El callback por bit del maestro llama a `gb_serial_clock_external` del esclavo.

### Cable link virtual (M9, `link.c`)
Helper para que la app conecte dos instancias en el mismo hilo sin programar el lockstep ella misma:
```c
gb_link *l = gb_link_create();          // única reserva (contexto + 2 framebuffers de respaldo)
gb_link_attach(l, rojo, amarillo);      // tras gb_load_rom; cualquiera puede ser NULL (cable suelto)
gb_link_run_frame(l);                   // = gb_link_run_cycles(l, GB_CYCLES_PER_FRAME)
gb_link_framebuffer(l, 0 | 1);          // último frame completo de cada lado, sin cortes
gb_link_detach(l); gb_link_destroy(l);  // antes de gb_destroy de las instancias
```
- **Tiempo del cable** `T`: T-ciclos de tiempo real desde `gb_link_attach`. Cada instancia guarda un desplazamiento (`local_i = gb_cycle_count(g_i) + offset_i`), así que pueden llevar tiempos distintos al conectarse.
- **Deadline absoluto:** `T += GB_LINK_BLOCK_CYCLES` (456, una línea); cada instancia con `local_i < T` ejecuta `gb_run_cycles(g_i, T - local_i)`. El exceso de la última instrucción no se acumula. Tras cada bloque, `T ≤ local_i` y `|local_a - local_b| ≤ 44` (medido: 20 con NOP frente a CALL tras 10⁶ bloques).
- **Causalidad:** el `serial_bit_cb` que instala el cable, en cada pulso del maestro y **antes** de entregar el bit, adelanta al otro extremo hasta el instante del pulso y después llama a `gb_serial_clock_external(par, bit)`. Un `SC=0x80` que el esclavo escriba entre el inicio del bloque y el flanco ya cuenta, y uno escrito después no se adelanta. Para que el par esté detrás del maestro, en cada bloque corre primero el lado con una transferencia de reloj interno activa (`SC & 0x81 == 0x81`) y, si ninguno la tiene, el lado con el reloj interno seleccionado (`SC` bit 0; Pokémon lo deja a 1 entre bytes). Límite conocido: un maestro que activa `SC=0x81` a mitad de un bloque en el que el par ya corrió puede encontrárselo hasta 456 T-ciclos por delante (no se puede rebobinar); el esclavo, que espera con `SC=0x80`, sigue recibiendo el byte entero. Si el par estuviera más de 4 bloques por detrás (relojes desalineados), no se le adelanta dentro del callback.
- **Reentrada:** mientras se adelanta al par dentro de un pulso, un pulso del par (los dos con reloj interno, indefinido en el hardware) recibe `1` sin tocar al maestro. Es defensa en profundidad: sin el flag tampoco habría recursión, porque el par va por detrás (no adelantaría a nadie) y `gb_serial_clock_external` sobre un maestro con reloj interno ya devuelve `1`. Sin par o sin transferencia externa activa, el maestro recibe `0xFF`.
- **Un solo cable por instancia:** `gb_link_attach` devuelve `bool`; una instancia que ya está en otro cable (o `b == a`) deja su lado vacío y devuelve `false`. La app debe tener un único dueño del cable que llame a `gb_link_detach` antes de `gb_destroy`.
- **Callbacks del llamador:** el cable sustituye `serial_bit_cb` y `serial_user` de cada instancia; el `serial_byte_cb` original se sigue llamando con su `user`, y `gb_link_detach` lo devuelve todo como estaba. Tras `gb_load_rom` o `gb_state_load` en una instancia conectada, el siguiente avance reinstala los callbacks y realinea su reloj con `T` (si queda fuera de `[T, T + 912]`), sin ráfagas largas.
- **Framebuffers:** `gb_link_run_frame` avanza tiempo fijo y puede terminar a mitad de pantalla. El cable copia el framebuffer de cada lado al entrar en VBlank (la PPU no lo toca hasta la línea 0, 4560 T-ciclos después), y con el LCD apagado copia la pantalla actual al final del avance. La app muestra `gb_link_framebuffer`, no `gb_framebuffer`.
- **Audio:** cada instancia llena su propio anillo; la app lee solo el del juego activo (el del otro se descarta al llenarse, contador `dropped`).
- Sin estado global ni estático mutable; el avance no reserva memoria. `gb_link_run_cycles` no es reentrante (llamarlo desde un callback devuelve 0).

## MBC
| MBC | Registros | Detalles que suelen fallar |
|---|---|---|
| MBC1 | `0000–1FFF` RAM enable (`0x0A` en nibble bajo); `2000–3FFF` banco ROM 5 bits (0→1); `4000–5FFF` 2 bits (RAM o bits altos del ROM); `6000–7FFF` modo | El 0→1 aplica solo a los 5 bits bajos (bancos 0x20/0x40/0x60 inaccesibles en `4000`). En modo 1, `0000–3FFF` usa los bits altos. MBC1M (multicart) queda fuera de v1. |
| MBC3 | RAM enable (nibble bajo `0xA`, también habilita el RTC); `2000–3FFF` banco ROM 7 bits (0→1; con ROM > 2 MiB se usan los 8 bits, como el MBC30, que es lo que espera MBC3-Tester); `4000–5FFF` banco RAM 0–7 (`%` bancos) o registro RTC `08–0C` (otro valor → lee `0xFF`); `6000–7FFF` latch (escribir 0 y luego 1) | **Rojo = `0x13` (sin RTC).** El RTC se implementa igual (para Oro y Plata a futuro): ver §RTC. |
| MBC5 | RAM enable (nibble bajo `0xA`, como el hardware según Pan Docs); `2000–2FFF` 8 bits bajos del banco ROM (**0 es válido**); `3000–3FFF` bit 8; `4000–5FFF` banco RAM 0–15 | **Amarillo = `0x1B`.** Carros con rumble (`0x1C–0x1E`): el bit 3 de `4000` es el motor, no un bit de banco (hook de háptica opcional). |

"RAM dirty": cualquier escritura en la RAM externa pone `dirty = true`. Un **flanco de deshabilitar RAM** (escritura ≠ `0x0A`) mientras está sucia activa `gb_sram_dirty()`, que el frontend usa como señal de "el juego acaba de guardar".

Los offsets de banco se precalculan (y se acotan con `%`) en cada escritura a un registro del MBC, no en cada lectura.

### RTC (MBC3, `rtc.c`)
- Avanza con el reloj emulado (4 194 304 T-ciclos = 1 s), así que es determinista. Con el bit de halt (`DH` bit 6) se detienen el reloj y su sub-segundo.
- Anchos reales del chip: S y M de 6 bits, H de 5 y día de 9. Un valor fuera de rango sigue contando hasta desbordar su ancho, y ese desborde lo pone a 0 **sin** acarreo (p. ej. S=63 → 0 sin tocar M). Día 511 → 0 con el bit de acarreo (`DH` bit 7), que se queda puesto hasta que el juego lo borre.
- Escribir un registro aplica su máscara (`3F 3F 1F FF C1`) y actualiza también la copia latched. Escribir S reinicia el sub-segundo.
- Hora de pared: `opts.unix_time` en `gb_load_rom`; cada segundo emulado la adelanta. `gb_rtc_set_time(now)` suma al reloj los segundos transcurridos desde esa hora (si no está en halt). Solo se aceptan horas en `[0, 2^40)`; fuera de ese rango se tratan como "sin hora" y el reloj no avanza (sin desbordes al restar).
- Escribir un registro del RTC en un cartucho con batería cuenta como escritura en la RAM externa: el siguiente flanco de deshabilitar activa `gb_sram_dirty()`, así que un cambio de hora del juego también se guarda.
- `.sav`: RAM + bloque de 48 bytes (5×u32 vivos S M H DL DH, 5×u32 latched, u64 hora Unix; little-endian, compatible con VBA/BGB). `gb_sram_load` también acepta el bloque antiguo de 44 bytes (hora u32) y el `.sav` sin bloque RTC, para no rechazar partidas de otros emuladores. Al cargarlo, el reloj avanza lo transcurrido desde la hora guardada.
- Verificación: rtc3test (los 3 subtests) y MBC3-Tester idénticos a sus capturas.


## APU
- Canales: 1 (pulso + sweep), 2 (pulso), 3 (onda, `FF30–FF3F`), 4 (ruido, LFSR 15/7 bits; con shift 14–15 en `NR43` el LFSR no recibe relojes). Registros `NR10–NR52` (`FF10–FF26`) con las máscaras de lectura de Pan Docs.
- **Frame sequencer** a 512 Hz, disparado por el flanco de bajada del bit 12 del contador DIV (bit 13 en doble velocidad). Longitud a 256 Hz (pasos 0, 2, 4, 6), sweep a 128 Hz (pasos 2, 6), envolvente a 64 Hz (paso 7). Al encender (`NR52` bit 7), el siguiente paso es el 0.
- **Avance sincronizado por "catch-up" (A9):** `gb_tick` solo acumula T-ciclos pendientes y `apu_sync` los procesa de evento en evento (paso de un generador o muestra de salida) antes de **cada lectura o escritura** de un registro de sonido, de cada paso del frame sequencer, de `gb_audio_read` y al final de `gb_run_frame`/`gb_run_cycles`. Cada escritura se oye en su ciclo exacto sin pagar el APU en cada M-ciclo. Coste: +7 % de instrucciones con un ROM sin sonido (frente a +31 % procesando M-ciclo a M-ciclo); el coste crece con la frecuencia de los canales, y el peor caso que puede montar un ROM (4 canales a la frecuencia máxima) baja a ~19–21× tiempo real en Linux. Hay que medirlo en el iPhone (parte 🍎 de M5). Un canal activo tiene siempre el temporizador > 0 (los save states lo validan).
- **Detalles verificados con Blargg `dmg_sound` 01–08 y 11:** habilitar la longitud en la primera mitad del periodo (el siguiente paso no cuenta longitud) la cuenta una vez extra, y si llega a 0 sin disparo apaga el canal; un disparo con longitud 0 recarga el máximo (64/256), o el máximo − 1 en esa primera mitad; DAC apagado (`NRx2 & 0xF8 == 0`, `NR30` bit 7) apaga el canal; sweep: sombra, periodo 0 = 8, comprobación de desborde al disparar si shift ≠ 0, y quitar el modo negativo tras usarlo apaga el canal 1. `09/10/12` (acceso a la wave RAM con el canal 3 sonando) quedan como `known-fail`.
- **Mezcla y salida:** DAC por canal (digital 0..15 → −15..15; DAC apagado → 0) → `NR51` (paneo) → `NR50` (volumen × 1..8). Remuestreo a `opts.sample_rate` (acotado a [8 000, 192 000] Hz; 0 = sin audio) con un filtro de caja integrador (media de todos los ciclos del periodo de salida). Pasa-altos tipo condensador (carga 0.999958 por T-ciclo, como el hardware) para quitar la continua. Escala ×32: un salto de extremo a extremo tras el pasa-altos (±960) da ±30 720, sin saturar. Un band-limited (BLEP) queda como mejora.
- **Anillo de salida:** 8 192 frames estéreo `int16` dentro de la instancia (sin `malloc`). Si el frontend no lee, las muestras nuevas se descartan (contador `dropped`). No se guarda en los save states.
- Apagar con `NR52` bit 7 pone a 0 todos los registros excepto la wave RAM (y en DMG, los contadores de longitud, que además se pueden escribir apagado). En CGB los contadores de longitud también se borran y apagado no se puede escribir nada (Blargg `cgb_sound` 08 y 11).

## Save states
Formato binario little-endian:
```
"PGBS" | u32 version | u8 rom_sha256[32] | u32 model | secciones {u32 tag, u32 len, bytes} ... | u32 crc32
```
- Versión actual: 3 (M5 añadió `APU `; M8, VRAM de 16 KiB y WRAM de 32 KiB en `MEM ` y la sección `CGB `). Secciones en orden fijo: `CPU `, `MEM `, `TIMR`, `PPU `, `DMA `, `APU `, `SER `, `JOY `, `CART` (registros del MBC, RAM externa y RTC), `CGB ` (bancos, velocidad, paletas, HDMA, `OPRI`, `FF56`, `FF72–75`), `MISC` (contador de ciclos y framebuffer). `model`: 1 DMG, 2 CGB, 3 CGB en compatibilidad; debe coincidir con el de la instancia (si no, `STATE_ROM_MISMATCH`: es el mismo ROM cargado en otro modelo). CRC-32 IEEE (polinomio reflejado `0xEDB88320`) sobre todo lo anterior al CRC, con tabla constante.
- `gb_state_load` rechaza, en este orden: magic incorrecto (`STATE_MAGIC`), versión distinta de la soportada (`STATE_VERSION`), CRC inválido o archivo truncado (`STATE_CORRUPT`), huella de ROM distinta (`STATE_ROM_MISMATCH`), modelo distinto (`STATE_ROM_MISMATCH`), tag o longitud de sección incoherente, o cualquier campo fuera de rango (`STATE_CORRUPT`). **Nunca** confía en las longitudes del archivo.
- `gb_state_save` valida su propia salida con la misma pasada que la carga: nunca devuelve `GB_OK` con un estado que luego no cargaría (auditoría M8, H1).
- La carga hace una pasada que solo valida y, si todo es correcto, otra que escribe: un estado rechazado deja la instancia intacta. No reserva memoria.
- Además de los rangos por campo, se validan relaciones entre campos: DMA activo ⇒ `index < 160`; `dot` par (pasos de 4, o de 2 en doble velocidad) y `mode3_end ∈ [252, 319]`; hora Unix del RTC en `[0, 2^40)`; fuera de CGB nativo, bancos a 0 y sin doble velocidad ni HDMA; origen y destino del HDMA alineados a 16. El modo de la PPU y su próximo evento **no** se toman del archivo: se recalculan desde LY/dot/LCDC (un modo incoherente permitía escribir fuera del framebuffer; auditoría M3, H1). `render_line` además ignora LY ≥ 144.
- Toda la memoria de estado de `struct gb` se serializa campo por campo (sin `memcpy` de structs con punteros). Los offsets de banco no se guardan: se recalculan al cargar.

## Lector de progreso Pokémon (`pgb_progress_read`, N6-C)
Función pura de **solo lectura** (`core/include/pocketgb_progress.h`, `core/src/progress_pokemon.c`): con la cabecera del ROM y la partida (`.sav`) devuelve el nombre del jugador, las medallas, la Pokédex (capturados y vistos), el tiempo de juego y el dinero de los Pokémon oficiales. Alimenta el panel de progreso de N6 (ND5). No usa `gb *`: no necesita un ROM cargado ni una instancia, no hace I/O ni `malloc`, no tiene estado global y su salida solo depende de los argumentos.

```c
pgb_prog_game pgb_progress_identify(const uint8_t *rom_header, size_t header_len);
bool pgb_progress_read(const uint8_t *rom_header, size_t header_len,
                       const uint8_t *sram, size_t sram_len, pgb_progress *out);
```
- `rom_header`: al menos `PGB_PROG_HEADER_MIN` (0x150) bytes del ROM. `sram`: exactamente 32 KiB, o 32 KiB + un bloque RTC de 16, 44 o 48 bytes (se ignora); cualquier otra longitud → `false`.
- Devuelve `true` y rellena `*out` **solo** si el juego está soportado y la partida es coherente; si no, `false` y `*out` queda a cero (`game = PGB_PROG_NONE`). `pgb_progress_identify` mira solo la cabecera (sirve para elegir la plantilla de hitos sin tener la partida).
- `pgb_progress`: `game` (`GEN1` = Rojo/Azul/Amarillo, `GEN2_GS` = Oro/Plata, `GEN2_C` = Cristal), `player_name[32]` (UTF-8 con NUL; 32 y no 24 porque 10 glifos como ♂ ocupan 30 bytes), `badges_mask`/`badges_count` (1.ª gen: 8 bits; 2.ª gen: Johto en los bits 0–7 y Kanto en 8–15), `pokedex_owned`/`pokedex_seen` (solo cuentan los 151 / 251 bits válidos), `play_hours` (1.ª gen 0–255; 2.ª gen 0–999)/`play_minutes`/`play_seconds` y `money` (Pokédólares).

**Identificación (cabecera).** Se exige `rom[0x14A] == 0x01` (edición no japonesa: Rojo/Azul/Amarillo japoneses comparten título con los internacionales pero guardan en otra disposición) y el título de `0x134`, sensible a mayúsculas:

| Título | Juego | Notas |
|---|---|---|
| `POKEMON RED`, `POKEMON BLUE`, `POKEMON YELLOW` + `0x00` | `GEN1` | Los pret los compilan con `rgbfix -t "POKEMON RED"` etc. y `-j` (destino 1). Las ediciones ES/FR/DE/IT usan estos mismos títulos (según el encargo y Data Crystal; **no se probaron ROMs reales**). |
| `POKEMON_GLD`, `POKEMON_SLV` | `GEN2_GS` | `0x13F–0x142` es el código del juego (`AAUE`…), no se mira. |
| `PM_CRYSTAL` + `0x00` | `GEN2_C` | |

Japonés y coreano → `false`. Los hacks (Prism, Epic Gold…) solo dan datos si su título coincide **y** conservan la disposición oficial con el checksum correcto; si no, `false`.

**Validación de la partida** (todo debe cumplirse; solo se lee la copia principal, no la de respaldo que usa el juego si la principal está corrupta):
- 1.ª gen: `0xFF − suma de 8 bits` (`CalcCheckSum`: complemento de la suma) de `[0x2598, 0x3523)` igual al byte de `0x3523`.
- 2.ª gen: `sCheckValue1` (`0x2008`) = 99 y `sCheckValue2` = 127 (`CheckPrimarySaveFile`), y suma de 16 bits de `[0x2009, fin)` igual al `u16` little-endian guardado en la posición del checksum (`VerifyChecksum`).
- Nombre con terminador `0x50` dentro de sus 11 bytes; en la 1.ª gen, dinero BCD con todos los nibbles ≤ 9; minutos y segundos < 60 (el contador del juego nunca los pasa).

**Posiciones en el `.sav`** (SRAM banco 1 = archivo + `0x2000`; versiones internacionales). Cada una se calculó evaluando `ram/sram.asm`, `ram/wram.asm` y `constants/*.asm` de pret con `docs/auditorias/N6-C-verificar-offsets.py` (66 comprobaciones, 0 diferencias frente a las constantes de `progress_pokemon.c`) y se contrastó con las direcciones internacionales de Data Crystal («RAM map» de Red/Blue, Gold/Silver y Crystal) y con PKHeX (`Gen12/SAV1Offsets.cs`, `SAV2Offsets.cs`; solo los números: PKHeX es GPLv3).

| Dato | Rojo/Azul/Amarillo | Oro/Plata | Cristal | Etiqueta de pret |
|---|---|---|---|---|
| Rango del checksum | `0x2598..0x3522` | `0x2009..0x2D68` | `0x2009..0x2B82` | `sGameData`…`sGameDataEnd` |
| Checksum | `0x3523` (1 B) | `0x2D69` (2 B LE) | `0x2D0D` (2 B LE) | `sMainDataCheckSum` / `sChecksum` |
| Bytes de validación | — | `0x2008`, `0x2D6B` | `0x2008`, `0x2D0F` | `sCheckValue1/2` |
| Nombre (11 B) | `0x2598` | `0x200B` | `0x200B` | `sPlayerName` / `wPlayerName` |
| Pokédex capturados | `0x25A3` (19 B, 151 bits) | `0x2A4C` (32 B, 251 bits) | `0x2A27` | `wPokedexOwned` / `wPokedexCaught` |
| Pokédex vistos | `0x25B6` | `0x2A6C` | `0x2A47` | `wPokedexSeen` |
| Dinero | `0x25F3` (3 B BCD) | `0x23DB` (3 B big-endian) | `0x23DC` | `wPlayerMoney` / `wMoney` |
| Medallas | `0x2602` (1 B) | `0x23E4` Johto, `0x23E5` Kanto | `0x23E5`, `0x23E6` | `wObtainedBadges` / `wJohtoBadges`, `wKantoBadges` |
| Tiempo | `0x2CED`: horas, «máximo» (0xFF al llegar a 255 h), minutos, segundos, fotogramas (1 B c/u) | `0x2053`: horas (2 B big-endian), minutos, segundos, fotogramas | `0x2052` | `wPlayTimeHours…` / `wGameTimeHours…` |

En Cristal el checksum no está pegado a los datos (`sGameDataEnd` = `0x2B83`; `ds $18a` de relleno hasta `0x2D0D`); en Oro/Plata sí. Pokédex: el bit *n−1* es el Pokémon *n* (bit 0 del primer byte = n.º 1); los bits sobrantes (151 en la 1.ª gen; 251–255 en la 2.ª) no cuentan.

**Texto.** Tabla de caracteres internacional de pret (`constants/charmap.asm`), subconjunto a UTF-8: `0x80–0x99` A–Z, `0xA0–0xB9` a–z, `0xF6–0xFF` 0–9, `0x7F` espacio, `0x9A–0x9F` `( ) : ; [ ]`, `0xE0` `'`, `0xE3` `-`, `0xE6` `?`, `0xE7` `!`, `0xE8`/`0xF2` `.`, `0xEF` ♂, `0xF5` ♀, `0xF0` ¥, `0xF1` ×, `0xF3` `/`, `0xF4` `,`; solo 1.ª gen: `0xBA` é; solo 2.ª gen: `0xC0–0xC5` Ä Ö Ü ä ö ü, `0xE9` `&`, `0xEA` é. Cualquier otro valor (katakana, control, marcos) → `?`. Los acentos propios de las ediciones FR/DE/ES/IT que pret no documenta salen como `?`.

**Fuentes** (hechos: posiciones, tamaños y fórmulas; el código es propio y no copia nada de pret —sin licencia declarada— ni de PKHeX —GPLv3—): pret/pokered, pokeyellow, pokegold y pokecrystal (`ram/wram.asm`, `ram/sram.asm`, `engine/menus/save.asm`, `engine/play_time.asm`, `home/game_time.asm`, `constants/charmap.asm`, `Makefile` para los títulos); Data Crystal; PKHeX. Evidencia y lo no verificado (nunca se probó con una partida real): [auditorias/N6-C-evidencia.md](auditorias/N6-C-evidencia.md).

**Pruebas:** `core/tests/unit_progress.c` (partidas sintéticas construidas byte a byte: valores, límites exactos del checksum, rechazos, glifos, tamaños y copias de tamaño exacto para ASan; nunca `.sav` reales), `core/fuzz/fuzz_progress.c` (libFuzzer; ver [06-testing](06-testing.md)).

## Seguridad (resumen de reglas verificables)
1. Ningún índice derivado de datos del ROM o de registros llega a un array sin `%` o `min()` contra su tamaño real.
2. `gb_load_rom` y `gb_state_load` están cubiertos por fuzzers ([06](06-testing.md)).
3. Se compila con `-Werror`, y los tests también con `-fsanitize=address,undefined`.
4. Sin `malloc` después de `gb_load_rom` (todo se reserva ahí). Si una reserva falla: `GB_ERR_OUT_OF_MEMORY`, y la instancia queda en estado válido **sin ROM** (se liberan las reservas parciales; cualquier llamada posterior de ejecución no hace nada).
5. Un `gb_load_rom` sobre una instancia que ya tenía un ROM libera primero el anterior (sin fugas, cubierto por ASan).
