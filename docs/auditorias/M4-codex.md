## Veredicto: RECHAZAR

## Hallazgos

| ID | Severidad (bloqueante/alta/media/baja) | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| H1 | bloqueante | [EmulatorSession.swift:169](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Emulator/EmulatorSession.swift:169), [cart.c:264](/Users/joelbermudez/Documents/workspace/pocketgb/core/src/cart.c:264) | Se pueden perder cambios de SRAM al salir o pasar a background si el juego todavía no deshabilitó la RAM. | Una escritura pone `ram_written = true`, pero `sram_dirty` solo se activa al deshabilitar RAM. `flushSRAM(sync:)` no guarda cuando `dirtySinceFirst == nil`. Camino concreto: habilitar RAM → escribir SRAM → background/menú sin deshabilitarla → no se crea snapshot ni `.sav`. También deja sin persistir el RTC si no hubo señal dirty. | En `flushSRAM(sync: true)`, guardar incondicionalmente cuando existe `SaveStore`; `AtomicFile` ya evita rotar si el contenido no cambió. Para el guardado periódico, exponer además una señal de escrituras pendientes independiente del flanco “el juego guardó”. |
| H2 | bloqueante | [M4-evidencia.md:50](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/M4-evidencia.md:50) | El criterio obligatorio en iPhone está explícitamente pendiente. | No se verificó dmg-acid2 en hardware, Pokémon Rojo llegando al menú ni la respuesta de los botones. La captura del simulador es visualmente coherente, pero no sustituye este criterio. | Probar ambos ROMs en el iPhone, controles incluidos, y añadir la confirmación y resultado a la evidencia. |
| H3 | bloqueante | [M4-evidencia.md:18](/Users/joelbermudez/Documents/workspace/pocketgb/docs/auditorias/M4-evidencia.md:18) | La evidencia no demuestra el comando exacto del primer criterio. | El build exitoso añade `CODE_SIGNING_ALLOWED=NO`; la variante con firma falla por falta de dispositivo/perfil. El criterio publicado exige el comando sin esa modificación. | Conectar y registrar el iPhone, generar el perfil y ejecutar literalmente el comando del hito, o modificar formalmente el criterio antes de cerrarlo para distinguir build sin firma de instalación firmada. |
| H4 | media | [CoreBridge.swift:45](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Emulator/CoreBridge.swift:45) | `CoreError.description` recurre sobre sí mismo para varios errores. | El caso `default` interpola `self`; como el tipo implementa `CustomStringConvertible`, vuelve a llamar a `description`. Errores como `.noROM`, `.nullArgument`, estados o `.unknown` pueden terminar en desbordamiento de pila mientras se intenta mostrarlos o registrarlos. | Añadir casos explícitos; para `.unknown(let code)`, mostrar el código numérico sin interpolar `self`. |
| H5 | baja | [ControlsOverlayView.swift:148](/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB/Input/ControlsOverlayView.swift:148) | Los controles verticales no son opacos como indican el hito, la especificación y el comentario del propio archivo. | En vertical se usan alfa `0.30` en reposo y `0.55` pulsado; la captura confirma la transparencia. | Usar alfa `1.0` en vertical o documentar y aprobar explícitamente el cambio de diseño. |

## Criterios del hito

| Criterio | Verificado por el auditor (sí/no) | Resultado |
|---|---|---|
| `xcodebuild ... build` → `BUILD SUCCEEDED`, sin warnings nuevos | no | El sandbox no permite crear DerivedData. La evidencia es plausible para el build sin firma, pero el comando exacto no pasa según la propia evidencia. |
| Sin `URLSession`, `NWConnection`, `Network` ni `http` | sí | PASS: el grep devolvió código 1 sin coincidencias. Tampoco encontré ATS, claves de red, paquetes SPM ni SDKs de runtime. |
| En iPhone: dmg-acid2 correcto y Pokémon Rojo llega al menú y responde | no | Pendiente explícitamente. La captura de dmg-acid2 solo corresponde al simulador. |

## Notas

- Commit auditado: `1a106dfba2ec95c8db23b09ed2803bfc08f802bc`, diff contra `main` (`51d2076`).
- Hay cambios locales sin commit en `docs/04-ios-spec.md`, `docs/hitos/M4-ios-minima.md` e `ios/README.md`; quedaron fuera de la auditoría del commit.
- `git log --all --stat`, el historial de objetos y los archivos rastreados no muestran ROMs, boot ROMs, `.sav` ni estados.
- No encontré indicios de código GPL/AGPL copiado ni referencias a Gambatte, Delta, mGBA o el frontend iOS de SameBoy en el diff.
- M4 no modifica `core/` ni `gb_state_load`; no aparecen nuevos accesos al ROM o índices de cartucho que auditar.
- La implementación de `AtomicFile` conserva el `.sav` vigente hasta el `rename(2)` final, sincroniza archivos/directorios y genera backups. El problema bloqueante es que ciertos cambios de SRAM nunca llegan a invocarla.
- Reejecuté el binario existente, compatible con las fuentes actuales: `1080` comprobaciones unitarias sin fallos y `103/103` casos requeridos M5 en PASS. ASan y Xcode no pudieron recompilarse porque el sandbox impide escribir incluso en `/tmp`; esto no es un hallazgo del proyecto.

