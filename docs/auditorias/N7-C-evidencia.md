# N7-C · Evidencia (núcleo: contenedor `.pgbm`, `pgbm_parse` / `pgbm_encode`)

Generada en el Mac de Joel (Darwin 25.5.0, Apple clang 21.0.0) el 2026-10-07, rama `n7-c-pgbm` desde `siguiente-nivel` (`e631cf5`). **Tercera versión (respuesta a la auditoría Opus, [N7-C-opus](N7-C-opus.md) y [N7-C-respuesta](N7-C-respuesta.md)):** `META v1` normativa, convención de secciones críticas (`PGBM_ERR_CRITICAL`), valores explícitos de `pgbm_result` y criterios nuevos en N7; todo lo de abajo se volvió a ejecutar con ese código (`a5d4451`). **Segunda versión del lote:** por indicación del coordinador `ROMF` lleva ahora el SHA-256 completo (32 bytes, no 16) y `.gitignore` ignora `*.pgbm`; en ella `ROMF` pasó a 32 bytes (`0ea10bd`). Formato y API: [12-formato-pgbm](../12-formato-pgbm.md) y `core/include/pocketgb_pgbm.h`. Plan: [N-README](../hitos/N-README.md) §3.3 y §3.4. **No se tocó** `ios/` ni `android/` (la integración es N7b) ni `core/include/module.modulemap`.

## Qué se añadió
- `core/include/pocketgb_pgbm.h` y `core/src/pgbm.c`: `pgbm_parse` (sin copiar), `pgbm_encoded_size`, `pgbm_encode`, `pgbm_result_name`. C11, sin I/O, sin `malloc`, sin estado global, determinista. El CRC-32 es el de `state.c` (`crc32_update`, declarado en `internal.h`): no se duplicó ningún símbolo. El SHA-256 de los vectores es el de `core/src/sha256.c`.
- `core/tests/unit_pgbm.c` (registrado en `unit.h` y `runner.c`) y `core/fuzz/fuzz_pgbm.c` + `core/fuzz/fuzz_pgbm.dict` (semillas en `gbtest --fuzz-seeds`).
- `core/Makefile`: `check-header` compila `pocketgb_pgbm.h` en C11 y C++17; objetivos `fuzz-pgbm` (libFuzzer) y `fuzz-pgbm-smoke` (sin libFuzzer); `fuzz` incluye `fuzz_pgbm`; `fuzz/corpus/fuzz_pgbm` en las listas de `mkdir` (sin eso `gbtest --fuzz-seeds` fallaría en los otros objetivos).
- `.githooks/pre-commit`: bloquea `*.pgbm` y cualquier archivo que empiece por el mágico «PGBM». `.gitignore`: `*.pgbm` (añadido por indicación del coordinador).
- Docs: `docs/12-formato-pgbm.md` (nuevo; incluye el esquema `META v1`), y punteros en `docs/03-core-spec.md` y `docs/06-testing.md`; en `docs/hitos/N-README.md` los criterios de N7 de la auditoría (H3). No se actualizaron `docs/ESTADO.md` ni la tabla de hitos: lo hace quien cierre N7 (evita choques entre lotes paralelos).
- **Diferencias con la API propuesta en el encargo** (justificadas en [12-formato-pgbm](../12-formato-pgbm.md) §Decisiones): **`ROMF` = SHA-256 completo de 32 bytes** (`pgbm_view.rom_fp[32]`; el encargo decía 16 y el coordinador lo cambió porque el núcleo expone `fingerprint[32]`, iOS usa los 16 primeros como clave interna y Android los 32; cada app compara en su forma); tipo de sección `SAVE` (4 bytes exactos; el encargo decía «SAV»); `SAVE` puede ir **vacía** (juegos sin batería), distinta de la falta de la sección; dos códigos nuevos, `PGBM_ERR_ARG` y `PGBM_ERR_NOSPACE`; el archivo debe medir **exactamente** lo que dice la cabecera; tope de 64 secciones; orden de escritura canónico `ROMF, META, THMB, SAVE, STAT`; las constantes de topes son literales sencillos (Swift los importa).

