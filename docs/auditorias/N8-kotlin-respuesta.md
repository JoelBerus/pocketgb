# N8 Kotlin · respuesta a la auditoría Opus

| # | Gravedad | Respuesta | Cambio |
|---|---|---|---|
| H1 | baja | Corregido | `GameSession`: comentario del invariante junto a `openedSaveSize`. Dentro de una sesión el tamaño solo crece (la EEPROM pasa de 512 B a 8 KiB una vez, y ya confirmado no cambia), así que el salto de `dirtySeq` es un único escalón y nunca retrocede. Si el tamaño volviera al de apertura, solo habría un guardado de más, nunca uno de menos. |
| H2 | baja | Corregido | `GbaGameTest.aValidLocalSaveKeepsSavingWhileAMirrorOfAnotherSizeIsNeverOverwritten`: con una local de 32 KiB, un espejo de 64 KiB y SRAM forzada, sale el aviso `GameSettingsMismatch`, la local se sigue guardando y el espejo queda intacto byte a byte. Mutación comprobada (`target = null` si hay aviso): el test falla con «la local válida se sigue guardando». |
| H3 | info | Documentado, sin cambio | Si L2 o R2 ya tienen otra acción, la de L1/R1 queda en GBA solo en sus otras teclas (el menú sigue en Guía). Lo dicen la guía y la pantalla «Botones del mando». |
| H4 | info | Fijado | `tools/build-gba-homebrew.sh` compila las homebrew con la toolchain de referencia de los hashes: el clang del sistema y, al final del `PATH`, solo `ld.lld` y `llvm-objcopy` del NDK. `GbaNativeTest` pasa 21/21 con las ROMs así compiladas (ver abajo). |

Sugerencia para iOS: conservar los estados de otra configuración, como Android, queda anotada para un lote iOS.

## Verificación desde limpio
Árbol limpio de `b65b30f` (`git archive` + `local.properties` + ROMs libres enlazadas + `tools/build-gba-homebrew.sh`, rc=0):
```
./gradlew --no-daemon --max-workers=1 clean :app:testDebugUnitTest :app:assembleDebug :app:lintDebug   rc=0
JVM: 708 tests, 0 fallos · lint: 0 errors, 23 warnings · aapt2: sin INTERNET
with-lock.sh emu: GbaNativeTest tests=21 failures=0 · GbaGameTest tests=9 failures=0
```
