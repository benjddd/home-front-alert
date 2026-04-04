#!/usr/bin/env node
'use strict';
/**
 * generate_country_outline.js
 *
 * Uses Natural Earth 50m admin_0 map_units (more granular than countries)
 * to find Israel + West Bank + Gaza, and saves as a FeatureCollection.
 *
 * No external dependencies — pure Node.js https only.
 *
 * Run: node backend/scripts/generate_country_outline.js
 */

const https = require('https');
const http  = require('http');
const fs    = require('fs');
const path  = require('path');

const OUTPUT = path.join(__dirname, '../public/israel-outline.json');

function fetch(url, hops = 0) {
  return new Promise((resolve, reject) => {
    if (hops > 5) return reject(new Error('Too many redirects'));
    const mod = url.startsWith('https') ? https : http;
    mod.get(url, { headers: { 'User-Agent': 'homefront-alerts/generate-outline' } }, res => {
      if (res.statusCode === 301 || res.statusCode === 302)
        return fetch(res.headers.location, hops + 1).then(resolve).catch(reject);
      if (res.statusCode !== 200)
        return reject(new Error(`HTTP ${res.statusCode}`));
      const bufs = [];
      res.on('data', b => bufs.push(b));
      res.on('end', () => resolve(Buffer.concat(bufs)));
      res.on('error', reject);
    }).on('error', reject);
  });
}

function roundCoords(coords) {
  // Keep higher precision (5 decimals ~ 1.1m) for cleaner coast/border fidelity.
  if (typeof coords[0] === 'number') return coords.map(v => Math.round(v * 100000) / 100000);
  return coords.map(roundCoords);
}
function simplifyGeometry(geom) {
  return { ...geom, coordinates: roundCoords(geom.coordinates) };
}

async function main() {
  // map_units has West Bank as a separate feature; countries.geojson merges it.
  // Use 10m dataset for higher border/coast precision vs prior 50m.
  const url = 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_map_units.geojson';

  console.log('⬇  Fetching Natural Earth 10m map_units…');
  const buf = await fetch(url);
  console.log(`   ${(buf.length / 1024).toFixed(0)} KB downloaded`);

  const fc = JSON.parse(buf.toString());
  console.log(`   ${fc.features.length} features`);

  // Debug: print all features in the Middle East region
  const region = fc.features.filter(f => {
    const lon = f.geometry?.coordinates?.[0]?.[0]?.[0] ?? f.geometry?.coordinates?.[0]?.[0];
    const lat = Array.isArray(lon) ? lon[1] : null;
    const lo  = Array.isArray(lon) ? lon[0] : null;
    const n   = (f.properties.NAME || '').toLowerCase();
    return (lo >= 30 && lo <= 42 && lat >= 28 && lat <= 38) ||
           n.includes('israel') || n.includes('palestin') || n.includes('west bank') ||
           n.includes('gaza') || n.includes('jordan') || n.includes('lebanon');
  });
  console.log('\nRegion features found:');
  region.forEach(f => console.log(`  NAME=${f.properties.NAME}  ISO_A3=${f.properties.ISO_A3}  ADM0_A3=${f.properties.ADM0_A3}  BRK_A3=${f.properties.BRK_A3}`));

  const isMatch = (f, terms) => terms.some(t => {
    const n = String(f.properties.NAME        || '').toLowerCase();
    const a = String(f.properties.ADM0_A3     || '').toLowerCase();
    const b = String(f.properties.BRK_A3      || '').toLowerCase();
    const g = String(f.properties.GU_A3       || '').toLowerCase();
    return n.includes(t) || a === t || b === t || g === t;
  });

  const il = fc.features.find(f => f.properties.ISO_A3 === 'ISR' || isMatch(f, ['israel']));
  const wb = fc.features.find(f => isMatch(f, ['west bank', 'wbs', 'westbank']));
  const gz = fc.features.find(f => isMatch(f, ['gaza', 'gaz']));

  if (!il) throw new Error('Israel not found');
  console.log(`\n✅ Israel:     ${il.properties.NAME}`);
  console.log(wb ? `✅ West Bank:  ${wb.properties.NAME}` : '⚠  West Bank not found');
  console.log(gz ? `✅ Gaza:       ${gz.properties.NAME}` : '⚠  Gaza not found');

  const features = [il, wb, gz].filter(Boolean).map(f => ({
    type: 'Feature',
    properties: { name_en: 'Israel', name_he: 'ישראל' },
    geometry: simplifyGeometry(f.geometry),
  }));

  const output = { type: 'FeatureCollection', features };
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  fs.writeFileSync(OUTPUT, JSON.stringify(output));
  const kb = (fs.statSync(OUTPUT).size / 1024).toFixed(1);
  console.log(`\n✅ Saved → ${OUTPUT} (${kb} KB, ${features.length} features)`);
}

main().catch(err => { console.error('❌', err.message); process.exit(1); });
