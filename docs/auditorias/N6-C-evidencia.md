# N6-C · Evidencia (núcleo: lector de progreso de Pokémon `pgb_progress_read`)

Generada en el Mac de Joel (Darwin 25.5.0, Apple clang 21.0.0) el 2026-10-06, rama `n6-c-progreso` desde `siguiente-nivel` (`c3d8405`). Lote C de la Ola 1 de N6 ([N-README](../hitos/N-README.md) §3.3 «Lector Pokémon», decisión ND5). Solo toca `core/` y `docs/`; la integración en iOS y Android es otro lote.

## Qué se añadió
- `core/include/pocketgb_progress.h` y `core/src/progress_pokemon.c`: `pgb_progress_identify` y `pgb_progress_read` (API y reglas en [03-core-spec](../03-core-spec.md) §Lector de progreso Pokémon).
- `core/tests/unit_progress.c` (registrado en `unit.h` y `runner.c`; partidas sintéticas) y `core/fuzz/fuzz_progress.c` (+ semillas en `gbtest --fuzz-seeds`).
- `core/Makefile`: las cabeceras de `include/` entran en las dependencias; `check-header` compila `pocketgb_progress.h` en C11 y C++17; objetivos `fuzz-progress` (libFuzzer) y `fuzz-progress-smoke` (sin libFuzzer); `make fuzz` incluye el fuzzer nuevo.
- `docs/auditorias/N6-C-verificar-offsets.py`: script que recalcula cada desplazamiento desde pret y lo compara con las constantes de `progress_pokemon.c`.
- Diferencias con la API propuesta en el encargo: `player_name[32]` en lugar de `[24]` (10 glifos de 3 bytes UTF-8, p. ej. ♂/♀, ocupan 30 bytes + NUL); se añade `pgb_progress_identify` (solo cabecera, útil para elegir la plantilla de hitos sin tener la partida); `badges_count` y el resto de campos como en el encargo.

## 1. Desplazamientos: tabla y fuente verificada
Posiciones en el `.sav` de las versiones internacionales (SRAM banco 1 = archivo + `0x2000`). **Verificado** significa: calculado evaluando los `.asm` de pret con [N6-C-verificar-offsets.py](N6-C-verificar-offsets.py) (sección 2) **y** coincidente con Data Crystal («RAM map», direcciones INT) y/o PKHeX (solo los números; GPLv3, no se copia código).

| Dato | Rojo/Azul/Amarillo | Oro/Plata | Cristal | Fuente (pret) | Contraste |
|---|---|---|---|---|---|
| Nombre (11 B, `NAME_LENGTH` = 11) | `0x2598` | `0x200B` | `0x200B` | `sPlayerName` (`ds $598` en «Save Data»); `wPlayerName` = `wGameData` + 2 | Data Crystal `A598–A5A2` (SRAM, R/B), `D1A3` (G/S) y `D47D` (C) en la WRAM; PKHeX `OT = 0x2598` (1.ª gen) y `Trainer1 = 0x2009` (2.ª gen, inicio del bloque; el nombre va 2 bytes después) |
| Pokédex capturados | `0x25A3` (19 B) | `0x2A4C` (32 B) | `0x2A27` (32 B) | `wPokedexOwned` = `wMainDataStart`; `wPokedexCaught` | PKHeX `DexCaught`, `PokedexCaught`; Data Crystal `D2F7` (R/B), `DBE4` (G/S), `DE99` (C) |
| Pokédex vistos | `0x25B6` | `0x2A6C` | `0x2A47` | `wPokedexSeen` | ídem |
| Dinero | `0x25F3` (3 B BCD) | `0x23DB` (3 B BE) | `0x23DC` (3 B BE) | `wPlayerMoney` / `wMoney` | PKHeX `Money`; Data Crystal `D347` (R/B), `D573` (G/S), `D84F–D850` (C; la página omite el primer byte) |
| Medallas | `0x2602` (1 B) | `0x23E4` Johto + `0x23E5` Kanto | `0x23E5` + `0x23E6` | `wObtainedBadges` / `wJohtoBadges`, `wKantoBadges` | PKHeX `Badges`, `JohtoBadges`; Data Crystal `D356`, `D57C`, `D857` |
| Tiempo | `0x2CED`: horas, máx., min, s, fotogramas | `0x2053`: horas (2 B BE), min, s, fotogramas | `0x2052` | `wPlayTimeHours…` / `wGameTimeHours…` | PKHeX `PlayTime`, `TimePlayed`; Data Crystal `DA40–DA44` (R/B: documenta horas y minutos como palabras de 2 B; con pret, `DA40` es relleno y `DA42` es el byte «máximo»), `D1EB–D1EF` (G/S), `D4C4–D4C8` (C) |
| Rango del checksum | `0x2598..0x3522` | `0x2009..0x2D68` | `0x2009..0x2B82` | `sGameData`…`sGameDataEnd` | PKHeX `AccumulatedChecksumEnd`; copia de 0xB7A bytes en `SAV2.cs` |
| Checksum | `0x3523` (1 B) | `0x2D69` (2 B LE) | `0x2D0D` (2 B LE) | `sMainDataCheckSum` / `sChecksum` | PKHeX `ChecksumOfs`, `OverallChecksumPosition`; Data Crystal `B523` |
| Bytes de validación (2.ª gen) | — | `0x2008` = 99, `0x2D6B` = 127 | `0x2008`, `0x2D0F` | `sCheckValue1/2`, `SAVE_CHECK_VALUE_1/2` | solo pret (`CheckPrimarySaveFile`) |

