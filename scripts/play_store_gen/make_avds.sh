#!/usr/bin/env bash
# Create AVDs for Play Store screenshot capture.
# Currently provisions only the phone AVD (1080x1920 @ 480dpi). Tablets are commented
# in case we extend later.

set -euo pipefail

SDK="${ANDROID_HOME:?ANDROID_HOME not set}"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager.bat"
SYSIMG="system-images;android-34;google_apis;x86_64"

create_avd() {
  local name="$1" device="$2" width="$3" height="$4" density="$5"
  echo "Creating AVD: $name ($width x $height @ ${density}dpi)"
  echo "no" | "$AVDM" create avd \
    --name "$name" --package "$SYSIMG" --device "$device" --force >/dev/null

  local cfg="$HOME/.android/avd/${name}.avd/config.ini"
  [ -f "$cfg" ] || { echo "config.ini missing for $name"; return 1; }

  # Force exact resolution and density (Play Store accepted phone size).
  python - "$cfg" "$width" "$height" "$density" <<'PYEOF'
import sys, configparser
cfg, w, h, d = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
# AVD config.ini isn't a real INI; it's flat key=value. Parse manually.
lines = open(cfg, "r", encoding="utf-8").read().splitlines()
out = {}
for line in lines:
    if "=" in line:
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
out["hw.lcd.width"] = w
out["hw.lcd.height"] = h
out["hw.lcd.density"] = d
out["hw.ramSize"] = "2048"
out["disk.dataPartition.size"] = "2048M"
out["showDeviceFrame"] = "no"
with open(cfg, "w", encoding="utf-8") as f:
    for k, v in out.items():
        f.write(f"{k}={v}\n")
PYEOF
}

create_avd play_phone     "pixel_5"  1080 1920 480
create_avd play_tablet7   "Nexus 7"  1200 1920 320
create_avd play_tablet10  "Nexus 10" 1600 2560 320

echo "AVD list:"
"$AVDM" list avd 2>/dev/null | grep -E "Name:" | head
