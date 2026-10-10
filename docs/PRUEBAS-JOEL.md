# Pruebas manuales para Joel

> **2026-10-10 · Joel:** hizo las pruebas pendientes del nivel N en los dos teléfonos (N7-1 Rojo iPhone → Android → iPhone con continuación exacta, N7-2 bandeja de Intercambio con Drive real, N8 Kirby en Android, biblioteca reorganizada y guía) y **todas fueron bien**.

Lo que no lleva ✅ no se ha verificado en dispositivo real (el emulador y el simulador no cubren audio, mando, TalkBack o VoiceOver con gestos, rendimiento, Google Drive ni iCloud reales). Marca cada punto al probarlo y anota el resultado. Si algo falla, no sigas jugando con esa partida: haz copia del `.sav`.

Consolidado en N9 (2026-10-09) con lo que dejaron pendiente A9 y N1–N8 en sus evidencias (`auditorias/*-evidencia.md`). **Empieza por las dos de N7** (son las que piden datos reales en los dos equipos) y después sigue por plataforma.

## Primero: N7, partidas que viajan entre iPhone y Android

Preparación (las dos):
- Los dos equipos con la versión de esta rama: Android con `./gradlew :app:installDebug`, iPhone desde Xcode.
- Rojo en las dos bibliotecas: Android en `Roms` de Google Drive y el iPhone en iCloud `GMRoms/`. No hay carpeta común (ND2); PocketGB reconoce el juego por su huella aunque el archivo se llame distinto.
- **Antes de empezar**, copia el `.sav` de Rojo de los dos lados (fuera de las carpetas de la app) y anota su SHA-256: en el Mac `shasum -a 256 "Pokemon Red.sav"`.
- Configuración del juego igual en los dos (modelo, color y paleta); si no, la partida viaja pero el sitio exacto no (se avisa).

### N7-1 · Rojo: iPhone → Android → iPhone, con continuación exacta (prueba de aceptación de N7)
| Paso | Qué haces | Qué debe pasar |
|---|---|---|
| 1 | **iPhone:** juega Rojo, avanza hasta un sitio reconocible (mapa y música) y **sal** del juego | El detalle dice «Partida: este iPhone · hace N min» |
| 2 | **iPhone:** detalle › Partida › **Enviar a otro dispositivo** › Google Drive › guárdalo en `Roms/PocketGB/Intercambio/` | Se sube un `.pgbm`. Anota cómo lo dejaste en esa carpeta (la hoja de compartir de Drive deja elegir la carpeta) |
| 3 | **Android:** abre la app (la biblioteca escanea y mira `PocketGB/Intercambio/`) | Aviso **«Partida recibida»** de iPhone para Rojo, con **Importar** y **Ahora no**. Con «Ahora no» el archivo se queda y se puede importar luego desde el detalle (⋮ › Importar partida…) |
| 4 | **Android:** **Importar** | Si Android ya tenía su propia partida de Rojo, distinta, pregunta **«¿Con qué partida sigues?»**: elige la del iPhone (la de Android queda como momento «Conflicto · …» y en Ajustes › Partidas). Si no, se instala sin preguntar. En los dos casos ofrece **«Continuar donde lo dejaste en iPhone de Joel»**. La partida anterior queda en Momentos › «Antes de importar» y en Ajustes › Partidas |
| 5 | **Android:** **Continuar** | Mismo mapa, misma posición y misma música que al salir en el iPhone. El detalle dice «Partida: iPhone de Joel · hace N…» |
| 6 | **Android:** juega un poco, guarda en el juego y **sal**; detalle › ⋮ › **Enviar a otro dispositivo** | Aparece `Roms/PocketGB/Intercambio/<juego> · <equipo> · <fecha>.pgbm` en Drive. El detalle dice «Este teléfono · hace N min» |
| 7 | **iPhone:** abre ese `.pgbm` desde Archivos o Drive con **Abrir con › PocketGB** | Se instala **sin preguntar** (el paquete continúa la partida que envió el iPhone) y ofrece «Continuar donde lo dejaste en <tu Android>». Si pregunta «¿Con qué partida sigues?», algo falló: anótalo |
| 8 | **iPhone:** **Continuar** | El sitio exacto donde saliste en Android |
| 9 | Comprobación final | SHA-256 del `.sav` de Rojo en el iPhone (junto al ROM en `GMRoms`) = el que escribió Android junto al ROM en `Roms` tras el paso 6. En Ajustes › Partidas de los dos equipos están las copias anteriores: nada se pisó sin copia |

