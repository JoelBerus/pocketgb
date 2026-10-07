# 12 · Formato del paquete `.pgbm` (N7-C)

> **Estado:** formato **v1** implementado y probado en el núcleo (`core/src/pgbm.c`). Las apps lo integran en N7b (iOS y Android).
> **Contrato C:** [`core/include/pocketgb_pgbm.h`](../core/include/pocketgb_pgbm.h). Pruebas: `core/tests/unit_pgbm.c` y `core/fuzz/fuzz_pgbm.c`. Evidencia: [auditorias/N7-C-evidencia.md](auditorias/N7-C-evidencia.md). Contexto del plan: [hitos/N-README.md](hitos/N-README.md) §3.3 y §3.4.

## Para qué sirve
Un `.pgbm` es **un archivo único** que las dos apps escriben y leen para exportar un momento o para «Enviar a otro dispositivo» (iPhone ↔ Android sin red: se mueve como archivo por Drive, iCloud o la hoja de compartir). Agrupa, en secciones binarias opacas para el C: la partida (`.sav`), un estado del núcleo si lo hay, una miniatura PNG, la huella del ROM y un JSON de metadatos que solo entienden las apps.

El C **solo empaqueta y valida**: no interpreta el JSON, no abre el `.sav` ni el estado, no decodifica el PNG y no toca el disco. Todo lo que llega de fuera es **entrada no confiable** (AGENTS.md, regla 3): un `.pgbm` puede venir de cualquier sitio.

## Reglas generales
- Todo **little-endian**. Sin relleno ni alineación entre campos.
- Una cabecera fija de 12 bytes, una lista de secciones y un CRC-32 final.
- **Tres topes** acotan lo que el lector está dispuesto a leer: por sección, número de secciones y archivo completo (tabla de abajo).
- Una sección = `tipo` (4 bytes ASCII) + `longitud` (u32) + `datos`. Las secciones no se solapan: cada una empieza donde acaba la anterior y la última acaba donde empieza el CRC.
- **Compatibilidad hacia delante:** un tipo de sección desconocido se **ignora** (pero cuenta para el CRC). Un cambio que un lector antiguo no pueda ignorar sube la versión y se rechaza con `PGBM_ERR_VERSION`.

## Disposición byte a byte (v1)
### Cabecera (12 bytes)
| Desplazamiento | Tamaño | Campo | Valor |
|---|---|---|---|
| 0 | 4 | mágico | `50 47 42 4D` = «PGBM» |
| 4 | 2 | versión del formato | `1` (u16). Cualquier otro valor (0 incluido) se rechaza |
| 6 | 2 | número de secciones | u16, **≤ 64**, las desconocidas incluidas. Debe coincidir con las que hay |
| 8 | 4 | longitud total del archivo | u32 = cabecera + secciones + CRC. **≤ 4 MiB** y exactamente igual al tamaño del archivo |

### Sección (repetida «número de secciones» veces, desde el byte 12)
| Desplazamiento | Tamaño | Campo | Valor |
|---|---|---|---|
| +0 | 4 | tipo | 4 bytes ASCII imprimibles (0x21–0x7E; sin espacio). Distingue mayúsculas de minúsculas |
| +4 | 4 | longitud | u32, no puede salirse de lo que queda hasta el CRC ni superar el tope de su tipo |
| +8 | `longitud` | datos | bytes de la sección |

### Cierre (4 bytes)
| Desplazamiento | Tamaño | Campo | Valor |
|---|---|---|---|
| total − 4 | 4 | CRC-32 | u32 del CRC-32 de **todos los bytes anteriores** (cabecera y secciones, las desconocidas incluidas) |

El CRC-32 es el estándar IEEE 802.3 (polinomio reflejado `0xEDB88320`, valor inicial y final `0xFFFFFFFF`), el mismo que el de los estados (`state.c`, `crc32_update`): `zlib.crc32` en Python, `crc32()` de zlib en Swift y `java.util.zip.CRC32` en Android dan el mismo número.

