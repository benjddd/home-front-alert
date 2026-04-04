const admin = require('firebase-admin');
const express = require('express');
const cors = require('cors');
const axios = require('axios');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { GoogleAuth } = require('google-auth-library');
const { execFile } = require('child_process');
const path = require('path');
const config = require('./config');

const auth = new GoogleAuth();
const app = express();
app.set('trust proxy', 1); // Trust Cloud Run Load Balancer
const MAP_SERVICE_URL = process.env.MAP_SERVICE_URL; 
// MAP_SHARED_SECRET is deprecated and no longer required for prod in favour of OIDC

// Security Hardening — custom CSP needed for dashboard iframes / inline scripts
app.use(helmet({
    contentSecurityPolicy: {
        directives: {
            ...helmet.contentSecurityPolicy.getDefaultDirectives(),
            "script-src": ["'self'", "'unsafe-inline'", "*.opendns.com", "gateway.id.swg.umbrella.com", "*.sse.cisco.com", "*.ciscosecureaccess.cn"],
            "script-src-attr": ["'self'", "'unsafe-inline'"],
        },
    },
}));
app.use(express.json());

// Handle favicon to prevent 404 logs
app.get('/favicon.ico', (req, res) => res.status(204).end());

// Serve static files from 'public' directory
app.use(express.static(path.join(__dirname, 'public')));

// Run string extraction script on startup to ensure SSOT
const extractScriptPath = path.join(__dirname, '..', 'scripts', 'extract_strings.js');
execFile('node', [extractScriptPath], (error, stdout, stderr) => {
    if (error) {
        console.error(`❌ String extraction failed: ${error.message}`);
        return;
    }
    if (stderr) console.warn(`⚠️ String extraction warning: ${stderr}`);
    console.log(`✅ String extraction successful: ${stdout.trim()}`);
});

// Strict Rate Limiting: 100 requests per 15 minutes
const limiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 100,
    standardHeaders: true,
    legacyHeaders: false,
    message: "Too many requests from this IP, please try again later."
});
app.use(limiter);

// Dashboard Password Check Utility
const dashboardAuth = (req, res, next) => {
    const pass = req.headers['x-dashboard-pass'];
    const correctPass = process.env.DASHBOARD_PASS;

    if (!correctPass) {
        console.error("CRITICAL: DASHBOARD_PASS environment variable is not set.");
        return res.status(500).json({ error: "Server Configuration Error" });
    }

    if (pass !== correctPass) {
        console.warn(`Unauthorized access attempt. Received pass length: ${pass?.length ?? 0}`);
        return res.status(401).json({ error: "Unauthorized" });
    }
    next();
};

// Auth middleware for sensitive endpoints
app.use((req, res, next) => {
    const apiKey = req.headers['x-api-key'];
    const validKey = process.env.API_KEY;

    // Health and Privacy are fully public
    const publicPaths = ['/health', '/privacy'];
    if (publicPaths.some(p => req.path.startsWith(p))) return next();

    if (!validKey) {
        console.error("CRITICAL: API_KEY environment variable is not set.");
        return res.status(500).json({ error: "Server Configuration Error" });
    }

    const isSensitive = req.path === '/alerts' || req.path === '/test-fcm';
    if (isSensitive && apiKey !== validKey) {
        return res.status(403).json({ error: "Forbidden: Valid API Key required" });
    }
    next();
});

app.use(cors({
    origin: '*',
    methods: ['GET', 'POST', 'DELETE'],
    allowedHeaders: ['Content-Type', 'Authorization', 'User-Agent', 'X-API-Key', 'X-Dashboard-Pass']
}));

// --- Firebase Init ---
try {
    if (!admin.apps.length) {
        if (process.env.FIREBASE_SERVICE_ACCOUNT) {
            const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
            admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
            console.log("🔥 Firebase: Initialized via Environment Variable.");
        } else if (process.env.K_SERVICE) {
            admin.initializeApp({ credential: admin.credential.applicationDefault() });
            console.log("🔥 Firebase: Initialized via Application Default Credentials (GCP).");
        } else {
            const keyPath = path.join(__dirname, 'serviceAccountKey.json');
            admin.initializeApp({ credential: admin.credential.cert(require(keyPath)) });
            console.log("🔥 Firebase: Initialized via local file at:", keyPath);
        }
    } else {
        console.log("ℹ️ Firebase: App already initialized.");
    }
} catch (e) {
    console.error("❌ Firebase: Initialization failed critical error.", e);
}

