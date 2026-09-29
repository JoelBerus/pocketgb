# D1 · Evidencia (fundamentos visuales)

Generada en Claude Code en la nube el 2026-09-29, rama `claude/amazing-babbage-lgip5y` desde `main` (`47b7f12`). El commit con el código de D1 es `96750f0`. El CI de macOS corrió sobre ese commit: run https://github.com/JoelBerus/pocketgb/actions/runs/36609588264 (Xcode 26.6, 17F113). Las capturas están en la rama `ci-shots/claude/amazing-babbage-lgip5y`.

## 1. Chequeos en Linux (D-README §2.2)
- `git diff --check`: sin errores.
- `rg 'GBA|\.gba|Cheat|cheat|URLSession|NWConnection|NSAppTransportSecurity' ios`: solo falsos positivos, que son subcadenas de `PocketGBApp` y `RGBA` en comentarios. `ShellTests` comprueba que "GBA" no aparece.
- `rg '\.blur\(|UIBlurEffect|Material\.' ios/PocketGB`: sin coincidencias.

## 2. CI (`SUMMARY.md`)
```
resultado: success
## Errores y warnings del proyecto
(ninguno)
✔ Test adaptiveColorsChangeInDarkMode() passed
✔ Test routerKnowsD1Screens() passed
✔ Test allColorAssetsExist() passed
✔ Test run with 14 tests in 3 suites passed after 0.301 seconds.
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (258.796 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (14.308 seconds).
** TEST SUCCEEDED **
```
Build Release (`log-tail.txt`): `** BUILD SUCCEEDED **` para `Release-iphonesimulator/PocketGB.app`. El router y los argumentos DEBUG solo existen dentro de `#if DEBUG`.

## 3. Criterios de CI de D1 (D-README §3)
| Criterio | Resultado |
|---|---|
| Compila con Swift 6 / iOS 26 | ✅ 0 errores y 0 warnings (Debug y Release) |
| `TabView` con `Tab` Biblioteca/Favoritos/Ajustes | ✅ `LibraryRootView.swift`; `ShellTests` encuentra las tres |
| `.tabBarMinimizeBehavior(.onScrollDown)` | ✅ `LibraryRootView.swift` |
| Settings usa `Form` | ✅ `SettingsView`, `AppearanceSettingsView` y `AboutView` |
| Assets light/dark con los nombres de SPEC | ✅ 12 color sets; `DesignTokensTests` comprueba que existen y que los adaptativos cambian en oscuro |
| Sin `.blur`, `UIBlurEffect` ni materiales | ✅ sección 1 |
| Sin GBA, L/R, cheats ni artwork online | ✅ sección 1 y capturas |
| Las once capturas existen y sin clipping (`launch` solo dark; Codex M8-D1 H2) | ✅ sección 4 |
| `ScreenshotTests` falla con un ID desconocido | ✅ el router muestra `debug-unknown-screen` y el test lo comprueba |
| Router DEBUG excluido de Release | ✅ build Release en el CI |

## 4. Revisión visual (cada PNG, 1206×2622, iPhone 17 Pro simulado)
| Captura | Resultado |
|---|---|
| launch · dark | ✅ Glifo centrado sobre `GameplayBackground`, sin texto ni spinner (la variante light se quitó: SPEC lo pide siempre oscuro) |
| library-no-folder · light | ✅ Título grande, icono, explicación, CTA "Elegir carpeta" con vidrio prominente, tab bar nativa y botón `…`. Sin "Abrir ROM" |
| library-no-folder · dark | ✅ Mismo contenido; el CTA usa el acento oscuro `#5A96FF` |
| library-empty · light / dark | ✅ Nombra la carpeta, explica el estado y ofrece "Volver a escanear" y "Cambiar carpeta" |
| settings-main · light / dark | ✅ Lista nativa agrupada. Las secciones de D4–D6 aparecen como "Próximamente" en gris, sin navegar |
| settings-appearance · light / dark | ✅ Sistema/Claro/Oscuro, y el footer explica que el gameplay es siempre oscuro |
| settings-about · light / dark | ✅ Versión 0.4 (1), núcleo, consolas, privacidad sin red, aviso de ROMs propios y licencias de terceros |
| game-acid · portrait / landscape, game-paused | ✅ Sin regresión respecto a M5: gameplay oscuro y barra de estado oculta. La captura landscape sale con los píxeles en orientación de sensor, como antes |

Observaciones que no bloquean:
- En dark, el CTA azul claro con texto blanco tiene menos contraste que en light. Es el token de SPEC; se revisará en el iPhone y en D7 (accesibilidad).
- Los dos botones de library-empty abren de momento el mismo aviso. El escaneo real llega en D2.

## 5. Pendiente del iPhone (🍎)
- Tab bar con Liquid Glass real y su minimización al desplazar.
- Launch screen real (`UILaunchScreen` → `GameplayBackground`).
- Contraste con brillo alto y bajo.
- Que la barra de estado se vea en biblioteca y ajustes y se oculte en el juego.
- Que "Abrir un archivo…" (menú `…`) siga abriendo ROMs sueltos.

## 6. Tras la auditoría (commits `119f1e7` y `3ed4f76`)
- Run https://github.com/JoelBerus/pocketgb/actions/runs/36613054415 sobre `3ed4f76`: **success**, sin errores ni warnings del proyecto.
```
✔ Test run with 14 tests in 3 suites passed
Test Case '-[PocketGBUITests.ScreenshotTests testScreenCatalog]' passed (253.224 seconds).
Test Case '-[PocketGBUITests.ShellOpenFileTests testFolderNoticeOpensFilePicker]' passed (24.638 seconds).
Test Case '-[PocketGBUITests.ShellOpenFileTests testMenuOpensFilePicker]' passed (14.219 seconds).
Test Case '-[PocketGBUITests.ShellTests testTabsAndSettingsNavigation]' passed (23.614 seconds).
** TEST SUCCEEDED **   ·   Release: ** BUILD SUCCEEDED **
```
- En el run intermedio `119f1e7`, los dos tests del selector fallaron al lanzar la app ("background assertion" agotado). Eran los primeros UI tests tras los unitarios, que usan la app como host. Pasan desde que corren después del catálogo (`ShellOpenFileTests`).
- Capturas revisadas otra vez: `library-no-folder`/`library-empty` en dark con el acento `#3F7FEF` ✅; `game-acid portrait light` sigue oscuro ✅; `launch` solo en dark ✅.

## 7. iPhone (🍎)
2026-09-29: Joel instaló la rama en su iPhone y confirma que funciona bien: tabs con Liquid Glass, launch oscuro, barra de estado (visible en biblioteca y ajustes, oculta en el juego), "Abrir un archivo…" y apariencia Claro/Oscuro con el juego siempre oscuro.
