# N6 Android · momentos y progreso: evidencia

Rama `n6-android-momentos` (desde `siguiente-nivel`, con el lector C de N6-C ya integrado). Solo Android; iOS hace su lote aparte. Las decisiones que iOS debe igualar están al final.

## Qué se hizo

| Pieza | Archivos |
|---|---|
| Lector Pokémon por JNI (`progress_pokemon.c` en el CMake, `nativeProgressRead`) | `android/app/src/main/cpp/{CMakeLists.txt,pocketgb_jni.c}`, `emulator/NativeLibrary.kt`, `progress/PokemonProgress.kt` |
| Momentos + anillo «Antes de cargar» + migración de ranuras | `saves/MomentStore.kt` |
| Momentos sin sesión (detalle): recuperar partida con exclusión por huella | `saves/MomentLibrary.kt` |
| Progreso: hitos, plantillas, porcentaje, tiempo de juego, cabecera del ROM | `progress/ProgressStore.kt`, `progress/ProgressService.kt` |
| Sesión: crear y cargar momentos (anillo antes, AUTO intacto), configuración ND13 | `game/GameSession.kt`, `game/GameLauncher.kt`, `game/GameplayViewModel.kt` |
| UI: hoja «Momentos» de la pausa, pantalla «Momentos» del detalle, panel y editor de progreso, fila «Progreso» del centro de ajustes | `ui/moments/*`, `ui/progress/ProgressPanel.kt`, `ui/details/*`, `ui/gameplay/*`, `app/*` |
| Cadenas | `res/values/strings_n6.xml` (archivo nuevo; ningún catálogo existente editado) |
| Guía | `docs/guia/momentos-android.md` |
| Catálogo de capturas | `debug/catalog/N6Catalog.kt` (+1 línea en `DebugCatalog.kt`), 17 líneas al final de `tools/android-screens.txt` |
| Kill-test con momentos | `debug/SaveStress.kt` (crea y carga momentos durante el stress; la verificación lee índice, anillo y estados) |

### Formato en disco (por dispositivo, ND12)
`files/moments/<huella>/`: `m-<id>.{state,sav,png}` (momento), `b-<id>.{state,sav,png}` (anillo «Antes de cargar») e `index.json` (versión 1: nombre, etiquetas, colección, nota, fecha, tiempo jugado, configuración, `hasState/hasSram/hasThumbnail`, `origin`/`originSha` de las ranuras migradas). Cada archivo: temporal + `fsync` + `rename`; **el índice es el punto de confirmación** (último al crear, primero al borrar). `recoverOrphans` borra temporales y archivos sin entrada; un índice ilegible **no borra nada**: se aparta (`index.damaged-…json`) y se reconstruye desde los archivos. Ids `[0-9a-z]{1,32}` (un id del índice nunca es una ruta). `files/progress/<huella>.json`: tiempo, sesiones, primera/última vez, hitos, plantilla, porcentaje y la cabecera del ROM (0x150 B) para el lector.

### Regla dura 6
- **Cargar un momento** (pausa): primero se guarda la posición actual (estado + RAM del cartucho + miniatura + configuración) en el anillo; si eso falla **no se carga nada** (`aMomentNeedsAPausedSessionAndNeverWritesWhenTheRingCannotBeSaved`). Después se aplica el estado con la ruta transaccional de siempre (`applyLoadedState`: vaciado, rollback, barrera y reparación) y la partida anterior pasa al backup `.1`. El AUTO **no se escribe** al cargar.
- **Recuperar** en el detalle (sin sesión): `MomentLibrary.installSram` bajo `FingerprintOwnership.withExclusive`; la partida actual entra en el anillo y, como toda escritura de la partida, en `.1`. Rechaza tamaños que el cartucho no acepta y momentos sin RAM.
- **Exclusión por huella**: cargar desde el detalle abre el juego y carga con la sesión ya dueña del lease; recuperar la RAM o migrar ranuras sin sesión exige el lease (`SavePendingException` si hay sesión abierta o aparcada).
- **Migración** de ranuras 1–4, RESCUE y `rescue-*.state`: copia → índice con `originSha` → borrado de la ranura. Una muerte entre índice y borrado no duplica (se reconoce el SHA). Un fallo en cualquier operación deja la ranura o el momento, nunca ninguno (`aMigrationThatFailsKeepsTheSlot`).

