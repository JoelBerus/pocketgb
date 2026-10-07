# Continuar, jugar desde el inicio y renombrar

«Continuar», «Jugar desde el inicio» y «Renombrar» funcionan igual en Android y en el iPhone. Los menús que se citan abajo son los de Android; en el iPhone están en el detalle del juego y en el menú que sale al mantener pulsada su portada.

## Tu partida y el estado automático

Cada juego tiene dos cosas distintas:

- **La partida** es lo que el juego guarda cuando tú guardas dentro del juego (en Pokémon, «Guardar» en el menú). Vive en el archivo `.sav`. PocketGB la copia de forma segura y guarda copias de respaldo.
- **El estado automático** es una foto del momento exacto en que saliste del juego o lo mandaste a segundo plano: el sitio donde estabas, la música y hasta el diálogo abierto. PocketGB lo guarda solo, siempre **después** de poner a salvo la partida.

## «Continuar»

Si hay un estado automático que sigue valiendo, el botón principal dice **Continuar**. Al pulsarlo vuelves al momento exacto en que lo dejaste, aunque no hubieras guardado dentro del juego.

PocketGB solo usa el estado automático si corresponde a tu partida actual. No lo usa si:

- el juego guardó la partida **después** de ese momento (por ejemplo, la abriste desde otro sitio o restauraste una copia en Ajustes › Partidas);
- la partida cambió por otro medio (llegó un `.sav` más nuevo junto al ROM);
- cambiaste el color o el modelo de consola de ese juego;
- el estado está dañado;
- tu partida no se pudo leer o tiene un tamaño que el juego no acepta (en ese caso PocketGB ni siquiera lo compara y lo deja como está).

En esos casos verás **«No se pudo continuar»** con el motivo y el botón **Jugar desde el inicio**. No se pierde nada: PocketGB no escribe tu partida al comprobarlo. En Android, si el estado ya no corresponde a tu partida, lo aparta en vez de borrarlo, y nunca pisa uno apartado antes.

## «Jugar desde el inicio»

Abre el juego con **tu partida guardada**, sin el estado automático: el juego arranca desde su pantalla de título y cargas tu partida como en la consola. Sirve si prefieres empezar desde tu último guardado. Aparece junto a «Continuar» en el detalle del juego y en el menú que sale al mantener pulsada su portada.

Si no hay estado automático, el botón dice simplemente **Jugar** y hace lo mismo.

## Renombrar un juego

Puedes ponerle a cada juego el nombre que quieras (por ejemplo, «Rojo de Joel» en vez de «POKEMON RED»):

- En el **detalle** del juego: menú ⋮ de la barra superior › **Renombrar**.
- **Manteniendo pulsada** su portada en la biblioteca o en Favoritos › **Renombrar**.
- En **Ajustes del juego** › Nombre › **Renombrar**.

Detalles:

- Hasta 80 caracteres. Si dejas el campo vacío, vuelve el nombre del cartucho.
- El nombre nuevo se ve en la biblioteca, en «Continuar jugando», en Favoritos, en el detalle y en el menú de pausa.
- El buscador encuentra el juego por su nombre nuevo, por el del cartucho y por el nombre del archivo.
- **No cambia ningún archivo**: el ROM, su `.sav` y sus estados siguen igual. Si mueves el ROM a otra carpeta, el nombre se conserva (en Android lo reconoce al volver a la app; si no puede, en cuanto lo abres: ver [Organizar tus juegos en carpetas](carpetas-android.md)).
- El nombre se guarda en este dispositivo. Si usas otro teléfono, renómbralo también allí.
