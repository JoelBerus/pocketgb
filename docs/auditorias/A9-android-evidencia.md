# A9 Android: evidencia (renombrar y continuación exacta)

Rama `a9-android-paridad` desde `siguiente-nivel` @ `c3d8405`. Plan: [N-README §4 A9](../hitos/N-README.md), decisión ND6 (cambia J8 de [A5-plan](../diseno-android/A5-plan.md)). Referencia iOS: D8.1 ([implementación](D8.1-implementacion.md), [auditorías](D8.1-opus.md), [2ª vuelta](D8.1-opus-v2.md)). Estado: **implementado, pendiente de auditoría Opus** (la auditoría debe centrarse en la regla dura 6).

## Qué se hizo

### Renombrar (alias), semántica de iOS D8.1
- `LibraryPreferencesData` gana `aliasesByFingerprint` y `aliasesByPath` (con valor por defecto: un `preferences.json` de A8 sigue cargando; test con la fixture `src/test/resources/library/preferences-a8.json`). Una versión anterior de la app ignora las claves nuevas (`ignoreUnknownKeys`).
- `setAlias`: recorta, convierte saltos de línea y tabuladores en espacios, máx. 80 caracteres **visibles** (grafemas: no parte emojis ni acentos combinantes); vacío o igual al título de la cabecera = sin alias. Va por huella si se conoce y por ruta si no. `recordPlayed` y `recordFingerprint` (detalle) migran el alias de ruta a huella, como `recordPlayed` de iOS.
- `RomEntry.alias`/`displayTitle`; `LibraryQuery.visible`, `recent` y `hidden` aplican el alias; la búsqueda mira alias, cabecera y archivo; el orden por nombre usa el nombre visible.
- UI: `RenameGameDialog` (alias actual seleccionado, tope al escribir o pegar, contador `n/80`, pie «Vacía el campo para volver a «…». El archivo y las partidas no cambian.»). Accesible desde el menú ⋮ de la barra superior del detalle, el menú contextual de la tarjeta (biblioteca, favoritos, recientes) y la hoja de ajustes del juego (fila «Nombre» con el título del cartucho debajo).
- El nombre visible se usa en tarjeta, lista, carril «Continuar jugando», favoritos y recientes, juegos ocultos, detalle (barra y título), diálogo de ocultar, descripción de la portada y menú de pausa (`GameSession.title`).

### Continuación exacta (cambia J8)
- `saves/ExactContinuation.kt` (pura, probada en JVM): «Continuar» retoma el estado AUTO solo si (1) no es anterior al `.sav` local (fecha, como `StateStore.automaticEntry` de iOS) y (2) con la partida **ya abierta** su RAM del cartucho, sin el pie del RTC, es la de la partida (contenido, como `start(restoring:)` de iOS tras D81-H1). El núcleo rechaza además otro ROM, otro modelo (DMG/CGB/compatibilidad) y firma/CRC/longitud/versión. Si no vale: se revierte el núcleo, **no se escribe nada** y se devuelve el motivo (`MISSING`, `UNREADABLE`, `NOT_CURRENT`, `INCOMPATIBLE`, `CORRUPT`).
- `GameLauncher.openBlocking(mode = RESUME)`: el paso va después de `SaveOpening` (que pudo instalar un espejo más nuevo, con backup) y de cargar la SRAM, con la propiedad exclusiva de la huella ya adquirida, y antes de crear el `SaveCoordinator` y el hilo. Si no vale, `OpenError.ResumeFailed` y el `finally` de siempre cierra la sesión y suelta la huella. Si vale, su RAM ya es la de disco: no hay que persistir nada (el `baseline` del guardado sigue siendo lo que hay en disco).
- RTC: `native_session_set_rtc_time` (nueva, solo aparcada, `gb_rtc_set_time` nunca retrasa) tras un estado aceptado, como iOS D81-H5. `EmulatorSession.saveStateParked/loadStateParked` permiten el estado con la sesión sin arrancar (el nativo ya lo admitía). `core/` no cambia.
- AUTO en segundo plano (iOS D81-H4/D81V2-H3): `ON_STOP` → `GameplayViewModel.onStopped` → `GameSession.saveAutoStateIfParked`, fuera del hilo principal y bajo el mismo mutex que salir y los estados: vacía la SRAM y **solo si quedó a salvo** guarda el AUTO; nunca con la sesión corriendo ni con la persistencia desactivada tras un rollback fallido. La salida (`exit`) no cambia: SRAM con backup primero, AUTO después.
- AUTO obsoleto: iOS lo borra (D81V2-H2). Android lo **aparta** como `states/<huella>/auto.obsolete.state` (uno por juego): en una sesión sin destino de guardado (J10) el AUTO puede llevar la única copia del progreso. Uno de otro modelo o dañado se queda donde está y el ViewModel no lo vuelve a ofrecer (por su fecha) hasta que se guarde otro.
- UI: el detalle y el menú contextual ofrecen «Continuar» solo con AUTO vigente (`GameplayViewModel.resumable`, calculado con fecha y firma sin leer miniaturas: `GameLauncher.continuations`), más «Jugar desde el inicio» y una nota breve; si no, «Jugar». Aviso «No se pudo continuar» con el motivo y «Jugar desde el inicio» (`ResumeFailedDialog`). Restaurar un backup en Ajustes › Partidas recalcula «Continuar».
- Cadenas nuevas solo en `res/values/strings_a9.xml`.

