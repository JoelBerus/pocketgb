# N2 Android: evidencia (controles: cruceta con la estructura de iOS, diagonales y separación de flechas)

Rama `n2-android-controles` sobre `siguiente-nivel` @ `c3d8405`. Commits: `a289524` (cruceta, diagonales, separación, tema), `83b26a1` (diagonales en lista, pruebas instrumentadas, guía, SPEC y el arreglo `UseKtx`) y el de esta evidencia. Estado: **lote Android de N2 listo para auditoría**; falta la prueba de Joel en su teléfono (ver «No verificado»). No se tocaron `library/`, `ui/details/`, `ui/library/`, `game/`, `saves/` ni `strings.xml`.

## Qué cambia

### Estructura y tema (equivalente a iOS, con Material)
- **Cruz** con las proporciones de `ControlsOverlayView.swift`: unión de dos rectángulos redondeados de 0,76 W × 0,29 W (esquinas 0,3 × grosor) dentro de un **disco**. Se dibuja con un solo relleno y un solo trazo (`Path.op(UNION)`), sin el alfa doble del centro de la versión anterior. El centro no se resalta.
- **Flechas separadas**: cuatro **círculos** de 0,36 W en rombo (separación de fábrica `(1 − 0,36) / 2 = 0,32 W`, la de iOS), con los iconos Material `ArrowDropUp/Down` y `ArrowLeft/Right` (`material-icons-extended`, ya dependencia). `DpadIcons` convierte el `ImageVector` en un `android.graphics.Path` una vez y lo escala por el tamaño de la propia figura, así crece con el control.
- **Tema Material propio** (`ControlsPalette`, tomado de los roles del esquema oscuro del juego, sin vidrio): fondo del disco `surface`, brazos y círculos `surfaceVariant`, pulsado `primary`, símbolos `onSurfaceVariant` / `onPrimary`, contorno `outline`. Los tres esquemas (estándar, contraste medio, contraste alto) cumplen el contraste (ver abajo). A, B, Start y Select usan la misma paleta (A y B conservan su anillo cálido/frío).
- **Capa oscura** (`scrimAlpha`) con la fórmula de iOS (`0,3 + 0,3 × opacidad`, antes `0,28 × opacidad`) y dibujada **antes** de todos los controles: la capa de uno ya no tapa a su vecino (Select/Start se solapan 1 dp de fábrica). El relleno pulsado ya no baja con la opacidad (0,92 siempre).

### Respuesta por dirección y diagonales
- Solo se ilumina el brazo o círculo pulsado (dos en diagonal): `GameControlsView.drawCross/drawArrows`.
- `DpadSectors` (nuevo, `input/DpadModel.kt`): sectores por modo, zona muerta del **30 %** con histéresis (activa desde 0,30, suelta al bajar de 0,24) e histéresis angular de 6° por dedo (la «previa» la guarda `TouchInputEngine` por puntero).
- Ajuste **Diagonales: Normales / Reducidas / Desactivadas** en Ajustes › Controles (por defecto **Reducidas**, `GameplaySettingsData.diagonalMode`). **Normales** = ocho sectores de 45° (el comportamiento anterior); **Reducidas** = diagonal a ±15° de los 45° (cardinales a ±30°); **Desactivadas** = cuatro sectores de 90°. Se muestra como lista de opciones con explicación (tres botones segmentados partían «Desactivadas»).
- **Háptica** (`DpadHaptics.shouldTick`) solo cuando se activa una dirección nueva: arriba → arriba+derecha da un toque; arriba+derecha → arriba no.
- El stick y el hat de un **mando físico** conservan sus ocho sectores de 45° (`GamepadInput` llama a `dpadMask(..., NORMAL)`): el ajuste es de la cruceta táctil.

