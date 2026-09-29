#!/usr/bin/env bash
# Extrae del SDK de iOS las firmas de las APIs de diseño (Liquid Glass, tabs, toolbars...)
# a docs/diseno/API-iOS26.md, para que Claude en la nube (sin SDK) no invente APIs. Solo macOS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK=$(xcrun --sdk iphoneos --show-sdk-path)
FW=$SDK/System/Library/Frameworks
PAT='glassEffect|GlassEffect|struct Glass|static var (regular|clear|identity)|func tint\(|func interactive|tabBarMinimizeBehavior|TabBarMinimizeBehavior|scrollEdgeEffect|ScrollEdgeEffect|ToolbarSpacer|searchToolbarBehavior|SearchToolbarBehavior|tabViewBottomAccessory|backgroundExtensionEffect|GlassButtonStyle|GlassProminentButtonStyle|static var glass|matchedTransitionSource|navigationTransition|NavigationTransition|static func zoom|tabViewSearchActivation|presentationDetents|presentationBackground|ContentUnavailableView\('
clean() { grep -hE "$PAT" "$1" | grep -v '^\s*//' | sed -E 's/@_[A-Za-z]+(\([^)]*\))? //g; s/@available\([^)]*\) //g; s/@backDeployed\([^)]*\) //g; s/nonisolated //g; s/^[[:space:]]+//' | grep -vE '^@|_outputs|interactiveSpring|typealias Body' | sort -u; }
{
  echo "# APIs de iOS 26 para el diseño (extraídas del SDK)"
  echo
  echo "Generado con $(xcodebuild -version | tr '\n' ' ')desde \`$(basename "$SDK")\`. La nube no tiene SDK: **usa solo firmas que aparezcan aquí o que ya compilen en el repo**; si necesitas otra, anótala como duda y compruébala con el CI. Regenerar en el Mac: \`tools/extract-ios-apis.sh\`."
  for m in SwiftUICore SwiftUI; do
    echo; echo "## $m"; echo '```swift'
    clean "$FW/$m.framework/Modules/$m.swiftmodule/arm64e-apple-ios.swiftinterface"
    echo '```'
  done
  echo; echo "## UIKit (vidrio para ControlsOverlayView)"; echo '```objc'
  grep -E 'UIGlass|@property|- \(|\+ \(|typedef NS_ENUM|Style(Regular|Clear)' "$FW/UIKit.framework/Headers/UIGlassEffect.h" | grep -vE '^\s*(//|\*|/\*)' | sed -E 's/^[[:space:]]+//; s/ API_AVAILABLE\([^)]*\)//g; s/ API_UNAVAILABLE\([^)]*\)//g' | sort -u
  echo '```'
} > "$ROOT/docs/diseno/API-iOS26.md"
wc -l "$ROOT/docs/diseno/API-iOS26.md"
