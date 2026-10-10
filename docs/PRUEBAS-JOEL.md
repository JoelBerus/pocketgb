# Pruebas manuales para Joel

Nada de esto se ha verificado en dispositivo real (el emulador no cubre audio, mando, TalkBack con gestos ni rendimiento). Marca cada punto al probarlo y anota el resultado. Si algo falla, no sigas jugando con esa partida: haz copia del `.sav`.

## Android

Preparación: `cd android && ./gradlew :app:installDebug` con el teléfono por USB ([07-instalacion-android.md](07-instalacion-android.md)). La versión Debug compila el código nativo con `-O2`, así que sirve también para las pruebas de rendimiento (60 fps, ms/frame).

| # | Prueba | Resultado esperado |
|---|---|---|
| A1 | Elegir una carpeta SAF real con Rojo y Amarillo | La biblioteca lista ambos juegos con su título; sin errores; al reiniciar la app la carpeta sigue concedida |
| A2 | Abrir Amarillo con «Color en juegos de Game Boy» activado | Se ve en color. Con Rojo, también (paleta de compatibilidad) |
| A3 | Cambiar la paleta en Ajustes › Emulación (global) y después una paleta distinta solo para un juego | La global afecta a los juegos sin ajuste propio; el juego con paleta propia conserva la suya |
| A4 | Jugar 10 min con auriculares y con altavoz | Audio continuo, sin chasquidos ni cortes; sin desfase perceptible |
| A5 | Multitoque: A+B a la vez, deslizar sobre la cruceta, D-pad diagonal | Todas las pulsaciones se registran; el pulgar que resbala no suelta la dirección; háptica al pulsar |
| A6 | Guardar en el juego, cerrar forzado desde el selector de apps (J11), reabrir | La partida está; Continuar retoma donde se guardó | ✅ **Hecha** (Joel, 2026-10-08): bien.
| A7 | Reinstalar la app (`installDebug` sobre la anterior, no desinstalar) y abrir el juego | Partida intacta |
| A8 | Recuperación desde el espejo: haz copia, desinstala, instala y reconecta la misma carpeta | La partida se recupera desde `<rom>.sav` junto a la ROM | ✅ **Hecha** (Joel, 2026-10-08): bien.
| A9 | Ajustes › Partidas › restaurar un backup | Pide confirmación, respalda lo actual antes y el juego carga la partida restaurada | ✅ **Hecha** (Joel, 2026-10-08): bien.
| A10 | Mando Bluetooth: conectar y jugar; probar el mapeo por posición (A/B según la posición física), hat y stick izquierdo | Funciona sin tocar la pantalla; los opuestos no se activan a la vez |
| A11 | Con mando: R1 | Velocidad rápida mientras se mantiene o según el ajuste |
| A12 | Con mando: Mode/Select y L1 | Abre el menú de pausa (L1 si así está asignado) |
| A13 | Ajustes › Controles: ocultar controles táctiles con mando conectado | Los controles desaparecen con mando y reaparecen al desconectarlo |
| A14 | TalkBack activo en la pantalla de juego | Cada control (cruceta, A, B, Start, Select, «Abrir menú») es un elemento propio con etiqueta clara y tamaño suficiente |
| A15 | Fuente del sistema al 200 % | Biblioteca, detalle, «Continuar» y ajustes legibles, sin textos cortados ni solapados |
| A16 | Contraste alto del sistema | Controles sólidos y colores con contraste suficiente |
| A17 | Girar el teléfono varias veces jugando | No se pausa el juego, no se corta el audio, no se pierde la partida |
| A18 | Tablet o plegable (si tienes) | Barra lateral (rail) en pantalla ancha; sin corte de contenido; área segura respetada con recorte de cámara |

