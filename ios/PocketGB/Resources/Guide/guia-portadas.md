<!-- Generado por tools/ios-guide-sync.py desde docs/guia/portadas.md: no editar aquí. -->
Cada juego de la biblioteca tiene una portada. PocketGB puede sacarla de cuatro sitios, y tú eliges cuál usar en cada juego o en toda la biblioteca. Todo se queda en el iPhone: la app no se conecta a internet y **nunca descarga portadas**. Las imágenes las pones tú.

## De dónde sale la portada

| Fuente | Qué es | Cómo se consigue |
|---|---|---|
| **Imagen importada** | Una foto o imagen que eliges en la app. | Ajustes del juego › Portada › «Elegir de Fotos» o «Elegir archivo». |
| **Imagen de la carpeta** | Una imagen junto al ROM, en tu carpeta de juegos. | Copia la imagen en la carpeta del juego con el mismo nombre que el ROM. |
| **Captura del juego** | Una escena del propio juego. | Se guarda sola al salir de una partida. También puedes fijar una con «Usar como portada» en la pausa. |
| **Generada** | Un color y un dibujo propios del juego, con sus iniciales y la consola. | Siempre está. Es la que se ve si no hay otra o si una imagen falla. |

### Imagen de la carpeta

Ponla junto al ROM con **el mismo nombre** y la extensión `.png`, `.jpg`, `.jpeg` o `.webp`. Da igual si va en mayúsculas o minúsculas:

```
GMRoms/
  Pokémon/1ª generación/
    Pokemon Red.gb
    Pokemon Red.png        ← portada de Pokemon Red
  Kirby/
    Kirby - Nightmare in Dream Land.gba
    portada.jpg            ← vale porque en esta carpeta solo hay un juego
```

- Si en la carpeta hay **un solo juego**, también vale `portada.*` o `cover.*`.
- Si hay varias imágenes con el mismo nombre, gana `.png`; después, `.jpg`, `.jpeg` y `.webp`.
- Cuando la copies, usa **Volver a escanear** (Ajustes › Biblioteca) o vuelve a abrir la app.
- Al buscar juegos, la app no abre las imágenes: solo mira sus nombres. La primera vez que una portada aparece en pantalla, la app lee la imagen y guarda una copia reducida. Si está en iCloud y no se ha descargado, se descarga solo esa imagen. Por eso **ver la biblioteca puede descargar portadas de iCloud** (las de los juegos que aparecen en pantalla, como mucho tres a la vez). Si no quieres que se descarguen, elige «Captura» o «Generada» para esos juegos o quita las imágenes de la carpeta. No se vuelve a leer mientras no cambie. Si la quitas o la cambias, la copia vieja se borra en el siguiente escaneo.

### Imagen importada

En los **ajustes del juego › Portada**:
- **Elegir de Fotos** abre el selector de fotos del sistema. No hace falta dar permiso a la fototeca.
- **Elegir archivo** abre Archivos. Ahí valen PNG, JPG o WebP. HEIC no se acepta; si eliges una foto HEIC desde Fotos, el sistema la entrega como JPEG.

La app guarda una **copia reducida** en el iPhone, de 1024 píxeles de lado como mucho. Tu imagen original no cambia. Al importarla, el juego pasa a usar «Imagen». «Quitar imagen importada» la borra.

### Captura del juego

Al salir de una partida se guarda la última escena, salvo que sea una pantalla toda blanca o toda negra. Si prefieres una escena concreta, toca **«Usar como portada»** en la **pausa**. Esa escena queda fijada, el juego pasa a usar «Captura» y salir de la partida ya no la cambia. Con «Soltar captura fijada» (en Portada) vuelve a usarse la última escena. Fijar una portada no toca la partida ni los estados.

## Elegir la portada

En los **ajustes del juego**, la fila **Portada** dice qué elegiste y qué se ve; por ejemplo, «Automática · se ve: Imagen de la carpeta». Al tocarla puedes elegir:

- **Automática** (por defecto): sigue lo que diga Ajustes › Biblioteca › Portadas.
- **Imagen**: la importada o, si no hay, la de la carpeta.
- **Captura**: la fijada desde la pausa o, si no hay, la última escena.
- **Generada**: siempre la generada.

Si eliges Imagen o Captura y el juego no la tiene, se ve la generada. La pantalla lo avisa con «No hay: se verá la generada».

En **Ajustes › Biblioteca › Portadas** eliges qué gana en «Automática» cuando un juego tiene imagen y captura: **Preferir imágenes** (por defecto) o **Preferir capturas**. Estas elecciones son solo de este iPhone.

## Cómo se ve

- En la **cuadrícula, la lista, las estanterías y «Continuar jugando»**, la tarjeta conserva su forma y la imagen la rellena centrada. Si la imagen es más alta o más ancha que la tarjeta, se recortan los bordes.
- En el **detalle**, la imagen se ve **entera** dentro del marco con la forma de la pantalla de la consola. Si no lo llena, quedan bandas a los lados o arriba y abajo.
- Las capturas se dibujan sin suavizar, para que el pixel art se vea nítido. Las imágenes sí se suavizan.
- «Continuar jugando» muestra todos los juegos que puedes retomar, tengan portada o no. Si un juego no tiene, lleva la generada.

## Imágenes que no valen

PocketGB trata cada imagen como algo que puede venir mal. Solo acepta PNG, JPG o WebP (nunca HEIC, ni al importar) de **menos de 15 MB** y de hasta 16 384 píxeles de lado y 100 megapíxeles. Mira el contenido real del archivo, no la extensión, y lee el tamaño en la cabecera antes de abrir la imagen. Si una imagen está dañada o cortada, es enorme o no es una imagen aunque se llame `.png`, la app pasa a la siguiente fuente; si no hay otra, usa la generada. Si falla al importarla, verás «Formato no compatible: usa PNG, JPEG o WebP».

## Espacio

Las portadas se cuentan en **Ajustes › Almacenamiento › Portadas**. «Borrar portadas» borra las capturas, las capturas fijadas y las copias reducidas de las imágenes. Las importadas habrá que volver a elegirlas. Nunca toca las partidas, los estados ni las imágenes de tu carpeta. La portada que elegiste en cada juego se mantiene.