// --- Alert Type Normalization (HFC Hebrew → Canonical Keys) ---
// ALERT_TYPE_MAP is the SSOT — defined in config.js, shared with mapState.js.
function normalizeAlertType(rawType) {
    if (!rawType) return 'OTHER';
    const trimmed = rawType.trim();
    const map = config.ALERT_TYPE_MAP;
    // Direct match
    if (map[trimmed]) return map[trimmed];
    // Case-insensitive match
    const lower = trimmed.toLowerCase();
    if (map[lower]) return map[lower];
    // Substring match for partial Hebrew descriptions
    for (const [pattern, canonical] of Object.entries(map)) {
        if (trimmed.includes(pattern)) return canonical;
    }
    console.warn(`[normalizeAlertType] Unknown type "${rawType}" → OTHER`);
    return 'OTHER';
}

// --- App State ---
const mapState = require('./mapState');
const threatManager = require('./threatManager');
const polygonCache = require('./polygonCache');
let isConnected = false;
let activeSource = "None";
let lastSuccessfulPoll = null;
let lastErrors = [];

// --- State Monitor (1s Ticker) ---
let systemStatus = 'CALM';
let prevStatus   = 'CALM';

setInterval(() => {
    // 1. Stabilization & Transitions handled by ThreatManager (CALM transition has 8s buffer)
    threatManager.tick(); 
    
    // 2. Derive SSOT status for Dashboard/Map
    const currentStatus = mapState.getSystemStatus(null);
    
    // 3. Log state transitions only. Explicit clears are dispatched from the
    // HFC clear path so zone-scoped CLEARING state can live for the full fade.
    if (currentStatus === 'CALM' && prevStatus !== 'CALM') {
        console.log("✅ State transition to CALM detected.");
    } else if (currentStatus !== 'CALM' && prevStatus === 'CALM') {
        console.log(`📡 State transition to ${currentStatus} detected.`);
    }

    prevStatus = currentStatus;
    systemStatus = currentStatus;
}, 1000);

// --- API Endpoints ---
app.get('/health', (req, res) => {
    res.status(200).json({
        status: 'ok',
        connectedToHomeFront: isConnected,
        source: activeSource,
        timestamp: new Date().toISOString()
    });
});

app.get('/privacy', (req, res) => {
    res.sendFile(__dirname + '/public/privacy.html');
});

// Current alert status and map data SSOT
app.get('/api/map-data', (req, res) => {
    res.setHeader('Cache-Control', 'no-cache, no-store');
    try {
        const payload = mapState.computeMapPayload();
        res.json(payload);
    } catch (err) {
        console.error('[Relay] computeMapPayload error:', err);
        res.status(500).json({ error: 'map synchronization failed' });
    }
});

app.get('/alerts', (req, res) => {
    // Consolidated SSOT state for Dashboard
    res.json({ 
        active: {
            status: mapState.getSystemStatus(null), // Direct from SSOT
            recent_alerts_10m: mapState.getRecentAlertCount(),
        }, 
        system: { 
            connected: isConnected, 
            source: activeSource, 
            last_sync: lastSuccessfulPoll 
        } 
    });
});

app.post('/test-fcm', (req, res) => {
    const isDryRun = req.body.dryRun === true;
    const testAlert = {
        id: 'TEST_' + Date.now(),
        type: req.body.type || 'Test Alert / בדיקה',
        cities: req.body.cities || ['בדיקה — FCM Test']
    };

    if (isDryRun) {
        console.log('🛡️ DRY-RUN /test-fcm — Map notified only.');
        mapState.updateAlerts(testAlert.cities, testAlert.type);
        notifyMapServiceAlert(testAlert);
        return res.json({ ok: true, dryRun: true, alert: testAlert });
    }

    console.log('🧪 Manual /test-fcm triggered (LIVE)');
    handleAlertDispatch(testAlert);
    res.json({ ok: true, dryRun: false, alert: testAlert });
});

// Dashboard status: password-protected so the web dashboard can poll live alert state
app.get('/dashboard/status', dashboardAuth, (req, res) => {
    res.json({
        status: mapState.getSystemStatus(null),
        recent_alerts_10m: mapState.getRecentAlertCount(),
        connected: isConnected,
        last_sync: lastSuccessfulPoll,
    });
});

