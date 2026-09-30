## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | bloqueante | [SaveMirror.swift](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Saves/SaveMirror.swift:149) | Un espejo más reciente puede sobrescribirse sin backup si su contenido coincide con cualquiera de los últimos hashes escritos por PocketGB. Esto infringe la regla dura 6 y el contrato “si difieren, gana el más reciente y se respalda el otro”. | `mirrorIsOwned` ignora completamente las fechas y elige la copia local con `backupOther: nil` y `updateMirror: true`. Después, `EmulatorSession.run()` llama a `retryMirrorIfNeeded`, que reemplaza el espejo. Caso concreto: el usuario restaura externamente una partida antigua legítima —cuyo hash sigue en `mirror-history.json`—, por lo que tiene fecha nueva; PocketGB la considera “escritura asíncrona vieja”, carga la local y sobrescribe el archivo restaurado sin guardarlo. Los tests solo distinguen “owned stale” de “external new” usando contenidos nunca vistos, y no cubren la restauración de contenido histórico. | No usar únicamente el hash histórico para invalidar la fecha. Registrar la identidad de una operación pendiente concreta —hash, generación y/o metadatos del archivo observado— y aplicar la excepción solo cuando pueda demostrarse que es la finalización tardía de esa operación. En los demás casos debe ganar `modificationDate` y respaldarse el perdedor. Añadir un test: espejo con contenido histórico reconocido pero fecha posterior debe ganar y conservar la local en `.1`. |
| H2 | alta | [EmulatorSession.swift](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Emulator/EmulatorSession.swift:223) | `loadState` comunica éxito aunque falle la persistencia inmediata de la SRAM cargada. | `core.stateLoad` ya mutó el núcleo; luego `flushSRAM(sync: true)` absorbe cualquier error en las líneas 421–459 y no devuelve resultado. `AppState.loadState` muestra “Estado cargado” porque nunca recibe el fallo. No existe test de error de disco durante esta operación. Por tanto, el criterio “tras cargar, la SRAM se guarda en el acto por la ruta normal” solo está probado para el camino feliz. | Hacer que el flush síncrono propague el error. Antes de cargar, capturar el estado/SRAM actual para poder restaurar el núcleo si la persistencia falla, o mantener el estado cargado pero presentar un error explícito y bloquear la salida hasta completar/reintentar el guardado. Añadir prueba con `SaveTarget`/writer local que falle. |
| H3 | media | [D2-evidencia.md](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/D2-evidencia.md:35) | La evidencia declara que en todo conflicto gana la copia más reciente, pero el código implementa una excepción no documentada que provoca H1. | La tabla atribuye el criterio a `newerMirrorWins…`; ese test llama a `resolve` con `mirrorIsOwned == false`. No demuestra el comportamiento cuando el espejo más reciente tiene un hash histórico. | Corregir primero H1 y ampliar tanto la evidencia como los tests para cubrir un espejo nuevo con contenido previamente escrito por PocketGB. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---:|---|
| D2: solo `.gb`/`.gbc`, profundidad máxima 1 y límite de 8 MiB | sí | Implementado con enumeración acotada y lectura máxima de 8 MiB + 1. |
| D2: lectura coordinada y ROM no modificado | sí | `LibraryScanner` usa `NSFileCoordinator`; no encontré escrituras al ROM. |
| D2: escritura local atómica con cinco backups | sí | `tmp` + `fsync` + `rename` y rotación `.1`–`.5`. |
| D2: primer guardado interrumpido recuperable | sí | Implementación y test específico presentes. |
| D2: `.sav` de tamaño incorrecto no se sobrescribe | sí | Se desactiva el destino o se ignora/cuarentena la copia incorrecta. |
| D2: conflicto local/espejo selecciona el más reciente y respalda el otro | no | Falla para contenido reconocido por el historial; véase H1. |
| D2: fallo del espejo conserva la copia local | sí | La escritura local precede al enqueue del espejo. |
| D2: restaurar backup respalda la partida actual | sí | Pasa por `SaveStore.save`/`AtomicFile`. |
| D3: filtros, búsqueda, favoritos, ocultar sin borrar archivos | sí | Código y tests correspondientes presentes. |
| D3: ausencia de red | sí | Sin coincidencias de `URLSession`, `NWConnection`, ATS o `import Network` en la app. |
| D3: portada local y placeholder determinista | sí | Implementación y tests presentes. |
| D4: única superficie multitáctil y área táctil independiente del alpha | sí | Implementación y tests geométricos presentes. |
| D4: ocho direcciones, zona muerta, A+B y ausencia de opuestos | sí | Tests dedicados presentes y registrados por el CI. |
| D4: `UIGlassEffect`, sin blur/materiales simulados | sí | No encontré `UIBlurEffect` ni `.blur` en el código relevante. |
| D4: layouts separados y cancelación de input al rotar | sí | Implementación y tests presentes. |
| D5: cuatro slots manuales y uno automático | sí | Definidos y comprobados por test. |
| D5: estado escrito atómicamente | sí | `StateStore` usa escritura sincronizada, rename y fsync del directorio. |
| D5: estado corrupto o de otro ROM no cambia SRAM | sí | El núcleo carga en dos pasadas y existe prueba directa. |
| D5: cargar estado persiste inmediatamente su SRAM con backup | no | Solo está probado el camino exitoso; los errores de persistencia se ocultan, H2. |
| D5: autoestado y flush al salir | sí | `closeGame` pausa, intenta guardar el autoestado y detiene la sesión; `stop` espera el flush local. |
| Reglas duras: ROMs, saves o estados versionados | sí | `git log --all --stat` y `git rev-list --objects --all` no mostraron archivos prohibidos. |
| Reglas duras: GPL/AGPL copiado | sí | No encontré indicios en el diff; la referencia de SameBoy en `core/cgb.c` conserva licencia MIT y no pertenece a este cambio. |
| Reglas duras: estado global mutable en `core/` | sí | Solo aparecieron tablas `static const` y funciones internas; D2–D5 no modifican el núcleo C. |
| Corrección Pan Docs/core spec | sí | El diff auditado no cambia ejecución de CPU, PPU, MMU ni parser de save states C; no encontré una desviación nueva atribuible a D2–D5. |
| Evidencia correspondiente al commit auditado | sí, con salvedad | `origin/ci-shots/d2-a1-wip/SUMMARY.md` identifica exactamente `6e0188c` y 79 tests exitosos. HEAD solo añade `D5-evidencia.md` y `ESTADO.md`. La afirmación funcional señalada en H3 sigue siendo incompleta. |

## Notas

- Commit auditado: `15c3b495a111a07432ef4b69b7a621d6c84c4b24`.
- Diff solicitado: `origin/main...HEAD`. La rama diverge de `origin/main` en `c384a9d`; los dos commits posteriores de `main` son cambios de CI, no lógica de partidas.
- `git diff --check origin/main...HEAD` terminó sin errores.
- La evidencia remota del CI es plausible: `SUMMARY.md` registra Xcode 26.6, commit `6e0188c`, 79 tests, cinco tests UI, build Release sin warnings y resultado `success`.
- No pude reejecutar compilación/tests: el sandbox de solo lectura impidió incluso crear el directorio temporal con `mktemp`. Esto no constituye un hallazgo del proyecto.
- El working tree contiene `.codex/` y `docs/auditorias/D2-D5-codex.md` sin seguimiento. No los incluí en el diff auditado ni los modifiqué.