### Secciones conocidas
| Tipo | Contenido | Longitud | ¿Obligatoria? | Comprobación del C |
|---|---|---|---|---|
| `ROMF` | Huella del ROM: el **SHA-256 completo** del ROM (32 bytes, el `fingerprint[32]` de `gb_rom_info` / `gba_rom_info`). El C no la interpreta: cada app la compara en su forma (iOS usa los 16 primeros bytes como clave interna; Android, los 32) | exactamente 32 | **Sí** | solo la longitud |
| `META` | JSON UTF-8 con los metadatos (alias, etiquetas, hitos, tiempo, equipo de origen, versión del núcleo, configuración…). Lo generan y lo interpretan las apps (N7b) | 1 – 65 536 (64 KiB) | No | UTF-8 estricto (RFC 3629: sin sobrelargos, sin sustitutos U+D800–DFFF, hasta U+10FFFF) y **sin NUL**. No se comprueba que sea JSON |
| `THMB` | Miniatura PNG | 8 – 262 144 (256 KiB) | No | solo la firma PNG `89 50 4E 47 0D 0A 1A 0A`. No se decodifica |
| `SAVE` | La partida: el `.sav` crudo. GB: hasta 128 KiB + el bloque RTC (48 B); GBA: hasta 128 KiB + 16 B de RTC | 0 – 131 136 (128 KiB + 64 B) | **Sí** (puede ir vacía, ver abajo) | solo el tope |
| `STAT` | Un save state del núcleo (GB `PGBS…` o GBA `PGBA…`; el de GBA ocupa ≈ 666 KiB) | 1 – 1 048 576 (1 MiB) | No | solo el tope. Lo valida `gb_state_load` / `gba_state_load` (huella, CRC y rangos) |

- **`SAVE` es la «SAV» del encargo**: el tipo mide 4 bytes, así que se llama `SAVE`.
- **`SAVE` vacía (longitud 0) es válida:** es la forma de exportar un momento de un juego **sin batería** (no hay RAM de cartucho que guardar). Es distinto de que falte la sección (`PGBM_ERR_MISSING`). El importador **debe** comprobar que el tamaño de `SAVE` encaja con el cartucho antes de tocar nada: una `SAVE` vacía para un juego con batería **no** debe sustituir la partida (AGENTS.md, regla 6).
- Las secciones opcionales **presentes** no pueden ir vacías (`PGBM_ERR_BOUNDS`): ausente = no se escribe.
- Los tipos conocidos no pueden repetirse (`PGBM_ERR_DUPLICATE`). Los **desconocidos** se ignoran siempre, aunque se repitan o vayan vacíos.
- El **orden** de las secciones al leer es libre.

### Topes
| Concepto | Tope |
|---|---|
| Archivo completo | 4 MiB (4 194 304 B) |
| `META` | 64 KiB |
| `SAVE` | 128 KiB + 64 B |
| `STAT` | 1 MiB |
| `THMB` | 256 KiB |
| `ROMF` | 32 B exactos |
| Secciones por archivo | 64 |
| Sección desconocida | solo lo que quede en el archivo (≤ 4 MiB) |

Un paquete con todo al tope ocupa 1 507 480 B (≈ 1,4 MiB); los 4 MiB dejan sitio de sobra para secciones futuras que un lector v1 todavía no entiende.

## Orden de las comprobaciones de `pgbm_parse`
1. **Mágico** (los bytes presentes; un prefijo propio del mágico es «truncado») → `PGBM_ERR_MAGIC`.
2. **Versión** (si hay 6 bytes) ≠ 1 → `PGBM_ERR_VERSION`. Se rechaza sin mirar nada más: otra versión puede tener otra cabecera.
3. **Longitud:** menos de 12 bytes → `TRUNCATED`; total < 16 → `BOUNDS`; total > 4 MiB → `TOO_LARGE`; archivo más corto que el total → `TRUNCATED`; más largo (bytes sobrantes) → `BOUNDS`.
4. **CRC-32** de todo lo anterior al CRC → `PGBM_ERR_CRC`. Una corrupción en tránsito de cualquier byte acaba aquí (salvo en el mágico, la versión y la longitud, que fallan antes con su propio código).
5. **Secciones**, en el orden del archivo: más de 64 → `TOO_LARGE`; cabecera de sección que no cabe, tipo no ASCII, longitud que se sale del contenedor, `ROMF` ≠ 32 B, opcional vacía, bytes que ninguna sección reclama o cuenta de secciones que no coincide → `BOUNDS`; tipo conocido repetido → `DUPLICATE`; sección sobre su tope → `TOO_LARGE`.
6. **Obligatorias:** falta `ROMF` o `SAVE` → `PGBM_ERR_MISSING`.
7. **Contenido:** `META` no UTF-8 o con NUL → `PGBM_ERR_UTF8`; `THMB` sin firma PNG → `PGBM_ERR_PNG`.

