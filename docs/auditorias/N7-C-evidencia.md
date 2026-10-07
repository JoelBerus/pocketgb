# N7-C · Evidencia (núcleo: contenedor `.pgbm`, `pgbm_parse` / `pgbm_encode`)

Generada en el Mac de Joel (Darwin 25.5.0, Apple clang 21.0.0) el 2026-10-07, rama `n7-c-pgbm` desde `siguiente-nivel` (`e631cf5`). Formato y API: [12-formato-pgbm](../12-formato-pgbm.md) y `core/include/pocketgb_pgbm.h`. Plan: [N-README](../hitos/N-README.md) §3.3 y §3.4. **No se tocó** `ios/` ni `android/` (la integración es N7b) ni `core/include/module.modulemap`.

## Qué se añadió
- `core/include/pocketgb_pgbm.h` y `core/src/pgbm.c`: `pgbm_parse` (sin copiar), `pgbm_encoded_size`, `pgbm_encode`, `pgbm_result_name`. C11, sin I/O, sin `malloc`, sin estado global, determinista. El CRC-32 es el de `state.c` (`crc32_update`, declarado en `internal.h`): no se duplicó ningún símbolo. El SHA-256 de los vectores es el de `core/src/sha256.c`.
- `core/tests/unit_pgbm.c` (registrado en `unit.h` y `runner.c`) y `core/fuzz/fuzz_pgbm.c` + `core/fuzz/fuzz_pgbm.dict` (semillas en `gbtest --fuzz-seeds`).
- `core/Makefile`: `check-header` compila `pocketgb_pgbm.h` en C11 y C++17; objetivos `fuzz-pgbm` (libFuzzer) y `fuzz-pgbm-smoke` (sin libFuzzer); `fuzz` incluye `fuzz_pgbm`; `fuzz/corpus/fuzz_pgbm` en las listas de `mkdir` (sin eso `gbtest --fuzz-seeds` fallaría en los otros objetivos).
- `.githooks/pre-commit`: bloquea `*.pgbm` y cualquier archivo que empiece por el mágico «PGBM».
- Docs: `docs/12-formato-pgbm.md` (nuevo), y punteros en `docs/03-core-spec.md` y `docs/06-testing.md`. No se actualizaron `docs/ESTADO.md` ni la tabla de hitos: lo hace quien cierre N7 (evita choques entre lotes paralelos).
- **Diferencias con la API propuesta en el encargo** (justificadas en [12-formato-pgbm](../12-formato-pgbm.md) §Decisiones): tipo de sección `SAVE` (4 bytes exactos; el encargo decía «SAV»); `SAVE` puede ir **vacía** (juegos sin batería), distinta de la falta de la sección; dos códigos nuevos, `PGBM_ERR_ARG` y `PGBM_ERR_NOSPACE`; el archivo debe medir **exactamente** lo que dice la cabecera; tope de 64 secciones; orden de escritura canónico `ROMF, META, THMB, SAVE, STAT`; las constantes de topes son literales sencillos (Swift los importa).

## 1. Regresión del núcleo (tras `make -C core clean`)
```
$ make -C core test HITO=M9
M1    PASS  requerido   unit tests        10.8s  PASS: 11068 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
$ make -C core asan HITO=M9
M1    PASS  requerido   unit tests        20.2s  PASS: 11068 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
$ make -C core check-header check-globals
pocketgb.h compila aislado: OK
pocketgb_progress.h compila aislado (C11 y C++17): OK
pocketgb_pgbm.h compila aislado (C11 y C++17): OK
Sin estado global mutable: OK
```
Antes del lote (`e631cf5`, extraído con `git archive` a una carpeta temporal): 8772 comprobaciones unitarias, 157/157 requeridos. Ahora 11 068: **+2296** (la suite `pgbm`). Compilación sin avisos con `-std=c11 -Wall -Wextra -Werror -pedantic`; `pgbm.c` además limpio con `-Wconversion -Wshadow -Wcast-qual -Wmissing-prototypes`.

