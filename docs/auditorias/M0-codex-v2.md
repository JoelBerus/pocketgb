## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| M0-05 (reabierto) | bloqueante | [docs/04-ios-spec.md:88](/Users/joelbermudez/Documents/workspace/pocketgb/docs/04-ios-spec.md:88) | La secuencia de guardado sigue permitiendo que desaparezca el `.sav` autoritativo antes de instalar el nuevo. | El paso 2 mueve/rota “el actual→`.1`”; por tanto, al llegar al paso 3 el destino ya no existe y la rama `replaceItemAt` de la línea 91 resulta inalcanzable. Si el proceso muere entre ambos pasos, falta el `.sav` que la carga exige en la línea 96. Esto contradice además el test de la línea 92, que afirma que el `.sav` previo debe quedar intacto ante un fallo anterior al paso 3. Viola la regla dura de atomicidad y backups. | No mover el `.sav` actual antes del reemplazo. Usar un reemplazo atómico que conserve el original como `.1` —por ejemplo, `replaceItemAt` con backup persistente— y rotar solamente `.1`–`.5`; o copiar el actual a un backup durable antes del reemplazo. Añadir fallos simulados antes, durante y después de cada operación. |
| M0-04 (reabierto) | alta | [docs/03-core-spec.md:16](/Users/joelbermudez/Documents/workspace/pocketgb/docs/03-core-spec.md:16) | `F` sigue mal especificado para CGB en modo compatibilidad: no es siempre `0x80`. | Pan Docs indica que la última operación que modifica flags es `INC B`; por ello `Z` y `H` dependen del valor calculado de `B`, mientras `N=C=0`. El ROM local `mooneye-test-suite/misc/boot_regs-cgb.gb` tiene licencia no Nintendo, por lo que `B=0`; `INC B` produce `F=0x00`, no `0x80`. El test M8 añadido detectaría una implementación fiel al documento como incorrecta. [Pan Docs: Power-Up Sequence](https://github.com/gbdev/pandocs/blob/master/src/Power_Up_Sequence.md#console-state-after-boot-rom-hand-off) | Especificar `F` como los flags resultantes de `INC B`. Añadir unit tests con valores de `B` que ejerciten `Z` y `H`, además de `boot_regs-cgb.gb`. |
| M0-11 | media | [docs/hitos/M9-link-virtual.md:5](/Users/joelbermudez/Documents/workspace/pocketgb/docs/hitos/M9-link-virtual.md:5), [core/include/pocketgb.h:110](/Users/joelbermudez/Documents/workspace/pocketgb/core/include/pocketgb.h:110) | El lockstep documentado ignora que `gb_run_cycles` puede ejecutar más de 456 T-ciclos y acumular deriva entre instancias. | El contrato devuelve los ciclos realmente ejecutados porque termina en frontera de instrucción, pero M9 alterna literalmente `gb_run_cycles(g, 456)` sin contabilizar el retorno. Con instrucciones repetidas de 16 T-ciclos, cada llamada ejecutaría 464; una instancia con otra mezcla puede ejecutar exactamente 456. La diferencia se acumula y deja de ser un lockstep de ≤456 ciclos. | Mantener un reloj acumulado por instancia y usar el valor devuelto para ejecutar siempre la instancia atrasada, o cambiar la API a un deadline absoluto/avance que no acumule exceso. Añadir un test con secuencias de instrucciones de distinta duración. |
| M0-12 | baja | [.githooks/pre-commit:6](/Users/joelbermudez/Documents/workspace/pocketgb/.githooks/pre-commit:6) | El hook pierde la delimitación NUL y puede omitir un ROM cuyo nombre contenga un salto de línea. | `git diff -z` es convertido inmediatamente con `tr '\0' '\n'`; el bucle procesa fragmentos que ya no son rutas válidas, haciendo que `git cat-file ":$f"` devuelva tamaño cero. Git admite saltos de línea en nombres. | Mantener las rutas delimitadas por NUL durante todo el procesamiento; si es necesario, cambiar el hook a Bash y usar `read -r -d ''`. Añadir un fixture con salto de línea. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `git ls-files \| grep -Ei '\.(gb\|gbc\|sav)$'` vacío | sí | **PASS.** Sin coincidencias. `git log --all --stat`, los nombres históricos y todos los blobs de todas las referencias fueron inspeccionados; no aparecen extensiones prohibidas ni la firma `CE ED 66 66` en `0x104`. |
| El hook rechaza `fake.txt` con firma de ROM | no | No fue posible crear índice/commit temporal en la sandbox de solo lectura. La evidencia es plausible y el código de las líneas 13–17 rechaza el fixture exacto. También se verificó la sintaxis POSIX del hook. |
| `make -C core check-header` | no | La compilación exacta requiere escribir `core/build` y cachés temporales. El intento equivalente quedó afectado por la prohibición de escritura de `/tmp`; esto no es un hallazgo del proyecto. La evidencia registrada es plausible y corresponde al header de `HEAD`. |
| Informe M0 sin hallazgos bloqueantes abiertos | no | **FAIL.** Permanece abierto el defecto bloqueante de atomicidad de partidas. |

## Notas

- Estado de M0-01…M0-10:

  - M0-01: resuelto; M0 figura nuevamente “en auditoría”.
  - M0-02: la carencia del ABI fue resuelta, pero el diseño consumidor presenta la deriva descrita en M0-11.
  - M0-03: resuelto; huella SHA-256 completa y 256 bits en estados.
  - M0-04: parcialmente resuelto; registros generales corregidos, `F` de compatibilidad todavía incorrecto.
  - M0-05: la rama de primer guardado fue añadida, pero el orden global sigue violando atomicidad.
  - M0-06, M0-07, M0-08, M0-09 y M0-10: resueltos en el diff auditado.

- La evidencia fue registrada a las 21:56 y el commit auditado `07690f4` a las 21:57; los archivos de evidencia y respuesta están incluidos en ese commit.
- No existe aún implementación en `core/src`, app iOS ni `gb_state_load`; por tanto, no hay accesos de ROM, globals mutables, red o código GPL/AGPL implementado que revisar.
- Las ROMs de prueba libres están presentes localmente bajo una ruta ignorada, pero ninguna está rastreada ni aparece en el historial.
- No se modificó el repositorio. El único archivo no rastreado observado fue `docs/auditorias/M0-codex-v2.md`.
