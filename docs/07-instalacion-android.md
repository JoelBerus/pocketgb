# 07 · Instalar PocketGB en tu teléfono Android

Funciona en macOS, Linux o Windows con Android Studio. No hay cuenta ni caducidad: Android no limita las apps instaladas por USB.

## Requisitos
- Android Studio con JDK 17, SDK Platform 37, Build Tools 37.0.0, NDK 27.3 y CMake 3.22.1 (ver [android/README.md](../android/README.md)).
- Teléfono con Android 8.0 (API 26) o posterior.
- En el teléfono: Ajustes › Acerca del teléfono › tocar 7 veces «Número de compilación» para activar *Opciones de desarrollador*, y en ellas **Depuración USB**. Conecta por cable y acepta la huella RSA.

## Instalar la versión Debug (la ruta soportada hoy)
```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
adb devices                       # debe aparecer el teléfono
./gradlew :app:installDebug
```
El código nativo (núcleos GB y GBA y la sesión) se compila con `-O2` también en Debug (N8-H1): el rendimiento que se ve y se mide con `installDebug` es el real, el mismo que en Release.

Instala encima de la versión previa y conserva los datos. Para actualizar, repite el comando. **No desinstales** la app para «instalar limpio»: borraría `filesDir/saves` (las partidas locales); quedaría solo el espejo `<rom>.sav` junto a la ROM, si la carpeta lo permite.

## Elegir la carpeta de juegos (SAF)
1. Abre PocketGB › Biblioteca › elegir carpeta.
2. En el selector del sistema elige la carpeta con tus `.gb` y `.gbc` (local, tarjeta SD o Google Drive) y concede el acceso. La app guarda un permiso persistente de lectura y escritura; si lo revocas, la biblioteca lo detecta y pide elegir de nuevo.
3. Las ROMs se leen solo desde esa carpeta (raíz y un nivel). La app escribe el espejo `<rom>.sav` junto a cada ROM; nunca modifica la ROM.

La carpeta es privada del usuario: **nunca** subas ROMs a GitHub ni a enlaces compartidos ([08](08-roms-legal.md)).

## Release firmado (opcional; solo documentado)
El repositorio **no define `signingConfigs` ni contiene claves**: `assembleRelease` produce `app-release-unsigned.apk`. Para firmarlo sin tocar el repo:

```bash
keytool -genkeypair -v -keystore ~/keys/pocketgb.jks -alias pocketgb \
  -keyalg RSA -keysize 4096 -validity 10000          # guarda el .jks y las contraseñas FUERA del repo
cd android && ./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign --ks ~/keys/pocketgb.jks \
  --out /tmp/pocketgb-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose /tmp/pocketgb-release.apk
adb install -r /tmp/pocketgb-release.apk
```
Alternativa: añadir un `signingConfigs` local cuyas rutas y contraseñas se lean de `~/.gradle/gradle.properties` (por ejemplo `POCKETGB_STORE_FILE`, `POCKETGB_STORE_PASSWORD`, `POCKETGB_KEY_ALIAS`, `POCKETGB_KEY_PASSWORD`); no está implementado en el repo para no meter secretos ni referencias a ellos. Un APK firmado con otra clave **no** se instala encima de uno Debug o de otra clave: hay que desinstalar antes (y se pierden las partidas locales; haz copia del espejo `.sav` primero).

Guarda el `.jks` con copia de seguridad: si lo pierdes, no podrás actualizar la app firmada con él.

## Comprobar que no usa red
```bash
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump permissions app/build/outputs/apk/release/app-release-unsigned.apk
```
No debe aparecer `INTERNET`.