## 2. Pruebas unitarias (`core/tests/unit_pgbm.c`)
Todo sintético: bytes pseudoaleatorios deterministas (xorshift32) o la fórmula de relleno del documento; ninguna partida ni estado reales. Cada análisis de un paquete se hace sobre una copia del montón de **tamaño exacto** (ASan detecta cualquier lectura de más). Los paquetes mal formados se construyen byte a byte con CRC correcto (`raw_build`) para llegar a cada comprobación de estructura sin que el CRC la tape.

| Grupo | Qué comprueba |
|---|---|
| Ida y vuelta | 13 formas fijas (vacía, MBC2 512 B, mínimos de cada opcional, GB con RTC, GBA con RTC, **todos los topes a la vez**, cada tope solo) + 60 formas aleatorias: tamaño medido = tamaño escrito, spans iguales a la entrada, ausentes = `{NULL, 0}`, spans dentro del buffer, **leer y volver a escribir da los mismos bytes**, con más sitio no se escribe de más |
| Cabecera | NULL; vacío; 6 mágicos erróneos; versiones 0, 2, 3, 0x0100, 0x7FFF, 0xFFFF; **truncado en cada byte** de un paquete de 9857 B (todos `TRUNCATED`); un byte de más; longitud total 0, 15, 16, ±1, 4 MiB, 4 MiB + 1, `0xFFFFFFFF`; archivo de 12–15 bytes con su propia longitud; **los 8 bits de cada byte** del paquete de 64 B y 2 bits de cada byte del de 9857 B → nunca `OK`, con el código esperado por campo (mágico, versión, longitud, resto = `CRC`) |
| Estructura | orden libre; falta `ROMF`, `SAVE`, ambas, ninguna sección; la falta gana a un META inválido; duplicadas (las 5 conocidas); `ROMF` de 0/1/15/17/32 B; opcionales vacías (3); `SAVE` vacía válida; longitud que se sale (+1, `0xFFFFFFFF`, `0x7FFFFFFF` al principio, de menos), cuenta de secciones 0/1/3 con 2 reales, 3 y 5 bytes sueltos antes del CRC; tope exacto y +1 de cada sección; archivo de exactamente 4 MiB y 4 MiB + 1; 64 secciones sí y 65 no; tipos desconocidos (repetidos, vacíos, con tipos parecidos a los conocidos), el CRC los cubre; 7 tipos no ASCII; `meta` ≠ `META`; versión y CRC en paquetes bien formados; `*out` a cero tras un fallo |
| Contenido | tabla de 15 UTF-8 válidos (bordes U+0080, U+07FF, U+0800, U+D7FF, U+E000, U+FFFF, BOM, U+10000, U+10FFFF) y 23 inválidos (NUL, continuación suelta, sobrelargos de 2, 3 y 4 bytes, sustitutos, > U+10FFFF, F5–FF, cortados, continuación de más), **por el lector y por el codificador** (aceptan y rechazan lo mismo); firma PNG con longitudes 1–64 y con cada uno de los 8 bytes alterado |
| Codificador | NULL; `NOSPACE` (cap-1 y 0) sin tocar la salida; versión 0/1 sí, 2 y `0xFFFF` no; cada tope +1 → `TOO_LARGE` y tamaño 0; el tamaño se mide antes que el contenido; UTF-8 y PNG; determinismo y orden canónico `ROMF, META, THMB, SAVE, STAT` |
| Vectores dorados | G1, G2, G3 de [12-formato-pgbm](../12-formato-pgbm.md): longitud, CRC-32 y SHA-256 (`sha256.c`); G2 byte a byte contra la tabla del documento; G1 y G3 leídos con el contenido esperado |