Si falla, `*out` queda a cero. Si acierta, los `pgbm_span` apuntan **dentro del buffer del llamador** (no se copia nada): el buffer debe seguir vivo y sin cambios mientras se usen. Los spans opcionales ausentes quedan `{NULL, 0}`.

| Código | Valor | Significa |
|---|---|---|
| `PGBM_OK` | 0 | correcto |
| `PGBM_ERR_MAGIC` | 1 | no es un `.pgbm` |
| `PGBM_ERR_VERSION` | 2 | versión desconocida (0, o mayor que la que entiende esta biblioteca) |
| `PGBM_ERR_TRUNCATED` | 3 | el archivo está cortado (descarga incompleta) |
| `PGBM_ERR_BOUNDS` | 4 | estructura imposible |
| `PGBM_ERR_CRC` | 5 | corrupto |
| `PGBM_ERR_DUPLICATE` | 6 | sección conocida repetida |
| `PGBM_ERR_MISSING` | 7 | falta `ROMF` o `SAVE` |
| `PGBM_ERR_UTF8` | 8 | `META` inválido |
| `PGBM_ERR_PNG` | 9 | `THMB` sin firma PNG |
| `PGBM_ERR_TOO_LARGE` | 10 | sobre un tope |
| `PGBM_ERR_ARG` | 11 | argumento NULL (añadido a la propuesta: separa el error del llamador del del archivo) |
| `PGBM_ERR_NOSPACE` | 12 | el buffer de salida del codificador es corto (añadido: no es «demasiado grande») |

Los valores son estables (las apps los mapean). `pgbm_result_name()` da el nombre para registros.

## Codificación canónica (`pgbm_encode`)
- El orden de escritura es **fijo**: `ROMF`, `META`, `THMB`, `SAVE`, `STAT` (lo pequeño y legible primero). Las opcionales con longitud 0 no se escriben; `ROMF` y `SAVE` se escriben siempre.
- Escribe siempre versión 1 (`pgbm_view.version` puede ser 0 o 1; otro valor → `PGBM_ERR_VERSION`).
- **Determinista:** el mismo `pgbm_view` produce siempre los mismos bytes; no hay marcas de tiempo ni relleno sin inicializar.
- Valida lo mismo que `pgbm_parse` (topes, UTF-8, firma PNG), así que **todo lo que se escribe vuelve a leerse**. Si falla, no toca la salida.
- Leer y volver a escribir un paquete canónico devuelve los mismos bytes. Un paquete de otro escritor (orden distinto, tipos desconocidos) se normaliza y **pierde las secciones desconocidas**: no se debe reenviar un `.pgbm` ajeno reescribiéndolo si hay que conservarlas.
- `pgbm_encoded_size()` mide sin escribir (0 si no cabe en los topes). `pgbm_encode` no admite que `out` se solape con los spans de entrada.