### Pruebas, catálogo, guía y docs
- JVM: `LibraryAliasTest` (17), `LibraryViewModelTest` (+2), `saves/ExactContinuationTest` (16), `game/ContinuationsQueryTest` (3).
- Instrumentados: `game/ExactContinuationTest` (10, núcleo real y ROM contador sintética), `game/ExactContinuationMirrorTest` (1, espejo SAF más nuevo: el caso bloqueante de iOS D81-H1), `ui/RenameUiTest` (5, ViewModel real y `preferences.json`), `ui/ContinueChoiceUiTest` (3); `LibraryUiTest` y `DebugCatalogTest` pasan de J8 a A9.
- Catálogo Debug (`debug/catalog/A9Catalog.kt`) y 14 líneas nuevas al final de `tools/android-screens.txt`: `details-resume-exact`, `details-rename`, `details-more-menu`, `game-context-menu-resume`, `library-renamed`, `game-settings-rename`, `resume-failed` (claro y oscuro). El menú contextual y el detalle del catálogo existente muestran «Renombrar» como la app.
- `save-stress` (kill-test) abre con «Continuar» (si no vale, desde el inicio, como la app) y guarda el AUTO de segundo plano al azar: si la continuación cargara una partida vieja, el contador del `.sav` retrocedería y `save-verify` lo detectaría. `save-verify` también carga el AUTO y el AUTO apartado.
- Guía: [docs/guia/partidas-continuar-y-renombrar.md](../guia/partidas-continuar-y-renombrar.md). Docs: J8 tachada en `A5-plan.md`, `05-android-spec.md`, filas A9-1…A9-6 en `PRUEBAS-JOEL.md`.

## Commits
`d92969f` alias en preferencias · `43c9757` decisión pura AUTO/SRAM · `9ee0c5b` AUTO obsoleto apartado · `c8af5ec` estados sin arrancar y RTC · `dcf5f6d` «Continuar» en la apertura y AUTO en segundo plano · `8289ba8` UI · `5d451b1` catálogo, instrumentados y estrés · `46d0b60` guía · `385d949` docs · `4cfbe8f` test de la consulta · `f3765ec` corrección de un test · `f9d4895` comentarios · `1b2e620` test D81-H1 con espejo SAF.

## Comandos y salidas

Mac compartido con otros lotes (load average entre 45 y 376 durante la sesión). Emulador `Small_Phone_API_35` (API 35) sin ventana, siempre con el mutex `with-lock.sh emu`.

### JVM, APK y lint (último commit, `1b2e620`)
`./gradlew --no-daemon --max-workers=1 --continue :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`
```
> Task :app:testDebugUnitTest
> Task :app:assembleRelease
> Task :app:lintDebug
BUILD SUCCESSFUL in 3m 23s
EXIT 0
```
Suma de los XML de `build/test-results/testDebugUnitTest`: **413 tests, 0 fallos, 0 errores, 0 omitidos** (línea base de `siguiente-nivel` en este Mac: 375). Nuevos o ampliados: `LibraryAliasTest` 17, `LibraryViewModelTest` 32 (+2), `saves/ExactContinuationTest` 16, `game/ContinuationsQueryTest` 3; `ManifestPolicyTest` 9 en verde.

Lint: `0 errors, 20 warnings`, ninguno en archivos de A9 (`grep -c "strings_a9|A9|ExactContinuation|Rename"` = 0). Un aviso intermedio (`Typos` sobre «momento», falso positivo en español) se quitó reformulando la cadena.