### N7-2 · Bandeja `PocketGB/Intercambio/` con Google Drive real (Android)
| Paso | Qué haces | Qué debe pasar |
|---|---|---|
| 1 | Sin `PocketGB/` en `Roms`, detalle de un juego › ⋮ › **Enviar a otro dispositivo** | Drive crea `Roms/PocketGB/Intercambio/` y deja el `.pgbm` (el emulador solo lo prueba con un proveedor de pruebas) |
| 2 | Vuelve a la biblioteca | No se ofrece el paquete que acabas de enviar; `PocketGB/` no sale como categoría |
| 3 | Deja en esa carpeta un `.pgbm` del iPhone (N7-1, paso 2) y abre la app | Aviso «Partida recibida». Anota cuánto tarda Drive en mostrarlo |
| 4 | **Ahora no**, cierra y vuelve a abrir la app | No vuelve a preguntar por ese paquete; el archivo sigue en la carpeta (PocketGB nunca borra nada de ahí) |

### N7 · resto (Android «A», iPhone «I»)
| # | Prueba | Resultado esperado |
|---|---|---|
| N7-A3 | Juega Rojo en Android y, por separado, en el iPhone; importa en Android el paquete del iPhone | **«¿Con qué partida sigues?»**; la que no elijas queda como momento «Conflicto · …», en las copias y en Ajustes › Partidas › Apartadas |
| N7-A4 | Importa otra vez un paquete más viejo que tu partida | **«Ya tuviste esta partida»**; lo no elegido queda en Ajustes › Partidas |
| N7-A5 | Abre un `Rojo (1).sav` con **Abrir con › PocketGB** | Siempre confirma: «¿Importar este .sav? Se usará como partida de «Rojo»». Si varios juegos se llaman así, eliges. Un `.sav` de otro tamaño no se instala y deja copia |
| N7-A6 | Con la app cerrada, cambia el `.sav` junto al ROM desde fuera (otro equipo en la misma carpeta) | Si tu partida no cambió: usa la de fuera y avisa. Si cambiaron las dos: pregunta **antes de abrir** (Seguir con la de este teléfono / Usar la del otro equipo / Cancelar); Cancelar no escribe nada |
| N7-A7 | Restaura en Drive una versión anterior del `.sav` (historial de versiones) | Sigue la del teléfono; esa versión queda apartada en Ajustes › Partidas y se avisa sin bloquear |
| N7-A8 | Abre y cierra Rojo sin jugar; después el iPhone juega y te envía un paquete | Se instala sin preguntar (ND20 b) |
| N7-A9 | Deja `Rojo 2.sav`, `Rojo (1).sav` o una «copia en conflicto» junto al ROM | Salen en Ajustes › Partidas › **Copias en conflicto del proveedor**; nunca se borran |
| N7-A10 | Importa un `.pgbm` cortado, de otro juego o de una versión más nueva | Rechazo con aviso («actualiza la app» en el último caso); la partida no cambia |
| N7-A11 | Con alias y etiquetas distintos en los dos equipos, importa | Etiquetas de los dos, tu alias si lo tienes, el tiempo de juego mayor y los hitos de los dos (ND20 i) |
| N7-I3 | iPhone: el `.sav` junto al ROM cambió por fuera y la del iPhone también; abre el juego | Pregunta con tres botones (Seguir con la de este iPhone / Usar la del otro equipo / Cancelar); la no elegida queda como «Conflicto» |
| N7-I4 | iPhone: abre un juego mientras iCloud aún no ha subido el `.sav` nuevo | Sigue con la del iPhone, aparta la de la carpeta, la reescribe y avisa |
| N7-I5 | iPhone: borra el `.sav` junto al ROM y abre el juego | Se vuelve a crear desde la partida del iPhone |
| N7-I6 | iPhone: `.sav` suelto (Importar partida… o Abrir con) | Siempre confirma nombrando el juego; exige el tamaño exacto |
| N7-I7 | iPhone: paquete con la misma partida y otro punto de continuar | Pregunta antes de cambiarlo; el tuyo queda en «Antes de cargar» |
| N7-I8 | Pon mal la hora de un equipo y repite N7-1 | Una partida vieja nunca gana por la fecha |

