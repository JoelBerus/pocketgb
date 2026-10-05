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

## PPU (G3, `ppu.c`)
- Por scanline: cada línea visible se dibuja al empezar su HBlank (ciclo 1006) con los registros de ese momento, antes de la IRQ y la DMA de HBlank (que preparan la línea siguiente). No hay cambios a mitad de línea.
- Modos 0–5: fondos de texto (4/8 bpp, 4 tamaños, volteos, desplazamiento), afines (modos 1 y 2, con y sin envolvimiento), bitmaps (modo 3 de 15 bits, modo 4 con paleta y página, modo 5 de 160×128 y página), todos con la transformación de BG2 en los bitmaps. Las referencias afines internas se recargan en el VBlank y al escribir BGxX/BGxY, y avanzan PB/PD por línea.
- Objetos: 128, normales y afines (doble tamaño), 1D/2D, 4/8 bpp (en 8 bpp el bit 0 de la tesela solo se ignora en 2D), volteos, envolvimiento en X (9 bits) e Y (8 bits), teselas < 512 invisibles en modos bitmap, semitransparentes y ventana de objeto. Entre objetos gana el de menor prioridad y, a igualdad, el de menor índice. **Peculiaridad del hardware:** un píxel transparente de un objeto también rebaja la prioridad (y la marca de semitransparencia) del búfer de objetos si ya había color de otro objeto.
- Ventanas 0, 1 y de objeto con sus reglas de valores fuera de rango; WININ/WINOUT por capa y efecto.
- Efectos: mezcla alfa (EVA/EVB ≤ 16, saturación a 31), aclarar y oscurecer por canal de 5 bits según GBATEK. Un objeto semitransparente es siempre primer objetivo: mezcla alfa si debajo hay un segundo objetivo; si no, el efecto activo.
- Mosaico: fondos (texto, afines y bitmaps; en afines y bitmaps la fila de la primera línea del bloque se repite restando `k·PB`/`k·PD` a la referencia) y objetos. En objetos, vertical alineado a la pantalla y acotado al objeto; horizontal alineado a la pantalla, con el último bloque extendido hasta el límite del mosaico; en afines, coordenadas tomadas al inicio de cada bloque.
- Color: BGR555 → RGBA8888 con `(c << 3) | (c >> 2)`; forced blank = blanco.

### Verificación de la PPU
- **Oráculo de desarrollo:** mGBA 0.10.5 (MPL-2.0) compilado aparte con `COLOR_16_BIT` (`make -C gba oracle`; nunca entra en la app) y `tools/gba-compare.py`, que compara frame a frame y avisa si un frame es uniforme (una prueba en blanco no dice nada).
- **ROMs homebrew propias** (`gba/tests/homebrew/`, MIT, compiladas con `clang --target=armv4t` y `ld.lld`): 14 escenas que cubren los modos 0–5, afines, objetos 1D/2D/afines/doble tamaño/envolvimiento, ventanas con mezcla, aclarar/oscurecer con semitransparentes y mosaico, efectos por línea con IRQ de HBlank/VCount y DMA de HBlank, mosaico en afín y bitmap, y objetos de 256 colores con tesela impar en 1D.
- Resultado: 12 de 14 escenas y las 3 pruebas de PPU de jsmolka idénticas píxel a píxel a mGBA. Sus imágenes son las referencias de `gba/tests/ref/` y la suite las comprueba sin mGBA.
- **Diferencias con mGBA (se mantiene GBATEK):** escena 10, oscurecer: mGBA en 16 bits redondea hacia abajo los canales verde y azul (diferencia de 1 en el 58 % de los píxeles; ninguna mayor). Escena 8: en una ventana de objeto con efectos, mGBA no mezcla con el fondo los píxeles cuya prioridad rebajó un objeto transparente, porque calcula la marca de mezcla del objeto según la ventana que dibuja (559 píxeles); PocketGB decide por píxel. Para estas dos escenas la referencia es nuestra salida (regresión).
- No verificado contra mGBA: bitmap **rotado** con mosaico (mGBA muestrea distinto que en su propio fondo afín con mosaico, que sí coincide); la escena 13 lo prueba sin rotación.
- Los primeros frames no son comparables entre emuladores (arranque distinto): se compara a partir del frame 60.
- Las referencias afines internas se acumulan en aritmética sin signo; al restaurar un estado (G6) se aplicará `sext28`.
- mGBA 0.10.5 no pasa `arm.gba` (prueba 235), `thumb.gba` (230) ni `bios.gba` (001); PocketGB sí.

