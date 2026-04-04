'use strict';
/**
 * polygonCache.js
 *
 * Downloads and caches the amitfin/oref_alert area_to_polygon.json.zip
 * on startup. Exposes fast zone-name → GeoJSON Polygon lookups.
 *
 * Source:  https://github.com/amitfin/oref_alert (Apache 2.0)
 * License: Apache 2.0 — attribution required in app UI.
 */

const axios = require('axios');
const path  = require('path');
const AdmZip = require('adm-zip');  // added to package.json

const POLYGON_ZIP_URL =
  'https://raw.githubusercontent.com/amitfin/oref_alert/main/' +
  'custom_components/oref_alert/metadata/area_to_polygon.json.zip';

// Cache: Hebrew zone name → GeoJSON Polygon Feature
/** @type {Map<string, object>} */
const polygonCache = new Map();

let loaded = false;
let loadError = null;
let loadedAt  = null;

const REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000; // refresh once a day

/**
 * Download a URL and return a Buffer.
 */
function downloadBuffer(url) {
  return new Promise((resolve, reject) => {
    axios.get(url, {
      timeout: 20000,
      responseType: 'arraybuffer',
      validateStatus: (status) => status === 200,
      maxRedirects: 5,
    }).then((response) => {
      resolve(Buffer.from(response.data));
    }).catch(reject);
  });
}

/**
 * Load and parse the polygon zip.
 * amitfin stores: { "zone name": [[lng, lat], [lng, lat], ...], ... }
 * We convert to GeoJSON Polygon Features.
 */
async function loadPolygons() {
  console.log('[polygonCache] Downloading area_to_polygon.json.zip …');
  const buf  = await downloadBuffer(POLYGON_ZIP_URL);
  const zip  = new AdmZip(buf);
  const entry = zip.getEntries().find(e => e.entryName.endsWith('.json'));
  if (!entry) throw new Error('No JSON entry found in polygon zip');

  const raw  = JSON.parse(zip.readAsText(entry));
  polygonCache.clear();

  let count = 0;
  for (const [name, coords] of Object.entries(raw)) {
    if (!Array.isArray(coords) || coords.length < 3) continue;

    // amitfin format: [[lat, lng], ...] — convert to GeoJSON [lng, lat]
    const ring = coords.map(([lat, lng]) => [lng, lat]);
    // Close ring if not already closed
    const first = ring[0], last = ring[ring.length - 1];
    if (first[0] !== last[0] || first[1] !== last[1]) ring.push([...first]);

    polygonCache.set(name, {
      type: 'Feature',
      properties: { name },
      geometry: { type: 'Polygon', coordinates: [ring] },
    });
    count++;
  }

  loaded    = true;
  loadedAt  = Date.now();
  loadError = null;
  console.log(`[polygonCache] Loaded ${count} zone polygons.`);
}

/**
 * Initialise — call once on server start.
 * Schedules a daily refresh automatically.
 */
async function init() {
  try {
    await loadPolygons();
  } catch (err) {
    loadError = err;
    console.error('[polygonCache] Initial load failed:', err.message);
  }
  // Daily refresh
  setInterval(async () => {
    try { await loadPolygons(); }
    catch (e) { console.error('[polygonCache] Refresh failed:', e.message); }
  }, REFRESH_INTERVAL_MS);
}

/**
 * Return GeoJSON Feature for a zone name, or null if not found.
 * @param {string} zoneName  Hebrew HFC zone name
 */
function getPolygon(zoneName) {
  return polygonCache.get(zoneName) ?? null;
}

/** Return the full cache as an array of Features. */
function getAllPolygons() {
  return [...polygonCache.values()];
}

/** True once polygons have been loaded at least once. */
function isReady() { return loaded; }

/** Expose load metadata for health checks. */
function status() {
  return { loaded, loadError: loadError?.message ?? null, loadedAt, zoneCount: polygonCache.size };
}

module.exports = { init, getPolygon, getAllPolygons, isReady, status };