Capturas de referencia: Android `n7-details-menu`, `n7-details-status`, `n7-imported-continue`, `n7-choose`, `n7-inbox`, `n7-warning-divergence`, `n7-saves-conflicts`; iPhone `n7-detail-save`, `n7-divergence`, `n7-import-prompt`, `n7-import-done`, `n7-settings-conflicts`.

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
| A9-7 | Oro, Plata o Cristal (con reloj): jugar, salir y «Continuar»; mirar la hora del juego | Retoma en el sitio y el reloj del juego sigue bien (solo se probó con un ROM sintético) |
| A9-8 | Con TalkBack: renombrar desde el detalle y usar «Continuar» / «Jugar desde el inicio» | Cada botón y el campo del nombre se anuncian con su función; el teclado no tapa el campo |

### Android N1 · carpetas e identidad ([guía](guia/carpetas-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N1-1 | En Drive, crear `Roms/Pokémon/1ª generación/` y mover ahí Rojo **con su `.sav`**; volver a la app | Rojo sigue favorito, con su nombre, su portada y «Continuar»; el detalle dice «Pokémon › 1ª generación · …»; no sale como «Nuevo» |
| N1-2 | Abrir Rojo tras moverlo y guardar en el juego | El `.sav` nuevo aparece en la carpeta nueva; en la vieja no queda nada |
| N1-3 | Crear `_Revisar/` y `PocketGB/` en `Roms` con un juego dentro de cada una | Esos juegos no salen en la biblioteca |
| N1-4 | Primer escaneo de la carpeta con todas las subcarpetas (con conexión) | Termina; anotar cuánto tarda. `adb logcat -s PocketGB/Library` muestra cuántas consultas hizo |
| N1-5 | Dejar dos copias del mismo juego en carpetas distintas y abrir el detalle de ambas | «Duplicado» en las dos tarjetas y «También en: …» en el detalle |
| N1-6 | Con dos copias cuyos `.sav` son distintos, abrir la copia con el `.sav` más nuevo | Se juega esa partida; en Ajustes › Partidas › «Apartadas» queda la otra y «Restaurar» la recupera |
| N1-7 | Activar el modo avión a mitad del primer escaneo de Drive y volver a la app | Ningún juego pierde su favorito, nombre ni categoría; al volver la conexión, el siguiente escaneo completa la lista |

### Android N2 · cruceta ([guía](guia/controles-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N2-1 | Ajustes › Controles › Diagonales: probar **Reducidas** (de serie), **Normales** y **Desactivadas** en Rojo | Con Reducidas, pulsar arriba enciende solo arriba aunque el pulgar tiemble; con Desactivadas, solo 4 direcciones |
| N2-2 | Háptica de la cruceta | Vibra al entrar una dirección nueva (arriba → arriba+derecha sí; arriba+derecha → arriba no) |
| N2-3 | Cruceta **Flechas separadas**; en el juego, Pausa › Personalizar controles › tocar la cruceta › segunda fila **−/+** y arrastrar | Cada flecha responde en toda su superficie; la separación va del 70 al 150 %; el grupo se mueve entero |
| N2-4 | Un dedo en arriba y, con un mando, abajo a la vez | No se pulsa ninguna de las dos |
| N2-5 | TalkBack en el juego: la cruceta | Ofrece las acciones Arriba, Abajo, Izquierda y Derecha |

