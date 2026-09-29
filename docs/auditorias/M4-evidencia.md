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
2026-09-29 08:35:15.635 appintentsmetadataprocessor[61483:2175478] warning: Metadata extraction skipped. No AppIntents.framework dependency found.
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

## 4. Pendiente de Joel (criterio 3, en el iPhone)
- dmg-acid2 correcto en el iPhone.
- Pokémon Rojo llega al menú y responde a los botones.
- Girar a horizontal: escala entera y controles translúcidos superpuestos (no probado en el simulador).
