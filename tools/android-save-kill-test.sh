#!/usr/bin/env bash
# Prueba de cierre forzado de las partidas (Android A5, SPEC §5.2/§8; decisión J11).
#
# Uso: tools/android-save-kill-test.sh [N=50]
#
# Cada iteración: arranca la app Debug en modo `save-stress` (ROM contador sintética, muchísimas escrituras
# atómicas con rotación de backups, pausas y estados al azar), espera a que escriba, duerme entre 50 y
# 1500 ms al azar, mata el proceso
# (alternando `am force-stop` y `run-as <pkg> kill -9 <pid>`) y lanza `save-verify`, que tras `recoverOrphans`
# comprueba: `.sav` presente y completo, ningún `.tmp`, ≤5 backups y que el núcleo acepta la partida y el
# estado. `save-verify` escribe `SAVE-VERIFY OK|FAIL <detalle>` en logcat. El script falla si hay un solo FAIL,
# si alguna verificación no llegó a ejecutarse o si ninguna se ejecutó.
set -euo pipefail

iterations="${1:-50}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
adb_bin="$sdk_root/platform-tools/adb"
package="com.joelbermudez.pocketgb"
activity="$package/.MainActivity"
tag="PocketGBStress"

if [[ ! -x "$adb_bin" ]]; then
  echo "No se encontró adb en $adb_bin" >&2
  exit 1
fi
if ! "$adb_bin" get-state >/dev/null 2>&1; then
  echo "No hay un dispositivo Android conectado" >&2
  exit 1
fi
if ! "$adb_bin" shell pm list packages "$package" | grep -q "$package"; then
  echo "La app Debug no está instalada (./gradlew :app:installDebug)" >&2
  exit 1
fi

start_mode() {
  "$adb_bin" shell am start -W -n "$activity" --es debug "$1" >/dev/null
}

# Espera una línea de logcat que case con $1 (regex); imprime la línea o nada si se agota $2 s.
wait_log() {
  local pattern="$1" timeout="$2" waited=0 line
  while (( waited < timeout * 10 )); do
    line="$("$adb_bin" logcat -d -s "$tag:I" 2>/dev/null | grep -E "$pattern" | tail -1 || true)"
    if [[ -n "$line" ]]; then
      printf '%s\n' "$line"
      return 0
    fi
    sleep 0.1
    waited=$((waited + 1))
  done
  return 1
}

kill_app() {
  local method="$1" pid
  if [[ "$method" == "kill9" ]]; then
    pid="$("$adb_bin" shell pidof "$package" | tr -d '\r' | awk '{print $1}')"
    if [[ -n "$pid" ]] && "$adb_bin" shell run-as "$package" kill -9 "$pid" >/dev/null 2>&1; then
      return 0
    fi
  fi
  "$adb_bin" shell am force-stop "$package"
}

echo "Calentamiento: crear la partida base y comprobar que el modo save-stress arranca…"
"$adb_bin" shell am force-stop "$package"
"$adb_bin" logcat -c
start_mode save-stress
if ! wait_log 'SAVE-STRESS READY' 40 >/dev/null; then
  echo "FALLO: save-stress no llegó a arrancar" >&2
  "$adb_bin" logcat -d -s "$tag:*" | tail -20 >&2
  exit 1
fi
"$adb_bin" shell am force-stop "$package"

ok=0
fail=0
missing=0
ready=0
for ((i = 1; i <= iterations; i++)); do
  if (( i % 2 == 1 )); then method="force-stop"; else method="kill9"; fi
  delay_ms=$(( (RANDOM * 32768 + RANDOM) % 1451 + 50 ))
  "$adb_bin" logcat -c
  start_mode save-stress
  # El retardo aleatorio cuenta desde que el juego ya escribe (en un emulador cargado el arranque tarda
  # más que el propio retardo y se mataría el proceso antes de que hubiera nada que proteger).
  if wait_log 'SAVE-STRESS READY' 40 >/dev/null; then ready=$((ready + 1)); fi
  sleep "$(awk -v ms="$delay_ms" 'BEGIN { printf "%.3f", ms / 1000 }')"
  kill_app "$method"
  "$adb_bin" logcat -c
  start_mode save-verify
  if result="$(wait_log 'SAVE-VERIFY (OK|FAIL)' 40)"; then
    detail="${result#*SAVE-VERIFY }"
    if [[ "$detail" == OK* ]]; then
      ok=$((ok + 1))
    else
      fail=$((fail + 1))
    fi
    printf 'iter %3d  kill=%-10s tras %4d ms  -> %s\n' "$i" "$method" "$delay_ms" "$detail"
  else
    missing=$((missing + 1))
    printf 'iter %3d  kill=%-10s tras %4d ms  -> SIN VERIFICACIÓN\n' "$i" "$method" "$delay_ms"
  fi
  "$adb_bin" shell am force-stop "$package"
done

echo
echo "Resultado: OK=$ok FAIL=$fail sin-verificación=$missing de $iterations (stress listo antes de matar: $ready)"
if (( fail > 0 || missing > 0 || ok == 0 )); then
  echo "FALLO de la prueba de cierre forzado" >&2
  exit 1
fi
echo "OK: $ok/$iterations iteraciones con el invariante intacto"
