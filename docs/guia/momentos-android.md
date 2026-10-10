# Momentos y progreso en Android

Un **momento** es una foto completa del juego en un instante: la posición exacta (el estado del emulador), **la partida del cartucho de ese instante**, una miniatura y la configuración con la que jugabas. Sirve para experimentar sin miedo («¿y si capturo al legendario con otra Ball?») y para no perder progreso en juegos que no dejan guardar donde quieres. Los momentos son **de este teléfono**: «Enviar a otro dispositivo» lleva la partida y el punto de «Continuar», no los momentos (ver [viajar-android.md](viajar-android.md)).

## Crear un momento

En la **pausa** del juego toca **Momentos › Nuevo momento**. La app propone un nombre («Momento 3»); cámbialo si quieres y toca **Crear**. Crear un momento **no cambia la partida** ni lo que retoma «Continuar».

## Ver y organizar

Los momentos de un juego están en dos sitios:

- en la pausa (**Momentos**), con el juego abierto;
- en el **detalle del juego › Momentos**, sin abrirlo.

Cada uno muestra su miniatura (con la forma de la pantalla de la consola), nombre, fecha, tiempo jugado, etiquetas y nota. Con **Editar** puedes cambiar el nombre, poner **etiquetas** (separadas por comas), elegir una **colección** («Principal», «Experimentos» o la que escribas) y añadir una **nota**. La lista se agrupa por colección y arriba puedes **filtrar por etiqueta**. **Borrar** pide confirmación; borrar un momento no toca la partida del juego.

## Cargar un momento (importante)

Cargar un momento **cambia también la partida del juego**: vuelve a la del instante en que lo creaste. Por eso:

1. La app pide confirmación y te lo explica.
2. Antes de cargar, guarda tu posición actual en **«Antes de cargar»** (las 3 últimas; son aparte de las 5 copias de seguridad de la partida).
3. La partida anterior queda además en las **copias de seguridad** (Ajustes › Partidas).
4. La posición de «Continuar» no se toca al cargar.

Desde la pausa el momento se carga al momento. Desde el detalle, **Cargar** abre el juego y lo carga en pausa.

Si el momento se creó con otra configuración (por ejemplo, otro modelo de Game Boy), la confirmación te avisa: puede que no cargue hasta que vuelvas a ponerla en los ajustes del juego.

## Me equivoqué: «Recuperar»

En **Momentos › Antes de cargar** toca **Recuperar** en la entrada más reciente:

- **En la pausa** vuelves a la posición y a la partida de antes de cargar. Lo de ahora también se guarda en «Antes de cargar», así que puedes ir y volver.
- **En el detalle** (sin abrir el juego) se instala la partida de antes; la de ahora queda en «Antes de cargar» y en las copias de seguridad. La posición exacta solo se recupera desde la pausa.

## Un momento que ya no carga

Cada momento guarda aparte **la partida del cartucho** de ese instante. Si algún día su estado no carga (una versión nueva del emulador, un archivo dañado), en el **detalle › Momentos** toca **Recuperar su partida**: se instala esa partida y la de ahora queda en «Antes de cargar» y en las copias. Los momentos que vienen de las **ranuras antiguas** (Ranura 1–4, Rescate) no tienen esa copia aparte.

## Ranuras antiguas

Las ranuras 1–4 y el estado de **Rescate** se convierten solas en momentos la primera vez que abres el juego (o su pantalla de Momentos), sin perder nada. Si había un rescate de una salida con fallo de guardado, la app te avisa al abrir el juego.

## Cuándo no se puede

Con el juego abierto (o aún guardando en segundo plano) no se puede recuperar una partida desde el detalle: la app te pide que esperes. Así nunca hay dos cosas escribiendo la misma partida a la vez.

## Progreso

En el **detalle del juego** verás **Progreso**:

- **Tiempo de juego** (solo cuenta con el juego corriendo: ni en pausa ni en segundo plano), **sesiones**, **primera** y **última vez**.
- **Hitos** opcionales con casillas. En **Ajustes del juego › Progreso** eliges una plantilla (**Pokémon: 8 medallas + Liga** o **Libre**), añades los tuyos y activas **Mostrar porcentaje** (desactivado de serie).
- En los **Pokémon** de Game Boy y Game Boy Color (Rojo, Azul, Amarillo, Oro, Plata y Cristal, versiones no japonesas), un panel **«Leído de la partida»** con el jugador, medallas, Pokédex, tiempo y dinero. Solo lee la partida guardada; si la partida no cuadra, no muestra nada. Si tienes la plantilla Pokémon, te propone **marcar las medallas** que ha leído (tú decides).

No hay copias periódicas automáticas: los momentos los creas tú.