## Verificación desde limpio

`git archive` de la rama en `<scratchpad>/n6clean` (+ `gba-tests` enlazadas desde `pocketgb-n5-android` y `make -C gba homebrew` con el NDK: rc=0, 22 `.gba`), `ANDROID_HOME=~/Library/Android/sdk`, Gradle `--no-daemon --max-workers=1`, emulador `Small_Phone_API_35` solo dentro de `with-lock.sh emu`.

### JVM, lint y APK (árbol limpio en `04f2880`)
```
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon --max-workers=1
BUILD SUCCESSFUL in 5m 25s
123 actionable tasks: 122 executed, 1 up-to-date
```
- **JVM: 749 pruebas, 0 fallos, 0 errores, 0 omitidas.** Nuevas: `MomentStoreTest` 10, `MomentLibraryTest` 5, `ProgressStoreTest` 7, `MomentKillTest` 1.
  ```
  MomentKillTest: semilla=669409533190416, kills en bucle=119, instalaciones completadas=58
  ProcessKillTest: semilla=669433050732500, kills en bucle=197, con .tmp huérfano=130, guardados completados=275
  ```
- **Lint: `0 errors, 27 warnings`.** De N6 solo son 2: `gameplay_pause_states` y `gameplay_pause_states_footer` quedan sin uso (la pausa usa ahora `n6_pause_moments`/`n6_pause_footer`; no se borran por la regla de solo añadir líneas a los catálogos de cadenas). El resto son los de siempre (KTX, versiones de dependencias, plurales y «momento» como errata en `strings_gameplay.xml`, carpeta v26, constructores de vistas). `strings_n6.xml` lleva `tools:ignore="Typos"` («momento» no es una errata en español). La primera corrida dio 6 errores `LocalContextGetResourceValueCall` en `MomentsScreen` y 4 avisos propios; corregidos en `04f2880`.
- **APK**: `app-debug.apk` 22 992 426 B, `app-release-unsigned.apk` 15 741 031 B.
- **Sin `INTERNET`** (`aapt2 dump permissions` del Release):
  ```
  package: com.joelbermudez.pocketgb
  permission: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
  ```

### Instrumentadas completas, sin filtro (árbol limpio en `f21a994`)
```
./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=1
<testsuites tests="481" failures="0" errors="0" skipped="0" ...>   (61 clases)
BUILD SUCCESSFUL in 7m 13s
```
`f21a994` solo cambia una prueba instrumentada respecto a `04f2880`. Nuevas o cambiadas:
- `MomentsSessionTest` 4 (núcleo real, ROM sintética contador): cargar un momento antiguo cambia la partida, la más nueva queda en backup y en el anillo, **el AUTO no cambia**; «Recuperar» la devuelve en un toque y la del momento queda en backup · un estado que el núcleo ya no carga (simulado: bytes alterados) da `StateError.Core` sin tocar la partida; con la sesión abierta `installSram` da `SavePendingException`; tras salir, la RAM del momento se instala y la anterior queda en backup · sin poder escribir el anillo no se carga nada · al abrir se migran `slot2` y `rescue` byte a byte, se avisa `RescueMoment` y el momento migrado carga.
- `PokemonReaderNativeTest` 2: partida de la 1.ª generación construida byte a byte («ROJO», 3 medallas, 2/5 Pokédex, 12:34:56, 1234 ₽) se lee por JNI; checksum mal, otro título, japonés, cabecera corta o tamaño de partida inválido = sin datos.
- `MomentsScreenTest` 2 (almacén real): colecciones, filtro por etiqueta, editar nombre y nota, «Cargar» abre el juego con el momento pendiente; «Recuperar» con la huella ocupada avisa y no toca nada, y sin dueño instala la partida con la anterior en `.1`.
- `GameplayUiTest` 12 (+1, 1 reescrita): crear (nombre sugerido «Momento 1»), cargar con la guía en la confirmación (sin AUTO), recuperar, cancelar y borrar · cargar desde el detalle abre el juego en pausa y carga; el tiempo de juego cuenta la sesión (1 sesión, 300–60 000 ms) y **no avanza en pausa**; la cabecera queda guardada para el lector.
- `GameCenterUiTest` 3: la fila «Progreso» ya no está deshabilitada; abre el editor, la plantilla Pokémon añade «Liga Pokémon» y se cierra.
- `StatesConfirmTest` (K14) se retira: «Cargar sin guardar» ya no existe porque cargar no escribe el AUTO (unificado, §3.3).
- `CatalogCoverageTest` 9 y `DebugCatalogTest` 11 con los 14 ids N6.

