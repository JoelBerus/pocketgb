# N2 Android: evidencia (controles: cruceta con la estructura de iOS, diagonales y separación de flechas)

Rama `n2-android-controles` sobre `siguiente-nivel` @ `c3d8405`. Commits: `a289524` (cruceta, diagonales, separación, tema), `83b26a1` (diagonales en lista, pruebas instrumentadas, guía, SPEC), `c5d660a` (primera evidencia), `7e6d056` (correcciones de la auditoría Opus H1–H3 y H5–H8 y unificación con iOS), `20f5939` (guía, SPEC, N-README, informe de auditoría y respuesta) y el de esta evidencia. La verificación «desde limpio» de abajo se hizo sobre **`20f5939`** (después solo cambian documentos).

Estado: **lote Android de N2 corregido tras la auditoría** ([N2-android-opus](N2-android-opus.md): APROBAR CON CAMBIOS; respuesta hallazgo por hallazgo en [N2-android-respuesta](N2-android-respuesta.md)). Falta la prueba de Joel en su teléfono y su decisión sobre H4 (ver abajo). No se tocaron `library/`, `ui/details/`, `ui/library/`, `game/`, `saves/` ni `strings.xml`.

## Qué cambia

### Estructura y tema (equivalente a iOS, con Material)
- **Cruz** con las proporciones de `ControlsOverlayView.swift`: unión de dos rectángulos redondeados de 0,76 W × 0,29 W (esquinas 0,3 × grosor) dentro de un **disco**. Se dibuja con un solo relleno y un solo trazo (`Path.op(UNION)`), sin el alfa doble del centro de la versión anterior. El centro no se resalta.
- **Flechas separadas**: cuatro **círculos** de 0,36 W en rombo (separación de fábrica `(1 − 0,36) / 2 = 0,32 W`, la de iOS), con los iconos Material `ArrowDropUp/Down` y `ArrowLeft/Right` (`material-icons-extended`, ya dependencia). `DpadIcons` convierte el `ImageVector` en un `android.graphics.Path` una vez y lo escala por el tamaño de la propia figura, así crece con el control.
- **Tema Material propio** (`ControlsPalette`, tomado de los roles del esquema oscuro del juego, sin vidrio): fondo del disco `surface`, brazos y círculos `surfaceVariant`, pulsado `primary`, contorno `outline`; **texto y símbolos `onSurface` (neutro) y `onPrimary` (pulsado), siempre con alfa 1** (H3). Los tres esquemas (estándar, contraste medio, contraste alto) cumplen el contraste (ver abajo). A, B, Start y Select usan la misma paleta (A y B conservan su anillo cálido/frío).
- **Capa oscura** (`scrimAlpha = 0,3 + 0,3 × opacidad`, fórmula propia de Android; antes `0,28 × opacidad`) con la **forma exacta** del control + 1 dp (H6: ya no es un anillo gris separado del contorno) y dibujada **antes** de todos los controles: la capa de uno no tapa a su vecino (Select/Start se solapan 1 dp de fábrica). El relleno pulsado ya no baja con la opacidad (0,92 siempre).
- **Marcos cuadrados** (H5): los controles redondos (cruceta, A, B, menú) usan `half = min(halfW, halfH)`; en una zona de controles más baja que ancha siguen cuadrados y dentro del área.

### Respuesta por dirección y diagonales
- Solo se ilumina el brazo o círculo pulsado (dos en diagonal): `GameControlsView.drawCross/drawArrows`.
- `DpadSectors` (`input/DpadModel.kt`): sectores por modo, zona muerta del **30 %** con histéresis (activa desde 0,30, suelta al bajar de 0,24) e **histéresis angular de 8°** por dedo, la de iOS (la «previa» la guarda `TouchInputEngine` por puntero).
- **Flechas separadas: manda lo que se ve** (H1, `ControlGeometry.dpadMask`): un toque dentro de un círculo da la dirección de ese círculo; el mismo dedo la conserva hasta salir 4 dp; la zona muerta se limita al borde interior de los círculos − 2 dp; fuera de los círculos (huecos y exterior) decide el ángulo.
- Ajuste **Diagonales: Normales / Reducidas / Desactivadas** en Ajustes › Controles (por defecto **Reducidas**, `GameplaySettingsData.diagonalMode`). **Normales** = ocho sectores de 45° (el comportamiento anterior); **Reducidas** = diagonal a ±15° de los 45° (cardinales a ±30°); **Desactivadas** = cuatro sectores de 90°. Se muestra como lista de opciones con explicación (tres botones segmentados partían «Desactivadas»).
- **Háptica común con iOS** (`DpadHaptics.shouldTick`): vibra cuando se activa una dirección que no estaba activa: ↑ → ↑→ vibra; ↑→ → ↑ no; ↑ → nada → ↑ sí.
- **Sin direcciones opuestas** (H2): `withoutOpposites` en `TouchInputEngine.mask`/`dpadMask` (dos dedos) y `combine_buttons` en `native_session.c` (táctil + mando).
- El stick y el hat de un **mando físico** conservan sus ocho sectores de 45° (`GamepadInput` llama a `dpadMask(..., NORMAL)`): el ajuste es de la cruceta táctil.