Fórmulas (pret `engine/menus/save.asm`): 1.ª gen `CalcCheckSum` = complemento a uno de la suma de 8 bits de `sGameData`…`sGameDataEnd`; 2.ª gen `Checksum` = suma de 16 bits de `sGameData`…`sGameDataEnd` comparada con `sChecksum` (byte bajo en `+0`). Contadores de tiempo: `engine/play_time.asm` (1.ª gen: 1 byte por campo; `wPlayTimeMaxed` = `$FF` al llegar a 255 h) y `home/game_time.asm` (2.ª gen: horas en 2 bytes big-endian, tope 999:59:59). Títulos de cabecera: `Makefile` de cada repo (`rgbfix -t "POKEMON RED"`, `"POKEMON BLUE"`, `"POKEMON YELLOW"`, `POKEMON_GLD`, `POKEMON_SLV`, `PM_CRYSTAL`; todos con `-j`, destino no japonés).

**Discrepancias con los valores del encargo: ninguna** (todos los offsets del encargo coinciden con el cálculo de pret y con PKHeX/Data Crystal). Lo que se añadió o precisó:
- El título solo no distingue las ediciones japonesas de la 1.ª generación («POKEMON RED/BLUE/YELLOW» también en Japón): se exige además `rom[0x14A] == 1`. PKHeX confirma que la disposición japonesa es otra (`Gen12/SAV1Offsets.cs`: checksum en `0x3594`, nombre de otro tamaño).
- Nombre: 11 bytes (10 glifos + terminador `0x50`) en las dos generaciones internacionales; se exige el terminador.
- Cristal: entre el fin de los datos (`0x2B83`) y el checksum (`0x2D0D`) hay `ds $18a` de relleno; Oro/Plata lo tienen pegado.
- Validaciones extra no pedidas: bytes 99/127 de la 2.ª generación, BCD válido, minutos y segundos < 60, nombre terminado. Según el código de pret, el propio juego las cumple siempre al guardar (`ValidateSave`, aritmética BCD y contadores de tiempo, entrada del nombre), pero no se comprobó con una partida real (sección 7).

