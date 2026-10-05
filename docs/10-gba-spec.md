# 10 · Especificación del núcleo Game Boy Advance (`gba/`)

> Plan y hitos: [hitos/G-README.md](hitos/G-README.md). Contrato: [`gba/include/pocketgba.h`](../gba/include/pocketgba.h).
> Referencia de hardware: **GBATEK** (Martin Korth) y el manual técnico del ARM7TDMI (ARM DDI 0029). Código propio salvo lo citado en cada archivo.

## Reglas (las mismas que el núcleo GB)
- C11 puro, sin I/O, sin `malloc` dentro de `gba_run_frame`, sin variables globales ni estáticas mutables (`make -C gba check-globals`), determinista.
- El ROM es entrada no confiable: cada índice derivado de una dirección se acota con una máscara o se compara con el **tamaño real del archivo** (`rom_size`); más allá del ROM se devuelve el patrón de bus abierto, nunca memoria ajena.
- Los dos núcleos se enlazan en el mismo binario de la app: todo símbolo global del núcleo GBA lleva el prefijo `gba_` (o `arm_` dentro de la CPU). `make -C gba check-symbols` falla si hay colisiones o símbolos sin prefijo.
- El SHA-256 es el de `core/src/sha256.c` (propio). No se duplica: el Makefile del núcleo GBA lo compila y la app ya lo enlaza con el núcleo GB.
- Sin BIOS de Nintendo en el repo (regla dura 1). Sin BIOS cargada, las SWI se emulan en alto nivel (HLE, G2). La app puede cargar el volcado propio de Joel con `gba_load_bios`.

## Mapa de memoria (GBATEK §GBA Memory Map)
| Región | Dirección | Tamaño | Bus | Notas |
|---|---|---|---|---|
| BIOS | `0000000` | 16 KiB | 32 | Solo legible ejecutando desde la BIOS (G2: si no, la última instrucción leída). |
| EWRAM | `2000000` | 256 KiB | 16 | Espejo cada 256 KiB. 2 waitstates (G2). |
| IWRAM | `3000000` | 32 KiB | 32 | Espejo cada 32 KiB. |
| E/S | `4000000` | 1 KiB | 32 | Registros; fuera de `4000400` no hay nada. |
| Paleta | `5000000` | 1 KiB | 16 | Escritura de 8 bits = media palabra duplicada. |
| VRAM | `6000000` | 96 KiB | 16 | Espejo cada 128 KiB; `18000`–`1FFFF` repite `10000`–`17FFF`. Escritura de 8 bits solo en la zona de fondos. |
| OAM | `7000000` | 1 KiB | 32 | Ignora escrituras de 8 bits. |
| ROM | `8000000`–`DFFFFFF` | ≤ 32 MiB | 16 | Tres zonas de waitstate (G2). Más allá del archivo: `(dirección >> 1) & 0xFFFF`. |
| SRAM/Flash | `E000000` | 64 KiB | 8 | G4. |

## CPU ARM7TDMI (G1)
- Modos usuario, FIQ, IRQ, SVC, ABT, UND y sistema. Bancos: r8–r12 para FIQ; r13, r14 y SPSR por modo. El bit 4 del modo es siempre 1.
- **Pipeline de 2 etapas visibles**: `pipe[0]` se ejecuta, `pipe[1]` ya está leída, y `r15` es la dirección que se lee durante la ejecución (instrucción + 8 en ARM, + 4 en Thumb). Escribir `r15` vacía el pipeline y se rellenan dos lecturas desde el nuevo PC. Las lecturas de código se alinean; el valor de `r15` no (como NanoBoyAdvance, origen de SingleStepTests).
- Casos límite que fijan las pruebas: el PC se lee como +12 en desplazamientos por registro, `STR`/`STM`/`SWP` de r15 y operandos de `MUL`; `S` con `Rd = 15` restaura el CPSR desde el SPSR en modos privilegiados y calcula banderas en usuario/sistema; listas vacías en `LDM`/`STM` transfieren r15 y mueven la base 0x40; la escritura de vuelta en r15 suma 4; `Bcc` Thumb con condición `1110` salta siempre; coprocesadores = instrucción indefinida.
- Bandera C de las multiplicaciones: algoritmo de Booth del ARM7TDMI ([`arm_mulcarry.c`](../gba/src/arm_mulcarry.c), zlib, zaydlang 2024).
- Decodificación por tablas **dentro de la instancia** (`arm_lut[4096]`, `thumb_lut[1024]`, clases de instrucción) y despacho con `switch`: sin punteros a función globales.
- Interrupciones: entrada en IRQ con `LR = siguiente + 4`; manejador de la BIOS (o el de la HLE) en `0x18`.

