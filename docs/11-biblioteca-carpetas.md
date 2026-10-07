# 11 · Tu carpeta de juegos: categorías, etiquetas y reglas (iOS y Android)

Documento común para quien organiza su carpeta a mano (en Drive, iCloud, el ordenador o una tarjeta). Explica qué lee PocketGB, cómo convierte tus carpetas en categorías y qué guarda la app por su cuenta (categorías virtuales, etiquetas, ajustes del inicio). La guía de cada app está en [guia/carpetas-android.md](guia/carpetas-android.md), [guia/categorias-android.md](guia/categorias-android.md) y [guia/carpetas.md](guia/carpetas.md) (iPhone). Hay una versión corta para copiar dentro de tu carpeta: [LEEME-PocketGB.txt](LEEME-PocketGB.txt).

> Estado: las reglas de carpetas (§1–§4) valen ya en las dos apps (N1). Las categorías virtuales, las etiquetas y el inicio con estanterías (§5–§7) están en **Android desde N4**; el iPhone los recibe en su lote N4 iOS. Las portadas junto al ROM (§4) llegan en N5.

## 1. La carpeta es la verdad
Eliges **una carpeta** en la app (en Android, `Roms` de Google Drive; en el iPhone, `GMRoms` de iCloud). Dentro, cada carpeta es una **categoría** y las carpetas que tenga dentro, **subcategorías**. PocketGB solo lee: nunca copia, mueve, renombra ni borra tus ROMs.

```
Roms/                                   ← la carpeta que eliges en la app
  Pokémon/                              ← categoría
    1ª generación/                      ← subcategorías (hasta 5 niveles)
      Pokemon Red.gb
      Pokemon Red.sav                   ← partida (la mantiene la app)
      Pokemon Red.png                   ← portada opcional, mismo nombre que el ROM (N5)
    2ª generación/
      Pokemon Gold.gbc
  Kirby/
    Kirby - Nightmare in Dream Land.gba
  Tetris.gb                             ← en la raíz: «Sin categoría»
  gba_bios.bin                          ← opcional, BIOS de tu GBA (raíz)
  PocketGB/                             ← de la app: Intercambio/, Exportados/
  _Revisar/                             ← apartada: no se escanea
```

- **Primer nivel = categoría** («Pokémon», «Kirby»). Cada una sale como una estantería en el inicio y tiene su pantalla con sus subcategorías.
- **Niveles siguientes = subcategorías** («Pokémon › 2ª generación»). Una categoría muestra sus juegos **y los de todas sus subcarpetas**.
- **La raíz = «Sin categoría».** Los juegos sueltos en la carpeta principal van ahí.
- **Profundidad:** hasta **5 niveles** dentro de la carpeta elegida. `Roms/A/B/C/D/E/juego.gb` se ve; un nivel más hondo, no (súbelo de nivel).

## 2. Nombres reservados (ND11)
| Nombre | Qué hace PocketGB |
|---|---|
| Empieza por `.` (`.oculta/`, `.DS_Store`) | Se ignora: son archivos y carpetas ocultos del sistema. |
| Carpeta que empieza por `_` (`_Revisar/`, `_Repetidos/`) | **Apartada**: no se mira dentro, en ningún nivel. Sirve para dejar a un lado copias o juegos que no quieres ver. Nada se borra. Un *archivo* que empieza por `_` sí se lee. |
| `PocketGB/` **en la raíz** | Es de la app (partidas que envías a otro equipo y exportados, N7). No se lee como biblioteca: no pongas juegos dentro. Una `PocketGB/` dentro de una categoría sí se lee. |
| `gba_bios.bin` en la raíz | BIOS opcional de tu Game Boy Advance (se valida por su huella). |

## 3. Qué archivos lee
- **Juegos:** `.gb` y `.gbc` de hasta **8 MiB**; `.gba` de hasta **32 MiB** (en el iPhone ya; en Android cuando llegue N8 Kotlin).
- **No lee `.zip`** ni otros comprimidos: descomprímelos antes.
- Como mucho **5 000 archivos y carpetas** por escaneo; si tu carpeta tiene más, algunos no aparecerán (siempre los mismos: los más hondos).
- El escaneo es por niveles y en orden de nombre. En Drive, mirar cada carpeta es una consulta por internet: muchas subcarpetas hacen el primer escaneo más lento (los siguientes solo abren los juegos nuevos o cambiados).

## 4. La partida y la portada van junto al juego
- **`<nombre del ROM>.sav` en la misma carpeta** es la copia de tu partida que comparten otros emuladores y equipos. La partida que manda es la del teléfono, con 5 copias de seguridad; la de junto al juego se mantiene al día.
- **Si mueves un juego, mueve también su `.sav`.** Si se queda atrás no se pierde nada: la del teléfono sigue a salvo y se crea un `.sav` nuevo junto al juego; el viejo se queda donde estaba, sin tocarlo.
- **Portadas por nombre (N5, anunciado):** `<nombre del ROM>.png|jpg|jpeg|webp` junto al juego, o `portada.*`/`cover.*` si la carpeta tiene un solo juego. Ya puedes dejarlas en su sitio; la app las leerá cuando llegue N5.

