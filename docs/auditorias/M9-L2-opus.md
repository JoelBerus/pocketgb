# Auditoría M9 lotes 1–2 (Opus, solo lectura)

Rango: `cdb8b2f...2b03aeb` (`5684089` lote 1, `2b03aeb` lote 2), auditado con `git show`/`git diff` por commit. El worktree tenía `ios/PocketGB/Emulator/LinkSession.swift` sin seguimiento (lote 3 en curso), así que **no** compilé ahí: exporté `2b03aeb` con `git archive` a una carpeta del scratchpad y ejecuté los tests ahí, bajo el mutex `sim`.

## Veredicto: APROBAR CON CAMBIOS

El movimiento de la lógica de SRAM es literal y se comporta igual. No hay hallazgos bloqueantes ni de severidad alta. Los cambios pedidos solo refuerzan tests y documentan una invariante; ninguno corrige un fallo de producción.

## Hallazgos

| ID | Severidad | Archivo:línea | Problema | Evidencia | Corrección sugerida |
|---|---|---|---|---|---|
| M9L2-H1 | media (tests, regla 6) | `ios/PocketGBTests/SRAMPersistenceTests.swift` (sin test) · `ios/PocketGB/Saves/SRAMPersistence.swift:103-112` | Ningún test cubre el reintento tras una escritura **asíncrona** fallida (`retryIfFailed`: `lastQueued = nil` y `dirtyLast = clock()`), que es la corrección M4-H6 en la ruta sin pausa. `readOnlyFolderReturnsFalseAndTheNextFlushRetries` solo prueba el flush síncrono, que compara con `confirmed` y reescribe aunque `retryIfFailed` no haga nada. | Mutación en una copia de `2b03aeb`: cambié el cuerpo de `if failed { … }` por `_ = 0`. La suite completa sigue en verde: `✔ Test run with 152 tests in 15 suites passed`. | Añadir un test con reloj inyectado: `prime`; cambiar la SRAM; carpeta a 0o555; `check()` + avanzar 1 s + `check()` + `waitForPendingWrites()` (falla en la cola); restaurar permisos; `retryIfFailed()`; `check()`; avanzar 1 s; `check()`; `waitForPendingWrites()`. Esperado: `.sav` escrito **sin** flush síncrono. Con la mutación debe salir en rojo. |
| M9L2-H2 | baja (tests) | `ios/PocketGBTests/EmulatorSessionContractTests.swift:58-67` | `stopCallsShutdownExactlyOnce` usa `persisters: []`. Comprueba que `shutdown()` se llama una vez, pero no que se llama **después** del último flush, que es lo que exige el plan §1.2 (detach con la SRAM ya en disco). | En el test no hay persister, así que si `core.shutdown()` se moviera antes de `flushAll(sync: true)` en `run()` (`EmulatorSession.swift:490-491`), seguiría en verde. | Que `FakeCore` anote si se llama a `sramSave()` después de `shutdown()` y que el test use un persister con la SRAM cambiada tras `start()`. Esperado: `.sav` escrito y ninguna lectura de SRAM después de `shutdown`. |
| M9L2-H3 | baja (robustez, regla 6) | `ios/PocketGB/Emulator/EmulatorSession.swift:163-171` | Con 2 o más persisters, `start(restoring:)` decide la vigencia con `ramBytes()` del núcleo **compuesto**, no con la SRAM de cada lado. Hoy no se puede llegar ahí: `LinkedPair.stateSave()` y `sramSave()` lanzan `.linkUnsupported` antes de cargar nada ni de llamar a `flushAll`, y `loadState` también falla en `stateSave()` antes del flush. Pero la protección depende solo de esos `throw`. Un núcleo compuesto futuro que sí guardara estados podría escribir la SRAM de un estado no vigente en los dos `.sav`. Además, con `confirmed = nil` siempre pasaría por el flush, también cuando solo cambia el pie RTC (D81V2-H4). | Lectura de `LinkedPair.swift:63-70` y de `EmulatorSession.swift:147-177`/`361-375`. | Hacer explícita la invariante: al principio de `start(restoring:)` y de `loadState`, `guard persisters.count <= 1 else { throw CoreError.linkUnsupported }`, o como mínimo un comentario que diga que con varios persisters los estados no se admiten. |

## Comparación del movimiento

Origen: `cdb8b2f:ios/PocketGB/Emulator/EmulatorSession.swift`. Destino: `2b03aeb:ios/PocketGB/Saves/SRAMPersistence.swift` (SP) y `2b03aeb:…/EmulatorSession.swift` (ES).

