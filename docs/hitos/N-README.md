# PocketGB — Plan «siguiente nivel» (A9 y N1–N9)

> Estado: **aprobado para ejecutar** (v2, 2026-10-07): borrador auditado por Opus ([N0-plan-opus](../auditorias/N0-plan-opus.md)) y DeepSeek ([N0-plan-deepseek](../auditorias/N0-plan-deepseek.md)), ambos APROBAR CON CAMBIOS; respuesta en [N0-plan-respuesta](../auditorias/N0-plan-respuesta.md). Decisiones de Joel en §2.
> Base: `main` en `51416b4` (PR #18 fusionada el 2026-10-07). Rama de integración: `siguiente-nivel`; cada lote en su worktree `nN-<plataforma>-<nombre>` y se fusiona en `siguiente-nivel` tras su auditoría. Al final, una PR a `main`.
> Ejecutor: Claude (Opus) orquesta y revisa; subagentes implementan por lotes con TDD; auditoría Opus por hito (DeepSeek como segunda opinión barata). Reglas: [AGENTS.md](../../AGENTS.md).

## 0. Resumen
Joel pidió llevar la app al siguiente nivel antes de cerrar la versión actual:
1. **Organización:** carpetas anidadas como categorías, un inicio útil, portadas propias, ajustes de juego más completos y renombrar en Android.
2. **Partidas:** «momentos» con nombre y etiquetas para experimentar sin perder progreso, medir el avance (con lectura automática para Pokémon), exportar y compartir, y continuar la misma partida entre iPhone y Android.
3. **Pulido:** biblioteca y detalle en horizontal y en distintos tamaños, título legible sobre capturas claras, carril «Continuar» de Android e información del juego equivalente en ambas apps.
4. **Controles y consola:** la cruceta solo reacciona en la dirección pulsada; nuevos bordes y flechas en iOS; la cruceta de Android con la estructura de la de iOS; flechas separadas ajustables. GBA en Android.

Principios: **la carpeta es la verdad** (su estructura define las categorías), **la partida local manda** (regla dura 6) y **la app no tiene red** (regla dura 5): compartir y mover archivos entre equipos lo hacen el sistema y el proveedor de la carpeta, nunca código de red propio. Cada hito entrega su **sección de la guía** y su consejo en la app (no se deja para el final).

## 1. Trazabilidad: petición de Joel → hito
| # | Petición | Hito |
|---|---|---|
| 1 | iOS: en horizontal, al hacer scroll, el título se pierde sobre una captura blanca | N3a |
| 2 | Medir el avance con etiquetas de progreso opcionales | N6 |
| 3 | Etiquetas en los guardados para experimentar y no perder progreso donde el juego no deja guardar | N6 |
| 4 | Exportar partidas, organizarlas y categorizarlas (opcional), con guía | N6, N7, guía en cada hito |
| 5 | Android: «Continuar jugando» sin cuadrícula correcta | N3a |
| 6 | Biblioteca en horizontal: sin buscador visible; a la derecha, botones flotantes (buscar, filtros, categorías…) que se abren hacia arriba sin tapar el título de sección. En iOS, búsqueda también en la barra | N3b (+ categorías en N4) |
| 7 | Detalle responsive (imagen gigante en horizontal), tamaños distintos, tablets si se puede | N3a |
| 8 | Ajustes del juego: etiquetas, mover de categoría, etc. | N4 (centro de ajustes), N5, N6 |
| 9 | iOS: borde extraño en controles y cruceta | N2 |
| 10 | iOS: mejores flechas en la cruceta | N2 |
| 11 | Android: cruceta (ambos modos) con la estructura de iOS, cada una con su tema | N2 |
| 12 | Flechas separadas: tamaño, separación y ubicación | N2 |
| 13 | Al pulsar arriba parece que se pulsa todo | N2 |
| 14 | Android no deja renombrar | A9 |
| 15 | Info del juego equivalente en ambas apps sin tocar la distribución de controles en juego | N3a, A9 |
| 16 | Android: falta GBA | N8 |
| 17 | Organización por carpetas definida y documentada con ejemplo; arreglar la estructura del Drive | N1b, N4, N9 |
| 18 | Subcategorías; ajustar categorías y vistas en la app; inicio más útil | N4 |
| 19 | Portadas propias; elegir portada o captura; ejemplo con los juegos del Drive | N5, N9 |
| 20 | Guardar en la nube además del dispositivo; continuar del iPhone en Android; compartir o exportar una partida | N7 |

## 2. Decisiones (Joel, 2026-10-07)
| # | Decisión |
|---|---|
| ND1 | PR #18 fusionada en `main` antes de empezar (`51416b4`). |
| ND2 | **Carpetas:** Android usa la carpeta `Roms` de Google Drive (Joel la eligió en el selector y funciona, aunque la documentación pública dice que Drive no ofrece árbol; se verificará la autoridad del URI con el teléfono). El iPhone sigue en iCloud `GMRoms/`: el selector de iOS no muestra Google Drive (prueba de Joel). **No hay carpeta común**, así que la partida viaja con «Enviar a otro dispositivo» (N7). Se mantiene la decisión «biblioteca del iPhone en iCloud». |
| ND3 | «Mover de categoría» **virtual**: la app lo recuerda sin tocar archivos. |
| ND4 | Portadas de los juegos de Joel desde libretro-thumbnails, **solo en sus carpetas** (Drive e iCloud), nunca en el repo, con permiso antes de descargar. |
| ND5 | Lector automático de progreso de Pokémon Gen 1/2: **sí**. |
| ND6 | Continuación exacta entre equipos: **sí**. Exige que Android retome el estado automático como iOS (A9; **cambia la decisión J8** de A5). |
| ND7 | Instantáneas periódicas automáticas: **no**. Se mantiene el anillo de seguridad «Antes de cargar», que exige la regla dura 6. |
| ND8 | iPad fuera de alcance (`TARGETED_DEVICE_FAMILY` = iPhone). Layouts por espacio disponible; Android se prueba en tablet y plegable de emulador. |
| ND9 | Joel usa los controles en modo **Siempre**: el desvanecido no es la causa de la sensación de «todo reacciona». |
| ND10 | Flechas separadas: **se mueve el grupo** y se ajustan tamaño y separación (siempre alineadas). |
| ND11 | Nombres reservados en la carpeta: lo que empieza por `.` se ignora; `PocketGB/` (visible) es de la app (intercambio y exportados); las carpetas que empiezan por `_` quedan **apartadas** (no se escanean; p. ej. `_Revisar/`). Joel puede cambiarlo antes de N1b. |
| ND12 | Momentos, ajustes de inicio y metadatos son **por dispositivo**; viajan dentro del paquete de N7. |
| ND13 | Política de estados del núcleo: una subida de versión de estado **debe seguir cargando la versión anterior** (migración), y cada momento guarda también la RAM del cartucho del instante, para que la partida sea recuperable aunque el estado ya no cargue. |

Decisiones provisionales anteriores que este plan toca (Joel las ratifica al cerrar cada hito): **J8** (Android «Continuar» = solo SRAM; cambia en A9), **K9** (portada = último fotograma; pasa a ser una fuente más en N5), **K10** (carril solo con portada; se revisa en N3a/N5), **R14** (lista-detalle desactivada; N3a añade detalle a dos columnas sin activarla), **G7-3** (RTC dentro del `.sav`; base de N8).

## 3. Arquitectura transversal
### 3.1 Identidad y metadatos
Hoy los favoritos y `lastPlayed` (ambas apps), el mapa ruta → huella y los ajustes por juego de iOS van **por ruta relativa**: reorganizar carpetas los perdería. Partidas, estados y portadas ya van por huella (SHA-256 del ROM).
- Todos los metadatos pasan a la **huella**; la ruta solo es clave provisional hasta conocerla, con migración automática.
- **Caché (ruta, tamaño, fecha) → huella.** La huella se calcula en segundo plano solo para archivos locales o ya descargados (cola cancelable; nunca fuerza una descarga). Un archivo movido sin huella conocida recupera sus metadatos en cuanto se calcula.
- **Duplicados:** misma huella en varias rutas → insignia «Duplicado» y «También en: …».
- **Robustez de preferencias en iOS:** si el JSON no se decodifica, se aparta como `.corrupt` y se avisa, como Android (hoy arranca vacío y la siguiente escritura lo pisa).

### 3.2 Carpetas = categorías
- Escaneo recursivo hasta `MAX_FOLDER_DEPTH` (constante, 5) con tope de entradas y los nombres reservados de ND11.
- Cada juego tiene `folderPath`. El primer nivel es la categoría y los siguientes, subcategorías. La raíz es «Sin categoría».
- Convención para el usuario (`docs/11-biblioteca-carpetas.md` + `LEEME-PocketGB.txt`):
```
Roms/                                   ← la carpeta que eliges en la app
  Pokémon/                              ← categoría
    1ª generación/                      ← subcategorías (hasta 5 niveles)
      Pokemon Red.gb
      Pokemon Red.sav                   ← partida (la mantiene la app)
      Pokemon Red.png                   ← portada opcional, mismo nombre que el ROM
  Kirby/
    Kirby - Nightmare in Dream Land.gba
  gba_bios.bin                          ← opcional, BIOS de tu GBA (raíz)
  PocketGB/                             ← de la app: Intercambio/, Exportados/
  _Revisar/                             ← apartada: no se escanea
```
- No se leen: `.zip` (descomprimir antes), archivos de más de 8 MiB (GB) o 32 MiB (GBA), ni carpetas ocultas o reservadas.

### 3.3 Momentos y progreso
- **Momento** = estado del núcleo + **RAM del cartucho del instante** + configuración (modelo DMG/CGB/compatibilidad, paleta; en GBA, tipo de partida, RTC y BIOS) + nombre, etiquetas, colección opcional («Principal», «Experimentos»…), nota, fecha, tiempo jugado y miniatura con la proporción de la consola.
- Las ranuras 1–4 (y RESCUE en Android) se migran a momentos. El estado automático no cambia.
- **Cargar un momento cambia también la partida del juego** (el estado incluye la RAM del cartucho y se vuelca al `.sav`). Por eso hay confirmación explícita y texto de guía, y antes de cargar se guarda el estado actual en el anillo «Antes de cargar» (3 entradas, fuera de la rotación de 5 backups) con «Recuperar» en un toque. Se unifica el comportamiento del AUTO en las dos apps: cargar no lo pisa.
- **Exclusión por huella:** importar, cargar un momento o instalar una partida que llega de otro equipo solo ocurre sin sesión abierta o aparcada de esa huella (Android: `FingerprintOwnership`; iOS: equivalente nuevo).
- **Tiempo de juego:** solo cuenta con el juego corriendo.
- **Lector Pokémon** (ND5): función C pura en `core/` (`pgb_progress_read`) que identifica los juegos oficiales por **título de la cabecera + destino no japonés (`0x14A` = 1) + idioma no coreano**, y solo muestra datos si las validaciones internas de la partida cuadran (checksum, bytes 99/127 de la 2.ª generación, rangos de dinero, horas, minutos y segundos, nombre terminado). Soporta Gen 1 internacional (EN/ES/FR/DE/IT; el Amarillo europeo lleva `POKEMON YELAPS` + letra de idioma en la cabecera) y Oro/Plata/Cristal internacional. Los offsets salen de fuentes documentales (pret evaluado con un script, Data Crystal, PKHeX: hechos, no código; Bulbapedia no estuvo accesible); el código es propio y cita sus fuentes. **Enmienda tras la auditoría de N6-C (H2):** el plan original decía «por título de cabecera y checksum global» y «los hacks no se leen»; no se usa el checksum global del ROM. **Riesgo asumido:** un hack que conserve título, destino y disposición oficial de la partida, con su checksum interno correcto (p. ej. uno de Cristal que no cambie la RAM de guardado), sí muestra datos; es de solo lectura y solo informativo. Un hack con otra disposición da «sin datos» por el checksum (es lo esperable de Prism y Epic Gold, que no se han probado). Detalle en [03-core-spec](../03-core-spec.md) §Lector de progreso Pokémon.

### 3.4 Partidas que viajan (N7)
- **Linaje** sobre el historial que ya existe de escrituras propias del espejo (`mirror-history.json` en iOS, equivalente en Android), acotado a los últimos N sha:

| Situación al abrir | Acción |
|---|---|
| Espejo = local | nada |
| Espejo = una escritura nuestra anterior | gana la local y se reescribe el espejo (como hoy) |
| Espejo desconocido y la local no cambió desde nuestra última escritura | cambio externo: se instala con backup y aviso |
| Espejo desconocido y la local sí cambió | **divergencia**: se pregunta; la otra queda como momento «Conflicto …» y en backup |
| Sin historial (primera vez) | regla actual por fecha, con backup |
| Espejo borrado | se recrea desde la local (documentado) |
| Copias en conflicto del proveedor (`X 2.sav`, `X (1).sav`, `.sync-conflict-…`, «conflicted copy») | se listan como candidatas en Ajustes › Partidas; nunca se borran |

- **Paquete `.pgbm`** (formato propio documentado: mágico, versión, longitudes acotadas y CRC-32; parser en C en `core/` con fuzzer). Lleva la partida (`.sav`), el estado automático si es válido, el sha y el sha base, el equipo de origen, la versión del núcleo, la configuración (§3.3) y los metadatos del juego (alias, etiquetas, hitos y tiempo de juego). Sirve para exportar momentos y para «Enviar a otro dispositivo».
- **Enviar a otro dispositivo:** exportar el `.pgbm` de la partida actual. En Android va directo a `Roms/PocketGB/Intercambio/` (la carpeta de Drive). En el iPhone, con la hoja de compartir (extensión de Google Drive si está instalada, o cualquier otra app). El otro equipo lo importa: Android detecta los paquetes nuevos en `Intercambio/` al abrir la biblioteca, y el iPhone con «Abrir con PocketGB» desde Archivos o Drive. Al importar se aplica el linaje (avance o divergencia) y, si el estado coincide con la partida, se ofrece **«Continuar donde lo dejaste en <equipo>»** (ND6).
- Exportar también el `.sav` crudo (sirve en otros emuladores). Importar `.sav` y `.pgbm` desde «Abrir con» o desde el detalle, validando el tamaño exacto y la huella, siempre con backup.

## 4. Hitos
Cada hito se cierra con:
- TDD.
- Capturas nuevas en los catálogos.
- Su sección de la guía y su consejo en la app.
- Evidencia `docs/auditorias/<hito>-evidencia.md`.
- Auditoría Opus con [PROMPT.md](../auditorias/PROMPT.md) y su respuesta.

Los criterios se verifican con comando y salida (regla dura 7). Para que los lotes paralelos no choquen:
- Las cadenas nuevas de Android van en `res/values/strings_<lote>.xml`.
- Los catálogos (`screens.txt` y `android-screens.txt`) solo reciben líneas añadidas al final.

### A9 · Paridad Android: renombrar y continuación exacta
- **Renombrar** con la semántica de iOS D8.1: ≤ 80 caracteres, vacío = título de cabecera, y la búsqueda incluye el alias. Disponible en el detalle, el menú contextual y los ajustes del juego.
- **Continuación exacta** como iOS:
  - «Continuar» retoma el estado AUTO si es válido, y lo invalida si la SRAM es más nueva.
  - «Jugar desde el inicio» abre solo la partida.
  - Cambia J8. Toca la ruta de partidas, así que la auditoría se centra en la regla 6.

**Criterios:**
- [ ] Tests JVM e instrumentados de alias (también la persistencia y la búsqueda).
- [ ] Tests de AUTO: válido, invalidado por SRAM más nueva, de otro modelo o corrupto. En todos esos casos se cae a la SRAM sin pérdida.
- [ ] Kill-test 50/50 y capturas.

### N1 · Identidad y carpetas (iOS + Android)
- **N1a:** §3.1 (metadatos por huella con migración, caché, duplicados; en iOS, ajustes por juego por huella y cuarentena de preferencias).
- **N1b:** §3.2 (escaneo recursivo, `folderPath`, nombres reservados).

**Criterios:**
- [ ] Árbol sintético de 5 niveles con carpetas `.oculta`, `_apartada` y `PocketGB/`: rutas y `folderPath` esperados en ambas apps.
- [ ] Mover un ROM de carpeta conserva favorito, alias, ocultar, ajustes, partida, estados y portada (extremo a extremo con carpeta temporal).
- [ ] Migración desde fixtures de los formatos actuales sin pérdida; preferencias corruptas apartadas y avisadas (iOS).
- [ ] Sin regresión: suites completas, kill-test Android 50/50, catálogos.

### N2 · Controles (iOS + Android)
- **Diagnóstico:**
  - iOS ilumina y escala al 0,9 la cruceta entera porque el dibujo no recibe la dirección (`ControlsOverlayView.swift:142-147, 529-536`).
  - Android ya resalta solo el brazo pulsado. Su sensación viene de:
    - sectores de 45° (la mitad del ángulo son diagonales), con zona muerta del 25 %;
    - región circular que incluye las esquinas;
    - contraste bajo entre pulsado y neutro;
    - háptica en cada cambio de sector.
  - Joel usa el modo «Siempre» (ND9).
- **Respuesta por dirección (ambas):** solo se resalta el brazo o la flecha pulsada (dos en diagonal); desaparece el escalado del grupo.
- **Diagonales:**
  - Sectores cardinales más anchos: diagonal solo a ±15° del eje de 45°.
  - Zona muerta del 30 % con histéresis.
  - Ajuste «Diagonales: normales / reducidas / desactivadas».
  - Háptica solo al cambiar de dirección.
- **iOS, bordes:** una sola capa de vidrio con trazo fino uniforme, sin el scrim inflado que crea el doble borde desplazado, y con sombra suave centrada. La cruz estilo Game Boy se conserva, con un hundido central.
- **iOS, flechas:** símbolos que escalan con el control (hoy 16 pt fijos), `arrowtriangle.*.fill` en la cruz y en las flechas separadas. Se valida con capturas.
- **Android, estructura de iOS:**
  - Cruz con las proporciones de iOS.
  - Flechas: 4 círculos en rombo (diámetro 0,36 × ancho) con iconos Material.
  - Tema Material propio, con contraste pulsado/neutro ≥ 3:1.
- **Flechas separadas (ambas), ND10:** tamaño (ya existe), separación 0,7–1,5 (nuevo) y mover el grupo (ya existe). Se guarda por disposición (consola × orientación) y se puede restablecer.
- No cambia la distribución general de los controles ni la posición del juego en ninguna plataforma.

**Criterios:**
- [ ] Traza sintética de toques con temblor de ±8 pt/dp alrededor del centro de ↑: solo UP en ≥ 99 % de las muestras (diagonales «reducidas»), en ambas apps.
- [ ] Tests de sector, histéresis, modos de diagonal y geometría de separación.
- [ ] Capturas pulsando ↑ en cruz y en flechas (solo ↑ resaltado), en claro/oscuro, vertical/horizontal y Reduce Transparency/contraste alto.
- [ ] VoiceOver/TalkBack sin regresión.
- [ ] Prueba de Joel en ambos teléfonos.

### N3 · Biblioteca y detalle adaptables (iOS + Android)
- **N3a:**
  - **iOS, título:** en altura compacta, título en línea con `scrollEdgeEffectStyle(.hard, for: .top)`. Las tarjetas con texto encima llevan scrim. Hay una captura con portada blanca.
  - **Android, carril «Continuar»:** el ancho de cada tarjeta sale de las mismas columnas que la cuadrícula, con el mismo margen y sin recorte. En horizontal, la altura se limita al alto disponible.
  - **Detalle adaptable:**
    - Dos columnas cuando el ancho supera al alto o mide ≥ 600 pt/dp: imagen limitada en altura a la izquierda, con la proporción de la consola (10:9 GB, 3:2 GBA); información y acciones a la derecha, con su propio scroll.
    - En vertical, la imagen ocupa como máximo ~45 % del alto.
    - Se corrige la captura GBA estirada a 10:9 (detalle y tarjetas de estados en iOS).
  - **Información equivalente:** iOS gana la sección plegable «Información técnica» de Android (cartucho/MBC o tipo de partida GBA, ROM, partida con RAM/batería/reloj, checksums, SHA-256 copiable).
- **N3b, horizontal (ambas):**
  - El buscador y los chips dejan de ocupar la parte superior, y la barra superior colapsa con el scroll.
  - A la derecha aparece un grupo flotante con Buscar, Filtros, Categorías (lo llena N4) y Vista/Orden. Cada botón se abre hacia arriba sin tapar el título de sección fijado.
  - **iOS:** `tabViewBottomAccessory(isEnabled:)` (iOS 26.1, con `#available` y un grupo de vidrio propio en 26.0), activo solo en la pestaña Biblioteca y en horizontal. La búsqueda también aparece como botón en la barra.
  - **Android:** barra flotante propia con componentes estables de Material 3. `HorizontalFloatingToolbar` solo existe en 1.5.0-alpha, y no se fija una alpha.
- **Tamaños:**
  - iOS: iPhone SE, 17 Pro y 17 Pro Max.
  - Android: teléfono vertical y horizontal, tablet y plegable (emulador).

**Criterios:**
- [ ] Capturas:
  - biblioteca en horizontal: arriba, con scroll, con cada panel abierto y con portada blanca;
  - detalle GB y GBA en horizontal y vertical, en todos los tamaños.
- [ ] Tests del cálculo de columnas del carril (Android) y del layout del detalle.
- [ ] AX5 / fuente al 200 % sin cortes en lo tocado.

### N4 · Categorías, etiquetas, inicio y centro de ajustes del juego (iOS + Android)
- **Inicio:**
  - «Continuar jugando», una fila de Favoritos y una estantería por categoría de primer nivel.
  - «Ver todo» lleva a la pantalla de la categoría: subcategorías, juegos y migas («Pokémon › 2ª generación»).
- **Vistas:** cuadrícula o lista por categoría. En Ajustes › Biblioteca › Inicio se ordenan, fijan y ocultan categorías (por dispositivo).
- **Etiquetas:** libres por juego, con filtro por etiqueta. La búsqueda incluye categoría y etiquetas.
- **Centro de ajustes del juego:**
  - nombre;
  - categoría virtual (ND3), con insignia y opción de revertir;
  - etiquetas;
  - portada (N5);
  - progreso y momentos (N6);
  - partida (N7);
  - color/paleta (GB) o tipo de partida/RTC/BIOS (GBA);
  - ocultar.
- **Documentación:** `docs/11-biblioteca-carpetas.md` y la plantilla `docs/LEEME-PocketGB.txt`.

**Criterios:**
- [ ] Árbol sintético → secciones, subcategorías y migas esperadas.
- [ ] Categoría virtual aplicada y revertida.
- [ ] Capturas: inicio (vertical/horizontal), categoría anidada y centro de ajustes.

### N5 · Portadas (iOS + Android)
- **Fuentes:**
  - imagen importada en la app;
  - imagen junto al ROM (`<nombre del ROM>.png|jpg|jpeg|webp`, o `portada.*`/`cover.*` si la carpeta tiene un solo juego);
  - captura (último fotograma, o fijada con «Usar como portada» desde la pausa);
  - generada.
- **Elección:**
  - por juego: Automática / Imagen / Captura / Generada;
  - global: preferir imágenes o capturas.
- **Encaje:** la tarjeta conserva su marco y la imagen lo rellena centrada; el detalle la muestra entera.
- **Importar:**
  - iOS: `PhotosPicker` y `fileImporter`.
  - Android: Photo Picker (sin permisos) y `OpenDocument`.
  - Se guarda una copia reducida (≤ 1024 px) por huella.
- **Entrada no confiable:**
  - límite de 15 MiB;
  - decodificación con submuestreo (ImageIO; `BitmapFactory` con `inSampleSize`, porque `ImageDecoder` exige API 28 y el `minSdk` es 26);
  - dimensiones máximas;
  - ante cualquier fallo, la portada generada.

**Criterios:**
- [ ] Tests de prioridad de fuentes e imágenes hostiles: truncada, dimensiones enormes, extensión falsa.
- [ ] Capturas con imagen, captura y generada en cuadrícula, lista, carril y detalle.

### N6 · Momentos y progreso (iOS + Android + C)
- **Momentos** (§3.3):
  - Se crean desde la pausa con un nombre sugerido.
  - Pantalla «Momentos» por juego, desde el detalle (sustituye al botón «Estados (próximamente)»; el motor ya existe) y desde la pausa.
  - Se agrupan por colección, se filtran por etiqueta y se pueden renombrar, editar y borrar con confirmación.
  - Incluye el anillo «Antes de cargar».
- **Progreso:**
  - tiempo de juego, sesiones y primera/última vez;
  - hitos con casillas y plantillas («Pokémon: 8 medallas + Liga», «Libre»);
  - porcentaje en tarjeta y detalle, desactivado por defecto;
  - panel del lector Pokémon (jugador, medallas, Pokédex, tiempo del juego), que propone marcar los hitos.
- **C (Ola 1):** `pgb_progress_read` puro, con fuzzer y ASan, y `make check-globals` limpio.

**Criterios:**
- [ ] Migración de ranuras 1–4 y RESCUE sin pérdida.
- [ ] Cargar un momento antiguo → «Recuperar» devuelve la partida más nueva en un toque; el `.sav` previo queda en backup.
- [ ] Un estado que el núcleo ya no carga (simulado) deja recuperar la RAM del momento.
- [ ] Exclusión por huella: tests de cargar con la sesión aparcada y kill-test a mitad de carga.
- [ ] Contabilidad del tiempo con pausa, segundo plano y cierre forzado.
- [ ] Lector: `.sav` sintéticos construidos byte a byte (nunca partidas reales en el repo), checksum incorrecto = sin datos, fuzz de 600 s.

### N7 · Partidas que viajan (iOS + Android + C)
- **N7a:** linaje (§3.4, tabla) en las dos apps.
- **N7b:** paquete `.pgbm` (parser C con fuzzer), exportar e importar `.sav`/`.pgbm`, hoja de compartir y guardar en…:
  - iOS: `ShareLink`, `fileExporter` y tipos de documento propios.
  - Android: `FileProvider` de AndroidX con `ACTION_SEND`/`ACTION_CREATE_DOCUMENT`, e intents genéricos validados por cabecera.
- **N7c:**
  - Enviar a otro dispositivo, con `PocketGB/Intercambio/` en Android.
  - Continuar donde lo dejaste en <equipo>.
  - Estado de la partida en el detalle («Partida: iPhone · hace 2 h»).
- **Previo:** Joel hace antes las pruebas de partidas pendientes de [PRUEBAS-JOEL](../PRUEBAS-JOEL.md) (A6, A8, A9 e I1).

**Criterios:**
- [ ] Tabla de linaje completa en tests Swift y JVM. Casos: latencia (el paquete anuncia un sha que aún no llegó), copias en conflicto del proveedor y reloj desfasado.
- [ ] Ida y vuelta exportar → importar en cada plataforma y **entre plataformas** (vectores dorados con carga sintética generada en el test, iguales en iOS y Android).
- [ ] Paquetes hostiles rechazados (fuzz de 600 s, ASan); `*.pgbm` bloqueado por el hook pre-commit.
- [ ] Importar respalda lo actual y respeta la exclusión por huella.
- [ ] Android sin `INTERNET` (test de manifiesto); iOS sin APIs de red.
- [ ] Prueba de Joel: Rojo iPhone → Android → iPhone con continuación exacta.

### N8 · Game Boy Advance en Android
- **Nativo (Ola 2):**
  - CMake compila `gba/src` + `core/src/sha256.c` (una sola vez).
  - `native_session` abstrae la consola: máscara de 16 bits, 240×160, audio, partida (`.sav` con RTC al final, como iOS) y estados.
  - BIOS opcional `gba_bios.bin` validada por SHA-256.
  - Al terminar se mide ms/frame en el teléfono de Joel.
- **Kotlin (después de N4 Android):**
  - `Console` en vez de `isColor`.
  - Escáner `.gba` ≤ 32 MiB y filtro GBA.
  - Tamaños de partida GBA y proporción 3:2.
  - L/R táctiles y de mando con disposición GBA propia (en GBA, L1/R1 pasan a ser L/R y la pausa y la velocidad se reasignan, como en iOS).
  - Ajustes por juego GBA y datos GBA en el detalle.

**Criterios:**
- [ ] JNI con `arm.gba` (jsmolka, libre) y escena idéntica a la del núcleo.
- [ ] Partida GBA atómica con espejo y kill-test GBA 50/50.
- [ ] Capturas GBA.
- [ ] Prueba de Joel: Kirby a 60 fps ≥ 30 min, guardar y cierre forzado.

### N9 · Carpetas de Joel, guía consolidada y cierre
- **Guía en la app sin red:** Ajustes › Guía, que reúne las secciones que entregó cada hito. Consejos con TipKit (iOS) y tarjetas descartables (Android).
- **Documentación al día:** 02, 04, 05, 06, 11, PRUEBAS-JOEL y ESTADO.
- **Carpetas de Joel** (Drive `Roms` e iCloud `GMRoms`), con su confirmación explícita y con N1b/N7 ya instalados en los dos equipos:
  - inventario y sha de cada `.sav` antes y después;
  - estructura de ejemplo;
  - movimientos que conservan id y fecha (en Drive, cambio de carpeta padre; en iCloud, `mv`);
  - duplicados apartados en `_Revisar/` sin borrar nada;
  - portadas (ND4) y `LEEME-PocketGB.txt`.
- Auditoría conjunta final y PR a `main`.

## 5. Orden y paralelismo
Límite de la máquina (16 GB): 1 lote iOS + 2 Android a la vez, y simulador/emulador con mutex. Los lotes de C no usan simulador.

| Ola | iOS | Android (2 carriles) | C / núcleo |
|---|---|---|---|
| 1 | N2 iOS | A9 · N2 Android | lector Pokémon |
| 2 | N1 iOS | N1 Android · N8 nativo | parser `.pgbm` |
| 3 | N3 iOS | N3 Android | — |
| 4 | N4 iOS | N4 Android | — |
| 5 | N5 iOS | N8 Kotlin, luego N5 Android | — |
| 6 | N6 iOS | N6 Android | — |
| 7 | N7 iOS | N7 Android | — |
| 8 | N9 | N9 | — |

Dentro de cada plataforma, los lotes que tocan biblioteca, detalle, ajustes del juego o partidas van en serie: N3 → N4 → N5/N8 Kotlin → N6 → N7.

## 6. Riesgos
- **Pérdida de partidas** en momentos, importación y linaje. Mitigación: regla 6 en cada criterio, exclusión por huella, anillo «Antes de cargar», auditoría dirigida y kill-tests.
- **Proveedor de la carpeta** (latencia, archivos sin descargar, copias en conflicto). La decisión nunca depende de la fecha ni del nombre: los dos lados se conservan.
- **Alcance.** Es el bloque más grande desde GBA. Si hay que recortar, se sigue la prioridad de Joel. Primero lo pedido: A9 → N2 → N1 → N3 → N4 → N8 → N6 → N7 → N5 → N9. Después, lo opcional que aceptó: lector Pokémon y continuación exacta entre equipos.
- **Derechos:** portadas e imágenes de juegos nunca en el repo; `*.pgbm` en el hook.
- **Pruebas en dispositivo** pendientes de antes (PRUEBAS-JOEL): sin ellas no se empieza N7.

## 7. Fuera de alcance
iPad nativo, `.zip`, trucos, red propia, cuentas en la nube, metadatos sincronizados automáticamente entre equipos (no hay carpeta común; viajan en `.pgbm`), instantáneas periódicas y UI del cable en Android.
