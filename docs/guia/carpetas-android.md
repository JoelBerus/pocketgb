# Organizar tus juegos en carpetas (Android)

PocketGB lee los juegos de **la carpeta que eliges** en la app (en el teléfono de Joel, `Roms` de Google Drive) y de las carpetas que tenga dentro. No copia ni cambia tus ROMs: solo los lee.

## Ejemplo

```
Roms/                                   ← la carpeta que eliges en la app
  Pokémon/
    1ª generación/
      Pokemon Red.gb
      Pokemon Red.sav                   ← la partida, junto al juego y con su mismo nombre
    2ª generación/
      Pokemon Gold.gbc
      Pokemon Gold.sav
  Tetris.gb                             ← también vale en la carpeta principal
  PocketGB/                             ← de la app: no pongas juegos aquí
  _Revisar/                             ← apartada: PocketGB no mira dentro
```

Las carpetas de primer nivel (aquí «Pokémon») son **categorías** y las de dentro, **subcategorías**: cada categoría tiene su estantería en el inicio y su pantalla, con las subcategorías y unas migas («Pokémon › 2ª generación»). En el detalle de cada juego ves dónde está, por ejemplo «Pokémon › 2ª generación · Pokemon Gold.gbc». Cómo usarlas, mostrar un juego en otra categoría sin mover el archivo y las etiquetas: [categorias-android.md](categorias-android.md). Hay un texto corto para dejar en tu carpeta: [LEEME-PocketGB.txt](../LEEME-PocketGB.txt).

## Las reglas

- **Hasta 5 niveles de carpetas** dentro de la que elegiste. `Roms/A/B/C/D/E/juego.gb` se ve; un nivel más hondo, no.
- **Lo que empieza por `.`** (punto) se ignora: son archivos y carpetas ocultos del sistema.
- **Las carpetas que empiezan por `_`** (guion bajo) quedan **apartadas**: PocketGB no mira dentro. Sirven para dejar a un lado lo que no quieres ver, por ejemplo `_Revisar/` o `_Repetidos/`. Un archivo que empieza por `_` sí se lee.
- **`PocketGB/`** en la carpeta principal es de la app (más adelante guardará ahí lo que envíes a otro equipo y lo que exportes). No se mira como biblioteca, así que no pongas juegos dentro. Una carpeta `PocketGB` dentro de otra carpeta sí se mira.
- Solo se leen archivos **`.gb` y `.gbc`** de hasta 8 MiB. Los **`.zip` no se leen**: descomprímelos antes.
- Como mucho se miran **5 000 archivos y carpetas** en total. Si tu carpeta tiene más, algunos juegos no aparecerán.

## La partida va junto al juego

El archivo `.sav` con el **mismo nombre** que el ROM, en la **misma carpeta**, es la copia de tu partida que comparten tus otros emuladores y equipos. PocketGB guarda además la suya en el teléfono, que es la que manda, con copias de respaldo.

Si en una misma carpeta tienes dos juegos con el mismo nombre y distinta extensión (`Juego.gb` y `Juego.gbc`), PocketGB no escribe su `.sav` junto a ellos para no mezclar las partidas; te lo avisa al abrirlos.

## Mover o renombrar juegos

Puedes reorganizar la carpeta cuando quieras. Al volver a la app, PocketGB reconoce el juego en su sitio nuevo **sin leerlo entero**, por su nombre, su tamaño, su fecha y la cabecera del cartucho (el título y los códigos de control que trae todo juego de Game Boy). Se conservan el favorito, el nombre que le pusiste, si estaba oculto, sus ajustes, su partida, sus estados y su portada. Un juego distinto que llegue con el mismo tamaño y la misma fecha no hereda nada: su cabecera es otra.

- **Mueve el `.sav` con el juego** (en Drive, selecciona los dos y muévelos juntos). Si mueves solo el ROM, no pasa nada malo: PocketGB usa la partida del teléfono y crea un `.sav` nuevo junto al juego; el viejo se queda donde estaba, sin tocarlo.
- Si cambias el nombre del archivo a la vez que lo mueves, también lo reconoce, salvo que haya otro archivo con el mismo tamaño y la misma fecha.
- Si no puede estar seguro (por ejemplo, mueves a la vez dos copias idénticas, o el proveedor no informa del tamaño o de la fecha), el juego aparece como nuevo hasta que lo abres o entras en su detalle: en ese momento recupera todo. No se pierde nada.
- Si un juego desaparece un rato (lo apartas en `_Revisar/` y lo devuelves, o Drive da una carpeta vacía mientras carga), PocketGB lo recuerda durante 30 días: al volver recupera su favorito, su nombre y lo demás, y no sale como nuevo.

## Juegos repetidos

Si el mismo juego está dos veces (aunque se llame distinto), en cuanto PocketGB lo sabe verás **«Duplicado»** en su tarjeta y, en el detalle, **«También en: …»** con las otras ubicaciones.

- Las copias **comparten** la partida del teléfono, los estados, los ajustes, la portada, el favorito y el nombre que le pusiste: para PocketGB son el mismo juego.
- Cada copia tiene su propio `.sav` al lado, pero en el teléfono **la partida es una sola**. Si las dos copias tienen partidas distintas, al abrir una se usa la más nueva de las dos (la del teléfono o la de junto a esa copia) y la otra **se aparta**: queda en Ajustes › Partidas › «Apartadas», no cuenta entre las 5 copias de seguridad y no se borra sola. Desde ahí puedes restaurarla.
- Lo mismo pasa si junto a un juego hay un `.sav` de **otro juego** con el mismo nombre: la partida que pierde queda apartada.
- Lo mejor es dejar una sola copia de cada juego y mover las demás (con su `.sav`) a `_Revisar/`.

## Google Drive

En Drive, mirar cada carpeta es una consulta por internet. El **primer escaneo** abre además cada juego para leer su cabecera, lo que puede descargarlo: con muchas subcarpetas y juegos **puede tardar**, déjalo terminar con conexión. Después, PocketGB recuerda la cabecera de cada archivo y solo abre los juegos nuevos o cambiados; cada vez que vuelves a la app se vuelve a mirar la lista de carpetas.

Si Drive aún está cargando una carpeta cuando PocketGB la mira, lo que no haya llegado todavía no se da por borrado: PocketGB vuelve a mirar sola a los pocos segundos (hasta tres veces) y, si no, en el siguiente escaneo.
