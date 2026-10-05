## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| G7-1 | bloqueante | `docs/auditorias/G7-evidencia.md:27` | La evidencia declara CI verde y capturas publicadas para `fef885d`, pero la rama remota indicada contiene un resultado fallido de un commit anterior. Incumple la regla dura 7: no queda evidencia verificable del criterio “CI verde” para el commit auditado. | `origin/ci-shots/g7-gba-app` apunta a `cbc591b`, cuyo `SUMMARY.md` identifica el commit `3fab62a`, resultado `failure`, con `testGridReflowsToOneColumnAtAX5` fallido y `** TEST FAILED **`. `HEAD` es `a3c5fcf` y el código es equivalente a `fef885d`, pero no hay artefacto publicado que pruebe su ejecución exitosa. | Publicar los artefactos del run verde correspondiente exactamente a `fef885d`/`a3c5fcf`, con `SUMMARY.md`, log y SHA correctos. Actualizar la evidencia únicamente después de comprobar la rama publicada. |
| G7-2 | alta | `ios/PocketGB/Emulator/GBACoreBridge.swift:138` | `sramLoad` acepta inicialmente cualquier tamaño estándar de medio GBA, no el tamaño detectado para el cartucho. La decisión de `SaveResolution` puede modificar la copia local antes de que el núcleo rechace el archivo. | Para un juego SRAM de 32 KiB, `isMediaSize` también acepta 64 y 128 KiB. `SaveOpening.prepare` puede instalar un espejo “más nuevo” mediante `store.save(data)` antes de `core.sramLoad` (`EmulatorSession.swift:111–124`). Después el núcleo rechaza el tamaño, pero la partida correcta ya fue desplazada a backup y el archivo incompatible queda como copia local activa. No hay pérdida irreversible gracias al backup, pero sí sustitución incorrecta y sesión sin guardado. | Separar los tamaños válidos por medio detectado: SRAM 32 KiB; Flash64 64 KiB; Flash128 128 KiB; EEPROM 512/8192 solo mientras sea realmente indeterminada. Validar con el núcleo antes de instalar, poner en cuarentena o actualizar cualquier copia. |
| G7-3 | alta | `docs/hitos/G-README.md:15,120`; `ios/PocketGB/Emulator/GBACoreBridge.swift:112` | El formato persistido contradice el contrato aprobado: el RTC debía ir aparte y el `.sav` debía seguir siendo crudo y compatible con mGBA/VBA. | G7 concatena 16 bytes de RTC al `.sav` (`sramSaveSize` y `sramSave`, líneas 112–155). La propia evidencia reconoce que no existe `.rtc`. Un `.sav` de 32 KiB + 16 bytes deja de ser un save SRAM crudo importable directamente. La “decisión de implementación” no está reflejada como cambio aprobado en el hito. | Mantener `<fingerprint>.sav` como medio crudo y persistir los 16 bytes en `<fingerprint>.rtc`, ambos con escritura atómica, backups y espejo; o aprobar y documentar explícitamente un formato contenedor versionado y una estrategia de interoperabilidad/migración. |
| G7-4 | media | `ios/PocketGBTests/GBATests.swift:97` | El test del criterio de tamaño incorrecto solo cubre un tamaño obviamente inválido y no detecta G7-2 ni la ambigüedad del RTC. | Usa 1000 bytes contra SRAM de 32 KiB. No prueba 64/128 KiB para un cartucho SRAM, medio+RTC, espejo más nuevo, local correcta frente a espejo incompatible ni que `SaveOpening` no altere el disco antes de la validación definitiva. | Añadir tests parametrizados por tipo de medio y por origen local/espejo. Incluir específicamente SRAM32 con espejo de 64 KiB más nuevo y verificar que local, backups y espejo permanecen byte por byte intactos. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---:|---|
| `ConsoleCore` con puentes GB y GBA | sí | Implementado. El cambio funcional visible en `CoreBridge` se limita a adoptar el protocolo y parametrizar el framebuffer. |
| Biblioteca `.gba`, máximo 32 MiB y cabecera GBA | sí | Lectura limitada a `32 MiB + 1`; exige mínimo `0xC0` y byte fijo `0x96`. El checksum se calcula y se informa, pero no bloquea, coherente con el contrato del núcleo. |
| Partidas atómicas, backups y espejo | no | La infraestructura sigue siendo atómica, pero la prevalidación puede instalar un save incompatible antes del rechazo del núcleo. Además, RTC no sigue el formato separado especificado. |
| Save GBA de tamaño incorrecto no sobrescrito | no | El test de 1000 bytes pasa, pero no cubre tamaños estándar incompatibles que `SaveResolution` considera válidos. |
| BIOS opcional de 16 KiB validada por SHA-256 | sí | Solo se entrega al núcleo si coincide con `fd2547…6570`; los archivos ausentes o inválidos usan HLE. |
| App iOS sin red | sí | No aparecen `URLSession`, `Network`, claves ATS ni dependencias runtime nuevas. `Info.plist` no contiene excepciones de red. |
| Core GB sin cambio de comportamiento | sí, parcialmente dinámico | Diff funcional mínimo. El binario existente ejecutó `PASS: 1349 comprobaciones, 0 fallos`. |
| Tests previos y siete tests GBA | no | El artefacto remoto fallido muestra que los 100 tests unitarios pasaron, pero la suite completa terminó fallando. El sandbox impidió reejecutar Xcode/CoreSimulator. |
| CI verde para el commit auditado | no | La evidencia publicada disponible corresponde a `3fab62a` y dice `failure`, no a `fef885d`/`a3c5fcf`. |
| Capturas con homebrew `.gba` | sí | Existen `game-gba-portrait-dark.png` y `game-gba-landscape-dark.png`, aunque pertenecen al run global fallido. |
| Sin ROMs, BIOS o saves prohibidos en Git | sí | La revisión de `git log --all --stat`, nombres históricos y archivos rastreados no encontró extensiones prohibidas ni BIOS. |
| Sin contaminación GPL/AGPL | sí | No se encontró código o atribución nueva que indique copia de Gambatte, Delta, mGBA/NanoBoy/Hades o SameBoy iOS. |

## Notas

- Commit auditado: `a3c5fcf771cda2a8e70ae4e98605f0bf6875cb39`, rama `g7-gba-app`.
- `git diff --check origin/main...HEAD` no reportó errores.
- Pruebas ejecutadas sin escribir el repositorio:
  - `core/build/gbtest --unit`: 1349 comprobaciones, 0 fallos.
  - `gba/build/gbatest --unit`: 0 fallos.
- Xcode no pudo reejecutarse: el sandbox denegó la creación de cachés y DerivedData en `/tmp`, y CoreSimulatorService quedó inaccesible. Esto no se considera un hallazgo del proyecto.
- No se revisó nuevamente la corrección interna del núcleo GBA G0–G6, conforme al alcance indicado.