## 2. Verificación contra pret (salida de `N6-C-verificar-offsets.py`)
Clones superficiales de pret en una carpeta temporal (`pokered` `af51989`, `pokeyellow` `e89ead1`, `pokegold` `ef0201d`, `pokecrystal` `3bc8daa`; los `.asm` solo se leen, no hace falta rgbds):
```
$ python3 docs/auditorias/N6-C-verificar-offsets.py $PRET
== pokered (Rojo/Azul)
  G1_GAME_DATA (sGameData = sPlayerName)         pret=0x2598  núcleo=0x2598  OK
  G1_CHECKSUM (sMainDataCheckSum = sGameDataEnd) pret=0x3523  núcleo=0x3523  OK
  sGameDataEnd                                   pret=0x3523  núcleo=0x3523  OK
  NAME_LENGTH                                    pret=0x000B  núcleo=0x000B  OK
  G1_DEX_OWNED                                   pret=0x25A3  núcleo=0x25A3  OK
  G1_DEX_SEEN                                    pret=0x25B6  núcleo=0x25B6  OK
  NUM_POKEMON                                    pret=0x0097  núcleo=0x0097  OK
  G1_MONEY                                       pret=0x25F3  núcleo=0x25F3  OK
  G1_BADGES                                      pret=0x2602  núcleo=0x2602  OK
  G1_PLAY_TIME (horas)                           pret=0x2CED  núcleo=0x2CED  OK
  wPlayTimeMaxed                                 pret=0x2CEE  núcleo=0x2CEE  OK
  wPlayTimeMinutes                               pret=0x2CEF  núcleo=0x2CEF  OK
  wPlayTimeSeconds                               pret=0x2CF0  núcleo=0x2CF0  OK
  wPlayTimeFrames                                pret=0x2CF1  núcleo=0x2CF1  OK
== pokeyellow (Amarillo)          (las mismas 14 líneas, todas OK: Amarillo usa la misma disposición)
== pokegold (Oro/Plata)
  GS_CHECK_VALUE_1 (sCheckValue1)                pret=0x2008  núcleo=0x2008  OK
  G2_GAME_DATA (sGameData)                       pret=0x2009  núcleo=0x2009  OK
  GS_GAME_DATA_END (sGameDataEnd)                pret=0x2D69  núcleo=0x2D69  OK
  GS_CHECKSUM (sChecksum, 2 B LE)                pret=0x2D69  núcleo=0x2D69  OK
  GS_CHECK_VALUE_2 (sCheckValue2)                pret=0x2D6B  núcleo=0x2D6B  OK
  SAVE_CHECK_VALUE_1                             pret=0x0063  núcleo=0x0063  OK
  SAVE_CHECK_VALUE_2                             pret=0x007F  núcleo=0x007F  OK
  G2_NAME (wPlayerName)                          pret=0x200B  núcleo=0x200B  OK
  NAME_LENGTH                                    pret=0x000B  núcleo=0x000B  OK
  GS_PLAY_TIME (wGameTimeHours, 2 B BE)          pret=0x2053  núcleo=0x2053  OK
  wGameTimeMinutes                               pret=0x2055  núcleo=0x2055  OK
  wGameTimeSeconds                               pret=0x2056  núcleo=0x2056  OK
  wGameTimeFrames                                pret=0x2057  núcleo=0x2057  OK
  GS_MONEY (wMoney, 3 B BE)                      pret=0x23DB  núcleo=0x23DB  OK
  GS_BADGES (wJohtoBadges)                       pret=0x23E4  núcleo=0x23E4  OK
  wKantoBadges                                   pret=0x23E5  núcleo=0x23E5  OK
  GS_DEX_OWNED (wPokedexCaught)                  pret=0x2A4C  núcleo=0x2A4C  OK
  GS_DEX_SEEN (wPokedexSeen)                     pret=0x2A6C  núcleo=0x2A6C  OK
  NUM_POKEMON                                    pret=0x00FB  núcleo=0x00FB  OK
== pokecrystal (Cristal)
  C_CHECK_VALUE_1 (sCheckValue1)                 pret=0x2008  núcleo=0x2008  OK
  G2_GAME_DATA (sGameData)                       pret=0x2009  núcleo=0x2009  OK
  C_GAME_DATA_END (sGameDataEnd)                 pret=0x2B83  núcleo=0x2B83  OK
  C_CHECKSUM (sChecksum, 2 B LE)                 pret=0x2D0D  núcleo=0x2D0D  OK
  C_CHECK_VALUE_2 (sCheckValue2)                 pret=0x2D0F  núcleo=0x2D0F  OK
  SAVE_CHECK_VALUE_1                             pret=0x0063  núcleo=0x0063  OK
  SAVE_CHECK_VALUE_2                             pret=0x007F  núcleo=0x007F  OK
  G2_NAME (wPlayerName)                          pret=0x200B  núcleo=0x200B  OK
  NAME_LENGTH                                    pret=0x000B  núcleo=0x000B  OK
  C_PLAY_TIME (wGameTimeHours, 2 B BE)           pret=0x2052  núcleo=0x2052  OK
  wGameTimeMinutes                               pret=0x2054  núcleo=0x2054  OK
  wGameTimeSeconds                               pret=0x2055  núcleo=0x2055  OK
  wGameTimeFrames                                pret=0x2056  núcleo=0x2056  OK
  C_MONEY (wMoney, 3 B BE)                       pret=0x23DC  núcleo=0x23DC  OK
  C_BADGES (wJohtoBadges)                        pret=0x23E5  núcleo=0x23E5  OK
  wKantoBadges                                   pret=0x23E6  núcleo=0x23E6  OK
  C_DEX_OWNED (wPokedexCaught)                   pret=0x2A27  núcleo=0x2A27  OK
  C_DEX_SEEN (wPokedexSeen)                      pret=0x2A47  núcleo=0x2A47  OK
  NUM_POKEMON                                    pret=0x00FB  núcleo=0x00FB  OK
0 diferencias        (66 comprobaciones OK)
```
Con una constante alterada el script lo detecta (`G1_MONEY = 0x25F4` → `DIFERENTE`, 2 diferencias: Rojo y Amarillo). Además, para Oro/Plata y Cristal el script comprueba (`assert`) que `sPlayerData`, `sCurMapData` y `sPokemonData` son contiguos y del mismo tamaño que sus bloques de WRAM, y a mano se comprobó que las direcciones de WRAM que salen del intérprete (`wPokedexOwned` = `$D2F7`; `wMoney` de Oro = `$D573` y de Cristal = `$D84E`; `wGameTimeHours` de Cristal = `$D4C4`) coinciden con las de Data Crystal.