// --- Poller Logic ---
const interval = config.POLL_INTERVAL_MS;
const HFC_API_URL = 'https://www.oref.org.il/WarningMessages/alert/Alerts.json';
const HFC_HEADERS = {
    'User-Agent': 'PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)',
    'Referer': 'https://www.oref.org.il/',
    'X-Requested-With': 'XMLHttpRequest',
    'Accept': 'application/json, text/javascript, */*; q=0.01',
    'Cache-Control': 'no-cache',
    'Pragma': 'no-cache'
};

const poll = async function () {
    try {
        let data = null;
        let source = "None";
        let errors = [];

        try {
            const hfcRes = await axios.get(HFC_API_URL, {
                headers: HFC_HEADERS,
                timeout: config.HFC_REQUEST_TIMEOUT_MS,
                validateStatus: s => s === 200 || s === 204
            });
            if (hfcRes.status === 204 || !hfcRes.data) { data = []; } else { data = hfcRes.data; }
            source = "Official API";
        } catch (e) { errors.push(`Official: ${e.message}`); }

        if (data === null) { /* ... Fallback A ... */ }
        if (data === null) { /* ... Fallback B ... */ }

        if (data !== null) {
            activeSource = source;
            lastSuccessfulPoll = new Date().toISOString();
            isConnected = true;
            handleSuccessfulConnection(data);
        } else {
            isConnected = false;
            activeSource = "None";
        }
        lastErrors = errors;
    } catch (e) {
        isConnected = false;
        activeSource = "None";
    } finally {
        setTimeout(poll, interval);
    }
};

function handleSuccessfulConnection(data) {
    const rawAlerts = Array.isArray(data) ? data : (data && typeof data === 'object' && Object.keys(data).length > 0 ? [data] : []);
    if (rawAlerts.length === 0) return;

    // 1. Group by Alert Type to enable batching
    const batchedByType = new Map(); // type -> { id, cities: Set }

    for (const alertPayload of rawAlerts) {
        const rawType = alertPayload.title || alertPayload.desc || alertPayload.type || "Rocket Alert";
        const type = normalizeAlertType(rawType);
        const legacyTitle = String(rawType || '').trim();
        const legacyCat = String(alertPayload.cat || '').trim();
        const id = alertPayload.id || Date.now().toString();
        const cities = Array.isArray(alertPayload.cities) ? alertPayload.cities : (Array.isArray(alertPayload.data) ? alertPayload.data : []);

        if (cities.length === 0) continue;

        if (!batchedByType.has(type)) {
            batchedByType.set(type, { id, cities: new Set(cities), legacyTitle, legacyCat });
        } else {
            const entry = batchedByType.get(type);
            cities.forEach(c => entry.cities.add(c));
            if (!entry.legacyTitle && legacyTitle) entry.legacyTitle = legacyTitle;
            if (!entry.legacyCat && legacyCat) entry.legacyCat = legacyCat;
        }
    }

    // 2. Dispatch one FCM per unique Alert Type (Batching)
    for (const [type, entry] of batchedByType) {
        const consolidatedCities = [...entry.cities];
        
        // Handle explicit clear if the 'type' indicates it (e.g., 'CLEAR' or 'CALM' or specific HFC category)
        if (type.toUpperCase().includes('CLEAR') || type.toUpperCase().includes('CALM')) {
            threatManager.handleExplicitClear(consolidatedCities, type);
            console.log(`🟢 BATCHED CLEAR: ${consolidatedCities.length} zones for ${type}.`);
            sendFCMClear({
                id: entry.id,
                type,
                cities: consolidatedCities,
                legacyTitle: entry.legacyTitle || '',
                legacyCat: entry.legacyCat || ''
            });
            notifyMapServiceClear({
                cities: consolidatedCities,
                type,
                legacyTitle: entry.legacyTitle || '',
                legacyCat: entry.legacyCat || ''
            });
            continue;
        }

        const newCount = threatManager.updateFromSnapshot(consolidatedCities, type);
        
        if (newCount > 0) {
            console.log(`🚨 BATCHED DISPATCH: ${newCount} new/escalated zones for ${type}.`);
            handleAlertDispatch({
                id: entry.id,
                type: type,
                cities: consolidatedCities,
                legacyTitle: entry.legacyTitle || '',
                legacyCat: entry.legacyCat || ''
            });
        }
    }
}