Manifiesto sin `INTERNET` (`aapt2 dump permissions` del APK Release):
```
package: com.joelbermudez.pocketgb
permission: com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

`ProcessKillTest` (JVM, ajeno a A9: `git diff c3d8405` de `SaveStore`, `AtomicSaveWriter`, `SaveFileOps`, `SaveLocks` y del propio test está vacío) falló tres veces con el Mac a load ~300 por su umbral de significatividad («debía completarse algún guardado en total (34)», luego 25 y 36; umbral > 40), con todos los invariantes tras los 200 SIGKILL en verde; es el comportamiento ya documentado en A5, A6 y A7. En la corrida final pasó: `kills en bucle=197, con .tmp huérfano=144, guardados completados=58`.

### Mutaciones (los tests detectan la falta de la corrección)
Sin el rollback del núcleo y con `isFreshByDate` siempre verdadero (`saves/ExactContinuationTest`):
```
ExactContinuationTest > aNewerSaveInvalidatesTheStateByDateAndFallsBackWithoutLoss FAILED
ExactContinuationTest > theLibraryOffersContinueOnlyForAFreshSignedState FAILED
ExactContinuationTest > aStateWhoseCartridgeRamDiffersIsRejectedAndTheCoreIsRolledBack FAILED
15 tests completed, 3 failed
```

### Instrumentados
Primero las clases de A9 (`am instrument -e class …`): 38 tests, 1 fallo del propio test (`theViewModelExplains…`: el ViewModel arranca el juego y la ROM contador ya había cambiado `$A000`; se compara el resto de la RAM, `f3765ec`). Repetida la clase: `OK (10 tests)`.

Suite completa: `./gradlew --no-daemon --max-workers=1 --continue :app:connectedDebugAndroidTest`
```
BUILD SUCCESSFUL in 7m 34s
CONNECTED EXIT 0
```
XML `TEST-Small_Phone_API_35(AVD) - 15.xml`: **357 tests, 0 fallos, 0 errores, 0 omitidos**. De A9: `ExactContinuationTest` 10, `ExactContinuationMirrorTest` 1, `RenameUiTest` 5, `ContinueChoiceUiTest` 3; `LibraryUiTest` 61, `DebugCatalogTest` 11 y `CatalogCoverageTest` 9 (manifiesto ↔ catálogo) en verde.

### Kill-test de partidas
`with-lock.sh emu tools/android-save-kill-test.sh 50`, con el estrés de A9 (abre con «Continuar» y guarda el AUTO de segundo plano):
```
iter   1  kill=force-stop tras  225 ms  -> OK bytes=8192 counter=25 confirmed=25 backups=2 stateTmpFound=0 stateTmpOrphans=0
…
iter  50  kill=kill9      tras 1122 ms  -> OK bytes=8192 counter=3234 confirmed=3234 backups=5 stateTmpFound=0 stateTmpOrphans=0
Resultado: OK=50 FAIL=0 sin-verificación=0 de 50 (stress listo antes de matar: 50)
OK: 50/50 iteraciones con el invariante intacto
```
El contador del `.sav` nunca retrocedió respecto al último confirmado (lo exige `save-verify`): ninguna apertura con «Continuar» cargó una partida vieja.

El script no registra con qué modo abre cada iteración, así que se tomó aparte una muestra de 16 aperturas del mismo estrés con muerte al azar (`force-stop`/`kill -9` alternos; la app recién reinstalada, sin datos), con una verificación al final:
```
muestra 1: READY fp=91fc834e mode=fresh(ResumeFailed(reason=MISSING, …))
muestra 2: READY fp=91fc834e mode=fresh(ResumeFailed(reason=NOT_CURRENT, …))
muestra 3: READY fp=91fc834e mode=resumed
muestra 4: READY fp=91fc834e mode=resumed
…
muestra 16: READY fp=91fc834e mode=resumed
verificación final: OK bytes=8192 counter=536 confirmed=536 backups=5 stateTmpFound=0 stateTmpOrphans=0
```
Recuento: 4 `resumed`, 9 `NOT_CURRENT` (el juego guardó después del AUTO: se aparta y se abre desde el inicio) y 3 `MISSING` (sin AUTO tras apartarlo o al empezar). Las tres rutas corren bajo muertes del proceso sin romper el invariante.

### Capturas revisadas
`SCREENS="…" THEMES="light dark" tools/android-screenshots.sh` (20 PNG, vistos uno a uno): `details-resume-exact`, `details-rename`, `details-more-menu`, `game-context-menu-resume`, `library-renamed`, `game-settings-rename`, `resume-failed` y las afectadas `game-context-menu` (ahora con «Renombrar»), `library-detail` (menú ⋮) y `library-detail-played` («Jugar»), en claro y oscuro. Se ven bien: el alias en la barra, el título, el carril y la hoja de ajustes; «Continuar» + «Jugar desde el inicio»; el contador `12/80` y el pie del diálogo. Observaciones: en `details-rename` aparece el teclado aunque el catálogo no pide el foco (el sistema enfoca el campo del diálogo; en la app se pide igual); en `game-settings-rename` sale el globo «Drag handle» de la hoja (lo mismo pasa con la hoja de ajustes existente); en `resume-failed` los dos botones se apilan por la longitud de «Jugar desde el inicio». El diálogo «No se pudo continuar» va oscuro también sobre la biblioteca clara, como el resto de diálogos de partida (K4, `GameplayRoot`).

## Decisiones tomadas (para ratificar por Joel)
| # | Decisión | Motivo |
|---|---|---|
| A9-1 | El AUTO obsoleto se **aparta** (`auto.obsolete.state`, uno por juego) en vez de borrarse como en iOS. | Regla 6: con un `.sav` de tamaño incorrecto (J10) la sesión no guarda y el AUTO puede llevar la única copia del progreso. |
| A9-2 | Un AUTO de otro modelo o dañado se queda en su sitio y no se vuelve a ofrecer (en memoria, por su fecha) hasta que se guarde otro. | Al volver al modelo anterior vuelve a servir; uno dañado se puede revisar. |
| A9-3 | El AUTO de segundo plano se guarda en `ON_STOP` (no en `ON_PAUSE`), fuera del hilo principal y bajo el mutex de operaciones, solo con la sesión aparcada y tras un vaciado a salvo. Si el proceso muere antes, vale el AUTO anterior o ninguno. | Sin riesgo de ANR (el vaciado de 3 s ya va en el hilo principal) y sin carreras con salir o con los estados. |
| A9-4 | Botón principal: «Continuar» solo con AUTO vigente; si no, «Jugar» aunque el juego se haya jugado o haya `.sav` junto a la ROM (antes, J8: «Continuar» = abrir la partida). | Paridad con iOS: «Continuar» ya no significa lo mismo. |
| A9-5 | El carril «Continuar jugando» conserva su contenido (K10: recientes con portada). Su botón retoma el AUTO si vale y, si no, abre la partida. iOS solo muestra en el carril los juegos reanudables. | El carril se revisa en N3a; aquí no se cambia qué muestra. |
| A9-6 | Un alias igual al título de la cabecera no se guarda; saltos de línea y tabuladores pasan a espacios; el tope de 80 cuenta caracteres visibles (grafemas). | iOS guarda el alias tal cual y corta por `Character`; el resultado visible es el mismo. |
| A9-7 | La búsqueda mira alias, título de la cabecera y nombre de archivo (iOS: nombre visible y archivo). | Que «pokemon» siga encontrando un Rojo renombrado. |
| A9-8 | El reloj del MBC3 se ajusta con una función nativa nueva de `native_session.c` (no de `core/`). | `core/` ya expone `gb_rtc_set_time`. |
| A9-9 | Sin aviso al continuar con éxito (como iOS). | — |

## Riesgos para la regla 6 (partidas)
- **Continuar nunca escribe la partida.** Si el AUTO vale, su RAM ya es la de disco y no se persiste nada al abrir (el `baseline` del guardado sigue siendo lo que hay en disco); si no vale, la sesión se cierra sin haber creado el guardado. Con RTC, el pie del reloj sí cambia y se escribe en el primer vaciado normal (con backup), como en iOS.
- **Orden SRAM → AUTO** sin cambios al salir; en segundo plano, el AUTO solo tras un vaciado `Saved/Unchanged`. Si la SRAM no quedó a salvo, no hay AUTO nuevo (y uno viejo queda invalidado por fecha o por contenido).
- **Huella:** la continuación corre con la propiedad exclusiva ya adquirida (`FingerprintOwnership`) y el `finally` de siempre suelta la huella si falla; no hay ruta nueva que escriba fuera de ella (el AUTO de segundo plano es de la sesión abierta, que posee la huella).
- **Residual:** la comprobación por fecha usa `lastModified` del sistema de archivos (resolución de 1 s en algunos); la comprobación por contenido es la que protege la partida y está probada sola (`anAutomaticStateWhoseRamDiffers…`). Un estado con la misma RAM pero otra posición se retoma: es la definición de «Continuar» (no cambia la partida).
- **Residual:** el AUTO de segundo plano lee la SRAM del núcleo con la sesión aparcada desde el hilo de E/S (como ya hacía `loadState`); si el usuario reanuda a la vez, la copia nativa falla con `NotParked` y no se escribe nada.

## No verificado
- Pruebas reales de Joel en el teléfono: filas A9-1 a A9-6 de [PRUEBAS-JOEL](../PRUEBAS-JOEL.md) (Rojo real, cierre desde el selector de apps, `.sav` llegado de otro equipo por Drive, renombrar y buscar).
- El espejo SAF con Google Drive real: el caso «espejo más nuevo + Continuar» se probó con el proveedor de pruebas (`ExactContinuationMirrorTest`), no con Drive.
- Un juego con RTC real (Oro/Plata/Cristal): solo la ROM sintética MBC3+RTC.
- TalkBack sobre el diálogo de renombrar y los botones nuevos (solo las pruebas Compose de semántica y tamaños de toque existentes).
- iOS no cambia en este lote; la guía describe ambas apps, pero solo Android se probó aquí.
- `make -C core test`: no se ejecutó; `core/` no cambia (`git diff c3d8405 -- core` vacío).
