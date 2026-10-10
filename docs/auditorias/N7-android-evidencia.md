# N7 Android · Evidencia (partidas que viajan: linaje, `.pgbm` y enviar a otro dispositivo)

Generada en el Mac de Joel (Darwin 25.5.0) del 2026-10-08 al 2026-10-09, rama `n7-android-viajan` desde `siguiente-nivel` (`d83e212`). Plan: [N-README](../hitos/N-README.md) §3.4 y §N7. Formato: [12-formato-pgbm](../12-formato-pgbm.md). Guía: [viajar-android](../guia/viajar-android.md). No se tocaron `docs/ESTADO.md`, la tabla de hitos ni `ios/`.

## Commits
| Commit | Qué |
|---|---|
| `538e625` | **Vectores dorados cruzados X1…X7** de las apps: generador C determinista `core/tests/unit_pgbm_cross.c` (suite `pgbmx`) y SHA-256 en `docs/12-formato-pgbm.md` §Vectores cruzados (contrastados con la referencia en Python) |
| `c4eb5dd` | **N7a** linaje (§3.4) + PRUEBAS-JOEL A6, A8, A9 e I1 hechas por Joel (2026-10-08) |
| `29b3fff` | **N7b** exportar/importar `.sav` y `.pgbm` por JNI al parser C |
| `ccd71be` | **N7c** enviar a otro dispositivo, continuar donde lo dejaste, estado de la partida |
| `48dcd5e` | lint: sin avisos nuevos |
| `61de4b5` | N7a: el cambio externo también aparta la local (lo detectó `MoveRomEndToEndTest` en el emulador) |
| `c835db8` | capturas del estado desplazadas; filas de copias en conflicto sin alto mínimo |

## N7a · linaje
- `saves/SaveLineage.kt`: la tabla de §3.4 pura, **solo por huellas** (nunca fechas, salvo la fila «sin historial»): `SAME`, `OWN_OLDER`, `EXTERNAL_CHANGE`, `DIVERGENCE`, `NO_HISTORY`, `MIRROR_MISSING`; y la tabla de un paquete entrante (`INSTALL`, `ALREADY_CURRENT`, `ADVANCE` si `base_sav_sha256` = local, `STALE` si ya la tuvimos, `DIVERGENCE` en otro caso). Detector de copias en conflicto del proveedor (`X 2.sav`, `X (1).sav`, `X.sync-conflict-…`, `X (… conflicted copy …).sav`).
- `SaveStore`: el historial existente (`<huella>.mirror-history.json`, últimos 8) gana la lista `received` (partidas instaladas de fuera = base del linaje para exportar); `<huella>.provider-conflicts.json` (se rellena al leer el espejo SAF; Ajustes › Partidas las lista; nunca se borran); `<huella>.origin.json` (N7c).
- `SaveOpening.prepare`: cambio externo → se instala, la anterior queda en `.1` **y apartada** (N1), aviso `ExternalChange`; divergencia → sigue la local, la otra se aparta, va a backup y a un momento «Conflicto · fecha», aviso `Divergence`; escritura propia anterior → gana la local y se reescribe el espejo.
- **Cambio de comportamiento:** un contenido que PocketGB ya escribió, restaurado con fecha nueva, **ya no gana por fecha** (fila «escritura nuestra anterior»). Se actualizó `SaveOpeningTest` (`restoredHistoricalMirrorWithNewDateLosesToLocalAndIsRewritten`) y el de «externo más nuevo con la local cambiada» pasa a divergencia.
- Tests JVM `SaveLineageTest` (16): tabla pura; cada fila con E/S real; **reloj desfasado** (la nube «del futuro» reaplica una versión vieja → gana la local; el otro equipo con el reloj atrasado → el cambio externo se instala igual); **latencia** (paquete con una base que aún no llegó → divergencia, nunca se pisa; espejo atrasado tras una importación → gana la local y luego «igual»); **copias del proveedor** (6 formas sí, 5 no; listado sin tocar la partida); historial acotado y formato antiguo.