### Android N3 · biblioteca y detalle adaptables ([guía](guia/biblioteca-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N3-1 | Biblioteca en horizontal con tu carpeta real: bajar, usar los botones flotantes (Buscar, Filtros, Categorías, Vista) y el teclado | Los paneles se abren hacia arriba sin tapar el título de la sección; al subir vuelve la barra de arriba |
| N3-2 | «Continuar jugando» | Solo juegos que se pueden retomar, con o sin portada (ND15, ND17) |
| N3-3 | Detalle en horizontal con un título largo | Imagen entera a la izquierda y **Jugar/Continuar** visible sin bajar |
| N3-4 | TalkBack en la biblioteca en horizontal | Los botones flotantes se anuncian por su nombre y se pueden pulsar |

### Android N4 · categorías, etiquetas e inicio ([guía](guia/categorias-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N4-1 | Con `Roms/Pokémon/1ª generación/…` y otras carpetas en Drive, abrir la biblioteca | Inicio con «Continuar jugando», Favoritos y una estantería por carpeta de primer nivel; «Ver todo» abre la categoría con sus migas y subcategorías |
| N4-2 | En una categoría, cambiar a lista; salir y volver a entrar; girar el teléfono | Esa categoría sigue en lista (las demás no); al girar sigue en la misma pantalla |
| N4-3 | Ajustes del juego de Rojo › Categoría › Cambiar › escribir `Favoritas` › Mostrar aquí | Estantería «Favoritas» con Rojo e insignia «Movido en la app»; en Drive el archivo sigue en su carpeta; «Volver a su carpeta» lo deshace |
| N4-4 | Añadir las etiquetas `rpg` y `pendiente` a dos juegos; Filtros › Etiquetas › rpg; buscar «pend» | Solo salen los etiquetados; la búsqueda los encuentra |
| N4-5 | Ajustes › Biblioteca › Inicio: fijar una categoría, ocultar otra y quitar la fila de Favoritos | El inicio cambia al momento; la oculta sigue en «Todos los juegos» y en Categorías |
| N4-6 | Mover en Drive un juego que estaba en una categoría virtual y con etiquetas; volver a la app y abrir su detalle | Sigue en su categoría virtual y con sus etiquetas |
| N4-7 | Con todas tus categorías reales, bajar por el inicio y abrir varias categorías | Fluido, sin saltos; con TalkBack cada estantería se anuncia con su nombre y cuántos juegos tiene |

### Android N5 · portadas ([guía](guia/portadas-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N5-1 | Copiar `Pokemon Red.png` junto a `Pokemon Red.gb` en Drive y «Volver a escanear» | Rojo usa esa imagen (la primera vez la app la descarga de Drive) |
| N5-2 | Ajustes del juego › Portada › Cambiar › «Elegir de Fotos» y «Elegir archivo» | Se ve la elegida; la app no pide permisos; la foto original no cambia |
| N5-3 | En el juego, Pausa › **Usar como portada**; después, en Portada, «Soltar captura fijada» | La captura fijada se usa como portada y al soltarla vuelve la anterior |
| N5-4 | Ajustes › Biblioteca › Portadas: «Preferir capturas»; después Ajustes › Almacenamiento › «Borrar portadas» | Cambia la fuente de todas; al borrar, las imágenes de la carpeta siguen ahí y partidas y estados no cambian |
| N5-5 | Una imagen dañada o un `.txt` renombrado a `.png` junto a un juego | Portada generada y aviso «No se pudo usar esa imagen»; la app no se cierra |

### Android N6 · momentos y progreso ([guía](guia/momentos-android.md))

