#!/usr/bin/env bash
# Capture Play Store screenshots from a headless emulator.
#
# Drives a single phone AVD (1080x1920) through 4 screens × 2 locales = 8 captures:
#   1. Dashboard tab — alert active   (state injected via run-as into SharedPreferences)
#   2. Dashboard tab — all quiet
#   3. Map tab
#   4. Settings activity
#
# Repeats for en-US and iw-IL. Output: phone_ss_1.png … phone_ss_8.png.

set -euo pipefail

# Stop Git Bash's MSYS layer from mangling /sdcard, /system, etc. into Windows paths
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SDK="${ANDROID_HOME:?ANDROID_HOME not set}"
ADB="$SDK/platform-tools/adb.exe"
EMULATOR="$SDK/emulator/emulator.exe"

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
APK="${APK_PATH:-$LOCALAPPDATA/play_store_gen/app-pro-debug.apk}"
PKG="com.attius.homefrontalert"

# FACTOR controls AVD, output dir, file prefix, and tap coords.
FACTOR="${FACTOR:-phone}"
case "$FACTOR" in
  phone)
    AVD=play_phone
    OUT="$REPO/android-app/app/src/main/play_store_assets/screenshots/phone"
    PFX=phone_ss
    SCREEN_W=1080; SCREEN_H=1920
    # @ 480dpi: 1dp=3px; status bar ~80px; header 64dp=192px; tabs ~96dp tall
    GEAR_X_LTR=948;  GEAR_Y=230
    GEAR_X_RTL=132
    TAB_LEFT_X=270;  TAB_RIGHT_X=810; TAB_Y=400
    ;;
  tablet7)
    AVD=play_tablet7
    OUT="$REPO/android-app/app/src/main/play_store_assets/screenshots/tablet_7"
    PFX=tablet7_ss
    SCREEN_W=1200; SCREEN_H=1920
    GEAR_X_LTR=1112; GEAR_Y=160
    GEAR_X_RTL=88
    TAB_LEFT_X=300;  TAB_RIGHT_X=900; TAB_Y=300
    ;;
  tablet10)
    AVD=play_tablet10
    OUT="$REPO/android-app/app/src/main/play_store_assets/screenshots/tablet_10"
    PFX=tablet10_ss
    SCREEN_W=1600; SCREEN_H=2560
    GEAR_X_LTR=1512; GEAR_Y=160
    GEAR_X_RTL=88
    TAB_LEFT_X=400;  TAB_RIGHT_X=1200; TAB_Y=300
    ;;
  *) echo "unknown FACTOR=$FACTOR" >&2; exit 2 ;;
esac
ASSETS_PHONE="$OUT"

[ -f "$APK" ] || { echo "APK not found at $APK" >&2; exit 2; }

mkdir -p "$ASSETS_PHONE"

