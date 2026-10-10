# 12 · Formato del paquete `.pgbm` (N7-C)

> **Estado:** formato **v1** implementado y probado en el núcleo (`core/src/pgbm.c`). Las apps lo integran en N7b (iOS y Android).
> **Contrato C:** [`core/include/pocketgb_pgbm.h`](../core/include/pocketgb_pgbm.h). Pruebas: `core/tests/unit_pgbm.c` y `core/fuzz/fuzz_pgbm.c`. Evidencia: [auditorias/N7-C-evidencia.md](auditorias/N7-C-evidencia.md). Contexto del plan: [hitos/N-README.md](hitos/N-README.md) §3.3 y §3.4.

## Para qué sirve
Un `.pgbm` es **un archivo único** que las dos apps escriben y leen para exportar un momento o para «Enviar a otro dispositivo» (iPhone ↔ Android sin red: se mueve como archivo por Drive, iCloud o la hoja de compartir). Agrupa, en secciones binarias opacas para el C: la partida (`.sav`), un estado del núcleo si lo hay, una miniatura PNG, la huella del ROM y un JSON de metadatos (**`META v1`, definido abajo**) que solo entienden las apps.

El C **solo empaqueta y valida el contenedor**: no interpreta el JSON (eso es el esquema `META v1`, que implementan las dos apps), no abre el `.sav` ni el estado, no decodifica el PNG y no toca el disco. Todo lo que llega de fuera es **entrada no confiable** (AGENTS.md, regla 3): un `.pgbm` puede venir de cualquier sitio.

