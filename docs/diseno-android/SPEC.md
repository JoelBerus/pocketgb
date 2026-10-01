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
- **Favoritos:** representación filtrada de la misma fuente y estado vacío propio.
- **Detalle:** portada local, metadatos, checksums, última partida, jugar/continuar, favorito, ajustes por juego y ocultar.
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

Mandos físicos usan `onKeyDown`, `onKeyUp` y `onGenericMotionEvent`; cruceta/stick, A/B, Start/Select y avance rápido son configurables. Las máscaras táctil y física se combinan con OR.

## 5. Biblioteca y persistencia

### 5.1 Biblioteca SAF

La selección usa `ACTION_OPEN_DOCUMENT_TREE` y conserva permisos `READ|WRITE` con `takePersistableUriPermission`. Se recorren la carpeta y un nivel de subcarpetas, solo para `.gb` y `.gbc`. Permiso revocado, documento remoto no disponible, ROM demasiado grande y cabecera inválida son estados distintos y accionables.

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
6. **A6 · Paridad visual:** ajustes, opciones por juego, portadas y todos los estados D1–D8.
7. **A7 · Robustez:** mando, TalkBack, fuente, contraste, rotación y límites adaptativos.
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
