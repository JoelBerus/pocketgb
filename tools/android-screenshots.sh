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
screens=(library-empty library-grid library-list library-search library-error library-loading library-access-error library-detail library-detail-problem favorites-empty favorites settings-main settings-library appearance about native-video gameplay-controls gameplay-fast-forward library-detail-played pause-sheet pause-dialog states-sheet states-dialog exit-save-failed exit-risk save-warning open-error saves-settings save-problem states-rescue)

# SCREENS="pause-sheet states-sheet" limita la ejecución a esas pantallas (útil al iterar).
if [[ -n "${SCREENS:-}" ]]; then read -r -a screens <<< "$SCREENS"; fi

set_rotation() {
  # 0 = vertical, 1 = horizontal (los diálogos del menú de pausa solo se usan en horizontal).
  "$adb_bin" shell settings put system accelerometer_rotation 0
  "$adb_bin" shell settings put system user_rotation "$1"
}
trap 'set_rotation 0' EXIT

for theme in light dark; do
  for screen in "${screens[@]}"; do
    if [[ "$screen" == *-dialog ]]; then set_rotation 1; else set_rotation 0; fi
    "$adb_bin" shell am force-stop "$package"
    "$adb_bin" shell am start -W -n "$activity" \
      --es screen "$screen" \
      --es theme "$theme" \
      --ez dynamicColor false \
      --ef fontScale 1.0 >/dev/null
    sleep 3
    if [[ "$screen" == "native-video" ]]; then
      sleep 4
    fi
    "$adb_bin" shell cmd statusbar collapse >/dev/null 2>&1 || true
    "$adb_bin" exec-out screencap -p > "$out_dir/${screen}-${theme}.png"
  done
done

printf 'Capturas generadas: %s\n' "${#screens[@]} × 2"