## Reglas generales
- Todo **little-endian**. Sin relleno ni alineación entre campos.
- Una cabecera fija de 12 bytes, una lista de secciones y un CRC-32 final.
- **Tres topes** acotan lo que el lector está dispuesto a leer: por sección, número de secciones y archivo completo (tabla de abajo).
- Una sección = `tipo` (4 bytes ASCII) + `longitud` (u32) + `datos`. Las secciones no se solapan: cada una empieza donde acaba la anterior y la última acaba donde empieza el CRC.
- **Compatibilidad hacia delante, a la manera de PNG:** el tipo de una sección desconocida dice cómo tratarla. Si su **primera letra es una mayúscula `A`–`Z`** la sección es **crítica**: el lector que no la entiende rechaza el paquete entero con `PGBM_ERR_CRITICAL` («actualiza la app»). Si empieza por **cualquier otro carácter** (minúscula, dígito, signo) es **auxiliar**: se **ignora** (pero cuenta para el CRC). Los cinco tipos conocidos son todos de mayúsculas. Así una versión futura puede añadir secciones auxiliares (`xtra`) sin romper a los lectores v1, o marcar como obligatoria de entender una sección nueva (`XTRA`) sin subir la versión del contenedor. Un cambio de la cabecera, del CRC o del significado de un tipo conocido sí sube la versión y se rechaza con `PGBM_ERR_VERSION`.

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
| `META` | JSON UTF-8 con el esquema **[META v1](#meta-v1-normativa)** (huellas, linaje, equipo de origen, núcleo, configuración, alias, etiquetas, hitos, tiempo y momento). Lo generan y lo interpretan las apps (N7b) | 1 – 65 536 (64 KiB) | **No para el C; sí para las apps** (el importador rechaza un paquete sin `META` válida) | UTF-8 estricto (RFC 3629: sin sobrelargos, sin sustitutos U+D800–DFFF, hasta U+10FFFF) y **sin NUL**. No se comprueba que sea JSON |
| `THMB` | Miniatura PNG | 8 – 262 144 (256 KiB) | No | solo la firma PNG `89 50 4E 47 0D 0A 1A 0A`. No se decodifica |
| `SAVE` | La partida: el `.sav` crudo. GB: hasta 128 KiB + el bloque RTC (48 B); GBA: hasta 128 KiB + 16 B de RTC | 0 – 131 136 (128 KiB + 64 B) | **Sí** (puede ir vacía, ver abajo) | solo el tope |
| `STAT` | Un save state del núcleo (GB `PGBS…` o GBA `PGBA…`; el de GBA ocupa ≈ 666 KiB) | 1 – 1 048 576 (1 MiB) | No | solo el tope. Lo valida `gb_state_load` / `gba_state_load` (huella, CRC y rangos) |

- **`SAVE` es la «SAV» del encargo**: el tipo mide 4 bytes, así que se llama `SAVE`.
- **`SAVE` vacía (longitud 0) es válida:** es la forma de exportar un momento de un juego **sin batería** (no hay RAM de cartucho que guardar). Es distinto de que falte la sección (`PGBM_ERR_MISSING`). El importador **debe** comprobar que el tamaño de `SAVE` encaja con el cartucho antes de tocar nada: una `SAVE` vacía para un juego con batería **no** debe sustituir la partida (AGENTS.md, regla 6).
- Las secciones opcionales **presentes** no pueden ir vacías (`PGBM_ERR_BOUNDS`): ausente = no se escribe.
- Los tipos conocidos no pueden repetirse (`PGBM_ERR_DUPLICATE`). Los desconocidos **auxiliares** (primera letra no mayúscula) se ignoran siempre, aunque se repitan o vayan vacíos; los **críticos** (mayúscula inicial) rechazan el paquete (`PGBM_ERR_CRITICAL`).
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
| Sección desconocida (auxiliar) | solo lo que quede en el archivo (≤ 4 MiB) |

Un paquete con todo al tope ocupa 1 507 480 B (≈ 1,4 MiB); los 4 MiB dejan sitio de sobra para secciones futuras que un lector v1 todavía no entiende.

## META v1 (normativa)
`META` es la **única** fuente de los datos que el plan de N7 ([N-README](hitos/N-README.md) §3.3 y §3.4) pide que viajen con la partida (sha y sha base para el linaje, equipo de origen, versión del núcleo, configuración, alias, etiquetas, hitos, tiempo de juego, datos del momento). El contenedor C solo garantiza que es UTF-8 sin NUL; **este esquema lo implementan las dos apps por igual** para que un paquete escrito en un equipo se lea en el otro. El esquema tiene su propia versión (`format`), independiente de la del contenedor.

**Reglas generales**
- Un objeto JSON (RFC 8259) en UTF-8 **sin BOM**, como máximo 64 KiB. Claves en inglés `snake_case`. No se repiten claves.
- **Claves desconocidas se ignoran** (compatibilidad hacia delante): una versión nueva puede añadir claves sin romper a la anterior.
- **Huellas (`*_sha256`):** 64 caracteres hexadecimales. Quien escribe usa minúsculas; quien lee acepta mayúsculas y compara sin distinguirlas.
- **Números:** enteros, sin fracción ni exponente, de 0 a 2^53 − 1 (caben exactos en un `double`). **Fechas y tiempos:** milisegundos; `*_ms` de fecha = época Unix en UTC.
- **Cadenas:** Unicode válido, con los escapes JSON que haga falta (`\uXXXX`). El texto se compara tal cual: no se normaliza.
- Un tipo equivocado, una clave obligatoria ausente o un límite superado hace la `META` **inválida**.

**Claves**
| Clave | Tipo | ¿Obligatoria? | Significado |
|---|---|---|---|
| `format` | entero | **Sí** | Versión del esquema: `1`. Otro valor = paquete de otra versión: se rechaza («actualiza la app») |
| `rom_sha256` | hex 64 | **Sí** | SHA-256 completo del ROM; **debe ser igual a `ROMF`** (los 32 bytes en hexadecimal) |
| `sav_sha256` | hex 64 | **Sí** | SHA-256 del contenido **exacto** de la sección `SAVE`, con el bloque RTC tal como se guarda (GB: el que escribe `gb_sram_save`; GBA: los 16 bytes finales). Con `SAVE` vacía es el SHA-256 de cero bytes (`e3b0c442…b855`) |
| `base_sav_sha256` | hex 64 o `null` | No | SHA-256 del `.sav` del que partió esta partida en el equipo de origen (la última versión que ese equipo había instalado o recibido antes de las escrituras que dan `SAVE`). Ausente o `null` = se desconoce. Es el «sha base» del linaje (§3.4) |
| `device` | objeto | **Sí** | Equipo de origen: `platform` (`"ios"` o `"android"`, obligatoria) y `name` (cadena ≤ 128, obligatoria, p. ej. «iPhone de Joel») |
| `created_ms` | entero | **Sí** | Instante en que el equipo de origen escribió el paquete |
| `core` | objeto | **Sí** | Núcleo que lo escribió: `name` (`"gb"` o `"gba"`, obligatoria; debe corresponder a la consola del juego que identifica `rom_sha256`) y `version` (cadena ≤ 32; informativa: la compatibilidad de `STAT` la decide `gb_state_load` / `gba_state_load`) |
| `state_of_sav_sha256` | hex 64 | **Sí si hay `STAT`** | SHA-256 del `.sav` que tenía el núcleo en el instante en que se tomó `STAT` (la RAM del cartucho forma parte del estado). Si es igual a `sav_sha256`, el estado corresponde exactamente a la partida del paquete y se puede ofrecer «Continuar donde lo dejaste» (ND6); si no, el estado no se ofrece como continuación. Sin `STAT` se ignora |
| `config` | objeto | No | Configuración con la que se jugó (§3.3). Todas sus claves son opcionales y las desconocidas se ignoran; ausente = los valores por defecto del equipo que importa. **GB:** `model` (`"auto"`, `"dmg"` o `"cgb"`), `compat_palette` (cadena ≤ 64: identificador de la paleta de compatibilidad, opaco para el C). **GBA:** `gba_save_type` (`"auto"`, `"none"`, `"sram"`, `"flash64"`, `"flash128"`, `"eeprom512"` o `"eeprom8k"`), `gba_rtc` (`"auto"`, `"on"` u `"off"`), `gba_bios` (booleano: el estado se tomó con una BIOS real y no con la emulada; un `STAT` de otra configuración lo rechaza el núcleo) |
| `play_time_ms` | entero | No | Tiempo jugado (solo cuenta con el juego corriendo): del juego, o del momento si el paquete exporta uno |
| `title` | cadena ≤ 256 | No | Título del juego tal como lo muestra la biblioteca |
| `alias` | cadena ≤ 256 | No | Alias que puso el usuario |
| `tags` | lista de cadenas | No | Etiquetas, como máximo 64 de ≤ 64 caracteres |
| `milestones` | lista de objetos | No | Hitos, como máximo 256: `id` (cadena ≤ 64), `title` (cadena ≤ 256) y `done` (booleano), los tres obligatorios en cada hito |
| `moment` | objeto | **Sí si el paquete exporta un momento** | `name` (cadena ≤ 256, obligatoria), `collection` (≤ 256), `note` (≤ 4096) y `created_ms` (cuándo se tomó el momento), los tres últimos opcionales |

**Valores de `config` que escriben las apps** (ND20 h; iOS y Android igual):
- `model`: el modelo con el que corre el juego, **`"dmg"` o `"cgb"`, nunca `"auto"`** (un ROM CGB, o un ROM de Game Boy con «Color en juegos de Game Boy» activado, = `"cgb"`). Al leer se acepta también `"auto"`.
- `compat_palette`: el número de la paleta de compatibilidad como cadena (`"0"` = automática).
- `gba_save_type`: `"auto"`, `"none"`, `"sram"`, `"flash64"`, `"flash128"`, `"eeprom512"`, `"eeprom8k"` (el orden de `gba_save_type` en `gba/include/pocketgba.h`); `gba_rtc`: `"auto"`, `"on"`, `"off"` (`GBA_RTC_*`); `gba_bios`: booleano.
- Un valor de tipo o fuera de esa lista hace la `META` inválida. Al importar, si el estado del paquete es de otro `model` o de otra `gba_save_type`, `gba_rtc` o `gba_bios` (la paleta no cuenta; un `"auto"` del paquete tampoco, y una clave ausente en un lado no se compara), **el estado no se descarta** (ND21): se instala como cualquier otro, con las protecciones de ND20 f (la partida actual, con su estado, va a «Antes de importar» y a una copia apartada), y se avisa de qué ajuste del juego cambiar para continuar justo donde se dejó. Si al continuar el núcleo lo rechaza, la partida queda intacta, el estado se conserva y se juega desde la partida.

**Qué comprueba el importador** (todo antes de tocar nada; cualquier fallo = paquete rechazado sin cambios):
1. **Sin `META`, o con una `META` inválida, el paquete se rechaza.** El contenedor C la admite opcional porque solo valida el formato; las apps no importan un `.pgbm` sin metadatos (no habría sha, ni linaje, ni forma de comprobar nada). Las apps **escriben siempre** `META`, también en «Enviar a otro dispositivo» y al exportar un momento.
2. `format` = 1, los tipos y los límites de la tabla.
3. `rom_sha256` = `ROMF`; `core.name` = la consola del juego.
4. `sav_sha256` = el SHA-256 de los bytes de `SAVE` (se calcula al importar): detecta un paquete cuyo `META` y `SAVE` no se corresponden.
5. Si hay `STAT`: existe `state_of_sav_sha256`.
6. Linaje: `sav_sha256` y `base_sav_sha256` entran en la tabla de [N-README](hitos/N-README.md) §3.4 (avance o divergencia); la importación **siempre** respalda lo actual y respeta la exclusión por huella.

**`META` de G1** (el vector dorado lo lleva en una sola línea, ASCII puro: «Pokémon Rojo – prueba» va como `Pok\u00e9mon Rojo \u2013 prueba`, así que no depende de la normalización Unicode del código fuente de cada plataforma; aquí con sangría y los hash abreviados):
```json
{
  "format": 1,
  "rom_sha256": "10355a7f…c41668b",
  "sav_sha256": "d3c5532f…ac28ef",
  "base_sav_sha256": null,
  "device": { "platform": "ios", "name": "iPhone de prueba" },
  "created_ms": 1790000000000,
  "core": { "name": "gb", "version": "1.0.0" },
  "config": { "model": "cgb", "compat_palette": "default" },
  "state_of_sav_sha256": "d3c5532f…ac28ef",
  "play_time_ms": 3723000,
  "title": "Pokémon Rojo – prueba",
  "alias": "Mi partida",
  "tags": ["rpg", "prueba"],
  "milestones": [ { "id": "badge1", "title": "Medalla Roca", "done": true },
                  { "id": "badge2", "title": "Medalla Cascada", "done": false } ],
  "moment": { "name": "Antes del gimnasio", "collection": "Principal", "note": "Nota de prueba",
              "created_ms": 1789999000000 }
}
```

## Orden de las comprobaciones de `pgbm_parse`
1. **Mágico** (los bytes presentes; un prefijo propio del mágico es «truncado») → `PGBM_ERR_MAGIC`.
2. **Versión** (si hay 6 bytes) ≠ 1 → `PGBM_ERR_VERSION`. Se rechaza sin mirar nada más: otra versión puede tener otra cabecera.
3. **Longitud:** menos de 12 bytes → `TRUNCATED`; total < 16 → `BOUNDS`; total > 4 MiB → `TOO_LARGE`; archivo más corto que el total → `TRUNCATED`; más largo (bytes sobrantes) → `BOUNDS`.
4. **CRC-32** de todo lo anterior al CRC → `PGBM_ERR_CRC`. Una corrupción en tránsito de cualquier byte acaba aquí (salvo en el mágico, la versión y la longitud, que fallan antes con su propio código).
5. **Secciones**, en el orden del archivo: más de 64 → `TOO_LARGE`; cabecera de sección que no cabe, tipo no ASCII, longitud que se sale del contenedor, `ROMF` ≠ 32 B, opcional vacía, bytes que ninguna sección reclama o cuenta de secciones que no coincide → `BOUNDS`; tipo desconocido que empieza por mayúscula → `CRITICAL` (después de comprobar que cabe y es ASCII; antes de lo que venga a continuación); tipo conocido repetido → `DUPLICATE`; sección sobre su tope → `TOO_LARGE`.
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
| `PGBM_ERR_CRITICAL` | 13 | sección desconocida **crítica** (tipo con mayúscula inicial): el paquete exige una versión más nueva de la app |

**Los valores son un contrato** (las apps los mapean por número, Swift y JNI): están escritos explícitamente en el `enum`, `pgbm.c` los fija con `_Static_assert` y `unit_pgbm.c` los comprueba con literales; los nuevos se añaden siempre al final. `pgbm_result_name()` da el nombre para registros.

## Codificación canónica (`pgbm_encode`)
- El orden de escritura es **fijo**: `ROMF`, `META`, `THMB`, `SAVE`, `STAT` (lo pequeño y legible primero). Las opcionales con longitud 0 no se escriben; `ROMF` y `SAVE` se escriben siempre.
- Escribe siempre versión 1 (`pgbm_view.version` puede ser 0 o 1; otro valor → `PGBM_ERR_VERSION`).
- **Determinista:** el mismo `pgbm_view` produce siempre los mismos bytes; no hay marcas de tiempo ni relleno sin inicializar.
- Valida lo mismo que `pgbm_parse` (topes, UTF-8, firma PNG), así que **todo lo que se escribe vuelve a leerse**. Si falla, no toca la salida.
- Leer y volver a escribir un paquete canónico devuelve los mismos bytes. Un paquete de otro escritor (orden distinto, secciones auxiliares desconocidas) se normaliza y **pierde las secciones desconocidas**: no se debe reenviar un `.pgbm` ajeno reescribiéndolo si hay que conservarlas.
- `pgbm_encoded_size()` mide sin escribir (0 si no cabe en los topes). `pgbm_encode` no admite que `out` se solape con los spans de entrada.

## Lo que debe hacer el importador (N7b)
El C solo garantiza que el contenedor es coherente; las comprobaciones de `META` están en [META v1](#meta-v1-normativa). Antes de instalar nada, la app:
1. **Rechaza un paquete sin `META` válida** y aplica las comprobaciones de `META` de arriba (`rom_sha256` = `ROMF`, `sav_sha256` = SHA-256 de `SAVE`, `state_of_sav_sha256` si hay `STAT`…).
2. Comprueba que `ROMF` coincide con la huella del juego (o elige el juego por ella), cada app en su forma: Android compara los 32 bytes; iOS, los 16 primeros que usa como clave interna (si el juego ya está en la biblioteca, conviene comprobar también los 32), y que **no hay sesión abierta o aparcada de esa huella** (exclusión por huella, N-README §3.3).
3. Comprueba que el tamaño de `SAVE` es el que corresponde al cartucho (incluido 0 solo si no tiene batería). **Un `SAVE` vacío, o de tamaño distinto al del cartucho, para un juego con batería no toca el `.sav`** y deja la copia de seguridad; en todo caso la instalación lleva **siempre copia de seguridad** y escritura atómica (regla 6).
4. Pasa `STAT` a `gb_state_load` / `gba_state_load`, que validan huella, CRC y rangos; un estado rechazado no impide recuperar la partida.
5. Decodifica `THMB` con el decodificador del sistema (ImageIO / `BitmapFactory`), con tope de dimensiones: el C solo comprobó la firma.
6. Trata `PGBM_ERR_CRITICAL` y `PGBM_ERR_VERSION` como «este paquete viene de una versión más nueva: actualiza la app».
7. No incluye nunca un `.pgbm` real (lleva una partida) en el repositorio ni en los tests: el hook `pre-commit` los bloquea por extensión y por el mágico y `.gitignore` los ignora (regla 1).

## Vectores dorados
Los cuatro se generan **en el test** (`core/tests/unit_pgbm.c`, `build_golden`) con la fórmula de relleno de abajo, sin datos reales. Cada plataforma de N7b debe poder reproducir el SHA-256 de G1 y G2 construyéndolos con **sus propias llamadas** a `pgbm_encode`, y leer G1, G2 y G3 (y comprobar que G4 se rechaza con `PGBM_ERR_CRITICAL`) con `pgbm_parse`: así se prueba la integración (módulo de Swift, JNI) y que los dos teléfonos producen y aceptan los mismos bytes.

**Relleno determinista:** `pat(semilla, i) = (semilla + 37·i + 11·(i >> 8)) mod 256`, con `i` desde 0 (en Swift/Kotlin: operar con enteros y `& 0xFF`).

| Vector | Contenido | Longitud | CRC-32 | SHA-256 del archivo |
|---|---|---|---|---|
| **G1** completo | `ROMF` = `pat(0x10, 32)` · `META` = el JSON de abajo (802 B) · `THMB` = firma PNG + `pat(0x63, 24)` (32 B) · `SAVE` = `pat(0x21, 8192)` · `STAT` = `pat(0x42, 1500)`; en el orden canónico | 10614 | `49b7b519` | `e83ee087bd870c6b395bca974654ce25f799839b3e77f6865b01f142bc16ab4d` |
| **G2** mínimo | `ROMF` = `pat(0x10, 32)` · `SAVE` = `pat(0x21, 16)` | 80 | `fe827d35` | `5548b8cac9de16d52d17aec2907fd61832443bdebb4dc747499f726da9ef41c9` |
| **G3** con sección auxiliar desconocida (solo lectura; el codificador no la puede producir) | `ROMF` = `pat(0x10, 32)` · `xtra` = «hola!» (5 B) · `SAVE` = `pat(0x21, 16)`, en ese orden | 93 | `0a62ba0a` | `1d61e5c030c7855a197e5b4b05e7417d7c452d4f2accf5441d494e84cb96a261` |
| **G4** con sección **crítica** desconocida (se rechaza: `PGBM_ERR_CRITICAL`) | como G3 pero el tipo es `XTRA` | 93 | `03717a59` (válido) | `b6b3f3e96df34995d4bdf171e368acae20f5b72bb512b824fae509d3979a4a53` |

`META` de G1: el esquema [META v1](#meta-v1-normativa), **802 bytes de ASCII puro** (la «é» y el guion largo «–» van como los escapes JSON `\u00e9` y `\u2013`), claves en este orden y sin espacios:
```
{"format":1,"rom_sha256":"10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668b","sav_sha256":"d3c5532fe0534370189004c7704430c1470f5c0f0be7b9e522c3799517ac28ef","base_sav_sha256":null,"device":{"platform":"ios","name":"iPhone de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"},"config":{"model":"cgb","compat_palette":"default"},"state_of_sav_sha256":"d3c5532fe0534370189004c7704430c1470f5c0f0be7b9e522c3799517ac28ef","play_time_ms":3723000,"title":"Pok\u00e9mon Rojo \u2013 prueba","alias":"Mi partida","tags":["rpg","prueba"],"milestones":[{"id":"badge1","title":"Medalla Roca","done":true},{"id":"badge2","title":"Medalla Cascada","done":false}],"moment":{"name":"Antes del gimnasio","collection":"Principal","note":"Nota de prueba","created_ms":1789999000000}}
```
`sav_sha256` y `state_of_sav_sha256` son el SHA-256 de `pat(0x21, 8192)` (`d3c5532fe0534370189004c7704430c1470f5c0f0be7b9e522c3799517ac28ef`) y `rom_sha256` es `ROMF` en hexadecimal: el test lo comprueba (el importador hace lo mismo con cualquier paquete). G3 lleva el número de secciones = 3; al leerlo, `pgbm_parse` devuelve `SAVE` y `ROMF` e ignora `xtra`. G4 es G3 con el tipo en mayúsculas: el CRC es válido y aun así se rechaza.

### Vectores cruzados de las apps (N7b: X1…X8)
Generador de referencia: `core/tests/unit_pgbm_cross.c` (`make -C core test`, suite `pgbmx`). **iOS y Android construyen cada vector con sus propias llamadas** (Swift / JNI a `pgbm_encode`), comprueban que el SHA-256 del archivo es el de la tabla y lo pasan **a su importador real** con el cartucho de referencia, que debe dar el resultado indicado. Ningún `.pgbm` se guarda en el repo: se generan en el test.

**Cartucho de referencia:** huella = `pat(0x10, 32)` (`10355a7f…c41668b`), Game Boy, **con batería**, RAM de **32 768 B** sin RTC. Cargas: `S1` = `pat(0x21, 32768)`, `S2` = `pat(0x23, 32768)`, `B` = `pat(0x22, 32768)`, `T1` = `pat(0x42, 4096)`, `T2` = `pat(0x43, 4096)`, `S4` = `pat(0x21, 8000)`. Ningún vector lleva `THMB`.

| SHA-256 de la carga | Valor |
|---|---|
| `S1` | `5e08944e1a748c003e29abf76d1930cb1ed35933e7741e52a9ce055f3fa77a76` |
| `B` | `b28ee9fab76c8a575342631959fca77e58ef6885e554111f35e76195dc16322b` |
| `S2` | `cbab8faef55e97b329a4bcffbe8cc683be5aabc75970565eca6008250e3f6b39` |
| `pat(0x24, 32768)` | `fb840ce50edf61d2eb00d99ca6db957bb61e7bbd3376c1e7fc783b0a7d63a752` |
| `S4` | `dc8f54f472d2e4a9e5562f22c1c52a3067a66b1374c398ab3e7a930944976c58` |

| Vector | Secciones | META (B) | Longitud | CRC-32 | SHA-256 del archivo | Resultado esperado del importador |
|---|---|---|---|---|---|---|
| **X1** Android envía, estado exacto | `ROMF` · `META` · `SAVE`=`S1` · `STAT`=`T1` | 536 | 37480 | `68b5f56d` | `b7d163cf9fda7b6ccbcd240b1a25b97518caccdda45b5a38e37a021e713bf796` | Se acepta. `state_of_sav_sha256` = `sav_sha256` → se ofrece «Continuar donde lo dejaste en Pixel de prueba» (el estado viaja como estado automático). Equipo origen `android` |
| **X2** iPhone avanza desde X1 | `ROMF` · `META` · `SAVE`=`S2` · `STAT`=`T2` | 466 | 37410 | `0c617b44` | `e79d7993c7cd380ac7496f792766362e0689fe285ff2c06a9424508b40db5f2f` | Se acepta; el estado es de otra partida (`state_of` ≠ `sav`): **no** se ofrece continuar ni se instala `STAT`. Con la local = `S1` (= `base`): **avance** (se instala con backup, sin pregunta). Con la local = `B`: **divergencia** (se pregunta) |
| **X3** `SAVE` vacía | `ROMF` · `META` · `SAVE` (0 B) | 318 | 390 | `44e5d413` | `c1f033894a9d9f7bc15cfcc353b776caf6303fadb51f7168829045cfbb430e17` | No toca el `.sav` (juego con batería); deja backup de lo actual |
| **X4** `SAVE` de 8000 B | `ROMF` · `META` · `SAVE`=`S4` | 315 | 8387 | `4727d089` | `336401621230102beadbadbdba1643acdc58da343fe36e2105d9e07966933771` | Igual que X3 (tamaño ≠ 32 768) |
| **X5** `META` `format: 2` | `ROMF` · `META` · `SAVE`=`S1` | 318 | 33158 | `feb5f534` | `8b1fb567aa21ffd5c907b59111c93eb4f67d7dba91bb57aff5e4b5804f30718a` | Rechazado sin tocar nada («actualiza la app») |
| **X6** sin `META` | `ROMF` · `SAVE`=`S1` | — | 32832 | `2ed4513a` | `c7f5650966a804b620f8d29a63766b2583d53a2a30384b030ceafe224b236a2e` | Rechazado sin tocar nada |
| **X7** `sav_sha256` ≠ `SAVE` | `ROMF` · `META` · `SAVE`=`S1` | 318 | 33158 | `9565865d` | `6d8b3af30e6fe76adf7fef36fc21f80a3db0a86d4df1a8faa00d573b07a39880` | Rechazado sin tocar nada |
| **X8** clave repetida (ND20 h) | `ROMF` · `META` · `SAVE`=`S1` | 345 | 33185 | `3b71a71a` | `08c1ad1b5c7422765a54d1e2d0bc19276c5feeb9db8d1fe49f7aadc0ebf7c95b` | Rechazado sin tocar nada (META inválida: `created_ms` aparece dos veces) |
| **G4** (arriba) | sección crítica `XTRA` | — | 93 | — | ver G4 | `PGBM_ERR_CRITICAL`: rechazado sin tocar nada («actualiza la app») |

`META` exacto de cada vector (ASCII, una línea, sin espacios; `R` = `10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388add2f71c41668b`):
```
X1 {"format":1,"rom_sha256":"R","sav_sha256":"S1","base_sav_sha256":"B","device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790003600000,"core":{"name":"gb","version":"1.0.0"},"state_of_sav_sha256":"S1","play_time_ms":7200000,"title":"Prueba cruzada","tags":["cruzado"]}
X2 {"format":1,"rom_sha256":"R","sav_sha256":"S2","base_sav_sha256":"S1","device":{"platform":"ios","name":"iPhone de prueba"},"created_ms":1790007200000,"core":{"name":"gb","version":"1.0.0"},"state_of_sav_sha256":"<pat(0x24, 32768)>"}
X3 {"format":1,"rom_sha256":"R","sav_sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","base_sav_sha256":null,"device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}
X4 {"format":1,"rom_sha256":"R","sav_sha256":"S4","base_sav_sha256":null,"device":{"platform":"ios","name":"iPhone de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}
X5 {"format":2,"rom_sha256":"R","sav_sha256":"S1","base_sav_sha256":null,"device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}
X7 {"format":1,"rom_sha256":"R","sav_sha256":"B","base_sav_sha256":null,"device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790000000000,"core":{"name":"gb","version":"1.0.0"}}
X8 {"format":1,"rom_sha256":"R","sav_sha256":"S1","base_sav_sha256":null,"device":{"platform":"android","name":"Pixel de prueba"},"created_ms":1790000000000,"created_ms":1790000000001,"core":{"name":"gb","version":"1.0.0"}}
```
(los nombres `R`, `S1`, `B`, `S2`, `S4` se sustituyen por su hex de 64 caracteres en minúsculas, entre las comillas). La referencia en Python de arriba los reproduce con `pgbm([(b"ROMF", ROM), (b"META", meta), (b"SAVE", sav), (b"STAT", st)])`.

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
Se usó para contrastar los hashes antes de que el C los produjera (la salida coincidió en los cuatro):
```python
import hashlib, json, struct, zlib
def pat(seed, n): return bytes((seed + i * 37 + (i >> 8) * 11) & 0xFF for i in range(n))
def pgbm(secs):                       # secs: [(tipo de 4 bytes, datos)], en el orden dado
    body = b"".join(t + struct.pack("<I", len(d)) + d for t, d in secs)
    pre = b"PGBM" + struct.pack("<HHI", 1, len(secs), 12 + len(body) + 4) + body
    return pre + struct.pack("<I", zlib.crc32(pre) & 0xFFFFFFFF)
ROM, SAVE, STAT = pat(0x10, 32), pat(0x21, 8192), pat(0x42, 1500)
PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + pat(0x63, 24)
sav_sha = hashlib.sha256(SAVE).hexdigest()
META = json.dumps({
    "format": 1, "rom_sha256": ROM.hex(), "sav_sha256": sav_sha, "base_sav_sha256": None,
    "device": {"platform": "ios", "name": "iPhone de prueba"}, "created_ms": 1790000000000,
    "core": {"name": "gb", "version": "1.0.0"}, "config": {"model": "cgb", "compat_palette": "default"},
    "state_of_sav_sha256": sav_sha, "play_time_ms": 3723000,
    "title": "Pokémon Rojo – prueba", "alias": "Mi partida", "tags": ["rpg", "prueba"],
    "milestones": [{"id": "badge1", "title": "Medalla Roca", "done": True},
                   {"id": "badge2", "title": "Medalla Cascada", "done": False}],
    "moment": {"name": "Antes del gimnasio", "collection": "Principal", "note": "Nota de prueba",
               "created_ms": 1789999000000}},
    separators=(",", ":"), ensure_ascii=True).encode("ascii")      # ensure_ascii: \u00e9, \u2013
g1 = pgbm([(b"ROMF", ROM), (b"META", META), (b"THMB", PNG), (b"SAVE", SAVE), (b"STAT", STAT)])
g2 = pgbm([(b"ROMF", ROM), (b"SAVE", pat(0x21, 16))])
g3 = pgbm([(b"ROMF", ROM), (b"xtra", b"hola!"), (b"SAVE", pat(0x21, 16))])
g4 = pgbm([(b"ROMF", ROM), (b"XTRA", b"hola!"), (b"SAVE", pat(0x21, 16))])
for g in (g1, g2, g3, g4): print(len(g), hashlib.sha256(g).hexdigest())
```

## Qué se prueba y cómo
| Prueba | Dónde | Qué cubre |
|---|---|---|
| Unitarias | `core/tests/unit_pgbm.c` (`make -C core test`, `asan`) | Ida y vuelta con cargas sintéticas aleatorias (incluido todo al tope a la vez); cada código de error; truncado en **cada** byte; corrupción de **cada** bit del paquete mínimo y de cada byte del completo; longitudes que se salen; duplicadas; faltas; UTF-8 (tabla de válidos e inválidos, en el lector y en el codificador); firma PNG; tipos desconocidos auxiliares y críticos (convención de PNG, con los vecinos de `A`–`Z`); topes exactos y +1; determinismo; valores numéricos de `pgbm_result`; vectores dorados (el `META` de G1 contra su esquema) |
| Fuzzer | `core/fuzz/fuzz_pgbm.c` (`make -C core fuzz-pgbm`, `fuzz-pgbm-smoke`) | Lector con arreglos de cabecera/CRC para llegar a la estructura; modo codificador contra un validador UTF-8/PNG/CRC independiente; invariantes «si se lee, se vuelve a escribir y se lee igual» y «nunca se acepta una sección crítica» (contra un recorrido propio) |
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
- **Convención de PNG para tipos desconocidos** (auditoría Opus, H2): mayúscula inicial = crítica (`PGBM_ERR_CRITICAL`), cualquier otra cosa = auxiliar. Se fija ahora, antes de que existan lectores v1 instalados que ignorarían todo lo desconocido.
- **`META v1` normativa** (H1): claves en inglés `snake_case`, huellas, linaje y vínculo `STAT`↔`SAVE` definidos arriba; el C sigue aceptando `META` opcional (valida el contenedor), el importador la exige.
- **Valores de `pgbm_result` explícitos** y fijados con `_Static_assert` y un test (H4).
