# Partidas que viajan (Android)

Tu partida puede ir del teléfono Android al iPhone y volver, sin internet: viaja como un archivo. Todo está en el **detalle del juego**, en el menú **⋮** de arriba a la derecha.

## Enviar a otro dispositivo

**⋮ › Enviar a otro dispositivo** guarda un paquete `.pgbm` en la carpeta **`PocketGB/Intercambio/`**, dentro de la carpeta de tu biblioteca (en tu caso, `Roms` de Google Drive). El paquete lleva:

- la partida (`.sav`);
- el **estado automático** si es el de esa misma partida, para seguir en el sitio exacto;
- el nombre de este teléfono, la fecha y los datos del juego (nombre que le pusiste, etiquetas, hitos y tiempo jugado).

En el iPhone, abre el `.pgbm` desde Archivos o Google Drive con **«Abrir con PocketGB»**.

Cuando el iPhone te devuelve una partida, déjala en esa misma carpeta `PocketGB/Intercambio/`. Al abrir la biblioteca, PocketGB te avisa: **«Partida recibida: hay una partida de iPhone para Rojo»**. Pulsa **Importar** (o **Ahora no**; el archivo sigue en la carpeta y puedes importarlo después desde el detalle). PocketGB nunca borra nada de esa carpeta: límpiala tú cuando quieras.

## Continuar donde lo dejaste en el otro equipo

Si el paquete trae el estado automático de su partida, al importarlo verás **«Continuar donde lo dejaste en iPhone de Joel»**. Te lleva al momento exacto en que saliste del juego en el otro equipo. Si eliges «Ahora no», el botón del detalle dice **«Continuar donde lo dejaste en iPhone de Joel»** y hace lo mismo más tarde, mientras no juegues aquí.

## De dónde viene tu partida

En el detalle, la casilla **Partida** te dice de dónde viene la que tienes ahora: **«iPhone de Joel · hace 2 h»** si llegó del iPhone y aún no la has jugado aquí, o **«Este teléfono · hace 10 min»** si la última vez se jugó en el Android.

## Compartir, guardar e importar a mano

- **Compartir partida…**: el `.pgbm` por la hoja de compartir de Android (Drive, correo, Bluetooth…).
- **Guardar partida en…**: elige tú dónde guardar el `.pgbm`.
- **Exportar .sav…**: la partida en crudo, la que entienden otros emuladores.
- **Importar partida…**: elige un `.pgbm` o un `.sav`. También puedes abrir un `.pgbm` o un `.sav` desde Drive o Archivos con **Abrir con › PocketGB**: PocketGB lo reconoce por su contenido y busca el juego solo.

El juego tiene que estar **cerrado** (no abierto ni en pausa) para importar.

## Qué pasa al importar

PocketGB compara la partida que llega con la tuya:

| Situación | Qué hace |
|---|---|
| No tienes partida de ese juego | La instala |
| Es la misma que ya tienes | Nada |
| Parte de la tuya (la jugaste en el otro equipo después de recibirla) | La instala; la tuya queda en las copias de seguridad |
| Es una que este teléfono ya tuvo (más vieja que la tuya) | Te pregunta **«Ya tuviste esta partida»**; la que no elijas queda en Ajustes › Partidas |
| Las dos cambiaron por separado | Te pregunta **«¿Con qué partida sigues?»**: la que no elijas queda como momento **«Conflicto · …»** (Momentos › Recuperar la partida) y en Ajustes › Partidas |

Nunca se pierde nada: **antes de instalar, la partida que tenías (con su punto de «Continuar») pasa a «Antes de importar»** en Momentos, a una copia apartada y a las copias de seguridad (Ajustes › Partidas). Si el paquete trae el punto de «Continuar» y tu partida no cambia, te pregunta antes de sustituir el tuyo; si eliges **Mantener el mío**, el del paquete no se pierde: queda en Momentos como **«Conflicto · …»**.

Un `.sav` suelto siempre se confirma: **«¿Importar este .sav? Se usará como partida de «Rojo»»**. Si lo abres con **Abrir con › PocketGB**, se busca el juego que se llama exactamente como el archivo; si no hay ninguno, se prueba sin la marca de copia (`Rojo (1).sav` vale para `Rojo.gb`, pero `Tetris 2.sav` es de `Tetris 2.gb` si lo tienes). Si hay varios con ese nombre, eliges.

El alias, las etiquetas, los hitos y el tiempo de juego del otro equipo **se suman** a los tuyos: se juntan las etiquetas y los hitos (un hito marcado en cualquiera de los dos queda marcado), se conserva tu alias si ya tenías uno y el tiempo de juego es el mayor de los dos. Si el otro equipo jugaba con otra configuración (color de Game Boy, tipo de partida, reloj o BIOS), la partida y el sitio exacto se importan igual y el aviso te dice qué ajuste del juego cambiar (por ejemplo, «el color de Game Boy») para continuar justo donde lo dejaste. Si no lo cambias y el sitio exacto no carga, no pasa nada: tu partida queda intacta y se juega desde ella; el sitio exacto se conserva por si cambias el ajuste después.

Puedes importar la partida de un juego que aún no has abierto en este teléfono: PocketGB mira siempre su cabecera para saber qué tamaño de partida le corresponde.

PocketGB **no toca tu partida** y te avisa si el archivo:

- trae una partida **vacía** o de **otro tamaño** que el de ese juego (deja además una copia de la tuya);
- está dañado o cortado (una descarga incompleta);
- es de otro juego;
- viene de una **versión más nueva** de PocketGB («actualiza la app»).

## La carpeta junto al ROM

Si el `.sav` que hay junto al ROM cambia por fuera (otro equipo que escribió en la misma carpeta), al abrir el juego:

- si tu partida no había cambiado desde la última vez que PocketGB escribió ese `.sav`, se usa la de fuera y te avisa; la tuya queda en las copias;
- si las dos cambiaron, **te pregunta antes de abrir** con cuál sigues (**Seguir con la de este teléfono**, **Usar la del otro equipo** o **Cancelar**); hasta que eliges no se toca nada. La otra queda como momento **«Conflicto · …»**, en las copias y apartada en Ajustes › Partidas;
- si el `.sav` es una versión anterior que ya escribió PocketGB (por ejemplo, la restauraste en Drive), se sigue con la de este teléfono y esa versión queda apartada en Ajustes › Partidas, con un aviso;
- si es el `.sav` de otra copia del mismo juego en otra carpeta, gana el más nuevo y el otro queda apartado.

Abrir y cerrar un juego sin jugar no cuenta como cambio: si después el otro equipo vuelve a jugar, su partida se instala sin preguntar.

PocketGB decide por el **contenido** de las partidas, no por la hora, así que un reloj mal puesto en un equipo no hace ganar a una partida vieja.

Si la nube crea copias como `Rojo 2.sav`, `Rojo (1).sav`, `Rojo (Joel's conflicted copy …).sav` o «copia en conflicto», PocketGB las enseña en **Ajustes › Partidas › Copias en conflicto del proveedor**, en cuanto escanea la biblioteca (aunque no hayas abierto el juego desde entonces). No las borra nunca; si una es la buena, impórtala con **Importar partida…**.
