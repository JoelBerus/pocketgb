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
- **G7-1 y G7-3:** decidir cuando se pruebe el cable.
- Cable: alternar entre juegos frente a pantalla dividida (qué presentación prefieres).