## 3. Tests unitarios (`gbtest --unit`)
```
  unit link     ok
  unit progress ok
PASS: 8640 comprobaciones, 0 fallos        (antes del lote: 1349; 7291 comprobaciones nuevas)
```
Cubre: identificación por título y destino (mayúsculas/minúsculas, `POKEMON REDX`, `PM_CRYSTALX`, `POKEMON GREEN`, Tetris, destino 0 y 2, cabecera de 0x14F bytes y NULL); Rojo/Azul/Amarillo con medallas por bit, nueva partida, 255:59:59 + «máximo», bit 151 de la Pokédex ignorado; Oro/Plata/Cristal con medallas Johto/Kanto, horas 258 (big-endian), 999:59:59, dinero 999999, bits 251–255 ignorados; límites exactos del checksum (último byte dentro y primero fuera, en cada juego, incluidos el relleno de Cristal y los dos bytes del checksum); checksum erróneo, bytes de validación erróneos, BCD inválido, minutos o segundos = 60, nombre sin terminador → `false` y salida a cero; una partida de cada generación no pasa por la otra ni Oro/Plata por Cristal; los 255 valores posibles del primer glifo (todos menos el terminador) en las dos generaciones (UTF-8 válido, sin desbordes), glifos desconocidos → `?`, nombre de 10 glifos de 3 bytes; longitudes 32 KiB y +16/+44/+48 B de RTC (con el sufijo a `0xFF`) y 17 longitudes inválidas con copias de tamaño exacto (ASan); cabeceras de 0..0x14F bytes; punteros nulos; partidas vacías (`0x00` y `0xFF`), datos pseudoaleatorios y ediciones japonesas; determinismo (dos llamadas, salida idéntica incluido el relleno).

**Prueba de mutación del test** (22 cambios deliberados en `progress_pokemon.c`, cada uno compilado y ejecutado con `--unit`; el test falla en todos los que cambian el comportamiento):

| Mutante | Fallos |
|---|---|
| `G1_MONEY` +1 / `G1_PLAY_TIME` −1 / `GS_PLAY_TIME` +1 / `C_DEX_SEEN` +1 | 5 / 21 / 12 / 2 |
| `C_GAME_DATA_END` +1 / `G1_DEX_BITS` 152 / `G2_DEX_BITS` 252 | 1 / 1 / 3 |
| límite de minutos 61 / orden Johto-Kanto invertido / sin comprobar destino | 1 / 6 / 18 |
| título sin terminador (Rojo, Cristal) / sin byte de validación 1 / sin byte 2 | 1 / 1 / 3 / 3 |
| sin comprobar BCD / checksum sin complemento / horas en little-endian | 2 / 338 / 9 |
| sufijo RTC 44 → 45 / ♂ como ♀ / tabla de la 2.ª gen en la 1.ª / dinero desplazado | 18 / 1 / 2 / 3 |
| (`out->money = 0 + …`: mutante equivalente, no cambia nada) | 0 (esperado) |

