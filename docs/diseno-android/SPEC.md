# PocketGB Android — Especificación de diseño e implementación

**Estado:** aprobada por Joel el 2026-09-30
**Objetivo:** portar a Android la paridad funcional de iOS D1–D8 con una interfaz nativa Material 3 Expressive. La UI del cable virtual queda fuera de esta entrega.

## 1. Alcance y principios

- Aplicación nativa en Kotlin y Jetpack Compose; el núcleo C11 existente se integra mediante NDK/CMake y JNI.
- Un único módulo Gradle `:app`, organizado por capas y paquetes con contratos internos sustituibles.
- Teléfono primero. Navegación, estado y repositorios no dependerán del tamaño de ventana para permitir añadir `NavigationRail` y layouts multipanel sin reescribirlos.
- `minSdk 26`; `compileSdk` y `targetSdk` usan la versión estable más reciente instalada. Kotlin/JVM 17.
- Sin permiso `INTERNET`, telemetría, WebView, frameworks híbridos ni dependencias de runtime ajenas a AndroidX/Jetpack.
- Nunca se incluyen ROMs comerciales, boot ROMs, partidas ni estados en Git. Los tests usan datos sintéticos y las ROMs libres gestionadas por las herramientas existentes.
- La copia local de SRAM es autoritativa. Un fallo remoto nunca puede degradarla y un archivo dudoso nunca se sobrescribe.

## 2. Arquitectura

### 2.1 Paquetes

```text
com.joelbermudez.pocketgb/
  app/       MainActivity, AppState, ciclo de vida
  ui/        tema, navegación, componentes y catálogo visual
  library/   SAF, escaneo, metadatos, búsqueda y preferencias
  emulator/  EmulatorSession, comandos, eventos y wrapper JNI
  video/     SurfaceView y presentación de framebuffer
  audio/     configuración y estado de audio
  input/     multitouch, geometría, háptica y mando físico
  saves/     SRAM, backups, espejo, índice y save states
  settings/  preferencias globales y por juego
```

La separación es lógica dentro de `:app`. No se crearán módulos Gradle adicionales en esta versión.

### 2.2 Propiedad y concurrencia

`EmulatorSession` es el único dueño del handle nativo. El hilo de emulación, el framebuffer escalado, AAudio y su ring SPSC viven en código nativo. Kotlin envía comandos y recibe snapshots/eventos; Compose nunca posee el core ni llama a `pocketgb.h` desde el hilo principal.

Todo acceso a una instancia `gb` ocurre en su hilo nativo, salvo operaciones ejecutadas tras una barrera que confirme que el hilo está aparcado. Pausar, cerrar y pasar a background esperan el flush local de SRAM. El espejo SAF puede terminar después, pero queda registrado para reintento.

Flujo de apertura:

1. Leer la ROM mediante `ContentResolver` en un dispatcher de I/O y rechazar más de 8 MiB.
2. Crear el core, validar cabecera/tamaño y obtener `gb_rom_info`.
3. Recuperar temporales locales y resolver SRAM local frente al espejo.
4. Cargar SRAM válida y crear la sesión.
5. Arrancar el hilo nativo, cebar audio y publicar el primer frame.

### 2.3 JNI

JNI expone una superficie pequeña y tipada: crear/cargar, iniciar, pausar, reanudar, detener, actualizar botones/velocidad/paleta, consultar información, y guardar/cargar SRAM o estado. Los códigos `gb_result` se traducen a errores Kotlin exhaustivos. Los buffers se validan antes de entrar al core y ninguna referencia JNI se conserva sin la propiedad global explícita correspondiente.

## 3. Sistema visual y navegación

### 3.1 Material 3 Expressive

- Material You con color dinámico por defecto en Android 12+.
- Fallback claro/oscuro generado desde una semilla verde inspirada en Game Boy; no se codifican colores directamente en componentes.
- Preferencias: Sistema, Claro u Oscuro, y activar/desactivar color dinámico.
- Superficies tonales opacas, formas Material, tipografía del sistema y roles semánticos de `MaterialTheme`.
- Ripple, stretch overscroll y motion Material nativos. No se reproduce Liquid Glass ni se usa blur decorativo.
- Se usan APIs estables. La API experimental Compose Styles y los layouts experimentales Grid/FlexBox quedan fuera.

### 3.2 Navigation 3

Las rutas son tipos serializables y cada destino superior conserva su propio back stack:

- Biblioteca
- Favoritos
- Ajustes