### Android A9 · renombrar y continuar ([guía](guia/partidas-continuar-y-renombrar.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| A9-1 | Rojo: jugar sin guardar en el juego unos minutos, Salir y pulsar «Continuar» | Vuelve al sitio exacto (mismo mapa, misma música) |
| A9-2 | Igual, pero saliendo con el botón de inicio y cerrando la app desde el selector | «Continuar» vuelve al sitio en que se dejó al pasar a segundo plano |
| A9-3 | Guardar en el juego, salir y volver a abrir con «Jugar desde el inicio» | Pantalla de título; al cargar, la partida guardada |
| A9-4 | Con un `.sav` más nuevo junto al ROM (copiado de otro equipo), pulsar «Continuar» | Aviso «No se pudo continuar» y «Jugar desde el inicio» abre el `.sav` nuevo, sin pisarlo |
| A9-5 | Renombrar Rojo («Rojo de Joel») desde el detalle, el menú de la portada y Ajustes del juego | El nombre se ve en biblioteca, Continuar jugando, Favoritos, detalle y pausa; el buscador lo encuentra; el archivo de la carpeta no cambia |
| A9-6 | Vaciar el nombre | Vuelve «POKEMON RED» |

### Android N1 · carpetas e identidad ([guía](guia/carpetas-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N1-1 | En Drive, crear `Roms/Pokémon/1ª generación/` y mover ahí Rojo **con su `.sav`**; volver a la app | Rojo sigue favorito, con su nombre, su portada y «Continuar»; el detalle dice «Pokémon › 1ª generación · …»; no sale como «Nuevo» |
| N1-2 | Abrir Rojo tras moverlo y guardar en el juego | El `.sav` nuevo aparece en la carpeta nueva; en la vieja no queda nada |
| N1-3 | Crear `_Revisar/` y `PocketGB/` en `Roms` con un juego dentro de cada una | Esos juegos no salen en la biblioteca |
| N1-4 | Primer escaneo de la carpeta con todas las subcarpetas (con conexión) | Termina; anotar cuánto tarda. `adb logcat -s PocketGB/Library` muestra cuántas consultas hizo |
| N1-5 | Dejar dos copias del mismo juego en carpetas distintas y abrir el detalle de ambas | «Duplicado» en las dos tarjetas y «También en: …» en el detalle |
| N1-6 | Con dos copias cuyos `.sav` son distintos, abrir la copia con el `.sav` más nuevo | Se juega esa partida; en Ajustes › Partidas › «Apartadas» queda la otra y «Restaurar» la recupera |

### Android N4 · categorías, etiquetas e inicio ([guía](guia/categorias-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N4-1 | Con `Roms/Pokémon/1ª generación/…` y otras carpetas en Drive, abrir la biblioteca | Inicio con «Continuar jugando», Favoritos y una estantería por carpeta de primer nivel; «Ver todo» abre la categoría con sus migas y subcategorías |
| N4-2 | En una categoría, cambiar a lista; salir y volver a entrar; girar el teléfono | Esa categoría sigue en lista (las demás no); al girar sigue en la misma pantalla |
| N4-3 | Ajustes del juego de Rojo › Categoría › Cambiar › escribir `Favoritas` › Mostrar aquí | Estantería «Favoritas» con Rojo e insignia «Movido en la app»; en Drive el archivo sigue en su carpeta; «Volver a su carpeta» lo deshace |
| N4-4 | Añadir las etiquetas `rpg` y `pendiente` a dos juegos; Filtros › Etiquetas › rpg; buscar «pend» | Solo salen los etiquetados; la búsqueda los encuentra |
| N4-5 | Ajustes › Biblioteca › Inicio: fijar una categoría, ocultar otra y quitar la fila de Favoritos | El inicio cambia al momento; la oculta sigue en «Todos los juegos» y en Categorías |
| N4-6 | Mover en Drive un juego que estaba en una categoría virtual y con etiquetas; volver a la app y abrir su detalle | Sigue en su categoría virtual y con sus etiquetas |

