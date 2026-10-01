#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
adb_bin="$sdk_root/platform-tools/adb"
out_dir="${1:-android/build/screenshots}"
package="com.joelbermudez.pocketgb"
activity="$package/.MainActivity"

if [[ ! -x "$adb_bin" ]]; then
  echo "No se encontró adb en $adb_bin" >&2
  exit 1
fi

if ! "$adb_bin" get-state >/dev/null 2>&1; then
  echo "No hay un dispositivo Android conectado" >&2
  exit 1
fi

mkdir -p "$out_dir"
screens=(library-empty library-grid favorites-empty settings-main appearance about)

for theme in light dark; do
  for screen in "${screens[@]}"; do
    "$adb_bin" shell am force-stop "$package"
    "$adb_bin" shell am start -W -n "$activity" \
      --es screen "$screen" \
      --es theme "$theme" \
      --ez dynamicColor false \
      --ef fontScale 1.0 >/dev/null
    sleep 1
    "$adb_bin" shell cmd statusbar collapse >/dev/null 2>&1 || true
    "$adb_bin" exec-out screencap -p > "$out_dir/${screen}-${theme}.png"
  done
done

printf 'Capturas generadas: %s\n' "${#screens[@]} × 2"