En teléfono se usa `NavigationBar` con etiquetas siempre visibles. Gameplay es un destino inmersivo que oculta la navegación principal. Predictive back funciona en pantallas normales; una sesión activa no se descarta accidentalmente y muestra primero la pausa cuando corresponda.

El contenedor y las rutas quedan preparados para sustituir la barra por `NavigationRail` y habilitar list-detail en pantallas grandes sin cambiar repositorios, estado de sesión ni argumentos de navegación.

### 3.3 Pantallas

- **Biblioteca:** top app bar, búsqueda, filtros, cuadrícula/lista, continuar jugando, selección de carpeta y estados vacío/cargando/error.
  - **N3a, carril «Continuar jugando»:** solo juegos reanudables (estado automático vigente, ND15) con portada, hasta 5 (`LibraryQuery.continueRail`). Cada tarjeta mide una celda de la cuadrícula (`railMetricsFor` sobre `columnsFor`/`gridCellWidth`, margen 16 dp y separación 12 dp): se ven tantas como columnas, alineadas, con ajuste al soltar (`SnapPosition.Start`); la fila sangra hasta el borde izquierdo y acaba en el margen derecho (sin trozos de tarjeta en reposo). La portada se limita al alto visible con la barra desplegada; con fuente grande, portada a la izquierda y «Continuar» debajo (apiladas solo en vertical con una columna).
  - **N3b, horizontal** (`isLandscapeLibrary`: espacio más ancho que alto, con barra inferior o NavigationRail): sin buscador ni chips arriba; `TopAppBar` con `enterAlwaysScrollBehavior`; título de sección fijado (`stickyHeader`, fondo opaco); barra flotante propia (`LibraryToolbar`: `Surface` en pastilla con `IconButton`/`FilledTonalIconButton`, sin `HorizontalFloatingToolbar` alpha) con Buscar, Filtros, Categorías y Vista/Orden. Cada panel es un `Popup` encima de la barra, alineado a su derecha, de hasta 480 dp con `FilterChip`, y su alto máximo llega hasta debajo del título de sección si se ve y el panel lo taparía (si no, hasta la barra superior); con más opciones se desplaza dentro. Buscar sustituye la barra superior por un campo; la flecha o Atrás la cierran, vacían la búsqueda y la cuadrícula vuelve a su posición. «⋮» conserva escanear y cambiar carpeta. Categorías = carpetas de primer nivel (`LibraryCategory`, «Sin categoría» para la raíz); en vertical se eligen en «⋮». Guía: [biblioteca-android](../guia/biblioteca-android.md).
- **Favoritos:** representación filtrada de la misma fuente y estado vacío propio.
- **Detalle:** portada local, metadatos, checksums, última partida, jugar/continuar, favorito, ajustes por juego y ocultar.
  - **N3a:** `detailLayoutFor` (como `DetailLayout` de iOS): dos columnas si ancho > alto o ≥ 600 dp (imagen a la izquierda, como mucho la mitad del ancho útil y el alto disponible; información con su propio scroll y «Jugar/Continuar» visible sin desplazar; con fuente grande va justo bajo el título); en una columna la imagen ocupa como mucho el 45 % del alto. Proporción de la consola (`RomEntry.screenAspectRatio` por `Console`: 10:9, 3:2 en GBA). «Información técnica» plegable (desplegada de entrada). Favorito/Estados/Ajustes: fila con icono al lado si los textos medidos caben, fila con icono encima si no, columna con fuente grande.
- **Gameplay:** framebuffer, controles, menú, avance rápido y avisos breves.
- **Pausa:** continuar, save states, editar controles y salir; bottom sheet en teléfono vertical y presentación apropiada en horizontal.
- **Ajustes:** Apariencia, Biblioteca, Controles, Pantalla, Emulación, Audio, Almacenamiento, Partidas y Acerca de.
- **Estados:** automático y cuatro slots manuales, con captura, fecha, guardar, cargar y eliminar.

### 3.4 Edge-to-edge y accesibilidad

`MainActivity` habilita edge-to-edge antes de `setContent`. Cada pantalla consume insets una sola vez; las listas los reciben mediante `contentPadding` y la búsqueda queda visible al abrir el IME. Gameplay respeta cutouts y zonas de gesto para sus controles críticos.

Todos los objetivos táctiles miden al menos 48 dp. Se soportan TalkBack, navegación por teclado/mando, fuentes al 200 %, claro/oscuro, contraste suficiente, información no dependiente solo del color y reducción de movimiento. Las etiquetas de navegación siempre son visibles.