| Función o fragmento original | Destino | ¿Idéntica? | Nota |
|---|---|---|---|
| Bloque SRAM de `init` (`:115-146`): `recoverOrphans` GB/GBA, `SaveOpening.prepare`, `sramLoad`, avisos `.localWrongSize`/`.unreadable`, `Refusal` reenviado | SP `open` `:58-82` | Sí | El mismo orden de llamadas y las mismas ramas de `catch`. El cálculo de `forcedWarning` de GBA (ES `:107-117`) sigue **antes** de `SaveOpening`, como antes. Desaparece la liberación manual de `audioScratch` porque ahora el rechazo ocurre antes de `self.init`. |
| `initialSRAM` → `confirmed`/`lastQueued` (D81-H7, `:134, 149-150`) | SP `init` `:45-52` | Sí | `try? core.sramSave()` justo después de `sramLoad`, en el mismo momento. Si no hay `target`, antes `confirmed` podía no ser nil, pero `flushSRAM` devolvía `true` sin escribir: el resultado observable es el mismo. |
| Prólogo de `run()` (`:434-447`): `lastQueued`, `confirmed`, `retryMirrorIfNeeded`, `lastCheck`, `-failAsyncSaves` | SP `prime` `:91-100`; ES `:414` `persisters.forEach { $0.prime() }` | Sí | `control` → `lock`; `mach_absolute_time()` → `clock()` (por defecto el mismo). Antes se condicionaba con `saves != nil`; ahora solo existen persisters con destino. |
| `saveFailed` en el bucle (`:471-475`, bajo `control`) | SP `retryIfFailed` `:103-112`; ES `:439` | Sí | Mismo orden: se resuelve antes de `if flushNow`. Ahora va bajo su propio `lock`, fuera de `control`. Sin cambio observable. |
| `checkSRAM` (`:563-575`) | SP `check` `:117-128`; ES `:471` | Sí | Debounce de 1,0 s y red de 60 s sin depender del flanco, iguales. `now - last` pasa a `now &- last`, que solo importa con un reloj falso no monótono (antes atraparía). |
| `flushSRAM(sync:)` (`:586-…`) | SP `flush` `:139-186` | Sí | Barrera `localSaveQueue.sync {}` y comparación con `confirmed` en síncrono; con `lastQueued` en asíncrono; `retryMirrorIfNeeded` si ya está en disco; `injectFailure` solo en asíncrono y en DEBUG; `confirmed` solo se actualiza si la escritura tuvo éxito; `saveFailed` si falla; devuelve `confirmedSRAM == data`. El closure se crea dentro de un método nonisolated (H0 de M5 respetado). |
| `whenMirrorIdle` (`:284-290`) | ES `:256-270` y SP `:190-192` | Sí con 0 y 1 persisters | Con 0, `callback()` síncrono; con 1, delega directamente; con N usa un `DispatchGroup` con `enter` antes de cada llamada y `notify` en `.global(.utility)`. Equilibrado aunque `whenMirrorIdle` llame al callback de forma síncrona. |
| `flushSRAM(sync: true)` en pausa, memoria baja y salida (`:453, 477, 527`) | ES `flushAll(sync: true)` `:420, 440, 490` | Sí con 1 | `flushAll` no cortocircuita: primero el flush, luego `&&`. Con 0 persisters devuelve `true`, como el antiguo `guard let saves else { return true }`. Verificado por mutación (ver Notas). |
| `start(restoring:)` (`:176-205`) | ES `:147-177` | Sí con 0 y 1 | `ramBytes()` con `dropLast(core.sramFooterBytes)` (INT-H1), rollback con `stateLoad(previous)` + `publish`, `notCurrent`, `stateConfig` → `notCurrent` (INT-H2), sin flush si la RAM coincide con lo confirmado (D81V2-H4), `setRTCTime`. Con 1 persister, `confirmedSRAM` es el valor de `init` (`prime` aún no ha corrido), igual que antes. Con 0, `flushAll` es un no-op que devuelve `true`, igual que antes. Con 2 no se puede llegar (ver H3). |
| `loadState` (`:381-395`) | ES `:361-375` | Sí | `flushSRAM` → `flushAll`. Con `LinkedPair` falla en `stateSave()` antes de tocar la SRAM. |
| Final de `run()` | ES `:490-495` | Añadido | `core.shutdown()` en el hilo de emulación, tras el último `flushAll` y antes de `finished = true`. El bucle ya ha salido, así que el núcleo no está corriendo. `withParkedCore` exige `!finished`, pero `finished` aún es `false` durante el `shutdown`. No pasa nada: el hilo principal está bloqueado en `stop()` esperando `finished` y no puede entrar. |

