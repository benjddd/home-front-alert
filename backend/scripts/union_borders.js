const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');
const https = require('https');
const turf = require('@turf/turf');

async function main() {
    const outlinePath = path.join(__dirname, '../public/israel-outline.json');
    const outlineData = JSON.parse(fs.readFileSync(outlinePath, 'utf8'));

    // Start with the Natural Earth 10m outline — do NOT buffer or simplify it.
    let combined = outlineData.features[0];
    for (let i = 1; i < outlineData.features.length; i++) {
        combined = turf.union(combined, outlineData.features[i]);
    }

    console.log('Downloading alert polygons…');
    const url = 'https://raw.githubusercontent.com/amitfin/oref_alert/main/custom_components/oref_alert/metadata/area_to_polygon.json.zip';

    const buf = await new Promise((resolve, reject) => {
        https.get(url, res => {
            if (res.statusCode !== 200) return reject(new Error('HTTP ' + res.statusCode));
            const bufs = [];
            res.on('data', d => bufs.push(d));
            res.on('end', () => resolve(Buffer.concat(bufs)));
            res.on('error', reject);
        }).on('error', reject);
    });

    const zip = new AdmZip(buf);
    const entry = zip.getEntries().find(e => e.entryName.endsWith('.json'));
    const raw = JSON.parse(zip.readAsText(entry));

    console.log('Unioning alert polygons…');
    let count = 0;
    let skipped = 0;

    for (const [name, coords] of Object.entries(raw)) {
        if (!coords || coords.length < 3) { skipped++; continue; }

        // amitfin stores [lat, lng]; Turf needs [lng, lat].
        const ring = coords.map(c => [c[1], c[0]]);

        // Ensure closed ring.
        if (ring[0][0] !== ring[ring.length - 1][0] || ring[0][1] !== ring[ring.length - 1][1]) {
            ring.push([...ring[0]]);
        }

        try {
            // Do NOT buffer(poly, 0) — even zero-distance buffering rounds concave
            // coastline features like Haifa Bay.  We rely on the try/catch for the rare
            // truly-invalid polygon instead.
            const poly = turf.polygon([ring]);
            combined = turf.union(combined, poly);
            count++;
        } catch (e) {
            console.warn(`  Skipped zone "${name}": ${e.message}`);
            skipped++;
        }
    }

    console.log(`Unioned ${count} zones (skipped ${skipped}).`);

    // No simplification — alert zones will be drawn on the map so every vertex matters.
    // Only truncate coordinates to 5-decimal precision (≈ 1 m), which reduces file size
    // without losing any visually meaningful detail.
    combined = turf.truncate(combined, { precision: 5 });

    const output = {
        type: 'FeatureCollection',
        features: [{
            type: 'Feature',
            properties: { name_en: 'Israel', name_he: 'ישראל' },
            geometry: combined.geometry
        }]
    };

    fs.writeFileSync(outlinePath, JSON.stringify(output));
    console.log('Done. Size:', (fs.statSync(outlinePath).size / 1024).toFixed(1), 'KB');
}

main().catch(console.error);
