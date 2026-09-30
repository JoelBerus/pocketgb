# D7 · Evidencia (accesibilidad y cierre visual)

Rama `d2-a1-wip`, CI del runner propio. Verde en `7f0fd0f`: 93 tests unitarios, UI (incluido `ShellAccessibilityTests`), Release sin warnings. `make -C core test` en Linux: todos los requeridos en PASS.

| Commit | Resultado |
|---|---|
| `4af1bb9` | failure: el test de 44 pt medía el botón de barra del sistema "Más opciones" (36 pt dibujado; iOS gestiona su área). AX5: "Continuar" partido en dos líneas y título cortado. |
| `7f0fd0f` | **success**: el test mide solo controles propios; "Continuar jugando" en columna a todo el ancho con AX5. |

## Criterios CI (D-README §9)
| Criterio | Verificación |
|---|---|
| Cada ID de SPEC en `screens.txt`, sin contradicciones | `testCatalogCoversEverySpecScreenWithoutContradictions` (`gameplay-portrait-hud` sustituida por decisión de Joel, anotado en SPEC §9) |
| Cada PNG publicado | `SUMMARY.md` lista todas las capturas del catálogo |
| Etiquetas y acciones principales | `testLibraryLabelsAndTouchTargets` (card = título + sistema + favorito) |
| Reflow AX5 | `testGridReflowsToOneColumnAtAX5`; captura `library-ax5` |
| Touch targets ≥ 44 pt | Mismo test (cards y tabs) + `ControlsGeometry.touchFrame` (D4) |
| Símbolos decorativos sin lectura duplicada | Cards y filas con `accessibilityElement(children: .ignore)` y etiqueta única; iconos decorativos `accessibilityHidden` |
| Reduce Transparency conserva jerarquía | `PocketGlassPolicy`; capturas `library-reduce-transparency` y `gameplay-reduce-transparency` |
| Reduce Motion sin zoom | `ZoomNavigation` + `PocketMotion.reducesMotion` (D3); el HUD con morph ya no existe (D5) |
| Estados con texto además de color | Chip GB/GBC, "Favorito", "Nuevo", "Dañado", "Global/Personalizado", ×2/×4 |
| Sin red, GBA, cheats ni vidrio simulado | `rg` sin coincidencias en `ios/PocketGB` |

## Pendiente del iPhone
VoiceOver completo (orden de foco en cards, pausa y estados; Magic Tap abre la pausa), Dynamic Type AX1–AX5, Negrita y Aumentar contraste, Reduce Transparency y Reduce Motion reales, Control por botón, legibilidad al sol.
