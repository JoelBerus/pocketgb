# 07 · Instalar PocketGB en tu iPhone (Apple ID gratuito)

Solo se hace en un Mac con Xcode. Claude en la nube **no** puede compilar ni instalar la app iOS.

## Una sola vez
1. **Xcode → Settings → Accounts →** `+` → Apple ID. Aparece "Personal Team".
2. Conecta el iPhone por cable y confía en el Mac.
3. **iPhone:** Ajustes → Privacidad y seguridad → **Modo Desarrollador** → activar → reiniciar → confirmar. La opción solo aparece tras conectar el iPhone a Xcode una vez.
4. En Xcode, abre `ios/PocketGB.xcodeproj` → target PocketGB → *Signing & Capabilities* → Team = Personal Team. Si el bundle ID choca, cámbialo (por ejemplo, `com.joelbermudez.pocketgb2`).
5. Destino = tu iPhone → **Run** (⌘R).
6. La primera vez, el iPhone muestra "Desarrollador no confiable": Ajustes → General → VPN y gestión de dispositivos → tu Apple ID → **Confiar**.
7. Opcional: Xcode → Window → Devices and Simulators → marca **Connect via network**. Las siguientes instalaciones funcionan por Wi-Fi.

## Cada 7 días (límite de la cuenta gratuita)
El certificado caduca a los 7 días y la app deja de abrir. **Tus partidas no se pierden**: el contenedor de la app sigue ahí y además hay espejo en iCloud.
1. Conecta el iPhone (o misma Wi-Fi si activaste el paso 7).
2. Abre el proyecto → **Run**. Xcode vuelve a firmar e instala encima, conservando los datos.

Desde la terminal (tras el primer Run):
```bash
xcodebuild -project ios/PocketGB.xcodeproj -scheme PocketGB -destination 'platform=iOS,name=iPhone de Joel' -allowProvisioningUpdates build
```
Y después instala con Xcode, o con `xcrun devicectl device install app --device <UDID> <ruta .app>`.

## Límites de la cuenta gratuita
- Máximo **3 apps** instaladas a la vez con firma gratuita.
- Máximo **10 App IDs nuevos cada 7 días**: no cambies el bundle ID cada vez.
- Sin iCloud capability, push ni App Groups. PocketGB está diseñada para no necesitarlos.
- **No borres la app** para "reinstalar limpio". Borrarla elimina su contenedor (quedaría solo el espejo en iCloud).

## Si pagas Apple Developer ($99/año) en el futuro
Cambia el Team. La firma dura 1 año y se puede usar TestFlight. No hay que cambiar nada del código.