### Kill-test (APK Debug del árbol limpio en `8f6b6be`)
El stress ahora también crea momentos (cada 5 vueltas, con recorte a 6) y **carga uno al azar a mitad de partida** (con el listón «confirmado» bajado antes al contador del momento, porque cargar retrocede a propósito); la verificación hace `recoverOrphans` de los momentos, exige índice legible, anillo ≤ 3, RAM de tamaño válido y que el núcleo acepte cada estado de momento y del anillo.
```
tools/android-save-kill-test.sh 50
iter  50  kill=kill9      tras  990 ms  -> OK bytes=8192 counter=1642 confirmed=1634 backups=5 stateTmpFound=0 stateTmpOrphans=0 moments=9
Resultado (gb): OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto

tools/android-save-kill-test.sh 20 gba
Resultado (gba): OK=20 FAIL=0 sin-verificación=0 de 20 (stress listo antes de matar: 20)
OK: 20/20 iteraciones con el invariante intacto
```
Tras la corrida, en el dispositivo cada huella tiene el anillo lleno (3 `b-*`, prueba de que hubo cargas) y momentos `m-*` con y sin `.sav` (los sin `.sav` son la `slot1` migrada en cada apertura).

### Mutaciones (JVM, worktree, restaurado con `git checkout` tras cada una)
| Mutación | Prueba que falla |
|---|---|
| El anillo no se recorta a 3 | `theBeforeLoadRingKeepsTheThreeNewestOutsideTheBackups` |
| El índice se escribe antes que los archivos | `everyFailurePointWhileCreatingLeavesEitherNothingOrTheWholeMoment` |
| `installSram` sin `withExclusive` | `nothingIsInstalledWhileTheFingerprintHasAnOwner` |
| Un índice dañado se trata como vacío | `aDamagedIndexIsSetAsideAndRebuiltFromTheFilesWithoutDeletingAnything` |
| El tiempo sigue contando tras la pausa | `playTimeCountsOnlyWhileRunningAndSurvivesAForcedClose` |