### Separación de las flechas (ND10)
- `StoredControlLayout.separation` (0,7–1,5; 1,0 de fábrica) **por orientación**; `adjustSeparation` en pasos de 0,1 (nueve valores exactos, sin deriva); `resetLayout` la devuelve a 1,0; `isFactoryLayout` la cuenta.
- Fórmula (la única, común con iOS por decisión del orquestador; ver [N-README §4 N2](../hitos/N-README.md)): diámetro 0,36 W fijo; distancia de cada círculo al centro `0,32 W × separación` si es ≥ 1,0; por debajo, lineal hasta `0,265 W` en 0,7 (los círculos casi se tocan, 2 dp de hueco a 140 dp, y nunca se solapan). El marco de la cruceta es el cuadro que envuelve el grupo (`footprint = 0,36 + 2 × distancia`, 1,0 W de fábrica y 1,32 W a 1,5), de modo que la **zona táctil**, la exclusión de gestos, la selección del editor y el **nodo de TalkBack** lo siguen. Si la zona de controles lo recorta, el grupo se encoge entero.
- Editor (`ControlsEditorBar`): con la cruceta elegida y el estilo de flechas separadas aparece una segunda fila «Separación · 100 %» con − / + (70–150 %). Con la cruz no aparece.

### Persistencia (H7)
`GameplaySettingsFile.layout` decodifica `StoredControlLayout` **campo a campo** y las posiciones y escalas entrada a entrada: un `separation` de tipo inválido, un control desconocido, un punto o una escala dañados pierden solo eso; las posiciones y escalas válidas de esa orientación se conservan.

### Cadenas, catálogo y guía
- Cadenas nuevas solo en `android/app/src/main/res/values/strings_n2.xml`.
- `tools/android-screens.txt`: 26 líneas añadidas al final (1 comentario y 25 capturas de 22 ids). Debug: `DebugIntent` gana `pressed=` (solo dibuja la pulsación, vía `LocalPreviewDpadMask`), `separation=` y `diagonals=`; `debug/catalog/N2Catalog.kt` registra los ids.
- Guía: `docs/guia/controles-android.md`. SPEC: §4.3 de `docs/diseno-android/SPEC.md`. Plan: «Geometría y háptica comunes» en `docs/hitos/N-README.md` §4 N2.

## Verificación DESDE LIMPIO (comandos y salidas)

Android no tiene CI: la compilación incremental puede ocultar un fallo. Se exportó el commit a un directorio nuevo y se compiló desde cero:
```
git archive HEAD | tar -x -C /private/tmp/claude-501/n2a-clean      # HEAD = 20f5939
cp android/local.properties /private/tmp/claude-501/n2a-clean/android/local.properties
cd /private/tmp/claude-501/n2a-clean/android
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```
```
BUILD SUCCESSFUL in 5m 10s
119 actionable tasks: 119 executed
```
(119 de 119 tareas ejecutadas: sin nada reutilizado, incluida la compilación nativa.)
- **Pruebas JVM: 432 pruebas, 0 fallos, 0 omitidas** (suma de los XML de `app/build/test-results/testDebugUnitTest`; la base `c3d8405` tiene 375 `@Test`). Las de este lote: `DpadModelTest` 21, `ArrowsTouchTest` 6 (nuevo), `ControlGeometryTest` 21, `TouchInputEngineTest` 9, `ControlsRenderOptionsTest` 13, `ControlsAccessibilityTest` 10, `GameplaySettingsTest` 27, `GamepadMappingTest` 17.
- `app-debug.apk` (21,6 MB) y `app-release-unsigned.apk` (14,8 MB). `ManifestPolicyTest` (sin `INTERNET`) en verde.
- **Lint: `0 errors, 20 warnings`**. El único aviso en archivos de este lote es `ViewConstructor` de `GameControlsView` (ya existía).
- `ProcessKillTest` (`saves/`, ajeno al lote) depende de la carga del Mac: en varias corridas con el load average entre 140 y 360 (otros lotes compilando) falló por su umbral (`kills en bucle` / `guardados completados`); sola pasa con poca carga (`kills en bucle=199, guardados completados=132`) y la suite completa del árbol limpio final (`20f5939`) pasó con 432 de 432.