### Las pruebas muerden (mutación manual)
Se fabricaron **43 mutantes** de `pgbm.c` (uno por comprobación: quitar el límite de una sección, la detección de duplicadas, el CRC, la longitud exacta, el tope de 64 secciones, la versión, la firma PNG, cada rango de UTF-8 —E0, ED, F0, F4, C0/C1, NUL, secuencia cortada—, ROMF/SAVE obligatorias, el rango de caracteres de un tipo, la comparación de los 4 bytes del tipo, el CRC y la versión que escribe el codificador, `cap-1`, etc.) y se corrió la suite con ASan/UBSan: **43 de 43 detectados**. Un primer pase dejó vivo un mutante (`total < 12` en lugar de `total < 16`: nada probaba un archivo de 12–15 bytes con su propia longitud) y se añadió esa prueba; el mutante equivalente de control sobrevivió, como debía, y no cuenta.

## 3. Vectores dorados (reproducibles en iOS y Android en N7b)
Generados en el test con `pat(semilla, i) = (semilla + 37·i + 11·(i >> 8)) mod 256`; receta completa y los bytes de G2 en [12-formato-pgbm](../12-formato-pgbm.md) §Vectores dorados.

| Vector | Longitud | CRC-32 | SHA-256 |
|---|---|---|---|
| G1 completo (5 secciones) | 9857 | `e477060f` | `617c82a522e01eddddba5606df1f383356d9f4ef93c0768d9d7e893dcf146cd7` |
| G2 mínimo (`ROMF` + `SAVE`) | 64 | `c42da47d` | `ea86e10010169eae94e57719e10230a15e1cbaf607fdeb650dc6eb0b352dcf75` |
| G3 con sección desconocida | 77 | `587f15d4` | `eba7c40e531741db94fc000ec90ed36774707357e9b181d05c8f40780d32f6a2` |

**Contraste independiente:** una referencia en Python (solo `struct`, `zlib.crc32` y `hashlib`, incluida en el documento) se escribió **antes** de poner los hashes en el test; el C y Python coinciden en los tres (longitud, CRC-32 y SHA-256), así que los valores no salen del propio codificador.
```
$ python3 pgbm_ref.py
G1 completo: len=9857 crc32=e477060f sha256=617c82a5…cf146cd7
G2 minimo: len=64 crc32=c42da47d sha256=ea86e100…352dcf75
G3 desconocida: len=77 crc32=587f15d4 sha256=eba7c40e…0d32f6a2
```

## 4. Fuzzing (`core/fuzz/fuzz_pgbm.c`)
Arnés: entrada `[indicadores][bytes]`. **Modo lector:** los indicadores reparan sobre una copia el mágico y la versión, la longitud total, el CRC-32 (tabla propia, independiente de `state.c`) y la cuenta de secciones, para llegar a cada rechazo de estructura y a la rama de éxito. **Modo codificador** (bit 7): huella, cuatro longitudes y datos, con opciones para forzar la firma PNG, un META UTF-8 válido o la versión 2; el resultado se contrasta con un validador UTF-8/PNG independiente de `pgbm.c` (decodifica el punto de código en lugar de usar rangos). Cada fragmento va a un bloque del montón de tamaño exacto. Invariantes con `abort()`: determinismo; fallo = salida a cero; spans dentro del buffer y de los topes; META UTF-8 sin NUL y THMB con firma; **si se lee, se vuelve a escribir (tamaño ≤ el del original), se vuelve a leer igual, dos escrituras dan los mismos bytes, la longitud y el CRC del resultado son correctos según el cálculo independiente, y con `cap-1` no se escribe nada**; en el modo codificador el resultado coincide con la predicción del validador independiente.

