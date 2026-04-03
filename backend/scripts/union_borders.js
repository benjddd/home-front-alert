const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');
const https = require('https');
const turf = require('@turf/turf');

async function main() {
    const outlinePath = path.join(__dirname, '../public/israel-outline.json');
    const outlineData = JSON.parse(fs.readFileSync(outlinePath, 'utf8'));
    
    // Combine all existing outline features into one
    let combined = outlineData.features[0];
    for (let i = 1; i < outlineData.features.length; i++) {
        combined = turf.union(combined, outlineData.features[i]);
    }

    console.log('Downloading polygons...');
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

    console.log('Unioning polygons...');
    let count = 0;
    
    for (const [name, coords] of Object.entries(raw)) {
        if (!coords || coords.length < 3) continue;
        
        // amitfin coordinates are [lat, lng]. Turf needs [lng, lat].
        const ring = coords.map(c => [c[1], c[0]]);
        
        // Ensure closed ring
        if (ring[0][0] !== ring[ring.length-1][0] || ring[0][1] !== ring[ring.length-1][1]) {
            ring.push([...ring[0]]);
        }
        
        try {
            // Clean up self-intersections or kinks in polygons by buffering by 0
            let poly = turf.polygon([ring]);
            poly = turf.buffer(poly, 0); // standard trick to fix invalid geometries in turf
            
            combined = turf.union(combined, poly);
            count++;
        } catch (e) {
            console.error(`Failed to union zone ${name}:`, e.message);
        }
    }
    
    console.log(`Unioned ${count} zones. Simplifying...`);
    // Simplify a bit to avoid excessively complex borders and huge file size
    combined = turf.simplify(combined, { tolerance: 0.0005, highQuality: true });
    // Truncate coordinates to 5 decimals
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
    console.log('Done! Size:', (fs.statSync(outlinePath).size / 1024).toFixed(1), 'KB');
}

main().catch(console.error);
