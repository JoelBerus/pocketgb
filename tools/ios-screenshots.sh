#!/usr/bin/env bash
# Compila la app, corre los tests unitarios y el catálogo de capturas (ios/PocketGBUITests/screens.txt).
# Uso: tools/ios-screenshots.sh [carpeta_salida]   (por defecto build/screenshots)
# Requiere macOS + Xcode 26 y las ROMs de prueba (tools/fetch-test-roms.sh). Lo usa el CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(cd "$ROOT" && mkdir -p "${1:-build/screenshots}" && cd "${1:-build/screenshots}" && pwd)"
# Simulador: SIM_DEVICE si existe; si no, el iPhone Pro más nuevo disponible.
UDID="$(xcrun simctl list devices available -j | python3 -c '
import json,os,re,sys
want=os.environ.get("SIM_DEVICE","iPhone 17 Pro")
devs=[]
for rt,ds in json.load(sys.stdin)["devices"].items():
    m=re.search(r"iOS-(\d+)-(\d+)",rt)
    if not m: continue
    for d in ds:
        if d["name"].startswith("iPhone"): devs.append(((int(m[1]),int(m[2])),d))
devs.sort(key=lambda x:x[0],reverse=True)
pick=[d for v,d in devs if d["name"]==want] or [d for v,d in devs if "Pro" in d["name"]] or [d for v,d in devs]
print(pick[0]["udid"]+" "+pick[0]["name"])
')"
echo "Simulador: $UDID"
UDID="${UDID%% *}"
# ROMs de prueba fuera de ~/Documents: macOS (TCC) bloquea ahí al simulador.
FIX="$(mktemp -d)"; trap 'rm -rf "$FIX"' EXIT
cp "$ROOT/core/tests/roms/dmg-acid2/dmg-acid2.gb" "$FIX/"
# Cable link (M9): el segundo juego del par (cgb-acid2, libre).
cp "$ROOT/core/tests/roms/cgb-acid2/cgb-acid2.gbc" "$FIX/"
# Game Boy Advance: arm.gba de jsmolka/gba-tests (MIT), homebrew libre.
"$ROOT/tools/fetch-gba-test-roms.sh" --solo-jsmolka
cp "$ROOT/gba/tests/roms/gba-tests/arm/arm.gba" "$FIX/"
rm -rf "$OUT"/*.png "$OUT/result.xcresult"
cd "$ROOT"
set +e
TEST_RUNNER_SCREENSHOT_DIR="$OUT" TEST_RUNNER_FIXTURE_DIR="$FIX" \
  xcodebuild test -project ios/PocketGB.xcodeproj -scheme PocketGB \
  -destination "id=$UDID" \
  -derivedDataPath build/DerivedData -resultBundlePath "$OUT/result.xcresult" \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee "$OUT/xcodebuild.log" | grep -E '(error|warning): |Test (Suite|Case).*(passed|failed)|✔|✘|BUILD|TEST' | grep -v appintents
STATUS=${PIPESTATUS[0]}
# Release: el router y los argumentos DEBUG no deben existir (si algo los usa fuera de
# #if DEBUG, este build falla). Sin firma, para el simulador genérico.
xcodebuild build -project ios/PocketGB.xcodeproj -scheme PocketGB -configuration Release \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee -a "$OUT/xcodebuild.log" | grep -E '(error|warning): |BUILD' | grep -v appintents
RELEASE=${PIPESTATUS[0]}
echo "xcodebuild Release: exit $RELEASE"
[ "$STATUS" -eq 0 ] && STATUS=$RELEASE
set -e
ls "$OUT"/*.png 2>/dev/null | sed "s|$OUT/||" || true
echo "xcodebuild test: exit $STATUS"
exit "$STATUS"
