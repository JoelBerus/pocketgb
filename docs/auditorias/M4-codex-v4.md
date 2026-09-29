## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H2 | bloqueante | `docs/hitos/M4-ios-minima.md:19` | El criterio de aceptación del iPhone sigue incompleto: Pokémon Rojo no se ha probado hasta el menú ni se ha verificado la respuesta de los botones. | `M4-evidencia.md:90-93` confirma dmg-acid2 en el iPhone, pero declara Pokémon Rojo pendiente por falta del volcado legal. | Joel debe volcar su cartucho y documentar menú y botones. Alternativamente, como dueño, debe aprobar explícitamente diferir o modificar este criterio antes de cerrar M4. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| Build para dispositivo genérico → `BUILD SUCCEEDED`, sin warnings nuevos | sí | H3 corregido. La evidencia muestra build firmado terminado a las 10:00:17, inmediatamente antes del commit auditado de las 10:00:18. El único warning procede de `appintentsmetadataprocessor` y ya estaba identificado. El sandbox no permitió reconstruir por restricciones de escritura en `/tmp`; no es un fallo del proyecto. |
| Sin `URLSession`, `NWConnection`, `Network` ni `http` | sí | PASS: búsqueda reejecutada sin coincidencias relevantes. Tampoco hay ATS, claves de red, paquetes SPM ni SDKs externos. |
| En iPhone: dmg-acid2 correcto y Pokémon Rojo llega al menú y responde | no | Parcial: instalación y dmg-acid2 confirmados por Joel. Pokémon Rojo y sus controles siguen sin verificarse. |

## Notas

- Commit auditado: `f5321b227ed337e0ec7d57309771983f45ccf582`; base `main`: `51d20763382f17570642790e66467e9211572ff7`. Árbol limpio.
- H7 queda corregido: la notificación de memoria baja llega a `AppState.memoryWarning()`, activa `requestFlush()` y el hilo de emulación ejecuta `flushSRAM(sync: true)` en el siguiente ciclo. La prueba documentada es coherente y ocurre antes de la red de seguridad de 60 segundos.
- Suites reejecutadas con los binarios existentes:
  - Normal: `103/103` casos requeridos.
  - ASan/UBSan: `103/103` casos requeridos.
  - Unitarias: `1080 comprobaciones, 0 fallos`.
- `llvm-nm` no encontró estado global mutable. El `check-globals` ordinario produce un falso “OK” si `nm` falla en este sandbox, por lo que se verificó directamente con `llvm-nm`.
- `git log --all --stat`, nombres históricos y revisión de la firma de cabecera no detectaron ROMs, boot ROMs, partidas ni estados en Git.
- No encontré indicios de código GPL/AGPL copiado ni cambios en `core/`, opcodes, tiempos, accesos al ROM o `gb_state_load` dentro de M4.
- La ruta de SRAM conserva escritura atómica, `fsync`, reemplazo por `rename(2)` y cinco backups.
- `git diff --check main...HEAD` pasa.
- Aparte de la prueba de Pokémon Rojo —bloqueada exclusivamente por la disponibilidad del volcado legal— el hito está listo para cerrarse. Conforme al criterio vigente y al flujo de auditoría, todavía no debe marcarse como cerrado sin esa prueba o una aprobación explícita de Joel para diferirla.
