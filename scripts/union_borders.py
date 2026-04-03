import json
import urllib.request
import zipfile
import io
import ssl
from pathlib import Path

try:
    from shapely.geometry import Polygon, MultiPolygon, shape, mapping
    from shapely.ops import unary_union
except ImportError:
    import sys
    print("Please pip install shapely")
    sys.exit(1)

def round_coords(coords):
    if isinstance(coords[0], (int, float)):
        return [round(c, 5) for c in coords]
    return [round_coords(c) for c in coords]

def main():
    outline_path = Path('backend/public/israel-outline.json')
    print(f"Loading existing borders from {outline_path}")
    outline_data = json.loads(outline_path.read_text(encoding='utf-8'))

    base_polys = []
    for f in outline_data.get('features', []):
        try:
            geom = shape(f['geometry'])
            if geom.geom_type == 'Polygon':
                base_polys.append(geom)
            elif geom.geom_type == 'MultiPolygon':
                base_polys.extend(list(geom.geoms))
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
    skipped = 0
    for zn, coords in raw.items():
        if not coords or len(coords) < 3:
            skipped += 1
            continue
        # amitfin stores [lat, lng]; Shapely needs (lng, lat).
        ring = [(c[1], c[0]) for c in coords]
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        try:
            poly = Polygon(ring)
            if poly.is_valid and not poly.is_empty:
                alert_polys.append(poly)
            else:
                skipped += 1
        except Exception:
            skipped += 1

    print(f"Unioning {len(base_polys)} base polygons + {len(alert_polys)} alert zones...")
    
    # Force precision on all input polygons to fix tiny coordinate system misalignments
    # 1e-5 ≈ 1 meter, enough to snap slightly misaligned points to the same grid
    all_polys = [p.simplify(1e-5, preserve_topology=False) for p in (base_polys + alert_polys)]
    
    # Perform the union
    unioned = unary_union(all_polys)

    # Re-structure as a single FeatureCollection
    out_features = []
    
    def process_geom(geom):
        if geom.geom_type == 'Polygon':
            return [geom]
        elif geom.geom_type == 'MultiPolygon':
            return list(geom.geoms)
        elif geom.geom_type == 'GeometryCollection':
            polys = []
            for g in geom.geoms:
                polys.extend(process_geom(g))
            return polys
        return []

    final_polys = process_geom(unioned)

    for poly in final_polys:
        if poly.area < 0.00001: continue
        out_features.append({
            "type": "Feature",
            "properties": {"name_en": "Israel", "name_he": "\u05d9\u05e9\u05e8\u05d0\u05dc"},
            "geometry": mapping(poly)
        })

    output = {"type": "FeatureCollection", "features": out_features}
    for f in output["features"]:
        f["geometry"]["coordinates"] = round_coords(f["geometry"]["coordinates"])

    outline_path.write_text(json.dumps(output, separators=(',', ':')), encoding='utf-8')
    size_kb = len(outline_path.read_bytes()) / 1024
    print(f"Done! Saved {len(out_features)} polygons to {outline_path} ({size_kb:.1f} KB)")

if __name__ == '__main__':
    main()