EMU_PID=""
boot_emulator() {
  local locale="$1"   # e.g. en-US or iw-IL
  echo "Booting AVD: $AVD ($locale)"
  EMU_LOG="$LOCALAPPDATA/play_store_gen/emulator-${locale}.log"
  "$EMULATOR" -avd "$AVD" -no-audio -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect -no-window -port 5554 -cores 4 -memory 3072 \
    -prop persist.sys.locale="$locale" \
    -prop persist.sys.language="${locale%-*}" \
    -prop persist.sys.country="${locale##*-}" \
    > "$EMU_LOG" 2>&1 &
  EMU_PID=$!
  "$ADB" wait-for-device
  echo "Waiting for boot…"
  local state=""
  for _ in $(seq 1 240); do
    state=$("$ADB" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$state" = "1" ] && break
    sleep 2
  done
  [ "$state" = "1" ] || { echo "boot timeout (see $EMU_LOG)"; return 3; }
  # wait for launcher (or any home) to settle so subsequent intents don't get ANR'd
  sleep 12
  "$ADB" -e shell input keyevent 82 >/dev/null 2>&1 || true
  sleep 2
  # disable animations + ANR popups (swiftshader is so slow that the launcher itself ANRs)
  "$ADB" -e shell "settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0" >/dev/null 2>&1 || true
  "$ADB" -e shell "settings put secure anr_show_background 0" >/dev/null 2>&1 || true
  "$ADB" -e shell "settings put global hide_error_dialogs 1" >/dev/null 2>&1 || true
  echo "Installing APK…"
  "$ADB" -e install -r -g "$APK"
}

shutdown_emulator() {
  "$ADB" emu kill >/dev/null 2>&1 || true
  if [ -n "$EMU_PID" ]; then
    wait "$EMU_PID" 2>/dev/null || true
    EMU_PID=""
  fi
  sleep 3
}
trap "shutdown_emulator" EXIT

# === helpers ===
inject_alert() {
  local now_ms="$1"
  local recent_ms=$((now_ms - 480000))   # 8 min ago
  local last_ms=$((now_ms - 60000))      # 1 min ago
  local xml='<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<map>
  <string name="dash_status">RED</string>
  <long name="dash_status_start_ms" value="'"$recent_ms"'"/>
  <string name="active_threat_map">{"תל אביב - מרכז העיר":{"s":"URGENT","t":'"$last_ms"',"c":90}}</string>
  <string name="last_alert_zones">תל אביב - מרכז העיר, אשדוד, באר שבע</string>
  <string name="last_alert_type">URGENT</string>
  <long name="last_alert_time" value="'"$last_ms"'"/>
  <float name="last_alert_dist" value="2.4"/>
  <boolean name="initial_setup_v135" value="true"/>
  <boolean name="tos_accepted" value="true"/>
  <float name="alert_volume" value="0.85"/>
  <string name="tracking_mode">GPS_LIVE</string>
  <string name="fixed_zone_he">תל אביב - מרכז העיר</string>
  <boolean name="shield_active" value="false"/>
  <string name="Locale.Helper.Selected.Language">__LANG__</string>
</map>'
  xml="${xml/__LANG__/$APP_LANG}"
  echo "$xml" | "$ADB" -e shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/HomeFrontAlertsPrefs.xml'"
}

inject_safe() {
  local now_ms="$1"
  local xml='<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<map>
  <string name="dash_status">GREEN</string>
  <long name="dash_status_start_ms" value="'"$now_ms"'"/>
  <string name="active_threat_map">{}</string>
  <boolean name="initial_setup_v135" value="true"/>
  <boolean name="tos_accepted" value="true"/>
  <float name="alert_volume" value="0.85"/>
  <string name="tracking_mode">GPS_LIVE</string>
  <string name="fixed_zone_he">תל אביב - מרכז העיר</string>
  <boolean name="shield_active" value="false"/>
  <string name="Locale.Helper.Selected.Language">__LANG__</string>
</map>'
  xml="${xml/__LANG__/$APP_LANG}"
  echo "$xml" | "$ADB" -e shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/HomeFrontAlertsPrefs.xml'"
}

dismiss_anr() {
  # If a system ANR dialog is up, click "Wait" — it's the bottom-right text in the AlertDialog.
  local cur
  cur=$("$ADB" -e shell "dumpsys window 2>/dev/null | grep mCurrentFocus" 2>/dev/null | tr -d '\r' || true)
  case "$cur" in
    *"Application Not Responding"*|*"isn't responding"*|*"appErrorDialog"*)
      echo "(dismissing ANR dialog)" >&2
      # Wait button: lower row in dialog. Tap a safe spot inside the dialog's right side.
      "$ADB" -e shell input tap 540 1140 >/dev/null 2>&1 || true
      sleep 2
      ;;
  esac
}

wait_for_focus() {
  local target="$1"
  local i cur
  for i in $(seq 1 60); do
    cur=$("$ADB" -e shell "dumpsys window 2>/dev/null | grep -E 'mFocusedApp|mCurrentFocus'" 2>/dev/null | tr -d '\r' || true)
    case "$cur" in
      *"$target"*)
        # If ANR dialog covering, dismiss
        case "$cur" in *"isn't responding"*|*"appErrorDialog"*) dismiss_anr ;; esac
        return 0
        ;;
    esac
    sleep 1
  done
  echo "warning: focus did not reach $target (last: $cur)" >&2
}

