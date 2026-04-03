#!/usr/bin/env node
/**
 * sync_cities.js
 *
 * Fetches the latest HFC city list and merges new entries into
 * android-app/app/src/main/res/raw/cities.json without removing existing data.
 *
 * Usage: node backend/scripts/sync_cities.js
 * Then commit the updated cities.json.
 */

'use strict';
const fs   = require('fs');
const path = require('path');
const https = require('https');

const HFC_URL = 'https://alerts.oref.org.il/Shared/Ajax/GetCities.aspx';
const OUT_PATH = path.resolve(__dirname, '../../android-app/app/src/main/res/raw/cities.json');

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { 'Referer': 'https://www.oref.org.il/' } }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch (e) { reject(new Error(`JSON parse failed: ${e.message}\nRaw (first 200): ${data.slice(0,200)}`)); }
      });
    });
    req.on('error', reject);
    req.setTimeout(10000, () => { req.destroy(); reject(new Error('Timeout')); });
  });
}

async function main() {
  console.log('Fetching latest HFC city list...');
  const hfcCities = await fetchJson(HFC_URL);
  console.log(`  HFC returned ${hfcCities.length} entries`);

  const existing = JSON.parse(fs.readFileSync(OUT_PATH, 'utf8'));
  const existingNames = new Set(existing.map(c => c.name));

  let added = 0;
  for (const city of hfcCities) {
    if (!city.name || existingNames.has(city.name)) continue;
    existing.push(city);
    existingNames.add(city.name);
    added++;
    console.log(`  + NEW: ${city.name} (${city.name_en || '?'})`);
  }

  fs.writeFileSync(OUT_PATH, JSON.stringify(existing, null, 2), 'utf8');
  console.log(`\nDone. Added ${added} new entries. Total: ${existing.length}`);
  if (added > 0) console.log('Commit cities.json to apply changes in the Android app.');
}

main().catch(e => { console.error('Error:', e.message); process.exit(1); });
