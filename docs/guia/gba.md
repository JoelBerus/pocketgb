# Game Boy Advance en el iPhone

PocketGB juega también los juegos de **Game Boy Advance** (`.gba`) con su propio núcleo. Todo funciona sin red ni cuentas. (En Android: [gba-android.md](gba-android.md).)

## Dónde poner los juegos
Los `.gba` van en la misma carpeta que los de Game Boy, en cualquier subcarpeta hasta 5 niveles:

```
GMRoms/
  Kirby/
    Kirby - Nightmare in Dream Land.gba
    Kirby - Nightmare in Dream Land.sav   ← la partida (la mantiene la app)
  Pokémon/3ª generación/Pokemon Emerald.gba
  gba_bios.bin                            ← opcional: tu BIOS (en la raíz)
```

- Un `.gba` puede medir hasta **32 MiB**.
- Cada juego lleva su chip **GB**, **GBC** o **GBA**. El filtro **GBA** deja solo los de Advance.
- La imagen es más ancha que la de Game Boy (3:2): las capturas, las portadas y las miniaturas de los momentos respetan esa forma.

## La BIOS (opcional)
La BIOS es el pequeño programa de la consola que arranca el juego. **PocketGB nunca la incluye** (es de Nintendo) y no la necesita: si no la hay, emula sus funciones y los juegos funcionan igual.

Si tienes **tu propio volcado**, cópialo como `gba_bios.bin` en la **raíz** de la carpeta de juegos. Solo se usa si es la BIOS oficial (16 KiB y su huella SHA-256); si no, se ignora y se usa la emulada.
- **Ajustes › Emulación › BIOS** dice qué se usa: «Emulada», «BIOS oficial verificada» o «gba_bios.bin no es la BIOS oficial: se usa la emulada».
- En los ajustes de cada juego, **BIOS** puede ser «Global (la tuya si existe)» o «Emulada».

## L y R
En los juegos de GBA aparecen dos botones más, **L** y **R**, en forma de cápsula, arriba a los lados. Se mueven y cambian de tamaño como los demás en **Pausa › Personalizar controles** (ver [Controles](controles.md)). La disposición de GBA se guarda **aparte** de la de Game Boy, una por orientación. Con VoiceOver son dos botones más.

Con un **mando**, los botones superiores izquierdo y derecho son L y R.

## Partidas
Cada juego de GBA guarda en un único `.sav`, compatible con otros emuladores (mGBA, VBA):
- El tipo de memoria (**SRAM** de 32 KiB, **Flash** de 64 o 128 KiB, o **EEPROM** de 512 B u 8 KiB) lo detecta la app.
- Si el cartucho tiene **reloj** (como Pokémon Rubí, Zafiro o Esmeralda), sus 16 bytes van al final del mismo `.sav`.
- Como en Game Boy: la copia que manda está en el iPhone (escritura segura y 5 copias de seguridad) y hay otra junto al ROM. **Continuar**, los momentos y «Enviar a otro dispositivo» funcionan igual.

### Si un juego no guarda bien
En **Ajustes del juego › Game Boy Advance** puedes forzar el **tipo de partida** y el **reloj**. Si lo que fuerzas no coincide con la partida que ya tienes, PocketGB **no la toca**: avisa al abrir el juego y esa sesión no guarda. Vuelve a «Detectado» para recuperarla. Los cambios se aplican la próxima vez que abras el juego, y un momento o estado guardado con otra configuración no se carga (se conserva).