### Separación de las flechas (ND10)
- `StoredControlLayout.separation` (0,7–1,5; 1,0 de fábrica) **por orientación**; `adjustSeparation` en pasos de 0,1 (nueve valores exactos, sin deriva); `resetLayout` la devuelve a 1,0; `isFactoryLayout` la cuenta.
- Distancia de cada círculo al centro: `0,32 W × separación` si es ≥ 1,0; por debajo, lineal hasta `0,265 W` en 0,7 (los círculos casi se tocan, 2 dp de hueco a 140 dp, y nunca se solapan; con la fórmula proporcional pura se solaparían bajo 0,8). El marco de la cruceta es el cuadro que envuelve el grupo (`footprint = 0,36 + 2 × distancia`, 1,0 W de fábrica y 1,32 W a 1,5), de modo que la **zona táctil**, la exclusión de gestos, la selección del editor y el **nodo de TalkBack** lo siguen. Si la zona de controles lo recorta, el grupo se encoge entero.
- Editor (`ControlsEditorBar`): con la cruceta elegida y el estilo de flechas separadas aparece una segunda fila «Separación · 100 %» con − / + (70–150 %). Con la cruz no aparece.

### Cadenas, catálogo y guía
- Cadenas nuevas solo en `android/app/src/main/res/values/strings_n2.xml`.
- `tools/android-screens.txt`: 26 líneas añadidas al final (1 comentario y 25 capturas de 22 ids). Debug: `DebugIntent` gana `pressed=` (solo dibuja la pulsación, vía `LocalPreviewDpadMask`), `separation=` y `diagonals=`; `debug/catalog/N2Catalog.kt` registra los ids.
- Guía: `docs/guia/controles-android.md`. SPEC: §4.3 de `docs/diseno-android/SPEC.md`.

## Verificación (comandos y salidas)

### Gradle completo (código final)
`cd android && ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`
```
BUILD SUCCESSFUL in 3m 52s
119 actionable tasks: 33 executed, 86 up-to-date
```
- Pruebas JVM: **417 pruebas, 0 fallos, 0 omitidas** (suma de los XML de `app/build/test-results/testDebugUnitTest`; la base `c3d8405` tiene 375 `@Test` en `src/test`, así que el lote añade 42). Las de este lote: `DpadModelTest` 21, `ControlGeometryTest` 20, `TouchInputEngineTest` 6, `ControlsRenderOptionsTest` 10, `ControlsAccessibilityTest` 10, `GameplaySettingsTest` 25, `GamepadMappingTest` 17 (sin cambios de contenido).
- Una corrida anterior del mismo comando falló en `ProcessKillTest > killedWriterNeverLeavesAPartialOrMissingSave` (`saves/`, ajeno a este lote): esa prueba mata 200 procesos hijos y depende de la carga del anfitrión (load average de 140 a 270 en el Mac por los otros lotes; su propio comentario lo advierte). Sola pasa (`kills en bucle=197`, `guardados completados=46`) y el comando completo pasó después.
- `app-debug.apk` (22,4 MB) y `app-release-unsigned.apk` (14,7 MB) generados. `ManifestPolicyTest` (sin `INTERNET`) en verde.
- Lint: `0 errors, 20 warnings`. El único aviso en archivos de este lote es `ViewConstructor` de `GameControlsView` (ya existía). `UseKtx` en `DpadIcons` se corrigió (`Canvas.withTranslation`).

