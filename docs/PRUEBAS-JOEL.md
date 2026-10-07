# Pruebas manuales para Joel

Nada de esto se ha verificado en dispositivo real (el emulador no cubre audio, mando, TalkBack con gestos ni rendimiento). Marca cada punto al probarlo y anota el resultado. Si algo falla, no sigas jugando con esa partida: haz copia del `.sav`.

## Android

Preparación: `cd android && ./gradlew :app:installDebug` con el teléfono por USB ([07-instalacion-android.md](07-instalacion-android.md)).

| # | Prueba | Resultado esperado |
|---|---|---|
| A1 | Elegir una carpeta SAF real con Rojo y Amarillo | La biblioteca lista ambos juegos con su título; sin errores; al reiniciar la app la carpeta sigue concedida |
| A2 | Abrir Amarillo con «Color en juegos de Game Boy» activado | Se ve en color. Con Rojo, también (paleta de compatibilidad) |
| A3 | Cambiar la paleta en Ajustes › Emulación (global) y después una paleta distinta solo para un juego | La global afecta a los juegos sin ajuste propio; el juego con paleta propia conserva la suya |
| A4 | Jugar 10 min con auriculares y con altavoz | Audio continuo, sin chasquidos ni cortes; sin desfase perceptible |
| A5 | Multitoque: A+B a la vez, deslizar sobre la cruceta, D-pad diagonal | Todas las pulsaciones se registran; el pulgar que resbala no suelta la dirección; háptica al pulsar |
| A6 | Guardar en el juego, cerrar forzado desde el selector de apps (J11), reabrir | La partida está; Continuar retoma donde se guardó |
| A7 | Reinstalar la app (`installDebug` sobre la anterior, no desinstalar) y abrir el juego | Partida intacta |
| A8 | Recuperación desde el espejo: haz copia, desinstala, instala y reconecta la misma carpeta | La partida se recupera desde `<rom>.sav` junto a la ROM |
| A9 | Ajustes › Partidas › restaurar un backup | Pide confirmación, respalda lo actual antes y el juego carga la partida restaurada |
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

## iPhone

| # | Prueba | Resultado esperado |
|---|---|---|
| I1 | D8.1: con un `.sav` más nuevo junto al ROM, abrir el juego desde Continuar y desde Jugar desde el inicio | Continuar retoma el estado automático; Jugar desde el inicio usa la partida del `.sav` más nuevo sin pisarla |
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
