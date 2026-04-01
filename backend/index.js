const admin = require('firebase-admin');
const express = require('express');
const cors = require('cors');
const axios = require('axios');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { GoogleAuth } = require('google-auth-library');
const path = require('path');

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
const { exec } = require('child_process');
const extractScriptPath = path.join(__dirname, '..', 'scripts', 'extract_strings.js');
exec(`node "${extractScriptPath}"`, (error, stdout, stderr) => {
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
    const pass = req.headers['x-dashboard-pass'] || req.query.pass;
    const correctPass = process.env.DASHBOARD_PASS;

    if (!correctPass) {
        console.error("CRITICAL: DASHBOARD_PASS environment variable is not set.");
        return res.status(500).json({ error: "Server Configuration Error" });
    }

    if (pass !== correctPass) {
        console.warn(`Unauthorized access attempt. Received pass length: ${pass?.length}, expected length: ${correctPass.length}`);
        return res.status(401).json({ error: "Unauthorized" });
    }
    next();
};

// Auth middleware for sensitive endpoints
app.use((req, res, next) => {
    const apiKey = req.headers['x-api-key'];
    const validKey = process.env.API_KEY;

    // Health, Privacy, Tester, and Dashboard paths are public or have their own auth
    const publicPaths = ['/health', '/privacy', '/apply-tester', '/apply-tester/status', '/dashboard', '/dashboard/data', '/dashboard/delete', '/dashboard/sync-all', '/dashboard/play-status'];
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

// --- Google Play Automation ---
async function addTesterToPlayStore(email) {
    const packageName = "com.attius.homefrontalert";
    const trackName = "alpha";
    const auth = new google.auth.GoogleAuth({
        scopes: ['https://www.googleapis.com/auth/androidpublisher']
    });

    try {
        const authClient = await auth.getClient();
        const edit = await play.edits.insert({ auth: authClient, packageName });
        const editId = edit.data.id;

        const currentTesters = await play.edits.testers.get({ auth: authClient, editId, packageName, track: trackName });
        const emails = currentTesters.data.emails || [];

        if (!emails.includes(email.toLowerCase())) {
            emails.push(email.toLowerCase());
            await play.edits.testers.update({
                auth: authClient, editId, packageName, track: trackName,
                requestBody: { emails }
            });
            await play.edits.commit({ auth: authClient, editId, packageName });
            console.log(`✅ Play Store Sync: ${email} added.`);
            return true;
        }
        return true; // Already there
    } catch (e) {
        console.error("❌ Play Store Sync Error:", e.response?.data?.error || e.message);
        return false;
    }
}

// --- App State ---
let lastDetectedAlert = null;
let currentAlert = null;
let isConnected = false;
let activeSource = "None";
let lastSuccessfulPoll = null;
let lastErrors = [];
let alertExpirationTimer = null;

// --- API Endpoints ---
app.get('/health', (req, res) => {
    res.status(200).json({
        status: 'ok',
        connectedToHomeFront: isConnected,
        source: activeSource,
        lastReportedAlert: lastDetectedAlert,
        timestamp: new Date().toISOString()
    });
});

app.get('/privacy', (req, res) => {
    res.sendFile(__dirname + '/public/privacy.html');
});

app.get('/alerts', (req, res) => {
    res.json({ active: currentAlert || {}, system: { connected: isConnected, source: activeSource, last_sync: lastSuccessfulPoll } });
});

app.get('/alerts/history', (req, res) => {
    res.json(lastDetectedAlert ? [lastDetectedAlert] : []);
});

app.post('/test-fcm', (req, res) => {
    const isDryRun = req.body.dryRun === true;
    const testAlert = {
        id: 'TEST_' + Date.now(),
        type: req.body.type || 'Test Alert / בדיקה',
        cities: req.body.cities || ['בדיקה — FCM Test']
    };

    if (isDryRun) {
        // 🛡️ SAFETY: Propagate to Map for visual verification — skip FCM entirely
        console.log('🛡️ DRY-RUN /test-fcm triggered — FCM skipped, Map notified only.');
        notifyMapServiceAlert(testAlert);
        return res.json({ ok: true, dryRun: true, alert: testAlert, note: 'FCM skipped. Map service notified.' });
    }

    console.log('🧪 Manual /test-fcm triggered (LIVE — will reach users)');
    sendFCMAlert(testAlert);
    notifyMapServiceAlert(testAlert);
    res.json({ ok: true, dryRun: false, alert: testAlert });
});

// --- Closed Testing Endpoints ---
const testerLimit = 100;
const testerLimiter = rateLimit({
    windowMs: 24 * 60 * 60 * 1000,
    max: 5,
    message: "Rate limit exceeded for tester applications. Please try again tomorrow."
});

app.get('/apply-tester/status', async (req, res) => {
    try {
        const snapshot = await admin.firestore().collection('testers').count().get();
        const count = snapshot.data().count;
        res.json({ count, full: count >= testerLimit });
    } catch (e) {
        res.status(500).json({ error: "Failed to fetch status" });
    }
});

app.post('/apply-tester', testerLimiter, async (req, res) => {
    const { name, email } = req.body;
    if (!name || !email || !email.includes('@')) {
        return res.status(400).json({ error: "Name and valid Email address are required." });
    }

    try {
        const db = admin.firestore();
        const snapshot = await db.collection('testers').count().get();
        if (snapshot.data().count >= testerLimit) {
            return res.status(403).json({ error: "Testing program full.", full: true });
        }

        const existing = await db.collection('testers').where('email', '==', email.toLowerCase()).get();
        if (!existing.empty) {
            return res.status(400).json({ error: "This email is already registered." });
        }

        const synced = await addTesterToPlayStore(email.toLowerCase());
        await db.collection('testers').add({
            name,
            email: email.toLowerCase(),
            appliedAt: admin.firestore.FieldValue.serverTimestamp(),
            synced
        });

        res.json({ ok: true, synced, message: synced ? "Tester added and synced to Play Store." : "Tester added to queue (Play Store sync pending)." });
    } catch (e) {
        console.error("Apply error:", e);
        res.status(500).json({ error: "Database error." });
    }
});

// --- Admin Dashboard Endpoints ---
app.get('/dashboard', (req, res) => {
    res.sendFile(__dirname + '/public/dashboard.html');
});

app.get('/dashboard/data', dashboardAuth, async (req, res) => {
    try {
        const snapshot = await admin.firestore().collection('testers').orderBy('appliedAt', 'desc').get();
        res.json(snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() })));
    } catch (e) {
        console.error("Dashboard data fetch error:", e);
        res.status(500).json({ error: "Failed to fetch dashboard data: " + e.message });
    }
});

