#!/usr/bin/env node
/**
 * enrich_population.js
 *
 * Fetches CBS (Israel Central Bureau of Statistics) population data and
 * enriches cities.json with a `population` field on each entry.
 * The grand total is extrapolated to match Israel's real population (~10.178M).
 *
 * Usage: node backend/scripts/enrich_population.js
 * Then commit the updated cities.json.
 */

'use strict';
const fs   = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

const CBS_URL = 'https://data.gov.il/api/3/action/datastore_search?resource_id=64edd0ee-3d5d-43ce-8562-c336c24dbc1f';
const PAGE_LIMIT = 1300;
const ISRAEL_POPULATION = 10178000;
const OUT_PATH = path.resolve(__dirname, '../../android-app/app/src/main/res/raw/cities.json');

const INDUSTRIAL_PATTERNS = ['אזור תעשייה', 'אזה"ת', 'תעשיי', 'מפעלי'];

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    const client = url.startsWith('https') ? https : http;
    const req = client.get(url, (res) => {
      // Follow redirects
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return fetchJson(res.headers.location).then(resolve, reject);
      }
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch (e) { reject(new Error(`JSON parse failed: ${e.message}\nRaw (first 200): ${data.slice(0, 200)}`)); }
      });
    });
    req.on('error', reject);
    req.setTimeout(30000, () => { req.destroy(); reject(new Error('Timeout')); });
  });
}

async function fetchAllCbsRecords() {
  const records = [];
  let offset = 0;

  while (true) {
    const url = `${CBS_URL}&limit=${PAGE_LIMIT}&offset=${offset}`;
    console.log(`  Fetching CBS page offset=${offset} ...`);
    const response = await fetchJson(url);
    const page = response.result.records;
    records.push(...page);
    console.log(`  Got ${page.length} records (total so far: ${records.length})`);

    if (records.length >= response.result.total || page.length === 0) break;
    offset += PAGE_LIMIT;
  }

  return records;
}

function parsePopulation(value) {
  if (value == null) return 0;
  const str = String(value).replace(/,/g, '').trim();
  const num = parseInt(str, 10);
  return isNaN(num) ? 0 : num;
}

function isIndustrialZone(name) {
  return INDUSTRIAL_PATTERNS.some(p => name.includes(p));
}

async function main() {
  // 1. Fetch CBS data
  console.log('Fetching CBS population data...');
  const cbsRecords = await fetchAllCbsRecords();
  console.log(`  CBS returned ${cbsRecords.length} total records`);

  // 2. Build lookup: Hebrew name (trimmed) → population
  //    Also build a base-name lookup for CBS entries that contain ' - '
  //    (e.g. CBS "תל אביב - יפו" → base "תל אביב" → 597713)
  const cbsLookup = new Map();
  const cbsBaseLookup = new Map();
  for (const rec of cbsRecords) {
    const name = (rec['שם_ישוב'] || '').trim();
    const pop = parsePopulation(rec['סהכ']);
    if (name) {
      cbsLookup.set(name, pop);
      if (name.includes(' - ') || name.includes(' -') || name.includes('- ')) {
        const base = name.split(/\s*-\s*/)[0].trim();
        if (base) cbsBaseLookup.set(base, (cbsBaseLookup.get(base) || 0) + pop);
      }
    }
  }
  console.log(`  CBS lookup has ${cbsLookup.size} entries, ${cbsBaseLookup.size} base-name entries`);

  // 3. Load cities.json
  const cities = JSON.parse(fs.readFileSync(OUT_PATH, 'utf8'));
  console.log(`  cities.json has ${cities.length} entries`);

  // Pre-compute split-city groups: base name → count of cities sharing that base
  const splitGroups = new Map();
  for (const city of cities) {
    if (city.id === 0) continue;
    if (isIndustrialZone(city.name)) continue;
    if (city.name.includes(' - ')) {
      const base = city.name.split(' - ')[0].trim();
      splitGroups.set(base, (splitGroups.get(base) || 0) + 1);
    }
  }

  // 4. Assign populations
  let matched = 0;
  let unmatched = 0;
  const unmatchedNames = [];

  for (const city of cities) {
    // Skip "Select All"
    if (city.id === 0) {
      city.population = 0;
      continue;
    }

    const name = city.name.trim();

    // Industrial zones
    if (isIndustrialZone(name)) {
      city.population = 0;
      continue;
    }

    // Direct match
    if (cbsLookup.has(name)) {
      city.population = cbsLookup.get(name);
      matched++;
      continue;
    }

    // Split-city match: check CBS exact base, then CBS base-name lookup
    if (name.includes(' - ')) {
      const base = name.split(' - ')[0].trim();
      const cbsPop = cbsLookup.get(base) ?? cbsBaseLookup.get(base);
      if (cbsPop != null) {
        const count = splitGroups.get(base) || 1;
        city.population = Math.round(cbsPop / count);
        matched++;
        continue;
      }
    }

    // No match
    city.population = 0;
    unmatched++;
    unmatchedNames.push(name);
  }

  // 5. Extrapolation
  let sumPop = 0;
  for (const city of cities) {
    sumPop += city.population;
  }

  console.log(`\n  Raw sum before extrapolation: ${sumPop.toLocaleString()}`);
  if (sumPop === 0) {
    console.error('Error: no population data matched — cannot extrapolate.');
    process.exit(1);
  }
  const scaleFactor = ISRAEL_POPULATION / sumPop;
  console.log(`  Scale factor: ${scaleFactor.toFixed(4)}`);

  for (const city of cities) {
    if (city.population > 0) {
      city.population = Math.round(city.population * scaleFactor);
    }
  }

  // Verify final total
  let finalTotal = 0;
  for (const city of cities) {
    finalTotal += city.population;
  }

  // 6. Write enriched cities.json
  fs.writeFileSync(OUT_PATH, JSON.stringify(cities, null, 2), 'utf8');

  // 7. Log summary
  console.log('\n=== Summary ===');
  console.log(`  Matched:   ${matched}`);
  console.log(`  Unmatched: ${unmatched}`);
  console.log(`  Final total population: ${finalTotal.toLocaleString()}`);

  if (unmatchedNames.length > 0) {
    console.log('\n  Unmatched cities:');
    for (const name of unmatchedNames) {
      console.warn(`    WARNING: no CBS match for "${name}"`);
    }
  }

  console.log('\nDone. cities.json has been enriched with population data.');
}

main().catch(e => { console.error('Error:', e.message); process.exit(1); });
