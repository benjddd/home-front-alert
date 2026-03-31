'use strict';
/**
 * map-server.js
 *
 * Standalone Express server for the homefront-map Cloud Run service.
 * Serves: GET /map  → map.html
 *         GET /api/map-data → computed alert cluster GeoJSON
 *         GET /health   → liveness probe
 *
 * This service is separate from the alert relay (homefront-backend)
 * so a map bug can never affect FCM notifications.
 */

const express   = require('express');
const path      = require('path');
const helmet    = require('helmet');

const polygonCache = require('./polygonCache');
const mapState     = require('./mapState');

const app  = express();
const PORT = parseInt(process.env.PORT ?? '8080', 10);

// ── Security headers ─────────────────────────────────────────────────────
app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      scriptSrc:  ["'self'", "'unsafe-inline'", 'https://unpkg.com'],
      styleSrc:   ["'self'", "'unsafe-inline'", 'https://unpkg.com'],
      imgSrc:     ["'self'", 'data:', 'https://*.basemaps.cartocdn.com', 'https://unpkg.com'],
      connectSrc: ["'self'", 'https://*.basemaps.cartocdn.com', 'https://demotiles.maplibre.org'],
      fontSrc:    ["'self'", 'https://demotiles.maplibre.org'],
      workerSrc:  ["'self'", 'blob:'],
    },
  },
  crossOriginEmbedderPolicy: false,
}));

// ── Routes ───────────────────────────────────────────────────────────────

// Serve the MapLibre map page
app.get('/map', (_req, res) => {
  res.sendFile(path.join(__dirname, 'map.html'));
});

// Current alert clusters as GeoJSON-based payload
app.get('/api/map-data', (_req, res) => {
  res.setHeader('Cache-Control', 'no-cache, no-store');
  res.setHeader('Content-Type', 'application/json');

  try {
    const payload = mapState.computeMapPayload();
    res.json(payload);
  } catch (err) {
    console.error('[map-server] computeMapPayload error:', err);
    res.status(500).json({ error: 'map data unavailable' });
  }
});

// Polygon cache status (internal health)
app.get('/api/polygons-ready', (_req, res) => {
  res.json(polygonCache.status());
});

// Cloud Run liveness probe
app.get('/health', (_req, res) => {
  res.json({ ok: true, polygons: polygonCache.isReady() });
});

// ── Alert ingestion endpoint ─────────────────────────────────────────────
// Called by the homefront-backend relay via an internal HTTP POST
// whenever a new HFC alert fires. Protected by shared secret.
app.use(express.json());

const MAP_SHARED_SECRET = process.env.MAP_SHARED_SECRET;

app.post('/internal/alert', (req, res) => {
  if (MAP_SHARED_SECRET && req.headers['x-map-secret'] !== MAP_SHARED_SECRET) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  const { zones, categoryDesc, action } = req.body ?? {};
  if (action === 'clear_all') {
    mapState.clearAll();
    return res.json({ ok: true, action: 'clear_all' });
  }
  if (action === 'clear' && Array.isArray(zones)) {
    mapState.clearZones(zones);
    return res.json({ ok: true, action: 'clear', count: zones.length });
  }
  if (Array.isArray(zones) && categoryDesc) {
    mapState.updateAlerts(zones, categoryDesc);
    return res.json({ ok: true, zones: zones.length });
  }
  return res.status(400).json({ error: 'invalid payload' });
});

// ── Start ────────────────────────────────────────────────────────────────
polygonCache.init().then(() => {
  console.log(`[map-server] Polygon cache ready: ${polygonCache.isReady()}`);
});

app.listen(PORT, () => {
  console.log(`[map-server] Listening on :${PORT}`);
});

module.exports = app; // for testing
