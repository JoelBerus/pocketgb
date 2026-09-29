# 03 · Especificación del núcleo (`core/`)

Fuente canónica: **Pan Docs** (https://gbdev.io/pandocs/). Si este documento y Pan Docs discrepan, gana Pan Docs y se corrige este. Timing de instrucciones: tabla de opcodes de gbdev (https://gbdev.io/gb-opcodes/optables/) y *Game Boy: Complete Technical Reference* (Gekkio).

## Principios
- **Modelo de tiempo: la CPU manda.** Cada acceso a memoria de la CPU cuesta 1 M-ciclo (4 T-ciclos) y llama a `gb_tick(gb, 4)`. Esa función avanza timer, PPU, APU, DMA y serial. Así, las lecturas y escrituras ocurren en el ciclo correcto dentro de la instrucción, que es lo que piden `instr_timing` y `mem_timing`.
- **PPU por scanline** (no FIFO) en v1: la línea se renderiza al entrar en modo 0 (HBlank). La duración del modo 3 es fija en 172 dots + penalización de sprites simplificada. Basta para dmg-acid2 y Pokémon.
- **Doble velocidad CGB:** la CPU y el timer van a 2×; PPU y APU siguen en tiempo real. `gb_tick` recibe T-ciclos de CPU y convierte.

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

**Selección de modo:** si `opts.model == GB_MODEL_AUTO`, se usa CGB cuando `rom[0x143] & 0x80`, y DMG en otro caso. En CGB con un ROM DMG se activa el **modo compatibilidad**: se aplica la paleta de compatibilidad por checksum del título (tabla de Pan Docs → *Compatibility palettes*).

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
| `8000–9FFF` | VRAM (CGB: 2 bancos, `VBK=FF4F`) | Inaccesible a la CPU en modo 3 → lee `0xFF` |
| `A000–BFFF` | RAM externa / RTC | Si la RAM está deshabilitada o no existe → lee `0xFF`, ignora escrituras. `bank %= num_ram_banks` |
| `C000–DFFF` | WRAM (CGB: `D000` bancos 1–7 vía `SVBK=FF70`, 0→1) | |
| `E000–FDFF` | Eco de `C000–DDFF` | |
| `FE00–FE9F` | OAM | Inaccesible en modos 2/3 y durante OAM DMA |
| `FEA0–FEFF` | No usable | Lee `0x00` (DMG) |
| `FF00–FF7F` | E/S | Bits no implementados leen 1 (máscaras de Pan Docs) |
| `FF80–FFFE` | HRAM | **DMG:** durante OAM DMA la CPU solo accede a HRAM (el resto lee `0xFF` y las escrituras se ignoran). **CGB:** conflicto de bus solo en el bus del origen del DMA (externo: ROM/SRAM/WRAM, o VRAM); ver Pan Docs *OAM DMA bus conflicts* |
| `FFFF` | IE | |

## CPU SM83
- Registros A F B C D E H L SP PC. Nibble bajo de F siempre 0 (`POP AF` lo enmascara).
- Los 256 opcodes base + 256 con prefijo `CB`, con los ciclos de la tabla de gbdev (incluidas las variantes con salto tomado o no).
- **Opcodes ilegales** `D3 DB DD E3 E4 EB EC ED F4 FC FD`: la CPU se bloquea (`gb->cpu.locked = true`). `gb_run_frame` sigue avanzando la PPU con pantalla fija y `gb_rom_info` expone el estado para que la UI lo muestre.
- **Interrupciones:** `IE=FFFF`, `IF=FF0F`. Prioridad VBlank(0x40) > STAT(0x48) > Timer(0x50) > Serial(0x58) > Joypad(0x60). El despacho cuesta 5 M-ciclos. `EI` tiene efecto tras la instrucción siguiente; `DI` es inmediato; `RETI` = `RET` + `IME=1` inmediato.
- **HALT:** sale al haber `IE & IF & 0x1F` aunque `IME=0`. **Bug de HALT:** con `IME=0` y una interrupción pendiente, el siguiente byte se lee 2 veces.
- **STOP:** en CGB con `KEY1` bit 0 → cambio de velocidad. Si no, se trata como HALT profundo hasta que se pulse un botón (suficiente para v1).

## Timer
- Contador interno de 16 bits que avanza 1 por T-ciclo; `DIV` = byte alto. Escribir en `DIV` pone el contador a 0.
- `TAC` bit 2 = habilitar; bits 1–0 → bit del contador observado: `00→9` (4096 Hz), `01→3` (262144 Hz), `10→5` (65536 Hz), `11→7` (16384 Hz).
- `TIMA` se incrementa en el **flanco de bajada** de `(bit_observado AND habilitar)`. Esto incluye los glitches al escribir `DIV` o `TAC`.
- Desbordamiento: `TIMA` queda en 0 durante 1 M-ciclo; después se carga `TMA` y se pide la interrupción Timer. Una escritura en `TIMA` en ese M-ciclo cancela la recarga.

## PPU (DMG, luego CGB)
- 456 dots por línea, 154 líneas (0–143 visibles, 144–153 VBlank). Modo 2 (80 dots) → 3 (≈172+) → 0 → … ; modo 1 en VBlank.
- Registros: `LCDC FF40`, `STAT FF41`, `SCY/SCX FF42/43`, `LY FF44` (solo lectura), `LYC FF45`, `DMA FF46`, `BGP FF47`, `OBP0/1 FF48/49`, `WY/WX FF4A/4B`.
- **Línea STAT:** OR de (modo0 & bit3) | (modo1 & bit4) | (modo2 & bit5) | (LY==LYC & bit6). La interrupción se pide solo en el **flanco de subida** de esa OR ("STAT blocking").
- **Render de línea:** fondo (SCX/SCY con wrap), ventana (contador de línea interno propio, que solo avanza si la ventana se dibujó en esa línea, `WX-7`), sprites (máx. 10 por línea en orden OAM; prioridad DMG = menor X y luego menor índice OAM; 8×16 ignora el bit 0 del tile; bit de prioridad BG sobre OBJ contra el color 0 del fondo).
- **LCD apagado** (`LCDC` bit 7 = 0): `LY=0`, modo 0, el framebuffer se pone blanco y el siguiente encendido empieza en la línea 0.
- **OAM DMA:** 160 M-ciclos (+1 de arranque), copia `XX00–XX9F` → OAM, 1 byte por M-ciclo. Origen `≥ 0xE0` se lee de WRAM (`XX - 0x20`). Accesos de la CPU según la fila HRAM del mapa de memoria.
- **Paleta DMG → RGBA:** 4 tonos configurables por el frontend (`opts.dmg_palette`). Por defecto, gris neutro `#FFFFFF #AAAAAA #555555 #000000`.
- **CGB:** atributos de tile en VRAM banco 1 (paleta, banco, flip X/Y, prioridad), `BCPS/BCPD FF68/69`, `OCPS/OCPD FF6A/6B` (autoincremento), prioridad de sprites por índice OAM, HDMA general y de HBlank (`FF51–FF55`). Color RGB555 → RGBA8888 con `c8 = (c5 << 3) | (c5 >> 2)`; la corrección de color del LCD es opcional.

## Joypad
`FF00`: bits 5/4 seleccionan botones o cruceta (activo en 0); bits 3–0 = estado (0 = pulsado). Se pide la interrupción Joypad al pasar cualquier bit seleccionado de 1 a 0. **El núcleo no filtra direcciones opuestas.** Lo hace el frontend (el D-pad por ángulo nunca las genera).

## Serial
`SB FF01`, `SC FF02`. El registro se desplaza **bit a bit** (MSB primero).
- **Reloj interno (maestro):** cada 512 T-ciclos (8192 Hz; CGB rápido: 16 T-ciclos) desplaza un bit. Llama a `opts.serial_bit_cb(user, bit_out)`, que devuelve el bit entrante. Sin callback, entra `1` (cable desconectado ⇒ recibe `0xFF`). Tras 8 bits: `SC.7 = 0` e interrupción Serial.
- **Reloj externo (esclavo):** no avanza solo; espera indefinidamente. El otro extremo llama a `gb_serial_clock_external(gb, bit_in)`, que desplaza un bit y devuelve el bit saliente; tras 8 bits, igual que arriba.
- `opts.serial_byte_cb(user, byte)`: notificación al completar un byte (lo usan las pruebas Blargg para leer la salida). No sustituye al callback por bit.
- Para M9, las dos instancias avanzan con `gb_run_cycles` en bloques ≤ 456 T-ciclos. El callback por bit del maestro llama a `gb_serial_clock_external` del esclavo.

## MBC
| MBC | Registros | Detalles que suelen fallar |
|---|---|---|
| MBC1 | `0000–1FFF` RAM enable (`0x0A` en nibble bajo); `2000–3FFF` banco ROM 5 bits (0→1); `4000–5FFF` 2 bits (RAM o bits altos del ROM); `6000–7FFF` modo | El 0→1 aplica solo a los 5 bits bajos (bancos 0x20/0x40/0x60 inaccesibles en `4000`). En modo 1, `0000–3FFF` usa los bits altos. MBC1M (multicart) queda fuera de v1. |
| MBC3 | RAM enable; `2000–3FFF` banco ROM 7 bits (0→1); `4000–5FFF` banco RAM 0–3 o registro RTC `08–0C`; `6000–7FFF` latch (escribir 0 y luego 1) | **Rojo = `0x13` (sin RTC).** El RTC se implementa igual (para Oro y Plata a futuro): el tiempo avanza con el reloj emulado y se persiste con timestamp Unix al final del `.sav` (formato de 48 bytes compatible con VBA/BGB). |
| MBC5 | RAM enable; `2000–2FFF` 8 bits bajos del banco ROM (**0 es válido**); `3000–3FFF` bit 8; `4000–5FFF` banco RAM 0–15 | **Amarillo = `0x1B`.** Carros con rumble (`0x1C–0x1E`): el bit 3 de `4000` es el motor, no un bit de banco (hook de háptica opcional). |

"RAM dirty": cualquier escritura en la RAM externa pone `dirty = true`. Un **flanco de deshabilitar RAM** (escritura ≠ `0x0A`) mientras está sucia activa `gb_sram_dirty()`, que el frontend usa como señal de "el juego acaba de guardar".

## APU
- Canales: 1 (pulso + sweep), 2 (pulso), 3 (onda, `FF30–FF3F`), 4 (ruido, LFSR 15/7 bits). Registros `NR10–NR52` (`FF10–FF26`) con las máscaras de lectura de Pan Docs.
- **Frame sequencer** a 512 Hz, disparado por el flanco de bajada del bit 12 del contador DIV (bit 13 en doble velocidad). Longitud a 256 Hz, sweep a 128 Hz, envolvente a 64 Hz.
- **Avance sincronizado:** el APU corre dentro de `gb_tick` y genera muestras en cada escritura, no solo al final del frame (A9).
- **Mezcla y salida:** DACs → `NR51` (paneo) → `NR50` (volumen). Pasa-altos de "capacitor" (como el hardware) para quitar DC. Remuestreo a `opts.sample_rate` con un filtro de caja integrador (media de todos los ciclos del periodo de salida). Es suficiente para v1; un band-limited (BLEP) queda como mejora.
- Apagar con `NR52` bit 7 pone a 0 todos los registros excepto la wave RAM (y en DMG, los contadores de longitud).

## Save states
Formato binario little-endian:
```
"PGBS" | u32 version | u8 rom_sha256[32] | u32 model | secciones {u32 tag, u32 len, bytes} ... | u32 crc32
```
- `gb_state_load` rechaza: magic incorrecto, versión mayor que la soportada, huella de ROM distinta, CRC inválido o longitudes fuera de rango. **Nunca** confía en las longitudes del archivo.
- Toda la memoria de estado de `struct gb` se serializa campo por campo (sin `memcpy` de structs con punteros).

## Seguridad (resumen de reglas verificables)
1. Ningún índice derivado de datos del ROM o de registros llega a un array sin `%` o `min()` contra su tamaño real.
2. `gb_load_rom` y `gb_state_load` están cubiertos por fuzzers ([06](06-testing.md)).
3. Se compila con `-Werror`, y los tests también con `-fsanitize=address,undefined`.
4. Sin `malloc` después de `gb_load_rom` (todo se reserva ahí). Si una reserva falla: `GB_ERR_OUT_OF_MEMORY`, y la instancia queda en estado válido **sin ROM** (se liberan las reservas parciales; cualquier llamada posterior de ejecución no hace nada).
5. Un `gb_load_rom` sobre una instancia que ya tenía un ROM libera primero el anterior (sin fugas, cubierto por ASan).