**libFuzzer no está en el Mac** (el clang de Apple no lo trae y no hay `llvm` de Homebrew): `make -C core fuzz-pgbm` termina con el mensaje del Makefile. Para no quedarse sin la campaña de 600 s se usó el libFuzzer compilado a mano en el lote N6-C (fuentes de LLVM 20.1.8, `libFuzzer.a` en la carpeta temporal de sesión, sin tocar el repo) y se enlazó con la misma instrumentación que usa el Makefile:
```
$ clang -std=c11 -g -O2 -fsanitize=fuzzer-no-link,address,undefined -fno-sanitize-recover=all \
      -Iinclude -Isrc fuzz/fuzz_pgbm.c src/*.c libFuzzer.a -lc++ -o fuzz_pgbm
$ ./fuzz_pgbm -max_total_time=600 -max_len=16384 -dict=fuzz/fuzz_pgbm.dict corpus     # = make fuzz-pgbm
Dictionary: 22 entries
INFO: seed corpus: files: 6 min: 47b max: 1074b total: 2389b
#7	INITED cov: 175 ft: 269 corp: 5/1315b exec/s: 0
...
#32373241	REDUCE cov: 269 ft: 745 corp: 220/22Kb lim: 16384 exec/s: 54226
Done 32579426 runs in 601 second(s)
```
Resultado (código final, `85677f2`): **32 579 426 ejecuciones en 601 s, sin crash, aborto, UB ni timeout** (ningún `crash-*` ni `timeout-*`; cero líneas `ERROR`/`SUMMARY` en el registro; corpus final de 220 entradas). **Las fugas no se comprobaron:** LeakSanitizer no existe en macOS/arm64 (`detect_leaks is not supported on this platform`); el arnés libera todo lo que reserva y `pgbm.c` no reserva memoria. Una campaña anterior de 600 s con una versión previa del arnés (sin diccionario ni la semilla UTF-8) dio 36 433 656 ejecuciones, también sin fallos.
- **Cobertura** de `pgbm.c` al reproducir ese corpus (`llvm-cov`, conductor `-DFUZZ_STANDALONE` con 0 s de mutación, 222 entradas): **91,36 % de líneas** (222 de 243), 94,79 % de regiones y 87,50 % de ramas. Lo que no alcanza el fuzzer (lo cubren las pruebas unitarias): `PGBM_ERR_ARG` por punteros NULL, los topes de `META`/`SAVE`/`STAT`/`THMB` (necesitan archivos de más de 64 KiB y el tope de entrada es 16 KiB), `pgbm_encoded_size` con spans NULL o sobre el tope, y `pgbm_result_name`.
- **Humo sin libFuzzer** (`make -C core fuzz-pgbm-smoke FUZZ_SECONDS=120`: el mismo archivo con un conductor propio, mutación aleatoria con ASan + UBSan, sin cobertura guiada): `fuzz_pgbm_smoke: 22086782 ejecuciones en 120 s, sin fallos`.
- **Pendiente**: la campaña de 600 s con el flujo oficial del Makefile (`make fuzz-pgbm` con el clang de Homebrew o el de Linux); lo que hay es la misma campaña con un libFuzzer compilado a mano.

**El arnés muerde:** tres mutantes de `pgbm.c` con el humo de 30 s (`FUZZ_STANDALONE`, ASan/UBSan) → los tres detectados: sin comprobar que una sección cabe (`heap-buffer-overflow`), UTF-8 que acepta sustitutos y lector que no exige la firma PNG (`abort()` del validador independiente). Con las semillas sin los bytes de borde y sin el operador de «bytes de borde» del conductor, el mutante de los sustitutos sobrevivió 20 s; por eso se añadieron esos bytes de borde al conductor, una semilla de modo codificador con UTF-8 de 1 a 4 bytes y un diccionario para libFuzzer (`core/fuzz/fuzz_pgbm.dict`).