| # | Prueba | Resultado esperado |
|---|---|---|
| N6-1 | Pausa › Momentos › Nuevo momento (dos veces); editar nombre, etiquetas, colección y nota; filtrar por etiqueta | Crear un momento no cambia la partida ni «Continuar» |
| N6-2 | Cargar un momento y después Momentos › Antes de cargar › **Recuperar** | Vuelven la posición y la partida de antes; puedes ir y volver |
| N6-3 | Desde el detalle (sin abrir el juego), Momentos › Recuperar su partida | Se instala esa partida; la de ahora queda en «Antes de cargar» y en las copias |
| N6-4 | Un juego con ranuras antiguas (1–4, Rescate) | Se convierten solas en momentos al abrirlo, sin perder ninguna |
| N6-5 | Tiempo de juego: jugar 5 min, pausar 2 min, mandar la app a segundo plano 2 min | Suma solo los 5 min; tras un cierre forzado se pierden como mucho 30 s |
| N6-6 | **Lector Pokémon con tus partidas reales** de Rojo/Amarillo (y Oro/Plata/Cristal si los tienes): detalle › Progreso | «Leído de la partida» con tu nombre, medallas, Pokédex, tiempo y dinero correctos; con la plantilla Pokémon propone marcar las medallas. Solo se probó con partidas sintéticas: anota cualquier dato raro (acentos, `?`) |

### Android N8 · Game Boy Advance (teléfono de Joel, APK Debug; [guía](guia/gba-android.md))
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
| N8-9 | (Si tienes Rubí, Zafiro o Esmeralda) Jugar, guardar, salir y volver más tarde | El reloj del juego avanza con la hora real; la partida se guarda con su reloj al final del `.sav` |

### Android N9 · guía y consejos (Ajustes › Guía)

| # | Prueba | Resultado esperado |
|---|---|---|
| N9-1 | En modo avión, Ajustes › **Guía**: abrir cada sección, buscar «intercambio» y «bios», tocar un resultado y un enlace a otra sección | Todo funciona sin conexión; el resultado abre la sección en su apartado y el enlace abre la otra sección |
| N9-2 | Guía con la fuente del sistema al 200 % y con TalkBack | Nada cortado ni fuera de la pantalla (las tablas se ven como fichas); TalkBack anuncia los títulos como encabezados y los enlaces como enlaces |
| N9-3 | Consejos: Pausa › (bajo Momentos), ajustes del juego › Categoría, detalle del juego (bajo «Partida»), Ajustes › Controles con «Flechas separadas» | Una tarjeta en cada sitio; **Entendido** la quita para siempre (también tras cerrar la app); Ajustes › Guía › «Volver a mostrar los consejos» las devuelve |

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

### iPhone N1–N6 (las de N7 están arriba)

| # | Prueba | Resultado esperado |
|---|---|---|
| IN1-1 | Primer arranque con la versión nueva sobre tu biblioteca de iCloud `GMRoms/` ([guía](guia/carpetas.md)) | Favoritos, nombres, ajustes de cada juego y portadas se conservan (migración de ruta a huella) |
| IN1-2 | Mover un juego **con su `.sav`** a otra carpeta desde Archivos; también con un juego aún «en la nube» | Conserva favorito, nombre, partida y portada; un juego sin descargar no pierde nada |
| IN1-3 | Dos copias del mismo juego: ocultar o marcar favorito una | Afecta a las dos («Duplicado»); una sola partida en el iPhone |
| IN2-1 | Cruceta con «Reducidas», háptica y flechas nuevas; con luz real sobre el vidrio ([guía](guia/controles.md)) | Pulsar arriba enciende solo arriba; sin bordes raros; la flecha pulsada se distingue |
| IN2-2 | VoiceOver en los controles del juego | Cada control con su nombre y la cruceta con sus cuatro direcciones |
| IN3-1 | Biblioteca en horizontal: grupo de herramientas, paneles y búsqueda en la barra ([guía](guia/biblioteca.md)) | El título de la sección no se pierde sobre una captura clara; los paneles no lo tapan |
| IN3-2 | Información técnica de juegos reales (Rojo, Cristal con reloj, un GBA con Flash) y de uno sin descargar de iCloud | Datos correctos; el que no está descargado lo dice sin forzar la descarga |
| IN4-1 | Inicio con tus categorías reales, «Ver todo», migas, categoría virtual y etiquetas ([guía](guia/categorias.md)) | Como N4-1…N4-6 de Android |
| IN4-2 | Cerrar la app desde el multitarea dentro de una categoría y volver a abrirla | Vuelve a la misma pantalla |
| IN5-1 | Portada desde Fotos (JPEG), desde Archivos y una HEIC ([guía](guia/portadas.md)) | JPEG/PNG/WebP sí; HEIC da «Formato no compatible: usa PNG, JPEG o WebP» (ND18) |
| IN5-2 | Imagen junto al ROM en iCloud, captura fijada desde la pausa y Ajustes › Biblioteca › Portadas | Como N5-1…N5-4 de Android |
| IN6-1 | Momentos y progreso como N6-1…N6-5 de Android ([guía](guia/momentos.md)) | En el iPhone los momentos están en el centro de ajustes del juego; no hay «Rescate» |
| IN6-2 | Lector Pokémon con tus `.sav` reales (Rojo/Amarillo, Oro/Plata/Cristal) | Datos correctos; anota cualquier rareza |