### Criterio objetivo: temblor de ±8 dp sobre el brazo ↑ (reducidas)
`DpadModelTest.jitterOfEightDpAroundTheUpArmGivesOnlyUpWithReducedDiagonals`: traza determinista (congruencial lineal con semilla fija), 4000 muestras uniformes en un cuadrado de ±8 dp (a 2,625 px/dp) alrededor del centro del brazo ↑ (cruz) y del círculo ↑ (flechas), arrastre continuo por `TouchInputEngine` (con histéresis):
```
TEMBLOR ±8 dp sobre ↑ (CROSS, reducidas, 4000 muestras): solo ↑ en 100.00 %
TEMBLOR ±8 dp sobre ↑ (ARROWS, reducidas, 4000 muestras): solo ↑ en 100.00 %
```
Además (todas ≥ 99 %): los cuatro brazos y ambos estilos con tamaño de cruceta 0,8–1,6 × global 0,85–1,15; las flechas con separación 0,7, 0,85, 1,0, 1,25 y 1,5; con «Desactivadas», 100 % en todos. La misma traza pasa por la vista real en el emulador (`GameControlsViewTest.jitterOfEightDpAroundTheUpArmGivesOnlyUpThroughTheRealView`, 1000 muestras por estilo, ≥ 99 %). Honestidad sobre el criterio: a ±8 dp el peor caso (15,5° de la vertical) cabe también en el sector de 22,5° de «Normales»; el criterio no distingue por sí solo. Lo que sí distingue es un temblor mayor: con ±20 dp sobre ↑ (casi el ancho del brazo), solo ↑ en **94,1 %** con «Reducidas» frente a **81,8 %** con «Normales» (`withAWiderTremorReducedDiagonalsStillBeatNormalOnes`).

### Contraste pulsado frente a neutro (≥ 3:1)
`ControlsRenderOptionsTest`. Colores del tema (WCAG, luminancia relativa):
```
CONTRASTE estándar        pulsado/neutro = 5.52, símbolo neutro = 5.50, símbolo pulsado = 7.65
CONTRASTE contraste medio pulsado/neutro = 8.86, símbolo neutro = 7.68, símbolo pulsado = 7.11
CONTRASTE contraste alto  pulsado/neutro = 12.05, símbolo neutro = 12.07, símbolo pulsado = 11.13
```
(Antes: `0x4A4D57` frente a `0x1F2024`, unos 1,95:1.) Compuesto como se dibuja (capa oscura, fondo del disco, brazo; brazo de la cruz / círculo de flechas) sobre un fotograma de juego uniforme:
```
opacidad 30 %: [negro] 9.42 / 9.50  [gris 64] 7.39 / 6.99  [gris 128] 5.29 / 4.57  [blanco] 2.65 / 1.98
opacidad 50 %: [negro] 8.51 / 8.73  [gris 64] 7.22 / 6.75  [gris 128] 5.89 / 4.97  [blanco] 3.90 / 2.67
opacidad 70 %: [negro] 7.54 / 7.77  [gris 64] 6.84 / 6.47  [gris 128] 6.16 / 5.25  [blanco] 4.88 / 3.41
opacidad 100 %: [negro] 6.28 / 6.34  [gris 64] 6.10 / 5.78  [gris 128] 5.92 / 5.30  [blanco] 5.61 / 4.45
```
Garantía probada: ≥ 3:1 con cualquier opacidad sobre fondos de negro a gris medio, y con 70 % o más (el predeterminado es 70 %) también sobre blanco; el contraste alto es sólido y ≥ 3:1 sobre cualquier fondo. **Límite conocido**: con 30–50 % de opacidad sobre un fotograma blanco puro, los círculos de las flechas bajan a 1,98–2,67:1 (la cruz, 2,65–3,90:1). Es una consecuencia de elegir controles casi transparentes; la pulsación sigue señalada por el color, el cambio del símbolo y el relleno 0,92 siempre.

### Pruebas instrumentadas (emulador `Small_Phone_API_35`, dentro de `with-lock.sh emu`)
`./gradlew --no-daemon --max-workers=1 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=<paquete>`:

| Paquete / clase | Pruebas | Fallos |
|---|---|---|
| `com.joelbermudez.pocketgb.input` (`GameControlsViewTest` 13, `ControlsTalkBackTest` 15) | 28 | 0 |
| `com.joelbermudez.pocketgb.ui` (incluye `ControlsSettingsN2Test` 1, `HighContrastTest` 2, `TouchTargetTest` 6) | 84 | 0 |
| `com.joelbermudez.pocketgb.game` (incluye `ControlsEditorTest` 6) | 97 | 0 |
| `CatalogCoverageTest` | 9 | 0 |
| `DebugCatalogTest` | 11 | 0 |
| **Total** | **229** | **0** |