## 1. Regresión del núcleo (tras `make -C core clean`)
```
$ make -C core test HITO=M9
M1    PASS  requerido   unit tests        11.3s  PASS: 11300 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
$ make -C core asan HITO=M9
M1    PASS  requerido   unit tests        21.1s  PASS: 11300 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
$ make -C core check-header check-globals
pocketgb.h compila aislado: OK
pocketgb_progress.h compila aislado (C11 y C++17): OK
pocketgb_pgbm.h compila aislado (C11 y C++17): OK
Sin estado global mutable: OK
```
Antes del lote (`e631cf5`, extraído con `git archive` a una carpeta temporal): 8772 comprobaciones unitarias, 157/157 requeridos. Ahora 11 300: **+2528** (la suite `pgbm`). Compilación sin avisos con `-std=c11 -Wall -Wextra -Werror -pedantic`; `pgbm.c` además limpio con `-Wconversion -Wshadow -Wcast-qual -Wmissing-prototypes`.

## 2. Pruebas unitarias (`core/tests/unit_pgbm.c`)
Todo sintético: bytes pseudoaleatorios deterministas (xorshift32) o la fórmula de relleno del documento; ninguna partida ni estado reales. Cada análisis de un paquete se hace sobre una copia del montón de **tamaño exacto** (ASan detecta cualquier lectura de más). Los paquetes mal formados se construyen byte a byte con CRC correcto (`raw_build`) para llegar a cada comprobación de estructura sin que el CRC la tape.

