# Respuesta a la auditoría Codex de G7

Informe: [G7-codex.md](G7-codex.md) (RECHAZAR, sobre `a3c5fcf`).

| ID | Resolución |
|---|---|
| G7-1 | **Descartado: referencia local desactualizada del auditor.** `origin/ci-shots/g7-gba-app` está en `4fbeb31` ("Capturas de g7-gba-app @ fef885d"); su `SUMMARY.md` dice `resultado: success`, run https://github.com/JoelBerus/pocketgb/actions/runs/37335162227. El código de `a3c5fcf` es `fef885d` más la evidencia. Además, Claude ejecutó la suite en el Mac antes del push (100 tests, Release OK). |
| G7-2 | **Descartado como defecto de `SaveOpening`, pero se endurece el puente.** `SaveOpening.prepare` recibe `EmulatorSession.validSaveSizes(info)` (para una SRAM de 32 KiB, solo `[32768]`): `SaveResolution` rechaza el espejo de 64/128 KiB antes de instalar nada, así que no hay sustitución de la local. Aun así `GBACoreBridge.isMediaSize` aceptaba cualquier medio GBA; ahora solo acepta el del cartucho (EEPROM: 512 B u 8 KiB) como defensa en profundidad. |
| G7-3 | **Aceptado como decisión y documentada** (`docs/hitos/G-README.md` §G7 actualizado). El RTC va al final del `.sav` del cartucho con reloj (el mismo formato que mGBA: p. ej. 131 088 bytes para Flash 128 KiB + 16), en lugar de un `.rtc` aparte que habría exigido un segundo archivo, backups y espejo. Un `.sav` crudo de otro emulador (sin los 16 bytes) se sigue leyendo. **Pendiente de confirmar por Joel** si prefiere el `.rtc` separado. |
| G7-4 | **Corregido.** Tests nuevos en `GBATests`: `newerMirrorOfAnotherMediumNeverReplacesTheLocalSave` (espejo más nuevo de 1000, 512, 8192, 64 KiB, 128 KiB y 32 KiB+16 bytes sobre un cartucho SRAM: `SaveOpening` devuelve la local, el disco queda igual y sin backups; el puente rechaza esos tamaños) y `rtcCartridgeAcceptsOnlyItsMediumWithOrWithoutTheClock`. 102/102 tests unitarios en verde. |
