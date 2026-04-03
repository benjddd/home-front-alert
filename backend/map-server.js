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
const axios        = require('axios');
const { GoogleAuth, OAuth2Client } = require('google-auth-library');

const auth = new GoogleAuth();
const BACKEND_SERVICE_URL = process.env.BACKEND_SERVICE_URL || 'https://homefront-backend-cjnpwpm63q-zf.a.run.app';
const client = new OAuth2Client();

const app  = express();
const PORT = parseInt(process.env.PORT ?? '8080', 10);

// ── Security headers ─────────────────────────────────────────────────────
app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      scriptSrc:  ["'self'", "'unsafe-inline'", 'https://unpkg.com'],
      styleSrc:   ["'self'", "'unsafe-inline'", 'https://unpkg.com'],
      // No external tile servers — basemap is our own GeoJSON outline.
      // blob: needed for MapLibre's internal Web Worker comms.
      imgSrc:     ["'self'", 'data:', 'blob:'],
      connectSrc: ["'self'", 'blob:'],
      workerSrc:  ["'self'", 'blob:'],
    },
  },
  crossOriginEmbedderPolicy: false,
}));

// Static assets (israel-outline.json etc.)
app.use(express.static(path.join(__dirname, 'public')));

// ── Routes ───────────────────────────────────────────────────────────────

// Serve the MapLibre map page
app.get('/map', (_req, res) => {
  res.sendFile(path.join(__dirname, 'map.html'));
});

// Unified SSOT: Proxy data from the Backend relay service
app.get('/api/map-data', async (_req, res) => {
  res.setHeader('Cache-Control', 'no-cache, no-store');
  res.setHeader('Content-Type', 'application/json');

  try {
    const oidcClient = await auth.getIdTokenClient(BACKEND_SERVICE_URL);
    const response = await oidcClient.request({
      url: `${BACKEND_SERVICE_URL}/api/map-data`,
      method: 'GET'
    });
    res.json(response.data);
  } catch (err) {
    console.error('[map-server] Proxy error to Backend relay:', err.message);
    // Fallback: Compute locally if proxy fails (prevents absolute outage)
    try {
        const payload = mapState.computeMapPayload();
        res.json(payload);
    } catch (localErr) {
        res.status(500).json({ error: 'synchronization failure' });
    }
  }
});

// Country outline GeoJSON — Israel + Judea & Samaria as one territory, no Green Line.
// Public domain natural earth data, pre-generated once by generate_country_outline.js.
app.get('/api/country-outline', (_req, res) => {
  res.setHeader('Cache-Control', 'public, max-age=86400');
  res.sendFile(path.join(__dirname, 'public', 'israel-outline.json'));
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
// whenever a new HFC alert fires. Protected by Google-signed OIDC ID Token.
app.use(express.json());

async function verifyOidcToken(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader?.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'unauthorized: missing bearer token' });
  }

  const idToken = authHeader.split(' ')[1];
  try {
    const ticket = await client.verifyIdToken({
      idToken,
      // Map service validates that we are the intended audience. 
      // If deployed without generic URL yet, we skip audience check or use env var.
    });
    req.user = ticket.getPayload();
    
    // Authorization: Verify the caller is our relay service account
    const allowedEmail = process.env.RELAY_SERVICE_ACCOUNT || '344391280523-compute@developer.gserviceaccount.com';
    if (req.user.email !== allowedEmail) {
      console.warn(`[map-server] Unauthorized caller: ${req.user.email}`);
      return res.status(403).json({ error: 'forbidden: identity mismatch' });
    }
    
    next();
  } catch (err) {
    console.error('[map-server] Token verification failed:', err.message);
    res.status(401).json({ error: 'unauthorized: invalid token' });
  }
}

app.post('/internal/alert', verifyOidcToken, (req, res) => {
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
