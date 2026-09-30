# D6 · Evidencia (mando, avance rápido y ajustes completos)

Rama `d2-a1-wip`, CI del runner propio. Verde en `63b8a19`: 93 tests unitarios, UI y Release sin warnings. Incluye las correcciones de la auditoría Codex D2–D5 ([D2-D5-respuesta.md](D2-D5-respuesta.md)), verdes desde `30f7170`.

## Criterios CI (D-README §8)
| Criterio | Verificación |
|---|---|
| Mapeo por posición (derecho = A, inferior = B), Menu → Start, Options → Select | `faceButtonsMapByPosition`, `menuIsStartAndOptionsIsSelect` |
| Cruceta y stick izquierdo, misma máscara; sin opuestos | `dpadAndLeftStickGiveTheSameMask`, `stickDeadZoneAndNoOpposites` |
| Máscara del mando OR táctil | `padMaskCombinesWithTouchByOR`; `EmulatorSession.run` usa `buttons.value \| padButtons.value` |
| Mando conectado oculta solo el dibujo | `ControlsOverlayView.controllerConnected`; captura `gameplay-controller` |
| Avance rápido ×2/×4 con la interfaz de audio de M5 | `setSpeed`: `audioOutput.stop()` (vacía el ring) + reloj; a ×1 se ceba como en `resume()`; el audio del núcleo se descarta mientras dura. `fastForwardCyclesOneTwoFour` |
| Audio: `.ambient` y opción `.playback`; sin audio en segundo plano | `AudioOutput.startOnQueue`; `noBackgroundAudioMode`, `audioAndEmulationSettingsPersistAndValidate` |
| Almacenamiento no ofrece borrar ROMs | `StorageSettingsView` (solo portadas); `storageUsageCountsOnlyAppFolders` |
| Biblioteca sin artwork por red | `LibrarySettingsView` sin esa opción; grep sin red |
| Ajustes por juego con texto "Global"/"Personalizado" | `SettingRowLabel`; `perGameOverridesFallBackToGlobal`, `clearingEveryOverrideRemovesTheGameEntry`; captura `game-settings` |
| Tests sin mando físico | `GamepadSnapshot` puro |

Extra: "Color en juegos de Game Boy" (global y por juego) con paleta automática o 1–12 → `GB_MODEL_CGB` + `compat_palette`. Rojo puede verse en color.

## Revisión visual
settings-audio, -emulation, -storage, -saves, game-settings (claro y oscuro), gameplay-controller y gameplay-fast-forward: ✅. Nota: en el catálogo DEBUG se ignoran los mandos reales (el Mac del CI tenía uno emparejado y ocultaba los controles de todas las capturas de gameplay).

## Pendiente del iPhone
Mando Bluetooth real (conexión, desconexión en juego, mapeo), avance rápido ×2/×4 sin audio desincronizado, Siri/llamada, modo silencio con las dos opciones, 10 min sin underruns, Rojo en color, uso de almacenamiento real.
