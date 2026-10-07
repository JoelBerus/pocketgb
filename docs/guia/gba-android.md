# Game Boy Advance en Android

PocketGB para Android juega también los juegos de **Game Boy Advance** (`.gba`) con su propio núcleo (`gba/`), igual que el iPhone. Esta guía explica dónde poner los juegos, qué hace la BIOS opcional, cómo se usan L y R (en pantalla y con mando) y cómo se guardan las partidas. Todo funciona sin red ni cuentas.

## Dónde poner los juegos

Los `.gba` van en la misma carpeta que los `.gb` y `.gbc` (la que eliges en la app; en el teléfono de Joel, `Roms/` de Google Drive), en cualquier subcarpeta hasta 5 niveles:

```
Roms/
  Kirby/
    Kirby - Nightmare in Dream Land.gba
    Kirby - Nightmare in Dream Land.sav     ← la partida (la mantiene la app)
  Pokémon/3ª generación/Pokemon Emerald.gba
  gba_bios.bin                             ← opcional: tu BIOS (en la raíz)
```

- Un `.gba` puede medir hasta **32 MiB** (los de Game Boy, hasta 8 MiB). Uno más grande sale en la biblioteca con «Supera los 32 MiB…».
- La app lee la cabecera del cartucho (título, código del juego y el byte fijo 0x96). Si no la tiene, sale como «No tiene una cabecera de Game Boy Advance válida».
- En la biblioteca cada juego lleva su chip **GB**, **GBC** o **GBA**. El filtro **GBA** (en vertical, debajo del buscador; en horizontal, en el panel de filtros) deja solo los de Advance. También puedes buscar «gba» o «advance».

## La BIOS (opcional)

La BIOS es el pequeño programa de la consola que arranca el juego y que los juegos usan para algunas operaciones. **PocketGB nunca la incluye** (es de Nintendo) y no la necesita: si no la hay, emula sus funciones y los juegos funcionan igual.

Si tienes **tu propio volcado** de la BIOS de tu GBA, puedes copiarlo como `gba_bios.bin` en la **raíz** de la carpeta de juegos (no en una subcarpeta). La app solo la usa si es la BIOS oficial: comprueba que mide 16 KiB y su huella SHA-256. Un archivo que no lo sea se ignora y se sigue usando la emulada; nunca llega al núcleo.

- **Ajustes › Emulación › BIOS** dice qué se está usando: «Emulada (sin gba_bios.bin…)», «BIOS oficial verificada» o «gba_bios.bin no es la BIOS oficial: se usa la emulada».
- En los ajustes de cada juego, **BIOS** puede ser «Global (la tuya si existe)» o «Emulada» (para ese juego no se usa ni se lee).

## L y R

En los juegos de GBA aparecen dos botones más, **L** y **R**, en forma de cápsula:

- **Vertical:** arriba de la zona de controles, L sobre la cruceta y R sobre A y B.
- **Horizontal:** en los márgenes a los lados de la imagen, por debajo de los botones de pausa y velocidad; la cruceta es más pequeña y A, B, Start y Select van en los laterales, porque la imagen 3:2 es más ancha que la de Game Boy.

Se pueden mover y cambiar de tamaño como los demás desde **Pausa › Personalizar controles**. La disposición de GBA se guarda **aparte** de la de Game Boy y, como siempre, una por orientación: el editor lo dice en su título («Controles GBA · Vertical»). «Restablecer» vuelve a la disposición de fábrica de GBA.

Con **TalkBack**, L y R son dos botones más («Botón L», «Botón R») que se pulsan con un doble toque.

## Con mando

En los juegos de GBA, los botones superiores del mando son los de la consola, como en el iPhone:

| Mando | Game Boy | Game Boy Advance |
|---|---|---|
| L1 (botón superior izquierdo) | Menú de pausa | **L** |
| R1 (botón superior derecho) | Avance rápido (×1, ×2, ×4) | **R** |
| L2 (gatillo izquierdo) | — | Menú de pausa |
| R2 (gatillo derecho) | — | Avance rápido |
| Guía / Mode | Menú de pausa | Menú de pausa |
| Botón derecho / inferior | A / B | A / B |
| Start / Select (Menu / Options) | Start / Select | Start / Select |

Si has cambiado el mapeo en **Ajustes › Controles › Botones del mando**, en GBA L1 y R1 siguen siendo siempre L y R, y lo que les hubieras asignado pasa a L2 y R2 si están libres. Esa misma pantalla muestra cómo queda en GBA.

## Partidas

Cada juego de GBA guarda en un único archivo `.sav`, igual que en el iPhone y compatible con otros emuladores (mGBA, VBA):

- El tipo de memoria del cartucho (**SRAM** de 32 KiB, **Flash** de 64 o 128 KiB, o **EEPROM** de 512 B u 8 KiB) lo detecta la app. Una EEPROM empieza como 512 B y pasa a 8 KiB cuando el juego lo confirma; en ese momento la app guarda la partida con su tamaño nuevo.
- Si el cartucho tiene **reloj** (RTC, como Pokémon Rubí, Zafiro o Esmeralda), sus 16 bytes van al final del mismo `.sav`.
- Como en Game Boy: la copia de verdad está dentro de la app (escritura atómica, con 5 copias de seguridad) y la app mantiene una copia `<nombre del ROM>.sav` junto al ROM en tu carpeta. Cerrar la app a la fuerza nunca deja una partida a medias.
- **Continuar** retoma el estado automático exacto también en GBA, y los estados guardados y las portadas son capturas de 240×160.

### Si un juego no guarda bien

En **Ajustes del juego › Partida, reloj y BIOS** puedes forzar el **tipo de partida** (Sin partida, SRAM 32 KiB, Flash 64 KiB, Flash 128 KiB, EEPROM 512 B, EEPROM 8 KiB) y el **reloj** (Con reloj, Sin reloj). «Detectado (…)» dice lo que encontró la app la última vez que abriste el juego sin forzar nada.

Si lo que fuerzas no coincide con la partida que ya tienes, PocketGB **no la toca**: avisa al abrir el juego («Partida distinta de los ajustes del juego») y esa sesión no guarda. Vuelve a «Detectado» para recuperarla. Los cambios se aplican la próxima vez que abras el juego, y un estado guardado con otra configuración no se carga (se conserva: «Continuar» lo explica y ofrece «Jugar desde el inicio»).
