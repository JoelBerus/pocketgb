# ios/

`PocketGB.xcodeproj` (Xcode 16+, carpetas sincronizadas: todo `.swift` que se añada en `PocketGB/` entra solo al target, sin tocar `project.pbxproj`) y las fuentes Swift en `PocketGB/`. El núcleo se compila desde `../core/src` (también carpeta sincronizada) y se importa con `import PocketGBCore` (`core/include/module.modulemap`).

- Compilar sin firma: `xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`
- Probar en el simulador (solo builds Debug): `xcrun simctl launch <UDID> com.joelbermudez.pocketgb -rom <ruta.gb>` abre ese ROM al arrancar. En el Mac, copia el ROM fuera de `~/Documents` (TCC bloquea al simulador).
- Instalar en el iPhone: [docs/07-instalacion-iphone.md](../docs/07-instalacion-iphone.md).

## Argumentos DEBUG (solo builds Debug; docs/hitos/D-README.md §2.5)
- `-screen <id>`: abre una pantalla del catálogo (`DebugScreen` en `App/DebugScreenRouter.swift`, IDs de docs/diseno/SPEC.md §9). Un id desconocido muestra "Pantalla desconocida" y `ScreenshotTests` falla.
- `-demoFolderState none` y `-demoLibrary empty`: biblioteca de demostración en memoria (D1). Los demás valores llegan con D2–D3.
- `-uiStyle light|dark`, `-rom <ruta>`, `-paused`, `-memoryWarningAfter <s>`, `-debugHUD`: como antes.
- El CI compila también en Release: si algo usa el router fuera de `#if DEBUG`, falla.
