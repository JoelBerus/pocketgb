# 00 · Visión y alcance

## Problema
Joel quiere jugar Pokémon Rojo y Amarillo en su iPhone sin instalar emuladores de terceros ni descargar ROMs de sitios dudosos. Tampoco quiere pagar servidores.

## Objetivo v1
Una app iOS nativa, construida y firmada por Joel, que:
1. Ejecuta volcados de **sus cartuchos** de Game Boy (DMG) y Game Boy Color (CGB) con precisión suficiente para Pokémon Rojo (MBC3) y Amarillo (MBC5).
2. Nunca pierde una partida.
3. En horizontal muestra el juego a pantalla completa con **controles superpuestos translúcidos**. En vertical, controles debajo.
4. Lee la biblioteca de una carpeta privada de iCloud Drive, sin red propia, sin cuentas y sin servidores.
5. No tiene código de terceros en runtime ni permisos de red.

## No-objetivos v1 (explícitos)
- Game Boy Advance, Super Game Boy (bordes y paletas SGB), impresora GB, sensores de cartucho.
- Precisión cycle-perfect (FIFO de píxeles exacto, rarezas de OAM). Se sube solo si un juego objetivo lo exige.
- Cable link entre dispositivos. El **cable virtual local** (dos instancias en la misma app) queda en M9.
- Shaders, filtros, rewind, trucos, RetroAchievements.
- App Store o TestFlight (requieren cuenta de pago; ver [07](07-instalacion-iphone.md)).
- Android en v1: solo se documenta ([05](05-android-spec.md)).

## Criterio de éxito final
En el iPhone de Joel: Rojo y Amarillo arrancan, se juega 1 h con audio estable a 59,73 fps, se guarda en el PC del Centro Pokémon, se fuerza el cierre de la app, se reinstala desde Xcode y la partida sigue ahí. En horizontal, los botones son translúcidos y responden a multitoque (A+B, deslizar entre direcciones).

## Plataformas
| | Mínimo | UI | Núcleo |
|---|---|---|---|
| iOS | 17.0 | SwiftUI + UIKit (vista de controles) + Metal | `core/` como target C en Xcode |
| Android (futuro) | API 26 | Kotlin + Jetpack Compose | `core/` vía NDK/CMake + JNI |