## 4. Emulación audiovisual e input

### 4.1 Vídeo

Un `SurfaceView` entrega su `ANativeWindow` al código nativo. El framebuffer RGBA8888 de 160×144 se amplía con vecino más cercano:

- **Entero:** máxima escala entera que quepa, centrada sobre fondo negro.
- **Llenar:** ajuste 10:9 sin suavizado perceptible.

La recreación o pérdida de la superficie no destruye la sesión. El render nunca bloquea el hilo principal.

### 4.2 Audio y pacing

AAudio usa modo low-latency y callback nativo sobre ring buffer SPSC estéreo a 48 kHz. El audio guía el pacing cuando está activo; tras timeouts acotados se cae a reloj. Avance rápido ×2/×4 silencia y usa reloj; volver a ×1 ceba de nuevo el ring. Interrupciones y pérdida de foco pausan la sesión, que no se reanuda sola.

### 4.3 Controles

Una única vista Android gestiona todos los punteros, deslizamiento entre botones, diagonales sin direcciones opuestas y captura del D-pad. La disposición y tamaño se guardan por orientación. Opacidad, visibilidad, estilo de cruceta y háptica siguen las opciones de iOS, adaptadas visualmente a Material.

**Cruceta (N2).** Misma estructura que la de iOS con el tema Material propio (roles tonales del esquema, sin vidrio): la cruz Game Boy es la unión de dos rectángulos redondeados (0,76 W × 0,29 W) dentro de un disco, y las flechas separadas son cuatro círculos de 0,36 W en rombo con iconos Material que escalan con el control. Solo se ilumina el brazo o círculo pulsado (dos en diagonal); el centro de la cruz no se resalta. La interpretación del toque es `DpadSectors`: zona muerta del 30 % con histéresis (sale a 0,24), sectores por modo «Diagonales» (Normales: 8 × 45°; **Reducidas**, por defecto: diagonal a ±15° de los 45° y cardinales a ±30°; Desactivadas: 4 × 90°) con 8° de histéresis angular por dedo (como iOS), y háptica solo cuando se activa una dirección que no estaba activa (↑ → ↑→ vibra; ↑→ → ↑ no; ↑ → nada → ↑ sí). Con flechas separadas manda lo que se ve: un toque dentro de un círculo da su dirección, el mismo dedo la conserva hasta salir 4 dp del círculo, la zona muerta se limita al borde interior de los círculos − 2 dp y fuera de los círculos decide el ángulo. Los marcos de los controles redondos son siempre cuadrados (`half = min(halfW, halfH)`) aunque la zona de controles sea más baja que ancha. El stick de un mando físico conserva sus ocho sectores de 45°. La separación de las flechas (0,7–1,5, por orientación, 1,0 de fábrica) se ajusta en el editor y el marco, la zona táctil y el nodo de TalkBack de la cruceta siguen al grupo (siempre ≥ 48 dp). Guía: [controles-android](../guia/controles-android.md).

Mandos físicos usan `onKeyDown`, `onKeyUp` y `onGenericMotionEvent`; cruceta/stick, A/B, Start/Select y avance rápido son configurables. Las máscaras táctil y física se combinan con OR y después se anulan las direcciones opuestas (↑↓, ←→) tanto entre dedos (`TouchInputEngine.mask`) como entre táctil y mando (`combine_buttons` en `native_session.c`): nunca llegan opuestas al núcleo.

## 5. Biblioteca y persistencia

### 5.1 Biblioteca SAF

La selección usa `ACTION_OPEN_DOCUMENT_TREE` y conserva permisos `READ|WRITE` con `takePersistableUriPermission`. Se recorren la carpeta y hasta cinco niveles de subcarpetas (N1b; antes, uno), solo para `.gb` y `.gbc`; lo que empieza por `.`, `PocketGB/` en la raíz y las carpetas que empiezan por `_` no se escanean (ND11). Permiso revocado, documento remoto no disponible, ROM demasiado grande y cabecera inválida son estados distintos y accionables.

Las ROM se leen para cada apertura y nunca se copian ni modifican. Favoritos, recientes, ocultos, vista, orden, opciones globales y opciones por juego viven en almacenamiento privado y se escriben atómicamente.

### 5.2 SRAM local

La ruta se identifica por SHA-256. La escritura cumple:

