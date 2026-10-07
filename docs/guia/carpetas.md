# Guía · Organizar tus juegos por carpetas

PocketGB lee los juegos de **una carpeta que eliges tú** (en el iPhone, en iCloud Drive o en Archivos). Dentro de ella puedes crear subcarpetas para ordenarlos: PocketGB las recorre solo, sin copiar, mover ni cambiar nada.

## Cómo organizarlos
- **Cada carpeta de primer nivel es una categoría** (por ejemplo, «Pokémon» o «Kirby»). Dentro puedes crear **subcategorías**, hasta **5 niveles** de carpetas en total.
- Los juegos sueltos en la carpeta principal quedan **sin categoría**.
- Un juego guardado a más de 5 niveles de profundidad no aparece: súbelo de nivel.
- En el detalle de cada juego verás dónde está, por ejemplo: «Pokémon › 2ª generación · Pokemon Crystal.gbc».

Ejemplo:

```
Roms/                                   ← la carpeta que eliges en la app
  Pokémon/                              ← categoría
    1ª generación/                      ← subcategoría (hasta 5 niveles)
      Pokemon Red.gb
      Pokemon Red.sav                   ← partida (la mantiene la app)
      Pokemon Red.png                   ← portada opcional, mismo nombre que el ROM
  Kirby/
    Kirby - Nightmare in Dream Land.gba
  gba_bios.bin                          ← opcional, BIOS de tu GBA (raíz)
  PocketGB/                             ← de la app: Intercambio/, Exportados/
  _Revisar/                             ← apartada: no se escanea
```

Las portadas junto al ROM y la carpeta `PocketGB/` llegarán en próximas versiones; ya puedes dejar su sitio preparado.

## Nombres especiales
| Nombre | Qué hace PocketGB |
|---|---|
| Empieza por `_` (p. ej. `_Revisar/`) | **Apartada:** no se lee. Úsala para juegos que no quieres ver todavía o copias que estás revisando. Nada se borra. |
| Empieza por `.` | Oculta: no se lee (son carpetas y archivos del sistema). |
| `PocketGB/` en la carpeta principal | Es de la app (intercambio y exportados): no se lee como juegos. Una carpeta «PocketGB» dentro de una categoría sí se lee. |

## Qué archivos lee
- Solo `.gb`, `.gbc` y `.gba`.
- **No lee `.zip`** ni otros comprimidos: descomprímelos antes.
- No abre archivos de más de 8 MiB (Game Boy y Game Boy Color) ni de más de 32 MiB (Game Boy Advance).
- Los juegos que aún están solo en iCloud aparecen con una nube: tócalos para descargarlos.
- Si la carpeta tiene más de 5 000 juegos, PocketGB muestra los primeros 5 000 y te avisa.

## Tu partida, junto al juego
- PocketGB guarda tu partida en el iPhone (la copia que manda) y mantiene una copia **`<nombre del juego>.sav` junto al ROM**, en su misma carpeta.
- Si mueves un juego de carpeta, **mueve también su `.sav`**. Si se queda atrás, tu partida sigue a salvo en el iPhone y PocketGB crea un `.sav` nuevo junto al juego la próxima vez que lo abras; el viejo se queda donde estaba, sin tocar.
- Ojo con ese `.sav` que se queda atrás: si algún día pones en esa carpeta otro juego con el mismo nombre, PocketGB lo tomaría como su partida. Bórralo o apártalo si ya no lo quieres.
- Cuando el `.sav` de junto al juego y la partida del iPhone no coinciden y el `.sav` no lo escribió PocketGB (por ejemplo, viene de otro emulador o de otra copia del juego), gana la más reciente y **la otra nunca se pierde**: queda en Ajustes › Partidas › el juego › «Copias apartadas», lista para restaurarla. Esas copias no se sustituyen con el tiempo.

## Mover y renombrar juegos
PocketGB reconoce cada juego por su **contenido** (su huella), no por su nombre ni su carpeta. Por eso, al mover un juego a otra carpeta o renombrar el archivo **conservas todo**: favorito, nombre que le pusiste, si estaba oculto, sus ajustes, la partida, los estados y la portada.

Para reconocerlo, PocketGB tiene que leer el archivo una vez. Lo hace en segundo plano, sin que esperes, y solo con los juegos que ya están en el iPhone: **nunca descarga nada de iCloud por su cuenta**. Un juego movido que aún está solo en iCloud recupera sus datos en cuanto lo descargues.

## Juegos repetidos
Si el mismo juego está en dos carpetas (por ejemplo, en «Pokémon» y en «Copias»), PocketGB lo marca con la insignia **«Duplicado»** y en su detalle te dice dónde está la otra copia («También en: Copias › Pokemon Red.gb»).
- Para PocketGB son **el mismo juego**: comparten favorito, nombre, ajustes, estados, portada y **una sola partida en el iPhone**.
- Cada copia tiene su propio `.sav` junto a ella. Si esos dos `.sav` son partidas distintas (por ejemplo, jugaste cada copia en otro emulador), **no puedes tener las dos a la vez**: al abrir una copia gana la partida más reciente y la otra queda en Ajustes › Partidas › el juego › «Copias apartadas», sin perderse.
- **Recomendación:** deja una sola copia de cada juego y mueve las demás, con su `.sav`, a `_Revisar/`. Dejan de leerse, desaparece el aviso y no se borra nada.

## Si algo falla con tus preferencias
Favoritos, nombres y ajustes por juego se guardan en un archivo de la app. Si un día ese archivo se daña, PocketGB no lo borra: lo aparta como `preferences.corrupt-<fecha>.json`, empieza con preferencias nuevas y te avisa. Tus partidas, estados y ROMs no se tocan.
