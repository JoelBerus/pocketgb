# Auditoría N6-C · lector de progreso Pokémon (Opus, subagente sin historial)

Fecha: 2026-10-07. Commit auditado: `869ca4b` (rama `n6-c-progreso`), diff `siguiente-nivel...n6-c-progreso`. Rol: solo lectura; ejecución en `/private/tmp/claude-501/audit-n6c`. Informe transcrito tal cual.

## Veredicto: APROBAR CON CAMBIOS

No hay ningún bloqueante. El lector cumple las reglas 1–4, los desplazamientos son correctos y la evidencia se reproduce. Hay dos hallazgos medios que conviene resolver antes del cierre: el Amarillo de las ediciones europeas (ES/FR/DE/IT) no se reconoce porque su título de cabecera es otro, y la identificación no sigue lo que fija el plan (§3.3/ND5) sin que la desviación quedara registrada como decisión.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | `core/src/progress_pokemon.c:101-103`; `core/include/pocketgb_progress.h:11-12`; `docs/03-core-spec.md:183` | **Amarillo europeo no se reconoce.** El código exige `POKEMON YELLOW` + `0x00`; las ediciones DE/FR/ES/IT llevan `POKEMON YELAPS` + letra de idioma en 0x142: `pgb_progress_identify` devuelve NONE. Contradice §3.3, la cabecera y el spec. Falla de forma segura. No afecta al cartucho de Joel (`POKEMON YELLOW`, `docs/ESTADO.md:149`). | `Brianum/pokeyellow-de` Makefile:141 `-t "POKEMON YELAPSD"` (reproduce la ROM alemana de No-Intro, sha1 `42f3714e…`); `Staacks/gbinterceptor` `firmware/gamedb/games.csv`: `POKEMON YELAPS` para ES/DE/FR/IT. Sonda: `POKEMON YELAPSD/F/S/I -> 0`. La disposición sí es la misma (0 diferencias contra `pokeyellow-de`). | Aceptar `POKEMON YELAPS` + {D,F,I,S} en 0x142 (confirmar letras) con tests; corregir el spec. |
| H2 | media | `core/src/progress_pokemon.c:92-110`; `pocketgb_progress.h:13-14`; `docs/03-core-spec.md:187` frente a `docs/hitos/N-README.md:92` | **Desviación del plan no registrada.** §3.3 (ND5): identificación «por título de cabecera y checksum global» y los hacks «no se leen». Implementado: título + `0x14A`; un hack con mismo título, disposición y checksum sí da datos. La evidencia no lo menciona. Riesgo real bajo. | Textos citados; el checksum global (0x14E–0x14F) cabe en los 0x150 bytes de la API. | (a) lista blanca del checksum global de cada edición oficial, o (b) enmendar ND5/§3.3 con el criterio actual y su riesgo. |
| H3 | baja | `progress_pokemon.c:114-160` | `<PK>` (0xE1) y `<MN>` (0xE2) se pueden teclear en el nombre en ambas generaciones y salen como `?`. | pret `pokered/data/text/alphabets.asm`, `pokecrystal/data/text/name_input_chars.asm`. Sonda: `E1 E2 50` → `"??"`. | Mapear a «PK» y «MN» con test. |
| H4 | baja | `progress_pokemon.c:147-148`; `docs/03-core-spec.md:210` | Tabla de la 1.ª gen en Europa: en DE/FR/ES 0xBA es «à» (no «é», que va en 0xBC); en alemán Ä Ö Ü ä ö ü (0xC0–0xC5) se teclean y salen como `?`. El spec dice que pret no los documenta, pero los desensamblados europeos sí. | `charmap.asm` de `pokered-de/-fr/-es` (154-165); `pokered-de/data/text/alphabets.asm`. En inglés 0xC0–0xC5 no tienen glifo. | Mapear 0xC0–0xC5 en la 1.ª gen; documentar que 0xBA es ambiguo según idioma. |
| H5 | baja | `progress_pokemon.c:319-327`; `pocketgb_progress.h:50`; `core/fuzz/fuzz_progress.c:136` | 2.ª gen sin validar rangos documentados: se aceptan 65535 h (la cabecera promete 0–999) y dinero hasta 16 777 215 (`MAX_MONEY` = 999999). El invariante del fuzzer `money > 0xFFFFFF` nunca falla. | Sonda con checksum correcto: `read=1 hours=65535 money=16777215`; pret `MAX_MONEY EQU 999999`, tope de 999 h en `home/game_time.asm`. | Rechazar horas > 999 y dinero > 999999 en la 2.ª gen (tests + invariantes), o corregir la documentación. |
| H6 | baja | `docs/auditorias/N6-C-verificar-offsets.py:31,235,255,544-564`; `docs/auditorias/N6-C-evidencia.md:54,95,103` | (1) El script acumula `ctx.errors` y nunca los muestra (13/18/28 errores de interpretación silenciosos fuera de los rangos medidos; un fallo dentro saldría como DIFERENTE, nunca falso OK). (2) Los bloques de «salida» de la evidencia llevan anotaciones que el programa no imprime. | Ejecución propia del script. | Imprimir el número de errores (o fallar si caen entre etiquetas medidas); separar anotaciones de la salida literal. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| `.sav` sintéticos byte a byte; ninguna partida real (regla 1) | sí | Sin binarios en el rango ni en `git log --all`; `fuzz/corpus/` ignorado; `core/tests/roms` enlace ignorado. |
| Checksum incorrecto = sin datos | sí | Límites exactos probados; mutante sin complemento: 338 fallos. |
| Fuzz de 600 s | parcial | `make fuzz-progress` avisa de que falta libFuzzer (no está roto); humo 120 s: 10 111 282 ejecuciones sin fallos; libFuzzer 300 s (libFuzzer compilado a mano): 8 370 111 ejecuciones sin crash/leak/UB. Los 600 s no pasaron por el flujo oficial del Makefile. |
| Puro, con ASan (regla 4) | sí | `make -C core asan HITO=M9`: 157/157 y 8640 comprobaciones; solo `string.h`; `nm`: solo `t/T/s`. |
| `check-globals` / `check-header` | sí | OK / OK (C11 y C++17). |
| `make -C core test HITO=M9` | sí | 157/157 requeridos, 8640 comprobaciones: coincide. |
| Entrada no confiable (regla 3) | sí | `at()` sin desbordamiento; longitudes exactas; `decode_name` acotado. |
| Desplazamientos correctos | sí | Script contra pret en los mismos commits: 66 OK, 0 diferencias; repetido contra desensamblados europeos: 0 diferencias. |
| Fórmulas y formatos | sí | `CalcCheckSum` Gen 1, `Checksum` Gen 2 LE desde 0x2009, `CheckPrimarySaveFile` 99/127, horas BE, dinero BCD/BE, medallas Johto+Kanto, Pokédex 151/251. |
| Identificación por título y `0x14A` | sí, con H1 | Bien para EN y Rojo/Azul DE/FR/ES; falla Amarillo europeo. |
| Hack con otra disposición → `false` | sí | Por checksum; uno con la misma disposición sí da datos (H2). |
| Soporte EN/ES/FR/DE/IT incluido Amarillo | no | H1. |
| Identificación «por título y checksum global» | no | H2. |
| Regla 2 (licencias) | sí | Solo constantes con fuente citada; el script lee los `.asm` de un clon externo. |
| Compila para las apps | sí | iOS arm64 y NDK 27 (4 ABI) con `-Werror -pedantic`. |
| Mutación de tests | sí (muestra) | Coinciden con la evidencia; glifo «A» → 5, checksum de Cristal pegado → 27. |

## Notas
- No verificado: cobertura del 97,02 %, ediciones italianas, Oro/Plata/Cristal ES/IT/FR más allá de `pokegold-de`, cualquier `.sav` real.
- Coreano: el rechazo depende solo del checksum; se podría comprobar ‘K’ en 0x142 (sugerencia).
- Origen del sufijo RTC de 16 bytes sin citar (inocuo).
- Integración pendiente correcta: `progress_pokemon.c` en el `CMakeLists.txt` de Android y `header "pocketgb_progress.h"` en el `module.modulemap` de iOS.
