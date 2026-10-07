# N8 nativo: respuesta a la auditoría Opus

Informe: [N8-nativo-opus.md](N8-nativo-opus.md) (APROBAR CON CAMBIOS, sin bloqueantes; TSan y ASan limpios). Rama `n8-android-gba-nativo`, sin push. Evidencia actualizada: [N8-nativo-evidencia.md](N8-nativo-evidencia.md).

| ID | Decisión | Qué se hizo | Verificación |
|---|---|---|---|
| H1 (media) | Corregido | `CMakeLists.txt` añade `$<$<CONFIG:Debug>:-O2>` a `pocketgba` (núcleo GBA) y a `pocketgb` (núcleo GB, sesión nativa con el blit, JNI y audio); `-g` se conserva. El código nativo es pequeño y lo caliente (núcleos y blit) es casi todo, así que no compensa dejar partes a `-O0`. `07-instalacion-android.md` y `PRUEBAS-JOEL.md` dicen que Debug sirve para medir rendimiento. | `compile_commands.json` Debug arm64: `gba.c`, `cpu.c`, `native_session.c` y `pocketgb_jni.c` con `-g -O2`. En el emulador, app Debug frente a runner `-O2`, en la misma corrida: arm.gba 0,61 frente a 0,56 ms; ppu_scene_11 1,94 frente a 1,73 ms; shades 0,89 frente a 0,80 ms. Antes de H1: 2,04, 8,90 y 3,51 ms. Las pruebas de frames idénticos al runner siguen pasando con `-O2`. |
| H2 (baja) | Corregido | `native_session_load` y `native_session_load_gba` solo cargan en NEW; fuera de NEW (ya cargada o detenida) devuelven `NS_BUSY` sin tocar nada. `native_session_load` devuelve ahora `int`, como el resto. Una carga GBA fallida recrea el núcleo; si no hay memoria para uno nuevo, la sesión se queda sin núcleo (nunca con la BIOS de un intento fallido) y toda carga posterior da `OutOfMemory`. `CoreBridge(Console.GBA).loadGbaRom` crea un núcleo nuevo en cada carga y libera el anterior, así que una BIOS o un ROM previos no siguen activos. | `GbaNativeTest.aSessionLoadsOnceAndAFailedLoadLeavesItNew`: fallo → NEW → carga OK → segunda carga −1 (cabecera y tamaño de partida siguen siendo los de la primera) → detenida −1; lo mismo en GB. `GbaNativeTest.coreBridgeUsesAFreshCoreForEachGbaLoad`: stripes y después shades en el mismo `CoreBridge` dan los 60 frames de shades del runner. El caso «BIOS previa» no se puede probar sin la BIOS oficial; queda cubierto por construcción (núcleo nuevo). |
| H3 (baja) | Corregido | `GameSurfaceTest.gbaImageKeepsThreeToTwoWithBlackBandsInIntegerAndFill` usa una ROM sintética en forced blank (pantalla blanca, comprobada con el runner) en una superficie de 700×400 px. Lee la `SurfaceView` con `PixelCopy` en INTEGER → FILL → INTEGER y compara píxel a píxel con la geometría del blit. Dentro debe haber blanco: 480×320 en INTEGER y 600×400 en FILL, ambos 3:2 y centrados. Fuera deben ser bandas negras. | 5/5 en `GameSurfaceTest` (worktree y desde limpio). |
| H4 (baja) | Corregido | La tabla de símbolos de la evidencia se recalculó con la rama integrada: 280 símbolos (A9 añade `nativeSessionSetRtcTime`), 79 `gba_`/`arm_`, `sha256` 1 y 0 duplicados en los 4 ABI. También se calculó para Release. | Ver evidencia, §Símbolos. |
| H5 (baja) | Riesgo residual documentado | Con `time_t` de 32 bits (armeabi-v7a, x86), a partir de 2038 `native_local_time` devuelve UTC sin convertir; es el mismo límite que ya tiene `time(NULL)`. No afecta a arm64-v8a ni a x86_64. | Evidencia, §No verificado. |
| Notas | Pasadas a N8 Kotlin | Orden `.sav` → estado automático con EEPROM de 8 KiB (`GameLauncher` ya carga el `.sav` antes de `ExactContinuation.resume`). Una DMA que confirma 8 KiB sin escribir deja sin `.sav` y la continuación da `StateConfig`. `Viewport` debe usar la geometría del blit. | Evidencia, §Lo que queda para «N8 Kotlin». |
| iOS `sramLoad` (fuera de alcance) | Sin cambios aquí | Android ya restaura el RTC si el medio se rechaza; la diferencia con iOS queda anotada para un lote iOS. | — |

## Verificación desde limpio
`git archive` de `fb8b6c8` en `scratchpad/n8-clean4` + `android/local.properties`, con las ROMs libres enlazadas (no copiadas):
```
$ ./gradlew --no-daemon --max-workers=1 clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
buildCMakeDebug y buildCMakeRelWithDebInfo × 4 ABI (Debug: gba.c, cpu.c, native_session.c con -g -O2)
BUILD SUCCESSFUL in 4m 50s — 123 actionable tasks: 122 executed, 1 up-to-date
JVM: 483 tests, 0 skipped, 0 failures, 0 errors
lint: 20 avisos, 0 errores
aapt2 dump permissions (Debug y Release): solo DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION → sin INTERNET
```
Dentro de `with-lock.sh emu` (Small_Phone_API_35, `boot_completed=1`, arm64-v8a):
```
package=com.joelbermudez.pocketgb.emulator   tests=65 failures=0 errors=0 skipped=0
  CoreBridgeTest 6 · EmulatorSessionHandleLockTest 3 · EmulatorSessionTest 15 · GbaNativeTest 21 · NativeLibraryTest 1 · NativeSaveBridgeTest 19
class=com.joelbermudez.pocketgb.video.GameSurfaceTest   tests=5 failures=0 (incluye la de PixelCopy GBA)
package=com.joelbermudez.pocketgb.input      tests=29 failures=0
tools/android-save-kill-test.sh 50 (APK Debug -O2 del árbol limpio):
iter  50  kill=kill9      tras  428 ms  -> OK bytes=8192 counter=3235 confirmed=3235 backups=5 stateTmpFound=0 stateTmpOrphans=0
Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
```
