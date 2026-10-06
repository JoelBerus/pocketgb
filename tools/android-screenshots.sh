#!/usr/bin/env bash
# Genera las capturas del catálogo de Android a partir de tools/android-screens.txt (único origen, también lo lee
# CatalogCoverageTest). Una captura por línea: <id> <portrait|landscape> <light|dark|dyn-green|dyn-violet> [clave=valor...].
# Salida: <dir>/<id>-<orientación>-<tema>.png (por defecto android/build/screenshots).
#   SCREENS="id1 id2"  limita a esos ids.   THEMES="light dark"  limita a esos temas.
#   swipe=up (argumento solo del script) desplaza el contenido antes de capturar.
#   window=wide (solo del script, A7): `wm size 1280x800` + `wm density 240` (853 dp, anchura expanded) sin girar la pantalla;
#     al terminar se restauran con `wm size reset` y `wm density reset`.
#   cutout=tall (solo del script, A7): activa el overlay de recorte simulado del sistema; si el AVD no lo trae, se omite el ID
#     con un aviso («requiere AVD con recorte») y se sigue.
#   RESUME=1  salta las capturas que ya existen (para retomar una corrida interrumpida).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
manifest="${MANIFEST:-$script_dir/android-screens.txt}"
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
if [[ ! -f "$manifest" ]]; then
  echo "Falta el manifiesto $manifest" >&2
  exit 1
fi

sdk_level="$("$adb_bin" shell getprop ro.build.version.sdk | tr -d '\r')"
mkdir -p "$out_dir"

# Semillas fijas del color dinámico (Android 12+): el sistema deriva toda la paleta de este color.
seed_hex() {
  case "$1" in
    dyn-green) echo "FF2E7D32" ;;
    dyn-violet) echo "FF6A1B9A" ;;
  esac
}

previous_ime="$("$adb_bin" shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
rotation=""
set_rotation() {
  # 0 = vertical, 1 = horizontal. Solo se toca el ajuste si cambia.
  if [[ "$rotation" != "$1" ]]; then
    "$adb_bin" shell settings put system accelerometer_rotation 0
    "$adb_bin" shell settings put system user_rotation "$1"
    rotation="$1"
    wait_for_rotation
  fi
}
# Espera (hasta 10 s) a que la pantalla haya girado a lo pedido: sin esto la primera captura horizontal sale vertical.
wait_for_rotation() {
  local want="ROTATION_0"
  [[ "$rotation" == 1 ]] && want="ROTATION_90"
  for _ in $(seq 1 20); do
    if "$adb_bin" shell dumpsys window displays | grep -q "mDisplayRotation=$want"; then return; fi
    sleep 0.5
  done
  echo "Aviso: la pantalla no giró a $want" >&2
}
# Ventana ancha (A7): se activa al llegar a un `window=wide` y se restaura al salir y al terminar (trap).
window_mode=""
set_window() {
  if [[ "$window_mode" == "$1" ]]; then return; fi
  if [[ "$1" == wide ]]; then
    "$adb_bin" shell wm size 1280x800
    "$adb_bin" shell wm density 240
    window_mode=wide
    # Con el AVD en 1280x800 la rotación 0 ya es horizontal: no se gira más.
    "$adb_bin" shell settings put system accelerometer_rotation 0
    "$adb_bin" shell settings put system user_rotation 0
    rotation=0
  else
    "$adb_bin" shell wm size reset
    "$adb_bin" shell wm density reset
    window_mode=""
    rotation=0
  fi
  sleep 3
}
# Recorte simulado (A7): overlay de emulación de AOSP; devuelve 1 si el AVD no lo trae.
cutout_overlay="com.android.internal.display.cutout.emulation.tall"
cutout_on=""
set_cutout() {
  if [[ "$cutout_on" == "$1" ]]; then return 0; fi
  if [[ "$1" == tall ]]; then
    if ! "$adb_bin" shell cmd overlay list --user 0 2>/dev/null | grep -q "$cutout_overlay"; then return 1; fi
    "$adb_bin" shell cmd overlay enable --user 0 "$cutout_overlay" >/dev/null
    cutout_on=tall
  else
    "$adb_bin" shell cmd overlay disable --user 0 "$cutout_overlay" >/dev/null 2>&1 || true
    cutout_on=""
  fi
  sleep 3
}
cleanup() {
  "$adb_bin" shell wm size reset >/dev/null 2>&1 || true
  "$adb_bin" shell wm density reset >/dev/null 2>&1 || true
  "$adb_bin" shell cmd overlay disable --user 0 "$cutout_overlay" >/dev/null 2>&1 || true
  "$adb_bin" shell settings put system user_rotation 0 || true
  "$adb_bin" shell settings delete secure theme_customization_overlay_packages >/dev/null 2>&1 || true
  if [[ "$previous_ime" == "null" || -z "$previous_ime" ]]; then
    "$adb_bin" shell settings delete secure show_ime_with_hard_keyboard >/dev/null 2>&1 || true
  else
    "$adb_bin" shell settings put secure show_ime_with_hard_keyboard "$previous_ime" || true
  fi
}
trap cleanup EXIT
# Que el teclado aparezca aunque el emulador tenga teclado físico (search-active).
"$adb_bin" shell settings put secure show_ime_with_hard_keyboard 1
# Sin el aviso del sistema «Viendo en pantalla completa» sobre el juego (el modo inmersivo lo muestra la primera vez).
"$adb_bin" shell settings put secure immersive_mode_confirmations confirmed

