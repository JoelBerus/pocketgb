# D4 · Evidencia (gameplay y controles Liquid Glass)

Rama `d2-a1-wip`, CI del runner propio en el Mac de Joel.

## CI
| Commit | Resultado |
|---|---|
| `8090729` | failure: el target de tests no compilaba (llamada `mutating` dentro de `#expect`). |
| `32f8f18` | failure: 70/70 tests unitarios; `ShellTests` no encontraba "Apariencia" (Ajustes tiene más filas y queda bajo el pliegue). |
| `1327624` | failure sin resultado de los tests unitarios (el host no arrancó; el código era el mismo que el de `32f8f18`, salvo el test de UI). Se relanzó una vez → **success**: tests unitarios y de UI en verde, Release sin warnings. |

## Criterios CI (D-README §6)
| Criterio | Verificación |
|---|---|
| Una única vista multitáctil | `ControlsOverlayView`; las vistas de cada control tienen `isUserInteractionEnabled = false` |
| `UIGlassEffect`, sin `UIBlurEffect` | `ControlVisualView` (grep sin `UIBlurEffect`) |
| Scrim localizado; legible al 30 % | Scrim ≥ 0,41 en la forma + 3 pt; etiqueta ≥ 70 % con sombra (`gameplay-landscape-clear`) |
| Área táctil independiente del alpha | La opacidad solo cambia `ControlVisualView`; el hit testing usa `ControlsGeometry` |
| Reduce Transparency sólido ≥ 90 % | Relleno 94 % y borde 1,5 pt (`gameplay-reduce-transparency`) |
| Sin L/R | `noShoulderButtons` |
| 8 sectores, ángulos límite, zona muerta, sin opuestos | `eightSectorsOf45Degrees`, `boundaryAnglesAt22Point5Degrees`, `deadZoneIs25PercentOfRadius`, `neverOppositeDirections` |
| Zona A+B, B→A, D-pad capturado | `abZoneBetweenAAndBPressesBoth`, `twoFingersAAndBAndSlideBToA`, `dpadCapturesItsFingerOutsideTheRadius` |
| Clamp al área segura; 44 pt | `positionsAreClampedInsideTheSafeArea`, `smallControlsKeepA44PointTouchTarget` |
| Layouts vertical/horizontal persistidos por separado | `portraitAndLandscapeLayoutsPersistSeparately` |
| Rotación publica máscara cero | `rotationCancelsEveryFingerWithZeroMask` + `layoutSubviews` suelta todos los dedos al cambiar de tamaño |
| Viewport 10:9 | `GameScreen` (`aspectRatio(10/9)` en vertical; Metal con escala entera en horizontal) |

## Revisión visual
| Captura | Resultado |
|---|---|
| gameplay-portrait | ✅ Imagen 10:9 arriba, controles de vidrio regular sobre `GameplayBackground`, menú entre imagen y controles, anillos A cálido / B frío |
| gameplay-landscape, -clear, -reduce-transparency | ✅ D-pad a la izquierda, A/B a la derecha, Start/Select abajo y menú arriba, dentro del área segura. Las capturas horizontales salen giradas en el PNG del simulador; el contraste real al 30 % se valida en el iPhone |
| gameplay-landscape-hidden | ✅ Solo el juego y el botón de menú |
| customize-controls-portrait / -landscape | ✅ Guías discontinuas del área segura, contorno por control, barra Restablecer / Controles · orientación / Listo |
| settings-controls | ✅ Opacidad 30–100 %, tamaño, mostrar, háptica y restablecer por orientación |
| settings-display | ✅ Vista previa 10:9 nítida, escala entera en horizontal |

## Pendiente del iPhone
A+B con dos dedos, B→A, ocho direcciones, D-pad fuera de su radio, háptica, scrim sobre escenas blancas, Dynamic Island a ambos lados, gestos diferidos, Home Indicator, editor con los dedos y persistencia tras reiniciar.