## Tiempos (G2)
- Cada acceso cuesta los ciclos de su región según `WAITCNT` (tabla por instancia recalculada al escribirlo): BIOS, IWRAM, E/S y OAM 1; EWRAM 3 (16 bits) / 6 (32); paleta y VRAM 1 / 2; ROM 1 + espera N o S de su zona (WS0 4,3,2,8 / 2,1; WS1 … / 4,1; WS2 … / 8,1), y un acceso de 32 bits al ROM es N + S. SRAM: 1 + espera, a 8 bits.
- Las lecturas de código son secuenciales si siguen a la anterior; tras un salto la primera es N. `LDM`/`STM`/`PUSH`/`POP` y la DMA hacen S a partir del segundo acceso.
- **Prefetch del cartucho (aproximación):** con `WAITCNT` bit 14, una lectura S de código en el ROM cuesta 1 ciclo (2 si es de 32 bits). No se modela el búfer de 8 medias palabras ni su vaciado. Las pruebas de tiempos de mGBA quedan fuera del alcance de G2.
- Frame: 228 líneas × 1232 ciclos = 280 896 ciclos. VBlank en las líneas 160–227 (la bandera de DISPSTAT se borra en la 227). HBlank (bandera, IRQ y DMA de HBlank en líneas 0–159) desde el ciclo 1006.
- La CPU parada (`HALTCNT`, `Halt`, `IntrWait`) salta al próximo evento (HBlank, fin de línea o desborde de un timer) y despierta con `IE & IF`, aunque `IME` sea 0.

## E/S, interrupciones, DMA y timers (G2)
- `IE`, `IF` (escribir 1 borra), `IME`; la IRQ se toma entre instrucciones si `IME` y no está el bit I del CPSR. `KEYCNT` con modo OR/AND.
- DMA 0–3: registros con sus máscaras de dirección (fuente 27/28 bits, destino 27/28), contador 0 = máximo, control de origen y destino (incluido incrementar y recargar), repetición, IRQ, inmediata/VBlank/HBlank/especial (FIFO de sonido en G5). Fuente en el ROM siempre incrementa. Leer de la BIOS o por debajo de `0x02000000` devuelve el último dato transferido. Una DMA bloquea a la CPU y se ejecuta entera.
- Timers 0–3: prescaler 1/64/256/1024, cascada, IRQ y recarga al desbordar; escribir el contador fija la recarga y activar copia la recarga al contador.
- Bus abierto: lecturas sin mapear devuelven lo último que leyó el pipeline (en Thumb, la media palabra repetida) y, durante una DMA, su último dato.
- **BIOS protegida:** leerla con el PC fuera de ella devuelve la última instrucción que la CPU leyó de la BIOS.

## BIOS en alto nivel (G2, `hle.c`)
- Sin la BIOS de Joel, en la zona de la BIOS hay un manejador de IRQ propio en las direcciones que documenta GBATEK (`0x128`–`0x13C`: guarda r0–r3, r12, lr; llama a `[0x03007FFC]`; restaura y `subs pc, lr, #4`). Así la lectura protegida de la BIOS da los valores del hardware (`0xE129F000` tras el arranque, `0xE25EF004` durante una IRQ, `0xE55EC002` después y `0xE3A02004` tras una SWI).
- Las SWI se ejecutan en C, en el modo del llamador: `SoftReset`, `RegisterRamReset`, `Halt`, `Stop` (como Halt), `IntrWait`, `VBlankIntrWait`, `Div`, `DivArm`, `Sqrt`, `ArcTan`, `ArcTan2`, `CpuSet`, `CpuFastSet`, `GetBiosChecksum`, `BgAffineSet`, `ObjAffineSet`, `BitUnPack`, `LZ77UnComp` (WRAM/VRAM), `HuffUnComp`, `RLUnComp` (WRAM/VRAM), `Diff8bitUnFilter` (WRAM/VRAM), `Diff16bitUnFilter`, `SoundBias`, `MidiKey2Freq`. El resto (driver de sonido de la BIOS, multiboot) se ignora.
- `IntrWait` pone `IME = 1`, para la CPU y vuelve a ejecutar la SWI al salir de la IRQ hasta que el manejador del juego marca el bit en `0x03007FF8` (que la SWI borra al volver).
- Diferencias conocidas con la BIOS real: la división por cero devuelve un valor estable en vez de colgarse; la tabla de senos de las funciones afines se calcula (puede diferir en el último bit); los tiempos de las SWI son aproximados. Las descompresiones acotan el tamaño de salida a 256 KiB y Huffman deja de leer si el árbol nunca llega a una hoja (ROM no confiable).

## Medio de guardado (G4)
Detección por las cadenas de la biblioteca de Nintendo en el ROM (`EEPROM_V`, `SRAM_V`, `SRAM_F_V`, `FLASH_V`, `FLASH512_V`, `FLASH1M_V`), con ajuste manual por juego. `.sav` crudo compatible con mGBA/VBA: SRAM 32 KiB, Flash 64/128 KiB, EEPROM 512 B / 8 KiB. RTC (GPIO S-3511A) en 16 bytes aparte. Un `.sav` de tamaño distinto se rechaza con `GBA_ERR_SAVE_SIZE` y la app no lo sobrescribe.

## Qué no se emula (por ahora)
Cable link GBA, e-Reader, sensores (solar, giroscopio, vibración), Wireless Adapter, GB Player, juegos de GB/GBC dentro de la GBA (para eso está el núcleo GB), JIT.
