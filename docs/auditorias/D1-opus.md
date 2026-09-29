# D1 · Auditoría (subagente Opus de respaldo, sin historial del desarrollo)

Auditado: commit `96750f0`, con `docs/auditorias/PROMPT.md`, el CI de macOS (run 36609588264) y sus 15 capturas. Codex no está disponible en la nube.

## Veredicto: APROBAR CON CAMBIOS

No encontré nada bloqueante. El CI de macOS está en verde, con 0 errores y 0 warnings en Debug y en Release. Se cumplen las reglas duras 1, 2, 5 y 6, y las doce capturas de D1 no tienen defectos visuales bloqueantes. Los cambios que pido son de cobertura de tests y de contraste.

## Hallazgos
| ID | Severidad | Archivo | Problema | Corrección sugerida |
|---|---|---|---|---|
| H1 | media | `ShellTests.swift`, `LibraryView.swift`, `PocketGBApp.swift` | Ningún test cubre la única forma de jugar en Release: el menú `…` → "Abrir un archivo…", y "Elegir carpeta" → aviso → "Abrir un archivo". El segundo camino presenta la sheet desde un botón de `.alert` mientras la alerta se cierra, y eso puede fallar sin avisar. | Añadir UI tests de los dos caminos que esperen el selector de documentos. |
| H2 | media | `AccentPrimary` dark `#5A96FF`, `.tint` | En oscuro, el texto blanco sobre `#5A96FF` tiene un contraste de unos 2,9:1. Afecta a los CTA y al "Continuar" de la pausa. Es una pequeña regresión frente al azul del sistema. | Oscurecer el acento dark (p. ej. `#3F7FEF`) y anotarlo en SPEC, o aplazarlo a D7 de forma explícita. |
| H3 | baja | `ScreenshotTests.swift` | El test solo detecta un `-screen` desconocido. No valida que el nombre de la captura coincida con `-screen`. | Si la línea lleva `-screen X`, exigir `name == X`. |
| H4 | baja | `AppState.swift`, `PocketGBApp.swift`, `LaunchGlyphView.swift` | `debugShowsLaunch`, `debugUnknownScreen`, `UnknownScreenView` y `LaunchPreviewView` se compilan en Release, donde son código muerto. | Opcional: envolverlos en `#if DEBUG`. |
| H5 | baja | `PocketGBApp.swift` | Las alertas que salen durante el juego heredan el modo claro si el usuario lo eligió. Ninguna captura prueba gameplay en light. | Forzar oscuro mientras hay sesión y añadir `game-acid portrait light`. |
| H6 | baja | D-README §3 frente a SPEC §9 | `launch` en light y dark genera dos PNG idénticos; SPEC define `launch` solo en dark. | Alinear los dos documentos. |

## Criterios del hito
| Criterio | Verificado | Resultado |
|---|---|---|
| Compila con Swift 6 / iOS 26 | sí (SUMMARY, `SWIFT_STRICT_CONCURRENCY = complete`) | ✅ Sin errores ni warnings, también en Release |
| `TabView` con `Tab` ×3 | sí | ✅ |
| `.tabBarMinimizeBehavior(.onScrollDown)` | sí | ✅ La minimización real se ve en el iPhone |
| Settings usa `Form` | sí | ✅ |
| Assets light/dark con los nombres de SPEC | sí (12 `Contents.json`) | ✅ Los hex coinciden con SPEC §7.1 |
| Sin vidrio simulado | sí | ✅ |
| Sin GBA/L-R/cheats/artwork online; sin red | sí | ✅ Solo falsos positivos (`PocketGBApp`, `RGBA8888`) |
| Doce capturas sin clipping | sí (15 PNG revisados) | ✅ Contraste del CTA en dark: ver H2 |
| `ScreenshotTests` falla con un ID desconocido | parcial | ✅ para `-screen`; ver H3 |
| Router DEBUG fuera de Release | sí | ✅ Quedan restos inertes: ver H4 |
| Concurrencia Swift 6 | sí | ✅ |
| Gameplay, persistencia y SRAM intactos | sí | ✅ No se tocan `Saves/`, `Emulator/`, `Input/` ni `core/` |
| Transitorio "Abrir un archivo…" | sí | ✅ Documentado en SPEC §4 y escondido en el menú `…`; ver H1 |

## Notas
- `game-acid-landscape` sale igual que en `main`: es un artefacto del CI que ya existía y lo rediseñan D4 y D5.
- No se puede verificar desde aquí (queda para el iPhone): el Liquid Glass real, el launch screen real, el paso alerta → selector de documentos en el dispositivo y la barra de estado en un dispositivo real.
