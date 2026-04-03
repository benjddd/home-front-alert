import json
import urllib.request
import zipfile
import io
import ssl
from pathlib import Path

try:
    from shapely.geometry import Polygon, shape, mapping
    from shapely.ops import unary_union
except ImportError:
    import sys
    print("Please pip install shapely")
    sys.exit(1)

def main():
    outline_path = Path('backend/public/israel-outline.json')
    print(f"Loading existing borders from {outline_path}")
    outline_data = json.loads(outline_path.read_text(encoding='utf-8'))

    base_polys = []
    for f in outline_data.get('features', []):
        try:
            base_polys.append(shape(f['geometry']))
        except Exception as e:
            print("Skipping invalid base geom:", e)

    ctx = ssl._create_unverified_context()
    url = 'https://raw.githubusercontent.com/amitfin/oref_alert/main/custom_components/oref_alert/metadata/area_to_polygon.json.zip'
    print(f"Downloading alert zones from {url}")
    buf = urllib.request.urlopen(url, context=ctx, timeout=60).read()
    z = zipfile.ZipFile(io.BytesIO(buf))
    name = [n for n in z.namelist() if n.endswith('.json')][0]
    raw = json.loads(z.read(name).decode('utf-8'))

    alert_polys = []
    for zn, coords in raw.items():
        if len(coords) < 3: continue
        ring = [(c[1], c[0]) for c in coords]
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        try:
            poly = Polygon(ring)
            if not poly.is_valid:
                poly = poly.buffer(0)
            if poly.is_valid:
                alert_polys.append(poly)
        except Exception as e:
            pass

    print(f"Unioning {len(base_polys)} base polygons with {len(alert_polys)} alert zones...")
    all_polys = base_polys + alert_polys
    unioned = unary_union(all_polys)

    # Smooth and fill tiny gaps using buffer
    print("Smoothing borders...")
    # 0.005 degrees is ~500 meters
    unioned = unioned.buffer(0.005, join_style=1).buffer(-0.005, join_style=1)
    unioned = unioned.simplify(0.001, preserve_topology=True)

    out_features = []
    if unioned.geom_type == 'Polygon':
        out_features.append({
            "type": "Feature",
            "properties": {"name_en": "Israel", "name_he": "ישראל"},
            "geometry": mapping(unioned)
        })
    elif unioned.geom_type == 'MultiPolygon':
        for poly in unioned.geoms:
            # filter out extremely tiny artifacts
            if poly.area < 0.0001: continue
            out_features.append({
                "type": "Feature",
                "properties": {"name_en": "Israel", "name_he": "ישראל"},
                "geometry": mapping(poly)
            })

    output = {"type": "FeatureCollection", "features": out_features}

    # Round coordinates to 5 decimals for file size
    def round_coords(coords):
        if isinstance(coords[0], (int, float)):
            return [round(c, 5) for c in coords]
        return [round_coords(c) for c in coords]

    for f in output["features"]:
        f["geometry"]["coordinates"] = round_coords(f["geometry"]["coordinates"])

    outline_path.write_text(json.dumps(output, separators=(',', ':')), encoding='utf-8')
    size_kb = len(outline_path.read_bytes()) / 1024
    print(f"Done! Saved to {outline_path} ({size_kb:.1f} KB)")

if __name__ == '__main__':
    main()