# La actividad es la de primer plano y el foco no es la pantalla de arranque ni un diálogo del sistema. Los menús,
# hojas y diálogos propios tienen su ventana con foco (PopupWindow o la ventana de la app), por eso no se exige MainActivity.
app_has_focus() {
  "$adb_bin" shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -q "$package/.MainActivity" || return 1
  local focus
  focus="$("$adb_bin" shell dumpsys window | grep "mCurrentFocus")"
  [[ "$focus" != *"Splash Screen"* && "$focus" != *"Not Responding"* && "$focus" != *"Application Error"* && "$focus" != *"=null"* ]]
}
# Espera (hasta 40 s) a que la actividad tenga el foco: antes hay una pantalla de arranque del sistema.
wait_for_app_focus() {
  for _ in $(seq 1 40); do
    if app_has_focus; then return; fi
    sleep 1
  done
}
# Cierra el diálogo «La app no responde» (el Mac cargado lo provoca): la pulsación de Atrás lo descarta.
dismiss_anr() {
  if "$adb_bin" shell dumpsys window windows | grep -q "Not Responding"; then "$adb_bin" shell input keyevent 4; fi
}

wanted_ids=" ${SCREENS:-} "
count=0
for theme in ${THEMES:-light dark dyn-green dyn-violet}; do
  if [[ "$theme" == dyn-* ]]; then
    if (( sdk_level < 31 )); then
      echo "Android $sdk_level no tiene color dinámico: se omite $theme" >&2
      continue
    fi
    seed="$(seed_hex "$theme")"
    # El JSON va entre comillas simples DENTRO del comando remoto (adb une los argumentos y el shell del teléfono los reparte).
    "$adb_bin" shell "settings put secure theme_customization_overlay_packages '{\"android.theme.customization.system_palette\":\"$seed\",\"android.theme.customization.accent_color\":\"$seed\",\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.theme_style\":\"TONAL_SPOT\",\"_applied_timestamp\":$(date +%s)000}'"
    sleep 6
  else
    "$adb_bin" shell settings delete secure theme_customization_overlay_packages >/dev/null 2>&1 || true
  fi
  while read -r -u 3 id orientation line_theme args; do
    [[ -z "${id:-}" || "$id" == \#* ]] && continue
    [[ "$line_theme" == "$theme" ]] || continue
    if [[ -n "${SCREENS:-}" && "$wanted_ids" != *" $id "* ]]; then continue; fi

    target="$out_dir/${id}-${orientation}-${theme}.png"
    if [[ -n "${RESUME:-}" && -s "$target" ]]; then continue; fi

    # Argumentos solo del script, leídos antes de tocar la pantalla.
    want_window=""; want_cutout=""
    for arg in ${args:-}; do
      case "${arg%%=*}" in
        window) want_window="${arg#*=}" ;;
        cutout) want_cutout="${arg#*=}" ;;
      esac
    done
    set_window "$want_window"
    if ! set_cutout "$want_cutout"; then
      echo "Aviso: $id requiere AVD con recorte (sin overlay $cutout_overlay): se omite" >&2
      continue
    fi
    if [[ "$window_mode" == wide ]]; then
      : # sin girar: 1280x800 ya es horizontal
    elif [[ "$orientation" == landscape ]]; then set_rotation 1; else set_rotation 0; fi
    extras=(--es screen "$id" --es orientation "$orientation")
    if [[ "$theme" == dyn-* ]]; then
      extras+=(--es theme light --ez dynamicColor true --es dynamicSeed "${theme#dyn-}")
    else
      extras+=(--es theme "$theme" --ez dynamicColor false)
    fi
    font_scale=1.0
    wait_seconds=4
    swipe=""
    for arg in ${args:-}; do
      key="${arg%%=*}"
      value="${arg#*=}"
      case "$key" in
        wait) wait_seconds="$value" ;;
        swipe) swipe="$value" ;; # solo del script: desplaza el contenido antes de capturar
        window|cutout) ;; # solo del script: ya aplicados arriba
        fontScale) font_scale="$value" ;; # Float en el intent (A7)
        *) extras+=(--es "$key" "$value") ;;
      esac
    done

    extras+=(--ef fontScale "$font_scale")

    captured=0
    for attempt in 1 2 3; do
      "$adb_bin" shell am force-stop "$package"
      "$adb_bin" shell am start -W -n "$activity" "${extras[@]}" >/dev/null || true
      wait_for_rotation
      wait_for_app_focus
      sleep "$wait_seconds"
      if [[ "$id" == "native-video" ]]; then sleep 4; fi
      if [[ "$swipe" == up ]]; then
        # Arrastre de abajo arriba por el centro de la pantalla (60 % → 20 % de la altura).
        size="$("$adb_bin" shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)"
        width="${size%x*}"; height="${size#*x}"
        if [[ "$rotation" == 1 ]]; then width="${size#*x}"; height="${size%x*}"; fi
        "$adb_bin" shell input swipe $((width / 2)) $((height * 60 / 100)) $((width / 2)) $((height * 20 / 100)) 400
        sleep 2
      fi
      "$adb_bin" shell cmd statusbar collapse >/dev/null 2>&1 || true
      # La captura solo vale si la app sigue en primer plano (con el Mac cargado el sistema puede mostrar un ANR o el lanzador).
      if app_has_focus; then
        "$adb_bin" exec-out screencap -p > "$target"
        captured=1
        break
      fi
      echo "Aviso: $id ($theme) sin primer plano en el intento $attempt; se reintenta" >&2
      dismiss_anr
    done
    if [[ "$captured" != 1 ]]; then
      echo "ERROR: no se pudo capturar $id ($theme)" >&2
      exit 1
    fi
    count=$((count + 1))
  done 3< "$manifest"
done

printf 'Capturas generadas: %s en %s\n' "$count" "$out_dir"
