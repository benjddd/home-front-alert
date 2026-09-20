#!/usr/bin/env bash
#
# prepare-website-data.sh
# Downloads polygon data and copies geo assets for the companion website.
# Run before: firebase deploy --only hosting
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB_DATA="$REPO_ROOT/proxy-microsite/public/data"
MAP_ASSETS="$REPO_ROOT/android-app/app/src/main/assets/map"

POLYGON_URL="https://raw.githubusercontent.com/amitfin/oref_alert/main/custom_components/oref_alert/metadata/area_to_polygon.json.zip"
TMP_ZIP="/tmp/polygons_$$.zip"

echo "=== Preparing website data ==="

# Ensure data directory exists
mkdir -p "$WEB_DATA"

# 1. Download and unzip polygon data
echo "Downloading polygon data..."
curl -fsSL "$POLYGON_URL" -o "$TMP_ZIP"
unzip -o -p "$TMP_ZIP" > "$WEB_DATA/polygons.json"
rm -f "$TMP_ZIP"
echo "  -> polygons.json ($(wc -c < "$WEB_DATA/polygons.json") bytes)"

# 2. Copy geo assets from Android app
echo "Copying geo assets from Android app..."
cp "$MAP_ASSETS/israel-outline.json" "$WEB_DATA/"
cp "$MAP_ASSETS/geo-extras.json" "$WEB_DATA/"
echo "  -> israel-outline.json"
echo "  -> geo-extras.json"

# 3. Copy vendor assets
VENDOR_DIR="$REPO_ROOT/proxy-microsite/public/vendor"
mkdir -p "$VENDOR_DIR"
cp "$MAP_ASSETS/vendor/maplibre-gl.js" "$VENDOR_DIR/"
cp "$MAP_ASSETS/vendor/maplibre-gl.css" "$VENDOR_DIR/"
echo "  -> vendor/maplibre-gl.js"
echo "  -> vendor/maplibre-gl.css"

echo "=== Done. Ready for: firebase deploy --only hosting ==="