app.delete('/dashboard/delete/:id', dashboardAuth, async (req, res) => {
    try {
        await admin.firestore().collection('testers').doc(req.params.id).delete();
        res.json({ ok: true });
    } catch (e) { res.status(500).json({ error: "Delete failed" }); }
});

app.post('/dashboard/sync-all', dashboardAuth, async (req, res) => {
    try {
        const pending = await admin.firestore().collection('testers').where('synced', '==', false).get();
        let successCount = 0;
        for (const doc of pending.docs) {
            if (await addTesterToPlayStore(doc.data().email)) {
                await doc.ref.update({ synced: true });
                successCount++;
            }
        }
        res.json({ ok: true, syncedCount: successCount });
    } catch (e) { res.status(500).json({ error: "Batch sync failed" }); }
});

app.get('/dashboard/play-status', dashboardAuth, async (req, res) => {
    try {
        const auth = new google.auth.GoogleAuth({ scopes: ['https://www.googleapis.com/auth/androidpublisher'] });
        const authClient = await auth.getClient();
        const edit = await play.edits.insert({ auth: authClient, packageName: "com.attius.homefrontalert" });
        const track = await play.edits.tracks.get({ auth: authClient, editId: edit.data.id, packageName: "com.attius.homefrontalert", track: 'alpha' });
        res.json({ status: "Live", releases: track.data.releases });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

// --- Poller Logic ---
const interval = 1000; // 1s polling frequency
const HFC_API_URL = 'https://www.oref.org.il/WarningMessages/alert/Alerts.json';
const HFC_HEADERS = {
    'User-Agent': 'PikudHaoref/1.6 (iPhone; iOS 17.4; Scale/3.00)',
    'Referer': 'https://www.oref.org.il/',
    'X-Requested-With': 'XMLHttpRequest',
    'Accept': 'application/json, text/javascript, */*; q=0.01',
    'Cache-Control': 'no-cache',
    'Pragma': 'no-cache'
};

console.log("Starting Home Front Command Native Poller (Axios)...");

const poll = async function () {
    try {
        let data = null;
        let source = "None";
        let errors = [];

        // 1. Official API
        try {
            const hfcRes = await axios.get(HFC_API_URL, {
                headers: HFC_HEADERS,
                timeout: 3000,
                validateStatus: s => s === 200 || s === 204
            });
            // HFC returns 204 No Content when there are no active alerts
            if (hfcRes.status === 204 || !hfcRes.data) {
                data = []; // Connected but no active alerts
            } else {
                data = hfcRes.data;
            }
            source = "Official API";
        } catch (e) {
            errors.push(`Official: ${e.message}`);
        }

        // 2. Fallback to Mirror A (Pakar API)
        if (data === null) {
            try {
                const res = await axios.get('https://api.pakar.ivr2.tel/alerts', {
                    timeout: 5000,
                    headers: HFC_HEADERS,
                    validateStatus: s => s === 200
                });
                if (res.data && typeof res.data === 'object') {
                    data = res.data;
                    source = "Mirror A (Pakar)";
                } else {
                    errors.push(`Mirror A: Invalid format`);
                }
            } catch (e) {
                errors.push(`Mirror A: ${e.message}`);
            }
        }

        // 3. Fallback to Mirror B (Tzeva Adom Historical Feed)
        if (data === null) {
            try {
                const res = await axios.get('https://www.tzevaadom.co.il/static/historical/all.json', {
                    timeout: 5000,
                    headers: HFC_HEADERS,
                    validateStatus: s => s === 200
                });
                if (Array.isArray(res.data) && res.data.length > 0) {
                    const latest = res.data[0];
                    const alertTime = new Date(latest.alertDate).getTime();
                    // Show as active only if it fired within the last 60 seconds
                    if (Date.now() - alertTime < 60000) {
                        data = latest;
                        source = "Mirror B (History Feed)";
                    } else {
                        data = []; // Connected but no recent alerts
                        source = "Mirror B (Connected)";
                    }
                } else {
                    errors.push(`Mirror B: Empty or malformed`);
                }
            } catch (e) {
                errors.push(`Mirror B: ${e.message}`);
            }
        }

        // Process results
        if (data !== null) {
            activeSource = source;
            lastSuccessfulPoll = new Date().toISOString();
            isConnected = true;
            handleSuccessfulConnection(data, source);
        } else {
            if (isConnected) {
                console.warn("⚠️ DISCONNECTED from all sources. Errors:", errors.join(" | "));
            }
            isConnected = false;
            activeSource = "None";
        }
        lastErrors = errors;

    } catch (e) {
        console.error("CRITICAL Poller Error:", e.message);
        isConnected = false;
        activeSource = "None";
    } finally {
        // Always restart poll, even after an unhandled error
        setTimeout(poll, interval);
    }
};

function handleSuccessfulConnection(data, source) {
    if (!isConnected) {
        console.log(`✅ Connection stable via ${source}.`);
    }
    isConnected = true;

    // Check for empty data (no active alerts)
    const isEmpty = !data ||
        (Array.isArray(data) && data.length === 0) ||
        (typeof data === 'object' && Object.keys(data).length === 0);

    if (isEmpty) {
        if (currentAlert) {
            console.log("Alert state cleared: API is now empty (All-Clear).");
            currentAlert = null;
            if (alertExpirationTimer) {
                clearTimeout(alertExpirationTimer);
                alertExpirationTimer = null;
            }
            notifyMapServiceClearAll();
            sendFCMClear(); // 🚀 Notify Android app
        }
        if (Math.random() < 0.02) { 
            console.log(`[${new Date().toLocaleTimeString()}] Heartbeat: Watching via ${source}`);
        }
        return;
    }

    // We have a REAL alert payload — canonicalize it
    const normalizedAlert = {
        id: data.id || Date.now().toString(),
        type: data.title || data.desc || data.type || "Rocket Alert",
        cities: Array.isArray(data.cities) ? data.cities : (Array.isArray(data.data) ? data.data : []),
        raw: data
    };

    if (normalizedAlert.cities.length > 0) {
        // Backend Deduplication: Only dispatch FCM if City List or ID changed.
        const currentCitiesKey = [...normalizedAlert.cities].sort().join('|');
        const lastCitiesKey = lastDetectedAlert ? [...lastDetectedAlert.cities].sort().join('|') : "";

        if (normalizedAlert.id !== (lastDetectedAlert ? lastDetectedAlert.id : "") || currentCitiesKey !== lastCitiesKey) {
            console.log(`🚨 DISPATCHING [${source}]: ${normalizedAlert.cities.length} cities`);
            handleAlertDispatch(normalizedAlert);
        } else {
            if (Math.random() < 0.05) console.log(`... Alert ${normalizedAlert.id} still active`);
        }

        // --- SSOT FAILOVER TIMER ---
        // If an All-Clear signal is missed, automatically clear the state after 30 minutes.
        // This matches the Android dashboard's failover window.
        currentAlert = normalizedAlert;
        if (alertExpirationTimer) clearTimeout(alertExpirationTimer);
        alertExpirationTimer = setTimeout(() => {
            console.log("🚨 FAILOVER: Alert state cleared after 30-minute timeout (All-Clear missed).");
            currentAlert = null;
            alertExpirationTimer = null;
            notifyMapServiceClearAll();
            sendFCMClear();
        }, 1800000); // 30 minutes
    }
}

function handleAlertDispatch(alert) {
    currentAlert = alert;
    lastDetectedAlert = {
        ...alert,
        serverTime: new Date().toISOString()
    };
    console.log('Pushing Notification for:', alert.cities.join(', '));
    sendFCMAlert(alert);
    notifyMapServiceAlert(alert);
}

function sendFCMAlert(alertData) {
    const CHUNK_SIZE = 100; // Safe threshold below FCM's 4KB data payload limit
    const citiesList = alertData.cities || [];

    // Guard: never send FCM with an empty cities list
    if (citiesList.length === 0) return;

    // Send in chunks to avoid "Android message is too big" errors on large alerts
    for (let i = 0; i < citiesList.length; i += CHUNK_SIZE) {
        const chunk = citiesList.slice(i, i + CHUNK_SIZE);
        const chunkIndex = Math.floor(i / CHUNK_SIZE) + 1;
        const totalChunks = Math.ceil(citiesList.length / CHUNK_SIZE);

        const message = {
            data: {
                alertId: String(alertData.id),
                type: String(alertData.type),
                cities: JSON.stringify(chunk),
                chunkInfo: String(`${chunkIndex}/${totalChunks}`),
                is_dedup: 'true' // Signals v1.7.7+ clients to use smart TTL deduplication
            },
            android: {
                priority: 'high',
                ttl: 60 * 1000 // 60 seconds — life-safety alerts must be fresh or discarded
            },
            topic: 'alerts'
        };

        admin.messaging().send(message)
            .then(res => console.log(`🚀 FCM Broadcast Success (Chunk ${chunkIndex}/${totalChunks}):`, res))
            .catch(err => console.error(`❌ FCM Broadcast Error (Chunk ${chunkIndex}/${totalChunks}):`, err.message));
    }
}

// --- FCM Clear Dispatch ---
// Sends a 'CLEAR' signal to the Android app to immediately reset its dashboard state
function sendFCMClear() {
    const message = {
        data: {
            type: 'CLEAR',
            time: new Date().toISOString()
        },
        android: {
            priority: 'high',
            ttl: 60 * 1000
        },
        topic: 'alerts'
    };

    admin.messaging().send(message)
        .then(res => console.log('🚀 FCM: All-Clear broadcast sent successfully:', res))
        .catch(err => console.error('❌ FCM: All-Clear broadcast error:', err.message));
}

// --- Map Service Notification ---
// Google Recommended "Secretless" IAM-based Auth for Service-to-Service communication
async function notifyMapServiceAlert(alertData) {
    if (!MAP_SERVICE_URL) return;
    try {
        const payload = {
            zones: alertData.cities || [],
            categoryDesc: alertData.type || '',
        };
        
        const client = await auth.getIdTokenClient(MAP_SERVICE_URL);
        await client.request({
            url: `${MAP_SERVICE_URL}/internal/alert`,
            method: 'POST',
            data: payload
        });
        console.log(`📡 Map: Notified of salvo (${payload.zones.length} zones)`);
    } catch (e) {
        console.error("Map service notify error:", e.message);
    }
}

async function notifyMapServiceClearAll() {
    if (!MAP_SERVICE_URL) return;
    try {
        const client = await auth.getIdTokenClient(MAP_SERVICE_URL);
        await client.request({
            url: `${MAP_SERVICE_URL}/internal/alert`,
            method: 'POST',
            data: { action: 'clear_all' }
        });
        console.log("📡 Map: Cleared all alerts.");
    } catch (e) {
        console.error("Map service clear error:", e.message);
    }
}

// Global crash handler — surface unhandled promise rejections to GC logs
process.on('unhandledRejection', (reason, promise) => {
    console.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

// FCM Keepalive Heartbeat for Auto-Failover
// Emits a silent KEEPALIVE message to the 'alerts' topic every 10 minutes.
// The Android app uses this to detect backend connectivity and reset connection state.
setInterval(() => {
    if (isConnected) {
        console.log(`💓 Emitting KEEPALIVE heartbeat to 'alerts' topic...`);
        const keepaliveMessage = {
            data: {
                type: 'KEEPALIVE',
                time: new Date().toISOString()
            },
            android: {
                priority: 'normal',
                ttl: 60 * 1000 // 60 seconds
            },
            topic: 'alerts'
        };

        admin.messaging().send(keepaliveMessage)
            .then(res => console.log('💓 KEEPALIVE Success:', res))
            .catch(err => console.error('❌ KEEPALIVE Error:', err.message));
    } else {
        console.log(`💔 Skipping KEEPALIVE (Backend disconnected from HFC)`);
    }
}, 10 * 60 * 1000); // 10 minutes

const PORT = process.env.PORT || 8080;
app.listen(PORT, () => {
    console.log(`Backend Server listening on port ${PORT}`);
    poll();
});