Nuevas instrumentadas: solo se ilumina el brazo pulsado y el centro de la cruz no (píxeles de un `Bitmap`), solo el círculo pulsado en las flechas (diagonal = dos), diagonal a 25° según el modo, temblor ±8 dp por la vista real, háptica solo al activar una dirección nueva, nodo de TalkBack de la cruceta con flechas separadas a 0,7/1,0/1,5 (≥ 48 dp, envuelve los cuatro círculos, sus cuatro acciones siguen), editor (separación en pasos del 10 % entre 70 y 150 %, por orientación, persistida, Restablecer a 100 %; la fila no aparece con la cruz) y Ajustes (Diagonales por defecto en «Reducidas», cada opción actualiza el ajuste). `HighContrastTest` se adaptó: con 30 % sobre blanco el relleno sigue siendo translúcido (luminancia > 100; la capa oscura nueva lo baja de > 150), y el contraste alto usa su esquema.

### Persistencia y migración
`GameplaySettingsTest`: un archivo en el formato anterior (sin `diagonalMode` ni `separation`) carga con «Reducidas» y 1,0 y conserva todo lo demás (opacidad, visibilidad, disposición, por juego, mapeo del mando); ida y vuelta de los campos nuevos; un `diagonalMode` o `separation` inválido cae solo a su valor por defecto sin perder el resto; la separación se recorta a 0,7–1,5; el archivo anterior no se aparta.

### Capturas (`tools/android-screenshots.sh`, `Small_Phone_API_35` 720×1280, 320 dpi)
Salida en `android/build/screenshots-n2/<id>-<orientación>-<tema>.png` (no se versiona). Todas se miraron con la herramienta de lectura de imágenes (hojas de contacto y recortes a tamaño real). El juego va siempre oscuro (K4): «claro/oscuro» solo aplica a Ajustes y al fotograma de juego blanco (`rom=light`).

Catálogo: 25 capturas de 22 ids (todas en `Small_Phone_API_35`, 720×1280; las horizontales 1280×720). Corrida sobre `83b26a1`.

