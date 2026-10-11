# Landing de PocketGB

Página estática en español e inglés, publicada con GitHub Pages por `.github/workflows/pages.yml` en <https://joelberus.github.io/pocketgb/>. Sin dependencias, sin paso de compilación y sin peticiones externas.

- `index.html`: la página, con el texto en español (se ve sin JavaScript). Cada texto traducible lleva `data-i18n="clave"` o `data-i18n-attr="atributo:clave"`.
- `assets/i18n/en.js`: el inglés, una clave por línea. `?lang=en` o el selector ES/EN lo aplican.
- `assets/js/landing.js`: idioma, tema claro/oscuro, pestañas, aparición al desplazar y botón de copiar.
- `assets/shots/`: capturas reales en WebP, generadas con `tools/landing-shots.py`.
- `MOCKS.md`: lo que aún es provisional (tiendas, precio).

## Probar en local
```bash
python3 -m http.server 8000 -d site      # http://localhost:8000/ y http://localhost:8000/?lang=en
python3 tools/landing-shots.py --check   # referencias, claves ES/EN, pesos y caracteres prohibidos
```

## Regenerar las capturas
Salen de los catálogos de la app con ROMs de prueba libres (nunca de juegos comerciales):
```bash
# iOS (simulador iPhone 17 Pro); el filtro limita el catálogo a las pantallas de la landing
TEST_RUNNER_SCREENSHOT_DIR="$PWD/build/landing-shots/ios" TEST_RUNNER_FIXTURE_DIR=<carpeta con dmg-acid2.gb, cgb-acid2.gbc y arm.gba> \
TEST_RUNNER_SCREEN_FILTER="n4-home,n5-library,game-details,gameplay-dpad-up,gameplay-landscape,customize-controls-portrait,game-gba,gameplay-link-portrait,n6-moments-pause,n7-detail-save,n9-guide" \
xcodebuild test -project ios/PocketGB.xcodeproj -scheme PocketGB -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:PocketGBUITests/ScreenshotTests/testScreenCatalog -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO

# Android (emulador Small_Phone_API_35 con la build Debug instalada)
SCREENS="n5-library n5-details-imported n8-gameplay n8-gameplay-landscape n6-moments settings-controls settings-controller-mapping n9-guide" \
THEMES="light dark" tools/android-screenshots.sh build/landing-shots/android

python3 tools/landing-shots.py --og      # WebP en site/assets/shots y la imagen para compartir
```
Revisa cada captura antes de publicarla: ningún título comercial a la vista.
