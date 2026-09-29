# M4 · Evidencia (app iOS mínima)

Generada en el Mac de Joel (macOS 26.5.1, Xcode 26.6 / 17F113, Apple clang version 21.0.0 (clang-2100.1.1.101)) el 2026-09-29, rama `m4-ios-minima` desde `main` (`51d2076`, incluye el núcleo de M5).

## 0. El núcleo en macOS (primera ejecución en el Mac; hasta ahora solo en Linux)
`make -C core test HITO=M5`:
```
103/117 PASS · requeridos: 103/103 PASS · HITO=M5
OK: todos los casos requeridos en PASS
```
`make -C core asan HITO=M5`:
```
103/117 PASS · requeridos: 103/103 PASS · HITO=M5
OK: todos los casos requeridos en PASS
```
`make -C core check-globals`: `Sin estado global mutable: OK`

## 1. Build para iPhone (criterio 1)
`xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO clean build` (filtrado a errores, warnings y resultado):
```
2026-09-29 08:43:28.554 appintentsmetadataprocessor[65344:2198752] warning: Metadata extraction skipped. No AppIntents.framework dependency found.
** BUILD SUCCEEDED **
```
El único warning lo emite `appintentsmetadataprocessor` (herramienta de Xcode que avisa de que la app no usa AppIntents); no sale del código del proyecto.

Con firma (Personal Team `3TC3R6S79G`), el mismo comando sin `CODE_SIGNING_ALLOWED=NO` y con `-allowProvisioningUpdates`:
```
/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB.xcodeproj: error: Communication with Apple failed: Your team has no devices from which to generate a provisioning profile. Connect a device to use or manually add device IDs in Certificates, Identif
/Users/joelbermudez/Documents/workspace/pocketgb/ios/PocketGB.xcodeproj: error: No profiles for 'com.joelbermudez.pocketgb' were found: Xcode couldn't find any iOS App Development provisioning profiles matching 'com.joelbermudez.pocketgb'. (in target 'PocketGB
** BUILD FAILED **
```
Esperado: una cuenta gratuita solo genera perfiles para dispositivos registrados. Se resuelve cuando Joel conecta el iPhone y pulsa Run una vez ([07](../07-instalacion-iphone.md)).

## 2. Sin red (criterio 2)
`grep -rnE 'URLSession|NWConnection|Network\b|http' ios/PocketGB`:
```
exit=1
```
(exit 1 = sin coincidencias. Se quitó el DOCTYPE del Info.plist, cuya URL `http://www.apple.com/DTDs/...` daba un falso positivo; `plutil -lint` → OK.)

## 3. Simulador (adelanto del criterio 3)
iPhone 15 Pro, iOS 17.5 (el deployment target), build Debug, arrancado con el argumento solo-DEBUG `-rom <ruta>`:
```
xcrun simctl launch <UDID> com.joelbermudez.pocketgb -rom <copia de dmg-acid2.gb>
```
Captura: [M4-simulador-acid2.png](M4-simulador-acid2.png). La cara de dmg-acid2 coincide con la referencia (`dmg-acid2-dmg.png`, idéntica píxel a píxel en el runner desde M2); la imagen queda bajo la Dynamic Island y los controles debajo, sobre fondo sólido.

Nota: en el Mac, el simulador se queda bloqueado en `getxattr` si el ROM está en `~/Documents` (permiso TCC de macOS, no es un fallo de la app); por eso se usa una copia en un directorio temporal.

## 4. Partida: H1 de Codex (SRAM escrita sin flanco "el juego guardó")
Simulador, ROM `blargg/dmg_sound/rom_singles/01-registers.gb` (MBC1+RAM+BATTERY; escribe su resultado en la SRAM). Background = abrir Ajustes con `xcrun simctl launch <UDID> com.apple.Preferences`.
1. Sin `.sav` previo, tras 4 s jugando: `Saves/` vacío (el juego no deshabilitó la RAM: sin flanco dirty, sin guardado por debounce). Tras pasar a background: `Saves/c6b9fa4b9d9d26919b33ebe78a6ef19a.sav` (8192 bytes) con `...a01-registers\n\n\nPassed`. Antes de la corrección, este camino no escribía nada.
2. Reabrir con el mismo contenido → background: no se crea `backups/` (sin cambios, sin rotación).
3. Alterar el byte 19 del `.sav` (`P`→`X`), reabrir (el test lo reescribe) → background: `.sav` actual con `Passed` y `backups/c6b9….1.sav` con `Xassed` (el anterior, intacto).

## 5. Vuelta 2 de Codex: H1 (red de 60 s sin flanco) y H6 (fallo asíncrono antes de pausar)
Mismo ROM y simulador, build Debug. Guion: instalar, borrar `Saves/`, T1 = abrir y esperar sin tocar nada; T2 = abrir con el argumento solo-DEBUG `-failAsyncSaves` (toda escritura asíncrona falla; las síncronas no), esperar 65 s y pasar a background. Salida real:
```
== T1: red de seguridad de 60 s (sin background)
t=30s:
t=65s:
c6b9fa4b9d9d26919b33ebe78a6ef19a.sav
00000010: 0a0a 0a50 6173 7365 640a 0000 0000 0000  ...Passed.......
== T2: escritura de los 60 s falla (-failAsyncSaves: toda escritura asíncrona falla) y luego background
t=65s (red de 60 s + reintentos, todos fallidos):
tras background:
c6b9fa4b9d9d26919b33ebe78a6ef19a.sav
00000010: 0a0a 0a50 6173 7365 640a 0000 0000 0000  ...Passed.......
2026-09-29 08:54:00.794 E  PocketGB[70686:21f24b] [com.joelbermudez.pocketgb:session] No se pudo guardar la partida: El archivo no ha podido guardarse.
2026-09-29 08:54:01.810 E  PocketGB[70686:21f412] [com.joelbermudez.pocketgb:session] No se pudo guardar la partida: El archivo no ha podido guardarse.
2026-09-29 08:54:02.842 E  PocketGB[70686:21f24b] [com.joelbermudez.pocketgb:session] No se pudo guardar la partida: El archivo no ha podido guardarse.
2026-09-29 08:54:03.855 E  PocketGB[70686:21f412] [com.joelbermudez.pocketgb:session] No se pudo guardar la partida: El archivo no ha podido guardarse.
2026-09-29 08:54:04.876 E  PocketGB[70686:21f412] [com.joelbermudez.pocketgb:session] No se pudo guardar la partida: El archivo no ha podido guardarse.
```
- T1: a los 30 s no hay `.sav` (sin flanco, sin debounce); a los 65 s la red de seguridad lo escribió sin pasar a background.
- T2: a los 60 s falla la escritura asíncrona y se reintenta cada ~1 s (todas fallan, sin `.sav`). Al pasar a background, el flush síncrono vacía la cola, compara con lo **confirmado en disco** (no con lo encolado) y escribe: `.sav` correcto.

## 6. Pendiente de Joel (criterio 3, en el iPhone)
- dmg-acid2 correcto en el iPhone.
- Pokémon Rojo llega al menú y responde a los botones.
- Girar a horizontal: escala entera y controles translúcidos superpuestos (no probado en el simulador).
