# D5 · Evidencia (HUD, pausa y save states) + cambios de Joel

Rama `d2-a1-wip`, CI del runner propio. Verde en `6e0188c`: 79 tests unitarios, UI y Release sin warnings.

## Historial de CI
| Commit | Resultado |
|---|---|
| `2be181e` | success (77 tests): HUD desplegable, pausa, estados |
| `e932cc6` | cancelado por el push siguiente (78 tests en verde antes de cancelar) |
| `eb4c358`, `68a9f56` | failure: `ShellTests` no encontraba "Apariencia" (la tab Ajustes a veces ignora el primer toque) |
| `37ac95a` | failure: `loadingAStateSavesItsSRAMWithBackupOfThePrevious` intermitente (el ROM de prueba solo cambiaba 1 byte: 1/256 de coincidir) |
| `6e0188c` | **success** |

## Decisiones de Joel (2026-09-30)
- **El botón de menú pausa y abre las opciones** (sheet de pausa). Sustituye al HUD desplegable de D5 que no pausaba; por eso no se usa `glassEffectID` ni hay auto-ocultado a 3 s (criterios D5 del HUD sustituidos). Fuera la captura `gameplay-portrait-hud`.
- **Cruceta a elegir** (Ajustes › Controles): Game Boy (cruz) o flechas separadas estilo PlayStation. Solo cambia el dibujo; misma lógica de 8 direcciones.
- **Tamaño por control** en el editor (60–160 %, pasos del 10 %, por orientación).
- "Continuar" exacto (cargar el estado automático al abrir): lo resuelve Joel en otra rama. D5 guarda el estado automático al salir.

## Criterios CI (D-README §7)
| Criterio | Verificación |
|---|---|
| Pause sheet con `presentationDetents` | `PauseView` (.medium, .large) |
| 4 manuales + 1 auto | `fourManualSlotsAndOneAuto` |
| Estado escrito de forma atómica | `StateStore.replace` (tmp + fsync + rename + fsync del directorio); `saveListLoadAndDelete` sin `.tmp` |
| Estado corrupto o de otro ROM se rechaza sin tocar SRAM | `coreRejectsCorruptAndForeignStatesWithoutTouchingSRAM` |
| Tras cargar, la SRAM se guarda por la ruta normal con backup | `loadingAStateSavesItsSRAMWithBackupOfThePrevious` |
| Estados solo con la sesión en pausa | `statesNeedAPausedSession` |
| Confirmación con "Guardar actual y cargar" | Captura `load-state-confirm` |
| Salir hace flush síncrono | `closeGame`: pausa (flush) → estado automático → `stop()` (flush) |
| Sin cheats | grep |

## Revisión visual
| Captura | Resultado |
|---|---|
| gameplay-pause | ✅ Frame atenuado, sheet medium: Continuar dominante, Estados, Personalizar controles, Salir separado |
| save-states | ✅ Automático + 3 manuales con captura y fecha, ranura 4 vacía, selección con check, barra Cargar / Guardar aquí / Borrar |
| load-state-confirm / replace-state-confirm | ✅ Explican qué se pierde y ofrecen cancelar |
| gameplay-portrait / -arrows / -landscape-arrows | ✅ Botón de pausa único; flechas separadas con vidrio |
| customize-controls-size | ✅ Control elegido con contorno continuo y − / + legible sobre frame claro (`68a9f56`) |

## Pendiente del iPhone
Detents y gesto de la sheet, guardar/cargar en una sesión real, salir justo después de cargar, background con una confirmación abierta, flechas separadas y tamaños al tacto.