### Instrumentadas desde el árbol limpio (emulador `Small_Phone_API_35`, arrancado dentro de `with-lock.sh emu`)
`cd /private/tmp/claude-501/n2a-clean/android && ./gradlew --no-daemon --max-workers=1 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=<paquete>` (o `class=`):

| Paquete / clase | Pruebas | Fallos |
|---|---|---|
| `com.joelbermudez.pocketgb.input` (`GameControlsViewTest` 14, `ControlsTalkBackTest` 15) | 29 | 0 |
| `com.joelbermudez.pocketgb.emulator` (incluye `EmulatorSessionTest` 15 con la combinación táctil + mando sin opuestas) | 44 | 0 |
| `com.joelbermudez.pocketgb.game` (incluye `ControlsEditorTest` 6) | 97 | 1 (`SaveCyclesTest`, ver abajo) |
| `com.joelbermudez.pocketgb.ui` (incluye `ControlsSettingsN2Test` 1, `HighContrastTest` 2, `TouchTargetTest` 6) | 84 | 0 |
| `CatalogCoverageTest` | 9 | 0 |
| `DebugCatalogTest` | 11 | 0 |
| **Total** | **274** | **1** |

- El fallo es `game.SaveCyclesTest.hundredOpenPlayPauseSaveStateExitCycles` («ciclo 26: el guardado pendiente no se confirmó solo»), una prueba de 100 ciclos con esperas por tiempo que corrió con el load average del Mac en 341 (otros lotes). Es del área de partidas, que este lote no toca. **Repetida sola** desde el mismo árbol limpio: `1 prueba, 0 fallos` (`BUILD SUCCESSFUL in 1m 17s`).
- Antes de la limpieza hubo dos fallos más por la carga, ambos repetidos en verde: `EmulatorSessionTest.fastForwardAdvancesFasterAndPauseRemainsABarrier` (rendimiento ×4) y `LibraryUiTest.detailsShowStatsContinueFromTheSave...` (esperaba el texto «hace 2 h»), y una corrida de `ControlsTalkBackTest` con 4 fallos por esperas de 250 ms con el Mac saturado; sola, con menos carga, 15 de 15.

Nuevas instrumentadas de este lote: solo se ilumina el brazo pulsado y el centro de la cruz no (píxeles de un `Bitmap`), solo el círculo pulsado en las flechas (diagonal = dos), un toque dentro de cada círculo (8 puntos al 90 % del radio, tres modos) da su dirección por la vista real (H1), diagonal a 25° según el modo, temblor ±8 dp por la vista real, háptica solo al activar una dirección nueva, táctil ↑ + mando ↓ → ninguna (H2, `EmulatorSessionTest`), nodo de TalkBack de la cruceta con flechas separadas a 0,7/1,0/1,5 (≥ 48 dp, envuelve los cuatro círculos, sus cuatro acciones siguen), editor (separación en pasos del 10 % entre 70 y 150 %, por orientación, persistida, Restablecer a 100 %; la fila no aparece con la cruz) y Ajustes (Diagonales por defecto en «Reducidas», cada opción actualiza el ajuste). `HighContrastTest` se adaptó: con 30 % sobre blanco el relleno sigue siendo translúcido (luminancia > 100; la capa oscura nueva lo baja de > 150), y el contraste alto usa su esquema.