## Lo que debe hacer el importador (N7b)
El C solo garantiza que el contenedor es coherente. Antes de instalar nada, la app:
1. Comprueba que `ROMF` coincide con la huella del juego (o elige el juego por ella), cada app en su forma: Android compara los 32 bytes; iOS, los 16 primeros que usa como clave interna (si el juego ya está en la biblioteca, conviene comprobar también los 32) y que **no hay sesión abierta o aparcada de esa huella** (exclusión por huella, N-README §3.3).
2. Comprueba que el tamaño de `SAVE` es el que corresponde al cartucho (incluido 0 solo si no tiene batería), **siempre con copia de seguridad** y escritura atómica de la partida (regla 6).
3. Pasa `STAT` a `gb_state_load` / `gba_state_load`, que validan huella, CRC y rangos; un estado rechazado no impide recuperar la partida.
4. Interpreta `META` con un analizador de JSON con límites (el C solo garantiza UTF-8) y decodifica `THMB` con el decodificador del sistema (ImageIO / `BitmapFactory`), con tope de dimensiones: el C solo comprobó la firma.
5. No incluye nunca un `.pgbm` real (lleva una partida) en el repositorio ni en los tests: el hook `pre-commit` los bloquea por extensión y por el mágico (regla 1).

## Vectores dorados
Los tres se generan **en el test** (`core/tests/unit_pgbm.c`, `build_golden`) con la fórmula de relleno de abajo, sin datos reales. Cada plataforma de N7b debe poder reproducir el SHA-256 de G1 y G2 construyéndolos con **sus propias llamadas** a `pgbm_encode`, y leer los tres con `pgbm_parse`: así se prueba la integración (módulo de Swift, JNI) y que los dos teléfonos producen y aceptan los mismos bytes.

**Relleno determinista:** `pat(semilla, i) = (semilla + 37·i + 11·(i >> 8)) mod 256`, con `i` desde 0 (en Swift/Kotlin: operar con enteros y `& 0xFF`).

| Vector | Contenido | Longitud | CRC-32 | SHA-256 del archivo |
|---|---|---|---|---|
| **G1** completo | `ROMF` = `pat(0x10, 32)` · `META` = el JSON de abajo (61 B) · `THMB` = firma PNG + `pat(0x63, 24)` (32 B) · `SAVE` = `pat(0x21, 8192)` · `STAT` = `pat(0x42, 1500)`; en el orden canónico | 9873 | `a46a6d04` | `a71baa2e1c81ba3e9ed0058571237f232cf5fdd3da13d8d4f61449dfcd30f3c6` |
| **G2** mínimo | `ROMF` = `pat(0x10, 32)` · `SAVE` = `pat(0x21, 16)` | 80 | `fe827d35` | `5548b8cac9de16d52d17aec2907fd61832443bdebb4dc747499f726da9ef41c9` |
| **G3** con tipo desconocido (solo lectura; el codificador no lo puede producir) | `ROMF` · `XTRA` = «hola!» (5 B) · `SAVE` = `pat(0x21, 16)`, en ese orden (`ROMF` = `pat(0x10, 32)`) | 93 | `03717a59` | `b6b3f3e96df34995d4bdf171e368acae20f5b72bb512b824fae509d3979a4a53` |

`META` de G1 (UTF-8, 61 bytes; la «é» es `C3 A9` y el guion largo «–» es `E2 80 93`):
```
{"formato":1,"juego":"Pokémon Rojo – prueba","ms":1234567}
```
G3 lleva el número de secciones = 3. Al leerlo, `pgbm_parse` devuelve `SAVE` y `ROMF` e ignora `XTRA`.

### G2 byte a byte
```
0000: 50 47 42 4d              mágico «PGBM»
0004: 01 00                    versión 1
0006: 02 00                    2 secciones
0008: 50 00 00 00              longitud total = 80
000c: 52 4f 4d 46              «ROMF»
0010: 20 00 00 00              longitud 32
0014: 10 35 5a 7f a4 c9 ee 13 38 5d 82 a7 cc f1 16 3b      huella = pat(0x10, 32), bytes 0–15
0024: 60 85 aa cf f4 19 3e 63 88 ad d2 f7 1c 41 66 8b                          bytes 16–31
0034: 53 41 56 45              «SAVE»
0038: 10 00 00 00              longitud 16
003c: 21 46 6b 90 b5 da ff 24 49 6e 93 b8 dd 02 27 4c      partida = pat(0x21, 16)
004c: 35 7d 82 fe              CRC-32 = 0xfe827d35 (little-endian)
```
Comprobación independiente: `hashlib.sha256` del bloque anterior da el valor de G2.