## 5. Hook `pre-commit`
Prueba con paquetes sintéticos en el índice (G2 de 64 bytes; **nunca se commitearon**; se hizo `git reset` y se borraron):
```
$ git add prueba-hook.pgbm prueba-hook.dat prueba-hook.txt      # .dat = el mismo G2 renombrado; .txt = texto de control
$ bash .githooks/pre-commit; echo exit=$?                       # hook nuevo
pre-commit: bloqueado prueba-hook.dat (paquete .pgbm: contiene una partida)
pre-commit: bloqueado prueba-hook.pgbm (ROM/partida/paquete .pgbm/archivo comprimido)
exit=1
$ bash pre-commit.old; echo exit=$?                             # el hook de HEAD (antes del lote)
exit=0
$ git reset prueba-hook.pgbm prueba-hook.dat; bash .githooks/pre-commit; echo exit=$?   # solo el texto de control
exit=0
```
El hook anterior dejaba pasar los dos; el nuevo bloquea el `.pgbm` por la extensión y el renombrado por el mágico, y no da falsos positivos con un texto normal.

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
- **Ninguna integración con las apps** (N7b): ni el módulo de Swift, ni el JNI, ni que `ROMF` coincida con la huella real de las apps. El encargo define `ROMF` como la huella truncada a 16 bytes; el documento la describe como los 16 primeros bytes del SHA-256 del ROM, **sin confirmarlo contra `RomFingerprint` (iOS) ni la huella de Android**: N7b debe comprobarlo.
- **Los vectores dorados no se han reproducido todavía en Swift ni en Kotlin**; solo en C (el test) y en Python (referencia independiente).
- **libFuzzer no está en el Mac** (`make -C core fuzz-pgbm` termina con el mensaje del Makefile). La campaña de 600 s se hizo con un libFuzzer compilado a mano a partir de las fuentes de LLVM (las del lote N6-C), enlazado con la misma instrumentación; no es el flujo oficial del Makefile. Sigue pendiente repetirla con `make fuzz-pgbm` con el clang de Homebrew o el de Linux.
- **Mutación manual:** 43 mutantes elegidos a mano, no una herramienta de mutación exhaustiva.
- `META` solo se acota y se valida como UTF-8 sin NUL: **no se comprueba que sea JSON** ni su contenido (lo hacen las apps). `THMB` solo se comprueba por su firma: un PNG con cuerpo malicioso o dimensiones enormes lo decodifica el sistema (ImageIO/`BitmapFactory`), no el C. `STAT` y `SAVE` son opacos: los validan `gb_state_load`/`gba_state_load` y la lógica de importación de las apps.
- **`SAVE` vacía es válida** (decisión de este lote, para poder exportar momentos de juegos sin batería): una app que olvide pasar la partida produciría un paquete válido con `SAVE` vacía; el importador **debe** rechazar un tamaño que no encaje con el cartucho. Si el orquestador prefiere exigir ≥ 1 byte son dos líneas (`walk_sections` y `pgbm_encoded_size`).
- Rendimiento: no se midió; el lector recorre el archivo una vez para el CRC (4 MiB como máximo) y una vez las ≤ 64 secciones.
- `.gitignore` no incluye `*.pgbm` (fuera del alcance del lote: solo `core/`, el hook y `docs/`); el hook los bloquea al hacer commit, pero `git add -A` los dejaría visibles como no rastreados.

## 8. Para el lote de integración (N7b)
1. iOS: añadir `header "pocketgb_pgbm.h"` al `module.modulemap`. Android: `pgbm.c` al `CMakeLists.txt` y puente JNI (`pgbm_parse` sin copiar: pasar un `ByteBuffer` directo o copiar una vez al buffer nativo).
2. Escribir: rellenar `pgbm_view` (`rom_fp` = 16 primeros bytes del SHA-256 del ROM, `meta` = JSON UTF-8, `sav` = `gb_sram_save`/equivalente GBA, `state` y `thumb` opcionales), llamar a `pgbm_encoded_size`, reservar y `pgbm_encode`.
3. Leer: `pgbm_parse` sobre el archivo entero; en error, mostrar según el código (`TRUNCATED` = descarga incompleta, `CRC` = corrupto, `VERSION` = «actualiza la app», `MAGIC` = no es un paquete). En éxito, aplicar la lista de [12-formato-pgbm](../12-formato-pgbm.md) §Lo que debe hacer el importador (huella, exclusión por huella, tamaño de `SAVE`, backup, estado, JSON y PNG).
4. Reproducir G1 y G2 con las llamadas propias de cada plataforma y comparar el SHA-256; leer G1, G2 y G3.
5. Nunca incluir `.pgbm` reales en tests de las apps: construirlos con `pgbm_encode` y datos sintéticos.