### Criterio objetivo: temblor de ±8 dp sobre el brazo ↑ (reducidas)
`DpadModelTest.jitterOfEightDpAroundTheUpArmGivesOnlyUpWithReducedDiagonals`: traza determinista (congruencial lineal con semilla fija), 4000 muestras uniformes en un cuadrado de ±8 dp (a 2,625 px/dp) alrededor del centro del brazo ↑ (cruz) y del círculo ↑ (flechas), arrastre continuo por `TouchInputEngine` (con histéresis):
```
TEMBLOR ±8 dp sobre ↑ (CROSS, reducidas, 4000 muestras): solo ↑ en 100.00 %
TEMBLOR ±8 dp sobre ↑ (ARROWS, reducidas, 4000 muestras): solo ↑ en 100.00 %
```
Además (todas ≥ 99 %): los cuatro brazos y ambos estilos con tamaño de cruceta 0,8–1,6 × global 0,85–1,15; las flechas con separación 0,7, 0,85, 1,0, 1,25 y 1,5; con «Desactivadas», 100 % en todos. La misma traza pasa por la vista real en el emulador. Honestidad sobre el criterio: a ±8 dp el peor caso (15,5° de la vertical) cabe también en el sector de 22,5° de «Normales»; no distingue por sí solo ni cubre el borde del círculo (era H1). Lo que sí distingue es un temblor mayor: con ±20 dp sobre ↑, solo ↑ en **95,7 %** con «Reducidas» frente a **84,6 %** con «Normales» (`withAWiderTremorReducedDiagonalsStillBeatNormalOnes`).

### Cobertura del círculo con flechas separadas (H1)
`ArrowsTouchTest.everyTouchInsideAnArrowCircleGivesThatDirection...`: 300 puntos uniformes por círculo × 4 círculos × 9 separaciones (0,7 a 1,5 en pasos de 0,1) × 3 modos de diagonales × 3 tamaños/densidades = **97 200 toques; el 100 % da la dirección de su círculo** (por `ControlGeometry.dpadMask` y por `TouchInputEngine.pointerDown`; solo se omiten los toques que, con el control al máximo, caen en B por el orden de `hit`). Antes de la corrección la auditoría midió sobre 50 000 toques en el círculo ↑ con «Reducidas»: 95,0 % a separación 1,0, 85,3 % a 0,8 y 79,8 % a 0,7. Además: retención del dedo hasta 4 dp y suelta a 6 dp; zona muerta ≤ borde interior − 2 dp con separación 0,7/0,8/1,0/1,5.

### Contraste
`ControlsRenderOptionsTest`. Colores del tema (WCAG, luminancia relativa; el símbolo neutro ahora es `onSurface`):
```
CONTRASTE estándar        pulsado/neutro = 5.52, símbolo neutro = 7.27, símbolo pulsado = 7.65
CONTRASTE contraste medio pulsado/neutro = 8.86, símbolo neutro = 15.13, símbolo pulsado = 7.11
CONTRASTE contraste alto  pulsado/neutro = 12.05, símbolo neutro = 15.13, símbolo pulsado = 11.13
```
(Antes: `0x4A4D57` frente a `0x1F2024`, unos 1,95:1.) Pulsado frente a neutro **compuesto** como se dibuja (capa oscura, fondo del disco, brazo; brazo de la cruz / círculo de flechas) sobre un fotograma de juego uniforme:
```
opacidad 30 %: [negro] 9.42 / 9.50  [gris 64] 7.39 / 6.99  [gris 128] 5.29 / 4.57  [blanco] 2.65 / 1.98
opacidad 50 %: [negro] 8.51 / 8.73  [gris 64] 7.22 / 6.75  [gris 128] 5.89 / 4.97  [blanco] 3.90 / 2.67
opacidad 70 %: [negro] 7.54 / 7.77  [gris 64] 6.84 / 6.47  [gris 128] 6.16 / 5.25  [blanco] 4.88 / 3.41
opacidad 100 %: [negro] 6.28 / 6.34  [gris 64] 6.10 / 5.78  [gris 128] 5.92 / 5.30  [blanco] 5.61 / 4.45
```
**Etiquetas y símbolos compuestos** (H3: START/SELECT neutro / pulsado frente a su relleno, con alfa 1; antes, con `onSurfaceVariant` al 70 %, 3,75:1 al 70 % sobre gris 128):
```
opacidad 30 %: [negro] 14.88 / 6.43  [gris 64] 10.57 / 6.66  [gris 128] 6.69 / 6.88  [gris 224] 3.34 / 7.24  [blanco] 2.72 / 7.33
opacidad 50 %: [negro] 13.67 / 6.43  [gris 64] 10.32 / 6.59  [gris 128] 7.36 / 6.81  [gris 224] 4.36 / 7.11  [blanco] 3.71 / 7.26
opacidad 70 %: [negro] 12.17 / 6.43  [gris 64]  9.89 / 6.59  [gris 128] 7.78 / 6.79  [gris 224] 5.40 / 7.03  [blanco] 4.80 / 7.16
opacidad 100 %: [negro] 9.94 / 6.43  [gris 64]  8.85 / 6.58  [gris 128] 7.94 / 6.72  [gris 224] 6.76 / 6.96  [blanco] 6.38 / 7.03
```
Garantía probada: etiqueta neutra ≥ 4,5:1 con cualquier opacidad sobre negro a gris 128 (al 70 % sobre gris 128: 7,78) y la pulsada ≥ 4,5:1 sobre cualquier fondo; en contraste alto, también.