### N8 · Game Boy Advance en Android (teléfono de Joel, APK Debug)
| # | Prueba | Resultado esperado |
|---|---|---|
| N8-1 | Con `Roms/Kirby/Kirby - Nightmare in Dream Land.gba` en Drive, abrir la biblioteca y tocar el filtro **GBA** | Kirby aparece con el chip «GBA» y su portada 3:2 recortada en la tarjeta; el filtro deja solo los `.gba`; el detalle muestra la imagen 3:2, «Tipo de partida: … (detectado)», el código del juego y el reloj. Anotar el tipo detectado |
| N8-2 | Rendimiento: `adb shell am start -n com.joelbermudez.pocketgb/.MainActivity --es debug gba-bench --es library "Kirby - Nightmare in Dream Land.gba"` y luego `adb logcat -d -s PocketGBBench` | Una línea `GBA-BENCH … núcleo=X ms/frame sesión×4=Y fps`: X muy por debajo de 16,7 ms y Y cerca de 239 (el tope a ×4). Copiar la línea a la evidencia de N8 |
| N8-3 | Jugar Kirby al menos 30 min en vertical y en horizontal | 60 fps sin cortes ni deriva de audio; L y R táctiles responden (cápsulas arriba), sin entradas fantasma |
| N8-4 | Con mando: L1 y R1 en Kirby; L2 y R2; botón Guía | L1/R1 son L y R del juego; L2 abre el menú y R2 cambia la velocidad; Guía abre el menú. En un juego de Game Boy, L1 sigue siendo el menú y R1 la velocidad |
| N8-5 | Guardar en Kirby, forzar el cierre (deslizar la app en recientes) y volver a abrir | La partida está; en Drive aparece `Kirby - Nightmare in Dream Land.sav` junto al ROM, del tamaño del tipo detectado en N8-1 |
| N8-6 | Salir de Kirby y tocar «Continuar» | Retoma justo donde lo dejaste |
| N8-7 | Ajustes del juego de Kirby › Tipo de partida › «Flash 64 KiB»; abrir el juego; volver a «Detectado» | Aviso «Partida distinta de los ajustes del juego» y la partida intacta; con «Detectado» vuelve a cargar |
| N8-8 | (Opcional) Copiar tu propio volcado como `gba_bios.bin` en la raíz de `Roms/` | Ajustes › Emulación › BIOS dice «BIOS oficial verificada» y el arranque muestra el logo de la consola; un archivo que no es la BIOS oficial se ignora (emulada) |

## iPhone

| # | Prueba | Resultado esperado |
|---|---|---|
| I1 | D8.1: con un `.sav` más nuevo junto al ROM, abrir el juego desde Continuar y desde Jugar desde el inicio | Continuar retoma el estado automático; Jugar desde el inicio usa la partida del `.sav` más nuevo sin pisarla | ✅ **Hecha** (Joel, 2026-10-08): bien.
| I2 | Icono de la app y pantalla de launch | Icono nuevo y launch con el glifo, sin parpadeos |
| I3 | GBA: Kirby durante al menos 30 min | Sin cortes, sin deriva de audio |
| I4 | GBA: L y R | Responden; sin entradas fantasma |
| I5 | GBA: audio, y HUD DEBUG | 60 fps estables en el HUD; underruns en 0 |
| I6 | GBA: cierre forzado y reabrir | La partida está |
| I7 | Cable M9: Rojo y Amarillo conectados | Se reconocen y se abre el intercambio |
| I8 | Cable M9: evolución por intercambio de Kadabra | Evoluciona en ambos lados sin cuelgues |
| I9 | Cable M9: audio | Suena el juego visible y cambia al alternar |
| I10 | Cable M9: mandar la app a segundo plano durante el cable y volver | La conexión sigue o se recupera sin perder partidas |

### Decisiones pendientes de Joel
- **GBA (vinculado a la prueba de Kirby, I3–I6):** G7-1: aprobar el descarte del hallazgo; G7-3: confirmar el RTC dentro del `.sav` (en lugar de un `.rtc` aparte). Ambas son de GBA, no del cable.
- **Cable M9:** alternar entre juegos con miniatura (provisional) frente a pantalla dividida; mismo ROM sin batería permitido en los dos lados; el lado 0 es el juego del detalle.
- **Android, decisiones provisionales K1–K20 y R1–R16** (no son teclas: son las decisiones numeradas de los planes). K1–K20 de A6 ([A6-plan](diseno-android/A6-plan.md)): sin interruptor de silencio, «Llenar» en vertical, paleta aplicada en la próxima apertura, portadas y carril «Continuar jugando». R1–R16 de A7 ([A7-plan](diseno-android/A7-plan.md)): mapeo Xbox por posición, R1 = velocidad, Mode/L1 = menú, `configChanges`, lista-detalle desactivada. Tabla completa: [ESTADO](ESTADO.md) §«Decisiones provisionales pendientes de ratificar por Joel».
- **Android A8:** los literales de la interfaz ya están extraídos a `strings.xml` (51 claves); solo queda `LibraryModels.kt:75`. Decidir si se extrae o se acepta.
- **PR a `main`:** aprobar la PR de `cierre-integracion`.
