# Partidas que viajan (iPhone)

Tu partida puede ir del iPhone a Android (o a otro iPhone) y volver, **sin red**: viaja como un archivo. PocketGB nunca la pierde por el camino: antes de cambiar la partida de un juego siempre guarda la que había.

## Enviar a otro dispositivo

En el **detalle del juego › Partida** toca **Enviar a otro dispositivo**. Se abre la hoja de compartir con un archivo `.pgbm` (un *paquete* de PocketGB) que lleva:

- la partida del cartucho (el `.sav`);
- el punto exacto donde lo dejaste (el estado automático), si corresponde a esa partida;
- el nombre del equipo, la fecha y los datos del juego (alias, etiquetas, tiempo jugado e hitos).

Elige **Google Drive**, **AirDrop**, **Guardar en Archivos** o la app que quieras. En el iPhone no hay una carpeta común con Android: el paquete va por la hoja de compartir. En Android, déjalo en `Roms/PocketGB/Intercambio/` y PocketGB lo verá al abrir la biblioteca.

## Recibir una partida

Abre el `.pgbm` desde **Archivos** o **Drive** con **Abrir con › PocketGB** (o, en el detalle del juego, **Importar partida…**). El juego tiene que estar en tu biblioteca: PocketGB lo reconoce por su huella, aunque el archivo se llame distinto.

- Si el paquete **continúa** tu partida (se creó a partir de la que tienes), se instala sin preguntar.
- Si trae el punto exacto, el detalle ofrece **Continuar donde lo dejaste en «equipo»**.
- Si **las dos avanzaron por separado**, o el paquete es más antiguo que la tuya, o parte de una versión que aquí aún no ha llegado, PocketGB **pregunta** con cuál seguir. La otra no se borra: queda en **Momentos** como «Conflicto …» y en **Ajustes › Partidas › Copias apartadas**.

- Si la partida ya es la misma pero el punto para continuar es otro, PocketGB pregunta antes de cambiarlo; el tuyo queda en «Antes de cargar».
- Los datos del juego se **juntan**: etiquetas de los dos, tu alias si tienes uno (si no, el del otro equipo), el tiempo de juego más alto y los hitos de los dos (uno marcado en cualquiera queda marcado).
- Si en el otro equipo se jugó con otra configuración (color, paleta; en GBA, tipo de partida, reloj o BIOS), PocketGB te avisa: cámbiala en los ajustes del juego para continuar justo donde lo dejaste. Tu partida se instala igual.

Antes de instalar nada, la partida actual (y su punto para continuar) pasa a **«Antes de cargar»**, a una copia apartada y a las copias de seguridad. Con el juego abierto (o aún guardando) no se puede importar: ciérralo y espera un momento.

PocketGB **rechaza sin tocar nada** un paquete dañado, de otro juego, de una versión más nueva de la app («Actualiza PocketGB») o sin sus datos. Si la partida del paquete está vacía o no tiene el tamaño de la de tu cartucho, tampoco la instala: tu partida sigue igual y queda además una copia de seguridad.

## Exportar el .sav

**Exportar .sav** comparte la partida tal cual, para otros emuladores. **Guardar paquete en Archivos…** guarda el `.pgbm` donde elijas. Para importar un `.sav` suelto usa **Importar partida…** en el detalle, o ábrelo con PocketGB: tiene que medir exactamente lo que la partida del juego y **siempre** se confirma antes, nombrando el juego (aunque no tenga partida todavía). Con «Abrir con», el juego se busca por el nombre del archivo; si varios juegos se llaman igual, eliges cuál.

## La partida junto al juego (iCloud)

PocketGB recuerda qué partidas escribió él mismo en el `.sav` junto al ROM. Al abrir un juego:

| Lo que encuentra | Qué hace |
|---|---|
| El `.sav` es igual a la partida del iPhone | nada |
| Es una versión que PocketGB escribió o recibió antes (iCloud aún no ha subido la nueva) | sigue con la del iPhone, aparta la del `.sav` y lo vuelve a escribir; un aviso breve lo indica |
| Cambió fuera de PocketGB y la del iPhone no (desde la última que escribió o recibió) | instala la de la carpeta, con copia y aviso |
| Cambió fuera y la del iPhone también | **pregunta** con cuál seguir; la otra queda como «Conflicto» |
| Es la primera vez | gana la más reciente por fecha, con copia de la otra |
| Lo borraste | lo vuelve a crear desde la del iPhone |

Las fechas solo cuentan la primera vez, porque el reloj de otro equipo puede ir desfasado. Si tienes el mismo juego en dos carpetas, cada `.sav` se compara por fecha (y la que pierde se aparta): el historial solo vale para el `.sav` en el que PocketGB escribió. Las **copias en conflicto** que crean iCloud, Drive o Syncthing (`Juego 2.sav`, `Juego (1).sav`, `.sync-conflict-…`) aparecen en **Ajustes › Partidas › Copias en conflicto**. PocketGB nunca las borra; para usar una, ábrela con PocketGB (se reconoce el juego aunque el nombre lleve « 2» o « (1)») o impórtala desde el detalle.

## Estado de la partida

El detalle muestra de dónde viene la partida actual y cuándo: «Partida: este iPhone · hace 5 min» o «Partida: Pixel 8 · hace 2 h».