### Decisiones pendientes de Joel
- **H4 (baja): contraste pulsado/neutro con controles casi transparentes sobre fotogramas claros.** Con 30–50 % de opacidad sobre un fotograma blanco puro el contraste compuesto pulsado/neutro baja a 2,65 (cruz) y 1,98 (flechas) al 30 %, y a 3,90 y 2,67 al 50 %; el relleno neutro translúcido se funde con el fondo claro. Se cumple ≥ 3:1 con cualquier opacidad sobre fondos de negro a gris medio y con 70 % o más (el predeterminado) sobre cualquier fondo, incluido blanco. **No se cambió el diseño.** Joel decide: (a) aceptar la excepción (la opacidad baja es elección suya y lo pulsado se sigue viendo por color, símbolo y relleno 0,92), o (b) fijar un mínimo de relleno neutro (p. ej. 0,5 de opacidad efectiva) a costa de que 30 % se vea menos transparente.

### Persistencia y migración
`GameplaySettingsTest`: un archivo en el formato anterior (sin `diagonalMode` ni `separation`) carga con «Reducidas» y 1,0 y conserva todo lo demás (opacidad, visibilidad, disposición, por juego, mapeo del mando); ida y vuelta de los campos nuevos; un `diagonalMode` inválido cae solo a «Reducidas»; **un `separation` inválido, un control desconocido, un punto o una escala dañados pierden solo eso y conservan las posiciones y escalas válidas de esa orientación** (H7, con el archivo exacto del hallazgo); la separación se recorta a 0,7–1,5; el archivo anterior no se aparta.

### Capturas (`tools/android-screenshots.sh`, `Small_Phone_API_35` 720×1280, 320 dpi)
Salida en `android/build/screenshots-n2/<id>-<orientación>-<tema>.png` (no se versiona). **Recapturadas tras las correcciones** con el APK de `7e6d056` (las 25) y miradas con la herramienta de lectura de imágenes (hojas de contacto y recortes a tamaño real); se comprobó que las horizontales salieron 1280×720. El juego va siempre oscuro (K4): «claro/oscuro» solo aplica a Ajustes y al fotograma de juego blanco (`rom=light`).

