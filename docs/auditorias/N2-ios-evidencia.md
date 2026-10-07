# N2 🍎 · Evidencia: controles (parte iOS)

Rama `n2-ios-controles` (desde `siguiente-nivel`, `c3d8405`), 2026-10-06/07. Plan: [N-README §4 N2](../hitos/N-README.md), decisiones ND9 y ND10. Guía: [docs/guia/controles.md](../guia/controles.md). Auditoría Opus: [N2-ios-opus](N2-ios-opus.md) (APROBAR CON CAMBIOS) y [respuesta](N2-ios-respuesta.md); esta evidencia ya recoge las correcciones y las reglas comunes con Android que fijó el orquestador.

## 1. Qué cambia
| Pedido de Joel / criterio | Qué se hizo | Dónde |
|---|---|---|
| «Al pulsar arriba siento que pulso todo» (#13) | El motor publica la máscara de direcciones de la cruceta (`ControlsInputEngine.dpadMask`) y el dibujo la recibe: solo se marca el brazo pulsado de la cruz (gris oscuro opaco con el triángulo blanco) o la flecha separada pulsada (disco blanco al 90 % con el triángulo oscuro); en diagonal, dos. Contraste pulsado/neutro ≥ 3:1 (H1). Se elimina el escalado 0,9 de toda la cruceta (A/B/Start/Select/L/R lo conservan). | `ControlsOverlayView.updateAppearance/publish`, `ControlVisualView.configure/updatePaths`, `ControlPalette` |
| Diagonales | Diagonal solo a ±15° de 45° con «Reducidas» (por defecto); «Normales» = 8 × 45° (lo de antes); «Desactivadas» = 4 rectas. Ajuste en Ajustes › Controles › Cruceta › Diagonales. El mando físico conserva sus 8 sectores. | `DpadDiagonals`, `DpadDirection.raw`, `ControlsSettingsView` |
| Zona muerta e histéresis | Zona muerta del 30 % del radio; un dedo que ya pulsa se suelta por debajo del 24 % (histéresis radial) y mantiene su dirección hasta 8° más allá del borde de su sector (histéresis angular). Estado por dedo en el motor. Con flechas separadas, todo el disco de cada flecha pulsa su dirección y la zona muerta acaba 2 pt antes de su borde interior (H2). Nunca llegan direcciones opuestas al núcleo, tampoco con dos dedos ni con dedo y mando (H3). | `ControlsGeometry.dpadMask`, `DpadTuning`, `DpadDirection.withoutOpposites`, `EmulatorSession.combinedButtons` |
| Háptica | Regla común con Android: vibra cuando se activa una dirección que no estaba activa (↑ → ↑→ sí; ↑→ → ↑ no; ↑ → nada → ↑ sí). | `DpadHapticGate` |
| «El borde se ve extraño» (#9) | Diagnóstico (se ve en las capturas de antes, p. ej. Start/Select al 30 %: anillo oscuro exterior + borde del vidrio): un scrim oscuro 3 pt más grande **debajo** del vidrio, que el vidrio refracta en su borde (anillo oscuro desplazado); además `layer.cornerRadius + clipsToBounds` recortaba el borde del vidrio, y A/B tenían dos bordes (trazo blanco + anillo). Ahora: una sola capa `UIGlassEffect` con `cornerConfiguration = .capsule()` (sin recorte), un único trazo fino uniforme encima (en A/B el anillo de color **es** el borde), sombra suave centrada (`shadowPath`, sin desplazamiento) y, en horizontal, un velo oscuro con la forma exacta **encima** del vidrio para el contraste (no se refracta). Mismo criterio en A/B/Start/Select/L/R. Reduce Transparency: sólido ≥ 90 % con borde de 1,5 pt. | `ControlVisualView` |
| «Mejores flechas» (#10) | `arrowtriangle.{up,right,down,left}.fill` dentro de cada brazo de la cruz (gris oscuro sobre blanco, 0,42 × el grosor del brazo) y en cada flecha separada (blancas, 0,36 × su diámetro): escalan con el control (antes `chevron.*` a 16 pt fijos). La cruz conserva su forma Game Boy con un hundido central sutil. | `ControlVisualView.layoutSubviews` |
| Flechas separadas ajustables (#12, ND10) | Separación k de 0,7 a 1,5 por disposición (`ControlsLayout.arrowSpacing`, opcional al decodificar), en el editor con la cruceta elegida y estilo flechas («Separación · N %», − / +). Fórmula común con Android: diámetro fijo 0,36 W; centro de cada flecha a 0,32 W × k con k ≥ 1 y, por debajo, en línea recta hasta 0,265 W con k = 0,7 (nunca se solapan ni encogen). El marco de la cruceta (y con él su zona táctil y su radio) crece o encoge con la separación. «Restablecer» la devuelve a 1,0. Mover el grupo y el tamaño ya existían. | `DpadArrows`, `ControlsGeometry.init(…dpadStyle:)`, `GameplaySettings.respaceArrows`, `ArrowSpacingStepper` |
| Sin cambiar la distribución ni la posición del juego | Las posiciones por defecto, tamaños base y la imagen del juego no cambian; con separación 1 la geometría de las flechas es idéntica a la de antes (test). | — |

Capturas DEBUG nuevas: `-uiPressedDpad <dirección>` simula un dedo **por el motor real** en el centro del brazo (o la flecha) de esa dirección; `-arrowSpacing` y `-dpadDiagonals` fijan esos ajustes en memoria.

## 2. Tests
Unitarios (Swift Testing) en el simulador iPhone 17 Pro (iOS 26.5), dentro del mutex del simulador, al final de la rama (`418e99f`):
```
$ xcodebuild test-without-building … -only-testing:PocketGBTests
✔ Test run with 195 tests in 19 suites passed after 10.873 seconds.
** TEST EXECUTE SUCCEEDED **
```
Antes de N2 había 172 tests en 17 suites (`git grep -c "@Test" c3d8405 -- ios/PocketGBTests`). Nuevos: 19 en `DpadInputTests` y 4 en `DpadContrastTests`.

| Test | Qué comprueba |
|---|---|
| `reducedDiagonalsOnlyWithin15DegreesOf45` | Fronteras a 29,9°/30,1°, 59,9°/60,1°… en los cuatro cuadrantes; las rectas ocupan 2/3 del círculo. Por defecto, «Reducidas». |
| `normalDiagonalsKeepEightEqualSectors` | «Normales» = los 8 × 45° de antes (22,4° → derecha, 22,6° → diagonal); mitad del círculo rectas. |
| `diagonalsOffGivesOnlyStraightDirections` | Barrido de 3600 ángulos: nunca dos bits; frontera a 45°. |
| `angularHysteresisKeepsTheCurrentDirectionNearTheBorder` | UP se mantiene a 55° (diagonal para un dedo nuevo) y cambia a 51°; la diagonal se mantiene a 66° y cambia a 69°; «Desactivadas» hasta 37°. |
| `radialHysteresisReleasesBelow24Percent` | Dedo nuevo a 19 pt (< 30 % de 70) no pulsa; el mismo dedo ya en UP sigue a 19 pt y se suelta a 16,5 pt. |
| `tremblingOnABorderDoesNotFlicker` | 200 muestras temblando ±3° sobre la frontera de 60°: siempre UP. |
| `jitterOf8PointsAroundTheUpArmGivesOnlyUp` | **Criterio objetivo.** Traza continua de 10 000 muestras (SplitMix64, semilla fija) con temblor uniforme de ±8 pt en cada eje alrededor del centro del brazo ↑ (o de la flecha ↑), con «Reducidas»: solo UP en ≥ 99 % en las 4 disposiciones (GB/GBA × vertical/horizontal, incluida la cruceta al 60 % de GBA horizontal) y en los dos estilos, cada uno con su geometría real (`dpadStyle: .separated` en flechas; se comprueba `dpadArrowSpacing`). Además, con toques sueltos (sin histéresis) y ±13 pt en la cruz de 140 pt: «Reducidas» ≥ 99 % y los sectores de 45° de antes < 98 %. |
| `restingOffAxisDiscriminatesReducedFromNormal` | Traza continua (con histéresis) que distingue los modos: pulgar apoyado en el brazo ↑ girado 26° y temblor de ±4 pt → ≥ 99 % UP con «Reducidas» y ≤ 5 % con «Normales». |
| `engineHighlightsOnlyThePressedDirection` | `dpadMask == UP`, `pressed == [.dpad]`; a 55° sigue UP; en la zona muerta el dedo sigue capturado pero no se ve pulsado. |
| `engineFollowsTheDiagonalsSetting` | A 45° exactos: diagonal con «Normales»/«Reducidas», una sola dirección con «Desactivadas». |
| `hapticFiresWhenADirectionTurnsOn` | Regla común: ↑ sostenido vibra una vez; ↑ → ↑→ vibra; ↑→ → ↑ no; ↑ → nada → ↑ sí; cada dirección nueva vibra. |
| `arrowSpacingDefaultsClampsAndDecodesOldLayouts` | 1 por defecto; 9 → 1,5; 0,1 → 0,7; NaN → 1; un layout guardado sin `arrowSpacing` se lee con 1. |
| `separatedFrameAndArrowsFollowTheSpacing` | Para k de 0,7 a 1,5: marco = 140 × `extentFactor`, el centro no se mueve, cada flecha a 44,8 × k pt del centro (k ≥ 1) o entre 37,1 y 44,8 pt en línea recta (k < 1), diámetro siempre 50,4 pt, vecinas sin tocarse y dentro del marco; con 1, idéntico a antes; la cruz ignora la separación. |
| `wholeDiscOfEachArrowPressesItsDirection` | H2: el disco completo de cada flecha (21 radios × 36 ángulos, incluidos los bordes interior y laterales) pulsa su dirección con separación 0,7/1/1,5, tamaño 0,6/1/1,6 y los tres modos de diagonal; el centro no pulsa; la zona muerta acaba 2 pt antes del borde interior; el hueco junto al borde interior de ↑ pulsa ↑. Sustituye a `touchZoneFollowsEachArrow`, que solo probaba el centro y el borde exterior. |
| `tremblingOnTheSideOfAnArrowDoesNotFlicker` | Separación 0,7: 2000 muestras temblando ±2 pt sobre el borde lateral de ↑, siempre UP. |
| `twoFingersOnOppositeArmsNeverSendOpposites` | H3: ↑ y ↓ a la vez → nada; con → además → solo →; al levantar ↓ → ↑→. Barrido de las 1024 máscaras de `withoutOpposites`: nunca opuestas y los demás botones intactos. |
| `sessionNeverSendsOppositeDirectionsToTheCore` | H3: `combinedButtons` y, extremo a extremo con `FakeCore`, lo que recibe el núcleo con táctil ↑+A y mando ↓+←: A+←. |
| `spacingIsSavedPerLayoutAndResetGoesBackTo100Percent` | Pasos exactos del 10 %, topes 0,7/1,5, cada disposición por separado, persiste en `UserDefaults` (también «Diagonales»), «Restablecer» devuelve 1 solo en su disposición. |
| `diagonalsSettingDecodesOldAndInvalidValues` | Ajustes antiguos o con un valor desconocido → «Reducidas»; ida y vuelta JSON. |
| `DpadContrastTests.pressedCrossArmContrastsAtLeast3To1` | H1: con los colores de `ControlPalette`, cruz en reposo frente a brazo pulsado ≥ 3:1 en vertical, horizontal al 30/50/70/100 % y Reduce Transparency, sobre los fondos medidos y sobre blanco; triángulo blanco del brazo pulsado ≥ 4,5:1. |
| `DpadContrastTests.pressedArrowContrastsAtLeast3To1` | H1: flecha en reposo frente a pulsada ≥ 3:1 en los mismos casos; triángulo oscuro de la pulsada ≥ 4,5:1. |
| `DpadContrastTests.previousColorsFailedTheThreshold` | Los colores de antes daban 1,68:1 (cruz en horizontal) y 2,39:1 (flechas): el test no es tautológico. |
| `DpadContrastTests.contrastMathMatchesWCAG` | Negro/blanco 21:1, #777/blanco 4,48:1, composición alfa. |

Actualizados en `ControlsLayoutTests`: los de 8 sectores y 22,5° pasan a probar «Normales»; `deadZoneIs30PercentOfRadius` (antes 25 %) en los tres modos; `neverOppositeDirections` barre los tres modos con y sin histéresis.

**Mutaciones (los tests pueden fallar):**
- Criterio de ±8 pt con el algoritmo de antes (zona muerta 25 %, sin histéresis, sectores de 45°, en el test del criterio): el test sale en rojo con dos incidencias (las mismas cifras que reprodujo la auditoría):
  ```
  ✘ Test jitterOf8PointsAroundTheUpArmGivesOnlyUp() recorded an issue at DpadInputTests.swift:150:21: Expectation failed: (ratio → 0.9565) >= 0.99
  ↳ .landscape GBA .cross: solo UP en 95.65 %
  ✘ Test jitterOf8PointsAroundTheUpArmGivesOnlyUp() recorded an issue at DpadInputTests.swift:158:9: Expectation failed: (Self.upRatio(gb, diagonals: .normal, amplitude: 13, separateTaps: true) → 0.9646) >= 0.99
  ✘ Test run with 1 test in 1 suite failed after 0.184 seconds with 2 issues.
  ```
  Con la cruceta normal de 140 pt el algoritmo de antes también aguanta ±8 pt; por eso existen la comprobación de ±13 pt con toques sueltos y `restingOffAxisDiscriminatesReducedFromNormal`.
- H2, sin la comprobación de los discos (medido en `39fd0e0`, con la fórmula de separación anterior): `wholeDiscOfEachArrowPressesItsDirection` falla con 24 incidencias (`failures → 620` con separación 0,7 y «Normales», `104` con 1,0 y «Reducidas»… y `hueco junto al borde interior de ↑`).
- H3, con `touch | pad` sin filtrar (en `39fd0e0`): `sessionNeverSendsOppositeDirectionsToTheCore` falla (`combinedButtons(touch: up | a, pad: down | left) → 225 == 33` y `core.lastButtons → 225`).

Todas revertidas; con el código final, verde.

UI (XCTest), `ShellControlsTests.testDpadAccessibilityAndArrowSpacingEditor` (nuevo): con flechas separadas, el elemento `control-dpad` existe con la etiqueta «Cruceta» y ≥ 44 pt, y existen `control-a/b/start/select`; en el editor, tocar la cruceta muestra «Separación de las flechas: 100 por ciento», «Más separadas» pasa a 110 % y el marco accesible de la cruceta crece (> +4 pt); «Restablecer» vuelve a 100 % y al ancho inicial; «Listo» cierra el editor.

Verificación completa al final de la rama (`418e99f`), script del CI dentro del mutex del simulador (unitarios + UI + catálogo + Release del simulador genérico):
```
$ tools/ios-screenshots.sh <scratchpad>/n2-full3
✔ Test run with 195 tests in 19 suites passed after 10.481 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (1352.558 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.191 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (10.037 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (7.469 seconds).
Test Case '-[PocketGBUITests.ShellControlsTests testDpadAccessibilityAndArrowSpacingEditor]' passed (22.104 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (13.548 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (5.936 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (19.455 seconds).
Test Case '-[PocketGBUITests.ShellLinkTests testSwitchAndExitTheCable]' passed (14.179 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (33.168 seconds).
** TEST SUCCEEDED **
xcodebuild Release: exit 0
xcodebuild test: exit 0
```
130 PNG (106 del catálogo anterior + 24 de N2), sin errores ni avisos del proyecto en el log. Los tests de accesibilidad existentes siguen en verde; 10 tests de UI (antes 9). La misma ejecución completa ya había pasado en `39fd0e0` (195 tests, 130 PNG, Release con exit 0) antes de aplicar las reglas comunes.
Release para dispositivo en `418e99f`:
```
$ xcodebuild build -project ios/PocketGB.xcodeproj -scheme PocketGB -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
** BUILD SUCCEEDED **
```
Otras comprobaciones: `git diff --check c3d8405 HEAD` sin salida (H4: ya no hay línea en blanco final en esta evidencia); `grep -rnE 'URLSession|NWConnection|NSAppTransportSecurity' ios` sin resultados (exit 1). `core/` y `gba/` no se tocan.

## 3. Capturas revisadas
Todas miradas a ojo (PNG del simulador, las horizontales giradas para revisarlas), comparadas con la descripción de Joel: ¿doble borde?, ¿flechas nítidas y proporcionadas?, ¿solo ↑ marcado y bien visible? Copia local (ignorada por git): `build/screenshots-n2/`.

**Contraste pulsado/neutro medido en las capturas (H1)** con un script de PIL: media de 9×9 píxeles junto al triángulo del brazo o flecha ↑ (pulsado) y del ↓ (reposo), luminancia WCAG. «Antes» = catálogo de `4b7e38d`; «Ahora» = `418e99f`.

| Captura | Antes (pulsado / reposo → contraste) | Ahora |
|---|---|---|
| `gameplay-dpad-up` vertical (oscuro y claro) | 168 / 255 → 2,38:1 | 82 / 255 → **7,81:1** |
| `gameplay-dpad-up-reduce-transparency` vertical (nueva) | — | 82 / 255 → **7,81:1** |
| `gameplay-arrows-up` vertical | 93 / 30 → 2,53:1 | 232 / 30 → **13,58:1** |
| `gameplay-arrows-up-reduce-transparency` vertical (nueva) | — | 232 / 25 → **14,35:1** |
| `gameplay-dpad-up` horizontal (70 %) | 139 / 182 → 1,68:1 | 82 / 182 → **3,85:1** |
| `gameplay-dpad-up-clear` horizontal (30 %) | 137 / 180 → 1,69:1 | 82 / 180 → **3,77:1** |
| `gameplay-dpad-up-reduce-transparency` horizontal | 168 / 255 → 2,38:1 | 82 / 255 → **7,81:1** |
| `gameplay-arrows-up` horizontal | 78 / 9 → 2,39:1 | 230 / 9 → **15,95:1** |
| `gameplay-arrows-up-reduce-transparency` horizontal | 93 / 24 → 2,70:1 | 232 / 24 → **14,49:1** |

Los «antes» coinciden con los que midió la auditoría.

**Antes (ci-shots/cierre-integracion @ c36c846) → después:**
- `antes-despues-cruceta-vertical.png`: arriba la cruz (antes / ahora en reposo / ahora con ↑), abajo las flechas. Antes, `chevron` de 16 pt fijos en las flechas y ninguna en la cruz; ahora triángulos proporcionales, hundido central y solo ↑ marcado.
- `antes-despues-select-start-horizontal.png`: Select/Start sobre el blanco del juego al 30 % (antes: anillo oscuro exterior + borde del vidrio = doble borde; ahora un solo borde con sombra suave y el mismo contraste de la etiqueta; también al 70 %), y la cruz en horizontal.

| Captura | Lo que se ve |
|---|---|
| `gameplay-dpad-up` vertical oscuro/claro | Solo el brazo ↑ gris oscuro con el triángulo blanco; el resto de la cruz blanca; el círculo, A, B, Start y Select sin cambios ni escala. Un único trazo fino alrededor del círculo, sin anillo desplazado. En claro el juego sigue oscuro (decisión D1). ✅ |
| `gameplay-dpad-up` horizontal oscuro/claro | Igual sobre el juego, el brazo pulsado se distingue a simple vista; B, que pisa el borde de la imagen, con un solo anillo de color; Start/Select legibles sobre el blanco. ✅ |
| `gameplay-dpad-upright` | Brazos ↑ y → gris oscuro, el centro blanco. ✅ |
| `gameplay-dpad-up-clear` (30 %) | Cruz, Start y Select legibles sobre el blanco gracias al velo; sin anillo exterior; brazo pulsado bien visible. ✅ |
| `gameplay-dpad-up-reduce-transparency` vertical y horizontal | Superficies sólidas oscuras con borde blanco de 1,5 pt; solo ↑ marcado. ✅ |
| `gameplay-arrows-up` vertical/horizontal, oscuro/claro | Solo el disco ↑ casi blanco con el triángulo oscuro; los demás oscuros con triángulos blancos nítidos y proporcionados. ✅ |
| `gameplay-arrows-upright` | Discos ↑ y → claros. ✅ |
| `gameplay-arrows-up-reduce-transparency` vertical y horizontal | Cuatro círculos sólidos con borde de 1,5 pt; ↑ casi blanco. ✅ |
| `gameplay-arrows-spacing-70` | Rombo compacto con la fórmula común: flechas del mismo tamaño que con 100 %, muy juntas pero sin tocarse. ✅ |
| `gameplay-arrows-spacing-150` vertical | Flechas separadas, la izquierda a ~8 pt del borde, sin salirse; no llega a B. ✅ |
| `gameplay-arrows-spacing-150` horizontal | La flecha → entra un poco sobre la imagen (la cruceta por defecto está pegada al borde izquierdo y el marco crece hacia dentro); legible por el velo. Es lo esperado al separar al máximo: el grupo se puede mover. ✅ (anotado) |
| `gameplay-gba-dpad-up` / `gameplay-gba-arrows-up` | Cruceta al 60 % en el margen de la imagen 3:2: triángulos pequeños pero visibles, solo ↑ marcado; L/R con un solo borde. ✅ |
| `customize-controls-dpad` | Con la cruz elegida solo aparece «Cruceta · 100 %» (sin separación). ✅ |
| `customize-controls-arrows` vertical/horizontal | «Cruceta · 100 %» y «Separación · 100 %» con − / +; en horizontal el texto de ayuda queda dentro del ancho de la imagen (antes de `e258382` cruzaba sobre la cruceta, también en `customize-controls-landscape`). ✅ |
| `settings-controls` claro/oscuro | Fila «Diagonales: Reducidas» bajo el estilo de cruceta, pie explicativo. ✅ |
| `settings-controls-ax5` | Con `-scrollTo settings-dpad-diagonals` (desplazamiento programático, H5) siempre se ve «Diagonales / Reducidas» con reflow a dos líneas; `-uiAssertHittable` lo comprueba en el test. ✅ |

También revisadas sin regresiones (hojas de contacto): `gameplay-portrait`, `-portrait-arrows`, `-landscape`, `-landscape-clear`, `-landscape-arrows`, `-reduce-transparency`, `-gba-landscape-clear`, `-gba-reduce-transparency`, `-link-portrait`, `-link-reduce-transparency`, `customize-controls-portrait/-landscape/-size/-gba-*`. Misma distribución y posición del juego que antes.

## 4. Lo que no se verificó aquí
- **Prueba de Joel en el iPhone** (pendiente): tacto real de la cruceta con «Reducidas», sensación de la histéresis y de la háptica, el brazo pulsado y el aspecto del vidrio en el dispositivo (el simulador dibuja el vidrio, pero los reflejos del borde con la luz real pueden variar). Si en el iPhone siguiera viéndose un borde raro, el siguiente paso sería quitar también el trazo blanco y dejar solo el borde del propio vidrio.
- VoiceOver real en el dispositivo (los elementos y etiquetas se comprueban en `ShellControlsTests`).
- **«Contraste alto» (criterio de N2) es de Android**: tema de alto contraste y TalkBack. La app de iOS no tiene esa preferencia ni lee «Aumentar contraste» del sistema. Su variante accesible es Reduce Transparency, con capturas en vertical y horizontal (H6).
- Flechas separadas **sobre la imagen clara del juego** (solo si el usuario las mueve encima o separa al máximo en horizontal): ahí el disco en reposo se ve gris claro a través del vidrio y el disco pulsado contrasta menos de 3:1. Con un relleno uniforme no se puede llegar a 3:1 a la vez sobre el margen negro y sobre blanco. Queda la inversión del triángulo (blanco → oscuro) como señal. La cruz sí llega a ≥ 3:1 sobre cualquier fondo, porque su brazo pulsado es opaco.
- La parte Android de N2 va en su propio lote.

## 5. Decisiones y desviaciones
- **Velo encima del vidrio** en lugar de solo una sombra: con solo la sombra centrada, Select/Start al 30 % perdían contraste sobre el blanco (comparado con la captura de antes). El velo tiene la forma exacta, así que no crea un segundo borde. Se documenta en SPEC §6.1 y §10.3.
- **Histéresis también radial** (suelta al 24 %): el plan pide «zona muerta del 30 % con histéresis»; se aplica a la frontera de la zona muerta y a las fronteras entre sectores (8°).
- **Reglas comunes con Android** (decisión del orquestador): fórmula de separación (diámetro fijo, compresión lineal por debajo de 1) y háptica al activarse una dirección nueva. Sustituyen a la primera versión (flechas que encogían y háptica «solo al cambiar con el mismo dedo»). N-README las documenta desde el lote Android.
- **Flechas separadas por disco**: dentro de una flecha manda la flecha; el ángulo solo decide en los huecos. Así el borde lateral de una flecha nunca da diagonal.
- **Separación al máximo en horizontal:** el marco crece alrededor del centro guardado; con la cruceta por defecto pegada al borde, la flecha → entra un poco sobre la imagen. No se mueve el centro automáticamente para no cambiar la disposición; la guía explica que el grupo se puede mover.
- `ScreenshotTests`: `SCREEN_FILTER` admite varios trozos separados por comas (solo para iterar; el CI no lo define); `-uiAssertHittable <id>` nuevo.
- Editor en horizontal: el texto de ayuda se limita al ancho de la imagen (antes ya tapaba la cruceta; con el estilo flechas es más largo).
- `ESTADO.md`, la tabla de hitos y N-README no se tocan en este lote (los actualiza quien integra en `siguiente-nivel`).