| Grupo | Qué comprueba |
|---|---|
| Ida y vuelta | 13 formas fijas (vacía, MBC2 512 B, mínimos de cada opcional, GB con RTC, GBA con RTC, **todos los topes a la vez**, cada tope solo) + 60 formas aleatorias: tamaño medido = tamaño escrito, spans iguales a la entrada, ausentes = `{NULL, 0}`, spans dentro del buffer, **leer y volver a escribir da los mismos bytes**, con más sitio no se escribe de más |
| Cabecera | NULL; vacío; 6 mágicos erróneos; versiones 0, 2, 3, 0x0100, 0x7FFF, 0xFFFF; **truncado en cada byte** de un paquete de 10 614 B (el G1; todos `TRUNCATED`); un byte de más; longitud total 0, 15, 16, ±1, 4 MiB, 4 MiB + 1, `0xFFFFFFFF`; archivo de 12–15 bytes con su propia longitud; **los 8 bits de cada byte** del paquete de 80 B y 2 bits de cada byte del de 10 614 B → nunca `OK`, con el código esperado por campo (mágico, versión, longitud, resto = `CRC`) |
| Estructura | orden libre; falta `ROMF`, `SAVE`, ambas, ninguna sección; la falta gana a un META inválido; duplicadas (las 5 conocidas); `ROMF` de 0/1/15/**16**/31/33/64 B (16 era la longitud del primer diseño: ahora es `BOUNDS`); opcionales vacías (3); `SAVE` vacía válida; longitud que se sale (+1, `0xFFFFFFFF`, `0x7FFFFFFF` al principio, de menos), cuenta de secciones 0/1/3 con 2 reales, 3 y 5 bytes sueltos antes del CRC; tope exacto y +1 de cada sección; archivo de exactamente 4 MiB y 4 MiB + 1; 64 secciones sí y 65 no; tipos desconocidos **auxiliares** (repetidos, vacíos, con la mayúscula/minúscula de la primera letra cambiada respecto a los conocidos), el CRC los cubre; **tipos críticos** (convención de PNG, H2): 12 tipos con mayúscula inicial → `CRITICAL` y 12 con los vecinos de `A`–`Z` (`@`, `[`, `` ` ``, `{`, dígitos, signos, minúsculas) → `OK`, crítica vacía, primera, única, y precedencia frente a `BOUNDS`, `DUPLICATE`, `CRC` y `VERSION`; 7 tipos no ASCII; `meta` ≠ `META`; versión y CRC en paquetes bien formados; `*out` a cero tras un fallo |
| Contenido | tabla de 15 UTF-8 válidos (bordes U+0080, U+07FF, U+0800, U+D7FF, U+E000, U+FFFF, BOM, U+10000, U+10FFFF) y 23 inválidos (NUL, continuación suelta, sobrelargos de 2, 3 y 4 bytes, sustitutos, > U+10FFFF, F5–FF, cortados, continuación de más), **por el lector y por el codificador** (aceptan y rechazan lo mismo); firma PNG con longitudes 1–64 y con cada uno de los 8 bytes alterado |
| Codificador | NULL; `NOSPACE` (cap-1 y 0) sin tocar la salida; versión 0/1 sí, 2 y `0xFFFF` no; cada tope +1 → `TOO_LARGE` y tamaño 0; el tamaño se mide antes que el contenido; UTF-8 y PNG; determinismo y orden canónico `ROMF, META, THMB, SAVE, STAT` |
| Valores de `pgbm_result` (H4) | los 14 códigos contra **literales** (0–13) y su nombre, `(pgbm_result)14` → `PGBM_ERR_UNKNOWN`, y las constantes que copian las apps (versión, `ROMF` = 32, topes) |
| Vectores dorados | G1, G2, G3 y G4 de [12-formato-pgbm](../12-formato-pgbm.md): longitud, CRC-32 y SHA-256 (`sha256.c`); G2 byte a byte contra la tabla del documento; G1 y G3 leídos con el contenido esperado y G4 rechazado con `CRITICAL`; **el `META` de G1 sigue el esquema**: ASCII puro, `rom_sha256` = hex de `ROMF`, `sav_sha256` y `state_of_sav_sha256` = SHA-256 de `SAVE` |

### Las pruebas muerden (mutación manual)
Se fabricaron **51 mutantes** de `pgbm.c` (uno por comprobación: quitar el límite de una sección, la detección de duplicadas, el CRC, la longitud exacta, el tope de 64 secciones, la versión, la firma PNG, cada rango de UTF-8 —E0, ED, F0, F4, C0/C1, NUL, secuencia cortada—, ROMF/SAVE obligatorias, el rango de caracteres de un tipo, la comparación de los 4 bytes del tipo, el CRC y la versión que escribe el codificador, `cap-1`, etc.; y, tras la auditoría, **8 de la convención de secciones críticas**: quitar el rechazo, ampliar o recortar el rango `A`–`Z` por cualquiera de los dos extremos, incluir minúsculas, devolver `VERSION` en lugar de `CRITICAL` y un nombre mal escrito) y se corrió la suite con ASan/UBSan: **51 de 51 detectados**. Un primer pase del lote (todavía con 43) dejó vivo un mutante (`total < 12` en lugar de `total < 16`: nada probaba un archivo de 12–15 bytes con su propia longitud) y se añadió esa prueba; el mutante equivalente de control sobrevivió, como debía, y no cuenta. **H4 comprobado a mano:** con `PGBM_ERR_CRC` y `PGBM_ERR_DUPLICATE` intercambiados en el header, `pgbm.c` no compila (`static assertion failed due to requirement 'PGBM_ERR_CRC == 5'`); si además se cambia el `_Static_assert`, falla `test_result_values` (3 fallos).

## 3. Vectores dorados (reproducibles en iOS y Android en N7b)
Generados en el test (`ROMF` = `pat(0x10, 32)`) con `pat(semilla, i) = (semilla + 37·i + 11·(i >> 8)) mod 256`; receta completa y los bytes de G2 en [12-formato-pgbm](../12-formato-pgbm.md) §Vectores dorados.

| Vector | Longitud | CRC-32 | SHA-256 |
|---|---|---|---|
| G1 completo (5 secciones, `META` v1 de 802 B) | 10614 | `49b7b519` | `e83ee087bd870c6b395bca974654ce25f799839b3e77f6865b01f142bc16ab4d` |
| G2 mínimo (`ROMF` + `SAVE`) | 80 | `fe827d35` | `5548b8cac9de16d52d17aec2907fd61832443bdebb4dc747499f726da9ef41c9` |
| G3 con sección **auxiliar** desconocida (`xtra`): se ignora | 93 | `0a62ba0a` | `1d61e5c030c7855a197e5b4b05e7417d7c452d4f2accf5441d494e84cb96a261` |
| G4 con sección **crítica** desconocida (`XTRA`): `PGBM_ERR_CRITICAL` | 93 | `03717a59` | `b6b3f3e96df34995d4bdf171e368acae20f5b72bb512b824fae509d3979a4a53` |

G2 no cambió respecto a la versión anterior (el `META` solo afecta a G1); G4 es el antiguo G3 con otro nombre (mismo hash: continuidad con la referencia). El `META` de G1 sigue ahora el esquema `META v1` en una sola línea de ASCII puro, con los escapes `\u00e9` y `\u2013`, para no depender de la normalización Unicode del fuente de cada plataforma.

**Contraste independiente:** una referencia en Python (solo `struct`, `zlib.crc32` y `hashlib`, incluida en el documento) se escribió **antes** de poner los hashes en el test; el C y Python coinciden en los cuatro (longitud, CRC-32 y SHA-256), así que los valores no salen del propio codificador.
```
$ python3 pgbm_ref2.py
G1: len=10614 crc32=49b7b519 sha256=e83ee087…16ab4d
G2: len=80 crc32=fe827d35 sha256=5548b8ca…ef41c9
G3: len=93 crc32=0a62ba0a sha256=1d61e5c0…96a261
G4: len=93 crc32=03717a59 sha256=b6b3f3e9…79a4a53
```

## 4. Fuzzing (`core/fuzz/fuzz_pgbm.c`)
Arnés: entrada `[indicadores][bytes]`. **Modo lector:** los indicadores reparan sobre una copia el mágico y la versión, la longitud total, el CRC-32 (tabla propia, independiente de `state.c`) y la cuenta de secciones, para llegar a cada rechazo de estructura y a la rama de éxito. **Modo codificador** (bit 7): huella, cuatro longitudes y datos, con opciones para forzar la firma PNG, un META UTF-8 válido o la versión 2; el resultado se contrasta con un validador UTF-8/PNG independiente de `pgbm.c` (decodifica el punto de código en lugar de usar rangos). Cada fragmento va a un bloque del montón de tamaño exacto. Invariantes con `abort()`: determinismo; fallo = salida a cero; spans dentro del buffer y de los topes; META UTF-8 sin NUL y THMB con firma; **si se lee, se vuelve a escribir (tamaño ≤ el del original), se vuelve a leer igual, dos escrituras dan los mismos bytes, la longitud y el CRC del resultado son correctos según el cálculo independiente, y con `cap-1` no se escribe nada**; en el modo codificador el resultado coincide con la predicción del validador independiente.

**libFuzzer no está en el Mac** (el clang de Apple no lo trae y no hay `llvm` de Homebrew): `make -C core fuzz-pgbm` termina con el mensaje del Makefile. Para no quedarse sin la campaña de 600 s se usó el libFuzzer compilado a mano en el lote N6-C (fuentes de LLVM 20.1.8, `libFuzzer.a` en la carpeta temporal de sesión, sin tocar el repo) y se enlazó con la misma instrumentación que usa el Makefile:
```
$ clang -std=c11 -g -O2 -fsanitize=fuzzer-no-link,address,undefined -fno-sanitize-recover=all \
      -Iinclude -Isrc fuzz/fuzz_pgbm.c src/*.c libFuzzer.a -lc++ -o fuzz_pgbm
$ ./fuzz_pgbm -max_total_time=600 -max_len=16384 -dict=fuzz/fuzz_pgbm.dict corpus     # = make fuzz-pgbm
Dictionary: 27 entries
INFO: seed corpus: files: 7 min: 63b max: 1831b total: 4061b
#7	INITED cov: ... (corpus de 7 semillas)
...
#28976810	REDUCE cov: 280 ft: 763 corp: 252/53Kb lim: 16384 exec/s: 48294
Done 28992165 runs in 601 second(s)
```
Resultado (código de `a5d4451`; `LLVMFuzzerTestOneInput` incluye el invariante de las secciones críticas): **28 992 165 ejecuciones en 601 s, sin crash, aborto, UB ni timeout** (ningún `crash-*` ni `timeout-*`; cero líneas `ERROR`/`SUMMARY` en el registro; corpus final de 254 entradas). Campañas anteriores de 600 s del lote (34 934 938 ejecuciones con `ROMF` de 32 bytes; 36 433 656 y 32 579 426 con el de 16) también salieron sin fallos. **Las fugas no se comprobaron:** LeakSanitizer no existe en macOS/arm64 (`detect_leaks is not supported on this platform`); el arnés libera todo lo que reserva y `pgbm.c` no reserva memoria. Campañas anteriores de 600 s con `ROMF` de 16 bytes (36 433 656 y 32 579 426 ejecuciones) también salieron sin fallos.
- **Cobertura** de `pgbm.c` al reproducir ese corpus (`llvm-cov`, conductor `-DFUZZ_STANDALONE` con 0 s de mutación, 254 entradas): **91,43 % de líneas** (224 de 245), 94,89 % de regiones y 87,84 % de ramas; la rama de `PGBM_ERR_CRITICAL` sí se alcanza. Lo que no alcanza el fuzzer (lo cubren las pruebas unitarias): `PGBM_ERR_ARG` por punteros NULL, los topes de `META`/`SAVE`/`STAT`/`THMB` (necesitan archivos de más de 64 KiB y el tope de entrada es 16 KiB), `pgbm_encoded_size` con spans NULL o sobre el tope, y `pgbm_result_name`.
- **Humo sin libFuzzer** (`make -C core fuzz-pgbm-smoke FUZZ_SECONDS=120`: el mismo archivo con un conductor propio, mutación aleatoria con ASan + UBSan, sin cobertura guiada): `fuzz_pgbm_smoke: 15680525 ejecuciones en 120 s, sin fallos`.
- **Pendiente**: la campaña de 600 s con el flujo oficial del Makefile (`make fuzz-pgbm` con el clang de Homebrew o el de Linux); lo que hay es la misma campaña con un libFuzzer compilado a mano.

**El arnés muerde:** cuatro mutantes de `pgbm.c` con el humo (`FUZZ_STANDALONE`, ASan/UBSan, 20 s × 3 intentos cada uno) → los cuatro detectados en los 3 intentos: sin comprobar que una sección cabe (`heap-buffer-overflow`), UTF-8 que acepta sustitutos, lector que no exige la firma PNG y **lector que no rechaza las secciones críticas** (`abort()` del validador y del recorrido independientes). Con un conductor anterior el mutante de los sustitutos sobrevivió 30 s en una pasada (una secuencia `ED A0..BF xx` exige tres bytes seguidos acertados); por eso el conductor tiene un operador con secuencias UTF-8/PNG en el borde de la validez (lo mismo que el diccionario hace en libFuzzer: `core/fuzz/fuzz_pgbm.dict`, ahora con tipos auxiliares/críticos y sus vecinos), una semilla de modo codificador con UTF-8 de 1 a 4 bytes y una semilla G4.

## 5. Hook `pre-commit`
Prueba con paquetes sintéticos en el índice (G2 de 80 bytes; **nunca se commitearon**; se hizo `git reset` y se borraron):
```
$ git add -f prueba-hook.pgbm prueba-hook.dat prueba-hook.txt   # -f: *.pgbm ya está en .gitignore; .dat = el mismo G2 renombrado; .txt = texto de control
$ bash .githooks/pre-commit; echo exit=$?                       # hook nuevo
pre-commit: bloqueado prueba-hook.dat (paquete .pgbm: contiene una partida)
pre-commit: bloqueado prueba-hook.pgbm (ROM/partida/paquete .pgbm/archivo comprimido)
exit=1
$ bash pre-commit.old; echo exit=$?                             # el hook de e631cf5 (antes del lote)
exit=0
$ git reset prueba-hook.pgbm prueba-hook.dat; bash .githooks/pre-commit; echo exit=$?   # solo el texto de control
exit=0
```
El hook anterior dejaba pasar los dos; el nuevo bloquea el `.pgbm` por la extensión y el renombrado por el mágico, y no da falsos positivos con un texto normal. Con `*.pgbm` en `.gitignore`, un `.pgbm` suelto ya no sale como no rastreado y `git add` exige `-f`; el hook sigue bloqueando ese caso:
```
$ git check-ignore -v probe-ignore.pgbm
.gitignore:9:*.pgbm	probe-ignore.pgbm
```

## 6. Compilación para las apps (sin tocarlas)
`pgbm.c` compila con `-std=c11 -Wall -Wextra -Werror -pedantic -O2` (con `-fPIC` en Android) y no deja símbolos de datos mutables (`nm`, sin `b/B/d/D`):
```
iOS OK  arm64-apple-ios26.0 (sdk iphoneos) nm: 0 símbolos de datos mutables
iOS OK  arm64-apple-ios26.0-simulator (sdk iphonesimulator) nm: 0 símbolos de datos mutables
iOS OK  x86_64-apple-ios26.0-simulator (sdk iphonesimulator) nm: 0 símbolos de datos mutables
NDK27 OK aarch64-linux-android26 nm: 0 símbolos de datos mutables
NDK27 OK armv7a-linux-androideabi26 nm: 0 símbolos de datos mutables
NDK27 OK x86_64-linux-android26 nm: 0 símbolos de datos mutables
NDK27 OK i686-linux-android26 nm: 0 símbolos de datos mutables
```
Hallazgo que se corrigió: con el NDK (PIC) la tabla de punteros de `pgbm_result_name` iba a `.data.rel.ro` (`nm` la mostraba como `d`) y `make check-globals` la habría tomado por estado mutable; ahora los nombres y los tipos de sección son matrices de `char` (sin relocaciones) y la comprobación sale limpia con `-O0/-O2/-Os` en los cuatro ABI. No se ejecutó `xcodebuild` ni Gradle.
- **iOS:** el proyecto referencia `core/src` como carpeta sincronizada, así que `pgbm.c` entra solo; falta exponer la API a Swift añadiendo `header "pocketgb_pgbm.h"` a `core/include/module.modulemap` (no se tocó).
- **Android:** `android/app/src/main/cpp/CMakeLists.txt` lista las fuentes a mano: habrá que añadir `"${CORE_ROOT}/src/pgbm.c"` y el puente JNI.

## 7. Lo que NO está verificado
- **Ninguna integración con las apps** (N7b): ni el módulo de Swift, ni el JNI. Que `ROMF` sea el SHA-256 completo de 32 bytes (`gb_rom_info.fingerprint[32]` / `gba_rom_info.fingerprint[32]`, comprobado en `pocketgb.h` y `pocketgba.h`) lo indicó el coordinador; **no se comprobó** en el código de las apps cómo comparan la huella (iOS con los 16 primeros como clave interna, Android con los 32): N7b debe confirmarlo. Un lector del primer diseño (16 bytes) rechazaría estos paquetes con `BOUNDS`; no hay paquetes de 16 bytes en circulación.
- **Los vectores dorados no se han reproducido todavía en Swift ni en Kotlin**; solo en C (el test) y en Python (referencia independiente).
- **`META v1` es una especificación, no código:** el C no la valida (solo UTF-8 sin NUL) y todavía no la implementa ninguna app. Lo único comprobado por máquina es que el `META` de G1 es ASCII puro, que `rom_sha256` coincide con `ROMF` y que `sav_sha256`/`state_of_sav_sha256` son el SHA-256 de `SAVE`. Los tipos, límites (p. ej. ≤ 64 etiquetas, ≤ 256 hitos) y las enumeraciones de `config` son decisiones de este lote tomadas a partir del encargo del coordinador y de los tipos del núcleo (`gb_model`, `gba_save_type`, `GBA_RTC_*`), no contrastadas con los modelos de datos de las apps: N7b puede encontrar que alguno no encaja y habrá que ajustar el documento (el esquema tiene su propio `format`).
- **Cómo trata `SAVE` vacía el importador no está probado**: es un criterio de N7 (Swift y JVM), añadido a `N-README` (H3). El C solo garantiza el contenedor.
- **libFuzzer no está en el Mac** (`make -C core fuzz-pgbm` termina con el mensaje del Makefile). La campaña de 600 s se hizo con un libFuzzer compilado a mano a partir de las fuentes de LLVM (las del lote N6-C), enlazado con la misma instrumentación; no es el flujo oficial del Makefile. Sigue pendiente repetirla con `make fuzz-pgbm` con el clang de Homebrew o el de Linux.
- **Mutación manual:** 51 mutantes elegidos a mano, no una herramienta de mutación exhaustiva.
- `META` solo se acota y se valida como UTF-8 sin NUL: **no se comprueba que sea JSON** ni su contenido (lo hacen las apps). `THMB` solo se comprueba por su firma: un PNG con cuerpo malicioso o dimensiones enormes lo decodifica el sistema (ImageIO/`BitmapFactory`), no el C. `STAT` y `SAVE` son opacos: los validan `gb_state_load`/`gba_state_load` y la lógica de importación de las apps.
- **`SAVE` vacía es válida** (decisión de este lote, para poder exportar momentos de juegos sin batería): una app que olvide pasar la partida produciría un paquete válido con `SAVE` vacía; el importador **debe** rechazar un tamaño que no encaje con el cartucho. Si el orquestador prefiere exigir ≥ 1 byte son dos líneas (`walk_sections` y `pgbm_encoded_size`).
- Rendimiento: no se midió; el lector recorre el archivo una vez para el CRC (4 MiB como máximo) y una vez las ≤ 64 secciones.

## 8. Para el lote de integración (N7b)
1. iOS: añadir `header "pocketgb_pgbm.h"` al `module.modulemap`. Android: `pgbm.c` al `CMakeLists.txt` y puente JNI (`pgbm_parse` sin copiar: pasar un `ByteBuffer` directo o copiar una vez al buffer nativo).
2. Escribir (siempre con `META` según `META v1`): rellenar `pgbm_view` (`rom_fp` = el SHA-256 completo del ROM, 32 bytes, p. ej. `gb_rom_info.fingerprint`; `meta` = JSON UTF-8, `sav` = `gb_sram_save`/equivalente GBA, `state` y `thumb` opcionales), llamar a `pgbm_encoded_size`, reservar y `pgbm_encode`.
3. Leer: `pgbm_parse` sobre el archivo entero; en error, mostrar según el código (`TRUNCATED` = descarga incompleta, `CRC` = corrupto, `VERSION` y `CRITICAL` = «actualiza la app», `MAGIC` = no es un paquete). Mapear los códigos **por número** (0–13, fijados por `_Static_assert` y por un test). En éxito, aplicar la lista de [12-formato-pgbm](../12-formato-pgbm.md) §Lo que debe hacer el importador (huella, exclusión por huella, tamaño de `SAVE`, backup, estado, JSON y PNG).
4. Reproducir G1 y G2 con las llamadas propias de cada plataforma y comparar el SHA-256; leer G1, G2 y G3 y comprobar que G4 da `PGBM_ERR_CRITICAL`; parsear el `META` de G1 con el lector de `META v1` de cada app y comprobar las reglas de importación (`rom_sha256` = `ROMF`, `sav_sha256` = SHA-256 de `SAVE`).
5. Nunca incluir `.pgbm` reales en tests de las apps: construirlos con `pgbm_encode` y datos sintéticos.
