# Evidencia · Android A3 — Audio, input y ciclo de vida

**Fecha:** 2026-10-01

**Rama:** `codex/android-port`

**Alcance:** AAudio de baja latencia con ring SPSC, pacing por audio y fallback monotónico, controles multitáctiles con háptica, avance rápido ×2/×4, pausa por lifecycle/foco de audio y pantalla gameplay de diagnóstico. Persistencia, biblioteca y mando físico completo continúan fuera de A3.

## Implementación verificada

- El hilo nativo sigue siendo el único dueño de `gb*`; combina por OR las máscaras táctil y física antes de cada frame.
- El callback AAudio consume PCM estéreo `int16_t` a 48 kHz desde un ring fijo de 8192 frames. No reserva memoria, no bloquea, no registra logs, no usa JNI y rellena silencio ante underrun.
- A ×1 el consumo de audio guía el pacing. Cuatro ventanas sin consumo cambian a reloj monotónico. ×2/×4 detienen y vacían audio; volver a ×1 crea una ejecución nueva y vuelve a cebar 2048 frames.
- Pausa y stop cierran AAudio antes de confirmar el estado. `ON_PAUSE`, `ON_STOP` y pérdida de foco pausan; foreground o recuperación de foco no reanudan automáticamente.
- Una sola `View` gestiona todos los `pointerId`, diagonales, A+B, deslizamiento y cancelación. La geometría garantiza objetivos de al menos 48 dp y nunca publica direcciones opuestas. La háptica ocurre únicamente al flanco de una nueva pulsación.
- Gameplay usa viewport superior y controles inferiores en vertical; en horizontal los controles son overlay. La revisión visual detectó y corrigió el solapamiento entre Menú y velocidad.

## Verificación fresca

Núcleo host:

```bash
make -C core test
make -C core asan
```

Resultado: `65/65` casos requeridos y `1349` comprobaciones unitarias; AddressSanitizer y UndefinedBehaviorSanitizer sin fallos. Solo permanecen los tres `known-fail` documentados de M1.

Android:

```bash
cd android
./gradlew clean :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Tras corregir una semántica faltante del overlay, la repetición completa terminó `BUILD SUCCESSFUL`:

- 16/16 tests JVM: manifiesto 2, navegación 4, apariencia 3, viewport 2, geometría 3 y motor táctil 2.
- 28/28 tests instrumentados: shell 2, catálogo 6, lifecycle 2, audio 3, puente 4, sesión 7, carga nativa 1, controles 2 y superficie 1.
- La prueba de sesión incluye 100 ciclos consecutivos pausa/reanudar con audio detenido en cada barrera.
- Debug, Release y lint verdes; las cuatro ABI compilan.

## Estabilidad y revisión visual

Se mantuvo `gameplay-controls` durante 600 segundos continuos en `Small_Phone_API_35`. El PID `26845` permaneció idéntico durante toda la corrida; `dumpsys activity exit-info` y logcat no mostraron salidas, crashes ni ANR atribuibles a PocketGB. Un `SIGABRT` detectado por el primer filtro pertenecía al proceso de Bluetooth del sistema, no a la app.

`tools/android-screenshots.sh` generó 18/18 capturas reproducibles en claro/oscuro. Se inspeccionaron las variantes de gameplay y una captura horizontal adicional local: ×1 y ×4 se seleccionan correctamente, la superficie conserva su proporción y el overlay horizontal no solapa Menú con los chips de velocidad. Las imágenes usan únicamente la ROM sintética creada en memoria y permanecen ignoradas bajo `android/build/`.

## Privacidad y separación Release

`aapt dump permissions` sobre Release solo muestra `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; no existen `INTERNET` ni `ACCESS_NETWORK_STATE`. La búsqueda en los DEX Release no encontró `DebugCatalog`, `GameplayDebugScreen` ni `NativeVideoScreen`, mientras que sí aparecen en Debug.

No se versionaron ROMs, boot ROMs, partidas, estados, APK ni capturas.

## Aceptación real

Joel probó A3 en Android el 2026-10-01 y aprobó continuar. Con esta aceptación manual, A3 queda cerrado. La auditoría conjunta con Claude continúa reservada para A8.
