#!/usr/bin/env python3
"""
update_cities_countdowns.py

Patches the countdown (migun time) values in cities.json using the
authoritative data from amitfin/oref_alert, which is actively maintained
and includes the March 2026 updates for northern confrontation-line zones.

Usage:
    python scripts/update_cities_countdowns.py

Output:
    Overwrites android-app/app/src/main/res/raw/cities.json in place.
    Prints a summary of changes made.

Source: https://github.com/amitfin/oref_alert (Apache 2.0)
"""

import json
import re
import urllib.request
import sys
from pathlib import Path

AMITFIN_URL = (
    "https://raw.githubusercontent.com/amitfin/oref_alert/main/"
    "custom_components/oref_alert/metadata/area_to_migun_time.py"
)

CITIES_JSON_PATH = Path(__file__).parent.parent / (
    "android-app/app/src/main/res/raw/cities.json"
)


def fetch_amitfin_migun_times() -> dict[str, int]:
    """Download and parse the AREA_TO_MIGUN_TIME dict from amitfin."""
    print(f"Fetching amitfin migun times from:\n  {AMITFIN_URL}")
    with urllib.request.urlopen(AMITFIN_URL, timeout=15) as resp:
        source = resp.read().decode("utf-8")

    # Extract all "zone_name": seconds pairs from the Python dict literal.
    # Handles both single and double-quoted keys.
    pattern = re.compile(
        r'[\'"]([^\'"]+)[\'"]\s*:\s*(\d+)',
        re.UNICODE,
    )
    result: dict[str, int] = {}
    for name, seconds in pattern.findall(source):
        result[name.strip()] = int(seconds)

    print(f"  → Loaded {len(result)} zones from amitfin")
    return result


def main() -> None:
    if not CITIES_JSON_PATH.exists():
        print(f"ERROR: cities.json not found at {CITIES_JSON_PATH}", file=sys.stderr)
        sys.exit(1)

    amitfin = fetch_amitfin_migun_times()

    print(f"\nLoading {CITIES_JSON_PATH.name} …")
    with CITIES_JSON_PATH.open(encoding="utf-8") as f:
        cities = json.load(f)
    print(f"  → Loaded {len(cities)} entries")

    changed = 0
    not_found_in_amitfin = 0

    for city in cities:
        name = city.get("name", "").strip()
        if not name or name == "בחר הכל":
            continue

        if name in amitfin:
            new_countdown = amitfin[name]
            old_countdown = city.get("countdown", -1)
            if old_countdown != new_countdown:
                print(
                    f"  CHANGED  {name!r}: {old_countdown}s → {new_countdown}s"
                )
                city["countdown"] = new_countdown
                changed += 1
        else:
            not_found_in_amitfin += 1

    print(f"\nSummary:")
    print(f"  {changed} countdown values updated")
    print(f"  {not_found_in_amitfin} zones in cities.json have no amitfin match (left unchanged)")

    print(f"\nWriting updated cities.json …")
    with CITIES_JSON_PATH.open("w", encoding="utf-8") as f:
        json.dump(cities, f, ensure_ascii=False, indent=2)
    print("Done.")


if __name__ == "__main__":
    main()