## Notas

**Tests ejecutados** sobre `git archive 2b03aeb` (no sobre el worktree), con `-derivedDataPath …/scratchpad/dd-m9-audit` y el mutex `sim`:
- `SRAMPersistenceTests`, `EmulatorSessionContractTests`, `LinkCableTests`, `StateSRAMTests`, `SaveMirrorTests` y `GBATests`: `✔ Test run with 76 tests in 6 suites passed after 9.458 seconds.`
- Suite completa `PocketGBTests`, en la ejecución de la mutación de H1: `152 tests in 15 suites passed`. Coincide con `git grep -c "@Test"`: 130 en `cdb8b2f`, 141 en `5684089` (+11 de `LinkCableTests`) y 152 en `2b03aeb` (+9 de `SRAMPersistenceTests` y +2 de `EmulatorSessionContractTests`).
- **No verifiqué las 95 capturas**: no ejecuté `tools/ios-screenshots.sh`. Es plausible: `screens.txt` tiene 95 líneas de pantalla y los lotes 1–2 no tocan la UI.

**Mutaciones** (en la copia del scratchpad, ya revertidas):
1. `ok = ok && persister.flush(sync:)`, con cortocircuito: `pauseFlushesEveryPersisterEvenIfOneFails` falla (`Expectation failed: try storeB.load() == dataB`). El test prueba código de producción y detecta el cortocircuito.
2. `retryIfFailed` neutralizado: nada falla (H1).

**Swift 6.** El proyecto no define `SWIFT_DEFAULT_ACTOR_ISOLATION` (tiene `SWIFT_VERSION = 6.0` y concurrencia estricta `complete`), así que `SRAMPersistence`, `LinkCable` y `LinkedPair` son nonisolated por defecto. `SRAMPersistence.write`, `whenMirrorIdle { group.leave() }` y el `clock` por defecto se crean en contextos nonisolated. En los tests, `{ clock.now }` es `@Sendable` y no hereda `@MainActor`. El nuevo `lock` es independiente de `control`: el closure de escritura ya no toma el `NSCondition` de la sesión, lo que reduce el acoplamiento.

**Hilos.** `flush(sync: true)` se llama desde el hilo principal en `start(restoring:)` (antes de `Thread.start`) y en `loadState` (con la sesión aparcada bajo `control`), como antes. Los estados `dirtyLast`, `lastQueued` y `lastCheck` mantienen la misma disciplina que en el original.

**LinkCable y LinkedPair:**
- `attach` llama primero a `detach()`. Si `gb_link_attach` falla, el núcleo puede haber instalado un lado parcial (`link.c:197-226`); `gb_link_detach` lo restaura y no se retiene nada.
- `deinit` llama a `gb_link_destroy` antes de que Swift suelte `cores`, así que no hay use-after-free de `gb_link_detach`.
- Tampoco hay riesgo en `LinkedPair.deinit`: `cable` sigue reteniendo los núcleos. Si `init` lanza, el cable se destruye sin `scratch` reservado.
- Los botones solo llegan al lado activo: el otro recibe `0` en cada frame. El audio del lado inactivo se drena en cada frame. `setRTCTime` se aplica a los dos lados.
- Las funciones `sram*` del par son inertes (0, `false` o `.linkUnsupported`). Es coherente con que cada `SRAMPersistence` reciba el `CoreBridge` de su lado (plan §1.2). `LinkSession` no se auditó: está en el lote 3.
- Los tests `cableRetainsItsCoresUntilDetached`, `destroyingTheCableReleasesItsCores` y `aCoreCannotBeInTwoCablesAndSelfAttachFails` usan referencias `weak` y comprueban la retención y la no retención.
- `audioAfterSwitchingSidesIsNotTheStaleBacklog` fallaría sin el drenaje: el lado 1 acumularía unos 8192 frames.

**Reglas duras:**
- Regla 1: en el diff solo hay ROMs sintéticos (`LinkTestROMs`, generados en código, basados en `core/tests/unit_link.c`, que es del propio proyecto). No hay `.gb`, `.sav` ni estados.
- Regla 4: `git diff --stat cdb8b2f 2b03aeb -- core gba` está vacío.
- Regla 5: no hay coincidencias de `URLSession`, `NWConnection`, `NSAppTransportSecurity` ni `import Network` en el diff.

**Menores, no son hallazgos:**
- `SRAMPersistenceTests.open` crea con `open` un persister que luego descarta (`_ = opened`) para rehacerlo con el reloj falso. Es aceptable, porque así también se prueba la fábrica.
- Los tests no borran sus carpetas temporales.