## N7b · `.pgbm` y `.sav`
- JNI `nativePgbmParse` / `nativePgbmEncode` (`pocketgb_jni.c`; `core/src/pgbm.c` en la `.so`): el paquete se copia a un bloque de tamaño exacto con tope de 4 MiB antes de `pgbm_parse`; los códigos se mapean por número (`travel/Pgbm.kt`).
- `travel/PgbmMeta.kt`: META v1 estricta (tipos, enteros 0…2^53−1 sin fracción, hex 64 con mayúsculas aceptadas, límites, BOM rechazado, claves desconocidas ignoradas, `format` > 1 = «actualiza la app»); escritura en orden fijo que **reproduce byte a byte** los META de X1…X7.
- `travel/SaveImporter.kt`: todo antes de tocar nada (mágico, `pgbm_parse`, META, `rom_sha256` = ROMF, ROMF = huella de 32 bytes, `core.name` = consola, `sav_sha256` = SHA-256 de SAVE, STAT ⇒ `state_of_sav_sha256`); exclusión por huella (`FingerprintOwnership.withExclusive`); SAVE vacía o de otro tamaño en un juego con batería → no toca el `.sav` y añade backup de la actual; divergencia → `NeedsChoice` sin tocar nada, y con la elección la otra va a momento «Conflicto · equipo» + apartada (+ backup); STAT se instala como AUTO solo si `state_of` = `sav` (el AUTO anterior se aparta; lo valida el núcleo al continuar). `.sav` crudo: tamaño exacto de los válidos del cartucho; sin base, así que con otra partida se pregunta.
- `travel/SaveExporter.kt`: `.sav` crudo; `.pgbm` con AUTO solo si está vigente (mismo criterio que «Continuar»), base del linaje, alias, etiquetas, hitos y tiempo jugado. Determinista.
- UI: menú ⋮ del detalle (Enviar, Compartir `ACTION_SEND` por `FileProvider` de AndroidX solo sobre `cache/exports/`, Guardar en… `ACTION_CREATE_DOCUMENT`, Exportar `.sav`, Importar `OpenDocument`). Intents `VIEW`/`SEND` genéricos (`application/octet-stream`, solo `content://`) validados por **cabecera**; el juego se elige por ROMF.
- Tests JVM `SaveTravelTest` (16) con un códec de referencia en Kotlin (`src/sharedTest`, compartido con los instrumentados): SHA de X1…X7, G2, G4; X1 instala y ofrece continuar; X2 avance; X2 sobre otra local = divergencia sin tocar nada hasta elegir (árbol de archivos idéntico); X3/X4 no tocan el `.sav` y dejan backup; X5, X6, X7, G4, G2 rechazados con el árbol idéntico; truncado/CRC/mágico; otro juego/consola; exclusión por huella; `.sav` crudo; validación por cabecera; ida y vuelta exportar → importar; AUTO viejo no se exporta; base del linaje; esquema META. Instrumentado `PgbmNativeTest` (3): **el C por JNI produce los mismos bytes** que la referencia y los SHA documentados; códigos `CRITICAL`, `TRUNCATED`, `CRC`, `MAGIC`; importador real con el parser C. `ManifestPolicyTest`: proveedor privado limitado a `cache/exports/`, intents solo `content://`, sin red.

## N7c · enviar y continuar
- `travel/ExchangeFolder.kt`: crea `PocketGB/Intercambio/` en la carpeta concedida (SAF) y escribe `<juego> · <equipo> · <fecha>.pgbm`, verificado leyéndolo. `ExchangeInbox` (`files/exchange-seen.json`): tras cada escaneo se ofrece el primer paquete nuevo de un juego de la biblioteca (no los enviados desde aquí); «Ahora no» lo da por visto. Nada se borra de la carpeta.
- «Continuar donde lo dejaste en <equipo>» tras importar un paquete con estado exacto (abre en `LaunchMode.RESUME`; `ExactContinuation` lo valida).
- Detalle: la casilla **Partida** = «iPhone de Joel · hace 2 h» si la partida actual es la recibida, «Este teléfono · …» si se jugó aquí (`travel/SaveStatus.kt`).
- Tests JVM `SaveTravelN7cTest` (4) e instrumentado `TravelUiTest` (3: las cinco entradas del menú llaman a su acción, el estado se ve, botón «Continuar donde lo dejaste», elección en divergencia).

## Verificación desde limpio
`git archive HEAD` a una carpeta temporal (con `core/tests/roms` y las ROMs libres de GBA copiadas; `ANDROID_HOME=~/Library/Android/sdk`, Gradle `--no-daemon --max-workers=1`).

```
# core (árbol de ccd71be; core no cambia después)
$ make -C core test      → unit tests PASS: 11343 comprobaciones, 0 fallos · requeridos 65/65 PASS (suites pgbm y pgbmx ok)
$ make -C core asan      → unit tests PASS: 11343 comprobaciones, 0 fallos · requeridos 65/65 PASS
$ make -C core fuzz-pgbm-smoke FUZZ_SECONDS=600
fuzz_pgbm_smoke: 73874826 ejecuciones en 600 s, sin fallos
# libFuzzer no está disponible: Apple clang no lo trae y no hay `brew llvm` instalado en este Mac.
# Se usó el humo con ASan/UBSan (mismo fuzz_pgbm.c, mutación aleatoria sin cobertura guiada).

# Android (árbol de c835db8)
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest → BUILD SUCCESSFUL
JVM: 790 tests, 0 fallos
lint: 23 avisos, 0 errores; ninguno en código de N7 (los 3 nuevos de la primera pasada se corrigieron en 48dcd5e)
aapt2 dump permissions app-debug.apk / app-release-unsigned.apk → 0 permisos de red (sin INTERNET ni ACCESS_NETWORK_STATE)
FileProvider en el APK de Release: exported=false, grantUriPermissions=true, authority com.joelbermudez.pocketgb.exports

# Emulador Small_Phone_API_35 (con with-lock.sh emu)
am instrument (suite completa) → OK (485 tests)
tools/android-save-kill-test.sh 50 gb → OK: 50/50 iteraciones con el invariante intacto
tools/android-screenshots.sh (ids n7-*) → 12 capturas
```

