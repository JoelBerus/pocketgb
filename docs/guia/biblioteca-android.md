# La biblioteca y el detalle de cada juego (Android)

## «Continuar jugando»
- Arriba de la biblioteca están los juegos que puedes **continuar justo donde los dejaste** (los que tienen estado automático, hasta cinco). Un juego que solo tiene su partida no sale aquí: se abre con **Jugar** desde su tarjeta o su detalle.
- Cada tarjeta mide lo mismo que una columna de la cuadrícula de abajo y está alineada con ella: ves tantas como columnas. Si hay más, desliza el carril hacia la izquierda; al soltar, las tarjetas se colocan otra vez en sus columnas.
- **Continuar** (sobre la imagen) abre el juego donde lo dejaste. Tocar la imagen abre su detalle.
- Con la letra muy grande, cada tarjeta lleva la imagen a la izquierda y el botón debajo.

## El inicio (N4)
Bajo «Continuar jugando» van la fila de **Favoritos** y **una estantería por categoría** (cada carpeta de primer nivel), con «Ver todo»; debajo, «Todos los juegos». Todo sobre categorías, etiquetas y los ajustes del juego, en [categorias-android.md](categorias-android.md).

## En vertical
- Arriba tienes el **buscador** y los filtros **Todos · GB · GBC · Favoritos** y, si tus juegos tienen etiquetas, **Etiqueta**.
- El menú **«⋮»** cambia la vista (cuadrícula o lista) y el orden (por nombre o por lo último que jugaste), abre una **categoría**, y también vuelve a escanear la carpeta o la cambia.

## En horizontal
Al girar el teléfono la pantalla se aprovecha para los juegos:
- **No hay buscador ni filtros arriba.** En su lugar, la barra de arriba («Biblioteca») tiene **cuatro iconos**:

| Icono | Qué hace |
|---|---|
| Lupa · **Buscar** | El buscador aparece en la barra de arriba con el teclado. Escribe parte del nombre (vale el nombre que le pusiste o el del archivo). La flecha o el botón Atrás lo cierran: la búsqueda se vacía y vuelven la barra y la lista como estaban. |
| Embudo · **Filtros** | Todos, GB, GBC o Favoritos y, debajo, las **Etiquetas** (N4). Con un filtro o una etiqueta puestos, el icono se ve relleno. |
| Carpeta · **Categorías** | Las carpetas de primer nivel de tu carpeta de juegos (por ejemplo «Pokémon» o «Kirby»), con cuántos juegos tiene cada una, y «Sin categoría» para los juegos sueltos. Desde N4, cada una **abre su pantalla** (subcategorías, migas y juegos). |
| Cuadrícula o lista · **Vista y orden** | Cuadrícula o lista, y ordenar por nombre o por lo último jugado. |

- Cada icono abre su panel **hacia abajo**, bajo la barra. El panel no tapa el título de la sección («Todos los juegos», «GBC», «Pokémon»…): si el título está justo debajo de la barra, el panel se abre debajo del título. Toca fuera del panel o pulsa Atrás para cerrarlo.
- **Al bajar por la lista**, la barra de arriba se esconde, el título de la sección se queda fijo arriba y aparecen **los mismos cuatro botones abajo a la derecha**, flotando. Sus paneles se abren **hacia arriba** y llegan como mucho hasta debajo del título. Si con la letra muy grande no cabe, la barra de arriba termina de esconderse antes de abrir el panel.
- **Al subir un poco**, vuelve la barra de arriba con sus iconos y los botones flotantes se van. Si tienes «Quitar animaciones» activado en el teléfono, aparecen y se van sin animación.
- «Volver a escanear» y «Cambiar carpeta» siguen en el menú «⋮» de arriba.
- Lo que elijas se mantiene al volver a vertical: el título de la sección te dice qué filtro o etiqueta tienes puesto. En vertical lo cambias con los filtros.
- Funciona igual con la barra lateral de navegación (en horizontal y en pantallas anchas los destinos Biblioteca, Favoritos y Ajustes van a la izquierda).

Las categorías salen de tus carpetas (ver [carpetas-android.md](carpetas-android.md)). Una categoría incluye sus subcarpetas: «Pokémon» muestra también los juegos de «Pokémon › 1ª generación». Desde N4 cada categoría tiene su pantalla con sus subcategorías, y puedes mostrar un juego en otra categoría sin tocar los archivos ([categorias-android.md](categorias-android.md)).

## El detalle de un juego
- **En vertical**, la imagen ocupa como mucho algo menos de la mitad de la pantalla, así ves enseguida **Jugar** o **Continuar**.
- **En horizontal** (o en pantallas anchas, como un plegable abierto o una tablet), la imagen va a la izquierda, entera, y la información a la derecha con su propio desplazamiento. **Jugar/Continuar** va justo debajo del título (que ocupa como mucho dos líneas), así se ve sin bajar aunque el nombre o la carpeta sean muy largos.
- La imagen respeta la forma de la pantalla de la consola: casi cuadrada en Game Boy y Game Boy Color (10:9). Nunca se estira.
- **Favorito, Estados y Ajustes** van en una fila si caben enteros; si no, con el icono encima del texto o uno debajo de otro.

## Información técnica
Bajo las acciones, **«Información técnica»** se pliega y despliega con un toque (empieza desplegada). PocketGB lee la cabecera del juego, sin tocar tu partida, y te muestra:

| Dato | Qué significa |
|---|---|
| Cartucho | El tipo de chip del cartucho, por ejemplo «MBC3 + RAM + batería». |
| ROM | El tamaño del juego. |
| Partida guardada | Cuánto ocupa la partida y si el cartucho tiene batería o reloj. «Sin RAM» si el juego no guarda. |
| Checksum de cabecera y global | Comprobaciones del volcado. Si el global no coincide no pasa nada: la consola real no lo mira. El aviso siempre lleva símbolo y texto. |
| Huella SHA-256 | La huella completa del archivo. Mantén pulsado para seleccionarla y copiarla (sirve para comprobar que dos copias son el mismo juego). |