## 4. Regresión del núcleo (tras `make -C core clean`)
```
$ make -C core test HITO=M9
M1    PASS  requerido   unit tests                    12.5s  PASS: 8640 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
$ make -C core asan HITO=M9
M1    PASS  requerido   unit tests                    22.2s  PASS: 8640 comprobaciones, 0 fallos
157/176 PASS · requeridos: 157/157 PASS · HITO=M9
OK: todos los casos requeridos en PASS
$ make -C core check-globals
Sin estado global mutable: OK
$ make -C core check-header
pocketgb.h compila aislado: OK
pocketgb_progress.h compila aislado (C11 y C++17): OK
```
Antes del lote, en este mismo árbol (`c3d8405`): `make -C core test HITO=M9` = 157/157 requeridos, 1349 comprobaciones unitarias. Compilación sin avisos con `-std=c11 -Wall -Wextra -Werror -pedantic`.

## 5. Fuzzing de `fuzz_progress`
`core/fuzz/fuzz_progress.c`: entrada `[juego][indicadores][partida]` (formato en la cabecera del archivo): preajuste de cabecera o cabecera libre; banderas para recalcular el checksum, forzar los bytes de validación, los rangos (BCD, minutos, segundos) y el nombre terminado, de modo que cada combinación alcance un rechazo distinto o la rama de éxito; longitud de partida y de cabecera variables. Cabecera y partida van a bloques del montón de tamaño exacto (ASan detecta cualquier lectura fuera de rango). Invariantes con `abort()`: salida a cero si falla, UTF-8 válido y nombre terminado, `badges_count` = bits de la máscara, Pokédex ≤ 151/251, minutos/segundos ≤ 59, dinero ≤ 999999 en la 1.ª gen, resultado idéntico en dos llamadas y nunca aceptar una partida corta. Semillas: una partida sintética válida por juego (`gbtest --fuzz-seeds`).

**libFuzzer no está disponible tal cual en este Mac**: el clang de Apple no lo trae y no hay `llvm` de Homebrew (`make fuzz-progress` termina con el mensaje del Makefile). Para no quedarse sin la campaña de 600 s se compiló libFuzzer desde `compiler-rt/lib/fuzzer` de LLVM 20.1.8 en una carpeta temporal (sin tocar el repo) y se enlazó a mano con la misma instrumentación que usa el Makefile:
```
$ for f in *.cpp; do clang++ -c -g -O2 -std=c++17 -fno-omit-frame-pointer $f -o obj/${f%.cpp}.o; done
$ ar rcs libFuzzer.a obj/*.o
$ clang -std=c11 -g -O2 -fsanitize=fuzzer-no-link,address,undefined -fno-sanitize-recover=all \
      -Iinclude -Isrc fuzz/fuzz_progress.c src/*.c libFuzzer.a -lc++ -o fuzz_progress
$ ./fuzz_progress -max_total_time=600 -max_len=33000 -len_control=0 corpus     # = make fuzz-progress
INFO: Seed: 1395617442
INFO: Loaded 1 modules   (3209 inline 8-bit counters)
INFO: seed corpus: files: 6 min: 32770b max: 32770b total: 196620b
#7	INITED cov: 64 ft: 67 corp: 6/192Kb exec/s: 0
...
#12687966	REDUCE cov: 185 ft: 364 corp: 196/1512Kb lim: 33000 exec/s: 21360 rss: 409Mb
Done 12829841 runs in 601 second(s)
```
Resultado: **12 829 841 ejecuciones en 601 s, sin crash, aborto, fuga, UB ni timeout** (no se generó ningún `crash-*`, `leak-*` ni `timeout-*`; el corpus final tiene 196 entradas, cobertura 185 contadores / 364 rasgos). Antes, con dos versiones anteriores de las banderas del arnés, se hicieron otras dos campañas de 600 s (18 830 472 y 13 010 468 ejecuciones), también sin fallos; la cifra de arriba es la del arnés final.
- **Cobertura de líneas** de `progress_pokemon.c` al reproducir ese corpus (`llvm-cov`): **97,02 %** (228 de 235 líneas; 95,54 % de las regiones, 87,93 % de las ramas). Las líneas sin cubrir son las ramas defensivas que no pueden alcanzarse con la longitud validada (`at()` devolviendo `NULL`, la comprobación de espacio de `put_utf8`, `out == NULL`); los tests unitarios sí cubren `out == NULL`.
- **Humo sin libFuzzer** (`make -C core fuzz-progress-smoke FUZZ_SECONDS=120`: el mismo archivo con un conductor propio, mutación aleatoria con ASan + UBSan, sin cobertura guiada): `fuzz_progress_smoke: 10918644 ejecuciones en 120 s, sin fallos`.
- Comprobación de que el arnés muerde: con un mutante que quita el bounds-check y mueve `G1_MONEY` a `0x7FFE`, el humo da `heap-buffer-overflow … READ of size 1 … 0 bytes after 32768-byte region` con las semillas sin mutar.
- **Pendiente**: la campaña de 600 s con el clang de Homebrew/Linux (`make fuzz-progress` o `make fuzz FUZZ_SECONDS=600`) sigue sin ejecutarse con el flujo oficial del Makefile; lo que hay es la misma campaña con un libFuzzer compilado a mano.

