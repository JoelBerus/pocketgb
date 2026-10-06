# M9 🍎 · Evidencia de la UI del cable link virtual

Rama `m9-link-ui`, 2026-10-06. Plan: [hitos/M9-ios-plan.md](../hitos/M9-ios-plan.md). Los lotes 1–2 (cable en Swift, `SRAMPersistence`) tienen su propia auditoría ([M9-L2-opus](M9-L2-opus.md)); aquí van los lotes 3–5 y la respuesta a esa auditoría.

## 1. Tests unitarios (suite completa de la app)
```
$ tools/ios-screenshots.sh build/shots-m9      # incluye tests unitarios, UI, catálogo y Release
✔ Test run with 169 tests in 17 suites passed after 10.514 seconds.
** BUILD SUCCEEDED **                          (Release, generic/platform=iOS Simulator)
xcodebuild Release: exit 0
xcodebuild test: exit 0
```
Antes de los lotes 3–5 había 152. Nuevos: `LinkSessionTests` (10), `LinkPartnersTests` (4) y 3 de la respuesta a la auditoría (`retryAfterAFailedAsyncWriteRewritesWithoutASyncFlush`, `shutdownRunsAfterTheFinalFlush`, `statesAreRefusedWithSeveralPersisters`).

`LinkSessionTests`: el intercambio llega por la ruta normal a los dos `.sav` (16 bytes `i ^ key` y `$A100 == 1`), la pausa guarda las dos SRAM, mismo juego con batería → `.sameGame` con la carpeta vacía, sin batería se abre, `.sav` de tamaño incorrecto → `.cannotSave` sin cambiar un byte ni el listado, iCloud sin descargar sin copia local → `.saveNotDownloaded`, con copia local → aviso con el título, `.gba` → `.notGameBoy`, `saveState()` lanza `.linkUnsupported`, `switchSide`.

## 2. Tests de UI
`ShellLinkTests.testSwitchAndExitTheCable` (abre `dmg-acid2.gb` + `cgb-acid2.gbc`): `hud-link-switch` ≥ 44×44 (tolerancia de 0,01 pt por el redondeo de coma flotante: mide 43,99999), etiqueta «Cambiar a CGB-ACID2»; al tocarlo, «Cambiar a DMG-ACID2»; la pausa tiene `pause-link-exit` y `pause-link-switch` y no «Estados guardados»; salir vuelve a las tabs. `passed (13.201 seconds)`. En la ejecución completa del script: ShellAccessibilityTests y el resto sin fallos (`xcodebuild test: exit 0`).

## 3. Catálogo de capturas
`tools/ios-screenshots.sh`: **106 PNG** (95 anteriores + 11 nuevas), `ScreenshotTests` sin fallos. Las 11 nuevas, todas revisadas a ojo:

| Captura | Lo que se ve |
|---|---|
| `gameplay-link-portrait-portrait-dark` | dmg-acid2 (gris) a pantalla; HUD con tres botones (pausa, avance rápido, ⇄); miniatura de cgb-acid2 (amarillo) arriba a la derecha de la zona de controles, con borde fino, sin tapar el D-pad ni la imagen |
| `gameplay-link-landscape-landscape-dark` | miniatura dentro del área segura (separada del borde del recorte), sin solaparse con los controles; HUD de tres botones |
| `gameplay-link-switched-portrait-dark` | toast «Ahora juegas con CGB-ACID2»; el juego principal es cgb-acid2 y la miniatura dmg-acid2 (cambio completo). El cambio se hace a los 2 s (no a los 0,5 s del plan: con 0,5 s el toast, que dura 2,5 s, ya no estaba en la captura) |
| `gameplay-link-pause-portrait-dark` | sheet con el título «DMG-ACID2», «Continuar», «Cambiar a CGB-ACID2 y continuar», «Personalizar controles», pie sobre los estados, «Salir del cable» en rojo con «Al salir se guardan las dos partidas.»; sin «Estados guardados» |
| `gameplay-link-reduce-transparency-landscape-dark` | controles y botones del HUD sólidos; miniatura legible |
| `link-partner-picker-portrait-light` / `-dark` | sheet «Conectar “DMG-ACID2” con…» con CGB-ACID2 (jugado, favorito), RTC3TEST y el homebrew largo (truncado); sin DMG-ACID2 ni el `.gba`; pie explicativo. La banda azul tras la fila central es la refracción del botón «Jugar» de detrás de la sheet |
| `link-partner-picker-ax5-portrait-light` | texto accesible 5 en la sheet (una fila a tamaño enorme, el título se trunca); en DEBUG la sheet aplica el tipo forzado porque no lo hereda |
| `link-open-refused-portrait-light` / `-dark` | alerta «Elige otro juego» con el motivo y OK |
| `link-continue-warning-portrait-light` | alerta «¿Conectar sin continuar?» con «Conectar igualmente» y «Cancelar» sobre el detalle, que ya muestra «Conectar con otro juego…» |

También revisadas: `game-context-menu-portrait-light` (nueva opción «Conectar con…», icono de cable) y el detalle (botón nuevo bajo «Jugar desde el inicio»). No se ve ningún defecto que corregir.