| Id (variante) | Debe verse | Se ve | Resultado |
|---|---|---|---|
| `n2-cross-up` (O, vertical) | Disco con cruz; solo el brazo ↑ iluminado | Cruz con los cuatro triángulos, brazo ↑ verde, el resto y el centro neutros; contorno fino; texto de A/B/Select/Start nítido y claro | ✅ |
| `n2-cross-diagonal` (O) | ↑ y → iluminados, centro no | Dos brazos verdes, el centro oscuro | ✅ |
| `n2-cross-up-landscape` (O, horizontal) | Igual sobre la imagen del juego | Cruz sobre el borde de la imagen, brazo ↑ verde, capa oscura ceñida al contorno | ✅ |
| `n2-cross-up-high-contrast` (O) | Contraste alto: relleno sólido, anillo claro | Disco con borde claro, brazos sólidos, Select/Start con anillo | ✅ |
| `n2-cross-up-light-frame`, `n2-cross-up-opacity30` (O, horizontal, fotograma claro) | Controles legibles sobre gris claro; con 30 % casi transparentes pero ↑ visible | **Sin el halo gris de antes (H6)**; texto de Select/Start/B nítido; con 30 % los neutros son manchas tenues, el brazo ↑ sigue verde | ✅ (límite H4 documentado) |
| `n2-arrows-up` (O) | Cuatro círculos en rombo con flechas Material; solo ↑ | Rombo limpio, círculo ↑ verde con símbolo oscuro, los otros neutros | ✅ |
| `n2-arrows-diagonal` (O) | ↑ y → iluminados | Dos círculos verdes | ✅ |
| `n2-arrows-up-landscape` (O, horizontal) | Igual en horizontal | Rombo sobre el borde de la imagen, ↑ verde | ✅ |
| `n2-arrows-up-high-contrast` (O) | Círculos sólidos con borde claro | Rellenos opacos, ↑ casi blanco con símbolo oscuro | ✅ |
| `n2-arrows-up-light-frame`, `n2-arrows-up-opacity30` (O, horizontal) | Igual que la cruz | Igual; con 30 % sobre claro los círculos neutros se funden más que la cruz (1,98:1, H4) | ✅ con reserva |
| `n2-arrows-separation-min` (O) | Separación 0,7: círculos casi tocándose, sin solaparse | Rombo compacto con hueco mínimo | ✅ |
| `n2-arrows-separation-max`, `n2-arrows-separation-max-landscape` (O) | Separación 1,5: grupo ancho dentro del área segura | Rombo amplio, completo en pantalla, sin tapar A/B | ✅ |
| `n2-editor-arrows`, `n2-editor-arrows-landscape` (O) | Cruceta elegida con contorno de rayas y fila «Separación · 130 %» | Contorno que envuelve el grupo, dos filas − / +, ayuda; en horizontal el borde derecho del grupo roza la barra | ✅ |
| `n2-editor-cross` (O) | Con la cruz no hay fila de separación | Solo «Cruceta · 100 %» | ✅ |
| `n2-settings-controls` (C, O) | Ajustes › Controles con el grupo «Diagonales» | Tres filas de opción (Normales, Reducidas marcada, Desactivadas) con su explicación | ✅ |
| `n2-settings-controls-scrolled` (C, O) | Diagonales con su pie, tras desplazar | Lista completa y pie legibles | ✅ |
| `n2-settings-controls-ax5` (C) | Fuente al 200 % | Pantalla legible; los segmentados existentes de Opacidad y Cruceta se desbordan (anterior a N2) | ✅ para lo nuevo; observación abajo |
| `n2-settings-diagonals-ax5` (C, O) | Solo el grupo Diagonales con fuente al 200 % | Opciones y explicaciones en varias líneas, sin cortes | ✅ |

Defectos encontrados al mirar y corregidos: con tres botones segmentados «Desactivadas» partía en «Desactivad/as» (pasó a lista); la capa oscura de un control tapaba el borde de su vecino (ahora se dibujan todas las capas primero); el halo gris separado del contorno sobre fotogramas claros (H6, de la auditoría: ahora la capa tiene la forma exacta).

## No verificado (a probar por Joel en su teléfono)
- **Tacto real**: la sensación de «al pulsar arriba reacciona toda la cruceta» se corrige con sectores, zona muerta, histéresis, contraste y capa oscura, y se midió con trazas sintéticas y con eventos táctiles inyectados por la vista real. No sustituye al pulgar de Joel con su cruceta y su modo «Siempre»: conviene probar «Reducidas» (por defecto) y «Normales» para comparar.
- **Háptica real** (`CLOCK_TICK`): se comprobó la regla (cuándo se pide), no cómo se siente.
- **TalkBack real**: los nodos virtuales y sus acciones se prueban con el árbol de accesibilidad, sin servicio de TalkBack encendido.
- **Pulgar sobre el borde del juego en horizontal con la separación al máximo**: la cruceta crece hasta 1,32 W y la barra del editor puede quedar sobre ella (se arrastra desde una parte visible).
- **Otros tamaños** (plegable, tablet) y densidades: solo el AVD de 360 × 640 dp; la geometría se prueba en JVM con distintas densidades y zonas bajas.
- **Direcciones opuestas con un mando real**: se prueba la combinación táctil + mando en el núcleo nativo (`EmulatorSessionTest`), no con un mando físico conectado.
- No se reprodujo una captura «antes» (la anterior ya estaba en `gameplay-controls`/`gameplay-portrait-arrows` de A6).

## Observaciones fuera de este lote
- Ajustes › Controles con fuente al 200 %: los botones segmentados de «Opacidad» y «Cruceta» (`ChoiceRow`, existente) se desbordan (visible en `n2-settings-controls-ax5`); no se tocó el componente compartido. Las tres opciones de Diagonales no sufren esto porque son una lista.
- Emulador compartido: con la máquina cargada (load average > 200) la rotación del AVD tarda o no llega y el script de capturas deja pasar una captura vertical con aviso («la pantalla no giró»); las horizontales se repitieron una a una hasta salir en horizontal. El mismo estado de carga explica los fallos por tiempo de `SaveCyclesTest`, `ProcessKillTest`, `EmulatorSessionTest.fastForward...` y `ControlsTalkBackTest` descritos arriba (todos en verde al repetirlos con menos carga).
