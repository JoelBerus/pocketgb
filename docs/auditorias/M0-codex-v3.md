## Veredicto: APROBAR CON CAMBIOS

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| M0-13 | alta | [docs/hitos/M9-link-virtual.md:5](/Users/joelbermudez/Documents/workspace/pocketgb/docs/hitos/M9-link-virtual.md:5) | El deadline absoluto elimina la deriva de M0-11, pero no conserva el orden temporal de los eventos entre ambas instancias. | Cada instancia avanza hasta `T` de forma secuencial. Si la primera produce un pulso serie en `t=600` mientras la segunda sigue en `t=456`, el callback consulta un estado atrasado. La segunda podría haber activado `SC=0x80` en `t=500`, pero esa instrucción aún no se ejecutó y `gb_serial_clock_external()` devolvería cable desconectado. Pan Docs exige que el esclavo haya activado el puerto antes de comenzar el reloj del maestro. El test propuesto de instrucciones de 4/24 ciclos solo mide deriva, no esta causalidad. [Pan Docs: Serial Data Transfer](https://raw.githubusercontent.com/gbdev/pandocs/master/src/Serial_Data_Transfer_%28Link_Cable%29.md) | Usar un scheduler por próximo evento compartido o, antes de entregar cada pulso, avanzar el peer hasta el timestamp del pulso. Añadir un test donde el esclavo active `SC` entre el inicio del bloque y el primer flanco del maestro. |
| M0-14 | media | [docs/04-ios-spec.md:86](/Users/joelbermudez/Documents/workspace/pocketgb/docs/04-ios-spec.md:86), [docs/04-ios-spec.md:93](/Users/joelbermudez/Documents/workspace/pocketgb/docs/04-ios-spec.md:93), [docs/hitos/M6-biblioteca-saves.md:8](/Users/joelbermudez/Documents/workspace/pocketgb/docs/hitos/M6-biblioteca-saves.md:8) | La especificación exige un resultado imposible durante el primer guardado. | Antes del paso 5 no puede existir un `.sav` viejo en el primer guardado. Por tanto, un fallo inyectado después de los pasos 2–4 no puede dejar “el viejo” ni garantizar que “en todos los casos existe un `.sav` completo”. La corrección sí preserva correctamente un `.sav` ya existente durante los reemplazos. | Limitar el invariante a reemplazos. Separar los tests: primer guardado exitoso; fallo antes de instalar el primero → destino ausente y temporal recuperable; reemplazo fallido → `.sav` viejo intacto. |
| M0-15 | media | [docs/auditorias/M0-evidencia.md:37](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/M0-evidencia.md:37) | La evidencia de M0-12 no es una transcripción plausible del comando mostrado. | Se muestra `git add -- $'rom\nnueva.txt'` seguido inmediatamente por la salida del hook y `exit=1`. `git add` no ejecuta el hook `pre-commit`; falta el `git commit` o la invocación explícita del hook. El código es NUL-safe por inspección y pasa `bash -n`, pero la ejecución registrada no demuestra lo afirmado. | Repetir en una copia temporal y pegar el comando real `git commit -m t`, su salida, código de retorno y limpieza posterior. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `git ls-files \| grep -Ei '\.(gb\|gbc\|sav)$'` vacío | sí | **PASS.** Sin coincidencias. `git log --all --stat`, nombres históricos y todos los blobs históricos ≥336 bytes fueron inspeccionados; no apareció la firma `CE ED 66 66` en `0x104`. |
| El hook rechaza `fake.txt` con firma de ROM | no | El sandbox impidió crear el repositorio temporal y el `index.lock`. La evidencia §2 es plausible para la versión anterior; la evidencia §5 del hook actual tiene la discrepancia M0-15. El código actual debería rechazar el fixture, pero falta una ejecución válida correspondiente a `HEAD`. |
| `make -C core check-header` | no | El comando exacto necesita escribir `core/build` y fue bloqueado por el sandbox. Una compilación equivalente con los mismos flags y `-fsyntax-only` terminó con código 0. La evidencia registrada es plausible y el header no cambió en `HEAD~1..HEAD`. |
| Informe M0 sin hallazgos bloqueantes abiertos | sí | **PASS.** M0-05 ya no deja desaparecer un `.sav` existente. Esta vuelta encuentra hallazgos altos/medios, pero ninguno bloqueante. |

## Notas

- Commit auditado: `2ba09113696b220d77d569007a2705b9917e52d2`.
- M0-04: descarte justificado. Pan Docs asigna `F=0x80` a **CGB en modo DMG**; la regla derivada de `INC B` pertenece a **AGB en modo DMG**. Mooneye también exige `F=0x80`, y el ROM local verificado tiene `0x143=0x00`. [Pan Docs](https://raw.githubusercontent.com/gbdev/pandocs/master/src/Power_Up_Sequence.md), [Mooneye `boot_regs-cgb.s`](https://raw.githubusercontent.com/Gekkio/mooneye-test-suite/main/misc/boot_regs-cgb.s).
- M0-05: resuelto respecto al hallazgo bloqueante anterior. El `.sav` existente ya no se mueve antes del reemplazo y `rename(2)` cubre creación y sustitución atómica. M0-14 es una inconsistencia nueva en el invariante y los tests del primer guardado.
- M0-11: resuelto en su alcance original; el deadline absoluto impide acumulación de exceso. M0-13 es un problema distinto de causalidad entre instancias.
- M0-12: resuelto por inspección del código; el hook conserva delimitación NUL mediante Bash y pasa validación sintáctica. Debe corregirse su evidencia.
- No existen todavía implementación en `core/src`, app iOS ni `gb_state_load`. Por ello no había accesos reales al ROM, estado global mutable, red, persistencia Swift o deserialización que auditar.
- No se encontró código GPL/AGPL copiado. SameBoy solo aparece como oráculo dev-only ignorado y no enlazado al runtime.
- El repositorio no fue modificado. El único archivo no rastreado era `docs/auditorias/M0-codex-v3.md`, vacío y creado por la redirección externa de esta auditoría.