## 4. Otras comprobaciones
```
$ make -C core test 2>&1 | tail -4
65/68 PASS · requeridos: 65/65 PASS · HITO=M1
OK: todos los casos requeridos en PASS
$ git diff --check          (sin salida)
$ rg -n 'URLSession|NWConnection|NSAppTransportSecurity' ios     (sin salida, exit 1)
```
`core/` y `gba/` no se tocan en esta rama desde los lotes 1–2.

## 5. Auditoría del lote 2 ([M9-L2-opus](M9-L2-opus.md): APROBAR CON CAMBIOS)
| ID | Estado | Qué se hizo |
|---|---|---|
| M9L2-H1 (media) | corregido | Test `retryAfterAFailedAsyncWriteRewritesWithoutASyncFlush` (carpeta a 0o555, `check`, reloj +1,1 s, `check`, `waitForPendingWrites`, permisos, `retryIfFailed`, +1,1 s, `check`: el `.sav` queda escrito sin flush síncrono). **Mutación:** con el cuerpo de `retryIfFailed` neutralizado el test sale en rojo: `Expectation failed: try o.store.load() == o.core.sramSave()` (`Test run with 10 tests in 1 suite failed`); revertida. |
| M9L2-H2 (baja) | corregido | `FakeCore` cuenta las lecturas de SRAM posteriores a `shutdown()`; `shutdownRunsAfterTheFinalFlush` cambia la SRAM tras `start()`, llama a `stop()` y exige el `.sav` escrito, `shutdownCount == 1` y 0 lecturas tras el `shutdown`. |
| M9L2-H3 (baja) | corregido | `guard persisters.count <= 1 else { throw CoreError.linkUnsupported }` en `start(restoring:)` (con estado) y en `loadState`, con el comentario de la invariante; test `statesAreRefusedWithSeveralPersisters`. |

Suite completa tras los tres cambios: `169 tests in 17 suites passed`.

## 6. Desviaciones del plan
- La alerta de continuación (`linkContinueRequest`) se lanza **antes** de abrir (en `openLink`, con `canResume` de los dos), no en `startLink`: así no se leen ROMs ni se crea nada si el usuario cancela. «Conectar igualmente» vuelve a `openLink(…, confirmedContinuation: true)`.
- `LinkPartners.candidates` recibe `LibraryPreferencesData` (valor puro) en lugar de `LibraryPreferences`.
- `gameplay-link-switched`: cambio a los 2 s, no a los 0,5 s (ver §3).
- `ShellLinkTests` va en `ShellTests.swift` (junto a las demás clases `Shell*`) y no en un archivo aparte.
- `DebugDynamicType` pasa de `private` a interno para aplicarlo a la sheet del selector (solo DEBUG).
- Un `.sav` compartido (`mirrorShared`) se avisa tras abrir, junto con los demás avisos.

## 7. Pendiente (no verificado; 🍎 de Joel, plan §5.2)
- [ ] HUD DEBUG con los dos núcleos durante 10 min: `audio ·0`, `U 0`, `emu` < 10 ms.
- [ ] Al alternar, el audio sigue al juego visible, sin audio viejo ni clics; controles y mando solo actúan sobre el visible.
- [ ] Rojo ↔ Amarillo: Cable Club, guardar, intercambiar un Kadabra y comprobar que evoluciona; al salir, los dos `.sav` locales y espejos con fecha nueva.
- [ ] Segundo plano y cierre forzado durante el cable sin perder partida.
- [ ] Miniatura y HUD en las dos rotaciones horizontales con notch o Dynamic Island.
- [ ] Rechazo con dos copias del mismo ROM; aviso de continuación con progreso sin guardar.
- Falta la auditoría independiente de los lotes 3–5 (`LinkSession`, rechazos, `AppState`).

## 8. Auditoría final ([M9-ios-opus](M9-ios-opus.md), respuesta en [M9-ios-respuesta](M9-ios-respuesta.md))
Veredicto APROBAR CON CAMBIOS (tres bajos). Corregidos en `0b9f570`, `46d0f64` y `b19d0cb`.

**M9-H1, mutación** (guardas `persisters.count <= 1` sustituidas por `_ = 0`):
```
✘ Test statesAreRefusedByTheSessionGuardEvenIfTheCoreCouldSaveThem() recorded an issue at EmulatorSessionContractTests.swift:116:9: Expectation failed: an error was expected but none was thrown
✘ ... :122:9: Expectation failed: an error was expected but none was thrown
✘ ... :123:9: Expectation failed: try storeA.load() == savedA && storeB.load() == savedB
✘ Test run with 5 tests in 1 suite failed after 0.077 seconds with 3 issues.
** TEST FAILED **
```
**Restaurado:**
```
✔ Test statesAreRefusedByTheSessionGuardEvenIfTheCoreCouldSaveThem() passed after 0.116 seconds.
✔ Test run with 5 tests in 1 suite passed after 0.121 seconds.
** TEST SUCCEEDED **
```
**`LinkSessionTests` (M9-H2 y `.sameGame` con espejo):** `✔ Test run with 12 tests in 1 suite passed after 0.949 seconds.`
