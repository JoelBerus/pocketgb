# Portadas en Android

Cada juego de la biblioteca tiene una portada. PocketGB para Android la puede sacar de cuatro sitios y tú eliges cuál usar, juego a juego o para toda la biblioteca. Todo se queda en el teléfono: la app no tiene acceso a internet y **nunca descarga portadas**; las imágenes las pones tú.

## De dónde sale la portada

| Fuente | Qué es | Cómo se consigue |
|---|---|---|
| **Imagen importada** | Una foto o imagen que eliges en la app. | Ajustes del juego › Portada › Cambiar › «Elegir de Fotos» o «Elegir archivo». |
| **Imagen de la carpeta** | Una imagen junto al ROM, en tu carpeta de juegos. | Copia la imagen en la carpeta del juego con el mismo nombre que el ROM. |
| **Captura del juego** | Una escena del propio juego. | Se guarda sola al salir de una partida, o la fijas tú con «Usar como portada» en la pausa. |
| **Generada** | Un color y un dibujo propios del juego, con sus iniciales y la consola. | Siempre está; es la que se ve si no hay otra o si una imagen falla. |

### Imagen de la carpeta

Ponla junto al ROM con **el mismo nombre** y extensión `.png`, `.jpg`, `.jpeg` o `.webp` (da igual mayúsculas o minúsculas):

```
Roms/
  Pokémon/1ª generación/
    Pokemon Red.gb
    Pokemon Red.png        ← portada de Pokemon Red
  Kirby/
    Kirby - Nightmare in Dream Land.gba
    portada.jpg            ← vale porque en esta carpeta solo hay un juego
```

- Si en la carpeta hay **un solo juego**, también vale `portada.*` o `cover.*`.
- Si hay varias con el mismo nombre, gana `.png`, luego `.jpg`, `.jpeg` y `.webp`.
- Tras copiarla, usa **Volver a escanear** (Ajustes › Biblioteca) o abre la biblioteca otra vez.
- La primera vez que se ve, la app lee la imagen (en Google Drive eso la descarga) y guarda una copia reducida; no la vuelve a leer mientras no cambie.

### Imagen importada

En los ajustes del juego, **Portada › Cambiar › Elegir de Fotos** abre el selector de fotos del sistema (no hace falta dar permisos) y **Elegir archivo** abre el selector de archivos. La app guarda una **copia reducida** (como mucho 1024 píxeles de lado) en el teléfono; tu imagen original no cambia. Al importarla, el juego pasa a usar «Imagen». «Quitar imagen importada» la borra.

### Captura del juego

Al salir de una partida se guarda la última escena (si no es una pantalla en blanco o en negro). Si prefieres una escena concreta, en la **pausa** toca **«Usar como portada»**: queda fijada, el juego pasa a usar «Captura» y salir de la partida ya no la cambia. «Soltar captura fijada» (en Portada › Cambiar) vuelve a usar la última escena.

## Elegir la portada

En los **ajustes del juego › Portada** ves qué eliges y qué se ve («Automática · se ve: Imagen de la carpeta»). Con **Cambiar**:

- **Automática** (por defecto): usa lo que diga Ajustes › Biblioteca › Portadas.
- **Imagen**: la importada o, si no hay, la de la carpeta.
- **Captura**: la fijada desde la pausa o, si no hay, la última escena.
- **Generada**: siempre la generada.

Si eliges Imagen o Captura y ese juego no la tiene, se ve la generada (el diálogo lo avisa con «No hay: se verá la generada»).

En **Ajustes › Biblioteca › Portadas** decides qué gana en «Automática» cuando un juego tiene imagen y captura: **Preferir imágenes** (por defecto) o **Preferir capturas**.

## Cómo se ve

- En la **cuadrícula, la lista y «Continuar jugando»** la tarjeta mantiene su forma y la imagen la rellena centrada: si la imagen es más alta o más ancha, se recorta por los bordes.
- En el **detalle** la imagen se ve **entera**, dentro del marco con la forma de la pantalla de la consola, con bandas a los lados o arriba y abajo si hace falta.
- Las capturas se dibujan sin suavizar (pixel art); las imágenes, suavizadas.

## Imágenes que no valen

La app trata cada imagen con cuidado: solo acepta PNG, JPG o WebP de **menos de 15 MB** y de un tamaño razonable, mira el contenido real del archivo (no la extensión) y la reduce antes de mostrarla. Si una imagen está dañada, cortada, es enorme o no es una imagen aunque se llame `.png`, se usa la portada generada y, al importarla, verás «No se pudo usar esa imagen».

## Espacio

Las portadas cuentan en **Ajustes › Almacenamiento › Portadas**. «Borrar portadas» borra las capturas, las capturas fijadas y las copias reducidas de las imágenes (las importadas habrá que volver a elegirlas). Nunca toca las partidas, los estados ni las imágenes de tu carpeta.