## 6. Compilación para las apps (sin tocarlas)
`progress_pokemon.c` compila con `-std=c11 -Wall -Wextra -Werror -pedantic -O2` para `arm64-apple-ios26.0` (dispositivo y simulador, arm64 y x86_64) y con el NDK 27 (`aarch64`, `armv7a`, `x86_64` e `i686`, API 26), sin avisos y sin símbolos de datos mutables (`nm` de Apple y `llvm-nm` del NDK: solo `t/T` y `r/s`). No se ejecutó `xcodebuild` ni Gradle.
- **iOS:** el proyecto referencia `core/src` como carpeta sincronizada (`PBXFileSystemSynchronizedRootGroup`, solo `cpu_ops.inc` está en las excepciones): el `.c` nuevo entra solo. Falta exponer la API a Swift: añadir `header "pocketgb_progress.h"` a `core/include/module.modulemap` (no se tocó).
- **Android:** `android/app/src/main/cpp/CMakeLists.txt` lista las fuentes a mano: **habrá que añadir `"${CORE_ROOT}/src/progress_pokemon.c"`** (no se tocó) y el puente JNI.

## 7. Lo que NO está verificado
- **Nunca se probó con una partida real** (ni de Joel ni de nadie): solo partidas sintéticas construidas con los mismos desplazamientos. Lo que sí respalda los números es el cálculo independiente desde pret y la coincidencia con PKHeX y Data Crystal. Es la prueba que falta: abrir el panel con un `.sav` real de Rojo/Azul/Amarillo y de Oro/Plata/Cristal en el Mac de Joel (los `.sav` nunca se versionan).
- **Ediciones ES/FR/DE/IT:** que usen los mismos títulos de cabecera y la misma disposición de la partida se tomó del encargo y de que PKHeX y Data Crystal las agrupan como «internacionales»; no hubo ROMs ni partidas de esas ediciones. Que su byte `0x14A` valga 1 se deduce del `-j` de pret y de la convención de la cabecera; tampoco se comprobó en ROMs reales.
- **Bulbapedia** («Save data structure») no se pudo consultar (HTTP 403 desde `curl` y `WebFetch`); se usaron Data Crystal y PKHeX como contraste documental.
- **Glifos acentuados** de FR/DE/ES/IT que pret (inglés) no documenta: salen como `?`.
- **Coreano:** PKHeX indica otra disposición para Oro/Plata coreano (checksum en `0x2DAB`) y que no hay Cristal coreano; el lector devolverá `false` por el checksum, pero no hay prueba con una partida coreana.
- **Hacks** (Prism, Epic Gold, etc.): no probados; por diseño solo darán datos si conservan título, destino, disposición y checksum oficiales.
- **Copia de respaldo:** si la copia principal está corrupta el juego usa la de respaldo; el lector no la lee y devuelve `false`.
- **Virtual Console** y partidas de otros emuladores con otros sufijos: no probadas; cualquier longitud distinta de 32 KiB (+16/44/48) se rechaza.
- **Fuzzing:** ver la sección 5 (libFuzzer compilado a mano, no el flujo oficial del Makefile).

## 8. Para el lote de integración
1. Android: añadir `progress_pokemon.c` al `CMakeLists.txt` y exponerlo por JNI. iOS: añadir `header "pocketgb_progress.h"` al `module.modulemap`.
2. Pasar `rom_header` = primeros 0x150 bytes del ROM y `sram` = el `.sav` tal cual lo guarda el núcleo (`gb_sram_save`: 32 KiB, más 48 B de RTC en Oro/Plata/Cristal).
3. `false` significa «sin datos» (juego no soportado o partida incoherente o aún sin guardar): la app no debe mostrar el panel ni proponer hitos.
4. Nunca incluir `.sav` reales en tests de la app; construir partidas sintéticas como en `unit_progress.c`.
