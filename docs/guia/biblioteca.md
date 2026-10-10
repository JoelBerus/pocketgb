# Guía · La biblioteca y el detalle de cada juego

## En vertical
- Arriba tienes el título, el **buscador** y los filtros **Todos · GB · GBC · GBA · Favoritos**.
- En la barra de arriba hay una **lupa**: abre el buscador aunque hayas bajado y ya no se vea.
- El menú **«…»** cambia la vista (cuadrícula o lista) y el orden (por nombre o por lo último que jugaste), abre una **categoría** y filtra por **etiqueta**; también vuelve a escanear la carpeta o la cambia.
- La biblioteca empieza con un **inicio**: «Continuar jugando», Favoritos y una estantería por categoría, y debajo «Todos los juegos». Todo lo explica [Categorías](categorias.md).

## En horizontal
Al girar el iPhone la pantalla se aprovecha para los juegos:
- El título pasa a una línea y siempre se lee, aunque debajo pase una captura muy clara.
- **No hay buscador ni filtros arriba**, y el título de la sección («Todos los juegos», «GBA», «Pokémon»…) se queda fijo arriba al bajar, para que siempre sepas qué estás viendo.
- **Arriba del todo**, las herramientas están en la barra de arriba, junto a «…». Sus paneles se abren **hacia abajo**.
- **Al bajar**, la barra de pestañas se encoge en una burbuja a la izquierda y las herramientas pasan a **cuatro botones redondos a la derecha, en la misma fila que la burbuja**. Sus paneles se abren **hacia arriba**, pegados al botón, y nunca tapan el título de la sección. Al volver arriba, los botones vuelven a la barra.

| Botón | Qué hace |
|---|---|
| Lupa · **Buscar** | Abre el buscador con el teclado. Escribe parte del nombre (vale el nombre que le pusiste o el del archivo). Al cerrarlo, el buscador desaparece. |
| Embudo · **Filtros** | Todos, GB, GBC, GBA o Favoritos y, debajo, tus **etiquetas**. Con un filtro puesto el icono se rellena. |
| Carpeta · **Categorías** | Las categorías de primer nivel (por ejemplo «Pokémon» o «Kirby»), con cuántos juegos tiene cada una, y «Sin categoría» para los juegos sueltos. Tocar una **abre su pantalla**, con sus subcategorías (ver [Categorías](categorias.md)). |
| Cuadrícula o lista · **Vista y orden** | Cuadrícula o lista, ordenar por nombre o por lo último jugado, volver a escanear y cambiar de carpeta. |

- Toca fuera de un panel para cerrarlo. Con texto muy grande, mantén pulsado un botón redondo para ver su nombre en grande.
- Lo que elijas se mantiene al volver a vertical: el título de la sección te dice qué filtro o etiqueta tienes puesto, y lo quitas con el segmentado, con «Quitar» junto a la etiqueta o con «…» › Etiqueta › Todas. Si cambias de carpeta o ningún juego lleva ya esa etiqueta, el filtro se quita solo.
- La lupa también está en la barra en vertical, como una opción más.

Las categorías salen de tus carpetas (ver [Carpetas](carpetas.md)). Las subcategorías, mostrar un juego en otra categoría sin tocar los archivos y las etiquetas están en [Categorías](categorias.md).

## El detalle de un juego
- **En vertical**, la imagen ocupa como mucho algo menos de la mitad de la pantalla, así ves enseguida el botón **Jugar** o **Continuar**.
- **En horizontal** (o en pantallas anchas), la imagen va a la izquierda, entera, y la información a la derecha con su propio desplazamiento. **Jugar/Continuar** queda justo debajo del título, sin tener que bajar.
- La imagen respeta la forma de la pantalla de cada consola: casi cuadrada en Game Boy y Game Boy Color (10:9) y más alargada en Game Boy Advance (3:2). Nunca se estira.
- En las tarjetas de la biblioteca todas las imágenes tienen el mismo marco; la captura de un juego de Game Boy Advance se recorta un poco por los lados para encajar, pero no se deforma. En el detalle la ves entera.
- En la lista de estados guardados, las miniaturas de Game Boy Advance también se ven en 3:2.

## Información técnica
Al final del detalle, **«Información técnica»** se despliega con un toque. PocketGB lee la cabecera del juego (sin abrirlo ni tocar tu partida) y te muestra:

| Dato | Qué significa |
|---|---|
| Título de cabecera | El nombre que trae el cartucho, aunque tú le hayas puesto otro. |
| Cartucho (Game Boy) | El tipo de chip del cartucho, por ejemplo «MBC3 + reloj + RAM + batería». |
| Tipo de partida y código del juego (Game Boy Advance) | Cómo guarda el juego (SRAM, Flash o EEPROM) tal como lo detecta PocketGB, y el código del cartucho, el fabricante y la versión. |
| ROM | El tamaño del juego. |
| Partida guardada | Cuánto ocupa la partida y si el cartucho tiene batería o reloj. «Sin RAM» o «Sin partida» si el juego no guarda. |
| Checksum de cabecera y global | Comprobaciones del volcado. Si el global no coincide no pasa nada: la consola real no lo mira. Un aviso siempre lleva símbolo y texto. |
| Huella SHA-256 | La huella completa del ROM (en Game Boy, de los bytes que declara su cabecera, aunque el archivo tenga relleno al final; en Game Boy Advance, del archivo entero). Con «Copiar huella» la llevas al portapapeles (sirve para comprobar que dos copias son el mismo juego). |

Si el juego está solo en iCloud, la información aparece cuando lo descargues: PocketGB nunca lo descarga por su cuenta solo para mirarla.