function handleAlertDispatch(alert) {
    sendFCMAlert(alert);
    notifyMapServiceAlert(alert);
}

function sendFCMAlert(alertData) {
    const CHUNK_SIZE = 100;
    const citiesList = alertData.cities || [];
    if (citiesList.length === 0) return;

    for (let i = 0; i < citiesList.length; i += CHUNK_SIZE) {
        const chunk = citiesList.slice(i, i + CHUNK_SIZE);
        const chunkIndex = Math.floor(i / CHUNK_SIZE) + 1;
        const totalChunks = Math.ceil(citiesList.length / CHUNK_SIZE);

        const message = {
            data: {
                alertId: String(alertData.id),
                // Backward-compat: old clients expect phrase-like type here.
                // New clients should prefer canonicalType.
                type: String(alertData.legacyTitle || alertData.type),
                canonicalType: String(alertData.type || ''),
                legacyTitle: String(alertData.legacyTitle || ''),
                legacyCat: String(alertData.legacyCat || ''),
                schemaVersion: '2',
                classificationPath: 'backend_canonical',
                cities: JSON.stringify(chunk),
                chunkInfo: String(`${chunkIndex}/${totalChunks}`),
                is_dedup: 'true'
            },
            android: { priority: 'high', ttl: 60 * 1000 },
            topic: 'alerts'
        };

        admin.messaging().send(message)
            .then(res => console.log(`🚀 FCM Success (${chunkIndex}/${totalChunks}):`, res))
            .catch(err => console.error(`❌ FCM Error:`, err.message));
    }
}

function sendFCMClear(alertData = {}) {
    const CHUNK_SIZE = 100;
    const citiesList = Array.isArray(alertData.cities) ? alertData.cities : [];
    const chunks = citiesList.length > 0
        ? Array.from({ length: Math.ceil(citiesList.length / CHUNK_SIZE) }, (_, index) => citiesList.slice(index * CHUNK_SIZE, (index + 1) * CHUNK_SIZE))
        : [[]];

    chunks.forEach((chunk, index) => {
        const hasZones = chunk.length > 0;
        const message = {
            data: {
                alertId: String(alertData.id || ''),
                type: 'CLEAR',
                canonicalType: 'CALM',
                legacyTitle: String(alertData.legacyTitle || ''),
                legacyCat: String(alertData.legacyCat || ''),
                schemaVersion: '2',
                classificationPath: 'backend_canonical',
                clearScope: hasZones ? 'zones' : 'global',
                time: new Date().toISOString(),
                is_dedup: 'true'
            },
            android: { priority: 'high', ttl: 60 * 1000 },
            topic: 'alerts'
        };

        if (hasZones) {
            message.data.cities = JSON.stringify(chunk);
            message.data.chunkInfo = String(`${index + 1}/${chunks.length}`);
        }

        admin.messaging().send(message)
            .then(() => console.log(`🚀 FCM: All-Clear sent (${index + 1}/${chunks.length}).`))
            .catch(err => console.error('❌ FCM: All-Clear error:', err.message));
    });
}

async function notifyMapServiceAlert(alertData) {
    if (!MAP_SERVICE_URL) return;
    try {
        const client = await auth.getIdTokenClient(MAP_SERVICE_URL);
        await client.request({
            url: `${MAP_SERVICE_URL}/internal/alert`,
            method: 'POST',
            data: { zones: alertData.cities || [], categoryDesc: alertData.type || '' }
        });
    } catch (e) { console.error("Map notify error:", e.message); }
}

async function notifyMapServiceClear(alertData = {}) {
    if (!MAP_SERVICE_URL) return;
    try {
        const client = await auth.getIdTokenClient(MAP_SERVICE_URL);
        const cities = Array.isArray(alertData.cities) ? alertData.cities : [];
        if (cities.length === 0) return;
        await client.request({
            url: `${MAP_SERVICE_URL}/internal/alert`,
            method: 'POST',
            data: { action: 'clear', zones: cities, categoryDesc: alertData.type || '' }
        });
    } catch (e) { console.error("Map clear error:", e.message); }
}

const PORT = process.env.PORT || 8080;
polygonCache.init().then(() => {
    console.log(`[backend] Polygon cache ready: ${polygonCache.isReady()}`);
    app.listen(PORT, () => {
        console.log(`Backend Server listening on port ${PORT}`);
        poll();
    });
});