restart_app() {
  "$ADB" -e shell "am force-stop $PKG"
  sleep 2
  # quiet the launcher so its ANR doesn't steal focus
  "$ADB" -e shell "am force-stop com.google.android.apps.nexuslauncher" >/dev/null 2>&1 || true
  "$ADB" -e shell "am start -n $PKG/.MainActivity --activity-clear-task" >/dev/null
  wait_for_focus "$PKG/.MainActivity"
  sleep 8   # extra time for fragments + views to render under swiftshader
  dismiss_anr
}

go_dashboard_tab() {
  case "${APP_LANG:-en}" in
    iw|he) "$ADB" -e shell input tap "$TAB_RIGHT_X" "$TAB_Y" ;;
    *)     "$ADB" -e shell input tap "$TAB_LEFT_X"  "$TAB_Y" ;;
  esac
  sleep 2
}

go_map_tab() {
  case "${APP_LANG:-en}" in
    iw|he) "$ADB" -e shell input tap "$TAB_LEFT_X"  "$TAB_Y" ;;
    *)     "$ADB" -e shell input tap "$TAB_RIGHT_X" "$TAB_Y" ;;
  esac
  sleep 5
}

tap_view_by_res_id() {
  # tap_view_by_res_id <resource-id-suffix>  (e.g. "btnSettings")
  local rid="$1"
  "$ADB" -e shell "uiautomator dump /sdcard/_ui.xml" >/dev/null
  local xml
  xml=$("$ADB" -e shell "cat /sdcard/_ui.xml")
  local bounds
  bounds=$(echo "$xml" | grep -oE "resource-id=\"[^\"]*$rid\"[^/]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | head -1)
  if [ -z "$bounds" ]; then
    echo "warning: $rid not found in UI dump" >&2
    return 1
  fi
  local x1 y1 x2 y2
  x1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
  y1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  x2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
  y2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  local cx=$(((x1 + x2) / 2)) cy=$(((y1 + y2) / 2))
  "$ADB" -e shell input tap $cx $cy
}

open_settings() {
  case "${APP_LANG:-en}" in
    iw|he) "$ADB" -e shell input tap "$GEAR_X_RTL" "$GEAR_Y" ;;
    *)     "$ADB" -e shell input tap "$GEAR_X_LTR" "$GEAR_Y" ;;
  esac
  wait_for_focus "$PKG/.SettingsActivity"
  sleep 4
}

snap() {
  local out="$1"
  # Pull to a temp file outside Drive (adb on Windows hates spaces in dest paths)
  local tmp="$LOCALAPPDATA/play_store_gen/_ss.png"
  rm -f "$tmp"
  "$ADB" -e shell screencap -p /sdcard/_ss.png
  "$ADB" -e pull /sdcard/_ss.png "$(cygpath -w "$tmp")" >/dev/null
  "$ADB" -e shell rm /sdcard/_ss.png >/dev/null
  cp "$tmp" "$out"
  echo "captured $(basename "$out")"
}

# === capture loop ===
NOW_MS=$(($(date +%s) * 1000))
IDX=1
LOCALES_TO_RUN="${ONLY_LOCALE:-en-US iw-IL}"
START_IDX="${START_IDX:-1}"
IDX=$START_IDX
for LOC in $LOCALES_TO_RUN; do
  case "$LOC" in
    iw-IL) APP_LANG=iw ;;
    *)     APP_LANG=en ;;
  esac
  boot_emulator "$LOC"

  # 1. Dashboard alert
  inject_alert "$NOW_MS"
  restart_app
  go_dashboard_tab
  snap "$ASSETS_PHONE/phone_ss_${IDX}.png"; IDX=$((IDX+1))

  # 2. Dashboard all-quiet
  inject_safe "$NOW_MS"
  restart_app
  go_dashboard_tab
  snap "$ASSETS_PHONE/phone_ss_${IDX}.png"; IDX=$((IDX+1))

  # 3. Map tab
  go_map_tab
  snap "$ASSETS_PHONE/phone_ss_${IDX}.png"; IDX=$((IDX+1))

  # 4. Settings
  go_dashboard_tab
  open_settings
  snap "$ASSETS_PHONE/phone_ss_${IDX}.png"; IDX=$((IDX+1))

  shutdown_emulator
done

echo "Done. Wrote $((IDX-1)) screenshots to $ASSETS_PHONE"
