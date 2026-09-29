# PocketGB

Emulador de Game Boy / Game Boy Color propio, nativo y sin red, para jugar volcados de **mis propios cartuchos** (objetivo: Pokémon Rojo y Amarillo) en iPhone. Android queda documentado para después.

- Núcleo: C11 portable en [`core/`](core/), sin dependencias.
- iOS: SwiftUI + Metal + AVAudioEngine en [`ios/`](ios/), sin red, instalado con Xcode (Apple ID gratuito).
- Android: receta en [docs/05-android-spec.md](docs/05-android-spec.md) (Kotlin + Compose + NDK).
- Biblioteca: una carpeta privada de iCloud Drive con los `.gb`/`.gbc`; la app la recuerda y escanea sola. Sin servidores.

Este repo **no contiene ni contendrá ROMs**. Ver [docs/08-roms-legal.md](docs/08-roms-legal.md).

Empieza por [AGENTS.md](AGENTS.md) y [docs/00-vision.md](docs/00-vision.md). Estado por hito en [docs/hitos/](docs/hitos/).