Primera pasada (árbol `48dcd5e`): 485 instrumentadas con 2 fallos: `MoveRomEndToEndTest.aDuplicateWithItsOwnNewerSave…` (**regresión real de N7a**: el historial es por huella, así que un duplicado con su propio `.sav` caía en «cambio externo» sin apartar la local; corregido en `61de4b5` con test JVM) y `GbaNativeTest.millisecondsPerFrameOnThisDevice` (entorno: el árbol limpio no tenía las ROMs libres de GBA; con `tools/fetch-gba-test-roms.sh --solo-jsmolka` pasa). La segunda pasada es la de arriba.

## Capturas revisadas
`n7-details-menu` (claro y oscuro), `n7-details-status`, `n7-details-status-here`, `n7-imported-continue`, `n7-choose` (claro y oscuro), `n7-inbox`, `n7-warning-divergence`, `n7-warning-external`, `n7-saves-conflicts` (claro y oscuro). Revisadas a mano: textos completos, sin cortes ni solapes; botones de 48 dp. Tras la primera revisión se corrigieron dos cosas (`c835db8`): la captura del estado no llegaba a la casilla «Partida» y las filas de copias en conflicto tenían un alto mínimo que dejaba hueco.

## Decisiones (para igualar en iOS)
1. **Vectores cruzados X1…X7** (y G2/G4): cartucho de referencia GB con batería de 32 768 B; resultados esperados en [12-formato-pgbm](../12-formato-pgbm.md). META en el orden de claves de la tabla (`format`, `rom_sha256`, `sav_sha256`, `base_sav_sha256`, `device`, `created_ms`, `core`, `config`, `state_of_sav_sha256`, `play_time_ms`, `title`, `alias`, `tags`, `milestones`, `moment`), sin espacios.
2. **Linaje solo por huellas**; la fecha solo decide la primera vez (sin historial). Una escritura propia antigua con fecha nueva pierde.
3. **Cambio externo:** se instala, la local va a `.1` y además se aparta (fuera de la rotación) con aviso.
4. **Divergencia al abrir:** sigue la local (no se bloquea la apertura); la otra va a backup, apartada y a un momento «Conflicto · fecha»; el aviso explica cómo recuperarla. **Divergencia al importar:** diálogo «¿Con qué partida sigues?» (Usar la importada / Seguir con esta); la no elegida va a momento «Conflicto · equipo» + apartada.
5. **Paquete con base que aún no llegó** (latencia) = divergencia (se pregunta). Un `.sav` crudo nunca trae base: con otra partida, se pregunta.
6. **Importar `STALE`** (ya la tuvimos): no se instala; va a backup.
7. **STAT del paquete** solo como estado automático si `state_of_sav_sha256` = `sav_sha256`; el AUTO anterior se aparta, no se borra. No se exige firma `PGBS`/`PGBA` al importar: lo valida el núcleo al continuar.
8. **SAVE de tamaño incorrecto:** no se toca el `.sav` y se añade un backup de la actual (lo que entendemos por «deja backup»).
9. **Base del linaje (`base_sav_sha256`)** = última partida recibida de fuera (cambio externo, importación o primera instalación desde el espejo); `null` si nunca recibió ninguna.
10. **Estado en el detalle:** origen anotado al importar (huella + equipo + `created_ms`); vale mientras la partida actual tenga esa huella.
11. **Juego nunca abierto:** se rechaza la importación («Abre el juego una vez»), porque no se conocen sus tamaños válidos.
12. Bandeja: el nombre del paquete es `<juego> · <equipo> · aaaammdd-hhmmss.pgbm`; se ofrecen solo `.pgbm` no enviados desde el propio teléfono.

## Pendiente / fuera de lo verificado
- Metadatos del paquete (alias, etiquetas, hitos, tiempo) **viajan** en META pero el importador de Android aún **no los aplica** a la biblioteca (ND12 dice que viajan; aplicarlos sería una decisión de producto: sobrescribir o fusionar).
- La bandeja SAF se probó con tests JVM de la lógica y las capturas; no hay un instrumentado contra un proveedor SAF real que cree `PocketGB/Intercambio/` (sí lo hay del espejo). Lo cubre la prueba de Joel con Drive.
- Claves de META repetidas: kotlinx se queda con la última (el esquema dice que no se repiten; no se rechaza explícitamente).
- Prueba de Joel: Rojo iPhone → Android → iPhone con continuación exacta.