1. Si el contenido es idéntico al actual, no escribir ni rotar.
2. Escribir `.sav.tmp` y sincronizar el descriptor.
3. Rotar únicamente backups `.4`→`.5` hasta `.1`→`.2`.
4. Copiar el actual a `.1.tmp`, sincronizarlo y renombrarlo a `.1`, sin retirar el actual.
5. Renombrar `.sav.tmp` sobre `.sav` y sincronizar el directorio cuando el sistema lo permita.
6. Al arrancar, instalar un temporal completo si falta el destino; eliminar temporales inválidos.

Los tamaños válidos se calculan con la información del core, incluido RTC +48/+44. Un tamaño inesperado se conserva en cuarentena y nunca se sobrescribe.

Se guarda tras `gb_sram_dirty` con debounce de un segundo, cada 60 segundos como red de seguridad, y de forma síncrona al pausar, salir, restaurar estado o entrar en background.

### 5.3 Espejo SAF

El `.sav` junto a la ROM es secundario. Se crea una copia de respaldo antes de actualizar y se usa un documento temporal cuando el proveedor lo soporte. Como SAF no promete semántica POSIX, no se declara atómico; cualquier fallo deja intacta la copia local y programa un reintento.

Al abrir se comparan existencia, tamaño, fecha y huellas propias conocidas. La copia válida más reciente puede ganar, pero la perdedora se conserva como backup antes de cargar. Un espejo no disponible o dudoso no se toca. Dos ROMs que resolverían al mismo nombre de espejo lo deshabilitan para evitar compartir partida.

### 5.4 Save states

Cada juego tiene slot automático y cuatro manuales, almacenados localmente con la misma escritura atómica. Guardar incluye una captura local. Al cargar, la sesión debe estar aparcada; después se persiste la SRAM contenida. Si el guardado falla, se restaura el estado previo del core y se informa el error.

## 6. Errores y ciclo de vida

Los errores se representan mediante tipos, no cadenas sueltas: carpeta ausente, permiso revocado, ROM inválida, documento remoto pendiente, save incompatible, fallo de guardado local, fallo de espejo y error de core. Compose traduce cada caso a recuperación concreta.

Un fallo de espejo es una advertencia y permite jugar porque la local ya está segura. Un fallo local impide cerrar limpiamente la sesión hasta reintentar o hasta que Joel confirme una salida con riesgo explícito. `ON_PAUSE` pausa audio/emulación y espera el flush; `ON_STOP` libera audio. Volver a foreground reescanea la biblioteca y deja el juego pausado.

## 7. Hitos

1. **A1 · Fundamentos:** Gradle, Compose, Material 3, Navigation 3, edge-to-edge, tema y catálogo determinista.
2. **A2 · Core y vídeo:** NDK/CMake, JNI, validación, sesión y framebuffer.
3. **A3 · Audio, input y ciclo de vida:** AAudio, pacing, multitouch, háptica, background y velocidad.
4. **A4 · Biblioteca:** SAF, permisos, escaneo, preferencias, búsqueda, filtros y detalle.
5. **A5 · Partidas y estados:** atomicidad local, backups, espejo, restauración y slots.
6. **A6 · Paridad visual:** ajustes, opciones por juego, portadas y todos los estados D1–D8. **Implementado y auditado** (Opus y DeepSeek; respuesta en `docs/auditorias/A6-android-respuesta.md`).
7. **A7 · Robustez:** mando, TalkBack, fuente, contraste, rotación y límites adaptativos. **Implementado** (L1 a L4); pendiente de auditoría y de las pruebas reales (`docs/auditorias/A7-android-evidencia.md`).
8. **A8 · Cierre:** regresión, Release, documentación, prueba real y auditoría conjunta con Claude.

## 8. Verificación y aceptación

- TDD para resolución de saves, atomicidad/recuperación, preferencias, filtros, rutas, geometría de controles, máquina de sesión y traducción JNI.
- Tests JVM para lógica pura; instrumentados para SAF, lifecycle, Compose y accesibilidad.
- Catálogo de capturas deterministas en claro, oscuro y varios colores dinámicos, sin ROM comercial.
- `make -C core test` y sanitizers cuando se toque integración nativa.
- Build Debug y Release por hito. Inspección del manifiesto final para confirmar que no existe `INTERNET`.
- Prueba prolongada de emulación y audio; cero crashes, ANR y pérdida de partidas.
- Validación final en teléfono: carpeta real, Rojo/Amarillo, audio, multitouch, mando, background, cierre forzado, backups, estados y recuperación desde espejo.
- Cada criterio requiere comando o prueba ejecutada y evidencia registrada. La entrega solo se cierra tras la auditoría final con Claude.
