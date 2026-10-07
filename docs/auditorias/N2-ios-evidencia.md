# N2 🍎 · Evidencia: controles (parte iOS)

Rama `n2-ios-controles` (desde `siguiente-nivel`, `c3d8405`), 2026-10-06. Plan: [N-README §4 N2](../hitos/N-README.md), decisiones ND9 y ND10. Guía: [docs/guia/controles.md](../guia/controles.md).

## 1. Qué cambia
| Pedido de Joel / criterio | Qué se hizo | Dónde |
|---|---|---|
| «Al pulsar arriba siento que pulso todo» (#13) | El motor publica la máscara de direcciones de la cruceta (`ControlsInputEngine.dpadMask`) y el dibujo la recibe: solo se **hunde** el brazo pulsado de la cruz (gris) o se **ilumina** la flecha separada pulsada; en diagonal, dos. Se elimina el escalado 0,9 de toda la cruceta (A/B/Start/Select/L/R lo conservan). | `ControlsOverlayView.updateAppearance/publish`, `ControlVisualView.configure/updatePaths` |
| Diagonales | Diagonal solo a ±15° de 45° con «Reducidas» (por defecto); «Normales» = 8 × 45° (lo de antes); «Desactivadas» = 4 rectas. Ajuste en Ajustes › Controles › Cruceta › Diagonales. El mando físico conserva sus 8 sectores. | `DpadDiagonals`, `DpadDirection.raw`, `ControlsSettingsView` |
| Zona muerta e histéresis | Zona muerta del 30 % del radio; un dedo que ya pulsa se suelta por debajo del 24 % (histéresis radial) y mantiene su dirección hasta 8° más allá del borde de su sector (histéresis angular). Estado por dedo en el motor. | `ControlsGeometry.dpadMask(dx:dy:radius:diagonals:previous:)`, `DpadTuning` |
| Háptica | Solo al cambiar de dirección: volver a la misma dirección con el mismo dedo (p. ej. tras pasar por la zona muerta) no vibra; levantar el dedo la reinicia. | `DpadHapticGate` |
| «El borde se ve extraño» (#9) | Diagnóstico (se ve en las capturas de antes, p. ej. Start/Select al 30 %: anillo oscuro exterior + borde del vidrio): un scrim oscuro 3 pt más grande **debajo** del vidrio, que el vidrio refracta en su borde (anillo oscuro desplazado); además `layer.cornerRadius + clipsToBounds` recortaba el borde del vidrio, y A/B tenían dos bordes (trazo blanco + anillo). Ahora: una sola capa `UIGlassEffect` con `cornerConfiguration = .capsule()` (sin recorte), un único trazo fino uniforme encima (en A/B el anillo de color **es** el borde), sombra suave centrada (`shadowPath`, sin desplazamiento) y, en horizontal, un velo oscuro con la forma exacta **encima** del vidrio para el contraste (no se refracta). Mismo criterio en A/B/Start/Select/L/R. Reduce Transparency: sólido ≥ 90 % con borde de 1,5 pt. | `ControlVisualView` |
| «Mejores flechas» (#10) | `arrowtriangle.{up,right,down,left}.fill` dentro de cada brazo de la cruz (gris oscuro sobre blanco, 0,42 × el grosor del brazo) y en cada flecha separada (blancas, 0,36 × su diámetro): escalan con el control (antes `chevron.*` a 16 pt fijos). La cruz conserva su forma Game Boy con un hundido central sutil. | `ControlVisualView.layoutSubviews` |
| Flechas separadas ajustables (#12, ND10) | Separación 0,7–1,5 por disposición (`ControlsLayout.arrowSpacing`, opcional al decodificar), en el editor con la cruceta elegida y estilo flechas («Separación · N %», − / +). El marco de la cruceta (y con él su zona táctil y su radio) crece o encoge con la separación; con separaciones pequeñas las flechas encogen para no tocarse. «Restablecer» la devuelve a 1,0. Mover el grupo y el tamaño ya existían. | `DpadArrows`, `ControlsGeometry.init(…dpadStyle:)`, `GameplaySettings.respaceArrows`, `ArrowSpacingStepper` |
| Sin cambiar la distribución ni la posición del juego | Las posiciones por defecto, tamaños base y la imagen del juego no cambian; con separación 1 la geometría de las flechas es idéntica a la de antes (test). | — |

Capturas DEBUG nuevas: `-uiPressedDpad <dirección>` simula un dedo **por el motor real** en el centro del brazo (o la flecha) de esa dirección; `-arrowSpacing` y `-dpadDiagonals` fijan esos ajustes en memoria.

## 2. Tests
Unitarios (Swift Testing), simulador iPhone 17 Pro (iOS 26.5), dentro del mutex del simulador:
```
$ xcodebuild test-without-building … -only-testing:PocketGBTests
✔ Test run with 187 tests in 18 suites passed after 10.662 seconds.
** TEST EXECUTE SUCCEEDED **
```
Antes de N2 había 172 tests en 17 suites (`git grep -c "@Test" c3d8405 -- ios/PocketGBTests`). Nuevos, en `DpadInputTests` (15):

| Test | Qué comprueba |
|---|---|
| `reducedDiagonalsOnlyWithin15DegreesOf45` | Fronteras a 29,9°/30,1°, 59,9°/60,1°… en los cuatro cuadrantes; las rectas ocupan 2/3 del círculo. Por defecto, «Reducidas». |
| `normalDiagonalsKeepEightEqualSectors` | «Normales» = los 8 × 45° de antes (22,4° → derecha, 22,6° → diagonal); mitad del círculo rectas. |
| `diagonalsOffGivesOnlyStraightDirections` | Barrido de 3600 ángulos: nunca dos bits; frontera a 45°. |
| `angularHysteresisKeepsTheCurrentDirectionNearTheBorder` | UP se mantiene a 55° (diagonal para un dedo nuevo) y cambia a 51°; la diagonal se mantiene a 66° y cambia a 69°; «Desactivadas» hasta 37°. |
| `radialHysteresisReleasesBelow24Percent` | Dedo nuevo a 19 pt (< 30 % de 70) no pulsa; el mismo dedo ya en UP sigue a 19 pt y se suelta a 16,5 pt. |
| `tremblingOnABorderDoesNotFlicker` | 200 muestras temblando ±3° sobre la frontera de 60°: siempre UP. |
| `jitterOf8PointsAroundTheUpArmGivesOnlyUp` | **Criterio objetivo.** Traza continua de 10 000 muestras (SplitMix64, semilla fija) con temblor uniforme de ±8 pt en cada eje alrededor del centro del brazo ↑, con «Reducidas»: solo UP en ≥ 99 % en las 4 disposiciones (GB/GBA × vertical/horizontal, incluida la cruceta al 60 % de GBA horizontal) y en los dos estilos. Además, con toques sueltos (sin histéresis) y ±13 pt en la cruz de 140 pt: «Reducidas» ≥ 99 % y los sectores de 45° de antes < 98 %. |
| `engineHighlightsOnlyThePressedDirection` | `dpadMask == UP`, `pressed == [.dpad]`; a 55° sigue UP; en la zona muerta el dedo sigue capturado pero no se ve pulsado. |
| `engineFollowsTheDiagonalsSetting` | A 45° exactos: diagonal con «Normales»/«Reducidas», una sola dirección con «Desactivadas». |
| `hapticFiresOnlyWhenTheDirectionChanges` | Traza `0, ↑, ↑, 0, ↑, ↗, ↑, (levantar), ↑` → vibra solo en el primer ↑, ↗, ↑ y el ↑ del dedo nuevo. |
| `arrowSpacingDefaultsClampsAndDecodesOldLayouts` | 1 por defecto; 9 → 1,5; 0,1 → 0,7; NaN → 1; un layout guardado sin `arrowSpacing` se lee con 1. |
| `separatedFrameAndArrowsFollowTheSpacing` | Para 0,7…1,5: marco = 140 × `extentFactor`, el centro no se mueve, cada flecha a 44,8 × separación pt del centro, vecinas sin tocarse y dentro del marco; con 1, idéntico a antes (círculos de 50,4 pt); la cruz ignora la separación. |
| `touchZoneFollowsEachArrow` | Separación 0,7/1/1,5 × tamaño 0,6/1/1,6: el centro y el borde exterior de cada flecha caen en la zona táctil de la cruceta y pulsan su dirección. |
| `spacingIsSavedPerLayoutAndResetGoesBackTo100Percent` | Pasos exactos del 10 %, topes 0,7/1,5, cada disposición por separado, persiste en `UserDefaults` (también «Diagonales»), «Restablecer» devuelve 1 solo en su disposición. |
| `diagonalsSettingDecodesOldAndInvalidValues` | Ajustes antiguos o con un valor desconocido → «Reducidas»; ida y vuelta JSON. |

Actualizados en `ControlsLayoutTests`: los de 8 sectores y 22,5° pasan a probar «Normales»; `deadZoneIs30PercentOfRadius` (antes 25 %) en los tres modos; `neverOppositeDirections` barre los tres modos con y sin histéresis.

**Mutación (el test del criterio puede fallar):** con el algoritmo de antes (zona muerta 25 %, sin histéresis, sectores de 45°) el test sale en rojo:
```
✘ Test jitterOf8PointsAroundTheUpArmGivesOnlyUp() recorded an issue at DpadInputTests.swift:143:17: Expectation failed: (ratio → 0.9565) >= 0.99
↳ landscape GBA .cross: solo UP en 95.65 %
```
(con la cruceta normal de 140 pt el algoritmo de antes también aguantaba ±8 pt; por eso se añadió la comprobación de ±13 pt con toques sueltos). Revertido; con el código final, verde.

UI (XCTest), `ShellControlsTests.testDpadAccessibilityAndArrowSpacingEditor` (nuevo): con flechas separadas, el elemento `control-dpad` existe con la etiqueta «Cruceta» y ≥ 44 pt, y existen `control-a/b/start/select`; en el editor, tocar la cruceta muestra «Separación de las flechas: 100 por ciento», «Más separadas» pasa a 110 % y el marco accesible de la cruceta crece (> +4 pt); «Restablecer» vuelve a 100 % y al ancho inicial; «Listo» cierra el editor.
```
Test Case '-[PocketGBUITests.ShellControlsTests testDpadAccessibilityAndArrowSpacingEditor]' passed (21.073 seconds).
```

Ejecución completa del script del CI (`c6d15e6` + docs; mutex del simulador), unitarios + UI + catálogo + Release del simulador genérico:
```
$ tools/ios-screenshots.sh <scratchpad>/n2-full
✔ Test run with 187 tests in 18 suites passed after 10.386 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (1345.817 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testCatalogCoversEverySpecScreenWithoutContradictions]' passed (0.134 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testGridReflowsToOneColumnAtAX5]' passed (6.880 seconds).
Test Case '-[PocketGBUITests.ShellAccessibilityTests testLibraryLabelsAndTouchTargets]' passed (6.349 seconds).
Test Case '-[PocketGBUITests.ShellControlsTests testDpadAccessibilityAndArrowSpacingEditor]' passed (21.193 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testChooseFolderOpensPicker]' passed (12.151 seconds).
Test Case '-[PocketGBUITests.ShellFolderPickerTests testUnavailableFolderOffersChooseAgain]' passed (6.551 seconds).
Test Case '-[PocketGBUITests.ShellLibraryTests testDetailsAndHideGame]' passed (14.773 seconds).
Test Case '-[PocketGBUITests.ShellLinkTests testSwitchAndExitTheCable]' passed (13.337 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (23.435 seconds).
** TEST SUCCEEDED **
xcodebuild Release: exit 0
xcodebuild test: exit 0
```
128 PNG (106 del catálogo anterior + 22 de N2), sin errores ni avisos del proyecto en el log. Los tests de accesibilidad existentes siguen en verde; 10 tests de UI (antes 9).

Tras el último commit (`e258382`: texto del editor y orden de brazos) se repitieron los unitarios (`187 tests in 18 suites passed`), `ShellControlsTests` (`passed (21.671 seconds)`) y las 12 capturas del editor y de separación. Release para dispositivo:
```
$ xcodebuild build -project ios/PocketGB.xcodeproj -scheme PocketGB -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO
** BUILD SUCCEEDED **
```
Otras comprobaciones: `git diff --check c3d8405` sin salida; `grep -rnE 'URLSession|NWConnection|NSAppTransportSecurity' ios` sin resultados (exit 1). `core/` y `gba/` no se tocan.

## 3. Capturas revisadas
Todas miradas a ojo (PNG del simulador, las horizontales giradas para revisarlas), comparadas con la descripción de Joel: ¿doble borde?, ¿flechas nítidas y proporcionadas?, ¿solo ↑ marcado? Copia local (ignorada por git): `build/screenshots-n2/`.

**Antes (ci-shots/cierre-integracion @ c36c846) → después:**
- `antes-despues-cruceta-vertical.png`: arriba la cruz (antes / ahora en reposo / ahora con ↑), abajo las flechas. Antes, `chevron` de 16 pt fijos en las flechas y ninguna en la cruz; ahora triángulos proporcionales, hundido central y solo ↑ marcado.
- `antes-despues-select-start-horizontal.png`: Select/Start sobre el blanco del juego al 30 % (antes: anillo oscuro exterior + borde del vidrio = doble borde; ahora un solo borde con sombra suave y el mismo contraste de la etiqueta; también al 70 %), y la cruz en horizontal.

| Captura | Lo que se ve |
|---|---|
| `gameplay-dpad-up` vertical oscuro/claro | Solo el brazo ↑ gris con su triángulo; el resto blanco; el círculo, A, B, Start y Select sin cambios ni escala. Un único trazo fino alrededor del círculo, sin anillo desplazado. En claro el juego sigue oscuro (decisión D1). ✅ |
| `gameplay-dpad-up` horizontal oscuro/claro | Igual sobre el juego; B, que pisa el borde de la imagen, con un solo anillo de color; Start/Select legibles sobre el blanco. ✅ |
| `gameplay-dpad-upright` | Brazos ↑ y → grises, el centro blanco. ✅ |
| `gameplay-dpad-up-clear` (30 %) | Cruz, Start y Select legibles sobre el blanco gracias al velo; sin anillo exterior. ✅ |
| `gameplay-dpad-up-reduce-transparency` | Superficies sólidas oscuras con borde blanco de 1,5 pt; solo ↑ gris. ✅ |
| `gameplay-arrows-up` vertical/horizontal, oscuro/claro | Solo el círculo ↑ más claro; los cuatro triángulos blancos, nítidos y del mismo tamaño relativo. ✅ |
| `gameplay-arrows-upright` | Círculos ↑ y → iluminados. ✅ |
| `gameplay-arrows-up-reduce-transparency` | Cuatro círculos sólidos con borde de 1,5 pt; ↑ más claro. ✅ |
| `gameplay-arrows-spacing-70` | Rombo compacto: las flechas encogen un poco y no se tocan. ✅ |
| `gameplay-arrows-spacing-150` vertical | Flechas separadas, la izquierda a ~8 pt del borde, sin salirse; no llega a B. ✅ |
| `gameplay-arrows-spacing-150` horizontal | La flecha → entra un poco sobre la imagen (la cruceta por defecto está pegada al borde izquierdo y el marco crece hacia dentro); legible por el velo. Es lo esperado al separar al máximo: el grupo se puede mover. ✅ (anotado) |
| `gameplay-gba-dpad-up` / `gameplay-gba-arrows-up` | Cruceta al 60 % en el margen de la imagen 3:2: triángulos pequeños pero visibles, solo ↑ marcado; L/R con un solo borde. ✅ |
| `customize-controls-dpad` | Con la cruz elegida solo aparece «Cruceta · 100 %» (sin separación). ✅ |
| `customize-controls-arrows` vertical/horizontal | «Cruceta · 100 %» y «Separación · 100 %» con − / +; en horizontal el texto de ayuda queda dentro del ancho de la imagen (antes de `e258382` cruzaba sobre la cruceta, también en `customize-controls-landscape`). ✅ |
| `settings-controls` claro/oscuro | Fila «Diagonales: Reducidas» bajo el estilo de cruceta, pie explicativo. ✅ |
| `settings-controls-ax5` | «Diagonales» y «Reducidas» con reflow a dos líneas, sin cortes. ✅ |

También revisadas sin regresiones (hojas de contacto): `gameplay-portrait`, `-portrait-arrows`, `-landscape`, `-landscape-clear`, `-landscape-arrows`, `-reduce-transparency`, `-gba-landscape-clear`, `-gba-reduce-transparency`, `-link-portrait`, `-link-reduce-transparency`, `customize-controls-portrait/-landscape/-size/-gba-*`. Misma distribución y posición del juego que antes.

## 4. Lo que no se verificó aquí
- **Prueba de Joel en el iPhone** (pendiente): tacto real de la cruceta con «Reducidas», sensación de la histéresis y de la háptica, y el aspecto del vidrio en el dispositivo (el simulador dibuja el vidrio, pero los reflejos del borde con la luz real pueden variar). Si en el iPhone siguiera viéndose un borde raro, el siguiente paso sería quitar también el trazo blanco y dejar solo el borde del propio vidrio.
- VoiceOver real en el dispositivo (los elementos y etiquetas se comprueban en `ShellControlsTests`).
- La parte Android de N2 va en su propio lote.

## 5. Decisiones y desviaciones
- **Velo encima del vidrio** en lugar de solo una sombra: con solo la sombra centrada, Select/Start al 30 % perdían contraste sobre el blanco (comparado con la captura de antes). El velo tiene la forma exacta, así que no crea un segundo borde. Se documenta en SPEC §6.1 y §10.3.
- **Histéresis también radial** (suelta al 24 %): el plan pide «zona muerta del 30 % con histéresis»; se aplica a la frontera de la zona muerta y a las fronteras entre sectores (8°).
- **Flechas que encogen** con separaciones < ~0,86: con 0,7 y el diámetro de siempre, dos flechas vecinas se solaparían.
- **Separación al máximo en horizontal:** el marco crece alrededor del centro guardado; con la cruceta por defecto pegada al borde, la flecha → entra un poco sobre la imagen. No se mueve el centro automáticamente para no cambiar la disposición; la guía explica que el grupo se puede mover.
- `ScreenshotTests`: `SCREEN_FILTER` admite varios trozos separados por comas (solo para iterar; el CI no lo define).
- Editor en horizontal: el texto de ayuda se limita al ancho de la imagen (antes ya tapaba la cruceta; con el estilo flechas es más largo).
- `ESTADO.md` y la tabla de hitos no se tocan en este lote (los actualiza quien integra en `siguiente-nivel`).

