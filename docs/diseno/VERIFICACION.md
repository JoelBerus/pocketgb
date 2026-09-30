# Verificación visual (nube + CI de macOS)

Claude en la nube no tiene Xcode ni simulador. La UI se verifica así:

## Ciclo por lote de cambios
1. Implementar un lote coherente (una pantalla o un componente con sus estados) y **añadir sus ids a `ios/PocketGBUITests/screens.txt`** (orientación y light/dark).
2. `git push` a la rama del hito. El workflow `iOS` (`.github/workflows/ios.yml`) compila, corre núcleo + tests unitarios + catálogo de capturas y publica el resultado en la rama huérfana `ci-shots/<rama>`.
3. Esperar el resultado (≈10–15 min) y traerlo:
   ```bash
   B=$(git branch --show-current)
   until git fetch -q origin "ci-shots/$B" 2>/dev/null && git log -1 --format=%B FETCH_HEAD | grep -q "$(git rev-parse --short=7 HEAD)"; do sleep 60; done
   rm -rf /tmp/shots && mkdir /tmp/shots && git archive FETCH_HEAD | tar -x -C /tmp/shots
   cat /tmp/shots/SUMMARY.md
   ```
   (Si la sesión tiene `gh`, `gh run watch` evita el sondeo.)
4. Leer `SUMMARY.md`: **cero errores y cero warnings nuevos del proyecto**, todos los tests en verde.
5. **Mirar cada PNG** (herramienta Read) contra la checklist de la pantalla en [SPEC.md](SPEC.md) y contra la maqueta de [propuesta.html](propuesta.html). Anotar en `docs/auditorias/Dn-evidencia.md` cada captura revisada: ✅ o el defecto encontrado.
6. Corregir y repetir. Un hito de diseño no se cierra con capturas que tengan defectos conocidos.

## Errores visuales que bloquean (revisar en cada captura)
- Texto cortado, truncado sin intención, solapado o fuera del safe area (Dynamic Island, home indicator).
- Controles ilegibles sobre el juego (contraste: deben leerse sobre fondo blanco **y** negro).
- Vidrio sobre vidrio, vidrio en contenido (portadas, viewport) o fondos opacos donde iOS 26 pone vidrio.
- Light/dark: elementos invisibles o colores fijos que no cambian de modo.
- Imagen del juego deformada (debe ser 10:9) o con filtrado borroso (debe ser nearest).
- Tamaños táctiles < 44 pt; controles que se salen de la pantalla en horizontal.
- Diferencias con la propuesta no justificadas en SPEC.md.

## Runner (2026-09-29)
El CI corre en un **runner propio en el Mac de Joel** (`mac-joel`, etiquetas `self-hosted, macOS, pocketgb`): sin coste. Si el Mac está apagado o dormido, el job queda en cola y corre al despertar; tenlo en cuenta al esperar capturas. Una sola corrida a la vez.

## Ahorro de tiempo del runner
El runner es el Mac de Joel: no lo satures. Agrupar cambios y hacer **un push por lote**; no hacer push de cambios solo de documentación con código iOS pendiente (el workflow solo corre si cambian `ios/`, `core/`, el script o el workflow). `workflow_dispatch` permite relanzar a mano.

## Lo que el CI no ve (se valida en el iPhone de Joel, en una sesión en el Mac)
Tacto real (multitoque, deslizar entre botones), háptica, audio, rendimiento a 60 fps, iCloud Drive real, mandos físicos y la sensación del vidrio en movimiento. Cada hito lista estos puntos como "pendiente del iPhone".

## Accesibilidad en el catálogo (D7)
- `-reduceTransparency`: la política `PocketGlassPolicy` (y los controles UIKit) cambia el vidrio propio por superficies sólidas y botones `.bordered`. La navegación del sistema solo adopta el fallback con el ajuste real del iPhone.
- `-contentSizeCategory accessibility5`: Dynamic Type AX5 fijo (`library-ax5`); la cuadrícula pasa a una columna y la línea de metadatos a columna.
- `-reduceMotion`: sin zoom portada → detalle.
- `ShellAccessibilityTests`: todos los IDs de SPEC §9 están en `screens.txt` sin contradicciones, etiquetas de las cards, áreas ≥ 44 pt y reflow con AX5.

## Registro de revisión visual final (D8, 2026-09-30)
Run del catálogo completo sobre el código final: `7f0fd0f` (`ci-shots/d2-a1-wip`, 84 capturas; los commits posteriores solo tocan `docs/`). Claude revisó las 84 en hojas de contacto:

| Grupo | Capturas | Resultado |
|---|---|---|
| Arranque y carpeta | launch, library-no-folder, library-folder-unavailable, library-empty | ✅ |
| Biblioteca | library-grid, -list, -continue, -cloud-pending, -cloud-downloading, -scan-progress, -scan-summary, -rom-error, save-data-error | ✅ |
| Búsqueda y detalle | search-active, -results, -no-results, game-details, game-context-menu, remove-game-confirm, favorites, game-settings | ✅ (en `search-active` claro sale el aviso del teclado bilingüe del simulador) |
| Gameplay | gameplay-portrait, -landscape, -landscape-clear, -landscape-hidden, -reduce-transparency, -portrait-arrows, -landscape-arrows, -controller, -fast-forward, game-acid ×3, game-paused | ✅ (las horizontales salen giradas en el PNG del simulador) |
| Editor | customize-controls-portrait, -landscape, -size | ✅ |
| Pausa y estados | gameplay-pause, save-states, load-state-confirm, replace-state-confirm | ✅ |
| Ajustes | settings-main, -appearance, -about, -library, -controls, -display, -audio, -emulation, -storage, -saves | ✅ |
| Accesibilidad | library-reduce-transparency, library-ax5 | ✅ (AX5: una columna; en la captura el botón Continuar queda bajo la tab bar porque el contenido se desplaza) |