## 5. Categorías virtuales: «Mostrar en categoría…» (ND3)
A veces quieres ver un juego en otra categoría sin tocar tu carpeta (por ejemplo, juntar en «Para jugar» los que tienes pendientes de varias sagas, o tener un juego en «Pokémon» aunque el archivo esté en la raíz). Para eso, en los **ajustes del juego › Categoría › Cambiar**:

- Elige **una categoría que ya existe** (cualquier carpeta con juegos, sus carpetas padre o otra categoría virtual) **o escribe una nueva**. Usa `/` para meterla dentro de otra: `Pokémon/Para jugar`.
- El juego **se ve ahí en vez de en su carpeta**: en su estantería, en su pantalla de categoría, en los conteos y en la búsqueda. Lleva la insignia discreta **«Movido en la app»** y su detalle dice «Se ve en «Para jugar»» además de dónde está el archivo.
- **Nunca toca archivos.** El ROM y su `.sav` se quedan en su carpeta; otro emulador u otro equipo lo seguirán viendo donde está.
- **«Volver a su carpeta»** lo deshace. Elegir su propia carpeta es lo mismo.
- Una categoría que solo existe en la app aparece mientras tenga algún juego; si «vuelves» todos sus juegos, desaparece.
- Los nombres siguen las reglas de las carpetas: ningún nivel puede empezar por `.` ni por `_`, y como mucho 5 niveles. Si escribes una que ya existe con otras mayúsculas o acentos («pokemon/para jugar»), se usa la existente («Pokémon/…»).
- Va **por huella** (el contenido del juego, N1): si mueves o renombras el archivo, el juego vuelve a su categoría virtual **en cuanto PocketGB reconoce el archivo** (normalmente al volver a escanear; si no, al abrir el juego o su detalle). Las copias del mismo juego (insignia «Duplicado») comparten categoría virtual; una copia que ya está en esa carpeta de verdad no lleva la insignia.
- Es **de este dispositivo** (ND12): no se sincroniza solo con el otro equipo; viajará dentro del paquete de N7.

¿Cuándo usar cada cosa? Si quieres que el cambio se vea también en otros emuladores, **mueve la carpeta de verdad** (en Drive o en el ordenador). Si solo quieres ordenar tu vista en PocketGB, usa la categoría virtual.

## 6. Etiquetas
- **Libres y varias por juego** («rpg», «pendiente», «dos jugadores»…), hasta 20 por juego y 30 caracteres cada una. Se añaden y quitan en los **ajustes del juego › Etiquetas**; allí también se ofrecen las que ya usas en otros juegos.
- «RPG» y «rpg» (o «acción» y «accion») son la misma etiqueta.
- Sirven para **filtrar** (Filtros › Etiquetas) y la **búsqueda las encuentra** (también encuentra el nombre de la categoría en la que se ve el juego).
- Van **por huella** y son **de este dispositivo**, como la categoría virtual.

## 7. El inicio y sus ajustes (por dispositivo)
- El inicio de la biblioteca muestra «Continuar jugando», una **fila de Favoritos** y **una estantería por categoría de primer nivel** (con «Sin categoría» para la raíz), cada una con hasta 10 juegos y **«Ver todo»**. Debajo, «Todos los juegos» como siempre. Si tu carpeta no tiene subcarpetas, no hay estanterías (repetirían «Todos los juegos»).
- En **Ajustes › Biblioteca › Inicio** ordenas las estanterías, **fijas arriba** las que más usas y **ocultas** las que no quieres en el inicio (sus juegos siguen en «Todos los juegos» y en su categoría), y muestras u ocultas la fila de Favoritos.
- Cada categoría recuerda su **vista** (cuadrícula o lista).
- Todo esto se guarda en el teléfono (ND12) y no cambia nada en tu carpeta.

## 8. Dónde lo guarda la app
En Android, en `preferences.json` (privado de la app), **formato 4** desde N4: etiquetas (`tagsByFingerprint`), categoría virtual (`virtualFoldersByFingerprint`), ajustes del inicio (`home`) y vista por categoría (`categoryLayouts`), junto con favoritos, nombres y ocultos de N1. Un archivo del formato anterior (2 o 3) se lee igual y se reescribe como 4; uno de una versión futura se usa sin sobrescribirlo (la biblioteca lo avisa). Borrar la app borra estos datos, nunca tus ROMs ni tus `.sav` de la carpeta.