### Decisiones pendientes de Joel
- **GBA (vinculado a la prueba de Kirby, I3–I6):** G7-1: aprobar el descarte del hallazgo; G7-3: confirmar el RTC dentro del `.sav` (en lugar de un `.rtc` aparte). Ambas son de GBA, no del cable.
- **Cable M9:** alternar entre juegos con miniatura (provisional) frente a pantalla dividida; mismo ROM sin batería permitido en los dos lados; el lado 0 es el juego del detalle.
- **Android, decisiones provisionales K1–K20 y R1–R16** (no son teclas: son las decisiones numeradas de los planes). K1–K20 de A6 ([A6-plan](diseno-android/A6-plan.md)): sin interruptor de silencio, «Llenar» en vertical, paleta aplicada en la próxima apertura y portadas (K10, el carril solo con portada, ya está sustituida por ND17). R1–R16 de A7 ([A7-plan](diseno-android/A7-plan.md)): mapeo Xbox por posición, R1 = velocidad, Mode/L1 = menú, `configChanges`, lista-detalle desactivada. Tabla completa: [ESTADO](ESTADO.md) §«Decisiones provisionales pendientes de ratificar por Joel».
- **Android A8:** los literales de la interfaz ya están extraídos a `strings.xml` (51 claves); solo queda `LibraryModels.kt:75`. Decidir si se extrae o se acepta.
- **N7 · ND20** (escrita por Claude el 2026-10-09; puedes cambiarla): comportamiento común de las partidas que viajan en las dos apps (preguntar antes de escribir en una divergencia, fusión de metadatos, copias apartadas…). Es la que más conviene ratificar **antes** de usar N7 con partidas que te importan. Incluye que un `.sav` que PocketGB ya escribió, restaurado con fecha nueva, ya no gana por fecha.
- **N5 Android:** decisiones 1–9 de su evidencia ([N5-android-evidencia](auditorias/N5-android-evidencia.md)); la de K10 quedó sustituida por ND17.
- **N9 Android:** la guía de la app muestra las guías Android de `docs/guia/` (no las del iPhone) y los consejos son 4: Momentos en la pausa, Categoría, Enviar a otro dispositivo y Flechas separadas.
- **N9 · tus carpetas** (Drive `Roms` e iCloud `GMRoms`): antes de reorganizarlas, de apartar duplicados en `_Revisar/` o de descargar portadas de libretro-thumbnails (ND4), PocketGB te pedirá permiso explícito y hará inventario y SHA-256 de cada `.sav` antes y después.
- **PR a `main`:** al cerrar N9, aprobar la PR de `siguiente-nivel` (la de `cierre-integracion`, PR #18, ya se fusionó).