### Referencia independiente en Python (solo biblioteca estándar)
Se usó para contrastar los hashes antes de que el C los produjera (la salida coincidió en los tres):
```python
import hashlib, struct, zlib
def pat(seed, n): return bytes((seed + i * 37 + (i >> 8) * 11) & 0xFF for i in range(n))
def pgbm(secs):                       # secs: [(tipo de 4 bytes, datos)], en el orden dado
    body = b"".join(t + struct.pack("<I", len(d)) + d for t, d in secs)
    pre = b"PGBM" + struct.pack("<HHI", 1, len(secs), 12 + len(body) + 4) + body
    return pre + struct.pack("<I", zlib.crc32(pre) & 0xFFFFFFFF)
ROM = pat(0x10, 32)
META = '{"formato":1,"juego":"Pokémon Rojo – prueba","ms":1234567}'.encode("utf-8")
PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + pat(0x63, 24)
g1 = pgbm([(b"ROMF", ROM), (b"META", META), (b"THMB", PNG), (b"SAVE", pat(0x21, 8192)), (b"STAT", pat(0x42, 1500))])
g2 = pgbm([(b"ROMF", ROM), (b"SAVE", pat(0x21, 16))])
g3 = pgbm([(b"ROMF", ROM), (b"XTRA", b"hola!"), (b"SAVE", pat(0x21, 16))])
for g in (g1, g2, g3): print(len(g), hashlib.sha256(g).hexdigest())
```

## Qué se prueba y cómo
| Prueba | Dónde | Qué cubre |
|---|---|---|
| Unitarias | `core/tests/unit_pgbm.c` (`make -C core test`, `asan`) | Ida y vuelta con cargas sintéticas aleatorias (incluido todo al tope a la vez); cada código de error; truncado en **cada** byte; corrupción de **cada** bit del paquete mínimo y de cada byte del completo; longitudes que se salen; duplicadas; faltas; UTF-8 (tabla de válidos e inválidos, en el lector y en el codificador); firma PNG; tipos desconocidos; topes exactos y +1; determinismo; vectores dorados |
| Fuzzer | `core/fuzz/fuzz_pgbm.c` (`make -C core fuzz-pgbm`, `fuzz-pgbm-smoke`) | Lector con arreglos de cabecera/CRC para llegar a la estructura; modo codificador contra un validador UTF-8/PNG/CRC independiente; invariante «si se lee, se vuelve a escribir y se lee igual» |
| Cabecera | `make -C core check-header` | `pocketgb_pgbm.h` compila aislado en C11 y C++17 |
| Hook | `.githooks/pre-commit` | `*.pgbm` y cualquier archivo que empiece por «PGBM» quedan bloqueados |

## Decisiones respecto a la propuesta inicial
- **`ROMF` lleva el SHA-256 completo (32 bytes)**, no una huella truncada: el núcleo la expone entera (`fingerprint[32]`) y así sirve a las dos apps (cada una la compara en su forma). El primer diseño usaba 16 bytes; un lector que espere 16 rechaza estos paquetes con `BOUNDS`.
- **Tipo `SAVE`** en lugar de «SAV» (4 bytes exactos).
- **`SAVE` puede ir vacía** (juegos sin batería); la falta de la sección sigue siendo `MISSING`. Si se prefiere exigir ≥ 1 byte, basta un `if` en `walk_sections` y otro en `pgbm_encoded_size`.
- **Dos códigos nuevos**, `PGBM_ERR_ARG` y `PGBM_ERR_NOSPACE`, para no confundir fallos del llamador con fallos del archivo.
- **Longitud estricta:** el archivo debe medir exactamente lo que dice la cabecera (los bytes de más son `BOUNDS`).
- **Tope de 64 secciones** además de los topes de bytes.
- **CRC antes de la estructura:** la corrupción en tránsito se diagnostica como `CRC` y no como un error de estructura aleatorio.
- La **versión** es un único número: lo compatible (secciones nuevas) no la sube.
