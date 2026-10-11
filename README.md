# PocketGB

Emulador de Game Boy / Game Boy Color / Game Boy Advance propio, nativo y sin red, para jugar volcados de **mis propios cartuchos** (objetivo: Pokémon Rojo y Amarillo) en iPhone y en Android.

- Núcleo: C11 portable en [`core/`](core/), sin dependencias.
- iOS: SwiftUI + Metal + AVAudioEngine en [`ios/`](ios/), sin red, instalado con Xcode (Apple ID gratuito).
- Android: Kotlin + Jetpack Compose + NDK en [`android/`](android/), sin red; el mismo núcleo vía JNI. Hitos A1–A9 y nivel N cerrados (incluye GBA): [docs/05-android-spec.md](docs/05-android-spec.md), instalación en [docs/07-instalacion-android.md](docs/07-instalacion-android.md).
- Biblioteca: una carpeta privada (iCloud Drive en iOS, carpeta SAF en Android) con los `.gb`/`.gbc`/`.gba`; la app la recuerda y escanea sola. Sin servidores.

Página del proyecto: <https://joelberus.github.io/pocketgb/> (fuente en [`site/`](site/), publicada con [`pages.yml`](.github/workflows/pages.yml)).

Este repo **no contiene ni contendrá ROMs**. Ver [docs/08-roms-legal.md](docs/08-roms-legal.md).

Empieza por [AGENTS.md](AGENTS.md) y [docs/00-vision.md](docs/00-vision.md). Estado por hito en [docs/hitos/](docs/hitos/).
