# Auditoría N7-C · contenedor `.pgbm` en C (Opus, subagente sin historial)

Fecha: 2026-10-07. Rama `n7-c-pgbm` en `8821c37` (diff `siguiente-nivel...n7-c-pgbm`). Rol: solo lectura; ejecución en `/private/tmp/claude-501/audit-n7c` y arneses propios en `audit-n7c-work`. Informe transcrito (formato condensado, contenido sin cambios).

## Veredicto: APROBAR CON CAMBIOS
Sin defectos en el código C: el parser es robusto ante entradas hostiles y cumple las reglas 1, 3 y 4. Los cambios son de documentación y diseño para N7b, sobre todo especificar el contenido mínimo de `META` antes de que iOS y Android lo implementen en paralelo.

## Hallazgos
| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | media | `docs/12-formato-pgbm.md:45`; `docs/hitos/N-README.md:110` | §3.4 exige sha y sha base (linaje), equipo de origen, versión del núcleo y configuración, pero `META` es un JSON sin esquema: (a) sha y sha base no aparecen en docs/12; (b) `META` es opcional y no se define qué hace el importador sin ella; (c) nada une `STAT` con `SAVE` (ND6 lo necesita); (d) el `META` de G1 no sigue ningún esquema, así que reproducir G1 en Swift/Kotlin no prueba que las apps entiendan lo mismo. | `grep` en docs/12: solo la línea 45. | Sección «META v1» con claves, tipos y obligatorias (`formato`, `sav_sha256` con o sin RTC dicho, `base_sha256`, `equipo {plataforma, nombre}`, `nucleo`, `config {…}`, `estado_de_sav_sha256`, tiempo en ms, fechas en epoch ms UTC, claves desconocidas ignoradas); comportamiento sin `META` o exigirla en «Enviar a otro dispositivo»; `META` de G1 conforme. |
| H2 | baja | `docs/12-formato-pgbm.md` §Reglas generales; `core/src/pgbm.c:156-176` | Todo tipo desconocido se ignora y no hay forma de marcar una sección futura como obligatoria de entender: un cambio no ignorable obliga a subir la versión y rechazar el paquete entero en lectores v1. Con lectores v1 instalados ya no se podrá añadir la convención. | `walk_sections`: `K_UNKNOWN` siempre se salta. | Convención estilo PNG (desconocido con mayúscula inicial → rechazo; minúscula → se ignora), o aceptar el modelo actual por escrito. |
| H3 | baja | `docs/12-formato-pgbm.md` (`SAVE` vacía); `docs/hitos/N-README.md:286-292` | `SAVE` vacía es válida; evitar que pise la partida de un juego con batería depende solo del importador (regla 6) y no está en los criterios de N7. | Fila `SAVE` y §7 de la evidencia. | Criterio N7: importar `SAVE` vacía o de tamaño distinto para un juego con batería no toca el `.sav` y deja backup (Swift y JVM). |
| H4 | baja | `core/include/pocketgb_pgbm.h:66-81` | Se dice que los valores de `pgbm_result` son estables pero los enumeradores son implícitos; reordenarlos cambia el número que Kotlin mapea por JNI sin que ningún test lo detecte. | `test_names` solo comprueba nombres. | Valores explícitos y `_Static_assert`/test. |

## Criterios (resumen)
`make -C core test HITO=M9`: 11198 comprobaciones y 157/157 (base 8772; +2426 coincide). ASan: limpio. `check-globals`/`check-header`: OK. iOS y NDK sin símbolos mutables (`crc32_update` una vez). Vectores G1/G2/G3: coinciden con implementación Python independiente. Entrada hostil: diferencial de 820 000 paquetes mutados y 17 834 240 secuencias UTF-8 con 0 discrepancias. Fuzz: 17,8 M ejecuciones en 301 s y humo de 60 s sin fallos (flujo oficial sin libFuzzer en el Mac). Mutantes del auditor: 10/10 detectados. Hook y `.gitignore`: bloquean por extensión y por mágico; historial limpio. Licencias: código propio.

## Notas
- El contenedor cubre en estructura §3.4 (SAVE, STAT opcional hasta 1 MiB > ≈666 KiB del estado GBA, ROMF de 32 bytes = `fingerprint[32]`, THMB, META); topes de `SAVE` cubren GB (128 KiB + 48) y GBA (128 KiB + 16); encode determinista.
- Para Swift/Kotlin: `META` de G1 con escapes (no depender de NFC del fuente); `rom_fp[32]` como tupla en Swift; los spans solo valen dentro de `withUnsafeBytes`/hasta `ReleaseByteArrayElements`: copiarlos.
- El fuzzer nunca llega a los topes por sección ni al total (los cubren las pruebas unitarias con ±1).
- `total > PGBM_MAX_TOTAL` en `pgbm_encoded_size` es defensivo e inalcanzable.
- Integración (N7b): `module.modulemap` en iOS y `CMakeLists.txt` en Android.