| Id (variante) | Debe verse | Se ve | Resultado |
|---|---|---|---|
| `n2-cross-up` (O, vertical) | Disco con cruz; solo el brazo ↑ iluminado | Cruz con los cuatro triángulos, brazo ↑ verde, el resto y el centro neutros; contorno fino; A/B con su anillo | ✅ |
| `n2-cross-diagonal` (O) | ↑ y → iluminados, centro no | Dos brazos verdes, el centro oscuro | ✅ |
| `n2-cross-up-landscape` (O, horizontal) | Igual sobre la imagen del juego | Cruz sobre bordes de la imagen, capa oscura visible, brazo ↑ verde | ✅ |
| `n2-cross-up-high-contrast` (O) | Contraste alto: relleno sólido, anillo claro | Disco con borde claro, brazos sólidos, Select/Start con anillo | ✅ |
| `n2-cross-up-light-frame`, `n2-cross-up-opacity30` (O, horizontal, fotograma claro) | Controles legibles sobre gris claro; con 30 % casi transparentes pero ↑ visible | Capa oscura bajo cada control; con 30 % los neutros son manchas tenues, el brazo ↑ sigue verde | ✅ (límite de contraste documentado arriba) |
| `n2-arrows-up` (O) | Cuatro círculos en rombo con flechas Material; solo ↑ | Rombo limpio, círculo ↑ verde con símbolo oscuro, los otros neutros | ✅ |
| `n2-arrows-diagonal` (O) | ↑ y → iluminados | Dos círculos verdes | ✅ |
| `n2-arrows-up-landscape` (O, horizontal) | Igual en horizontal | Rombo sobre el borde de la imagen, ↑ verde | ✅ |
| `n2-arrows-up-high-contrast` (O) | Círculos sólidos con borde claro | Rellenos opacos, ↑ casi blanco con símbolo oscuro | ✅ |
| `n2-arrows-up-light-frame`, `n2-arrows-up-opacity30` (O, horizontal) | Igual que la cruz | Igual; con 30 % sobre claro los círculos neutros se funden más que la cruz (1,98:1, documentado) | ✅ con reserva |
| `n2-arrows-separation-min` (O) | Separación 0,7: círculos casi tocándose, sin solaparse | Rombo compacto con hueco mínimo | ✅ |
| `n2-arrows-separation-max`, `n2-arrows-separation-max-landscape` (O) | Separación 1,5: grupo ancho dentro del área segura | Rombo amplio, completo en pantalla, sin tapar A/B | ✅ |
| `n2-editor-arrows`, `n2-editor-arrows-landscape` (O) | Cruceta elegida con contorno de rayas y fila «Separación · 130 %» | Contorno que envuelve el grupo, dos filas − / +, ayuda; en horizontal el borde derecho del grupo roza la barra | ✅ |
| `n2-editor-cross` (O) | Con la cruz no hay fila de separación | Solo «Cruceta · 100 %» | ✅ |
| `n2-settings-controls` (C, O) | Ajustes › Controles con el grupo «Diagonales» | Tres filas de opción (Normales, Reducidas marcada, Desactivadas) con su explicación | ✅ |
| `n2-settings-controls-scrolled` (C, O) | Diagonales con su pie, tras desplazar | Lista completa y pie legibles | ✅ |
| `n2-settings-controls-ax5` (C) | Fuente al 200 % | Pantalla legible; los segmentados existentes de Opacidad y Cruceta se desbordan (anterior a N2) | ✅ para lo nuevo; observación abajo |
| `n2-settings-diagonals-ax5` (C, O) | Solo el grupo Diagonales con fuente al 200 % | Opciones y explicaciones en varias líneas, sin cortes | ✅ |

Defectos encontrados al mirar y corregidos: con tres botones segmentados «Desactivadas» partía en «Desactivad/as» (pasó a lista); la capa oscura de un control tapaba el borde de su vecino (Select/Start; ahora se dibujan todas las capas primero).

## No verificado (a probar por Joel en su teléfono)
- **Tacto real**: la sensación de «al pulsar arriba reacciona toda la cruceta» se corrige con sectores, zona muerta, histéresis, contraste y capa oscura, y se midió con trazas sintéticas y con eventos táctiles inyectados por la vista real. No sustituye al pulgar de Joel con su cruceta y su modo «Siempre»: conviene probar «Reducidas» (por defecto) y «Normales» para comparar.
- **Háptica real** (`CLOCK_TICK`): se comprobó la regla (cuándo se pide), no cómo se siente.
- **TalkBack real**: los nodos virtuales y sus acciones se prueban con el árbol de accesibilidad, sin servicio de TalkBack encendido.
- **Pulgar sobre el borde del juego en horizontal con la separación al máximo**: la cruceta crece hasta 1,32 W y la barra del editor puede quedar sobre ella (se arrastra desde una parte visible).
- **Otros tamaños** (plegable, tablet) y densidades: solo el AVD de 360 × 640 dp; la geometría se prueba en JVM con distintas densidades.
- No se reprodujo una captura «antes» (la anterior ya estaba en `gameplay-controls`/`gameplay-portrait-arrows` de A6).

## Observaciones fuera de este lote
- Ajustes › Controles con fuente al 200 %: los botones segmentados de «Opacidad» y «Cruceta» (`ChoiceRow`, existente) se desbordan (visible en `n2-settings-controls-ax5`); no se tocó el componente compartido. Las tres opciones de Diagonales no sufren esto porque son una lista.
- Emulador compartido: con la máquina cargada (load average > 200) la rotación del AVD tarda o no llega y el script de capturas deja pasar una captura vertical con aviso («la pantalla no giró»); las horizontales se repitieron una a una hasta salir en horizontal.