## Medio de guardado y RTC (G4, `cart.c`)
- **Detección:** cadenas de la biblioteca de Nintendo en el ROM (alineadas a 4): `EEPROM_V` → EEPROM; `SRAM_V`/`SRAM_F_V` → SRAM 32 KiB; `FLASH1M_V` → Flash 128 KiB; `FLASH_V`/`FLASH512_V` → Flash 64 KiB; ninguna → sin medio. `gba_options.save_type` lo fuerza por juego.
- **SRAM y Flash** en `0x0E000000`–`0x0FFFFFFF` (bus de 8 bits, espejo cada 32/64 KiB): una lectura de 16/32 bits replica el byte; una escritura de 16/32 bits guarda el byte que corresponde a la dirección (`v >> 8·(dirección mod tamaño)`). Sin medio, se lee `0xFF`.
- **Flash:** comandos `AA`/`55` en `5555`/`2AAA`; `90` ID (Panasonic `32 1B` para 64 KiB, Sanyo `62 13` para 128 KiB), `F0` salir, `A0` programar un byte, `80`+`10` borrar el chip, `80`+`30` borrar un sector de 4 KiB, `B0` banco (solo 128 KiB). Borrado instantáneo; programar solo baja bits a 0 (`&=`, como el chip). Una secuencia rota desarma el borrado y `F0` suelto sale del modo ID.
- **EEPROM** en `0x0D000000` (o `0x0DFFFF00` con ROM de más de 16 MiB), por bits en el bit 0: `11`+dirección+`0` pide lectura (después 4 bits a 0 y 64 de datos, MSB primero); `10`+dirección+64 bits+`0` escribe; sin operación en curso se lee 1 (listo). Direcciones de 6 bits (512 B) o 14 bits (8 KiB, 10 útiles). Sin ajuste, el tamaño lo fija el `.sav` (512 u 8192) o, si no hay, la longitud de la primera DMA (9/73 → 512 B; 17/81 → 8 KiB). Una vez confirmado ya no cambia (un `.sav` de otro tamaño se rechaza). Un juego que accediera por CPU bit a bit sin `.sav` ni ajuste se trataría como de 512 B (la biblioteca de Nintendo siempre usa DMA). La app pide `gba_save_size()` en cada guardado.
- **RTC S-3511A** por GPIO (`0x080000C4` datos, `C6` dirección, `C8` lectura habilitada) en los juegos con RTC por código (`AXV`, `AXP`, `BPE`, `U3I`, `U32`, `U33`, `BR4`, `BKA`) o con `gba_options.rtc`. Pines SCK/SIO/CS; comando MSB primero (se acepta invertido), datos LSB primero. Reinicio (12 h), estado (bit 6 = 24 h), fecha y hora (7 bytes BCD), hora (3 bytes); escribir la fecha (en 12 h, con el bit PM) se guarda como desplazamiento sobre la hora del anfitrión, acotado a ±200 años (un `.rtc` fuera de rango se rechaza).
- **Hora:** `gba_options.unix_time` (o `gba_rtc_set_time`) es la hora **local** del anfitrión; luego avanza con los ciclos emulados (determinista).
- **Archivos:** `.sav` crudo compatible con mGBA/VBA. `.rtc` de 16 bytes: 0–7 desplazamiento en segundos (little-endian, con signo), 8 registro de estado, 9–15 a cero. Un `.sav` de tamaño distinto se rechaza con `GBA_ERR_SAVE_SIZE` sin tocar la partida en memoria; la app no lo sobrescribe (regla dura 6). `gba_save_dirty` se activa solo si una escritura cambia algún byte del medio.

## Qué no se emula (por ahora)
Cable link GBA, e-Reader, sensores (solar, giroscopio, vibración), Wireless Adapter, GB Player, juegos de GB/GBC dentro de la GBA (para eso está el núcleo GB), JIT.