## Capturas (revisadas una a una; scratchpad `n6shots/`)
`n6-moments` (C,O), `-empty`, `-load`, `-recover`, `-edit`, `-ax5`, `n6-pause` (O), `n6-pause-moments`, `-create`, `-load-config`, `-recover`, `n6-details-progress` (C,O), `n6-game-center`, `n6-progress-editor` (C,O).
- Detalle › Momentos: «Antes de cargar» con miniatura, «Antes de «…»», fecha y «Recuperar» (el más reciente destacado) debajo de cada entrada; filtro «Todos · captura · jefe · ruta»; colecciones «Experimentos», «Principal» y sin colección; «Ranura 1» migrada avisa de que no tiene copia aparte de la partida.
- Primera versión revisada: el «Recuperar» al lado del texto lo estrujaba («Antes de «…»» en la pausa y una letra por línea al 200 %) y la configuración se veía en crudo («model CGB → DMG»); corregido en `b3513b9` (botón debajo, diálogo desplazable, «modelo: CGB → DMG», dinero con separador de miles («₽12,480» con el emulador en inglés)). Revisadas de nuevo: bien también al 200 %.
- Cargar: título con el nombre, la guía («cambia también la partida… «Antes de cargar»… copias de seguridad… «Recuperar» en un toque») y, desde el detalle, «Se abrirá el juego y se cargará el momento.».
- Pausa: «Momentos» sustituye a «Estados guardados», con el pie nuevo; «Nuevo momento» propone «Momento 5».
- Detalle: botón «Momentos» activo y panel «Progreso» (tiempo, sesiones, primera/última vez, «33 % completado», «Leído de la partida» con «Marcar las medallas leídas (5)»).
- Centro de ajustes: «Progreso y momentos · Hitos, porcentaje y lector de Pokémon» con flecha.
- Conocido y previo a N6: el aviso «Drag handle» sobre el asa de las hojas, y «Bibliotec a» en la barra inferior con la fuente al 200 %.

## Fuera de alcance o pendiente
- **Exportar momentos** entre dispositivos: N7 (ND12).
- **Instantáneas periódicas**: no (ND7, Joel las rechazó).
- **Porcentaje en la tarjeta** de la biblioteca: no está; solo en el detalle (el plan lo pide en tarjeta y detalle, desactivado por defecto).
- El menú contextual de la tarjeta sigue diciendo «Estados (próximamente)»; los momentos se abren desde el detalle y la pausa.
- `StatesSheet` sigue en el código solo para las capturas antiguas del catálogo (`states-sheet`, `states-rescue`); la app ya no la usa.
- La API `GameSession.loadState(slot, saveCurrentToAuto)` sigue para las pruebas antiguas; la UI ya no la llama.
- Un rescate idéntico byte a byte a una ranura ya migrada no crea un segundo momento (mismo SHA): no hay pérdida, pero tampoco aparece «Rescate».

## Decisiones que iOS debe igualar
1. **Cargar no pisa el AUTO** (unificado): la posición previa va al anillo «Antes de cargar», no al AUTO. Desaparece «Cargar sin guardar» (K14).
2. **Anillo de 3**, fuera de la rotación de 5 backups, más reciente primero; el 4.º expulsa al más antiguo. «Recuperar» en la pausa también guarda la posición actual en el anillo (ir y volver).
3. **Cada momento guarda la RAM del cartucho** aparte (`.sav`) además del estado y la configuración `{console, model, palette}` o `{console, gbaSaveType, gbaRtc, gbaBios}`; si la configuración actual difiere, la confirmación lo dice.
4. **Desde el detalle**: «Cargar» abre el juego y carga en pausa (exclusión por la propia sesión); «Recuperar» y «Recuperar su partida» instalan solo la RAM, sin sesión y bajo el lease, con la partida actual al anillo y al backup.
5. **Migración** de ranuras 1–4 y RESCUE (y rescates apartados) al abrir el juego o su pantalla de momentos con la huella libre; nombres «Ranura N», «Rescate», «Rescate (fecha)»; deduplicación por SHA-256 del estado; aviso al abrir si había rescate.
6. **Índice como punto de confirmación**; índice dañado = apartar y reconstruir, nunca borrar.
7. **Tiempo de juego** solo con el juego corriendo; escritura en cada parada y cada 30 s (un cierre forzado pierde como mucho 30 s); una sesión = la primera vez que el juego corre tras abrirlo.
8. **Hitos**: plantillas «Libre» y «Pokémon: 8 medallas + Liga» (9 hitos «Medalla 1…8», «Liga Pokémon»); aplicar una plantilla no borra ni duplica; porcentaje desactivado por defecto. El lector **propone** marcar las N primeras medallas, nunca las marca solo.
9. Lector: la cabecera del ROM se guarda al abrir el juego para leer desde el detalle sin abrir el ROM; solo lee la partida local.
