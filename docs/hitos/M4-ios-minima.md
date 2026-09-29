# M4 · App iOS mínima (ver el juego en el iPhone)

🍎 Solo Mac. Spec: [04](../04-ios-spec.md) §Proyecto Xcode, §Pantalla de juego, [07](../07-instalacion-iphone.md).

**Proyecto Xcode:** lo creó Claude en M4 (`ios/PocketGB.xcodeproj`). Paso manual de Joel: conectar el iPhone y pulsar Run una vez para que Xcode genere el perfil de firma ([07](../07-instalacion-iphone.md)).

**Archivos:** `ios/PocketGB/App/*`, `Emulator/{EmulatorSession,CoreBridge,FrameBuffers}.swift`, `Video/*`, `Input/ControlsOverlayView.swift` (versión básica: botones opacos sin deslizar), `core/include/module.modulemap`.

**Tareas**
1. `CoreBridge`: dueño del puntero `gb*`, copia el ROM y convierte los errores a `enum` Swift.
2. Hilo de emulación con pacing por reloj (`mach_absolute_time`, 59,7275 Hz) mientras no hay audio (el audio llega en M5).
3. `MTKView` + textura nearest y escalado entero.
4. Abrir un ROM con `UIDocumentPicker` (un solo archivo; la biblioteca llega en M6).
5. SRAM: carga y guardado **básicos** (local, atómico) desde ya, para no perder partidas mientras se prueba.

**Criterios de aceptación**
- [ ] `xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -destination 'generic/platform=iOS' build` → BUILD SUCCEEDED, sin warnings nuevos.
- [ ] `grep -rnE 'URLSession|NWConnection|Network\b|http' ios/PocketGB` → vacío.
- [ ] En el iPhone (Joel confirma): se ve dmg-acid2 correcto y Pokémon Rojo llega al menú y responde a los botones.
