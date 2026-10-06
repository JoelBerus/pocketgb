# A8 Android: evidencia (cierre: documentación, identidad y versión)

Rama `a8-cierre` sobre `codex/android-port` @ `ae8be16`. Commits: `a572317` (icono y versión) y `ea37fe7` (documentación). Estado: **en curso**; este lote no cierra A8.

## Qué se hizo
- Icono adaptativo: `res/drawable/ic_launcher_{background,foreground,monochrome}.xml` (VectorDrawable a mano, viewport 1024, derivados de `background.svg`, `screen.svg`, `controls.svg` y `app-icon-mono.svg`; primer plano y monocromo reducidos al 85 % para la zona segura; el monocromo recorta la pantalla con `evenOdd`) y `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`. Los SVG no están en esta rama (llegan por la integración); se leyeron desde `cierre-integracion`.
- Manifiesto: `android:icon`, `android:roundIcon`; `android:label` ya era `@string/app_name` = «PocketGB».
- Versión: `versionCode 2`, `versionName "1.0.0"`.
- Documentación (ver lista) y `docs/PRUEBAS-JOEL.md`.

## Comprobaciones (`./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleRelease :app:lintDebug`, con `ANDROID_HOME`)
```
BUILD SUCCESSFUL in 3m 19s
114 actionable tasks: 114 executed
(lint: Wrote HTML report to .../app/build/reports/lint-results-debug.html; sin fallo)
```
`aapt2 dump badging` (Debug y Release sin firmar):
```
package: name='com.joelbermudez.pocketgb' versionCode='2' versionName='1.0.0' ... compileSdkVersion='37'
application-label:'PocketGB'
application-icon-160:'res/mipmap-anydpi-v26/ic_launcher.xml'   (Debug)
application-icon-160:'res/BW.xml'                                (Release; el shrinker de recursos renombra el XML del icono)
uses-permission: name='com.joelbermudez.pocketgb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```
No aparece `INTERNET` en ninguno de los dos APK (el único permiso es el interno de AndroidX).

## Documentos actualizados
`android/README.md`, `docs/05-android-spec.md` (reescrito), `docs/02-arquitectura.md` (Android, hilos y propiedad del handle), `docs/06-testing.md` (sección Android), `docs/07-instalacion-android.md` (nuevo), `docs/PRUEBAS-JOEL.md` (nuevo), `README.md`, `docs/00-vision.md`, `docs/08-roms-legal.md`, `docs/hitos/README.md`, `docs/ESTADO.md`.

## Pendientes y no verificado
- Los 47 literales `Text("…")` siguen sin extraer a `strings.xml` (lote posterior, para no chocar con la respuesta a la auditoría de A7).
- Release sigue sin firma; la firma solo está documentada (`docs/07-instalacion-android.md`), no hay `signingConfigs`.
- No se ejecutaron pruebas JVM, instrumentadas ni el catálogo en este lote; el icono no se vio en un launcher real (ni en dispositivo ni en emulador), solo se comprobó que compila y que `aapt2` lo referencia. La forma vectorial se escribió a mano a partir de los SVG y no se comparó pixel a pixel.
- Los «trucos de entorno» del emulador (`hide_error_dialogs`, animaciones a 0) están descritos pero no los aplica ningún script del repo.
- Las cifras de pruebas citadas en `docs/06-testing.md` (372, 333, 50/50) provienen de la evidencia de A7, no de una corrida nueva.
- Auditoría conjunta de A8, pruebas reales de Joel y cierre de A5V6-H1, A5V6-H3 y A5V7-H1.
